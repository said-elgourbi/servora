import { Module } from '@nestjs/common';
import { EMAIL_PROVIDER, type EmailProvider } from './email-provider.js';
import { loadEmailConfig } from './email-config.js';
import { NoOpEmailProvider } from './providers/no-op-email-provider.js';
import { ResendEmailProvider } from './providers/resend-email-provider.js';

/**
 * Binds the transactional-email port to a delivery implementation.
 *
 * `ADR-008` records the choice of Resend as the initial production provider and keeps that
 * choice inside this module: the notification layer depends on `EMAIL_PROVIDER`, never on a
 * provider. Selection and credentials come from configuration (`EMAIL_PROVIDER`, `RESEND_*`),
 * and `loadEmailConfig` fails at startup when a selected provider cannot be configured.
 *
 * The default remains the no-op implementation, so a machine with no provider credentials boots
 * and tests run without sending anything. Nothing here logs a message: a body carries a one-time
 * code (`BR-046`).
 */
@Module({
  providers: [
    {
      provide: EMAIL_PROVIDER,
      useFactory: (): EmailProvider => {
        const config = loadEmailConfig();

        switch (config.provider) {
          case 'resend':
            return config.resend === null
              ? new NoOpEmailProvider()
              : new ResendEmailProvider(config.resend);
          case 'noop':
            return new NoOpEmailProvider();
        }
      },
    },
  ],
  exports: [EMAIL_PROVIDER],
})
export class EmailModule {}
