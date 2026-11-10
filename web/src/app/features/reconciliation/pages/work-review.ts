import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import type { AdHocWorkReport, AdHocReviewCustomerOption, AdHocReviewPropertyOption, AdHocReviewJobOption } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { JobsApi } from '../../jobs/data-access/jobs-api';
import { WorkLabel } from '../../../shared/ui/work-label';

@Component({
  selector: 'app-work-review',
  imports: [DatePipe, WorkLabel, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './work-review.scss',
  templateUrl: './work-review.html',
})
export class WorkReviewPage {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  protected readonly router = inject(Router);
  private readonly api = inject(JobsApi);
  protected readonly reports = signal<AdHocWorkReport[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly selected = computed(() => this.reports().find((row) => row.id === this.selectedId()) ?? null);
  protected readonly pendingCount = computed(() => this.reports().filter((r) => r.status === 'PENDING').length);
  protected readonly showResolved = signal(false);
  protected readonly visibleReports = computed(() => this.reports().filter((r) => this.showResolved() || r.status === 'PENDING'));
  protected readonly busy = signal(false);
  protected readonly loading = signal(true);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly locationMode = signal<'existing' | 'property' | 'new'>('existing');
  protected readonly canChooseExisting = this.auth.hasPermission('customers.view') && this.auth.hasPermission('properties.view');
  protected readonly canCreateCustomerAndProperty = this.auth.hasPermission('customers.create') && this.auth.hasPermission('properties.create');
  protected readonly canCreateProperty = this.auth.hasPermission('customers.view') && this.auth.hasPermission('properties.create');
  protected readonly customerQuery = signal('');
  protected readonly customers = signal<AdHocReviewCustomerOption[]>([]);
  protected readonly selectedCustomer = signal('');
  protected readonly properties = signal<AdHocReviewPropertyOption[]>([]);
  protected readonly selectedProperty = signal('');
  protected readonly jobQuery = signal('');
  protected readonly jobs = signal<AdHocReviewJobOption[]>([]);
  protected readonly selectedJob = signal('');
  protected readonly selectedJobStatus = signal<string | null>(null);
  protected readonly reviewNote = signal('');
  protected readonly customerName = signal('');
  protected readonly customerType = signal<'COMPANY' | 'INDIVIDUAL'>('COMPANY');
  protected readonly firstName = signal('');
  protected readonly lastName = signal('');
  protected readonly customerPhone = signal('');
  protected readonly propertyLine = signal('');
  protected readonly propertyCity = signal('');
  protected readonly propertyProvince = signal('ON');
  protected readonly propertyPostal = signal('');
  protected readonly convertTitle = signal('');
  private customerSearchTimer: ReturnType<typeof setTimeout> | undefined;
  private jobSearchTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    if (!this.canChooseExisting) this.locationMode.set(this.canCreateProperty ? 'property' : 'new');
    if (this.auth.hasPermission('visits.review_ad_hoc_work')) void this.load(); else { this.loading.set(false); this.error.set('forbiddenDescription'); }
  }
  protected async load(): Promise<void> {
    this.loading.set(true); this.error.set(null);
    try {
      const rows = await firstValueFrom(this.api.adHocReports());
      this.reports.set(rows);
      const current = this.selectedId();
      this.selectedId.set(rows.some((r) => r.id === current) ? current : rows.find((r) => r.status === 'PENDING')?.id ?? rows[0]?.id ?? null);
      this.syncFormFromReport();
    } catch (e) { this.error.set(this.errorKey(e)); }
    finally { this.loading.set(false); }
  }
  protected select(report: AdHocWorkReport): void { this.selectedId.set(report.id); this.notice.set(null); this.error.set(null); this.syncFormFromReport(); }
  protected setCustomerQuery(value: string): void {
    this.customerQuery.set(value); this.selectedCustomer.set(''); this.properties.set([]); this.selectedProperty.set('');
    clearTimeout(this.customerSearchTimer);
    if (value.trim().length < 2) { this.customers.set([]); return; }
    this.customerSearchTimer = setTimeout(() => void this.searchCustomers(value.trim()), 250);
  }
  private async searchCustomers(q: string): Promise<void> {
    try { this.customers.set(await firstValueFrom(this.api.adHocCustomers(q))); } catch (e) { this.error.set(this.errorKey(e)); }
  }
  protected async chooseCustomer(id: string): Promise<void> {
    this.selectedCustomer.set(id); this.selectedProperty.set(''); this.selectedJob.set('');
    const customer = this.customers().find((x) => x.id === id);
    this.customerQuery.set(customer?.displayName ?? '');
    try {
      const [properties, jobs] = await Promise.all([
        firstValueFrom(this.api.adHocProperties(id)), firstValueFrom(this.api.adHocJobs(id, '')),
      ]);
      this.properties.set(properties); this.jobs.set(jobs);
    } catch (e) { this.error.set(this.errorKey(e)); }
  }
  protected setJobQuery(value: string): void {
    this.jobQuery.set(value); this.selectedJob.set(''); clearTimeout(this.jobSearchTimer);
    if (this.selectedCustomer() || value.trim().length >= 2)
      this.jobSearchTimer = setTimeout(() => void this.searchJobs(), 250);
  }
  private async searchJobs(): Promise<void> {
    try { this.jobs.set(await firstValueFrom(this.api.adHocJobs(this.selectedCustomer() || null, this.jobQuery().trim()))); }
    catch (e) { this.error.set(this.errorKey(e)); }
  }
  protected async chooseJob(id: string): Promise<void> {
    this.selectedJob.set(id); this.selectedJobStatus.set(null);
    if (!id) return;
    try { const job = await firstValueFrom(this.api.detail(id)); this.selectedJobStatus.set(job.status); }
    catch (e) { this.error.set(this.errorKey(e)); }
  }
  protected async resolveLocation(): Promise<void> {
    const report = this.selected(); if (!report || this.busy()) return;
    if (this.locationMode() === 'existing' && (!this.selectedCustomer() || !this.selectedProperty())) return;
    if (this.locationMode() === 'property' && (!this.selectedCustomer() || !this.propertyLine().trim() || !this.propertyCity().trim() || !this.propertyPostal().trim())) return;
    const customerDetailsMissing = this.customerType() === 'COMPANY'
      ? !this.customerName().trim()
      : !this.firstName().trim() || !this.lastName().trim();
    if (this.locationMode() === 'new' && (customerDetailsMissing || !this.propertyLine().trim() || !this.propertyCity().trim() || !this.propertyPostal().trim())) return;
    this.busy.set(true); this.error.set(null); this.notice.set(null);
    try {
      const newLocation = this.locationMode() === 'new';
      const newProperty = this.locationMode() !== 'existing';
      const input: Record<string, unknown> = {
        expectedStatus: report.status, expectedVersion: report.version, note: this.reviewNote().trim() || null,
        existingCustomerId: newLocation ? null : this.selectedCustomer(),
        existingPropertyId: newProperty ? null : this.selectedProperty(),
      };
      if (newLocation) {
        const name = this.customerName().trim();
        input['newCustomer'] = this.customerType() === 'COMPANY'
          ? { type: 'COMPANY', displayName: name, phone: this.customerPhone().trim() || null, company: { legalName: name } }
          : { type: 'INDIVIDUAL', displayName: `${this.firstName().trim()} ${this.lastName().trim()}`, phone: this.customerPhone().trim() || null, individual: { firstName: this.firstName().trim(), lastName: this.lastName().trim() } };
      }
      if (newProperty) input['newProperty'] = { addressLine1: this.propertyLine().trim(), city: this.propertyCity().trim(), province: this.propertyProvince(), postalCode: this.propertyPostal().trim() };
      const updated = await firstValueFrom(this.api.resolveAdHocLocation(report.id, input));
      this.replace(updated); this.notice.set(this.i18n.text('reviewLocationSaved'));
    } catch (e) { this.error.set(this.errorKey(e)); }
    finally { this.busy.set(false); }
  }
  protected async linkReport(): Promise<void> {
    const report = this.selected(); if (!report || this.busy() || !this.selectedJob()) return;
    const job = this.jobs().find((x) => x.id === this.selectedJob());
    if (job) {
      const closed = this.selectedJobStatus() === 'COMPLETED' || this.selectedJobStatus() === 'CANCELED';
      const key = closed ? 'reviewConfirmClosedLink' : 'reviewConfirmLink';
      const prompt = this.i18n.text(key).replace('{job}', `#${job.jobNumber} · ${job.title}`).replace('{outcome}', this.i18n.text(this.statusOutcomeKey(report.outcomeCode))).replace('{state}', this.i18n.text(this.resultStateKey(report.outcomeCode, this.selectedJobStatus()!)));
      if (!window.confirm(prompt)) return;
    }
    await this.mutate(async () => {
      const result = await firstValueFrom(this.api.linkAdHocReport(report.id, { jobId: this.selectedJob(), expectedStatus: report.status, expectedVersion: report.version, note: this.reviewNote().trim() || null }));
      this.notice.set(this.i18n.text('reviewLinked').replace('{job}', `#${result.jobNumber}`));
    });
  }
  protected async convertReport(): Promise<void> {
    const report = this.selected(); if (!report || this.busy()) return;
    const title = (this.convertTitle().trim() || report.summary.trim()).slice(0, 255);
    if (!window.confirm(this.i18n.text('reviewConfirmConvert').replace('{title}', title))) return;
    await this.mutate(async () => {
      const result = await firstValueFrom(this.api.convertAdHocReport(report.id, { title, description: report.notes, expectedStatus: report.status, expectedVersion: report.version, note: this.reviewNote().trim() || null }));
      this.notice.set(this.i18n.text('reviewConverted').replace('{job}', `#${result.jobNumber}`));
    });
  }
  protected async rejectReport(): Promise<void> {
    const report = this.selected(); if (!report || this.busy()) return;
    if (!window.confirm(this.i18n.text('reviewConfirmReject'))) return;
    await this.mutate(async () => {
      const updated = await firstValueFrom(this.api.rejectAdHocReport(report.id, { expectedStatus: report.status, expectedVersion: report.version, note: this.reviewNote().trim() || null }));
      this.replace(updated); this.notice.set(this.i18n.text('reviewRejected'));
    });
  }
  private async mutate(action: () => Promise<void>): Promise<void> {
    this.busy.set(true); this.error.set(null); this.notice.set(null);
    try { await action(); await this.load(); }
    catch (e) { const key = this.errorKey(e); await this.load(); this.error.set(key); }
    finally { this.busy.set(false); }
  }
  private replace(report: AdHocWorkReport): void { this.reports.update((rows) => rows.map((row) => row.id === report.id ? report : row)); this.syncFormFromReport(); }
  private syncFormFromReport(): void {
    const report = this.selected();
    this.reviewNote.set(report?.reviewNote ?? '');
    this.customerName.set(report?.reportedCustomerName ?? report?.customerName ?? '');
    this.firstName.set(''); this.lastName.set(''); this.customerType.set('COMPANY');
    this.customerPhone.set(report?.reportedCustomerPhone ?? '');
    const address = this.parseAddress(report?.reportedCustomerAddress ?? '');
    this.propertyLine.set(address.line || report?.propertyAddress?.addressLine1 || '');
    this.propertyCity.set(address.city || report?.propertyAddress?.city || '');
    this.propertyProvince.set(address.province || report?.propertyAddress?.province || 'ON');
    this.propertyPostal.set(address.postal || report?.propertyAddress?.postalCode || '');
    this.convertTitle.set(report?.summary ?? '');
    this.customerQuery.set(''); this.selectedCustomer.set(''); this.selectedProperty.set(''); this.properties.set([]);
    this.jobQuery.set(''); this.selectedJob.set(''); this.jobs.set([]);
    this.selectedJobStatus.set(null);
  }
  protected locationText(report: AdHocWorkReport): string {
    if (report.customerName) return [report.customerName, report.propertyAddress?.addressLine1, report.propertyAddress?.city].filter(Boolean).join(' · ');
    return [report.reportedCustomerName, report.reportedCustomerAddress].filter(Boolean).join(' · ') || this.i18n.text('reviewLocationUnknown');
  }
  protected formatRange(report: AdHocWorkReport): string {
    const locale = this.i18n.language();
    return `${new Date(report.workStartedAt).toLocaleString(locale, { dateStyle: 'short', timeStyle: 'short' })} – ${new Date(report.workEndedAt).toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit' })}`;
  }
  protected statusKey(status: string): TranslationKey { return ({ PENDING: 'reviewPending', LINKED: 'reviewLinkedStatus', CONVERTED: 'reviewConvertedStatus', REJECTED: 'reviewRejectedStatus' } as Record<string, TranslationKey>)[status] ?? 'reviewPending'; }
  protected statusOutcomeKey(code: string): TranslationKey { return ({ RESOLVED: 'resolved', NEEDS_FOLLOW_UP: 'followUpScheduling', NEEDS_PARTS: 'partsRequired', UNABLE_TO_COMPLETE: 'unableToComplete' } as Record<string, TranslationKey>)[code] ?? 'reviewOutcome'; }
  protected resultStateKey(outcome: string, current: string): TranslationKey { return outcome === 'RESOLVED' ? (current === 'CANCELED' ? 'statusCompleted' : 'statusCompleted') : 'statusActive'; }
  private errorKey(error: unknown): TranslationKey {
    if (error instanceof HttpErrorResponse) return error.status === 409 ? 'reviewConflict' : error.status === 403 ? 'forbiddenDescription' : error.status === 0 || error.status >= 500 ? 'connectionError' : 'reviewFailed';
    return 'reviewFailed';
  }
  private parseAddress(value: string): { line: string; city: string; province: string; postal: string } {
    const parts = value.split(',').map((part) => part.trim()).filter(Boolean);
    const tail = parts.at(-1) ?? '';
    const matched = tail.match(/\b(AB|BC|MB|NB|NL|NS|NT|NU|ON|PE|QC|SK|YT)\s+([A-Z]\d[A-Z]\s?\d[A-Z]\d)\b/i);
    return {
      line: parts.length > 1 ? (parts[0] ?? value) : value,
      city: parts.length > 2 ? (parts[1] ?? '') : '',
      province: matched?.[1]?.toUpperCase() ?? '',
      postal: matched?.[2]?.toUpperCase() ?? '',
    };
  }
}
