import { randomBytes, randomUUID } from 'node:crypto';
import { eq, sql } from 'drizzle-orm';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { DatabaseService } from '../src/database/database.service.js';
import {
  authSessions,
  customers,
  properties,
  jobs,
  organizationMembers,
  organizationRoles,
  organizations,
  permissions,
  organizationMemberPermissions,
  rolePermissions,
  pushAssignmentEvents,
  pushDeliveries,
  pushInstallations,
  users,
  visits,
  visitTechnicians,
} from '../src/database/schema.js';
import {
  claimAssignmentDeliveries,
  sendAssignmentClaim,
  runAssignmentDeliveryBatch,
} from '../src/push/assignment-delivery-worker.js';
import {
  PushDeliveryError,
  type PushProvider,
} from '../src/push/push-provider.js';
import { maintainPushState } from '../src/push/push-maintenance.js';
import { expandAssignmentEvents } from '../src/push/expand-assignment-events.js';
import { PushInstallationsService } from '../src/push/push-installations.service.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

const config = {
  projectId: 'servora-test-123',
  applicationId: 'com.servora.android.debug',
  transportEnabled: true,
};

describe('assignment delivery worker (PostgreSQL)', () => {
  let database: FoundationTestDatabase;
  let registrations: PushInstallationsService;
  beforeAll(async () => {
    vi.stubEnv('PUSH_PROVIDER', 'disabled');
    vi.stubEnv('FIREBASE_PROJECT_ID', config.projectId);
    vi.stubEnv('PUSH_ANDROID_APPLICATION_ID', config.applicationId);
    database = await createFoundationTestDatabase();
    registrations = new PushInstallationsService({
      db: database.db,
    } as DatabaseService);
  });
  afterAll(async () => {
    await database?.dispose();
    vi.unstubAllEnvs();
  });

  async function fixture() {
    const org = await createTestOrganization(database.db);
    database.cleanup.trackOrganization(org.id);
    const user = await createTestUser(database.db);
    const actor = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    database.cleanup.trackUser(actor.id);
    const role = await createTestOrganizationRole(database.db, org.id);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId: org.id, userId: user.id, roleId: role.id })
      .returning();
    const [permission] = await database.db
      .select()
      .from(permissions)
      .where(eq(permissions.code, 'VISIT_VIEW_ASSIGNED'));
    await database.db.insert(rolePermissions).values({
      organizationId: org.id,
      roleId: role.id,
      permissionId: permission.id,
    });
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId: org.id,
        type: 'COMPANY',
        displayName: 'Push fixture',
      })
      .returning();
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId: org.id,
        customerId: customer.id,
        jobNumber: 1,
        title: 'Push fixture',
      })
      .returning();
    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId: org.id,
        addressLine1: '10 Test Street',
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .returning();
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId: org.id,
        jobId: job.id,
        status: 'SCHEDULED',
        propertyId: property.id,
        locationAddressSnapshot: {
          addressLine1: '10 Test Street',
          city: 'Ottawa',
          province: 'ON',
          postalCode: 'K1A 0B1',
          country: 'CA',
        },
        scheduledStart: new Date('2027-01-01T10:00:00Z'),
        scheduledEnd: new Date('2027-01-01T11:00:00Z'),
      })
      .returning();
    await database.db.insert(visitTechnicians).values({
      organizationId: org.id,
      visitId: visit.id,
      technicianMembershipId: member.id,
      roleCode: 'LEAD',
    });
    const [session] = await database.db
      .insert(authSessions)
      .values({
        userId: user.id,
        platform: 'ANDROID',
        deviceId: randomUUID(),
        expiresAt: new Date(Date.now() + 86400000),
      })
      .returning();
    return {
      org,
      user,
      actor,
      role,
      member,
      permission,
      job,
      visit,
      customer,
      session,
    };
  }
  type Fixture = Awaited<ReturnType<typeof fixture>>;
  async function device(f: Fixture) {
    const id = randomUUID();
    const secret = randomBytes(32).toString('base64url');
    const input = {
      expectedRevision: 0,
      applicationId: config.applicationId,
      registration: { kind: 'FCM_TOKEN' as const, value: randomUUID() },
      locale: 'en-CA' as const,
      alertsEnabled: true,
      notificationPermission: 'GRANTED' as const,
      channelEnabled: true,
    };
    const auth = { userId: f.user.id, sessionId: f.session.id };
    const result = await registrations.register(auth, id, secret, input);
    return { id, secret, input, auth, result };
  }
  async function event(f: Fixture, overrides = {}) {
    const [row] = await database.db
      .insert(pushAssignmentEvents)
      .values({
        organizationId: f.org.id,
        jobId: f.job.id,
        visitId: f.visit.id,
        recipientMembershipId: f.member.id,
        actorUserId: f.actor.id,
        assignmentVersion: 1,
        ...overrides,
      })
      .returning();
    return row;
  }
  const rows = (eventId: string) =>
    database.db
      .select()
      .from(pushDeliveries)
      .where(eq(pushDeliveries.eventId, eventId));
  const expand = (f: Fixture) =>
    expandAssignmentEvents(database.db, config, { organizationId: f.org.id });

  const provider = () => ({
    send: vi
      .fn<PushProvider['send']>()
      .mockResolvedValue({ status: 'accepted', messageId: 'fake-only' }),
  });
  async function ready() {
    const f = await fixture();
    const d = await device(f);
    const e = await event(f);
    await expand(f);
    return { f, d, e, scope: { organizationId: f.org.id } };
  }
  async function claimOne(scope: { organizationId: string }) {
    const [claim] = await claimAssignmentDeliveries(database.db, scope);
    return claim;
  }
  const expireLease = (id: string) =>
    database.db
      .update(pushDeliveries)
      .set({ leaseExpiresAt: sql`now() - interval '1 second'` })
      .where(eq(pushDeliveries.id, id));

  it('expands and sends every device with the exact opaque v1 contract and remaining TTL', async () => {
    const f = await fixture();
    const a = await device(f);
    const b = await device(f);
    const e = await event(f);
    const p = provider();
    await runAssignmentDeliveryBatch(database.db, config, p, {
      organizationId: f.org.id,
    });
    expect(p.send).toHaveBeenCalledTimes(2);
    expect(p.send.mock.calls.map(([m]) => m.registrationToken).sort()).toEqual(
      [a.input.registration.value, b.input.registration.value].sort(),
    );
    const message = p.send.mock.calls[0][0];
    expect(message.data).toEqual({
      version: '1',
      eventType: 'VISIT_ASSIGNED',
      eventId: e.id,
      bindingId: expect.any(String),
      organizationId: f.org.id,
      jobId: f.job.id,
      visitId: f.visit.id,
      expiresAt: e.expiresAt.toISOString(),
    });
    expect(message.ttlMs).toBeGreaterThan(23 * 3600_000);
    expect(message.ttlMs).toBeLessThanOrEqual(24 * 3600_000);
    for (const row of await rows(e.id))
      expect(row).toMatchObject({
        state: 'ACCEPTED',
        attempts: 1,
        claimId: null,
        registrationRevision: null,
        terminalAt: expect.any(Date),
      });
    await runAssignmentDeliveryBatch(database.db, config, p, {
      organizationId: f.org.id,
    });
    expect(p.send).toHaveBeenCalledTimes(2);
  });
  it('concurrent workers claim once and do not touch another tenant', async () => {
    const { scope, e } = await ready();
    const other = await ready();
    const p = provider();
    await Promise.all([
      runAssignmentDeliveryBatch(database.db, config, p, scope),
      runAssignmentDeliveryBatch(database.db, config, p, scope),
    ]);
    expect(p.send).toHaveBeenCalledTimes(1);
    expect((await rows(e.id))[0].state).toBe('ACCEPTED');
    expect((await rows(other.e.id))[0].state).toBe('PENDING');
  });
  it('skips locked deliveries without waiting and respects batch bounds', async () => {
    const { scope, e } = await ready();
    await database.db.transaction(async (tx) => {
      await tx
        .select()
        .from(pushDeliveries)
        .where(eq(pushDeliveries.eventId, e.id))
        .for('update');
      expect(await claimAssignmentDeliveries(database.db, scope)).toEqual([]);
    });
    expect(await claimAssignmentDeliveries(database.db, scope, 1)).toHaveLength(
      1,
    );
    for (const size of [0, -1, 101, 1.5])
      await expect(
        claimAssignmentDeliveries(database.db, scope, size),
      ).rejects.toThrow('batch size');
  });
  it('reclaims abandoned leases with new authority and fences stale callbacks', async () => {
    const { scope, e } = await ready();
    const old = await claimOne(scope);
    await expireLease(old.id);
    const next = await claimOne(scope);
    expect(next.claimId).not.toBe(old.claimId);
    expect(next.attempts).toBe(2);
    const p = provider();
    await sendAssignmentClaim(database.db, config, p, old);
    expect(p.send).not.toHaveBeenCalled();
    await sendAssignmentClaim(database.db, config, p, next);
    expect((await rows(e.id))[0]).toMatchObject({
      state: 'ACCEPTED',
      attempts: 2,
    });
  });
  it('does not reclaim an unexpired lease or reset an exhausted budget', async () => {
    const { scope, e } = await ready();
    const claim = await claimOne(scope);
    expect(await claimAssignmentDeliveries(database.db, scope)).toEqual([]);
    await database.db
      .update(pushDeliveries)
      .set({ attempts: 8, leaseExpiresAt: sql`now() - interval '1 second'` })
      .where(eq(pushDeliveries.id, claim.id));
    expect(await claimAssignmentDeliveries(database.db, scope)).toEqual([]);
    expect((await rows(e.id))[0]).toMatchObject({
      state: 'FAILED',
      attempts: 8,
      claimId: null,
    });
  });
  it.each([
    'revocation',
    'expiry',
    'crew removal',
    'role grant removal',
    'member disabled',
    'user disabled',
    'organization disabled',
    'role disabled',
    'completed visit',
    'canceled job',
    'deleted customer',
    'stale registration',
    'blocked channel',
    'denied permission',
    'disabled preference',
    'wrong project',
    'wrong application',
    'missing eligibility',
  ])('rechecks %s after claim and suppresses without I/O', async (change) => {
    const { f, d, e, scope } = await ready();
    const claim = await claimOne(scope);
    const db = database.db;
    switch (change) {
      case 'revocation':
        await db
          .update(authSessions)
          .set({ revokedAt: sql`now()` })
          .where(eq(authSessions.id, f.session.id));
        break;
      case 'expiry':
        await db
          .update(authSessions)
          .set({ expiresAt: sql`now() - interval '1 second'` })
          .where(eq(authSessions.id, f.session.id));
        break;
      case 'crew removal':
        await db
          .delete(visitTechnicians)
          .where(eq(visitTechnicians.visitId, f.visit.id));
        break;
      case 'role grant removal':
        await db
          .delete(rolePermissions)
          .where(eq(rolePermissions.roleId, f.role.id));
        break;
      case 'member disabled':
        await db
          .update(organizationMembers)
          .set({ status: 'INACTIVE' })
          .where(eq(organizationMembers.id, f.member.id));
        break;
      case 'user disabled':
        await db
          .update(users)
          .set({ status: 'INACTIVE' })
          .where(eq(users.id, f.user.id));
        break;
      case 'organization disabled':
        await db
          .update(organizations)
          .set({ status: 'INACTIVE' })
          .where(eq(organizations.id, f.org.id));
        break;
      case 'role disabled':
        await db
          .update(organizationRoles)
          .set({ status: 'INACTIVE' })
          .where(eq(organizationRoles.id, f.role.id));
        break;
      case 'completed visit':
        await db
          .update(visits)
          .set({
            status: 'COMPLETED',
            outcomeCode: 'RESOLVED',
            outcomeSummary: 'Done',
            outcomeRecordedAt: sql`now()`,
            outcomeRecordedByMembershipId: f.member.id,
          })
          .where(eq(visits.id, f.visit.id));
        break;
      case 'canceled job':
        await db
          .update(jobs)
          .set({ status: 'CANCELED' })
          .where(eq(jobs.id, f.job.id));
        break;
      case 'deleted customer':
        await db
          .update(customers)
          .set({ deletedAt: sql`now()`, deletedByMembershipId: f.member.id })
          .where(eq(customers.id, f.customer.id));
        break;
      case 'stale registration':
        await db
          .update(pushInstallations)
          .set({ registeredAt: sql`now() - interval '31 days'` })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'blocked channel':
        await db
          .update(pushInstallations)
          .set({ channelEnabled: false })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'denied permission':
        await db
          .update(pushInstallations)
          .set({ notificationPermission: 'DENIED' })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'disabled preference':
        await db
          .update(pushInstallations)
          .set({ alertsEnabled: false })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'wrong project':
        await db
          .update(pushInstallations)
          .set({ projectId: 'other-project-123' })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'wrong application':
        await db
          .update(pushInstallations)
          .set({ applicationId: 'com.servora.android' })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'missing eligibility':
        await db
          .update(pushInstallations)
          .set({ eligibleSince: null })
          .where(eq(pushInstallations.id, d.id));
        break;
    }
    const p = provider();
    await sendAssignmentClaim(db, config, p, claim);
    expect(p.send).not.toHaveBeenCalled();
    expect((await rows(e.id))[0].state).toBe('SUPPRESSED');
  });
  it('accepts a current direct permission grant', async () => {
    const { f, scope, e } = await ready();
    const claim = await claimOne(scope);
    await database.db
      .delete(rolePermissions)
      .where(eq(rolePermissions.roleId, f.role.id));
    await database.db.insert(organizationMemberPermissions).values({
      organizationId: f.org.id,
      memberId: f.member.id,
      permissionId: f.permission.id,
    });
    const p = provider();
    await sendAssignmentClaim(database.db, config, p, claim);
    expect(p.send).toHaveBeenCalledTimes(1);
    expect((await rows(e.id))[0].state).toBe('ACCEPTED');
  });
  it('expires before send without contacting a provider', async () => {
    const { e, scope } = await ready();
    const claim = await claimOne(scope);
    await database.db
      .update(pushAssignmentEvents)
      .set({
        occurredAt: sql`now() - interval '25 hours'`,
        expiresAt: sql`now() - interval '1 hour'`,
      })
      .where(eq(pushAssignmentEvents.id, e.id));
    const p = provider();
    await sendAssignmentClaim(database.db, config, p, claim);
    expect(p.send).not.toHaveBeenCalled();
    expect((await rows(e.id))[0].state).toBe('EXPIRED');
  });
  it('disabled transport consumes pending intent without creating a future backlog', async () => {
    const { e, scope } = await ready();
    const p = provider();
    await runAssignmentDeliveryBatch(
      database.db,
      { ...config, transportEnabled: false },
      p,
      scope,
    );
    expect(p.send).not.toHaveBeenCalled();
    expect((await rows(e.id))[0]).toMatchObject({
      state: 'SUPPRESSED',
      lastFailure: 'disabled',
    });
    await runAssignmentDeliveryBatch(database.db, config, p, scope);
    expect(p.send).not.toHaveBeenCalled();
  });
  it.each(['configuration', 'rejected', 'invalid-registration'] as const)(
    'terminalizes %s with controlled diagnostics',
    async (kind) => {
      const { d, e, scope } = await ready();
      const p = provider();
      p.send.mockRejectedValue(new PushDeliveryError(kind));
      await sendAssignmentClaim(database.db, config, p, await claimOne(scope));
      expect((await rows(e.id))[0]).toMatchObject({
        state: 'FAILED',
        lastFailure: kind,
        attempts: 1,
      });
      const [installation] = await database.db
        .select()
        .from(pushInstallations)
        .where(eq(pushInstallations.id, d.id));
      expect(installation.state).toBe(
        kind === 'invalid-registration' ? 'INVALID' : 'ACTIVE',
      );
      if (kind === 'invalid-registration')
        expect(installation).toMatchObject({
          targetValue: null,
          targetDigest: null,
          eligibleSince: null,
          revision: 2,
        });
    },
  );
  it('retries transient failures on schedule and ends at attempt eight', async () => {
    const { e, scope } = await ready();
    const p = provider();
    p.send.mockRejectedValue(new PushDeliveryError('retryable'));
    await sendAssignmentClaim(database.db, config, p, await claimOne(scope));
    let [row] = await rows(e.id);
    expect(row).toMatchObject({
      state: 'PENDING',
      attempts: 1,
      lastFailure: 'retryable',
      terminalAt: null,
    });
    expect(row.nextAttemptAt.getTime() - Date.now()).toBeGreaterThan(55_000);
    expect(row.nextAttemptAt.getTime() - Date.now()).toBeLessThanOrEqual(
      72_000,
    );
    expect(await claimAssignmentDeliveries(database.db, scope)).toEqual([]);
    await database.db
      .update(pushDeliveries)
      .set({ attempts: 7, nextAttemptAt: sql`now()` })
      .where(eq(pushDeliveries.id, row.id));
    await sendAssignmentClaim(database.db, config, p, await claimOne(scope));
    [row] = await rows(e.id);
    expect(row).toMatchObject({ state: 'FAILED', attempts: 8, claimId: null });
  });
  it('caps retry scheduling at event expiry and honors available Retry-After', async () => {
    const { e, scope } = await ready();
    await database.db
      .update(pushAssignmentEvents)
      .set({ expiresAt: sql`now() + interval '10 minutes'` })
      .where(eq(pushAssignmentEvents.id, e.id));
    const p = provider();
    p.send.mockRejectedValue(new PushDeliveryError('retryable', 3600_000));
    await sendAssignmentClaim(database.db, config, p, await claimOne(scope));
    const [row] = await rows(e.id);
    const [saved] = await database.db
      .select()
      .from(pushAssignmentEvents)
      .where(eq(pushAssignmentEvents.id, e.id));
    expect(row.nextAttemptAt).toEqual(saved.expiresAt);
  });
  it('does not send a superseded token claim; a fresh claim uses the new revision', async () => {
    const { d, e, scope } = await ready();
    const claim = await claimOne(scope);
    const newToken = randomUUID();
    await registrations.register(d.auth, d.id, d.secret, {
      ...d.input,
      expectedRevision: 1,
      registration: { kind: 'FCM_TOKEN', value: newToken },
    });
    const p = provider();
    await sendAssignmentClaim(database.db, config, p, claim);
    expect(p.send).not.toHaveBeenCalled();
    expect((await rows(e.id))[0].state).toBe('PENDING');
    await sendAssignmentClaim(database.db, config, p, await claimOne(scope));
    expect(p.send.mock.calls[0][0].registrationToken).toBe(newToken);
    expect((await rows(e.id))[0]).toMatchObject({
      state: 'ACCEPTED',
      attempts: 2,
    });
  });
  it.each(['accepted', 'invalid-registration'] as const)(
    'fences a late %s result after token rotation',
    async (result) => {
      const { d, e, scope } = await ready();
      const p = provider();
      p.send.mockImplementation(async () => {
        await registrations.register(d.auth, d.id, d.secret, {
          ...d.input,
          expectedRevision: 1,
          registration: { kind: 'FCM_TOKEN', value: randomUUID() },
        });
        if (result === 'invalid-registration')
          throw new PushDeliveryError(result);
        return { status: 'accepted', messageId: 'fake-only' };
      });
      await sendAssignmentClaim(database.db, config, p, await claimOne(scope));
      const [installation] = await database.db
        .select()
        .from(pushInstallations)
        .where(eq(pushInstallations.id, d.id));
      expect(installation).toMatchObject({ revision: 2, state: 'ACTIVE' });
      expect((await rows(e.id))[0].state).toBe('CLAIMED');
    },
  );
  it('fences late completion after unregister, and performs provider I/O outside a transaction', async () => {
    const { d, e, scope } = await ready();
    const p = provider();
    p.send.mockImplementation(async () => {
      // This locks installation and delivery; it would block if the worker held those locks during I/O.
      await registrations.unregister(d.auth, d.id, d.secret);
      return { status: 'accepted', messageId: 'fake-only' };
    });
    await sendAssignmentClaim(database.db, config, p, await claimOne(scope));
    expect((await rows(e.id))[0].state).toBe('SUPPRESSED');
  });
  it('invalid-target failure cancels other outstanding deliveries and keeps terminal history', async () => {
    const { f, e, scope } = await ready();
    const second = await event(f, { assignmentVersion: 2 });
    await expand(f);
    const p = provider();
    p.send.mockRejectedValue(new PushDeliveryError('invalid-registration'));
    await sendAssignmentClaim(database.db, config, p, await claimOne(scope));
    const states = [...(await rows(e.id)), ...(await rows(second.id))]
      .map((r) => r.state)
      .sort();
    expect(states).toEqual(['FAILED', 'SUPPRESSED']);
  });
  it('cannot inherit a delivery across organization/session rebinding', async () => {
    const { d, e, scope } = await ready();
    const claim = await claimOne(scope);
    const other = await fixture();
    await registrations.register(
      { userId: other.user.id, sessionId: other.session.id },
      d.id,
      d.secret,
      { ...d.input, expectedRevision: 1 },
    );
    const p = provider();
    await sendAssignmentClaim(database.db, config, p, claim);
    expect(p.send).not.toHaveBeenCalled();
    expect((await rows(e.id))[0].state).toBe('SUPPRESSED');
  });

  it('refuses a lease too short for the provider deadline', async () => {
    const { scope, e } = await ready();
    const claim = await claimOne(scope);
    await database.db
      .update(pushDeliveries)
      .set({ leaseExpiresAt: sql`now() + interval '20 seconds'` })
      .where(eq(pushDeliveries.id, claim.id));
    const p = provider();
    await sendAssignmentClaim(database.db, config, p, claim);
    expect(p.send).not.toHaveBeenCalled();
    expect((await rows(e.id))[0].state).toBe('CLAIMED');
  });
  it('fences an old in-flight result after another worker reclaims the lease', async () => {
    const { scope, e, d } = await ready();
    const old = await claimOne(scope);
    let next!: Awaited<ReturnType<typeof claimOne>>;
    const p = provider();
    p.send.mockImplementation(async () => {
      await expireLease(old.id);
      next = await claimOne(scope);
      throw new PushDeliveryError('invalid-registration');
    });
    await sendAssignmentClaim(database.db, config, p, old);
    const [row] = await rows(e.id);
    expect(row).toMatchObject({
      state: 'CLAIMED',
      claimId: next.claimId,
      attempts: 2,
    });
    const [installation] = await database.db
      .select()
      .from(pushInstallations)
      .where(eq(pushInstallations.id, d.id));
    expect(installation.state).toBe('ACTIVE');
    await sendAssignmentClaim(database.db, config, provider(), next);
    expect((await rows(e.id))[0].state).toBe('ACCEPTED');
  });
  it('records disabled provider results as suppressed', async () => {
    const { scope, e } = await ready();
    const p = provider();
    p.send.mockResolvedValue({ status: 'disabled' });
    await sendAssignmentClaim(database.db, config, p, await claimOne(scope));
    expect((await rows(e.id))[0]).toMatchObject({
      state: 'SUPPRESSED',
      lastFailure: 'disabled',
    });
  });
  it('returns the trusted organization in authenticated registration', async () => {
    const f = await fixture();
    const d = await device(f);
    expect(d.result.organizationId).toBe(f.org.id);
    expect(
      (await registrations.get(d.auth, d.id, d.secret)).organizationId,
    ).toBe(f.org.id);
  });

  it('erases stale targets and cancels their claims without touching fresh or other-tenant registrations', async () => {
    const stale = await ready();
    const fresh = await device(stale.f);
    const other = await ready();
    await claimAssignmentDeliveries(database.db, stale.scope);
    await database.db
      .update(pushInstallations)
      .set({ registeredAt: sql`now() - interval '31 days'` })
      .where(eq(pushInstallations.id, stale.d.id));
    await maintainPushState(database.db, stale.f.org.id);
    const [row] = await database.db
      .select()
      .from(pushInstallations)
      .where(eq(pushInstallations.id, stale.d.id));
    expect(row).toMatchObject({
      state: 'INVALID',
      targetValue: null,
      targetDigest: null,
      eligibleSince: null,
      revision: stale.d.result.revision + 1,
    });
    expect((await rows(stale.e.id))[0]).toMatchObject({
      state: 'SUPPRESSED',
      claimId: null,
      leaseExpiresAt: null,
      registrationRevision: null,
    });
    for (const id of [fresh.id, other.d.id]) {
      const [untouched] = await database.db
        .select()
        .from(pushInstallations)
        .where(eq(pushInstallations.id, id));
      expect(untouched.state).toBe('ACTIVE');
      expect(untouched.targetValue).not.toBeNull();
    }
  });

  it('retains recent terminal and all pending rows, then deletes old terminal history and only empty expired events', async () => {
    const old = await ready();
    const recent = await ready();
    const pending = await ready();
    for (const record of [old, recent, pending]) {
      await database.db
        .update(pushAssignmentEvents)
        .set({
          occurredAt: sql`now() - interval '40 days'`,
          expiresAt: sql`now() - interval '39 days'`,
        })
        .where(eq(pushAssignmentEvents.id, record.e.id));
    }
    const oldDelivery = (await rows(old.e.id))[0];
    await database.db
      .update(pushDeliveries)
      .set({
        state: 'ACCEPTED',
        attempts: 1,
        terminalAt: sql`now() - interval '31 days'`,
      })
      .where(eq(pushDeliveries.id, oldDelivery.id));
    const recentDelivery = (await rows(recent.e.id))[0];
    await database.db
      .update(pushDeliveries)
      .set({
        state: 'ACCEPTED',
        attempts: 1,
        terminalAt: sql`now() - interval '29 days'`,
      })
      .where(eq(pushDeliveries.id, recentDelivery.id));
    for (const record of [old, recent, pending])
      await maintainPushState(database.db, record.f.org.id);
    expect(await rows(old.e.id)).toHaveLength(0);
    expect(
      await database.db
        .select()
        .from(pushAssignmentEvents)
        .where(eq(pushAssignmentEvents.id, old.e.id)),
    ).toHaveLength(0);
    expect(await rows(recent.e.id)).toHaveLength(1);
    expect(await rows(pending.e.id)).toHaveLength(1);
    for (const record of [recent, pending])
      expect(
        await database.db
          .select()
          .from(pushAssignmentEvents)
          .where(eq(pushAssignmentEvents.id, record.e.id)),
      ).toHaveLength(1);
  });

  it('bounds stale-target cleanup and finishes remaining registrations in the next batch', async () => {
    const f = await fixture();
    const a = await device(f);
    const b = await device(f);
    for (const id of [a.id, b.id])
      await database.db
        .update(pushInstallations)
        .set({ registeredAt: sql`now() - interval '31 days'` })
        .where(eq(pushInstallations.id, id));
    await maintainPushState(database.db, f.org.id, 1);
    const list = await database.db
      .select()
      .from(pushInstallations)
      .where(eq(pushInstallations.organizationId, f.org.id));
    expect(list.filter((row) => row.state === 'INVALID')).toHaveLength(1);
    await maintainPushState(database.db, f.org.id, 1);
    const final = await database.db
      .select()
      .from(pushInstallations)
      .where(eq(pushInstallations.organizationId, f.org.id));
    expect(final.every((row) => row.targetValue === null)).toBe(true);
  });
});
