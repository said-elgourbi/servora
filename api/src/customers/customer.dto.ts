import {
  optionalDate,
  optionalEmail,
  optionalText,
  requireEnum,
  requireText,
} from '../validation/domain-validation.js';
import { DomainValidationError } from '../validation/domain-validation.js';
import {
  CUSTOMER_TYPES,
  type CompanyCustomer,
  type Customer,
  type CustomerCompany,
  type CustomerIndividual,
  type CustomerLanguage,
  type CustomerPreferredContactMethod,
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
  preferredContactMethod: CustomerPreferredContactMethod;
  language: CustomerLanguage;
  status: CustomerStatus;
  deletedAt: string | null;
  deletedByMembershipId: string | null;
  deleteReason: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * A customer list row: the customer plus the derived counts the list renders.
 *
 * `propertyCount` is the customer's **active** Property relationships (`BR-050`); an ended
 * relationship stays in history but is no longer one of the customer's properties. `jobCount`
 * counts the Jobs that belong to the customer, whatever their status (`BR-048`). Neither count is
 * stored on the customer; both are derived from the authoritative tables (`BR-080`).
 */
export interface CustomerSummaryDto extends CustomerDto {
  propertyCount: number;
  jobCount: number;
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
  preferredContactMethod?: CustomerPreferredContactMethod;
  language?: CustomerLanguage;
}

export interface CreateIndividualCustomerDto extends CustomerHeaderInput {
  type: 'INDIVIDUAL';
  individual: {
    firstName: string;
    lastName: string;
    dateOfBirth?: string | null;
  };
}

export interface CreateCompanyCustomerDto extends CustomerHeaderInput {
  type: 'COMPANY';
  company: {
    legalName: string;
    businessName?: string | null;
    taxNumber?: string | null;
  };
}

export type CreateCustomerDto =
  CreateIndividualCustomerDto | CreateCompanyCustomerDto;

export interface UpdateCustomerDto {
  /**
   * The customer's type. Supplying it states the customer's kind, so it is only accepted together
   * with the display name and the required fields of that kind (`BR-087`).
   */
  type?: CustomerType;
  displayName?: string;
  email?: string | null;
  phone?: string | null;
  billingEmail?: string | null;
  billingPhone?: string | null;
  notes?: string | null;
  preferredContactMethod?: CustomerPreferredContactMethod;
  language?: CustomerLanguage;
  status?: CustomerStatus;
  individual?: {
    firstName?: string;
    lastName?: string;
    dateOfBirth?: string | null;
  };
  company?: {
    legalName?: string;
    businessName?: string | null;
    taxNumber?: string | null;
  };
}

export interface ArchiveCustomerDto {
  reason?: string | null;
}

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
    preferredContactMethod:
      customer.preferredContactMethod as CustomerPreferredContactMethod,
    language: customer.language as CustomerLanguage,
    status: customer.status as CustomerStatus,
    deletedAt: customer.deletedAt?.toISOString() ?? null,
    deletedByMembershipId: customer.deletedByMembershipId,
    deleteReason: customer.deleteReason,
    createdAt: customer.createdAt.toISOString(),
    updatedAt: customer.updatedAt.toISOString(),
  };
}

export function toCustomerSummaryDto(
  customer: Customer,
  counts: { propertyCount: number; jobCount: number },
): CustomerSummaryDto {
  return {
    ...toCustomerDto(customer),
    propertyCount: counts.propertyCount,
    jobCount: counts.jobCount,
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

export function toCustomerCompanyDto(
  company: CustomerCompany,
): CustomerCompanyDto {
  return {
    customerId: company.customerId,
    legalName: company.legalName,
    businessName: company.businessName,
    taxNumber: company.taxNumber,
  };
}

export function toIndividualCustomerDto(
  input: IndividualCustomer,
): IndividualCustomerDto {
  return {
    customer: toCustomerDto(input.customer),
    individual: toCustomerIndividualDto(input.individual),
  };
}

export function toCompanyCustomerDto(
  input: CompanyCustomer,
): CompanyCustomerDto {
  return {
    customer: toCustomerDto(input.customer),
    company: toCustomerCompanyDto(input.company),
  };
}

function parseCustomerHeader(
  source: Record<string, unknown>,
): CustomerHeaderInput {
  return {
    displayName: requireText(source.displayName, 'displayName', 255),
    email: optionalEmail(source.email, 'email'),
    phone: optionalText(source.phone, 'phone', 50),
    billingEmail: optionalEmail(source.billingEmail, 'billingEmail'),
    billingPhone: optionalText(source.billingPhone, 'billingPhone', 50),
    notes: optionalText(source.notes, 'notes', 4000),
    preferredContactMethod:
      source.preferredContactMethod === undefined
        ? undefined
        : requireEnum(
            source.preferredContactMethod,
            ['EMAIL', 'PHONE', 'SMS', 'NONE'] as const,
            'preferredContactMethod',
          ),
    language:
      source.language === undefined
        ? undefined
        : requireEnum(source.language, ['en-CA', 'fr-CA'] as const, 'language'),
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
        firstName: requireText(
          individual.firstName,
          'individual.firstName',
          100,
        ),
        lastName: requireText(individual.lastName, 'individual.lastName', 100),
        dateOfBirth: optionalDate(
          individual.dateOfBirth,
          'individual.dateOfBirth',
        ),
      },
    };
  }

  const company = (source.company ?? {}) as Record<string, unknown>;
  return {
    ...header,
    type,
    company: {
      legalName: requireText(company.legalName, 'company.legalName', 255),
      businessName: optionalText(
        company.businessName,
        'company.businessName',
        255,
      ),
      taxNumber: optionalText(company.taxNumber, 'company.taxNumber', 100),
    },
  };
}

