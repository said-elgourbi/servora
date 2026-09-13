import type { ResendEmailConfig } from '../email-config.js';
import {
  EmailDeliveryError,
  type EmailProvider,
  type OutboundEmailMessage,
} from '../email-provider.js';

/**
 * The Resend implementation of the transactional-email port (`ADR-008`).
 *
 * Resend delivers a message Servora composed. It does not generate or verify the one-time code,
 * and it never sees a code that Servora did not choose to send: `BR-043` requires Servora to own
 * generation, expiry, the attempt limit, hashed storage and single use, none of which a provider
 * can guarantee. Confining the provider to transport is what keeps that rule implementable, and
 * keeps replacing Resend a change to this file and `EmailModule` only.
 *
 * Resend's HTTP API is called directly rather than through its SDK: one endpoint and one payload
 * do not justify a vendor dependency in the API (`dev.md` §4). The SMS feature does bind Twilio's
 * official SDK, which is a separate decision recorded in `ADR-006` D4. `FetchLike` is declared
 * locally, so the email feature never imports the SMS feature.
 *
 * Delivery reporting (Resend's webhooks) is a deferred decision, so this implementation reports
 * only "accepted or not".
 */

/** Resend's send endpoint. */
const RESEND_API_ORIGIN = 'https://api.resend.com';

/** A hung provider must not hold an HTTP request open for a user indefinitely. */
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

export class ResendEmailProvider implements EmailProvider {
  constructor(
    private readonly config: ResendEmailConfig,
    private readonly fetchImplementation: FetchLike = defaultFetch,
  ) {}

  async send(message: OutboundEmailMessage): Promise<void> {
    const abort = AbortSignal.timeout(REQUEST_TIMEOUT_MS);

    let response: Response;
    try {
      response = await this.fetchImplementation(`${RESEND_API_ORIGIN}/emails`, {
        method: 'POST',
        headers: {
          authorization: `Bearer ${this.config.apiKey}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify({
          from: this.config.from,
          to: [message.to],
          subject: message.subject,
          text: message.text,
        }),
        signal: abort,
      });
    } catch {
      // The transport failure is reported without its cause: a provider error body may echo
      // the message, which for a password reset contains the code (`BR-046`).
      throw new EmailDeliveryError();
    }

    if (!response.ok) {
      throw new EmailDeliveryError();
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
