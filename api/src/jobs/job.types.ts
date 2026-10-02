import type { InferSelectModel } from 'drizzle-orm';
import { jobs } from '../database/schema.js';

/*
 * The Job & Visit domain's stable, machine-readable vocabulary (`BR-041`).
 *
 * These codes cross application boundaries, so they have exactly one definition here and are
 * re-exported by the reads that project them (`customers/customer-detail.dto.ts`,
 * `home/manager-home.dto.ts`) rather than declared a second time. Clients present a localized label
 * for a code and must never invent a parallel vocabulary (`BR-028`, `BR-041`, `BR-042`).
 */

/** The Job status vocabulary the API exchanges (`BR-058`). */
export const JOB_STATUSES = [
  'NEW',
  'ACTIVE',
  'COMPLETED',
  'CANCELED',
] as const;
export type JobStatus = (typeof JOB_STATUSES)[number];

/** The Visit status vocabulary the API exchanges (`BR-074`). */
export const VISIT_STATUSES = [
  'DRAFT',
  'SCHEDULED',
  'EN_ROUTE',
  'ON_SITE',
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELED',
] as const;
export type VisitStatus = (typeof VISIT_STATUSES)[number];

/** The follow-up request lifecycle (`BR-041`): proposed work, not a scheduled Visit. */
export const FOLLOW_UP_VISIT_REQUEST_STATUSES = [
  'PENDING',
  'NEEDS_CLARIFICATION',
  'APPROVED',
  'REJECTED',
] as const;
export type FollowUpVisitRequestStatus =
  (typeof FOLLOW_UP_VISIT_REQUEST_STATUSES)[number];

/**
 * Which side of a follow-up request's clarification conversation wrote a message (`BR-FV-012`).
 *
 * The side is **derived** from the request's own requester rather than stored: the requester is the
 * member who raised it, and every other author is the office answering it. It is exchanged so a client
 * presents "Your answer" and "Office" from the API's own answer instead of comparing ids itself
 * (`BR-041`), and it is not a stored vocabulary — no column carries it (`BR-042`).
 */
export const FOLLOW_UP_VISIT_REQUEST_MESSAGE_AUTHOR_KINDS = [
  'REQUESTER',
  'OFFICE',
] as const;
export type FollowUpVisitRequestMessageAuthorKind =
  (typeof FOLLOW_UP_VISIT_REQUEST_MESSAGE_AUTHOR_KINDS)[number];

/**
 * The Visit statuses that count as active work (`BR-060`).
 *
 * A `DRAFT` Visit is not scheduled work, and a `COMPLETED` or `CANCELED` Visit is historical: none of
 * them is active, so a Job holding only these still surfaces the derived "Needs Scheduling" condition.
 */
export const ACTIVE_VISIT_STATUSES = [
  'SCHEDULED',
  'EN_ROUTE',
  'ON_SITE',
  'IN_PROGRESS',
] as const;

/** The assignment role codes a technician can hold on a Visit (`BR-068`). */
export const ASSIGNMENT_ROLE_CODES = ['LEAD', 'TECHNICIAN'] as const;
export type AssignmentRoleCode = (typeof ASSIGNMENT_ROLE_CODES)[number];

/**
 * The Job lifecycle's **structurally** permitted transitions (`BR-058`).
 *
 * This is the authoritative structural validation contract: a destination the table does not list is
 * not a business transition and the API refuses it with `JOB_STATUS_TRANSITION_NOT_ALLOWED`. The
 * table is declared once here and is projected to clients through the Job read
 * (`allowedStatusTransitions`), so a client never holds a second copy of the same rule (`BR-041`).
 *
 * The table answers *which destinations exist*; it does not answer *whether a listed destination may
 * be entered right now*. That is a runtime eligibility question owned by its own rule — `BR-062` for
 * `COMPLETED` — and the API answers it with the rule's own error rather than by hiding a structurally
 * valid destination from the read. A client may therefore offer a destination the Job does not
 * currently qualify for and present the refusal (`BR-042`).
 *
 * An open Job may be moved **directly** to any destination the table lists for its status, in one
 * operation: `NEW` → `ACTIVE` starts execution, and `NEW`/`ACTIVE` → `COMPLETED` or `CANCELED` closes
 * the request. `NEW` is never a destination for an open Job: an explicit reopen (`BR-063`) moves a
 * terminal Job back to `ACTIVE`, which is the status a Job with historical Visits really has
 * (`docs/tracker/051-job-visit-lifecycle-redesign.md`). `COMPLETED` and `CANCELED` are terminal and
 * offer exactly that one destination.
 */