/**
 * Validates untrusted input into a partial customer edit, including a type conversion.
 *
 * An edit is partial: a member that is absent is left as it is. Supplying `type` is the one
 * exception — it states which kind of customer this is, so the edit must also state everything that
 * kind requires: the `displayName` the list and detail show, and the new subtype's required fields
 * (`BR-087`). The API never derives the display name from the subtype, exactly as on a create.
 */
export function parseUpdateCustomerDto(input: unknown): UpdateCustomerDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const output: UpdateCustomerDto = {};

  if (source.type !== undefined) {
    output.type = requireEnum(source.type, CUSTOMER_TYPES, 'type');
  }
  const statesType = output.type !== undefined;

  if (source.displayName !== undefined || statesType) {
    output.displayName = requireText(source.displayName, 'displayName', 255);
  }
  if (source.email !== undefined) {
    output.email = optionalEmail(source.email, 'email');
  }
  if (source.phone !== undefined) {
    output.phone = optionalText(source.phone, 'phone', 50);
  }
  if (source.billingEmail !== undefined) {
    output.billingEmail = optionalEmail(source.billingEmail, 'billingEmail');
  }
  if (source.billingPhone !== undefined) {
    output.billingPhone = optionalText(source.billingPhone, 'billingPhone', 50);
  }
  if (source.notes !== undefined) {
    output.notes = optionalText(source.notes, 'notes', 4000);
  }
  if (source.preferredContactMethod !== undefined) {
    output.preferredContactMethod = requireEnum(
      source.preferredContactMethod,
      ['EMAIL', 'PHONE', 'SMS', 'NONE'] as const,
      'preferredContactMethod',
    );
  }
  if (source.language !== undefined) {
    output.language = requireEnum(
      source.language,
      ['en-CA', 'fr-CA'] as const,
      'language',
    );
  }
  if (source.status !== undefined) {
    output.status = requireEnum(
      source.status,
      ['ACTIVE', 'INACTIVE'] as const,
      'status',
    );
  }

  if (statesType) {
    // A conversion must not carry the subtype it is leaving: rejecting a contradictory body is how
    // it fails loudly instead of having a payload silently dropped (`BR-042`, `BR-087`).
    const leaving = output.type === 'INDIVIDUAL' ? 'company' : 'individual';
    if (source[leaving] !== undefined) {
      throw new DomainValidationError([
        `${leaving} must not be supplied for a ${output.type} customer`,
      ]);
    }

    if (output.type === 'INDIVIDUAL') {
      const individual = (source.individual ?? {}) as Record<string, unknown>;
      output.individual = {
        firstName: requireText(
          individual.firstName,
          'individual.firstName',
          100,
        ),
        lastName: requireText(individual.lastName, 'individual.lastName', 100),
        dateOfBirth: optionalDate(
          individual.dateOfBirth,
          'individual.dateOfBirth',
        ),
      };
    } else {
      const company = (source.company ?? {}) as Record<string, unknown>;
      output.company = {
        legalName: requireText(company.legalName, 'company.legalName', 255),
        businessName: optionalText(
          company.businessName,
          'company.businessName',
          255,
        ),
        taxNumber: optionalText(company.taxNumber, 'company.taxNumber', 100),
      };
    }

    return output;
  }

  if (source.individual !== undefined) {
    const individual = source.individual as Record<string, unknown>;
    output.individual = {};
    if (individual.firstName !== undefined) {
      output.individual.firstName = requireText(
        individual.firstName,
        'individual.firstName',
        100,
      );
    }
    if (individual.lastName !== undefined) {
      output.individual.lastName = requireText(
        individual.lastName,
        'individual.lastName',
        100,
      );
    }
    if (individual.dateOfBirth !== undefined) {
      output.individual.dateOfBirth = optionalDate(
        individual.dateOfBirth,
        'individual.dateOfBirth',
      );
    }
  }

  if (source.company !== undefined) {
    const company = source.company as Record<string, unknown>;
    output.company = {};
    if (company.legalName !== undefined) {
      output.company.legalName = requireText(
        company.legalName,
        'company.legalName',
        255,
      );
    }
    if (company.businessName !== undefined) {
      output.company.businessName = optionalText(
        company.businessName,
        'company.businessName',
        255,
      );
    }
    if (company.taxNumber !== undefined) {
      output.company.taxNumber = optionalText(
        company.taxNumber,
        'company.taxNumber',
        100,
      );
    }
  }

  return output;
}

export function parseArchiveCustomerDto(input: unknown): ArchiveCustomerDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return { reason: optionalText(source.reason, 'reason', 1000) };
}
