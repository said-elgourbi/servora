import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { ReactiveFormsModule, FormControl } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import type {
  CustomerJobFilter,
  CustomerStatusFilter,
  CustomerListSummary,
} from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { Icon } from '../../../shared/ui/icon';
import { PageTitle } from '../../../shared/ui/page-title';
import { AuthSession } from '../../auth/data-access/auth-session';
import { CustomersApi } from '../data-access/customers-api';
import { customerReadError } from '../data-access/customer-errors';
@Component({
  selector: 'app-customer-list',
  imports: [RouterLink, ReactiveFormsModule, MatButtonModule, Icon, PageTitle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './customer-list.html',
  styleUrls: ['./customers.scss', './customer-directory.scss'],
})
export class CustomerList {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  private readonly api = inject(CustomersApi);
  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly status = new FormControl<CustomerStatusFilter>('ALL', { nonNullable: true });
  protected readonly jobs = new FormControl<CustomerJobFilter>('ALL', { nonNullable: true });
  private readonly searchValue = toSignal(this.search.valueChanges, { initialValue: '' });
  private readonly statusValue = toSignal(this.status.valueChanges, {
    initialValue: this.status.value,
  });
  private readonly jobsValue = toSignal(this.jobs.valueChanges, { initialValue: this.jobs.value });
  protected readonly rows = signal<CustomerListSummary[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  private readonly revision = signal(0);
  protected readonly filtered = computed(() => {
    const query = this.searchValue().trim().toLocaleLowerCase(this.i18n.language());
    return this.rows().filter((row) =>
      [row.displayName, row.email, row.phone].some((value) =>
        value?.toLocaleLowerCase(this.i18n.language()).includes(query),
      ),
    );
  });
  constructor() {
    effect((cleanup) => {
      this.revision();
      const status = this.statusValue(),
        jobs = this.jobsValue();
      this.rows.set([]);
      this.error.set(null);
      this.loading.set(false);
      if (!this.auth.hasPermission('customers.view')) {
        this.error.set('forbiddenDescription');
        return;
      }
      this.loading.set(true);
      const request = this.api.list(status, jobs).subscribe({
        next: (rows) => {
          this.rows.set(rows);
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
  protected readonly hasFilters = computed(
    () =>
      Boolean(this.searchValue().trim()) ||
      this.statusValue() !== 'ALL' ||
      this.jobsValue() !== 'ALL',
  );
  protected clearFilters() {
    this.search.setValue('');
    this.status.setValue('ALL');
    this.jobs.setValue('ALL');
  }
  protected initials(name: string): string {
    return name
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((part) => part[0])
      .join('')
      .toLocaleUpperCase(this.i18n.language());
  }
  protected refresh() {
    this.revision.update((value) => value + 1);
  }
}
