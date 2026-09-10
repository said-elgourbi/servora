import { AUTH_SESSION_PLATFORMS, isSessionActive, type AuthSession } from './auth.types.js';

function session(overrides: Partial<AuthSession> = {}): AuthSession {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    userId: '22222222-2222-2222-2222-222222222222',
    platform: 'ANDROID',
    deviceId: 'device-1',
    deviceName: null,
    appVersion: null,
    ipAddress: null,
    userAgent: null,
    createdAt: new Date('2026-01-01T00:00:00.000Z'),
    updatedAt: new Date('2026-01-01T00:00:00.000Z'),
    lastUsedAt: new Date('2026-01-01T00:00:00.000Z'),
    expiresAt: new Date('2026-01-31T00:00:00.000Z'),
    revokedAt: null,
    ...overrides,
  };
}

describe('auth session vocabulary', () => {
  it('lists exactly the supported client platforms', () => {
    expect([...AUTH_SESSION_PLATFORMS]).toEqual(['ANDROID', 'WEB']);
  });
});

describe('isSessionActive', () => {
  const now = new Date('2026-01-15T00:00:00.000Z');

  it('is active while neither revoked nor expired', () => {
    expect(isSessionActive(session(), now)).toBe(true);
  });

  it('is inactive once revoked', () => {
    expect(
      isSessionActive(session({ revokedAt: new Date('2026-01-10T00:00:00.000Z') }), now),
    ).toBe(false);
  });

  it('is inactive once expired', () => {
    expect(
      isSessionActive(session({ expiresAt: new Date('2026-01-14T00:00:00.000Z') }), now),
    ).toBe(false);
  });

  it('treats the exact expiry instant as expired', () => {
    expect(isSessionActive(session({ expiresAt: now }), now)).toBe(false);
  });
});
