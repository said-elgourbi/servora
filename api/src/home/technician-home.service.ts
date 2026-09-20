import { Injectable } from '@nestjs/common';
import {
  and,
  asc,
  eq,
  exists,
  gte,
  isNotNull,
  lt,
  notInArray,
  sql,
} from 'drizzle-orm';
import type { SQL } from 'drizzle-orm';
import { readAddressSnapshot } from '../address/address-snapshot.js';
import type { AddressSnapshot } from '../address/address-snapshot.js';
import { DatabaseService } from '../database/database.service.js';
import {
  customers,
  jobs,
  visits,
  visitTechnicians,
} from '../database/schema.js';
import { HISTORICAL_VISIT_STATUSES } from '../jobs/job.types.js';
import type { JobStatus, VisitStatus } from '../jobs/job.types.js';
import { readAssignedTechnicians } from '../jobs/visit-assignment.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type { DayWindow } from './home-day.js';
import {
  NOT_DAY_WORK_VISIT_STATUSES,
  isVisitOverdue,
} from './home-visit-conditions.js';
import { readViewerDisplayName } from './home-viewer.js';
import {
  TECHNICIAN_HOME_ATTENTION_LIMIT,
  TECHNICIAN_HOME_UPCOMING_LIMIT,
  compareChronologically,
  selectNextVisit,
} from './technician-home.dto.js';
import type {
  TechnicianAttentionItem,
  TechnicianHomeRead,
  TechnicianHomeTechnician,
  TechnicianHomeVisit,
} from './technician-home.dto.js';

/** One Visit with the Job and Customer context a technician's row needs. */
interface TechnicianVisitRow {
  readonly visitId: string;
  readonly visitStatus: string;
  /** Both ends of the schedule. Every query here requires it, so it is present for every row. */
  readonly scheduledStart: Date | null;
  readonly scheduledEnd: Date | null;
  readonly visitAddressSnapshot: unknown;
  readonly jobId: string;
  readonly jobNumber: number;
  readonly jobTitle: string;
  readonly jobStatus: string;
  readonly jobAddressSnapshot: unknown;
  readonly customerId: string;
  readonly customerName: string;
}

/**
 * Assembles the technician home (`GET /home/technician`) from authoritative records.
 *
 * The read answers the one question the technician's own screen exists for — **what do I need to do
 * next?** (`BR-010`, `BR-012`) — and it answers it from the caller's **own current assignments**,
 * which is `ADR-019` D2's scope: a Visit the caller's membership is not on the crew of is not this
 * read's business, and that scope is enforced here rather than by any client (`BR-001`, `BR-007`).
 * The membership is the one the route was reached with, resolved from the capability the caller
 * holds and never from a role name (`BR-004`, `BR-006`).
 *
 * Nothing is invented for the screen: "today" is `home-day.ts`'s resolved local day, the next Visit
 * and the overdue condition are derived from the authoritative Visit status and schedule
 * (`BR-072`, `BR-074`), and the two Visit sets this read excludes or reports are the ones the
 * manager home already presents, defined once in `home-visit-conditions.ts` (`BR-041`).
 *
 * TENANCY: every read is scoped by `organization_id` and there is no method that fetches a Visit, a
 * Job or a Customer by primary key alone (`src/tenancy/tenant-scope.ts`).
 */
@Injectable()
export class TechnicianHomeService {
  constructor(private readonly database: DatabaseService) {}

  private get db() {
    return this.database.db;
  }

