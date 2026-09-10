import { describe, expect, it } from 'vitest';
import {
  isRefreshTokenUsable,
  successorExpiresAt,
} from './refresh-token-rules.js';

const NOW = new Date('2026-09-10T12:00:00.000Z');

describe('isRefreshTokenUsable', () => {
  it('accepts a token that is neither revoked nor expired', () => {
    const token = {
      revokedAt: null,
      expiresAt: new Date(NOW.getTime() + 60_000),
    };

    expect(isRefreshTokenUsable(token, NOW)).toBe(true);
  });

  it('rejects a revoked token, which is how rotation and reuse are detected', () => {
    const token = {
      revokedAt: NOW,
      expiresAt: new Date(NOW.getTime() + 60_000),
    };

    expect(isRefreshTokenUsable(token, NOW)).toBe(false);
  });

  it('rejects a token whose expiry has passed', () => {
    const token = { revokedAt: null, expiresAt: new Date(NOW.getTime() - 1) };

    expect(isRefreshTokenUsable(token, NOW)).toBe(false);
  });

  it('rejects a token expiring exactly now', () => {
    const token = { revokedAt: null, expiresAt: new Date(NOW.getTime()) };

    expect(isRefreshTokenUsable(token, NOW)).toBe(false);
  });
});

describe('successorExpiresAt', () => {
  const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;

  it('never lets a rotated token outlive its session', () => {
    const sessionExpiresAt = new Date(NOW.getTime() + 60_000);

    const expiry = successorExpiresAt(NOW, sessionExpiresAt, THIRTY_DAYS_MS);

    expect(expiry).toEqual(sessionExpiresAt);
  });

  it('uses the configured lifetime when it ends before the session does', () => {
    const sessionExpiresAt = new Date(NOW.getTime() + THIRTY_DAYS_MS * 2);

    const expiry = successorExpiresAt(NOW, sessionExpiresAt, THIRTY_DAYS_MS);

    expect(expiry.getTime()).toBe(NOW.getTime() + THIRTY_DAYS_MS);
  });
});
