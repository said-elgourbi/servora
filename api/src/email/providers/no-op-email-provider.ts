import { Injectable } from '@nestjs/common';
import type { EmailProvider } from '../email-provider.js';

/**
 * The development and test implementation of `EmailProvider`: it accepts messages and delivers
 * nothing.
 *
 * Why it exists: the port must be resolvable so the application can start and be tested before
 * any provider is configured. It keeps nothing and logs nothing - not the recipient, not the
 * subject, not the body - because a body can carry a one-time code (`BR-046`).
 *
 * It is not a production delivery path: `EMAIL_PROVIDER=resend` must be configured and its
 * credentials supplied before password reset can deliver anything (`ADR-008`).
 */
@Injectable()
export class NoOpEmailProvider implements EmailProvider {
  // No parameter is declared: the message is discarded unread, on purpose.
  send(): Promise<void> {
    return Promise.resolve();
  }
}
