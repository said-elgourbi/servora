import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { and, eq, isNull } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import { hashAuthCode } from '../src/auth/auth-code.js';
import { passwordResetTokens, users } from '../src/database/schema.js';
import {
  EMAIL_PROVIDER,
  EmailDeliveryError,
} from '../src/email/email-provider.js';
import { FakeEmailProvider } from '../src/email/providers/fake-email-provider.js';
import {
  PASSWORD_RESET_NOTIFIER,
  type PasswordResetMessage,
  type PasswordResetNotifier,
} from '../src/notifications/password-reset-notifier.js';
import { hashPassword, verifyPassword } from '../src/users/password-hasher.js';
import {
  createFoundationTestDatabase,
  createTestUser,
  uniqueEmail,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

/**
 * HTTP contract of the forgotten-password flow (`BR-043`, `BR-044`, `BR-045`) against a real
 * PostgreSQL database.
 *
 * The delivery port is replaced with a recording double, because the suite must not send email
 * and because the code is only obtainable from the delivered message — the API never returns
 * it (`BR-046`).
 */
const PASSWORD = 'servora-e2e-password';
const NEW_PASSWORD = 'servora-e2e-new-password';

class RecordingPasswordResetNotifier implements PasswordResetNotifier {
  readonly messages: PasswordResetMessage[] = [];

  async send(message: PasswordResetMessage): Promise<void> {
    this.messages.push(message);
  }

  get last(): PasswordResetMessage | null {
    return this.messages.at(-1) ?? null;
  }

  get deliveredCode(): string {
    const message = this.last;
    if (message === null) {
      throw new Error('No password-reset message was delivered.');
    }
    return message.code;
  }

  reset(): void {
    this.messages.length = 0;
  }
}

describe('password reset (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let notifier: RecordingPasswordResetNotifier;
  let jwtSecret: string;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    notifier = new RecordingPasswordResetNotifier();
    jwtSecret = process.env.JWT_SECRET ?? '';
    expect(jwtSecret.length).toBeGreaterThan(0);

    const moduleFixture = await Test.createTestingModule({
      imports: [AppModule],
    })
      .overrideProvider(PASSWORD_RESET_NOTIFIER)
      .useValue(notifier)
      .compile();

    app = moduleFixture.createNestApplication({ logger: false });
    await app.init();
  });

  afterAll(async () => {
    await app.close();
    await database.dispose();
  });

  function http() {
    return request(app.getHttpServer());
  }

  async function createAccount(
    overrides: { email?: string; status?: string } = {},
  ): Promise<{ id: string; email: string }> {
    const email = overrides.email ?? uniqueEmail('reset');
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(PASSWORD),
      ...(overrides.status === undefined ? {} : { status: overrides.status }),
    });
    database.cleanup.trackUser(user.id);
    return { id: user.id, email };
  }

  /** Requests a reset and returns the code that was delivered. */
  async function requestReset(email: string): Promise<string> {
    notifier.reset();
    await http()
      .post('/auth/password-reset/request')
      .send({ email })
      .expect(202);
    return notifier.deliveredCode;
  }

  async function storedCredentials(userId: string) {
    return database.db
      .select()
      .from(passwordResetTokens)
      .where(eq(passwordResetTokens.userId, userId));
  }

  describe('POST /auth/password-reset/request', () => {
    it('answers 202, stores only a digest and delivers one code', async () => {
      const account = await createAccount();

      const response = await http()
        .post('/auth/password-reset/request')
        .send({ email: account.email })
        .expect(202);

      // Nothing is returned that could describe the account.
      expect(Object.keys(response.body)).toHaveLength(0);

      const credentials = await storedCredentials(account.id);
      expect(credentials).toHaveLength(1);

      const [credential] = credentials;
      expect(credential?.usedAt).toBeNull();
      expect(credential?.attempts).toBe(0);
      expect(credential?.tokenHash).not.toBe(notifier.deliveredCode);
      // A six-digit code is stored as a keyed digest, never as the code (`BR-046`).
      expect(credential?.tokenHash).toMatch(/^[0-9a-f]{64}$/);

      const lifetimeMs = (credential?.expiresAt.getTime() ?? 0) - Date.now();
      expect(lifetimeMs).toBeGreaterThan(29 * 60_000);
      expect(lifetimeMs).toBeLessThanOrEqual(30 * 60_000);

      expect(notifier.messages).toHaveLength(1);
      expect(notifier.last?.to).toBe(account.email);
      expect(notifier.deliveredCode).toMatch(/^[0-9]{6}$/);
      expect(notifier.last?.body).toContain(notifier.deliveredCode);
    });

    it('answers identically for an unknown address, without delivering anything', async () => {
      const account = await createAccount();
      const unknownEmail = uniqueEmail('unknown');

      const known = await http()
        .post('/auth/password-reset/request')
        .send({ email: account.email });
      const unknown = await http()
        .post('/auth/password-reset/request')
        .send({ email: unknownEmail });

      // Same status and same empty body: the response is not an account oracle (`BR-044`).
      expect(unknown.status).toBe(known.status);
      expect(unknown.body).toEqual(known.body);

      notifier.reset();
      await http()
        .post('/auth/password-reset/request')
        .send({ email: unknownEmail })
        .expect(202);
      expect(notifier.messages).toHaveLength(0);
    });

    it('rejects a value that is not an address before looking anything up', async () => {
      const response = await http()
        .post('/auth/password-reset/request')
        .send({ email: 'not-an-address' })
        .expect(400);

      expect(response.body).toEqual({
        statusCode: 400,
        code: 'VALIDATION_FAILED',
        message: expect.any(String),
      });
    });

    it('throttles a known identity and an unknown identity the same way', async () => {
      const account = await createAccount();
      const unknownEmail = uniqueEmail('throttled');

      // The approved limit is three requests per identity per 15 minutes (`BR-045`).
      for (let attempt = 0; attempt < 3; attempt += 1) {
        await http()
          .post('/auth/password-reset/request')
          .send({ email: account.email })
          .expect(202);
        await http()
          .post('/auth/password-reset/request')
          .send({ email: unknownEmail })
          .expect(202);
      }

      const refusedKnown = await http()
        .post('/auth/password-reset/request')
        .send({ email: account.email })
        .expect(429);
      const refusedUnknown = await http()
        .post('/auth/password-reset/request')
        .send({ email: unknownEmail })
        .expect(429);

      expect(refusedKnown.body).toEqual({
        statusCode: 429,
        code: 'TOO_MANY_REQUESTS',
        message: expect.any(String),
      });
      // Identical for both identities, so throttling cannot be used to enumerate.
      expect(refusedUnknown.body).toEqual(refusedKnown.body);
    });
  });

  describe('POST /auth/password-reset/verify', () => {
    it('accepts the delivered code without consuming it', async () => {
      const account = await createAccount();
      const code = await requestReset(account.email);

      await http()
        .post('/auth/password-reset/verify')
        .send({ email: account.email, code })
        .expect(204);

      const credentials = await storedCredentials(account.id);
      // Still redeemable: verification only proves the code was right.
      expect(credentials[0]?.usedAt).toBeNull();
      expect(credentials[0]?.attempts).toBe(0);
    });

    it('rejects a wrong code and counts the attempt', async () => {
      const account = await createAccount();
      const code = await requestReset(account.email);
      const wrongCode = code === '000000' ? '111111' : '000000';

      const response = await http()
        .post('/auth/password-reset/verify')
        .send({ email: account.email, code: wrongCode })
        .expect(401);

      expect(response.body).toEqual({
        statusCode: 401,
        code: 'RESET_CODE_INVALID',
        message: expect.any(String),
      });

      const credentials = await storedCredentials(account.id);
      expect(credentials[0]?.attempts).toBe(1);
    });

    it('rejects an unknown identity with an identical body', async () => {
      const account = await createAccount();
      const code = await requestReset(account.email);
      const wrongCode = code === '000000' ? '111111' : '000000';

      const knownWrong = await http()
        .post('/auth/password-reset/verify')
        .send({ email: account.email, code: wrongCode })
        .expect(401);
      const unknown = await http()
        .post('/auth/password-reset/verify')
        .send({ email: uniqueEmail('nobody'), code })
        .expect(401);

      expect(unknown.body).toEqual(knownWrong.body);
    });

    it('rejects an expired credential', async () => {
      const account = await createAccount();
      const code = '246810';

      await database.db.insert(passwordResetTokens).values({
        userId: account.id,
        tokenHash: hashAuthCode(jwtSecret, 'PASSWORD_RESET', account.id, code),
        expiresAt: new Date(Date.now() - 1000),
      });

      await http()
        .post('/auth/password-reset/verify')
        .send({ email: account.email, code })
        .expect(401);
    });

    it('stops accepting the correct code once the attempt limit is reached', async () => {
      const account = await createAccount();
      const code = await requestReset(account.email);
      const wrongCode = code === '000000' ? '111111' : '000000';

      for (let attempt = 0; attempt < 5; attempt += 1) {
        await http()
          .post('/auth/password-reset/verify')
          .send({ email: account.email, code: wrongCode })
          .expect(401);
      }

      const credentials = await storedCredentials(account.id);
      expect(credentials[0]?.attempts).toBe(5);

      // The credential is now unusable even for the right code.
      await http()
        .post('/auth/password-reset/verify')
        .send({ email: account.email, code })
        .expect(401);
    });

    it('supersedes the previous code when a new one is requested', async () => {
      const account = await createAccount();
      const firstCode = await requestReset(account.email);
      const secondCode = await requestReset(account.email);

      expect(secondCode).not.toBe(firstCode);

      await http()
        .post('/auth/password-reset/verify')
        .send({ email: account.email, code: firstCode })
        .expect(401);
      await http()
        .post('/auth/password-reset/verify')
        .send({ email: account.email, code: secondCode })
        .expect(204);
    });
  });

  describe('POST /auth/password-reset/complete', () => {
    it('changes the password, consumes the credential and returns no session', async () => {
      const account = await createAccount();
      const code = await requestReset(account.email);

      const response = await http()
        .post('/auth/password-reset/complete')
        .send({ email: account.email, code, newPassword: NEW_PASSWORD })
        .expect(204);

      // A reset does not sign the user in (`BR-043`).
      expect(Object.keys(response.body)).toHaveLength(0);

      const [storedUser] = await database.db
        .select({ passwordHash: users.passwordHash })
        .from(users)
        .where(eq(users.id, account.id));

      const hash = storedUser?.passwordHash ?? '';
      expect(await verifyPassword(hash, NEW_PASSWORD)).toBe(true);
      expect(await verifyPassword(hash, PASSWORD)).toBe(false);

      const credentials = await storedCredentials(account.id);
      expect(
        credentials.every((credential) => credential.usedAt !== null),
      ).toBe(true);
    });

    it('lets the user sign in with the new password and not the old one', async () => {
      const account = await createAccount();
      const code = await requestReset(account.email);

      await http()
        .post('/auth/password-reset/complete')
        .send({ email: account.email, code, newPassword: NEW_PASSWORD })
        .expect(204);

      const device = {
        platform: 'ANDROID',
        deviceId: 'reset-e2e-installation',
        deviceName: 'Pixel 8',
        appVersion: '1.0.0',
      };

      await http()
        .post('/auth/sign-in')
        .send({ email: account.email, password: PASSWORD, device })
        .expect(401);
      await http()
        .post('/auth/sign-in')
        .send({ email: account.email, password: NEW_PASSWORD, device })
        .expect(200);
    });

    it('cannot be replayed with the same code', async () => {
      const account = await createAccount();
      const code = await requestReset(account.email);

      await http()
        .post('/auth/password-reset/complete')
        .send({ email: account.email, code, newPassword: NEW_PASSWORD })
        .expect(204);
      await http()
        .post('/auth/password-reset/complete')
        .send({ email: account.email, code, newPassword: 'another-password' })
        .expect(401);
    });

    it('consumes every outstanding credential for the account', async () => {
      const account = await createAccount();
      const supersededCode = await requestReset(account.email);
      const currentCode = await requestReset(account.email);

      await http()
        .post('/auth/password-reset/complete')
        .send({
          email: account.email,
          code: currentCode,
          newPassword: NEW_PASSWORD,
        })
        .expect(204);

      expect(currentCode).not.toBe(supersededCode);

      const outstanding = await database.db
        .select()
        .from(passwordResetTokens)
        .where(
          and(
            eq(passwordResetTokens.userId, account.id),
            isNull(passwordResetTokens.usedAt),
          ),
        );
      expect(outstanding).toHaveLength(0);
    });

    it('rejects a too-short password with the project policy', async () => {
      const account = await createAccount();
      const code = await requestReset(account.email);

      const response = await http()
        .post('/auth/password-reset/complete')
        .send({ email: account.email, code, newPassword: 'short' })
        .expect(400);

      expect(response.body.code).toBe('VALIDATION_FAILED');

      // The credential is untouched, so the user can retry with a valid password.
      const credentials = await storedCredentials(account.id);
      expect(credentials[0]?.usedAt).toBeNull();
    });

    it('never returns the code, the digest or the password', async () => {
      const account = await createAccount();
      const code = await requestReset(account.email);

      const response = await http()
        .post('/auth/password-reset/complete')
        .send({ email: account.email, code, newPassword: NEW_PASSWORD })
        .expect(204);

      const [credential] = await storedCredentials(account.id);
      expect(response.text).not.toContain(code);
      expect(response.text).not.toContain(credential?.tokenHash ?? 'unset');
      expect(response.text).not.toContain(NEW_PASSWORD);
    });
  });

  /**
   * The delivery chain with the approved email transport selected (`ADR-008`): configuration
   * selects the adapter, the adapter uses the bound `EmailProvider`, and the code still never
   * appears in a response (`BR-046`).
   */
  describe('delivery through the email provider', () => {
    let emailApp: INestApplication;
    let emailProvider: FakeEmailProvider;
    const previousDelivery = process.env.PASSWORD_RESET_DELIVERY;

    beforeAll(async () => {
      process.env.PASSWORD_RESET_DELIVERY = 'email';
      emailProvider = new FakeEmailProvider();

      const moduleFixture = await Test.createTestingModule({
        imports: [AppModule],
      })
        .overrideProvider(EMAIL_PROVIDER)
        .useValue(emailProvider)
        .compile();

      emailApp = moduleFixture.createNestApplication({ logger: false });
      await emailApp.init();
    });

    afterAll(async () => {
      await emailApp.close();
      if (previousDelivery === undefined) {
        delete process.env.PASSWORD_RESET_DELIVERY;
      } else {
        process.env.PASSWORD_RESET_DELIVERY = previousDelivery;
      }
    });

    it('delivers the composed message through the port', async () => {
      const account = await createAccount();
      emailProvider.reset();

      await request(emailApp.getHttpServer())
        .post('/auth/password-reset/request')
        .send({ email: account.email })
        .expect(202);

      const message = emailProvider.last;
      expect(message?.to).toBe(account.email);
      // The subject and body were composed by the authentication domain, in the recipient's
      // language, so the transport adds no copy of its own (`BR-028`).
      expect(message?.subject).toBe('Reset your Servora password');
      expect(message?.text).toMatch(
        /Use this code to reset your Servora password: \d{6}/,
      );
    });

    it('answers 202 with the same empty body when the provider rejects the message', async () => {
      const account = await createAccount();
      emailProvider.reset();
      emailProvider.failNextSend(new EmailDeliveryError());

      const response = await request(emailApp.getHttpServer())
        .post('/auth/password-reset/request')
        .send({ email: account.email })
        .expect(202);

      // A delivery failure must not become an account oracle (`BR-044`), and the response must
      // not carry the message or the code (`BR-046`).
      expect(Object.keys(response.body)).toHaveLength(0);
      expect(response.text).not.toMatch(/\d{6}/);

      // The attempt was still recorded, so a retry is throttled like any other (`BR-045`).
      const credentials = await storedCredentials(account.id);
      expect(credentials).toHaveLength(1);
    });
  });
});
