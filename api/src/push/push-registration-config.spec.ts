import { describe, expect, it } from 'vitest';
import { loadPushRegistrationConfig } from './push-registration-config.js';
describe('push registration environment', () => {
  it('does not select a project or variant implicitly', () =>
    expect(loadPushRegistrationConfig({})).toEqual({
      projectId: null,
      applicationId: null,
      transportEnabled: false,
    }));
  it('allows registration configuration with transport disabled', () =>
    expect(
      loadPushRegistrationConfig({
        FIREBASE_PROJECT_ID: 'servora-test-123',
        PUSH_ANDROID_APPLICATION_ID: 'com.servora.android.debug',
        PUSH_PROVIDER: 'disabled',
      }).transportEnabled,
    ).toBe(false));
  it.each([
    { FIREBASE_PROJECT_ID: 'bad' },
    { PUSH_ANDROID_APPLICATION_ID: 'untrusted.app' },
    {
      NODE_ENV: 'production',
      PUSH_ANDROID_APPLICATION_ID: 'com.servora.android.debug',
    },
  ])('rejects invalid environment selection', (env) =>
    expect(() => loadPushRegistrationConfig(env)).toThrow(),
  );
});
