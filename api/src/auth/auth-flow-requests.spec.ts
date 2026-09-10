import { describe, expect, it } from 'vitest';
import { AuthApiError } from './auth-error.js';
import {
  parsePasswordResetCompleteRequest,
  parsePasswordResetRequest,
  parsePasswordResetVerifyRequest,
  parseSmsOtpRequest,
  parseSmsOtpVerifyRequest,
} from './auth-request.dto.js';

function rejection(action: () => unknown): AuthApiError {
  try {
    action();
  } catch (error) {
    expect(error).toBeInstanceOf(AuthApiError);
    return error as AuthApiError;
  }
  throw new Error('expected the request to be rejected');
}

describe('password-reset request contracts', () => {
  it('accepts and normalises the identity', () => {
    expect(parsePasswordResetRequest({ email: '  User@Example.COM ' })).toEqual(
      {
        email: 'user@example.com',
      },
    );
  });

  it.each([
    ['a missing body', undefined],
    ['a non-object body', 'user@example.com'],
    ['a missing email', {}],
    ['a value that is not an address', { email: 'not-an-address' }],
  ])('rejects %s as VALIDATION_FAILED', (_reason, body) => {
    expect(rejection(() => parsePasswordResetRequest(body)).code).toBe(
      'VALIDATION_FAILED',
    );
  });

  it('requires the code to be six digits', () => {
    expect(
      parsePasswordResetVerifyRequest({ email: 'a@b.co', code: '000123' }),
    ).toEqual({ email: 'a@b.co', code: '000123' });

    for (const code of ['12345', '1234567', 'abcdef', '12345a', '']) {
      expect(
        rejection(() =>
          parsePasswordResetVerifyRequest({ email: 'a@b.co', code }),
        ).code,
      ).toBe('VALIDATION_FAILED');
    }
  });

  it('applies the existing password policy to the new password', () => {
    expect(
      parsePasswordResetCompleteRequest({
        email: 'a@b.co',
        code: '123456',
        newPassword: 'eight-characters',
      }),
    ).toEqual({
      email: 'a@b.co',
      code: '123456',
      newPassword: 'eight-characters',
    });

    // The project's policy is `MIN_PASSWORD_LENGTH = 8`; the prototype's six characters are
    // not a rule (`BR-043`: no new password policy is introduced).
    const tooShort = rejection(() =>
      parsePasswordResetCompleteRequest({
        email: 'a@b.co',
        code: '123456',
        newPassword: 'short',
      }),
    );
    expect(tooShort.code).toBe('VALIDATION_FAILED');
    expect(JSON.stringify(tooShort.getResponse())).toContain('newPassword');
  });

  it('never echoes the submitted password', () => {
    const submitted = 'zzzzzzzzzzzzzzzzzzzzzzzzzzzzzz';

    const error = rejection(() =>
      parsePasswordResetCompleteRequest({
        email: 'a@b.co',
        code: '123456',
        newPassword: `${submitted}${'x'.repeat(2000)}`,
      }),
    );

    expect(JSON.stringify(error.getResponse())).not.toContain(submitted);
  });
});

describe('SMS one-time-password request contracts', () => {
  it('normalises the phone number to E.164', () => {
    expect(parseSmsOtpRequest({ phone: '+1 (514) 555-0100' })).toEqual({
      phone: '+15145550100',
    });
  });

  it('rejects a number the server cannot interpret', () => {
    expect(
      rejection(() => parseSmsOtpRequest({ phone: '5145550100' })).code,
    ).toBe('VALIDATION_FAILED');
  });

  it('requires the same device context as a password sign-in', () => {
    const parsed = parseSmsOtpVerifyRequest({
      phone: '+15145550100',
      code: '123456',
      device: {
        platform: 'ANDROID',
        deviceId: 'installation-1',
        deviceName: 'Pixel 8',
        appVersion: '1.0.0',
      },
    });

    expect(parsed).toEqual({
      phone: '+15145550100',
      code: '123456',
      device: {
        platform: 'ANDROID',
        deviceId: 'installation-1',
        deviceName: 'Pixel 8',
        appVersion: '1.0.0',
      },
    });
  });

  it('rejects an unknown platform and a missing device', () => {
    expect(
      rejection(() =>
        parseSmsOtpVerifyRequest({
          phone: '+15145550100',
          code: '123456',
          device: { platform: 'IOS', deviceId: 'x' },
        }),
      ).code,
    ).toBe('VALIDATION_FAILED');

    expect(
      rejection(() =>
        parseSmsOtpVerifyRequest({ phone: '+15145550100', code: '123456' }),
      ).code,
    ).toBe('VALIDATION_FAILED');
  });
});
