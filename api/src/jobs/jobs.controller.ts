import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpException,
  HttpStatus,
  Param,
  Patch,
  Post,
  Put,
  Query,
  Req,
  StreamableFile,
  UploadedFile,
  UseGuards,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { AuthApiError } from '../auth/auth-error.js';
import { AuthGuard } from '../auth/auth.guard.js';
import {
  CUSTOMER_PERMISSIONS,
  EVIDENCE_PERMISSIONS,
  JOB_PERMISSIONS,
  VISIT_PERMISSIONS,
} from '../auth/permissions.js';
import { RequireAnyPermission, RequirePermissions } from '../auth/permissions.decorator.js';
import { PermissionsGuard } from '../auth/permissions.guard.js';
import type { PermissionedRequest } from '../auth/permissions.guard.js';
import { ObjectStorageError } from '../storage/object-storage.js';
import {
  PropertyNotFoundError,
  PropertyNotAvailableForNewWorkError,
} from '../customers/properties.service.js';
import { DomainValidationError } from '../validation/domain-validation.js';
import { parseCreateJobDto } from './job-create.dto.js';
import {
  parseDirectCreateVisitDto,
  parseFollowUpVisitApprovalDto,
  parseFollowUpVisitReviewDto,
  parseSubmitFollowUpVisitRequestDto,
  type FollowUpVisitRequestDto,
} from './follow-up-visit-request.dto.js';
import { toJobDetailsDto } from './job-details.dto.js';
import type {
  JobDetailsDto,
  JobDetails,
  JobDetailsReadOptions,
} from './job-details.dto.js';
import { toJobActivityDto } from './job-activity.js';
import type { JobActivityDto } from './job-activity.js';
import { parseJobActivityOptions } from './job-activity-query.dto.js';
import {
  MAX_JOB_PHOTO_BYTES,
  parseCreateJobPhotoDto,
  parseRemoveJobPhotoDto,
  validateJobPhotoUpload,
} from './job-photo.dto.js';
import type { JobPhotoUpload } from './job-photo.dto.js';
import {
  MAX_JOB_AUDIO_BYTES,
  parseCreateJobAudioNoteDto,
  parseRemoveJobAudioNoteDto,
  validateJobAudioNoteUpload,
} from './job-audio.dto.js';
import type { JobAudioNoteUpload } from './job-audio.dto.js';
import {
  JobAudioNoteAlreadyRemovedError,
  JobAudioNoteNotFoundError,
  JobAudioNoteOperationReusedError,
  JobAudioNotesService,
} from './job-audio-notes.service.js';
import {
  JobPhotoAlreadyRemovedError,
  JobPhotoNotFoundError,
  JobPhotoOperationReusedError,
  JobPhotosService,
} from './job-photos.service.js';
import {
  parseAssignVisitTechniciansDto,
  parseAddVisitNoteDto,
  parseChangeJobStatusDto,
  parseChangeVisitStatusDto,
  parseRescheduleVisitDto,
} from './job-action.dto.js';
import type {
  AssignedJobViewer,
  AssignedVisitWriter,
  ScheduleConflict,
} from './jobs.service.js';
import {
  JobCancellationUnavailableError,
  JobClosedForFieldWorkError,
  JobCompletionBlockedError,
  FollowUpVisitRequestConflictError,
  FollowUpVisitRequestNotFoundError,
  FollowUpVisitRequestNotReviewableError,
  JobCustomerInactiveError,
  JobCustomerNotFoundError,
  JobNotFoundError,
  JobReviewConditionNotMetError,
  JobStatusTransitionNotAllowedError,
  JobsService,
  JobVersionConflictError,
  ScheduleConflictError,
  TechnicianNotAssignableError,
  VisitNotReschedulableError,
  VisitNotFoundError,
  VisitOperationReusedError,
  VisitSchedulingConditionNotMetError,
  VisitStatusTransitionNotAllowedError,
  VisitVersionConflictError,
} from './jobs.service.js';

/**
 * The Job read and the Job and Visit management actions (`BR-058` – `BR-079`).
 *
 * The **read** is guarded by `customers.view` **or** `VISIT_VIEW_ASSIGNED`: the first is the capability
 * the API already uses for the organization's Job and Visit data (`GET /customers/:id/jobs` is guarded
 * by it, so a caller who can open a Job Details screen can already read the same records), and the
 * second is the capability `BR-009` gives the technician who does the work. A field caller reads only
 * the Jobs their own assignments reach, and a Job they are not assigned to is reported as not found;
 * both halves of that decision are recorded in `docs/decisions/019-technician-field-experience.md`
 * (D1, D2). The catalogue still has **no** `jobs.*` capability, so the guard stays interim until the
 * Jobs feature defines its own (`BR-006`, `BR-042`; `docs/api/job-details.md` §2).
 *
 * The read's **second** question — whether the Customer's own contact details belong in the answer — is
 * answered from `customers.view` or `customers.view_assigned` instead, through `jobDetailsReadOptionsOf`
 * (`BR-092`; `ADR-021` D1, D2). It is not part of the guard, because a technician assigned to the Job
 * must still read the Job when they hold no Customer capability at all.
 *
 * The **actions** are guarded by the existing `JOB_UPDATE` capability, which `BR-008` already names
 * as a Manager default and which the seeded catalogue already grants to that role. Introducing
 * `jobs.*` codes would invent the capability set the Jobs feature has not defined, so the actions
 * reuse the one that exists; the interim decision and its open question are recorded in
 * `docs/api/job-actions.md` §2 and `docs/tracker/018-android-job-actions.md`.
 *
 * The **create** route is the exception among the Job writes: `BR-008` names "create jobs" as a
 * Manager default and the foundation catalogue already defines and grants `JOB_CREATE`, so creating a
 * Job uses the capability that exists for exactly that action rather than the interim `JOB_UPDATE`
 * (`BR-006`, `docs/api/job-details.md` §2).
 *
 * The **evidence routes** are the exception, and deliberately so: a photo is added by the technician
 * who took it (`BR-015`, `BR-027`), so they are guarded by the evidence capabilities the catalogue
 * defines for that purpose (`EVIDENCE_PERMISSIONS`) instead of by a Manager capability. The decision
 * and the per-kind catalogue are recorded in `docs/decisions/015-evidence-capabilities.md` and
 * `docs/api/job-photos.md` §2.
 *
 * The **field routes** (`PATCH /jobs/:id/visits/:visitId/status`, `POST /jobs/:id/visits/:visitId/notes`)
 * are the technician's own lifecycle (`BR-074`, `BR-077`; `ADR-019` D3), so they are guarded by the
 * capabilities `BR-009` gives that role rather than by the office's `JOB_UPDATE`. The note route accepts
 * either, because the office has always recorded notes through it and widening a guard must not take a
 * capability away (`BR-008`, `ADR-019` D2).
 *
 * Every action is an explicit, authorized business action whose outcome the service records in
 * append-only history (`BR-066`, `BR-067`). This controller orchestrates and maps failures onto the
 * HTTP contract; it decides no business rule of its own.
 */
