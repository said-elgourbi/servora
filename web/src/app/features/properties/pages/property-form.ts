import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom, Subscription } from 'rxjs';
import type {
  CanadianProvinceCode,
  CustomerProperty,
  PropertyDetails,
  SavePropertyRequest,
} from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { PageTitle } from '../../../shared/ui/page-title';
import { AuthSession } from '../../auth/data-access/auth-session';
import { PropertiesApi } from '../data-access/properties-api';
import { propertyReadError, propertyWriteError } from '../data-access/property-errors';
const nonblank = [Validators.required, Validators.pattern(/\S/)];
@Component({
  selector: 'app-property-form',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    PageTitle,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './property-form.html',
  styleUrl: './properties.scss',
})
export class PropertyForm {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  private readonly api = inject(PropertiesApi);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly params = toSignal(inject(ActivatedRoute).paramMap);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    name: ['', Validators.maxLength(255)],
    addressLine1: ['', [...nonblank, Validators.maxLength(255)]],
    addressLine2: ['', Validators.maxLength(255)],
    city: ['', [...nonblank, Validators.maxLength(100)]],
    province: this.fb.control<CanadianProvinceCode | ''>('', Validators.required),
    postalCode: ['', [...nonblank, Validators.maxLength(20)]],
    notes: ['', Validators.maxLength(4000)],
  });
  protected readonly provinces: readonly { code: CanadianProvinceCode; label: TranslationKey }[] = [
    { code: 'AB', label: 'provinceAB' },
    { code: 'BC', label: 'provinceBC' },
    { code: 'MB', label: 'provinceMB' },
    { code: 'NB', label: 'provinceNB' },
    { code: 'NL', label: 'provinceNL' },
    { code: 'NS', label: 'provinceNS' },
    { code: 'NT', label: 'provinceNT' },
    { code: 'NU', label: 'provinceNU' },
    { code: 'ON', label: 'provinceON' },
    { code: 'PE', label: 'provincePE' },
    { code: 'QC', label: 'provinceQC' },
    { code: 'SK', label: 'provinceSK' },
    { code: 'YT', label: 'provinceYT' },
  ];
  protected readonly property = signal<PropertyDetails | null>(null);
  protected readonly saved = signal<CustomerProperty | null>(null);
  protected readonly customerName = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly readError = signal<TranslationKey | null>(null);
  protected readonly blocked = signal(false);
  protected readonly attempted = signal(false);
  private readonly revision = signal(0);
  protected customerId() {
    return this.params()?.get('customerId') ?? '';
  }
  protected id() {
    return this.params()?.get('propertyId') ?? undefined;
  }
  protected readonly allowed = computed(() =>
    this.id()
      ? this.auth.hasPermission('properties.view') && this.auth.hasPermission('properties.edit')
      : this.auth.hasPermission('properties.create'),
  );
  private readonly customerReadAllowed = computed(() => this.auth.hasPermission('customers.view'));
  protected back() {
    return this.id() && this.auth.hasPermission('properties.view')
      ? ['/work/customers', this.customerId(), 'properties', this.id()]
      : this.customerReadAllowed()
        ? ['/work/customers', this.customerId()]
        : ['/'];
  }
  constructor() {
    effect((cleanup) => {
      const customerId = this.customerId(),
        id = this.id();
      this.revision();
      // Credential refresh with the same capabilities must not reset an unsent editor.
      // Real session boundaries and permission changes still invalidate its reads.
      this.auth.boundary();
      this.property.set(null);
      this.saved.set(null);
      this.customerName.set(null);
      this.readError.set(null);
      this.error.set(null);
      this.blocked.set(false);
      this.loading.set(false);
      if (!this.allowed()) {
        this.readError.set('forbiddenDescription');
        return;
      }
      if (!customerId) return;
      const requests = new Subscription();
      let pending = Number(!!id) + Number(this.customerReadAllowed());
      this.loading.set(pending > 0);
      const settled = () => {
        pending--;
        this.loading.set(pending > 0);
      };
      if (this.customerReadAllowed())
        requests.add(
          this.api.customer(customerId).subscribe({
            next: (detail) => {
              this.customerName.set(detail.customer.displayName);
              settled();
            },
            error: (error: unknown) => {
              this.readError.set(propertyReadError(error));
              settled();
            },
          }),
        );
      if (id)
        requests.add(
          this.api.detail(customerId, id).subscribe({
            next: (property) => {
              this.property.set(property);
              this.form.reset({
                name: property.name ?? '',
                addressLine1: property.addressLine1,
                addressLine2: property.addressLine2 ?? '',
                city: property.city,
                province:
                  this.provinces.find((province) => province.code === property.province)?.code ??
                  '',
                postalCode: property.postalCode,
                notes: property.notes ?? '',
              });
              this.attempted.set(false);
              settled();
            },
            error: (error: unknown) => {
              this.readError.set(propertyReadError(error));
              settled();
            },
          }),
        );
      else this.form.reset();
      cleanup(() => requests.unsubscribe());
    });
  }
  protected reload() {
    if (!this.busy()) this.revision.update((value) => value + 1);
  }
  protected async save() {
    if (this.busy() || this.blocked() || this.saved() || this.readError() || !this.allowed())
      return;
    this.attempted.set(true);
    this.form.markAllAsTouched();
    const value = this.form.getRawValue();
    if (this.form.invalid || !value.province) return;
    const id = this.id(),
      customerId = this.customerId(),
      boundary = this.auth.boundary();
    const original = this.property();
    if (id && !original) return;
    this.busy.set(true);
    this.error.set(null);
    const input: SavePropertyRequest = {
      name: value.name.trim() || null,
      addressLine1: value.addressLine1.trim(),
      addressLine2: value.addressLine2.trim() || null,
      city: value.city.trim(),
      province: value.province,
      postalCode: value.postalCode.trim(),
      notes: value.notes.trim() || null,
      ...(original ? { expectedVersion: original.version } : {}),
    };
    try {
      const result = await firstValueFrom(
        id ? this.api.update(customerId, id, input) : this.api.create(customerId, input),
      );
      if (
        this.destroy.destroyed ||
        boundary !== this.auth.boundary() ||
        id !== this.id() ||
        customerId !== this.customerId()
      )
        return;
      if (this.auth.hasPermission('properties.view')) {
        await this.router.navigate(['/work/customers', customerId, 'properties', result.id]);
      } else {
        this.saved.set(result);
        this.form.reset();
      }
    } catch (error) {
      if (
        !this.destroy.destroyed &&
        boundary === this.auth.boundary() &&
        id === this.id() &&
        customerId === this.customerId()
      ) {
        const message = propertyWriteError(error);
        this.error.set(message);
        this.blocked.set(message !== 'customerValidationFailed');
      }
    } finally {
      this.busy.set(false);
    }
  }
}
