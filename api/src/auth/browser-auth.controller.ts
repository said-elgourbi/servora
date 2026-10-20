import {
  Body,
  Controller,
  Header,
  HttpCode,
  Inject,
  Post,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import type { CookieOptions, Request, Response } from 'express';
import type { BrowserAuthResponse } from '@servora/shared-types';
import { AuthService, type SignInResponse } from './auth.service.js';
import { SmsOtpService } from './sms-otp.service.js';
import {
  parseSignInRequest,
  parseSmsOtpRequest,
  parseSmsOtpVerifyRequest,
} from './auth-request.dto.js';
import { AuthApiError } from './auth-error.js';
import { readClientContext } from './client-context.js';
import { BrowserOriginGuard } from './browser-origin.guard.js';
import {
  BROWSER_AUTH_CONFIG,
  type BrowserAuthConfig,
} from './browser-auth.config.js';
import { AUTH_CONFIG } from './auth-config.provider.js';
import type { AuthConfig } from './auth-config.js';

@Controller('auth/web')
@UseGuards(BrowserOriginGuard)
export class BrowserAuthController {
  constructor(
    private readonly auth: AuthService,
    private readonly sms: SmsOtpService,
    @Inject(BROWSER_AUTH_CONFIG) private readonly config: BrowserAuthConfig,
    @Inject(AUTH_CONFIG) private readonly authConfig: AuthConfig,
  ) {}

  @Post('sign-in')
  @HttpCode(200)
  @Header('Cache-Control', 'no-store')
  async signIn(
    @Body() body: unknown,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<BrowserAuthResponse> {
    const input = parseSignInRequest(body);
    if (input.device.platform !== 'WEB')
      throw AuthApiError.validationFailed('device.platform must be WEB');
    return this.issue(
      await this.auth.signIn(input, readClientContext(req)),
      res,
    );
  }

  @Post('sms/request')
  @HttpCode(202)
  @Header('Cache-Control', 'no-store')
  async requestSms(@Body() body: unknown): Promise<void> {
    await this.sms.request(parseSmsOtpRequest(body));
  }

  @Post('sms/verify')
  @HttpCode(200)
  @Header('Cache-Control', 'no-store')
  async verifySms(
    @Body() body: unknown,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<BrowserAuthResponse> {
    const input = parseSmsOtpVerifyRequest(body);
    if (input.device.platform !== 'WEB')
      throw AuthApiError.validationFailed('device.platform must be WEB');
    return this.issue(
      await this.sms.verify(input, readClientContext(req)),
      res,
    );
  }

  @Post('refresh')
  @HttpCode(200)
  @Header('Cache-Control', 'no-store')
  async refresh(
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<BrowserAuthResponse> {
    const token = this.readCookie(req);
    if (!token) throw AuthApiError.refreshTokenInvalid();
    try {
      return this.issue(await this.auth.refresh(token), res);
    } catch (error) {
      if (error instanceof AuthApiError && error.getStatus() === 401)
        this.clear(res);
      throw error;
    }
  }

  @Post('sign-out')
  @HttpCode(204)
  @Header('Cache-Control', 'no-store')
  async signOut(
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    const token = this.readCookie(req);
    // On database failure retain the cookie so revocation can be retried.
    if (token) await this.auth.signOutByRefreshToken(token);
    this.clear(res);
  }

  private get name(): string {
    return this.config.secure ? '__Secure-servora-refresh' : 'servora-refresh';
  }
  private get options(): CookieOptions {
    return {
      httpOnly: true,
      secure: this.config.secure,
      sameSite: 'strict',
      path: '/auth/web',
    };
  }
  private readCookie(req: Request): string | undefined {
    const matches = (req.headers.cookie ?? '')
      .split(';')
      .map((part) => part.trim())
      .filter((part) => part.startsWith(`${this.name}=`));
    if (matches.length !== 1) return undefined;
    const token = matches[0].slice(this.name.length + 1);
    return /^[A-Za-z0-9_-]{1,512}$/.test(token) ? token : undefined;
  }
  private clear(res: Response): void {
    res.clearCookie(this.name, this.options);
  }
  private issue(tokens: SignInResponse, res: Response): BrowserAuthResponse {
    res.cookie(this.name, tokens.refreshToken, {
      ...this.options,
      maxAge: this.authConfig.sessionLifetimeMs,
    });
    return {
      accessToken: tokens.accessToken,
      accessTokenExpiresAt: tokens.accessTokenExpiresAt,
      sessionId: tokens.sessionId,
    };
  }
}
