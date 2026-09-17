import { describe, expect, it } from 'vitest';
import {
  compareChronologically,
  selectNextVisit,
  toTechnicianHomeDto,
} from './technician-home.dto.js';
import type { TechnicianHomeVisit } from './technician-home.dto.js';

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
    jobStatus: 'SCHEDULED',
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
            jobStatus: 'SCHEDULED',
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

