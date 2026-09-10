import type { InferInsertModel, InferSelectModel } from 'drizzle-orm';
import {
  authRateLimitEvents,
  authRefreshTokens,
  authSessions,
  passwordResetTokens,
  phoneOtpChallenges,
} from '../database/schema.js';

// Domain types for the authentication slice. See
// docs/domain/authentication-domain-model.md.
//
// These types mirror the database rows, so they contain credential material
// (`tokenHash`). Nothing in this file may be serialized to a client: the API
// boundary uses `auth.dto.ts`, which selects public fields explicitly.

export type AuthSession = InferSelectModel<typeof authSessions>;
export type NewAuthSession = InferInsertModel<typeof authSessions>;

export type AuthRefreshToken = InferSelectModel<typeof authRefreshTokens>;
export type NewAuthRefreshToken = InferInsertModel<typeof authRefreshTokens>;

export type PasswordResetToken = InferSelectModel<typeof passwordResetTokens>;
export type NewPasswordResetToken = InferInsertModel<typeof passwordResetTokens>;

export type PhoneOtpChallenge = InferSelectModel<typeof phoneOtpChallenges>;
export type NewPhoneOtpChallenge = InferInsertModel<typeof phoneOtpChallenges>;

export type AuthRateLimitEvent = InferSelectModel<typeof authRateLimitEvents>;
export type NewAuthRateLimitEvent = InferInsertModel<typeof authRateLimitEvents>;

/**
 * Stable, machine-readable rate-limit scopes (`BR-045`). One scope per authentication entry
 * point that may be attempted repeatedly, so the same subject hash in two scopes counts
 * independently. Stored in `auth_rate_limit_events.scope` and enforced by a CHECK constraint.
 */
export const AUTH_RATE_LIMIT_SCOPES = [
  'PASSWORD_RESET_REQUEST',
  'SMS_OTP_REQUEST',
] as const;
export type AuthRateLimitScope = (typeof AUTH_RATE_LIMIT_SCOPES)[number];

/**
 * Stable, machine-readable client platform codes stored in
 * `auth_sessions.platform`. Presentation labels are resolved from these codes, so
 * they are never translated and never compared against display text.
 */
export const AUTH_SESSION_PLATFORMS = ['ANDROID', 'WEB'] as const;
export type AuthSessionPlatform = (typeof AUTH_SESSION_PLATFORMS)[number];

/**
 * True while a session may still be used: it has neither been revoked nor
 * expired. Expiry is evaluated against the supplied clock so every caller uses
 * the server's notion of "now" instead of a client-supplied timestamp.
 */
export function isSessionActive(session: AuthSession, now: Date): boolean {
  return session.revokedAt === null && session.expiresAt.getTime() > now.getTime();
}

/**
 * The credential pair produced by a successful authentication.
 *
 * The raw `refreshToken` is a credential: it is returned to the client once, is
 * never persisted (only its hash is) and must never be logged. Field names match
 * the wire contract shared with the Android and Angular clients.
 */
export interface IssuedAuthTokens {
  readonly accessToken: string;
  readonly accessTokenExpiresAt: string;
  readonly refreshToken: string;
}
