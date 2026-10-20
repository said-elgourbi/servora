import { BreakpointObserver } from '@angular/cdk/layout';
import { DispatchGrid } from '../ui/dispatch-grid';
import { ScheduleList } from '../ui/schedule-list';
import { chronological, dispatchBoard } from '../ui/dispatch-model';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { timeout } from 'rxjs';
import type { ScheduleResponse } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
import { PageTitle } from '../../../shared/ui/page-title';

export function localDate(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
export function shiftDate(value: string, days: number): string {
  const date = new Date(`${value}T12:00:00`);
  date.setDate(date.getDate() + days);
  return localDate(date);
}
export function validDate(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && localDate(new Date(`${value}T12:00:00`)) === value;
}
@Component({
  selector: 'app-office-schedule',
  imports: [
    DispatchGrid,
    ScheduleList,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatSelectModule,
    PageTitle,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './schedule.html',
  styleUrl: './schedule.scss',
})
export class OfficeSchedule {
  protected readonly emptyOverlaps: ReadonlySet<string> = new Set();
  protected readonly i18n = inject(I18n);
  private readonly auth = inject(AuthSession);
  private readonly http = inject(HttpClient);
  protected readonly date = new FormControl(localDate(new Date()), { nonNullable: true });
  protected readonly members = new FormControl<string[]>([], { nonNullable: true });
  private readonly selectedDate = toSignal(this.date.valueChanges, {
    initialValue: this.date.value,
  });
  private readonly selectedMembers = toSignal(this.members.valueChanges, { initialValue: [] });
  protected readonly data = signal<ScheduleResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly lane = signal<'day' | 'unassigned'>('day');
  protected readonly compact = toSignal(inject(BreakpointObserver).observe('(max-width: 1099px)'), {
    initialValue: { matches: false, breakpoints: {} },
  });
  protected readonly view = signal<'grid' | 'list'>('grid');
  protected readonly fullDay = signal(false);
  private readonly revision = signal(0);
  protected readonly technicians = signal<ScheduleResponse['technicians']>([]);
  protected readonly rows = computed(() =>
    this.lane() === 'day'
      ? chronological(this.data()?.visits ?? [])
      : (this.data()?.unassigned?.items ?? []),
  );
  protected readonly board = computed(() => {
    const data = this.data();
    return data ? dispatchBoard(data, this.selectedMembers(), this.fullDay()) : null;
  });
  protected readonly overdue = computed(
    () => this.data()?.visits.filter((visit) => visit.overdue).length ?? 0,
  );
  constructor() {
    effect((cleanup) => {
      this.revision();
      this.auth.boundary();
      const date = this.selectedDate();
      const membershipId = this.selectedMembers();
      this.data.set(null);
      this.error.set(null);
      this.loading.set(false);
      if (!this.auth.hasPermission('schedule.view_org')) {
        this.technicians.set([]);
        this.error.set('forbiddenDescription');
        return;
      }
      if (!validDate(date)) {
        this.error.set('scheduleInvalidDate');
        return;
      }
      this.loading.set(true);
      const request = this.http
        .get<ScheduleResponse>('/schedule', {
          context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
          params: {
            date,
            timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
            membershipId,
          },
        })
        .pipe(timeout(15000))
        .subscribe({
          next: (data) => {
            this.loading.set(false);
            if (data.scope.kind !== 'ORGANIZATION') {
              this.technicians.set([]);
              this.error.set('forbiddenDescription');
              return;
            }
            this.technicians.set(data.technicians);
            this.data.set(data);
          },
          error: (error: unknown) => {
            this.loading.set(false);
            if (error instanceof HttpErrorResponse && error.status === 403)
              this.technicians.set([]);
            this.error.set(
              error instanceof HttpErrorResponse && error.status === 403
                ? 'forbiddenDescription'
                : 'connectionError',
            );
          },
        });
      cleanup(() => request.unsubscribe());
    });
  }
  protected move(days: number) {
    if (validDate(this.date.value)) this.date.setValue(shiftDate(this.date.value, days));
  }
  protected today() {
    this.date.setValue(localDate(new Date()));
  }
  protected refresh() {
    this.revision.update((value) => value + 1);
  }
}
