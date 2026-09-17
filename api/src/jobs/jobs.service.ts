import { Injectable } from '@nestjs/common';
import {
  and,
  asc,
  desc,
  eq,
  exists,
  getTableColumns,
  gt,
  inArray,
  isNotNull,
  isNull,
  lt,
  ne,
  notInArray,
  sql,
} from 'drizzle-orm';
import type { SQL } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  customers,
  jobStatusHistory,
  jobs,
  organizationMembers,
  userProfiles,
  visitNotes,
  visitOutcomeHistory,
  visitScheduleHistory,
  visitStatusHistory,
  visitTechnicianHistory,
  visitTechnicians,
  visits,
} from '../database/schema.js';
import { isUniqueViolation } from '../database/unique-violation.js';
import { memberName } from '../members/member-name.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type { JobDetails } from './job-details.dto.js';
import { planAssignmentChanges } from './job-assignment-plan.js';
import type {
  AssignVisitTechniciansDto,
  AddVisitNoteDto,
  ChangeJobStatusDto,
  ChangeVisitStatusDto,
  RescheduleVisitDto,
} from './job-action.dto.js';
import {
  ACTIVE_VISIT_STATUSES,
  TERMINAL_VISIT_STATUSES,
  VISIT_STATUS_TRANSITIONS,
  applicableJobStatusTransitions,
  isPermittedJobStatusTransition,
  isPermittedVisitStatusTransition,
  isReschedulableVisitStatus,
  isVisitStatusCorrection,
  type AssignmentRoleCode,
  type JobStatus,
  type VisitOutcomeCode,
  type VisitStatus,
} from './job.types.js';
import {
  readAssignedTechnicians,
  selectVisitsForJobs,
} from './visit-assignment.js';
import { jobStatusConsequenceForVisitTransition } from './visit-job-consequence.js';
import {
  readJobActivity,
  type JobActivityEventDto,
  type JobActivityOptions,
} from './job-activity.js';

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

/**
 * `BR-062`'s completion invariant is not met: the Job still has an open Visit.
 *
 * A Visit is open while its status is not historical (`BR-074`, `BR-083`). Closing a Job is the
 * office's decision about *its* lifecycle (`BR-059`), so the refusal never touches the Visit: the
 * Visit must reach a historical status through its own field lifecycle, and the Job may be closed
 * then.
 */
export class JobCompletionBlockedError extends Error {
  constructor() {
    super('The job cannot be completed while it has an open visit.');
    this.name = 'JobCompletionBlockedError';
  }
}

/**
 * The client a Job mutation reads and writes through: the pool itself, or the transaction it opened.
 *
 * Drizzle's transaction shares the query-builder surface with the database, so a condition a
 * transition must satisfy can be evaluated through the very transaction that applies it.
 */
type JobMutationClient = Pick<
  DatabaseService['db'],
  'select' | 'insert' | 'update'
>;

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

/**
 * `BR-074` (or `BR-075` for the correction) does not permit the requested Visit transition.
 *
 * The permitted destinations travel with the failure, as the Job status route carries its own, so a
 * client can present the Visit's real options instead of holding a second copy of the lifecycle
 * (`BR-041`, `BR-022`).
 */
export class VisitStatusTransitionNotAllowedError extends Error {
  constructor(
    readonly from: VisitStatus,
    readonly to: VisitStatus,
    readonly allowed: readonly VisitStatus[],
  ) {
    super(`A visit in ${from} cannot move to ${to}.`);
    this.name = 'VisitStatusTransitionNotAllowedError';
  }
}

/**
 * `BR-072`'s conditions for a Visit becoming `SCHEDULED` are not met.
 *
 * It is runtime eligibility over a structurally permitted destination, not a gap in the transition
 * table: the destination exists, and this rule refuses it for a Visit that is not ready to be
 * scheduled. The reason names the condition that failed.
 */
export class VisitSchedulingConditionNotMetError extends Error {
  constructor(
    readonly reason: 'PROPERTY' | 'SCHEDULE' | 'CREW_LEAD',
  ) {
    super('The visit cannot be scheduled yet.');
    this.name = 'VisitSchedulingConditionNotMetError';
  }
}

