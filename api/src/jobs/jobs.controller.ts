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
  Req,
  UseGuards,
} from '@nestjs/common';
import { AuthApiError } from '../auth/auth-error.js';
import { AuthGuard } from '../auth/auth.guard.js';
import { CUSTOMER_PERMISSIONS, JOB_PERMISSIONS } from '../auth/permissions.js';
import { RequirePermissions } from '../auth/permissions.decorator.js';
import { PermissionsGuard } from '../auth/permissions.guard.js';
import type { PermissionedRequest } from '../auth/permissions.guard.js';
import { DomainValidationError } from '../validation/domain-validation.js';
import { toJobDetailsDto } from './job-details.dto.js';
import type { JobDetailsDto, JobDetails } from './job-details.dto.js';
import { toJobActivityDto } from './job-activity.js';
import type { JobActivityDto } from './job-activity.js';
import {
  parseAssignVisitTechniciansDto,
  parseAddVisitNoteDto,
  parseChangeJobStatusDto,
  parseRescheduleVisitDto,
} from './job-action.dto.js';
import type { ScheduleConflict } from './jobs.service.js';
import {
  JobCancellationUnavailableError,
  JobNotFoundError,
  JobReviewConditionNotMetError,
  JobStatusTransitionNotAllowedError,
  JobsService,
  JobVersionConflictError,
  ScheduleConflictError,
  TechnicianNotAssignableError,
  VisitNotReschedulableError,
  VisitNotFoundError,
  VisitVersionConflictError,
} from './jobs.service.js';

/**
 * The Job read and the Job and Visit management actions (`BR-058` – `BR-079`).
 *
 * The **read** is guarded by `customers.view`, the capability the API already uses for the
 * organization's Job and Visit data: `GET /customers/:id/jobs` is guarded by it, so a caller who can
 * open a Job Details screen can already read the same records. The catalogue has **no** `jobs.*`
 * capability and adding one would invent a permission the Jobs feature has not defined (`BR-006`,
 * `BR-042`); the interim decision is recorded in `docs/api/job-details.md` §2 and
 * `docs/tracker/017-android-job-details.md`.
 *
 * The **actions** are guarded by the existing `JOB_UPDATE` capability, which `BR-008` already names
 * as a Manager default and which the seeded catalogue already grants to that role. Introducing
 * `jobs.*` codes would invent the capability set the Jobs feature has not defined, so the actions
 * reuse the one that exists; the interim decision and its open question are recorded in
 * `docs/api/job-actions.md` §2 and `docs/tracker/018-android-job-actions.md`.
 *
 * Every action is an explicit, authorized business action whose outcome the service records in
 * append-only history (`BR-066`, `BR-067`). This controller orchestrates and maps failures onto the
 * HTTP contract; it decides no business rule of its own.
 */
@Controller('jobs')
@UseGuards(AuthGuard, PermissionsGuard)
export class JobsController {
  constructor(private readonly jobs: JobsService) {}

  @Get(':id')
  @RequirePermissions(CUSTOMER_PERMISSIONS.VIEW)
  async findOne(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const details = await this.jobs.findJobDetailsInOrganization(
      { organizationId: authorization.organizationId },
      id,
    );
    if (details === null) {
      throw jobNotFound();
    }
    return toJobDetailsDto(details);
  }

  /**
   * One Job's chronological activity, newest first (`BR-080`).
   *
   * The same `customers.view` capability guards the Job read and its activity, because the activity is
   * a projection of the same records (`BR-006`). The read stores nothing (`BR-001`) and its event
   * vocabulary is the one defined in `job-activity.ts` (`BR-041`).
   */
  @Get(':id/activity')
  @RequirePermissions(CUSTOMER_PERMISSIONS.VIEW)
  async findActivity(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
  ): Promise<JobActivityDto> {
    const authorization = authorizationOf(request);
    const events = await this.jobs.findJobActivityInOrganization(
      { organizationId: authorization.organizationId },
      id,
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
    return this.action(() =>
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
  @RequirePermissions(JOB_PERMISSIONS.UPDATE)
  async reschedule(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('visitId') visitId: string,
    @Body() body: unknown,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseRescheduleVisitDto(body));
    return this.action(() =>
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
  @RequirePermissions(JOB_PERMISSIONS.UPDATE)
  async assign(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('visitId') visitId: string,
    @Body() body: unknown,
  ): Promise<JobDetailsDto> {
    const authorization = authorizationOf(request);
    const input = parseInput(() => parseAssignVisitTechniciansDto(body));
    return this.action(() =>
      this.jobs.assignVisitTechnicians(
        { organizationId: authorization.organizationId },
        id,
        visitId,
        authorization.membershipId,
        input,
      ),
    );
  }

  /** Adds one text update to a Visit and returns the refreshed Job Activity timeline. */
  @Post(':id/visits/:visitId/notes')
  @HttpCode(HttpStatus.CREATED)
  @RequirePermissions(JOB_PERMISSIONS.UPDATE)
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
      );
      return toJobActivityDto(id, events);
    } catch (error) {
      throw mapActionError(error);
    }
  }

  /** Runs one action and answers with the Job as it now stands, or maps its failure. */
  private async action(
    perform: () => Promise<JobDetails>,
  ): Promise<JobDetailsDto> {
    try {
      return toJobDetailsDto(await perform());
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

/** The caller's organization and membership; the guard has already resolved both. */
function authorizationOf(request: PermissionedRequest) {
  const authorization = request.authorization;
  if (authorization === undefined) {
    throw AuthApiError.unauthenticated();
  }
  return authorization;
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

/** Maps a Job or Visit action failure onto the HTTP contract (`dev.md` §7). */
function mapActionError(error: unknown): unknown {
  if (error instanceof JobNotFoundError) {
    return jobNotFound();
  }
  if (error instanceof VisitNotFoundError) {
    return visitNotFound();
  }
  if (error instanceof JobStatusTransitionNotAllowedError) {
    return transitionNotAllowed(error);
  }
  if (error instanceof JobCancellationUnavailableError) {
    return cancellationUnavailable();
  }
  if (error instanceof JobReviewConditionNotMetError) {
    return reviewConditionNotMet(error);
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
