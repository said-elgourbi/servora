import {
  DomainValidationError,
  requireText,
} from '../validation/domain-validation.js';

export const JOB_SCHEDULE_SEARCH_LIMIT = 20;
export const JOB_SCHEDULE_SEARCH_MIN_LENGTH = 1;

export interface JobScheduleSearchOptionDto {
  id: string;
  jobNumber: number;
  title: string;
  status: string;
  customerName: string;
  propertyName: string | null;
}

export function parseJobScheduleSearchQuery(input: unknown): string {
  const source = (input ?? {}) as Record<string, unknown>;
  const value = requireText(source.query, 'query', 100);
  if (value.length < JOB_SCHEDULE_SEARCH_MIN_LENGTH) {
    throw new DomainValidationError(['query must contain at least one character']);
  }
  return value;
}
