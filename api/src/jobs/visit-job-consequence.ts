import {
  isTerminalVisitStatus,
  type JobStatus,
  type VisitOutcomeCode,
  type VisitStatus,
} from './job.types.js';

/**
 * The Visit → Job consequence (`BR-058`, `BR-061`; `ADR-019` D4).
 *
 * `BR-058` states that a Job "advances as a consequence of Visit lifecycle events" without naming which
 * ones, and `BR-061` owns the conditions a Job must satisfy to await review. `ADR-019` D4 defines the
 * mapping, and this module is that definition in one place: the API applies it inside the transaction
 * that records the Visit transition, and no client derives or sends it (`BR-001`, `BR-041`).
 *
 * The mapping deliberately never completes and never cancels a Job — `BR-062` makes closing a Job an
 * explicit office action and `BR-064` requires a cancellation reason — and never returns a terminal
 * status, so a consequence can never move a Job into `COMPLETED` or `CANCELED`.
 *
 * @param to the Visit status the field action is moving the Visit to.
 * @param outcomeCode the outcome recorded with the completion, or `null` for any other destination.
 * @param jobMayAwaitReview whether `BR-061`'s entry conditions for `PENDING_REVIEW` hold right now:
 *   no Visit remains active or scheduled, and the latest completed Visit's outcome resolves the Job.
 *   It is evaluated by the caller against the state inside the same transaction, because it is a
 *   runtime condition rather than part of this structural mapping.
 * @param jobStatus the Job's status inside the same transaction.
 *
 * @returns the Job status the consequence moves the Job to, or `null` when the event has no Job
 *   consequence at all. A destination equal to the Job's current status is not a transition and writes
 *   nothing (`BR-058`); the caller compares before applying.
 */
export function jobStatusConsequenceForVisitTransition(
  to: VisitStatus,
  outcomeCode: VisitOutcomeCode | null,
  jobMayAwaitReview: boolean,
  jobStatus: JobStatus,
): JobStatus | null {
  // `BR-061`'s invariant read in the other direction: a Job awaits review *because* no Visit remained
  // active, so a Visit moved into a working status takes the Job back out of review. `BR-074`'s free
  // movement makes that reachable in several ways — a reopen out of `COMPLETED`, a step backward, or a
  // skipped step — and none of them may leave an actively worked Visit beside a Job awaiting completion
  // review. `IN_PROGRESS` is the Job's meaning for "work has started and/or work remains", and `BR-058`
  // gives an open Job no other destination to fall back to (`NEW` is reached only by an explicit reopen).
  if (jobStatus === 'PENDING_REVIEW' && !isTerminalVisitStatus(to)) {
    return 'IN_PROGRESS';
  }

  switch (to) {
    // Work has started, or started and continues: `BR-058`'s meaning of `IN_PROGRESS` is "work has
    // started and/or work remains", which is exactly what these three field events report.
    case 'EN_ROUTE':
    case 'ON_SITE':
    case 'IN_PROGRESS':
      return 'IN_PROGRESS';
    case 'COMPLETED':
      // Only a resolving outcome lets the Job await review, and only while `BR-061`'s conditions hold.
      // Every other outcome expects follow-up, which keeps the Job in progress (`BR-061`, `BR-078`).
      return outcomeCode === 'RESOLVED' && jobMayAwaitReview
        ? 'PENDING_REVIEW'
        : 'IN_PROGRESS';
    // `DRAFT` and `SCHEDULED` have no Job consequence: `BR-058` has no Job destination for a field
    // attempt merely being scheduled or returned to draft, and `NEW` is not a destination for an open
    // Job, so mirroring them would need rules the product has not defined (`BR-042`, `ADR-019` D4).
    // `CANCELED` and `NO_SHOW` are dispatch actions no field caller may take (`BR-066`).
    default:
      return null;
  }
}
