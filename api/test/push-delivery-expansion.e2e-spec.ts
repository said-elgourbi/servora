import { randomBytes, randomUUID } from 'node:crypto';
import { and, eq, sql } from 'drizzle-orm';
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
import { expandAssignmentEvents } from '../src/push/expand-assignment-events.js';
import { PushInstallationsService } from '../src/push/push-installations.service.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  expectPostgresError,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

const config = {
  projectId: 'servora-test-123',
  applicationId: 'com.servora.android.debug',
  transportEnabled: true,
};

describe('assignment delivery expansion (PostgreSQL)', () => {
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

  it('snapshots every eligible device once without storing provider targets', async () => {
    const f = await fixture();
    const a = await device(f);
    const b = await device(f);
    const e = await event(f);
    await expand(f);
    const deliveries = await rows(e.id);
    expect(deliveries.map((r) => r.installationId).sort()).toEqual(
      [a.id, b.id].sort(),
    );
    expect(deliveries[0]).toMatchObject({
      organizationId: f.org.id,
      state: 'PENDING',
      attempts: 0,
      claimId: null,
      terminalAt: null,
    });
    expect(JSON.stringify(deliveries)).not.toContain(
      a.input.registration.value,
    );
    await expand(f);
    expect(await rows(e.id)).toEqual(deliveries);
  });
  it('concurrent expanders create a single snapshot', async () => {
    const f = await fixture();
    await device(f);
    const e = await event(f);
    await Promise.all([expand(f), expand(f), expand(f)]);
    expect(await rows(e.id)).toHaveLength(1);
  });
  it('skips a locked event and expands it after the lock is released', async () => {
    const f = await fixture();
    await device(f);
    const e = await event(f);
    await database.db.transaction(async (tx) => {
      await tx
        .select()
        .from(pushAssignmentEvents)
        .where(eq(pushAssignmentEvents.id, e.id))
        .for('update');
      await expand(f);
      expect(await rows(e.id)).toHaveLength(0);
    });
    await expand(f);
    expect(await rows(e.id)).toHaveLength(1);
  });
  it('rolls back deliveries and expanded marker together', async () => {
    const f = await fixture();
    await device(f);
    const e = await event(f);
    await expect(
      database.db.transaction(async (tx) => {
        await expandAssignmentEvents(
          tx as unknown as DatabaseService['db'],
          config,
          { organizationId: f.org.id },
        );
        throw new Error('rollback');
      }),
    ).rejects.toThrow('rollback');
    expect(await rows(e.id)).toHaveLength(0);
    const [saved] = await database.db
      .select()
      .from(pushAssignmentEvents)
      .where(eq(pushAssignmentEvents.id, e.id));
    expect(saved.expandedAt).toBeNull();
    await expand(f);
    expect(await rows(e.id)).toHaveLength(1);
  });
  it('consumes zero-device events and excludes later registration', async () => {
    const f = await fixture();
    const e = await event(f);
    await expand(f);
    await device(f);
    await expand(f);
    expect(await rows(e.id)).toHaveLength(0);
  });
  it('excludes devices registered after occurrence even before expansion', async () => {
    const f = await fixture();
    const e = await event(f);
    await device(f);
    await expand(f);
    expect(await rows(e.id)).toHaveLength(0);
  });
  it('suppresses disabled transport permanently instead of accumulating a backlog', async () => {
    const f = await fixture();
    await device(f);
    const e = await event(f);
    await expandAssignmentEvents(
      database.db,
      {
        ...config,
        transportEnabled: false,
      },
      { organizationId: f.org.id },
    );
    const before = await rows(e.id);
    expect(before[0]).toMatchObject({
      state: 'SUPPRESSED',
      attempts: 0,
      lastFailure: 'disabled',
      terminalAt: expect.any(Date),
    });
    await expand(f);
    expect(await rows(e.id)).toEqual(before);
  });
  const exclusions = [
    'expired event',
    'stale registration',
    'revoked session',
    'expired session',
    'inactive organization',
    'inactive user',
    'inactive member',
    'inactive role',
    'lost read permission',
    'removed crew',
    'completed visit',
    'canceled job',
    'deleted customer',
    'actor recipient',
    'wrong project',
    'wrong application',
    'disabled preference',
    'denied permission',
    'blocked channel',
    'invalid registration',
    'missing eligibility',
  ];
  it.each(exclusions)('does not expand %s', async (reason) => {
    const f = await fixture();
    const d = await device(f);
    const e = await event(f);
    const db = database.db;
    switch (reason) {
      case 'expired event':
        await db
          .update(pushAssignmentEvents)
          .set({
            occurredAt: sql`now() - interval '2 days'`,
            expiresAt: sql`now() - interval '1 day'`,
          })
          .where(eq(pushAssignmentEvents.id, e.id));
        break;
      case 'stale registration':
        await db
          .update(pushInstallations)
          .set({ registeredAt: sql`now() - interval '30 days'` })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'revoked session':
        await db
          .update(authSessions)
          .set({ revokedAt: sql`now()` })
          .where(eq(authSessions.id, f.session.id));
        break;
      case 'expired session':
        await db
          .update(authSessions)
          .set({ expiresAt: sql`now()` })
          .where(eq(authSessions.id, f.session.id));
        break;
      case 'inactive organization':
        await db
          .update(organizations)
          .set({ status: 'INACTIVE' })
          .where(eq(organizations.id, f.org.id));
        break;
      case 'inactive user':
        await db
          .update(users)
          .set({ status: 'INACTIVE' })
          .where(eq(users.id, f.user.id));
        break;
      case 'inactive member':
        await db
          .update(organizationMembers)
          .set({ status: 'INACTIVE' })
          .where(eq(organizationMembers.id, f.member.id));
        break;
      case 'inactive role':
        await db
          .update(organizationRoles)
          .set({ status: 'INACTIVE' })
          .where(eq(organizationRoles.id, f.role.id));
        break;
      case 'lost read permission':
        await db
          .delete(rolePermissions)
          .where(eq(rolePermissions.roleId, f.role.id));
        break;
      case 'removed crew':
        await db
          .delete(visitTechnicians)
          .where(eq(visitTechnicians.visitId, f.visit.id));
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
      case 'actor recipient':
        await db
          .update(pushAssignmentEvents)
          .set({ actorUserId: f.user.id })
          .where(eq(pushAssignmentEvents.id, e.id));
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
      case 'disabled preference':
        await db
          .update(pushInstallations)
          .set({ alertsEnabled: false })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'denied permission':
        await db
          .update(pushInstallations)
          .set({ notificationPermission: 'DENIED' })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'blocked channel':
        await db
          .update(pushInstallations)
          .set({ channelEnabled: false })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'invalid registration':
        await db
          .update(pushInstallations)
          .set({ state: 'INVALID', targetValue: null, targetDigest: null })
          .where(eq(pushInstallations.id, d.id));
        break;
      case 'missing eligibility':
        await db
          .update(pushInstallations)
          .set({ eligibleSince: null })
          .where(eq(pushInstallations.id, d.id));
        break;
    }
    await expand(f);
    expect(await rows(e.id)).toHaveLength(0);
    const [saved] = await db
      .select()
      .from(pushAssignmentEvents)
      .where(eq(pushAssignmentEvents.id, e.id));
    expect(saved.expandedAt).toBeInstanceOf(Date);
  });
  it('honors direct member read grants without relying on role names', async () => {
    const f = await fixture();
    await database.db
      .delete(rolePermissions)
      .where(eq(rolePermissions.roleId, f.role.id));
    await database.db.insert(organizationMemberPermissions).values({
      organizationId: f.org.id,
      memberId: f.member.id,
      permissionId: f.permission.id,
    });
    await device(f);
    const e = await event(f);
    await expand(f);
    expect(await rows(e.id)).toHaveLength(1);
  });
  it('preserves eligibility across token refresh, but re-enable applies only to future events', async () => {
    const f = await fixture();
    const d = await device(f);
    const e = await event(f);
    await registrations.register(d.auth, d.id, d.secret, {
      ...d.input,
      expectedRevision: 1,
      registration: { kind: 'FCM_TOKEN', value: randomUUID() },
    });
    await expand(f);
    expect(await rows(e.id)).toHaveLength(1);
    await registrations.register(d.auth, d.id, d.secret, {
      ...d.input,
      expectedRevision: 2,
      alertsEnabled: false,
    });
    expect((await rows(e.id))[0].state).toBe('SUPPRESSED');
    const old = await event(f, { assignmentVersion: 2 });
    await registrations.register(d.auth, d.id, d.secret, {
      ...d.input,
      expectedRevision: 3,
    });
    const future = await event(f, { assignmentVersion: 3 });
    await expand(f);
    expect(await rows(old.id)).toHaveLength(0);
    expect(await rows(future.id)).toHaveLength(1);
  });
  it('unregister cancels pending delivery and cannot revive it on later registration', async () => {
    const f = await fixture();
    const d = await device(f);
    const e = await event(f);
    await expand(f);
    await registrations.unregister(d.auth, d.id, d.secret);
    expect((await rows(e.id))[0].state).toBe('SUPPRESSED');
    await registrations.register(d.auth, d.id, d.secret, {
      ...d.input,
      expectedRevision: 2,
    });
    await expand(f);
    expect((await rows(e.id))[0].state).toBe('SUPPRESSED');
  });
  it('snapshots tenant and binding across cross-organization installation rebinding', async () => {
    const f = await fixture();
    const d = await device(f);
    const e = await event(f);
    await expand(f);
    const other = await fixture();
    const result = await registrations.register(
      { userId: other.user.id, sessionId: other.session.id },
      d.id,
      d.secret,
      { ...d.input, expectedRevision: 1 },
    );
    expect(result.bindingId).not.toBe(d.result.bindingId);
    expect((await rows(e.id))[0]).toMatchObject({
      organizationId: f.org.id,
      bindingId: d.result.bindingId,
      state: 'SUPPRESSED',
    });
    const next = await event(other);
    await expand(other);
    expect((await rows(next.id))[0]).toMatchObject({
      organizationId: other.org.id,
      bindingId: result.bindingId,
    });
  });
  it('enforces event tenant, uniqueness, budget, claim, terminal and failure constraints in SQL', async () => {
    const f = await fixture();
    const d = await device(f);
    const e = await event(f);
    const other = await fixture();
    const base = {
      organizationId: f.org.id,
      eventId: e.id,
      installationId: d.id,
      bindingId: d.result.bindingId,
    };
    await expectPostgresError(
      () =>
        database.db
          .insert(pushDeliveries)
          .values({ ...base, organizationId: other.org.id }),
      '23503',
    );
    for (const change of [
      { state: 'UNKNOWN' },
      { attempts: -1 },
      { attempts: 9 },
      { attempts: 8 },
      { state: 'CLAIMED', attempts: 1 },
      { claimId: randomUUID() },
      { state: 'ACCEPTED', attempts: 1 },
      { terminalAt: new Date() },
      { lastFailure: 'raw provider error' },
      {
        state: 'CLAIMED',
        attempts: 1,
        claimId: randomUUID(),
        leaseExpiresAt: new Date(),
      },
    ])
      await expectPostgresError(
        () => database.db.insert(pushDeliveries).values({ ...base, ...change }),
        '23514',
      );
    await database.db.insert(pushDeliveries).values(base);
    await expectPostgresError(
      () => database.db.insert(pushDeliveries).values(base),
      '23505',
    );
  });
  it('leaves other organizations events and devices untouched', async () => {
    const f = await fixture();
    const other = await fixture();
    await device(f);
    await device(other);
    const e = await event(f);
    const otherEvent = await event(other);
    await expand(f);
    expect(await rows(e.id)).toHaveLength(1);
    expect(await rows(otherEvent.id)).toHaveLength(0);
    const [saved] = await database.db
      .select()
      .from(pushAssignmentEvents)
      .where(eq(pushAssignmentEvents.id, otherEvent.id));
    expect(saved.expandedAt).toBeNull();
  });
  it('a stale registration refresh starts eligibility again without replaying older events', async () => {
    const f = await fixture();
    const d = await device(f);
    await database.db
      .update(pushInstallations)
      .set({ registeredAt: sql`now() - interval '31 days'` })
      .where(eq(pushInstallations.id, d.id));
    const old = await event(f);
    await registrations.register(d.auth, d.id, d.secret, {
      ...d.input,
      expectedRevision: 1,
    });
    const future = await event(f, { assignmentVersion: 2 });
    await expand(f);
    expect(await rows(old.id)).toHaveLength(0);
    expect(await rows(future.id)).toHaveLength(1);
  });
  it('disable fences claimed deliveries while preserving accepted history', async () => {
    const f = await fixture();
    const d = await device(f);
    const e = await event(f);
    const accepted = await event(f, { assignmentVersion: 2 });
    await expand(f);
    await database.db
      .update(pushDeliveries)
      .set({
        state: 'CLAIMED',
        attempts: 1,
        claimId: randomUUID(),
        leaseExpiresAt: sql`now() + interval '120 seconds'`,
        registrationRevision: 1,
      })
      .where(eq(pushDeliveries.eventId, e.id));
    await database.db
      .update(pushDeliveries)
      .set({ state: 'ACCEPTED', attempts: 1, terminalAt: sql`now()` })
      .where(eq(pushDeliveries.eventId, accepted.id));
    await registrations.register(d.auth, d.id, d.secret, {
      ...d.input,
      expectedRevision: 1,
      alertsEnabled: false,
    });
    expect((await rows(e.id))[0]).toMatchObject({
      state: 'SUPPRESSED',
      attempts: 1,
      claimId: null,
      registrationRevision: null,
      leaseExpiresAt: null,
      terminalAt: expect.any(Date),
    });
    expect((await rows(accepted.id))[0].state).toBe('ACCEPTED');
  });
  it('bounds batch size and rejects invalid inputs', async () => {
    for (const size of [0, -1, 101, 1.5])
      await expect(
        expandAssignmentEvents(
          database.db,
          config,
          { organizationId: randomUUID() },
          size,
        ),
      ).rejects.toThrow('batch size');
    const f = await fixture();
    const e = await event(f);
    await event(f, { assignmentVersion: 2 });
    expect(
      await expandAssignmentEvents(
        database.db,
        config,
        { organizationId: f.org.id },
        1,
      ),
    ).toBe(1);
    const expanded = await database.db
      .select()
      .from(pushAssignmentEvents)
      .where(
        and(
          eq(pushAssignmentEvents.organizationId, f.org.id),
          sql`expanded_at is not null`,
        ),
      );
    expect(expanded).toHaveLength(1);
    await expand(f);
    expect(await rows(e.id)).toHaveLength(0);
  });
});
