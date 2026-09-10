import {
  CanActivate,
  ExecutionContext,
  Inject,
  Injectable,
} from '@nestjs/common';
import type { IncomingHttpHeaders } from 'node:http';
import {
  InvalidAccessTokenError,
  verifyAccessToken,
  type AccessTokenClaims,
} from './access-token.js';
import { AUTH_CONFIG } from './auth-config.provider.js';
import type { AuthConfig } from './auth-config.js';
import { AuthApiError } from './auth-error.js';
import { AuthService } from './auth.service.js';

/** Authenticated caller attached to the request by `AuthGuard`. */
export interface RequestAuth {
  readonly userId: string;
  readonly sessionId: string;
}

/** Request shape seen inside guarded handlers. */
export interface AuthenticatedRequest {
  readonly headers: IncomingHttpHeaders;
  auth?: RequestAuth;
}

const BEARER_PREFIX = 'Bearer ';

/**
 * Proves the caller holds a valid access token *and* that the session behind it is
 * still active (`ADR-004` D2).
 *
 * The token is never trusted alone: a revoked or expired session fails here on the
 * very next request instead of continuing to work until its access token happens
 * to expire — which is what `BR-013`/`BR-014` need from an offline-first client.
 *
 * Failures throw the same vague `401 UNAUTHENTICATED`, so nothing about why a
 * credential was rejected is disclosed (`dev.md` §11).
 */
@Injectable()
export class AuthGuard implements CanActivate {
  constructor(
    private readonly auth: AuthService,
    @Inject(AUTH_CONFIG) private readonly config: AuthConfig,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const accessToken = readBearerToken(request.headers.authorization);
    if (accessToken === null) {
      throw AuthApiError.unauthenticated();
    }

    let claims: AccessTokenClaims;
    try {
      claims = await verifyAccessToken(accessToken, this.config.jwtSecret);
    } catch (error) {
      if (error instanceof InvalidAccessTokenError) {
        throw AuthApiError.unauthenticated();
      }
      throw error;
    }

    const session = await this.auth.findActiveSession(claims.sessionId);
    // The session owner must match the token subject, so a token cannot be replayed
    // against a session it was not issued for.
    if (session === null || session.userId !== claims.userId) {
      throw AuthApiError.unauthenticated();
    }

    request.auth = { userId: session.userId, sessionId: session.id };
    await this.auth.touchSession(session.id);
    return true;
  }
}

function readBearerToken(header: string | string[] | undefined): string | null {
  const value = Array.isArray(header) ? header[0] : header;
  if (typeof value !== 'string' || !value.startsWith(BEARER_PREFIX)) {
    return null;
  }
  const token = value.slice(BEARER_PREFIX.length).trim();
  return token.length === 0 ? null : token;
}
