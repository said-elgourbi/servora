import { describe, expect, it } from 'vitest';
import { DomainValidationError } from '../validation/domain-validation.js';
import {
  AD_HOC_CUSTOMER_SEARCH_MIN_LENGTH,
  AD_HOC_REVIEW_JOB_SEARCH_MIN_LENGTH,
  parseAdHocCustomerSearchQuery,
  parseAdHocReportCustomerContextQuery,
  parseAdHocReviewJobOptionsQuery,
} from './ad-hoc-work-report-options.dto.js';

describe('ad-hoc work report option DTOs', () => {
  it('parses a customer name search query', () => {
    expect(parseAdHocCustomerSearchQuery({ q: 'Acme' })).toEqual({
      query: 'Acme',
    });
  });

  it('trims the customer name search query', () => {
    expect(parseAdHocCustomerSearchQuery({ q: '  Acme  ' })).toEqual({
      query: 'Acme',
    });
  });

  it('refuses a customer name query below the minimum length', () => {
    expect(() => parseAdHocCustomerSearchQuery({ q: 'A' })).toThrow(
      DomainValidationError,
    );
  });

  it('refuses a missing customer name query', () => {
    expect(() => parseAdHocCustomerSearchQuery({})).toThrow(
      DomainValidationError,
    );
  });

  it('parses a customer context query', () => {
    expect(
      parseAdHocReportCustomerContextQuery({
        customerId: '00000000-0000-4000-8000-000000000001',
      }),
    ).toEqual({ customerId: '00000000-0000-4000-8000-000000000001' });
  });

  it('refuses a missing customer id in a context query', () => {
    expect(() => parseAdHocReportCustomerContextQuery({})).toThrow(
      DomainValidationError,
    );
  });

  it('refuses a malformed customer id in a context query', () => {
    expect(() =>
      parseAdHocReportCustomerContextQuery({ customerId: 'not-a-uuid' }),
    ).toThrow(DomainValidationError);
  });

  it('exposes the minimum search length as a constant', () => {
    expect(AD_HOC_CUSTOMER_SEARCH_MIN_LENGTH).toBe(2);
  });

  it('parses a review Job search scoped to a customer', () => {
    expect(
      parseAdHocReviewJobOptionsQuery({
        customerId: '00000000-0000-4000-8000-000000000001',
      }),
    ).toEqual({
      customerId: '00000000-0000-4000-8000-000000000001',
      query: null,
    });
  });

  it('parses a review Job search by query when no customer is known', () => {
    expect(parseAdHocReviewJobOptionsQuery({ q: '1042' })).toEqual({
      customerId: null,
      query: '1042',
    });
  });

  it('refuses a review Job search naming neither a customer nor a query', () => {
    expect(() => parseAdHocReviewJobOptionsQuery({})).toThrow(
      DomainValidationError,
    );
  });

  it('refuses a review Job query below the minimum length', () => {
    expect(() => parseAdHocReviewJobOptionsQuery({ q: '1' })).toThrow(
      DomainValidationError,
    );
  });

  it('exposes the review Job search minimum length as a constant', () => {
    expect(AD_HOC_REVIEW_JOB_SEARCH_MIN_LENGTH).toBe(2);
  });
});
