import { requireEnum } from '../validation/domain-validation.js';
import { CUSTOMER_STATUSES } from './customer.types.js';

/**
 * The customer-list filter a client may send to `GET /customers`.
 *
 * Both dimensions use stable, machine-readable codes so the same vocabulary reaches every client
 * (`BR-041`). `ALL` means "no constraint on this dimension"; it is a query value, not a stored
 * customer or job state.
 */
export const CUSTOMER_STATUS_FILTERS = ['ALL', ...CUSTOMER_STATUSES] as const;
export type CustomerStatusFilter = (typeof CUSTOMER_STATUS_FILTERS)[number];

/**
 * The job/visit dimension of the customer list.
 *
 * The meaning of each code is a product decision recorded in
 * `docs/tracker/008-android-customers-filter.md`:
 *
 * - `HAS_OPEN_JOBS` / `NO_OPEN_JOBS` — the customer has, or does not have, at least one Job whose
 *   status is not terminal (`BR-058`). A customer with no Jobs at all has no open Jobs.
 * - `HAS_OVERDUE_VISITS` — the customer has at least one Visit still `SCHEDULED` (`BR-074`) whose
 *   scheduled end has already passed.
 * - `NO_JOBS` — the customer has never had a Job (`BR-048`).
 *
 * Every condition is derived from the authoritative Job and Visit tables; nothing new is stored
 * (`BR-080`).
 */
export const CUSTOMER_JOB_FILTERS = [
  'ALL',
  'HAS_OPEN_JOBS',
  'NO_OPEN_JOBS',
  'HAS_OVERDUE_VISITS',
  'NO_JOBS',
] as const;
export type CustomerJobFilter = (typeof CUSTOMER_JOB_FILTERS)[number];

/** A parsed customer-list filter, with every dimension defaulted to `ALL`. */
export interface CustomerListFilters {
  readonly status: CustomerStatusFilter;
  readonly jobs: CustomerJobFilter;
}

/** The filter applied when the request names no dimension. */
export const DEFAULT_CUSTOMER_LIST_FILTERS: CustomerListFilters = {
  status: 'ALL',
  jobs: 'ALL',
};

/**
 * Reads a single query value.
 *
 * A repeated parameter (`?status=A&status=B`) arrives as an array; the first value is read so the
 * parser still validates a scalar. An unknown value fails validation rather than being ignored.
 */
function firstValue(value: unknown): unknown {
  return Array.isArray(value) ? value[0] : value;
}

/** Validates untrusted query input into a customer-list filter. */
export function parseCustomerListFilters(
  input: unknown,
): CustomerListFilters {
  const source = (input ?? {}) as Record<string, unknown>;
  const status = firstValue(source.status);
  const jobs = firstValue(source.jobs);

  return {
    status:
      status === undefined
        ? DEFAULT_CUSTOMER_LIST_FILTERS.status
        : requireEnum(status, CUSTOMER_STATUS_FILTERS, 'status'),
    jobs:
      jobs === undefined
        ? DEFAULT_CUSTOMER_LIST_FILTERS.jobs
        : requireEnum(jobs, CUSTOMER_JOB_FILTERS, 'jobs'),
  };
}
