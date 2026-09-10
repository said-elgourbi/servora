import type { InferInsertModel, InferSelectModel } from 'drizzle-orm';
import { organizationMembers } from '../database/schema.js';

// Foundation roles (BR-003). Custom, organisation-specific roles are a later
// feature; the role column stays a stable code, never a display label.
export const MEMBER_ROLES = ['MANAGER', 'TECHNICIAN'] as const;
export type MemberRole = (typeof MEMBER_ROLES)[number];

export const MEMBER_STATUSES = ['ACTIVE', 'INACTIVE'] as const;
export type MemberStatus = (typeof MEMBER_STATUSES)[number];

/**
 * Links a `User` to an `Organization` with exactly one role. A user's role is
 * never stored on `User` or `UserProfile` — this table is the single source of
 * the (user, organization, role) relationship.
 */
export type OrganizationMember = InferSelectModel<typeof organizationMembers>;
export type NewOrganizationMember = InferInsertModel<typeof organizationMembers>;
