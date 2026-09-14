import { Controller, Get, Req, UseGuards } from '@nestjs/common';
import { AuthApiError } from '../auth/auth-error.js';
import { AuthGuard } from '../auth/auth.guard.js';
import { TECHNICIAN_PERMISSIONS } from '../auth/permissions.js';
import { RequirePermissions } from '../auth/permissions.decorator.js';
import { PermissionsGuard } from '../auth/permissions.guard.js';
import type { PermissionedRequest } from '../auth/permissions.guard.js';
import { toAssignableTechnicianDto } from './technician.dto.js';
import type { AssignableTechnicianDto } from './technician.dto.js';
import { TechniciansService } from './technicians.service.js';

/**
 * The technician read (`GET /technicians`).
 *
 * It exists because assigning field work means choosing from the organization's technicians
 * (`BR-068`), and it is guarded by the existing `TECHNICIAN_VIEW` capability `BR-008` already names
 * as a Manager default. The Technician capability set has not been re-defined as `technicians.*` —
 * that is an **OPEN QUESTION** recorded in `docs/api/job-actions.md` §2 — so no new permission is
 * invented here (`BR-006`, `BR-042`).
 *
 * This controller exposes **reads only**: which users are technicians, and how technicians are
 * managed, is the Technician feature's to define (`BR-024`).
 */
@Controller('technicians')
@UseGuards(AuthGuard, PermissionsGuard)
export class TechniciansController {
  constructor(private readonly technicians: TechniciansService) {}

  @Get()
  @RequirePermissions(TECHNICIAN_PERMISSIONS.VIEW)
  async list(
    @Req() request: PermissionedRequest,
  ): Promise<AssignableTechnicianDto[]> {
    const authorization = request.authorization;
    if (authorization === undefined) {
      throw AuthApiError.unauthenticated();
    }
    const rows = await this.technicians.listAssignableTechniciansInOrganization(
      {
        organizationId: authorization.organizationId,
      },
    );
    return rows.map(toAssignableTechnicianDto);
  }
}
