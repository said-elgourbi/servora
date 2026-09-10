import { HttpStatus } from '@nestjs/common';
import { describe, expect, it } from 'vitest';
import { AUTH_ERROR_CODES, AuthApiError } from './auth-error.js';

describe('AuthApiError', () => {
  it('maps a validation failure to 400 with a stable code', () => {
    const error = AuthApiError.validationFailed(
      '"email" must be an email address.',
    );

    expect(error.getStatus()).toBe(HttpStatus.BAD_REQUEST);
    expect(error.code).toBe(AUTH_ERROR_CODES.VALIDATION_FAILED);
    expect(error.getResponse()).toEqual({
      statusCode: HttpStatus.BAD_REQUEST,
      code: 'VALIDATION_FAILED',
      message: '"email" must be an email address.',
    });
  });

  it('maps every credential failure to 401', () => {
    const failures = [
      AuthApiError.invalidCredentials(),
      AuthApiError.refreshTokenInvalid(),
      AuthApiError.unauthenticated(),
    ];

    for (const failure of failures) {
      expect(failure.getStatus()).toBe(HttpStatus.UNAUTHORIZED);
    }
  });

  it('does not disclose why credentials were rejected', () => {
    const messageOf = (error: AuthApiError): string =>
      (error.getResponse() as { message: string }).message;

    // "wrong password" and "unknown email" must be indistinguishable.
    expect(messageOf(AuthApiError.invalidCredentials())).not.toMatch(
      /unknown|not found|does not exist|no account/i,
    );
    // Nor may a rejected token reveal whether it was revoked, expired or reused.
    expect(messageOf(AuthApiError.refreshTokenInvalid())).not.toMatch(
      /unknown|revoked|expired|rotated|reused/i,
    );
  });

  it('keeps every code unique and machine-readable', () => {
    const codes = Object.values(AUTH_ERROR_CODES);

    expect(new Set(codes).size).toBe(codes.length);
    for (const code of codes) {
      expect(code).toMatch(/^[A-Z][A-Z_]*$/);
    }
  });
});
