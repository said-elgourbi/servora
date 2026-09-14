import {
  ASSIGNMENT_ROLE_CODES,
  JOB_STATUSES,
  type AssignmentRoleCode,
  type JobStatus,
} from '../jobs/job.types.js';
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
import {
  readAddressSnapshot,
  type AddressSnapshot,
} from '../address/address-snapshot.js';
import type { AssignedTechnician } from '../jobs/visit-assignment.js';
import type { PropertyStatus } from './property.dto.js';
import type {
  Customer,
  CustomerCompany,
  CustomerContact,
  CustomerIndividual,
  Job,
  Property,
} from './customer.types.js';

/**
 * The Job status and assignment-role vocabularies the API exchanges (`BR-058`, `BR-068`, `BR-041`).
 *
 * The Job & Visit domain declares them once in `jobs/job.types.ts`; they are re-exported here because
 * the customer-detail projection was their first consumer. Clients present localized labels for these
 * codes and must not invent a parallel vocabulary.
 */
export { ASSIGNMENT_ROLE_CODES, JOB_STATUSES };
export type { AssignmentRoleCode, JobStatus };

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
export type CustomerJobTechnician = AssignedTechnician;

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
  /**
   * The Property's lifecycle state (`BR-082`).
   *
   * The default projection is `ACTIVE` (`BR-081`); the state is carried so an explicit archived
   * request can be presented with the lifecycle it actually has.
   */
  status: PropertyStatus;
  jobCount: number;
  lastServiceAt: string | null;
}

/**
 * The Job's address snapshot (`BR-056`).
 *
 * The snapshot is stored as JSON, so each field is taken only when it really is a string; an
 * unreadable snapshot is reported as no address rather than as a partly invented one.
 */
/**
 * A Job's preserved address snapshot (`BR-056`).
 *
 * The shape and the reading of a stored snapshot are defined once, in
 * `address/address-snapshot.ts`, because the Job row and the manager-home Visit row both project it
 * (`BR-041`).
 */
export type CustomerJobAddressDto = AddressSnapshot;

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
    status: property.status as PropertyStatus,
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
    propertyAddress: readAddressSnapshot(job.propertyAddressSnapshot),
    scheduledStart: summary.selectedVisit?.scheduledStart.toISOString() ?? null,
    technicians: summary.technicians.map((technician) => ({
      membershipId: technician.membershipId,
      name: technician.name,
      roleCode: technician.roleCode,
    })),
  };
}
