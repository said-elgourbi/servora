import { describe, expect, it } from 'vitest';
import {
  compareAttentionItems,
  compareVisits,
  isVisitOverdue,
  toManagerHomeDto,
} from './manager-home.dto.js';
import type {
  ManagerAttentionItem,
  ManagerHomeVisit,
  VisitStatus,
} from './manager-home.dto.js';

const NOW = new Date('2026-09-14T15:00:00.000Z');

function visit(
  overrides: Partial<ManagerHomeVisit> & { visitStatus: VisitStatus },
): ManagerHomeVisit {
  return {
    visitId: 'visit-1',
    scheduledStart: new Date('2026-09-14T13:00:00.000Z'),
    scheduledEnd: new Date('2026-09-14T14:00:00.000Z'),
    jobId: 'job-1',
    jobNumber: 1,
    jobTitle: 'Furnace repair',
    jobStatus: 'SCHEDULED',
    customerId: 'customer-1',
    customerName: 'ABC Property Management',
    address: null,
    technicians: [],
    overdue: false,
    ...overrides,
  };
}

describe('isVisitOverdue', () => {
  it('reports a Visit still SCHEDULED after its window as overdue', () => {
    expect(isVisitOverdue(visit({ visitStatus: 'SCHEDULED' }), NOW)).toBe(true);
  });

  it('does not report a Visit whose window is still open', () => {
    const open = visit({
      visitStatus: 'SCHEDULED',
      scheduledStart: new Date('2026-09-14T14:30:00.000Z'),
      scheduledEnd: new Date('2026-09-14T16:00:00.000Z'),
    });

    expect(isVisitOverdue(open, NOW)).toBe(false);
  });

  it('does not report a Visit that has progressed past SCHEDULED', () => {
    // A technician who has started work is not "late"; the status is the authority (`BR-074`).
    expect(isVisitOverdue(visit({ visitStatus: 'EN_ROUTE' }), NOW)).toBe(false);
    expect(isVisitOverdue(visit({ visitStatus: 'ON_SITE' }), NOW)).toBe(false);
    expect(isVisitOverdue(visit({ visitStatus: 'IN_PROGRESS' }), NOW)).toBe(
      false,
    );
  });

  it('does not report a completed Visit as overdue', () => {
    expect(isVisitOverdue(visit({ visitStatus: 'COMPLETED' }), NOW)).toBe(
      false,
    );
  });
});

describe('compareVisits', () => {
  it('orders overdue work, then work under way, then what is to come, then completed', () => {
    const completed = visit({
      visitId: 'completed',
      visitStatus: 'COMPLETED',
      scheduledStart: new Date('2026-09-14T08:00:00.000Z'),
      scheduledEnd: new Date('2026-09-14T09:00:00.000Z'),
    });
    const upcoming = visit({
      visitId: 'upcoming',
      visitStatus: 'SCHEDULED',
      scheduledStart: new Date('2026-09-14T16:00:00.000Z'),
      scheduledEnd: new Date('2026-09-14T17:00:00.000Z'),
    });
    const active = visit({
      visitId: 'active',
      visitStatus: 'ON_SITE',
      scheduledStart: new Date('2026-09-14T14:30:00.000Z'),
      scheduledEnd: new Date('2026-09-14T15:30:00.000Z'),
    });
    const overdue = visit({
      visitId: 'overdue',
      visitStatus: 'SCHEDULED',
      overdue: true,
    });

    const ordered = [completed, upcoming, active, overdue].sort(compareVisits);

    expect(ordered.map((row) => row.visitId)).toEqual([
      'overdue',
      'active',
      'upcoming',
      'completed',
    ]);
  });

  it('orders one rank chronologically and breaks a tie by Job number', () => {
    const later = visit({
      visitId: 'later',
      visitStatus: 'SCHEDULED',
      jobNumber: 3,
      scheduledStart: new Date('2026-09-14T18:00:00.000Z'),
      scheduledEnd: new Date('2026-09-14T19:00:00.000Z'),
    });
    const earlierHigherNumber = visit({
      visitId: 'earlier-higher',
      visitStatus: 'SCHEDULED',
      jobNumber: 9,
      scheduledStart: new Date('2026-09-14T16:00:00.000Z'),
      scheduledEnd: new Date('2026-09-14T17:00:00.000Z'),
    });
    const earlierLowerNumber = visit({
      visitId: 'earlier-lower',
      visitStatus: 'SCHEDULED',
      jobNumber: 2,
      scheduledStart: new Date('2026-09-14T16:00:00.000Z'),
      scheduledEnd: new Date('2026-09-14T17:00:00.000Z'),
    });

    const ordered = [later, earlierHigherNumber, earlierLowerNumber].sort(
      compareVisits,
    );

    expect(ordered.map((row) => row.visitId)).toEqual([
      'earlier-lower',
      'earlier-higher',
      'later',
    ]);
  });
});

