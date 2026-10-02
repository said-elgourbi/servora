import type { AddressSnapshot } from '../address/address-snapshot.js';
import {
  DomainValidationError,
  optionalInstant,
  optionalPositiveInteger,
  optionalText,
  optionalUuid,
  requireEnum,
  requireText,
} from '../validation/domain-validation.js';
import { VISIT_OUTCOME_CODES, type VisitOutcomeCode } from './job.types.js';

function fail(field: string, rule: string): never {
  throw new DomainValidationError([`${field} ${rule}`]);
}

function requireInstant(value: unknown, field: string): Date {
  const instant = optionalInstant(value, field);
  if (instant === null) {
    fail(field, 'is required');
  }
  return new Date(instant);
}

export type AdHocWorkReportStatus =
  'PENDING' | 'LINKED' | 'CONVERTED' | 'REJECTED';

export interface AdHocWorkReportContext {
  readonly customerName: string | null;
  readonly propertyAddress: AddressSnapshot | null;
  readonly knownJobNumber: number | null;
  readonly knownJobTitle: string | null;
  readonly createdJobNumber: number | null;
  readonly createdJobTitle: string | null;
}

export interface AdHocWorkReportDto {
  readonly id: string;
  readonly reportingTechnicianMembershipId: string;
  readonly customerId: string | null;
  readonly customerName: string | null;
  readonly propertyId: string | null;
  readonly propertyAddress: AddressSnapshot | null;
  readonly knownJobId: string | null;
  readonly knownJobNumber: number | null;
  readonly knownJobTitle: string | null;
  readonly workStartedAt: string;
  readonly workEndedAt: string;
  readonly outcomeCode: VisitOutcomeCode;
  readonly summary: string;
  readonly notes: string | null;
  readonly reportedCustomerName: string | null;
  readonly reportedCustomerPhone: string | null;
  readonly reportedCustomerAddress: string | null;
  readonly status: AdHocWorkReportStatus;
  readonly reviewerMembershipId: string | null;
  readonly reviewedAt: string | null;
  readonly reviewNote: string | null;
  readonly createdJobId: string | null;
  readonly createdJobNumber: number | null;
  readonly createdJobTitle: string | null;
  readonly createdVisitId: string | null;
  readonly version: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface SubmitAdHocWorkReportDto {
  readonly customerId: string | null;
  readonly propertyId: string | null;
  readonly knownJobId: string | null;
  readonly workStartedAt: Date;
  readonly workEndedAt: Date;
  readonly outcomeCode: VisitOutcomeCode;
  readonly summary: string;
  readonly notes: string | null;
  readonly reportedCustomerName: string | null;
  readonly reportedCustomerPhone: string | null;
  readonly reportedCustomerAddress: string | null;
  readonly clientOperationId: string | null;
}

export interface AdHocWorkReportReviewDto {
  readonly note: string | null;
  readonly expectedStatus: AdHocWorkReportStatus | null;
  readonly expectedVersion: number | null;
}

export interface LinkAdHocWorkReportDto extends AdHocWorkReportReviewDto {
  readonly jobId: string;
}

export interface ConvertAdHocWorkReportDto extends AdHocWorkReportReviewDto {
  readonly title: string;
  readonly description: string | null;
}

export function parseSubmitAdHocWorkReportDto(
  input: unknown,
): SubmitAdHocWorkReportDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const workStartedAt = requireInstant(source.workStartedAt, 'workStartedAt');
  const workEndedAt = requireInstant(source.workEndedAt, 'workEndedAt');
  if (workEndedAt.getTime() <= workStartedAt.getTime()) {
    fail('workEndedAt', 'must be after workStartedAt');
  }
  return {
    customerId: optionalUuid(source.customerId, 'customerId'),
    propertyId: optionalUuid(source.propertyId, 'propertyId'),
    knownJobId: optionalUuid(source.knownJobId, 'knownJobId'),
    workStartedAt,
    workEndedAt,
    outcomeCode: requireEnum(
      source.outcomeCode,
      VISIT_OUTCOME_CODES,
      'outcomeCode',
    ),
    summary: requireText(source.summary, 'summary', 2000),
    notes: optionalText(source.notes, 'notes', 4000),
    reportedCustomerName: optionalText(
      source.reportedCustomerName,
      'reportedCustomerName',
      255,
    ),
    reportedCustomerPhone: optionalText(
      source.reportedCustomerPhone,
      'reportedCustomerPhone',
      50,
    ),
    reportedCustomerAddress: optionalText(
      source.reportedCustomerAddress,
      'reportedCustomerAddress',
      2000,
    ),
    clientOperationId: optionalUuid(
      source.clientOperationId,
      'clientOperationId',
    ),
  };
}

