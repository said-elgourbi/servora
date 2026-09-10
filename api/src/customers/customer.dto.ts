import {
  optionalDate,
  optionalEmail,
  optionalText,
  requireEnum,
  requireText,
} from '../validation/domain-validation.js';
import {
  CUSTOMER_TYPES,
  type CompanyCustomer,
  type Customer,
  type CustomerCompany,
  type CustomerIndividual,
  type CustomerStatus,
  type CustomerType,
  type IndividualCustomer,
} from './customer.types.js';

export interface CustomerDto {
  id: string;
  organizationId: string;
  type: CustomerType;
  displayName: string;
  email: string | null;
  phone: string | null;
  billingEmail: string | null;
  billingPhone: string | null;
  notes: string | null;
  status: CustomerStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CustomerIndividualDto {
  customerId: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string | null;
}

export interface CustomerCompanyDto {
  customerId: string;
  legalName: string;
  businessName: string | null;
  taxNumber: string | null;
}

export interface IndividualCustomerDto {
  customer: CustomerDto;
  individual: CustomerIndividualDto;
}

export interface CompanyCustomerDto {
  customer: CustomerDto;
  company: CustomerCompanyDto;
}

interface CustomerHeaderInput {
  displayName: string;
  email?: string | null;
  phone?: string | null;
  billingEmail?: string | null;
  billingPhone?: string | null;
  notes?: string | null;
}

export interface CreateIndividualCustomerDto extends CustomerHeaderInput {
  type: 'INDIVIDUAL';
  individual: { firstName: string; lastName: string; dateOfBirth?: string | null };
}

export interface CreateCompanyCustomerDto extends CustomerHeaderInput {
  type: 'COMPANY';
  company: { legalName: string; businessName?: string | null; taxNumber?: string | null };
}

export type CreateCustomerDto = CreateIndividualCustomerDto | CreateCompanyCustomerDto;

export function toCustomerDto(customer: Customer): CustomerDto {
  return {
    id: customer.id,
    organizationId: customer.organizationId,
    type: customer.type as CustomerType,
    displayName: customer.displayName,
    email: customer.email,
    phone: customer.phone,
    billingEmail: customer.billingEmail,
    billingPhone: customer.billingPhone,
    notes: customer.notes,
    status: customer.status as CustomerStatus,
    createdAt: customer.createdAt.toISOString(),
    updatedAt: customer.updatedAt.toISOString(),
  };
}

export function toCustomerIndividualDto(
  individual: CustomerIndividual,
): CustomerIndividualDto {
  return {
    customerId: individual.customerId,
    firstName: individual.firstName,
    lastName: individual.lastName,
    dateOfBirth: individual.dateOfBirth,
  };
}

export function toCustomerCompanyDto(company: CustomerCompany): CustomerCompanyDto {
  return {
    customerId: company.customerId,
    legalName: company.legalName,
    businessName: company.businessName,
    taxNumber: company.taxNumber,
  };
}

export function toIndividualCustomerDto(input: IndividualCustomer): IndividualCustomerDto {
  return {
    customer: toCustomerDto(input.customer),
    individual: toCustomerIndividualDto(input.individual),
  };
}

export function toCompanyCustomerDto(input: CompanyCustomer): CompanyCustomerDto {
  return {
    customer: toCustomerDto(input.customer),
    company: toCustomerCompanyDto(input.company),
  };
}

function parseCustomerHeader(source: Record<string, unknown>): CustomerHeaderInput {
  return {
    displayName: requireText(source.displayName, 'displayName', 255),
    email: optionalEmail(source.email, 'email'),
    phone: optionalText(source.phone, 'phone', 50),
    billingEmail: optionalEmail(source.billingEmail, 'billingEmail'),
    billingPhone: optionalText(source.billingPhone, 'billingPhone', 50),
    notes: optionalText(source.notes, 'notes', 4000),
  };
}

/** Validates untrusted input into a discriminated `CreateCustomerDto`. */
export function parseCreateCustomerDto(input: unknown): CreateCustomerDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const type = requireEnum(source.type, CUSTOMER_TYPES, 'type');
  const header = parseCustomerHeader(source);

  if (type === 'INDIVIDUAL') {
    const individual = (source.individual ?? {}) as Record<string, unknown>;
    return {
      ...header,
      type,
      individual: {
        firstName: requireText(individual.firstName, 'individual.firstName', 100),
        lastName: requireText(individual.lastName, 'individual.lastName', 100),
        dateOfBirth: optionalDate(individual.dateOfBirth, 'individual.dateOfBirth'),
      },
    };
  }

  const company = (source.company ?? {}) as Record<string, unknown>;
  return {
    ...header,
    type,
    company: {
      legalName: requireText(company.legalName, 'company.legalName', 255),
      businessName: optionalText(company.businessName, 'company.businessName', 255),
      taxNumber: optionalText(company.taxNumber, 'company.taxNumber', 100),
    },
  };
}

