import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SMS_FILE_DIRECTORY,
  DEFAULT_SMS_PROVIDER_KIND,
  loadSmsConfig,
} from './sms-config.js';

describe('SMS provider configuration', () => {
  it('selects the no-op provider by default and needs no credentials', () => {
    const config = loadSmsConfig({});

    expect(config.provider).toBe(DEFAULT_SMS_PROVIDER_KIND);
    expect(config.sinch).toBeNull();
  });

  it('reads the Sinch credentials when Sinch is selected', () => {
    const config = loadSmsConfig({
      SMS_PROVIDER: 'sinch',
      SINCH_SERVICE_PLAN_ID: 'service-plan',
      SINCH_API_TOKEN: 'token',
      SINCH_FROM: 'Servora',
    });

    expect(config.provider).toBe('sinch');
    expect(config.sinch).toEqual({
      servicePlanId: 'service-plan',
      apiToken: 'token',
      from: 'Servora',
    });
  });

  it.each([['SINCH_SERVICE_PLAN_ID'], ['SINCH_API_TOKEN'], ['SINCH_FROM']])(
    'fails at startup when %s is missing',
    (missing) => {
      const env: NodeJS.ProcessEnv = {
        SMS_PROVIDER: 'sinch',
        SINCH_SERVICE_PLAN_ID: 'service-plan',
        SINCH_API_TOKEN: 'token',
        SINCH_FROM: 'Servora',
        [missing]: '',
      };

      // Selecting a provider that cannot be configured must fail loudly rather than produce a
      // delivery path that silently cannot deliver.
      expect(() => loadSmsConfig(env)).toThrowError(new RegExp(missing));
    },
  );

  it('rejects an unknown provider name', () => {
    expect(() => loadSmsConfig({ SMS_PROVIDER: 'twilio' })).toThrowError(
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
    expect(config.sinch).toBeNull();
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
