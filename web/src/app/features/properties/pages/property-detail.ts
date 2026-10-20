import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
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
import type { CustomerJob, PropertyDetails } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { PageTitle } from '../../../shared/ui/page-title';
import { WorkStatus } from '../../../shared/ui/work-status';
import { addressText, directionsUrl } from '../../../shared/ui/work-format';
import { AuthSession } from '../../auth/data-access/auth-session';
import { PropertiesApi } from '../data-access/properties-api';
import { propertyReadError, propertyWriteError } from '../data-access/property-errors';
import { PropertyConfirm } from '../ui/property-confirm';
@Component({
  selector: 'app-property-detail',
  imports: [RouterLink, MatButtonModule, DatePipe, PageTitle, WorkStatus],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './property-detail.html',
  styleUrl: './properties.scss',
})
export class PropertyDetailPage {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  private readonly api = inject(PropertiesApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly params = toSignal(inject(ActivatedRoute).paramMap);
  private readonly revision = signal(0);
  protected readonly property = signal<PropertyDetails | null>(null);
  protected readonly customerName = signal<string | null>(null);
  protected readonly jobs = signal<CustomerJob[] | null>(null);
  protected readonly jobsError = signal<TranslationKey | null>(null);
  protected readonly customerError = signal<TranslationKey | null>(null);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly writeError = signal<TranslationKey | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly blocked = signal(false);
  protected readonly address = addressText;
  protected readonly directions = directionsUrl;
  protected customerId() {
    return this.params()?.get('customerId') ?? '';
  }
  protected id() {
    return this.params()?.get('propertyId') ?? '';
  }
  protected back() {
    return this.auth.hasPermission('customers.view')
      ? ['/work/customers', this.customerId()]
      : ['/'];
  }
  protected propertyAddress(property: PropertyDetails) {
    return { ...property, propertyName: property.name };
  }
  constructor() {
    effect((cleanup) => {
      const customerId = this.customerId(),
        id = this.id();
      this.revision();
      this.property.set(null);
      this.customerName.set(null);
      this.jobs.set(null);
      this.error.set(null);
      this.jobsError.set(null);
      this.customerError.set(null);
      this.loading.set(false);
      this.writeError.set(null);
      this.blocked.set(false);
      if (!this.auth.hasPermission('properties.view')) {
        this.error.set('forbiddenDescription');
        return;
      }
      if (!id || !customerId) return;
      this.loading.set(true);
      const requests = new Subscription();
      requests.add(
        this.api.detail(customerId, id).subscribe({
          next: (property) => {
            this.property.set(property);
            this.loading.set(false);
            if (this.auth.hasPermission('customers.view')) {
              requests.add(
                this.api.customer(customerId).subscribe({
                  next: (detail) => this.customerName.set(detail.customer.displayName),
                  error: (error: unknown) => this.customerError.set(propertyReadError(error)),
                }),
              );
              requests.add(
                this.api.jobs(customerId).subscribe({
                  next: (jobs) => this.jobs.set(jobs.filter((job) => job.propertyId === id)),
                  error: (error: unknown) => this.jobsError.set(propertyReadError(error)),
                }),
              );
            }
          },
          error: (error: unknown) => {
            this.loading.set(false);
            this.error.set(propertyReadError(error));
          },
        }),
      );
      cleanup(() => requests.unsubscribe());
    });
  }
  protected refresh() {
    if (!this.busy()) this.revision.update((value) => value + 1);
  }
  protected async mutate(action: 'archive' | 'restore' | 'delete') {
    const original = this.property(),
      customerId = this.customerId(),
      id = this.id();
    const permission = action === 'delete' ? 'properties.delete' : 'properties.archive';
    if (
      !original ||
      this.busy() ||
      this.blocked() ||
      !this.auth.hasPermission(permission) ||
      (action === 'delete' && !original.canBePermanentlyDeleted)
    )
      return;
    this.busy.set(true);
    this.writeError.set(null);
    const boundary = this.auth.boundary();
    try {
      const ref = this.dialog.open(PropertyConfirm, {
        data: { property: original, action },
        maxWidth: '480px',
      });
      const unregister = this.destroy.onDestroy(() => ref.close(false));
      const confirmed = await firstValueFrom(ref.afterClosed());
      unregister();
      if (
        !confirmed ||
        this.destroy.destroyed ||
        boundary !== this.auth.boundary() ||
        customerId !== this.customerId() ||
        id !== this.id() ||
        !this.auth.hasPermission(permission)
      )
        return;
      if (action === 'delete') await firstValueFrom(this.api.remove(customerId, id));
      else await firstValueFrom(this.api.lifecycle(customerId, original, action));
      if (
        this.destroy.destroyed ||
        boundary !== this.auth.boundary() ||
        customerId !== this.customerId() ||
        id !== this.id()
      )
        return;
      if (action === 'delete') await this.router.navigate(this.back());
      else {
        this.busy.set(false);
        this.refresh();
      }
    } catch (error) {
      if (
        !this.destroy.destroyed &&
        boundary === this.auth.boundary() &&
        customerId === this.customerId() &&
        id === this.id()
      ) {
        this.writeError.set(propertyWriteError(error));
        this.blocked.set(true);
      }
    } finally {
      this.busy.set(false);
    }
  }
}
