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
  'SCHEDULED',
  'IN_PROGRESS',
  'PENDING_REVIEW',
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
  'NO_SHOW',
] as const;
export type VisitStatus = (typeof VISIT_STATUSES)[number];

/**
 * The Visit statuses that count as active work (`BR-060`).
 *
 * A `DRAFT` Visit is not scheduled work, and a `CANCELED`, `NO_SHOW` or `COMPLETED` Visit is
 * historical: none of them is active, so none of them keeps a Job from awaiting review.
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
 * be entered right now*. That is a runtime eligibility question owned by its own rule — `BR-061` for
 * `PENDING_REVIEW` and `BR-062` for `COMPLETED` — and the API answers it with the rule's own error
 * rather than by hiding a structurally valid destination from the read. A client may therefore offer
 * a destination the Job does not currently qualify for and present the refusal (`BR-042`).
 *
 * An open Job may be moved **directly** to any other open status — backwards included, so
 * `IN_PROGRESS` → `SCHEDULED` is one transition, not a walk through the lifecycle — or closed, in one
 * operation. `NEW` is not a destination for an open Job: it is reached only by the explicit reopen
 * (`BR-063`). `COMPLETED` and `CANCELED` are terminal and offer exactly that one destination.
 */
export const JOB_STATUS_TRANSITIONS: Readonly<
  Record<JobStatus, readonly JobStatus[]>
> = {
  NEW: ['SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED', 'CANCELED'],
  SCHEDULED: ['IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED', 'CANCELED'],
  IN_PROGRESS: ['SCHEDULED', 'PENDING_REVIEW', 'COMPLETED', 'CANCELED'],
  PENDING_REVIEW: ['SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELED'],
  COMPLETED: ['NEW'],
  CANCELED: ['NEW'],
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
 * `CANCELED` is a transition `BR-058` permits but not one this API can apply: `BR-064` requires a
 * **structured** Job-cancellation reason and its catalogue is an **OPEN QUESTION**, so accepting a
 * cancellation would mean inventing the reason vocabulary (`BR-042`). Cancelling a Job therefore
 * stays unavailable until product ownership defines that catalogue, and the read tells clients so by
 * leaving `CANCELED` out of `allowedStatusTransitions` rather than drawing an action the API would
 * have to refuse.
 *
 * The list is **structural**: every other destination `BR-058` permits for the Job's status, whether
 * or not the Job currently qualifies for it. `PENDING_REVIEW` (`BR-061`) and `COMPLETED` (`BR-062`)
 * keep their runtime eligibility checks at the API, so a client offers the destination and presents
 * the API's refusal rather than holding a second, staler copy of the rule (`BR-041`, `BR-042`).
 */
export function applicableJobStatusTransitions(
  status: JobStatus,
): readonly JobStatus[] {
  return JOB_STATUS_TRANSITIONS[status].filter((to) => to !== 'CANCELED');
}

/**
 * The Visit statuses that are **historical** — the field attempt is over (`BR-074`).
 *
 * `BR-083` classifies an active Visit by exactly this set, and `BR-062`'s completion invariant does
 * too: a Visit that is not historical is open work and keeps its Job from being closed.
 *
 * It is deliberately **not** `ACTIVE_VISIT_STATUSES`. A `DRAFT` Visit is not scheduled work for the
 * derived "Needs Scheduling" signal (`BR-060`), but it is a field attempt that has not happened yet,
 * so it is remaining work for completion. The two named sets answer different questions and neither
 * replaces the other (`BR-060`, `BR-083` Notes).
 */
export const TERMINAL_VISIT_STATUSES = [
  'COMPLETED',
  'CANCELED',
  'NO_SHOW',
] as const;

/** Whether a Visit in [status] is historical, i.e. no longer open work (`BR-074`, `BR-083`). */
export function isTerminalVisitStatus(status: VisitStatus): boolean {
  return (TERMINAL_VISIT_STATUSES as readonly VisitStatus[]).includes(status);
}

/**
 * The Visit statuses a Visit may be rescheduled in (`BR-073`).
 *
 * A reschedule edits the existing Visit, and `BR-073` permits it **only** while the Visit is
 * `SCHEDULED`: an `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`, `COMPLETED`, `CANCELED` or `NO_SHOW` Visit
 * cannot be rescheduled.
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
