import { Controller, Get, Query, Req, UseGuards } from '@nestjs/common';
import { AuthApiError } from '../auth/auth-error.js';
import { AuthGuard } from '../auth/auth.guard.js';
import { CUSTOMER_PERMISSIONS } from '../auth/permissions.js';
import { RequirePermissions } from '../auth/permissions.decorator.js';
import { PermissionsGuard } from '../auth/permissions.guard.js';
import type { PermissionedRequest } from '../auth/permissions.guard.js';
import { resolveDayWindow } from './home-day.js';
import { readTimeZoneQuery } from './home-query.js';
import { toManagerHomeDto } from './manager-home.dto.js';
import type { ManagerHomeDto } from './manager-home.dto.js';
import { ManagerHomeService } from './manager-home.service.js';

/**
 * The manager home read (`GET /home/manager`).
 *
 * Authorization is `customers.view`, the capability the API already uses for the organization's Job
 * and Visit data: `GET /customers/:id/jobs` is guarded by it, so a caller that can open the home can
 * already reach every Job and Visit it returns. Reusing it keeps this read inside the existing
 * permission model instead of introducing a capability the Jobs feature has not defined yet
 * (`BR-006`, `BR-042`); the interim decision is recorded in `docs/api/manager-home.md` §2 and
 * `docs/tracker/016-android-manager-home.md`.
 *
 * The date is settled here rather than by the client: the caller names the time zone it renders in
 * and the API resolves the local day, so the "today" this read returns and the "today" every other
 * client sees are one window over authoritative records (`BR-001`).
 */
@Controller('home')
@UseGuards(AuthGuard, PermissionsGuard)
export class ManagerHomeController {
  constructor(private readonly managerHome: ManagerHomeService) {}

  @Get('manager')
  @RequirePermissions(CUSTOMER_PERMISSIONS.VIEW)
  async read(
    @Req() request: PermissionedRequest,
    @Query() query: unknown,
  ): Promise<ManagerHomeDto> {
    const authorization = request.authorization;
    const auth = request.auth;
    if (authorization === undefined || auth === undefined) {
      throw AuthApiError.unauthenticated();
    }

    const timeZone = readTimeZoneQuery(query);
    const now = new Date();
    const day = resolveDayWindow(timeZone, now);
    const read = await this.managerHome.readManagerHome(
      { organizationId: authorization.organizationId },
      auth.userId,
      day,
      now,
    );

    return toManagerHomeDto({
      generatedAt: now,
      day,
      viewerDisplayName: read.viewerDisplayName,
      attention: read.attention,
      attentionTotal: read.attentionTotal,
      today: read.today,
      visits: read.visits,
    });
  }
}

