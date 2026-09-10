import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PASSWORD_RESET_DELIVERY,
  DEFAULT_PASSWORD_RESET_DIRECTORY,
  loadNotificationsConfig,
} from './notifications-config.js';

describe('notification configuration', () => {
  it('delivers nothing by default', () => {
    const config = loadNotificationsConfig({});

    expect(config.delivery).toBe(DEFAULT_PASSWORD_RESET_DELIVERY);
    expect(config.directory).toBe(DEFAULT_PASSWORD_RESET_DIRECTORY);
  });

  it('allows the file transport outside production', () => {
    const config = loadNotificationsConfig({
      PASSWORD_RESET_DELIVERY: 'file',
      PASSWORD_RESET_DELIVERY_DIRECTORY: '/tmp/servora-notifications',
      NODE_ENV: 'development',
    });

    expect(config.delivery).toBe('file');
    expect(config.directory).toBe('/tmp/servora-notifications');
  });

  it('refuses the file transport in production', () => {
    // The file transport writes a one-time code to disk, so it must not be reachable in a
    // deployment (`dev.md` §11).
    expect(() =>
      loadNotificationsConfig({
        PASSWORD_RESET_DELIVERY: 'file',
        NODE_ENV: 'production',
      }),
    ).toThrowError(/must not be used with NODE_ENV=production/);
  });

  it('allows the email transport, which delegates provider selection to the email port', () => {
    const config = loadNotificationsConfig({
      PASSWORD_RESET_DELIVERY: 'email',
      NODE_ENV: 'production',
    });

    expect(config.delivery).toBe('email');
  });

  it('rejects an unknown transport', () => {
    expect(() =>
      loadNotificationsConfig({ PASSWORD_RESET_DELIVERY: 'smtp' }),
    ).toThrowError(/Invalid PASSWORD_RESET_DELIVERY/);
  });
});
