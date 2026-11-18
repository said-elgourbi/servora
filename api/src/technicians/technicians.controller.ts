import { Body, Controller, Get, Param, Patch, Req, UseGuards } from '@nestjs/common';
import { AuthApiError } from '../auth/auth-error.js';
import { AuthGuard } from '../auth/auth.guard.js';
import { TECHNICIAN_PERMISSIONS } from '../auth/permissions.js';
import { RequirePermissions } from '../auth/permissions.decorator.js';
import { PermissionsGuard } from '../auth/permissions.guard.js';
import type { PermissionedRequest } from '../auth/permissions.guard.js';
import { parseTechnicianMembershipId, parseUpdateTechnicianRequest, toAssignableTechnicianDto } from './technician.dto.js';
import type { AssignableTechnicianDto, TechnicianManagementDto } from './technician.dto.js';
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
 * `GET /technicians` remains the minimal assignment read. Manager profile management has separate,
 * explicitly permissioned routes (`GET /management`, `PATCH /:membershipId`) documented in
 * `docs/api/technicians.md`; it never changes the assignment projection's contract.
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

  @Get('management')
  @RequirePermissions(TECHNICIAN_PERMISSIONS.VIEW)
  async management(@Req() request: PermissionedRequest): Promise<TechnicianManagementDto> {
    const authorization = request.authorization;
    if (!authorization) throw AuthApiError.unauthenticated();
    return this.technicians.managementInOrganization({ organizationId: authorization.organizationId });
  }

  @Patch(':membershipId')
  @RequirePermissions(TECHNICIAN_PERMISSIONS.UPDATE)
  async update(
    @Param('membershipId') membershipId: string,
    @Body() body: unknown,
    @Req() request: PermissionedRequest,
  ) {
    const authorization = request.authorization;
    if (!authorization) throw AuthApiError.unauthenticated();
    return this.technicians.updateTechnicianInOrganization(
      { organizationId: authorization.organizationId }, parseTechnicianMembershipId(membershipId),
      authorization.membershipId, parseUpdateTechnicianRequest(body),
    );
  }
}
