import { Injectable } from '@nestjs/common';
import type { PasswordResetNotifier } from '../password-reset-notifier.js';

/**
 * The default binding of `PasswordResetNotifier`: it accepts a message and delivers nothing.
 *
 * It exists so the port is resolvable before a production transport is approved
 * (`ADR-006` D3). It retains nothing and logs nothing — not the recipient, not the subject,
 * and not the body — because the body contains a one-time code (`BR-046`).
 */
@Injectable()
export class NoOpPasswordResetNotifier implements PasswordResetNotifier {
  // No parameter is declared: the message is discarded unread, on purpose.
  send(): Promise<void> {
    return Promise.resolve();
  }
}
