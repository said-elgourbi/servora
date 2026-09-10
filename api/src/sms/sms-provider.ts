/**
 * The provider-neutral SMS port.
 *
 * `BR-019` makes SMS/OTP an authentication method. The product owner's decision
 * of 2026-09-10 is that the authentication domain depends on a port rather than
 * on a provider SDK, so a provider can be replaced without touching the domain:
 *
 * `Authentication -> SmsProvider -> provider implementation -> Sinch / Twilio / Telnyx / ...`
 *
 * See `docs/decisions/005-sign-in-hardening-and-sms-port.md`.
 *
 * The port is deliberately the smallest thing that can send a message. The OTP
 * flow that will consume it - code generation, storage, expiry, single use,
 * attempt limits, purpose/context, rate limits, phone-number lifecycle and
 * delivery reporting - is not specified by `BR-019` yet, so none of it is
 * designed or assumed here.
 */

/** One SMS addressed to one recipient, free of provider vocabulary. */
export interface OutboundSmsMessage {
  /** Recipient number in E.164 form. The caller validates it; the port does not. */
  readonly to: string;
  /**
   * Message text, which may contain a one-time code. Treat it as credential
   * material: it must not be logged and must not be returned in an API response.
   */
  readonly body: string;
}

/**
 * Sends messages to handsets.
 *
 * Only an implementation of this interface may reference a provider SDK.
 */
export interface SmsProvider {
  /**
   * Accepts one message for delivery.
   *
   * Resolving means the provider accepted the message for sending, not that the
   * handset received it. Delivery reporting is a deferred decision, so it is not
   * part of the contract yet.
   */
  send(message: OutboundSmsMessage): Promise<void>;
}

/** Injection token for the bound `SmsProvider`. */
export const SMS_PROVIDER = Symbol('SMS_PROVIDER');
