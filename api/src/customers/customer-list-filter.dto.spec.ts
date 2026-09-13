import { DomainValidationError } from '../validation/domain-validation.js';
import {
  DEFAULT_CUSTOMER_LIST_FILTERS,
  parseCustomerListFilters,
} from './customer-list-filter.dto.js';

describe('parseCustomerListFilters', () => {
  it('defaults both dimensions to ALL when the request names none', () => {
    expect(parseCustomerListFilters({})).toEqual(DEFAULT_CUSTOMER_LIST_FILTERS);
    expect(parseCustomerListFilters(undefined)).toEqual(
      DEFAULT_CUSTOMER_LIST_FILTERS,
    );
  });

  it('accepts every documented status code', () => {
    expect(parseCustomerListFilters({ status: 'ACTIVE' }).status).toBe('ACTIVE');
    expect(parseCustomerListFilters({ status: 'INACTIVE' }).status).toBe(
      'INACTIVE',
    );
    expect(parseCustomerListFilters({ status: 'ALL' }).status).toBe('ALL');
  });

  it('accepts every documented jobs code', () => {
    for (const jobs of [
      'ALL',
      'HAS_OPEN_JOBS',
      'NO_OPEN_JOBS',
      'HAS_OVERDUE_VISITS',
      'NO_JOBS',
    ]) {
      expect(parseCustomerListFilters({ jobs }).jobs).toBe(jobs);
    }
  });

  it('reads the first value when a parameter is repeated', () => {
    expect(parseCustomerListFilters({ status: ['ACTIVE', 'INACTIVE'] })).toEqual({
      status: 'ACTIVE',
      jobs: 'ALL',
    });
  });

  it('rejects an unknown value instead of ignoring it', () => {
    expect(() => parseCustomerListFilters({ status: 'ARCHIVED' })).toThrow(
      DomainValidationError,
    );
    expect(() => parseCustomerListFilters({ jobs: 'HAS_JOBS' })).toThrow(
      DomainValidationError,
    );
  });

  it('rejects a non-string value', () => {
    expect(() => parseCustomerListFilters({ jobs: 3 })).toThrow(
      DomainValidationError,
    );
  });
});
