import {
  ACTIVE_VISIT_STATUSES,
  JOB_STATUS_TRANSITIONS,
  TERMINAL_VISIT_STATUSES,
  applicableJobStatusTransitions,
  isPermittedJobStatusTransition,
  isReschedulableVisitStatus,
  isTerminalVisitStatus,
} from './job.types.js';

/**
 * The Job and Visit lifecycle vocabulary (`BR-058`, `BR-062`, `BR-073`).
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
