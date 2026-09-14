import { Injectable } from '@nestjs/common';
import { and, asc, eq, sql } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  organizationMembers,
  organizationRoles,
  userProfiles,
} from '../database/schema.js';
import { memberName } from '../members/member-name.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type { AssignableTechnician } from './technician.dto.js';

/**
 * Reads the organization's technicians (`BR-024`, `BR-068`).
 *
 * A technician is an active member of the organization holding the default Technician role
 * (`BR-003`): a technician authenticates as a Servora user and is who field work is assigned to.
 * Membership is what an assignment names, so the read is keyed by membership id and never by user id.
 *
 * Every query is scoped by `organization_id` (`BR-001`, `src/tenancy/tenant-scope.ts`): one
 * organization never sees another's technicians.
 */
@Injectable()
export class TechniciansService {
  constructor(private readonly database: DatabaseService) {}

  private get db() {
    return this.database.db;
  }

  /** The active technicians of the caller's organization, ordered by name. */
  async listAssignableTechniciansInOrganization(
    scope: OrganizationScope,
  ): Promise<readonly AssignableTechnician[]> {
    const rows = await this.db
      .select({
        membershipId: organizationMembers.id,
        displayName: userProfiles.displayName,
        firstName: userProfiles.firstName,
        lastName: userProfiles.lastName,
      })
      .from(organizationMembers)
      .innerJoin(
        organizationRoles,
        eq(organizationRoles.id, organizationMembers.roleId),
      )
      .leftJoin(
        userProfiles,
        eq(userProfiles.userId, organizationMembers.userId),
      )
      .where(
        and(
          eq(organizationMembers.organizationId, scope.organizationId),
          eq(organizationMembers.status, 'ACTIVE'),
          eq(organizationRoles.systemCode, 'TECHNICIAN'),
        ),
      )
      // Ordered by the name the screen will show, so the list does not depend on insertion order.
      // A member without a profile sorts by the empty name rather than failing the read.
      .orderBy(
        asc(
          sql`lower(coalesce(${userProfiles.displayName}, concat_ws(' ', ${userProfiles.firstName}, ${userProfiles.lastName}), ''))`,
        ),
      );

    return rows.map((row) => ({
      membershipId: row.membershipId,
      name: memberName(row),
    }));
  }
}
