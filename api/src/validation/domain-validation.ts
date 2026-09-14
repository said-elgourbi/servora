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

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Accepts an optional UUID in canonical `8-4-4-4-12` form.
 *
 * Used for client-generated identifiers such as an offline operation's idempotency key (`BR-031`),
 * which the API stores as a `uuid` column: validating here keeps an unparseable key from reaching
 * the database as a 500.
 */
export function optionalUuid(value: unknown, field: string): string | null {
  const text = optionalText(value, field, 36);
  if (text === null) {
    return null;
  }
  if (!UUID_PATTERN.test(text)) {
    fail(field, 'must be a UUID');
  }
  return text;
}

// ISO-8601 permits fractional seconds of any precision, and a device clock reports more than
// milliseconds: Kotlin's `Instant.toString()` emits 0, 3, 6 or 9 digits depending on the clock's
// resolution, and Android reports microseconds. A 1-3 digit pattern therefore rejected a valid
// device time. Up to a nanosecond is accepted — the finest any supported client emits — while the
// stored column keeps microseconds.
const INSTANT_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/;

/**
 * Accepts an optional ISO-8601 instant (`Project.md` §15).
 *
 * A device-recorded time travels as an instant with an explicit zone or `Z`, so a client cannot
 * send a local wall-clock string that the backend would have to guess at. Fractional seconds are
 * accepted at the precision the device clock reports, not only milliseconds.
 */
export function optionalInstant(value: unknown, field: string): string | null {
  const text = optionalText(value, field, 40);
  if (text === null) {
    return null;
  }
  if (!INSTANT_PATTERN.test(text)) {
    fail(field, 'must be an ISO-8601 instant');
  }
  return text;
}

/** Accepts an optional integer greater than zero. */
export function optionalPositiveInteger(
  value: unknown,
  field: string,
): number | null {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1) {
    fail(field, 'must be a positive integer');
  }
  return value;
}