@Controller('jobs')
@UseGuards(AuthGuard, PermissionsGuard)
export class JobsController {
  constructor(
    private readonly jobs: JobsService,
    private readonly photos: JobPhotosService,
    private readonly audioNotes: JobAudioNotesService,
  ) {}

  /**
   * Creates a Job (`BR-021`, `BR-047` – `BR-053`, `BR-056`, `BR-094`).
   *
   * The caller states the Customer, the Property and the title; the property-less form a Job may also
   * take (`BR-051`, `BR-056`) is not what this operation supports, because a Job must be created for a
   * Customer at a location that Customer currently holds (`BR-094`). The Customer, the Property, the
   * Job number, the `NEW` status, the address snapshot and the version are the backend's.
   *
   * It is guarded by the existing `JOB_CREATE` capability `BR-008` names as a Manager default and
   * migration `0004_woozy_spitfire` already grants to that role — the same capability that already
   * exists for this action, rather than the interim `JOB_UPDATE` the other actions reuse.
   *
   * No Visit is created: a Visit is one field attempt (`BR-047`, `BR-051`), and this route creates the
   * work request only.
   */
  @Post()
  @HttpCode(HttpStatus.CREATED)
  @RequirePermissions(JOB_PERMISSIONS.CREATE)
  async create(
    @Req() request: PermissionedRequest,
    @Body() body: unknown,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseCreateJobDto(body));
    return this.action(authorization, () =>
      this.jobs.createJob(
        { organizationId: authorization.organizationId },
        authorization.membershipId,
        input,
      ),
    );
  }

  @Get('visit-requests')
  @RequireAnyPermission(
    VISIT_PERMISSIONS.REVIEW_REQUESTS,
    VISIT_PERMISSIONS.REQUEST_FOLLOW_UP,
  )
  async listVisitRequests(
    @Req() request: PermissionedRequest,
  ): Promise<readonly FollowUpVisitRequestDto[]> {
    const authorization = authorizationOf(request);
    return this.jobs.listFollowUpVisitRequests(
      { organizationId: authorization.organizationId },
      authorization.membershipId,
      authorization.permissions.includes(VISIT_PERMISSIONS.REVIEW_REQUESTS),
    );
  }

  /**
   * One Job, for the office caller or for a field caller whose own crew includes the Job (`ADR-019`
   * D1, D2).
   *
   * Both audiences share one projection and one guard. Which capability admitted the caller is what
   * decides the scope applied to the read — never a role name (`BR-006`, `BR-007`).
   *
   * Beside that scope the read asks a **second** question: whether the Customer's own contact details
   * belong in the answer (`BR-092`; `ADR-021` D2). A caller holding `customers.view` or
   * `customers.view_assigned` receives them; a caller holding neither receives `null` for the block and
   * the rest of the Job unchanged, because the Job is still theirs to work (`BR-009`, `BR-011`).
   */
  @Get(':id')
  @RequireAnyPermission(
    CUSTOMER_PERMISSIONS.VIEW,
    VISIT_PERMISSIONS.VIEW_ASSIGNED,
  )
  async findOne(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const details = await this.jobs.findJobDetailsInOrganization(
      { organizationId: authorization.organizationId },
      id,
      assignedViewerOf(authorization),
    );
    if (details === null) {
      throw jobNotFound();
    }
    return toJobDetailsDto(details, jobDetailsReadOptionsOf(authorization));
  }

  @Post(':id/visit-requests')
  @HttpCode(HttpStatus.CREATED)
  @RequirePermissions(VISIT_PERMISSIONS.REQUEST_FOLLOW_UP)
  async submitVisitRequest(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ): Promise<FollowUpVisitRequestDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseSubmitFollowUpVisitRequestDto(body));
    try {
      return await this.jobs.submitFollowUpVisitRequest(
        { organizationId: authorization.organizationId },
        id,
        authorization.membershipId,
        input,
      );
    } catch (error) {
      throw mapActionError(error);
    }
  }

  @Post(':id/visits')
  @HttpCode(HttpStatus.CREATED)
  @RequirePermissions(VISIT_PERMISSIONS.CREATE_SCHEDULE)
  async createVisit(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseDirectCreateVisitDto(body));
    return this.action(authorization, () =>
      this.jobs.createScheduledVisit(
        { organizationId: authorization.organizationId },
        id,
        authorization.membershipId,
        input,
      ),
    );
  }

  @Post(':id/visit-requests/:requestId/clarification')
  @HttpCode(HttpStatus.OK)
  @RequirePermissions(VISIT_PERMISSIONS.REVIEW_REQUESTS)
  async clarifyVisitRequest(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('requestId') requestId: string,
    @Body() body: unknown,
  ): Promise<FollowUpVisitRequestDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseFollowUpVisitReviewDto(body));
    try {
      return await this.jobs.askFollowUpVisitRequestClarification(
        { organizationId: authorization.organizationId },
        id,
        requestId,
        authorization.membershipId,
        input,
      );
    } catch (error) {
      throw mapActionError(error);
    }
  }

  @Post(':id/visit-requests/:requestId/rejection')
  @HttpCode(HttpStatus.OK)
  @RequirePermissions(VISIT_PERMISSIONS.REVIEW_REQUESTS)
  async rejectVisitRequest(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('requestId') requestId: string,
    @Body() body: unknown,
  ): Promise<FollowUpVisitRequestDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseFollowUpVisitReviewDto(body));
    try {
      return await this.jobs.rejectFollowUpVisitRequest(
        { organizationId: authorization.organizationId },
        id,
        requestId,
        authorization.membershipId,
        input,
      );
    } catch (error) {
      throw mapActionError(error);
    }
  }

  @Post(':id/visit-requests/:requestId/approval')
  @HttpCode(HttpStatus.CREATED)
  @RequirePermissions(VISIT_PERMISSIONS.REVIEW_REQUESTS)
  async approveVisitRequest(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('requestId') requestId: string,
    @Body() body: unknown,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    if (!authorization.permissions.includes(VISIT_PERMISSIONS.CREATE_SCHEDULE)) {
      throw AuthApiError.forbidden();
    }
    const input = parseInput(() => parseFollowUpVisitApprovalDto(body));
    return this.action(authorization, () =>
      this.jobs.approveFollowUpVisitRequest(
        { organizationId: authorization.organizationId },
        id,
        requestId,
        authorization.membershipId,
        input,
      ),
    );
  }

  /**
   * One Job's chronological activity, newest first (`BR-080`).
   *
   * The Job read's own guard covers its activity, because the activity is a projection of the same
   * records (`BR-006`, `BR-080`): `customers.view` for the office caller, `VISIT_VIEW_ASSIGNED` for the
   * field caller, with the same scope applied to both (`ADR-019` D1, D2). The read stores nothing
   * (`BR-001`) and its event vocabulary is the one defined in `job-activity.ts` (`BR-041`).
   *
   * `includeRemovedEvidence=true` asks for the **audit/history context** rather than the ordinary one:
   * evidence a Manager has removed from ordinary use (`BR-089`) is included, of every kind, which is what
   * `D6d` requires of the context it stays visible in. That is a read of evidence taken out of use, so it
   * is authorized by an evidence **removal** capability — the Manager-level capability that governs the
   * evidence lifecycle — and a caller who may read the activity without one is refused rather than
   * quietly answered with the ordinary projection (`BR-007`, `BR-089`). Either kind's removal capability
   * is enough, because the flag asks one question about one read rather than one question per kind
   * (`ADR-018` A7).
   */
  @Get(':id/activity')
  @RequireAnyPermission(
    CUSTOMER_PERMISSIONS.VIEW,
    VISIT_PERMISSIONS.VIEW_ASSIGNED,
  )
  async findActivity(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Query() query: unknown,
  ): Promise<JobActivityDto> {
    const authorization = authorizationOf(request);
    const options = parseInput(() => parseJobActivityOptions(query));
    if (
      options.includeRemovedEvidence === true &&
      !authorization.permissions.includes(EVIDENCE_PERMISSIONS.PHOTO_REMOVE) &&
      !authorization.permissions.includes(EVIDENCE_PERMISSIONS.AUDIO_REMOVE)
    ) {
      throw AuthApiError.forbidden();
    }
    const events = await this.jobs.findJobActivityInOrganization(
      { organizationId: authorization.organizationId },
      id,
      options,
      assignedViewerOf(authorization),
    );
    if (events === null) {
      throw jobNotFound();
    }
    return toJobActivityDto(id, events);
  }

  /**
   * Moves a Job through its lifecycle (`BR-058`).
   *
   * `BR-058`'s permitted transitions are the validation contract, so a status the Job cannot move to
   * is refused and answered with the transitions it may make instead. `BR-061`'s entry conditions are
   * checked before `PENDING_REVIEW`, and the change is recorded as an append-only history row
   * (`BR-067`).
   */
  @Patch(':id/status')
  @HttpCode(HttpStatus.OK)
  @RequirePermissions(JOB_PERMISSIONS.UPDATE)
  async changeStatus(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseChangeJobStatusDto(body));
    return this.action(authorization, () =>
      this.jobs.changeJobStatus(
        { organizationId: authorization.organizationId },
        id,
        authorization.membershipId,
        input,
      ),
    );
  }

  /**
   * Edits the represented Visit's schedule (`BR-073`).
   *
   * `BR-070` conflicts are refused on the first attempt and carried in the response so the client can
   * show which Visit, which technician and which time overlap; the same request with
   * `confirmConflicts` applies the change, because a conflict is a warning rather than a prohibition
   * in v1.
   */
  @Patch(':id/visits/:visitId/schedule')
  @HttpCode(HttpStatus.OK)
  @RequirePermissions(VISIT_PERMISSIONS.UPDATE_SCHEDULE)
  async reschedule(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('visitId') visitId: string,
    @Body() body: unknown,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseRescheduleVisitDto(body));
    return this.action(authorization, () =>
      this.jobs.rescheduleVisit(
        { organizationId: authorization.organizationId },
        id,
        visitId,
        authorization.membershipId,
        input,
      ),
    );
  }

  /**
   * States the crew a Visit carries (`BR-068`, `BR-069`).
   *
   * The request carries the whole crew with exactly one Lead, because removing the current Lead and
   * choosing the next one is one explicit action the API never decides for the user.
   */
  @Put(':id/visits/:visitId/technicians')
  @HttpCode(HttpStatus.OK)
  @RequirePermissions(VISIT_PERMISSIONS.ASSIGN_TECHNICIANS)
  async assign(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('visitId') visitId: string,
    @Body() body: unknown,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseAssignVisitTechniciansDto(body));
    return this.action(authorization, () =>
      this.jobs.assignVisitTechnicians(
        { organizationId: authorization.organizationId },
        id,
        visitId,
        authorization.membershipId,
        input,
      ),
    );
  }

  /**
   * Advances one Visit through its field lifecycle (`BR-074`, `BR-075`, `BR-077`, `BR-093`; `ADR-019`
   * D3, D4, D5, D7).
   *
   * The route applies `BR-074`'s transitions and `BR-075`'s one correction; `CANCELED` and `NO_SHOW` are
   * refused because `BR-066` makes them dispatch actions and no capability authorizes one today
   * (`BR-042`, `BR-076`). A destination the lifecycle does not permit is answered with the destinations
   * the Visit has, as the Job status route already does.
   *
   * **Two authorizations reach it, and `BR-093` keeps them distinct.** `VISIT_UPDATE_ASSIGNED_STATUS` is
   * the capability `BR-009` gives the technician for their own assigned work, and the caller's scope is
   * then their own current assignment: a Visit their crew does not include is reported as not found,
   * never as forbidden (`ADR-019` D2, D3). The office's `JOB_UPDATE` — the capability every office Visit
   * action on a Job already requires (`BR-008`, `docs/api/job-actions.md` §2) — admits an office member
   * to any Visit of the organization **without** crew membership, which is how a manager completes a
   * Visit from Job Details (`BR-093`). Which capability admitted the caller decides the scope, never a
   * role name (`BR-004`, `BR-006`, `BR-007`).
   */
  @Patch(':id/visits/:visitId/status')
  @HttpCode(HttpStatus.OK)
  @RequireAnyPermission(
    JOB_PERMISSIONS.UPDATE,
    VISIT_PERMISSIONS.UPDATE_ASSIGNED_STATUS,
  )
  async changeVisitStatus(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('visitId') visitId: string,
    @Body() body: unknown,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseChangeVisitStatusDto(body));
    // Completing a Visit records the outcome `BR-077` requires, which the catalogue keeps as its own
    // capability (`BR-009`). A route declares either an all-of requirement or an any-of one and never
    // both (`ADR-019` D1), so the second question is asked here, in its own place — exactly as the
    // activity read asks the question its `includeRemovedEvidence` flag raises. `BR-093` requires it of
    // **every** caller: the office capability admits a member to the route, but the completion's own
    // capability is what authorizes the outcome, the office included.
    if (
      input.status === 'COMPLETED' &&
      !authorization.permissions.includes(VISIT_PERMISSIONS.RECORD_OUTCOME)
    ) {
      throw AuthApiError.forbidden();
    }
    return this.action(authorization, () =>
      this.jobs.changeVisitStatus(
        { organizationId: authorization.organizationId },
        id,
        visitId,
        authorization.membershipId,
        // The scope follows the capability that admitted the caller (`BR-093`): the office's
        // `JOB_UPDATE` addresses the organization's Visits, a field capability keeps the caller bounded
        // by their own current crew (`ADR-019` D2, D3).
        visitWriterOf(authorization),
        input,
      ),
    );
  }

  /**
   * Adds one text update to a Visit and returns the refreshed Job Activity timeline (`BR-027`).
   *
   * Two capabilities reach it, and each keeps its own reach: `VISIT_ADD_NOTE` for the technician on the
   * Visit's crew (`BR-009`, `ADR-019` D3), and the office's `JOB_UPDATE`, which is how a manager has
   * always recorded a note about a Visit (`BR-008`, `docs/api/job-actions.md` §2). The scope follows the
   * capability that admitted the caller, so widening the guard changes nothing for the office and gives
   * the technician the action their own capability names.
   *
   * The write is idempotent on the device's own operation id when one is supplied (`BR-031`,
   * `ADR-019` D5).
   */
  @Post(':id/visits/:visitId/notes')
  @HttpCode(HttpStatus.CREATED)
  @RequireAnyPermission(JOB_PERMISSIONS.UPDATE, VISIT_PERMISSIONS.ADD_NOTE)
  async addNote(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('visitId') visitId: string,
    @Body() body: unknown,
  ): Promise<JobActivityDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseAddVisitNoteDto(body));
    try {
      const events = await this.jobs.addVisitNote(
        { organizationId: authorization.organizationId },
        id,
        visitId,
        authorization.membershipId,
        input,
        visitWriterOf(authorization),
      );
      return toJobActivityDto(id, events);
    } catch (error) {
      throw mapActionError(error);
    }
  }

  /**
   * Adds one photo to a Job's Activity and returns the refreshed timeline (`BR-015`, `BR-027`).
   *
   * A photo is field evidence a technician captures while working, so it is offered on the Job rather
   * than on a Visit: a Job may exist with no Visit at all (`BR-051`), and the camera must not depend
   * on one. The bytes are validated before they become evidence (`ADR-013` D7) and the write is
   * idempotent on the device's own operation id (`BR-031`), so a retry after a timeout returns the
   * original activity instead of storing the photo twice.
   *
   * `evidence.photo.add` guards the route rather than `JOB_UPDATE`: the caller is the technician who
   * took the photo, and the default Technician role does not update Jobs (`BR-009`,
   * `docs/decisions/015-evidence-capabilities.md`).
   *
   * The part is read through the same multipart handling the API already has, bounded by
   * `MAX_JOB_PHOTO_BYTES`; an oversized body is refused by the parser before it is buffered further.
   */
  @Post(':id/photos')
  @HttpCode(HttpStatus.CREATED)
  @RequirePermissions(EVIDENCE_PERMISSIONS.PHOTO_ADD)
  @UseInterceptors(
    FileInterceptor('file', {
      limits: { fileSize: MAX_JOB_PHOTO_BYTES, files: 1 },
    }),
  )
  async addPhoto(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
    @UploadedFile() file: JobPhotoUpload | undefined,
  ): Promise<JobActivityDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseCreateJobPhotoDto(body));
    const photo = parseInput(() => validateJobPhotoUpload(file));
    try {
      const events = await this.photos.addJobPhoto(
        { organizationId: authorization.organizationId },
        id,
        authorization.membershipId,
        input,
        photo,
      );
      return toJobActivityDto(id, events);
    } catch (error) {
      throw mapPhotoError(error);
    }
  }

  /**
   * Returns one photo's bytes (`BR-015`).
   *
   * Evidence travels through the API on the API port, never through a presigned URL: the request is
   * authorized like every other one, the object's key is derived from the record rather than supplied
   * by the client, and moving to a provider later stays a configuration change (`ADR-013` D6.4, D7).
   *
   * `evidence.view` is required in its own right: adding photo evidence does not grant reading it,
   * so the capability can be withdrawn per kind (`BR-006`, tracker 029 D1b).
   */
  @Get(':id/photos/:photoId/content')
  @RequirePermissions(EVIDENCE_PERMISSIONS.VIEW)
  async readPhotoContent(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('photoId') photoId: string,
  ): Promise<StreamableFile> {
    const authorization = authorizationOf(request);
    try {
      const object = await this.photos.readJobPhotoContent(
        { organizationId: authorization.organizationId },
        id,
        photoId,
      );
      return new StreamableFile(object.body, {
        type: object.contentType,
        length: object.byteSize,
      });
    } catch (error) {
      throw mapPhotoError(error);
    }
  }

  /**
   * Takes one photo out of ordinary use, recording who removed it, when and why (`BR-089`).
   *
   * Evidence is immutable once this API has accepted it (`BR-088`), so there is **no edit route** and
   * this is the only operation that reaches a recorded photo: it appends a removal record, which is
   * what stops the evidence appearing in ordinary reads while its record and history are preserved
   * (`BR-067`). The photo is named by the id it is already known by, so a client never supplies an
   * object key (`ADR-013` D6.5).
   *
   * `evidence.photo.remove` guards it — a Manager-level capability that the default Technician role
   * does not hold (`BR-089`) — and the route is **online-only**: it takes no client-generated
   * idempotency key and has no decided conflict policy, which is the offline standard's own condition
   * for staying out of the outbox (§5, §8, §13.2). It answers with the refreshed timeline, exactly as
   * adding a photo and adding a note do, so the client presents what the backend now reports rather
   * than patching its own copy (`BR-001`, `BR-080`).
   */
  @Post(':id/photos/:photoId/removal')
  @HttpCode(HttpStatus.CREATED)
  @RequirePermissions(EVIDENCE_PERMISSIONS.PHOTO_REMOVE)
  async removePhoto(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('photoId') photoId: string,
    @Body() body: unknown,
  ): Promise<JobActivityDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseRemoveJobPhotoDto(body));
    try {
      const events = await this.photos.removeJobPhoto(
        { organizationId: authorization.organizationId },
        id,
        photoId,
        authorization.membershipId,
        input,
      );
      return toJobActivityDto(id, events);
    } catch (error) {
      throw mapPhotoError(error);
    }
  }

  /**
   * Records one audio note on a Job and returns the refreshed timeline (`BR-091`, `ADR-018`).
   *
   * An audio note is field evidence of its own kind, so it is offered on the **Job** exactly as a photo
   * is: a Job may exist with no Visit at all (`BR-051`), and recording must not depend on one. The bytes
   * are validated before they become evidence — an audio-only MP4 whose length the API reads from the
   * container (`ADR-018` A2/A3) — and the write is idempotent on the device's own operation id
   * (`BR-031`), so a retry after a timeout returns the original activity instead of storing the recording
   * twice.
   *
   * `evidence.audio.add` guards the route rather than `JOB_UPDATE`: the caller is the technician who made
   * the recording, and the default Technician role does not update Jobs (`BR-009`, `ADR-018` A7).
   *
   * The part is read through the same multipart handling the photo route uses, bounded by
   * `MAX_JOB_AUDIO_BYTES`; an oversized body is refused by the parser before it is buffered further.
   */
  @Post(':id/audio-notes')
  @HttpCode(HttpStatus.CREATED)
  @RequirePermissions(EVIDENCE_PERMISSIONS.AUDIO_ADD)
  @UseInterceptors(
    FileInterceptor('file', {
      limits: { fileSize: MAX_JOB_AUDIO_BYTES, files: 1 },
    }),
  )
  async addAudioNote(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
    @UploadedFile() file: JobAudioNoteUpload | undefined,
  ): Promise<JobActivityDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseCreateJobAudioNoteDto(body));
    const audio = parseInput(() => validateJobAudioNoteUpload(file));
    try {
      const events = await this.audioNotes.addJobAudioNote(
        { organizationId: authorization.organizationId },
        id,
        authorization.membershipId,
        input,
        audio,
      );
      return toJobActivityDto(id, events);
    } catch (error) {
      throw mapAudioError(error);
    }
  }

  /**
   * Returns one audio note's bytes (`BR-091`).
   *
   * Evidence travels through the API on the API port, never through a presigned URL: the request is
   * authorized like every other one, the object's key is derived from the record rather than supplied by
   * the client, and moving to a provider later stays a configuration change (`ADR-013` D6.4, D7).
   *
   * `evidence.view` is required in its own right, and it is the **same** read capability a photo's bytes
   * need: reading evidence is one capability rather than one per kind, so a client that may play a
   * recording may also fetch a photo's bytes. What is per kind is adding and removing (`ADR-018` A7).
   */
  @Get(':id/audio-notes/:audioNoteId/content')
  @RequirePermissions(EVIDENCE_PERMISSIONS.VIEW)
  async readAudioNoteContent(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('audioNoteId') audioNoteId: string,
  ): Promise<StreamableFile> {
    const authorization = authorizationOf(request);
    try {
      const object = await this.audioNotes.readJobAudioNoteContent(
        { organizationId: authorization.organizationId },
        id,
        audioNoteId,
      );
      return new StreamableFile(object.body, {
        type: object.contentType,
        length: object.byteSize,
      });
    } catch (error) {
      throw mapAudioError(error);
    }
  }

  /**
   * Takes one audio note out of ordinary use, recording who removed it, when and why (`BR-089`).
   *
   * Evidence is immutable once this API has accepted it (`BR-088`), so there is **no edit route** and
   * this is the only operation that reaches a recorded audio note: it appends a removal record, which is
   * what stops the recording appearing in ordinary reads while its record and history are preserved
   * (`BR-067`). The recording is named by the id it is already known by, so a client never supplies an
   * object key (`ADR-013` D6.5).
   *
   * `evidence.audio.remove` guards it — a Manager-level capability that the default Technician role does
   * not hold, and which is separate from the photo kind's (`ADR-018` A7) — and the route is
   * **online-only**: it takes no client-generated idempotency key and has no decided conflict policy,
   * which is the offline standard's own condition for staying out of the outbox (§5, §8, §13.2).
   */
  @Post(':id/audio-notes/:audioNoteId/removal')
  @HttpCode(HttpStatus.CREATED)
  @RequirePermissions(EVIDENCE_PERMISSIONS.AUDIO_REMOVE)
  async removeAudioNote(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('audioNoteId') audioNoteId: string,
    @Body() body: unknown,
  ): Promise<JobActivityDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseRemoveJobAudioNoteDto(body));
    try {
      const events = await this.audioNotes.removeJobAudioNote(
        { organizationId: authorization.organizationId },
        id,
        audioNoteId,
        authorization.membershipId,
        input,
      );
      return toJobActivityDto(id, events);
    } catch (error) {
      throw mapAudioError(error);
    }
  }

  /** Runs one action and answers with the Job as it now stands, or maps its failure. */
  private async action(
    authorization: ReturnType<typeof authorizationOf>,
    perform: () => Promise<JobDetails>,
  ): Promise<JobDetailsDto> {
    try {
      // The action routes return the same projection the read does, so they answer the read's second
      // authorization question the same way — otherwise a technician's customer block would vanish the
      // moment they moved a Visit's status (`BR-092`, `ADR-021` D2).
      return toJobDetailsDto(
        await perform(),
        jobDetailsReadOptionsOf(authorization),
      );
    } catch (error) {
      throw mapActionError(error);
    }
  }
}

