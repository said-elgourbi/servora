import { parseDuration } from '../config/duration.js';

// Authentication lifetimes are configuration rather than hard-coded magic
// numbers, so a deployment can tighten them without a code change.
//
// The defaults are the approved authentication lifetimes (see
// docs/domain/authentication-domain-model.md):
//   ACCESS_TOKEN_LIFETIME=15m          short-lived access token
//   SESSION_LIFETIME=30d               how long an idle session stays usable
//   PASSWORD_RESET_TOKEN_LIFETIME=30m  how long a reset link stays valid
//
// They are validated by their own loader instead of `AppConfig` because only the
// authentication module consumes them; `AppConfig` stays focused on process-wide
// runtime configuration.

export const DEFAULT_ACCESS_TOKEN_LIFETIME = '15m';
export const DEFAULT_SESSION_LIFETIME = '30d';
export const DEFAULT_PASSWORD_RESET_TOKEN_LIFETIME = '30m';

// Approved authentication-flow limits (`BR-019`, `BR-043`, `BR-045`). Like the lifetimes
// above they are configuration, so a deployment may tighten them without a product change;
// the defaults are the values product ownership approved.

/** Failed verifications accepted for one password-reset credential (`BR-043`). */
export const DEFAULT_PASSWORD_RESET_MAX_ATTEMPTS = 5;

/** How long an SMS one-time password stays usable (`BR-019`). */
export const DEFAULT_SMS_OTP_LIFETIME = '10m';

/** Failed verifications accepted for one SMS one-time password (`BR-019`). */
export const DEFAULT_SMS_OTP_MAX_ATTEMPTS = 5;

/** Shortest interval between two OTP requests for the same number (`BR-019`). */
export const DEFAULT_SMS_OTP_RESEND_COOLDOWN = '60s';

/** Short rolling window applied to authentication attempts (`BR-045`). */
export const DEFAULT_AUTH_RATE_LIMIT_SHORT_WINDOW = '15m';
export const DEFAULT_AUTH_RATE_LIMIT_SHORT_MAX_ATTEMPTS = 3;

/** Long rolling window applied to authentication attempts (`BR-045`). */
export const DEFAULT_AUTH_RATE_LIMIT_LONG_WINDOW = '24h';
export const DEFAULT_AUTH_RATE_LIMIT_LONG_MAX_ATTEMPTS = 10;

/**
 * Minimum accepted length for `JWT_SECRET`. The access token is the only value
 * signed with a symmetric key, so a short, guessable secret must never reach a
 * running deployment.
 */
export const MIN_JWT_SECRET_LENGTH = 32;

export interface AuthConfig {
  readonly accessTokenLifetimeMs: number;
  readonly sessionLifetimeMs: number;
  readonly passwordResetTokenLifetimeMs: number;
  readonly passwordResetMaxAttempts: number;
  readonly smsOtpLifetimeMs: number;
  readonly smsOtpMaxAttempts: number;
  readonly smsOtpResendCooldownMs: number;
  readonly authRateLimitShortWindowMs: number;
  readonly authRateLimitShortMaxAttempts: number;
  readonly authRateLimitLongWindowMs: number;
  readonly authRateLimitLongMaxAttempts: number;
  readonly jwtSecret: string;
}

/**
 * Reads the HS256 signing key.
 *
 * There is deliberately no development fallback: a built-in default would
 * silently become a production secret the moment a deployment forgot to set it.
 * A missing or weak secret therefore fails at startup instead (`BR-007`,
 * `dev.md` §5).
 */
function requireJwtSecret(env: NodeJS.ProcessEnv): string {
  const value = env.JWT_SECRET?.trim();
  if (!value) {
    throw new Error(
      `JWT_SECRET is required: provide at least ${MIN_JWT_SECRET_LENGTH} characters of high-entropy secret.`,
    );
  }
  if (value.length < MIN_JWT_SECRET_LENGTH) {
    throw new Error(
      `JWT_SECRET must be at least ${MIN_JWT_SECRET_LENGTH} characters.`,
    );
  }
  return value;
}

function parseLifetime(
  env: NodeJS.ProcessEnv,
  name: string,
  fallback: string,
): number {
  const value = env[name]?.trim() || fallback;
  return parseDuration(value, name);
}

/**
 * Reads a positive whole-number limit.
 *
 * A limit of zero would silently disable a security control (`BR-045`), so it is rejected
 * at startup like a malformed duration rather than accepted as "no attempts allowed".
 */
function parseCount(
  env: NodeJS.ProcessEnv,
  name: string,
  fallback: number,
): number {
  const raw = env[name]?.trim();
  if (raw === undefined || raw.length === 0) {
    return fallback;
  }

  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(
      `Invalid ${name} "${raw}". Expected a whole number greater than zero.`,
    );
  }
  return value;
}

/**
 * Loads and validates the authentication lifetimes from the environment.
 *
 * Fails fast with a descriptive error when a configured lifetime is malformed so
 * an unusable token lifetime never reaches production by accident.
 */
export function loadAuthConfig(
  env: NodeJS.ProcessEnv = process.env,
): AuthConfig {
  return {
    accessTokenLifetimeMs: parseLifetime(
      env,
      'ACCESS_TOKEN_LIFETIME',
      DEFAULT_ACCESS_TOKEN_LIFETIME,
    ),
    sessionLifetimeMs: parseLifetime(
      env,
      'SESSION_LIFETIME',
      DEFAULT_SESSION_LIFETIME,
    ),
    passwordResetTokenLifetimeMs: parseLifetime(
      env,
      'PASSWORD_RESET_TOKEN_LIFETIME',
      DEFAULT_PASSWORD_RESET_TOKEN_LIFETIME,
    ),
    passwordResetMaxAttempts: parseCount(
      env,
      'PASSWORD_RESET_MAX_ATTEMPTS',
      DEFAULT_PASSWORD_RESET_MAX_ATTEMPTS,
    ),
    smsOtpLifetimeMs: parseLifetime(
      env,
      'SMS_OTP_LIFETIME',
      DEFAULT_SMS_OTP_LIFETIME,
    ),
    smsOtpMaxAttempts: parseCount(
      env,
      'SMS_OTP_MAX_ATTEMPTS',
      DEFAULT_SMS_OTP_MAX_ATTEMPTS,
    ),
    smsOtpResendCooldownMs: parseLifetime(
      env,
      'SMS_OTP_RESEND_COOLDOWN',
      DEFAULT_SMS_OTP_RESEND_COOLDOWN,
    ),
    authRateLimitShortWindowMs: parseLifetime(
      env,
      'AUTH_RATE_LIMIT_SHORT_WINDOW',
      DEFAULT_AUTH_RATE_LIMIT_SHORT_WINDOW,
    ),
    authRateLimitShortMaxAttempts: parseCount(
      env,
      'AUTH_RATE_LIMIT_SHORT_MAX_ATTEMPTS',
      DEFAULT_AUTH_RATE_LIMIT_SHORT_MAX_ATTEMPTS,
    ),
    authRateLimitLongWindowMs: parseLifetime(
      env,
      'AUTH_RATE_LIMIT_LONG_WINDOW',
      DEFAULT_AUTH_RATE_LIMIT_LONG_WINDOW,
    ),
    authRateLimitLongMaxAttempts: parseCount(
      env,
      'AUTH_RATE_LIMIT_LONG_MAX_ATTEMPTS',
      DEFAULT_AUTH_RATE_LIMIT_LONG_MAX_ATTEMPTS,
    ),
    jwtSecret: requireJwtSecret(env),
  };
}
