import { argon2id, hash, verify, type HashOptions } from 'argon2';
import { requirePassword } from '../validation/domain-validation.js';

// Argon2id is the Servora password-hashing algorithm. The parameters follow the
// OWASP recommended baseline for interactive logins: 19 MiB memory, 2 passes,
// 1 lane. Servora only ever stores the resulting hash — never the plaintext.
const ARGON2ID_OPTIONS: HashOptions = {
  type: argon2id,
  memoryCost: 19456,
  timeCost: 2,
  parallelism: 1,
};

/** Hashes a plaintext password with Argon2id. The plaintext is not retained. */
export async function hashPassword(plainPassword: string): Promise<string> {
  const password = requirePassword(plainPassword);
  return hash(password, ARGON2ID_OPTIONS);
}

/**
 * Verifies a plaintext password against a stored Argon2id hash.
 *
 * A malformed or foreign hash is treated as a non-match rather than an error so
 * authentication fails closed without leaking implementation details.
 */
export async function verifyPassword(
  passwordHash: string,
  plainPassword: string,
): Promise<boolean> {
  try {
    return await verify(passwordHash, plainPassword);
  } catch {
    return false;
  }
}