/**
 * A Job outside the caller's organization is `404`, never `403`: the API does not confirm that
 * another organization's Job exists (`BR-001`), exactly as the Customer routes answer.
 */
function jobNotFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'JOB_NOT_FOUND',
      message: 'Job was not found.',
    },
    HttpStatus.NOT_FOUND,
  );
}

/**
 * The Customer a Job is created for is outside the caller's organization, does not exist, or has been
 * deleted (`BR-001`, `BR-023`).
 *
 * One code for all three, so the answer cannot be used to probe another organization's Customer ids or
 * to learn whether a deleted Customer exists — exactly as `GET /customers/:id` answers.
 */
function customerNotFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'CUSTOMER_NOT_FOUND',
      message: 'Customer was not found.',
    },
    HttpStatus.NOT_FOUND,
  );
}

/**
 * The Customer exists but does not accept new work (`BR-023`).
 *
 * The request is well formed and the Customer is the caller's; it is the Customer's own lifecycle that
 * refuses the operation, so it is a conflict with current state rather than a bad payload.
 */
function customerInactive(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'CUSTOMER_INACTIVE',
      message: 'The customer is not active and cannot take new work.',
    },
    HttpStatus.CONFLICT,
  );
}

/**
 * The Property is outside the caller's organization, does not exist, or is not one this Customer
 * currently holds (`BR-001`, `BR-050`).
 *
 * The same code the Customer-scoped Property routes answer with, so a Property id cannot be probed for
 * which Customer holds it or whether it exists at all.
 */
function propertyNotFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'PROPERTY_NOT_FOUND',
      message: 'Property was not found.',
    },
    HttpStatus.NOT_FOUND,
  );
}

/**
 * The Property exists but is not a location new work may start at because it is archived (`BR-083`).
 *
 * Archiving blocks new Jobs while leaving existing work alone, so the operation conflicts with the
 * Property's lifecycle rather than being a bad payload.
 */
function propertyNotAvailableForNewWork(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'PROPERTY_NOT_AVAILABLE_FOR_NEW_WORK',
      message: 'The property is archived and cannot take new work.',
    },
    HttpStatus.CONFLICT,
  );
}

/** The caller's organization and membership; the guard has already resolved both. */
function authorizationOf(request: PermissionedRequest) {
  const authorization = request.authorization;
  if (authorization === undefined) {
    throw AuthApiError.unauthenticated();
  }
  return authorization;
}

/**
 * The field caller's scope for the two Job reads, or `null` for the office caller (`ADR-019` D2).
 *
 * A caller holding the office read capability reads the organization's Jobs — exactly what the route
 * answered before this slice. A caller admitted by `VISIT_VIEW_ASSIGNED` instead reads only the Jobs
 * their own current assignments reach, and a Job it does not reach is reported as not found. The scope
 * is enforced in the service against current assignment rows; it is decided here from the capability
 * that admitted the caller, never from a role name (`BR-004`, `BR-006`, `BR-007`).
 */
