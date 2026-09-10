import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import { hashAuthCode } from '../src/auth/auth-code.js';
import {
  authRefreshTokens,
  authSessions,
  phoneOtpChallenges,
} from '../src/database/schema.js';
import { SMS_PROVIDER, SmsDeliveryError } from '../src/sms/sms-provider.js';
import { FakeSmsProvider } from '../src/sms/providers/fake-sms-provider.js';
import {
  createFoundationTestDatabase,
  createTestUser,
  uniquePhone,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

/**
 * HTTP contract of phone/SMS authentication (`BR-019`, `BR-044`, `BR-045`) against a real
 * PostgreSQL database.
 *
 * The SMS port is replaced with the in-memory double, so the suite never sends a real message
 * and so the test can read the code the user would have received — the API never returns it
 * (`BR-046`).
 */
const TEST_RESEND_COOLDOWN = '1s';

/** Just past `TEST_RESEND_COOLDOWN`, so the next request is allowed. */
const AFTER_COOLDOWN_MS = 1100;

const DEVICE = {
  platform: 'ANDROID',
  deviceId: 'sms-e2e-installation',
  deviceName: 'Pixel 8',
  appVersion: '1.0.0',
};

function device(sequence: number) {
  return { ...DEVICE, deviceId: `${DEVICE.deviceId}-${sequence}` };
}

function codeFrom(smsBody: string): string {
  const match = /\d{6}/.exec(smsBody);
  if (match === null) {
    throw new Error('The delivered SMS did not contain a six-digit code.');
  }
  return match[0];
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, milliseconds);
  });
}

