import { requireEnum, requireText } from '../validation/domain-validation.js';
import {
  MEMBER_ROLES,
  MEMBER_STATUSES,
  type MemberRole,
  type MemberStatus,
  type OrganizationMember,
} from './organization-member.types.js';

export interface OrganizationMemberDto {
  id: string;
  organizationId: string;
  userId: string;
  role: MemberRole;
  status: MemberStatus;
  joinedAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface AddOrganizationMemberDto {
  userId: string;
  role: MemberRole;
  status?: MemberStatus;
}

export function toOrganizationMemberDto(
  member: OrganizationMember,
): OrganizationMemberDto {
  return {
    id: member.id,
    organizationId: member.organizationId,
    userId: member.userId,
    role: member.role as MemberRole,
    status: member.status as MemberStatus,
    joinedAt: member.joinedAt.toISOString(),
    createdAt: member.createdAt.toISOString(),
    updatedAt: member.updatedAt.toISOString(),
  };
}

/** Validates untrusted input into an `AddOrganizationMemberDto`. */
export function parseAddOrganizationMemberDto(
  input: unknown,
): AddOrganizationMemberDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    userId: requireText(source.userId, 'userId', 36),
    role: requireEnum(source.role, MEMBER_ROLES, 'role'),
    status:
      source.status === undefined
        ? undefined
        : requireEnum(source.status, MEMBER_STATUSES, 'status'),
  };
}
