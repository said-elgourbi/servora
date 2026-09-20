import type { AddressSnapshot } from '../address/address-snapshot.js';
import { VISIT_STATUSES } from '../jobs/job.types.js';
import type {
  AssignmentRoleCode,
  JobStatus,
  VisitStatus,
} from '../jobs/job.types.js';
import type { DayWindow } from './home-day.js';
import { hasVisitStarted } from './home-visit-conditions.js';

/**
 * The Visit status vocabulary the API exchanges (`BR-074`, `BR-041`).
 *
 * The Job & Visit domain declares it once in `jobs/job.types.ts`; it is re-exported here because the
 * manager-home read was its first consumer. The exchanged values are these stable codes and never
 * localized labels (`BR-028`); a client resolves its own label for a code and must not invent a
 * parallel vocabulary (`BR-041`).
 */
export { VISIT_STATUSES };
export type { VisitStatus };

/**
 * Why a Job or Visit is on the manager home's "Needs attention" list.
 *
 * Each kind is a *derived condition* over records that already exist, not a stored state and not a
 * Job or Visit status (`BR-060`). The API decides which records carry a condition, using the server
 * clock where time decides it, so two clients cannot disagree about what needs attention
 * (`BR-001`, `BR-041`).
 *
 * | Kind                   | Condition                                                                          |
 * | ---------------------- | ---------------------------------------------------------------------------------- |
 * | `VISIT_OVERDUE`        | A Visit still `SCHEDULED` after its scheduled window ended (`BR-072`, `BR-074`).    |
 * | `JOB_PENDING_REVIEW`   | A Job awaiting the office review only an authorized user can close (`BR-061`, `BR-062`). |
 * | `JOB_NEEDS_SCHEDULING` | A Job with no active Visit, so it needs scheduling (`BR-060`).                      |
 */
export const MANAGER_ATTENTION_KINDS = [
  'VISIT_OVERDUE',
  'JOB_PENDING_REVIEW',
  'JOB_NEEDS_SCHEDULING',
] as const;
export type ManagerAttentionKind = (typeof MANAGER_ATTENTION_KINDS)[number];

/**
 * The Visit statuses that count as a Job's active work for the derived scheduling signal
 * (`BR-060`).
 *
 * This is `BR-060`'s `needsSchedulingActiveVisit` set — `SCHEDULED`, `EN_ROUTE`, `ON_SITE` and
 * `IN_PROGRESS`. It is deliberately not `BR-083`'s `archiveWarningOpenWork` set, which also counts a
 * `DRAFT` Visit: a `DRAFT` Visit is not yet scheduled work, so a Job holding one still needs
 * scheduling.
 */
export const NEEDS_SCHEDULING_ACTIVE_VISIT_STATUSES = [
  'SCHEDULED',
  'EN_ROUTE',
  'ON_SITE',
  'IN_PROGRESS',
] as const;

/**
 * The Job statuses that can need scheduling (`BR-060`).
 *
 * `NEW` and `IN_PROGRESS` are the two non-terminal statuses whose work is not represented by an
 * active Visit. A `SCHEDULED` Job has a scheduled Visit by definition, and `PENDING_REVIEW`,
 * `COMPLETED` and `CANCELED` are not awaiting a schedule.
 */
export const NEEDS_SCHEDULING_JOB_STATUSES = ['NEW', 'IN_PROGRESS'] as const;

/*
 * The two derived Visit conditions this read presents — the attempts that did not happen and the
 * overdue one — are defined in `home-visit-conditions.ts`, because the technician home presents the
 * same two questions and a condition two screens show must have one answer (`BR-041`).
 */

/** How many attention items this read returns before `attention.total` carries the rest. */
export const MANAGER_HOME_ATTENTION_LIMIT = 20;

/** The technician assigned to a Visit, as a manager-home row renders them (`BR-068`). */
export interface ManagerHomeTechnician {
  readonly membershipId: string;
  /** Resolved from the member's profile; `null` when the member has no profile yet. */
  readonly name: string | null;
  readonly roleCode: AssignmentRoleCode;
}

