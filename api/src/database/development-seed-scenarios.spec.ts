import { describe, expect, it } from 'vitest';
import {
  SEED_CUSTOMERS,
  SEED_TECHNICIAN_MEMBER_TEMPLATES,
  addHours,
  assertSeedScenariosAreCoherent,
  orderedPastEventTimes,
  pastInstant,
  resolveSeedJob,
  resolveSeedWindow,
  type SeedCustomerPlan,
  type SeedJobPlan,
} from './development-seed-scenarios.js';

/**
 * The dataset has to stay honest whatever time of day the seed runs, so it is checked at the edges of a
 * day as well as in the middle of one.
 */
const SEED_RUN_TIMES: readonly Date[] = [
  new Date(2026, 2, 10, 0, 15),
  new Date(2026, 2, 10, 8, 0),
  new Date(2026, 2, 10, 12, 0),
  new Date(2026, 2, 10, 23, 45),
];

const SAMPLE_CUSTOMER: SeedCustomerPlan = {
  kind: 'COMPANY',
  displayName: 'Sample Mechanical',
  legalName: 'Sample Mechanical Inc.',
  businessName: 'Sample Mechanical',
  taxNumber: 'GST-0000-0000',
  email: 'office@sample-mechanical.test',
  phone: '+1 555 0100',
  billingEmail: 'billing@sample-mechanical.test',
  billingPhone: '+1 555 0101',
  notes: 'Fixture used only by scenario coherence tests.',
  preferredContactMethod: 'EMAIL',
  language: 'en-CA',
  status: 'ACTIVE',
  customerSinceDaysAgo: 30,
  billingAddress: {
    addressLine1: '100 Test Avenue',
    city: 'Toronto',
    province: 'Ontario',
    postalCode: 'M5V 0A1',
  },
  contacts: [
    {
      firstName: 'Alex',
      lastName: 'Sample',
      role: 'Operations Manager',
      email: 'alex@sample-mechanical.test',
      phone: '+1 555 0102',
      isPrimary: true,
      isJobContact: true,
    },
  ],
  properties: [
    {
      name: 'Sample Site',
      addressLine1: '100 Test Avenue',
      city: 'Toronto',
      province: 'Ontario',
      postalCode: 'M5V 0A1',
    },
  ],
  jobs: [
    {
      title: 'Sample service call',
      description: 'Fixture job used only by scenario coherence tests.',
      typeCode: 'SERVICE_CALL',
      status: 'ACTIVE',
      propertyIndex: 0,
      owner: true,
      visits: [
        {
          status: 'IN_PROGRESS',
          schedule: { kind: 'HOURS_FROM_NOW', hours: -2, durationHours: 4 },
          crew: ['SEEDED'],
        },
      ],
    },
  ],
};

describe('resolveSeedWindow', () => {
  it('places a past day at that hour, entirely before the seed runs', () => {
    const now = new Date(2026, 2, 10, 12, 0);

    const window = resolveSeedWindow(
      { kind: 'DAYS_AGO', days: 2, hour: 9, durationHours: 2 },
      now,
    );

    expect(window.scheduledStart.getFullYear()).toBe(2026);
    expect(window.scheduledStart.getMonth()).toBe(2);
    expect(window.scheduledStart.getDate()).toBe(8);
    expect(window.scheduledStart.getHours()).toBe(9);
    expect(window.scheduledEnd.getHours()).toBe(11);
    expect(window.scheduledEnd.getTime()).toBeLessThan(now.getTime());
  });

  it('places a future day at that hour, entirely after the seed runs', () => {
    const now = new Date(2026, 2, 10, 23, 45);

    const window = resolveSeedWindow(
      { kind: 'IN_DAYS', days: 1, hour: 9, durationHours: 2 },
      now,
    );

    expect(window.scheduledStart.getDate()).toBe(11);
    expect(window.scheduledStart.getHours()).toBe(9);
    expect(window.scheduledStart.getTime()).toBeGreaterThan(now.getTime());
  });

  it('places an in-flight window around the moment the seed runs', () => {
    const now = new Date(2026, 2, 10, 12, 0);

    const window = resolveSeedWindow(
      { kind: 'HOURS_FROM_NOW', hours: -2, durationHours: 3 },
      now,
    );

    expect(window.scheduledStart.getTime()).toBeLessThan(now.getTime());
    expect(window.scheduledEnd.getTime()).toBeGreaterThan(now.getTime());
  });
});

