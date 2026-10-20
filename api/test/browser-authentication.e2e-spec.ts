import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import { BROWSER_AUTH_CONFIG } from '../src/auth/browser-auth.config.js';
import { authSessions } from '../src/database/schema.js';
import { hashPassword } from '../src/users/password-hasher.js';
import {
  createFoundationTestDatabase,
  createTestUser,
  uniqueEmail,
  type FoundationTestDatabase,
} from './support/foundation-database.js';
const password = 'servora-browser-e2e-password';
describe('browser sessions against PostgreSQL', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let email: string;
  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const module = await Test.createTestingModule({ imports: [AppModule] })
      .overrideProvider(BROWSER_AUTH_CONFIG)
      .useValue({ origin: 'https://office.example', secure: true })
      .compile();
    app = module.createNestApplication({ logger: false });
    await app.init();
    email = uniqueEmail();
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(password),
    });
    database.cleanup.trackUser(user.id);
  });
  afterAll(async () => {
    await app?.close();
    await database?.dispose();
  });
  const cookieFrom = (res: { headers: Record<string, unknown> }): string =>
    (res.headers['set-cookie'] as string[])[0]!.split(';')[0]!;
  it('signs in, reads permissions, rotates, rejects replay, and revokes without an access token', async () => {
    const signedIn = await request(app.getHttpServer())
      .post('/auth/web/sign-in')
      .set('Origin', 'https://office.example')
      .set('X-Servora-Web', '1')
      .send({
        email,
        password,
        device: { platform: 'WEB', deviceId: 'browser-e2e' },
      })
      .expect(200);
    expect(signedIn.body).not.toHaveProperty('refreshToken');
    const initialCookie = cookieFrom(signedIn);
    const me = await request(app.getHttpServer())
      .get('/auth/me')
      .auth(signedIn.body.accessToken, { type: 'bearer' })
      .expect(200);
    expect(me.body.userId).toEqual(expect.any(String));
    expect(Array.isArray(me.body.permissions)).toBe(true);
    const refreshed = await request(app.getHttpServer())
      .post('/auth/web/refresh')
      .set('Origin', 'https://office.example')
      .set('X-Servora-Web', '1')
      .set('Cookie', initialCookie)
      .send({})
      .expect(200);
    const currentCookie = cookieFrom(refreshed);
    expect(currentCookie).not.toBe(initialCookie);
    await request(app.getHttpServer())
      .post('/auth/web/refresh')
      .set('Origin', 'https://office.example')
      .set('X-Servora-Web', '1')
      .set('Cookie', initialCookie)
      .send({})
      .expect(401);
    await request(app.getHttpServer())
      .post('/auth/web/sign-out')
      .set('Origin', 'https://office.example')
      .set('X-Servora-Web', '1')
      .set('Cookie', currentCookie)
      .send({})
      .expect(204);
    await request(app.getHttpServer())
      .get('/auth/me')
      .auth(refreshed.body.accessToken, { type: 'bearer' })
      .expect(401);
    await request(app.getHttpServer())
      .post('/auth/web/refresh')
      .set('Origin', 'https://office.example')
      .set('X-Servora-Web', '1')
      .set('Cookie', currentCookie)
      .send({})
      .expect(401);
    const [session] = await database.db
      .select()
      .from(authSessions)
      .where(eq(authSessions.id, signedIn.body.sessionId));
    expect(session?.platform).toBe('WEB');
    expect(session?.revokedAt).not.toBeNull();
  });
});
