import { Injectable } from '@nestjs/common';
import {
  and,
  asc,
  desc,
  eq,
  getTableColumns,
  gt,
  inArray,
  isNotNull,
  isNull,
  lt,
  ne,
  sql,
} from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  customers,
  jobStatusHistory,
  jobs,
  organizationMembers,
  userProfiles,
  visitScheduleHistory,
  visitNotes,
  visitTechnicianHistory,
  visitTechnicians,
  visits,
} from '../database/schema.js';
import { memberName } from '../members/member-name.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type { JobDetails } from './job-details.dto.js';
import { planAssignmentChanges } from './job-assignment-plan.js';
import type {
  AssignVisitTechniciansDto,
  AddVisitNoteDto,
  ChangeJobStatusDto,
  RescheduleVisitDto,
} from './job-action.dto.js';
import {
  ACTIVE_VISIT_STATUSES,
  applicableJobStatusTransitions,
  isPermittedJobStatusTransition,
  isReschedulableVisitStatus,
  type AssignmentRoleCode,
  type JobStatus,
  type VisitStatus,
} from './job.types.js';
import {
  readAssignedTechnicians,
  selectVisitsForJobs,
} from './visit-assignment.js';
import { readJobActivity, type JobActivityEventDto } from './job-activity.js';

/** The Job does not exist in the caller's organization (`BR-001`). */
export class JobNotFoundError extends Error {
  constructor() {
    super('Job was not found.');
    this.name = 'JobNotFoundError';
  }
}

/** The Visit does not belong to that Job in the caller's organization (`BR-001`). */
export class VisitNotFoundError extends Error {
  constructor() {
    super('Visit was not found on this job.');
    this.name = 'VisitNotFoundError';
  }
}

/** `BR-058` does not permit the requested transition. */
export class JobStatusTransitionNotAllowedError extends Error {
  constructor(
    readonly from: JobStatus,
    readonly to: JobStatus,
    readonly allowed: readonly JobStatus[],
  ) {
    super(`A job in ${from} cannot move to ${to}.`);
    this.name = 'JobStatusTransitionNotAllowedError';
  }
}

/**
 * `BR-064` requires a structured cancellation reason whose catalogue is not defined, so the API does
 * not cancel a Job rather than inventing the reason vocabulary (`BR-042`).
 */
export class JobCancellationUnavailableError extends Error {
  constructor() {
    super('Job cancellation is not available yet.');
    this.name = 'JobCancellationUnavailableError';
  }
}

/** `BR-061`'s entry conditions for `PENDING_REVIEW` are not met. */
export class JobReviewConditionNotMetError extends Error {
  constructor(readonly reason: 'ACTIVE_VISIT' | 'OUTCOME') {
    super('The job cannot await review yet.');
    this.name = 'JobReviewConditionNotMetError';
  }
}

/** `BR-073` only permits rescheduling a Visit that is `SCHEDULED`. */
export class VisitNotReschedulableError extends Error {
  constructor(readonly status: VisitStatus) {
    super(`A visit in ${status} cannot be rescheduled.`);
    this.name = 'VisitNotReschedulableError';
  }
}

/** One technician's overlapping Visit, as `BR-070` requires it to be presented. */
export interface ScheduleConflict {
  readonly visitId: string;
  readonly jobId: string;
  readonly jobNumber: number;
  readonly technicianMembershipId: string;
  readonly technicianName: string | null;
  readonly scheduledStart: string;
  readonly scheduledEnd: string;
}

/**
 * `BR-070` conflicts were detected and the caller has not confirmed them.
 *
 * They are carried so the client can show which Visit, which technician and which time overlap
 * before the user decides, rather than being reduced to a message.
 */
export class ScheduleConflictError extends Error {
  constructor(readonly conflicts: readonly ScheduleConflict[]) {
    super('The new schedule conflicts with another visit.');
    this.name = 'ScheduleConflictError';
  }
}

/** A requested technician is not an active member of the caller's organization. */
export class TechnicianNotAssignableError extends Error {
  constructor(readonly membershipIds: readonly string[]) {
    super('One or more technicians cannot be assigned.');
    this.name = 'TechnicianNotAssignableError';
  }
}

/** The record changed since the client read it (`BR-086`). */
export class JobVersionConflictError extends Error {
  constructor(readonly currentVersion: number) {
    super('The job changed since it was read.');
    this.name = 'JobVersionConflictError';
  }
}

