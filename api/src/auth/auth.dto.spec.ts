import { toAuthSessionDto } from './auth.dto.js';
import type { AuthSession } from './auth.types.js';

const row: AuthSession = {
  id: '11111111-1111-1111-1111-111111111111',
  userId: '22222222-2222-2222-2222-222222222222',
  platform: 'WEB',
  deviceId: 'device-1',
  deviceName: 'Office desktop',
  appVersion: '1.4.0',
  ipAddress: '203.0.113.10',
  userAgent: 'Mozilla/5.0',
  createdAt: new Date('2026-01-01T10:00:00.000Z'),
  updatedAt: new Date('2026-01-01T10:00:00.000Z'),
  lastUsedAt: new Date('2026-01-02T11:30:00.000Z'),
  expiresAt: new Date('2026-01-31T10:00:00.000Z'),
  revokedAt: null,
};

describe('toAuthSessionDto', () => {
  it('exposes exactly the public session fields', () => {
    expect(Object.keys(toAuthSessionDto(row)).sort()).toEqual([
      'appVersion',
      'createdAt',
      'deviceId',
      'deviceName',
      'expiresAt',
      'id',
      'ipAddress',
      'lastUsedAt',
      'platform',
      'revokedAt',
      'updatedAt',
      'userAgent',
      'userId',
    ]);
  });

  it('drops credential columns when they are present on the row', () => {
    const withCredentials = {
      ...row,
      tokenHash: 'deadbeef',
      passwordHash: '$argon2id$v=19$secret',
    } as AuthSession;

    const serialized = JSON.stringify(toAuthSessionDto(withCredentials));

    expect(serialized).not.toContain('deadbeef');
    expect(serialized).not.toContain('argon2');
    expect(serialized).not.toContain('tokenHash');
    expect(serialized).not.toContain('passwordHash');
  });

  it('serializes timestamps as ISO-8601 UTC strings', () => {
    const dto = toAuthSessionDto(row);

    expect(dto.createdAt).toBe('2026-01-01T10:00:00.000Z');
    expect(dto.updatedAt).toBe('2026-01-01T10:00:00.000Z');
    expect(dto.lastUsedAt).toBe('2026-01-02T11:30:00.000Z');
    expect(dto.expiresAt).toBe('2026-01-31T10:00:00.000Z');
  });

  it('maps a missing revocation to null', () => {
    expect(toAuthSessionDto(row).revokedAt).toBeNull();
  });

  it('serializes a revoked session with its revocation instant', () => {
    const dto = toAuthSessionDto({
      ...row,
      revokedAt: new Date('2026-01-05T08:00:00.000Z'),
    });

    expect(dto.revokedAt).toBe('2026-01-05T08:00:00.000Z');
  });
});
