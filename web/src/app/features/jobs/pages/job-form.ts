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
import type { CustomerProperty, OfficeJobDetails } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { PageTitle } from '../../../shared/ui/page-title';
import { AuthSession } from '../../auth/data-access/auth-session';
import { CustomersApi } from '../../customers/data-access/customers-api';
import { JobsApi } from '../data-access/jobs-api';
import { jobReadError, jobWriteError } from '../data-access/job-errors';
@Component({
  selector: 'app-job-form',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    PageTitle,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './job-form.html',
  styleUrl: './job-form.scss',
})
export class JobForm {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  private readonly customers = inject(CustomersApi);
  private readonly api = inject(JobsApi);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);
  private readonly params = toSignal(this.route.paramMap);
  private readonly query = toSignal(this.route.queryParamMap);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    propertyId: ['', Validators.required],
    title: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(255)]],
    description: ['', Validators.maxLength(2000)],
  });
  protected readonly properties = signal<CustomerProperty[]>([]);
  protected readonly customerName = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly readError = signal<TranslationKey | null>(null);
  protected readonly blocked = signal(false);
  protected readonly saved = signal<OfficeJobDetails | null>(null);
  private readonly revision = signal(0);
  protected customerId() {
    return this.params()?.get('customerId') ?? '';
  }
  protected readonly allowed = computed(
    () => this.auth.hasPermission('JOB_CREATE') && this.auth.hasPermission('properties.view'),
  );
  private readonly customerReadAllowed = computed(() => this.auth.hasPermission('customers.view'));
  protected back() {
    return this.customerReadAllowed() ? ['/work/customers', this.customerId()] : ['/'];
  }
  constructor() {
    effect((cleanup) => {
      const customerId = this.customerId(),
        preferred = this.query()?.get('property');
      this.auth.boundary();
      this.revision();
      this.properties.set([]);
      this.customerName.set(null);
      this.readError.set(null);
      this.error.set(null);
      this.saved.set(null);
      this.blocked.set(false);
      this.form.reset();
      this.loading.set(false);
      if (!this.allowed()) {
        this.readError.set('forbiddenDescription');
        return;
      }
      if (!customerId) return;
      this.loading.set(true);
      const requests = new Subscription();
      let pending = 1 + Number(this.customerReadAllowed());
      const settled = () => {
        this.loading.set(--pending > 0);
      };
      requests.add(
        this.customers.properties(customerId).subscribe({
          next: (items) => {
            this.properties.set(items);
            if (items.some((p) => p.id === preferred))
              this.form.controls.propertyId.setValue(preferred ?? '');
            settled();
          },
          error: (error: unknown) => {
            this.readError.set(jobReadError(error));
            settled();
          },
        }),
      );
      if (this.customerReadAllowed())
        requests.add(
          this.customers.detail(customerId).subscribe({
            next: (detail) => {
              this.customerName.set(detail.customer.displayName);
              settled();
            },
            error: (error: unknown) => {
              this.readError.set(jobReadError(error));
              settled();
            },
          }),
        );
      cleanup(() => requests.unsubscribe());
    });
  }
  protected reload() {
    if (!this.busy()) this.revision.update((v) => v + 1);
  }
  protected async save() {
    if (
      this.busy() ||
      this.blocked() ||
      this.saved() ||
      this.loading() ||
      this.readError() ||
      !this.allowed()
    )
      return;
    this.form.markAllAsTouched();
    const value = this.form.getRawValue();
    if (this.form.invalid || !this.properties().some((p) => p.id === value.propertyId)) return;
    const customerId = this.customerId(),
      boundary = this.auth.boundary();
    this.busy.set(true);
    this.error.set(null);
    try {
      const result = await firstValueFrom(
        this.api.create({
          customerId,
          propertyId: value.propertyId,
          title: value.title.trim(),
          description: value.description.trim() || null,
        }),
      );
      if (
        this.destroy.destroyed ||
        boundary !== this.auth.boundary() ||
        customerId !== this.customerId()
      )
        return;
      if (this.customerReadAllowed())
        await this.router.navigate(['/work/jobs', result.id], { queryParams: { view: 'office' } });
      else {
        this.saved.set(result);
        this.form.reset();
      }
    } catch (error) {
      if (
        !this.destroy.destroyed &&
        boundary === this.auth.boundary() &&
        customerId === this.customerId()
      ) {
        const message = jobWriteError(error);
        this.error.set(message);
        this.blocked.set(message !== 'customerValidationFailed');
      }
    } finally {
      this.busy.set(false);
    }
  }
}
