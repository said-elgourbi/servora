import {
  DomainValidationError,
  optionalBoolean,
  optionalInstant,
  optionalPositiveInteger,
  optionalText,
  optionalUuid,
  requireEnum,
  requireText,
} from '../validation/domain-validation.js';
import {
  ASSIGNMENT_ROLE_CODES,
  FOLLOW_UP_VISIT_REQUEST_STATUSES,
  type AssignmentRoleCode,
  type FollowUpVisitRequestStatus,
} from './job.types.js';

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

export interface FollowUpVisitRequestDto {
  id: string;
  jobId: string;
  sourceVisitId: string | null;
  requestingTechnicianMembershipId: string;
  proposedStart: string;
  proposedEnd: string;
  reason: string;
  sameTechnicianPreferred: boolean;
  status: FollowUpVisitRequestStatus;
  reviewerMembershipId: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  createdVisitId: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface SubmitFollowUpVisitRequestDto {
  readonly sourceVisitId: string | null;
  readonly proposedStart: Date;
  readonly proposedEnd: Date;
  readonly reason: string;
  readonly sameTechnicianPreferred: boolean;
}

export interface FollowUpVisitReviewDto {
  readonly note: string | null;
  readonly expectedStatus: FollowUpVisitRequestStatus | null;
  readonly expectedVersion: number | null;
}

export interface FollowUpVisitApprovalDto extends FollowUpVisitReviewDto {
  readonly scheduledStart: Date | null;
  readonly scheduledEnd: Date | null;
  readonly technicians: readonly RequestedVisitTechnician[];
  readonly confirmConflicts: boolean;
}

export interface DirectCreateVisitDto {
  readonly scheduledStart: Date;
  readonly scheduledEnd: Date;
  readonly technicians: readonly RequestedVisitTechnician[];
  readonly confirmConflicts: boolean;
}

export interface RequestedVisitTechnician {
  readonly membershipId: string;
  readonly roleCode: AssignmentRoleCode;
}

export function parseSubmitFollowUpVisitRequestDto(
  input: unknown,
): SubmitFollowUpVisitRequestDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const proposedStart = requireInstant(source.proposedStart, 'proposedStart');
  const proposedEnd = requireInstant(source.proposedEnd, 'proposedEnd');
  if (proposedEnd.getTime() <= proposedStart.getTime()) {
    fail('proposedEnd', 'must be after proposedStart');
  }
  return {
    sourceVisitId: optionalUuid(source.sourceVisitId, 'sourceVisitId'),
    proposedStart,
    proposedEnd,
    reason: requireText(source.reason, 'reason', 2000),
    sameTechnicianPreferred: optionalBoolean(
      source.sameTechnicianPreferred,
      'sameTechnicianPreferred',
      false,
    ),
  };
}

export function parseFollowUpVisitReviewDto(
  input: unknown,
): FollowUpVisitReviewDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    note: optionalText(source.note, 'note', 2000),
    expectedStatus: optionalFollowUpStatus(source.expectedStatus),
    expectedVersion: optionalPositiveInteger(
      source.expectedVersion,
      'expectedVersion',
    ),
  };
}

export function parseFollowUpVisitApprovalDto(
  input: unknown,
): FollowUpVisitApprovalDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const scheduledStart =
    source.scheduledStart === undefined || source.scheduledStart === null
      ? null
      : requireInstant(source.scheduledStart, 'scheduledStart');
  const scheduledEnd =
    source.scheduledEnd === undefined || source.scheduledEnd === null
      ? null
      : requireInstant(source.scheduledEnd, 'scheduledEnd');
  if ((scheduledStart === null) !== (scheduledEnd === null)) {
    fail('scheduledStart', 'and scheduledEnd must be supplied together');
  }
  if (
    scheduledStart !== null &&
    scheduledEnd !== null &&
    scheduledEnd.getTime() <= scheduledStart.getTime()
  ) {
    fail('scheduledEnd', 'must be after scheduledStart');
  }
  return {
    ...parseFollowUpVisitReviewDto(input),
    scheduledStart,
    scheduledEnd,
    technicians: parseRequestedTechnicians(source.technicians),
    confirmConflicts: source.confirmConflicts === true,
  };
}

