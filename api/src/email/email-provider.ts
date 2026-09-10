/**
 * The provider-neutral transactional-email port.
 *
 * `BR-043` requires a password-reset credential to reach the user through a delivery channel
 * configured for the account, and the reset is delivered by email. Which transactional-email
 * service carries it is isolated behind this port, so the authentication domain and the
 * notification layer depend on `EmailProvider` rather than on a vendor SDK
 * (`docs/decisions/008-transactional-email-and-resend-provider.md`):
 *
 * `Notification/authentication -> EmailProvider -> provider implementation -> Resend / …`
 *
 * Only an implementation of this interface may reference a provider SDK, and no implementation
 * may log a message: an authentication message body carries a one-time code (`BR-046`).
 *
 * The port is deliberately the smallest thing that can send a transactional message — one
 * recipient, a subject and plain text. Recipients, templates, attachments, HTML and delivery
 * reporting are not specified by any approved rule, so none of them is designed or assumed here
 * (`BR-042`).
 */

/** One transactional email addressed to one recipient, free of provider vocabulary. */
export interface OutboundEmailMessage {
  /** Recipient address. It is personal data: never log it alongside the message body. */
  readonly to: string;
  /** Subject line, already resolved to the recipient's language (`BR-028`). */
  readonly subject: string;
  /**
   * Plain-text body. It may contain a one-time code, so treat it as credential material: it
   * must not be logged and must not be returned in an API response (`BR-046`).
   */
  readonly text: string;
}

/**
 * Sends transactional messages.
 *
 * Resolving means the provider accepted the message for delivery, not that the recipient's
 * mailbox received it. Delivery reporting is a deferred decision, so it is not part of the
 * contract yet.
 */
export interface EmailProvider {
  send(message: OutboundEmailMessage): Promise<void>;
}

/** Injection token for the bound `EmailProvider`. */
export const EMAIL_PROVIDER = Symbol('EMAIL_PROVIDER');

/**
 * Raised when a provider rejected, refused or could not accept a message.
 *
 * It carries no provider-specific detail on purpose: everything above the port reacts to the
 * failure the same way, and a provider's error body may echo the message it was given — which
 * for a reset message is the code itself (`BR-046`).
 */
export class EmailDeliveryError extends Error {
  constructor() {
    super('The email provider did not accept the message.');
    this.name = 'EmailDeliveryError';
  }
}
