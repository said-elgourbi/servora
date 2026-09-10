import { describe, expect, it } from 'vitest';
import { loadAuthConfig } from './auth-config.js';

const SECRET = 'unit-test-jwt-secret-value-at-least-32-characters';

function env(overrides: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  return { JWT_SECRET: SECRET, ...overrides };
}

/**
 * The approved authentication-flow limits (`BR-019`, `BR-043`, `BR-045`) are configuration, so
 * their defaults and their rejection of an unusable value are both part of the contract.
 */
describe('authentication flow configuration', () => {
  it('defaults to the approved limits', () => {
    const config = loadAuthConfig(env());

    expect(config.passwordResetTokenLifetimeMs).toBe(30 * 60_000);
    expect(config.passwordResetMaxAttempts).toBe(5);

    expect(config.smsOtpLifetimeMs).toBe(10 * 60_000);
    expect(config.smsOtpMaxAttempts).toBe(5);
    expect(config.smsOtpResendCooldownMs).toBe(60_000);

    expect(config.authRateLimitShortWindowMs).toBe(15 * 60_000);
    expect(config.authRateLimitShortMaxAttempts).toBe(3);
    expect(config.authRateLimitLongWindowMs).toBe(24 * 60 * 60_000);
    expect(config.authRateLimitLongMaxAttempts).toBe(10);
  });

  it('accepts a deployment that tightens the limits', () => {
    const config = loadAuthConfig(
      env({
        PASSWORD_RESET_MAX_ATTEMPTS: '3',
        SMS_OTP_LIFETIME: '5m',
        SMS_OTP_MAX_ATTEMPTS: '2',
        SMS_OTP_RESEND_COOLDOWN: '90s',
        AUTH_RATE_LIMIT_SHORT_WINDOW: '10m',
        AUTH_RATE_LIMIT_SHORT_MAX_ATTEMPTS: '1',
        AUTH_RATE_LIMIT_LONG_WINDOW: '12h',
        AUTH_RATE_LIMIT_LONG_MAX_ATTEMPTS: '4',
      }),
    );

    expect(config.passwordResetMaxAttempts).toBe(3);
    expect(config.smsOtpLifetimeMs).toBe(5 * 60_000);
    expect(config.smsOtpMaxAttempts).toBe(2);
    expect(config.smsOtpResendCooldownMs).toBe(90_000);
    expect(config.authRateLimitShortWindowMs).toBe(10 * 60_000);
    expect(config.authRateLimitShortMaxAttempts).toBe(1);
    expect(config.authRateLimitLongWindowMs).toBe(12 * 60 * 60_000);
    expect(config.authRateLimitLongMaxAttempts).toBe(4);
  });

  it.each([
    ['PASSWORD_RESET_MAX_ATTEMPTS', '0'],
    ['PASSWORD_RESET_MAX_ATTEMPTS', '-1'],
    ['PASSWORD_RESET_MAX_ATTEMPTS', 'many'],
    ['SMS_OTP_MAX_ATTEMPTS', '1.5'],
    ['AUTH_RATE_LIMIT_SHORT_MAX_ATTEMPTS', '0'],
    ['AUTH_RATE_LIMIT_LONG_MAX_ATTEMPTS', 'lots'],
  ])('rejects an unusable %s of %o', (name, value) => {
    // A limit of zero would silently disable a control rather than tighten it.
    expect(() => loadAuthConfig(env({ [name]: value }))).toThrowError();
  });

  it.each([
    ['SMS_OTP_LIFETIME', '10 minutes'],
    ['SMS_OTP_RESEND_COOLDOWN', '60'],
    ['AUTH_RATE_LIMIT_SHORT_WINDOW', '0m'],
  ])('rejects a malformed %s of %o', (name, value) => {
    expect(() => loadAuthConfig(env({ [name]: value }))).toThrowError();
  });
});
