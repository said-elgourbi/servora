import type { SinchSmsConfig } from '../sms-config.js';
import {
  SmsDeliveryError,
  type OutboundSmsMessage,
  type SmsProvider,
} from '../sms-provider.js';

/**
 * The Sinch implementation of the SMS port (`BR-019`, `ADR-006` D4).
 *
 * Sinch delivers a message Servora composed. It does not generate or verify the one-time
 * password: `BR-019` requires Servora to own generation, expiry, the attempt limit, hashed
 * storage and immediate invalidation, none of which a provider-side code generator can
 * guarantee. Confining the provider to transport is what keeps that rule implementable, and
 * keeps replacing Sinch a change to this file and `SmsModule` only.
 *
 * The provider's own status endpoint and webhooks (delivery reporting) are a deferred
 * decision, so this implementation reports only "accepted or not".
 */

/** Sinch's messaging endpoint; the service plan identifies the sending route. */
const SINCH_API_ORIGIN = 'https://sms.api.sinch.com';

/** A hung provider must not hold an HTTP request open for a technician indefinitely. */
const REQUEST_TIMEOUT_MS = 10_000;

/** Injected so unit tests can exercise the request without a network. */
export type FetchLike = (
  input: string,
  init: {
    readonly method: string;
    readonly headers: Record<string, string>;
    readonly body: string;
    readonly signal: AbortSignal;
  },
) => Promise<Response>;

export class SinchSmsProvider implements SmsProvider {
  constructor(
    private readonly config: SinchSmsConfig,
    private readonly fetchImplementation: FetchLike = defaultFetch,
  ) {}

  async send(message: OutboundSmsMessage): Promise<void> {
    const url = `${SINCH_API_ORIGIN}/xms/v1/${this.config.servicePlanId}/batches`;
    const abort = AbortSignal.timeout(REQUEST_TIMEOUT_MS);

    let response: Response;
    try {
      response = await this.fetchImplementation(url, {
        method: 'POST',
        headers: {
          authorization: `Bearer ${this.config.apiToken}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify({
          from: this.config.from,
          to: [message.to],
          body: message.body,
        }),
        signal: abort,
      });
    } catch {
      // The transport failure is reported without its cause: a provider error body may
      // quote the message, which for an OTP is the code (`BR-046`).
      throw new SmsDeliveryError();
    }

    if (!response.ok) {
      throw new SmsDeliveryError();
    }
  }
}

function defaultFetch(
  input: string,
  init: {
    readonly method: string;
    readonly headers: Record<string, string>;
    readonly body: string;
    readonly signal: AbortSignal;
  },
): Promise<Response> {
  return fetch(input, init);
}