function assignedViewerOf(
  authorization: ReturnType<typeof authorizationOf>,
): AssignedJobViewer | null {
  return authorization.permissions.includes(CUSTOMER_PERMISSIONS.VIEW)
    ? null
    : { membershipId: authorization.membershipId };
}

/**
 * The Job read's other authorization questions: what the projection may include and what the caller may
 * do on the Visit it reports (`BR-092`, `BR-093`; `ADR-021` D1, D2; `ADR-019` D3, D7).
 *
 * The guard decided who may read the Job at all; this decides what the projection may answer. It is
 * asked here, at the boundary, because a route declares either an all-of requirement or an any-of one and
 * never both (`ADR-019` D1). `customers.view` is the office read, which already reaches every Customer of
 * the organization; `customers.view_assigned` is the field capability `BR-092` grants the default
 * Technician role. A caller holding neither reads the same Job with `null` for the block rather than
 * being refused the Job, because the Job is still theirs to work (`BR-009`, `BR-011`).
 *
 * Every route that returns a Job answers it through this one function, the action routes included, so the
 * read and the actions can never disagree (`BR-041`).
 */
function jobDetailsReadOptionsOf(
  authorization: ReturnType<typeof authorizationOf>,
): JobDetailsReadOptions {
  return {
    includeCustomerContact:
      authorization.permissions.includes(CUSTOMER_PERMISSIONS.VIEW) ||
      authorization.permissions.includes(CUSTOMER_PERMISSIONS.VIEW_ASSIGNED),
    // The Visit's own question — may **this caller** drive its field lifecycle — is answered from the
    // membership the request was authorized for, because the crew the projection reports holds
    // memberships and the field route's scope is that same membership (`ADR-019` D3).
    callerMembershipId: authorization.membershipId,
    // Beside the crew, `BR-093` gives the question a second answer for the office caller: the capability
    // that reaches Visit writes authorizes any Visit of the organization without crew membership, which
    // is how a manager completes a Visit from Job Details (`ADR-019` D7).
    officeVisitWriter: authorization.permissions.includes(JOB_PERMISSIONS.UPDATE),
    // The completion keeps its own capability for **every** caller, the office included, so the
    // projection exposes the `COMPLETED` destination only when the caller holds it (`BR-009`, `BR-077`,
    // `BR-093`).
    recordsVisitOutcome: authorization.permissions.includes(
      VISIT_PERMISSIONS.RECORD_OUTCOME,
    ),
  };
}