  async readTechnicianHome(
    scope: OrganizationScope,
    userId: string,
    membershipId: string,
    day: DayWindow,
    now: Date,
  ): Promise<TechnicianHomeRead> {
    const [viewerDisplayName, dayRows, openRows] = await Promise.all([
      readViewerDisplayName(this.db, scope, userId),
      this.findDayVisits(scope, membershipId, day),
      this.findOpenVisits(scope, membershipId),
    ]);

    const technicians = await readAssignedTechnicians(this.db, scope, [
      ...dayRows.map((row) => row.visitId),
      ...openRows.map((row) => row.visitId),
    ]);

    const mapped = (rows: readonly TechnicianVisitRow[]) =>
      rows
        .flatMap((row) => {
          const visit = toTechnicianVisit(
            row,
            technicians.get(row.visitId) ?? [],
            now,
          );
          return visit === null ? [] : [visit];
        })
        .sort(compareChronologically);

    const visits = mapped(dayRows);
    // The open Visits are read once and split by the day window the schedule was resolved for:
    // what remains of today is already in `visits`, and everything from the day's end onwards is the
    // preview. The next Visit is chosen over the whole open set, so a technician with nothing left
    // today is answered with what they do next rather than with nothing (`BR-012`).
    const open = mapped(openRows);
    const upcoming = open.filter(
      (visit) => visit.scheduledStart.getTime() >= day.end.getTime(),
    );
    const overdue = open.filter((visit) => visit.overdue);

    return {
      viewerDisplayName,
      nextVisit: selectNextVisit(open),
      visits,
      upcoming: upcoming.slice(0, TECHNICIAN_HOME_UPCOMING_LIMIT),
      upcomingTotal: upcoming.length,
      attention: overdue
        .slice(0, TECHNICIAN_HOME_ATTENTION_LIMIT)
        .map(toAttentionItem),
      attentionTotal: overdue.length,
    };
  }

  /**
   * The caller's own Visits scheduled inside the requested local day, excluding the ones that did
   * not happen.
   *
   * A `CANCELED` or `NO_SHOW` Visit is not work of the day, so it is excluded — the same set the
   * manager home excludes, defined once (`BR-074`, `home-visit-conditions.ts`). A `COMPLETED` Visit
   * stays: it is what the day has produced so far, and the technician's own record of it belongs on
   * their day.
   */
  private async findDayVisits(
    scope: OrganizationScope,
    membershipId: string,
    day: DayWindow,
  ): Promise<TechnicianVisitRow[]> {
    return this.db
      .select(visitSelection)
      .from(visits)
      .innerJoin(jobs, eq(jobs.id, visits.jobId))
      .innerJoin(customers, customerJoin(scope))
      .where(
        and(
          ...this.assignedVisitConditions(scope, membershipId),
          notInArray(visits.status, [...NOT_DAY_WORK_VISIT_STATUSES]),
          isNotNull(visits.scheduledStart),
          isNotNull(visits.scheduledEnd),
          gte(visits.scheduledStart, day.start),
          lt(visits.scheduledStart, day.end),
        ),
      )
      .orderBy(asc(visits.scheduledStart), asc(jobs.jobNumber));
  }

  /**
   * The caller's own Visits that are still to do: every assigned Visit whose field attempt is not
   * over (`BR-062`, `BR-074`) and which carries the schedule a row is read from.
   *
   * A Visit with no schedule is not part of this set: a `DRAFT` Visit the office has not scheduled
   * yet has no time to be "next" at, and scheduling it is the office's work rather than the
   * technician's (`BR-060`, `BR-072`). Everything else — a Visit under way, an overdue one, what is
   * left of today and what comes after — is here, and the read splits it rather than asking the
   * database the same question three times.
   */
  private async findOpenVisits(
    scope: OrganizationScope,
    membershipId: string,
  ): Promise<TechnicianVisitRow[]> {
    return this.db
      .select(visitSelection)
      .from(visits)
      .innerJoin(jobs, eq(jobs.id, visits.jobId))
      .innerJoin(customers, customerJoin(scope))
      .where(
        and(
          ...this.assignedVisitConditions(scope, membershipId),
          notInArray(visits.status, [...HISTORICAL_VISIT_STATUSES]),
          isNotNull(visits.scheduledStart),
          isNotNull(visits.scheduledEnd),
        ),
      )
      .orderBy(asc(visits.scheduledStart), asc(jobs.jobNumber));
  }

