import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { catchError, debounceTime, distinctUntilChanged, of, startWith, switchMap } from 'rxjs';
import type { JobScheduleSearchOption } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { WorkLabel } from '../../../shared/ui/work-label';
import { PreferredTime } from '../../../shared/ui/preferred-time';
import { JobsApi } from '../../jobs/data-access/jobs-api';
import { ScheduleVisit as ScheduleVisitDialog } from '../../jobs/ui/schedule-visit';

export interface ScheduleSlotData { startAt: Date }

@Component({
  selector: 'app-schedule-slot-dialog',
  imports: [DatePipe, ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatProgressBarModule, WorkLabel, PreferredTime],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ i18n.text('jobScheduleVisit') }}</h2>
    <mat-dialog-content>
      <p>{{ i18n.text('scheduleSlotSelectedTime') }} <strong>{{ data.startAt | date:'mediumDate' : undefined : i18n.language() }} {{ data.startAt | preferredTime }}</strong></p>
      <mat-form-field appearance="outline" class="search-field">
        <mat-label>{{ i18n.text('scheduleFindExistingJob') }}</mat-label>
        <input matInput type="search" [formControl]="query" autocomplete="off" />
      </mat-form-field>
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (error(); as message) { <p role="alert">{{ i18n.text(message) }}</p> }
      @if (!loading() && query.value.trim().length && !jobs().length && !error()) { <p role="status">{{ i18n.text('scheduleNoJobsFound') }}</p> }
      <div class="results" aria-live="polite">
        @for (job of jobs(); track job.id) {
          <button class="job-result" type="button" [disabled]="opening()" (click)="choose(job)">
            <strong>#{{ job.jobNumber }} · {{ job.title }}</strong>
            <span>{{ job.customerName }} · {{ job.propertyName || i18n.text('noAddress') }}</span>
            <small>{{ job.status | workLabel }}</small>
          </button>
        }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button mat-dialog-close>{{ i18n.text('cancel') }}</button></mat-dialog-actions>
  `,
  styles: [`
    :host{display:block;width:min(540px,90vw)}mat-dialog-content{min-height:180px}.search-field{width:100%;margin-top:8px}.results{display:grid;gap:6px;max-height:45vh;overflow:auto}.job-result{display:grid;gap:4px;padding:11px;text-align:left;border:1px solid var(--mat-sys-outline-variant);border-radius:7px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);cursor:pointer}.job-result:hover,.job-result:focus-visible{border-color:var(--mat-sys-primary);background:var(--mat-sys-primary-container);color:var(--mat-sys-on-primary-container)}.job-result span,.job-result small{font-size:12px;color:var(--mat-sys-on-surface-variant)}
  `],
})
export class ScheduleSlotDialog {
  protected readonly data = inject<ScheduleSlotData>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18n);
  private readonly api = inject(JobsApi);
  private readonly dialogs = inject(MatDialog);
  private readonly destroy = inject(DestroyRef);
  protected readonly ref = inject<MatDialogRef<ScheduleSlotDialog, 'scheduled' | undefined>>(MatDialogRef);
  protected readonly query = new FormControl('', { nonNullable: true });
  protected readonly jobs = signal<JobScheduleSearchOption[]>([]);
  protected readonly loading = signal(false);
  protected readonly opening = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);

  constructor() {
    const subscription = this.query.valueChanges.pipe(
      startWith(this.query.value), debounceTime(250), distinctUntilChanged(),
      switchMap((raw) => {
        const query = raw.trim();
        this.error.set(null);
        if (!query) { this.jobs.set([]); this.loading.set(false); return of([] as JobScheduleSearchOption[]); }
        this.loading.set(true);
        return this.api.searchForScheduling(query).pipe(catchError(() => { this.error.set('connectionError'); return of([] as JobScheduleSearchOption[]); }));
      }),
    ).subscribe((jobs) => { this.jobs.set(jobs); this.loading.set(false); });
    this.destroy.onDestroy(() => subscription.unsubscribe());
  }

  protected choose(option: JobScheduleSearchOption): void {
    if (this.opening()) return;
    this.opening.set(true); this.error.set(null);
    this.api.detail(option.id).subscribe({
      next: (job) => {
        this.opening.set(false);
        if (!job.canScheduleVisit) { this.error.set('jobVisitUnavailable'); return; }
        if (!job.propertyId) { this.error.set('jobVisitPropertyRequired'); return; }
        const ref = this.dialogs.open(ScheduleVisitDialog, {
          data: { job, startAt: this.data.startAt }, width: '720px', maxWidth: '94vw', maxHeight: '94vh',
        });
        ref.afterClosed().subscribe((result) => { if (result === 'scheduled') this.ref.close('scheduled'); });
      },
      error: () => { this.opening.set(false); this.error.set('jobVisitUnavailable'); },
    });
  }
}
