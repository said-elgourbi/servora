import {
  DomainValidationError,
  optionalUuid,
} from '../validation/domain-validation.js';
import type { LocalDate } from '../home/home-day.js';

/**
 * The two narrowing parameters `GET /schedule` accepts.
 *
 * Both are validated here rather than by the query itself, so a request that names a day Servora
 * cannot resolve or a technician id that is not a UUID is refused with the API's own validation
 * envelope instead of silently answering for a different day or an empty crew (`dev.md` §7).
 */

/** The `YYYY-MM-DD` form a client names a local calendar date in. */
const LOCAL_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * The local date the request asks about, or `null` when it names none.
 *
 * A request that names no date is answered for **today in the submitted zone**, which is the same
 * day the manager home answers for, so the two screens cannot disagree (`BR-041`). The submitted
 * date is a *calendar* date: the API resolves its instants, and the client's clock is never
 * consulted (`BR-001`).
 */
export function readScheduleDateQuery(query: unknown): LocalDate | null {
  const source = (query ?? {}) as Record<string, unknown>;
  const value = source.date;
  if (value === undefined || value === null || value === '') {
    return null;
  }
  if (typeof value !== 'string') {
    fail('date', 'must be a calendar date in YYYY-MM-DD form');
  }

  const match = LOCAL_DATE_PATTERN.exec(value.trim());
  if (match === null) {
    fail('date', 'must be a calendar date in YYYY-MM-DD form');
  }

  const year = Number.parseInt(match[1] as string, 10);
  const month = Number.parseInt(match[2] as string, 10);
  const day = Number.parseInt(match[3] as string, 10);
  // The round trip is the calendar check: `2026-02-30` and `2026-13-01` both roll over, so a date
  // that does not survive it is not a date the API can resolve a day for.
  const rolled = new Date(Date.UTC(year, month - 1, day));
  if (
    rolled.getUTCFullYear() !== year ||
    rolled.getUTCMonth() + 1 !== month ||
    rolled.getUTCDate() !== day
  ) {
    fail('date', 'must be a calendar date in YYYY-MM-DD form');
  }

  return { year, month, day };
}

/**
 * The technicians the request narrows the day's schedule to, or an empty list for the whole
 * organization.
 *
 * Each id is the organization **membership** an assignment names (`BR-068`): a Visit is assigned to
 * members, not to users, so the filter is expressed in the same vocabulary the assignments are.
 * Whether a membership exists, and whether it is a technician, is not decided here — the filter
 * narrows the day's own rows, so an id that matches no assignment answers with an empty day rather
 * than inventing a refusal for a technician the organization may simply not have assigned today
 * (`BR-042`).
 *
 * The parameter is **repeatable** — `?membershipId=a&membershipId=b` — which is how a dispatcher
 * asks for one technician, a small group, or the whole team from the same control. A single value
 * stays the same question it always was, so an existing caller is unaffected.
 *
 * Several ids are a **union**: a Visit is answered when **any** of them is currently assigned to it
 * (`BR-068`). A filter asks "whose work am I looking at", not "which Visits do these people share",
 * so a Visit is never dropped for having only one of the selected technicians on it.
 */
export function readScheduleMembershipsQuery(query: unknown): string[] {
  const source = (query ?? {}) as Record<string, unknown>;
  const value = source.membershipId;
  if (value === undefined || value === null) {
    return [];
  }

  const submitted = Array.isArray(value) ? value : [value];
  const membershipIds: string[] = [];
  for (const entry of submitted) {
    // A cleared filter is not a malformed one: a blank value names no technician, and repeating the
    // parameter with one blank entry still answers for the technicians that were named.
    if (typeof entry === 'string' && entry.trim() === '') {
      continue;
    }
    const membershipId = optionalUuid(entry, 'membershipId');
    if (membershipId === null) {
      continue;
    }
    if (!membershipIds.includes(membershipId)) {
      membershipIds.push(membershipId);
    }
  }
  return membershipIds;
}

/** Fails the request with the same envelope every other parser produces. */
function fail(field: string, rule: string): never {
  throw new DomainValidationError([`${field} ${rule}`]);
}
