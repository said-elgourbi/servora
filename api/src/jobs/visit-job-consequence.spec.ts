import {
  VISIT_OUTCOME_CODES,
  VISIT_STATUSES,
  isPermittedJobStatusTransition,
  type JobStatus,
  type VisitOutcomeCode,
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

describe('the Visit to Job consequence', () => {
  it('moves the Job to IN_PROGRESS when field work starts or continues', () => {
    // `BR-058`'s meaning of `IN_PROGRESS` is "work has started and/or work remains", which is exactly
    // what these three field events report.
    for (const to of ['EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'] as const) {
      expect(jobStatusConsequenceForVisitTransition(to, null, false)).toBe(
        'IN_PROGRESS',
      );
      // A resolving completion that happens to be in flight is irrelevant to a non-completing event.
      expect(jobStatusConsequenceForVisitTransition(to, 'RESOLVED', true)).toBe(
        'IN_PROGRESS',
      );
    }
  });

  it('lets a resolved Visit put the Job in review, but only while BR-061 holds', () => {
    // `BR-061` states both the entry condition for `PENDING_REVIEW` and the outcome of failing it.
    expect(
      jobStatusConsequenceForVisitTransition('COMPLETED', 'RESOLVED', true),
    ).toBe('PENDING_REVIEW');
    expect(
      jobStatusConsequenceForVisitTransition('COMPLETED', 'RESOLVED', false),
    ).toBe('IN_PROGRESS');
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
      // Even where `BR-061`'s conditions happen to hold, an unresolved outcome is not "awaiting review".
      expect(
        jobStatusConsequenceForVisitTransition('COMPLETED', outcomeCode, true),
      ).toBe('IN_PROGRESS');
      expect(
        jobStatusConsequenceForVisitTransition('COMPLETED', outcomeCode, false),
      ).toBe('IN_PROGRESS');
    }
  });

  it('gives the BR-075 correction no Job consequence at all', () => {
    // `BR-058` has no backwards Job destination and `NEW` is not one for an open Job, so mirroring a
    // correction would need two rules the product has not defined (`BR-042`, `ADR-019` D4).
    expect(
      jobStatusConsequenceForVisitTransition('SCHEDULED', null, true),
    ).toBeNull();
  });

  it('moves nothing for a Visit destination this slice never applies', () => {
    // `DRAFT` is not a destination of any transition, and `CANCELED`/`NO_SHOW` are dispatch actions no
    // capability authorizes today (`BR-066`, `BR-076`, `ADR-019` D7). None of them moves the Job.
    expect(jobStatusConsequenceForVisitTransition('DRAFT', null, true)).toBeNull();
    expect(jobStatusConsequenceForVisitTransition('CANCELED', null, true)).toBeNull();
    expect(jobStatusConsequenceForVisitTransition('NO_SHOW', null, true)).toBeNull();
  });

  it('never completes, cancels or otherwise terminalises a Job', () => {
    for (const to of VISIT_STATUSES) {
      for (const outcomeCode of [null, ...VISIT_OUTCOME_CODES]) {
        for (const jobMayAwaitReview of [true, false]) {
          const destination = jobStatusConsequenceForVisitTransition(
            to,
            outcomeCode,
            jobMayAwaitReview,
          );
          // `BR-062` makes closing a Job an explicit authorized office action and `BR-064` requires a
          // structured cancellation reason, so no field event may produce either.
          expect(destination).not.toBe('COMPLETED');
          expect(destination).not.toBe('CANCELED');
          expect(destination).not.toBe('NEW');
        }
      }
    }
  });

  it('only ever produces a status BR-058 permits the Job to move to', () => {
    // The consequence is applied as a Job transition, so it is always the target of one that exists. A
    // destination equal to the Job's current status is not a transition and writes nothing (`BR-058`).
    for (const jobStatus of OPEN_JOB_STATUSES) {
      for (const to of VISIT_STATUSES) {
        for (const outcomeCode of [null, ...VISIT_OUTCOME_CODES]) {
          for (const jobMayAwaitReview of [true, false]) {
            const destination = jobStatusConsequenceForVisitTransition(
              to,
              outcomeCode,
              jobMayAwaitReview,
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
