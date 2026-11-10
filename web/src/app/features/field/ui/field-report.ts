import {
  ChangeDetectionStrategy,
  computed,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import type { AdHocWorkReport } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { AppUpdates } from '../../../core/updates/app-updates';
import { WorkLabel } from '../../../shared/ui/work-label';
import { AuthSession } from '../../auth/data-access/auth-session';
import { FieldSession, fieldPartition } from '../data-access/field-session';
import { FieldStore } from '../data-access/field-store';
import { FieldQueue } from '../data-access/field-queue';

@Component({
  selector: 'app-field-report',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, ReactiveFormsModule, WorkLabel, DatePipe],
  styleUrl: './field-workflow.scss',
  template: `
    <a routerLink="/" [queryParams]="{ view: 'field' }">{{ i18n.text('backToDay') }}</a>
    @if (error()) {
      <p role="alert">{{ i18n.text(error()!) }}</p>
    }
    @if (ready() && session.context() && auth.hasPermission('visits.report_ad_hoc_work')) {
      <section class="panel report-history" aria-labelledby="my-reports-title">
        <header>
          <div>
            <h1 id="my-reports-title">{{ i18n.text('fieldMyReports') }}</h1>
            <p>{{ i18n.text('fieldMyReportsHint') }}</p>
          </div>
          <button type="button" class="report-new-button" (click)="reportDialog.showModal()">
            {{ i18n.text('fieldNewReport') }}
          </button>
        </header>
        <div class="report-filters" role="search" [attr.aria-label]="i18n.text('fieldReportFilters')">
          <label class="report-search">
            <span>{{ i18n.text('fieldReportSearch') }}</span>
            <input type="search" [value]="reportSearch()" (input)="reportSearch.set($any($event.target).value)" [placeholder]="i18n.text('fieldReportSearchHint')" />
          </label>
          <label class="report-date-filter">
            <span>{{ i18n.text('fieldReportDateRange') }}</span>
            <select [value]="reportDateRange()" (change)="reportDateRange.set($any($event.target).value)">
              <option value="all">{{ i18n.text('fieldReportAnyTime') }}</option>
              <option value="30">{{ i18n.text('fieldReportLast30Days') }}</option>
              <option value="90">{{ i18n.text('fieldReportLast90Days') }}</option>
              <option value="365">{{ i18n.text('fieldReportLastYear') }}</option>
            </select>
          </label>
          <div class="report-status-filters" role="group" [attr.aria-label]="i18n.text('fieldReportFilterByStatus')">
            <button type="button" [class.active]="reportStatusFilter() === 'ALL'" (click)="reportStatusFilter.set('ALL')">{{ i18n.text('fieldFilterAll') }} <span>{{ ownReports().length }}</span></button>
            <button type="button" [class.active]="reportStatusFilter() === 'PENDING'" (click)="reportStatusFilter.set('PENDING')">{{ i18n.text('reviewPending') }} <span>{{ reportStatusCount('PENDING') }}</span></button>
            <button type="button" [class.active]="reportStatusFilter() === 'LINKED'" (click)="reportStatusFilter.set('LINKED')">{{ i18n.text('reviewLinkedStatus') }} <span>{{ reportStatusCount('LINKED') }}</span></button>
            <button type="button" [class.active]="reportStatusFilter() === 'CONVERTED'" (click)="reportStatusFilter.set('CONVERTED')">{{ i18n.text('reviewConvertedStatus') }} <span>{{ reportStatusCount('CONVERTED') }}</span></button>
            <button type="button" [class.active]="reportStatusFilter() === 'REJECTED'" (click)="reportStatusFilter.set('REJECTED')">{{ i18n.text('reviewRejectedStatus') }} <span>{{ reportStatusCount('REJECTED') }}</span></button>
          </div>
          @if (hasReportFilters()) { <button type="button" class="report-clear-filters" (click)="clearReportFilters()">{{ i18n.text('fieldClearFilters') }}</button> }
        </div>
        <div class="report-table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{{ i18n.text('fieldReportSummary') }}</th>
                <th scope="col">{{ i18n.text('fieldReportLocation') }}</th>
                <th scope="col">{{ i18n.text('fieldSubmitted') }}</th>
                <th scope="col">{{ i18n.text('fieldReportStatus') }}</th>
                <th scope="col">{{ i18n.text('fieldJob') }}</th>
              </tr>
            </thead>
            <tbody>
              @for (report of filteredReports(); track report.id) {
                <tr>
                  <td [attr.data-label]="i18n.text('fieldReportSummary')">
                    <strong>{{ report.summary }}</strong>
                    <small>{{ formatRange(report) }} · {{ report.outcomeCode | workLabel }}</small>
                    @if (report.reviewNote) {
                      <small>{{ i18n.text('fieldOfficeDecision') }}: {{ report.reviewNote }}</small>
                    }
                  </td>
                  <td [attr.data-label]="i18n.text('fieldReportLocation')">
                    {{ reportLocation(report) || i18n.text('reviewLocationUnknown') }}
                  </td>
                  <td [attr.data-label]="i18n.text('fieldSubmitted')">
                    {{ report.createdAt | date:'mediumDate':undefined:i18n.language() }}
                  </td>
                  <td [attr.data-label]="i18n.text('fieldReportStatus')">
                    <span class="report-status" [class.pending]="report.status === 'PENDING'" [class.linked]="report.status === 'LINKED'" [class.converted]="report.status === 'CONVERTED'" [class.rejected]="report.status === 'REJECTED'">{{ i18n.text(reportStatusKey(report.status)) }}</span>
                  </td>
                  <td [attr.data-label]="i18n.text('fieldJob')">
                    @if (resultJobId(report)) {
                      <a [routerLink]="['/work/jobs', resultJobId(report)]" [queryParams]="{ view: 'field' }">
                        {{ i18n.text('fieldOpenJob') }}
                        @if (resultJobNumber(report)) { <span>#{{ resultJobNumber(report) }}</span> }
                      </a>
                    } @else { <span class="report-no-job">—</span> }
                  </td>
                </tr>
              } @empty {
                <tr class="report-empty-row">
                  <td colspan="5">{{ ownReports().length ? i18n.text('fieldNoMatchingReports') : i18n.text('fieldNoMyReports') }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <div class="report-table-actions">
          <button type="button" (click)="refreshReports()">{{ i18n.text('refresh') }}</button>
        </div>
      </section>
      <dialog #reportDialog class="report-dialog" aria-labelledby="new-report-title">
        <div class="report-dialog-header">
          <h2 id="new-report-title">{{ i18n.text('fieldAdHoc') }}</h2>
          <button type="button" class="report-dialog-close" (click)="reportDialog.close()" [attr.aria-label]="i18n.text('fieldCloseDialog')">×</button>
        </div>
        <form [formGroup]="form" (ngSubmit)="submit(reportDialog)" class="report-form">
          <label for="report-summary">{{ i18n.text('fieldReportSummary') }}</label>
          <textarea id="report-summary" formControlName="summary" maxlength="2000" required></textarea>
          <label for="report-outcome">{{ i18n.text('fieldOutcome') }}</label>
          <select id="report-outcome" formControlName="outcomeCode" required>
            <option value="">{{ i18n.text('fieldChooseOutcome') }}</option>
            @for (outcome of outcomes; track outcome) {
              <option [value]="outcome">{{ outcome | workLabel }}</option>
            }
          </select>
          <div class="report-times">
            <div class="report-time-field">
              <label for="report-start">{{ i18n.text('fieldStart') }}</label>
              <input id="report-start" type="datetime-local" formControlName="workStartedAt" required />
              <button type="button" class="report-time-shortcut" (click)="setTimeNow('workStartedAt')">{{ i18n.text('fieldSetNow') }}</button>
            </div>
            <div class="report-time-field">
              <label for="report-end">{{ i18n.text('fieldEnd') }}</label>
              <input id="report-end" type="datetime-local" formControlName="workEndedAt" required />
              <button type="button" class="report-time-shortcut" (click)="setTimeNow('workEndedAt')">{{ i18n.text('fieldSetNow') }}</button>
            </div>
          </div>
          <details class="report-customer-details">
            <summary>{{ i18n.text('fieldAddCustomerDetails') }}</summary>
            <label for="report-customer">{{ i18n.text('fieldCustomerName') }}</label>
            <input id="report-customer" formControlName="reportedCustomerName" />
            <label for="report-address">{{ i18n.text('fieldCustomerAddress') }}</label>
            <textarea id="report-address" formControlName="reportedCustomerAddress"></textarea>
            <label for="report-phone">{{ i18n.text('fieldCustomerPhone') }}</label>
            <input id="report-phone" formControlName="reportedCustomerPhone" type="tel" />
          </details>
          <p role="status" class="report-draft-status">{{ i18n.text(saved() ? 'fieldSaved' : 'fieldDraft') }}</p>
          <div class="actions">
            <button type="submit" class="report-submit" [disabled]="form.invalid || busy()">
              {{ i18n.text('fieldSubmitReport') }}
            </button>
            <button type="button" (click)="discard()" [disabled]="busy()">
              {{ i18n.text('fieldDiscard') }}
            </button>
          </div>
        </form>
      </dialog>
    }
  `,
})
export class FieldReport {
  protected readonly i18n = inject(I18n);
  protected readonly session = inject(FieldSession);
  protected readonly auth = inject(AuthSession);
  private readonly http = inject(HttpClient);
  private readonly store = inject(FieldStore);
  protected readonly queue = inject(FieldQueue);
  private readonly updates = inject(AppUpdates);
  protected readonly error = signal<
    'fieldStorageFailure' | 'fieldContextUnavailable' | 'fieldRequestFailed' | null
  >(null);
  protected readonly busy = signal(false);
  protected readonly ready = signal(false);
  protected readonly saved = signal(false);
  protected readonly ownReports = signal<AdHocWorkReport[]>([]);
  protected readonly reportSearch = signal('');
  protected readonly reportStatusFilter = signal<'ALL' | 'PENDING' | 'LINKED' | 'CONVERTED' | 'REJECTED'>('ALL');
  protected readonly reportDateRange = signal<'all' | '30' | '90' | '365'>('all');
  protected readonly filteredReports = computed(() => {
    const query = this.reportSearch().trim().toLocaleLowerCase();
    const status = this.reportStatusFilter();
    const days = this.reportDateRange() === 'all' ? 0 : Number(this.reportDateRange());
    const cutoff = days ? Date.now() - days * 24 * 60 * 60 * 1000 : 0;
    return this.ownReports()
      .filter((report) => status === 'ALL' || report.status === status)
      .filter((report) => !cutoff || new Date(report.createdAt).getTime() >= cutoff)
      .filter((report) => {
        if (!query) return true;
        const searchable = [report.summary, this.reportLocation(report), report.reviewNote,
          this.resultJobNumber(report)?.toString(), report.outcomeCode,
          this.i18n.text(this.reportStatusKey(report.status))]
          .filter(Boolean).join(' ').toLocaleLowerCase();
        return searchable.includes(query);
      })
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  });
  protected readonly hasReportFilters = computed(() =>
    this.reportSearch().trim().length > 0 || this.reportStatusFilter() !== 'ALL' || this.reportDateRange() !== 'all');
  private restoring = false;
  protected readonly outcomes = [
    'RESOLVED',
    'NEEDS_FOLLOW_UP',
    'NEEDS_PARTS',
    'UNABLE_TO_COMPLETE',
  ];
  protected readonly form = new FormGroup({
    reportedCustomerName: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(255)] }),
    reportedCustomerPhone: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(50)],
    }),
    reportedCustomerAddress: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(2000)],
    }),
    workStartedAt: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    workEndedAt: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    outcomeCode: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    summary: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(2000)],
    }),
  });
  protected setTimeNow(field: 'workStartedAt' | 'workEndedAt'): void {
    const now = new Date();
    const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
      .toISOString()
      .slice(0, 16);
    this.form.controls[field].setValue(local);
  }
  constructor() {
    void this.restore();
    const changes = this.form.valueChanges.subscribe(() => {
      if (!this.restoring) {
        this.saved.set(false);
        void this.save().catch(() => this.error.set('fieldStorageFailure'));
      }
    });
    effect(() => {
      const scope = this.session.context();
      if (!scope) {
        this.form.reset({}, { emitEvent: false });
      }
    });
    const unload = (event: BeforeUnloadEvent) => {
      if (!this.saved() && this.form.dirty) event.preventDefault();
    };
    window.addEventListener('beforeunload', unload);
    const unregister = this.updates.beforeReload(() => this.save());
    inject(DestroyRef).onDestroy(() => {
      changes.unsubscribe();
      unregister();
      window.removeEventListener('beforeunload', unload);
    });
  }
  private async restore(): Promise<void> {
    try {
      const scope = this.session.context() ?? (await this.session.verify());
      const rows = await this.store.records(scope);
      try { this.ownReports.set(await firstValueFrom(this.http.get<AdHocWorkReport[]>('/jobs/ad-hoc-work-reports', this.session.options(scope)))); }
      catch { this.error.set('fieldRequestFailed'); }
      const draft = rows.find((row) => row.kind === 'report-draft');
      if (draft?.kind === 'report-draft') {
        this.restoring = true;
        this.form.patchValue(draft.data);
        this.restoring = false;
        this.saved.set(true);
      }
    } catch {
      this.error.set('fieldContextUnavailable');
    } finally {
      this.ready.set(true);
    }
  }
  protected reportStatusKey(status: string): 'fieldPending' | 'reviewLinkedStatus' | 'reviewConvertedStatus' | 'reviewRejectedStatus' {
    return status === 'LINKED' ? 'reviewLinkedStatus' : status === 'CONVERTED' ? 'reviewConvertedStatus' : status === 'REJECTED' ? 'reviewRejectedStatus' : 'fieldPending';
  }
  protected reportStatusCount(status: string): number {
    return this.ownReports().filter((report) => report.status === status).length;
  }
  protected clearReportFilters(): void {
    this.reportSearch.set('');
    this.reportStatusFilter.set('ALL');
    this.reportDateRange.set('all');
  }
  protected reportLocation(report: AdHocWorkReport): string {
    return [report.customerName ?? report.reportedCustomerName, report.propertyAddress?.addressLine1 ?? report.reportedCustomerAddress, report.propertyAddress?.city].filter(Boolean).join(' · ');
  }
  protected resultJobId(report: AdHocWorkReport): string | null {
    return report.createdJobId ?? report.knownJobId;
  }
  protected resultJobNumber(report: AdHocWorkReport): number | null {
    return report.createdJobNumber ?? report.knownJobNumber;
  }
  protected formatRange(report: AdHocWorkReport): string {
    const locale = this.i18n.language() === 'fr' ? 'fr-CA' : 'en-CA';
    const start = new Date(report.workStartedAt);
    const end = new Date(report.workEndedAt);
    return `${start.toLocaleDateString(locale, { dateStyle: 'short' })} · ${start.toLocaleTimeString(locale, { timeStyle: 'short' })}–${end.toLocaleTimeString(locale, { timeStyle: 'short' })}`;
  }
  protected async refreshReports(): Promise<void> {
    try {
      const scope = this.session.context() ?? (await this.session.verify());
      this.ownReports.set(await firstValueFrom(this.http.get<AdHocWorkReport[]>('/jobs/ad-hoc-work-reports', this.session.options(scope))));
    }
    catch { this.error.set('fieldRequestFailed'); }
  }
  private async save(): Promise<void> {
    const scope = this.session.context();
    if (!Object.values(this.form.getRawValue()).some(Boolean)) return;
    if (!scope) throw new Error('FIELD_CONTEXT_UNAVAILABLE');
    const data = this.form.getRawValue();
    await this.store.write(scope, [
      {
        key: this.store.key(scope, 'report-draft/current'),
        kind: 'report-draft',
        partition: fieldPartition(scope),
        jobId: '',
        savedAt: Date.now(),
        data,
      },
    ]);
    this.saved.set(JSON.stringify(data) === JSON.stringify(this.form.getRawValue()));
  }
  protected async submit(dialog: HTMLDialogElement): Promise<void> {
    if (this.busy() || this.form.invalid) return;
    this.busy.set(true);
    try {
      const value = this.form.getRawValue();
      const start = new Date(value.workStartedAt);
      const end = new Date(value.workEndedAt);
      if (end <= start || !this.outcomes.includes(value.outcomeCode))
        throw new Error('INVALID_REPORT');
      await this.queue.submit('', '', 'ad-hoc', {
        ...value,
        workStartedAt: start.toISOString(),
        workEndedAt: end.toISOString(),
      });
      const scope = this.session.context();
      if (scope) {
        try {
          await this.store.write(scope, [], [this.store.key(scope, 'report-draft/current')]);
        } catch {
          this.error.set('fieldStorageFailure');
        }
        try {
          this.ownReports.set(
            await firstValueFrom(
              this.http.get<AdHocWorkReport[]>(
                '/jobs/ad-hoc-work-reports',
                this.session.options(scope),
              ),
            ),
          );
        } catch {
          // The accepted outbox receipt remains visible above.
        }
      }
      this.form.reset({}, { emitEvent: false });
      this.saved.set(false);
      dialog.close();
    } catch {
      this.error.set('fieldRequestFailed');
    } finally {
      this.busy.set(false);
    }
  }
  protected async discard(): Promise<void> {
    const scope = this.session.context();
    if (!scope || !window.confirm(this.i18n.text('fieldDiscardConfirm'))) return;
    try {
      await this.store.write(scope, [], [this.store.key(scope, 'report-draft/current')]);
      this.form.reset({}, { emitEvent: false });
      this.saved.set(false);
    } catch {
      this.error.set('fieldStorageFailure');
    }
  }
}
