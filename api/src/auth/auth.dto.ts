import type { AuthSession, AuthSessionPlatform } from './auth.types.js';

/**
 * Public API shape of an authentication session.
 *
 * SECURITY: a session row currently holds no credential material, but the mapper
 * still selects fields explicitly so a future column cannot start leaking simply
 * because it was added to the table. Refresh and password-reset credentials have
 * no DTO at all: only their hashes are stored, and a raw token exists solely
 * inside the single response that hands it to the client.
 */
export interface AuthSessionDto {
  id: string;
  userId: string;
  platform: AuthSessionPlatform;
  deviceId: string;
  deviceName: string | null;
  appVersion: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string;
  updatedAt: string;
  lastUsedAt: string;
  expiresAt: string;
  revokedAt: string | null;
}

/**
 * Public view of the authenticated principal. It carries only the stable identity and
 * effective permission codes the client needs for permission-aware UI.
 */
export interface AuthMeDto {
  userId: string;
  permissions: readonly string[];
}

export function toAuthSessionDto(session: AuthSession): AuthSessionDto {
  return {
    id: session.id,
    userId: session.userId,
    platform: session.platform as AuthSessionPlatform,
    deviceId: session.deviceId,
    deviceName: session.deviceName,
    appVersion: session.appVersion,
    ipAddress: session.ipAddress,
    userAgent: session.userAgent,
    createdAt: session.createdAt.toISOString(),
    updatedAt: session.updatedAt.toISOString(),
    lastUsedAt: session.lastUsedAt.toISOString(),
    expiresAt: session.expiresAt.toISOString(),
    revokedAt:
      session.revokedAt === null ? null : session.revokedAt.toISOString(),
  };
}
