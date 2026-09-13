import {
  AUTH_SESSION_PLATFORMS,
  type AuthSessionPlatform,
} from './auth.types.js';
import { isWellFormedAuthCode } from './auth-code.js';
import { AuthApiError } from './auth-error.js';
import { normalizePhoneNumber } from './phone-number.js';
import { MIN_PASSWORD_LENGTH } from '../validation/domain-validation.js';

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
/** One-time codes are fixed at six digits (`BR-019`, `BR-043`); the cap is a sanity bound. */
const MAX_CODE_LENGTH = 16;

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

/**
 * `POST /auth/password-reset/request` (`BR-043`).
 *
 * The identity is the existing email/password identity (`BR-018`); the reset is delivered to
 * that same address. Whether the address belongs to an account is never decided here — the
 * parser only rejects a value that is not an address at all.
 */
export interface PasswordResetRequestDto {
  readonly email: string;
}

/** `POST /auth/password-reset/verify` (`BR-043`). */
export interface PasswordResetVerifyRequestDto {
  readonly email: string;
  readonly code: string;
}

/** `POST /auth/password-reset/complete` (`BR-043`). */
export interface PasswordResetCompleteRequestDto {
  readonly email: string;
  readonly code: string;
  readonly newPassword: string;
}

/** `POST /auth/sms/request` (`BR-019`). */
export interface SmsOtpRequestDto {
  readonly phone: string;
}

/** `POST /auth/sms/verify` (`BR-019`); the device context opens the resulting session. */
export interface SmsOtpVerifyRequestDto {
  readonly phone: string;
  readonly code: string;
  readonly device: AuthDeviceContext;
}

export function parsePasswordResetRequest(
  body: unknown,
): PasswordResetRequestDto {
  const source = requireRecord(body, 'body');
  return { email: requireEmail(source) };
}

export function parsePasswordResetVerifyRequest(
  body: unknown,
): PasswordResetVerifyRequestDto {
  const source = requireRecord(body, 'body');
  return { email: requireEmail(source), code: requireAuthCode(source) };
}

export function parsePasswordResetCompleteRequest(
  body: unknown,
): PasswordResetCompleteRequestDto {
  const source = requireRecord(body, 'body');
  return {
    email: requireEmail(source),
    code: requireAuthCode(source),
    // The new password is a secret, so it is never trimmed or normalised, and it is
    // validated by the existing password policy rather than a new one (`BR-043`).
    newPassword: requireNewPassword(source),
  };
}

export function parseSmsOtpRequest(body: unknown): SmsOtpRequestDto {
  const source = requireRecord(body, 'body');
  return { phone: normalizePhoneNumber(source['phone']) };
}

export function parseSmsOtpVerifyRequest(
  body: unknown,
): SmsOtpVerifyRequestDto {
  const source = requireRecord(body, 'body');
  return {
    phone: normalizePhoneNumber(source['phone']),
    code: requireAuthCode(source),
    device: parseDeviceContext(source['device']),
  };
}

function requireEmail(source: Record<string, unknown>): string {
  // Normalising the identity the same way sign-in does keeps one row addressable by one
  // spelling (`BR-018`), and never echoes the submitted value on rejection.
  const email = requireString(source, 'email', MAX_EMAIL_LENGTH).toLowerCase();
  if (!EMAIL_PATTERN.test(email)) {
    fail('email', 'must be an email address');
  }
  return email;
}

function requireAuthCode(source: Record<string, unknown>): string {
  const code = requireSecret(source, 'code', MAX_CODE_LENGTH);
  if (!isWellFormedAuthCode(code)) {
    // A malformed code is rejected before any identity is examined, so this response
    // cannot disclose whether an account exists (`BR-044`).
    fail('code', 'must be a 6-digit code');
  }
  return code;
}

function requireNewPassword(source: Record<string, unknown>): string {
  const value = source['newPassword'];
  if (value === undefined) {
    fail('newPassword', 'is required');
  }
  if (typeof value !== 'string') {
    fail('newPassword', 'must be a string');
  }
  if (value.length > MAX_PASSWORD_LENGTH) {
    fail('newPassword', `must be at most ${MAX_PASSWORD_LENGTH} characters`);
  }
  if (value.length < MIN_PASSWORD_LENGTH) {
    fail('newPassword', `must be at least ${MIN_PASSWORD_LENGTH} characters`);
  }
  return value;
}

function parseDeviceContext(value: unknown): AuthDeviceContext {
  const device = requireRecord(value, 'device');
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
