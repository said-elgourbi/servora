import { Module } from '@nestjs/common';
import { NoOpSmsProvider } from './providers/no-op-sms-provider.js';
import { SMS_PROVIDER } from './sms-provider.js';

/**
 * Binds the SMS port to a delivery implementation.
 *
 * The current binding is the no-op provider because no provider is integrated
 * yet and no OTP flow consumes the port (`BR-019` OPEN QUESTION). Selecting a
 * provider - and therefore any provider configuration - is a deferred decision,
 * and adding it must change only this module: the authentication domain depends
 * on `SMS_PROVIDER`, never on a provider.
 */
@Module({
  providers: [{ provide: SMS_PROVIDER, useClass: NoOpSmsProvider }],
  exports: [SMS_PROVIDER],
})
export class SmsModule {}
