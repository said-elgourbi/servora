import { Module } from '@nestjs/common';
import { EMAIL_PROVIDER, type EmailProvider } from '../email/email-provider.js';
import { EmailModule } from '../email/email.module.js';
import {
  PASSWORD_RESET_NOTIFIER,
  type PasswordResetNotifier,
} from './password-reset-notifier.js';
import { NoOpPasswordResetNotifier } from './providers/no-op-password-reset-notifier.js';
import { FilePasswordResetNotifier } from './providers/file-password-reset-notifier.js';
import { EmailPasswordResetNotifier } from './providers/email-password-reset-notifier.js';
import { loadNotificationsConfig } from './notifications-config.js';

/**
 * Binds the password-reset delivery port to a transport.
 *
 * `PASSWORD_RESET_DELIVERY` selects the transport, and `ADR-008` adds `email`: the adapter hands
 * the already-localized message to the bound `EmailProvider`, so which service sends it stays a
 * decision of `EmailModule` (`EMAIL_PROVIDER`) rather than of this module or of the
 * authentication domain. The default binding still delivers nothing, and the `file` transport is
 * a local development affordance: `loadNotificationsConfig` refuses it in production, so the only
 * way to reach it in a deployment is to run development code in production *and* misconfigure
 * `NODE_ENV`.
 */
@Module({
  imports: [EmailModule],
  providers: [
    {
      provide: PASSWORD_RESET_NOTIFIER,
      inject: [EMAIL_PROVIDER],
      useFactory: (emailProvider: EmailProvider): PasswordResetNotifier => {
        const config = loadNotificationsConfig();

        switch (config.delivery) {
          case 'file':
            return new FilePasswordResetNotifier(config.directory);
          case 'email':
            return new EmailPasswordResetNotifier(emailProvider);
          case 'noop':
            return new NoOpPasswordResetNotifier();
        }
      },
    },
  ],
  exports: [PASSWORD_RESET_NOTIFIER],
})
export class NotificationsModule {}
