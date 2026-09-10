import { Module } from '@nestjs/common';
import { FileSmsProvider } from './providers/file-sms-provider.js';
import { NoOpSmsProvider } from './providers/no-op-sms-provider.js';
import { SinchSmsProvider } from './providers/sinch-sms-provider.js';
import { loadSmsConfig } from './sms-config.js';
import { SMS_PROVIDER, type SmsProvider } from './sms-provider.js';

/**
 * Binds the SMS port to a delivery implementation.
 *
 * `BR-019` approves Sinch as the initial production provider, and `ADR-006` D4 keeps that
 * choice inside this module: the authentication domain depends on `SMS_PROVIDER`, never on a
 * provider. Selection and credentials come from configuration (`SMS_PROVIDER`, `SINCH_*`),
 * and `loadSmsConfig` fails at startup when a selected provider cannot be configured.
 *
 * `SMS_PROVIDER=file` is a development sink that writes messages to a directory, so the flow can
 * be exercised without a provider account; `loadSmsConfig` refuses it in production. The default
 * remains the no-op implementation, so a machine with no provider credentials boots and tests run
 * without sending anything.
 */
@Module({
  providers: [
    {
      provide: SMS_PROVIDER,
      useFactory: (): SmsProvider => {
        const config = loadSmsConfig();

        switch (config.provider) {
          case 'sinch':
            return config.sinch === null
              ? new NoOpSmsProvider()
              : new SinchSmsProvider(config.sinch);
          case 'file':
            return new FileSmsProvider(config.directory);
          case 'noop':
            return new NoOpSmsProvider();
        }
      },
    },
  ],
  exports: [SMS_PROVIDER],
})
export class SmsModule {}
