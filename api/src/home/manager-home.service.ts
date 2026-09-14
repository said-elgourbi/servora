import { Injectable } from '@nestjs/common';
import {
  and,
  asc,
  eq,
  gte,
  inArray,
  isNotNull,
  lt,
  notExists,
  notInArray,
  sql,
} from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  customers,
  jobs,
  organizationMembers,
  userProfiles,
  visits,
  visitTechnicians,
} from '../database/schema.js';
import { readAddressSnapshot } from '../address/address-snapshot.js';
import type { AddressSnapshot } from '../address/address-snapshot.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type {
  AssignmentRoleCode,
  JobStatus,
} from '../customers/customer-detail.dto.js';
import {
  NEEDS_SCHEDULING_ACTIVE_VISIT_STATUSES,
  NEEDS_SCHEDULING_JOB_STATUSES,
  NOT_DAY_WORK_VISIT_STATUSES,
  compareAttentionItems,
  compareVisits,
  isVisitOverdue,
} from './manager-home.dto.js';
import type {
  ManagerAttentionItem,
  ManagerHomeDaySummary,
  ManagerHomeTechnician,
  ManagerHomeVisit,
  VisitStatus,
} from './manager-home.dto.js';
import { MANAGER_HOME_ATTENTION_LIMIT } from './manager-home-day.js';
import type { DayWindow } from './manager-home-day.js';

/** Everything one manager home read assembles. */
export interface ManagerHomeRead {
  readonly viewerDisplayName: string | null;
  readonly attention: readonly ManagerAttentionItem[];
  readonly attentionTotal: number;
  readonly today: ManagerHomeDaySummary;
  readonly visits: readonly ManagerHomeVisit[];
}

/** One Visit with the Job and Customer context a schedule row needs. */
interface VisitContextRow {
  readonly visitId: string;
  readonly visitStatus: string;
  /**
   * The Visit's schedule. The day and overdue reads both require it, so it is present for every row
   * they return; the mapper still checks it rather than asserting it.
   */
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

/** One Job carrying a job-level attention condition. */
interface JobConditionRow {
  readonly jobId: string;
  readonly jobNumber: number;
  readonly jobTitle: string;
  readonly jobStatus: string;
  readonly customerId: string;
  readonly customerName: string;
}

/**
 * Assembles the manager home (`GET /home/manager`) from authoritative records.
 *
 * TENANCY: every read is scoped by `organization_id` and there is no method that fetches a Job, a
 * Visit or a Customer by primary key alone (`docs/tenancy/tenant-scope` rule in
 * `src/tenancy/tenant-scope.ts`).
 *
 * Nothing here is business state invented for the screen: the derived conditions are `BR-060`
 * (a Job with no active Visit), `BR-072`/`BR-074` (a Visit still `SCHEDULED` after its window) and
 * `BR-061`/`BR-062` (a Job awaiting office review). Which records carry a condition is decided once
 * here, with the server clock, so no client decides it again (`BR-001`, `BR-041`).
 */
@Injectable()
export class ManagerHomeService {
  constructor(private readonly database: DatabaseService) {}

  private get db() {
    return this.database.db;
  }

  async readManagerHome(
    scope: OrganizationScope,
    userId: string,
    day: DayWindow,
    now: Date,
  ): Promise<ManagerHomeRead> {
    const [
      viewerDisplayName,
      dayVisits,
      overdueVisits,
      schedulingJobs,
      reviewJobs,
    ] = await Promise.all([
      this.findViewerDisplayName(scope, userId),
      this.findDayVisits(scope, day),
      this.findOverdueVisits(scope, now),
      this.findSchedulingCandidates(scope),
      this.findJobsAwaitingReview(scope),
    ]);

    const technicians = await this.techniciansForVisits(scope, [
      ...dayVisits.map((row) => row.visitId),
      ...overdueVisits.map((row) => row.visitId),
    ]);

    const visits = dayVisits.flatMap((row) => {
      const visit = toVisit(row, technicians.get(row.visitId) ?? [], now);
      return visit === null ? [] : [visit];
    });

    const attention = [
      ...overdueVisits.map((row) => toOverdueItem(row)),
      ...reviewJobs.map((row) => toJobConditionItem('JOB_PENDING_REVIEW', row)),
      ...schedulingJobs.map((row) =>
        toJobConditionItem('JOB_NEEDS_SCHEDULING', row),
      ),
    ].sort(compareAttentionItems);

    return {
      viewerDisplayName,
      attention: attention.slice(0, MANAGER_HOME_ATTENTION_LIMIT),
      attentionTotal: attention.length,
      today: summarize(visits),
      visits: [...visits].sort(compareVisits),
    };
  }

