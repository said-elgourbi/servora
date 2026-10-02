import {
  DomainValidationError,
  optionalUuid,
  requireText,
} from '../validation/domain-validation.js';

/**
 * The discoverability options the ad-hoc work report form presents (`BR-AH-009`).
 *
 * A technician reports work that happened outside a scheduled Visit, and the form lets them say
 * *which* Customer it was for, *which* Property, and optionally *which* Job it relates to. Those
 * choices are discoverable, not typed by id: the API answers a scoped search rather than exposing
 * the organization's full Customer list, and the Property and Job choices are derived from the
 * selected Customer (`BR-050`, `BR-094`). Every option is a stable identifier plus the human
 * readable values a client needs to present it — never a decision the client makes (`BR-001`,
 * `BR-007`).
 */

/** The shortest Customer name query the search answers (`BR-AH-009`). */
export const AD_HOC_CUSTOMER_SEARCH_MIN_LENGTH = 2;

/**
 * Which Customers a reporter may discover (`BR-AH-009`, `BR-092`).
 *
 * - `ORGANIZATION` — the caller holds `customers.view` and searches the whole organization.
 * - `ASSIGNED` — the caller holds `customers.view_assigned` and searches only the Customers of Jobs
 *   their own current crew includes.
 * - `NONE` — the caller holds neither Customer read capability, so no Customer is discoverable and
 *   the report's unknown-customer path is the only way to state who the work was for.
 */
export type AdHocReportCustomerScope =
  | { readonly kind: 'ORGANIZATION' }
  | { readonly kind: 'ASSIGNED'; readonly membershipId: string }
  | { readonly kind: 'NONE' };

/** One Customer the reporter may select, as the search answers it. */
export interface AdHocReportCustomerOptionDto {
  readonly id: string;
  readonly displayName: string;
}

/** One active Property of the selected Customer the reporter may select (`BR-050`, `BR-094`). */
export interface AdHocReportPropertyOptionDto {
  readonly id: string;
  readonly name: string | null;
  readonly addressLine1: string;
  readonly addressLine2: string | null;
  readonly city: string;
  readonly province: string;
  readonly postalCode: string;
  readonly country: string;
}

/** One Job of the selected Customer the reporter may name as the related work (`BR-AH-009`). */
export interface AdHocReportJobOptionDto {
  readonly id: string;
  readonly jobNumber: number;
  readonly title: string;
}

export interface AdHocCustomerSearchQuery {
  readonly query: string;
}

export interface AdHocReportCustomerContextQuery {
  readonly customerId: string;
}

/** Reads a single query value, so a repeated parameter still validates as a scalar. */
function firstQueryValue(value: unknown): unknown {
  return Array.isArray(value) ? value[0] : value;
}

/**
 * Validates the Customer name search query (`BR-AH-009`).
 *
 * The search is a type-ahead: it answers only once the reporter has typed enough to be asking about
 * a real Customer rather than a single letter, so a query below the minimum is refused instead of
 * answered with the whole list (`BR-042`).
 */
export function parseAdHocCustomerSearchQuery(
  input: unknown,
): AdHocCustomerSearchQuery {
  const source = (input ?? {}) as Record<string, unknown>;
  const query = requireText(firstQueryValue(source.q), 'q', 255);
  if (query.length < AD_HOC_CUSTOMER_SEARCH_MIN_LENGTH) {
    throw new DomainValidationError([
      `q must be at least ${AD_HOC_CUSTOMER_SEARCH_MIN_LENGTH} characters`,
    ]);
  }
  return { query };
}

/**
 * Validates the Customer id that scopes the Property and Job option reads (`BR-AH-009`).
 */
export function parseAdHocReportCustomerContextQuery(
  input: unknown,
): AdHocReportCustomerContextQuery {
  const source = (input ?? {}) as Record<string, unknown>;
  const customerId = optionalUuid(firstQueryValue(source.customerId), 'customerId');
  if (customerId === null) {
    throw new DomainValidationError(['customerId is required']);
  }
  return { customerId };
}