describe('the seeded dataset', () => {
  it('accepts a coherent scenario fixture whenever the seed runs', () => {
    for (const now of SEED_RUN_TIMES) {
      expect(() =>
        assertSeedScenariosAreCoherent([SAMPLE_CUSTOMER], now),
      ).not.toThrow();
    }
  });

  it('names each extra technician member once, at a reserved address', () => {
    const keys = SEED_TECHNICIAN_MEMBER_TEMPLATES.map((member) => member.key);

    expect(new Set(keys).size).toBe(keys.length);
    for (const member of SEED_TECHNICIAN_MEMBER_TEMPLATES) {
      expect(member.email).toMatch(/@servora\.test$/);
      expect(member.firstName.length).toBeGreaterThan(0);
      expect(member.lastName.length).toBeGreaterThan(0);
    }
  });
});

describe('orderedPastEventTimes', () => {
  it('keeps a history strictly ordered and entirely in the past', () => {
    const now = new Date(2026, 2, 10, 12, 0);

    const times = orderedPastEventTimes(
      [
        addHours(now, -6),
        addHours(now, -1),
        addHours(now, 5),
        addHours(now, 9),
      ],
      now,
    );

    expect(times).toHaveLength(4);
    let previous = Number.NEGATIVE_INFINITY;
    for (const time of times) {
      expect(time.getTime()).toBeGreaterThan(previous);
      expect(time.getTime()).toBeLessThan(now.getTime());
      previous = time.getTime();
    }
  });

  it('pulls a wholly future history back into the past, keeping its order', () => {
    const now = new Date(2026, 2, 10, 12, 0);
    const future = new Date(2026, 3, 2, 10, 0);

    const times = orderedPastEventTimes(
      [future, addHours(future, 1), addHours(future, 2)],
      now,
    );

    const [first, second, third] = times;
    expect(third?.getTime()).toBeLessThan(now.getTime());
    expect(first?.getTime()).toBeLessThan(second?.getTime() ?? 0);
    expect(second?.getTime()).toBeLessThan(third?.getTime() ?? 0);
  });

  it('returns nothing for a history with no events', () => {
    expect(orderedPastEventTimes([], new Date(2026, 2, 10, 12, 0))).toEqual([]);
  });
});

describe('pastInstant', () => {
  it('leaves an instant that is already far enough in the past alone', () => {
    const now = new Date(2026, 2, 10, 12, 0);
    const longAgo = addHours(now, -50);

    expect(pastInstant(longAgo, now, 3).getTime()).toBe(longAgo.getTime());
  });

  it('pulls a future instant back to the requested margin', () => {
    const now = new Date(2026, 2, 10, 12, 0);

    const pulled = pastInstant(addHours(now, 48), now, 5);

    expect(pulled.getTime()).toBe(now.getTime() - 5 * 60 * 1000);
  });
});

describe('assertSeedScenariosAreCoherent', () => {
  const now = SEED_RUN_TIMES[2] as Date;
  const baseJob = SAMPLE_CUSTOMER.jobs[0] as SeedJobPlan;

  function oneCustomer(job: SeedJobPlan): readonly SeedCustomerPlan[] {
    return [{ ...SAMPLE_CUSTOMER, jobs: [job] }];
  }

  it('refuses a Visit scheduled in the future that is already finished work', () => {
    const broken: SeedJobPlan = {
      ...baseJob,
      status: 'COMPLETED',
      visits: [
        {
          status: 'COMPLETED',
          schedule: { kind: 'IN_DAYS', days: 3, hour: 9, durationHours: 2 },
          crew: ['SEEDED'],
          outcome: {
            code: 'RESOLVED',
            summary:
              'Reported as finished three days before it was due to happen.',
          },
        },
      ],
    };

    expect(() =>
      assertSeedScenariosAreCoherent(oneCustomer(broken), now),
    ).toThrow(/scheduled in the future/);
  });

  it('refuses a Job that is closed while a Visit is still open', () => {
    const broken: SeedJobPlan = {
      ...baseJob,
      status: 'COMPLETED',
      visits: [
        {
          status: 'SCHEDULED',
          schedule: { kind: 'IN_DAYS', days: 2, hour: 9, durationHours: 2 },
          crew: ['SEEDED'],
        },
      ],
    };

    expect(() =>
      assertSeedScenariosAreCoherent(oneCustomer(broken), now),
    ).toThrow(/still open/);
  });

  it('refuses a technician booked on two overlapping Visits', () => {
    const broken: SeedJobPlan = {
      ...baseJob,
      status: 'ACTIVE',
      visits: [
        {
          status: 'IN_PROGRESS',
          schedule: { kind: 'HOURS_FROM_NOW', hours: -2, durationHours: 4 },
          crew: ['SEEDED'],
        },
        {
          status: 'IN_PROGRESS',
          schedule: { kind: 'HOURS_FROM_NOW', hours: -1, durationHours: 4 },
          crew: ['SEEDED'],
        },
      ],
    };

    expect(() =>
      assertSeedScenariosAreCoherent(oneCustomer(broken), now),
    ).toThrow(/overlapping Visits/);
  });

  it('refuses a note written by somebody who is not on the Visit crew', () => {
    const broken: SeedJobPlan = {
      ...baseJob,
      status: 'ACTIVE',
      visits: [
        {
          status: 'IN_PROGRESS',
          schedule: { kind: 'HOURS_FROM_NOW', hours: -2, durationHours: 4 },
          crew: ['SEEDED'],
          notes: [
            {
              atHours: -1,
              author: 'LUC',
              body: 'Written by a technician who is not on this Visit.',
            },
          ],
        },
      ],
    };

    expect(() =>
      assertSeedScenariosAreCoherent(oneCustomer(broken), now),
    ).toThrow(/not on its crew/);
  });
});

