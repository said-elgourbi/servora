import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Post,
  Req,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import {
  parsePasswordResetCompleteRequest,
  parsePasswordResetRequest,
  parsePasswordResetVerifyRequest,
  parseRefreshRequest,
  parseSignInRequest,
  parseSmsOtpRequest,
  parseSmsOtpVerifyRequest,
} from './auth-request.dto.js';
import { AuthService, type SignInResponse } from './auth.service.js';
import { AuthApiError } from './auth-error.js';
import { PasswordResetService } from './password-reset.service.js';
import { SmsOtpService } from './sms-otp.service.js';
import {
  AuthGuard,
  type AuthenticatedRequest,
  type RequestAuth,
} from './auth.guard.js';
import type { AuthSessionDto } from './auth.dto.js';
import { readClientContext } from './client-context.js';

/**
 * `/auth` endpoints (`docs/api/authentication.md` §3).
 *
 * `@Body()` is deliberately `unknown`: the request contract is enforced by the
 * hand-written parsers, which either return a narrowed value or reject the whole
 * payload as `400 VALIDATION_FAILED` (`docs/api/authentication.md` §2 C2).
 *
 * No handler performs a permission check. Sign-in acts on the caller's credentials,
 * refresh on the caller's refresh token, and sign-out and session listing on the
 * caller's own session, so authorization (`403`) belongs to resource endpoints
 * (`docs/api/authentication.md` §4).
 */
@Controller('auth')
export class AuthController {
  constructor(
    private readonly auth: AuthService,
    private readonly passwordReset: PasswordResetService,
    private readonly smsOtp: SmsOtpService,
  ) {}

  @Post('sign-in')
  @HttpCode(HttpStatus.OK)
  signIn(
    @Body() body: unknown,
    @Req() request: Request,
  ): Promise<SignInResponse> {
    return this.auth.signIn(
      parseSignInRequest(body),
      readClientContext(request),
    );
  }

  @Post('refresh')
  @HttpCode(HttpStatus.OK)
  refresh(@Body() body: unknown): Promise<SignInResponse> {
    return this.auth.refresh(parseRefreshRequest(body).refreshToken);
  }

  @Post('sign-out')
  @UseGuards(AuthGuard)
  @HttpCode(HttpStatus.NO_CONTENT)
  async signOut(@Req() request: AuthenticatedRequest): Promise<void> {
    await this.auth.signOut(requireAuth(request).sessionId);
  }

  @Get('sessions')
  @UseGuards(AuthGuard)
  listSessions(
    @Req() request: AuthenticatedRequest,
  ): Promise<AuthSessionDto[]> {
    return this.auth.listSessions(requireAuth(request).userId);
  }

  // ------------------------------------------------------ password reset

  /**
   * Starts a password reset (`BR-043`).
   *
   * `202` with an empty body is the response whether or not the address exists, so the status
   * carries no information about the account (`BR-044`).
   */
  @Post('password-reset/request')
  @HttpCode(HttpStatus.ACCEPTED)
  async requestPasswordReset(@Body() body: unknown): Promise<void> {
    await this.passwordReset.request(parsePasswordResetRequest(body));
  }

  /**
   * Checks a reset credential without consuming it (`BR-043`).
   *
   * `204` on success; every failure is the single `401 RESET_CODE_INVALID`. No body is
   * returned, so a client cannot learn more than "this code may be used".
   */
  @Post('password-reset/verify')
  @HttpCode(HttpStatus.NO_CONTENT)
  async verifyPasswordReset(@Body() body: unknown): Promise<void> {
    await this.passwordReset.verify(parsePasswordResetVerifyRequest(body));
  }

  /**
   * Redeems a reset credential and sets the new password (`BR-043`).
   *
   * `204`, and deliberately not a session: a reset returns the user to normal authentication.
   */
  @Post('password-reset/complete')
  @HttpCode(HttpStatus.NO_CONTENT)
  async completePasswordReset(@Body() body: unknown): Promise<void> {
    await this.passwordReset.complete(parsePasswordResetCompleteRequest(body));
  }

  // ------------------------------------------------------- phone/SMS

  /** Issues an SMS one-time password (`BR-019`), answering `202` in every case. */
  @Post('sms/request')
  @HttpCode(HttpStatus.ACCEPTED)
  async requestSmsOtp(@Body() body: unknown): Promise<void> {
    await this.smsOtp.request(parseSmsOtpRequest(body));
  }

  /**
   * Verifies an SMS one-time password and authenticates the caller (`BR-019`).
   *
   * Returns the same body as `POST /auth/sign-in`: a phone verification is a sign-in, not a
   * second kind of session.
   */
  @Post('sms/verify')
  @HttpCode(HttpStatus.OK)
  smsSignIn(
    @Body() body: unknown,
    @Req() request: Request,
  ): Promise<SignInResponse> {
    return this.smsOtp.verify(
      parseSmsOtpVerifyRequest(body),
      readClientContext(request),
    );
  }
}

/**
 * `AuthGuard` is the only writer of `request.auth`; its absence inside a guarded
 * handler is a wiring bug, and failing closed is the safe way to report it.
 */
function requireAuth(request: AuthenticatedRequest): RequestAuth {
  const auth = request.auth;
  if (auth === undefined) {
    throw AuthApiError.unauthenticated();
  }
  return auth;
}
