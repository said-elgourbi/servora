import { describe, expect, it } from 'vitest';
import type { ScheduleResponse, ScheduleVisit } from '@servora/shared-types';
import { chronological, dispatchBoard, PIXELS_PER_MINUTE, visitTime } from './dispatch-model';
const alex = { membershipId: 'alex', name: 'Alex', roleCode: 'LEAD' };
const jo = { membershipId: 'jo', name: 'Jo', roleCode: 'TECHNICIAN' };
function visit(id: string, start: string, end: string, crew = [alex]): ScheduleVisit {
  return {
    visitId: id,
    jobId: 'job-' + id,
    jobNumber: 100,
    jobTitle: id,
    customerId: 'customer',
    customerName: 'Customer',
    visitStatus: 'SCHEDULED',
    purposeCode: 'WORK',
    scheduledStart: start,
    scheduledEnd: end,
    technicians: crew,
    address: null,
    overdue: false,
  };
}
function response(
  visits: ScheduleVisit[],
  start = '2026-10-20T04:00:00Z',
  end = '2026-10-21T04:00:00Z',
): ScheduleResponse {
  return {
    generatedAt: start,
    day: { localDate: '2026-10-20', start, end, timeZone: 'America/Toronto' },
    scope: { kind: 'ORGANIZATION', membershipId: 'alex' },
    technicians: [alex, jo, { membershipId: 'free', name: 'Sam' }],
    visits,
    unassigned: { total: 0, items: [] },
  };
}
describe('dispatch geometry', () => {
  it('positions actual minutes, preserving midnight and short visits', () => {
    const board = dispatchBoard(
      response([
        visit('midnight', '2026-10-20T04:00:00Z', '2026-10-20T04:15:00Z'),
        visit('morning', '2026-10-20T13:30:00Z', '2026-10-20T14:45:00Z'),
      ]),
      [],
      true,
    );
    expect(board.columns[0]?.entries.map((e) => [e.top, e.height])).toEqual([
      [0, 15 * PIXELS_PER_MINUTE],
      [570 * PIXELS_PER_MINUTE, 75 * PIXELS_PER_MINUTE],
    ]);
    expect(board.columns[2]?.entries).toEqual([]);
  });
  it('splits intersecting intervals into slots and reuses slots at exact boundaries', () => {
    const board = dispatchBoard(
      response([
        visit('a', '2026-10-20T13:00Z', '2026-10-20T15:00Z'),
        visit('b', '2026-10-20T13:30Z', '2026-10-20T14:00Z'),
        visit('c', '2026-10-20T14:00Z', '2026-10-20T16:00Z'),
        visit('d', '2026-10-20T16:00Z', '2026-10-20T17:00Z'),
      ]),
    );
    expect(board.columns[0]?.entries.map((e) => [e.slot, e.slots, e.overlap])).toEqual([
      [0, 2, true],
      [1, 2, true],
      [1, 2, true],
      [0, 1, false],
    ]);
    expect([...board.overlapping]).toEqual(['a', 'b', 'c']);
  });
  it('identifies triple overlaps without counting shared crew as separate visits', () => {
    const shared = visit('shared', '2026-10-20T13:00Z', '2026-10-20T15:00Z', [alex, jo]);
    const board = dispatchBoard(
      response([
        shared,
        visit('b', '2026-10-20T13:30Z', '2026-10-20T14:30Z'),
        visit('c', '2026-10-20T14:00Z', '2026-10-20T16:00Z'),
      ]),
    );
    expect(board.columns[0]?.entries.every((e) => e.slots === 3)).toBe(true);
    expect(board.columns[1]?.entries[0]).toMatchObject({ visit: shared, overlap: false, slots: 1 });
    expect(board.overlapping.size).toBe(3);
    expect(dispatchBoard(response([shared]), ['jo']).columns.map((c) => c.id)).toEqual(['jo']);
  });
  it('clips overnight geometry at the day boundary while retaining the real end and continuation', () => {
    const item = visit('night', '2026-10-21T03:00Z', '2026-10-21T06:00Z');
    const board = dispatchBoard(response([item]), [], true);
    expect(board.columns[0]?.entries[0]).toMatchObject({
      height: 60 * PIXELS_PER_MINUTE,
      continues: true,
    });
    expect(visitTime(item, 'America/Toronto', 'en')).toContain('Oct 21');
    expect(visitTime(item, 'America/Toronto', 'en')).toContain('02:00');
  });
  it('uses the server day length on spring-forward and fall-back days with repeated times disambiguated', () => {
    const spring = dispatchBoard(response([], '2026-03-08T05:00Z', '2026-03-09T04:00Z'), [], true);
    const fall = dispatchBoard(response([], '2026-11-01T04:00Z', '2026-11-02T05:00Z'), [], true);
    expect(spring.height).toBe(23 * 60 * PIXELS_PER_MINUTE);
    expect(fall.height).toBe(25 * 60 * PIXELS_PER_MINUTE);
    const repeated = visitTime(
      visit('repeat', '2026-11-01T05:30Z', '2026-11-01T06:30Z'),
      'America/Toronto',
      'en',
    );
    expect(repeated).toContain('GMT-4');
    expect(repeated).toContain('GMT-5');
  });
  it('does not fabricate times and preserves unplaced records for the list', () => {
    const unscheduled = {
      ...visit('draft', '2026-10-20T13:00Z', '2026-10-20T14:00Z', []),
      scheduledStart: null,
      scheduledEnd: null,
    };
    const noCrew = visit('no-crew', '2026-10-20T13:00Z', '2026-10-20T14:00Z', []);
    const board = dispatchBoard(response([unscheduled, noCrew]));
    expect(board.outside).toEqual([unscheduled]);
    expect(board.columns.at(-1)).toMatchObject({ id: '', entries: [{ visit: noCrew }] });
    expect(chronological([unscheduled, noCrew])).toEqual([noCrew, unscheduled]);
    const unassigned = dispatchBoard(
      response([noCrew, { ...noCrew, visitId: 'another-unassigned' }]),
    );
    expect(unassigned.overlapping.size).toBe(0);
    expect(unassigned.columns.at(-1)?.entries.map((entry) => entry.slots)).toEqual([2, 2]);
  });
});
