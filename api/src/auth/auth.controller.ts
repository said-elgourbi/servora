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
import { parseRefreshRequest, parseSignInRequest } from './auth-request.dto.js';
import { AuthService, type SignInResponse } from './auth.service.js';
import { AuthApiError } from './auth-error.js';
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
  constructor(private readonly auth: AuthService) {}

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
