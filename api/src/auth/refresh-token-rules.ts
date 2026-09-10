import type { AuthRefreshToken } from './auth.types.js';

/**
 * Rotation and expiry rule for a stored refresh token (`ADR-004` D4, D6).
 *
 * Reuse needs no separate flag: rotating a token sets `revoked_at`, so a replayed
 * token is simply no longer usable. That is exactly what makes reuse detectable
 * instead of indistinguishable from a valid token.
 */
export function isRefreshTokenUsable(
  refreshToken: Pick<AuthRefreshToken, 'revokedAt' | 'expiresAt'>,
  now: Date,
): boolean {
  return (
    refreshToken.revokedAt === null &&
    refreshToken.expiresAt.getTime() > now.getTime()
  );
}

/**
 * Expiry of a rotated successor.
 *
 * Rotation must not silently extend a session (`ADR-004` D9): the successor never
 * outlives the session it belongs to, even when the configured refresh lifetime
 * would allow a later instant.
 */
export function successorExpiresAt(
  now: Date,
  sessionExpiresAt: Date,
  refreshTokenLifetimeMs: number,
): Date {
  const candidate = new Date(now.getTime() + refreshTokenLifetimeMs);
  return candidate.getTime() > sessionExpiresAt.getTime()
    ? sessionExpiresAt
    : candidate;
}
