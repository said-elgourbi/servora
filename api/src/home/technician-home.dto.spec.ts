import { describe, expect, it } from 'vitest';
import {
  compareAttentionItems,
  compareChronologically,
  selectAttentionVisits,
  selectNextVisit,
  toTechnicianHomeDto,
} from './technician-home.dto.js';
import type {
  TechnicianAttentionItem,
  TechnicianHomeVisit,
} from './technician-home.dto.js';

const NOW = new Date('2026-09-14T15:00:00.000Z');

function visit(
  overrides: Partial<TechnicianHomeVisit> = {},
): TechnicianHomeVisit {
  return {
    visitId: 'visit-1',
    visitStatus: 'SCHEDULED',
    scheduledStart: new Date('2026-09-14T13:00:00.000Z'),
    scheduledEnd: new Date('2026-09-14T14:00:00.000Z'),
    jobId: 'job-1',
    jobNumber: 1,
    jobTitle: 'Furnace repair',
    jobStatus: 'ACTIVE',
    customerId: 'customer-1',
    customerName: 'ABC Property Management',
    address: null,
    technicians: [],
    overdue: false,
    ...overrides,
  };
}

describe('compareChronologically', () => {
  it('orders by scheduled start and breaks a tie by Job number', () => {
    const later = visit({
      visitId: 'later',
      jobNumber: 3,
      scheduledStart: new Date('2026-09-14T16:00:00.000Z'),
    });
    const first = visit({
      visitId: 'first',
      jobNumber: 2,
      scheduledStart: new Date('2026-09-14T08:00:00.000Z'),
    });
    const tie = visit({
      visitId: 'tie',
      jobNumber: 1,
      scheduledStart: new Date('2026-09-14T08:00:00.000Z'),
    });

    expect([later, first, tie].sort(compareChronologically)).toEqual([
      tie,
      first,
      later,
    ]);
  });

  it('does not present the manager home operational order', () => {
    // The technician reads their day in time order; an overdue Visit is not pulled to the front
    // here (`BR-012`). It is the attention section that reports it.
    const overdue = visit({
      visitId: 'overdue',
      overdue: true,
      scheduledStart: new Date('2026-09-14T09:00:00.000Z'),
      scheduledEnd: new Date('2026-09-14T10:00:00.000Z'),
    });
    const underWay = visit({
      visitId: 'under-way',
      visitStatus: 'ON_SITE',
      scheduledStart: new Date('2026-09-14T14:00:00.000Z'),
    });

    expect([underWay, overdue].sort(compareChronologically)).toEqual([
      overdue,
      underWay,
    ]);
  });
});

describe('selectAttentionVisits', () => {
  it('does not repeat the Visit the read already offers as the next one', () => {
    const next = visit({ visitId: 'next', overdue: true });

    expect(selectAttentionVisits([next], next, [])).toEqual([]);
  });

  it("does not repeat a Visit today's own list already states", () => {
    const today = visit({ visitId: 'today', overdue: true });

    expect(selectAttentionVisits([today], null, [today])).toEqual([]);
  });

  it('states late work from an earlier day the screen says nowhere else', () => {
    const earlierDay = visit({
      visitId: 'earlier-day',
      overdue: true,
      scheduledStart: new Date('2026-09-13T09:00:00.000Z'),
      scheduledEnd: new Date('2026-09-13T10:00:00.000Z'),
    });
    const next = visit({ visitId: 'next' });

    // The attempt is neither what the technician does next nor part of today, so the section is the
    // only place this screen names it (`BR-012`).
    expect(selectAttentionVisits([earlierDay], next, [next])).toEqual([
      earlierDay,
    ]);
  });
});

describe('compareAttentionItems', () => {
  function item(
    overrides: Partial<TechnicianAttentionItem> = {},
  ): TechnicianAttentionItem {
    return {
      kind: 'VISIT_OVERDUE',
      visitId: 'visit-1',
      jobId: 'job-1',
      jobNumber: 1,
      jobTitle: 'Furnace repair',
      jobStatus: 'ACTIVE',
      customerId: 'customer-1',
      customerName: 'ABC Property Management',
      scheduledStart: new Date('2026-09-14T09:00:00.000Z'),
      scheduledEnd: new Date('2026-09-14T10:00:00.000Z'),
      ...overrides,
    };
  }

  it('puts the attempt that has been overdue longest first', () => {
    const recentlyDue = item({
      visitId: 'recently-due',
      scheduledStart: new Date('2026-09-14T13:00:00.000Z'),
    });
    const longOverdue = item({
      visitId: 'long-overdue',
      scheduledStart: new Date('2026-09-13T09:00:00.000Z'),
    });

    expect([recentlyDue, longOverdue].sort(compareAttentionItems)).toEqual([
      longOverdue,
      recentlyDue,
    ]);
  });

  it('breaks a tie between two attempts due at the same instant by Job number', () => {
    const laterJob = item({ visitId: 'later-job', jobNumber: 1049 });
    const earlierJob = item({ visitId: 'earlier-job', jobNumber: 1042 });

    expect([laterJob, earlierJob].sort(compareAttentionItems)).toEqual([
      earlierJob,
      laterJob,
    ]);
  });
});

