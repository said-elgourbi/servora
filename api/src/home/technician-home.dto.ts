import type { AddressSnapshot } from '../address/address-snapshot.js';
import type {
  AssignmentRoleCode,
  JobStatus,
  VisitStatus,
} from '../jobs/job.types.js';
import type { DayWindow } from './home-day.js';
import { hasVisitStarted } from './home-visit-conditions.js';

/**
 * The technician home read (`GET /home/technician`).
 *
 * It is a **projection**: every value is derived from the authoritative Visit, Job, assignment and
 * Customer records and nothing is stored (`BR-001`, `BR-080`). Nothing is derived here that a client
 * could compute differently from its own clock: which Visits are today's, which one is next and
 * which are overdue are the API's answers, because they depend on the server clock and on the
 * caller's own assignments (`BR-001`, `BR-041`).
 *
 * What this read is for is the one question `BR-010` and `BR-012` give the technician:
 * **"what do I need to do next?"** — not a copy of the manager home, which answers "what is
 * happening across the business?". The content was decided by product ownership on 2026-09-17
 * (`ADR-019` D6, `docs/api/technician-home.md`).
 */

/**
 * Why something on the technician's own work asks for attention.
 *
 * Every kind is a *derived condition* over records that already exist, never a stored state and
 * never a Visit status (`BR-060`). The API decides which of the caller's Visits carries one, using
 * the server clock, so no client decides it again (`BR-001`, `BR-041`).
 *
 * | Kind            | Condition                                                                        |
 * | --------------- | -------------------------------------------------------------------------------- |
 * | `VISIT_OVERDUE` | An assigned Visit still `SCHEDULED` after its window ended (`BR-072`, `BR-074`). |
 *
 * The code is the one the manager home already reports for the same condition, so one Visit cannot
 * be described with two vocabularies (`BR-041`). The kinds the section *could* also carry —
 * evidence waiting to be synchronized, a pending follow-up request — are deliberately absent: the
 * first is a device-local fact the API cannot see, and the second belongs to the follow-up feature
 * that has not been built (`BR-FV-001` – `BR-FV-013`, `BR-042`).
 */
export const TECHNICIAN_ATTENTION_KINDS = ['VISIT_OVERDUE'] as const;
export type TechnicianAttentionKind = (typeof TECHNICIAN_ATTENTION_KINDS)[number];

/**
 * How many of the technician's upcoming Visits the preview carries.
 *
 * The section is a preview of what comes after today, not a schedule: the full schedule is a
 * surface of its own (the Android Schedule destination is still a placeholder). The payload reports
 * `upcomingTotal` beside the capped list, exactly as the manager home reports `attention.total`
 * beside its capped list, so a client never under-reports the caller's own work.
 */
export const TECHNICIAN_HOME_UPCOMING_LIMIT = 5;

/** How many attention items the read returns before `attention.total` carries the rest. */
export const TECHNICIAN_HOME_ATTENTION_LIMIT = 20;

/** The technician assigned to a Visit, as a home row renders them (`BR-068`). */
export interface TechnicianHomeTechnician {
  readonly membershipId: string;
  /** Resolved from the member's profile; `null` when the member has no profile yet. */
  readonly name: string | null;
  readonly roleCode: AssignmentRoleCode;
}

/** One assigned Visit, as the technician home renders it. */
export interface TechnicianHomeVisit {
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
  /** The located address this attempt is for; the Visit's own, or the Job's (`BR-056`). */
  readonly address: AddressSnapshot | null;
  /** Assigned technicians, Lead first (`BR-068`); empty when the Visit has none. */
  readonly technicians: readonly TechnicianHomeTechnician[];
  /** Whether the Visit is overdue, decided by the API from the server clock (`BR-072`). */
  readonly overdue: boolean;
}

/** One condition on the technician's own work. */
export interface TechnicianAttentionItem {
  readonly kind: TechnicianAttentionKind;
  readonly visitId: string;
  readonly jobId: string;
  readonly jobNumber: number;
  readonly jobTitle: string;
  readonly jobStatus: JobStatus;
  readonly customerId: string;
  readonly customerName: string;
  readonly scheduledStart: Date;
  readonly scheduledEnd: Date;
}

/** Everything one technician home read assembles. */
export interface TechnicianHomeRead {
  readonly viewerDisplayName: string | null;
  /** The caller's next actionable Visit, or `null` when no assigned Visit is left to do. */
  readonly nextVisit: TechnicianHomeVisit | null;
  /** Today's assigned Visits, chronologically (`BR-074`). */
  readonly visits: readonly TechnicianHomeVisit[];
  /** The preview of assigned Visits after today, and how many there are in all. */
  readonly upcoming: readonly TechnicianHomeVisit[];
  readonly upcomingTotal: number;
  readonly attention: readonly TechnicianAttentionItem[];
  readonly attentionTotal: number;
}

export interface TechnicianHomeTechnicianDto {
  membershipId: string;
  name: string | null;
  roleCode: AssignmentRoleCode;
}

export type TechnicianHomeAddressDto = AddressSnapshot;

export interface TechnicianHomeVisitDto {
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
  address: TechnicianHomeAddressDto | null;
  technicians: TechnicianHomeTechnicianDto[];
  overdue: boolean;
}

export interface TechnicianAttentionItemDto {
  kind: TechnicianAttentionKind;
  visitId: string;
  jobId: string;
  jobNumber: number;
  jobTitle: string;
  jobStatus: JobStatus;
  customerId: string;
  customerName: string;
  scheduledStart: string;
  scheduledEnd: string;
}

