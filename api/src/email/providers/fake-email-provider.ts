import type { EmailProvider, OutboundEmailMessage } from '../email-provider.js';

/**
 * In-memory `EmailProvider` for tests: it records what it was asked to send and fails on demand.
 *
 * Why it exists: a test suite must never send a real message, but the flows above the port are
 * worth testing end to end. It is bound only by test modules — it is not a provider
 * implementation and must not be selected by configuration (`ADR-008`).
 *
 * It keeps the body in memory, so a test can read the code the recipient would have received.
 * That is acceptable for a process-local test double; it is why this class must never be bound
 * outside a test.
 */
export class FakeEmailProvider implements EmailProvider {
  private readonly messages: OutboundEmailMessage[] = [];
  private failure: Error | null = null;

  async send(message: OutboundEmailMessage): Promise<void> {
    if (this.failure !== null) {
      throw this.failure;
    }
    this.messages.push({
      to: message.to,
      subject: message.subject,
      text: message.text,
    });
  }

  /** Every message accepted so far, in order. */
  get sent(): readonly OutboundEmailMessage[] {
    return [...this.messages];
  }

  /** The last accepted message, or `null` when nothing was sent. */
  get last(): OutboundEmailMessage | null {
    return this.messages.at(-1) ?? null;
  }

  /** Makes the next `send` fail, to exercise provider-failure handling. */
  failNextSend(error: Error): void {
    this.failure = error;
  }

  /** Stops failing. */
  recover(): void {
    this.failure = null;
  }

  /** Forgets every accepted message. */
  reset(): void {
    this.messages.length = 0;
    this.failure = null;
  }
}
