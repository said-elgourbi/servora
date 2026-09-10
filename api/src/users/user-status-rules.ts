import type { UserStatus } from './user.types.js';

/**
 * Whether an account in this status may sign in.
 *
 * Decision (product owner, 2026-09-10): only an `ACTIVE` account authenticates.
 * `INACTIVE` and `SUSPENDED` accounts are refused, and the refusal is reported
 * exactly like a wrong password, so sign-in never tells an unauthenticated
 * caller which email addresses exist or what state they are in (`BR-018`).
 *
 * See `docs/decisions/005-sign-in-hardening-and-sms-port.md`.
 */
export function canUserSignIn(status: UserStatus): boolean {
  return status === 'ACTIVE';
}
