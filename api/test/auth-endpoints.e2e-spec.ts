import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import { hashOpaqueToken } from '../src/auth/auth-token.js';
import {
  authRefreshTokens,
  authSessions,
  users,
} from '../src/database/schema.js';
import { hashPassword } from '../src/users/password-hasher.js';
import {
  createFoundationTestDatabase,
  createTestUser,
  uniqueEmail,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

/**
 * HTTP contract of `/auth` (`docs/api/authentication.md` §3) against a real
 * PostgreSQL database, so rotation, revocation and row retention are verified as
 * they are actually persisted rather than as they are mocked.
 */
const PASSWORD = 'servora-e2e-password';

interface IssuedTokens {
  readonly sessionId: string;
  readonly accessToken: string;
  readonly accessTokenExpiresAt: string;
  readonly refreshToken: string;
}

describe('auth endpoints (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let userId: string;
  let email: string;
  let deviceSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();

    const moduleFixture = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleFixture.createNestApplication({ logger: false });
    await app.init();

    email = uniqueEmail();
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(PASSWORD),
    });
    database.cleanup.trackUser(user.id);
    userId = user.id;
  });

  afterAll(async () => {
    await app.close();
    await database.dispose();
  });

  function signInBody(
    overrides: Record<string, unknown> = {},
  ): Record<string, unknown> {
    deviceSequence += 1;
    return {
      email,
      password: PASSWORD,
      device: {
        platform: 'ANDROID',
        deviceId: `e2e-installation-${deviceSequence}`,
        deviceName: 'Pixel 8',
        appVersion: '1.0.0',
      },
      ...overrides,
    };
  }

  async function signIn(
    overrides: Record<string, unknown> = {},
  ): Promise<IssuedTokens> {
    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send(signInBody(overrides))
      .expect(200);

    return response.body as IssuedTokens;
  }

  describe('POST /auth/sign-in', () => {
    it('issues a token pair, a session and a hashed refresh token', async () => {
      const tokens = await signIn();

      expect(tokens.accessToken).toEqual(expect.any(String));
      expect(tokens.refreshToken).toEqual(expect.any(String));
      expect(Date.parse(tokens.accessTokenExpiresAt)).toBeGreaterThan(
        Date.now(),
      );

      const [session] = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.id, tokens.sessionId));

      expect(session.userId).toBe(userId);
      expect(session.platform).toBe('ANDROID');
      expect(session.revokedAt).toBeNull();

      const refreshTokens = await database.db
        .select()
        .from(authRefreshTokens)
        .where(eq(authRefreshTokens.sessionId, tokens.sessionId));

      expect(refreshTokens).toHaveLength(1);
      // The raw token is never stored.
      expect(refreshTokens[0]?.tokenHash).not.toBe(tokens.refreshToken);
    });

    it('never returns credential material in the response body', async () => {
      const tokens = await signIn();

      const [session] = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.id, tokens.sessionId));
      const [storedRefreshToken] = await database.db
        .select()
        .from(authRefreshTokens)
        .where(eq(authRefreshTokens.sessionId, session.id));

      const body = JSON.stringify(tokens);
      expect(body).not.toContain(storedRefreshToken?.tokenHash ?? 'unset');
      expect(body).not.toContain('argon2');
      expect(body).not.toContain(PASSWORD);
    });

    it('rejects a wrong password and an unknown email with the same response', async () => {
      const wrongPassword = await request(app.getHttpServer())
        .post('/auth/sign-in')
        .send(signInBody({ password: `${PASSWORD}-wrong` }))
        .expect(401);
      const unknownEmail = await request(app.getHttpServer())
        .post('/auth/sign-in')
        .send(signInBody({ email: uniqueEmail() }))
        .expect(401);

      expect(wrongPassword.body).toEqual(unknownEmail.body);
      expect(wrongPassword.body).toEqual({
        statusCode: 401,
        code: 'INVALID_CREDENTIALS',
        message: expect.any(String),
      });
    });

    it.each(['INACTIVE', 'SUSPENDED'])(
      'refuses a %s account exactly like an invalid credential',
      async (status) => {
        const refusedEmail = uniqueEmail();
        const refusedUser = await createTestUser(database.db, {
          email: refusedEmail,
          passwordHash: await hashPassword(PASSWORD),
          status,
        });
        database.cleanup.trackUser(refusedUser.id);

        // The account exists, is in the refused status, and its stored hash is the
        // right password, so the 401 below can only come from the status check.
        const [storedUser] = await database.db
          .select({ status: users.status })
          .from(users)
          .where(eq(users.id, refusedUser.id));
        expect(storedUser?.status).toBe(status);

        const response = await request(app.getHttpServer())
          .post('/auth/sign-in')
          .send(signInBody({ email: refusedEmail }))
          .expect(401);

        expect(response.body).toEqual({
          statusCode: 401,
          code: 'INVALID_CREDENTIALS',
          message: expect.any(String),
        });

        // A refused account leaves no session behind.
        const sessions = await database.db
          .select()
          .from(authSessions)
          .where(eq(authSessions.userId, refusedUser.id));
        expect(sessions).toHaveLength(0);
      },
    );

    it('rejects a malformed payload with a stable validation code', async () => {
      const response = await request(app.getHttpServer())
        .post('/auth/sign-in')
        .send(
          signInBody({ device: { platform: 'IOS', deviceId: 'install-1' } }),
        )
        .expect(400);

      expect(response.body).toEqual({
        statusCode: 400,
        code: 'VALIDATION_FAILED',
        message: expect.stringContaining('device.platform'),
      });
    });

    it('rejects an empty body without creating a session', async () => {
      const before = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.userId, userId));

      await request(app.getHttpServer())
        .post('/auth/sign-in')
        .send({})
        .expect(400);

      const after = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.userId, userId));

      expect(after).toHaveLength(before.length);
    });
  });
  describe('GET /auth/sessions', () => {
    it('requires an access token', async () => {
      const missing = await request(app.getHttpServer())
        .get('/auth/sessions')
        .expect(401);
      const malformed = await request(app.getHttpServer())
        .get('/auth/sessions')
        .set('Authorization', 'Bearer not-a-token')
        .expect(401);
      const wrongScheme = await request(app.getHttpServer())
        .get('/auth/sessions')
        .set('Authorization', 'Basic abc')
        .expect(401);

      const expected = {
        statusCode: 401,
        code: 'UNAUTHENTICATED',
        message: expect.any(String),
      };
      expect(missing.body).toEqual(expected);
      expect(malformed.body).toEqual(expected);
      expect(wrongScheme.body).toEqual(expected);
    });

    it("lists only the caller's own active sessions", async () => {
      const mine = await signIn();
      const strangerEmail = uniqueEmail();
      const stranger = await createTestUser(database.db, {
        email: strangerEmail,
        passwordHash: await hashPassword(PASSWORD),
      });
      database.cleanup.trackUser(stranger.id);

      const strangerSignIn = await request(app.getHttpServer())
        .post('/auth/sign-in')
        .send({
          email: strangerEmail,
          password: PASSWORD,
          device: { platform: 'WEB', deviceId: 'stranger-installation' },
        })
        .expect(200);

      const response = await request(app.getHttpServer())
        .get('/auth/sessions')
        .set('Authorization', `Bearer ${mine.accessToken}`)
        .expect(200);

      const sessions = response.body as { id: string; userId: string }[];
      expect(sessions.map((session) => session.id)).toContain(mine.sessionId);
      expect(sessions.map((session) => session.id)).not.toContain(
        (strangerSignIn.body as IssuedTokens).sessionId,
      );
      for (const session of sessions) {
        expect(session.userId).toBe(userId);
      }
    });

    it('records that an authenticated request used the session', async () => {
      const tokens = await signIn();
      const [before] = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.id, tokens.sessionId));

      await new Promise((resolve) => setTimeout(resolve, 20));

      await request(app.getHttpServer())
        .get('/auth/sessions')
        .set('Authorization', `Bearer ${tokens.accessToken}`)
        .expect(200);

      const [after] = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.id, tokens.sessionId));

      expect(after.lastUsedAt.getTime()).toBeGreaterThan(
        before.lastUsedAt.getTime(),
      );
    });
  });
  describe('POST /auth/sign-out', () => {
    it('requires an access token', async () => {
      await request(app.getHttpServer()).post('/auth/sign-out').expect(401);
    });

    it('revokes the session and invalidates its access token immediately', async () => {
      const tokens = await signIn();

      await request(app.getHttpServer())
        .post('/auth/sign-out')
        .set('Authorization', `Bearer ${tokens.accessToken}`)
        .expect(204);

      // Revocation is recorded, never deleted (`ADR-004` D6).
      const [session] = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.id, tokens.sessionId));
      expect(session.revokedAt).not.toBeNull();

      // The token stops working on the next request instead of surviving until `exp`.
      await request(app.getHttpServer())
        .get('/auth/sessions')
        .set('Authorization', `Bearer ${tokens.accessToken}`)
        .expect(401);
      await request(app.getHttpServer())
        .post('/auth/sign-out')
        .set('Authorization', `Bearer ${tokens.accessToken}`)
        .expect(401);
    });
  });

  describe('POST /auth/refresh', () => {
    it('rejects a malformed payload', async () => {
      await request(app.getHttpServer())
        .post('/auth/refresh')
        .send({})
        .expect(400);
    });

    it('rotates the refresh token and links the successor', async () => {
      const tokens = await signIn();

      const response = await request(app.getHttpServer())
        .post('/auth/refresh')
        .send({ refreshToken: tokens.refreshToken })
        .expect(200);
      const rotated = response.body as IssuedTokens;

      expect(rotated.sessionId).toBe(tokens.sessionId);
      expect(rotated.refreshToken).not.toBe(tokens.refreshToken);

      // The access token may be byte-identical when both are issued within the same
      // second, because `iat`/`exp` have one-second resolution. The contract that
      // matters is that the rotated token authenticates the same session.
      await request(app.getHttpServer())
        .get('/auth/sessions')
        .set('Authorization', `Bearer ${rotated.accessToken}`)
        .expect(200);

      const rows = await database.db
        .select()
        .from(authRefreshTokens)
        .where(eq(authRefreshTokens.sessionId, tokens.sessionId));
      expect(rows).toHaveLength(2);

      const previous = rows.find(
        (row) => row.tokenHash === hashOpaqueToken(tokens.refreshToken),
      );
      const successor = rows.find((row) => row.id !== previous?.id);
      expect(previous?.revokedAt).not.toBeNull();
      expect(previous?.replacedByTokenId).toBe(successor?.id);
      expect(successor?.tokenHash).toBe(hashOpaqueToken(rotated.refreshToken));
    });

    it('rejects a reused token and an unknown token identically', async () => {
      const tokens = await signIn();
      await request(app.getHttpServer())
        .post('/auth/refresh')
        .send({ refreshToken: tokens.refreshToken })
        .expect(200);

      const reused = await request(app.getHttpServer())
        .post('/auth/refresh')
        .send({ refreshToken: tokens.refreshToken })
        .expect(401);
      const unknown = await request(app.getHttpServer())
        .post('/auth/refresh')
        .send({ refreshToken: 'never-issued-token' })
        .expect(401);

      expect(reused.body).toEqual(unknown.body);
      expect(reused.body).toEqual({
        statusCode: 401,
        code: 'REFRESH_TOKEN_INVALID',
        message: expect.any(String),
      });
    });

    it('rejects a refresh after the session was signed out', async () => {
      const tokens = await signIn();

      await request(app.getHttpServer())
        .post('/auth/sign-out')
        .set('Authorization', `Bearer ${tokens.accessToken}`)
        .expect(204);

      await request(app.getHttpServer())
        .post('/auth/refresh')
        .send({ refreshToken: tokens.refreshToken })
        .expect(401);
    });
  });
});
