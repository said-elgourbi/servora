import {
  DomainValidationError,
  optionalText,
  optionalUuid,
  requireEmail,
  requireText,
} from '../validation/domain-validation.js';

/*
 * The technician read that makes assignment possible (`BR-024`, `BR-068`).
 *
 * Assigning field work means choosing from the organization's technicians, so the assign sheet needs
 * the list. This is deliberately the smallest read that serves that need: the technician's stable
 * membership id and their name. Manager contact editing is a separate projection in
 * `docs/api/technicians.md`; skills, territory, availability and lifecycle rules remain outside
 * this assignment DTO.
 */

/** One technician a Job's Visit may be assigned to. */
export interface AssignableTechnicianDto {
  /** The organization membership id an assignment names (`BR-068`). */
  membershipId: string;
  /** Resolved from the member's profile; `null` when the member has no profile yet. */
  name: string | null;
}

/** One technician as the read resolves them. */
export interface AssignableTechnician {
  readonly membershipId: string;
  readonly name: string | null;
}

export interface TechnicianPermissionDto {
  readonly code: string;
  readonly nameEn: string;
  readonly nameFr: string;
  readonly descriptionEn: string;
  readonly descriptionFr: string;
}

export interface ManagedTechnicianDto {
  readonly membershipId: string;
  readonly userId: string;
  readonly email: string;
  readonly phone: string | null;
  readonly firstName: string;
  readonly lastName: string;
  readonly status: string;
  readonly rolePermissionCodes: readonly string[];
  readonly directPermissionCodes: readonly string[];
}

export interface TechnicianManagementDto {
  readonly technicians: readonly ManagedTechnicianDto[];
  readonly permissions: readonly TechnicianPermissionDto[];
}

export interface UpdateTechnicianRequest {
  readonly email: string;
  readonly phone: string | null;
  readonly firstName: string;
  readonly lastName: string;
  readonly directPermissionCodes: readonly string[];
}

export function parseTechnicianMembershipId(value: string): string {
  const id = optionalUuid(value, 'membershipId');
  if (!id) throw new DomainValidationError(['membershipId must be a UUID']);
  return id;
}

export function parseUpdateTechnicianRequest(input: unknown): UpdateTechnicianRequest {
  const source = (input ?? {}) as Record<string, unknown>;
  const codes = source.directPermissionCodes;
  if (!Array.isArray(codes) || codes.length > 300 || codes.some((code) => typeof code !== 'string')) {
    throw new DomainValidationError(['directPermissionCodes must be an array of permission codes']);
  }
  return {
    email: requireEmail(source.email, 'email'),
    phone: optionalText(source.phone, 'phone', 50),
    firstName: requireText(source.firstName, 'firstName', 100),
    lastName: requireText(source.lastName, 'lastName', 100),
    directPermissionCodes: [...new Set(codes.map((code) => requireText(code, 'permissionCode', 100)))],
  };
}

export function toAssignableTechnicianDto(
  technician: AssignableTechnician,
): AssignableTechnicianDto {
  return {
    membershipId: technician.membershipId,
    name: technician.name,
  };
}
