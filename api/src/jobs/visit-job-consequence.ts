import type {
  JobStatus,
  VisitOutcomeCode,
  VisitStatus,
} from './job.types.js';

/**
 * What the Job's **other** Visits say about the event being applied (`BR-062`).
 *
 * `BR-062`'s invariant is that a Job is never `COMPLETED` while it still has an open Visit — a Visit
 * whose status is anything other than `COMPLETED` or `CANCELED` (`BR-074`; the same classification
 * `BR-083` uses for an active Visit). The mapping below is a pure rule over one event, so the fact it
 * needs is passed in rather than queried here (`BR-041`).
 */
export interface VisitConsequenceContext {
  /**
   * Whether the Job has another Visit that is still open.
   *
   * The Visit this event belongs to has already reached its destination when the consequence is
   * applied, so it is not itself counted: a Job whose only Visit just completed has no open Visit.
   */
  readonly hasOtherOpenVisit: boolean;
}

/** No other Visit is open: a Job whose field work is entirely over. */
const LAST_VISIT: VisitConsequenceContext = { hasOtherOpenVisit: false };

/**
 * The Visit → Job consequence (`BR-058`, `BR-062`; `ADR-019` D4).
 *
 * `BR-058` states that a Job "advances as a consequence of Visit lifecycle events" without naming which
 * ones. `ADR-019` D4 defines the mapping, and this module is that definition in one place: the API
 * applies it inside the transaction that records the Visit transition, and no client derives or sends
 * it (`BR-001`, `BR-041`).
 *
 * A resolving Visit completion completes the Job **when nothing else remains**; `BR-062` forbids a
 * `COMPLETED` Job that still holds an open Visit, so a resolving completion while another Visit is
 * still scheduled or being worked leaves the Job `ACTIVE` and the office sees the remaining attempt on
 * the Job itself. Non-resolving outcomes keep the Job active in every case.
 *
 * @param to the Visit status the field action is moving the Visit to.
 * @param outcomeCode the outcome recorded with the completion, or `null` for any other destination.
 * @param jobStatus the Job's status inside the same transaction.
 * @param context whether the Job still has an open Visit (`BR-062`).
 *
 * @returns the Job status the consequence moves the Job to, or `null` when the event has no Job
 *   consequence at all. A destination equal to the Job's current status is not a transition and writes
 *   nothing (`BR-058`); the caller compares before applying.
 */
export function jobStatusConsequenceForVisitTransition(
  to: VisitStatus,
  outcomeCode: VisitOutcomeCode | null,
  jobStatus: JobStatus,
  context: VisitConsequenceContext = LAST_VISIT,
): JobStatus | null {
  if (jobStatus === 'COMPLETED' || jobStatus === 'CANCELED') {
    return null;
  }

  switch (to) {
    case 'SCHEDULED':
    case 'EN_ROUTE':
    case 'ON_SITE':
    case 'IN_PROGRESS':
      return 'ACTIVE';
    case 'COMPLETED':
      if (outcomeCode !== 'RESOLVED') {
        return 'ACTIVE';
      }
      return context.hasOtherOpenVisit ? 'ACTIVE' : 'COMPLETED';
    default:
      return null;
  }
}