/** The Visit changed since the client read it (`BR-086`). */
export class VisitVersionConflictError extends Error {
  constructor(readonly currentVersion: number) {
    super('The visit changed since it was read.');
    this.name = 'VisitVersionConflictError';
  }
}

/**
 * Reads one Job as the Job Details screen presents it.
 *
 * The read projects authoritative records and stores nothing (`BR-001`). It resolves the same
 * selected Visit the customer-detail Job row uses (`BR-081`), so both surfaces present one schedule
 * and one crew for a Job, and it never fetches a Job by primary key alone: every query is scoped by
 * `organization_id` (`src/tenancy/tenant-scope.ts`).
 */
@Injectable()
export class JobsService {
  constructor(private readonly database: DatabaseService) {}

  private get db() {
    return this.database.db;
  }

  /**
   * One Job of the caller's organization, or `null` when it does not exist there.
   *
   * A Job whose Customer the organization has deleted is reported as not found: `BR-023` hides a
   * deleted customer's Jobs by default, and a Job whose customer is gone is reached through the
   * explicit deleted-customer path rather than by its own id.
   */
  async findJobDetailsInOrganization(
    scope: OrganizationScope,
    jobId: string,
  ): Promise<JobDetails | null> {
    const [row] = await this.db
      .select({
        job: getTableColumns(jobs),
        customerId: customers.id,
        customerName: customers.displayName,
      })
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
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.id, jobId),
          isNull(customers.deletedAt),
        ),
      )
      .limit(1);

    if (row === undefined) {
      return null;
    }

    // The Job may legitimately have no Visit, and a Visit with no schedule is never represented
    // (`BR-051`, `BR-081`), so both reads answer "nothing" rather than failing.
    const selectedVisit =
      (await selectVisitsForJobs(this.db, scope, [row.job.id])).get(
        row.job.id,
      ) ?? null;
    const technicians = await readAssignedTechnicians(
      this.db,
      scope,
      selectedVisit === null ? [] : [selectedVisit.visitId],
    );

    return {
      job: row.job,
      customerId: row.customerId,
      customerName: row.customerName,
      selectedVisit,
      technicians:
        selectedVisit === null
          ? []
          : (technicians.get(selectedVisit.visitId) ?? []),
    };
  }

  /**
   * One Job's chronological activity, newest first, or `null` when the Job does not exist here.
   *
   * The same `customers.view` caller who reads the Job Details screen reads its activity (`BR-080`),
   * and a Job whose Customer the organization deleted is reported as not found exactly as the Job read
   * reports it (`BR-023`). The projection itself lives in `job-activity.ts`; this method only guards the
   * read with the same existence check the Job read applies.
   */
  async findJobActivityInOrganization(
    scope: OrganizationScope,
    jobId: string,
  ): Promise<JobActivityEventDto[] | null> {
    const [row] = await this.db
      .select({ id: jobs.id })
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
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.id, jobId),
          isNull(customers.deletedAt),
        ),
      )
      .limit(1);

    if (row === undefined) {
      return null;
    }

    return readJobActivity(this.db, scope, jobId);
  }

  /**
   * Moves a Job through its lifecycle (`BR-058`).
   *
   * The transition is validated against the shared table, so a status a client invents, a status
   * that is already current and a transition `BR-058` does not permit are all refused. `BR-061`'s
   * entry conditions are checked before `PENDING_REVIEW` is entered, and the change is recorded as
   * an append-only `job_status_history` row (`BR-067`).
   */
  async changeJobStatus(
    scope: OrganizationScope,
    jobId: string,
    actorMembershipId: string,
    input: ChangeJobStatusDto,
  ): Promise<JobDetails> {
    const current = await this.requireJobDetails(scope, jobId);
    const from = current.job.status as JobStatus;

    if (
      input.expectedVersion !== null &&
      input.expectedVersion !== current.job.version
    ) {
      throw new JobVersionConflictError(current.job.version);
    }
    if (input.status === 'CANCELED') {
      throw new JobCancellationUnavailableError();
    }
    if (!isPermittedJobStatusTransition(from, input.status)) {
      throw new JobStatusTransitionNotAllowedError(
        from,
        input.status,
        applicableJobStatusTransitions(from),
      );
    }
    if (input.status === 'PENDING_REVIEW') {
      await this.assertJobMayAwaitReview(scope, jobId);
    }

    await this.db.transaction(async (tx) => {
      await tx
        .update(jobs)
        .set({
          status: input.status,
          version: sql`${jobs.version} + 1`,
          updatedAt: new Date(),
        })
        .where(
          and(
            eq(jobs.organizationId, scope.organizationId),
            eq(jobs.id, jobId),
          ),
        );
      await tx.insert(jobStatusHistory).values({
        organizationId: scope.organizationId,
        jobId,
        fromStatus: from,
        toStatus: input.status,
        note: input.note,
        actorMembershipId,
      });
    });

    return this.requireJobDetails(scope, jobId);
  }

  /**
   * Edits the existing Visit's schedule (`BR-073`).
   *
   * Rescheduling is a schedule change, not a new Visit: the Visit's identity, status and assignments
   * are untouched, the previous schedule stays reconstructible in `visit_schedule_history`, and the
   * change is only permitted while the Visit is `SCHEDULED`.
   */
  async rescheduleVisit(
    scope: OrganizationScope,
    jobId: string,
    visitId: string,
    actorMembershipId: string,
    input: RescheduleVisitDto,
  ): Promise<JobDetails> {
    await this.requireJobDetails(scope, jobId);
    const visit = await this.requireVisit(scope, jobId, visitId);
    const status = visit.status as VisitStatus;

    if (
      input.expectedVersion !== null &&
      input.expectedVersion !== visit.version
    ) {
      throw new VisitVersionConflictError(visit.version);
    }
    if (!isReschedulableVisitStatus(status)) {
      throw new VisitNotReschedulableError(status);
    }

    const crew =
      (await readAssignedTechnicians(this.db, scope, [visitId])).get(visitId) ??
      [];
    const conflicts = await this.findScheduleConflicts(
      scope,
      visitId,
      crew.map((technician) => technician.membershipId),
      input.scheduledStart,
      input.scheduledEnd,
    );
    if (conflicts.length > 0 && !input.confirmConflicts) {
      throw new ScheduleConflictError(conflicts);
    }

    await this.db.transaction(async (tx) => {
      await tx
        .update(visits)
        .set({
          scheduledStart: input.scheduledStart,
          scheduledEnd: input.scheduledEnd,
          arrivalWindowStart: input.arrivalWindowStart,
          arrivalWindowEnd: input.arrivalWindowEnd,
          version: sql`${visits.version} + 1`,
          updatedAt: new Date(),
        })
        .where(
          and(
            eq(visits.organizationId, scope.organizationId),
            eq(visits.id, visitId),
          ),
        );
      await tx.insert(visitScheduleHistory).values({
        organizationId: scope.organizationId,
        visitId,
        previousScheduledStart: visit.scheduledStart,
        previousScheduledEnd: visit.scheduledEnd,
        newScheduledStart: input.scheduledStart,
        newScheduledEnd: input.scheduledEnd,
        previousArrivalWindowStart: visit.arrivalWindowStart,
        previousArrivalWindowEnd: visit.arrivalWindowEnd,
        newArrivalWindowStart: input.arrivalWindowStart,
        newArrivalWindowEnd: input.arrivalWindowEnd,
        reason: input.reason,
        actorMembershipId,
        confirmedConflicts: conflicts.length === 0 ? null : conflicts,
      });
    });

    return this.requireJobDetails(scope, jobId);
  }

  /**
   * States the crew a Visit carries (`BR-068`, `BR-069`).
   *
   * The caller states the **whole** crew with exactly one `LEAD`, so removing the current Lead and
   * choosing the next one is one explicit action and the API never promotes anybody on its own
   * (`BR-069`). Every added, removed and re-roled technician is recorded in
   * `visit_technician_history`, which is append-only: the change is a new recorded fact, never a
   * rewrite of an earlier one.
   */
  async assignVisitTechnicians(
    scope: OrganizationScope,
    jobId: string,
    visitId: string,
    actorMembershipId: string,
    input: AssignVisitTechniciansDto,
  ): Promise<JobDetails> {
    await this.requireJobDetails(scope, jobId);
    const visit = await this.requireVisit(scope, jobId, visitId);

    if (
      input.expectedVersion !== null &&
      input.expectedVersion !== visit.version
    ) {
      throw new VisitVersionConflictError(visit.version);
    }

    const requestedIds = input.technicians.map(
      (technician) => technician.membershipId,
    );
    await this.assertTechniciansAssignable(scope, requestedIds);

    const current =
      (await readAssignedTechnicians(this.db, scope, [visitId])).get(visitId) ??
      [];
    const changes = planAssignmentChanges(current, input.technicians);

    // `BR-070` compares the crew being assigned against work the same technicians already hold. A
    // Visit that is not going to be field work — canceled, a no-show or already completed — has no
    // schedule to conflict with.
    const status = visit.status as VisitStatus;
    const hasActiveSchedule =
      visit.scheduledStart !== null &&
      visit.scheduledEnd !== null &&
      status !== 'CANCELED' &&
      status !== 'NO_SHOW' &&
      status !== 'COMPLETED';
    const conflicts = hasActiveSchedule
      ? await this.findScheduleConflicts(
          scope,
          visitId,
          requestedIds,
          visit.scheduledStart as Date,
          visit.scheduledEnd as Date,
        )
      : [];
    if (conflicts.length > 0 && !input.confirmConflicts) {
      throw new ScheduleConflictError(conflicts);
    }

    await this.db.transaction(async (tx) => {
      // The plan is already in the only order the one-Lead index tolerates: removals, then
      // demotions, then additions, then promotions (`job-assignment-plan.ts`).
      for (const change of changes) {
        const assignment = and(
          eq(visitTechnicians.organizationId, scope.organizationId),
          eq(visitTechnicians.visitId, visitId),
          eq(visitTechnicians.technicianMembershipId, change.membershipId),
        );
        if (change.event === 'REMOVED') {
          await tx.delete(visitTechnicians).where(assignment);
        } else if (change.event === 'ASSIGNED') {
          await tx.insert(visitTechnicians).values({
            organizationId: scope.organizationId,
            visitId,
            technicianMembershipId: change.membershipId,
            roleCode: change.roleCode as AssignmentRoleCode,
          });
        } else {
          await tx
            .update(visitTechnicians)
            .set({
              roleCode: change.roleCode as AssignmentRoleCode,
              updatedAt: new Date(),
            })
            .where(assignment);
        }

        await tx.insert(visitTechnicianHistory).values({
          organizationId: scope.organizationId,
          visitId,
          technicianMembershipId: change.membershipId,
          event: change.event,
          roleCode: change.roleCode,
          previousRoleCode: change.previousRoleCode,
          actorMembershipId,
        });
      }

      // A crew that did not change is not a change: nothing is written and the Visit keeps its
      // version (`BR-067` records what happened, and nothing happened).
      if (changes.length > 0) {
        await tx
          .update(visits)
          .set({ version: sql`${visits.version} + 1`, updatedAt: new Date() })
          .where(
            and(
              eq(visits.organizationId, scope.organizationId),
              eq(visits.id, visitId),
            ),
          );
      }
    });

    return this.requireJobDetails(scope, jobId);
  }

  /**
   * Adds one text update to a Visit's append-only notes (`BR-027`, `BR-077`).
   *
   * The note is recorded on a Visit, not the Job itself, because the domain has no confirmed Job-note
   * concept. The Job Activity read already projects Visit notes into the unified timeline (`BR-080`).
   */
  async addVisitNote(
    scope: OrganizationScope,
    jobId: string,
    visitId: string,
    actorMembershipId: string,
    input: AddVisitNoteDto,
  ): Promise<JobActivityEventDto[]> {
    await this.requireJobDetails(scope, jobId);
    await this.requireVisit(scope, jobId, visitId);

    await this.db.insert(visitNotes).values({
      organizationId: scope.organizationId,
      visitId,
      authorMembershipId: actorMembershipId,
      body: input.body,
    });

    return readJobActivity(this.db, scope, jobId);
  }

  /**
   * One Job of the caller's organization, or a failure a route can map (`BR-001`).
   *
   * An action addresses the same Job the read reports, so a Job whose Customer the organization has
   * deleted is not actionable either (`BR-023`).
   */
  private async requireJobDetails(
    scope: OrganizationScope,
    jobId: string,
  ): Promise<JobDetails> {
    const details = await this.findJobDetailsInOrganization(scope, jobId);
    if (details === null) {
      throw new JobNotFoundError();
    }
    return details;
  }

  /** One Visit of that Job, addressed by `(organizationId, jobId, visitId)` (`BR-001`). */
  private async requireVisit(
    scope: OrganizationScope,
    jobId: string,
    visitId: string,
  ): Promise<typeof visits.$inferSelect> {
    const [visit] = await this.db
      .select()
      .from(visits)
      .where(
        and(
          eq(visits.organizationId, scope.organizationId),
          eq(visits.jobId, jobId),
          eq(visits.id, visitId),
        ),
      )
      .limit(1);
    if (visit === undefined) {
      throw new VisitNotFoundError();
    }
    return visit;
  }

  /**
   * `BR-061`: a Job may enter `PENDING_REVIEW` only when no Visit remains active and the latest
   * completed Visit's outcome says the Job may be resolved.
   *
   * Only `RESOLVED` says that (`BR-078`: every other outcome expects follow-up). The effect of
   * `NEEDS_QUOTE_APPROVAL` is an **OPEN QUESTION**, so it is not treated as resolvable here rather
   * than being decided by the implementation (`BR-042`).
   */
  private async assertJobMayAwaitReview(
    scope: OrganizationScope,
    jobId: string,
  ): Promise<void> {
    const [active] = await this.db
      .select({ id: visits.id })
      .from(visits)
      .where(
        and(
          eq(visits.organizationId, scope.organizationId),
          eq(visits.jobId, jobId),
          inArray(visits.status, [...ACTIVE_VISIT_STATUSES]),
        ),
      )
      .limit(1);
    if (active !== undefined) {
      throw new JobReviewConditionNotMetError('ACTIVE_VISIT');
    }

    const [latestCompleted] = await this.db
      .select({ outcomeCode: visits.outcomeCode })
      .from(visits)
      .where(
        and(
          eq(visits.organizationId, scope.organizationId),
          eq(visits.jobId, jobId),
          eq(visits.status, 'COMPLETED'),
        ),
      )
      .orderBy(desc(visits.outcomeRecordedAt))
      .limit(1);
    if (latestCompleted?.outcomeCode !== 'RESOLVED') {
      throw new JobReviewConditionNotMetError('OUTCOME');
    }
  }

  /** Every requested technician must be an active member of the caller's organization. */
  private async assertTechniciansAssignable(
    scope: OrganizationScope,
    membershipIds: readonly string[],
  ): Promise<void> {
    const rows = await this.db
      .select({
        id: organizationMembers.id,
        status: organizationMembers.status,
      })
      .from(organizationMembers)
      .where(
        and(
          eq(organizationMembers.organizationId, scope.organizationId),
          inArray(organizationMembers.id, [...membershipIds]),
        ),
      );
    const assignable = new Set(
      rows
        .filter((member) => member.status === 'ACTIVE')
        .map((member) => member.id),
    );
    const unavailable = membershipIds.filter(
      (membershipId) => !assignable.has(membershipId),
    );
    if (unavailable.length > 0) {
      throw new TechnicianNotAssignableError(unavailable);
    }
  }

  /**
   * The `BR-070` availability conflicts for one technician set and one time window.
   *
   * A conflict is another non-canceled Visit that shares a technician and whose window overlaps:
   * the API reports it so the user can be shown which Visit, which technician and which time, and
   * `BR-070` keeps it a warning rather than a prohibition. The Visit being changed is excluded,
   * because it is the one whose crew or schedule is being edited.
   */
  private async findScheduleConflicts(
    scope: OrganizationScope,
    visitId: string,
    technicianMembershipIds: readonly string[],
    scheduledStart: Date,
    scheduledEnd: Date,
  ): Promise<readonly ScheduleConflict[]> {
    if (technicianMembershipIds.length === 0) {
      return [];
    }

    const rows = await this.db
      .select({
        visitId: visitTechnicians.visitId,
        jobId: visits.jobId,
        jobNumber: jobs.jobNumber,
        technicianMembershipId: visitTechnicians.technicianMembershipId,
        displayName: userProfiles.displayName,
        firstName: userProfiles.firstName,
        lastName: userProfiles.lastName,
        scheduledStart: visits.scheduledStart,
        scheduledEnd: visits.scheduledEnd,
      })
      .from(visitTechnicians)
      .innerJoin(visits, eq(visits.id, visitTechnicians.visitId))
      .innerJoin(jobs, eq(jobs.id, visits.jobId))
      .leftJoin(
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
          inArray(visitTechnicians.technicianMembershipId, [
            ...technicianMembershipIds,
          ]),
          ne(visitTechnicians.visitId, visitId),
          ne(visits.status, 'CANCELED'),
          isNotNull(visits.scheduledStart),
          isNotNull(visits.scheduledEnd),
          // A real overlap, not a touching window: one ends exactly when the other starts.
          lt(visits.scheduledStart, scheduledEnd),
          gt(visits.scheduledEnd, scheduledStart),
        ),
      )
      .orderBy(asc(visits.scheduledStart));

    return rows.map((row) => ({
      visitId: row.visitId,
      jobId: row.jobId,
      jobNumber: row.jobNumber,
      technicianMembershipId: row.technicianMembershipId,
      technicianName: memberName(row),
      scheduledStart: (row.scheduledStart as Date).toISOString(),
      scheduledEnd: (row.scheduledEnd as Date).toISOString(),
    }));
  }
}
