import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { randomBytes, randomUUID } from 'node:crypto';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { AppModule } from '../src/app.module.js';
import { issueAccessToken } from '../src/auth/access-token.js';
import {
  authSessions,
  organizationMembers,
  organizations,
  pushInstallations,
} from '../src/database/schema.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  expectPostgresError,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

describe('push installation endpoints (PostgreSQL)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let first: Awaited<ReturnType<typeof actor>>;
  let other: Awaited<ReturnType<typeof actor>>;
  async function actor(platform = 'ANDROID') {
    const org = await createTestOrganization(database.db);
    database.cleanup.trackOrganization(org.id);
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, org.id);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId: org.id, userId: user.id, roleId: role.id })
      .returning();
    const [session] = await database.db
      .insert(authSessions)
      .values({
        userId: user.id,
        platform,
        deviceId: randomUUID(),
        expiresAt: new Date(Date.now() + 86400000),
      })
      .returning();
    const token = await issueAccessToken({
      userId: user.id,
      sessionId: session.id,
      secret: process.env.JWT_SECRET!,
      lifetimeMs: 900000,
    });
    return { org, user, member, session, token: token.accessToken };
  }
  const body = (overrides = {}) => ({
    expectedRevision: 0,
    applicationId: 'com.servora.android.debug',
    registration: { kind: 'FCM_TOKEN', value: randomUUID() },
    locale: 'en-CA',
    alertsEnabled: true,
    notificationPermission: 'GRANTED',
    channelEnabled: true,
    ...overrides,
  });
  const identity = () => ({
    id: randomUUID(),
    secret: randomBytes(32).toString('base64url'),
  });
  const endpoint = (id: string) => `/push/installations/${id}`;
  function put(
    who: typeof first,
    install: ReturnType<typeof identity>,
    input = body(),
  ) {
    return request(app.getHttpServer())
      .put(endpoint(install.id))
      .auth(who.token, { type: 'bearer' })
      .set('X-Push-Installation-Secret', install.secret)
      .send(input);
  }
  function get(who: typeof first, install: ReturnType<typeof identity>) {
    return request(app.getHttpServer())
      .get(endpoint(install.id))
      .auth(who.token, { type: 'bearer' })
      .set('X-Push-Installation-Secret', install.secret);
  }
  function remove(who: typeof first, install: ReturnType<typeof identity>) {
    return request(app.getHttpServer())
      .delete(endpoint(install.id))
      .auth(who.token, { type: 'bearer' })
      .set('X-Push-Installation-Secret', install.secret);
  }
  async function stored(id: string) {
    return (
      await database.db
        .select()
        .from(pushInstallations)
        .where(eq(pushInstallations.id, id))
    )[0];
  }

  beforeAll(async () => {
    vi.stubEnv('PUSH_PROVIDER', 'disabled');
    vi.stubEnv('FIREBASE_PROJECT_ID', 'servora-test-123');
    vi.stubEnv('PUSH_ANDROID_APPLICATION_ID', 'com.servora.android.debug');
    database = await createFoundationTestDatabase();
    const module = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = module.createNestApplication({ logger: false });
    await app.init();
    first = await actor();
    other = await actor();
  });
  afterAll(async () => {
    if (app) await app.close();
    if (database) await database.dispose();
    vi.unstubAllEnvs();
  });

  it('persists authenticated ownership and never returns provider targets or proof', async () => {
    const install = identity();
    const input = body();
    const response = await put(first, install, input).expect(200);
    expect(response.body).toMatchObject({
      installationId: install.id,
      revision: 1,
      alertsEnabled: true,
      deliveryEnabled: false,
    });
    const row = await stored(install.id);
    expect(row).toMatchObject({
      userId: first.user.id,
      sessionId: first.session.id,
      organizationId: first.org.id,
      membershipId: first.member.id,
      state: 'ACTIVE',
      targetValue: input.registration.value,
    });
    expect(row.secretDigest).not.toBe(install.secret);
    const publicData = JSON.stringify(response.body);
    expect(publicData).not.toContain(install.secret);
    expect(publicData).not.toContain(input.registration.value);
    expect(publicData).not.toContain(row.secretDigest);
    await get(first, install).expect(200);
  });
  it('serializes simultaneous creates and revision updates', async () => {
    const install = identity();
    const input = body();
    const creates = await Promise.all([
      put(first, install, input),
      put(first, install, input),
    ]);
    expect(creates.map((r) => r.status).sort()).toEqual([200, 409]);
    const updates = await Promise.all([
      put(first, install, { ...input, expectedRevision: 1 }),
      put(first, install, { ...input, expectedRevision: 1 }),
    ]);
    expect(updates.map((r) => r.status).sort()).toEqual([200, 409]);
    expect((await stored(install.id)).revision).toBe(2);
  });
  it('updates targets and distinct preference/OS state without changing the binding', async () => {
    const install = identity();
    const initial = await put(first, install).expect(200);
    const next = body({
      expectedRevision: 1,
      notificationPermission: 'DENIED',
    });
    const response = await put(first, install, next).expect(200);
    expect(response.body.bindingId).toBe(initial.body.bindingId);
    expect(response.body.alertsEnabled).toBe(true);
    expect(await stored(install.id)).toMatchObject({
      state: 'DISABLED',
      notificationPermission: 'DENIED',
      targetValue: next.registration.value,
    });
    await put(
      first,
      install,
      body({ expectedRevision: 2, alertsEnabled: false }),
    ).expect(200);
    expect((await stored(install.id)).state).toBe('DISABLED');
  });
  it('hides installations from other tenants and rejects wrong proof', async () => {
    const install = identity();
    await put(first, install).expect(200);
    await get(other, install).expect(404);
    await remove(other, install).expect(404);
    const wrong = { ...install, secret: identity().secret };
    await get(first, wrong).expect(404);
    await put(other, wrong, body({ expectedRevision: 1 })).expect(404);
    expect((await stored(install.id)).userId).toBe(first.user.id);
  });
  it('rebinds to a newer login and fences delayed old-account requests', async () => {
    const install = identity();
    const original = await put(first, install).expect(200);
    const newer = await actor();
    const rebound = await put(
      newer,
      install,
      body({ expectedRevision: 1 }),
    ).expect(200);
    expect(rebound.body.bindingId).not.toBe(original.body.bindingId);
    expect(await stored(install.id)).toMatchObject({
      organizationId: newer.org.id,
      sessionId: newer.session.id,
    });
    await get(first, install).expect(404);
    await remove(first, install).expect(404);
    await put(first, install, body({ expectedRevision: 1 })).expect(409);
    await put(first, install, body({ expectedRevision: 2 })).expect(409);
  });
  it('rejects duplicate provider targets without transferring ownership', async () => {
    const install = identity();
    const input = body();
    await put(first, install, input).expect(200);
    const duplicate = identity();
    const response = await put(other, duplicate, input).expect(409);
    expect(JSON.stringify(response.body)).not.toContain(
      input.registration.value,
    );
    expect(await stored(duplicate.id)).toBeUndefined();
  });
  it('unregisters idempotently, clears targets and supports later reconciliation', async () => {
    const install = identity();
    await put(first, install).expect(200);
    await remove(first, install).expect(204);
    await remove(first, install).expect(204);
    expect(await stored(install.id)).toMatchObject({
      state: 'UNREGISTERED',
      targetValue: null,
      targetDigest: null,
      revision: 2,
    });
    const response = await get(first, install).expect(200);
    expect(response.body.revision).toBe(2);
    await put(first, install, body({ expectedRevision: 2 })).expect(200);
  });
  it('revokes registration atomically on sign-out and refuses the revoked access token', async () => {
    const who = await actor();
    const install = identity();
    await put(who, install).expect(200);
    await request(app.getHttpServer())
      .post('/auth/sign-out')
      .auth(who.token, { type: 'bearer' })
      .expect(204);
    expect(await stored(install.id)).toMatchObject({
      state: 'UNREGISTERED',
      targetValue: null,
      alertsEnabled: false,
    });
    await get(who, install).expect(401);
    await put(who, install, body({ expectedRevision: 2 })).expect(401);
  });
  it('rejects unauthenticated, web, inactive membership and wrong-variant registrations', async () => {
    const install = identity();
    await request(app.getHttpServer())
      .put(endpoint(install.id))
      .send(body())
      .expect(401);
    const web = await actor('WEB');
    await put(web, install).expect(403);
    const inactive = await actor();
    await database.db
      .update(organizationMembers)
      .set({ status: 'INACTIVE' })
      .where(eq(organizationMembers.id, inactive.member.id));
    await put(inactive, install).expect(403);
    const inactiveOrg = await actor();
    await database.db
      .update(organizations)
      .set({ status: 'INACTIVE' })
      .where(eq(organizations.id, inactiveOrg.org.id));
    await put(inactiveOrg, install).expect(403);
    await put(
      first,
      install,
      body({ applicationId: 'com.servora.android' }),
    ).expect(400);
    await put(first, install, body({ userId: other.user.id })).expect(400);
  });
  it('rejects expired sessions even when the access JWT is still valid', async () => {
    const who = await actor();
    const install = identity();
    await put(who, install).expect(200);
    await database.db
      .update(authSessions)
      .set({ expiresAt: new Date(Date.now() - 1000) })
      .where(eq(authSessions.id, who.session.id));
    await get(who, install).expect(401);
    await put(who, install, body({ expectedRevision: 1 })).expect(401);
    expect((await stored(install.id)).revision).toBe(1);
  });
  it('does not unregister the new owner when an old account signs out', async () => {
    const old = await actor();
    const install = identity();
    await put(old, install).expect(200);
    const newer = await actor();
    await put(newer, install, body({ expectedRevision: 1 })).expect(200);
    await request(app.getHttpServer())
      .post('/auth/sign-out')
      .auth(old.token, { type: 'bearer' })
      .expect(204);
    expect(await stored(install.id)).toMatchObject({
      state: 'ACTIVE',
      sessionId: newer.session.id,
      revision: 2,
    });
  });
  it('enforces membership/session ownership and target-state constraints in PostgreSQL', async () => {
    const install = identity();
    await put(first, install).expect(200);
    await expectPostgresError(
      () =>
        database.db
          .update(pushInstallations)
          .set({ sessionId: other.session.id })
          .where(eq(pushInstallations.id, install.id)),
      '23503',
    );
    await expectPostgresError(
      () =>
        database.db
          .update(pushInstallations)
          .set({ membershipId: other.member.id })
          .where(eq(pushInstallations.id, install.id)),
      '23503',
    );
    await expectPostgresError(
      () =>
        database.db
          .update(pushInstallations)
          .set({ revision: 0 })
          .where(eq(pushInstallations.id, install.id)),
      '23514',
    );
    await expectPostgresError(
      () =>
        database.db
          .update(pushInstallations)
          .set({ state: 'UNREGISTERED' })
          .where(eq(pushInstallations.id, install.id)),
      '23514',
    );
  });
});
