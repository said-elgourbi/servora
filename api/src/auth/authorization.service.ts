import { Injectable } from '@nestjs/common';
import { and, eq } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  rolePermissions,
} from '../database/schema.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type { PermissionCode } from './permissions.js';

export interface AuthorizedMembership extends OrganizationScope {
  readonly membershipId: string;
  readonly permissions: readonly PermissionCode[];
}

@Injectable()
export class AuthorizationService {
  constructor(private readonly database: DatabaseService) {}

  async resolveMembershipForUser(
    userId: string,
  ): Promise<AuthorizedMembership | null> {
    const memberships = await this.resolveMembershipsForUser(userId);
    return memberships[0] ?? null;
  }

  async resolveMembershipForUserWithPermissions(
    userId: string,
    required: readonly PermissionCode[],
  ): Promise<AuthorizedMembership | null> {
    const memberships = await this.resolveMembershipsForUser(userId);
    return (
      memberships.find((membership) =>
        this.hasAllPermissions(membership, required),
      ) ?? null
    );
  }

  async resolveEffectivePermissionCodesForUser(
    userId: string,
  ): Promise<readonly PermissionCode[]> {
    const memberships = await this.resolveMembershipsForUser(userId);
    return [
      ...new Set(memberships.flatMap((membership) => membership.permissions)),
    ];
  }

  async resolveMembershipsForUser(
    userId: string,
  ): Promise<readonly AuthorizedMembership[]> {
    const members = await this.database.db
      .select({
        membershipId: organizationMembers.id,
        organizationId: organizationMembers.organizationId,
        roleId: organizationMembers.roleId,
      })
      .from(organizationMembers)
      .where(
        and(
          eq(organizationMembers.userId, userId),
          eq(organizationMembers.status, 'ACTIVE'),
        ),
      )
      .orderBy(organizationMembers.joinedAt);

    const memberships: AuthorizedMembership[] = [];
    for (const member of members) {
      memberships.push({
        membershipId: member.membershipId,
        organizationId: member.organizationId,
        permissions: await this.resolveEffectivePermissionCodes(member),
      });
    }
    return memberships;
  }

  private async resolveEffectivePermissionCodes(member: {
    readonly membershipId: string;
    readonly organizationId: string;
    readonly roleId: string;
  }): Promise<readonly PermissionCode[]> {
    const roleRows = await this.database.db
      .select({ code: permissions.code })
      .from(rolePermissions)
      .innerJoin(permissions, eq(permissions.id, rolePermissions.permissionId))
      .where(
        and(
          eq(rolePermissions.organizationId, member.organizationId),
          eq(rolePermissions.roleId, member.roleId),
        ),
      );

    const directRows = await this.database.db
      .select({ code: permissions.code })
      .from(organizationMemberPermissions)
      .innerJoin(
        permissions,
        eq(permissions.id, organizationMemberPermissions.permissionId),
      )
      .where(
        and(
          eq(
            organizationMemberPermissions.organizationId,
            member.organizationId,
          ),
          eq(organizationMemberPermissions.memberId, member.membershipId),
        ),
      );

    return [...new Set([...roleRows, ...directRows].map((row) => row.code))];
  }

  hasAllPermissions(
    membership: AuthorizedMembership,
    required: readonly PermissionCode[],
  ): boolean {
    const granted = new Set(membership.permissions);
    return required.every((permission) => granted.has(permission));
  }
}