/**
 * The Visit-level write scope, or `null` for the office caller (`BR-093`; `ADR-019` D2, D3, D7).
 *
 * Both Visit write routes ask it, and both read the same answer: `POST /jobs/:id/visits/:visitId/notes`
 * (`ADR-019` D3) and `PATCH /jobs/:id/visits/:visitId/status` (`BR-093`).
 *
 * A caller holding the office's Job-update capability keeps exactly the reach it has always had on these
 * routes: recording a note about a Visit, and `BR-093`'s office completion, are office work `BR-008`
 * names, so the assignment scope is not imposed on it — `ADR-019` D2 applies the scope only to a caller
 * **without** the office capability, and both routes follow the same shape as the Job read. A caller
 * admitted by a field capability instead writes only a Visit their own current crew includes, and a Visit
 * their assignments do not reach is reported as not found.
 *
 * Which capability admitted the caller decides the scope, never a role name (`BR-004`, `BR-006`,
 * `BR-007`). Note that the office caller is exempt from **crew membership**, not from a destination's own
 * capability: a completion still requires `VISIT_RECORD_OUTCOME` of every caller, and that question is
 * asked in its own place (`BR-093`).
 */
function visitWriterOf(
  authorization: ReturnType<typeof authorizationOf>,
): AssignedVisitWriter | null {
  return authorization.permissions.includes(JOB_PERMISSIONS.UPDATE)
    ? null
    : { membershipId: authorization.membershipId };
}

/** Turns a domain validation failure into the API's validation envelope (`dev.md` §7). */
function parseInput<T>(parser: () => T): T {
  try {
    return parser();
  } catch (error) {
    if (error instanceof DomainValidationError) {
      throw AuthApiError.validationFailed(error.issues.join('; '));
    }
    throw error;
  }
}