export const JOB_STATUS_TRANSITIONS: Readonly<Record<JobStatus, readonly JobStatus[]>> = {
  NEW: ['ACTIVE', 'COMPLETED', 'CANCELED'],
  ACTIVE: ['COMPLETED', 'CANCELED'],
  COMPLETED: ['ACTIVE'],
  CANCELED: ['ACTIVE'],
};

/** Whether `BR-058` permits a Job to move from [from] to [to]. */
export function isPermittedJobStatusTransition(
  from: JobStatus,
  to: JobStatus,
): boolean {
  return JOB_STATUS_TRANSITIONS[from].includes(to);
}

/**
 * The Job statuses a client may ask the API to move a Job to today.
 *
 * The list is **structural**: every destination `BR-058` permits for the Job's status, whether or not
 * the Job currently qualifies for it. `COMPLETED` keeps its runtime eligibility check at the API
 * (`BR-062`'s open-Visit invariant), so a client offers the destination and presents the API's refusal
 * rather than holding a second, staler copy of the rule (`BR-041`, `BR-042`).
 *
 * `CANCELED` **is** included: cancellation is applied, cascading to the Job's open Visits in the same
 * transaction (`BR-065`). `BR-064`'s **structured** cancellation-reason catalogue is still an `OPEN
 * QUESTION`, so the request's optional `note` is the only explanation recorded and no reason vocabulary
 * is invented (`BR-042`, `docs/api/job-actions.md` §10).
 */
export function applicableJobStatusTransitions(
  status: JobStatus,
): readonly JobStatus[] {
  return JOB_STATUS_TRANSITIONS[status];
}

/**
 * The Visit statuses that are **terminal** — the field attempt is over (`BR-074`).
 *
 * `BR-083` classifies an active Visit by exactly this set, and `BR-062`'s completion invariant does
 * too: a Visit that is not terminal is open work and keeps its Job from being closed.
 *
 * It is deliberately **not** `ACTIVE_VISIT_STATUSES`. A `DRAFT` Visit is not scheduled work for the
 * derived "Needs Scheduling" signal (`BR-060`), but it is a field attempt that has not happened yet,
 * so it is remaining work for completion. The two named sets answer different questions and neither
 * replaces the other (`BR-060`, `BR-083` Notes).
 */
export const TERMINAL_VISIT_STATUSES = [
  'COMPLETED',
  'CANCELED',
] as const;

/** Whether a Visit in [status] is terminal, i.e. no longer open work (`BR-074`, `BR-083`). */
export function isTerminalVisitStatus(status: VisitStatus): boolean {
  return (TERMINAL_VISIT_STATUSES as readonly VisitStatus[]).includes(status);
}

/**
 * The Visit outcome vocabulary the API exchanges (`BR-078`).
 *
 * An outcome records what resulted from the field attempt; a Visit status records whether the attempt
 * is over (`BR-074`). The two are separate concepts and are never merged, so the codes are declared
 * once here and validated at the API boundary rather than only by the database's own check
 * constraints (`BR-041`). A code Servora does not have is refused, never stored.
 *
 * The follow-up expectation is derived from the code (`BR-078`): only `RESOLVED` expects none.
 */
export const VISIT_OUTCOME_CODES = [
  'RESOLVED',
  'NEEDS_FOLLOW_UP',
  'NEEDS_PARTS',
  'UNABLE_TO_COMPLETE',
] as const;

export type VisitOutcomeCode = (typeof VISIT_OUTCOME_CODES)[number];

/**
 * Whether a recorded outcome expects a follow-up field attempt (`BR-078`).
 *
 * The follow-up expectation is derived from the outcome code rather than stored: only `RESOLVED`
 * expects none, so every other code — `NEEDS_FOLLOW_UP`, `NEEDS_PARTS`, `UNABLE_TO_COMPLETE` —
 * expects one. A Visit that holds no outcome (`null`) holds no expectation either, because no field
 * attempt has recorded a result to derive one from (`BR-077`, `BR-079`).
 */
export function expectsFollowUpVisit(
  outcomeCode: VisitOutcomeCode | null,
): boolean {
  return outcomeCode !== null && outcomeCode !== 'RESOLVED';
}

