import { Inject, Injectable } from '@nestjs/common';
import {
  EMAIL_PROVIDER,
  type EmailProvider,
} from '../../email/email-provider.js';
import type {
  PasswordResetMessage,
  PasswordResetNotifier,
} from '../password-reset-notifier.js';

/**
 * Production binding of `PasswordResetNotifier`: it delivers the reset message through the
 * transactional-email port (`ADR-008`).
 *
 * The message the authentication domain composed already carries the subject and the body in
 * the recipient's language (`api/src/auth/auth-message.ts`, `BR-028`), so this adapter only maps
 * the reset message onto `OutboundEmailMessage`. It adds no copy, no template and no
 * localization of its own, and it logs nothing: the body carries a one-time code (`BR-046`).
 */
@Injectable()
export class EmailPasswordResetNotifier implements PasswordResetNotifier {
  constructor(
    @Inject(EMAIL_PROVIDER) private readonly emailProvider: EmailProvider,
  ) {}

  async send(message: PasswordResetMessage): Promise<void> {
    await this.emailProvider.send({
      to: message.to,
      subject: message.subject,
      text: message.body,
    });
  }
}
