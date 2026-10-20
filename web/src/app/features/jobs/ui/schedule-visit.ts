import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { DateAdapter, provideNativeDateAdapter } from '@angular/material/core';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatTimepickerModule } from '@angular/material/timepicker';
import { MatSelectModule } from '@angular/material/select';
import { firstValueFrom } from 'rxjs';
import type {
  AssignableTechnician,
  OfficeJobDetails,
  ScheduleVisitRequest,
  VisitScheduleConflict,
} from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { JobsApi } from '../data-access/jobs-api';
import { jobReadError, jobWriteError } from '../data-access/job-errors';

@Component({
  selector: 'app-schedule-visit',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDatepickerModule,
    MatTimepickerModule,
  ],
  providers: [provideNativeDateAdapter()],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './schedule-visit.html',
  styleUrl: './schedule-visit.scss',
})
export class ScheduleVisit {
  protected readonly job = inject<OfficeJobDetails>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18n);
  private readonly auth = inject(AuthSession);
  private readonly api = inject(JobsApi);
  private readonly ref = inject(MatDialogRef<ScheduleVisit, 'scheduled' | 'reload' | undefined>);
  private readonly destroy = inject(DestroyRef);
  private readonly boundary = this.auth.boundary();
  private readonly dateAdapter = inject<DateAdapter<Date>>(DateAdapter);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    purpose: this.fb.control<'WORK' | 'ASSESSMENT'>('WORK', Validators.required),
    startDate: this.fb.control<Date | null>(null, Validators.required),
    startTime: this.fb.control<Date | null>(null, Validators.required),
    endDate: this.fb.control<Date | null>(null, Validators.required),
    endTime: this.fb.control<Date | null>(null, Validators.required),
    crew: this.fb.control<string[]>([], Validators.required),
    lead: ['', Validators.required],
  });
  protected readonly technicians = signal<AssignableTechnician[]>([]);
  protected readonly loading = signal(false);
  protected readonly readError = signal<TranslationKey | null>(null);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly busy = signal(false);
  protected readonly blocked = signal(false);
  protected readonly conflicts = signal<VisitScheduleConflict[]>([]);
  private pending: ScheduleVisitRequest | null = null;
  constructor() {
    this.ref.disableClose = true;
    effect(() => this.dateAdapter.setLocale(this.i18n.language() === 'fr' ? 'fr-CA' : 'en-CA'));
    effect(() => {
      if (this.auth.boundary() !== this.boundary) this.ref.close();
    });
    const subscription = this.form.valueChanges.subscribe(() => {
      this.conflicts.set([]);
      this.pending = null;
    });
    this.destroy.onDestroy(() => subscription.unsubscribe());
    void this.loadTechnicians();
  }
  private current(): boolean {
    return !this.destroy.destroyed && this.boundary === this.auth.boundary();
  }
  protected async loadTechnicians(): Promise<void> {
    if (this.loading() || !this.current()) return;
    this.loading.set(true);
    this.readError.set(null);
    try {
      const technicians = await firstValueFrom(this.api.technicians());
      if (this.current()) this.technicians.set(technicians);
    } catch (error) {
      if (this.current()) this.readError.set(jobReadError(error));
    } finally {
      if (this.current()) this.loading.set(false);
    }
  }
  protected crew(): AssignableTechnician[] {
    return this.technicians().filter((member) =>
      this.form.controls.crew.value.includes(member.membershipId),
    );
  }
  protected invalidWindow(): boolean {
    const { startDate, startTime, endDate, endTime } = this.form.getRawValue();
    const start = this.dateTime(startDate, startTime);
    const end = this.dateTime(endDate, endTime);
    return !!start && !!end && end.getTime() <= start.getTime();
  }
  private dateTime(date: Date | null, time: Date | null): Date | null {
    if (!date || !time) return null;
    const result = new Date(date);
    result.setHours(time.getHours(), time.getMinutes(), 0, 0);
    return Number.isNaN(result.getTime()) ? null : result;
  }
  protected close(): void {
    if (!this.busy()) this.ref.close(this.blocked() ? 'reload' : undefined);
  }
  protected async save(confirmConflicts = false): Promise<void> {
    if (this.busy() || this.blocked() || this.loading() || this.readError() || !this.current())
      return;
    this.form.markAllAsTouched();
    const value = this.form.getRawValue();
    const start = this.dateTime(value.startDate, value.startTime);
    const end = this.dateTime(value.endDate, value.endTime);
    if (
      !start ||
      !end ||
      this.form.invalid ||
      this.invalidWindow() ||
      !value.crew.includes(value.lead) ||
      value.crew.some((id) => !this.technicians().some((member) => member.membershipId === id))
    )
      return;
    if (confirmConflicts && !this.pending) return;
    const request: ScheduleVisitRequest =
      confirmConflicts && this.pending
        ? { ...this.pending, confirmConflicts: true }
        : {
            purposeCode: value.purpose,
            scheduledStart: start.toISOString(),
            scheduledEnd: end.toISOString(),
            technicians: value.crew.map((membershipId) => ({
              membershipId,
              roleCode: membershipId === value.lead ? 'LEAD' : 'TECHNICIAN',
            })),
            confirmConflicts: false,
          };
    this.busy.set(true);
    this.form.disable({ emitEvent: false });
    this.error.set(null);
    try {
      await firstValueFrom(this.api.schedule(this.job.id, request));
      if (this.current()) this.ref.close('scheduled');
    } catch (error) {
      if (!this.current()) return;
      if (
        error instanceof HttpErrorResponse &&
        error.error?.code === 'SCHEDULE_CONFLICT' &&
        Array.isArray(error.error?.details?.conflicts) &&
        error.error.details.conflicts.length
      ) {
        this.pending = { ...request, confirmConflicts: false };
        this.conflicts.set(error.error.details.conflicts);
      } else {
        const message = jobWriteError(error);
        this.error.set(message);
        this.blocked.set(message !== 'customerValidationFailed');
      }
    } finally {
      if (this.current()) {
        this.busy.set(false);
        this.form.enable({ emitEvent: false });
      }
    }
  }
}
