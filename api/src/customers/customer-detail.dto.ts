import {
  toCustomerCompanyDto,
  toCustomerIndividualDto,
  toCustomerSummaryDto,
  type CustomerCompanyDto,
  type CustomerIndividualDto,
  type CustomerSummaryDto,
} from './customer.dto.js';
import {
  toCustomerContactDto,
  type CustomerContactDto,
} from './customer-contact.dto.js';
import type {
  Customer,
  CustomerCompany,
  CustomerContact,
  CustomerIndividual,
  Job,
  Property,
} from './customer.types.js';

/**
 * The Job status vocabulary the API exchanges (`BR-058`, `BR-041`).
 *
 * It is the backend's single definition; clients present localized labels for these codes and must
 * not invent a parallel vocabulary.
 */
export const JOB_STATUSES = [
  'NEW',
  'SCHEDULED',
  'IN_PROGRESS',
  'PENDING_REVIEW',
  'COMPLETED',
  'CANCELED',
] as const;
export type JobStatus = (typeof JOB_STATUSES)[number];

/** The assignment role codes a technician can hold on a Visit (`BR-068`). */
export const ASSIGNMENT_ROLE_CODES = ['LEAD', 'TECHNICIAN'] as const;
export type AssignmentRoleCode = (typeof ASSIGNMENT_ROLE_CODES)[number];

/**
 * A customer's detail: its header with the derived counts the sections render, its required subtype
 * record and its contacts (`BR-023`, `BR-081`).
 */
export interface CustomerDetail {
  readonly customer: Customer;
  readonly individual: CustomerIndividual | null;
  readonly company: CustomerCompany | null;
  readonly propertyCount: number;
  readonly jobCount: number;
  readonly contacts: CustomerContact[];
}

/** One of the customer's active Properties with its derived row values (`BR-081`). */
export interface CustomerPropertySummary {
  readonly property: Property;
  /** Jobs associated with the Property, whatever their status (`BR-081`). */
  readonly jobCount: number;
  /** Scheduled start of the most recent `COMPLETED` Visit, or `null` (`BR-081`). */
  readonly lastServiceAt: Date | null;
}

/** A technician currently assigned to a Visit, as the Job row renders it (`BR-068`, `BR-081`). */
export interface CustomerJobTechnician {
  readonly membershipId: string;
  /** Resolved from the member's profile; `null` when the member has no profile yet. */
  readonly name: string | null;
  readonly roleCode: AssignmentRoleCode;
}

/** One of the customer's Jobs with the selected Visit's derived values (`BR-081`). */
export interface CustomerJobSummary {
  readonly job: Job;
  /** The selected Visit, or `null` when the Job has no eligible Visit (`BR-081`). */
  readonly selectedVisit: { readonly scheduledStart: Date } | null;
  /** The selected Visit's technicians, Lead first; empty when unassigned (`BR-081`). */
  readonly technicians: CustomerJobTechnician[];
}

export interface CustomerDetailDto {
  customer: CustomerSummaryDto;
  individual: CustomerIndividualDto | null;
  company: CustomerCompanyDto | null;
  contacts: CustomerContactDto[];
}

export interface CustomerPropertyDto {
  id: string;
  name: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  province: string;
  postalCode: string;
  country: string;
  jobCount: number;
  lastServiceAt: string | null;
}

/**
 * The Job's address snapshot (`BR-056`).
 *
 * The snapshot is stored as JSON, so each field is taken only when it really is a string; an
 * unreadable snapshot is reported as no address rather than as a partly invented one.
 */
export interface CustomerJobAddressDto {
  propertyName: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  province: string | null;
  postalCode: string | null;
  country: string | null;
}

export interface CustomerJobTechnicianDto {
  membershipId: string;
  /** Resolved from the member's profile; `null` when the member has no profile yet. */
  name: string | null;
  roleCode: AssignmentRoleCode;
}

export interface CustomerJobDto {
  id: string;
  jobNumber: number;
  title: string;
  description: string | null;
  typeCode: string | null;
  status: JobStatus;
  propertyId: string | null;
  propertyAddress: CustomerJobAddressDto | null;
  /** The selected Visit's scheduled start, or `null` when the Job has none (`BR-081`). */
  scheduledStart: string | null;
  technicians: CustomerJobTechnicianDto[];
}

export function toCustomerDetailDto(detail: CustomerDetail): CustomerDetailDto {
  return {
    customer: toCustomerSummaryDto(detail.customer, {
      propertyCount: detail.propertyCount,
      jobCount: detail.jobCount,
    }),
    individual:
      detail.individual === null
        ? null
        : toCustomerIndividualDto(detail.individual),
    company:
      detail.company === null ? null : toCustomerCompanyDto(detail.company),
    contacts: detail.contacts.map(toCustomerContactDto),
  };
}

export function toCustomerPropertyDto(
  summary: CustomerPropertySummary,
): CustomerPropertyDto {
  const property = summary.property;
  return {
    id: property.id,
    name: property.name,
    addressLine1: property.addressLine1,
    addressLine2: property.addressLine2,
    city: property.city,
    province: property.province,
    postalCode: property.postalCode,
    country: property.country,
    jobCount: summary.jobCount,
    lastServiceAt: summary.lastServiceAt?.toISOString() ?? null,
  };
}

export function toCustomerJobDto(summary: CustomerJobSummary): CustomerJobDto {
  const job = summary.job;
  return {
    id: job.id,
    jobNumber: job.jobNumber,
    title: job.title,
    description: job.description,
    typeCode: job.typeCode,
    status: job.status as JobStatus,
    propertyId: job.propertyId,
    propertyAddress: toCustomerJobAddressDto(job.propertyAddressSnapshot),
    scheduledStart: summary.selectedVisit?.scheduledStart.toISOString() ?? null,
    technicians: summary.technicians.map((technician) => ({
      membershipId: technician.membershipId,
      name: technician.name,
      roleCode: technician.roleCode,
    })),
  };
}

function toCustomerJobAddressDto(
  snapshot: unknown,
): CustomerJobAddressDto | null {
  if (typeof snapshot !== 'object' || snapshot === null) {
    return null;
  }
  const source = snapshot as Record<string, unknown>;
  return {
    propertyName: textValue(source.propertyName),
    addressLine1: textValue(source.addressLine1),
    addressLine2: textValue(source.addressLine2),
    city: textValue(source.city),
    province: textValue(source.province),
    postalCode: textValue(source.postalCode),
    country: textValue(source.country),
  };
}

function textValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}
