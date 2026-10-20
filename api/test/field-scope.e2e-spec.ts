import { DatabaseService } from '../src/database/database.service.js';
import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { randomUUID } from 'node:crypto';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { beforeAll, afterAll, describe, it, expect, vi } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  customers,
  jobs,
  visits,
  visitTechnicians,
  organizationMembers,
  organizationMemberPermissions,
  permissions,
} from '../src/database/schema.js';
import { hashPassword } from '../src/users/password-hasher.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  uniqueEmail,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

describe('verified browser field scope (PostgreSQL)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let number = 0;
  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const module = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = module.createNestApplication({ logger: false });
    await app.init();
  });
  afterAll(async () => {
    await app?.close();
    await database?.dispose();
  });
  async function setup() {
    const org = await createTestOrganization(database.db);
    database.cleanup.trackOrganization(org.id);
    const role = await createTestOrganizationRole(database.db, org.id);
    const email = uniqueEmail('field-scope');
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword('field-scope-test-password'),
    });
    database.cleanup.trackUser(user.id);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId: org.id, userId: user.id, roleId: role.id })
      .returning();
    for (const code of [
      'VISIT_VIEW_ASSIGNED',
      'VISIT_ADD_NOTE',
      'VISIT_UPDATE_ASSIGNED_STATUS',
      'customers.view',
      'JOB_UPDATE',
    ]) {
      const [permission] = await database.db
        .select()
        .from(permissions)
        .where(eq(permissions.code, code));
      if (!permission) throw new Error(`Missing seeded permission ${code}`);
      await database.db.insert(organizationMemberPermissions).values({
        organizationId: org.id,
        memberId: member!.id,
        permissionId: permission.id,
      });
    }
    const signed = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({
        email,
        password: 'field-scope-test-password',
        device: { platform: 'WEB', deviceId: randomUUID() },
      })
      .expect(200);
    const headers = {
      'X-Servora-Field-User': user.id,
      'X-Servora-Field-Organization': org.id,
      'X-Servora-Field-Membership': member!.id,
    };
    const get = (path: string) =>
      request(app.getHttpServer())
        .get(path)
        .auth(signed.body.accessToken, { type: 'bearer' });
    return {
      org,
      role,
      user,
      member: member!,
      headers,
      get,
      token: signed.body.accessToken as string,
    };
  }
  async function job(organizationId: string, membershipId?: string) {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId,
        type: 'COMPANY',
        displayName: 'Field customer',
      })
      .returning();
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        customerId: customer!.id,
        jobNumber: ++number,
        title: 'Field job',
        status: 'ACTIVE',
      })
      .returning();
    const [visit] = await database.db
      .insert(visits)
      .values({ organizationId, jobId: job!.id, status: 'IN_PROGRESS' })
      .returning();
    if (membershipId)
      await database.db.insert(visitTechnicians).values({
        organizationId,
        visitId: visit!.id,
        technicianMembershipId: membershipId,
        roleCode: 'LEAD',
      });
    return { job: job!, visit: visit! };
  }
  it('verifies a partition, rejects malformed/forged scope, and keeps mixed users assignment-scoped', async () => {
    const a = await setup();
    await a
      .get('/auth/field-context')
      .expect(200)
      .expect(({ body, headers }) => {
        expect(body).toEqual({
          userId: a.user.id,
          organizationId: a.org.id,
          membershipId: a.member.id,
        });
        expect(headers['cache-control']).toBe('no-store');
      });
    const assigned = await job(a.org.id, a.member.id);
    const unassigned = await job(a.org.id);
    await a.get(`/jobs/${assigned.job.id}`).set(a.headers).expect(200);
    await a.get(`/jobs/${unassigned.job.id}`).set(a.headers).expect(404);
    await a.get(`/jobs/${unassigned.job.id}`).expect(200); // Original office/Android contract.
    await a
      .get(`/jobs/${assigned.job.id}/activity?includeRemovedEvidence=true`)
      .set(a.headers)
      .expect(403);
    await a
      .get('/home/technician')
      .set('X-Servora-Field-User', a.user.id)
      .expect(400);
    await a
      .get('/home/technician')
      .set({ ...a.headers, 'X-Servora-Field-Membership': randomUUID() })
      .expect(403);
    await a.get('/home/manager').set(a.headers).expect(400);
    const b = await setup();
    const foreign = await job(b.org.id, b.member.id);
    await a.get(`/jobs/${foreign.job.id}`).set(a.headers).expect(404);
    await a
      .get('/home/technician')
      .set({ ...a.headers, 'X-Servora-Field-User': b.user.id })
      .expect(403);
  });
  it('rechecks revocation, additional memberships and capabilities on subsequent reads/writes', async () => {
    const a = await setup();
    const assigned = await job(a.org.id, a.member.id);
    await a.get('/auth/field-context').expect(200);
    await database.db
      .update(organizationMembers)
      .set({ status: 'INACTIVE' })
      .where(eq(organizationMembers.id, a.member.id));
    await a.get(`/jobs/${assigned.job.id}`).set(a.headers).expect(403);
    await request(app.getHttpServer())
      .post(`/jobs/${assigned.job.id}/visits/${assigned.visit.id}/notes`)
      .auth(a.token, { type: 'bearer' })
      .set(a.headers)
      .send({ body: 'revoked', clientOperationId: randomUUID() })
      .expect(403);
    await database.db
      .update(organizationMembers)
      .set({ status: 'ACTIVE' })
      .where(eq(organizationMembers.id, a.member.id));
    const b = await setup();
    await database.db.insert(organizationMembers).values({
      organizationId: b.org.id,
      userId: a.user.id,
      roleId: b.role.id,
    });
    await a.get('/auth/field-context').expect(409);
    await a.get(`/jobs/${assigned.job.id}`).set(a.headers).expect(409);
  });
  it('executes scoped writes transactionally and refuses a revoked assignment', async () => {
    const a = await setup();
    const assigned = await job(a.org.id, a.member.id);
    const path = `/jobs/${assigned.job.id}/visits/${assigned.visit.id}/notes`;
    const send = () =>
      request(app.getHttpServer())
        .post(path)
        .auth(a.token, { type: 'bearer' })
        .set(a.headers);
    const operation = randomUUID();
    await send()
      .send({
        body: 'scoped note',
        clientOperationId: operation,
        capturedAt: new Date().toISOString(),
      })
      .expect(201);
    await send()
      .send({
        body: 'scoped note',
        clientOperationId: operation,
        capturedAt: new Date().toISOString(),
      })
      .expect(201);
    await database.db
      .delete(visitTechnicians)
      .where(eq(visitTechnicians.visitId, assigned.visit.id));
    await send()
      .send({
        body: 'removed',
        clientOperationId: randomUUID(),
        capturedAt: new Date().toISOString(),
      })
      .expect(404);
  });
  it('refuses capability loss and rechecks a membership changed after guards', async () => {
    const a = await setup();
    const assigned = await job(a.org.id, a.member.id);
    await a.get('/auth/field-context').expect(200);
    const [notePermission] = await database.db
      .select()
      .from(permissions)
      .where(eq(permissions.code, 'VISIT_ADD_NOTE'));
    const grants = await database.db
      .select()
      .from(organizationMemberPermissions)
      .where(eq(organizationMemberPermissions.memberId, a.member.id));
    for (const grant of grants)
      if (grant.permissionId === notePermission!.id)
        await database.db
          .delete(organizationMemberPermissions)
          .where(eq(organizationMemberPermissions.id, grant.id));
    await request(app.getHttpServer())
      .post(`/jobs/${assigned.job.id}/visits/${assigned.visit.id}/notes`)
      .auth(a.token, { type: 'bearer' })
      .set(a.headers)
      .send({ body: 'capability revoked', clientOperationId: randomUUID() })
      .expect(403);
    const service = app.get(DatabaseService);
    const original = service.withFieldTransaction.bind(service);
    const spy = vi
      .spyOn(service, 'withFieldTransaction')
      .mockImplementationOnce(async (work) => {
        await database.db
          .update(organizationMembers)
          .set({ status: 'INACTIVE' })
          .where(eq(organizationMembers.id, a.member.id));
        return original(work);
      });
    try {
      await a.get(`/jobs/${assigned.job.id}`).set(a.headers).expect(403);
    } finally {
      spy.mockRestore();
    }
  });
});
