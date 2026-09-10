import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

// Opaque credentials used for refresh tokens and password-reset tokens.
//
// Such a token carries 256 bits of entropy from the CSPRNG, so there is no
// guessable, low-entropy secret to slow an attacker down with a memory-hard
// function: a single fast one-way digest is sufficient for storage. Argon2id
// stays reserved for user-chosen passwords (`src/users/password-hasher.ts`),
// which are the values that actually need it.

const OPAQUE_TOKEN_BYTES = 32;

/** Generates a cryptographically random, URL-safe opaque token. */
export function generateOpaqueToken(): string {
  return randomBytes(OPAQUE_TOKEN_BYTES).toString('base64url');
}

/**
 * Hashes an opaque token for persistence.
 *
 * Only the hash is ever stored, so a database disclosure does not hand out
 * usable credentials. The raw token exists only in the response to the client.
 */
export function hashOpaqueToken(rawToken: string): string {
  return createHash('sha256').update(rawToken, 'utf8').digest('hex');
}

/** Compares two token hashes in constant time. */
export function tokenHashesEqual(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left, 'utf8');
  const rightBytes = Buffer.from(right, 'utf8');
  if (leftBytes.length !== rightBytes.length) {
    return false;
  }
  return timingSafeEqual(leftBytes, rightBytes);
}
