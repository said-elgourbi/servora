import { HttpException, HttpStatus, Injectable } from '@nestjs/common';
import { and, asc, eq, inArray, sql } from 'drizzle-orm';
import permissionCatalogue from '@servora/shared-types/permissions.json' with { type: 'json' };
import { DatabaseService } from '../database/database.service.js';
import {
  organizationMemberPermissions,
  organizationMembers,
  organizationRoles,
  permissions,
  rolePermissions,
  users,
  userProfiles,
} from '../database/schema.js';
import { isUniqueViolation } from '../database/unique-violation.js';
import { memberName } from '../members/member-name.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type {
  AssignableTechnician,
  ManagedTechnicianDto,
  TechnicianManagementDto,
  UpdateTechnicianRequest,
} from './technician.dto.js';

const supportedPermissionCodes = Object.keys(permissionCatalogue);
const supportedPermissionCodeSet = new Set(supportedPermissionCodes);

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

  async managementInOrganization(scope: OrganizationScope): Promise<TechnicianManagementDto> {
    const rows = await this.db
      .select({
        membershipId: organizationMembers.id,
        userId: organizationMembers.userId,
        roleId: organizationMembers.roleId,
        status: organizationMembers.status,
        email: users.email,
        userPhone: users.phone,
        firstName: userProfiles.firstName,
        lastName: userProfiles.lastName,
        profilePhone: userProfiles.phone,
      })
      .from(organizationMembers)
      .innerJoin(organizationRoles, eq(organizationRoles.id, organizationMembers.roleId))
      .innerJoin(users, eq(users.id, organizationMembers.userId))
      .leftJoin(userProfiles, eq(userProfiles.userId, organizationMembers.userId))
      .where(and(
        eq(organizationMembers.organizationId, scope.organizationId),
        eq(organizationMembers.status, 'ACTIVE'),
        eq(organizationRoles.systemCode, 'TECHNICIAN'),
      ));

    const roleIds = [...new Set(rows.map((row) => row.roleId))];
    const memberIds = rows.map((row) => row.membershipId);
    const [roleRows, directRows, permissionRows] = await Promise.all([
      roleIds.length ? this.db.select({ roleId: rolePermissions.roleId, code: permissions.code })
        .from(rolePermissions).innerJoin(permissions, eq(permissions.id, rolePermissions.permissionId))
        .where(and(eq(rolePermissions.organizationId, scope.organizationId), inArray(rolePermissions.roleId, roleIds))) : [],
      memberIds.length ? this.db.select({ memberId: organizationMemberPermissions.memberId, code: permissions.code })
        .from(organizationMemberPermissions).innerJoin(permissions, eq(permissions.id, organizationMemberPermissions.permissionId))
        .where(and(eq(organizationMemberPermissions.organizationId, scope.organizationId), inArray(organizationMemberPermissions.memberId, memberIds))) : [],
      this.db.select({ code: permissions.code, nameEn: permissions.nameEn, nameFr: permissions.nameFr,
        descriptionEn: permissions.descriptionEn, descriptionFr: permissions.descriptionFr })
        .from(permissions).where(inArray(permissions.code, supportedPermissionCodes)).orderBy(asc(permissions.code)),
    ]);

    const byRole = new Map<string, string[]>();
    for (const row of roleRows) if (supportedPermissionCodeSet.has(row.code)) byRole.set(row.roleId, [...(byRole.get(row.roleId) ?? []), row.code]);
    const byMember = new Map<string, string[]>();
    for (const row of directRows) if (supportedPermissionCodeSet.has(row.code)) byMember.set(row.memberId, [...(byMember.get(row.memberId) ?? []), row.code]);
    const technicians: ManagedTechnicianDto[] = rows.map((row) => ({
      membershipId: row.membershipId,
      userId: row.userId,
      email: row.email,
      phone: row.profilePhone ?? row.userPhone,
      firstName: row.firstName ?? '',
      lastName: row.lastName ?? '',
      status: row.status,
      rolePermissionCodes: byRole.get(row.roleId) ?? [],
      directPermissionCodes: byMember.get(row.membershipId) ?? [],
    })).sort((a, b) => `${a.firstName} ${a.lastName}`.localeCompare(`${b.firstName} ${b.lastName}`));
    return { technicians, permissions: permissionRows };
  }

  async updateTechnicianInOrganization(
    scope: OrganizationScope,
    membershipId: string,
    actorMembershipId: string,
    input: UpdateTechnicianRequest,
  ): Promise<ManagedTechnicianDto> {
    try {
      await this.db.transaction(async (tx) => {
        const [member] = await tx.select({ membershipId: organizationMembers.id, userId: organizationMembers.userId,
          roleId: organizationMembers.roleId })
          .from(organizationMembers)
          .innerJoin(organizationRoles, eq(organizationRoles.id, organizationMembers.roleId))
          .where(and(eq(organizationMembers.id, membershipId), eq(organizationMembers.organizationId, scope.organizationId),
            eq(organizationMembers.status, 'ACTIVE'), eq(organizationRoles.systemCode, 'TECHNICIAN')))
          .for('update');
        if (!member) throw technicianNotFound();

        if (input.directPermissionCodes.some((code) => !supportedPermissionCodeSet.has(code))) throw invalidPermission();
        const permissionRows = input.directPermissionCodes.length
          ? await tx.select({ id: permissions.id, code: permissions.code }).from(permissions)
            .where(inArray(permissions.code, [...input.directPermissionCodes]))
          : [];
        if (permissionRows.length !== input.directPermissionCodes.length) throw invalidPermission();
        const inheritedRows = await tx.select({ code: permissions.code }).from(rolePermissions)
          .innerJoin(permissions, eq(permissions.id, rolePermissions.permissionId))
          .where(and(eq(rolePermissions.organizationId, scope.organizationId), eq(rolePermissions.roleId, member.roleId)));
        const inherited = new Set(inheritedRows.map((row) => row.code));
        const existingUncatalogued = await tx.select({
          permissionId: organizationMemberPermissions.permissionId,
          grantedByMembershipId: organizationMemberPermissions.grantedByMembershipId,
          code: permissions.code,
        }).from(organizationMemberPermissions)
          .innerJoin(permissions, eq(permissions.id, organizationMemberPermissions.permissionId))
          .where(and(eq(organizationMemberPermissions.organizationId, scope.organizationId),
            eq(organizationMemberPermissions.memberId, member.membershipId)));

        const now = new Date();
        await tx.update(users).set({ email: input.email, phone: input.phone, updatedAt: now }).where(eq(users.id, member.userId));
        await tx.insert(userProfiles).values({
          userId: member.userId,
          firstName: input.firstName,
          lastName: input.lastName,
          displayName: `${input.firstName} ${input.lastName}`,
          phone: input.phone,
        }).onConflictDoUpdate({
          target: userProfiles.userId,
          set: { firstName: input.firstName, lastName: input.lastName,
            displayName: `${input.firstName} ${input.lastName}`, phone: input.phone, updatedAt: now },
        });
        await tx.delete(organizationMemberPermissions).where(and(
          eq(organizationMemberPermissions.organizationId, scope.organizationId),
          eq(organizationMemberPermissions.memberId, member.membershipId),
        ));
        const extras = permissionRows.filter((permission) => !inherited.has(permission.code)).map((permission) => ({
          organizationId: scope.organizationId,
          memberId: member.membershipId,
          permissionId: permission.id,
          grantedByMembershipId: actorMembershipId,
        }));
        const preserve = existingUncatalogued.filter((permission) => !supportedPermissionCodeSet.has(permission.code)).map((permission) => ({
          organizationId: scope.organizationId,
          memberId: member.membershipId,
          permissionId: permission.permissionId,
          grantedByMembershipId: permission.grantedByMembershipId,
        }));
        if (extras.length + preserve.length) await tx.insert(organizationMemberPermissions).values([...extras, ...preserve]);
      });
    } catch (error) {
      if (isUniqueViolation(error)) throw emailInUse();
      throw error;
    }

    const updated = (await this.managementInOrganization(scope)).technicians.find((row) => row.membershipId === membershipId);
    if (!updated) throw technicianNotFound();
    return updated;
  }
}

function technicianNotFound() {
  return new HttpException({ statusCode: HttpStatus.NOT_FOUND, code: 'TECHNICIAN_NOT_FOUND', message: 'Technician was not found.' }, HttpStatus.NOT_FOUND);
}
function invalidPermission() {
  return new HttpException({ statusCode: HttpStatus.BAD_REQUEST, code: 'INVALID_TECHNICIAN_PERMISSION', message: 'A selected permission is not available.' }, HttpStatus.BAD_REQUEST);
}
function emailInUse() {
  return new HttpException({ statusCode: HttpStatus.CONFLICT, code: 'TECHNICIAN_EMAIL_IN_USE', message: 'This email address is already in use.' }, HttpStatus.CONFLICT);
}