/** One row of today's schedule. */
export interface ManagerHomeVisit {
  readonly visitId: string;
  readonly visitStatus: VisitStatus;
  readonly scheduledStart: Date;
  readonly scheduledEnd: Date;
  readonly jobId: string;
  readonly jobNumber: number;
  readonly jobTitle: string;
  readonly jobStatus: JobStatus;
  readonly customerId: string;
  readonly customerName: string;
  /**
   * The located address this attempt is for (`BR-056`).
   *
   * The Visit's own preserved location is used, because that is the address the attempt happens at;
   * a Visit that carries none falls back to the Job's preserved address. `null` when neither exists.
   */
  readonly address: AddressSnapshot | null;
  /** Assigned technicians, Lead first (`BR-068`); empty when the Visit has none. */
  readonly technicians: readonly ManagerHomeTechnician[];
  /**
   * Whether the Visit is overdue: still `SCHEDULED` after its scheduled window ended
   * (`BR-072`, `BR-074`).
   *
   * It is derived by the API from the server clock, so a client presents the condition without
   * deciding it from its own clock (`BR-001`). It is a condition, never a status.
   */
  readonly overdue: boolean;
}

/** One item of the "Needs attention" list. */
export interface ManagerAttentionItem {
  readonly kind: ManagerAttentionKind;
  readonly jobId: string;
  readonly jobNumber: number;
  readonly jobTitle: string;
  readonly jobStatus: JobStatus;
  readonly customerId: string;
  readonly customerName: string;
  /** The Visit the condition is about, or `null` for a Job-level condition (`BR-060`). */
  readonly visitId: string | null;
  /** The condition Visit's scheduled start, or `null` (`BR-072`). */
  readonly scheduledStart: Date | null;
  readonly scheduledEnd: Date | null;
}

/**
 * The derived counts today's summary shows, over the Visits in the requested local day.
 *
 * The three named counts partition `total`: `completed + inProgress + upcoming === total`. Exception
 * counts are deliberately not repeated here — they belong to the "Needs attention" section, so the
 * summary answers "how is today going?" instead of counting one Visit in two places.
 */
export interface ManagerHomeDaySummary {
  readonly total: number;
  readonly completed: number;
  /** Visits whose field work is under way: `EN_ROUTE`, `ON_SITE` or `IN_PROGRESS` (`BR-074`). */
  readonly inProgress: number;
  /** Visits not yet started: `SCHEDULED` (`BR-074`). */
  readonly upcoming: number;
}

/**
 * The order conditions are presented in: what has already gone wrong first, then what is waiting on
 * the office, then what still has to be planned.
 *
 * Within one kind the list is ordered by scheduled time and then by Job number, so two reads of the
 * same data present the same order.
 */
const ATTENTION_KIND_RANK: Record<ManagerAttentionKind, number> = {
  VISIT_OVERDUE: 0,
  JOB_PENDING_REVIEW: 1,
  JOB_NEEDS_SCHEDULING: 2,
};

export function compareAttentionItems(
  left: ManagerAttentionItem,
  right: ManagerAttentionItem,
): number {
  const byKind =
    ATTENTION_KIND_RANK[left.kind] - ATTENTION_KIND_RANK[right.kind];
  if (byKind !== 0) {
    return byKind;
  }
  const bySchedule =
    (left.scheduledStart?.getTime() ?? Number.POSITIVE_INFINITY) -
    (right.scheduledStart?.getTime() ?? Number.POSITIVE_INFINITY);
  if (bySchedule !== 0) {
    return bySchedule;
  }
  return left.jobNumber - right.jobNumber;
}

/**
 * The order today's schedule is presented in.
 *
 * The manager scans for what needs them, so the day is ordered operationally rather than strictly
 * chronologically: an overdue attempt, then work under way, then what is still to come, and
 * completed work last. Every rank is derived from an authoritative Visit status and the schedule
 * (`BR-072`, `BR-074`) — nothing here is a new state.
 */
const VISIT_PRESENTATION_RANK = {
  OVERDUE: 0,
  ACTIVE: 1,
  UPCOMING: 2,
  COMPLETED: 3,
} as const;

function presentationRank(
  visit: ManagerHomeVisit,
): (typeof VISIT_PRESENTATION_RANK)[keyof typeof VISIT_PRESENTATION_RANK] {
  const status = visit.visitStatus;
  if (hasVisitStarted(status)) {
    return VISIT_PRESENTATION_RANK.ACTIVE;
  }
  if (status === 'COMPLETED') {
    return VISIT_PRESENTATION_RANK.COMPLETED;
  }
  if (visit.overdue) {
    return VISIT_PRESENTATION_RANK.OVERDUE;
  }
  return VISIT_PRESENTATION_RANK.UPCOMING;
}

