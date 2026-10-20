import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import type {
  CustomerDetails,
  CustomerRecord,
  CustomerStatus,
  CustomerType,
  SaveCustomerRequest,
} from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { PageTitle } from '../../../shared/ui/page-title';
import { AuthSession } from '../../auth/data-access/auth-session';
import { CustomersApi } from '../data-access/customers-api';
import { customerReadError, customerWriteError } from '../data-access/customer-errors';
import { CustomerConfirm } from '../ui/customer-confirm';
const requiredText = [Validators.required, Validators.pattern(/\S/)];
@Component({
  selector: 'app-customer-form',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    PageTitle,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './customer-form.html',
  styleUrl: './customers.scss',
})
export class CustomerForm {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  private readonly api = inject(CustomersApi);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroy = inject(DestroyRef);
  private readonly params = toSignal(inject(ActivatedRoute).paramMap);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    type: this.fb.control<CustomerType>('INDIVIDUAL'),
    displayName: ['', [...requiredText, Validators.maxLength(255)]],
    firstName: ['', [Validators.maxLength(100)]],
    lastName: ['', [Validators.maxLength(100)]],
    dateOfBirth: [''],
    legalName: ['', [Validators.maxLength(255)]],
    businessName: ['', [Validators.maxLength(255)]],
    taxNumber: ['', [Validators.maxLength(100)]],
    email: ['', [Validators.email, Validators.maxLength(255)]],
    phone: ['', [Validators.maxLength(50)]],
    notes: ['', [Validators.maxLength(4000)]],
    language: this.fb.control<CustomerRecord['language']>('en-CA'),
    preferredContactMethod: this.fb.control<CustomerRecord['preferredContactMethod']>('NONE'),
    status: this.fb.control<CustomerStatus>('ACTIVE'),
  });
  protected readonly type = toSignal(this.form.controls.type.valueChanges, {
    initialValue: this.form.controls.type.value,
  });
  protected readonly loaded = signal<CustomerDetails | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly uncertain = signal(false);
  protected readonly attempted = signal(false);
  private readonly revision = signal(0);
  protected id() {
    return this.params()?.get('customerId') ?? undefined;
  }
  protected allowed() {
    return (
      this.auth.hasPermission('customers.view') &&
      this.auth.hasPermission(this.id() ? 'customers.edit' : 'customers.create')
    );
  }
  constructor() {
    this.form.controls.type.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.setSubtypeValidation());
    this.setSubtypeValidation();
    effect((cleanup) => {
      const id = this.id();
      this.revision();
      this.loaded.set(null);
      this.error.set(null);
      this.loading.set(false);
      if (!this.allowed()) {
        this.error.set('forbiddenDescription');
        return;
      }
      if (!id) return;
      this.loading.set(true);
      const request = this.api.detail(id).subscribe({
        next: (detail) => {
          this.loaded.set(detail);
          this.fill(detail);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.error.set(customerReadError(error));
          this.loading.set(false);
        },
      });
      cleanup(() => request.unsubscribe());
    });
  }
  private setSubtypeValidation() {
    const individual = this.form.controls.type.value === 'INDIVIDUAL';
    for (const key of ['firstName', 'lastName', 'legalName'] as const) {
      const control = this.form.controls[key];
      control.setValidators([
        Validators.maxLength(key === 'legalName' ? 255 : 100),
        ...((key === 'legalName' ? !individual : individual) ? requiredText : []),
      ]);
      control.updateValueAndValidity();
    }
  }
  private fill(detail: CustomerDetails) {
    const c = detail.customer;
    this.form.reset({
      type: c.type,
      displayName: c.displayName,
      email: c.email ?? '',
      phone: c.phone ?? '',
      notes: c.notes ?? '',
      language: c.language,
      preferredContactMethod: c.preferredContactMethod,
      status: c.status,
      firstName: detail.individual?.firstName ?? '',
      lastName: detail.individual?.lastName ?? '',
      dateOfBirth: detail.individual?.dateOfBirth ?? '',
      legalName: detail.company?.legalName ?? '',
      businessName: detail.company?.businessName ?? '',
      taxNumber: detail.company?.taxNumber ?? '',
    });
    this.setSubtypeValidation();
  }
  protected refresh() {
    this.revision.update((value) => value + 1);
  }
  protected async save() {
    if (this.busy() || this.uncertain() || !this.allowed()) return;
    this.attempted.set(true);
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.busy.set(true);
    this.error.set(null);
    const boundary = this.auth.boundary(),
      id = this.id();
    const value = this.form.getRawValue();
    try {
      if (this.loaded() && this.loaded()?.customer.type !== value.type) {
        const confirmed = await firstValueFrom(
          this.dialog
            .open(CustomerConfirm, {
              data: {
                title: 'customerConvert',
                message: 'customerConvertDescription',
                name: value.displayName,
              },
              maxWidth: '460px',
            })
            .afterClosed(),
        );
        if (!confirmed) return;
      }
      if (
        this.destroy.destroyed ||
        boundary !== this.auth.boundary() ||
        id !== this.id() ||
        !this.allowed()
      )
        return;
      const header = {
        displayName: value.displayName.trim(),
        email: value.email.trim() || null,
        phone: value.phone.trim() || null,
        notes: value.notes.trim() || null,
        language: value.language,
        preferredContactMethod: value.preferredContactMethod,
        ...(this.id() ? { status: value.status } : {}),
      };
      const input: SaveCustomerRequest =
        value.type === 'INDIVIDUAL'
          ? {
              ...header,
              type: 'INDIVIDUAL',
              individual: {
                firstName: value.firstName.trim(),
                lastName: value.lastName.trim(),
                dateOfBirth: value.dateOfBirth || null,
              },
            }
          : {
              ...header,
              type: 'COMPANY',
              company: {
                legalName: value.legalName.trim(),
                businessName: value.businessName.trim() || null,
                taxNumber: value.taxNumber.trim() || null,
              },
            };
      const result = await firstValueFrom(this.api.save(input, id));
      if (!this.destroy.destroyed && boundary === this.auth.boundary() && id === this.id())
        await this.router.navigate(['/work/customers', result.customer.id]);
    } catch (error) {
      if (!this.destroy.destroyed && boundary === this.auth.boundary() && id === this.id()) {
        const message = customerWriteError(error);
        this.error.set(message);
        this.uncertain.set(message === 'customerWriteUncertain');
      }
    } finally {
      this.busy.set(false);
    }
  }
}
