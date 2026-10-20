import { randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
  parseInstallationId,
  parseInstallationSecret,
  parsePushRegistration,
} from './push-registration.dto.js';

const body = {
  expectedRevision: 0,
  applicationId: 'com.servora.android.debug',
  registration: { kind: 'FCM_TOKEN', value: 'opaque-target' },
  locale: 'fr-CA',
  alertsEnabled: true,
  notificationPermission: 'GRANTED',
  channelEnabled: true,
};
describe('push registration validation', () => {
  it('accepts explicit independent preference and permission', () => {
    expect(
      parsePushRegistration({ ...body, notificationPermission: 'DENIED' }),
    ).toMatchObject({ alertsEnabled: true, notificationPermission: 'DENIED' });
  });
  it.each([
    null,
    [],
    {},
    { ...body, userId: 'injected' },
    { ...body, expectedRevision: -1 },
    { ...body, expectedRevision: 2147483647 },
    { ...body, expectedRevision: 0.5 },
    { ...body, locale: 'en' },
    { ...body, channelEnabled: 'true' },
    { ...body, registration: { kind: 'FID', value: 'target' } },
    { ...body, registration: { kind: 'FCM_TOKEN', value: 'bad\nvalue' } },
    { ...body, registration: { kind: 'FCM_TOKEN', value: 'é'.repeat(2049) } },
  ])('rejects malformed or unsupported fields', (input) => {
    expect(() => parsePushRegistration(input)).toThrow(
      'The push registration request is invalid.',
    );
  });
  it('requires canonical installation proof and a UUID', () => {
    const secret = randomBytes(32).toString('base64url');
    expect(parseInstallationSecret(secret)).toBe(secret);
    for (const invalid of [undefined, [secret], secret + '=', 'a'.repeat(43)])
      expect(() => parseInstallationSecret(invalid)).toThrow();
    expect(parseInstallationId('ABCDEF00-0000-4000-8000-000000000001')).toBe(
      'abcdef00-0000-4000-8000-000000000001',
    );
    expect(() => parseInstallationId('invalid')).toThrow();
  });
});
