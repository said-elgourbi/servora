import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import type { CanadianProvinceCode, CreateCustomerRequest, CustomerDetails, CustomerType, SaveCustomerRequest } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { CustomersApi } from '../data-access/customers-api';
import { PhoneFormatDirective } from '../../../shared/ui/phone-format.directive';

const provinceCodes: CanadianProvinceCode[] = ['AB','BC','MB','NB','NL','NS','NT','NU','ON','PE','QC','SK','YT'];
@Component({
  selector: 'app-customer-form',
  imports: [ReactiveFormsModule, MatButtonModule, RouterLink, PhoneFormatDirective],
  templateUrl: './customer-form.html',
  styleUrl: './customer-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerFormPage {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  protected readonly provinces = provinceCodes;
  protected provinceName(code: CanadianProvinceCode): string { return this.i18n.text(('province' + code) as TranslationKey); }
  private readonly api = inject(CustomersApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly customerId = this.route.snapshot.paramMap.get('customerId');
  protected readonly editing = computed(() => this.customerId !== null);
  protected readonly customerType = signal<CustomerType>('INDIVIDUAL');
  protected readonly status = signal<'ACTIVE' | 'INACTIVE'>('ACTIVE');
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly unfinished = signal(false);
  protected readonly createdId = signal<string | null>(null);
  protected readonly canCreateProperty = this.auth.hasPermission('properties.create');
  protected readonly canCreateContact = this.auth.hasPermission('customers.contacts.create');
  protected readonly form = this.fb.group({
    firstName: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(100)]],
    dateOfBirth: [''],
    legalName: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(255)]],
    businessName: ['', Validators.maxLength(255)], taxNumber: ['', Validators.maxLength(100)],
    email: ['', Validators.email], phone: [''], billingEmail: ['', Validators.email], billingPhone: [''],
    preferredContactMethod: ['NONE'], language: ['en-CA'], notes: [''],
    contactFirstName: [''], contactLastName: [''], contactEmail: ['', Validators.email], contactPhone: [''],
    propertyName: [''], addressLine1: [''], addressLine2: [''], city: [''], province: [''], postalCode: [''], propertyNotes: [''],
  });
  constructor() { if (this.customerId && this.auth.hasPermission('customers.edit')) this.loadCustomer(); else if (this.customerId) this.error.set('customerEditForbidden'); }
  protected retryLoad(): void { if (this.auth.hasPermission('customers.edit')) this.loadCustomer(); }
  protected setType(type: CustomerType): void { this.customerType.set(type); this.form.controls.firstName.updateValueAndValidity(); this.form.controls.legalName.updateValueAndValidity(); }
  protected isPropertyStarted(): boolean { const v = this.form.getRawValue(); return !!(v.propertyName || v.addressLine1 || v.addressLine2 || v.city || v.province || v.postalCode || v.propertyNotes); }
  protected isContactStarted(): boolean { const v = this.form.getRawValue(); return !!(v.contactFirstName || v.contactLastName || v.contactEmail || v.contactPhone); }
  protected formInvalid(): boolean {
    const type = this.customerType();
    this.form.controls.firstName.setValidators(type === 'INDIVIDUAL' ? [Validators.required, Validators.pattern(/\S/)] : []);
    this.form.controls.lastName.setValidators(type === 'INDIVIDUAL' ? [Validators.required, Validators.pattern(/\S/)] : []);
    this.form.controls.legalName.setValidators(type === 'COMPANY' ? [Validators.required, Validators.pattern(/\S/)] : []);
    this.form.controls.firstName.updateValueAndValidity(); this.form.controls.lastName.updateValueAndValidity(); this.form.controls.legalName.updateValueAndValidity();
    const v = this.form.getRawValue();
    const propertyStarted = !this.editing() && this.canCreateProperty && this.isPropertyStarted();
    const contactStarted = !this.editing() && type === 'COMPANY' && this.canCreateContact && this.isContactStarted();
    const badProperty = propertyStarted && (!v.addressLine1.trim() || !v.city.trim() || !v.province || !v.postalCode.trim());
    const badContact = contactStarted && (!v.contactFirstName.trim() || !v.contactLastName.trim());
    return this.form.invalid || badProperty || badContact;
  }
  private loadCustomer(): void {
    this.loading.set(true);
    this.api.customer(this.customerId!).subscribe({ next: (d) => { this.prefill(d); this.loading.set(false); }, error: () => { this.error.set('customerUnavailable'); this.loading.set(false); } });
  }
  private prefill(d: CustomerDetails): void {
    const c = d.customer; this.customerType.set(c.type); this.originalType = c.type; this.status.set(c.status);
    this.form.patchValue({ email: c.email ?? '', phone: c.phone ?? '', billingEmail: c.billingEmail ?? '', billingPhone: c.billingPhone ?? '', preferredContactMethod: c.preferredContactMethod, language: c.language, notes: c.notes ?? '', firstName: d.individual?.firstName ?? '', lastName: d.individual?.lastName ?? '', dateOfBirth: d.individual?.dateOfBirth ?? '', legalName: d.company?.legalName ?? '', businessName: d.company?.businessName ?? '', taxNumber: d.company?.taxNumber ?? '' });
  }
  protected async save(): Promise<void> {
    if (this.saving()) return;
    this.form.markAllAsTouched();
    if (this.formInvalid()) { this.error.set('customerFormInvalid'); return; }
    const v = this.form.getRawValue();
    const name = this.customerType() === 'INDIVIDUAL' ? `${v.firstName.trim()} ${v.lastName.trim()}`.trim() : v.legalName.trim();
    const header = { displayName: name, email: v.email.trim() || null, phone: v.phone.trim() || null, billingEmail: v.billingEmail.trim() || null, billingPhone: v.billingPhone.trim() || null, notes: v.notes.trim() || null, preferredContactMethod: v.preferredContactMethod as 'EMAIL'|'PHONE'|'SMS'|'NONE', language: v.language as 'en-CA'|'fr-CA' };
    const subtype = this.customerType() === 'INDIVIDUAL' ? { individual: { firstName: v.firstName.trim(), lastName: v.lastName.trim(), dateOfBirth: v.dateOfBirth || null } } : { company: { legalName: v.legalName.trim(), businessName: v.businessName.trim() || null, taxNumber: v.taxNumber.trim() || null } };
    const body = { ...header, type: this.customerType(), ...subtype } as CreateCustomerRequest;
    this.saving.set(true); this.error.set(null); this.unfinished.set(false);
    try {
      if (this.editing()) {
        if (this.customerType() !== this.originalType) {
          const confirmed = window.confirm(this.i18n.text('customerConvertDescription'));
          if (!confirmed) { this.saving.set(false); return; }
        }
        const update = { ...body, status: this.status() } as SaveCustomerRequest;
        await firstValueFrom(this.api.update(this.customerId!, update));
        await this.router.navigate(['/work/customers', this.customerId]);
        return;
      }
      const created = await firstValueFrom(this.api.create(body));
      const id = created.customer.id; this.createdId.set(id);
      try {
        if (this.canCreateProperty && this.isPropertyStarted()) {
          await firstValueFrom(this.api.createProperty(id, { name: v.propertyName.trim() || null, addressLine1: v.addressLine1.trim(), addressLine2: v.addressLine2.trim() || null, city: v.city.trim(), province: v.province as CanadianProvinceCode, postalCode: v.postalCode.trim(), notes: v.propertyNotes.trim() || null }));
        }
        if (this.customerType() === 'COMPANY' && this.canCreateContact && this.isContactStarted()) {
          await firstValueFrom(this.api.addContact(id, { firstName: v.contactFirstName.trim(), lastName: v.contactLastName.trim(), email: v.contactEmail.trim() || null, phone: v.contactPhone.trim() || null, isPrimary: true }));
        }
        await this.router.navigate(['/work/customers', id]);
      } catch { this.unfinished.set(true); this.error.set('customerPartialCreate'); }
    } catch (err) { this.error.set(err instanceof HttpErrorResponse && err.status === 409 ? 'customerConflict' : 'customerSaveFailed'); }
    finally { this.saving.set(false); }
  }
  protected originalType: CustomerType = 'INDIVIDUAL';
  protected setStatus(value: 'ACTIVE'|'INACTIVE'): void { this.status.set(value); }
  protected openCreated(): void { if (this.createdId()) void this.router.navigate(['/work/customers', this.createdId()]); }
  protected cancel(): void { if (this.customerId) void this.router.navigate(['/work/customers', this.customerId]); else void this.router.navigate(['/work/customers']); }
}
