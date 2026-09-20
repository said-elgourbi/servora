import type { AddressSnapshot } from '../address/address-snapshot.js';
import { HISTORICAL_VISIT_STATUSES } from '../jobs/job.types.js';
import type { AssignmentRoleCode, VisitStatus } from '../jobs/job.types.js';
import type { ScheduleScope, ScheduleScopeKind } from './schedule-scope.js';
import type { AssignableTechnicianDto } from '../technicians/technician.dto.js';

export { VISIT_STATUSES } from '../jobs/job.types.js';
export type { VisitStatus } from '../jobs/job.types.js';

/*
 * The day schedule projection behind `GET /schedule` (`BR-068`, `BR-072`, `BR-074`, `BR-081`).
 *
 * Nothing here is stored and nothing is a new status: the read reports Visits that already exist,
 * their authoritative schedule, their current crew and one derived condition (the overdue Visit
 * `BR-072`/`BR-074` define). The sets it answers with are named here rather than being written twice,
 * so the schedule screen and the manager home cannot classify a Visit differently (`BR-041`).
 */

/**
 * How many unassigned Visits the read returns before `unassigned.total` carries the rest.
 *
 * The lane answers "what still needs a crew?", and a manager acts on the nearest work first; the
 * total is reported beside the items so a capped list never under-reports the queue.
 */
export const SCHEDULE_UNASSIGNED_LIMIT = 20;

/**
 * The Visit statuses that still need a crew.
 *
 * A Visit is unassigned work while it is **open** — anything other than `COMPLETED`, `CANCELED` or
 * `NO_SHOW`, which is exactly `BR-062`'s open-Visit classification and `BR-074`'s historical set —
 * and it has no technician assigned to it. That is the absence of an assignment rather than a status
 * (`BR-042`): a Visit whose field attempt is over does not need anybody, and `BR-071`/`BR-072`
 * explicitly allow a Visit to exist with no technicians while it is being arranged.
 */
export const UNASSIGNED_EXCLUDED_VISIT_STATUSES = HISTORICAL_VISIT_STATUSES;

/** One technician currently assigned to a Visit (`BR-068`), Lead first. */
export interface ScheduleTechnician {
  readonly membershipId: string;
  /** Resolved from the member's profile; `null` when the member has no profile yet (`BR-020`). */
  readonly name: string | null;
  readonly roleCode: AssignmentRoleCode;
}

/**
 * One Visit as the schedule presents it.
 *
 * The schedule fields are absent for a Visit that has no agreed time yet — an unassigned attempt
 * being arranged — so the row states what is known rather than a time nobody agreed to (`BR-072`,
 * `BR-042`).
 */
export interface ScheduleVisit {
  readonly visitId: string;
  readonly visitStatus: VisitStatus;
  readonly scheduledStart: Date | null;
  readonly scheduledEnd: Date | null;
  readonly jobId: string;
  readonly jobNumber: number;
  readonly jobTitle: string;
  readonly customerId: string;
  readonly customerName: string;
  /** The Visit's own preserved location, falling back to the Job's (`BR-056`, `BR-057`). */
  readonly address: AddressSnapshot | null;
  /** The current crew, Lead first (`BR-068`); empty when the Visit has none. */
  readonly technicians: readonly ScheduleTechnician[];
  /** The derived overdue condition, decided by the API from the server clock (`BR-072`, `BR-074`). */
  readonly overdue: boolean;
}

export interface ScheduleTechnicianDto {
  membershipId: string;
  name: string | null;
  roleCode: AssignmentRoleCode;
}

export interface ScheduleVisitDto {
  visitId: string;
  visitStatus: VisitStatus;
  scheduledStart: string | null;
  scheduledEnd: string | null;
  jobId: string;
  jobNumber: number;
  jobTitle: string;
  customerId: string;
  customerName: string;
  address: AddressSnapshot | null;
  technicians: ScheduleTechnicianDto[];
  overdue: boolean;
}

/** The local calendar day the read was resolved for. */
export interface ScheduleDayDto {
  /** The `YYYY-MM-DD` date the day's instants were resolved from. */
  localDate: string;
  timeZone: string;
  start: string;
  end: string;
}

/**
 * The scope the read was resolved for (`BR-006`, `BR-009`, `ADR-019` D2).
 *
 * It is reported rather than left implicit because schedule **ownership and visibility are not the
 * same question**: the day the payload holds is the work the caller's capability authorized, and a
 * client presents what the API answered rather than deciding a scope for itself (`BR-007`,
 * `BR-041`).
 */
export interface ScheduleScopeDto {
  /** `ORGANIZATION` — the operation's day; `SELF` — the caller's own assigned work. */
  kind: ScheduleScopeKind;
  /** The organization membership the read was resolved for: the caller's own. */
  membershipId: string;
}

