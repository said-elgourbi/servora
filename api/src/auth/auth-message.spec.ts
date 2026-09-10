import { describe, expect, it } from 'vitest';
import {
  passwordResetMessage,
  resolveAuthMessageLocale,
  smsOtpMessage,
} from './auth-message.js';

describe('authentication message language', () => {
  it.each([
    ['en', 'en'],
    ['en-CA', 'en'],
    ['fr', 'fr'],
    ['fr-CA', 'fr'],
    ['FR-ca', 'fr'],
    ['  fr  ', 'fr'],
    ['es-MX', 'en'],
    ['', 'en'],
    [null, 'en'],
    [undefined, 'en'],
  ])('resolves %o to %s', (locale, expected) => {
    expect(resolveAuthMessageLocale(locale)).toBe(expected);
  });
});

describe('password reset message', () => {
  it('carries the code, the lifetime and a subject in English', () => {
    const message = passwordResetMessage('en', '123456', 30);

    expect(message.subject).toBe('Reset your Servora password');
    expect(message.body).toContain('123456');
    expect(message.body).toContain('30 minutes');
  });

  it('carries the same information in French', () => {
    const message = passwordResetMessage('fr', '123456', 30);

    expect(message.subject).toContain('Servora');
    expect(message.subject).not.toBe(
      passwordResetMessage('en', '123456', 30).subject,
    );
    expect(message.body).toContain('123456');
    expect(message.body).toContain('30 minutes');
  });
});

describe('SMS one-time password message', () => {
  it('is a single short message containing the code in both languages', () => {
    const english = smsOtpMessage('en', '123456', 10);
    const french = smsOtpMessage('fr', '123456', 10);

    expect(english).toContain('123456');
    expect(french).toContain('123456');
    expect(english).not.toBe(french);
    expect(english.length).toBeLessThan(160);
    expect(french.length).toBeLessThan(160);
  });
});
