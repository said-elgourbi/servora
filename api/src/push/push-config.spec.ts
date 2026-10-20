import { describe, expect, it } from 'vitest';
import { loadPushConfig } from './push-config.js';

describe('loadPushConfig', () => {
  it('boots without Firebase credentials by default', () => {
    expect(loadPushConfig({})).toEqual({ provider: 'disabled' });
    expect(loadPushConfig({ PUSH_PROVIDER: ' ' })).toEqual({
      provider: 'disabled',
    });
  });

  it('requires an explicit, valid project for Firebase', () => {
    expect(() => loadPushConfig({ PUSH_PROVIDER: 'firebase' })).toThrow(
      'FIREBASE_PROJECT_ID',
    );
    for (const projectId of [
      '123456789',
      'https://example.com',
      '../key',
      'bad project',
      'short',
    ]) {
      expect(() =>
        loadPushConfig({
          PUSH_PROVIDER: 'firebase',
          FIREBASE_PROJECT_ID: projectId,
        }),
      ).toThrow('FIREBASE_PROJECT_ID');
    }
    expect(
      loadPushConfig({
        PUSH_PROVIDER: ' firebase ',
        FIREBASE_PROJECT_ID: ' servora-dev-123 ',
      }),
    ).toEqual({ provider: 'firebase', projectId: 'servora-dev-123' });
  });

  it('rejects unknown providers without echoing input', () => {
    expect(() =>
      loadPushConfig({ PUSH_PROVIDER: 'secret-accidentally-pasted' }),
    ).toThrow('PUSH_PROVIDER must be disabled or firebase.');
  });
});
