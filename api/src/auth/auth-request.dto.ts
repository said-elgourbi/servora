import {
  AUTH_SESSION_PLATFORMS,
  type AuthSessionPlatform,
} from './auth.types.js';
import { AuthApiError } from './auth-error.js';

/**
 * Request contracts for `/auth` (`docs/api/authentication.md` §3).
 *
 * Validation is hand-written rather than annotation-driven: the project has no
 * validation library, so each parser either returns a narrowed, immutable value or
 * rejects the payload as a whole (`docs/api/authentication.md` §2 C2).
 *
 * No rejection message ever echoes the submitted value — only the field name — so
 * a malformed password or refresh token cannot be reflected back or logged.
 */
const MAX_EMAIL_LENGTH = 320;
const MAX_PASSWORD_LENGTH = 1024;
const MAX_PLATFORM_LENGTH = 20;
const MAX_DEVICE_ID_LENGTH = 200;
const MAX_DEVICE_NAME_LENGTH = 200;
const MAX_APP_VERSION_LENGTH = 50;
const MAX_REFRESH_TOKEN_LENGTH = 512;

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Device/installation context a client reports when it signs in. */
export interface AuthDeviceContext {
  readonly platform: AuthSessionPlatform;
  readonly deviceId: string;
  readonly deviceName: string | null;
  readonly appVersion: string | null;
}

export interface SignInRequestDto {
  readonly email: string;
  readonly password: string;
  readonly device: AuthDeviceContext;
}

export interface RefreshRequestDto {
  readonly refreshToken: string;
}

export function parseSignInRequest(body: unknown): SignInRequestDto {
  const source = requireRecord(body, 'body');

  const email = requireString(source, 'email', MAX_EMAIL_LENGTH).toLowerCase();
  if (!EMAIL_PATTERN.test(email)) {
    fail('email', 'must be an email address');
  }

  const device = requireRecord(source['device'], 'device');
  const platform = requireString(
    device,
    'platform',
    MAX_PLATFORM_LENGTH,
    'device.',
  );
  if (!isAuthSessionPlatform(platform)) {
    fail(
      'device.platform',
      `must be one of ${AUTH_SESSION_PLATFORMS.join(', ')}`,
    );
  }

  return {
    email,
    // A password is a secret: it is never trimmed or normalized.
    password: requireSecret(source, 'password', MAX_PASSWORD_LENGTH),
    device: {
      platform,
      deviceId: requireString(
        device,
        'deviceId',
        MAX_DEVICE_ID_LENGTH,
        'device.',
      ),
      deviceName: optionalString(
        device,
        'deviceName',
        MAX_DEVICE_NAME_LENGTH,
        'device.',
      ),
      appVersion: optionalString(
        device,
        'appVersion',
        MAX_APP_VERSION_LENGTH,
        'device.',
      ),
    },
  };
}

export function parseRefreshRequest(body: unknown): RefreshRequestDto {
  const source = requireRecord(body, 'body');
  return {
    refreshToken: requireSecret(
      source,
      'refreshToken',
      MAX_REFRESH_TOKEN_LENGTH,
    ),
  };
}

function isAuthSessionPlatform(value: string): value is AuthSessionPlatform {
  return (AUTH_SESSION_PLATFORMS as readonly string[]).includes(value);
}

function fail(field: string, problem: string): never {
  throw AuthApiError.validationFailed(`"${field}" ${problem}.`);
}

function requireRecord(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    fail(field, 'must be a JSON object');
  }
  return value as Record<string, unknown>;
}

function requireString(
  source: Record<string, unknown>,
  key: string,
  maxLength: number,
  fieldPrefix = '',
): string {
  const value = source[key];
  if (typeof value !== 'string') {
    fail(`${fieldPrefix}${key}`, 'must be a string');
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    fail(`${fieldPrefix}${key}`, 'must not be empty');
  }
  if (trimmed.length > maxLength) {
    fail(`${fieldPrefix}${key}`, `must be at most ${maxLength} characters`);
  }
  return trimmed;
}

function requireSecret(
  source: Record<string, unknown>,
  field: string,
  maxLength: number,
): string {
  const value = source[field];
  if (typeof value !== 'string') {
    fail(field, 'must be a string');
  }
  if (value.length === 0) {
    fail(field, 'must not be empty');
  }
  if (value.length > maxLength) {
    fail(field, `must be at most ${maxLength} characters`);
  }
  return value;
}

function optionalString(
  source: Record<string, unknown>,
  key: string,
  maxLength: number,
  fieldPrefix = '',
): string | null {
  const value = source[key];
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== 'string') {
    fail(`${fieldPrefix}${key}`, 'must be a string');
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return null;
  }
  if (trimmed.length > maxLength) {
    fail(`${fieldPrefix}${key}`, `must be at most ${maxLength} characters`);
  }
  return trimmed;
}
