import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
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
import { MatSelectModule } from '@angular/material/select';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
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
import { TimeFormatPreference, type TimeFormat } from '../../../core/preferences/time-format';
import { PreferredTime } from '../../../shared/ui/preferred-time';

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
    MatButtonToggleModule,
    PreferredTime,
  ],
  providers: [provideNativeDateAdapter()],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './schedule-visit.html',
  styleUrl: './schedule-visit.scss',
})
export class ScheduleVisit {
  private readonly dialogData = inject<OfficeJobDetails | { job: OfficeJobDetails; startAt: Date }>(MAT_DIALOG_DATA);
  protected readonly job = 'job' in this.dialogData ? this.dialogData.job : this.dialogData;
  protected readonly lockedStartAt = 'job' in this.dialogData ? this.dialogData.startAt : null;
  protected readonly lockedEndAt = this.lockedStartAt ? new Date(this.lockedStartAt.getTime() + 60 * 60_000) : null;
  protected readonly i18n = inject(I18n);
  private readonly auth = inject(AuthSession);
  private readonly api = inject(JobsApi);
  private readonly ref = inject(MatDialogRef<ScheduleVisit, 'scheduled' | 'reload' | undefined>);
  private readonly destroy = inject(DestroyRef);
  private readonly boundary = this.auth.boundary();
  private readonly dateAdapter = inject<DateAdapter<Date>>(DateAdapter);
  private readonly timePreference = inject(TimeFormatPreference);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly timeFormat = this.timePreference.format;
  protected readonly today = new Date(new Date().getFullYear(), new Date().getMonth(), new Date().getDate());
  protected readonly startTimeMinutes = signal<number | null>(null);
  protected readonly endTimeMinutes = signal<number | null>(null);
  protected readonly startTimeText = signal('');
  protected readonly endTimeText = signal('');
  protected readonly timeAttempted = signal(false);
  protected readonly startTimeTouched = signal(false);
  protected readonly endTimeTouched = signal(false);
  protected readonly timeSuggestions = computed(() =>
    Array.from({ length: 96 }, (_, quarter) => this.timePreference.formatMinutes(quarter * 15)),
  );
  protected readonly form = this.fb.group({
    purpose: this.fb.control<'WORK' | 'ASSESSMENT'>('WORK', Validators.required),
    startDate: this.fb.control<Date | null>(null, Validators.required),
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
    if (this.lockedStartAt) {
      this.form.controls.startDate.setValue(new Date(this.lockedStartAt.getFullYear(), this.lockedStartAt.getMonth(), this.lockedStartAt.getDate()));
      this.startTimeMinutes.set(this.lockedStartAt.getHours() * 60 + this.lockedStartAt.getMinutes());
      this.endTimeMinutes.set(this.lockedEndAt!.getHours() * 60 + this.lockedEndAt!.getMinutes());
    }
    effect(() => this.dateAdapter.setLocale(this.i18n.language() === 'fr' ? 'fr-CA' : 'en-CA'));
    effect(() => {
      if (this.auth.boundary() !== this.boundary) this.ref.close();
    });
    effect(() => {
      const format = this.timeFormat();
      const start = this.startTimeMinutes();
      const end = this.endTimeMinutes();
      if (start !== null) this.startTimeText.set(this.timePreference.formatMinutes(start, format));
      if (end !== null) this.endTimeText.set(this.timePreference.formatMinutes(end, format));
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
  protected setTimeFormat(format: TimeFormat): void {
    this.timePreference.setFormat(format);
    if (this.startTimeMinutes() === null) this.startTimeText.set('');
    if (this.endTimeMinutes() === null) this.endTimeText.set('');
    this.conflicts.set([]);
    this.pending = null;
  }
  protected setStartTime(event: Event): void {
    const value = this.normalizeTimeEntry((event.target as HTMLInputElement).value);
    this.setInputValue(event.target as HTMLInputElement, value);
    this.startTimeText.set(value);
    const minutes = this.timePreference.parseMinutes(value);
    this.startTimeMinutes.set(minutes);
    this.clearConflicts();
  }
  protected setEndTime(event: Event): void {
    const value = this.normalizeTimeEntry((event.target as HTMLInputElement).value);
    this.setInputValue(event.target as HTMLInputElement, value);
    this.endTimeText.set(value);
    const minutes = this.timePreference.parseMinutes(value);
    this.endTimeMinutes.set(minutes);
    this.clearConflicts();
  }
  private normalizeTimeEntry(value: string): string {
    const format = this.timeFormat();
    const digits = value.replace(/\D/g, '').slice(0, 4);
    const time = digits.length > 2 ? `${digits.slice(0, 2)}:${digits.slice(2)}` : digits;
    if (format === '24H') return time;
    const letters = value.toUpperCase().replace(/[\d:\s]/g, '').replace(/[^APM]/g, '');
    const period = letters.includes('AM') ? 'AM' : letters.includes('PM') ? 'PM' : letters === 'A' || letters === 'P' ? letters : '';
    return `${time}${period ? ` ${period}` : ''}`;
  }
  private setInputValue(input: HTMLInputElement, value: string): void {
    const cursor = input.selectionStart ?? input.value.length;
    const normalizedCursor = this.normalizeTimeEntry(input.value.slice(0, cursor)).length;
    input.value = value;
    input.setSelectionRange(normalizedCursor, normalizedCursor);
  }
  protected timeError(which: 'start' | 'end'): TranslationKey | null {
    if (!this.timeAttempted() && !(which === 'start' ? this.startTimeTouched() : this.endTimeTouched())) return null;
    const value = which === 'start' ? this.startTimeText() : this.endTimeText();
    return !value.trim() ? 'required' : this.timePreference.parseMinutes(value) === null ? 'jobVisitTimeInvalid' : null;
  }
  private clearConflicts(): void { this.conflicts.set([]); this.pending = null; }
  protected invalidWindow(): boolean {
    if (this.lockedStartAt && this.lockedEndAt) return this.lockedEndAt <= this.lockedStartAt;
    const start = this.timePreference.parseMinutes(this.startTimeText());
    const end = this.timePreference.parseMinutes(this.endTimeText());
    return start !== null && end !== null && end <= start;
  }
  protected pastStart(): boolean {
    if (this.lockedStartAt) return this.lockedStartAt.getTime() <= Date.now();
    const value = this.form.getRawValue();
    const start = this.dateTime(value.startDate, this.timePreference.parseMinutes(this.startTimeText()));
    return !!start && start.getTime() <= Date.now();
  }
  private dateTime(date: Date | null, timeMinutes: number | null): Date | null {
    if (!date || timeMinutes === null) return null;
    const result = new Date(date);
    result.setHours(Math.floor(timeMinutes / 60), timeMinutes % 60, 0, 0);
    return Number.isNaN(result.getTime()) ? null : result;
  }
  protected close(): void {
    if (!this.busy()) this.ref.close(this.blocked() ? 'reload' : undefined);
  }
  protected async save(confirmConflicts = false): Promise<void> {
    if (this.busy() || this.blocked() || this.loading() || this.readError() || !this.current())
      return;
    this.form.markAllAsTouched();
    this.timeAttempted.set(true);
    const value = this.form.getRawValue();
    const start = this.lockedStartAt ?? this.dateTime(value.startDate, this.timePreference.parseMinutes(this.startTimeText()));
    const end = this.lockedEndAt ?? this.dateTime(value.startDate, this.timePreference.parseMinutes(this.endTimeText()));
    if (
      !start ||
      !end ||
      this.form.invalid ||
      this.invalidWindow() ||
      this.pastStart() ||
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
