import { Injectable } from '@nestjs/common';
import {
  and,
  asc,
  eq,
  exists,
  gte,
  inArray,
  isNotNull,
  lt,
  notExists,
  notInArray,
  sql,
} from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import { customers, jobs, visits, visitTechnicians } from '../database/schema.js';
import { readAddressSnapshot } from '../address/address-snapshot.js';
import type { AddressSnapshot } from '../address/address-snapshot.js';
import { NOT_DAY_WORK_VISIT_STATUSES, isVisitOverdue } from '../home/home-visit-conditions.js';
import type { DayWindow } from '../home/home-day.js';
import { readAssignedTechnicians } from '../jobs/visit-assignment.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import { TechniciansService } from '../technicians/technicians.service.js';
import type {
  AssignableTechnician,
  AssignableTechnicianDto,
} from '../technicians/technician.dto.js';
import type { VisitStatus } from '../jobs/job.types.js';
import {
  SCHEDULE_UNASSIGNED_LIMIT,
  UNASSIGNED_EXCLUDED_VISIT_STATUSES,
  compareScheduleVisits,
} from './schedule.dto.js';
import type { ScheduleTechnician, ScheduleVisit } from './schedule.dto.js';
import { scheduleScopedMembershipIds } from './schedule-scope.js';
import type { ScheduleScope } from './schedule-scope.js';

/** Everything one schedule read assembles. */
export interface ScheduleRead {
  readonly technicians: readonly AssignableTechnicianDto[];
  readonly visits: readonly ScheduleVisit[];
  /**
   * The office's "waiting for a crew" lane, or `null` for a scope that has no such lane.
   *
   * A field scope reads the Visits assigned to the caller (`BR-009`), so the operation's unassigned
   * work is neither narrowed nor reported for it: `null` states that the scope has no lane, which
   * is a different answer from an empty one (`BR-042`).
   */
  readonly unassigned: {
    readonly total: number;
    readonly items: readonly ScheduleVisit[];
  } | null;
}

/** One Visit with the Job and Customer context a schedule row carries. */
interface ScheduleVisitRow {
  readonly visitId: string;
  readonly visitStatus: string;
  readonly scheduledStart: Date | null;
  readonly scheduledEnd: Date | null;
  readonly visitAddressSnapshot: unknown;
  readonly jobId: string;
  readonly jobNumber: number;
  readonly jobTitle: string;
  readonly jobAddressSnapshot: unknown;
  readonly customerId: string;
  readonly customerName: string;
}

/**
 * Assembles one local day's schedule (`GET /schedule`).
 *
 * TENANCY: every read is scoped by `organization_id` and nothing here fetches a Job or a Visit by
 * primary key alone (`src/tenancy/tenant-scope.ts`), so one organization never sees another's work.
 *
 * Nothing here is business state invented for the screen. The day's Visits are `BR-072`'s
 * authoritative schedule (`scheduled_start`, which the Visit owns) and `BR-074`'s statuses; the
 * unassigned lane is the absence of a `BR-068` assignment on a Visit that is still open; and the
 * overdue flag is the condition `BR-072`/`BR-074` already define, decided with the server clock so
 * no client compares a schedule against its own device clock (`BR-001`, `BR-041`).
 *
 * The **scope** is applied here rather than by the caller: an office scope reads the operation's
 * day, a field scope reads the Visits the caller's own membership is on (`BR-009`, `ADR-019` D2), and
 * the two office-only projections — the technicians the filter may name and the "waiting for a crew"
 * lane — are absent from a field answer. That is what makes the scope a server decision the client
 * cannot widen (`BR-007`).
 */
@Injectable()
export class ScheduleService {
  constructor(
    private readonly database: DatabaseService,
    private readonly technicians: TechniciansService,
  ) {}

  private get db() {
    return this.database.db;
  }