  /**
   * The signed-in member's display name, or `null` when they have no profile yet.
   *
   * It is read inside the caller's membership, so the name belongs to the member the request is
   * authorized for rather than to a member of another organization.
   */
  private async findViewerDisplayName(
    scope: OrganizationScope,
    userId: string,
  ): Promise<string | null> {
    const [row] = await this.db
      .select({
        displayName: userProfiles.displayName,
        firstName: userProfiles.firstName,
        lastName: userProfiles.lastName,
      })
      .from(organizationMembers)
      .leftJoin(
        userProfiles,
        eq(userProfiles.userId, organizationMembers.userId),
      )
      .where(
        and(
          eq(organizationMembers.organizationId, scope.organizationId),
          eq(organizationMembers.userId, userId),
          eq(organizationMembers.status, 'ACTIVE'),
        ),
      )
      .orderBy(asc(organizationMembers.joinedAt))
      .limit(1);

    if (row === undefined) {
      return null;
    }
    if (row.displayName !== null) {
      return row.displayName;
    }
    const composed = [row.firstName, row.lastName]
      .filter((part): part is string => part !== null)
      .join(' ')
      .trim();
    return composed.length === 0 ? null : composed;
  }

  /**
   * The Visits scheduled inside the requested local day, excluding the ones that did not happen.
   *
   * A `CANCELED` or `NO_SHOW` Visit is not work happening today, so it is excluded from the day's
   * schedule and from its counts (`BR-074`). A `COMPLETED` Visit stays in the list: it is what the
   * day has produced so far, and the summary and the list must describe the same set.
   */
  private async findDayVisits(
    scope: OrganizationScope,
    day: DayWindow,
  ): Promise<VisitContextRow[]> {
    return this.db
      .select(visitContextSelection)
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
          visitScope(scope),
          activeCustomer(),
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
   * Every Visit still `SCHEDULED` after the window it was scheduled for has ended (`BR-072`,
   * `BR-074`).
   *
   * The condition is not restricted to the requested day: a Visit scheduled for an earlier day that
   * never advanced is still work that has gone wrong, and hiding it because its day has passed would
   * hide exactly the exception the manager has to resolve.
   */
  private async findOverdueVisits(
    scope: OrganizationScope,
    now: Date,
  ): Promise<VisitContextRow[]> {
    return this.db
      .select(visitContextSelection)
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
          visitScope(scope),
          activeCustomer(),
          eq(visits.status, 'SCHEDULED'),
          isNotNull(visits.scheduledEnd),
          lt(visits.scheduledEnd, now),
        ),
      )
      .orderBy(asc(visits.scheduledStart), asc(jobs.jobNumber));
  }

