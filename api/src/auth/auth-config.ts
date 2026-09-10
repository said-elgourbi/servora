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
    jwtSecret: requireJwtSecret(env),
  };
}
