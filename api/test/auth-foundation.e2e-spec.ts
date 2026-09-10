import { eq } from 'drizzle-orm';
import {
  authRefreshTokens,
  authSessions,
  passwordResetTokens,
  users,
} from '../src/database/schema.js';
import {
  generateOpaqueToken,
  hashOpaqueToken,
} from '../src/auth/auth-token.js';
import { loadAuthConfig } from '../src/auth/auth-config.js';
import {
  createFoundationTestDatabase,
  createTestUser,
  expectPostgresError,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

// Requires PostgreSQL at DATABASE_URL with migrations applied (global setup), so
// this spec verifies the generated migration itself rather than a hand-built
// expectation of it.

const { sessionLifetimeMs } = loadAuthConfig();
const sessionExpiry = () => new Date(Date.now() + sessionLifetimeMs);

describe('authentication schema (e2e)', () => {
  let database: FoundationTestDatabase;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
  });

  afterAll(async () => {
    await database.dispose();
  });

  async function createSession(
    userId: string,
    overrides: { platform?: string; deviceId?: string } = {},
  ) {
    const [session] = await database.db
      .insert(authSessions)
      .values({
        userId,
        platform: overrides.platform ?? 'ANDROID',
        deviceId: overrides.deviceId ?? 'device-1',
        expiresAt: sessionExpiry(),
      })
      .returning();
    return session;
  }

  describe('auth_sessions', () => {
    it('creates a session with a generated id and default timestamps', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);

      const before = Date.now();
      const session = await createSession(user.id);

      expect(session.id).toMatch(/^[0-9a-f-]{36}$/);
      expect(session.platform).toBe('ANDROID');
      expect(session.createdAt).toBeInstanceOf(Date);
      expect(session.lastUsedAt.getTime()).toBeGreaterThanOrEqual(
        before - 1000,
      );
      expect(session.revokedAt).toBeNull();
    });

    it('stores the client context used to present the session', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);

      const [session] = await database.db
        .insert(authSessions)
        .values({
          userId: user.id,
          platform: 'WEB',
          deviceId: 'browser-1',
          deviceName: 'Office desktop',
          appVersion: '1.4.0',
          ipAddress: '203.0.113.7',
          userAgent: 'Mozilla/5.0',
          expiresAt: sessionExpiry(),
        })
        .returning();

      expect(session.deviceName).toBe('Office desktop');
      expect(session.ipAddress).toBe('203.0.113.7');
      expect(session.userAgent).toBe('Mozilla/5.0');
    });

    it('allows several sessions for the same user and device', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);

      const first = await createSession(user.id, { deviceId: 'shared-device' });
      const second = await createSession(user.id, {
        deviceId: 'shared-device',
      });

      expect(first.id).not.toBe(second.id);
    });

    it('rejects a platform outside the supported vocabulary', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);

      await expectPostgresError(
        () => createSession(user.id, { platform: 'IOS' }),
        '23514',
      );
    });

    it('rejects a session for a user that does not exist', async () => {
      await expectPostgresError(
        () => createSession('00000000-0000-0000-0000-000000000000'),
        '23503',
      );
    });

    it('removes sessions when the user is deleted', async () => {
      const user = await createTestUser(database.db);
      await createSession(user.id);

      await database.db.delete(users).where(eq(users.id, user.id));

      const remaining = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.userId, user.id));
      expect(remaining).toHaveLength(0);
    });
  });

  describe('auth_refresh_tokens', () => {
    it('stores only the hash of a refresh token', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);
      const session = await createSession(user.id);
      const rawToken = generateOpaqueToken();

      const [token] = await database.db
        .insert(authRefreshTokens)
        .values({
          sessionId: session.id,
          tokenHash: hashOpaqueToken(rawToken),
          expiresAt: sessionExpiry(),
        })
        .returning();

      expect(token.tokenHash).toMatch(/^[0-9a-f]{64}$/);
      expect(token.revokedAt).toBeNull();

      const columns = (
        await database.client`select column_name from information_schema.columns
          where table_schema = 'public' and table_name = 'auth_refresh_tokens'`
      ).map((row) => row.column_name as string);

      expect(columns).toContain('token_hash');
      expect(columns).not.toContain('token');
      expect(columns).not.toContain('refresh_token');
    });

    it('rejects a duplicate token hash', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);
      const session = await createSession(user.id);
      const tokenHash = hashOpaqueToken(generateOpaqueToken());

      await database.db.insert(authRefreshTokens).values({
        sessionId: session.id,
        tokenHash,
        expiresAt: sessionExpiry(),
      });

      await expectPostgresError(
        () =>
          database.db.insert(authRefreshTokens).values({
            sessionId: session.id,
            tokenHash,
            expiresAt: sessionExpiry(),
          }),
        '23505',
      );
    });

    it('rejects a token for a session that does not exist', async () => {
      await expectPostgresError(
        () =>
          database.db.insert(authRefreshTokens).values({
            sessionId: '00000000-0000-0000-0000-000000000000',
            tokenHash: hashOpaqueToken(generateOpaqueToken()),
            expiresAt: sessionExpiry(),
          }),
        '23503',
      );
    });

    it('removes refresh tokens when their session is deleted', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);
      const session = await createSession(user.id);
      await database.db.insert(authRefreshTokens).values({
        sessionId: session.id,
        tokenHash: hashOpaqueToken(generateOpaqueToken()),
        expiresAt: sessionExpiry(),
      });

      await database.db
        .delete(authSessions)
        .where(eq(authSessions.id, session.id));

      const remaining = await database.db
        .select()
        .from(authRefreshTokens)
        .where(eq(authRefreshTokens.sessionId, session.id));
      expect(remaining).toHaveLength(0);
    });

    it('records rotation and keeps the history when the successor is removed', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);
      const session = await createSession(user.id);

      const [successor] = await database.db
        .insert(authRefreshTokens)
        .values({
          sessionId: session.id,
          tokenHash: hashOpaqueToken(generateOpaqueToken()),
          expiresAt: sessionExpiry(),
        })
        .returning();

      const [predecessor] = await database.db
        .insert(authRefreshTokens)
        .values({
          sessionId: session.id,
          tokenHash: hashOpaqueToken(generateOpaqueToken()),
          expiresAt: sessionExpiry(),
          revokedAt: new Date(),
          replacedByTokenId: successor.id,
        })
        .returning();

      expect(predecessor.replacedByTokenId).toBe(successor.id);
      expect(predecessor.revokedAt).toBeInstanceOf(Date);

      await database.db
        .delete(authRefreshTokens)
        .where(eq(authRefreshTokens.id, successor.id));

      const [reloaded] = await database.db
        .select()
        .from(authRefreshTokens)
        .where(eq(authRefreshTokens.id, predecessor.id));
      expect(reloaded.replacedByTokenId).toBeNull();
    });
  });

  describe('password_reset_tokens', () => {
    it('stores an unused single-use token', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);

      const [token] = await database.db
        .insert(passwordResetTokens)
        .values({
          userId: user.id,
          tokenHash: hashOpaqueToken(generateOpaqueToken()),
          expiresAt: sessionExpiry(),
        })
        .returning();

      expect(token.tokenHash).toMatch(/^[0-9a-f]{64}$/);
      expect(token.usedAt).toBeNull();
    });

    it('allows several unused tokens for the same user', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);

      await database.db.insert(passwordResetTokens).values({
        userId: user.id,
        tokenHash: hashOpaqueToken(generateOpaqueToken()),
        expiresAt: sessionExpiry(),
      });
      await database.db.insert(passwordResetTokens).values({
        userId: user.id,
        tokenHash: hashOpaqueToken(generateOpaqueToken()),
        expiresAt: sessionExpiry(),
      });

      const rows = await database.db
        .select()
        .from(passwordResetTokens)
        .where(eq(passwordResetTokens.userId, user.id));
      expect(rows).toHaveLength(2);
    });

    it('rejects a duplicate token hash', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);
      const tokenHash = hashOpaqueToken(generateOpaqueToken());

      await database.db
        .insert(passwordResetTokens)
        .values({ userId: user.id, tokenHash, expiresAt: sessionExpiry() });

      await expectPostgresError(
        () =>
          database.db
            .insert(passwordResetTokens)
            .values({ userId: user.id, tokenHash, expiresAt: sessionExpiry() }),
        '23505',
      );
    });

    it('rejects a reset token for a user that does not exist', async () => {
      await expectPostgresError(
        () =>
          database.db.insert(passwordResetTokens).values({
            userId: '00000000-0000-0000-0000-000000000000',
            tokenHash: hashOpaqueToken(generateOpaqueToken()),
            expiresAt: sessionExpiry(),
          }),
        '23503',
      );
    });

    it('removes reset tokens when the user is deleted', async () => {
      const user = await createTestUser(database.db);
      await database.db.insert(passwordResetTokens).values({
        userId: user.id,
        tokenHash: hashOpaqueToken(generateOpaqueToken()),
        expiresAt: sessionExpiry(),
      });

      await database.db.delete(users).where(eq(users.id, user.id));

      const remaining = await database.db
        .select()
        .from(passwordResetTokens)
        .where(eq(passwordResetTokens.userId, user.id));
      expect(remaining).toHaveLength(0);
    });
  });

  describe('index integrity', () => {
    it('keeps index predicates immutable', async () => {
      const definitions = (
        await database.client`select indexdef from pg_indexes
          where schemaname = 'public'
            and tablename in ('auth_sessions', 'auth_refresh_tokens', 'password_reset_tokens')`
      ).map((row) => row.indexdef as string);

      expect(definitions.length).toBeGreaterThan(0);
      // `now()` is only stable within a single statement, so PostgreSQL rejects it
      // in an index predicate; a time-dependent predicate would also make the
      // index silently stop matching rows as time passes.
      for (const definition of definitions) {
        expect(definition).not.toMatch(/now\(\)/i);
      }
    });

    it('indexes the active-session lookup by revocation only', async () => {
      const rows = await database.client`select indexdef from pg_indexes
        where schemaname = 'public' and indexname = 'auth_sessions_active_idx'`;

      expect(rows).toHaveLength(1);
      const definition = rows[0].indexdef as string;
      expect(definition).toMatch(/revoked_at IS NULL/i);
      expect(definition).not.toMatch(/expires_at/i);
    });
  });
});
