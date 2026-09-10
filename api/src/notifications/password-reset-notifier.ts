/**
 * The provider-neutral password-reset delivery port.
 *
 * `BR-043` requires the reset credential to reach the user through a delivery channel
 * configured for the account. Which transport carries it (SMTP, a transactional email API, …)
 * is an infrastructure decision that is still open, so the authentication domain depends on
 * this port rather than on a transport (`ADR-006` D3):
 *
 * `Authentication -> PasswordResetNotifier -> transport implementation`
 *
 * Only an implementation may reference a transport, and no implementation may log the
 * message: the body carries a one-time code (`BR-046`).
 */

/** One password-reset message addressed to one recipient. */
export interface PasswordResetMessage {
  /** Recipient address. It is personal data: never log it alongside the code. */
  readonly to: string;
  /**
   * The one-time reset code. Credential material: it may be delivered to [to] and nowhere
   * else, and must never be persisted in plaintext or written to a log (`BR-046`).
   */
  readonly code: string;
  /** Message subject in the recipient's language. */
  readonly subject: string;
  /** Message body in the recipient's language. Contains [code]. */
  readonly body: string;
}

/**
 * Delivers password-reset messages.
 *
 * Resolving means the transport accepted the message for delivery, not that the recipient
 * received it. Delivery reporting is a deferred decision and is not part of the contract.
 */
export interface PasswordResetNotifier {
  send(message: PasswordResetMessage): Promise<void>;
}

/** Injection token for the bound `PasswordResetNotifier`. */
export const PASSWORD_RESET_NOTIFIER = Symbol('PASSWORD_RESET_NOTIFIER');
