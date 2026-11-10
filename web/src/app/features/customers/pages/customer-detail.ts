import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import type { CustomerDetails, CustomerJob, CustomerProperty } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { CustomersApi } from '../data-access/customers-api';
import { ContactEditorDialog } from '../ui/contact-editor-dialog';
import { PropertyEditorDialog } from '../ui/property-editor-dialog';
import { WorkLabel } from '../../../shared/ui/work-label';
import { Icon } from '../../../shared/ui/icon';
import { CustomerConfirmDialog } from '../ui/customer-confirm-dialog';

@Component({
  selector: 'app-customer-detail',
  imports: [DatePipe, RouterLink, MatButtonModule, MatDialogModule, WorkLabel, Icon],
  templateUrl: './customer-detail.html',
  styleUrl: './customer-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerDetailPage {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  private readonly api = inject(CustomersApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialogs = inject(MatDialog);
  protected readonly detail = signal<CustomerDetails | null>(null);
  protected readonly properties = signal<CustomerProperty[]>([]);
  protected readonly hasArchivedProperties = signal(false);
  protected readonly jobs = signal<CustomerJob[]>([]);
  protected readonly propertiesLoading = signal(false);
  protected readonly jobsLoading = signal(false);
  protected readonly activeJobCount = computed(() => this.jobs().filter((job) => job.status === 'NEW' || job.status === 'ACTIVE').length);
  protected readonly showArchived = signal(false);
  protected readonly loading = signal(true);
  protected readonly propertiesUnavailable = signal(false);
  protected readonly jobsUnavailable = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly copiedValue = signal<string | null>(null);
  protected readonly customer = computed(() => this.detail()?.customer ?? null);
  protected readonly displaySubtype = computed(() => {
    const d = this.detail();
    if (!d) return '';
    return d.customer.type === 'COMPANY' ? d.company?.legalName ?? '' : `${d.individual?.firstName ?? ''} ${d.individual?.lastName ?? ''}`.trim();
  });
  private readonly customerId = this.route.snapshot.paramMap.get('customerId') ?? '';

  constructor() { this.load(); }
  protected load(): void {
    if (!this.customerId) { this.error.set('customerUnavailable'); this.loading.set(false); return; }
    this.loading.set(true); this.error.set(null);
    this.api.customer(this.customerId).subscribe({
      next: (detail) => {
        this.detail.set(detail); this.loading.set(false);
        this.loadProperties(); this.jobsLoading.set(true); this.jobsUnavailable.set(false);
        this.api.jobs(this.customerId).subscribe({ next: (rows) => { this.jobs.set(rows); this.jobsLoading.set(false); }, error: () => { this.jobsUnavailable.set(true); this.jobsLoading.set(false); } });
      },
      error: (err: unknown) => { this.loading.set(false); this.error.set(err instanceof HttpErrorResponse && err.status === 403 ? 'customerViewForbidden' : 'customerUnavailable'); },
    });
  }
  private loadProperties(): void {
    if (!this.auth.hasPermission('properties.view')) { this.propertiesUnavailable.set(true); return; }
    this.propertiesLoading.set(true); this.propertiesUnavailable.set(false);
    this.api.properties(this.customerId, this.showArchived() ? 'ARCHIVED' : 'ACTIVE').subscribe({ next: (rows) => {
      this.properties.set(rows); this.propertiesUnavailable.set(false); this.propertiesLoading.set(false);
      if (this.showArchived()) this.hasArchivedProperties.set(rows.length > 0);
      else this.api.properties(this.customerId, 'ARCHIVED').subscribe({ next: (archived) => this.hasArchivedProperties.set(archived.length > 0), error: () => this.hasArchivedProperties.set(false) });
    }, error: () => { this.propertiesUnavailable.set(true); this.propertiesLoading.set(false); } });
  }
  protected toggleArchived(): void { this.showArchived.update((value) => !value); this.loadProperties(); }
  protected initials(name: string): string { return name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toLocaleUpperCase(); }
  protected openContact(contact?: CustomerDetails['contacts'][number]): void {
    const d = this.detail(); if (!d) return;
    this.dialogs.open(ContactEditorDialog, { data: { customerId: d.customer.id, customerName: d.customer.displayName, contact }, width: '640px', maxWidth: '94vw', maxHeight: 'calc(100dvh - 32px)', autoFocus: 'first-tabbable' }).afterClosed().subscribe((saved: boolean) => { if (saved) this.load(); });
  }
  protected async openProperty(property: CustomerProperty): Promise<void> {
    const d = this.detail(); if (!d) return;
    try {
      const full = await firstValueFrom(this.api.property(this.customerId, property.id));
      this.dialogs.open(PropertyEditorDialog, { data: { customerId: d.customer.id, customerName: d.customer.displayName, property: full }, width: '780px', maxWidth: '94vw', maxHeight: 'calc(100dvh - 32px)', autoFocus: 'first-tabbable' }).afterClosed().subscribe((saved: boolean) => { if (saved) this.loadProperties(); });
    } catch { this.error.set('propertyLoadFailed'); }
  }
  protected addProperty(): void {
    const d = this.detail(); if (!d) return;
    this.dialogs.open(PropertyEditorDialog, { data: { customerId: d.customer.id, customerName: d.customer.displayName }, width: '780px', maxWidth: '94vw', maxHeight: 'calc(100dvh - 32px)', autoFocus: 'first-tabbable' }).afterClosed().subscribe((saved: boolean) => { if (saved) this.loadProperties(); });
  }
  protected removeContact(contact: CustomerDetails['contacts'][number]): void {
    const fullName = `${contact.firstName} ${contact.lastName}`;
    const ref = this.dialogs.open(CustomerConfirmDialog, { data: { title: 'customerRemove', message: 'customerRemoveContactConfirm', messageParams: { name: fullName }, confirm: 'customerRemove', destructive: true }, width: '500px', maxWidth: '94vw' });
    ref.afterClosed().subscribe((confirmed: boolean) => { if (confirmed) this.api.removeContact(this.customerId, contact.id, contact.version).subscribe({ next: () => this.load(), error: () => this.error.set('customerSaveFailed') }); });
  }
  protected archive(): void {
    if (!this.customer()) return;
    const ref = this.dialogs.open(CustomerConfirmDialog, { data: { title: 'customerArchive', message: 'customerArchiveDescription', confirm: 'customerArchive', destructive: true }, width: '520px', maxWidth: '94vw' });
    ref.afterClosed().subscribe((confirmed: boolean) => { if (confirmed) this.api.archive(this.customerId).subscribe({ next: () => void this.router.navigate(['/work/customers']), error: () => this.error.set('customerSaveFailed') }); });
  }
  protected async lifecycleProperty(property: CustomerProperty, archive: boolean): Promise<void> {
    try {
      const details = await firstValueFrom(this.api.property(this.customerId, property.id));
      const ref = this.dialogs.open(CustomerConfirmDialog, { data: { title: archive ? 'propertyArchive' : 'propertyRestore', message: archive ? 'customerPropertyArchivePrompt' : 'propertyRestoreDescription', messageParams: { jobs: details.archiveImpact.activeJobCount, visits: details.archiveImpact.activeVisitCount }, confirm: 'customerConfirm', destructive: archive }, width: '520px', maxWidth: '94vw' });
      const confirmed = await firstValueFrom(ref.afterClosed());
      if (!confirmed) return;
      const body = { expectedVersion: details.version, clientOperationId: crypto.randomUUID() };
      await firstValueFrom(archive ? this.api.archiveProperty(this.customerId, property.id, body) : this.api.restoreProperty(this.customerId, property.id, body));
      if (!archive) this.showArchived.set(false);
      this.loadProperties();
    } catch { this.error.set('propertySaveFailed'); }
  }
  protected async copyValue(value: string): Promise<void> {
    try { await navigator.clipboard.writeText(value); this.copiedValue.set(value); window.setTimeout(() => { if (this.copiedValue() === value) this.copiedValue.set(null); }, 1400); }
    catch { this.copiedValue.set(null); }
  }
  protected copyLabel(value: string): string { return this.i18n.text(this.copiedValue() === value ? 'customerCopied' : 'customerCopyValue'); }
  protected propertyAddress(property: CustomerProperty): string { return [property.addressLine1, property.addressLine2, property.city, property.province, property.postalCode].filter(Boolean).join(', '); }
  protected jobAddress(job: CustomerJob): string { const a = job.propertyAddress; return a ? [a.propertyName, a.addressLine1, a.addressLine2, a.city, a.province, a.postalCode].filter(Boolean).join(', ') : this.i18n.text('customerNoProperty'); }
  protected technicianNames(job: CustomerJob): string { return job.technicians.map((t) => t.name || this.i18n.text('unnamedMember')).join(', ') || this.i18n.text('scheduleUnassigned'); }
}
