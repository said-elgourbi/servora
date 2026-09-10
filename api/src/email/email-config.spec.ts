import { describe, expect, it } from 'vitest';
import {
  DEFAULT_EMAIL_PROVIDER_KIND,
  loadEmailConfig,
} from './email-config.js';

const RESEND_ENV = {
  EMAIL_PROVIDER: 'resend',
  RESEND_API_KEY: 'the-api-key',
  EMAIL_FROM: 'Servora <no-reply@example.com>',
};

describe('loadEmailConfig', () => {
  it('delivers nothing when nothing is configured, rather than guessing a provider', () => {
    const config = loadEmailConfig({});

    expect(config.provider).toBe(DEFAULT_EMAIL_PROVIDER_KIND);
    expect(config.provider).toBe('noop');
    expect(config.resend).toBeNull();
  });

  it('ignores credentials that belong to an unselected provider', () => {
    const config = loadEmailConfig({ RESEND_API_KEY: 'the-api-key' });

    expect(config.provider).toBe('noop');
    expect(config.resend).toBeNull();
  });

  it('requires the Resend credentials when Resend is selected', () => {
    expect(() =>
      loadEmailConfig({
        EMAIL_PROVIDER: 'resend',
        RESEND_API_KEY: 'the-api-key',
      }),
    ).toThrowError(/EMAIL_FROM is required/);
    expect(() =>
      loadEmailConfig({
        EMAIL_PROVIDER: 'resend',
        EMAIL_FROM: 'no-reply@example.com',
      }),
    ).toThrowError(/RESEND_API_KEY is required/);
  });

  it('treats a blank credential as missing', () => {
    expect(() =>
      loadEmailConfig({ ...RESEND_ENV, RESEND_API_KEY: '   ' }),
    ).toThrowError(/RESEND_API_KEY is required/);
  });

  it('loads the Resend credentials when Resend is selected', () => {
    const config = loadEmailConfig(RESEND_ENV);

    expect(config.provider).toBe('resend');
    expect(config.resend).toEqual({
      apiKey: 'the-api-key',
      from: 'Servora <no-reply@example.com>',
    });
  });

  it('rejects a provider it does not implement instead of falling back silently', () => {
    expect(() => loadEmailConfig({ EMAIL_PROVIDER: 'sendgrid' })).toThrowError(
      /Invalid EMAIL_PROVIDER "sendgrid"/,
    );
  });
});
