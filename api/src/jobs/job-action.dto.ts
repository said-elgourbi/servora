import {
  DomainValidationError,
  optionalInstant,
  optionalPositiveInteger,
  optionalText,
  optionalUuid,
  requireEnum,
} from '../validation/domain-validation.js';
import {
  ASSIGNMENT_ROLE_CODES,
  JOB_STATUSES,
  type AssignmentRoleCode,
  type JobStatus,
} from './job.types.js';

/*
 * The Job and Visit **action** requests (`BR-058`, `BR-063`, `BR-068`, `BR-069`, `BR-073`).
 *
 * Every parser validates untrusted input at the API boundary (`dev.md` §7) with the shared,
 * dependency-free validation helpers, so the rules are unit-testable without a Nest application. A
 * code this API does not know is rejected rather than ignored: the Job and Visit vocabularies are
 * shared and closed (`BR-041`), and a client must not be able to send a status Servora does not
 * have.
 */

/** Fails the request with the same envelope every other parser produces. */
function fail(field: string, rule: string): never {
  throw new DomainValidationError([`${field} ${rule}`]);
}

/** A required ISO-8601 instant, as `Project.md` §15 defines one. */
function requireInstant(value: unknown, field: string): Date {
  const instant = optionalInstant(value, field);
  if (instant === null) {
    fail(field, 'is required');
  }
  return new Date(instant);
}

/** A required UUID in canonical form. */
function requireUuid(value: unknown, field: string): string {
  const id = optionalUuid(value, field);
  if (id === null) {
    fail(field, 'is required');
  }
  return id;
}

/** The fields a caller supplies to move a Job through its lifecycle (`BR-058`). */
export interface ChangeJobStatusDto {
  readonly status: JobStatus;
  /** Optional note recorded with the change — a reopen reason, for instance (`BR-063`). */
  readonly note: string | null;
  /**
   * The Job version the client last saw. When it is supplied and the Job has moved on, the change is
   * rejected rather than applied (`BR-086`).
   */
  readonly expectedVersion: number | null;
}

/** Validates untrusted input into a `ChangeJobStatusDto`. */
export function parseChangeJobStatusDto(input: unknown): ChangeJobStatusDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    status: requireEnum(source.status, JOB_STATUSES, 'status'),
    note: optionalText(source.note, 'note', 2000),
    expectedVersion: optionalPositiveInteger(
      source.expectedVersion,
      'expectedVersion',
    ),
  };
}

/** The new schedule a caller supplies for an existing Visit (`BR-072`, `BR-073`). */
export interface RescheduleVisitDto {
  readonly scheduledStart: Date;
  readonly scheduledEnd: Date;
  /** Optional customer-facing arrival window; it is a pair or nothing (`BR-072`). */
  readonly arrivalWindowStart: Date | null;
  readonly arrivalWindowEnd: Date | null;
  /** Optional reason recorded in the schedule history (`BR-073`). */
  readonly reason: string | null;
  /**
   * Whether the caller accepted the availability conflicts the API reported (`BR-070`).
   *
   * A conflict is a warning, not a prohibition: the first request is refused with the conflicts so
   * the user can be shown them, and the same request resent with this flag applies the change and
   * records what was accepted.
   */
  readonly confirmConflicts: boolean;
  /** The Visit version the client last saw, when it holds one (`BR-086`). */
  readonly expectedVersion: number | null;
}

