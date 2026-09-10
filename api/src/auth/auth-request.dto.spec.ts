import { describe, expect, it } from 'vitest';
import { AUTH_ERROR_CODES, AuthApiError } from './auth-error.js';
import { parseRefreshRequest, parseSignInRequest } from './auth-request.dto.js';

function validSignInBody(): Record<string, unknown> {
  return {
    email: '  Tech@Example.com ',
    password: '  pass word  ',
    device: {
      platform: 'ANDROID',
      deviceId: ' install-42 ',
      deviceName: ' Pixel 8 ',
      appVersion: ' 1.0.0 ',
    },
  };
}

function capture(run: () => unknown): AuthApiError {
  try {
    run();
  } catch (error) {
    if (error instanceof AuthApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('expected the parser to reject the payload');
}

function expectValidationFailure(field: string, run: () => unknown): void {
  const failure = capture(run);

  expect(failure.code).toBe(AUTH_ERROR_CODES.VALIDATION_FAILED);
  expect(messageOf(failure)).toContain(`"${field}"`);
}

function messageOf(error: AuthApiError): string {
  return (error.getResponse() as { message: string }).message;
}

describe('parseSignInRequest', () => {
  it('narrows and normalizes a valid payload', () => {
    const parsed = parseSignInRequest(validSignInBody());

    expect(parsed.email).toBe('tech@example.com');
    expect(parsed.device).toEqual({
      platform: 'ANDROID',
      deviceId: 'install-42',
      deviceName: 'Pixel 8',
      appVersion: '1.0.0',
    });
  });

  it('keeps the password exactly as submitted', () => {
    const parsed = parseSignInRequest(validSignInBody());

    // Trimming a password would silently change the credential.
    expect(parsed.password).toBe('  pass word  ');
  });

  it('treats the optional device metadata as absent rather than empty', () => {
    const parsed = parseSignInRequest({
      email: 'tech@example.com',
      password: 'pw',
      device: { platform: 'WEB', deviceId: 'install-1', deviceName: '   ' },
    });

    expect(parsed.device.deviceName).toBeNull();
    expect(parsed.device.appVersion).toBeNull();
  });

  it('rejects a payload that is not a valid sign-in request', () => {
    const invalidCases: readonly { field: string; body: unknown }[] = [
      { field: 'body', body: null },
      { field: 'body', body: ['email'] },
      { field: 'email', body: { ...validSignInBody(), email: 'not-an-email' } },
      { field: 'email', body: { ...validSignInBody(), email: 42 } },
      {
        field: 'email',
        body: { ...validSignInBody(), email: `${'a'.repeat(320)}@example.com` },
      },
      { field: 'password', body: { ...validSignInBody(), password: '' } },
      {
        field: 'password',
        body: { ...validSignInBody(), password: 'x'.repeat(1025) },
      },
      { field: 'device', body: { ...validSignInBody(), device: 'ANDROID' } },
      {
        field: 'device.platform',
        body: {
          ...validSignInBody(),
          device: { platform: 'IOS', deviceId: 'd' },
        },
      },
      {
        field: 'device.deviceId',
        body: { ...validSignInBody(), device: { platform: 'ANDROID' } },
      },
      {
        field: 'device.appVersion',
        body: {
          ...validSignInBody(),
          device: {
            platform: 'ANDROID',
            deviceId: 'd',
            appVersion: 'x'.repeat(51),
          },
        },
      },
    ];

    for (const { field, body } of invalidCases) {
      expectValidationFailure(field, () => parseSignInRequest(body));
    }
  });

  it('never echoes the submitted password in a validation error', () => {
    const password = 's3cret-'.repeat(200);

    const failure = capture(() =>
      parseSignInRequest({ ...validSignInBody(), password }),
    );

    expect(messageOf(failure)).not.toContain('s3cret');
  });
});

describe('parseRefreshRequest', () => {
  it('accepts a submitted refresh token', () => {
    expect(parseRefreshRequest({ refreshToken: 'opaque-token' })).toEqual({
      refreshToken: 'opaque-token',
    });
  });

  it('rejects a payload that is not a valid refresh request', () => {
    const invalidCases: readonly { field: string; body: unknown }[] = [
      { field: 'body', body: undefined },
      { field: 'refreshToken', body: {} },
      { field: 'refreshToken', body: { refreshToken: '' } },
      { field: 'refreshToken', body: { refreshToken: 42 } },
      { field: 'refreshToken', body: { refreshToken: 'x'.repeat(513) } },
    ];

    for (const { field, body } of invalidCases) {
      expectValidationFailure(field, () => parseRefreshRequest(body));
    }
  });

  it('never echoes the submitted token in a validation error', () => {
    const refreshToken = 'r3fresh-'.repeat(100);

    const failure = capture(() => parseRefreshRequest({ refreshToken }));

    expect(messageOf(failure)).not.toContain('r3fresh');
  });
});