describe('selectNextVisit', () => {
  it('answers nothing when no assigned Visit is left to do', () => {
    expect(selectNextVisit([])).toBeNull();
  });

  it('chooses the Visit already under way over one scheduled earlier', () => {
    const notStarted = visit({
      visitId: 'not-started',
      scheduledStart: new Date('2026-09-14T09:00:00.000Z'),
    });
    const underWay = visit({
      visitId: 'under-way',
      visitStatus: 'IN_PROGRESS',
      scheduledStart: new Date('2026-09-14T14:00:00.000Z'),
    });

    expect(selectNextVisit([notStarted, underWay])?.visitId).toBe('under-way');
  });

  it('chooses the nearest Visit when nothing has started', () => {
    const tomorrow = visit({
      visitId: 'tomorrow',
      scheduledStart: new Date('2026-09-15T13:00:00.000Z'),
    });
    const overdue = visit({
      visitId: 'overdue',
      overdue: true,
      scheduledStart: new Date('2026-09-14T09:00:00.000Z'),
    });
    const laterToday = visit({
      visitId: 'later-today',
      scheduledStart: new Date('2026-09-14T18:00:00.000Z'),
    });

    expect(selectNextVisit([tomorrow, laterToday, overdue])?.visitId).toBe(
      'overdue',
    );
  });

  it('breaks a tie between two Visits under way by the nearer one', () => {
    const laterTravelling = visit({
      visitId: 'later-travelling',
      visitStatus: 'EN_ROUTE',
      scheduledStart: new Date('2026-09-14T19:00:00.000Z'),
    });
    const earlierOnSite = visit({
      visitId: 'earlier-on-site',
      visitStatus: 'ON_SITE',
      scheduledStart: new Date('2026-09-14T14:00:00.000Z'),
    });

    expect(selectNextVisit([laterTravelling, earlierOnSite])?.visitId).toBe(
      'earlier-on-site',
    );
  });
});

describe('toTechnicianHomeDto', () => {
  it('serializes every instant as ISO-8601 and carries stable codes', () => {
    const dto = toTechnicianHomeDto({
      generatedAt: NOW,
      day: {
        timeZone: 'America/Toronto',
        start: new Date('2026-09-14T04:00:00.000Z'),
        end: new Date('2026-09-15T04:00:00.000Z'),
      },
      read: {
        viewerDisplayName: 'Mike Johnson',
        nextVisit: visit({
          visitStatus: 'EN_ROUTE',
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
        visits: [visit()],
        upcoming: [visit({ visitId: 'visit-2' })],
        upcomingTotal: 4,
        attention: [
          {
            kind: 'VISIT_OVERDUE',
            visitId: 'visit-3',
            jobId: 'job-1',
            jobNumber: 1042,
            jobTitle: 'Furnace repair',
            jobStatus: 'ACTIVE',
            customerId: 'customer-1',
            customerName: 'ABC Property Management',
            scheduledStart: new Date('2026-09-14T09:00:00.000Z'),
            scheduledEnd: new Date('2026-09-14T10:00:00.000Z'),
          },
        ],
        attentionTotal: 2,
      },
    });

    expect(dto.generatedAt).toBe('2026-09-14T15:00:00.000Z');
    expect(dto.day).toEqual({
      timeZone: 'America/Toronto',
      start: '2026-09-14T04:00:00.000Z',
      end: '2026-09-15T04:00:00.000Z',
    });
    expect(dto.viewer).toEqual({ displayName: 'Mike Johnson' });
    expect(dto.nextVisit?.visitStatus).toBe('EN_ROUTE');
    expect(dto.nextVisit?.scheduledStart).toBe('2026-09-14T13:00:00.000Z');
    expect(dto.nextVisit?.address?.city).toBe('Montreal');
    expect(dto.nextVisit?.technicians).toEqual([
      { membershipId: 'member-1', name: 'Mike Johnson', roleCode: 'LEAD' },
    ]);
    // The preview list is capped by the service, so `upcomingTotal` is what keeps it honest.
    expect(dto.upcoming).toHaveLength(1);
    expect(dto.upcomingTotal).toBe(4);
    expect(dto.attention.total).toBe(2);
    expect(dto.attention.items[0]?.kind).toBe('VISIT_OVERDUE');
    expect(dto.attention.items[0]?.scheduledStart).toBe(
      '2026-09-14T09:00:00.000Z',
    );
  });

  it('reports no next Visit and a member without a profile as a null name', () => {
    const dto = toTechnicianHomeDto({
      generatedAt: NOW,
      day: { timeZone: 'UTC', start: NOW, end: NOW },
      read: {
        viewerDisplayName: null,
        nextVisit: null,
        visits: [],
        upcoming: [],
        upcomingTotal: 0,
        attention: [],
        attentionTotal: 0,
      },
    });

    expect(dto.viewer.displayName).toBeNull();
    expect(dto.nextVisit).toBeNull();
    expect(dto.visits).toEqual([]);
    expect(dto.upcoming).toEqual([]);
    expect(dto.attention).toEqual({ total: 0, items: [] });
  });
});

