export const PUSH_PROVIDER = Symbol('PUSH_PROVIDER');

/** Only opaque routing identifiers belong here. Android resolves localized presentation. */
export interface OutboundPushMessage {
  readonly registrationToken: string;
  readonly eventId: string;
  readonly data: Readonly<Record<string, string>>;
  /** Remaining lifetime, supplied by the delivery owner rather than inferred by transport. */
  readonly ttlMs: number;
}

export type PushDeliveryResult =
  | { readonly status: 'accepted'; readonly messageId: string }
  | { readonly status: 'disabled' };

export interface PushProvider {
  /** Accepted by the transport does not mean displayed or read. */
  send(message: OutboundPushMessage): Promise<PushDeliveryResult>;
}

export type PushFailureKind =
  'invalid-registration' | 'retryable' | 'configuration' | 'rejected';

/** Provider bodies can echo tokens and data. Expose only a controlled classification. */
export class PushDeliveryError extends Error {
  constructor(
    readonly kind: PushFailureKind,
    readonly retryAfterMs?: number,
  ) {
    super(`Push delivery failed (${kind}).`);
    this.name = 'PushDeliveryError';
  }
}
