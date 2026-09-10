import { describe, expect, it } from 'vitest';
import {
  AUTH_CODE_LENGTH,
  authCodeDigestsEqual,
  generateAuthCode,
  hashAuthCode,
  isWellFormedAuthCode,
} from './auth-code.js';

const SECRET = 'unit-test-auth-code-secret-at-least-32-chars';
const OWNER = '3f1c8b0e-0000-4000-8000-000000000001';

describe('one-time authentication codes', () => {
  describe('generateAuthCode', () => {
    it('always produces exactly six numeric digits', () => {
      for (let index = 0; index < 500; index += 1) {
        const code = generateAuthCode();

        expect(code).toHaveLength(AUTH_CODE_LENGTH);
        expect(code).toMatch(/^[0-9]{6}$/);
      }
    });

    it('reaches values at both ends of the range', () => {
      // A generator that dropped leading zeros would never produce a code starting with
      // "0", and a biased one would not cover the space; both would weaken the code.
      const codes = new Set(
        Array.from({ length: 4000 }, () => generateAuthCode()),
      );

      expect([...codes].some((code) => code.startsWith('0'))).toBe(true);
      expect([...codes].some((code) => code.startsWith('9'))).toBe(true);
      expect(codes.size).toBeGreaterThan(3000);
    });
  });

  describe('hashAuthCode', () => {
    it('is stable for the same secret, scope, owner and code', () => {
      expect(hashAuthCode(SECRET, 'SMS_OTP', OWNER, '123456')).toBe(
        hashAuthCode(SECRET, 'SMS_OTP', OWNER, '123456'),
      );
    });

    it('separates purposes, owners and secrets', () => {
      const sms = hashAuthCode(SECRET, 'SMS_OTP', OWNER, '123456');
      const reset = hashAuthCode(SECRET, 'PASSWORD_RESET', OWNER, '123456');
      const otherOwner = hashAuthCode(
        SECRET,
        'SMS_OTP',
        '3f1c8b0e-0000-4000-8000-000000000002',
        '123456',
      );
      const otherSecret = hashAuthCode(
        `${SECRET}-rotated`,
        'SMS_OTP',
        OWNER,
        '123456',
      );

      expect(new Set([sms, reset, otherOwner, otherSecret]).size).toBe(4);
    });

    it('never contains the code, so a leaked digest is not a leaked credential', () => {
      expect(hashAuthCode(SECRET, 'SMS_OTP', OWNER, '123456')).not.toContain(
        '123456',
      );
    });
  });

  describe('authCodeDigestsEqual', () => {
    it('accepts identical digests and rejects different ones', () => {
      const digest = hashAuthCode(SECRET, 'SMS_OTP', OWNER, '123456');

      expect(authCodeDigestsEqual(digest, digest)).toBe(true);
      expect(
        authCodeDigestsEqual(
          digest,
          hashAuthCode(SECRET, 'SMS_OTP', OWNER, '654321'),
        ),
      ).toBe(false);
    });

    it('rejects values of different length without throwing', () => {
      expect(authCodeDigestsEqual('abcd', 'abcdef')).toBe(false);
    });
  });

  describe('isWellFormedAuthCode', () => {
    it.each([
      ['123456', true],
      ['000000', true],
      ['12345', false],
      ['1234567', false],
      ['12345a', false],
      ['', false],
      [' 123456', false],
    ])('reports %o as %s', (value, expected) => {
      expect(isWellFormedAuthCode(value)).toBe(expected);
    });
  });
});