export function compareVisits(
  left: ManagerHomeVisit,
  right: ManagerHomeVisit,
): number {
  const byRank = presentationRank(left) - presentationRank(right);
  if (byRank !== 0) {
    return byRank;
  }
  const bySchedule =
    left.scheduledStart.getTime() - right.scheduledStart.getTime();
  if (bySchedule !== 0) {
    return bySchedule;
  }
  return left.jobNumber - right.jobNumber;
}

export interface ManagerHomeTechnicianDto {
  membershipId: string;
  name: string | null;
  roleCode: AssignmentRoleCode;
}

export type ManagerHomeAddressDto = AddressSnapshot;

export interface ManagerHomeVisitDto {
  visitId: string;
  visitStatus: VisitStatus;
  scheduledStart: string;
  scheduledEnd: string;
  jobId: string;
  jobNumber: number;
  jobTitle: string;
  jobStatus: JobStatus;
  customerId: string;
  customerName: string;
  address: ManagerHomeAddressDto | null;
  technicians: ManagerHomeTechnicianDto[];
  overdue: boolean;
}

export interface ManagerAttentionItemDto {
  kind: ManagerAttentionKind;
  jobId: string;
  jobNumber: number;
  jobTitle: string;
  jobStatus: JobStatus;
  customerId: string;
  customerName: string;
  visitId: string | null;
  scheduledStart: string | null;
  scheduledEnd: string | null;
}

export interface ManagerHomeDayDto {
  timeZone: string;
  start: string;
  end: string;
}

/**
 * The manager home payload (`GET /home/manager`).
 *
 * Nothing in it is stored: every value is either an authoritative record or a projection over those
 * records (`BR-001`, `BR-080`). Stable codes travel, never display text (`BR-028`, `BR-041`), and
 * every instant is ISO-8601 UTC (`Project.md` §15).
 */
export interface ManagerHomeDto {
  generatedAt: string;
  day: ManagerHomeDayDto;
  /**
   * The signed-in member's name, for the screen's greeting (`BR-010`).
   *
   * `null` when the member has no profile yet, so a screen greets without a name rather than
   * inventing one.
   */
  viewer: { displayName: string | null };
  attention: {
    /** Every matching condition, including any beyond `items` (`MANAGER_HOME_ATTENTION_LIMIT`). */
    total: number;
    items: ManagerAttentionItemDto[];
  };
  today: ManagerHomeDaySummary;
  visits: ManagerHomeVisitDto[];
}

export function toManagerHomeDto(input: {
  readonly generatedAt: Date;
  readonly day: DayWindow;
  readonly viewerDisplayName: string | null;
  readonly attention: readonly ManagerAttentionItem[];
  readonly attentionTotal: number;
  readonly today: ManagerHomeDaySummary;
  readonly visits: readonly ManagerHomeVisit[];
}): ManagerHomeDto {
  return {
    generatedAt: input.generatedAt.toISOString(),
    day: {
      timeZone: input.day.timeZone,
      start: input.day.start.toISOString(),
      end: input.day.end.toISOString(),
    },
    viewer: { displayName: input.viewerDisplayName },
    attention: {
      total: input.attentionTotal,
      items: input.attention.map(toManagerAttentionItemDto),
    },
    today: input.today,
    visits: input.visits.map(toManagerHomeVisitDto),
  };
}

function toManagerAttentionItemDto(
  item: ManagerAttentionItem,
): ManagerAttentionItemDto {
  return {
    kind: item.kind,
    jobId: item.jobId,
    jobNumber: item.jobNumber,
    jobTitle: item.jobTitle,
    jobStatus: item.jobStatus,
    customerId: item.customerId,
    customerName: item.customerName,
    visitId: item.visitId,
    scheduledStart: item.scheduledStart?.toISOString() ?? null,
    scheduledEnd: item.scheduledEnd?.toISOString() ?? null,
  };
}

function toManagerHomeVisitDto(visit: ManagerHomeVisit): ManagerHomeVisitDto {
  return {
    visitId: visit.visitId,
    visitStatus: visit.visitStatus,
    scheduledStart: visit.scheduledStart.toISOString(),
    scheduledEnd: visit.scheduledEnd.toISOString(),
    jobId: visit.jobId,
    jobNumber: visit.jobNumber,
    jobTitle: visit.jobTitle,
    jobStatus: visit.jobStatus,
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
