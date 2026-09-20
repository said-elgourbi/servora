import { AuthApiError } from '../auth/auth-error.js';
import { DEFAULT_HOME_TIME_ZONE, normaliseTimeZone } from './home-day.js';

/**
 * The `timeZone` parameter both home reads accept (`GET /home/manager`, `GET /home/technician`).
 *
 * It is defined once for the two reads so the parameter cannot be accepted differently by one of
 * them (`BR-041`, `dev.md` §7): a request that names no zone is resolved in UTC, which is a defined
 * answer rather than a guess at where the organization is, and a zone this runtime cannot format is
 * rejected — the read never silently answers for a different day than the one the client asked
 * about.
 *
 * The rejected value is reported with the API's own validation error, so a client sees the same
 * `400 VALIDATION_FAILED` envelope every other invalid input produces.
 */
export function readTimeZoneQuery(query: unknown): string {
  const source = (query ?? {}) as Record<string, unknown>;
  const value = source.timeZone;
  if (value === undefined || value === null || value === '') {
    return DEFAULT_HOME_TIME_ZONE;
  }
  const timeZone = normaliseTimeZone(value);
  if (timeZone === null) {
    throw AuthApiError.validationFailed(
      'timeZone must be a time-zone identifier the server can resolve.',
    );
  }
  return timeZone;
}