export interface TechnicianHomeDayDto {
  timeZone: string;
  start: string;
  end: string;
}

/**
 * The technician home payload (`GET /home/technician`).
 *
 * Nothing in it is stored: every value is either an authoritative record or a projection over those
 * records (`BR-001`, `BR-080`). Stable codes travel, never display text (`BR-028`, `BR-041`), and
 * every instant is ISO-8601 UTC (`Project.md` §15).
 */
export interface TechnicianHomeDto {
  generatedAt: string;
  day: TechnicianHomeDayDto;
  /**
   * The signed-in member's name, for the screen's greeting (`BR-010`).
   *
   * `null` when the member has no profile yet, so a screen greets without a name rather than
   * inventing one (`BR-020`).
   */
  viewer: { displayName: string | null };
  /** The one Visit to do next, or `null` when nothing assigned is left to do (`BR-012`). */
  nextVisit: TechnicianHomeVisitDto | null;
  /** Today's assigned Visits, chronologically. */
  visits: TechnicianHomeVisitDto[];
  /** The preview of what comes after today, capped at `TECHNICIAN_HOME_UPCOMING_LIMIT`. */
  upcoming: TechnicianHomeVisitDto[];
  /** How many assigned Visits are after today, so the preview never under-reports them. */
  upcomingTotal: number;
  /** The caller's own conditions, capped at `TECHNICIAN_HOME_ATTENTION_LIMIT`. */
  attention: {
    total: number;
    items: TechnicianAttentionItemDto[];
  };
}



export function toTechnicianHomeDto(input: {
  readonly generatedAt: Date;
  readonly day: DayWindow;
  readonly read: TechnicianHomeRead;
}): TechnicianHomeDto {
  return {
    generatedAt: input.generatedAt.toISOString(),
    day: {
      timeZone: input.day.timeZone,
      start: input.day.start.toISOString(),
      end: input.day.end.toISOString(),
    },
    viewer: { displayName: input.read.viewerDisplayName },
    nextVisit:
      input.read.nextVisit === null
        ? null
        : toTechnicianHomeVisitDto(input.read.nextVisit),
    visits: input.read.visits.map(toTechnicianHomeVisitDto),
    upcoming: input.read.upcoming.map(toTechnicianHomeVisitDto),
    upcomingTotal: input.read.upcomingTotal,
    attention: {
      total: input.read.attentionTotal,
      items: input.read.attention.map((item) => ({
        kind: item.kind,
        visitId: item.visitId,
        jobId: item.jobId,
        jobNumber: item.jobNumber,
        jobTitle: item.jobTitle,
        jobStatus: item.jobStatus,
        customerId: item.customerId,
        customerName: item.customerName,
        scheduledStart: item.scheduledStart.toISOString(),
        scheduledEnd: item.scheduledEnd.toISOString(),
      })),
    },
  };
}

function toTechnicianHomeVisitDto(
  visit: TechnicianHomeVisit,
): TechnicianHomeVisitDto {
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

/**
 * The order this read presents each of its lists in: by scheduled start, then by Job number.
 *
 * The technician reads their day in time order — "what is next?" is a chronological question, which
 * is deliberately **not** the manager home's operational order (overdue first), where the office
 * scans the operation for what has gone wrong (`BR-010`, `BR-012`). The Job number breaks a tie
 * between two Visits starting at the same instant, so two reads of the same data present the same
 * order.
 */
export function compareChronologically(
  left: TechnicianHomeVisit,
  right: TechnicianHomeVisit,
): number {
  const bySchedule =
    left.scheduledStart.getTime() - right.scheduledStart.getTime();
  if (bySchedule !== 0) {
    return bySchedule;
  }
  return left.jobNumber - right.jobNumber;
}

/**
 * The Visit the technician should do next, or `null` when nothing assigned is left to do.
 *
 * The choice is presentation over authoritative statuses, and it is stated once here rather than in
 * a client, so two clients cannot disagree about which Visit is "next" (`BR-041`):
 *
 * 1. **Work already started first** — `EN_ROUTE`, `ON_SITE` or `IN_PROGRESS` (`BR-074`). That is
 *    what the technician is doing now, so it is ahead of everything else, including a Visit
 *    scheduled earlier that nobody has started.
 * 2. **Then the nearest in time** — the earliest scheduled start, with the Job number breaking a
 *    tie (`compareChronologically`).
 *
 * It is not the manager home's order, which leads with the overdue Visit because the office scans
 * for what has gone wrong (`BR-010`, `BR-012`). An overdue Visit is reported to the technician too,
 * in the attention section, and it is the next Visit whenever nothing is under way.
 */
export function selectNextVisit(
  visits: readonly TechnicianHomeVisit[],
): TechnicianHomeVisit | null {
  const [next] = [...visits].sort(startedWorkFirst);
  return next ?? null;
}

/** Work under way before work to come; otherwise the nearer Visit first (`BR-074`). */
function startedWorkFirst(
  left: TechnicianHomeVisit,
  right: TechnicianHomeVisit,
): number {
  const leftStarted = hasVisitStarted(left.visitStatus);
  const rightStarted = hasVisitStarted(right.visitStatus);
  if (leftStarted !== rightStarted) {
    return leftStarted ? -1 : 1;
  }
  return compareChronologically(left, right);
}

