import type { Message } from 'firebase-admin/messaging';
import {
  PushDeliveryError,
  type OutboundPushMessage,
  type PushDeliveryResult,
  type PushFailureKind,
  type PushProvider,
} from './push-provider.js';

/** Injected so transport tests never need Google credentials or network access. */
export interface FirebaseMessagingClient {
  send(message: Message, dryRun?: boolean): Promise<string>;
}

function failureKind(error: unknown): PushFailureKind {
  const code =
    typeof error === 'object' && error !== null && 'code' in error
      ? error.code
      : undefined;
  switch (code) {
    case 'messaging/registration-token-not-registered':
    case 'messaging/invalid-registration-token':
      return 'invalid-registration';
    case 'messaging/server-unavailable':
    case 'messaging/internal-error':
    case 'messaging/unknown-error':
    case 'messaging/message-rate-exceeded':
    case 'messaging/device-message-rate-exceeded':
    case 'messaging/topics-message-rate-exceeded':
    case 'app/network-error':
    case 'app/network-timeout':
      return 'retryable';
    case 'messaging/authentication-error':
    case 'messaging/mismatched-credential':
    case 'messaging/third-party-auth-error':
    case 'app/invalid-credential':
    case 'app/invalid-app-options':
      return 'configuration';
    default:
      // In particular, invalid-argument is NOT proof that a registration is invalid.
      return 'rejected';
  }
}

export class FirebasePushProvider implements PushProvider {
  constructor(private readonly client: FirebaseMessagingClient) {}

  async send(message: OutboundPushMessage): Promise<PushDeliveryResult> {
    // The event id is reserved for stable presentation deduplication on Android.
    if (
      !message.registrationToken.trim() ||
      !message.eventId.trim() ||
      !Number.isInteger(message.ttlMs) ||
      message.ttlMs <= 0 ||
      message.ttlMs > 28 * 24 * 60 * 60 * 1000
    ) {
      throw new PushDeliveryError('rejected');
    }
    try {
      const messageId = await this.client.send({
        token: message.registrationToken,
        data: { ...message.data, eventId: message.eventId },
        // Data-only messages allow the app to enforce sign-out, preference and permission
        // checks before displaying anything, including when it is in the background.
        android: { priority: 'high', ttl: message.ttlMs },
      });
      return { status: 'accepted', messageId };
    } catch (error: unknown) {
      throw new PushDeliveryError(failureKind(error));
    }
  }
}