/** Validates untrusted input into a `RescheduleVisitDto`. */
export function parseRescheduleVisitDto(input: unknown): RescheduleVisitDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const scheduledStart = requireInstant(
    source.scheduledStart,
    'scheduledStart',
  );
  const scheduledEnd = requireInstant(source.scheduledEnd, 'scheduledEnd');
  if (scheduledEnd.getTime() <= scheduledStart.getTime()) {
    fail('scheduledEnd', 'must be after scheduledStart');
  }

  const arrivalWindowStart = optionalInstant(
    source.arrivalWindowStart,
    'arrivalWindowStart',
  );
  const arrivalWindowEnd = optionalInstant(
    source.arrivalWindowEnd,
    'arrivalWindowEnd',
  );
  if ((arrivalWindowStart === null) !== (arrivalWindowEnd === null)) {
    fail(
      'arrivalWindowStart',
      'and arrivalWindowEnd must be supplied together',
    );
  }

  const arrivalStart =
    arrivalWindowStart === null ? null : new Date(arrivalWindowStart);
  const arrivalEnd =
    arrivalWindowEnd === null ? null : new Date(arrivalWindowEnd);
  if (
    arrivalStart !== null &&
    arrivalEnd !== null &&
    arrivalEnd.getTime() <= arrivalStart.getTime()
  ) {
    fail('arrivalWindowEnd', 'must be after arrivalWindowStart');
  }

  return {
    scheduledStart,
    scheduledEnd,
    arrivalWindowStart: arrivalStart,
    arrivalWindowEnd: arrivalEnd,
    reason: optionalText(source.reason, 'reason', 2000),
    confirmConflicts: source.confirmConflicts === true,
    expectedVersion: optionalPositiveInteger(
      source.expectedVersion,
      'expectedVersion',
    ),
  };
}

/** One technician the caller wants assigned to a Visit, with the role they hold (`BR-068`). */
export interface RequestedVisitTechnician {
  readonly membershipId: string;
  readonly roleCode: AssignmentRoleCode;
}

/**
 * The complete crew a caller wants a Visit to carry (`BR-068`, `BR-069`).
 *
 * The request states the **whole** assignment, not a delta: exactly one of the technicians is the
 * `LEAD`, so removing the current Lead and choosing the next one is one explicit action, and the API
 * never has to promote anybody on its own (`BR-069`).
 */
export interface AssignVisitTechniciansDto {
  readonly technicians: readonly RequestedVisitTechnician[];
  /**
   * Whether the caller accepted the availability conflicts the API reported (`BR-070`).
   *
   * Assigning a technician who is already booked elsewhere overlaps two Visits just as
   * rescheduling does, so the same warn-then-confirm rule applies.
   */
  readonly confirmConflicts: boolean;
  /** The Visit version the client last saw, when it holds one (`BR-086`). */
  readonly expectedVersion: number | null;
}

/** The text update a caller adds to one Visit's activity (`BR-027`, `BR-077`). */
export interface AddVisitNoteDto {
  readonly body: string;
}

/** Validates untrusted input into an `AddVisitNoteDto`. */
export function parseAddVisitNoteDto(input: unknown): AddVisitNoteDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const body = optionalText(source.body, 'body', 2000);
  if (body === null) {
    fail('body', 'is required');
  }
  return { body };
}

/** Validates untrusted input into an `AssignVisitTechniciansDto`. */
export function parseAssignVisitTechniciansDto(
  input: unknown,
): AssignVisitTechniciansDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const raw = source.technicians;
  if (!Array.isArray(raw) || raw.length === 0) {
    fail('technicians', 'must be a non-empty array');
  }

  const technicians: RequestedVisitTechnician[] = [];
  const seen = new Set<string>();
  for (const [index, entry] of raw.entries()) {
    const item = (entry ?? {}) as Record<string, unknown>;
    const membershipId = requireUuid(
      item.membershipId,
      `technicians[${index}].membershipId`,
    );
    if (seen.has(membershipId)) {
      fail('technicians', 'must not name the same technician twice');
    }
    seen.add(membershipId);
    technicians.push({
      membershipId,
      roleCode: requireEnum(
        item.roleCode,
        ASSIGNMENT_ROLE_CODES,
        `technicians[${index}].roleCode`,
      ),
    });
  }

  // Exactly one Lead (`BR-068`): a Visit with technicians has one, so the crew the caller states
  // either names it or is refused.
  const leads = technicians.filter(
    (technician) => technician.roleCode === 'LEAD',
  );
  if (leads.length !== 1) {
    fail('technicians', 'must name exactly one LEAD');
  }

  return {
    technicians,
    confirmConflicts: source.confirmConflicts === true,
    expectedVersion: optionalPositiveInteger(
      source.expectedVersion,
      'expectedVersion',
    ),
  };
}