export function parseLinkAdHocWorkReportDto(
  input: unknown,
): LinkAdHocWorkReportDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const jobId = optionalUuid(source.jobId, 'jobId');
  if (jobId === null) {
    fail('jobId', 'is required');
  }
  return {
    ...parseAdHocWorkReportReviewDto(input),
    jobId,
  };
}

export function parseConvertAdHocWorkReportDto(
  input: unknown,
): ConvertAdHocWorkReportDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    ...parseAdHocWorkReportReviewDto(input),
    title: requireText(source.title, 'title', 255),
    description: optionalText(source.description, 'description', 4000),
  };
}

function parseAdHocWorkReportReviewDto(
  input: unknown,
): AdHocWorkReportReviewDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    note: optionalText(source.note, 'note', 2000),
    expectedStatus: optionalAdHocWorkReportStatus(source.expectedStatus),
    expectedVersion: optionalPositiveInteger(
      source.expectedVersion,
      'expectedVersion',
    ),
  };
}

function optionalAdHocWorkReportStatus(
  value: unknown,
): AdHocWorkReportStatus | null {
  if (value === undefined || value === null) {
    return null;
  }
  return requireEnum(
    value,
    ['PENDING', 'LINKED', 'CONVERTED', 'REJECTED'] as const,
    'expectedStatus',
  );
}

export function toAdHocWorkReportDto(
  report: {
    id: string;
    reportingTechnicianMembershipId: string;
    customerId: string | null;
    propertyId: string | null;
    knownJobId: string | null;
    workStartedAt: Date;
    workEndedAt: Date;
    outcomeCode: string;
    summary: string;
    notes: string | null;
    reportedCustomerName: string | null;
    reportedCustomerPhone: string | null;
    reportedCustomerAddress: string | null;
    status: string;
    reviewerMembershipId: string | null;
    reviewedAt: Date | null;
    reviewNote: string | null;
    createdJobId: string | null;
    createdVisitId: string | null;
    version: number;
    createdAt: Date;
    updatedAt: Date;
  },
  context: AdHocWorkReportContext,
): AdHocWorkReportDto {
  return {
    id: report.id,
    reportingTechnicianMembershipId: report.reportingTechnicianMembershipId,
    customerId: report.customerId,
    customerName: context.customerName,
    propertyId: report.propertyId,
    propertyAddress: context.propertyAddress,
    knownJobId: report.knownJobId,
    knownJobNumber: context.knownJobNumber,
    knownJobTitle: context.knownJobTitle,
    workStartedAt: report.workStartedAt.toISOString(),
    workEndedAt: report.workEndedAt.toISOString(),
    outcomeCode: report.outcomeCode as VisitOutcomeCode,
    summary: report.summary,
    notes: report.notes,
    reportedCustomerName: report.reportedCustomerName,
    reportedCustomerPhone: report.reportedCustomerPhone,
    reportedCustomerAddress: report.reportedCustomerAddress,
    status: report.status as AdHocWorkReportStatus,
    reviewerMembershipId: report.reviewerMembershipId,
    reviewedAt: report.reviewedAt?.toISOString() ?? null,
    reviewNote: report.reviewNote,
    createdJobId: report.createdJobId,
    createdJobNumber: context.createdJobNumber,
    createdJobTitle: context.createdJobTitle,
    createdVisitId: report.createdVisitId,
    version: report.version,
    createdAt: report.createdAt.toISOString(),
    updatedAt: report.updatedAt.toISOString(),
  };
}