describe('compareAttentionItems', () => {
  function item(
    overrides: Partial<ManagerAttentionItem> & {
      kind: ManagerAttentionItem['kind'];
    },
  ): ManagerAttentionItem {
    return {
      jobId: 'job-1',
      jobNumber: 1,
      jobTitle: 'Furnace repair',
      jobStatus: 'NEW',
      customerId: 'customer-1',
      customerName: 'ABC Property Management',
      visitId: null,
      scheduledStart: null,
      scheduledEnd: null,
      ...overrides,
    };
  }

  it('puts what has gone wrong before what is waiting and what is unplanned', () => {
    const scheduling = item({ kind: 'JOB_NEEDS_SCHEDULING', jobNumber: 1 });
    const review = item({ kind: 'JOB_PENDING_REVIEW', jobNumber: 2 });
    const overdue = item({
      kind: 'VISIT_OVERDUE',
      jobNumber: 3,
      scheduledStart: new Date('2026-09-14T13:00:00.000Z'),
    });

    const ordered = [scheduling, review, overdue].sort(compareAttentionItems);

    expect(ordered.map((row) => row.kind)).toEqual([
      'VISIT_OVERDUE',
      'JOB_PENDING_REVIEW',
      'JOB_NEEDS_SCHEDULING',
    ]);
  });

  it('orders overdue items by how long they have been overdue', () => {
    const recent = item({
      kind: 'VISIT_OVERDUE',
      jobNumber: 5,
      scheduledStart: new Date('2026-09-14T14:00:00.000Z'),
    });
    const stale = item({
      kind: 'VISIT_OVERDUE',
      jobNumber: 4,
      scheduledStart: new Date('2026-09-13T14:00:00.000Z'),
    });

    expect([recent, stale].sort(compareAttentionItems)).toEqual([
      stale,
      recent,
    ]);
  });

  it('orders an item with no schedule by Job number, after the scheduled ones', () => {
    const scheduled = item({
      kind: 'JOB_NEEDS_SCHEDULING',
      jobNumber: 9,
      scheduledStart: new Date('2026-09-14T13:00:00.000Z'),
    });
    const unscheduled = item({ kind: 'JOB_NEEDS_SCHEDULING', jobNumber: 1 });

    expect([unscheduled, scheduled].sort(compareAttentionItems)).toEqual([
      scheduled,
      unscheduled,
    ]);
  });
});

describe('toManagerHomeDto', () => {
  it('serializes every instant as ISO-8601 and carries stable codes', () => {
    const dto = toManagerHomeDto({
      generatedAt: NOW,
      day: {
        timeZone: 'America/Toronto',
        start: new Date('2026-09-14T04:00:00.000Z'),
        end: new Date('2026-09-15T04:00:00.000Z'),
      },
      viewerDisplayName: 'Sarah Tremblay',
      attention: [
        {
          kind: 'VISIT_OVERDUE',
          jobId: 'job-1',
          jobNumber: 1042,
          jobTitle: 'Furnace repair',
          jobStatus: 'SCHEDULED',
          customerId: 'customer-1',
          customerName: 'ABC Property Management',
          visitId: 'visit-1',
          scheduledStart: new Date('2026-09-14T13:00:00.000Z'),
          scheduledEnd: new Date('2026-09-14T14:00:00.000Z'),
        },
      ],
      attentionTotal: 4,
      today: { total: 1, completed: 0, inProgress: 0, upcoming: 1 },
      visits: [
        visit({
          visitStatus: 'SCHEDULED',
          visitId: 'visit-1',
          overdue: true,
          address: {
            propertyName: 'Cedar Lane Building',
            addressLine1: '987 Cedar Lane',
            addressLine2: null,
            city: 'Montreal',
            province: 'QC',
            postalCode: 'H3A 2T6',
            country: 'Canada',
          },
          technicians: [
            {
              membershipId: 'member-1',
              name: 'Mike Johnson',
              roleCode: 'LEAD',
            },
          ],
        }),
      ],
    });

    expect(dto.generatedAt).toBe('2026-09-14T15:00:00.000Z');
    expect(dto.day).toEqual({
      timeZone: 'America/Toronto',
      start: '2026-09-14T04:00:00.000Z',
      end: '2026-09-15T04:00:00.000Z',
    });
    expect(dto.viewer).toEqual({ displayName: 'Sarah Tremblay' });
    // `total` reports every condition, including the ones beyond `items`.
    expect(dto.attention.total).toBe(4);
    expect(dto.attention.items[0]?.kind).toBe('VISIT_OVERDUE');
    expect(dto.attention.items[0]?.scheduledStart).toBe(
      '2026-09-14T13:00:00.000Z',
    );
    expect(dto.visits[0]?.overdue).toBe(true);
    expect(dto.visits[0]?.technicians).toEqual([
      { membershipId: 'member-1', name: 'Mike Johnson', roleCode: 'LEAD' },
    ]);
    expect(dto.visits[0]?.address?.city).toBe('Montreal');
  });

  it('reports a member without a profile as a null name rather than an invented one', () => {
    const dto = toManagerHomeDto({
      generatedAt: NOW,
      day: { timeZone: 'UTC', start: NOW, end: NOW },
      viewerDisplayName: null,
      attention: [],
      attentionTotal: 0,
      today: { total: 0, completed: 0, inProgress: 0, upcoming: 0 },
      visits: [],
    });

    expect(dto.viewer.displayName).toBeNull();
    expect(dto.attention).toEqual({ total: 0, items: [] });
  });
});
