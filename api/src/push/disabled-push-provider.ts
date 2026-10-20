import type {
  OutboundPushMessage,
  PushDeliveryResult,
  PushProvider,
} from './push-provider.js';

export class DisabledPushProvider implements PushProvider {
  send(_message: OutboundPushMessage): Promise<PushDeliveryResult> {
    return Promise.resolve({ status: 'disabled' });
  }
}
