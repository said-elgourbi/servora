import {
  optionalEmail,
  optionalText,
  requireText,
} from '../validation/domain-validation.js';
import type { Organization, OrganizationStatus } from './organization.types.js';

/** Public API shape of an organization. */
export interface OrganizationDto {
  id: string;
  name: string;
  email: string | null;
  phone: string | null;
  status: OrganizationStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateOrganizationDto {
  name: string;
  email?: string | null;
  phone?: string | null;
}

export function toOrganizationDto(organization: Organization): OrganizationDto {
  return {
    id: organization.id,
    name: organization.name,
    email: organization.email,
    phone: organization.phone,
    status: organization.status as OrganizationStatus,
    createdAt: organization.createdAt.toISOString(),
    updatedAt: organization.updatedAt.toISOString(),
  };
}

/** Validates and normalizes untrusted input into a `CreateOrganizationDto`. */
export function parseCreateOrganizationDto(
  input: unknown,
): CreateOrganizationDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    name: requireText(source.name, 'name', 200),
    email: optionalEmail(source.email, 'email'),
    phone: optionalText(source.phone, 'phone', 50),
  };
}
