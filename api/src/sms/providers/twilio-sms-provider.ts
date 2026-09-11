import twilio from 'twilio';
import type { TwilioSmsConfig } from '../sms-config.js';
import {
  SmsDeliveryError,
  type OutboundSmsMessage,
  type SmsProvider,
} from '../sms-provider.js';

/**
 * The Twilio implementation of the SMS port (`BR-019`, `ADR-006` D4).
 *
 * Twilio delivers a message Servora composed. It does not generate or verify the one-time
 * password: `BR-019` requires Servora to own generation, expiry, the attempt limit, hashed
 * storage and immediate invalidation, none of which a provider-side code generator can
 * guarantee. Confining the provider to transport is what keeps that rule implementable, and
 * keeps replacing Twilio a change to this file and `SmsModule` only.
 *
 * The provider's delivery-status callbacks (delivery reporting) are a deferred decision, so this
 * implementation reports only "accepted or not".
 */

/** A hung provider must not hold an HTTP request open for a technician indefinitely. */
const REQUEST_TIMEOUT_MS = 10_000;

/** Twilio's prefix for Messaging Service SIDs, which travel in a different parameter. */
const MESSAGING_SERVICE_SID_PREFIX = 'MG';

/** The `messages.create` parameters this provider sends. */
export interface TwilioMessageParams {
  readonly to: string;
  readonly body: string;
  readonly from?: string;
  readonly messagingServiceSid?: string;
}

/**
 * The single Twilio capability this provider uses, expressed without the SDK's types so the
 * automated suite exercises the request without a network.
 */
export interface TwilioMessageClient {
  sendMessage(params: TwilioMessageParams): Promise<unknown>;
}

/** Injected so the suite can substitute the client seam. */
export type TwilioClientFactory = (
  config: TwilioSmsConfig,
) => TwilioMessageClient;

export class TwilioSmsProvider implements SmsProvider {
  private client: TwilioMessageClient | null = null;

  constructor(
    private readonly config: TwilioSmsConfig,
    private readonly clientFactory: TwilioClientFactory = createTwilioClient,
  ) {}

  async send(message: OutboundSmsMessage): Promise<void> {
    try {
      await this.getClient().sendMessage(this.paramsFor(message));
    } catch {
      // The SDK failure is reported without its cause: a provider error can quote the message it
      // was given, which for an OTP is the code itself (`BR-046`).
      throw new SmsDeliveryError();
    }
  }

  /**
   * Built once and reused: the SDK client owns an HTTP agent, and one client serves every OTP.
   */
  private getClient(): TwilioMessageClient {
    this.client ??= this.clientFactory(this.config);
    return this.client;
  }

  /**
   * A Messaging Service SID is sent as `messagingServiceSid` and a phone number as `from`: Twilio
   * requires the distinction, and an A2P-registered deployment normally sends through a service.
   */
  private paramsFor(message: OutboundSmsMessage): TwilioMessageParams {
    const params = { to: message.to, body: message.body };

    return this.config.from.startsWith(MESSAGING_SERVICE_SID_PREFIX)
      ? { ...params, messagingServiceSid: this.config.from }
      : { ...params, from: this.config.from };
  }
}

/** Builds the SDK client; the only place the SDK is referenced. */
function createTwilioClient(config: TwilioSmsConfig): TwilioMessageClient {
  const client = twilio(config.accountSid, config.authToken, {
    timeout: REQUEST_TIMEOUT_MS,
  });

  return {
    sendMessage: (params) => client.messages.create(params),
  };
}