  async readSchedule(
    scope: OrganizationScope,
    day: DayWindow,
    membershipIds: readonly string[],
    now: Date,
    scheduleScope: ScheduleScope,
  ): Promise<ScheduleRead> {
    const fieldScope = scheduleScope.kind === 'SELF';
    const narrowedTo = scheduleScopedMembershipIds(scheduleScope, membershipIds);
    const [dayRows, unassignedRows, technicians] = await Promise.all([
      this.findDayVisits(scope, day, narrowedTo),
      fieldScope
        ? Promise.resolve<ScheduleVisitRow[]>([])
        : this.findUnassignedVisits(scope),
      fieldScope
        ? Promise.resolve<readonly AssignableTechnician[]>([])
        : this.technicians.listAssignableTechniciansInOrganization(scope),
    ]);

    const crew = await readAssignedTechnicians(this.db, scope, [
      ...dayRows.map((row) => row.visitId),
      ...unassignedRows.map((row) => row.visitId),
    ]);

    const visits = dayRows
      .map((row) => this.toVisit(row, crew.get(row.visitId) ?? [], now))
      .sort(compareScheduleVisits);
    // The cap is applied after the order is settled, and the total is the whole lane, so a capped
    // list never under-reports how much work is waiting for a crew.
    const unassigned = unassignedRows
      .map((row) => this.toVisit(row, [], now))
      .sort(compareScheduleVisits);

    return {
      technicians,
      visits,
      unassigned: fieldScope
        ? null
        : {
            total: unassigned.length,
            items: unassigned.slice(0, SCHEDULE_UNASSIGNED_LIMIT),
          },
    };
  }

