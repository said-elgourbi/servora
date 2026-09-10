import { Injectable } from '@nestjs/common';
import type { SmsProvider } from '../sms-provider.js';

/**
 * The development and test implementation of `SmsProvider`: it accepts messages
 * and delivers nothing.
 *
 * Why it exists: the port must be resolvable so the application can start and be
 * tested before any provider is integrated (`BR-019` leaves provider selection
 * open). It keeps nothing and logs nothing - not the recipient, not the body -
 * because a body can be a one-time code and an OTP must never reach the logs or
 * an API response.
 *
 * It is not a production delivery path: a real provider must be bound before any
 * OTP flow ships (`docs/decisions/005-sign-in-hardening-and-sms-port.md`).
 */
@Injectable()
export class NoOpSmsProvider implements SmsProvider {
  // No parameter is declared: the message is discarded unread, on purpose.
  send(): Promise<void> {
    return Promise.resolve();
  }
}
