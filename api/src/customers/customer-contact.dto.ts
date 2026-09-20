import {
  optionalBoolean,
  optionalEmail,
  optionalText,
  requirePositiveInteger,
  requireText,
} from '../validation/domain-validation.js';
import type { CustomerContact } from './customer.types.js';

/**
 * A contact person as every read returns it (`BR-095`).
 *
 * `version` is the optimistic-concurrency token: the edit and removal routes take it back as
 * `expectedVersion`, so a client must be able to read it (`ADR-022` D9).
 */
export interface CustomerContactDto {
  id: string;
  customerId: string;
  firstName: string;
  lastName: string;
  email: string | null;
  phone: string | null;
  role: string | null;
  isPrimary: boolean;
  isBillingContact: boolean;
  isJobContact: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCustomerContactDto {
  firstName: string;
  lastName: string;
  email?: string | null;
  phone?: string | null;
  role?: string | null;
  isPrimary: boolean;
  isBillingContact: boolean;
  isJobContact: boolean;
}

/**
 * The fields a caller supplies to edit a contact person (`BR-095`).
 *
 * The edit is **partial**: a member that is absent is left as it is, exactly as a Customer edit is
 * (`BR-087`). That is what keeps a value no client captures in this slice — the free-text `role` —
 * from being cleared by an edit that never knew about it (`BR-095` notes). Sending an explicit
 * `null` clears an optional value.
 *
 * `expectedVersion` is the version the caller read, and it is **required**: a mutation naming no
 * version would overwrite newer state blindly, which `BR-032` and `BR-086` forbid.
 */
export interface UpdateCustomerContactDto {
  firstName?: string;
  lastName?: string;
  email?: string | null;
  phone?: string | null;
  role?: string | null;
  isPrimary?: boolean;
  expectedVersion: number;
}

/**
 * What a removal carries (`BR-095`).
 *
 * A removal is soft and records the acting member from the request's own authorization, so the only
 * thing the caller states is the version it read (`ADR-022` D8, D9). No reason is required and none
 * is invented (`BR-042`).
 */
export interface RemoveCustomerContactDto {
  expectedVersion: number;
}

export function toCustomerContactDto(
  contact: CustomerContact,
): CustomerContactDto {
  return {
    id: contact.id,
    customerId: contact.customerId,
    firstName: contact.firstName,
    lastName: contact.lastName,
    email: contact.email,
    phone: contact.phone,
    role: contact.role,
    isPrimary: contact.isPrimary,
    isBillingContact: contact.isBillingContact,
    isJobContact: contact.isJobContact,
    version: contact.version,
    createdAt: contact.createdAt.toISOString(),
    updatedAt: contact.updatedAt.toISOString(),
  };
}

/** Validates untrusted input into a `CreateCustomerContactDto`. */
export function parseCreateCustomerContactDto(
  input: unknown,
): CreateCustomerContactDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    firstName: requireText(source.firstName, 'firstName', 100),
    lastName: requireText(source.lastName, 'lastName', 100),
    email: optionalEmail(source.email, 'email'),
    phone: optionalText(source.phone, 'phone', 50),
    role: optionalText(source.role, 'role', 100),
    isPrimary: optionalBoolean(source.isPrimary, 'isPrimary'),
    isBillingContact: optionalBoolean(
      source.isBillingContact,
      'isBillingContact',
    ),
    isJobContact: optionalBoolean(source.isJobContact, 'isJobContact'),
  };
}

/**
 * Validates untrusted input into an `UpdateCustomerContactDto`.
 *
 * A member that is absent leaves the contact's value as it is, so the parser keeps the difference
 * between "absent" and "explicitly cleared" that the partial edit is built on (`BR-095`).
 */
export function parseUpdateCustomerContactDto(
  input: unknown,
): UpdateCustomerContactDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const update: UpdateCustomerContactDto = {
    expectedVersion: requirePositiveInteger(
      source.expectedVersion,
      'expectedVersion',
    ),
  };

  if (source.firstName !== undefined) {
    update.firstName = requireText(source.firstName, 'firstName', 100);
  }
  if (source.lastName !== undefined) {
    update.lastName = requireText(source.lastName, 'lastName', 100);
  }
  if (source.email !== undefined) {
    update.email = optionalEmail(source.email, 'email');
  }
  if (source.phone !== undefined) {
    update.phone = optionalText(source.phone, 'phone', 50);
  }
  if (source.role !== undefined) {
    update.role = optionalText(source.role, 'role', 100);
  }
  if (source.isPrimary !== undefined) {
    update.isPrimary = optionalBoolean(source.isPrimary, 'isPrimary');
  }
  return update;
}

/** Validates untrusted input into a `RemoveCustomerContactDto`. */
export function parseRemoveCustomerContactDto(
  input: unknown,
): RemoveCustomerContactDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    expectedVersion: requirePositiveInteger(
      source.expectedVersion,
      'expectedVersion',
    ),
  };
}
