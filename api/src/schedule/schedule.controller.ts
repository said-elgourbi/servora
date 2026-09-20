import { Controller, Get, Query, Req, UseGuards } from '@nestjs/common';
import { AuthApiError } from '../auth/auth-error.js';
import { AuthGuard } from '../auth/auth.guard.js';
import { SCHEDULE_PERMISSIONS, VISIT_PERMISSIONS } from '../auth/permissions.js';
import { RequireAnyPermission } from '../auth/permissions.decorator.js';
import { PermissionsGuard } from '../auth/permissions.guard.js';
import type { PermissionedRequest } from '../auth/permissions.guard.js';
import {
  formatLocalDate,
  localDateIn,
  resolveLocalDayWindow,
} from '../home/home-day.js';
import { readTimeZoneQuery } from '../home/home-query.js';
import { DomainValidationError } from '../validation/domain-validation.js';
import {
  readScheduleDateQuery,
  readScheduleMembershipsQuery,
} from './schedule-query.js';
import { toScheduleDto } from './schedule.dto.js';
import type { ScheduleDto } from './schedule.dto.js';
import {
  resolveScheduleScope,
  scheduleScopeAdmitsFilter,
} from './schedule-scope.js';
import { ScheduleService } from './schedule.service.js';

/**
 * The day schedule read (`GET /schedule`).
 *
 * **Two audiences, one day, two scopes** (`BR-006`, `BR-009`, `ADR-019` D2). The office reaches it
 * with `schedule.view_org`, the capability that names organization-wide dispatch visibility, and reads the operation's day. A field
 * caller reaches it with `VISIT_VIEW_ASSIGNED`, the capability `BR-009` names for their own assigned
 * work, and reads **only** the Visits their own membership is on. The route declares the two as
 * alternatives; which of them the caller actually holds is then resolved into a scope and enforced
 * by the service, never by the client (`BR-007`, `BR-042`).
 *
 * The **day** is settled here rather than by the client: the caller names the time zone it renders
 * in and, optionally, the local date it is showing, and the API resolves the window those name. A
 * client therefore never decides which Visits belong to a day, and two clients of the same
 * organization asked for the same date agree (`BR-001`, `BR-041`).
 *
 * The **technician filter** is a repeatable `membershipId`. The ids are a union: one technician, a
 * small group and the whole team are the same question with a different number of names in it, so a
 * dispatcher's filter is one read rather than one read per technician (`BR-068`). It is
 * **authorized**: a filter naming a technician outside the caller's scope is refused with `403` for
 * every such id, whether or not that membership exists, so the refusal cannot be used to learn who
 * the organization employs (`BR-042`, `BR-044`).
 */
@Controller('schedule')
@UseGuards(AuthGuard, PermissionsGuard)
export class ScheduleController {
  constructor(private readonly schedule: ScheduleService) {}

  @Get()
  @RequireAnyPermission(
    SCHEDULE_PERMISSIONS.VIEW_ORG,
    VISIT_PERMISSIONS.VIEW_ASSIGNED,
  )
  async read(
    @Req() request: PermissionedRequest,
    @Query() query: unknown,
  ): Promise<ScheduleDto> {
    const authorization = request.authorization;
    if (authorization === undefined) {
      throw AuthApiError.unauthenticated();
    }

    try {
      const timeZone = readTimeZoneQuery(query);
      const requestedDate = readScheduleDateQuery(query);
      const membershipIds = readScheduleMembershipsQuery(query);
      const scope = resolveScheduleScope(authorization);

      // The requested scope is authorized, not assumed: a field caller asking for a colleague's
      // day is refused rather than quietly answered with their own (`BR-007`, `BR-042`).
      if (!scheduleScopeAdmitsFilter(scope, membershipIds)) {
        throw AuthApiError.forbidden();
      }

      const now = new Date();
      const localDate = requestedDate ?? localDateIn(timeZone, now);
      const day = resolveLocalDayWindow(timeZone, localDate);

      const read = await this.schedule.readSchedule(
        { organizationId: authorization.organizationId },
        day,
        membershipIds,
        now,
        scope,
      );

      return toScheduleDto({
        generatedAt: now,
        localDate: formatLocalDate(localDate),
        day,
        scope,
        technicians: read.technicians,
        visits: read.visits,
        unassigned: read.unassigned,
      });
    } catch (error) {
      if (error instanceof DomainValidationError) {
        throw AuthApiError.validationFailed(error.issues.join('; '));
      }
      throw error;
    }
  }
}
