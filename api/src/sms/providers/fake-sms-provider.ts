import type { OutboundSmsMessage, SmsProvider } from '../sms-provider.js';

/**
 * In-memory `SmsProvider` for tests: it records what it was asked to send and fails on demand.
 *
 * Why it exists: `BR-019` requires automated tests for the OTP flow, and a test suite must never
 * send a real message. It is bound only by test modules — it is not a provider implementation and
 * must not be selected by configuration (`ADR-006` D4).
 *
 * It keeps the body in memory, so a test can read the code the user would have received. That is
 * acceptable for a process-local test double; it is why this class must never be bound outside a
 * test.
 */
export class FakeSmsProvider implements SmsProvider {
  private readonly messages: OutboundSmsMessage[] = [];
  private failure: Error | null = null;

  async send(message: OutboundSmsMessage): Promise<void> {
    if (this.failure !== null) {
      throw this.failure;
    }
    this.messages.push({ to: message.to, body: message.body });
  }

  /** Every message accepted so far, in order. */
  get sent(): readonly OutboundSmsMessage[] {
    return [...this.messages];
  }

  /** The last accepted message, or `null` when nothing was sent. */
  get last(): OutboundSmsMessage | null {
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
