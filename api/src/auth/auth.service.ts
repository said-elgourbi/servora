import { Inject, Injectable } from '@nestjs/common';
import { and, desc, eq, gt, isNull } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import { authRefreshTokens, authSessions, users } from '../database/schema.js';
import {
  DUMMY_PASSWORD_HASH,
  verifyPassword,
} from '../users/password-hasher.js';
import { canUserSignIn } from '../users/user-status-rules.js';
import type { UserStatus } from '../users/user.types.js';
import { issueAccessToken } from './access-token.js';
import { AUTH_CONFIG } from './auth-config.provider.js';
import type { AuthConfig } from './auth-config.js';
import { toAuthSessionDto, type AuthSessionDto } from './auth.dto.js';
import { AuthApiError } from './auth-error.js';
import type {
  AuthDeviceContext,
  SignInRequestDto,
} from './auth-request.dto.js';
import {
  generateOpaqueToken,
  hashOpaqueToken,
  tokenHashesEqual,
} from './auth-token.js';
import {
  isSessionActive,
  type AuthSession,
  type IssuedAuthTokens,
} from './auth.types.js';
import type { ClientContext } from './client-context.js';
import {
  isRefreshTokenUsable,
  successorExpiresAt,
} from './refresh-token-rules.js';

/** Successful sign-in and refresh body (`docs/api/authentication.md` §3.1). */
export interface SignInResponse extends IssuedAuthTokens {
  readonly sessionId: string;
}

/**
 * Session lifecycle for `/auth` (`docs/api/authentication.md`).
 *
 * The session row is the authority for revocation: a signed access token only says
 * which user and which session it was issued for (`ADR-004` D2), and every request
 * re-checks the row through `AuthGuard`.
 */
@Injectable()
export class AuthService {
  constructor(
    private readonly database: DatabaseService,
    @Inject(AUTH_CONFIG) private readonly config: AuthConfig,
  ) {}

  /**
   * Verifies email/password credentials, then opens one session for the device and
   * issues its first token pair (`BR-018`).
   *
   * Only an `ACTIVE` account signs in: `INACTIVE` and `SUSPENDED` accounts are
   * refused by `canUserSignIn`. Every rejection - unknown email, wrong password,
   * refused account - returns the same `401 INVALID_CREDENTIALS` after the same
   * Argon2id work, so sign-in reveals neither which email addresses exist nor
   * what state they are in (`docs/api/authentication.md` §3.1).
   */
  async signIn(
    input: SignInRequestDto,
    context: ClientContext,
    now: Date = new Date(),
  ): Promise<SignInResponse> {
    const [user] = await this.database.db
      .select({
        id: users.id,
        passwordHash: users.passwordHash,
        status: users.status,
      })
      .from(users)
      .where(eq(users.email, input.email))
      .limit(1);

    // Exactly one Argon2id verification runs on every rejected attempt, including
    // the unknown-email one, which is verified against a throwaway hash. Skipping
    // it would answer "is this email address registered?" through response timing
    // instead of the response body.
    const passwordMatches = await verifyPassword(
      user?.passwordHash ?? DUMMY_PASSWORD_HASH,
      input.password,
    );

    // Unknown email, wrong password and a refused account are one outcome to the
    // caller, so none of the three can be enumerated from the outside.
    if (
      user === undefined ||
      !passwordMatches ||
      !canUserSignIn(user.status as UserStatus)
    ) {
      throw AuthApiError.invalidCredentials();
    }

    return this.openSession(user.id, input.device, context, now);
  }

