import { describe, expect, it } from 'vitest';
import {
  SCHEDULE_UNASSIGNED_LIMIT,
  UNASSIGNED_EXCLUDED_VISIT_STATUSES,
  compareScheduleVisits,
  toScheduleDto,
} from './schedule.dto.js';
import type { ScheduleVisit } from './schedule.dto.js';

/**
 * The schedule projection's own answers: the order the day is presented in, what an unassigned row
 * carries, and which statuses still need a crew.
 */

function visit(overrides: Partial<ScheduleVisit> = {}): ScheduleVisit {
  return {
    visitId: 'visit-1',
    visitStatus: 'SCHEDULED',
    scheduledStart: new Date('2026-09-07T13:00:00.000Z'),
    scheduledEnd: new Date('2026-09-07T14:00:00.000Z'),
    jobId: 'job-1',
    jobNumber: 1042,
    jobTitle: 'Furnace repair',
    customerId: 'customer-1',
    customerName: 'ABC Property Management',
    address: null,
    technicians: [],
    overdue: false,
    ...overrides,
  };
}

describe('compareScheduleVisits', () => {
  it('orders the day chronologically', () => {
    const later = visit({
      visitId: 'later',
      scheduledStart: new Date('2026-09-07T16:00:00.000Z'),
    });
    const earlier = visit({
      visitId: 'earlier',
      scheduledStart: new Date('2026-09-07T08:00:00.000Z'),
    });

    expect([later, earlier].sort(compareScheduleVisits)).toEqual([
      earlier,
      later,
    ]);
  });

  it('places a Visit nobody has given a time to after the day\u2019s timed work', () => {
    // An unassigned attempt being arranged has no place in "what happens when" (`BR-072`).
    const noTime = visit({
      visitId: 'no-time',
      scheduledStart: null,
      scheduledEnd: null,
    });
    const timed = visit({ visitId: 'timed' });

    expect([noTime, timed].sort(compareScheduleVisits)).toEqual([timed, noTime]);
  });

  it('breaks a tie between two Visits at the same minute by Job number', () => {
    const second = visit({ visitId: 'second', jobNumber: 1043 });
    const first = visit({ visitId: 'first', jobNumber: 1042 });

    expect([second, first].sort(compareScheduleVisits)).toEqual([first, second]);
  });
});

describe('UNASSIGNED_EXCLUDED_VISIT_STATUSES', () => {
  it('is the open-Visit classification, so work that is over never needs a crew', () => {
    expect([...UNASSIGNED_EXCLUDED_VISIT_STATUSES]).toEqual([
      'COMPLETED',
      'CANCELED',
      'NO_SHOW',
    ]);
  });

  it('caps the lane and reports the whole queue beside it', () => {
    expect(SCHEDULE_UNASSIGNED_LIMIT).toBeGreaterThan(0);
  });
});

describe('toScheduleDto', () => {
  it('echoes the local day and sends an instant for every scheduled end it has', () => {
    const dto = toScheduleDto({
      generatedAt: new Date('2026-09-07T12:00:00.000Z'),
      localDate: '2026-09-07',
      day: {
        timeZone: 'America/Toronto',
        start: new Date('2026-09-07T04:00:00.000Z'),
        end: new Date('2026-09-08T04:00:00.000Z'),
      },
      technicians: [{ membershipId: 'member-1', name: 'Mike Johnson' }],
      visits: [visit()],
      unassigned: { total: 3, items: [visit({ visitId: 'visit-9', scheduledStart: null, scheduledEnd: null })] },
      scope: { kind: 'ORGANIZATION', membershipId: 'member-1' },
    });

    expect(dto.day).toEqual({
      localDate: '2026-09-07',
      timeZone: 'America/Toronto',
      start: '2026-09-07T04:00:00.000Z',
      end: '2026-09-08T04:00:00.000Z',
    });
    expect(dto.generatedAt).toBe('2026-09-07T12:00:00.000Z');
    // The scope the read was resolved for travels with the day, so a client presents the work the
    // caller's capability authorized rather than deciding a scope for itself (`BR-006`, `BR-041`).
    expect(dto.scope).toEqual({
      kind: 'ORGANIZATION',
      membershipId: 'member-1',
    });
    expect(dto.technicians).toEqual([
      { membershipId: 'member-1', name: 'Mike Johnson' },
    ]);
    expect(dto.visits[0]?.scheduledStart).toBe('2026-09-07T13:00:00.000Z');
    expect(dto.visits[0]?.technicians).toEqual([]);
    // The lane's own total is not the length of its capped list.
    expect(dto.unassigned?.total).toBe(3);
    expect(dto.unassigned?.items[0]?.scheduledStart).toBeNull();
    expect(dto.unassigned?.items[0]?.scheduledEnd).toBeNull();
  });

  it('reports no unassigned lane for a scope that does not carry one', () => {
    const dto = toScheduleDto({
      generatedAt: new Date('2026-09-07T12:00:00.000Z'),
      localDate: '2026-09-07',
      day: {
        timeZone: 'America/Toronto',
        start: new Date('2026-09-07T04:00:00.000Z'),
        end: new Date('2026-09-08T04:00:00.000Z'),
      },
      scope: { kind: 'SELF', membershipId: 'member-7' },
      technicians: [],
      visits: [visit()],
      unassigned: null,
    });

    // `null` is "this scope has no such lane", never "nothing is waiting" (`BR-009`, `BR-042`).
    expect(dto.unassigned).toBeNull();
    expect(dto.scope).toEqual({ kind: 'SELF', membershipId: 'member-7' });
  });

  it('carries the crew Lead first, as the read resolved it', () => {
    const dto = toScheduleDto({
      generatedAt: new Date('2026-09-07T12:00:00.000Z'),
      localDate: '2026-09-07',
      day: {
        timeZone: 'UTC',
        start: new Date('2026-09-07T00:00:00.000Z'),
        end: new Date('2026-09-08T00:00:00.000Z'),
      },
      scope: { kind: 'ORGANIZATION', membershipId: 'member-1' },
      technicians: [],
      visits: [
        visit({
          technicians: [
            { membershipId: 'lead', name: 'Mike Johnson', roleCode: 'LEAD' },
            { membershipId: 'helper', name: null, roleCode: 'TECHNICIAN' },
          ],
        }),
      ],
      unassigned: { total: 0, items: [] },
    });

    expect(dto.visits[0]?.technicians).toEqual([
      { membershipId: 'lead', name: 'Mike Johnson', roleCode: 'LEAD' },
      { membershipId: 'helper', name: null, roleCode: 'TECHNICIAN' },
    ]);
  });
});
