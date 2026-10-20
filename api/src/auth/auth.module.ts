import { APP_INTERCEPTOR } from '@nestjs/core';
import { FieldScopeInterceptor } from './field-scope.interceptor.js';
import { FieldContextController } from './field-context.controller.js';
import { FieldContextService } from './field-context.service.js';
import { BrowserAuthController } from './browser-auth.controller.js';
import { BrowserOriginGuard } from './browser-origin.guard.js';
import { browserAuthConfigProvider } from './browser-auth.config.js';
import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { NotificationsModule } from '../notifications/notifications.module.js';
import { SmsModule } from '../sms/sms.module.js';
import { AUTH_CONFIG, authConfigProvider } from './auth-config.provider.js';
import { AuthController } from './auth.controller.js';
import { AuthGuard } from './auth.guard.js';
import { AuthRateLimiter } from './auth-rate-limiter.js';
import { AuthService } from './auth.service.js';
import { AuthorizationService } from './authorization.service.js';
import { PasswordResetService } from './password-reset.service.js';
import { PermissionsGuard } from './permissions.guard.js';
import { SmsOtpService } from './sms-otp.service.js';

@Module({
  imports: [DatabaseModule, SmsModule, NotificationsModule],
  controllers: [AuthController, BrowserAuthController, FieldContextController],
  providers: [
    FieldContextService,
    { provide: APP_INTERCEPTOR, useClass: FieldScopeInterceptor },
    authConfigProvider,
    browserAuthConfigProvider,
    BrowserOriginGuard,
    AuthService,
    AuthGuard,
    AuthorizationService,
    PermissionsGuard,
    AuthRateLimiter,
    PasswordResetService,
    SmsOtpService,
  ],
  // `AUTH_CONFIG` is exported so a later resource module can reuse `AuthGuard`
  // without re-declaring the configuration provider.
  exports: [
    AuthService,
    AuthGuard,
    AuthorizationService,
    PermissionsGuard,
    FieldContextService,
    AUTH_CONFIG,
  ],
})
export class AuthModule {}
