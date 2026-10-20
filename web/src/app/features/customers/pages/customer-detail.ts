import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom, Subscription } from 'rxjs';
import type {
  CustomerContact,
  CustomerDetails,
  CustomerJob,
  CustomerProperty,
  PropertyStatusFilter,
} from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { PageTitle } from '../../../shared/ui/page-title';
import { WorkStatus } from '../../../shared/ui/work-status';
import { addressText } from '../../../shared/ui/work-format';
import { AuthSession } from '../../auth/data-access/auth-session';
import { CustomersApi } from '../data-access/customers-api';
import { customerReadError, customerWriteError } from '../data-access/customer-errors';
import { ContactEditor } from '../ui/contact-editor';
import { CustomerConfirm } from '../ui/customer-confirm';
@Component({
  selector: 'app-customer-detail',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    PageTitle,
    DatePipe,
    WorkStatus,
    ContactEditor,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './customer-detail.html',
  styleUrl: './customers.scss',
})
export class CustomerDetail {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  private readonly api = inject(CustomersApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);
  private readonly params = toSignal(this.route.paramMap);
  protected readonly propertyStatus = new FormControl<PropertyStatusFilter>(
    this.route.snapshot.queryParamMap.get('properties') === 'ARCHIVED'
      ? 'ARCHIVED'
      : this.route.snapshot.queryParamMap.get('properties') === 'ALL'
        ? 'ALL'
        : 'ACTIVE',
    { nonNullable: true },
  );
  private readonly propertyStatusValue = toSignal(this.propertyStatus.valueChanges, {
    initialValue: this.propertyStatus.value,
  });
  private readonly revision = signal(0);
  protected readonly detail = signal<CustomerDetails | null>(null);
  protected readonly properties = signal<CustomerProperty[] | null>(null);
  protected readonly jobs = signal<CustomerJob[] | null>(null);
  protected readonly propertyError = signal<TranslationKey | null>(null);
  protected readonly jobSearch = new FormControl('', { nonNullable: true });
  private readonly jobSearchValue = toSignal(this.jobSearch.valueChanges, { initialValue: '' });
  protected readonly visibleJobs = computed(() => {
    const search = this.jobSearchValue().trim().toLocaleLowerCase();
    return (
      this.jobs()?.filter(
        (job) =>
          !search ||
          `${job.jobNumber} ${job.title} ${job.description ?? ''}`
            .toLocaleLowerCase()
            .includes(search),
      ) ?? null
    );
  });
  protected readonly jobsError = signal<TranslationKey | null>(null);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly writeError = signal<TranslationKey | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly blocked = signal(false);
  protected readonly editing = signal(false);
  protected readonly selectedContact = signal<CustomerContact | null>(null);
  protected readonly address = addressText;
  protected id() {
    return this.params()?.get('customerId') ?? '';
  }
  constructor() {
    effect((cleanup) => {
      const id = this.id();
      this.revision();
      this.detail.set(null);
      this.properties.set(null);
      this.jobs.set(null);
      this.error.set(null);
      this.propertyError.set(null);
      this.jobsError.set(null);
      this.loading.set(false);
      this.editing.set(false);
      this.selectedContact.set(null);
      if (!this.auth.hasPermission('customers.view')) {
        this.error.set('forbiddenDescription');
        return;
      }
      if (!id) return;
      this.loading.set(true);
      const subscriptions = new Subscription();
      subscriptions.add(
        this.api.detail(id).subscribe({
          next: (detail) => {
            this.detail.set(detail);
            this.loading.set(false);
            subscriptions.add(
              this.api.jobs(id).subscribe({
                next: (jobs) => this.jobs.set(jobs),
                error: (error: unknown) => this.jobsError.set(customerReadError(error)),
              }),
            );
          },
          error: (error: unknown) => {
            this.error.set(customerReadError(error));
            this.loading.set(false);
          },
        }),
      );
      cleanup(() => subscriptions.unsubscribe());
    });
    effect((cleanup) => {
      const customerId = this.detail()?.customer.id,
        status = this.propertyStatusValue();
      this.properties.set(null);
      this.propertyError.set(null);
      if (!customerId || !this.auth.hasPermission('properties.view')) return;
      const request = this.api.properties(customerId, status).subscribe({
        next: (properties) => this.properties.set(properties),
        error: (error: unknown) => this.propertyError.set(customerReadError(error)),
      });
      cleanup(() => request.unsubscribe());
    });
  }
  protected refresh() {
    if (this.busy()) return;
    this.writeError.set(null);
    this.blocked.set(false);
    this.revision.update((value) => value + 1);
  }
  protected edit(contact: CustomerContact | null) {
    this.selectedContact.set(contact);
    this.editing.set(true);
  }
  protected async mutate(contact?: CustomerContact) {
    const permission = contact ? 'customers.contacts.remove' : 'customers.archive';
    if (this.busy() || this.blocked() || !this.auth.hasPermission(permission)) return;
    this.busy.set(true);
    this.writeError.set(null);
    const boundary = this.auth.boundary(),
      id = this.id();
    try {
      const confirmed = await firstValueFrom(
        this.dialog
          .open(CustomerConfirm, {
            data: {
              title: contact ? 'customerContactRemove' : 'customerArchive',
              message: contact ? 'customerRemoveDescription' : 'customerArchiveDescription',
              name: contact
                ? `${contact.firstName} ${contact.lastName}`
                : this.detail()?.customer.displayName,
            },
            maxWidth: '460px',
          })
          .afterClosed(),
      );
      if (
        !confirmed ||
        this.destroy.destroyed ||
        boundary !== this.auth.boundary() ||
        id !== this.id() ||
        !this.auth.hasPermission(permission)
      )
        return;
      if (contact) await firstValueFrom(this.api.removeContact(id, contact));
      else await firstValueFrom(this.api.archive(id));
      if (this.destroy.destroyed || boundary !== this.auth.boundary() || id !== this.id()) return;
      if (contact) {
        this.busy.set(false);
        this.refresh();
      } else await this.router.navigate(['/work/customers']);
    } catch (error) {
      if (!this.destroy.destroyed && boundary === this.auth.boundary() && id === this.id()) {
        this.writeError.set(customerWriteError(error));
        this.blocked.set(true);
      }
    } finally {
      this.busy.set(false);
    }
  }
}
