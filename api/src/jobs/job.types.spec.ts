import {
  ACTIVE_VISIT_STATUSES,
  JOB_STATUS_TRANSITIONS,
  TERMINAL_VISIT_STATUSES,
  VISIT_OUTCOME_CODES,
  VISIT_STATUSES,
  VISIT_STATUS_TRANSITIONS,
  applicableJobStatusTransitions,
  applicableVisitStatusTransitions,
  isPermittedJobStatusTransition,
  isPermittedVisitStatusTransition,
  isReschedulableVisitStatus,
  isTerminalVisitStatus,
  isVisitStatusCorrection,
} from './job.types.js';

/**
 * The Job and Visit lifecycle vocabulary (`BR-058`, `BR-062`, `BR-073`, `BR-074`, `BR-075`,
 * `BR-078`).
 *
 * These tables are the validation contract the API enforces and the list a client draws its actions
 * from, so they are asserted directly rather than only through a route.
 */
describe('job lifecycle vocabulary', () => {
  it('permits exactly the destinations BR-058 lists', () => {
    expect(JOB_STATUS_TRANSITIONS).toEqual({
      NEW: ['SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED', 'CANCELED'],
      SCHEDULED: ['IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED', 'CANCELED'],
      IN_PROGRESS: ['SCHEDULED', 'PENDING_REVIEW', 'COMPLETED', 'CANCELED'],
      PENDING_REVIEW: ['SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELED'],
      COMPLETED: ['NEW'],
      CANCELED: ['NEW'],
    });
  });

  it('permits an open Job to move directly to any other open status, backwards included', () => {
    // A manager selects where the Job should be, not the next rung of a ladder, and one operation
    // carries it there (`BR-058`, `BR-067`).
    expect(isPermittedJobStatusTransition('NEW', 'COMPLETED')).toBe(true);
    expect(isPermittedJobStatusTransition('NEW', 'PENDING_REVIEW')).toBe(true);
    expect(isPermittedJobStatusTransition('SCHEDULED', 'COMPLETED')).toBe(true);
    expect(isPermittedJobStatusTransition('SCHEDULED', 'PENDING_REVIEW')).toBe(
      true,
    );
    expect(isPermittedJobStatusTransition('IN_PROGRESS', 'SCHEDULED')).toBe(
      true,
    );
    expect(isPermittedJobStatusTransition('PENDING_REVIEW', 'IN_PROGRESS')).toBe(
      true,
    );
    expect(isPermittedJobStatusTransition('PENDING_REVIEW', 'SCHEDULED')).toBe(
      true,
    );
  });

  it('refuses a destination BR-058 does not list', () => {
    // Standing still is not a transition, `NEW` is reached only by reopening (`BR-063`), and a Job in
    // a terminal status leaves only that way.
    expect(isPermittedJobStatusTransition('IN_PROGRESS', 'IN_PROGRESS')).toBe(
      false,
    );
    expect(isPermittedJobStatusTransition('SCHEDULED', 'NEW')).toBe(false);
    expect(isPermittedJobStatusTransition('IN_PROGRESS', 'NEW')).toBe(false);
    expect(isPermittedJobStatusTransition('COMPLETED', 'IN_PROGRESS')).toBe(
      false,
    );
    expect(isPermittedJobStatusTransition('CANCELED', 'SCHEDULED')).toBe(false);
  });

  it('permits reopening a completed or canceled job only to NEW', () => {
    // Reopening never produces IN_PROGRESS: it does not imply work is underway (`BR-063`).
    expect(isPermittedJobStatusTransition('COMPLETED', 'NEW')).toBe(true);
    expect(isPermittedJobStatusTransition('CANCELED', 'NEW')).toBe(true);
    expect(isPermittedJobStatusTransition('COMPLETED', 'IN_PROGRESS')).toBe(
      false,
    );
  });

  it('offers a client every structurally permitted destination except cancellation', () => {
    // Cancellation is a permitted transition whose structured reason catalogue is undefined, so it
    // is not offered until product ownership defines the catalogue (`BR-064`, `BR-042`).
    expect(applicableJobStatusTransitions('NEW')).toEqual([
      'SCHEDULED',
      'IN_PROGRESS',
      'PENDING_REVIEW',
      'COMPLETED',
    ]);
    expect(applicableJobStatusTransitions('SCHEDULED')).toEqual([
      'IN_PROGRESS',
      'PENDING_REVIEW',
      'COMPLETED',
    ]);
    expect(applicableJobStatusTransitions('IN_PROGRESS')).toEqual([
      'SCHEDULED',
      'PENDING_REVIEW',
      'COMPLETED',
    ]);
    expect(applicableJobStatusTransitions('PENDING_REVIEW')).toEqual([
      'SCHEDULED',
      'IN_PROGRESS',
      'COMPLETED',
    ]);
    expect(applicableJobStatusTransitions('COMPLETED')).toEqual(['NEW']);
    expect(applicableJobStatusTransitions('CANCELED')).toEqual(['NEW']);
    for (const from of Object.keys(JOB_STATUS_TRANSITIONS)) {
      expect(
        applicableJobStatusTransitions(
          from as keyof typeof JOB_STATUS_TRANSITIONS,
        ),
      ).not.toContain('CANCELED');
    }
  });

  it('leaves runtime eligibility to the rule that owns it rather than to the table', () => {
    // The list is structural: `BR-061` decides whether `PENDING_REVIEW` may be entered now and
    // `BR-062` decides whether the Job may be closed, so both stay in the list and the API answers the
    // attempt with its own error instead of hiding a structurally valid destination (`BR-041`).
    expect(applicableJobStatusTransitions('NEW')).toContain('PENDING_REVIEW');
    expect(applicableJobStatusTransitions('IN_PROGRESS')).toContain(
      'COMPLETED',
    );

    // `NEW` is never a destination for an open Job: it is reached by reopening a terminal one.
    const openStatuses = [
      'NEW',
      'SCHEDULED',
      'IN_PROGRESS',
      'PENDING_REVIEW',
    ] as const;
    for (const from of openStatuses) {
      expect(applicableJobStatusTransitions(from)).not.toContain('NEW');
    }
  });

  it('treats exactly the scheduled and started Visit statuses as active work', () => {
    // A DRAFT Visit is not scheduled work, and a COMPLETED, CANCELED or NO_SHOW Visit is historical
    // (`BR-060`, `BR-061`).
    expect([...ACTIVE_VISIT_STATUSES]).toEqual([
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
    ]);
  });

  it('classifies a Visit as historical by the statuses BR-074 calls terminal', () => {
    // `BR-083` tests for an active Visit by this set, and `BR-062`'s completion invariant reuses it.
    expect([...TERMINAL_VISIT_STATUSES]).toEqual([
      'COMPLETED',
      'CANCELED',
      'NO_SHOW',
    ]);
    for (const status of TERMINAL_VISIT_STATUSES) {
      expect(isTerminalVisitStatus(status)).toBe(true);
    }

    // A DRAFT Visit is not scheduled work for the derived signal (`BR-060`), but it is a field attempt
    // that has not happened yet, so it is remaining work and keeps its Job from being closed.
    expect(isTerminalVisitStatus('DRAFT')).toBe(false);
    for (const status of ACTIVE_VISIT_STATUSES) {
      expect(isTerminalVisitStatus(status)).toBe(false);
    }
  });

  it('permits rescheduling a Visit only while it is scheduled', () => {
    expect(isReschedulableVisitStatus('SCHEDULED')).toBe(true);
    for (const status of [
      'DRAFT',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
      'COMPLETED',
      'CANCELED',
      'NO_SHOW',
    ] as const) {
      expect(isReschedulableVisitStatus(status)).toBe(false);
    }
  });
});

