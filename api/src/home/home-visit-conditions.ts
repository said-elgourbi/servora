import type { VisitStatus } from '../jobs/job.types.js';

/**
 * The derived Visit conditions both home reads present (`BR-072`, `BR-074`).
 *
 * They are defined **once** here rather than in either read, because a condition two screens show
 * must be one question with one answer (`BR-041`): the manager home asks whether a Visit has gone
 * wrong for the operation, and the technician home asks whether the same Visit has gone wrong for
 * the technician doing it. Neither may compute its own version from its own clock and disagree with
 * the other (`BR-001`).
 *
 * Neither is business state: each is derived from an authoritative Visit status and its schedule,
 * and nothing here is stored.
 */

/**
 * The Visit statuses that are not work of the day (`BR-074`).
 *
 * A `CANCELED` or `NO_SHOW` Visit is an attempt that did not happen, so it is excluded from a day's
 * schedule and from its counts rather than being presented as an attempt someone must account for.
 * A `COMPLETED` Visit stays: it is what the day has produced so far.
 */
export const NOT_DAY_WORK_VISIT_STATUSES = ['CANCELED', 'NO_SHOW'] as const;

/**
 * The Visit statuses whose field work has started (`BR-074`).
 *
 * A Visit in one of these has left the schedule and is being executed: it is the technician's
 * present work rather than their next one. It is deliberately **not** `ACTIVE_VISIT_STATUSES` (the
 * `BR-060` scheduling set), which also counts `SCHEDULED`: a scheduled Visit nobody has started is
 * not work under way, it is work still to come.
 */
export const STARTED_VISIT_STATUSES = [
  'EN_ROUTE',
  'ON_SITE',
  'IN_PROGRESS',
] as const;

/** Whether [status] is one of the started statuses above (`BR-074`). */
export function hasVisitStarted(status: VisitStatus): boolean {
  return (STARTED_VISIT_STATUSES as readonly string[]).includes(status);
}

/**
 * Whether a Visit is overdue: still `SCHEDULED` after the window it was scheduled for has ended
 * (`BR-072`, `BR-074`).
 *
 * The scheduled *end* decides, not the start: a Visit whose window is still open has not yet gone
 * wrong, so a technician who has not tapped `EN_ROUTE` inside their own window is not reported as a
 * problem. Nothing outside the schedule is considered — no travel time and no location
 * (`BR-070`, `BR-038`), because Servora has no confirmed rule for either.
 */
export function isVisitOverdue(
  visit: { readonly visitStatus: VisitStatus; readonly scheduledEnd: Date },
  now: Date,
): boolean {
  return (
    visit.visitStatus === 'SCHEDULED' &&
    visit.scheduledEnd.getTime() < now.getTime()
  );
}
