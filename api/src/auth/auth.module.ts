import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { NotificationsModule } from '../notifications/notifications.module.js';
import { SmsModule } from '../sms/sms.module.js';
import { AUTH_CONFIG, authConfigProvider } from './auth-config.provider.js';
import { AuthController } from './auth.controller.js';
import { AuthGuard } from './auth.guard.js';
import { AuthRateLimiter } from './auth-rate-limiter.js';
import { AuthService } from './auth.service.js';
import { PasswordResetService } from './password-reset.service.js';
import { SmsOtpService } from './sms-otp.service.js';

@Module({
  imports: [DatabaseModule, SmsModule, NotificationsModule],
  controllers: [AuthController],
  providers: [
    authConfigProvider,
    AuthService,
    AuthGuard,
    AuthRateLimiter,
    PasswordResetService,
    SmsOtpService,
  ],
  // `AUTH_CONFIG` is exported so a later resource module can reuse `AuthGuard`
  // without re-declaring the configuration provider.
  exports: [AuthService, AuthGuard, AUTH_CONFIG],
})
export class AuthModule {}