describe('phone/SMS authentication (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let sms: FakeSmsProvider;
  let jwtSecret: string;
  let deviceSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    sms = new FakeSmsProvider();
    jwtSecret = process.env.JWT_SECRET ?? '';
    expect(jwtSecret.length).toBeGreaterThan(0);

    // Configured before the module is compiled, because the authentication configuration is
    // read once at bootstrap.
    process.env.SMS_OTP_RESEND_COOLDOWN = TEST_RESEND_COOLDOWN;

    const moduleFixture = await Test.createTestingModule({
      imports: [AppModule],
    })
      .overrideProvider(SMS_PROVIDER)
      .useValue(sms)
      .compile();

    app = moduleFixture.createNestApplication({ logger: false });
    await app.init();
  });

  afterAll(async () => {
    delete process.env.SMS_OTP_RESEND_COOLDOWN;
    await app.close();
    await database.dispose();
  });

  function http() {
    return request(app.getHttpServer());
  }

  async function createAccountWithPhone(
    overrides: { phone?: string; status?: string } = {},
  ): Promise<{ id: string; email: string; phone: string }> {
    const phone = overrides.phone ?? uniquePhone();
    const user = await createTestUser(database.db, {
      phone,
      ...(overrides.status === undefined ? {} : { status: overrides.status }),
    });
    database.cleanup.trackUser(user.id);
    return { id: user.id, email: user.email, phone };
  }

  /** Requests one OTP and returns the code that went out over SMS. */
  async function requestOtp(phone: string): Promise<string> {
    sms.reset();
    await http().post('/auth/sms/request').send({ phone }).expect(202);
    const message = sms.last;
    if (message === null) {
      throw new Error('No SMS was sent.');
    }
    return codeFrom(message.body);
  }

  async function storedChallenges(userId: string) {
    return database.db
      .select()
      .from(phoneOtpChallenges)
      .where(eq(phoneOtpChallenges.userId, userId));
  }

  describe('POST /auth/sms/request', () => {
    it('answers 202, stores only a digest and sends one code', async () => {
      const account = await createAccountWithPhone();

      const response = await http()
        .post('/auth/sms/request')
        .send({ phone: account.phone })
        .expect(202);

      expect(Object.keys(response.body)).toHaveLength(0);
      expect(sms.sent).toHaveLength(1);
      expect(sms.last?.to).toBe(account.phone);

      const code = codeFrom(sms.last?.body ?? '');
      expect(code).toMatch(/^[0-9]{6}$/);

      const challenges = await storedChallenges(account.id);
      expect(challenges).toHaveLength(1);
      const [challenge] = challenges;
      expect(challenge?.attempts).toBe(0);
      expect(challenge?.consumedAt).toBeNull();
      // A six-digit code is stored as a keyed digest, never as the code (`BR-046`).
      expect(challenge?.codeHash).toMatch(/^[0-9a-f]{64}$/);
      expect(challenge?.codeHash).not.toBe(code);

      const lifetimeMs = (challenge?.expiresAt.getTime() ?? 0) - Date.now();
      expect(lifetimeMs).toBeGreaterThan(9 * 60_000);
      expect(lifetimeMs).toBeLessThanOrEqual(10 * 60_000);
    });

    it('answers identically for a number with no account, and sends nothing', async () => {
      const account = await createAccountWithPhone();
      const unknownPhone = uniquePhone();

      sms.reset();
      const known = await http()
        .post('/auth/sms/request')
        .send({ phone: account.phone })
        .expect(202);
      expect(sms.sent).toHaveLength(1);

      sms.reset();
      const unknown = await http()
        .post('/auth/sms/request')
        .send({ phone: unknownPhone })
        .expect(202);

      // Same status and same empty body, but nothing is delivered: the response is not an
      // account oracle (`BR-044`).
      expect(unknown.status).toBe(known.status);
      expect(unknown.body).toEqual(known.body);
      expect(sms.sent).toHaveLength(0);
    });

    it.each(['INACTIVE', 'SUSPENDED'])(
      'sends nothing for a number on a %s account',
      async (status) => {
        const account = await createAccountWithPhone({ status });

        sms.reset();
        const response = await http()
          .post('/auth/sms/request')
          .send({ phone: account.phone })
          .expect(202);

        // The status is not disclosed, and no message is delivered: an ineligible account
        // cannot be told apart from an unknown number (`BR-019`, `BR-044`).
        expect(Object.keys(response.body)).toHaveLength(0);
        expect(sms.sent).toHaveLength(0);
        expect(await storedChallenges(account.id)).toHaveLength(0);
      },
    );

    it('sends nothing when a number is associated with more than one account', async () => {
      const phone = uniquePhone();
      const first = await createAccountWithPhone({ phone });
      const second = await createAccountWithPhone({ phone });

      sms.reset();
      await http().post('/auth/sms/request').send({ phone }).expect(202);

      // Ambiguity fails closed rather than picking an account (`ADR-006` D5).
      expect(sms.sent).toHaveLength(0);
      expect(await storedChallenges(first.id)).toHaveLength(0);
      expect(await storedChallenges(second.id)).toHaveLength(0);
    });

    it('rejects a number that is not in E.164 form', async () => {
      const response = await http()
        .post('/auth/sms/request')
        .send({ phone: '5145550100' })
        .expect(400);

      expect(response.body.code).toBe('VALIDATION_FAILED');
    });

    it('answers 202 even when the provider rejects the message', async () => {
      const account = await createAccountWithPhone();
      sms.reset();
      sms.failNextSend(new SmsDeliveryError());

      // A different answer for a number that has an account would be an account oracle, so a
      // delivery failure is logged and the response stays generic (`BR-044`).
      const response = await http()
        .post('/auth/sms/request')
        .send({ phone: account.phone })
        .expect(202);

      expect(Object.keys(response.body)).toHaveLength(0);
      sms.recover();
    });

    it('enforces the resend cooldown for known and unknown numbers alike', async () => {
      const account = await createAccountWithPhone();
      const unknownPhone = uniquePhone();

      await http()
        .post('/auth/sms/request')
        .send({ phone: account.phone })
        .expect(202);
      await http()
        .post('/auth/sms/request')
        .send({ phone: unknownPhone })
        .expect(202);

      const refusedKnown = await http()
        .post('/auth/sms/request')
        .send({ phone: account.phone })
        .expect(429);
      const refusedUnknown = await http()
        .post('/auth/sms/request')
        .send({ phone: unknownPhone })
        .expect(429);

      expect(refusedKnown.body).toEqual({
        statusCode: 429,
        code: 'TOO_MANY_REQUESTS',
        message: expect.any(String),
      });
      expect(refusedUnknown.body).toEqual(refusedKnown.body);
    });

    it('enforces the rolling window limit', async () => {
      const account = await createAccountWithPhone();

      // Three requests per number per 15 minutes (`BR-045`), spaced past the cooldown so the
      // window is what refuses the fourth.
      for (let attempt = 0; attempt < 3; attempt += 1) {
        if (attempt > 0) {
          await sleep(AFTER_COOLDOWN_MS);
        }
        await http()
          .post('/auth/sms/request')
          .send({ phone: account.phone })
          .expect(202);
      }

      await sleep(AFTER_COOLDOWN_MS);
      const refused = await http()
        .post('/auth/sms/request')
        .send({ phone: account.phone })
        .expect(429);

      expect(refused.body.code).toBe('TOO_MANY_REQUESTS');
    });
  });

  describe('POST /auth/sms/verify', () => {
    it('authenticates the user with the same session and token pair as a password sign-in', async () => {
      const account = await createAccountWithPhone();
      const code = await requestOtp(account.phone);
      deviceSequence += 1;

      const response = await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code, device: device(deviceSequence) })
        .expect(200);

      expect(response.body).toEqual({
        sessionId: expect.any(String),
        accessToken: expect.any(String),
        accessTokenExpiresAt: expect.any(String),
        refreshToken: expect.any(String),
      });
      expect(Date.parse(response.body.accessTokenExpiresAt)).toBeGreaterThan(
        Date.now(),
      );

      const [session] = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.id, response.body.sessionId));

      expect(session?.userId).toBe(account.id);
      expect(session?.platform).toBe('ANDROID');
      expect(session?.deviceId).toBe(`${DEVICE.deviceId}-${deviceSequence}`);
      expect(session?.revokedAt).toBeNull();

      const refreshTokens = await database.db
        .select()
        .from(authRefreshTokens)
        .where(eq(authRefreshTokens.sessionId, response.body.sessionId));
      expect(refreshTokens).toHaveLength(1);
      expect(refreshTokens[0]?.tokenHash).not.toBe(response.body.refreshToken);

      // The code was consumed the moment it verified (`BR-019`).
      const challenges = await storedChallenges(account.id);
      expect(challenges).toHaveLength(1);
      expect(challenges[0]?.consumedAt).not.toBeNull();
    });

    it('never returns the code or any credential material', async () => {
      const account = await createAccountWithPhone();
      const code = await requestOtp(account.phone);

      const response = await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code, device: device(deviceSequence) })
        .expect(200);

      const [challenge] = await storedChallenges(account.id);
      expect(response.text).not.toContain(code);
      expect(response.text).not.toContain(challenge?.codeHash ?? 'unset');
      expect(response.text).not.toContain('argon2');
    });

    it('rejects a wrong code and counts the attempt', async () => {
      const account = await createAccountWithPhone();
      const code = await requestOtp(account.phone);
      const wrongCode = code === '000000' ? '111111' : '000000';

      const response = await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code: wrongCode, device: device(0) })
        .expect(401);

      expect(response.body).toEqual({
        statusCode: 401,
        code: 'OTP_CODE_INVALID',
        message: expect.any(String),
      });

      const [challenge] = await storedChallenges(account.id);
      expect(challenge?.attempts).toBe(1);
      // A failed verification leaves no session behind.
      const sessions = await database.db
        .select()
        .from(authSessions)
        .where(eq(authSessions.userId, account.id));
      expect(sessions).toHaveLength(0);
    });

    it('answers an unknown number exactly like a wrong code', async () => {
      const account = await createAccountWithPhone();
      const code = await requestOtp(account.phone);
      const wrongCode = code === '000000' ? '111111' : '000000';

      const knownWrong = await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code: wrongCode, device: device(0) })
        .expect(401);
      const unknown = await http()
        .post('/auth/sms/verify')
        .send({ phone: uniquePhone(), code, device: device(0) })
        .expect(401);

      expect(unknown.body).toEqual(knownWrong.body);
    });

    it('rejects an expired code', async () => {
      const account = await createAccountWithPhone();
      const code = '135790';

      await database.db.insert(phoneOtpChallenges).values({
        userId: account.id,
        codeHash: hashAuthCode(jwtSecret, 'SMS_OTP', account.id, code),
        expiresAt: new Date(Date.now() - 1000),
      });

      await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code, device: device(0) })
        .expect(401);
    });

    it('stops accepting the correct code once the attempt limit is reached', async () => {
      const account = await createAccountWithPhone();
      const code = await requestOtp(account.phone);
      const wrongCode = code === '000000' ? '111111' : '000000';

      for (let attempt = 0; attempt < 5; attempt += 1) {
        await http()
          .post('/auth/sms/verify')
          .send({ phone: account.phone, code: wrongCode, device: device(0) })
          .expect(401);
      }

      const [challenge] = await storedChallenges(account.id);
      expect(challenge?.attempts).toBe(5);

      await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code, device: device(0) })
        .expect(401);
    });

    it('does not accept a code twice', async () => {
      const account = await createAccountWithPhone();
      const code = await requestOtp(account.phone);
      deviceSequence += 1;

      await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code, device: device(deviceSequence) })
        .expect(200);
      await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code, device: device(deviceSequence) })
        .expect(401);
    });

    it('rejects a code that a newer request superseded', async () => {
      const account = await createAccountWithPhone();
      const firstCode = await requestOtp(account.phone);

      await sleep(AFTER_COOLDOWN_MS);
      const secondCode = await requestOtp(account.phone);

      expect(secondCode).not.toBe(firstCode);

      await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code: firstCode, device: device(0) })
        .expect(401);
      await http()
        .post('/auth/sms/verify')
        .send({ phone: account.phone, code: secondCode, device: device(0) })
        .expect(200);
    });

    it('refuses to authenticate an ambiguous number', async () => {
      const phone = uniquePhone();
      await createAccountWithPhone({ phone });
      await createAccountWithPhone({ phone });

      await http()
        .post('/auth/sms/verify')
        .send({ phone, code: '123456', device: device(0) })
        .expect(401);
    });
  });
});
