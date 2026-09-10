import { createHmac, randomInt, timingSafeEqual } from 'node:crypto';

// One-time authentication codes: SMS OTPs (`BR-019`) and password-reset credentials
// (`BR-043`). Both are six digits, so both need a construction that survives a database
// disclosure — a six-digit value has about 20 bits of entropy, which an unkeyed digest
// would surrender instantly (`ADR-006` D1).
//
// The digest is therefore keyed with a server-side secret and domain-separated per purpose
// and per owner. Separating by owner also means two accounts may hold the same six digits
// without their digests colliding, and `token_hash`/`code_hash` stay globally unique.

/** `BR-019`/`BR-043` fix the code at six numeric digits. */
export const AUTH_CODE_LENGTH = 6;

/** The purposes a code digest is bound to; never translate or reuse these values. */
export const AUTH_CODE_SCOPES = ['PASSWORD_RESET', 'SMS_OTP'] as const;
export type AuthCodeScope = (typeof AUTH_CODE_SCOPES)[number];

const DIGEST_DOMAIN = 'servora-auth-code:v1';
const CODE_PATTERN = /^[0-9]{6}$/;

/** Generates a uniformly distributed six-digit code from the CSPRNG. */
export function generateAuthCode(): string {
  return randomInt(0, 10 ** AUTH_CODE_LENGTH)
    .toString()
    .padStart(AUTH_CODE_LENGTH, '0');
}

/**
 * Computes the stored digest of a code.
 *
 * `secret` is the deployment's JWT signing key: the only server-side secret the
 * authentication module already requires, so no new required configuration is introduced.
 */
export function hashAuthCode(
  secret: string,
  scope: AuthCodeScope,
  ownerId: string,
  code: string,
): string {
  return createHmac('sha256', secret)
    .update(`${DIGEST_DOMAIN}:${scope}:${ownerId}:${code}`, 'utf8')
    .digest('hex');
}

/** Compares two code digests in constant time. */
export function authCodeDigestsEqual(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left, 'utf8');
  const rightBytes = Buffer.from(right, 'utf8');
  if (leftBytes.length !== rightBytes.length) {
    return false;
  }
  return timingSafeEqual(leftBytes, rightBytes);
}

/** Whether a submitted value could be a code; a malformed value can never match one. */
export function isWellFormedAuthCode(value: string): boolean {
  return CODE_PATTERN.test(value);
}