  /**
   * The Visits scheduled inside the requested local day, excluding the ones that did not happen.
   *
   * A `CANCELED` or `NO_SHOW` Visit is not work of the day, so it is absent from the day's schedule
   * — the same classification the manager home uses (`NOT_DAY_WORK_VISIT_STATUSES`, `BR-074`), so
   * the two screens cannot disagree about which Visits a day holds. A `COMPLETED` Visit stays: it is
   * what the day has produced so far.
   *
   * The filter, when [membershipIds] names any, narrows the day to the Visits those technicians are
   * **currently** assigned to (`BR-068`). Several ids are a union rather than an intersection: a
   * Visit is kept when **any** of them is on it, because a dispatch filter asks whose work to show
   * rather than which Visits a group happens to share. It is applied in the query, inside the tenant
   * scope, so a filter can only narrow what the caller could already read (`BR-001`, `BR-007`).
   */
  private async findDayVisits(
    scope: OrganizationScope,
    day: DayWindow,
    membershipIds: readonly string[],
  ): Promise<ScheduleVisitRow[]> {
    return this.db
      .select(scheduleVisitSelection)
      .from(visits)
      .innerJoin(jobs, eq(jobs.id, visits.jobId))
      .innerJoin(
        customers,
        and(
          eq(customers.id, jobs.customerId),
          eq(customers.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          eq(visits.organizationId, scope.organizationId),
          activeCustomer(),
          notInArray(visits.status, [...NOT_DAY_WORK_VISIT_STATUSES]),
          isNotNull(visits.scheduledStart),
          isNotNull(visits.scheduledEnd),
          gte(visits.scheduledStart, day.start),
          lt(visits.scheduledStart, day.end),
          ...(membershipIds.length === 0
            ? []
            : [assignedToAny(scope, membershipIds, this.db)]),
        ),
      )
      .orderBy(asc(visits.scheduledStart), asc(jobs.jobNumber));
  }

  /**
   * The organization's open Visits that have no technician assigned (`BR-068`, `BR-071`).
   *
   * This is the "work requiring assignment" lane: a Visit that is still open (`BR-062`'s
   * classification) and whose current crew is empty. It is **not** day-scoped — a Visit with no
   * agreed time yet belongs to no day — and the lane is ordered by scheduled start, so the nearest
   * arranged work is first and an attempt nobody has given a time to comes last.
   *
   * A customer the organization has deleted is excluded, exactly as the home reads exclude one
   * (`BR-023`).
   */
  private async findUnassignedVisits(
    scope: OrganizationScope,
  ): Promise<ScheduleVisitRow[]> {
    return this.db
      .select(scheduleVisitSelection)
      .from(visits)
      .innerJoin(jobs, eq(jobs.id, visits.jobId))
      .innerJoin(
        customers,
        and(
          eq(customers.id, jobs.customerId),
          eq(customers.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          eq(visits.organizationId, scope.organizationId),
          activeCustomer(),
          notInArray(visits.status, [...UNASSIGNED_EXCLUDED_VISIT_STATUSES]),
          notExists(unassignedCrew(scope, this.db)),
        ),
      )
      .orderBy(asc(visits.scheduledStart), asc(jobs.jobNumber));
  }

  /** One row of the schedule, built from the Visit with its Job and Customer context. */
  private toVisit(
    row: ScheduleVisitRow,
    technicians: readonly ScheduleTechnician[],
    now: Date,
  ): ScheduleVisit {
    const visitStatus = row.visitStatus as VisitStatus;
    return {
      visitId: row.visitId,
      visitStatus,
      scheduledStart: row.scheduledStart,
      scheduledEnd: row.scheduledEnd,
      jobId: row.jobId,
      jobNumber: row.jobNumber,
      jobTitle: row.jobTitle,
      customerId: row.customerId,
      customerName: row.customerName,
      address: visitAddress(row),
      technicians: [...technicians],
      // Overdue is `BR-074`'s condition over a schedule that exists: a Visit being arranged has no
      // window to be overdue against (`BR-072`).
      overdue:
        row.scheduledEnd !== null &&
        isVisitOverdue({ visitStatus, scheduledEnd: row.scheduledEnd }, now),
    };
  }
}

const scheduleVisitSelection = {
  visitId: visits.id,
  visitStatus: visits.status,
  scheduledStart: visits.scheduledStart,
  scheduledEnd: visits.scheduledEnd,
  visitAddressSnapshot: visits.locationAddressSnapshot,
  jobId: jobs.id,
  jobNumber: jobs.jobNumber,
  jobTitle: jobs.title,
  jobAddressSnapshot: jobs.propertyAddressSnapshot,
  customerId: customers.id,
  customerName: customers.displayName,
} as const;

/**
 * The address a schedule row shows.
 *
 * The Visit's own preserved location wins, because that is the address the attempt is for; the Job's
 * snapshot is the fallback, and neither being present means the row shows no address rather than a
 * partly invented one (`BR-056`, `BR-057`).
 */
function visitAddress(row: ScheduleVisitRow): AddressSnapshot | null {
  return (
    readAddressSnapshot(row.visitAddressSnapshot) ??
    readAddressSnapshot(row.jobAddressSnapshot)
  );
}

/**
 * A Job of a customer the organization still has in active use.
 *
 * `BR-023` hides a deleted customer's Jobs by default, and archiving a customer is how Servora
 * deletes one, so the schedule never presents work for a customer that was archived.
 */
function activeCustomer() {
  return sql`${customers.deletedAt} is null`;
}

/**
 * The Visits any of [membershipIds] is currently assigned to (`BR-068`), as a query predicate.
 *
 * One `exists` with an `in` list answers the whole union, so a filter over several technicians is
 * still one query inside the tenant scope rather than one query per technician — the caller's day is
 * narrowed once, and a Visit matches as soon as one of the selected members is on it.
 */
function assignedToAny(
  scope: OrganizationScope,
  membershipIds: readonly string[],
  db: DatabaseService['db'],
) {
  return exists(
    db
      .select({ one: sql`1` })
      .from(visitTechnicians)
      .where(
        and(
          eq(visitTechnicians.visitId, visits.id),
          eq(visitTechnicians.organizationId, scope.organizationId),
          inArray(visitTechnicians.technicianMembershipId, [
            ...membershipIds,
          ]),
        ),
      ),
  );
}

/** Whether a Visit still has no technician assigned to it (`BR-068`). */
function unassignedCrew(scope: OrganizationScope, db: DatabaseService['db']) {
  return db
    .select({ one: sql`1` })
    .from(visitTechnicians)
    .where(
      and(
        eq(visitTechnicians.visitId, visits.id),
        eq(visitTechnicians.organizationId, scope.organizationId),
      ),
    );
}

