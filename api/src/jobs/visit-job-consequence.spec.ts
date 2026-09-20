import {
  VISIT_OUTCOME_CODES,
  VISIT_STATUSES,
  isPermittedJobStatusTransition,
  isTerminalVisitStatus,
  type JobStatus,
  type VisitOutcomeCode,
  type VisitStatus,
} from './job.types.js';
import { jobStatusConsequenceForVisitTransition } from './visit-job-consequence.js';

/**
 * The Visit → Job consequence (`BR-058`, `BR-061`; `ADR-019` D4).
 *
 * The mapping is the one place a field event's effect on the Job's business lifecycle is defined, so it
 * is asserted directly: what each event does, what it deliberately never does, and that every
 * consequence it can produce is a transition `BR-058` actually permits.
 */
const OPEN_JOB_STATUSES: readonly JobStatus[] = [
  'NEW',
  'SCHEDULED',
  'IN_PROGRESS',
  'PENDING_REVIEW',
];

/** The Visit statuses a field caller may move a Visit to — everything but the dispatch pair. */
const WORKING_VISIT_STATUSES: readonly VisitStatus[] = VISIT_STATUSES.filter(
  (status) => status === 'COMPLETED' || !isTerminalVisitStatus(status),
);

describe('the Visit to Job consequence', () => {
  it('moves the Job to IN_PROGRESS when field work starts or continues', () => {
    // `BR-058`'s meaning of `IN_PROGRESS` is "work has started and/or work remains", which is exactly
    // what these three field events report.
    for (const jobStatus of OPEN_JOB_STATUSES) {
      for (const to of ['EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'] as const) {
        expect(
          jobStatusConsequenceForVisitTransition(to, null, false, jobStatus),
        ).toBe('IN_PROGRESS');
        // A resolving completion that happens to be in flight is irrelevant to a non-completing event.
        expect(
          jobStatusConsequenceForVisitTransition(
            to,
            'RESOLVED',
            true,
            jobStatus,
          ),
        ).toBe('IN_PROGRESS');
      }
    }
  });

  it('lets a resolved Visit put the Job in review, but only while BR-061 holds', () => {
    // `BR-061` states both the entry condition for `PENDING_REVIEW` and the outcome of failing it.
    for (const jobMayAwaitReview of [true, false]) {
      expect(
        jobStatusConsequenceForVisitTransition(
          'COMPLETED',
          'RESOLVED',
          jobMayAwaitReview,
          'IN_PROGRESS',
        ),
      ).toBe(jobMayAwaitReview ? 'PENDING_REVIEW' : 'IN_PROGRESS');
    }
  });

  it('keeps the Job in progress for every outcome that expects follow-up', () => {
    // `BR-078`: only `RESOLVED` expects none, so every other outcome leaves work outstanding.
    const followUpOutcomes: readonly VisitOutcomeCode[] =
      VISIT_OUTCOME_CODES.filter((code) => code !== 'RESOLVED');
    expect(followUpOutcomes).toEqual([
      'NEEDS_PARTS',
      'NEEDS_FOLLOWUP',
      'NEEDS_QUOTE_APPROVAL',
      'UNABLE_TO_COMPLETE',
    ]);
    for (const outcomeCode of followUpOutcomes) {
      for (const jobMayAwaitReview of [true, false]) {
        // Even where `BR-061`'s conditions happen to hold, an unresolved outcome is not "awaiting review".
        expect(
          jobStatusConsequenceForVisitTransition(
            'COMPLETED',
            outcomeCode,
            jobMayAwaitReview,
            'IN_PROGRESS',
          ),
        ).toBe('IN_PROGRESS');
      }
    }
  });

  it('takes a Job awaiting review back out of it when a Visit becomes working work again', () => {
    // `BR-061`'s invariant read in the other direction, and the reason it is not cosmetic: `BR-074` lets
    // a technician reopen a `COMPLETED` Visit, step backward or skip a step, and none of those may leave
    // an actively worked Visit beside a Job awaiting completion review. `DRAFT` and `SCHEDULED` are the
    // two destinations this rule actually changes — the other three already imply `IN_PROGRESS` alone.
    for (const to of [
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
    ] as const) {
      for (const jobMayAwaitReview of [true, false]) {
        expect(
          jobStatusConsequenceForVisitTransition(
            to,
            null,
            jobMayAwaitReview,
            'PENDING_REVIEW',
          ),
        ).toBe('IN_PROGRESS');
      }
    }
    // A completion is not this rule's business: `COMPLETED` is historical, so the outcome decides.
    expect(
      jobStatusConsequenceForVisitTransition(
        'COMPLETED',
        'RESOLVED',
        true,
        'PENDING_REVIEW',
      ),
    ).toBe('PENDING_REVIEW');
  });

  it('gives a step backward or a return to draft no Job consequence of its own', () => {
    // `BR-058` has no backwards Job destination and `NEW` is not one for an open Job, so a Visit merely
    // being returned to `SCHEDULED` or `DRAFT` mirrors nothing (`BR-042`, `ADR-019` D4). `BR-075`'s
    // original correction is the `SCHEDULED` case below, and no field event moves a Job out of
    // `PENDING_REVIEW` except by the rule above.
    for (const to of ['DRAFT', 'SCHEDULED'] as const) {
      for (const jobStatus of ['NEW', 'SCHEDULED', 'IN_PROGRESS'] as const) {
        expect(
          jobStatusConsequenceForVisitTransition(to, null, true, jobStatus),
        ).toBeNull();
      }
    }
  });

  it('moves nothing for the destinations no field caller may take', () => {
    // `CANCELED` and `NO_SHOW` are dispatch actions no capability authorizes, so no field event reaches
    // them and neither moves the Job (`BR-066`, `BR-076`, `ADR-019` D7).
    for (const jobStatus of OPEN_JOB_STATUSES) {
      expect(
        jobStatusConsequenceForVisitTransition('CANCELED', null, true, jobStatus),
      ).toBeNull();
      expect(
        jobStatusConsequenceForVisitTransition('NO_SHOW', null, true, jobStatus),
      ).toBeNull();
    }
  });

  it('never completes, cancels or otherwise terminalises a Job', () => {
    for (const jobStatus of OPEN_JOB_STATUSES) {
      for (const to of VISIT_STATUSES) {
        for (const outcomeCode of [null, ...VISIT_OUTCOME_CODES]) {
          for (const jobMayAwaitReview of [true, false]) {
            const destination = jobStatusConsequenceForVisitTransition(
              to,
              outcomeCode,
              jobMayAwaitReview,
              jobStatus,
            );
            // `BR-062` makes closing a Job an explicit authorized office action and `BR-064` requires a
            // structured cancellation reason, so no field event may produce either.
            expect(destination).not.toBe('COMPLETED');
            expect(destination).not.toBe('CANCELED');
            expect(destination).not.toBe('NEW');
          }
        }
      }
    }
  });

  it('only ever produces a status BR-058 permits the Job to move to', () => {
    // The consequence is applied as a Job transition, so it is always the target of one that exists. A
    // destination equal to the Job's current status is not a transition and writes nothing (`BR-058`).
    for (const jobStatus of OPEN_JOB_STATUSES) {
      for (const to of WORKING_VISIT_STATUSES) {
        for (const outcomeCode of [null, ...VISIT_OUTCOME_CODES]) {
          for (const jobMayAwaitReview of [true, false]) {
            const destination = jobStatusConsequenceForVisitTransition(
              to,
              outcomeCode,
              jobMayAwaitReview,
              jobStatus,
            );
            if (destination === null || destination === jobStatus) {
              continue;
            }
            expect(isPermittedJobStatusTransition(jobStatus, destination)).toBe(
              true,
            );
          }
        }
      }
    }
  });
});
