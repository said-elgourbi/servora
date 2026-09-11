import type { InferInsertModel, InferSelectModel } from 'drizzle-orm';
import {
  organizationMemberPermissions,
  organizationMembers,
  organizationRoles,
  permissions,
  rolePermissions,
} from '../database/schema.js';

// Stable system role codes identify the default role templates. Organization
// roles carry bilingual display names, and authorization uses permissions.
export const SYSTEM_ROLE_CODES = ['MANAGER', 'TECHNICIAN'] as const;
export type SystemRoleCode = (typeof SYSTEM_ROLE_CODES)[number];

export const MEMBER_STATUSES = ['ACTIVE', 'INACTIVE'] as const;
export type MemberStatus = (typeof MEMBER_STATUSES)[number];

/**
 * Links a `User` to an `Organization` with exactly one organization role.
 * Effective permissions are the role permissions plus optional direct member
 * permissions.
 */
export type OrganizationMember = InferSelectModel<typeof organizationMembers>;
export type NewOrganizationMember = InferInsertModel<
  typeof organizationMembers
>;

export type Permission = InferSelectModel<typeof permissions>;
export type NewPermission = InferInsertModel<typeof permissions>;

export type OrganizationRole = InferSelectModel<typeof organizationRoles>;
export type NewOrganizationRole = InferInsertModel<typeof organizationRoles>;

export type RolePermission = InferSelectModel<typeof rolePermissions>;
export type NewRolePermission = InferInsertModel<typeof rolePermissions>;

export type OrganizationMemberPermission = InferSelectModel<
  typeof organizationMemberPermissions
>;
export type NewOrganizationMemberPermission = InferInsertModel<
  typeof organizationMemberPermissions
>;
