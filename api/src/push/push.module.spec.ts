import { Test } from '@nestjs/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { deleteApp } from 'firebase-admin/app';
import { createFirebasePushApp, firebaseMessaging } from './firebase-client.js';
import { PushModule } from './push.module.js';
import { PUSH_PROVIDER, type PushProvider } from './push-provider.js';

vi.mock('./firebase-client.js', () => ({
  createFirebasePushApp: vi.fn(() => ({ name: 'servora-push' })),
  firebaseMessaging: vi.fn(() => ({
    send: vi.fn().mockResolvedValue('message-id'),
  })),
}));
vi.mock('firebase-admin/app', () => ({
  deleteApp: vi.fn().mockResolvedValue(undefined),
}));

afterEach(() => {
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

describe('PushModule', () => {
  it('boots and closes with no Firebase initialization when disabled', async () => {
    vi.stubEnv('PUSH_PROVIDER', 'disabled');
    const module = await Test.createTestingModule({
      imports: [PushModule],
    }).compile();
    try {
      const provider = module.get<PushProvider>(PUSH_PROVIDER);
      await expect(
        provider.send({
          registrationToken: 'token',
          eventId: 'event',
          data: {},
          ttlMs: 1000,
        }),
      ).resolves.toEqual({ status: 'disabled' });
      expect(createFirebasePushApp).not.toHaveBeenCalled();
    } finally {
      await module.close();
    }
    expect(deleteApp).not.toHaveBeenCalled();
  });

  it('selects the configured project and releases its Firebase app on shutdown', async () => {
    vi.stubEnv('PUSH_PROVIDER', 'firebase');
    vi.stubEnv('FIREBASE_PROJECT_ID', 'servora-dev-123');
    const module = await Test.createTestingModule({
      imports: [PushModule],
    }).compile();
    try {
      expect(createFirebasePushApp).toHaveBeenCalledWith('servora-dev-123');
      expect(firebaseMessaging).toHaveBeenCalledWith({ name: 'servora-push' });
      await expect(
        module
          .get<PushProvider>(PUSH_PROVIDER)
          .send({
            registrationToken: 'token',
            eventId: 'event',
            data: {},
            ttlMs: 1000,
          }),
      ).resolves.toEqual({ status: 'accepted', messageId: 'message-id' });
    } finally {
      await module.close();
    }
    expect(deleteApp).toHaveBeenCalledExactlyOnceWith({ name: 'servora-push' });
  });

  it('rejects a misconfigured Firebase deployment at startup', async () => {
    vi.stubEnv('PUSH_PROVIDER', 'firebase');
    vi.stubEnv('FIREBASE_PROJECT_ID', '');
    await expect(
      Test.createTestingModule({ imports: [PushModule] }).compile(),
    ).rejects.toThrow('FIREBASE_PROJECT_ID');
    expect(createFirebasePushApp).not.toHaveBeenCalled();
  });
});
