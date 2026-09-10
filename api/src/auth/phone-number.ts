import { AuthApiError } from './auth-error.js';

// Phone numbers are an authentication identity (`BR-019`), so their canonical form is part
// of the API contract: E.164, international by construction, never assumed to be Canadian.
//
// Normalisation is server-authoritative. The client may help, but a value the server cannot
// interpret is rejected rather than guessed at: without a country the server cannot invent
// a dialling code, and assuming `+1` would make the domain permanently Canada-only.

/** E.164: `+`, a country code that never starts with `0`, and 8-15 digits in total. */
const E164_PATTERN = /^\+[1-9][0-9]{7,14}$/;

/** Matches the `users.phone varchar(50)` column. */
const MAX_PHONE_LENGTH = 50;

/**
 * Characters that carry no meaning in a dialled number. They are removed before validation
 * so "typed" input such as `+1 (514) 555-0100` is accepted without weakening the stored form.
 */
const GROUPING_CHARACTERS = /[\s()./-]/g;

/**
 * Normalises a submitted phone number to E.164, or rejects it as `400 VALIDATION_FAILED`.
 *
 * The rejection names only the field: a phone number is an identifier and must never be
 * echoed back (`BR-044`).
 */
export function normalizePhoneNumber(value: unknown, field = 'phone'): string {
  if (typeof value !== 'string') {
    throw AuthApiError.validationFailed(`"${field}" must be a string.`);
  }

  const trimmed = value.trim();
  if (trimmed.length === 0) {
    throw AuthApiError.validationFailed(`"${field}" must not be empty.`);
  }
  if (trimmed.length > MAX_PHONE_LENGTH) {
    throw AuthApiError.validationFailed(
      `"${field}" must be at most ${MAX_PHONE_LENGTH} characters.`,
    );
  }

  const normalized = trimmed.replace(GROUPING_CHARACTERS, '');
  if (!E164_PATTERN.test(normalized)) {
    throw AuthApiError.validationFailed(
      `"${field}" must be a phone number in international E.164 format.`,
    );
  }

  return normalized;
}