/**
 * The Visit's Job is closed, so its field record is final (`BR-062`, `BR-079`).
 *
 * `BR-062` prevents a Job being closed while it has an open Visit, so this state should not be
 * reachable; if it is, the API fails closed rather than writing field history under a closed Job.
 * Whether the state itself deserves behaviour of its own stays an `OPEN QUESTION` and nothing here
 * gives it one (`BR-042`, `ADR-019` D4).
 */
export class JobClosedForFieldWorkError extends Error {
  constructor() {
    super('The job is closed; its field record is final.');
    this.name = 'JobClosedForFieldWorkError';
  }
}

/**
 * The idempotency key was already used for another Visit.
 *
 * One operation id names one operation, and that operation addressed one Visit: answering with
 * another Visit's result would report a change the caller did not make (`BR-031`).
 */
export class VisitOperationReusedError extends Error {
  constructor() {
    super('That operation id was already used for another visit.');
    this.name = 'VisitOperationReusedError';
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
 * A caller who reaches a Job through their own field assignments (`BR-009`; `ADR-019` D2).
 *
 * The office caller passes `null` and reads the organization's Jobs, exactly as before this slice. A
 * field caller passes their membership, and the read answers only for a Job that at least one of their
 * current assignments reaches. A Job it does not reach is reported as **not found**, never as
 * forbidden, so a Job id cannot be probed for existence (`BR-001`).
 */
export interface AssignedJobViewer {
  readonly membershipId: string;
}

/**
 * A caller whose write reaches a Visit through their own current assignment (`BR-009`; `ADR-019` D3).
 *
 * The field routes are open to any technician on the Visit's **current crew**, not only the `LEAD`
 * (`BR-068` treats `LEAD` as an assignment role rather than an authority, and `BR-069` lets a manager
 * change it at any time). "Currently assigned" is evaluated at the moment of the action against the
 * same rows `AssignedJobViewer` reads.
 *
 * A caller who reaches the route through the **office** capability writes the whole organization's
 * Visits, exactly as they did before this slice: `ADR-019` D2 applies the assignment scope only to a
 * caller who does not hold the office capability, and the note route follows the same shape. Which
 * Visit an office caller addresses is therefore decided by the capability that admitted them, never
 * by a role name (`BR-006`, `BR-007`).
 */
export interface AssignedVisitWriter {
  readonly membershipId: string;
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
   * One Job of the caller's organization, or `null` when it does not exist there — or when the caller
   * is a field caller whose own assignments do not reach it (`ADR-019` D2).
   *
   * A Job whose Customer the organization has deleted is reported as not found: `BR-023` hides a
   * deleted customer's Jobs by default, and a Job whose customer is gone is reached through the
   * explicit deleted-customer path rather than by its own id.
   */
  async findJobDetailsInOrganization(
    scope: OrganizationScope,
    jobId: string,
    assignedViewer: AssignedJobViewer | null = null,
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
          ...this.assignedViewerConditions(scope, assignedViewer),
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
   * One Job's chronological activity, newest first, or `null` when the Job does not exist here — or
   * when a field caller's own assignments do not reach it (`ADR-019` D2).
   *
   * The caller who reads the Job Details screen reads its activity (`BR-080`): the office caller
   * through `customers.view`, the field caller through `VISIT_VIEW_ASSIGNED`, and both through this one
   * projection. A Job whose Customer the organization deleted is reported as not found exactly as the
   * Job read reports it (`BR-023`). The projection itself lives in `job-activity.ts`; this method only
   * guards the read with the same existence and scope check the Job read applies.
   */
  async findJobActivityInOrganization(
    scope: OrganizationScope,
    jobId: string,
    options: JobActivityOptions = {},
    assignedViewer: AssignedJobViewer | null = null,
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
          ...this.assignedViewerConditions(scope, assignedViewer),
        ),
      )
      .limit(1);

    if (row === undefined) {
      return null;
    }

    return readJobActivity(this.db, scope, jobId, options);
  }

  /**
   * A field caller's scope, as the semi-join both reads apply (`BR-009`; `ADR-019` D2).
   *
   * A Job is readable when the caller's membership is on the **current crew of at least one of its
   * Visits** — including a Visit that has already completed, because a completed assignment stays
   * current until a manager removes it (`BR-069`, domain §10.2). It is a semi-join rather than a join
   * so the read still answers about exactly one Job. It is deliberately not "the caller's own Visits
   * only": the Job is the context of the assignment, and a second Job projection would be a second
   * definition of the same Job (`BR-041`; the trade-off is recorded in `ADR-019` D2).
   *
   * An office caller adds no condition at all, so the read it gets is exactly the read it had before
   * this slice.
   */
  private assignedViewerConditions(
    scope: OrganizationScope,
    viewer: AssignedJobViewer | null,
  ): SQL[] {
    if (viewer === null) {
      return [];
    }

    return [
      exists(
        this.db
          .select({ present: sql`1` })
          .from(visitTechnicians)
          .innerJoin(
            visits,
            and(
              eq(visits.id, visitTechnicians.visitId),
              eq(visits.organizationId, scope.organizationId),
            ),
          )
          .where(
            and(
              eq(visitTechnicians.organizationId, scope.organizationId),
              eq(
                visitTechnicians.technicianMembershipId,
                viewer.membershipId,
              ),
              eq(visits.jobId, jobs.id),
            ),
          ),
      ),
    ];
  }

  /**
   * Moves a Job through its lifecycle (`BR-058`).
   *
   * The destination is validated against the shared structural table first, so a status a client
   * invents, a status that is already current and a destination `BR-058` does not list are all
   * refused — including an attempt to leave a terminal Job by anything other than its reopen.
   *
   * A structurally valid destination may still be one the Job does not currently qualify for, and
   * that is its own rule's answer rather than a gap in the table: `BR-061` decides whether the Job may
   * enter `PENDING_REVIEW`, and `BR-062` decides whether it may be closed. Both conditions are
   * evaluated **inside the transaction that applies the change**, through the same client that writes
   * it, so a Job is never moved on a picture of its Visits that has already changed underneath.
   *
   * The change is recorded as one append-only `job_status_history` row (`BR-033`, `BR-067`). It never
   * writes a Visit's status or history: the Job's business lifecycle and the field execution lifecycle
   * are two state machines (`BR-059`), and the Job moving is not a Visit moving.
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

    await this.db.transaction(async (tx) => {
      if (input.status === 'PENDING_REVIEW') {
        await this.assertJobMayAwaitReview(tx, scope, jobId);
      }
      if (input.status === 'COMPLETED') {
        await this.assertJobHasNoOpenVisit(tx, scope, jobId);
      }

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
   * Advances one Visit through its field lifecycle (`BR-074`, `BR-075`, `BR-077`; `ADR-019` D3, D4,
   * D5).
   *
   * The Visit's own state machine is validated against the shared structural table first, so a status a
   * client invents, the status the Visit already holds and a destination `BR-074` does not list are all
   * refused, with the destinations the Visit really has. `CANCELED` and `NO_SHOW` are among the refused
   * destinations: `BR-066` makes them dispatch actions and no capability authorizes one today
   * (`BR-042`, `ADR-019` D7).
   *
   * A structurally permitted destination may still be one the Visit does not currently qualify for, and
   * that is its own rule's answer rather than a gap in the table: `BR-072` decides whether a Visit may
   * become `SCHEDULED`, so the conditions it states are evaluated and the refusal names the condition
   * that failed. It is the same split `BR-061`/`BR-062` have for Jobs (`BR-058`).
   *
   * The state change, its append-only history row, the outcome completion requires and the Job
   * consequence `BR-058` implies are written in **one** transaction, and the transition is decided
   * against the Visit row locked in that same transaction: an operation a device queued that arrives
   * after the Visit moved on is refused with the state the Visit actually holds, never applied "as
   * close as possible" (`BR-067`, `BR-031`, `BR-086`, offline standard §15).
   *
   * Nothing here changes a Visit's schedule, status history or assignment beyond the one transition, and
   * a Job consequence writes the Job's own history rather than a Visit's (`BR-059`, `BR-067`).
   */
  async changeVisitStatus(
    scope: OrganizationScope,
    jobId: string,
    visitId: string,
    actorMembershipId: string,
    input: ChangeVisitStatusDto,
  ): Promise<JobDetails> {
    await this.requireJobDetails(scope, jobId);
    // The field route is reached by holding a field capability, so its scope is always the caller's own
    // current assignment (`ADR-019` D3). No office caller reaches it today: the office half of the Visit
    // lifecycle is `ADR-019` D7's open question, and nothing is invented for it (`BR-042`).
    const visit = await this.requireVisitForWriter(scope, jobId, visitId, {
      membershipId: actorMembershipId,
    });
    const status = visit.status as VisitStatus;

    // Idempotency is answered before any conflict, because a replay is not a second writer: an operation
    // the device already had applied answers with the state it produced (`BR-031`, `ADR-019` D5).
    if (input.clientOperationId !== null) {
      const applied = await this.findAppliedStatusOperation(
        scope,
        input.clientOperationId,
      );
      if (applied !== null) {
        this.assertSameVisit(applied.visitId, visitId);
        return this.requireJobDetails(scope, jobId);
      }
    }

    if (
      input.expectedVersion !== null &&
      input.expectedVersion !== visit.version
    ) {
      throw new VisitVersionConflictError(visit.version);
    }
    if (!isPermittedVisitStatusTransition(status, input.status)) {
      throw new VisitStatusTransitionNotAllowedError(
        status,
        input.status,
        [...VISIT_STATUS_TRANSITIONS[status]],
      );
    }

    const conflicts =
      input.status === 'SCHEDULED'
        ? await this.assertVisitMayBeScheduled(scope, visit, input.confirmConflicts)
        : [];

    try {
      await this.db.transaction(async (tx) => {
        const [locked] = await tx
          .select()
          .from(visits)
          .where(
            and(
              eq(visits.organizationId, scope.organizationId),
              eq(visits.jobId, jobId),
              eq(visits.id, visitId),
            ),
          )
          .for('update')
          .limit(1);
        if (locked === undefined) {
          throw new VisitNotFoundError();
        }

        if (input.clientOperationId !== null) {
          const applied = await this.findAppliedStatusOperation(
            scope,
            input.clientOperationId,
            tx,
          );
          if (applied !== null) {
            this.assertSameVisit(applied.visitId, visitId);
            return;
          }
        }

        const live = locked.status as VisitStatus;
        if (!isPermittedVisitStatusTransition(live, input.status)) {
          throw new VisitStatusTransitionNotAllowedError(
            live,
            input.status,
            [...VISIT_STATUS_TRANSITIONS[live]],
          );
        }

        const jobStatus = await this.requireLockedJobStatus(tx, scope, jobId);
        if (jobStatus === 'COMPLETED' || jobStatus === 'CANCELED') {
          throw new JobClosedForFieldWorkError();
        }

        const recordedAt = new Date();
        await tx
          .update(visits)
          .set({
            status: input.status,
            version: sql`${visits.version} + 1`,
            updatedAt: recordedAt,
            ...(input.status === 'COMPLETED'
              ? {
                  outcomeCode: input.outcomeCode,
                  outcomeSummary: input.outcomeSummary,
                  outcomeRecordedAt: recordedAt,
                  outcomeRecordedByMembershipId: actorMembershipId,
                }
              : {}),
          })
          .where(
            and(
              eq(visits.organizationId, scope.organizationId),
              eq(visits.id, visitId),
            ),
          );

        await tx.insert(visitStatusHistory).values({
          organizationId: scope.organizationId,
          visitId,
          fromStatus: live,
          toStatus: input.status,
          // `BR-075`'s one correction is recorded as what it is, never as a rewrite of the status it
          // corrects (`BR-067`).
          isCorrection: isVisitStatusCorrection(live, input.status),
          actorMembershipId,
          capturedAt: input.capturedAt,
          clientOperationId: input.clientOperationId,
          confirmedConflicts: conflicts.length === 0 ? null : conflicts,
        });

        if (input.status === 'COMPLETED') {
          // The outcome is recorded with the completion in the same transaction, so `BR-077`'s "outcome
          // before completion" is a property of the record rather than of the request order
          // (`visits_completed_outcome_check`). A DRAFT outcome is not modelled, so there is nothing else
          // to store (`docs/domain/job-visit-domain-model.md` §11.2).
          await tx.insert(visitOutcomeHistory).values({
            organizationId: scope.organizationId,
            visitId,
            outcomeCode: input.outcomeCode as string,
            outcomeSummary: input.outcomeSummary as string,
            previousOutcomeCode: locked.outcomeCode,
            previousOutcomeSummary: locked.outcomeSummary,
            actorMembershipId,
            capturedAt: input.capturedAt,
            clientOperationId: input.clientOperationId,
          });
        }

        await this.applyVisitConsequence(
          tx,
          scope,
          jobId,
          actorMembershipId,
          jobStatus,
          input.status,
          input.outcomeCode,
        );
      });
    } catch (error) {
      // A concurrent replay of the same operation won the race and recorded the same transition. That
      // record is the result this request was after, so the caller is answered with the current state
      // rather than with a failure the device would retry forever (`BR-031`, `BR-014`).
      if (!isUniqueViolation(error)) {
        throw error;
      }
    }

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
   * Adds one text update to a Visit's append-only notes (`BR-027`, `BR-077`; `ADR-019` D3, D5).
   *
   * The note is recorded on a Visit, not the Job itself, because the domain has no confirmed Job-note
   * concept. The Job Activity read already projects Visit notes into the unified timeline (`BR-080`).
   *
   * A note is append-only and carries no expected version, so idempotency alone covers a repeat: a
   * replay writes no second note and answers with the activity the first attempt produced
   * (`ADR-019` D5). Who may write one is the route's authorization; the assignment scope is the same one
   * the transition applies, and an office caller is not bounded by it (`ADR-019` D2, D3).
   */
  async addVisitNote(
    scope: OrganizationScope,
    jobId: string,
    visitId: string,
    actorMembershipId: string,
    input: AddVisitNoteDto,
    writer: AssignedVisitWriter | null,
  ): Promise<JobActivityEventDto[]> {
    await this.requireJobDetails(scope, jobId);
    await this.requireVisitForWriter(scope, jobId, visitId, writer);

    if (input.clientOperationId !== null) {
      const applied = await this.findAppliedNoteOperation(
        scope,
        input.clientOperationId,
      );
      if (applied !== null) {
        this.assertSameVisit(applied.visitId, visitId);
        return readJobActivity(this.db, scope, jobId);
      }
    }

    try {
      await this.db.insert(visitNotes).values({
        organizationId: scope.organizationId,
        visitId,
        authorMembershipId: actorMembershipId,
        body: input.body,
        capturedAt: input.capturedAt,
        clientOperationId: input.clientOperationId,
      });
    } catch (error) {
      // A concurrent replay won the race and recorded the same note; answering with the activity it
      // produced is the result this request was after (`BR-031`).
      if (!isUniqueViolation(error)) {
        throw error;
      }
    }

    return readJobActivity(this.db, scope, jobId);
  }

  /**
   * One Visit this caller may **write**, or a failure the route maps (`ADR-019` D3).
   *
   * A `writer` means the caller is bounded by their own current assignment, and a Visit whose crew does
   * not include them is reported as **not found** — the scope's own answer, exactly as the Job read
   * answers a Job the caller's assignments do not reach, so a Visit id cannot be probed for existence
   * (`ADR-019` D2). `null` means the caller reached the route through the organization's own capability
   * and addresses the organization's Visits, unchanged from before this slice.
   */
  private async requireVisitForWriter(
    scope: OrganizationScope,
    jobId: string,
    visitId: string,
    writer: AssignedVisitWriter | null,
  ): Promise<typeof visits.$inferSelect> {
    const visit = await this.requireVisit(scope, jobId, visitId);
    if (writer === null) {
      return visit;
    }

    const [assignment] = await this.db
      .select({ visitId: visitTechnicians.visitId })
      .from(visitTechnicians)
      .where(
        and(
          eq(visitTechnicians.organizationId, scope.organizationId),
          eq(visitTechnicians.visitId, visitId),
          eq(
            visitTechnicians.technicianMembershipId,
            writer.membershipId,
          ),
        ),
      )
      .limit(1);
    if (assignment === undefined) {
      throw new VisitNotFoundError();
    }
    return visit;
  }

  /** The Visit an already-applied transition was recorded against, or `null` (`BR-031`). */
  private async findAppliedStatusOperation(
    scope: OrganizationScope,
    clientOperationId: string,
    client: JobMutationClient = this.db,
  ): Promise<{ visitId: string } | null> {
    const [row] = await client
      .select({ visitId: visitStatusHistory.visitId })
      .from(visitStatusHistory)
      .where(
        and(
          eq(visitStatusHistory.organizationId, scope.organizationId),
          eq(visitStatusHistory.clientOperationId, clientOperationId),
        ),
      )
      .limit(1);
    return row ?? null;
  }

  /** The Visit an already-recorded note was written on, or `null` (`BR-031`). */
  private async findAppliedNoteOperation(
    scope: OrganizationScope,
    clientOperationId: string,
  ): Promise<{ visitId: string } | null> {
    const [row] = await this.db
      .select({ visitId: visitNotes.visitId })
      .from(visitNotes)
      .where(
        and(
          eq(visitNotes.organizationId, scope.organizationId),
          eq(visitNotes.clientOperationId, clientOperationId),
        ),
      )
      .limit(1);
    return row ?? null;
  }

  /**
   * A key already used for another Visit is refused rather than answered.
   *
   * The idempotency key names the operation the device performed, and that operation addressed one
   * Visit: answering with another Visit's state would report a change the caller did not make
   * (`BR-001`, `BR-031`).
   */
  private assertSameVisit(existingVisitId: string, visitId: string): void {
    if (existingVisitId !== visitId) {
      throw new VisitOperationReusedError();
    }
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
   * `BR-072`: the conditions a Visit must satisfy before it may become `SCHEDULED`.
   *
   * `BR-072` states them explicitly — the Job has a Property, the scheduled start/end are valid, at
   * least one technician is assigned with exactly one Lead (`BR-068`), and the availability check has
   * been performed with any conflict explicitly confirmed (`BR-070`). A destination `BR-074` permits
   * that does not satisfy them is refused with the condition that failed, so the API never refuses an
   * attempt by pretending the destination does not exist.
   *
   * This is the same runtime-eligibility shape `BR-061` and `BR-062` have over the Job's structural
   * table, and it is the only place the conditions are evaluated: the database's own
   * `visits_scheduled_requirements_check` remains the backstop it has always been.
   *
   * @returns the conflicts the caller explicitly accepted, so the record can carry what was accepted.
   */
  private async assertVisitMayBeScheduled(
    scope: OrganizationScope,
    visit: typeof visits.$inferSelect,
    confirmConflicts: boolean,
  ): Promise<readonly ScheduleConflict[]> {
    if (visit.propertyId === null) {
      throw new VisitSchedulingConditionNotMetError('PROPERTY');
    }
    if (visit.scheduledStart === null || visit.scheduledEnd === null) {
      throw new VisitSchedulingConditionNotMetError('SCHEDULE');
    }

    const crew =
      (await readAssignedTechnicians(this.db, scope, [visit.id])).get(
        visit.id,
      ) ?? [];
    const leads = crew.filter((technician) => technician.roleCode === 'LEAD');
    if (crew.length === 0 || leads.length !== 1) {
      throw new VisitSchedulingConditionNotMetError('CREW_LEAD');
    }

    const conflicts = await this.findScheduleConflicts(
      scope,
      visit.id,
      crew.map((technician) => technician.membershipId),
      visit.scheduledStart,
      visit.scheduledEnd,
    );
    if (conflicts.length > 0 && !confirmConflicts) {
      throw new ScheduleConflictError(conflicts);
    }
    return conflicts;
  }

  /**
   * The Job's current status, read inside the transaction that is about to record a field event.
   *
   * The Visit row is locked first and the Job row second, in that order in every field write, so two
   * technicians completing two Visits of the same Job cannot both record the same Job transition: the
   * second one re-reads after the first commits, sees the status it was moving to, and writes nothing —
   * because a status that does not change is not a transition (`BR-058`, `BR-067`).
   */
  private async requireLockedJobStatus(
    client: JobMutationClient,
    scope: OrganizationScope,
    jobId: string,
  ): Promise<JobStatus> {
    const [job] = await client
      .select({ status: jobs.status })
      .from(jobs)
      .where(
        and(
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.id, jobId),
        ),
      )
      .for('update')
      .limit(1);
    if (job === undefined) {
      throw new JobNotFoundError();
    }
    return job.status as JobStatus;
  }

  /**
   * Applies the Job consequence `ADR-019` D4 decides for one recorded Visit event, in the same
   * transaction as the Visit transition (`BR-058`, `BR-059`, `BR-067`).
   *
   * The mapping itself lives in `visit-job-consequence.ts`, so the same rule is not implemented twice
   * (`BR-041`), and nothing here completes or cancels a Job: `BR-062` makes closing a Job an explicit
   * office action and `BR-064` requires a cancellation reason.
   */
  private async applyVisitConsequence(
    client: JobMutationClient,
    scope: OrganizationScope,
    jobId: string,
    actorMembershipId: string,
    currentJobStatus: JobStatus,
    to: VisitStatus,
    outcomeCode: VisitOutcomeCode | null,
  ): Promise<void> {
    const destination = jobStatusConsequenceForVisitTransition(
      to,
      outcomeCode,
      // `BR-061`'s conditions are asked only where the mapping depends on them, and always against the
      // state inside this transaction — the Visit being completed included.
      to === 'COMPLETED'
        ? (await this.jobReviewConditionFailure(client, scope, jobId)) === null
        : false,
    );
    if (destination === null || destination === currentJobStatus) {
      return;
    }

    await client
      .update(jobs)
      .set({
        status: destination,
        version: sql`${jobs.version} + 1`,
        updatedAt: new Date(),
      })
      .where(
        and(
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.id, jobId),
        ),
      );
    // One recorded Job transition (`BR-033`, `BR-067`): the new status, the actor and the server's
    // instant. No note is invented for it, and whether the row should carry an explicit link to the
    // Visit event that caused it stays an open question (`ADR-019` D4, open question 8).
    await client.insert(jobStatusHistory).values({
      organizationId: scope.organizationId,
      jobId,
      fromStatus: currentJobStatus,
      toStatus: destination,
      note: null,
      actorMembershipId,
    });
  }

  /**
   * `BR-061`: a Job may enter `PENDING_REVIEW` only when no Visit remains active and the latest
   * completed Visit's outcome says the Job may be resolved.
   *
   * Only `RESOLVED` says that (`BR-078`: every other outcome expects follow-up). The effect of
   * `NEEDS_QUOTE_APPROVAL` is an **OPEN QUESTION**, so it is not treated as resolvable here rather
   * than being decided by the implementation (`BR-042`).
   *
   * This is runtime eligibility, not a structural limit: `NEW` → `PENDING_REVIEW` is a permitted
   * transition (`BR-058`) that this rule refuses when the field work does not support it, and the
   * refusal names which of the two conditions failed.
   */
  private async assertJobMayAwaitReview(
    client: JobMutationClient,
    scope: OrganizationScope,
    jobId: string,
  ): Promise<void> {
    const failure = await this.jobReviewConditionFailure(client, scope, jobId);
    if (failure !== null) {
      throw new JobReviewConditionNotMetError(failure);
    }
  }

  /**
   * Which of `BR-061`'s two conditions keeps a Job from awaiting review, or `null` when it may.
   *
   * The conditions are defined once here because two callers ask the same question: the Job status
   * route refuses a destination the Job does not qualify for, and the Visit completion applies
   * `BR-061` as the Job consequence `ADR-019` D4 decides. Answering with *which* condition failed keeps
   * the route's refusal as specific as it was.
   */
  private async jobReviewConditionFailure(
    client: JobMutationClient,
    scope: OrganizationScope,
    jobId: string,
  ): Promise<'ACTIVE_VISIT' | 'OUTCOME' | null> {
    const [active] = await client
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
      return 'ACTIVE_VISIT';
    }

    const [latestCompleted] = await client
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
    return latestCompleted?.outcomeCode === 'RESOLVED' ? null : 'OUTCOME';
  }