  /**
   * The conditions every query in this read applies.
   *
   * The tenant boundary, the caller's **own current assignment** and a Customer the organization
   * still has in active use are not optional parts of a query here: a read that forgot the
   * assignment would show an operation's whole day to one of its technicians (`BR-001`, `BR-007`;
   * `ADR-019` D2), and `BR-023` hides a deleted Customer's Jobs by default.
   */
  private assignedVisitConditions(
    scope: OrganizationScope,
    membershipId: string,
  ): SQL[] {
    return [
      eq(visits.organizationId, scope.organizationId),
      sql`${customers.deletedAt} is null`,
      exists(
        this.db
          .select({ present: sql`1` })
          .from(visitTechnicians)
          .where(
            and(
              eq(visitTechnicians.organizationId, scope.organizationId),
              eq(visitTechnicians.technicianMembershipId, membershipId),
              eq(visitTechnicians.visitId, visits.id),
            ),
          ),
      ),
    ];
  }
}

const visitSelection = {
  visitId: visits.id,
  visitStatus: visits.status,
  scheduledStart: visits.scheduledStart,
  scheduledEnd: visits.scheduledEnd,
  visitAddressSnapshot: visits.locationAddressSnapshot,
  jobId: jobs.id,
  jobNumber: jobs.jobNumber,
  jobTitle: jobs.title,
  jobStatus: jobs.status,
  jobAddressSnapshot: jobs.propertyAddressSnapshot,
  customerId: customers.id,
  customerName: customers.displayName,
} as const;

/** The Customer join, which is also scoped by the tenant (`BR-001`). */
function customerJoin(scope: OrganizationScope) {
  return and(
    eq(customers.id, jobs.customerId),
    eq(customers.organizationId, scope.organizationId),
  );
}

/**
 * One row of the technician's day, built from the Visit with its Job and Customer context.
 *
 * The crew is read once for every Visit the payload carries, so a row never triggers its own query
 * (`BR-068`).
 */
function toTechnicianVisit(
  row: TechnicianVisitRow,
  technicians: readonly TechnicianHomeTechnician[],
  now: Date,
): TechnicianHomeVisit | null {
  // The reads require a schedule, so this only guards a row the query could not have returned.
  if (row.scheduledStart === null || row.scheduledEnd === null) {
    return null;
  }
  const visitStatus = row.visitStatus as VisitStatus;
  return {
    visitId: row.visitId,
    visitStatus,
    scheduledStart: row.scheduledStart,
    scheduledEnd: row.scheduledEnd,
    jobId: row.jobId,
    jobNumber: row.jobNumber,
    jobTitle: row.jobTitle,
    jobStatus: row.jobStatus as JobStatus,
    customerId: row.customerId,
    customerName: row.customerName,
    address: visitAddress(row),
    technicians: [...technicians],
    overdue: isVisitOverdue(
      { visitStatus, scheduledEnd: row.scheduledEnd },
      now,
    ),
  };
}

/**
 * The address a Visit row shows.
 *
 * The Visit's own preserved location wins, because that is the address the attempt is for; the Job's
 * snapshot is the fallback, and neither being present means the row shows no address rather than a
 * partly invented one (`BR-056`, `BR-057`).
 */
function visitAddress(row: TechnicianVisitRow): AddressSnapshot | null {
  return (
    readAddressSnapshot(row.visitAddressSnapshot) ??
    readAddressSnapshot(row.jobAddressSnapshot)
  );
}

/**
 * The overdue condition as an attention item (`BR-072`, `BR-074`).
 *
 * It is built from the Visit the read already mapped, so the section and the list cannot disagree
 * about a Visit's schedule, its crew or its status.
 */
function toAttentionItem(visit: TechnicianHomeVisit): TechnicianAttentionItem {
  return {
    kind: 'VISIT_OVERDUE',
    visitId: visit.visitId,
    jobId: visit.jobId,
    jobNumber: visit.jobNumber,
    jobTitle: visit.jobTitle,
    jobStatus: visit.jobStatus,
    customerId: visit.customerId,
    customerName: visit.customerName,
    scheduledStart: visit.scheduledStart,
    scheduledEnd: visit.scheduledEnd,
  };
}