  /**
   * Opens one session for a device and issues its first token pair.
   *
   * Shared by every authentication method (`BR-019`): a phone verification produces exactly
   * the same session and tokens as a password sign-in, so there is one authenticated state in
   * the system and one place where a session is created.
   */
  async openSession(
    userId: string,
    device: AuthDeviceContext,
    context: ClientContext,
    now: Date = new Date(),
  ): Promise<SignInResponse> {
    const sessionExpiresAt = new Date(
      now.getTime() + this.config.sessionLifetimeMs,
    );
    const refreshToken = generateOpaqueToken();

    const session = await this.database.db.transaction(async (tx) => {
      const [created] = await tx
        .insert(authSessions)
        .values({
          userId,
          platform: device.platform,
          deviceId: device.deviceId,
          deviceName: device.deviceName,
          appVersion: device.appVersion,
          ipAddress: context.ipAddress,
          userAgent: context.userAgent,
          expiresAt: sessionExpiresAt,
        })
        .returning();

      // Only the hash is persisted; the raw token exists solely in the response
      // that hands it to the client (`ADR-004` D3).
      await tx.insert(authRefreshTokens).values({
        sessionId: created.id,
        tokenHash: hashOpaqueToken(refreshToken),
        expiresAt: sessionExpiresAt,
      });

      return created;
    });

    return this.issueTokens(session.id, userId, refreshToken, now);
  }
  /**
   * Rotates a refresh token (`ADR-004` D4): the presented token is revoked and
   * linked to its successor, so a replayed token stays detectable.
   */
  async refresh(
    rawRefreshToken: string,
    now: Date = new Date(),
  ): Promise<SignInResponse> {
    const presentedHash = hashOpaqueToken(rawRefreshToken);

    const [stored] = await this.database.db
      .select()
      .from(authRefreshTokens)
      .where(eq(authRefreshTokens.tokenHash, presentedHash))
      .limit(1);

    // The lookup already identified the row; comparing again in constant time keeps
    // "unknown token" and "rotated token" on the same path.
    if (
      stored === undefined ||
      !tokenHashesEqual(stored.tokenHash, presentedHash)
    ) {
      throw AuthApiError.refreshTokenInvalid();
    }
    if (!isRefreshTokenUsable(stored, now)) {
      throw AuthApiError.refreshTokenInvalid();
    }

    const session = await this.findSession(stored.sessionId);
    if (session === null || !isSessionActive(session, now)) {
      throw AuthApiError.refreshTokenInvalid();
    }

    const successorRefreshToken = generateOpaqueToken();
    const successorExpiry = successorExpiresAt(
      now,
      session.expiresAt,
      this.config.sessionLifetimeMs,
    );

    await this.database.db.transaction(async (tx) => {
      const [successor] = await tx
        .insert(authRefreshTokens)
        .values({
          sessionId: session.id,
          tokenHash: hashOpaqueToken(successorRefreshToken),
          expiresAt: successorExpiry,
        })
        .returning();

      await tx
        .update(authRefreshTokens)
        .set({ revokedAt: now, replacedByTokenId: successor.id })
        .where(eq(authRefreshTokens.id, stored.id));
    });

    return this.issueTokens(
      session.id,
      session.userId,
      successorRefreshToken,
      now,
    );
  }

  /**
   * Revokes one session. The row is recorded, never deleted (`ADR-004` D6), so
   * "was this session revoked, and when" stays answerable (`BR-033`).
   */
  async signOut(sessionId: string, now: Date = new Date()): Promise<void> {
    await this.database.db
      .update(authSessions)
      .set({ revokedAt: now })
      .where(eq(authSessions.id, sessionId));
  }

  /** The caller's own active sessions, most recently used first. */
  async listSessions(
    userId: string,
    now: Date = new Date(),
  ): Promise<AuthSessionDto[]> {
    const sessions = await this.database.db
      .select()
      .from(authSessions)
      .where(
        and(
          eq(authSessions.userId, userId),
          isNull(authSessions.revokedAt),
          gt(authSessions.expiresAt, now),
        ),
      )
      .orderBy(desc(authSessions.lastUsedAt));

    return sessions.map(toAuthSessionDto);
  }

  /** Loads a session for `AuthGuard`; `null` means the caller must not be trusted. */
  async findActiveSession(
    sessionId: string,
    now: Date = new Date(),
  ): Promise<AuthSession | null> {
    const session = await this.findSession(sessionId);
    return session !== null && isSessionActive(session, now) ? session : null;
  }

  /** Records that an authenticated request was accepted (`last_used_at`). */
  async touchSession(sessionId: string, now: Date = new Date()): Promise<void> {
    await this.database.db
      .update(authSessions)
      .set({ lastUsedAt: now })
      .where(eq(authSessions.id, sessionId));
  }

  private async findSession(sessionId: string): Promise<AuthSession | null> {
    const [session] = await this.database.db
      .select()
      .from(authSessions)
      .where(eq(authSessions.id, sessionId))
      .limit(1);

    return session ?? null;
  }

  private async issueTokens(
    sessionId: string,
    userId: string,
    refreshToken: string,
    now: Date,
  ): Promise<SignInResponse> {
    const issued = await issueAccessToken({
      userId,
      sessionId,
      secret: this.config.jwtSecret,
      lifetimeMs: this.config.accessTokenLifetimeMs,
      now,
    });

    return {
      sessionId,
      accessToken: issued.accessToken,
      accessTokenExpiresAt: issued.expiresAt.toISOString(),
      refreshToken,
    };
  }
}