  /**
   * The Jobs that need scheduling: `NEW` or `IN_PROGRESS` with no Visit in
   * `needsSchedulingActiveVisit` (`BR-060`).
   *
   * A Job holding only a `DRAFT` Visit still qualifies, because a `DRAFT` Visit is not yet scheduled
   * work — which is exactly what this signal reports.
   */
  private async findSchedulingCandidates(
    scope: OrganizationScope,
  ): Promise<JobConditionRow[]> {
    return this.db
      .select(jobConditionSelection)
      .from(jobs)
      .innerJoin(
        customers,
        and(
          eq(customers.id, jobs.customerId),
          eq(customers.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          jobScope(scope),
          activeCustomer(),
          inArray(jobs.status, [...NEEDS_SCHEDULING_JOB_STATUSES]),
          notExists(
            this.db
              .select({ id: visits.id })
              .from(visits)
              .where(
                and(
                  visitScope(scope),
                  eq(visits.jobId, jobs.id),
                  inArray(visits.status, [
                    ...NEEDS_SCHEDULING_ACTIVE_VISIT_STATUSES,
                  ]),
                ),
              ),
          ),
        ),
      )
      .orderBy(asc(jobs.jobNumber));
  }

  /** The Jobs awaiting the office review only an authorized user can perform (`BR-061`, `BR-062`). */
  private async findJobsAwaitingReview(
    scope: OrganizationScope,
  ): Promise<JobConditionRow[]> {
    return this.db
      .select(jobConditionSelection)
      .from(jobs)
      .innerJoin(
        customers,
        and(
          eq(customers.id, jobs.customerId),
          eq(customers.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          jobScope(scope),
          activeCustomer(),
          eq(jobs.status, 'PENDING_REVIEW'),
        ),
      )
      .orderBy(asc(jobs.jobNumber));
  }

  /** The technicians currently assigned to each Visit, Lead first (`BR-068`). */
  private async techniciansForVisits(
    scope: OrganizationScope,
    visitIds: readonly string[],
  ): Promise<Map<string, ManagerHomeTechnician[]>> {
    if (visitIds.length === 0) {
      return new Map();
    }

    const rows = await this.db
      .select({
        visitId: visitTechnicians.visitId,
        membershipId: visitTechnicians.technicianMembershipId,
        roleCode: visitTechnicians.roleCode,
        displayName: userProfiles.displayName,
        firstName: userProfiles.firstName,
        lastName: userProfiles.lastName,
      })
      .from(visitTechnicians)
      .innerJoin(
        organizationMembers,
        eq(organizationMembers.id, visitTechnicians.technicianMembershipId),
      )
      .leftJoin(
        userProfiles,
        eq(userProfiles.userId, organizationMembers.userId),
      )
      .where(
        and(
          eq(visitTechnicians.organizationId, scope.organizationId),
          inArray(visitTechnicians.visitId, [...visitIds]),
        ),
      )
      .orderBy(
        sql`case when ${visitTechnicians.roleCode} = 'LEAD' then 0 else 1 end`,
        asc(visitTechnicians.createdAt),
      );

    const byVisit = new Map<string, ManagerHomeTechnician[]>();
    for (const row of rows) {
      const assigned = byVisit.get(row.visitId) ?? [];
      assigned.push({
        membershipId: row.membershipId,
        name:
          row.displayName ??
          ([row.firstName, row.lastName]
            .filter((part): part is string => part !== null)
            .join(' ')
            .trim() ||
            null),
        roleCode: row.roleCode as AssignmentRoleCode,
      });
      byVisit.set(row.visitId, assigned);
    }
    return byVisit;
  }
}

const visitContextSelection = {
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

const jobConditionSelection = {
  jobId: jobs.id,
  jobNumber: jobs.jobNumber,
  jobTitle: jobs.title,
  jobStatus: jobs.status,
  customerId: customers.id,
  customerName: customers.displayName,
} as const;

function visitScope(scope: OrganizationScope) {
  return eq(visits.organizationId, scope.organizationId);
}

function jobScope(scope: OrganizationScope) {
  return eq(jobs.organizationId, scope.organizationId);
}

/**
 * A Job of a customer the organization still has in active use.
 *
 * `BR-023` hides a deleted customer's Jobs by default, and archiving a customer is how Servora
 * deletes one, so the home does not present work for a customer that was archived. Reaching that
 * work is the explicit "include deleted customers" path the customer list offers.
 */
function activeCustomer() {
  return sql`${customers.deletedAt} is null`;
}

/** One row of today's schedule, built from the Visit with its Job and Customer context. */
function toVisit(
  row: VisitContextRow,
  technicians: readonly ManagerHomeTechnician[],
  now: Date,
): ManagerHomeVisit | null {
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
function visitAddress(row: VisitContextRow): AddressSnapshot | null {
  return (
    readAddressSnapshot(row.visitAddressSnapshot) ??
    readAddressSnapshot(row.jobAddressSnapshot)
  );
}

/** The overdue condition as an attention item (`BR-072`, `BR-074`). */
function toOverdueItem(row: VisitContextRow): ManagerAttentionItem {
  return {
    kind: 'VISIT_OVERDUE',
    jobId: row.jobId,
    jobNumber: row.jobNumber,
    jobTitle: row.jobTitle,
    jobStatus: row.jobStatus as JobStatus,
    customerId: row.customerId,
    customerName: row.customerName,
    visitId: row.visitId,
    scheduledStart: row.scheduledStart,
    scheduledEnd: row.scheduledEnd,
  };
}

/** A Job-level condition as an attention item: it carries no Visit of its own (`BR-060`). */
function toJobConditionItem(
  kind: 'JOB_PENDING_REVIEW' | 'JOB_NEEDS_SCHEDULING',
  row: JobConditionRow,
): ManagerAttentionItem {
  return {
    kind,
    jobId: row.jobId,
    jobNumber: row.jobNumber,
    jobTitle: row.jobTitle,
    jobStatus: row.jobStatus as JobStatus,
    customerId: row.customerId,
    customerName: row.customerName,
    visitId: null,
    scheduledStart: null,
    scheduledEnd: null,
  };
}

/**
 * The counts today's summary shows.
 *
 * The three named counts partition the total, so the card can never report more work than the list
 * it describes: a Visit is `COMPLETED`, under way, or still to come.
 */
function summarize(visits: readonly ManagerHomeVisit[]): ManagerHomeDaySummary {
  let completed = 0;
  let inProgress = 0;
  let upcoming = 0;
  for (const visit of visits) {
    if (visit.visitStatus === 'COMPLETED') {
      completed += 1;
    } else if (
      visit.visitStatus === 'EN_ROUTE' ||
      visit.visitStatus === 'ON_SITE' ||
      visit.visitStatus === 'IN_PROGRESS'
    ) {
      inProgress += 1;
    } else {
      upcoming += 1;
    }
  }
  return { total: visits.length, completed, inProgress, upcoming };
}
