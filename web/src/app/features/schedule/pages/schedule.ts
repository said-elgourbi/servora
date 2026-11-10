import { BreakpointObserver } from '@angular/cdk/layout';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { forkJoin, timeout } from 'rxjs';
import type { ScheduleResponse, ScheduleVisit } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { TimeFormatPreference, formatTimeInstant } from '../../../core/preferences/time-format';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
import { PageTitle } from '../../../shared/ui/page-title';
import { WorkLabel } from '../../../shared/ui/work-label';
import { ScheduleSlotDialog } from '../ui/schedule-slot-dialog';

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
function weekStart(value: string): string {
  const d = new Date(`${value}T12:00:00`);
  return shiftDate(value, -((d.getDay() + 6) % 7));
}
function dateRange(view: string, date: string): string[] {
  if (view === 'day') return [date];
  if (view === 'week') return Array.from({ length: 7 }, (_, i) => shiftDate(weekStart(date), i));
  const d = new Date(`${date.slice(0, 7)}-01T12:00:00`);
  const first = shiftDate(localDate(d), -((d.getDay() + 6) % 7));
  return Array.from({ length: 42 }, (_, i) => shiftDate(first, i));
}
function timeMinutes(iso: string, zone: string): number {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(iso));
  return Number(parts.find((p) => p.type === 'hour')?.value ?? 0) * 60 + Number(parts.find((p) => p.type === 'minute')?.value ?? 0);
}
function dateInZone(iso: string, zone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(iso));
  return `${parts.find((p) => p.type === 'year')?.value}-${parts.find((p) => p.type === 'month')?.value}-${parts.find((p) => p.type === 'day')?.value}`;
}
@Component({
  selector: 'app-office-schedule',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatSelectModule, MatDialogModule, RouterLink, PageTitle, WorkLabel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './schedule.html',
  styleUrl: './schedule.scss',
})
export class OfficeSchedule {
  protected readonly i18n = inject(I18n);
  protected readonly isOffice = computed(() => this.auth.hasPermission('schedule.view_org'));
  private readonly timePreference = inject(TimeFormatPreference);
  private readonly auth = inject(AuthSession);
  private readonly http = inject(HttpClient);
  private readonly dialogs = inject(MatDialog);
  protected readonly date = new FormControl(localDate(new Date()), { nonNullable: true });
  protected readonly members = new FormControl<string[]>(this.readMembers(), { nonNullable: true });
  protected readonly search = new FormControl(this.readPreference('search', ''), { nonNullable: true });
  protected readonly technicianSearch = new FormControl('', { nonNullable: true });
  protected readonly purpose = new FormControl(this.readPreference('purpose', ''), { nonNullable: true });
  protected readonly status = new FormControl(this.readPreference('status', ''), { nonNullable: true });
  protected readonly selectedDate = toSignal(this.date.valueChanges, { initialValue: this.date.value });
  protected readonly selectedMembers = toSignal(this.members.valueChanges, { initialValue: this.members.value });
  protected readonly selectedSearch = toSignal(this.search.valueChanges, { initialValue: this.search.value });
  protected readonly selectedTechnicianSearch = toSignal(this.technicianSearch.valueChanges, { initialValue: '' });
  protected readonly selectedPurpose = toSignal(this.purpose.valueChanges, { initialValue: this.purpose.value });
  protected readonly selectedStatus = toSignal(this.status.valueChanges, { initialValue: this.status.value });
  protected readonly data = signal<ScheduleResponse[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly view = signal<'day' | 'week' | 'month'>(this.readPreference('view', 'week'));
  protected readonly showWeekends = signal<boolean>(this.readPreference<boolean>('weekends', true));
  protected readonly fullDay = signal(false);
  protected readonly groupByTech = signal(false);
  protected readonly selected = signal<ScheduleVisit | null>(null);
  protected readonly queueOpen = signal(false);
  protected readonly compact = toSignal(inject(BreakpointObserver).observe('(max-width: 800px)'), { initialValue: { matches: false, breakpoints: {} } });
  private readonly revision = signal(0);
  protected readonly technicians = signal<ScheduleResponse['technicians']>([]);
  protected readonly visibleTechnicians = computed(() => {
    const query = this.selectedTechnicianSearch().trim().toLocaleLowerCase();
    return this.technicians().filter((tech) => (tech.name ?? '').toLocaleLowerCase().includes(query));
  });
  protected readonly dates = computed(() => dateRange(this.view(), this.selectedDate()).filter((d) => this.showWeekends() || this.view() !== 'week' || ![0, 6].includes(new Date(`${d}T12:00:00`).getDay())));
  protected readonly calendarColumns = computed(() => {
    const dates = this.dates();
    if (this.isOffice() && this.view() === 'day' && this.groupByTech()) {
      const day = dates[0];
      if (!day) return [];
      const selected = this.selectedMembers();
      const assigned = this.technicians().filter((tech) => !selected.length || selected.includes(tech.membershipId)).map((tech) => ({ key: tech.membershipId, date: day, label: tech.name || this.i18n.text('unnamedMember'), membershipId: tech.membershipId }));
      if (this.visitsOn(day).some((visit) => visit.technicians.length === 0)) assigned.push({ key: 'unassigned', date: day, label: this.i18n.text('noCrew'), membershipId: '' });
      return assigned;
    }
    return dates.map((date) => ({ key: date, date, label: this.dayLabel(date), membershipId: null as string | null }));
  });
  protected readonly visits = computed(() => {
    const seen = new Map<string, ScheduleVisit>();
    for (const result of this.data()) for (const visit of result.visits) seen.set(visit.visitId, visit);
    const needle = this.selectedSearch().trim().toLocaleLowerCase();
    return [...seen.values()].filter((v) => (!this.isOffice() || !this.selectedMembers().length || v.technicians.some((t) => this.selectedMembers().includes(t.membershipId))) && (!needle || `${v.jobNumber} ${v.jobTitle} ${v.customerName}`.toLocaleLowerCase().includes(needle)) && (!this.selectedPurpose() || v.purposeCode === this.selectedPurpose()) && (!this.selectedStatus() || v.visitStatus === this.selectedStatus()));
  });
  protected readonly unassigned = computed(() => this.data()[0]?.unassigned ?? null);
  protected readonly zone = computed(() => this.data()[0]?.day.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC');
  protected readonly rangeLabel = computed(() => {
    const dates = this.dates();
    if (!dates.length) return '';
    const fmt = new Intl.DateTimeFormat(this.i18n.language(), { timeZone: this.zone(), month: 'short', day: 'numeric', year: 'numeric' });
    return dates.length === 1 ? fmt.format(new Date(`${dates[0]}T12:00:00`)) : `${fmt.format(new Date(`${dates[0]}T12:00:00`))} – ${fmt.format(new Date(`${dates.at(-1)}T12:00:00`))}`;
  });
  protected readonly slots = computed(() => {
    const start = this.fullDay() ? 0 : 6 * 60;
    const end = this.fullDay() ? 1440 : 22 * 60;
    return Array.from({ length: (end - start) / 60 }, (_, i) => start + i * 60);
  });
  constructor() {
    effect((cleanup) => {
      this.revision(); this.auth.boundary();
      const dates = this.dates();
      this.error.set(null);
      const officeScope = this.auth.hasPermission('schedule.view_org');
      if (!officeScope && !this.auth.hasPermission('VISIT_VIEW_ASSIGNED')) { this.technicians.set([]); this.error.set('forbiddenDescription'); return; }
      if (dates.some((d) => !validDate(d))) { this.error.set('scheduleInvalidDate'); return; }
      this.loading.set(true);
      const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
      const calls = dates.map((date) => this.http.get<ScheduleResponse>('/schedule', { context: new HttpContext().set(AUTHENTICATED_REQUEST, true), params: { date, timeZone: zone } }).pipe(timeout(15000)));
      const expectedScope = officeScope ? 'ORGANIZATION' : 'SELF';
      const sub = forkJoin(calls).subscribe({ next: (result) => { this.loading.set(false); if (result.some((r) => r.scope.kind !== expectedScope)) { this.error.set('forbiddenDescription'); return; } this.data.set(result); this.technicians.set(result[0]?.technicians ?? []); }, error: (e: unknown) => { this.loading.set(false); this.error.set(e instanceof HttpErrorResponse && e.status === 403 ? 'forbiddenDescription' : 'connectionError'); } });
      cleanup(() => sub.unsubscribe());
    });
    effect(() => { try { localStorage.setItem('servora.schedule.view', this.view()); localStorage.setItem('servora.schedule.weekends', String(this.showWeekends())); localStorage.setItem('servora.schedule.purpose', this.selectedPurpose()); localStorage.setItem('servora.schedule.status', this.selectedStatus()); localStorage.setItem('servora.schedule.search', this.selectedSearch()); localStorage.setItem('servora.schedule.members', JSON.stringify(this.selectedMembers())); } catch { /* Preferences are optional when storage is unavailable. */ } });
  }
  private readPreference<T extends string | boolean>(key: string, fallback: T): T {
    try { const value = localStorage.getItem(`servora.schedule.${key}`); if (typeof fallback === 'boolean') return (value === null ? fallback : value === 'true') as T; return (value as T) ?? fallback; } catch { return fallback; }
  }
  private readMembers(): string[] {
    try { const value: unknown = JSON.parse(localStorage.getItem('servora.schedule.members') ?? '[]'); return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []; } catch { return []; }
  }
  protected navigate(direction: number) { const step = this.view() === 'day' ? 1 : this.view() === 'week' ? 7 : 28; this.date.setValue(shiftDate(this.date.value, step * direction)); }
  protected today() { this.date.setValue(localDate(new Date())); }
  protected refresh() { this.revision.update((n) => n + 1); }
  protected setView(view: 'day' | 'week' | 'month') { this.view.set(view); }
  protected visitFor(date: string, visit: ScheduleVisit): boolean { return !!visit.scheduledStart && dateInZone(visit.scheduledStart, this.zone()) === date; }
  protected style(visit: ScheduleVisit, membershipId: string | null = null) {
    const day = dateInZone(visit.scheduledStart!, this.zone());
    const peers = this.visitsInColumn({ date: day, membershipId: membershipId ?? null }).filter((other) => other.scheduledStart && other.scheduledEnd).sort((a, b) => Date.parse(a.scheduledStart!) - Date.parse(b.scheduledStart!));
    let cluster: ScheduleVisit[] = [], edge = -Infinity;
    for (const peer of peers) {
      const start = Date.parse(peer.scheduledStart!), end = Date.parse(peer.scheduledEnd!);
      if (start >= edge) { if (cluster.some((v) => v.visitId === visit.visitId)) break; cluster = []; edge = -Infinity; }
      cluster.push(peer); edge = Math.max(edge, end);
    }
    if (!cluster.some((v) => v.visitId === visit.visitId)) cluster = [visit];
    const ends: number[] = []; const laneById = new Map<string, number>();
    for (const peer of cluster) { const start = Date.parse(peer.scheduledStart!); let lane = ends.findIndex((end) => end <= start); if (lane < 0) lane = ends.length; ends[lane] = Date.parse(peer.scheduledEnd!); laneById.set(peer.visitId, lane); }
    const lanes = Math.max(1, ends.length), lane = laneById.get(visit.visitId) ?? 0;
    const start = timeMinutes(visit.scheduledStart!, this.zone()); const end = timeMinutes(visit.scheduledEnd!, this.zone()); const from = this.fullDay() ? 0 : 360;
    return { top: `${Math.max(0, start - from) * 1.1}px`, height: `${Math.max(26, (end - start) * 1.1)}px`, left: `calc(${lane * 100 / lanes}% + 3px)`, width: `calc(${100 / lanes}% - 6px)` };
  }
  protected isShort(visit: ScheduleVisit) { return !!visit.scheduledStart && !!visit.scheduledEnd && Date.parse(visit.scheduledEnd) - Date.parse(visit.scheduledStart) < 45 * 60000; }
  protected overlapPeak(date: string) {
    const events = this.visitsOn(date).flatMap((visit) => visit.scheduledStart && visit.scheduledEnd ? [{ at: Date.parse(visit.scheduledStart), delta: 1 }, { at: Date.parse(visit.scheduledEnd), delta: -1 }] : []).sort((a, b) => a.at - b.at || a.delta - b.delta);
    let active = 0, peak = 0;
    for (const event of events) { active += event.delta; peak = Math.max(peak, active); }
    return peak;
  }
  protected visitTime(visit: ScheduleVisit) {
    const zone = this.zone();
    const locale = this.i18n.language() === 'fr' ? 'fr-CA' : 'en-CA';
    const format = this.timePreference.format();
    return visit.scheduledStart && visit.scheduledEnd
      ? `${formatTimeInstant(visit.scheduledStart, locale, zone, format)}–${formatTimeInstant(visit.scheduledEnd, locale, zone, format)}`
      : '';
  }
  protected dayLabel(date: string) { return new Intl.DateTimeFormat(this.i18n.language(), { timeZone: this.zone(), weekday: 'short', day: 'numeric' }).format(new Date(`${date}T12:00:00`)); }
  protected weekdayLabels() { const monday = new Date(2024, 0, 1); return Array.from({ length: 7 }, (_, i) => { const d = new Date(monday); d.setDate(d.getDate() + i); return new Intl.DateTimeFormat(this.i18n.language(), { weekday: 'short' }).format(d); }); }
  protected isToday(date: string) { return date === localDate(new Date()); }
  protected isPastDate(date: string) { return date < localDate(new Date()); }
  protected visitsOn(date: string) { return this.visits().filter((v) => this.visitFor(date, v)); }
  protected visitsInColumn(column: { date: string; membershipId: string | null }) {
    if (column.membershipId === null) return this.visitsOn(column.date);
    return this.visitsOn(column.date).filter((visit) => !column.membershipId ? visit.technicians.length === 0 : visit.technicians.some((tech) => tech.membershipId === column.membershipId));
  }
  protected open(visit: ScheduleVisit) { this.selected.set(visit); }
  protected openQueueVisit(visit: ScheduleVisit) { this.queueOpen.set(false); this.open(visit); }
  protected close() { this.selected.set(null); }
  protected openDate(date: string) { this.date.setValue(date); this.setView('day'); }
  protected moveTo(date: string) { this.date.setValue(date); }
  protected hourLabel(minute: number) { const d = new Date(2000, 0, 1, Math.floor(minute / 60), minute % 60); return new Intl.DateTimeFormat(this.i18n.language(), { hour: 'numeric' }).format(d); }
  protected nowTop(date: string) { if (date !== localDate(new Date())) return null; const now = new Date(); const minute = now.getHours() * 60 + now.getMinutes(); const from = this.fullDay() ? 0 : 360; if (minute < from || minute > (this.fullDay() ? 1440 : 1320)) return null; return `${(minute - from) * 1.1}px`; }
  protected clearFilters() { this.members.setValue([]); this.search.setValue(''); this.purpose.setValue(''); this.status.setValue(''); }
  protected startAtSlot(event: Event, date: string) {
    if (!this.isOffice()) return;
    if (this.isPastDate(date)) return;
    const column = event.currentTarget as HTMLElement;
    const minutesFromTop = event instanceof MouseEvent ? Math.max(0, event.clientY - column.getBoundingClientRect().top) : 0;
    const initialMinute = (this.fullDay() ? 0 : 360) + Math.floor(minutesFromTop / 66) * 60 + Math.floor((minutesFromTop % 66) / 16.5) * 15;
    const [year, month, day] = date.split('-').map(Number);
    const startAt = new Date(year!, month! - 1, day!, Math.floor(initialMinute / 60), initialMinute % 60);
    if (startAt.getTime() <= Date.now()) return;
    const ref = this.dialogs.open(ScheduleSlotDialog, { data: { startAt }, width: '560px', maxWidth: '94vw' });
    ref.afterClosed().subscribe((result) => { if (result === 'scheduled') this.refresh(); });
  }
}
