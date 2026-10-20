import { describe, expect, it, vi } from 'vitest';
import { FirebasePushProvider } from './firebase-push-provider.js';
import { DisabledPushProvider } from './disabled-push-provider.js';
import {
  PushDeliveryError,
  type OutboundPushMessage,
} from './push-provider.js';

const MESSAGE: OutboundPushMessage = {
  registrationToken: 'private-device-token',
  eventId: 'event-123',
  data: { eventType: 'VISIT_ASSIGNED', jobId: 'job-123' },
  ttlMs: 60_000,
};

describe('FirebasePushProvider', () => {
  it('sends a data-only message and reports provider acceptance', async () => {
    const send = vi.fn().mockResolvedValue('projects/test/messages/123');
    const provider = new FirebasePushProvider({ send });
    await expect(provider.send(MESSAGE)).resolves.toEqual({
      status: 'accepted',
      messageId: 'projects/test/messages/123',
    });
    expect(send).toHaveBeenCalledWith({
      token: MESSAGE.registrationToken,
      data: { ...MESSAGE.data, eventId: MESSAGE.eventId },
      android: { priority: 'high', ttl: 60_000 },
    });
  });

  it('keeps the stable event id authoritative', async () => {
    const send = vi.fn().mockResolvedValue('id');
    await new FirebasePushProvider({ send }).send({
      ...MESSAGE,
      data: { eventId: 'incorrect' },
    });
    expect(send.mock.calls[0]?.[0].data.eventId).toBe(MESSAGE.eventId);
  });

  it.each([
    ['messaging/registration-token-not-registered', 'invalid-registration'],
    ['messaging/invalid-registration-token', 'invalid-registration'],
    ['messaging/server-unavailable', 'retryable'],
    ['messaging/internal-error', 'retryable'],
    ['messaging/message-rate-exceeded', 'retryable'],
    ['app/network-timeout', 'retryable'],
    ['messaging/mismatched-credential', 'configuration'],
    ['app/invalid-credential', 'configuration'],
    ['messaging/invalid-argument', 'rejected'],
    ['unrecognized', 'rejected'],
  ])('classifies %s without leaking provider details', async (code, kind) => {
    const provider = new FirebasePushProvider({
      send: vi
        .fn()
        .mockRejectedValue({
          code,
          message: MESSAGE.registrationToken,
          data: MESSAGE.data,
        }),
    });
    try {
      await provider.send(MESSAGE);
      expect.fail('Expected delivery failure');
    } catch (error) {
      expect(error).toBeInstanceOf(PushDeliveryError);
      expect(error).toMatchObject({ kind });
      expect(String(error)).not.toContain(MESSAGE.registrationToken);
      expect(error).not.toHaveProperty('cause');
    }
  });

  it.each([0, -1, 1.5, Number.NaN, 28 * 24 * 60 * 60 * 1000 + 1])(
    'refuses invalid lifetime %s without sending',
    async (ttlMs) => {
      const send = vi.fn();
      await expect(
        new FirebasePushProvider({ send }).send({ ...MESSAGE, ttlMs }),
      ).rejects.toMatchObject({ kind: 'rejected' });
      expect(send).not.toHaveBeenCalled();
    },
  );

  it('reports disabled transport without pretending to accept a message', async () => {
    await expect(new DisabledPushProvider().send(MESSAGE)).resolves.toEqual({
      status: 'disabled',
    });
  });
});