/**
 * The day schedule payload (`GET /schedule`).
 *
 * Every value is an authoritative record or a projection over those records (`BR-001`, `BR-080`).
 * Stable codes travel, never display text (`BR-028`, `BR-041`), and every instant is ISO-8601 UTC
 * (`Project.md` §15).
 */
export interface ScheduleDto {
  generatedAt: string;
  day: ScheduleDayDto;
  /** The scope the day was resolved for, and the membership it names (`BR-006`, `BR-009`). */
  scope: ScheduleScopeDto;
  /**
   * The technicians the day may be narrowed to, for the schedule's technician filter (`BR-024`,
   * `BR-068`).
   *
   * It is the same list `GET /technicians` returns, carried here so the screen is served by one
   * request the way the manager home is. The list is **not** day-scoped: it is who the filter can
   * choose between. A `SELF` scope offers no options, because a field caller reads their own work
   * and no other technician's (`BR-009`).
   */
  technicians: AssignableTechnicianDto[];
  /** The day's Visits, chronologically (`BR-072`). */
  visits: ScheduleVisitDto[];
  /**
   * The work that still needs a crew, or `null` when the scope has no such lane.
   *
   * It is deliberately **not** day-scoped: an unassigned Visit may have no agreed time yet, so it
   * belongs to no day, and a Visit waiting for a crew is waiting whoever's day it lands on
   * (`BR-071`, `BR-072`). `total` counts every such Visit, including any beyond `items`.
   *
   * The lane is the **office's** question — "what is waiting for a technician?" — and a `SELF`
   * scope answers `null` for it rather than an empty lane: a field caller reads the Visits assigned
   * to them (`BR-009`), and the operation's waiting work is not theirs to see. `null` is therefore
   * "this scope has no such lane", which is not the same answer as "nothing is waiting".
   */
  unassigned: {
    total: number;
    items: ScheduleVisitDto[];
  } | null;
}


export function toScheduleDto(input: {
  readonly generatedAt: Date;
  readonly localDate: string;
  readonly day: { readonly timeZone: string; readonly start: Date; readonly end: Date };
  readonly scope: ScheduleScope;
  readonly technicians: readonly AssignableTechnicianDto[];
  readonly visits: readonly ScheduleVisit[];
  readonly unassigned: {
    readonly total: number;
    readonly items: readonly ScheduleVisit[];
  } | null;
}): ScheduleDto {
  return {
    generatedAt: input.generatedAt.toISOString(),
    day: {
      localDate: input.localDate,
      timeZone: input.day.timeZone,
      start: input.day.start.toISOString(),
      end: input.day.end.toISOString(),
    },
    scope: {
      kind: input.scope.kind,
      membershipId: input.scope.membershipId,
    },
    technicians: input.technicians.map((technician) => ({
      membershipId: technician.membershipId,
      name: technician.name,
    })),
    visits: input.visits.map(toScheduleVisitDto),
    unassigned:
      input.unassigned === null
        ? null
        : {
            total: input.unassigned.total,
            items: input.unassigned.items.map(toScheduleVisitDto),
          },
  };
}

export function toScheduleVisitDto(visit: ScheduleVisit): ScheduleVisitDto {
  return {
    visitId: visit.visitId,
    visitStatus: visit.visitStatus,
    scheduledStart: visit.scheduledStart?.toISOString() ?? null,
    scheduledEnd: visit.scheduledEnd?.toISOString() ?? null,
    jobId: visit.jobId,
    jobNumber: visit.jobNumber,
    jobTitle: visit.jobTitle,
    customerId: visit.customerId,
    customerName: visit.customerName,
    address: visit.address,
    technicians: visit.technicians.map((technician) => ({
      membershipId: technician.membershipId,
      name: technician.name,
      roleCode: technician.roleCode,
    })),
    overdue: visit.overdue,
  };
}

/**
 * The schedule's chronological order: by scheduled start, then by Job number.
 *
 * A Visit with no time yet sorts **after** the day's timed work: the schedule answers "what happens
 * when" first, and an attempt still being arranged has no place in that order (`BR-072`). The Job
 * number breaks a tie so two Visits at the same minute are always presented in the same order
 * (`BR-052`).
 */
export function compareScheduleVisits(
  left: ScheduleVisit,
  right: ScheduleVisit,
): number {
  if (left.scheduledStart !== null && right.scheduledStart !== null) {
    const byStart = left.scheduledStart.getTime() - right.scheduledStart.getTime();
    if (byStart !== 0) {
      return byStart;
    }
  } else if (left.scheduledStart !== null) {
    return -1;
  } else if (right.scheduledStart !== null) {
    return 1;
  }
  return left.jobNumber - right.jobNumber;
}

