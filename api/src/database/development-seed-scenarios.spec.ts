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
  type ResolvedSeedWindow,
  type SeedCustomerPlan,
  type SeedJobPlan,
  type SeedJobStatus,
  type SeedNoteAuthor,
  type SeedTechnicianKey,
  type SeedVisitStatus,
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

const EVERY_VISIT_STATUS: readonly SeedVisitStatus[] = [
  'DRAFT',
  'SCHEDULED',
  'EN_ROUTE',
  'ON_SITE',
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELED',
  'NO_SHOW',
];

const EVERY_JOB_STATUS: readonly SeedJobStatus[] = [
  'NEW',
  'SCHEDULED',
  'IN_PROGRESS',
  'PENDING_REVIEW',
  'COMPLETED',
  'CANCELED',
];

/** One seeded Visit with the label an assertion should name and its window resolved against `now`. */
interface FoundVisit {
  readonly label: string;
  readonly status: SeedVisitStatus;
  readonly crew: readonly SeedTechnicianKey[];
  readonly window: ResolvedSeedWindow | null;
  readonly notes: readonly { readonly author: SeedNoteAuthor; readonly at: Date }[];
}

function everyVisit(now: Date): readonly FoundVisit[] {
  return SEED_CUSTOMERS.flatMap((customer) =>
    customer.jobs.flatMap((job) => {
      const resolved = resolveSeedJob(job, now);
      return resolved.visits.map((visit, index) => ({
        label: `${customer.displayName} → "${job.title}" visit ${index + 1}`,
        status: visit.plan.status,
        crew: visit.plan.crew,
        window: visit.window,
        notes: visit.notes.map((note) => ({
          author: note.plan.author,
          at: note.recordedAt,
        })),
      }));
    }),
  );
}

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
  it('is coherent whenever the seed runs', () => {
    for (const now of SEED_RUN_TIMES) {
      expect(() =>
        assertSeedScenariosAreCoherent(SEED_CUSTOMERS, now),
      ).not.toThrow();
    }
  });

  it('never presents a Visit that has not happened yet as finished work', () => {
    // The defect this dataset replaced: a Visit whose window is still ahead was seeded `COMPLETED`, so
    // the field work appeared finished before anybody could have done it.
    for (const now of SEED_RUN_TIMES) {
      for (const found of everyVisit(now)) {
        if (
          found.window === null ||
          found.window.scheduledStart.getTime() <= now.getTime()
        ) {
          continue;
        }
        expect(
          ['DRAFT', 'SCHEDULED', 'CANCELED'],
          `${found.label} is scheduled in the future`,
        ).toContain(found.status);
      }
    }
  });

  it('only marks a Visit COMPLETED once its window has ended', () => {
    for (const now of SEED_RUN_TIMES) {
      for (const found of everyVisit(now)) {
        if (found.status !== 'COMPLETED') {
          continue;
        }
        expect(
          found.window?.scheduledEnd.getTime() ?? Number.POSITIVE_INFINITY,
          `${found.label} is COMPLETED`,
        ).toBeLessThan(now.getTime());
      }
    }
  });

  it('shows work under way only in Visits whose window has started', () => {
    const now = SEED_RUN_TIMES[2] as Date;
    const inFlight = everyVisit(now).filter(
      (found) =>
        found.status === 'EN_ROUTE' ||
        found.status === 'ON_SITE' ||
        found.status === 'IN_PROGRESS',
    );

    expect(inFlight.length).toBeGreaterThan(0);
    for (const found of inFlight) {
      expect(found.window?.scheduledStart.getTime()).toBeLessThanOrEqual(
        now.getTime(),
      );
    }
  });

  it('covers every Job and Visit status so each filter has something to show', () => {
    const jobStatuses = new Set(
      SEED_CUSTOMERS.flatMap((customer) =>
        customer.jobs.map((job) => job.status),
      ),
    );
    const visitStatuses = new Set(
      everyVisit(SEED_RUN_TIMES[2] as Date).map((found) => found.status),
    );

    expect([...jobStatuses].sort()).toEqual([...EVERY_JOB_STATUS].sort());
    expect([...visitStatuses].sort()).toEqual([...EVERY_VISIT_STATUS].sort());
  });

  it('gives every scheduled Visit a crew, and never the same technician twice', () => {
    const now = SEED_RUN_TIMES[2] as Date;

    for (const found of everyVisit(now)) {
      if (found.status === 'DRAFT') {
        continue;
      }
      expect(found.crew.length, `${found.label} has no crew`).toBeGreaterThan(0);
      expect(new Set(found.crew).size).toBe(found.crew.length);
    }
  });

  it('gives the seeded technician QA account work to sign in to', () => {
    const now = SEED_RUN_TIMES[2] as Date;
    const assignedToSeeded = everyVisit(now).filter(
      (found) =>
        found.crew.includes('SEEDED') &&
        found.window !== null &&
        found.status !== 'CANCELED' &&
        found.window.scheduledEnd.getTime() > now.getTime(),
    );

    expect(assignedToSeeded.length).toBeGreaterThan(0);
  });

  it('keeps every seeded history in the past and in order', () => {
    for (const now of SEED_RUN_TIMES) {
      for (const customer of SEED_CUSTOMERS) {
        for (const job of customer.jobs) {
          const resolved = resolveSeedJob(job, now);
          const histories = [
            resolved.statusEventTimes,
            ...resolved.visits.map((visit) => visit.statusEventTimes),
            ...resolved.visits.flatMap((visit) =>
              visit.notes.map((note) => [note.recordedAt]),
            ),
          ];
          for (const times of histories) {
            let previous = Number.NEGATIVE_INFINITY;
            for (const time of times) {
              expect(time.getTime()).toBeGreaterThan(previous);
              expect(time.getTime()).toBeLessThanOrEqual(now.getTime());
              previous = time.getTime();
            }
          }
        }
      }
    }
  });

  it('describes work in the words an office and a crew would use, never an internal tag', () => {
    const seeded = JSON.stringify(SEED_CUSTOMERS);

    expect(seeded).not.toMatch(/dev-seed/i);
    expect(seeded).not.toMatch(/operational seed/i);
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
  const baseCustomer = SEED_CUSTOMERS[0] as SeedCustomerPlan;
  const baseJob = baseCustomer.jobs[0] as SeedJobPlan;

  function oneCustomer(job: SeedJobPlan): readonly SeedCustomerPlan[] {
    return [{ ...baseCustomer, jobs: [job] }];
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
            summary: 'Reported as finished three days before it was due to happen.',
          },
        },
      ],
    };

    expect(() =>
      assertSeedScenariosAreCoherent(oneCustomer(broken), now),
    ).toThrow(/scheduled in the future/);
  });

  it('refuses a Job that awaits review while a Visit is still open', () => {
    const broken: SeedJobPlan = {
      ...baseJob,
      status: 'PENDING_REVIEW',
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
      status: 'IN_PROGRESS',
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
      status: 'IN_PROGRESS',
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