describe('visit lifecycle vocabulary', () => {
  it('permits free movement between the working statuses and reopens a completed Visit', () => {
    // `BR-074`: a Visit is not advanced one step at a time. Every working status is reachable from every
    // other, in either direction, and `COMPLETED` is reopenable — while `CANCELED` and `NO_SHOW` stay
    // terminal and remain unreachable from a field action (`BR-066`).
    expect(VISIT_STATUS_TRANSITIONS).toEqual({
      DRAFT: ['SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED'],
      SCHEDULED: ['DRAFT', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED'],
      EN_ROUTE: ['DRAFT', 'SCHEDULED', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED'],
      ON_SITE: ['DRAFT', 'SCHEDULED', 'EN_ROUTE', 'IN_PROGRESS', 'COMPLETED'],
      IN_PROGRESS: ['DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'COMPLETED'],
      COMPLETED: ['DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'],
      CANCELED: [],
      NO_SHOW: [],
    });
  });

  it('moves a Visit directly between working statuses, skipping a step or going backward', () => {
    // `BR-074` is a menu, not a chain: a skipped step and a step backward are each **one** permitted
    // transition, so a technician never has to walk a Visit through statuses it never passed through.
    expect(isPermittedVisitStatusTransition('SCHEDULED', 'IN_PROGRESS')).toBe(true);
    expect(isPermittedVisitStatusTransition('IN_PROGRESS', 'SCHEDULED')).toBe(true);
    expect(isPermittedVisitStatusTransition('ON_SITE', 'EN_ROUTE')).toBe(true);
    expect(isPermittedVisitStatusTransition('DRAFT', 'COMPLETED')).toBe(true);
    // And a completion is reachable from every working status, not only from `IN_PROGRESS`.
    for (const from of [
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
    ] as const) {
      expect(isPermittedVisitStatusTransition(from, 'COMPLETED')).toBe(true);
    }
  });

  it('records EN_ROUTE to SCHEDULED as the correction BR-075 names and nothing else as one', () => {
    // `BR-075`: the flag stays exactly as narrow as the correction the rule named — a technician who left
    // for a Property without arriving. `BR-074`'s free movement needs no flag of its own, because every
    // status event already carries its previous status, its new status, its actor and its timestamp.
    expect(isVisitStatusCorrection('EN_ROUTE', 'SCHEDULED')).toBe(true);
    for (const [from, to] of [
      ['COMPLETED', 'IN_PROGRESS'],
      ['IN_PROGRESS', 'SCHEDULED'],
      ['ON_SITE', 'EN_ROUTE'],
      ['SCHEDULED', 'ON_SITE'],
      ['DRAFT', 'SCHEDULED'],
      ['COMPLETED', 'SCHEDULED'],
    ] as const) {
      expect(isVisitStatusCorrection(from, to)).toBe(false);
    }
  });

  it('refuses standing still and reaches nothing from the truly terminal statuses', () => {
    // A Visit may not "change" to the status it already holds; that is not a transition (`BR-074`).
    for (const status of VISIT_STATUSES) {
      expect(isPermittedVisitStatusTransition(status, status)).toBe(false);
      expect(applicableVisitStatusTransitions(status)).not.toContain(status);
    }
    // `CANCELED` and `NO_SHOW` remain truly terminal: no working status is reachable from them, and a
    // `COMPLETED` Visit is the only historical status that offers a destination (`BR-074`).
    for (const status of ['CANCELED', 'NO_SHOW'] as const) {
      expect(VISIT_STATUS_TRANSITIONS[status]).toEqual([]);
    }
    for (const status of ['CANCELED', 'NO_SHOW'] as const) {
      expect(isTerminalVisitStatus(status)).toBe(true);
      expect(applicableVisitStatusTransitions(status)).toEqual([]);
    }
    expect(applicableVisitStatusTransitions('COMPLETED')).not.toEqual([]);
  });

  it('offers no route to CANCELED or NO_SHOW, which are dispatch actions', () => {
    // `BR-066` makes cancelling a Visit and marking `NO_SHOW` dispatch actions and no capability
    // authorizes one today, so neither is a destination of the field lifecycle (`BR-042`, `ADR-019` D7).
    for (const status of [
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
      'COMPLETED',
      'CANCELED',
      'NO_SHOW',
    ] as const) {
      expect(applicableVisitStatusTransitions(status)).not.toContain('CANCELED');
      expect(applicableVisitStatusTransitions(status)).not.toContain('NO_SHOW');
    }
  });

  it('exposes exactly the five outcome codes BR-078 defines', () => {
    // The follow-up expectation is derived from the code rather than stored beside it (`BR-078`), so the
    // vocabulary is closed and a code Servora does not have is refused at the API boundary (`BR-041`).
    expect([...VISIT_OUTCOME_CODES]).toEqual([
      'RESOLVED',
      'NEEDS_PARTS',
      'NEEDS_FOLLOWUP',
      'NEEDS_QUOTE_APPROVAL',
      'UNABLE_TO_COMPLETE',
    ]);
  });
});