/**
 * The Visit lifecycle's **structurally** permitted transitions (`BR-074`, `BR-075`).
 *
 * This is the authoritative structural validation contract for a Visit. `BR-074` deliberately does
 * **not** enforce a strict next-status chain on field actions: field reality requires correcting a status
 * mistake, catching up after a missed tap, and moving backward when the work genuinely goes back a step.
 * A Visit may therefore be moved **directly** between any two working statuses, in either direction, in
 * one operation.
 *
 * `CANCELED` is deliberately absent. `BR-066` makes it a **dispatch** action, no capability authorizes a
 * field caller to take it, and `BR-076` requires a structured cancellation reason — so accepting it would
 * mean inventing both the authority and the vocabulary (`BR-042`, `ADR-019` D7). It stays truly terminal,
 * and no working status is reachable from it.
 *
 * `COMPLETED` is reached by the **explicit completion operation** rather than this table (`BR-077`), so it
 * has no destination here. `BR-074` states that a `COMPLETED` Visit may be reopened into a working status;
 * that turn is **not implemented** and stays an `OPEN QUESTION` (`docs/api/job-actions.md` §10), because
 * offering it would mean deciding the reopen's own rules (`BR-042`).
 *
 * The table answers *which destinations exist* for a Visit; it does not answer *whether a destination
 * may be entered right now*. That is a runtime eligibility question owned by its own rule — `BR-072` for
 * a Visit becoming `SCHEDULED`, `BR-077` for the outcome a completion requires — and the API answers it
 * with that rule's own error rather than by removing a structurally valid destination (`BR-058` follows
 * the same split for Jobs).
 */
export const VISIT_STATUS_TRANSITIONS: Readonly<
  Record<VisitStatus, readonly VisitStatus[]>
> = {
  DRAFT: [],
  SCHEDULED: ['EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'],
  EN_ROUTE: ['SCHEDULED', 'ON_SITE', 'IN_PROGRESS'],
  ON_SITE: ['SCHEDULED', 'EN_ROUTE', 'IN_PROGRESS'],
  IN_PROGRESS: ['SCHEDULED', 'EN_ROUTE', 'ON_SITE'],
  COMPLETED: [],
  // Truly terminal (`BR-074`): a canceled Visit never returns to a working status, and no field caller
  // may take one (`BR-066`).
  CANCELED: [],
};

/** Whether `BR-074`/`BR-075` permit a Visit to move from [from] to [to]. */
export function isPermittedVisitStatusTransition(
  from: VisitStatus,
  to: VisitStatus,
): boolean {
  return VISIT_STATUS_TRANSITIONS[from].includes(to);
}

/**
 * Whether the transition is the correction `BR-075` originally named rather than the normal progression.
 *
 * `BR-074` now permits free movement between the working statuses, so a reversal needs no special
 * permission and is a status change like any other. The **flag** stays as narrow as the business rule
 * that named it — `EN_ROUTE → SCHEDULED`, a technician who left for a Property without arriving —
 * because `visit_status_history_correction_check` declares exactly that pair, and because generalizing it
 * is not needed: every status event already carries its previous status, its new status, its actor and
 * its timestamp, which is the audit `BR-067` requires (`BR-075` Notes). It is recorded as a status event
 * that says so (`is_correction`), never as a rewrite of the status it corrects (`BR-067`, `BR-075`).
 */
export function isVisitStatusCorrection(
  from: VisitStatus,
  to: VisitStatus,
): boolean {
  return from === 'EN_ROUTE' && to === 'SCHEDULED';
}

/**
 * The destinations a client may select for a Visit in [status] (`BR-074`).
 *
 * A working Visit is offered every other working status. The list is the backend's own answer, so no
 * client holds a copy of the lifecycle (`BR-041`); `CANCELED` is never in it (`BR-066`), and `COMPLETED`
 * is a destination of the completion operation rather than of this list (`BR-077`).
 */
export function applicableVisitStatusTransitions(
  status: VisitStatus,
): readonly VisitStatus[] {
  return VISIT_STATUS_TRANSITIONS[status];
}

/**
 * The Visit statuses a Visit may be rescheduled in (`BR-073`).
 *
 * A reschedule edits the existing Visit, and `BR-073` permits it **only** while the Visit is
 * `SCHEDULED`: an `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`, `COMPLETED` or `CANCELED` Visit cannot be
 * rescheduled.
 */
export const RESCHEDULABLE_VISIT_STATUSES = ['SCHEDULED'] as const;

/** Whether a Visit in [status] may be rescheduled (`BR-073`). */
export function isReschedulableVisitStatus(status: VisitStatus): boolean {
  return (RESCHEDULABLE_VISIT_STATUSES as readonly VisitStatus[]).includes(
    status,
  );
}

/**
 * A Job row (`BR-021`, `BR-052`).
 *
 * The Job & Visit domain owns this entity, so its row type belongs to this module; the customer
 * projections re-export it rather than redeclaring it (`docs/domain/job-visit-domain-model.md` §5).
 */
export type Job = InferSelectModel<typeof jobs>;
