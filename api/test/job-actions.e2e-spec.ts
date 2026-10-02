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
  VISIT_PERMISSIONS,
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
  visitStatusHistory,
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
 * The lifecycle asserted here is the one `docs/tracker/051-job-visit-lifecycle-redesign.md` defines:
 * a Job is `NEW`, `ACTIVE`, `COMPLETED` or `CANCELED`, and operational attention is derived rather
 * than stored. Authorization is asserted per route (`401` / `403` / `200`), and so is the business
 * rule each action exists for: the lifecycle table refuses a destination `BR-058` does not permit,
 * `BR-062`'s open-Visit invariant gates `COMPLETED` as runtime eligibility on top of structurally
 * permitted destinations, `BR-064`/`BR-065` cascade a cancellation to the Job's open Visits, `BR-073`
 * restricts rescheduling to a `SCHEDULED` Visit, `BR-070` reports a conflict before applying it, and
 * `BR-069` records the assignment history without ever promoting a technician on its own.
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
    const job = await newJob(customer.id, 'ACTIVE', property.id);
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
    const customer = await newCustomer('Transition Co');
    const job = await newJob(customer.id, 'NEW');
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'ACTIVE', expectedVersion: job.version })
      .expect(200);

    expect(response.body.status).toBe('ACTIVE');
    // The action answers with the Job as it now stands, so the client does not have to re-read it.
    expect(response.body.version).toBe(job.version + 1);
    // An `ACTIVE` Job has no open status to move to: its destinations are the terminal pair
    // (`BR-058`).
    expect(response.body.allowedStatusTransitions).toEqual([
      'COMPLETED',
      'CANCELED',
    ]);

    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, job.id));
    expect(history).toHaveLength(1);
    expect(history[0]).toMatchObject({
      fromStatus: 'NEW',
      toStatus: 'ACTIVE',
      actorMembershipId: session.membershipId,
    });
  });

  it('refuses a destination BR-058 does not list', async () => {
    // An open Job is never moved back to NEW: NEW is reached by reopening a terminal Job (`BR-063`).
    // The refusal names the destinations that do exist for the Job's status, so a client can offer
    // one instead of guessing (`BR-041`).
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'NEW' })
      .expect(409);

    expect(response.body.code).toBe('JOB_STATUS_TRANSITION_NOT_ALLOWED');
    expect(response.body.details).toEqual({
      from: 'ACTIVE',
      to: 'NEW',
      allowed: ['COMPLETED', 'CANCELED'],
    });

    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, fixture.job.id));
    expect(history).toEqual([]);
  });

  it('closes a Job directly from an open status, in one operation and one history record', async () => {
    // `ACTIVE` → `COMPLETED` is one request rather than a walk through every status of the
    // lifecycle, and it writes exactly one transition carrying its actor and timestamp
    // (`BR-058`, `BR-067`).
    const actor = await newMembership();
    const customer = await newCustomer('Direct Close Co');
    const property = await newProperty('12 Direct Way');
    await linkProperty(property.id, customer.id, actor.id);
    const job = await newJob(customer.id, 'ACTIVE', property.id);
    const now = Date.now();
    const visit = await newVisit({
      jobId: job.id,
      status: 'COMPLETED',
      scheduledStart: new Date(now - 3_600_000),
      scheduledEnd: new Date(now - 1_800_000),
      propertyId: property.id,
      outcomeCode: 'RESOLVED',
      actorMembershipId: actor.id,
    });
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'COMPLETED', expectedVersion: job.version })
      .expect(200);

    expect(response.body.status).toBe('COMPLETED');
    // A terminal Job offers its reopen and nothing else (`BR-058`, `BR-063`).
    expect(response.body.allowedStatusTransitions).toEqual(['ACTIVE']);

    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, job.id));
    expect(history).toHaveLength(1);
    expect(history[0]).toMatchObject({
      fromStatus: 'ACTIVE',
      toStatus: 'COMPLETED',
      actorMembershipId: session.membershipId,
    });
    expect(history[0]?.recordedAt).toBeInstanceOf(Date);

    // The Job moved; the Visit did not, and no Visit status history was written (`BR-059`, `BR-067`).
    const [visitAfter] = await database.db
      .select({ status: visits.status, version: visits.version })
      .from(visits)
      .where(eq(visits.id, visit.id));
    expect(visitAfter).toMatchObject({
      status: 'COMPLETED',
      version: visit.version,
    });
    const visitHistory = await database.db
      .select()
      .from(visitStatusHistory)
      .where(eq(visitStatusHistory.visitId, visit.id));
    expect(visitHistory).toEqual([]);
  });

  it('cancels a Job and cascades the cancellation to its open Visits', async () => {
    // Canceling is a destination of the Job lifecycle (`BR-058`) and it moves the Job in one
    // operation; `BR-064`/`BR-065` then cancel the Job's open Visits in the same transaction, each
    // with its own append-only status-history row recording the trigger.
    const actor = await newMembership();
    const customer = await newCustomer('Cancellation Co');
    const property = await newProperty('9 Cancellation Row');
    await linkProperty(property.id, customer.id, actor.id);
    const job = await newJob(customer.id, 'ACTIVE', property.id);
    const now = Date.now();
    const openVisit = await newVisit({
      jobId: job.id,
      status: 'EN_ROUTE',
      scheduledStart: new Date(now + 3_600_000),
      scheduledEnd: new Date(now + 7_200_000),
      propertyId: property.id,
    });
    const historicalVisit = await newVisit({
      jobId: job.id,
      status: 'COMPLETED',
      scheduledStart: new Date(now - 7_200_000),
      scheduledEnd: new Date(now - 3_600_000),
      propertyId: property.id,
      outcomeCode: 'RESOLVED',
      actorMembershipId: actor.id,
    });
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'CANCELED', note: 'Customer called.' })
      .expect(200);

    expect(response.body.status).toBe('CANCELED');
    // A terminal Job offers its reopen and nothing else (`BR-058`, `BR-063`).
    expect(response.body.allowedStatusTransitions).toEqual(['ACTIVE']);

    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, job.id));
    expect(history).toHaveLength(1);
    expect(history[0]).toMatchObject({
      fromStatus: 'ACTIVE',
      toStatus: 'CANCELED',
      note: 'Customer called.',
      actorMembershipId: session.membershipId,
    });

    // The open Visit was canceled by the cascade, with the trigger recorded on its own history row.
    const [openAfter] = await database.db
      .select({ status: visits.status })
      .from(visits)
      .where(eq(visits.id, openVisit.id));
    expect(openAfter?.status).toBe('CANCELED');

    const cascadeHistory = await database.db
      .select()
      .from(visitStatusHistory)
      .where(eq(visitStatusHistory.visitId, openVisit.id));
    expect(cascadeHistory).toHaveLength(1);
    expect(cascadeHistory[0]).toMatchObject({
      fromStatus: 'EN_ROUTE',
      toStatus: 'CANCELED',
      cancellationSource: 'JOB_CANCELLATION',
      actorMembershipId: session.membershipId,
    });

    // A historical Visit is not touched, and no Visit status history is invented for it (`BR-065`).
    const [historicalAfter] = await database.db
      .select({ status: visits.status, version: visits.version })
      .from(visits)
      .where(eq(visits.id, historicalVisit.id));
    expect(historicalAfter).toMatchObject({
      status: 'COMPLETED',
      version: historicalVisit.version,
    });
    const historicalHistory = await database.db
      .select()
      .from(visitStatusHistory)
      .where(eq(visitStatusHistory.visitId, historicalVisit.id));
    expect(historicalHistory).toEqual([]);
  });

  it('closes a Job administratively when it has no Visit', async () => {
    // A Job may exist with no Visit (`BR-051`), and an office user may close it: with no Visit there is
    // no open field work to block it (`BR-062`).
    const customer = await newCustomer('Administrative Closure Co');
    const job = await newJob(customer.id, 'NEW');
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'COMPLETED' })
      .expect(200);

    expect(response.body.status).toBe('COMPLETED');
  });

  it('closes a Job whose Visits are all historical', async () => {
    // `BR-062`: a historical Visit — `COMPLETED` or `CANCELED` (`BR-074`) — does not prevent
    // completion, whatever the Job's history holds.
    const actor = await newMembership();
    const customer = await newCustomer('Historical Visits Co');
    const job = await newJob(customer.id, 'ACTIVE');
    const now = Date.now();
    await newVisit({
      jobId: job.id,
      status: 'COMPLETED',
      scheduledStart: new Date(now - 10_800_000),
      scheduledEnd: new Date(now - 10_000_000),
      outcomeCode: 'NEEDS_PARTS',
      actorMembershipId: actor.id,
    });
    await newVisit({
      jobId: job.id,
      status: 'CANCELED',
      scheduledStart: new Date(now - 7_200_000),
      scheduledEnd: new Date(now - 3_600_000),
    });
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'COMPLETED' })
      .expect(200);

    expect(response.body.status).toBe('COMPLETED');
  });

  it('refuses completion while the Job has an open Visit', async () => {
    // The destination is structurally permitted and stays in the read for the client to offer
    // (`BR-058`); what refuses it is `BR-062`'s invariant, which answers with its own code so a client
    // can say why rather than guess (`BR-041`, `BR-042`).
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'COMPLETED' })
      .expect(409);

    expect(response.body.code).toBe('JOB_COMPLETION_BLOCKED');
    expect(response.body.details).toBeUndefined();

    // Nothing moved and nothing was recorded: a refusal is not a partial transition.
    const [unchanged] = await database.db
      .select({ status: jobs.status, version: jobs.version })
      .from(jobs)
      .where(eq(jobs.id, fixture.job.id));
    expect(unchanged).toMatchObject({
      status: 'ACTIVE',
      version: fixture.job.version,
    });
    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, fixture.job.id));
    expect(history).toEqual([]);
  });

  it('keeps a Job completion-blocked while a Visit is still being worked', async () => {
    // `BR-062` applies whatever open status the field work is in (`BR-074`).
    const customer = await newCustomer('Open Work Co');
    const job = await newJob(customer.id, 'ACTIVE');
    const now = Date.now();
    await newVisit({
      jobId: job.id,
      status: 'ON_SITE',
      scheduledStart: new Date(now - 3_600_000),
      scheduledEnd: new Date(now + 3_600_000),
    });
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'COMPLETED' })
      .expect(409);

    expect(response.body.code).toBe('JOB_COMPLETION_BLOCKED');
  });

  it('refuses completion for a Visit that has not happened yet', async () => {
    // A `DRAFT` Visit is not scheduled work for the derived signal (`BR-060`), but it is a field
    // attempt that has not happened, so it is remaining work and keeps its Job open
    // (`BR-062`, `BR-074`).
    const customer = await newCustomer('Draft Visit Co');
    const job = await newJob(customer.id, 'NEW');
    await newVisit({ jobId: job.id, status: 'DRAFT' });
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'COMPLETED' })
      .expect(409);

    expect(response.body.code).toBe('JOB_COMPLETION_BLOCKED');
  });

  it('refuses a second cancellation, because a terminal Job only reopens', async () => {
    // `BR-064`'s structured reason catalogue is still an `OPEN QUESTION`, so the request's optional
    // `note` is the only explanation recorded and no reason vocabulary is invented (`BR-042`). What
    // the lifecycle does state is that `CANCELED` has exactly one destination (`BR-058`).
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'CANCELED', note: 'Customer called.' })
      .expect(200);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'CANCELED' })
      .expect(409);

    expect(response.body).toMatchObject({
      code: 'JOB_STATUS_TRANSITION_NOT_ALLOWED',
      details: { from: 'CANCELED', to: 'CANCELED', allowed: ['ACTIVE'] },
    });
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
      .send({ status: 'CANCELED', expectedVersion: fixture.job.version + 5 })
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
        status: 'ACTIVE',
      })
      .returning();

    // A Job another organization owns is `404`, never `403`: the API does not confirm that it
    // exists (`BR-001`).
    const response = await request(app.getHttpServer())
      .patch(`/jobs/${otherJob.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'CANCELED' })
      .expect(404);
    expect(response.body.code).toBe('JOB_NOT_FOUND');

    // The caller's own Job is untouched by the attempt.
    const [unchanged] = await database.db
      .select({ status: jobs.status })
      .from(jobs)
      .where(eq(jobs.id, fixture.job.id));
    expect(unchanged?.status).toBe('ACTIVE');
  });

  it('reports the destinations a Job has and never a derived condition as one', async () => {
    // The read is the client's source of the lifecycle (`BR-041`): an `ACTIVE` Job offers the terminal
    // pair and nothing else. Operational attention is a separate projection (`BR-060`), so no derived
    // condition may appear in this list.
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const open = await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(open.body.status).toBe('ACTIVE');
    expect(open.body.allowedStatusTransitions).toEqual(['COMPLETED', 'CANCELED']);
    expect(open.body.allowedStatusTransitions).not.toContain('JOB_NEEDS_SCHEDULING');
    expect(open.body.attention).toEqual([]);
  });

  it('completes a Job directly once its Visits are all historical', async () => {
    // There is no review status between the field work and the closure (`BR-058`): an open Job whose
    // Visits are all historical closes in one operation (`BR-062`).
    const actor = await newMembership();
    const customer = await newCustomer('Closure Co');
    const job = await newJob(customer.id, 'ACTIVE');
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
      .send({ status: 'COMPLETED' })
      .expect(200);

    expect(response.body.status).toBe('COMPLETED');
    // A terminal Job offers its reopen and nothing else (`BR-058`, `BR-063`).
    expect(response.body.allowedStatusTransitions).toEqual(['ACTIVE']);
  });

  it('derives office attention from the latest completed Visit outcome', async () => {
    // What the office must act on is the **outcome** of the latest completed Visit, projected as
    // attention rather than stored as a status (`BR-060`, `BR-078`, `api/src/jobs/job-attention.ts`).
    // The most recent outcome is the one that decides.
    const actor = await newMembership();
    const customer = await newCustomer('Follow-up Co');
    const job = await newJob(customer.id, 'ACTIVE');
    const now = Date.now();
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
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.status).toBe('ACTIVE');
    expect(response.body.attention).toHaveLength(1);
    expect(response.body.attention[0]).toMatchObject({
      code: 'PARTS_REQUIRED',
      reasonCode: null,
    });
    // Attention is not lifecycle: it never appears among the Job's destinations.
    expect(response.body.allowedStatusTransitions).toEqual(['COMPLETED', 'CANCELED']);
  });

  it('reopens a completed Job to ACTIVE with the reopen note recorded', async () => {
    // Reopening is the only way out of a terminal status (`BR-058`, `BR-063`), and it returns the
    // request to execution — `ACTIVE`, the status a Job with historical Visits really has
    // (`docs/tracker/051-job-visit-lifecycle-redesign.md`; an `OPEN QUESTION` in `BR-063`).
    const customer = await newCustomer('Close Co');
    const job = await newJob(customer.id, 'ACTIVE');
    const session = await signInFor([JOB_PERMISSIONS.UPDATE]);

    await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'COMPLETED' })
      .expect(200);

    const reopened = await request(app.getHttpServer())
      .patch(`/jobs/${job.id}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ status: 'ACTIVE', note: 'Customer reported the fault returned.' })
      .expect(200);

    expect(reopened.body.status).toBe('ACTIVE');
    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, job.id))
      .orderBy(jobStatusHistory.recordedAt);
    expect(history.map((row) => `${row.fromStatus}->${row.toStatus}`)).toEqual([
      'ACTIVE->COMPLETED',
      'COMPLETED->ACTIVE',
    ]);
    expect(history[1]?.note).toBe('Customer reported the fault returned.');
  });

  it('reschedules a scheduled Visit and records the previous schedule', async () => {
    const fixture = await scheduledJobWithCrew();
    const session = await signInFor([VISIT_PERMISSIONS.UPDATE_SCHEDULE]);
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
    const session = await signInFor([VISIT_PERMISSIONS.UPDATE_SCHEDULE]);
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
    const session = await signInFor([VISIT_PERMISSIONS.UPDATE_SCHEDULE]);
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
    const session = await signInFor([VISIT_PERMISSIONS.ASSIGN_TECHNICIANS]);

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
    const session = await signInFor([VISIT_PERMISSIONS.ASSIGN_TECHNICIANS]);

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
    const session = await signInFor([VISIT_PERMISSIONS.ASSIGN_TECHNICIANS]);
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
    const session = await signInFor([VISIT_PERMISSIONS.UPDATE_SCHEDULE]);

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
