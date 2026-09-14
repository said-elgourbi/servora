import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { and, eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_PERMISSIONS,
  JOB_PERMISSIONS,
  TECHNICIAN_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
  customers,
  jobStatusHistory,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  organizationRoles,
  permissions,
  properties,
  propertyCustomerRelationships,
  userProfiles,
  visitScheduleHistory,
  visitTechnicianHistory,
  visitTechnicians,
  visits,
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

const PASSWORD = 'servora-e2e-password';

/**
 * The Job and Visit management actions (`BR-058` – `BR-079`).
 *
 * Authorization is asserted per route (`401` / `403` / `200`), and so is the business rule each
 * action exists for: the lifecycle table refuses a transition `BR-058` does not permit, `BR-061`
 * gates `PENDING_REVIEW`, `BR-073` restricts rescheduling to a `SCHEDULED` Visit, `BR-070` reports a
 * conflict before applying it, and `BR-069` records the assignment history without ever promoting a
 * technician on its own.
 */
describe('job actions (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Job Actions Org',
    });
    database.cleanup.trackOrganization(organization.id);
    organizationId = organization.id;

    const moduleFixture = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleFixture.createNestApplication({ logger: false });
    await app.init();
  });

  afterAll(async () => {
    await app.close();
    await database.dispose();
  });

  /** A signed-in member of the organization holding exactly [granted]. */
  async function signInFor(granted: readonly PermissionCode[]) {
    const email = uniqueEmail('job-actions');
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(PASSWORD),
    });
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, organizationId);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId: role.id })
      .returning();

    for (const code of granted) {
      const [permission] = await database.db
        .select({ id: permissions.id })
        .from(permissions)
        .where(eq(permissions.code, code))
        .limit(1);
      if (permission === undefined) {
        throw new Error(`Missing permission seed: ${code}`);
      }
      await database.db.insert(organizationMemberPermissions).values({
        organizationId,
        memberId: member.id,
        permissionId: permission.id,
      });
    }

    deviceSequence += 1;
    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({
        email,
        password: PASSWORD,
        device: {
          platform: 'ANDROID',
          deviceId: `job-actions-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return {
      userId: user.id,
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  /** A member of the organization, needed as the actor of a record and as an assignee. */
  async function newMembership() {
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, organizationId);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId: role.id })
      .returning();
    return member;
  }

  /**
   * The organization's default Technician role (`BR-003`), created once and reused.
   *
   * One role per organization carries the `TECHNICIAN` system code, so the tests resolve it rather
   * than trying to create a second one.
   */
  async function technicianRoleId(): Promise<string> {
    const [existing] = await database.db
      .select({ id: organizationRoles.id })
      .from(organizationRoles)
      .where(
        and(
          eq(organizationRoles.organizationId, organizationId),
          eq(organizationRoles.systemCode, 'TECHNICIAN'),
        ),
      )
      .limit(1);
    if (existing !== undefined) {
      return existing.id;
    }
    const role = await createTestOrganizationRole(database.db, organizationId, {
      systemCode: 'TECHNICIAN',
    });
    return role.id;
  }

  /** A member holding the default Technician role, so the technician read reports them. */
  async function newTechnician(firstName: string, lastName: string) {
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const roleId = await technicianRoleId();
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId })
      .returning();
    await database.db.insert(userProfiles).values({
      userId: member.userId,
      firstName,
      lastName,
      displayName: `${firstName} ${lastName}`,
    });
    return member;
  }

  async function newCustomer(displayName: string) {
    const [customer] = await database.db
      .insert(customers)
      .values({ organizationId, type: 'COMPANY', displayName })
      .returning();
    return customer;
  }

  async function newProperty(addressLine1: string) {
    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId,
        addressLine1,
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .returning();
    return property;
  }

  async function linkProperty(
    propertyId: string,
    customerId: string,
    actorMembershipId: string,
  ) {
    await database.db.insert(propertyCustomerRelationships).values({
      organizationId,
      propertyId,
      customerId,
      actorMembershipId,
    });
  }

  async function newJob(
    customerId: string,
    status: string,
    propertyId?: string,
  ) {
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId,
        title: `Job ${jobNumberSequence}`,
        status,
        ...(propertyId === undefined
          ? {}
          : {
              propertyId,
              propertyAddressSnapshot: {
                addressLine1: '1 Main Street',
                city: 'Ottawa',
                province: 'ON',
                postalCode: 'K1A 0B1',
              },
            }),
      })
      .returning();
    return job;
  }

  async function newVisit(values: {
    jobId: string;
    status: string;
    scheduledStart?: Date;
    scheduledEnd?: Date;
    propertyId?: string;
    outcomeCode?: string;
    actorMembershipId?: string;
  }) {
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId,
        jobId: values.jobId,
        propertyId: values.propertyId ?? null,
        locationAddressSnapshot:
          values.propertyId === undefined
            ? null
            : { addressLine1: '1 Main Street' },
        status: values.status,
        scheduledStart: values.scheduledStart ?? null,
        scheduledEnd: values.scheduledEnd ?? null,
        ...(values.status === 'COMPLETED'
          ? {
              outcomeCode: values.outcomeCode ?? 'RESOLVED',
              outcomeSummary: 'Work completed.',
              outcomeRecordedAt: values.scheduledStart ?? new Date(),
              outcomeRecordedByMembershipId: values.actorMembershipId ?? null,
            }
          : {}),
      })
      .returning();
    return visit;
  }

  async function assign(values: {
    visitId: string;
    technicianMembershipId: string;
    roleCode: 'LEAD' | 'TECHNICIAN';
  }) {
    await database.db.insert(visitTechnicians).values({
      organizationId,
      visitId: values.visitId,
      technicianMembershipId: values.technicianMembershipId,
      roleCode: values.roleCode,
    });
  }

  /** A Job with a Property and one scheduled Visit carrying a Lead and a second technician. */
  async function scheduledJobWithCrew() {
    const actor = await newMembership();
    const lead = await newTechnician('Mike', 'Lead');
    const second = await newTechnician('Sarah', 'Moreau');
    const customer = await newCustomer('Martha Reynolds');
    const property = await newProperty('987 Cedar Lane');
    await linkProperty(property.id, customer.id, actor.id);
    const job = await newJob(customer.id, 'SCHEDULED', property.id);
    const now = Date.now();
    const visit = await newVisit({
      jobId: job.id,
      propertyId: property.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(now + 3_600_000),
      scheduledEnd: new Date(now + 7_200_000),
    });
    await assign({
      visitId: visit.id,
      technicianMembershipId: lead.id,
      roleCode: 'LEAD',
    });
    await assign({
      visitId: visit.id,
      technicianMembershipId: second.id,
      roleCode: 'TECHNICIAN',
    });
    return { job, visit, property, customer, actor, lead, second };
  }

  it('refuses an unauthenticated caller with 401', async () => {
    const fixture = await scheduledJobWithCrew();

    await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .send({ status: 'IN_PROGRESS' })
      .expect(401);
    await request(app.getHttpServer())
      .put(`/jobs/${fixture.job.id}/visits/${fixture.visit.id}/technicians`)
      .send({
        technicians: [{ membershipId: fixture.lead.id, roleCode: 'LEAD' }],
      })
      .expect(401);
    await request(app.getHttpServer()).get('/technicians').expect(401);
  });

  it('refuses a caller without the capability with 403', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'IN_PROGRESS' })
      .expect(403);
    await request(app.getHttpServer())
      .get('/technicians')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  it('applies a permitted transition and records it in history', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'IN_PROGRESS', expectedVersion: fixture.job.version })
      .expect(200);

    expect(response.body.status).toBe('IN_PROGRESS');
    // The action answers with the Job as it now stands, so the client does not have to re-read it.
    expect(response.body.version).toBe(fixture.job.version + 1);
    expect(response.body.allowedStatusTransitions).toEqual(['PENDING_REVIEW']);

    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, fixture.job.id));
    expect(history).toHaveLength(1);
    expect(history[0]).toMatchObject({
      fromStatus: 'SCHEDULED',
      toStatus: 'IN_PROGRESS',
      actorMembershipId: session.membershipId,
    });
  });

  it('refuses a transition BR-058 does not permit', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'COMPLETED' })
      .expect(409);

    expect(response.body.code).toBe('JOB_STATUS_TRANSITION_NOT_ALLOWED');
    expect(response.body.details).toEqual({
      from: 'SCHEDULED',
      to: 'COMPLETED',
      allowed: ['IN_PROGRESS'],
    });

    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, fixture.job.id));
    expect(history).toEqual([]);
  });

  it('refuses a cancellation while the reason catalogue is undefined', async () => {
    // `BR-064` requires a structured reason; its catalogue is an open question, so the API refuses
    // the cancellation rather than inventing the vocabulary (`BR-042`).
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'CANCELED', note: 'Customer called.' })
      .expect(409);

    expect(response.body.code).toBe('JOB_CANCELLATION_UNAVAILABLE');
  });

  it('refuses a status the vocabulary does not have', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'EN_ROUTE' })
      .expect(400);
  });

  it('refuses a change the client read before someone else changed the Job', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'IN_PROGRESS', expectedVersion: fixture.job.version + 5 })
      .expect(409);

    expect(response.body.code).toBe('VERSION_CONFLICT');
    expect(response.body.details).toEqual({
      record: 'JOB',
      currentVersion: fixture.job.version,
    });
  });

  it('refuses an action on another organization\u2019s Job with 404', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const other = await createTestOrganization(database.db, {
      name: 'Other Job Actions Org',
    });
    database.cleanup.trackOrganization(other.id);
    const [otherCustomer] = await database.db
      .insert(customers)
      .values({
        organizationId: other.id,
        type: 'COMPANY',
        displayName: 'Other',
      })
      .returning();
    const [otherJob] = await database.db
      .insert(jobs)
      .values({
        organizationId: other.id,
        jobNumber: 1,
        customerId: otherCustomer.id,
        title: 'Other organization job',
        status: 'SCHEDULED',
      })
      .returning();

    // A Job another organization owns is `404`, never `403`: the API does not confirm that it
    // exists (`BR-001`).
    const response = await request(app.getHttpServer())
      .patch(`/jobs/${otherJob.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'IN_PROGRESS' })
      .expect(404);
    expect(response.body.code).toBe('JOB_NOT_FOUND');

    // The caller's own Job is untouched by the attempt.
    const [unchanged] = await database.db
      .select({ status: jobs.status })
      .from(jobs)
      .where(eq(jobs.id, fixture.job.id));
    expect(unchanged?.status).toBe('SCHEDULED');
  });

  it('refuses PENDING_REVIEW while the Job still has an active Visit', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'PENDING_REVIEW' })
      .expect(409);

    // The transition is not permitted from SCHEDULED at all, which is the first rule that applies.
    expect(response.body.code).toBe('JOB_STATUS_TRANSITION_NOT_ALLOWED');

    // Driving it to IN_PROGRESS first shows `BR-061`'s own condition refusing the review.
    await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'IN_PROGRESS' })
      .expect(200);
    const review = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'PENDING_REVIEW' })
      .expect(409);

    expect(review.body).toMatchObject({
      code: 'JOB_REVIEW_CONDITION_NOT_MET',
      details: { reason: 'ACTIVE_VISIT' },
    });
  });

  it('awaits review once no Visit is active and the latest completed Visit resolved the Job', async () => {
    const actor = await newMembership();
    const customer = await newCustomer('Review Co');
    const job = await newJob(customer.id, 'IN_PROGRESS');
    const now = Date.now();
    await newVisit({
      jobId: job.id,
      status: 'COMPLETED',
      scheduledStart: new Date(now - 7_200_000),
      scheduledEnd: new Date(now - 3_600_000),
      outcomeCode: 'RESOLVED',
      actorMembershipId: actor.id,
    });
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'PENDING_REVIEW' })
      .expect(200);

    expect(response.body.status).toBe('PENDING_REVIEW');
  });

  it('refuses PENDING_REVIEW when the latest completed Visit expects follow-up', async () => {
    const actor = await newMembership();
    const customer = await newCustomer('Follow-up Co');
    const job = await newJob(customer.id, 'IN_PROGRESS');
    const now = Date.now();
    // The most recent outcome is the one that decides (`BR-061`, `BR-078`).
    await newVisit({
      jobId: job.id,
      status: 'COMPLETED',
      scheduledStart: new Date(now - 10_800_000),
      scheduledEnd: new Date(now - 10_100_000),
      outcomeCode: 'RESOLVED',
      actorMembershipId: actor.id,
    });
    await newVisit({
      jobId: job.id,
      status: 'COMPLETED',
      scheduledStart: new Date(now - 3_600_000),
      scheduledEnd: new Date(now - 2_700_000),
      outcomeCode: 'NEEDS_PARTS',
      actorMembershipId: actor.id,
    });
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'PENDING_REVIEW' })
      .expect(409);

    expect(response.body.details).toEqual({ reason: 'OUTCOME' });
  });

  it('closes a Job awaiting review and reopens it to NEW with the reopen note recorded', async () => {
    const customer = await newCustomer('Close Co');
    const job = await newJob(customer.id, 'PENDING_REVIEW');
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'COMPLETED' })
      .expect(200);

    const reopened = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'NEW', note: 'Customer reported the fault returned.' })
      .expect(200);

    // Reopening returns the Job to the scheduling workflow and never to IN_PROGRESS (`BR-063`).
    expect(reopened.body.status).toBe('NEW');
    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, job.id))
      .orderBy(jobStatusHistory.recordedAt);
    expect(history.map((row) => `${row.fromStatus}->${row.toStatus}`)).toEqual([
      'PENDING_REVIEW->COMPLETED',
      'COMPLETED->NEW',
    ]);
    expect(history[1]?.note).toBe('Customer reported the fault returned.');
  });

  it('reschedules a scheduled Visit and records the previous schedule', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const start = new Date(Date.now() + 86_400_000);
    const end = new Date(start.getTime() + 3_600_000);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/visits/${fixture.visit.id}/schedule`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({
        scheduledStart: start.toISOString(),
        scheduledEnd: end.toISOString(),
        arrivalWindowStart: new Date(start.getTime() - 1_800_000).toISOString(),
        arrivalWindowEnd: new Date(start.getTime() + 1_800_000).toISOString(),
        reason: 'Customer asked for the morning.',
        expectedVersion: fixture.visit.version,
      })
      .expect(200);

    expect(response.body.selectedVisit).toMatchObject({
      id: fixture.visit.id,
      status: 'SCHEDULED',
      scheduledStart: start.toISOString(),
      scheduledEnd: end.toISOString(),
    });

    const history = await database.db
      .select()
      .from(visitScheduleHistory)
      .where(eq(visitScheduleHistory.visitId, fixture.visit.id));
    expect(history).toHaveLength(1);
    expect(history[0]).toMatchObject({
      previousScheduledStart: fixture.visit.scheduledStart,
      newScheduledStart: start,
      reason: 'Customer asked for the morning.',
      actorMembershipId: session.membershipId,
    });
    // The Visit's identity, status and crew are untouched: a reschedule is a schedule change, not a
    // new Visit (`BR-073`).
    const crew = await database.db
      .select()
      .from(visitTechnicians)
      .where(eq(visitTechnicians.visitId, fixture.visit.id));
    expect(crew).toHaveLength(2);
  });

  it('refuses to reschedule a Visit that is no longer scheduled', async () => {
    const fixture = await scheduledJobWithCrew();
    await database.db
      .update(visits)
      .set({ status: 'EN_ROUTE' })
      .where(eq(visits.id, fixture.visit.id));
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const start = new Date(Date.now() + 86_400_000);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/visits/${fixture.visit.id}/schedule`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({
        scheduledStart: start.toISOString(),
        scheduledEnd: new Date(start.getTime() + 3_600_000).toISOString(),
      })
      .expect(409);

    expect(response.body).toMatchObject({
      code: 'VISIT_NOT_RESCHEDULABLE',
      details: { status: 'EN_ROUTE' },
    });
  });

  it('reports a technician conflict before rescheduling, then applies it once confirmed', async () => {
    // A second Job whose Visit books the same Lead over an overlapping window (`BR-070`).
    const other = await scheduledJobWithCrew();
    const start = new Date(Date.now() + 2 * 3_600_000);
    const conflictVisit = await newVisit({
      jobId: other.job.id,
      propertyId: other.property.id,
      status: 'SCHEDULED',
      scheduledStart: start,
      scheduledEnd: new Date(start.getTime() + 3_600_000),
    });
    await assign({
      visitId: conflictVisit.id,
      technicianMembershipId: other.lead.id,
      roleCode: 'LEAD',
    });

    const fixture = await scheduledJobWithCrew();
    await assign({
      visitId: fixture.visit.id,
      technicianMembershipId: other.lead.id,
      roleCode: 'TECHNICIAN',
    });
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const body = {
      scheduledStart: start.toISOString(),
      scheduledEnd: new Date(start.getTime() + 3_600_000).toISOString(),
    };

    const refused = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/visits/${fixture.visit.id}/schedule`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send(body)
      .expect(409);

    expect(refused.body.code).toBe('SCHEDULE_CONFLICT');
    // The conflict identifies the Visit, the technician and the time, which is what the user has to
    // be shown before deciding (`BR-070`).
    expect(refused.body.details.conflicts).toEqual([
      {
        visitId: conflictVisit.id,
        jobId: other.job.id,
        jobNumber: other.job.jobNumber,
        technicianMembershipId: other.lead.id,
        technicianName: 'Mike Lead',
        scheduledStart: start.toISOString(),
        scheduledEnd: new Date(start.getTime() + 3_600_000).toISOString(),
      },
    ]);

    const applied = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/visits/${fixture.visit.id}/schedule`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ ...body, confirmConflicts: true })
      .expect(200);

    expect(applied.body.selectedVisit.scheduledStart).toBe(body.scheduledStart);
    // A confirmed conflict is a warning, not a prohibition: the change is applied and what was
    // accepted is recorded with it (`BR-070`).
    const [recorded] = await database.db
      .select()
      .from(visitScheduleHistory)
      .where(eq(visitScheduleHistory.visitId, fixture.visit.id));
    expect(recorded?.confirmedConflicts).toHaveLength(1);
  });

  it('states the whole crew and records every assignment change', async () => {
    const fixture = await scheduledJobWithCrew();
    const third = await newTechnician('John', 'Tremblay');
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    // The Lead is removed and the second technician is promoted in one action (`BR-069`), and a new
    // technician is added.
    await request(app.getHttpServer())
      .put(`/jobs/${fixture.job.id}/visits/${fixture.visit.id}/technicians`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({
        technicians: [
          { membershipId: fixture.second.id, roleCode: 'LEAD' },
          { membershipId: third.id, roleCode: 'TECHNICIAN' },
        ],
        expectedVersion: fixture.visit.version,
      })
      .expect(200);

    const crew = await database.db
      .select()
      .from(visitTechnicians)
      .where(eq(visitTechnicians.visitId, fixture.visit.id));
    expect(
      crew.map((row) => `${row.technicianMembershipId}:${row.roleCode}`).sort(),
    ).toEqual([`${fixture.second.id}:LEAD`, `${third.id}:TECHNICIAN`].sort());

    const history = await database.db
      .select()
      .from(visitTechnicianHistory)
      .where(eq(visitTechnicianHistory.visitId, fixture.visit.id));
    // Every change is a recorded fact with the actor who made it (`BR-069`). The three happened as
    // one action, so they share an instant: the set is asserted rather than an order the database
    // does not promise.
    expect(history).toHaveLength(3);
    expect(history.find((row) => row.event === 'REMOVED')).toMatchObject({
      technicianMembershipId: fixture.lead.id,
      roleCode: null,
      previousRoleCode: 'LEAD',
      actorMembershipId: session.membershipId,
    });
    expect(history.find((row) => row.event === 'ASSIGNED')).toMatchObject({
      technicianMembershipId: third.id,
      roleCode: 'TECHNICIAN',
      previousRoleCode: null,
    });
    expect(history.find((row) => row.event === 'ROLE_CHANGED')).toMatchObject({
      technicianMembershipId: fixture.second.id,
      roleCode: 'LEAD',
      previousRoleCode: 'TECHNICIAN',
      actorMembershipId: session.membershipId,
    });
  });

  it('refuses a crew that does not name exactly one Lead', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    await request(app.getHttpServer())
      .put(`/jobs/${fixture.job.id}/visits/${fixture.visit.id}/technicians`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({
        technicians: [
          { membershipId: fixture.lead.id, roleCode: 'TECHNICIAN' },
          { membershipId: fixture.second.id, roleCode: 'TECHNICIAN' },
        ],
      })
      .expect(400);

    const crew = await database.db
      .select()
      .from(visitTechnicians)
      .where(eq(visitTechnicians.visitId, fixture.visit.id));
    expect(crew).toHaveLength(2);
  });

  it('refuses to assign a technician who is not an active member of the organization', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const other = await createTestOrganization(database.db, {
      name: 'Other Assignees Org',
    });
    database.cleanup.trackOrganization(other.id);
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, other.id);
    const [outsider] = await database.db
      .insert(organizationMembers)
      .values({ organizationId: other.id, userId: user.id, roleId: role.id })
      .returning();

    const response = await request(app.getHttpServer())
      .put(`/jobs/${fixture.job.id}/visits/${fixture.visit.id}/technicians`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({
        technicians: [{ membershipId: outsider.id, roleCode: 'LEAD' }],
      })
      .expect(422);

    expect(response.body).toMatchObject({
      code: 'TECHNICIANS_NOT_ASSIGNABLE',
      details: { membershipIds: [outsider.id] },
    });
  });

  it('lists the organization\u2019s active technicians for choosing a crew', async () => {
    const technician = await newTechnician('Sarah', 'Moreau');
    const session = await signInFor([TECHNICIAN_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get('/technicians')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body).toContainEqual({
      membershipId: technician.id,
      name: 'Sarah Moreau',
    });

    // Another organization's technicians are never listed (`BR-001`).
    const other = await createTestOrganization(database.db, {
      name: 'Other Technicians Org',
    });
    database.cleanup.trackOrganization(other.id);
    const otherRole = await createTestOrganizationRole(database.db, other.id, {
      systemCode: 'TECHNICIAN',
    });
    const otherUser = await createTestUser(database.db);
    database.cleanup.trackUser(otherUser.id);
    const [otherMember] = await database.db
      .insert(organizationMembers)
      .values({
        organizationId: other.id,
        userId: otherUser.id,
        roleId: otherRole.id,
      })
      .returning();

    expect(
      response.body.map((row: { membershipId: string }) => row.membershipId),
    ).not.toContain(otherMember.id);
  });

  it('answers a Visit that does not belong to the Job with 404', async () => {
    const customer = await newCustomer('No Visit Co');
    const job = await newJob(customer.id, 'NEW');
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    // A Job may exist with no Visit (`BR-051`), and none is invented for it.
    await request(app.getHttpServer())
      .patch(
        `/jobs/${job.id}/visits/11111111-1111-4111-8111-111111111111/schedule`,
      )
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({
        scheduledStart: new Date(Date.now() + 3_600_000).toISOString(),
        scheduledEnd: new Date(Date.now() + 7_200_000).toISOString(),
      })
      .expect(404);
  });
});