export function parseDirectCreateVisitDto(input: unknown): DirectCreateVisitDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const scheduledStart = requireInstant(source.scheduledStart, 'scheduledStart');
  const scheduledEnd = requireInstant(source.scheduledEnd, 'scheduledEnd');
  if (scheduledEnd.getTime() <= scheduledStart.getTime()) {
    fail('scheduledEnd', 'must be after scheduledStart');
  }
  return {
    scheduledStart,
    scheduledEnd,
    technicians: parseRequestedTechnicians(source.technicians),
    confirmConflicts: source.confirmConflicts === true,
  };
}

function optionalFollowUpStatus(
  value: unknown,
): FollowUpVisitRequestStatus | null {
  if (value === undefined || value === null) {
    return null;
  }
  return requireEnum(
    value,
    FOLLOW_UP_VISIT_REQUEST_STATUSES,
    'expectedStatus',
  );
}

function parseRequestedTechnicians(
  value: unknown,
): readonly RequestedVisitTechnician[] {
  if (!Array.isArray(value) || value.length === 0) {
    fail('technicians', 'must include one lead technician');
  }
  const technicians = value.map((entry, index) => {
    const source = (entry ?? {}) as Record<string, unknown>;
    const membershipId = optionalUuid(
      source.membershipId,
      `technicians[${index}].membershipId`,
    );
    if (membershipId === null) {
      fail(`technicians[${index}].membershipId`, 'is required');
    }
    return {
      membershipId,
      roleCode: requireEnum(
        source.roleCode,
        ASSIGNMENT_ROLE_CODES,
        `technicians[${index}].roleCode`,
      ),
    };
  });
  const uniqueIds = new Set(technicians.map((technician) => technician.membershipId));
  if (uniqueIds.size !== technicians.length) {
    fail('technicians', 'must not contain duplicates');
  }
  if (technicians.filter((technician) => technician.roleCode === 'LEAD').length !== 1) {
    fail('technicians', 'must include exactly one lead technician');
  }
  return technicians;
}

export function toFollowUpVisitRequestDto(
  request: FollowUpVisitRequestRecord,
): FollowUpVisitRequestDto {
  return {
    id: request.id,
    jobId: request.jobId,
    sourceVisitId: request.sourceVisitId,
    requestingTechnicianMembershipId: request.requestingTechnicianMembershipId,
    proposedStart: request.proposedStart.toISOString(),
    proposedEnd: request.proposedEnd.toISOString(),
    reason: request.reason,
    sameTechnicianPreferred: request.sameTechnicianPreferred,
    status: request.status as FollowUpVisitRequestStatus,
    reviewerMembershipId: request.reviewerMembershipId,
    reviewedAt: request.reviewedAt?.toISOString() ?? null,
    reviewNote: request.reviewNote,
    createdVisitId: request.createdVisitId,
    version: request.version,
    createdAt: request.createdAt.toISOString(),
    updatedAt: request.updatedAt.toISOString(),
  };
}

type FollowUpVisitRequestRecord = {
  readonly id: string;
  readonly jobId: string;
  readonly sourceVisitId: string | null;
  readonly requestingTechnicianMembershipId: string;
  readonly proposedStart: Date;
  readonly proposedEnd: Date;
  readonly reason: string;
  readonly sameTechnicianPreferred: boolean;
  readonly status: string;
  readonly reviewerMembershipId: string | null;
  readonly reviewedAt: Date | null;
  readonly reviewNote: string | null;
  readonly createdVisitId: string | null;
  readonly version: number;
  readonly createdAt: Date;
  readonly updatedAt: Date;
};
