import { Controller, Get, Query, Req, UseGuards } from '@nestjs/common';
import { AuthApiError } from '../auth/auth-error.js';
import { AuthGuard } from '../auth/auth.guard.js';
import { VISIT_PERMISSIONS } from '../auth/permissions.js';
import { RequirePermissions } from '../auth/permissions.decorator.js';
import { PermissionsGuard } from '../auth/permissions.guard.js';
import type { PermissionedRequest } from '../auth/permissions.guard.js';
import { resolveDayWindow } from './home-day.js';
import { readTimeZoneQuery } from './home-query.js';
import { toTechnicianHomeDto } from './technician-home.dto.js';
import type { TechnicianHomeDto } from './technician-home.dto.js';
import { TechnicianHomeService } from './technician-home.service.js';

/**
 * The technician home read (`GET /home/technician`).
 *
 * Authorization is `VISIT_VIEW_ASSIGNED`, the capability `BR-009` gives the technician for their own
 * assigned work, and the read is scoped to the assignments of the membership the route was reached
 * with (`ADR-019` D2, D3). It is **not** the manager home under another name: the office reads
 * `GET /home/manager` with `customers.view`, and this read deliberately does not admit that
 * capability, because every row it returns belongs to one technician (`BR-010`, `BR-012`).
 *
 * A member who holds the field capability *and* the office one still gets their own assignments
 * here — the scope is the caller's crew rows, never their permissions (`BR-006`, `BR-007`).
 *
 * The date is settled here rather than by the client, through the same accepted `timeZone`
 * parameter and the same day resolution the manager home uses, so the "today" this read returns and
 * the "today" the office sees are one window over authoritative records (`BR-001`, `BR-041`).
 */
@Controller('home')
@UseGuards(AuthGuard, PermissionsGuard)
export class TechnicianHomeController {
  constructor(private readonly technicianHome: TechnicianHomeService) {}

  @Get('technician')
  @RequirePermissions(VISIT_PERMISSIONS.VIEW_ASSIGNED)
  async read(
    @Req() request: PermissionedRequest,
    @Query() query: unknown,
  ): Promise<TechnicianHomeDto> {
    const authorization = request.authorization;
    const auth = request.auth;
    if (authorization === undefined || auth === undefined) {
      throw AuthApiError.unauthenticated();
    }

    const timeZone = readTimeZoneQuery(query);
    const now = new Date();
    const day = resolveDayWindow(timeZone, now);
    const read = await this.technicianHome.readTechnicianHome(
      { organizationId: authorization.organizationId },
      auth.userId,
      authorization.membershipId,
      day,
      now,
    );

    return toTechnicianHomeDto({ generatedAt: now, day, read });
  }
}
