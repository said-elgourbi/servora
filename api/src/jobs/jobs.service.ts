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
import { alias } from 'drizzle-orm/pg-core';
import {
  addressSnapshotOf,
  readAddressSnapshot,
} from '../address/address-snapshot.js';
import { CustomersService } from '../customers/customers.service.js';
import {
  PropertiesService,
  PropertyNotFoundError,
  type Executor,
} from '../customers/properties.service.js';
import { DatabaseService } from '../database/database.service.js';
import {
  customers,
  adHocWorkReports,
  followUpVisitRequestMessages,
  followUpVisitRequests,
  jobStatusHistory,
  jobs,
  organizationJobNumberCounters,
  organizationMembers,
  properties,
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
import type { CreateJobDto } from './job-create.dto.js';
import type {
  AdHocWorkReportContext,
  AdHocWorkReportDto,
  AdHocWorkReportReviewDto,
  ConvertAdHocWorkReportDto,
  LinkAdHocWorkReportDto,
  SubmitAdHocWorkReportDto,
} from './ad-hoc-work-report.dto.js';
import { toAdHocWorkReportDto } from './ad-hoc-work-report.dto.js';
import type {
  AdHocReportCustomerOptionDto,
  AdHocReportCustomerScope,
  AdHocReportJobOptionDto,
  AdHocReportPropertyOptionDto,
} from './ad-hoc-work-report-options.dto.js';
import type {
  DirectCreateVisitDto,
  FollowUpVisitApprovalDto,
  FollowUpVisitRequestContext,
  FollowUpVisitRequestDto,
  FollowUpVisitReviewDto,
  ReplyFollowUpVisitRequestDto,
  RequestedVisitTechnician,
  SubmitFollowUpVisitRequestDto,
} from './follow-up-visit-request.dto.js';
import { toFollowUpVisitRequestDto } from './follow-up-visit-request.dto.js';
import type { JobDetails } from './job-details.dto.js';
import { planAssignmentChanges } from './job-assignment-plan.js';
import type {
  AssignVisitTechniciansDto,
  AddVisitNoteDto,
  EditVisitNoteDto,
  RemoveVisitNoteDto,
  ChangeJobStatusDto,
  ChangeVisitStatusDto,
  CompleteVisitDto,
  RescheduleVisitDto,
} from './job-action.dto.js';
import {
  TERMINAL_VISIT_STATUSES,
  ACTIVE_VISIT_STATUSES,
  VISIT_STATUS_TRANSITIONS,
  applicableJobStatusTransitions,
  isPermittedJobStatusTransition,
  isPermittedVisitStatusTransition,
  isReschedulableVisitStatus,
  isTerminalVisitStatus,
  isVisitStatusCorrection,
  type AssignmentRoleCode,
  type FollowUpVisitRequestStatus,
  type JobStatus,
  type VisitOutcomeCode,
  type VisitStatus,
} from './job.types.js';
import {
  readAssignedTechnicians,
  readJobVisits,
  selectVisitsForJobs,
} from './visit-assignment.js';
import { jobStatusConsequenceForVisitTransition } from './visit-job-consequence.js';
import {
  readJobActivity,
  type JobActivityEventDto,
  type JobActivityOptions,
} from './job-activity.js';

/** How many Customer matches the type-ahead search answers at most (`BR-AH-009`). */
const AD_HOC_CUSTOMER_SEARCH_RESULT_LIMIT = 20;

/** The Job does not exist in the caller's organization (`BR-001`). */
export class JobNotFoundError extends Error {
  constructor() {
    super('Job was not found.');
    this.name = 'JobNotFoundError';
  }
}

/**
 * The Customer a Job is created for does not exist in the caller's organization, or has been deleted
 * (`BR-001`, `BR-023`).
 *
 * A Customer another organization owns and a Customer that does not exist are reported identically, so
 * an id cannot be probed for existence (`BR-023`).
 */
export class JobCustomerNotFoundError extends Error {
  constructor() {
    super('Customer was not found.');
    this.name = 'JobCustomerNotFoundError';
  }
}

/**
 * The Customer a Job is created for exists but does not accept new work (`BR-023`).
 *
 * A customer may be set `INACTIVE` without being deleted, so this is a state the API reports rather
 * than treating as absent: the Customer is there, and the operation conflicts with its lifecycle.
 */
export class JobCustomerInactiveError extends Error {
  constructor() {
    super('Customer is not active.');
    this.name = 'JobCustomerInactiveError';
  }
}

/** The Visit does not belong to that Job in the caller's organization (`BR-001`). */
export class VisitNotFoundError extends Error {
  constructor() {
    super('Visit was not found on this job.');
    this.name = 'VisitNotFoundError';
  }
}

export class FollowUpVisitRequestNotFoundError extends Error {
  constructor() {
    super('Follow-up visit request was not found.');
    this.name = 'FollowUpVisitRequestNotFoundError';
  }
}

export class FollowUpVisitRequestConflictError extends Error {
  constructor(
    readonly currentStatus: string,
    readonly currentVersion: number,
  ) {
    super('The follow-up visit request changed since it was read.');
    this.name = 'FollowUpVisitRequestConflictError';
  }
}

export class FollowUpVisitRequestNotReviewableError extends Error {
  constructor(readonly status: string) {
    super(`A follow-up visit request in ${status} cannot be reviewed.`);
    this.name = 'FollowUpVisitRequestNotReviewableError';
  }
}

/**
 * Only a request the office returned for clarification is awaiting an answer (`BR-FV-012`).
 *
 * It is its own outcome rather than a review refusal: nothing is being decided here, so a client can
 * tell "this request was not returned for clarification" apart from "this request is no longer
 * reviewable". The destination the requester wanted — a request the office reviews again — is not
 * reachable from the state the request is in.
 */
export class FollowUpVisitRequestNotAwaitingReplyError extends Error {
  constructor(readonly status: string) {
    super(`A follow-up visit request in ${status} is not awaiting an answer.`);
    this.name = 'FollowUpVisitRequestNotAwaitingReplyError';
  }
}

export class AdHocWorkReportNotFoundError extends Error {
  constructor() {
    super('Ad-hoc work report was not found.');
    this.name = 'AdHocWorkReportNotFoundError';
  }
}

export class AdHocWorkReportConflictError extends Error {
  constructor(
    readonly currentStatus: string,
    readonly currentVersion: number,
  ) {
    super('The ad-hoc work report changed since it was read.');
    this.name = 'AdHocWorkReportConflictError';
  }
}

export class AdHocWorkReportNotReviewableError extends Error {
  constructor(readonly status: string) {
    super(`An ad-hoc work report in ${status} cannot be reviewed.`);
    this.name = 'AdHocWorkReportNotReviewableError';
  }
}

export class AdHocWorkReportLocationRequiredError extends Error {
  constructor() {
    super(
      'A customer and property are required to create a new job from the report.',
    );
    this.name = 'AdHocWorkReportLocationRequiredError';
  }
}

/** One message of a request's clarification conversation (`BR-FV-012`). */
type FollowUpVisitRequestMessageRow =
  typeof followUpVisitRequestMessages.$inferSelect;

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
export class VisitNoteForbiddenError extends Error {
  constructor() {
    super('This note can only be changed by its author or a manager.');
    this.name = 'VisitNoteForbiddenError';
  }
}

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
  constructor(readonly reason: 'PROPERTY' | 'SCHEDULE' | 'CREW_LEAD') {
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
 * The Visit write routes are open to any technician on the Visit's **current crew**, not only the `LEAD`
 * (`BR-068` treats `LEAD` as an assignment role rather than an authority, and `BR-069` lets a manager
 * change it at any time). "Currently assigned" is evaluated at the moment of the action against the
 * same rows `AssignedJobViewer` reads.
 *
 * A caller who reaches a route through the **office** capability writes the whole organization's Visits
 * without crew membership: `BR-093` makes that capability the second authorization of the Visit status
 * route, and the note route has followed the same shape all along. `ADR-019` D2 applies the assignment
 * scope only to a caller who does **not** hold the office capability. Which Visit an office caller
 * addresses is therefore decided by the capability that admitted them, never by a role name (`BR-006`,
 * `BR-007`).
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
  constructor(
    private readonly database: DatabaseService,
    private readonly properties: PropertiesService,
    private readonly customers: CustomersService,
  ) {}

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
    representedVisitId: string | null = null,
  ): Promise<JobDetails | null> {
    const [row] = await this.db
      .select({
        job: getTableColumns(jobs),
        customerId: customers.id,
        customerName: customers.displayName,
        // The Customer's own contact details (`BR-092`). They are columns of the row this read already
        // joins, so resolving them costs no extra query; whether the route **exposes** them is its
        // second authorization question, answered at the projection (`JobDetailsReadOptions`).
        customerEmail: customers.email,
        customerPhone: customers.phone,
        customerNotes: customers.notes,
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

    // The Job's Visits in their own sequence (`BR-047`, `BR-071`), each with the crew it carries. One
    // read of the crews serves every Visit, so a Job with several Visits does not cost one query each
    // (`BR-068`, `dev.md` §6).
    const jobVisits = await readJobVisits(this.db, scope, row.job.id);
    // The Job may legitimately have no Visit. When a caller names a Visit, the Job is represented by
    // that exact field attempt; otherwise the legacy default selection still applies (`BR-081`).
    const requestedVisit = representedVisitId
      ? (jobVisits.find((visit) => visit.visitId === representedVisitId) ??
        null)
      : null;
    const selectedVisit =
      requestedVisit !== null &&
      requestedVisit.scheduledStart !== null &&
      requestedVisit.scheduledEnd !== null
        ? {
            visitId: requestedVisit.visitId,
            status: requestedVisit.status,
            scheduledStart: requestedVisit.scheduledStart,
            scheduledEnd: requestedVisit.scheduledEnd,
            version: requestedVisit.version,
          }
        : ((await selectVisitsForJobs(this.db, scope, [row.job.id])).get(
            row.job.id,
          ) ?? null);
    const technicians = await readAssignedTechnicians(
      this.db,
      scope,
      jobVisits.map((visit) => visit.visitId),
    );
    // The Customer's contact persons (`BR-092`, `BR-095`). They are read through the service that owns
    // their scope, their removal rule and their order, so the field block and the office detail can
    // never disagree about which contacts exist or which one comes first (`BR-041`). Whether the read
    // **exposes** them is still the second authorization question, answered at the projection.
    const contactPersons = await this.customers.listContacts(
      scope,
      row.customerId,
    );
    const followUpRequestConditions = [
      eq(followUpVisitRequests.organizationId, scope.organizationId),
      eq(followUpVisitRequests.jobId, row.job.id),
      ...(selectedVisit === null
        ? []
        : [eq(followUpVisitRequests.sourceVisitId, selectedVisit.visitId)]),
      ...(assignedViewer === null
        ? []
        : [
            eq(
              followUpVisitRequests.requestingTechnicianMembershipId,
              assignedViewer.membershipId,
            ),
          ]),
    ];
    const [latestFollowUpVisitRequest] = await this.db
      .select()
      .from(followUpVisitRequests)
      .where(and(...followUpRequestConditions))
      .orderBy(desc(followUpVisitRequests.createdAt))
      .limit(1);

    return {
      job: row.job,
      customerId: row.customerId,
      customerName: row.customerName,
      customerContactDetails: {
        email: row.customerEmail,
        phone: row.customerPhone,
        notes: row.customerNotes,
        contacts: contactPersons,
      },
      selectedVisit,
      technicians:
        selectedVisit === null
          ? []
          : (technicians.get(selectedVisit.visitId) ?? []),
      visits: jobVisits.map((visit) => ({
        ...visit,
        technicians: technicians.get(visit.visitId) ?? [],
      })),
      followUpVisitRequest:
        latestFollowUpVisitRequest === undefined
          ? null
          : {
              id: latestFollowUpVisitRequest.id,
              jobId: latestFollowUpVisitRequest.jobId,
              sourceVisitId: latestFollowUpVisitRequest.sourceVisitId,
              createdVisitId: latestFollowUpVisitRequest.createdVisitId,
              requestingTechnicianMembershipId:
                latestFollowUpVisitRequest.requestingTechnicianMembershipId,
              status:
                latestFollowUpVisitRequest.status as FollowUpVisitRequestStatus,
              version: latestFollowUpVisitRequest.version,
            },
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
   * Creates a Job for a Customer at a Property (`BR-021`, `BR-047` – `BR-053`, `BR-056`).
   *
   * The supported create operation requires both an **active Customer** and an **active Property that
   * currently belongs to that Customer** (`BR-094`): a Job is work for a party receiving service
   * (`BR-048`) performed at a location (`BR-049`), and the Product decision is that this operation
   * states both. The database still permits a property-less Job — `BR-051` keeps a Job creatable
   * without a Visit and `BR-056` lets a Property be added later — so a Job created by another path or
   * an earlier one remains valid; it is this operation that requires the Property.
   *
   * Everything the Job is created with that a client must not decide belongs to the backend: the
   * identifier, the organization-scoped Job number (`BR-052`), the `NEW` status (`BR-058`), the
   * Property address snapshot (`BR-056`) and the version. The client names the Customer, the Property
   * and the title, and nothing else.
   *
   * **One transaction.** The Customer, the Property (under its row lock), the Job-number allocation and
   * the insert are one unit, so a failure leaves neither a consumed number nor a half-created Job. The
   * counter row is the serialization point for concurrent creation within an organization
   * (`docs/domain/job-visit-domain-model.md` §13, §15), and `unique (organization_id, job_number)`
   * backs it at the database.
   *
   * **No Visit is created.** A Visit is one field attempt (`BR-047`, `BR-051`): creating the work
   * request never creates or schedules field execution, and a Job with no Visit is the normal state a
   * new Job is in. The Job's `NEW` status is the Job lifecycle's own, not a Visit's (`BR-059`).
   *
   * The initial state is recorded as append-only status history (`BR-033`, `BR-067`). The Job enters
   * `NEW` from **no** status, which is what `job_status_history` permits a `null` previous status for:
   * nothing is fabricated as the status the Job was in before it existed.
   */
  async createJob(
    scope: OrganizationScope,
    actorMembershipId: string,
    input: CreateJobDto,
  ): Promise<JobDetails> {
    const jobId = await this.db.transaction(async (tx) => {
      const [customer] = await tx
        .select({ id: customers.id, status: customers.status })
        .from(customers)
        .where(
          and(
            eq(customers.organizationId, scope.organizationId),
            eq(customers.id, input.customerId),
            isNull(customers.deletedAt),
          ),
        )
        .limit(1);
      if (customer === undefined) {
        throw new JobCustomerNotFoundError();
      }
      if (customer.status !== 'ACTIVE') {
        throw new JobCustomerInactiveError();
      }

      // The Property's own rules are the Property domain's, evaluated here inside this transaction:
      // it must belong to this Customer and be `ACTIVE` (`BR-050`, `BR-083`).
      const property =
        await this.properties.assertPropertyAvailableForCustomerNewWork(
          tx,
          scope,
          input.customerId,
          input.propertyId,
        );

      // One statement bootstraps the counter for a new organization and increments it, so two
      // concurrent creations cannot read the same number (`BR-052`). The inserted value states the
      // first number explicitly: the increment applies only on conflict, so a fresh row must already
      // carry the number this creation takes.
      const [counter] = await tx
        .insert(organizationJobNumberCounters)
        .values({ organizationId: scope.organizationId, lastJobNumber: 1 })
        .onConflictDoUpdate({
          target: organizationJobNumberCounters.organizationId,
          set: {
            lastJobNumber: sql`${organizationJobNumberCounters.lastJobNumber} + 1`,
            updatedAt: sql`now()`,
          },
        })
        .returning({ jobNumber: organizationJobNumberCounters.lastJobNumber });
      if (counter === undefined) {
        throw new Error('The job number counter returned no row.');
      }
      const [job] = await tx
        .insert(jobs)
        .values({
          organizationId: scope.organizationId,
          jobNumber: counter.jobNumber,
          customerId: input.customerId,
          propertyId: property.id,
          // The location is frozen as it is now; a later edit of the Property never rewrites it
          // (`BR-056`, `BR-057`).
          propertyAddressSnapshot: addressSnapshotOf(property),
          title: input.title,
          description: input.description,
          // A Job is created `NEW`: the status is the lifecycle's first state and never a client's
          // (`BR-058`).
          status: 'NEW',
          version: 1,
        })
        .returning({ id: jobs.id });
      if (job === undefined) {
        throw new Error('The job insert returned no row.');
      }
      await tx.insert(jobStatusHistory).values({
        organizationId: scope.organizationId,
        jobId: job.id,
        fromStatus: null,
        toStatus: 'NEW',
        actorMembershipId,
      });

      return job.id;
    });

    // The answer is the read's own projection, so a client is handed the Job exactly as the Job
    // Details screen reads it — including the number, the status and the address the backend chose
    // (`BR-001`, `BR-041`).
    return this.requireJobDetails(scope, jobId);
  }

  async submitAdHocWorkReport(
    scope: OrganizationScope,
    actorMembershipId: string,
    input: SubmitAdHocWorkReportDto,
  ): Promise<AdHocWorkReportDto> {
    if (input.clientOperationId !== null) {
      const applied = await this.findAppliedAdHocReportOperation(
        scope,
        input.clientOperationId,
      );
      if (applied !== null) {
        return this.toAdHocWorkReportDto(this.db, scope, applied);
      }
    }

    const report = await this.db.transaction(async (tx) => {
      await this.assertAdHocWorkReportContext(tx, scope, input);
      const [inserted] = await tx
        .insert(adHocWorkReports)
        .values({
          organizationId: scope.organizationId,
          reportingTechnicianMembershipId: actorMembershipId,
          customerId: input.customerId,
          propertyId: input.propertyId,
          knownJobId: input.knownJobId,
          workStartedAt: input.workStartedAt,
          workEndedAt: input.workEndedAt,
          outcomeCode: input.outcomeCode,
          summary: input.summary,
          notes: input.notes,
          reportedCustomerName: input.reportedCustomerName,
          reportedCustomerPhone: input.reportedCustomerPhone,
          reportedCustomerAddress: input.reportedCustomerAddress,
          clientOperationId: input.clientOperationId,
        })
        .onConflictDoNothing()
        .returning();
      return inserted;
    });

    if (report !== undefined) {
      return this.toAdHocWorkReportDto(this.db, scope, report);
    }

    if (input.clientOperationId !== null) {
      const applied = await this.findAppliedAdHocReportOperation(
        scope,
        input.clientOperationId,
      );
      if (applied !== null) {
        return this.toAdHocWorkReportDto(this.db, scope, applied);
      }
    }
    throw new Error('The ad-hoc report insert returned no row.');
  }

  async listAdHocWorkReports(
    scope: OrganizationScope,
    actorMembershipId: string,
    canReview: boolean,
  ): Promise<readonly AdHocWorkReportDto[]> {
    const conditions = [
      eq(adHocWorkReports.organizationId, scope.organizationId),
    ];
    if (!canReview) {
      conditions.push(
        eq(adHocWorkReports.reportingTechnicianMembershipId, actorMembershipId),
      );
    }
    const rows = await this.db
      .select()
      .from(adHocWorkReports)
      .where(and(...conditions))
      .orderBy(desc(adHocWorkReports.createdAt));
    const contexts = await this.readAdHocWorkReportContexts(
      this.db,
      scope,
      rows.map((row) => row.id),
    );
    return rows.map((row) =>
      toAdHocWorkReportDto(
        row,
        this.requireAdHocWorkReportContext(contexts, row.id),
      ),
    );
  }

  async getAdHocWorkReport(
    scope: OrganizationScope,
    actorMembershipId: string,
    canReview: boolean,
    reportId: string,
  ): Promise<AdHocWorkReportDto> {
    const conditions = [
      eq(adHocWorkReports.organizationId, scope.organizationId),
      eq(adHocWorkReports.id, reportId),
    ];
    if (!canReview) {
      conditions.push(
        eq(adHocWorkReports.reportingTechnicianMembershipId, actorMembershipId),
      );
    }
    const [report] = await this.db
      .select()
      .from(adHocWorkReports)
      .where(and(...conditions))
      .limit(1);
    if (report === undefined) {
      throw new AdHocWorkReportNotFoundError();
    }
    return this.toAdHocWorkReportDto(this.db, scope, report);
  }

  /**
   * Searches the Customers a reporter may name on an ad-hoc work report (`BR-AH-009`).
   *
   * The search is a type-ahead, not a list: it answers a name query of at least two characters and
   * only over the Customers the caller is authorized to reach. An office caller (`customers.view`)
   * searches the organization's active Customers; a field caller (`customers.view_assigned`, `BR-092`)
   * searches only the Customers of Jobs their own current crew includes. The scope is the assignment,
   * never the Customer, exactly as the Job Details customer block scopes it (`ADR-019` D2).
   *
   * It decides nothing a client may later submit: the submit route re-validates whatever Customer id
   * the reporter chooses (`BR-001`, `BR-007`).
   */
  async searchAdHocReportCustomers(
    scope: OrganizationScope,
    customerScope: AdHocReportCustomerScope,
    query: string,
  ): Promise<readonly AdHocReportCustomerOptionDto[]> {
    return this.db
      .select({ id: customers.id, displayName: customers.displayName })
      .from(customers)
      .where(
        and(
          eq(customers.organizationId, scope.organizationId),
          eq(customers.status, 'ACTIVE'),
          isNull(customers.deletedAt),
          sql`${customers.displayName} ilike ${`%${query}%`}`,
          ...this.adHocReportCustomerScopeConditions(scope, customerScope),
        ),
      )
      .orderBy(asc(customers.displayName))
      .limit(AD_HOC_CUSTOMER_SEARCH_RESULT_LIMIT);
  }

  /**
   * Lists the active Properties of a Customer a reporter may select (`BR-AH-009`, `BR-050`).
   *
   * The Customer must first be one the caller is authorized to reach; an id outside that scope is
   * reported as not found rather than forbidden, so a Customer id cannot be probed (`BR-001`).
   */
  async listAdHocReportProperties(
    scope: OrganizationScope,
    customerScope: AdHocReportCustomerScope,
    customerId: string,
  ): Promise<readonly AdHocReportPropertyOptionDto[]> {
    await this.requireAdHocReportCustomerSelectable(scope, customerScope, customerId);
    const rows = await this.customers.findCustomerPropertiesInOrganization(
      scope,
      customerId,
      { status: 'ACTIVE' },
    );
    return rows.map((row) => ({
      id: row.property.id,
      name: row.property.name,
      addressLine1: row.property.addressLine1,
      addressLine2: row.property.addressLine2,
      city: row.property.city,
      province: row.property.province,
      postalCode: row.property.postalCode,
      country: row.property.country,
    }));
  }

  /**
   * Lists the Jobs of a Customer a reporter may name as the related work (`BR-AH-009`).
   *
   * The Job is an optional technician hint, not a decision: it says "this work relates to that Job"
   * for the office to read, and the submit route validates the id the same way it validates every
   * other part of the report (`BR-001`, `BR-007`).
   */
  async listAdHocReportJobs(
    scope: OrganizationScope,
    customerScope: AdHocReportCustomerScope,
    customerId: string,
  ): Promise<readonly AdHocReportJobOptionDto[]> {
    await this.requireAdHocReportCustomerSelectable(scope, customerScope, customerId);
    const rows = await this.customers.findCustomerJobsInOrganization(
      scope,
      customerId,
    );
    return rows.map((row) => ({
      id: row.job.id,
      jobNumber: row.job.jobNumber,
      title: row.job.title,
    }));
  }

  /**
   * The Customer scope a reporter's discoverability is narrowed to (`BR-092`, `ADR-019` D2).
   *
   * An office caller (`null`) is not narrowed: they read the organization's Customers. A field caller
   * is narrowed to the Customers of Jobs whose Visit crew currently includes them — the same rows
   * `assignedViewerConditions` reads, expressed over Customers rather than one Job.
   */
  private adHocReportCustomerScopeConditions(
    scope: OrganizationScope,
    customerScope: AdHocReportCustomerScope,
  ): SQL[] {
    switch (customerScope.kind) {
      case 'ORGANIZATION':
        return [];
      case 'NONE':
        // No Customer read capability: the search and the option reads answer nothing, and the
        // unknown-customer path is the only way a reporter states who the work was for (`BR-AH-009`).
        return [sql`false`];
      case 'ASSIGNED':
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
              .innerJoin(
                jobs,
                and(
                  eq(jobs.id, visits.jobId),
                  eq(jobs.organizationId, scope.organizationId),
                ),
              )
              .where(
                and(
                  eq(visitTechnicians.organizationId, scope.organizationId),
                  eq(
                    visitTechnicians.technicianMembershipId,
                    customerScope.membershipId,
                  ),
                  eq(jobs.customerId, customers.id),
                ),
              ),
          ),
        ];
    }
  }

  /**
   * Asserts a Customer is one the caller may select on an ad-hoc work report (`BR-AH-009`).
   *
   * A Customer outside the caller's organization, an inactive or deleted one, and — for a field
   * caller — one their own assignments do not reach are all reported as not found, the way the Job
   * reads report a Job a technician is not assigned to (`BR-092`, `ADR-019` D2).
   */
  private async requireAdHocReportCustomerSelectable(
    scope: OrganizationScope,
    customerScope: AdHocReportCustomerScope,
    customerId: string,
  ): Promise<void> {
    const [customer] = await this.db
      .select({ id: customers.id })
      .from(customers)
      .where(
        and(
          eq(customers.organizationId, scope.organizationId),
          eq(customers.id, customerId),
          eq(customers.status, 'ACTIVE'),
          isNull(customers.deletedAt),
          ...this.adHocReportCustomerScopeConditions(scope, customerScope),
        ),
      )
      .limit(1);
    if (customer === undefined) {
      throw new JobCustomerNotFoundError();
    }
  }

  async linkAdHocWorkReportToJob(
    scope: OrganizationScope,
    reportId: string,
    actorMembershipId: string,
    input: LinkAdHocWorkReportDto,
  ): Promise<JobDetails> {
    let jobId = input.jobId;
    await this.db.transaction(async (tx) => {
      const report = await this.requirePendingAdHocWorkReport(
        tx,
        scope,
        reportId,
        input,
      );
      await this.assertTechniciansAssignable(scope, [
        report.reportingTechnicianMembershipId,
      ]);
      const job = await this.requireJobRow(tx, scope, input.jobId);
      const visitId = await this.insertCompletedVisitFromAdHocReport(
        tx,
        scope,
        job,
        actorMembershipId,
        report,
      );
      await tx
        .update(adHocWorkReports)
        .set({
          status: 'LINKED',
          reviewerMembershipId: actorMembershipId,
          reviewedAt: new Date(),
          reviewNote: input.note,
          createdVisitId: visitId,
          version: sql`${adHocWorkReports.version} + 1`,
          updatedAt: new Date(),
        })
        .where(eq(adHocWorkReports.id, reportId));
    });
    return this.requireJobDetails(scope, jobId);
  }

  async convertAdHocWorkReportToNewJob(
    scope: OrganizationScope,
    reportId: string,
    actorMembershipId: string,
    input: ConvertAdHocWorkReportDto,
  ): Promise<JobDetails> {
    let createdJobId = '';
    await this.db.transaction(async (tx) => {
      const report = await this.requirePendingAdHocWorkReport(
        tx,
        scope,
        reportId,
        input,
      );
      await this.assertTechniciansAssignable(scope, [
        report.reportingTechnicianMembershipId,
      ]);
      if (report.customerId === null || report.propertyId === null) {
        throw new AdHocWorkReportLocationRequiredError();
      }
      const [customer] = await tx
        .select({ id: customers.id, status: customers.status })
        .from(customers)
        .where(
          and(
            eq(customers.organizationId, scope.organizationId),
            eq(customers.id, report.customerId),
            isNull(customers.deletedAt),
          ),
        )
        .limit(1);
      if (customer === undefined) {
        throw new JobCustomerNotFoundError();
      }
      if (customer.status !== 'ACTIVE') {
        throw new JobCustomerInactiveError();
      }
      const property =
        await this.properties.assertPropertyAvailableForCustomerNewWork(
          tx,
          scope,
          report.customerId,
          report.propertyId,
        );
      const [counter] = await tx
        .insert(organizationJobNumberCounters)
        .values({ organizationId: scope.organizationId, lastJobNumber: 1 })
        .onConflictDoUpdate({
          target: organizationJobNumberCounters.organizationId,
          set: {
            lastJobNumber: sql`${organizationJobNumberCounters.lastJobNumber} + 1`,
            updatedAt: sql`now()`,
          },
        })
        .returning({ jobNumber: organizationJobNumberCounters.lastJobNumber });
      if (counter === undefined) {
        throw new Error('The job number counter returned no row.');
      }
      const [job] = await tx
        .insert(jobs)
        .values({
          organizationId: scope.organizationId,
          jobNumber: counter.jobNumber,
          customerId: report.customerId,
          propertyId: property.id,
          propertyAddressSnapshot: addressSnapshotOf(property),
          title: input.title,
          description: input.description,
          status: 'ACTIVE',
          version: 1,
        })
        .returning();
      if (job === undefined) {
        throw new Error('The job insert returned no row.');
      }
      await tx.insert(jobStatusHistory).values({
        organizationId: scope.organizationId,
        jobId: job.id,
        fromStatus: null,
        toStatus: 'ACTIVE',
        actorMembershipId,
      });
      const visitId = await this.insertCompletedVisitFromAdHocReport(
        tx,
        scope,
        job,
        actorMembershipId,
        report,
      );
      await tx
        .update(adHocWorkReports)
        .set({
          status: 'CONVERTED',
          reviewerMembershipId: actorMembershipId,
          reviewedAt: new Date(),
          reviewNote: input.note,
          createdJobId: job.id,
          createdVisitId: visitId,
          version: sql`${adHocWorkReports.version} + 1`,
          updatedAt: new Date(),
        })
        .where(eq(adHocWorkReports.id, reportId));
      createdJobId = job.id;
    });
    return this.requireJobDetails(scope, createdJobId);
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
              eq(visitTechnicians.technicianMembershipId, viewer.membershipId),
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
   * that is its own rule's answer rather than a gap in the table: completion is blocked while open
   * Visits remain, and cancellation cascades to those open Visits inside the same transaction.
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
    if (!isPermittedJobStatusTransition(from, input.status)) {
      throw new JobStatusTransitionNotAllowedError(
        from,
        input.status,
        applicableJobStatusTransitions(from),
      );
    }

    await this.db.transaction(async (tx) => {
      if (input.status === 'COMPLETED') {
        await this.assertJobHasNoOpenVisit(tx, scope, jobId);
      }
      const recordedAt = new Date();

      await tx
        .update(jobs)
        .set({
          status: input.status,
          version: sql`${jobs.version} + 1`,
          updatedAt: recordedAt,
        })
        .where(
          and(
            eq(jobs.organizationId, scope.organizationId),
            eq(jobs.id, jobId),
          ),
        );
      const [jobStatusEvent] = await tx
        .insert(jobStatusHistory)
        .values({
          organizationId: scope.organizationId,
          jobId,
          fromStatus: from,
          toStatus: input.status,
          note: input.note,
          actorMembershipId,
        })
        .returning({ id: jobStatusHistory.id });

      if (input.status === 'CANCELED') {
        await this.cancelOpenVisitsForJobCancellation(
          tx,
          scope,
          jobId,
          actorMembershipId,
          jobStatusEvent.id,
          recordedAt,
        );
      }
    });

    return this.requireJobDetails(scope, jobId);
  }

  /**
   * Moves one Visit's field status (`BR-074`, `BR-075`, `BR-077`, `BR-093`; `ADR-019` D3, D4, D5, D7).
   *
   * `BR-074` permits free movement between the working statuses, so this applies **one** transition to
   * any destination the shared structural table lists for the Visit's current status, in either
   * direction — a skipped step, a step backward and a reopen out of `COMPLETED` included. The table is
   * consulted first, so a status a client invents, the status the Visit already holds and a destination
   * `BR-074` does not list are all refused, with the destinations the Visit really has. `CANCELED` and
   * `CANCELED` is among the refused field destinations: cancellation is a dispatch/office consequence,
   * not a technician working-state selection.
   *
   * `writer` is the scope the route resolved from the capability that admitted the caller (`BR-093`): a
   * field caller is bounded by their own current crew, while the office capability addresses the
   * organization's Visits without crew membership — which is how a manager completes a Visit from Job
   * Details (`ADR-019` D3, D7). The authorization to take the **completion** destination is not decided
   * here: the route requires `VISIT_RECORD_OUTCOME` of every caller, the office included (`BR-009`,
   * `BR-077`, `BR-093`).
   *
   * `actorMembershipId` is the authenticated member who performed the action, whichever authorization
   * admitted them, and it is what the append-only history records — an office completion names the office
   * member who performed it rather than a technician who did not (`BR-033`, `BR-067`, `BR-093`).
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
    writer: AssignedVisitWriter | null,
    input: ChangeVisitStatusDto,
  ): Promise<JobDetails> {
    await this.requireJobDetails(scope, jobId);
    // The scope the route resolved from the capability that admitted the caller (`BR-093`).
    const visit = await this.requireVisitForWriter(
      scope,
      jobId,
      visitId,
      writer,
    );
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
      throw new VisitStatusTransitionNotAllowedError(status, input.status, [
        ...VISIT_STATUS_TRANSITIONS[status],
      ]);
    }

    const conflicts =
      input.status === 'SCHEDULED'
        ? await this.assertVisitMayBeScheduled(
            scope,
            visit,
            input.confirmConflicts,
          )
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
          throw new VisitStatusTransitionNotAllowedError(live, input.status, [
            ...VISIT_STATUS_TRANSITIONS[live],
          ]);
        }

        const jobStatus = await this.requireLockedJobStatus(tx, scope, jobId);
        if (jobStatus === 'COMPLETED' || jobStatus === 'CANCELED') {
          throw new JobClosedForFieldWorkError();
        }

        const recordedAt = new Date();
        // `BR-074`'s reopen: moving a `COMPLETED` Visit back into a working status keeps the previous
        // completion and its outcome in append-only history (`visit_status_history`,
        // `visit_outcome_history`) while clearing the Visit's **current** outcome, because that outcome
        // is no longer what resulted from this field attempt. The next completion records a new one
        // (`BR-077`, `BR-079`). The four columns are cleared together, as their pair checks require.
        const reopening =
          live === 'COMPLETED' && !isTerminalVisitStatus(input.status);
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
              : reopening
                ? {
                    outcomeCode: null,
                    outcomeSummary: null,
                    outcomeRecordedAt: null,
                    outcomeRecordedByMembershipId: null,
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

  async completeVisit(
    scope: OrganizationScope,
    jobId: string,
    visitId: string,
    actorMembershipId: string,
    writer: AssignedVisitWriter | null,
    input: CompleteVisitDto,
  ): Promise<JobDetails> {
    await this.requireJobDetails(scope, jobId);
    const visit = await this.requireVisitForWriter(
      scope,
      jobId,
      visitId,
      writer,
    );

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
    if (
      !(ACTIVE_VISIT_STATUSES as readonly VisitStatus[]).includes(
        visit.status as VisitStatus,
      )
    ) {
      throw new VisitStatusTransitionNotAllowedError(
        visit.status as VisitStatus,
        'COMPLETED',
        [...VISIT_STATUS_TRANSITIONS[visit.status as VisitStatus]],
      );
    }

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
        if (!(ACTIVE_VISIT_STATUSES as readonly VisitStatus[]).includes(live)) {
          throw new VisitStatusTransitionNotAllowedError(live, 'COMPLETED', [
            ...VISIT_STATUS_TRANSITIONS[live],
          ]);
        }

        const jobStatus = await this.requireLockedJobStatus(tx, scope, jobId);
        if (jobStatus === 'COMPLETED' || jobStatus === 'CANCELED') {
          throw new JobClosedForFieldWorkError();
        }

        const recordedAt = new Date();
        await tx
          .update(visits)
          .set({
            status: 'COMPLETED',
            version: sql`${visits.version} + 1`,
            updatedAt: recordedAt,
            outcomeCode: input.outcomeCode,
            outcomeSummary: input.outcomeSummary,
            outcomeRecordedAt: recordedAt,
            outcomeRecordedByMembershipId: actorMembershipId,
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
          toStatus: 'COMPLETED',
          isCorrection: false,
          actorMembershipId,
          capturedAt: input.capturedAt,
          clientOperationId: input.clientOperationId,
        });

        await tx.insert(visitOutcomeHistory).values({
          organizationId: scope.organizationId,
          visitId,
          outcomeCode: input.outcomeCode,
          outcomeSummary: input.outcomeSummary,
          previousOutcomeCode: locked.outcomeCode,
          previousOutcomeSummary: locked.outcomeSummary,
          actorMembershipId,
          capturedAt: input.capturedAt,
          clientOperationId: input.clientOperationId,
        });

        await this.applyVisitConsequence(
          tx,
          scope,
          jobId,
          actorMembershipId,
          jobStatus,
          'COMPLETED',
          input.outcomeCode,
        );
      });
    } catch (error) {
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

  async submitFollowUpVisitRequest(
    scope: OrganizationScope,
    jobId: string,
    actorMembershipId: string,
    input: SubmitFollowUpVisitRequestDto,
  ): Promise<FollowUpVisitRequestDto> {
    await this.requireJobDetails(scope, jobId);
    if (input.sourceVisitId !== null) {
      await this.requireVisit(scope, jobId, input.sourceVisitId);
    }
    const [request] = await this.db
      .insert(followUpVisitRequests)
      .values({
        organizationId: scope.organizationId,
        jobId,
        sourceVisitId: input.sourceVisitId,
        requestingTechnicianMembershipId: actorMembershipId,
        proposedStart: input.proposedStart,
        proposedEnd: input.proposedEnd,
        reason: input.reason,
        sameTechnicianPreferred: input.sameTechnicianPreferred,
      })
      .returning();
    return this.toFollowUpRequestDto(this.db, scope, request);
  }

  async listFollowUpVisitRequests(
    scope: OrganizationScope,
    actorMembershipId: string,
    reviewer: boolean,
  ): Promise<readonly FollowUpVisitRequestDto[]> {
    const rows = await this.db
      .select()
      .from(followUpVisitRequests)
      .where(
        reviewer
          ? eq(followUpVisitRequests.organizationId, scope.organizationId)
          : and(
              eq(followUpVisitRequests.organizationId, scope.organizationId),
              eq(
                followUpVisitRequests.requestingTechnicianMembershipId,
                actorMembershipId,
              ),
            ),
      )
      .orderBy(desc(followUpVisitRequests.createdAt));
    const messages = await this.readFollowUpRequestMessages(
      this.db,
      scope,
      rows.map((row) => row.id),
    );
    const contexts = await this.readFollowUpRequestContexts(
      this.db,
      scope,
      rows.map((row) => row.id),
    );
    return rows.map((row) =>
      toFollowUpVisitRequestDto(
        row,
        this.requireFollowUpRequestContext(contexts, row.id),
        messages.get(row.id) ?? [],
      ),
    );
  }

  /**
   * The requester's answer to a request the office returned for clarification (`BR-FV-012`).
   *
   * One operation in one transaction: the answer is appended to the request's conversation **and** the
   * request returns to `PENDING`, the state the office reviews it in. That pair is one business move and
   * is never separable — an answer recorded without the request becoming reviewable would leave the
   * office unable to act on what it asked for, and a request returned to `PENDING` with no answer
   * recorded would claim the requester answered when they said nothing.
   *
   * It is the requester's own action and only theirs: a request raised by another member is reported as
   * not found rather than as forbidden, so an id is never a way to read or write someone else's request
   * (`BR-007`, `BR-009`).
   */
  async replyToFollowUpVisitRequest(
    scope: OrganizationScope,
    requestedJobId: string,
    requestId: string,
    actorMembershipId: string,
    input: ReplyFollowUpVisitRequestDto,
  ): Promise<FollowUpVisitRequestDto> {
    return this.db.transaction(async (tx) => {
      const [request] = await tx
        .select()
        .from(followUpVisitRequests)
        .where(
          and(
            eq(followUpVisitRequests.organizationId, scope.organizationId),
            eq(followUpVisitRequests.id, requestId),
          ),
        )
        .for('update')
        .limit(1);
      if (request === undefined) {
        throw new FollowUpVisitRequestNotFoundError();
      }
      if (request.jobId !== requestedJobId) {
        throw new FollowUpVisitRequestNotFoundError();
      }
      if (request.requestingTechnicianMembershipId !== actorMembershipId) {
        throw new FollowUpVisitRequestNotFoundError();
      }
      this.assertFollowUpRequestExpected(request, input);
      if (request.status !== 'NEEDS_CLARIFICATION') {
        throw new FollowUpVisitRequestNotAwaitingReplyError(request.status);
      }
      await this.appendFollowUpRequestMessage(
        tx,
        scope,
        requestId,
        actorMembershipId,
        input.body,
      );
      // The request returns to the office's review queue. The reviewer fields and the note stay as the
      // record of the return for clarification that was answered (`BR-FV-013`): they are the last
      // review decision, and the answer is not a review.
      const [updated] = await tx
        .update(followUpVisitRequests)
        .set({
          status: 'PENDING',
          version: sql`${followUpVisitRequests.version} + 1`,
          updatedAt: new Date(),
        })
        .where(eq(followUpVisitRequests.id, requestId))
        .returning();
      const messages = await this.readFollowUpRequestMessages(tx, scope, [
        requestId,
      ]);
      return this.toFollowUpRequestDto(
        tx,
        scope,
        updated,
        messages.get(requestId) ?? [],
      );
    });
  }

  async askFollowUpVisitRequestClarification(
    scope: OrganizationScope,
    jobId: string,
    requestId: string,
    actorMembershipId: string,
    input: FollowUpVisitReviewDto,
  ): Promise<FollowUpVisitRequestDto> {
    return this.reviewFollowUpVisitRequest(
      scope,
      jobId,
      requestId,
      actorMembershipId,
      input,
      'NEEDS_CLARIFICATION',
    );
  }

  async rejectFollowUpVisitRequest(
    scope: OrganizationScope,
    jobId: string,
    requestId: string,
    actorMembershipId: string,
    input: FollowUpVisitReviewDto,
  ): Promise<FollowUpVisitRequestDto> {
    return this.reviewFollowUpVisitRequest(
      scope,
      jobId,
      requestId,
      actorMembershipId,
      input,
      'REJECTED',
    );
  }

  async approveFollowUpVisitRequest(
    scope: OrganizationScope,
    requestedJobId: string,
    requestId: string,
    actorMembershipId: string,
    input: FollowUpVisitApprovalDto,
  ): Promise<JobDetails> {
    await this.assertTechniciansAssignable(
      scope,
      input.technicians.map((technician) => technician.membershipId),
    );
    let jobId = '';
    await this.db.transaction(async (tx) => {
      const [request] = await tx
        .select()
        .from(followUpVisitRequests)
        .where(
          and(
            eq(followUpVisitRequests.organizationId, scope.organizationId),
            eq(followUpVisitRequests.id, requestId),
          ),
        )
        .for('update')
        .limit(1);
      if (request === undefined) {
        throw new FollowUpVisitRequestNotFoundError();
      }
      if (request.jobId !== requestedJobId) {
        throw new FollowUpVisitRequestNotFoundError();
      }
      jobId = request.jobId;
      // Approval is idempotent once this request owns a Visit. A retry commonly carries the
      // pre-approval version it originally read, so resolve the completed operation before applying
      // optimistic-concurrency checks intended for a new review attempt.
      if (request.createdVisitId !== null) {
        return;
      }
      this.assertFollowUpRequestExpected(request, input);
      if (
        request.status !== 'PENDING' &&
        request.status !== 'NEEDS_CLARIFICATION'
      ) {
        throw new FollowUpVisitRequestNotReviewableError(request.status);
      }

      const job = await this.requireJobRow(tx, scope, request.jobId);
      const scheduledStart = input.scheduledStart ?? request.proposedStart;
      const scheduledEnd = input.scheduledEnd ?? request.proposedEnd;
      const conflicts = await this.findScheduleConflicts(
        scope,
        '00000000-0000-0000-0000-000000000000',
        input.technicians.map((technician) => technician.membershipId),
        scheduledStart,
        scheduledEnd,
      );
      if (conflicts.length > 0 && !input.confirmConflicts) {
        throw new ScheduleConflictError(conflicts);
      }
      const visitId = await this.insertScheduledVisit(
        tx,
        scope,
        job,
        actorMembershipId,
        scheduledStart,
        scheduledEnd,
        input.technicians,
        conflicts,
      );
      await tx
        .update(followUpVisitRequests)
        .set({
          status: 'APPROVED',
          reviewerMembershipId: actorMembershipId,
          reviewedAt: new Date(),
          reviewNote: input.note,
          createdVisitId: visitId,
          version: sql`${followUpVisitRequests.version} + 1`,
          updatedAt: new Date(),
        })
        .where(eq(followUpVisitRequests.id, requestId));
    });

    return this.requireJobDetails(scope, jobId);
  }

  async createScheduledVisit(
    scope: OrganizationScope,
    jobId: string,
    actorMembershipId: string,
    input: DirectCreateVisitDto,
  ): Promise<JobDetails> {
    await this.assertTechniciansAssignable(
      scope,
      input.technicians.map((technician) => technician.membershipId),
    );
    const conflicts = await this.findScheduleConflicts(
      scope,
      '00000000-0000-0000-0000-000000000000',
      input.technicians.map((technician) => technician.membershipId),
      input.scheduledStart,
      input.scheduledEnd,
    );
    if (conflicts.length > 0 && !input.confirmConflicts) {
      throw new ScheduleConflictError(conflicts);
    }

    await this.db.transaction(async (tx) => {
      const job = await this.requireJobRow(tx, scope, jobId);
      await this.insertScheduledVisit(
        tx,
        scope,
        job,
        actorMembershipId,
        input.scheduledStart,
        input.scheduledEnd,
        input.technicians,
        conflicts,
      );
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
    await this.assertJobOpenForFieldWork(this.db, scope, jobId);

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

  async editVisitNote(
    scope: OrganizationScope,
    jobId: string,
    noteId: string,
    actorMembershipId: string,
    input: EditVisitNoteDto,
    writer: AssignedVisitWriter | null,
  ): Promise<JobActivityEventDto[]> {
    await this.requireJobDetails(scope, jobId);
    const note = await this.findVisitNote(scope, jobId, noteId);
    if (note === null) {
      throw new VisitNotFoundError();
    }
    if (writer !== null && note.authorMembershipId !== writer.membershipId) {
      throw new VisitNoteForbiddenError();
    }
    if (note.removedAt !== null) {
      throw new VisitNotFoundError();
    }

    await this.db
      .update(visitNotes)
      .set({
        body: input.body,
        editedAt: new Date(),
        editedByMembershipId: actorMembershipId,
        updatedAt: new Date(),
      })
      .where(
        and(
          eq(visitNotes.organizationId, scope.organizationId),
          eq(visitNotes.id, noteId),
        ),
      );

    return readJobActivity(this.db, scope, jobId);
  }

  async removeVisitNote(
    scope: OrganizationScope,
    jobId: string,
    noteId: string,
    actorMembershipId: string,
    input: RemoveVisitNoteDto,
    writer: AssignedVisitWriter | null,
  ): Promise<JobActivityEventDto[]> {
    await this.requireJobDetails(scope, jobId);
    const note = await this.findVisitNote(scope, jobId, noteId);
    if (note === null) {
      throw new VisitNotFoundError();
    }
    if (writer !== null && note.authorMembershipId !== writer.membershipId) {
      throw new VisitNoteForbiddenError();
    }
    if (note.removedAt !== null) {
      throw new VisitNotFoundError();
    }

    await this.db
      .update(visitNotes)
      .set({
        removedAt: new Date(),
        removedByMembershipId: actorMembershipId,
        removalReason: input.reason,
        updatedAt: new Date(),
      })
      .where(
        and(
          eq(visitNotes.organizationId, scope.organizationId),
          eq(visitNotes.id, noteId),
        ),
      );

    return readJobActivity(this.db, scope, jobId);
  }

  private async findVisitNote(
    scope: OrganizationScope,
    jobId: string,
    noteId: string,
  ): Promise<{ authorMembershipId: string; removedAt: Date | null } | null> {
    const [row] = await this.db
      .select({
        authorMembershipId: visitNotes.authorMembershipId,
        removedAt: visitNotes.removedAt,
      })
      .from(visitNotes)
      .innerJoin(visits, eq(visits.id, visitNotes.visitId))
      .where(
        and(
          eq(visitNotes.organizationId, scope.organizationId),
          eq(visitNotes.id, noteId),
          eq(visits.organizationId, scope.organizationId),
          eq(visits.jobId, jobId),
        ),
      )
      .limit(1);
    return row ?? null;
  }

  private async assertJobOpenForFieldWork(
    client: JobMutationClient,
    scope: OrganizationScope,
    jobId: string,
  ): Promise<void> {
    const [job] = await client
      .select({ status: jobs.status })
      .from(jobs)
      .where(
        and(eq(jobs.organizationId, scope.organizationId), eq(jobs.id, jobId)),
      )
      .limit(1);
    if (job === undefined) {
      throw new JobNotFoundError();
    }
    if (job.status === 'COMPLETED' || job.status === 'CANCELED') {
      throw new JobClosedForFieldWorkError();
    }
  }

  /**
   * Applies the office's decision to a request, and the clarification it asks for.
   *
   * A return for clarification is a question the requester answers, so when the office writes one it
   * enters the request's conversation in the same transaction as the status change (`BR-FV-012`). A
   * rejection appends nothing: it is a decision that closes the request, and its reason is the request's
   * own note (`BR-FV-013`).
   *
   * The read, the status change and the appended message are one transaction over the locked request, so
   * two reviewers deciding at once cannot both satisfy the optimistic-concurrency check (`BR-032`).
   */
  private async reviewFollowUpVisitRequest(
    scope: OrganizationScope,
    jobId: string,
    requestId: string,
    actorMembershipId: string,
    input: FollowUpVisitReviewDto,
    status: 'NEEDS_CLARIFICATION' | 'REJECTED',
  ): Promise<FollowUpVisitRequestDto> {
    return this.db.transaction(async (tx) => {
      const [request] = await tx
        .select()
        .from(followUpVisitRequests)
        .where(
          and(
            eq(followUpVisitRequests.organizationId, scope.organizationId),
            eq(followUpVisitRequests.id, requestId),
          ),
        )
        .for('update')
        .limit(1);
      if (request === undefined) {
        throw new FollowUpVisitRequestNotFoundError();
      }
      if (request.jobId !== jobId) {
        throw new FollowUpVisitRequestNotFoundError();
      }
      this.assertFollowUpRequestExpected(request, input);
      if (
        request.status !== 'PENDING' &&
        request.status !== 'NEEDS_CLARIFICATION'
      ) {
        throw new FollowUpVisitRequestNotReviewableError(request.status);
      }
      if (status === 'NEEDS_CLARIFICATION' && input.note !== null) {
        await this.appendFollowUpRequestMessage(
          tx,
          scope,
          requestId,
          actorMembershipId,
          input.note,
        );
      }
      const [updated] = await tx
        .update(followUpVisitRequests)
        .set({
          status,
          reviewerMembershipId: actorMembershipId,
          reviewedAt: new Date(),
          reviewNote: input.note,
          version: sql`${followUpVisitRequests.version} + 1`,
          updatedAt: new Date(),
        })
        .where(eq(followUpVisitRequests.id, requestId))
        .returning();
      const messages = await this.readFollowUpRequestMessages(tx, scope, [
        requestId,
      ]);
      return this.toFollowUpRequestDto(
        tx,
        scope,
        updated,
        messages.get(requestId) ?? [],
      );
    });
  }

  /** Adds the Job, Customer and source-Visit identity every request client needs to recognize it. */
  private async toFollowUpRequestDto(
    client: JobMutationClient,
    scope: OrganizationScope,
    request: typeof followUpVisitRequests.$inferSelect,
    messages: readonly FollowUpVisitRequestMessageRow[] = [],
  ): Promise<FollowUpVisitRequestDto> {
    const contexts = await this.readFollowUpRequestContexts(client, scope, [
      request.id,
    ]);
    return toFollowUpVisitRequestDto(
      request,
      this.requireFollowUpRequestContext(contexts, request.id),
      messages,
    );
  }

  /** Reads request identity in one batch so a list does not issue one Job query per row. */
  private async readFollowUpRequestContexts(
    client: JobMutationClient,
    scope: OrganizationScope,
    requestIds: readonly string[],
  ): Promise<ReadonlyMap<string, FollowUpVisitRequestContext>> {
    if (requestIds.length === 0) {
      return new Map();
    }
    const rows = await client
      .select({
        requestId: followUpVisitRequests.id,
        jobNumber: jobs.jobNumber,
        jobTitle: jobs.title,
        customerName: customers.displayName,
        addressSnapshot: jobs.propertyAddressSnapshot,
        sourceVisitScheduledStart: visits.scheduledStart,
        sourceVisitStatus: visits.status,
        sourceVisitOutcomeCode: visits.outcomeCode,
      })
      .from(followUpVisitRequests)
      .innerJoin(
        jobs,
        and(
          eq(jobs.id, followUpVisitRequests.jobId),
          eq(jobs.organizationId, scope.organizationId),
        ),
      )
      .innerJoin(
        customers,
        and(
          eq(customers.id, jobs.customerId),
          eq(customers.organizationId, scope.organizationId),
        ),
      )
      .leftJoin(
        visits,
        and(
          eq(visits.id, followUpVisitRequests.sourceVisitId),
          eq(visits.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          eq(followUpVisitRequests.organizationId, scope.organizationId),
          inArray(followUpVisitRequests.id, [...requestIds]),
        ),
      );
    return new Map(
      rows.map((row) => [
        row.requestId,
        {
          jobNumber: row.jobNumber,
          jobTitle: row.jobTitle,
          customerName: row.customerName,
          address: readAddressSnapshot(row.addressSnapshot),
          sourceVisitScheduledStart: row.sourceVisitScheduledStart,
          sourceVisitStatus: row.sourceVisitStatus,
          sourceVisitOutcomeCode: row.sourceVisitOutcomeCode,
        },
      ]),
    );
  }

  private requireFollowUpRequestContext(
    contexts: ReadonlyMap<string, FollowUpVisitRequestContext>,
    requestId: string,
  ): FollowUpVisitRequestContext {
    const context = contexts.get(requestId);
    if (context === undefined) {
      throw new FollowUpVisitRequestNotFoundError();
    }
    return context;
  }

  /**
   * Appends one message to a request's clarification conversation (`BR-FV-012`).
   *
   * The conversation is append-only: it has no update path and no delete path, so what a side said stays
   * readable after the other side answers it (`BR-067`, `BR-FV-013`).
   */
  private async appendFollowUpRequestMessage(
    client: JobMutationClient,
    scope: OrganizationScope,
    requestId: string,
    authorMembershipId: string,
    body: string,
  ): Promise<void> {
    await client.insert(followUpVisitRequestMessages).values({
      organizationId: scope.organizationId,
      requestId,
      authorMembershipId,
      body,
    });
  }

  /**
   * The clarification conversation of [requestIds], oldest first, grouped by request.
   *
   * One query for the whole page rather than one per request: the conversation belongs to every
   * projection of a request, so a list read must not become a query per row (`dev.md` §6).
   */
  private async readFollowUpRequestMessages(
    client: JobMutationClient,
    scope: OrganizationScope,
    requestIds: readonly string[],
  ): Promise<Map<string, FollowUpVisitRequestMessageRow[]>> {
    const grouped = new Map<string, FollowUpVisitRequestMessageRow[]>();
    if (requestIds.length === 0) {
      return grouped;
    }
    const rows = await client
      .select()
      .from(followUpVisitRequestMessages)
      .where(
        and(
          eq(followUpVisitRequestMessages.organizationId, scope.organizationId),
          inArray(followUpVisitRequestMessages.requestId, [...requestIds]),
        ),
      )
      .orderBy(
        asc(followUpVisitRequestMessages.recordedAt),
        asc(followUpVisitRequestMessages.createdAt),
      );
    for (const row of rows) {
      const existing = grouped.get(row.requestId);
      if (existing === undefined) {
        grouped.set(row.requestId, [row]);
      } else {
        existing.push(row);
      }
    }
    return grouped;
  }

  private assertFollowUpRequestExpected(
    request: typeof followUpVisitRequests.$inferSelect,
    input: {
      readonly expectedStatus: FollowUpVisitRequestStatus | null;
      readonly expectedVersion: number | null;
    },
  ): void {
    if (
      input.expectedStatus !== null &&
      input.expectedStatus !== request.status
    ) {
      throw new FollowUpVisitRequestConflictError(
        request.status,
        request.version,
      );
    }
    if (
      input.expectedVersion !== null &&
      input.expectedVersion !== request.version
    ) {
      throw new FollowUpVisitRequestConflictError(
        request.status,
        request.version,
      );
    }
  }

  private async assertAdHocWorkReportContext(
    client: Executor,
    scope: OrganizationScope,
    input: SubmitAdHocWorkReportDto,
  ): Promise<void> {
    if (input.customerId !== null) {
      const [customer] = await client
        .select({ id: customers.id, status: customers.status })
        .from(customers)
        .where(
          and(
            eq(customers.organizationId, scope.organizationId),
            eq(customers.id, input.customerId),
            isNull(customers.deletedAt),
          ),
        )
        .limit(1);
      if (customer === undefined) {
        throw new JobCustomerNotFoundError();
      }
      if (customer.status !== 'ACTIVE') {
        throw new JobCustomerInactiveError();
      }
    }

    if (input.propertyId !== null) {
      if (input.customerId === null) {
        throw new PropertyNotFoundError(input.propertyId);
      }
      await this.properties.assertPropertyAvailableForCustomerNewWork(
        client,
        scope,
        input.customerId,
        input.propertyId,
      );
    }

    if (input.knownJobId !== null) {
      const [job] = await client
        .select({ id: jobs.id, customerId: jobs.customerId })
        .from(jobs)
        .where(
          and(
            eq(jobs.organizationId, scope.organizationId),
            eq(jobs.id, input.knownJobId),
          ),
        )
        .limit(1);
      if (job === undefined) {
        throw new JobNotFoundError();
      }
      if (input.customerId !== null && job.customerId !== input.customerId) {
        throw new JobNotFoundError();
      }
    }
  }

  private async findAppliedAdHocReportOperation(
    scope: OrganizationScope,
    clientOperationId: string,
  ): Promise<typeof adHocWorkReports.$inferSelect | null> {
    const [report] = await this.db
      .select()
      .from(adHocWorkReports)
      .where(
        and(
          eq(adHocWorkReports.organizationId, scope.organizationId),
          eq(adHocWorkReports.clientOperationId, clientOperationId),
        ),
      )
      .limit(1);
    return report ?? null;
  }

  private async requirePendingAdHocWorkReport(
    client: JobMutationClient,
    scope: OrganizationScope,
    reportId: string,
    input: AdHocWorkReportReviewDto,
  ): Promise<typeof adHocWorkReports.$inferSelect> {
    const [report] = await client
      .select()
      .from(adHocWorkReports)
      .where(
        and(
          eq(adHocWorkReports.organizationId, scope.organizationId),
          eq(adHocWorkReports.id, reportId),
        ),
      )
      .for('update')
      .limit(1);
    if (report === undefined) {
      throw new AdHocWorkReportNotFoundError();
    }
    if (
      input.expectedStatus !== null &&
      input.expectedStatus !== report.status
    ) {
      throw new AdHocWorkReportConflictError(report.status, report.version);
    }
    if (
      input.expectedVersion !== null &&
      input.expectedVersion !== report.version
    ) {
      throw new AdHocWorkReportConflictError(report.status, report.version);
    }
    if (report.status !== 'PENDING') {
      throw new AdHocWorkReportNotReviewableError(report.status);
    }
    return report;
  }

  private async insertCompletedVisitFromAdHocReport(
    client: JobMutationClient,
    scope: OrganizationScope,
    job: typeof jobs.$inferSelect,
    actorMembershipId: string,
    report: typeof adHocWorkReports.$inferSelect,
  ): Promise<string> {
    const [visit] = await client
      .insert(visits)
      .values({
        organizationId: scope.organizationId,
        jobId: job.id,
        propertyId: job.propertyId,
        locationAddressSnapshot: job.propertyAddressSnapshot,
        status: 'COMPLETED',
        scheduledStart: report.workStartedAt,
        scheduledEnd: report.workEndedAt,
        outcomeCode: report.outcomeCode as VisitOutcomeCode,
        outcomeSummary: report.summary,
        outcomeRecordedAt: new Date(),
        outcomeRecordedByMembershipId: report.reportingTechnicianMembershipId,
      })
      .returning({ id: visits.id });
    if (visit === undefined) {
      throw new Error('The visit insert returned no row.');
    }
    await client.insert(visitScheduleHistory).values({
      organizationId: scope.organizationId,
      visitId: visit.id,
      previousScheduledStart: null,
      previousScheduledEnd: null,
      newScheduledStart: report.workStartedAt,
      newScheduledEnd: report.workEndedAt,
      actorMembershipId,
    });
    await client.insert(visitStatusHistory).values({
      organizationId: scope.organizationId,
      visitId: visit.id,
      fromStatus: null,
      toStatus: 'COMPLETED',
      actorMembershipId: report.reportingTechnicianMembershipId,
    });
    await client.insert(visitOutcomeHistory).values({
      organizationId: scope.organizationId,
      visitId: visit.id,
      outcomeCode: report.outcomeCode as VisitOutcomeCode,
      outcomeSummary: report.summary,
      actorMembershipId: report.reportingTechnicianMembershipId,
    });
    await client.insert(visitTechnicians).values({
      organizationId: scope.organizationId,
      visitId: visit.id,
      technicianMembershipId: report.reportingTechnicianMembershipId,
      roleCode: 'LEAD',
    });
    await client.insert(visitTechnicianHistory).values({
      organizationId: scope.organizationId,
      visitId: visit.id,
      technicianMembershipId: report.reportingTechnicianMembershipId,
      event: 'ASSIGNED',
      roleCode: 'LEAD',
      actorMembershipId,
    });
    if (report.notes !== null) {
      await client.insert(visitNotes).values({
        organizationId: scope.organizationId,
        visitId: visit.id,
        authorMembershipId: report.reportingTechnicianMembershipId,
        body: report.notes,
        capturedAt: report.workEndedAt,
      });
    }
    if (job.status === 'NEW') {
      await client
        .update(jobs)
        .set({
          status: 'ACTIVE',
          version: sql`${jobs.version} + 1`,
          updatedAt: new Date(),
        })
        .where(
          and(
            eq(jobs.organizationId, scope.organizationId),
            eq(jobs.id, job.id),
          ),
        );
      await client.insert(jobStatusHistory).values({
        organizationId: scope.organizationId,
        jobId: job.id,
        fromStatus: 'NEW',
        toStatus: 'ACTIVE',
        actorMembershipId,
      });
    }
    return visit.id;
  }

  private async toAdHocWorkReportDto(
    client: JobMutationClient,
    scope: OrganizationScope,
    report: typeof adHocWorkReports.$inferSelect,
  ): Promise<AdHocWorkReportDto> {
    const contexts = await this.readAdHocWorkReportContexts(client, scope, [
      report.id,
    ]);
    return toAdHocWorkReportDto(
      report,
      this.requireAdHocWorkReportContext(contexts, report.id),
    );
  }

  private async readAdHocWorkReportContexts(
    client: JobMutationClient,
    scope: OrganizationScope,
    reportIds: readonly string[],
  ): Promise<ReadonlyMap<string, AdHocWorkReportContext>> {
    if (reportIds.length === 0) {
      return new Map();
    }
    const createdJobs = alias(jobs, 'created_jobs');
    const rows = await client
      .select({
        reportId: adHocWorkReports.id,
        customerName: customers.displayName,
        propertyAddress: properties,
        knownJobNumber: jobs.jobNumber,
        knownJobTitle: jobs.title,
        createdJobNumber: createdJobs.jobNumber,
        createdJobTitle: createdJobs.title,
      })
      .from(adHocWorkReports)
      .leftJoin(
        customers,
        and(
          eq(customers.id, adHocWorkReports.customerId),
          eq(customers.organizationId, scope.organizationId),
        ),
      )
      .leftJoin(
        properties,
        and(
          eq(properties.id, adHocWorkReports.propertyId),
          eq(properties.organizationId, scope.organizationId),
        ),
      )
      .leftJoin(
        jobs,
        and(
          eq(jobs.id, adHocWorkReports.knownJobId),
          eq(jobs.organizationId, scope.organizationId),
        ),
      )
      .leftJoin(
        createdJobs,
        and(
          eq(createdJobs.id, adHocWorkReports.createdJobId),
          eq(createdJobs.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          eq(adHocWorkReports.organizationId, scope.organizationId),
          inArray(adHocWorkReports.id, [...reportIds]),
        ),
      );
    return new Map(
      rows.map((row) => [
        row.reportId,
        {
          customerName: row.customerName,
          propertyAddress:
            row.propertyAddress === null
              ? null
              : addressSnapshotOf(row.propertyAddress),
          knownJobNumber: row.knownJobNumber,
          knownJobTitle: row.knownJobTitle,
          createdJobNumber: row.createdJobNumber,
          createdJobTitle: row.createdJobTitle,
        },
      ]),
    );
  }

  private requireAdHocWorkReportContext(
    contexts: ReadonlyMap<string, AdHocWorkReportContext>,
    reportId: string,
  ): AdHocWorkReportContext {
    const context = contexts.get(reportId);
    if (context === undefined) {
      throw new AdHocWorkReportNotFoundError();
    }
    return context;
  }

  /**
   * The Job a scheduled Visit is created on, locked for the write (`BR-001`, `BR-031`).
   *
   * A Visit can be created only on an **open** Job with a Property: `BR-062` keeps a closed Job from
   * receiving new field work (reopening is the only way back, `BR-063`), and `BR-072` requires a
   * Property for a Visit to become `SCHEDULED` (`BR-056`). This one guard serves both the direct
   * schedule route and the follow-up approval route, which create the same kind of Visit.
   */
  private async requireJobRow(
    client: JobMutationClient,
    scope: OrganizationScope,
    jobId: string,
  ): Promise<typeof jobs.$inferSelect> {
    const [job] = await client
      .select()
      .from(jobs)
      .where(
        and(eq(jobs.organizationId, scope.organizationId), eq(jobs.id, jobId)),
      )
      .for('update')
      .limit(1);
    if (job === undefined) {
      throw new JobNotFoundError();
    }
    if (job.status === 'COMPLETED' || job.status === 'CANCELED') {
      throw new JobClosedForFieldWorkError();
    }
    if (job.propertyId === null || job.propertyAddressSnapshot === null) {
      throw new VisitSchedulingConditionNotMetError('PROPERTY');
    }
    return job;
  }

  private async insertScheduledVisit(
    client: JobMutationClient,
    scope: OrganizationScope,
    job: typeof jobs.$inferSelect,
    actorMembershipId: string,
    scheduledStart: Date,
    scheduledEnd: Date,
    technicians: readonly RequestedVisitTechnician[],
    confirmedConflicts: readonly ScheduleConflict[],
  ): Promise<string> {
    const [visit] = await client
      .insert(visits)
      .values({
        organizationId: scope.organizationId,
        jobId: job.id,
        propertyId: job.propertyId,
        locationAddressSnapshot: job.propertyAddressSnapshot,
        status: 'SCHEDULED',
        scheduledStart,
        scheduledEnd,
      })
      .returning({ id: visits.id });
    await client.insert(visitScheduleHistory).values({
      organizationId: scope.organizationId,
      visitId: visit.id,
      previousScheduledStart: null,
      previousScheduledEnd: null,
      newScheduledStart: scheduledStart,
      newScheduledEnd: scheduledEnd,
      actorMembershipId,
      confirmedConflicts:
        confirmedConflicts.length === 0 ? null : confirmedConflicts,
    });
    for (const technician of technicians) {
      await client.insert(visitTechnicians).values({
        organizationId: scope.organizationId,
        visitId: visit.id,
        technicianMembershipId: technician.membershipId,
        roleCode: technician.roleCode,
      });
      await client.insert(visitTechnicianHistory).values({
        organizationId: scope.organizationId,
        visitId: visit.id,
        technicianMembershipId: technician.membershipId,
        event: 'ASSIGNED',
        roleCode: technician.roleCode,
        actorMembershipId,
      });
    }
    if (job.status === 'NEW') {
      await client
        .update(jobs)
        .set({
          status: 'ACTIVE',
          version: sql`${jobs.version} + 1`,
          updatedAt: new Date(),
        })
        .where(
          and(
            eq(jobs.organizationId, scope.organizationId),
            eq(jobs.id, job.id),
          ),
        );
      await client.insert(jobStatusHistory).values({
        organizationId: scope.organizationId,
        jobId: job.id,
        fromStatus: 'NEW',
        toStatus: 'ACTIVE',
        actorMembershipId,
      });
    }
    return visit.id;
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
          eq(visitTechnicians.technicianMembershipId, writer.membershipId),
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
        and(eq(jobs.organizationId, scope.organizationId), eq(jobs.id, jobId)),
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
   * transaction as the Visit transition (`BR-058`, `BR-059`, `BR-062`, `BR-067`).
   *
   * The mapping itself lives in `visit-job-consequence.ts`, so the same rule is not implemented twice
   * (`BR-041`), and cancellation remains a Job/dispatch action. A resolving Visit completion completes
   * the Job **when the attempt that just ended was the Job's last remaining work**: `BR-062` forbids a
   * `COMPLETED` Job that still holds an open Visit, so the Job is asked about its other Visits and the
   * answer is part of the rule rather than a second copy of it. Non-resolving outcomes keep it active.
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
    // The Visit this event belongs to has already reached [to] when this runs, so the query counts only
    // the Job's *other* Visits. It is asked only for the one event that could close the Job, because no
    // other case consults the answer.
    const hasOtherOpenVisit =
      to === 'COMPLETED' && outcomeCode === 'RESOLVED'
        ? await this.jobHasOpenVisit(client, scope, jobId)
        : false;

    const destination = jobStatusConsequenceForVisitTransition(
      to,
      outcomeCode,
      currentJobStatus,
      { hasOtherOpenVisit },
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
        and(eq(jobs.organizationId, scope.organizationId), eq(jobs.id, jobId)),
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
   * Whether [jobId] still has an open Visit: one whose status is not `COMPLETED` or `CANCELED`
   * (`BR-062`, `BR-074`).
   *
   * The guard is the same one the explicit Job close applies (`BR-062`'s open-Visit invariant); it is
   * asked here so the Visit consequence cannot reach a state the Job's own status route would refuse.
   */
  private async jobHasOpenVisit(
    client: JobMutationClient,
    scope: OrganizationScope,
    jobId: string,
  ): Promise<boolean> {
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
    return open !== undefined;
  }

  private async cancelOpenVisitsForJobCancellation(
    client: JobMutationClient,
    scope: OrganizationScope,
    jobId: string,
    actorMembershipId: string,
    jobStatusHistoryId: string,
    recordedAt: Date,
  ): Promise<void> {
    const openVisits = await client
      .select({ id: visits.id, status: visits.status })
      .from(visits)
      .where(
        and(
          eq(visits.organizationId, scope.organizationId),
          eq(visits.jobId, jobId),
          notInArray(visits.status, [...TERMINAL_VISIT_STATUSES]),
        ),
      );

    for (const visit of openVisits) {
      await client
        .update(visits)
        .set({
          status: 'CANCELED',
          version: sql`${visits.version} + 1`,
          updatedAt: recordedAt,
        })
        .where(
          and(
            eq(visits.organizationId, scope.organizationId),
            eq(visits.id, visit.id),
          ),
        );
      await client.insert(visitStatusHistory).values({
        organizationId: scope.organizationId,
        visitId: visit.id,
        fromStatus: visit.status,
        toStatus: 'CANCELED',
        note: 'Canceled by job cancellation.',
        cancellationSource: 'JOB_CANCELLATION',
        jobStatusHistoryId,
        actorMembershipId,
      });
    }
  }

  /**
   * `BR-062`: a Job must not be completed while it has an open Visit.
   *
   * A Visit is open while its status is not historical — `COMPLETED` or `CANCELED`
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