  /**
   * `BR-062`: a Job must not be completed while it has an open Visit.
   *
   * A Visit is open while its status is not historical — `COMPLETED`, `CANCELED` or `NO_SHOW`
   * (`BR-074`, `BR-083`). A Job whose Visits are all historical may be closed, and a Job with no Visit
   * at all may be closed administratively; a `DRAFT` Visit is a field attempt that has not happened
   * yet, so it is remaining work and keeps the Job open.
   *
   * This is the governing condition `BR-062` states ("no remaining work requires another Visit") made
   * checkable, so completion is refused rather than performed on a Job whose field work is unfinished.
   * Nothing here changes a Visit: a Job reaching a terminal status must not carry a Visit with it
   * (`BR-059`, `BR-067`).
   */
  private async assertJobHasNoOpenVisit(
    client: JobMutationClient,
    scope: OrganizationScope,
    jobId: string,
  ): Promise<void> {
    const [open] = await client
      .select({ id: visits.id })
      .from(visits)
      .where(
        and(
          eq(visits.organizationId, scope.organizationId),
          eq(visits.jobId, jobId),
          notInArray(visits.status, [...TERMINAL_VISIT_STATUSES]),
        ),
      )
      .limit(1);
    if (open !== undefined) {
      throw new JobCompletionBlockedError();
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
