import { randomUUID } from 'node:crypto';
import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { and, eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_PERMISSIONS,
  VISIT_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
  customers,
  followUpVisitRequests,
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

const REVIEW_AND_CREATE = [
  VISIT_PERMISSIONS.REVIEW_REQUESTS,
  VISIT_PERMISSIONS.CREATE_SCHEDULE,
] as const satisfies readonly PermissionCode[];

/**
 * Follow-up Visit requests and direct scheduled Visit creation.
 *
 * These tests stay at the HTTP boundary because the important guarantees are authorization, tenant
 * scoping, lifecycle conflicts, schedule-conflict reporting, and transactional duplicate approval.
 */
describe('visit requests and scheduling (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let technicianRoleIdValue: string | null = null;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Visit Requests Org',
    });
    organizationId = database.cleanup.trackOrganization(organization.id);

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

  async function signInFor(granted: readonly PermissionCode[]) {
    const email = uniqueEmail('visit-requests');
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
          deviceId: `visit-requests-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return {
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  async function technicianRoleId(): Promise<string> {
    if (technicianRoleIdValue !== null) {
      return technicianRoleIdValue;
    }
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
      technicianRoleIdValue = existing.id;
      return existing.id;
    }
    const role = await createTestOrganizationRole(database.db, organizationId, {
      systemCode: 'TECHNICIAN',
    });
    technicianRoleIdValue = role.id;
    return role.id;
  }

  async function newTechnician(firstName: string, lastName: string) {
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({
        organizationId,
        userId: user.id,
        roleId: await technicianRoleId(),
      })
      .returning();
    await database.db.insert(userProfiles).values({
      userId: user.id,
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

  async function newProperty(customerId: string, actorMembershipId: string) {
    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId,
        addressLine1: '412 Visit Request Road',
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .returning();
    await database.db.insert(propertyCustomerRelationships).values({
      organizationId,
      propertyId: property.id,
      customerId,
      actorMembershipId,
    });
    return property;
  }

  async function newJob(status = 'NEW') {
    const actor = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);
    const customer = await newCustomer(`Visit Request Customer ${jobNumberSequence}`);
    const property = await newProperty(customer.id, actor.membershipId);
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId: customer.id,
        propertyId: property.id,
        propertyAddressSnapshot: {
          addressLine1: property.addressLine1,
          city: property.city,
          province: property.province,
          postalCode: property.postalCode,
        },
        title: `Visit Request Job ${jobNumberSequence}`,
        status,
      })
      .returning();
    return { job, customer, property, actor };
  }

  async function newScheduledVisit(input: {
    jobId: string;
    propertyId: string;
    technicianMembershipId: string;
    scheduledStart: Date;
    scheduledEnd: Date;
  }) {
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId,
        jobId: input.jobId,
        propertyId: input.propertyId,
        locationAddressSnapshot: { addressLine1: '412 Visit Request Road' },
        status: 'SCHEDULED',
        scheduledStart: input.scheduledStart,
        scheduledEnd: input.scheduledEnd,
      })
      .returning();
    await database.db.insert(visitTechnicians).values({
      organizationId,
      visitId: visit.id,
      technicianMembershipId: input.technicianMembershipId,
      roleCode: 'LEAD',
    });
    return visit;
  }

  function submitFollowUpRequest(input: {
    token: string;
    jobId: string;
    sourceVisitId?: string | null;
    proposedStart?: Date;
    proposedEnd?: Date;
  }) {
    const proposedStart =
      input.proposedStart ?? new Date('2026-10-12T14:00:00.000Z');
    const proposedEnd =
      input.proposedEnd ?? new Date('2026-10-12T15:00:00.000Z');
    return request(app.getHttpServer())
      .post(`/jobs/${input.jobId}/visit-requests`)
      .set('Authorization', `Bearer ${input.token}`)
      .send({
        sourceVisitId: input.sourceVisitId ?? null,
        proposedStart: proposedStart.toISOString(),
        proposedEnd: proposedEnd.toISOString(),
        reason: 'The site needs a second field visit.',
        sameTechnicianPreferred: true,
      });
  }

  it('lets a technician submit and read only their own follow-up requests', async () => {
    const { job } = await newJob();
    const requester = await signInFor([VISIT_PERMISSIONS.REQUEST_FOLLOW_UP]);
    const otherRequester = await signInFor([
      VISIT_PERMISSIONS.REQUEST_FOLLOW_UP,
    ]);

    const created = await submitFollowUpRequest({
      token: requester.accessToken,
      jobId: job.id,
    }).expect(201);

    expect(created.body).toMatchObject({
      jobId: job.id,
      sourceVisitId: null,
      requestingTechnicianMembershipId: requester.membershipId,
      reason: 'The site needs a second field visit.',
      sameTechnicianPreferred: true,
      status: 'PENDING',
      reviewerMembershipId: null,
      reviewedAt: null,
      reviewNote: null,
      createdVisitId: null,
      version: 1,
    });

    const ownList = await request(app.getHttpServer())
      .get('/jobs/visit-requests')
      .set('Authorization', `Bearer ${requester.accessToken}`)
      .expect(200);
    expect(ownList.body.map((row: { id: string }) => row.id)).toContain(
      created.body.id,
    );

    const otherList = await request(app.getHttpServer())
      .get('/jobs/visit-requests')
      .set('Authorization', `Bearer ${otherRequester.accessToken}`)
      .expect(200);
    expect(otherList.body.map((row: { id: string }) => row.id)).not.toContain(
      created.body.id,
    );
  });

  it('enforces the follow-up request and review capabilities', async () => {
    const { job } = await newJob();
    const requester = await signInFor([VISIT_PERMISSIONS.REQUEST_FOLLOW_UP]);
    const reviewerOnly = await signInFor([VISIT_PERMISSIONS.REVIEW_REQUESTS]);
    const creatorOnly = await signInFor([VISIT_PERMISSIONS.CREATE_SCHEDULE]);
    const requestRow = await submitFollowUpRequest({
      token: requester.accessToken,
      jobId: job.id,
    }).expect(201);

    await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests`)
      .send({
        proposedStart: '2026-10-13T14:00:00.000Z',
        proposedEnd: '2026-10-13T15:00:00.000Z',
        reason: 'Unauthenticated.',
      })
      .expect(401);

    await submitFollowUpRequest({
      token: creatorOnly.accessToken,
      jobId: job.id,
    }).expect(403);

    await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests/${requestRow.body.id}/approval`)
      .set('Authorization', `Bearer ${reviewerOnly.accessToken}`)
      .send({
        expectedStatus: 'PENDING',
        expectedVersion: 1,
        technicians: [{ membershipId: requester.membershipId, roleCode: 'LEAD' }],
      })
      .expect(403);

    await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visits`)
      .set('Authorization', `Bearer ${requester.accessToken}`)
      .send({
        scheduledStart: '2026-10-13T14:00:00.000Z',
        scheduledEnd: '2026-10-13T15:00:00.000Z',
        technicians: [{ membershipId: requester.membershipId, roleCode: 'LEAD' }],
      })
      .expect(403);
  });

  it('moves a request through clarification, rejects stale review attempts, and rejects a clarified request', async () => {
    const { job } = await newJob();
    const requester = await signInFor([VISIT_PERMISSIONS.REQUEST_FOLLOW_UP]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_REQUESTS]);
    const created = await submitFollowUpRequest({
      token: requester.accessToken,
      jobId: job.id,
    }).expect(201);

    const clarified = await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests/${created.body.id}/clarification`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({
        expectedStatus: 'PENDING',
        expectedVersion: 1,
        note: 'Please add the access window.',
      })
      .expect(200);

    expect(clarified.body).toMatchObject({
      status: 'NEEDS_CLARIFICATION',
      reviewerMembershipId: reviewer.membershipId,
      reviewNote: 'Please add the access window.',
      version: 2,
    });

    const stale = await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests/${created.body.id}/rejection`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({
        expectedStatus: 'PENDING',
        expectedVersion: 1,
        note: 'Reject with stale state.',
      })
      .expect(409);
    expect(stale.body).toMatchObject({
      code: 'FOLLOW_UP_VISIT_REQUEST_CONFLICT',
      details: { currentStatus: 'NEEDS_CLARIFICATION', currentVersion: 2 },
    });

    const rejected = await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests/${created.body.id}/rejection`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({
        expectedStatus: 'NEEDS_CLARIFICATION',
        expectedVersion: 2,
        note: 'Customer declined the return visit.',
      })
      .expect(200);

    expect(rejected.body).toMatchObject({
      status: 'REJECTED',
      reviewerMembershipId: reviewer.membershipId,
      reviewNote: 'Customer declined the return visit.',
      version: 3,
    });

    await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests/${created.body.id}/clarification`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ expectedStatus: 'REJECTED', expectedVersion: 3 })
      .expect(409);
  });

  it('approves a request into one scheduled Visit and makes approval retry idempotent', async () => {
    const { job } = await newJob();
    const requester = await signInFor([VISIT_PERMISSIONS.REQUEST_FOLLOW_UP]);
    const reviewer = await signInFor(REVIEW_AND_CREATE);
    const technician = await newTechnician('Priya', 'Schedule');
    const created = await submitFollowUpRequest({
      token: requester.accessToken,
      jobId: job.id,
      proposedStart: new Date('2026-10-15T14:00:00.000Z'),
      proposedEnd: new Date('2026-10-15T15:30:00.000Z'),
    }).expect(201);

    const body = {
      expectedStatus: 'PENDING',
      expectedVersion: 1,
      technicians: [{ membershipId: technician.id, roleCode: 'LEAD' }],
      note: 'Approved for Thursday.',
    };

    const approved = await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests/${created.body.id}/approval`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send(body)
      .expect(201);
    const createdVisitIds = approved.body.visits.map(
      (visit: { visitId: string }) => visit.visitId,
    );

    const retried = await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests/${created.body.id}/approval`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send(body)
      .expect(201);
    expect(
      retried.body.visits.map((visit: { visitId: string }) => visit.visitId),
    ).toEqual(createdVisitIds);

    const [storedRequest] = await database.db
      .select()
      .from(followUpVisitRequests)
      .where(eq(followUpVisitRequests.id, created.body.id))
      .limit(1);
    expect(storedRequest.status).toBe('APPROVED');
    expect(storedRequest.createdVisitId).not.toBeNull();
    expect(storedRequest.version).toBe(2);

    const storedVisits = await database.db
      .select()
      .from(visits)
      .where(eq(visits.jobId, job.id));
    expect(storedVisits).toHaveLength(1);
    expect(storedVisits[0].id).toBe(storedRequest.createdVisitId);
    expect(storedVisits[0].status).toBe('SCHEDULED');

    const crew = await database.db
      .select()
      .from(visitTechnicians)
      .where(eq(visitTechnicians.visitId, storedVisits[0].id));
    expect(crew).toMatchObject([
      { technicianMembershipId: technician.id, roleCode: 'LEAD' },
    ]);
  });

  it('reports schedule conflicts before approving and records the confirmed conflicts once approved', async () => {
    const { job, property } = await newJob();
    const other = await newJob('SCHEDULED');
    const requester = await signInFor([VISIT_PERMISSIONS.REQUEST_FOLLOW_UP]);
    const reviewer = await signInFor(REVIEW_AND_CREATE);
    const technician = await newTechnician('Luc', 'Conflict');
    const overlapStart = new Date('2026-10-16T14:00:00.000Z');
    const overlapEnd = new Date('2026-10-16T15:30:00.000Z');
    await newScheduledVisit({
      jobId: other.job.id,
      propertyId: other.property.id,
      technicianMembershipId: technician.id,
      scheduledStart: overlapStart,
      scheduledEnd: overlapEnd,
    });
    const created = await submitFollowUpRequest({
      token: requester.accessToken,
      jobId: job.id,
      proposedStart: new Date('2026-10-16T15:00:00.000Z'),
      proposedEnd: new Date('2026-10-16T16:00:00.000Z'),
    }).expect(201);

    const approval = {
      expectedStatus: 'PENDING',
      expectedVersion: 1,
      technicians: [{ membershipId: technician.id, roleCode: 'LEAD' }],
    };

    const conflict = await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests/${created.body.id}/approval`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send(approval)
      .expect(409);
    expect(conflict.body).toMatchObject({
      code: 'SCHEDULE_CONFLICT',
      details: {
        conflicts: [
          {
            jobId: other.job.id,
            jobNumber: other.job.jobNumber,
            technicianMembershipId: technician.id,
            technicianName: 'Luc Conflict',
            scheduledStart: overlapStart.toISOString(),
            scheduledEnd: overlapEnd.toISOString(),
          },
        ],
      },
    });

    await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visit-requests/${created.body.id}/approval`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ ...approval, confirmConflicts: true })
      .expect(201);

    const [storedRequest] = await database.db
      .select()
      .from(followUpVisitRequests)
      .where(eq(followUpVisitRequests.id, created.body.id))
      .limit(1);
    expect(storedRequest.createdVisitId).not.toBeNull();
    const createdVisitId = storedRequest.createdVisitId as string;
    const [history] = await database.db
      .select()
      .from(visitScheduleHistory)
      .where(eq(visitScheduleHistory.visitId, createdVisitId))
      .limit(1);
    expect(history.confirmedConflicts).toMatchObject([
      {
        jobId: other.job.id,
        technicianMembershipId: technician.id,
      },
    ]);
    expect(property.id).toBe(job.propertyId);
  });

  it('creates a scheduled Visit directly and records schedule, crew, assignment history, and Job status', async () => {
    const { job } = await newJob();
    const scheduler = await signInFor([VISIT_PERMISSIONS.CREATE_SCHEDULE]);
    const technician = await newTechnician('Mina', 'Direct');

    await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visits`)
      .set('Authorization', `Bearer ${scheduler.accessToken}`)
      .send({
        scheduledStart: '2026-10-17T14:00:00.000Z',
        scheduledEnd: '2026-10-17T15:00:00.000Z',
        technicians: [{ membershipId: technician.id, roleCode: 'LEAD' }],
      })
      .expect(201);

    const [storedJob] = await database.db
      .select()
      .from(jobs)
      .where(eq(jobs.id, job.id))
      .limit(1);
    expect(storedJob.status).toBe('SCHEDULED');

    const [storedVisit] = await database.db
      .select()
      .from(visits)
      .where(eq(visits.jobId, job.id))
      .limit(1);
    expect(storedVisit).toMatchObject({
      status: 'SCHEDULED',
      propertyId: job.propertyId,
    });

    const [scheduleHistory] = await database.db
      .select()
      .from(visitScheduleHistory)
      .where(eq(visitScheduleHistory.visitId, storedVisit.id))
      .limit(1);
    expect(scheduleHistory).toMatchObject({
      previousScheduledStart: null,
      previousScheduledEnd: null,
      actorMembershipId: scheduler.membershipId,
    });

    const [assignmentHistory] = await database.db
      .select()
      .from(visitTechnicianHistory)
      .where(eq(visitTechnicianHistory.visitId, storedVisit.id))
      .limit(1);
    expect(assignmentHistory).toMatchObject({
      technicianMembershipId: technician.id,
      event: 'ASSIGNED',
      roleCode: 'LEAD',
      actorMembershipId: scheduler.membershipId,
    });

    const [statusHistory] = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, job.id))
      .limit(1);
    expect(statusHistory).toMatchObject({
      fromStatus: 'NEW',
      toStatus: 'SCHEDULED',
      actorMembershipId: scheduler.membershipId,
    });
  });

  it('refuses an inactive or outside technician and hides another organization’s job', async () => {
    const { job } = await newJob();
    const scheduler = await signInFor([VISIT_PERMISSIONS.CREATE_SCHEDULE]);
    const inactive = await newTechnician('Noah', 'Inactive');
    await database.db
      .update(organizationMembers)
      .set({ status: 'INACTIVE' })
      .where(eq(organizationMembers.id, inactive.id));

    const inactiveResponse = await request(app.getHttpServer())
      .post(`/jobs/${job.id}/visits`)
      .set('Authorization', `Bearer ${scheduler.accessToken}`)
      .send({
        scheduledStart: '2026-10-18T14:00:00.000Z',
        scheduledEnd: '2026-10-18T15:00:00.000Z',
        technicians: [{ membershipId: inactive.id, roleCode: 'LEAD' }],
      })
      .expect(422);
    expect(inactiveResponse.body).toMatchObject({
      code: 'TECHNICIANS_NOT_ASSIGNABLE',
      details: { membershipIds: [inactive.id] },
    });

    await request(app.getHttpServer())
      .post(`/jobs/${randomUUID()}/visits`)
      .set('Authorization', `Bearer ${scheduler.accessToken}`)
      .send({
        scheduledStart: '2026-10-18T14:00:00.000Z',
        scheduledEnd: '2026-10-18T15:00:00.000Z',
        technicians: [{ membershipId: scheduler.membershipId, roleCode: 'LEAD' }],
      })
      .expect(404);
  });
});
