import type { ScheduleResponse, ScheduleVisit } from '@servora/shared-types';
export const PIXELS_PER_MINUTE = 1.2;
const MINUTE = 60000;
export interface DispatchEntry {
  visit: ScheduleVisit;
  top: number;
  height: number;
  slot: number;
  slots: number;
  overlap: boolean;
  continues: boolean;
}
export interface DispatchColumn {
  id: string;
  name: string | null;
  entries: DispatchEntry[];
}
export interface DispatchBoard {
  columns: DispatchColumn[];
  ticks: { instant: number; top: number }[];
  height: number;
  overlapping: Set<string>;
  outside: ScheduleVisit[];
  brief: ScheduleVisit[];
}
export function chronological(visits: ScheduleVisit[]): ScheduleVisit[] {
  return [...visits].sort(
    (a, b) =>
      (a.scheduledStart ? Date.parse(a.scheduledStart) : Infinity) -
        (b.scheduledStart ? Date.parse(b.scheduledStart) : Infinity) ||
      a.jobNumber - b.jobNumber ||
      a.visitId.localeCompare(b.visitId),
  );
}
/** Display geometry only. Overlap means intersecting returned time intervals, not a server conflict verdict. */
export function dispatchBoard(
  data: ScheduleResponse,
  selected: string[] = [],
  fullDay = false,
): DispatchBoard {
  const start = Date.parse(data.day.start),
    end = Date.parse(data.day.end);
  const timed = chronological(data.visits).filter(
    (v) =>
      v.scheduledStart &&
      v.scheduledEnd &&
      Date.parse(v.scheduledEnd) > Date.parse(v.scheduledStart) &&
      Date.parse(v.scheduledStart) < end &&
      Date.parse(v.scheduledEnd) > start,
  );
  const timedIds = new Set(timed.map((v) => v.visitId));
  const outside = data.visits.filter((v) => !timedIds.has(v.visitId));
  // Round elapsed instants relative to the authoritative day boundary, never assume a 24-hour day.
  const first = timed.length ? Math.min(...timed.map((v) => Date.parse(v.scheduledStart!))) : start;
  const last = timed.length ? Math.max(...timed.map((v) => Date.parse(v.scheduledEnd!))) : end;
  const from = fullDay
    ? start
    : Math.max(start, start + Math.floor((first - start) / 3600000 - 1) * 3600000);
  const to = fullDay
    ? end
    : Math.min(end, start + Math.ceil((last - start) / 3600000 + 1) * 3600000);
  const height = Math.max(1, ((to - from) / MINUTE) * PIXELS_PER_MINUTE);
  const columns = new Map<string, DispatchColumn>();
  for (const member of data.technicians) {
    if (!selected.length || selected.includes(member.membershipId))
      columns.set(member.membershipId, { id: member.membershipId, name: member.name, entries: [] });
  }
  for (const visit of timed) {
    const crew = visit.technicians.length ? visit.technicians : [{ membershipId: '', name: null }];
    for (const member of crew) {
      if (selected.length && !selected.includes(member.membershipId)) continue;
      if (!columns.has(member.membershipId))
        columns.set(member.membershipId, {
          id: member.membershipId,
          name: member.name,
          entries: [],
        });
      columns.get(member.membershipId)!.entries.push({
        visit,
        top:
          ((Math.max(from, Date.parse(visit.scheduledStart!)) - from) / MINUTE) * PIXELS_PER_MINUTE,
        height:
          ((Math.min(to, Date.parse(visit.scheduledEnd!)) -
            Math.max(from, Date.parse(visit.scheduledStart!))) /
            MINUTE) *
          PIXELS_PER_MINUTE,
        slot: 0,
        slots: 1,
        overlap: false,
        continues: Date.parse(visit.scheduledEnd!) > end,
      });
    }
  }
  const overlapping = new Set<string>();
  for (const column of columns.values()) {
    let group: DispatchEntry[] = [],
      groupEnd = -Infinity;
    const finish = () => {
      const ends: number[] = [];
      for (const entry of group) {
        const start = Date.parse(entry.visit.scheduledStart!);
        let slot = ends.findIndex((end) => end <= start);
        if (slot === -1) slot = ends.length;
        ends[slot] = Date.parse(entry.visit.scheduledEnd!);
        entry.slot = slot;
      }
      for (const entry of group) {
        entry.slots = ends.length;
        entry.overlap = column.id !== '' && ends.length > 1;
        if (entry.overlap) overlapping.add(entry.visit.visitId);
      }
    };
    for (const entry of column.entries) {
      const start = Date.parse(entry.visit.scheduledStart!);
      if (start >= groupEnd) {
        finish();
        group = [];
        groupEnd = -Infinity;
      }
      group.push(entry);
      groupEnd = Math.max(groupEnd, Date.parse(entry.visit.scheduledEnd!));
    }
    finish();
  }
  const ticks: DispatchBoard['ticks'] = [];
  for (let instant = from; instant <= to; instant += 3600000)
    ticks.push({ instant, top: ((instant - from) / MINUTE) * PIXELS_PER_MINUTE });
  return {
    columns: [...columns.values()],
    ticks,
    height,
    overlapping,
    outside,
    brief: timed.filter(
      (visit) =>
        ((Date.parse(visit.scheduledEnd!) - Date.parse(visit.scheduledStart!)) / MINUTE) *
          PIXELS_PER_MINUTE <
        65,
    ),
  };
}
export function visitTime(visit: ScheduleVisit, zone: string, language: string): string {
  if (!visit.scheduledStart || !visit.scheduledEnd) return '';
  const date = new Intl.DateTimeFormat(language, {
    timeZone: zone,
    month: 'short',
    day: 'numeric',
  });
  const time = new Intl.DateTimeFormat(language, {
    timeZone: zone,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZoneName: 'shortOffset',
  });
  const start = new Date(visit.scheduledStart),
    end = new Date(visit.scheduledEnd);
  return `${date.format(start)} · ${time.format(start)} – ${date.format(start) === date.format(end) ? '' : `${date.format(end)} · `}${time.format(end)}`;
}
