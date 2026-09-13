// Shared, dependency-free validation helpers for API request/DTO boundaries.
//
// Validation is deliberately explicit (no decorators or reflection metadata) so
// the rules can be unit-tested without a Nest application and stay easy to read.
// Every helper fails closed by raising a single DomainValidationError that the
// HTTP layer can translate into a 400/422 response envelope.
export const MIN_PASSWORD_LENGTH = 8;
export const MAX_EMAIL_LENGTH = 320;

export class DomainValidationError extends Error {
  readonly issues: readonly string[];

  constructor(issues: readonly string[]) {
    super(`Domain validation failed: ${issues.join('; ')}`);
    this.name = 'DomainValidationError';
    this.issues = [...issues];
  }
}

function fail(field: string, rule: string): never {
  throw new DomainValidationError([`${field} ${rule}`]);
}

/** Requires a non-empty, trimmed string no longer than `maxLength`. */
export function requireText(
  value: unknown,
  field: string,
  maxLength: number,
): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    fail(field, 'is required');
  }
  const trimmed = value.trim();
  if (trimmed.length > maxLength) {
    fail(field, `must be at most ${maxLength} characters`);
  }
  return trimmed;
}

/** Like `requireText` but returns `null` for missing/blank input. */
export function optionalText(
  value: unknown,
  field: string,
  maxLength: number,
): string | null {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value === 'string' && value.trim().length === 0) {
    return null;
  }
  return requireText(value, field, maxLength);
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Requires a syntactically valid email address. */
export function requireEmail(value: unknown, field: string): string {
  const text = requireText(value, field, MAX_EMAIL_LENGTH);
  if (!EMAIL_PATTERN.test(text)) {
    fail(field, 'must be a valid email address');
  }
  return text;
}

/** Like `requireEmail` but returns `null` for missing/blank input. */
export function optionalEmail(value: unknown, field: string): string | null {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value === 'string' && value.trim().length === 0) {
    return null;
  }
  return requireEmail(value, field);
}

/** Requires `value` to be one of the stable, machine-readable allowed values. */
export function requireEnum<T extends string>(
  value: unknown,
  allowed: readonly T[],
  field: string,
): T {
  if (typeof value !== 'string' || !allowed.includes(value as T)) {
    fail(field, `must be one of: ${allowed.join(', ')}`);
  }
  return value as T;
}

/**
 * Requires a plaintext password of at least `MIN_PASSWORD_LENGTH` characters.
 * Never log or persist the returned value.
 */
export function requirePassword(value: unknown): string {
  if (typeof value !== 'string' || value.length < MIN_PASSWORD_LENGTH) {
    fail('password', `must be at least ${MIN_PASSWORD_LENGTH} characters`);
  }
  return value;
}

/** Coerces an optional boolean flag to `fallback` when absent; rejects other types. */
export function optionalBoolean(
  value: unknown,
  field: string,
  fallback = false,
): boolean {
  if (value === undefined || value === null) {
    return fallback;
  }
  if (typeof value !== 'boolean') {
    fail(field, 'must be a boolean');
  }
  return value;
}

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** Accepts an optional calendar date in ISO `YYYY-MM-DD` form. */
export function optionalDate(value: unknown, field: string): string | null {
  const text = optionalText(value, field, 10);
  if (text === null) {
    return null;
  }
  if (!DATE_PATTERN.test(text)) {
    fail(field, 'must be an ISO date (YYYY-MM-DD)');
  }
  return text;
}
