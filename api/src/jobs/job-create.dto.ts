import {
  DomainValidationError,
  optionalText,
  optionalUuid,
  requireText,
} from '../validation/domain-validation.js';

/*
 * The `POST /jobs` create request (`BR-047`, `BR-048`, `BR-049`, `BR-053`, `BR-056`).
 *
 * A caller states the Customer the Job belongs to, the Property the work is performed at and the
 * Job's title. Everything else the Job is created with is the backend's and is never accepted from a
 * client: the identifier, the organization-scoped Job number, the `NEW` status, the Property address
 * snapshot and the version (`BR-001`, `BR-052`, `BR-058`).
 *
 * There is deliberately no priority, no owner, no type/category, no schedule and no Visit in this
 * request. `BR-054` removes Job priority from v1; the owner and the category are optional,
 * non-controlling data (`BR-053`, `BR-055`) that a later slice adds; and a Job is created without a
 * Visit because a Visit is one field attempt, not part of the work request (`BR-047`, `BR-051`).
 */

/** Fails the request with the same envelope every other parser produces. */
function fail(field: string, rule: string): never {
  throw new DomainValidationError([`${field} ${rule}`]);
}

/** A required UUID in canonical form. */
function requireUuid(value: unknown, field: string): string {
  const id = optionalUuid(value, field);
  if (id === null) {
    fail(field, 'is required');
  }
  return id;
}

/**
 * The longest description the API accepts.
 *
 * The column is `text`, so the cap is the boundary's own rather than a business limit: it keeps an
 * unbounded body out of a request, and it matches the free-text cap the Job and Visit actions already
 * apply to a note or an update body (`job-action.dto.ts`). The Job category catalogue is an open
 * question (`BR-053`), so no category is accepted here at all.
 */
export const MAX_JOB_DESCRIPTION_LENGTH = 2000;

/** The fields a caller supplies to create a Job (`BR-053`, `BR-056`). */
export interface CreateJobDto {
  /** The Customer the Job belongs to; a Job cannot exist without one (`BR-048`). */
  readonly customerId: string;
  /** The Property the work is performed at; the supported create requires one (`BR-056`). */
  readonly propertyId: string;
  /** The Job's title; required (`BR-053`). */
  readonly title: string;
  /** The Job's description; optional (`BR-053`). */
  readonly description: string | null;
}

/** Validates untrusted input into a `CreateJobDto`. */
export function parseCreateJobDto(input: unknown): CreateJobDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    customerId: requireUuid(source.customerId, 'customerId'),
    propertyId: requireUuid(source.propertyId, 'propertyId'),
    // `BR-053` requires a title, and `jobs_title_not_blank_check` rejects a blank one at the
    // database: trimming here is what keeps a whitespace-only title from reaching it as a 500.
    title: requireText(source.title, 'title', 255),
    description: optionalText(
      source.description,
      'description',
      MAX_JOB_DESCRIPTION_LENGTH,
    ),
  };
}
