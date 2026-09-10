import {
  optionalBoolean,
  optionalEmail,
  optionalText,
  requireText,
} from '../validation/domain-validation.js';
import type { CustomerContact } from './customer.types.js';

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

export function toCustomerContactDto(contact: CustomerContact): CustomerContactDto {
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
    createdAt: contact.createdAt.toISOString(),
    updatedAt: contact.updatedAt.toISOString(),
  };
}

/** Validates untrusted input into a `CreateCustomerContactDto`. */
export function parseCreateCustomerContactDto(input: unknown): CreateCustomerContactDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    firstName: requireText(source.firstName, 'firstName', 100),
    lastName: requireText(source.lastName, 'lastName', 100),
    email: optionalEmail(source.email, 'email'),
    phone: optionalText(source.phone, 'phone', 50),
    role: optionalText(source.role, 'role', 100),
    isPrimary: optionalBoolean(source.isPrimary, 'isPrimary'),
    isBillingContact: optionalBoolean(source.isBillingContact, 'isBillingContact'),
    isJobContact: optionalBoolean(source.isJobContact, 'isJobContact'),
  };
}