describe('SEED_CUSTOMERS dataset', () => {
  const allJobStatuses = ['NEW', 'ACTIVE', 'COMPLETED', 'CANCELED'] as const;
  const allVisitStatuses = [
    'DRAFT',
    'SCHEDULED',
    'EN_ROUTE',
    'ON_SITE',
    'IN_PROGRESS',
    'COMPLETED',
    'CANCELED',
  ] as const;
  const inFlightStatuses: ReadonlySet<string> = new Set([
    'EN_ROUTE',
    'ON_SITE',
    'IN_PROGRESS',
  ]);

  it('is non-empty and coherent at every seed run time', () => {
    expect(SEED_CUSTOMERS.length).toBeGreaterThan(0);
    for (const now of SEED_RUN_TIMES) {
      expect(() =>
        assertSeedScenariosAreCoherent(SEED_CUSTOMERS, now),
      ).not.toThrow();
    }
  });

  it('covers every Job status', () => {
    const statuses = new Set(
      SEED_CUSTOMERS.flatMap((customer) =>
        customer.jobs.map((job) => job.status),
      ),
    );
    for (const status of allJobStatuses) {
      expect(statuses.has(status)).toBe(true);
    }
  });

  it('covers every Visit status', () => {
    const statuses = new Set(
      SEED_CUSTOMERS.flatMap((customer) =>
        customer.jobs.flatMap((job) => job.visits.map((visit) => visit.status)),
      ),
    );
    for (const status of allVisitStatuses) {
      expect(statuses.has(status)).toBe(true);
    }
  });

  it('gives the seeded technician a current or upcoming non-canceled visit', () => {
    const now = SEED_RUN_TIMES[2] as Date;
    const hasWork = SEED_CUSTOMERS.some((customer) =>
      customer.jobs.some((job) =>
        resolveSeedJob(job, now).visits.some((visit) => {
          if (
            visit.plan.status === 'CANCELED' ||
            !visit.plan.crew.includes('SEEDED') ||
            visit.window === null
          ) {
            return false;
          }
          return (
            visit.window.scheduledStart.getTime() >= now.getTime() ||
            inFlightStatuses.has(visit.plan.status)
          );
        }),
      ),
    );
    expect(hasWork).toBe(true);
  });

  it('keeps every Job and Visit history in the past and in order', () => {
    const now = SEED_RUN_TIMES[2] as Date;
    for (const customer of SEED_CUSTOMERS) {
      for (const job of customer.jobs) {
        const resolved = resolveSeedJob(job, now);

        expect(
          resolved.statusEventTimes.every(
            (time) => time.getTime() <= now.getTime(),
          ),
        ).toBe(true);
        for (
          let index = 1;
          index < resolved.statusEventTimes.length;
          index += 1
        ) {
          expect(resolved.statusEventTimes[index].getTime()).toBeGreaterThan(
            resolved.statusEventTimes[index - 1].getTime(),
          );
        }

        for (const visit of resolved.visits) {
          expect(
            visit.statusEventTimes.every(
              (time) => time.getTime() <= now.getTime(),
            ),
          ).toBe(true);
          for (
            let index = 1;
            index < visit.statusEventTimes.length;
            index += 1
          ) {
            expect(visit.statusEventTimes[index].getTime()).toBeGreaterThan(
              visit.statusEventTimes[index - 1].getTime(),
            );
          }
        }
      }
    }
  });
});
