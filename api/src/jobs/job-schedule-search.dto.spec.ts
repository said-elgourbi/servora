import { describe, expect, it } from 'vitest';
import { DomainValidationError } from '../validation/domain-validation.js';
import { parseJobScheduleSearchQuery } from './job-schedule-search.dto.js';

describe('schedule job search query', () => {
  it('trims a non-empty search term', () => {
    expect(parseJobScheduleSearchQuery({ query: '  1042 ' })).toBe('1042');
  });

  it('rejects blank, non-string, and overly long terms', () => {
    for (const query of ['', '  ', 1042, 'x'.repeat(101)]) {
      expect(() => parseJobScheduleSearchQuery({ query })).toThrow(DomainValidationError);
    }
  });
});
