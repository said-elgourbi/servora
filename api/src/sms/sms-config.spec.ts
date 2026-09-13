import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SMS_FILE_DIRECTORY,
  DEFAULT_SMS_PROVIDER_KIND,
  loadSmsConfig,
  type SmsConfig,
} from './sms-config.js';

/** A complete set of Twilio credentials, so a case can blank exactly one of them. */
const TWILIO_CREDENTIALS: NodeJS.ProcessEnv = {
  SMS_PROVIDER: 'twilio',
  TWILIO_ACCOUNT_SID: 'AC00000000000000000000000000000000',
  TWILIO_AUTH_TOKEN: 'auth-token',
  TWILIO_FROM: '+15145550100',
};

function loadTwilioConfig(overrides: NodeJS.ProcessEnv = {}): SmsConfig {
  return loadSmsConfig({ ...TWILIO_CREDENTIALS, ...overrides });
}

describe('SMS provider configuration', () => {
  it('selects the no-op provider by default and needs no credentials', () => {
    const config = loadSmsConfig({});

    expect(config.provider).toBe(DEFAULT_SMS_PROVIDER_KIND);
    expect(config.twilio).toBeNull();
  });

  it('reads the Twilio credentials when Twilio is selected', () => {
    const config = loadTwilioConfig();

    expect(config.provider).toBe('twilio');
    expect(config.twilio).toEqual({
      accountSid: 'AC00000000000000000000000000000000',
      authToken: 'auth-token',
      from: '+15145550100',
    });
  });

  it('accepts a Messaging Service SID as the sender', () => {
    const config = loadTwilioConfig({
      TWILIO_FROM: 'MG00000000000000000000000000000000',
    });

    expect(config.twilio?.from).toBe('MG00000000000000000000000000000000');
  });

  it.each([['TWILIO_ACCOUNT_SID'], ['TWILIO_AUTH_TOKEN'], ['TWILIO_FROM']])(
    'fails at startup when %s is missing',
    (missing) => {
      // Selecting a provider that cannot be configured must fail loudly rather than produce a
      // delivery path that silently cannot deliver.
      expect(() => loadTwilioConfig({ [missing]: '' })).toThrowError(
        new RegExp(missing),
      );
    },
  );

  it('rejects a sender Twilio cannot send from', () => {
    // An alphanumeric sender id is not a Twilio sender, and Twilio would refuse it on the first
    // OTP instead of at startup.
    expect(() => loadTwilioConfig({ TWILIO_FROM: 'Servora' })).toThrowError(
      /TWILIO_FROM must be an E\.164 phone number/,
    );
  });

  it('rejects an unknown provider name', () => {
    expect(() => loadSmsConfig({ SMS_PROVIDER: 'twilio-verify' })).toThrowError(
      /Invalid SMS_PROVIDER/,
    );
  });

  it('allows the file sink outside production and honours its directory', () => {
    const config = loadSmsConfig({
      SMS_PROVIDER: 'file',
      SMS_FILE_DIRECTORY: '/tmp/servora-sms',
      NODE_ENV: 'development',
    });

    expect(config.provider).toBe('file');
    expect(config.directory).toBe('/tmp/servora-sms');
    // The file sink needs no provider credentials.
    expect(config.twilio).toBeNull();
  });

  it('defaults the file sink to the git-ignored development directory', () => {
    const config = loadSmsConfig({ SMS_PROVIDER: 'file' });

    expect(config.directory).toBe(DEFAULT_SMS_FILE_DIRECTORY);
  });

  it('refuses the file sink in production', () => {
    // It writes a one-time password to disk, so it must not be reachable in a deployment.
    expect(() =>
      loadSmsConfig({ SMS_PROVIDER: 'file', NODE_ENV: 'production' }),
    ).toThrowError(/must not be used with NODE_ENV=production/);
  });
});