/** Maps a Job photo failure onto the HTTP contract (`dev.md` §7). */
function mapPhotoError(error: unknown): unknown {
  if (error instanceof JobNotFoundError) {
    return jobNotFound();
  }
  if (error instanceof JobPhotoNotFoundError) {
    return photoNotFound();
  }
  if (error instanceof JobPhotoOperationReusedError) {
    return new HttpException(
      {
        statusCode: HttpStatus.CONFLICT,
        code: 'PHOTO_OPERATION_REUSED',
        message: 'That operation id was already used for another job.',
      },
      HttpStatus.CONFLICT,
    );
  }
  if (error instanceof JobPhotoAlreadyRemovedError) {
    return photoAlreadyRemoved();
  }
  if (error instanceof ObjectStorageError) {
    // The evidence was not stored, so the request did not succeed. Answering with a success status
    // would tell the client its photo is safe when the backend does not hold it (`BR-015`, `BR-042`).
    return new HttpException(
      {
        statusCode: HttpStatus.SERVICE_UNAVAILABLE,
        code: 'STORAGE_UNAVAILABLE',
        message: 'The photo could not be stored.',
      },
      HttpStatus.SERVICE_UNAVAILABLE,
    );
  }
  return error;
}

/** The photo is not in the caller's organization and Job, or its bytes are unavailable (`BR-001`). */
function photoNotFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'JOB_PHOTO_NOT_FOUND',
      message: 'Job photo was not found.',
    },
    HttpStatus.NOT_FOUND,
  );
}

/**
 * The photo has already been removed from ordinary use (`BR-089`).
 *
 * One photo has one removal and no restore is defined, so a repeat is a conflict with the evidence's
 * own state rather than a second removal: the API refuses it explicitly instead of reporting a change
 * it did not record (`BR-067`).
 */
function photoAlreadyRemoved(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'JOB_PHOTO_ALREADY_REMOVED',
      message: 'That photo has already been removed.',
    },
    HttpStatus.CONFLICT,
  );
}

/**
 * Maps a Job audio note failure onto the HTTP contract (`dev.md` §7).
 *
 * The same answers the photo kind gives, with the audio kind's own codes: a client resolves a stable code
 * per failure rather than sharing one vocabulary between two kinds (`BR-041`, `ADR-018` A6).
 */
function mapAudioError(error: unknown): unknown {
  if (error instanceof JobNotFoundError) {
    return jobNotFound();
  }
  if (error instanceof JobAudioNoteNotFoundError) {
    return audioNoteNotFound();
  }
  if (error instanceof JobAudioNoteOperationReusedError) {
    return new HttpException(
      {
        statusCode: HttpStatus.CONFLICT,
        code: 'AUDIO_NOTE_OPERATION_REUSED',
        message: 'That operation id was already used for another job.',
      },
      HttpStatus.CONFLICT,
    );
  }
  if (error instanceof JobAudioNoteAlreadyRemovedError) {
    return audioNoteAlreadyRemoved();
  }
  if (error instanceof ObjectStorageError) {
    // The evidence was not stored, so the request did not succeed. Answering with a success status
    // would tell the client its recording is safe when the backend does not hold it (`BR-015`, `BR-042`).
    return new HttpException(
      {
        statusCode: HttpStatus.SERVICE_UNAVAILABLE,
        code: 'STORAGE_UNAVAILABLE',
        message: 'The recording could not be stored.',
      },
      HttpStatus.SERVICE_UNAVAILABLE,
    );
  }
  return error;
}

/** The audio note is not in the caller's organization and Job, or its bytes are unavailable (`BR-001`). */
function audioNoteNotFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'JOB_AUDIO_NOTE_NOT_FOUND',
      message: 'Job audio note was not found.',
    },
    HttpStatus.NOT_FOUND,
  );
}

/**
 * The audio note has already been removed from ordinary use (`BR-089`).
 *
 * One recording has one removal and no restore is defined, so a repeat is a conflict with the evidence's
 * own state rather than a second removal: the API refuses it explicitly instead of reporting a change it
 * did not record (`BR-067`).
 */
function audioNoteAlreadyRemoved(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'JOB_AUDIO_NOTE_ALREADY_REMOVED',
      message: 'That audio note has already been removed.',
    },
    HttpStatus.CONFLICT,
  );
}

/** Maps a Job or Visit action failure onto the HTTP contract (`dev.md` §7). */
function mapActionError(error: unknown): unknown {
  if (error instanceof JobNotFoundError) {
    return jobNotFound();
  }
  if (error instanceof JobCustomerNotFoundError) {
    return customerNotFound();
  }
  if (error instanceof JobCustomerInactiveError) {
    return customerInactive();
  }
  if (error instanceof PropertyNotFoundError) {
    return propertyNotFound();
  }
  if (error instanceof PropertyNotAvailableForNewWorkError) {
    return propertyNotAvailableForNewWork();
  }
  if (error instanceof VisitNotFoundError) {
    return visitNotFound();
  }
  if (error instanceof FollowUpVisitRequestNotFoundError) {
    return followUpVisitRequestNotFound();
  }
  if (error instanceof FollowUpVisitRequestConflictError) {
    return followUpVisitRequestConflict(error);
  }
  if (error instanceof FollowUpVisitRequestNotReviewableError) {
    return followUpVisitRequestNotReviewable(error);
  }
  if (error instanceof JobStatusTransitionNotAllowedError) {
    return transitionNotAllowed(error);
  }
  if (error instanceof VisitStatusTransitionNotAllowedError) {
    return visitTransitionNotAllowed(error);
  }
  if (error instanceof VisitSchedulingConditionNotMetError) {
    return visitSchedulingConditionNotMet(error);
  }
  if (error instanceof JobClosedForFieldWorkError) {
    return jobClosedForFieldWork();
  }
  if (error instanceof VisitOperationReusedError) {
    return new HttpException(
      {
        statusCode: HttpStatus.CONFLICT,
        code: 'VISIT_OPERATION_REUSED',
        message: 'That operation id was already used for another visit.',
      },
      HttpStatus.CONFLICT,
    );
  }
  if (error instanceof JobCancellationUnavailableError) {
    return cancellationUnavailable();
  }
  if (error instanceof JobReviewConditionNotMetError) {
    return reviewConditionNotMet(error);
  }
  if (error instanceof JobCompletionBlockedError) {
    return completionBlocked();
  }
  if (error instanceof VisitNotReschedulableError) {
    return visitNotReschedulable(error);
  }
  if (error instanceof ScheduleConflictError) {
    return scheduleConflict(error.conflicts);
  }
  if (error instanceof TechnicianNotAssignableError) {
    return techniciansNotAssignable(error.membershipIds);
  }
  if (error instanceof JobVersionConflictError) {
    return versionConflict('JOB', error.currentVersion);
  }
  if (error instanceof VisitVersionConflictError) {
    return versionConflict('VISIT', error.currentVersion);
  }
  return error;
}

function visitNotFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'VISIT_NOT_FOUND',
      message: 'Visit was not found on this job.',
    },
    HttpStatus.NOT_FOUND,
  );
}

function followUpVisitRequestNotFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'FOLLOW_UP_VISIT_REQUEST_NOT_FOUND',
      message: 'Follow-up visit request was not found.',
    },
    HttpStatus.NOT_FOUND,
  );
}

function followUpVisitRequestConflict(
  error: FollowUpVisitRequestConflictError,
): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'FOLLOW_UP_VISIT_REQUEST_CONFLICT',
      message: 'The follow-up visit request changed since it was read.',
      details: {
        currentStatus: error.currentStatus,
        currentVersion: error.currentVersion,
      },
    },
    HttpStatus.CONFLICT,
  );
}

function followUpVisitRequestNotReviewable(
  error: FollowUpVisitRequestNotReviewableError,
): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'FOLLOW_UP_VISIT_REQUEST_NOT_REVIEWABLE',
      message: `A follow-up visit request in ${error.status} cannot be reviewed.`,
      details: { status: error.status },
    },
    HttpStatus.CONFLICT,
  );
}

function transitionNotAllowed(
  error: JobStatusTransitionNotAllowedError,
): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'JOB_STATUS_TRANSITION_NOT_ALLOWED',
      message: `A job in ${error.from} cannot move to ${error.to}.`,
      details: { from: error.from, to: error.to, allowed: [...error.allowed] },
    },
    HttpStatus.CONFLICT,
  );
}

/**
 * `BR-064` requires a structured cancellation reason whose catalogue product ownership has not
 * defined, so the API refuses the cancellation instead of inventing the vocabulary (`BR-042`).
 */
function cancellationUnavailable(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'JOB_CANCELLATION_UNAVAILABLE',
      message:
        'Cancelling a job is not available yet: the cancellation reason catalogue is not defined.',
    },
    HttpStatus.CONFLICT,
  );
}

function reviewConditionNotMet(
  error: JobReviewConditionNotMetError,
): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'JOB_REVIEW_CONDITION_NOT_MET',
      message:
        error.reason === 'ACTIVE_VISIT'
          ? 'The job still has an active visit.'
          : 'The latest completed visit does not resolve the job.',
      details: { reason: error.reason },
    },
    HttpStatus.CONFLICT,
  );
}

/**
 * `BR-062`: a Job must not be completed while it has an open Visit.
 *
 * The refusal is its own code rather than a `JOB_STATUS_TRANSITION_NOT_ALLOWED`: the destination is
 * structurally permitted (`BR-058`), and what refuses it is the state of the Job's field work. A
 * client can therefore tell the two apart and say which one happened.
 */
function completionBlocked(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'JOB_COMPLETION_BLOCKED',
      message: 'The job still has an open visit.',
    },
    HttpStatus.CONFLICT,
  );
}

function visitNotReschedulable(
  error: VisitNotReschedulableError,
): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'VISIT_NOT_RESCHEDULABLE',
      message: `A visit in ${error.status} cannot be rescheduled.`,
      details: { status: error.status },
    },
    HttpStatus.CONFLICT,
  );
}

/**
 * `BR-074` (or `BR-075`) does not permit that Visit destination.
 *
 * The permitted destinations travel with the refusal, as the Job status route carries its own, so a
 * client presents the Visit's real options rather than holding a second copy of the lifecycle
 * (`BR-041`, `BR-022`).
 */
function visitTransitionNotAllowed(
  error: VisitStatusTransitionNotAllowedError,
): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'VISIT_STATUS_TRANSITION_NOT_ALLOWED',
      message: `A visit in ${error.from} cannot move to ${error.to}.`,
      details: {
        from: error.from,
        to: error.to,
        allowed: [...error.allowed],
      },
    },
    HttpStatus.CONFLICT,
  );
}

/**
 * `BR-072`'s conditions for a Visit becoming `SCHEDULED` are not met.
 *
 * It is its own code rather than a `VISIT_STATUS_TRANSITION_NOT_ALLOWED`: the destination is
 * structurally permitted and what refuses it is the state of the Visit's own preparation, so a client
 * can tell the two apart and say which one happened.
 */
function visitSchedulingConditionNotMet(
  error: VisitSchedulingConditionNotMetError,
): HttpException {
  const message =
    error.reason === 'PROPERTY'
      ? 'The job has no property yet.'
      : error.reason === 'SCHEDULE'
        ? 'The visit has no valid schedule.'
        : 'The visit needs one lead technician assigned.';
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'VISIT_SCHEDULING_CONDITION_NOT_MET',
      message,
      details: { reason: error.reason },
    },
    HttpStatus.CONFLICT,
  );
}

/**
 * The Visit's Job is closed, so its field record is final (`BR-062`, `BR-079`).
 *
 * `BR-062` prevents the state being reached through completion, so this is the API failing closed
 * rather than writing field history under a closed Job; the state itself keeps its own open question
 * (`BR-042`, `ADR-019` D4).
 */
function jobClosedForFieldWork(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'JOB_CLOSED_FOR_FIELD_WORK',
      message: 'The job is closed; its field record is final.',
    },
    HttpStatus.CONFLICT,
  );
}

/** The conflicts `BR-070` requires the user to see before the change is applied. */
function scheduleConflict(
  conflicts: readonly ScheduleConflict[],
): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'SCHEDULE_CONFLICT',
      message: 'The change conflicts with another visit.',
      details: { conflicts: [...conflicts] },
    },
    HttpStatus.CONFLICT,
  );
}

function techniciansNotAssignable(
  membershipIds: readonly string[],
): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.UNPROCESSABLE_ENTITY,
      code: 'TECHNICIANS_NOT_ASSIGNABLE',
      message: 'One or more technicians cannot be assigned.',
      details: { membershipIds: [...membershipIds] },
    },
    HttpStatus.UNPROCESSABLE_ENTITY,
  );
}

function versionConflict(
  record: 'JOB' | 'VISIT',
  currentVersion: number,
): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'VERSION_CONFLICT',
      message: `The ${record.toLowerCase()} changed since it was read. Re-read it and try again.`,
      details: { record, currentVersion },
    },
    HttpStatus.CONFLICT,
  );
}
