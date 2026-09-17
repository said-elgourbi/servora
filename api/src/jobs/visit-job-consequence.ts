import type { JobStatus, VisitOutcomeCode, VisitStatus } from './job.types.js';

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
 *
 * @returns the Job status the consequence moves the Job to, or `null` when the event has no Job
 *   consequence at all. A destination equal to the Job's current status is not a transition and writes
 *   nothing (`BR-058`); the caller compares before applying.
 */
export function jobStatusConsequenceForVisitTransition(
  to: VisitStatus,
  outcomeCode: VisitOutcomeCode | null,
  jobMayAwaitReview: boolean,
): JobStatus | null {
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
    // `EN_ROUTE → SCHEDULED` is `BR-075`'s correction, and it has no Job consequence: `BR-058` has no
    // backwards Job destination and `NEW` is not one for an open Job, so mirroring the correction
    // would need rules the product has not defined (`BR-042`, `ADR-019` D4).
    default:
      return null;
  }
}
