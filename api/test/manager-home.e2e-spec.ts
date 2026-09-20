import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import { DatabaseModule } from '../src/database/database.module.js';
import {
  customers,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  properties,
  userProfiles,
  visits,
  visitTechnicians,
} from '../src/database/schema.js';
import { resolveDayWindow } from '../src/home/home-day.js';
import { ManagerHomeModule } from '../src/home/manager-home.module.js';
import { ManagerHomeService } from '../src/home/manager-home.service.js';
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
 * `GET /home/manager` over HTTP: who may read it and what the wire carries.
 *
 * The derivation itself — which records carry a condition, and in what order — is asserted
 * separately against the service with a pinned clock, so it does not depend on when the test runs.
 */
describe('manager home endpoint (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Manager Home Org',
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
    const email = uniqueEmail('manager-home');
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
          deviceId: `manager-home-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return {
      userId: user.id,
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  /** A Job of a customer this organization owns, with the snapshot the schema requires. */
  async function createStoredJob(title: string) {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId,
        type: 'COMPANY',
        displayName: 'Manager Home Customer',
      })
      .returning();
    // A Job's address snapshot requires the Property it was taken from (`BR-056`).
    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId,
        addressLine1: '987 Cedar Lane',
        city: 'Montreal',
        province: 'QC',
        postalCode: 'H3A 2T6',
      })
      .returning();
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId: customer.id,
        title,
        propertyId: property.id,
        propertyAddressSnapshot: { addressLine1: '987 Cedar Lane' },
      })
      .returning();
    return job;
  }

  async function createStoredVisit(values: {
    jobId: string;
    status: string;
    scheduledStart: Date;
    scheduledEnd: Date;
    actorMembershipId: string;
  }) {
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId,
        jobId: values.jobId,
        status: values.status,
        scheduledStart: values.scheduledStart,
        scheduledEnd: values.scheduledEnd,
        ...(values.status === 'COMPLETED'
          ? {
              outcomeCode: 'RESOLVED',
              outcomeSummary: 'Work completed.',
              outcomeRecordedAt: values.scheduledEnd,
              outcomeRecordedByMembershipId: values.actorMembershipId,
            }
          : {}),
      })
      .returning();
    return visit;
  }

  it('refuses an unauthenticated read with 401', async () => {
    await request(app.getHttpServer()).get('/home/manager').expect(401);
  });

  it('refuses a caller without customers.view with 403', async () => {
    const session = await signInFor([]);

    await request(app.getHttpServer())
      .get('/home/manager')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  it('answers an authorized caller with the day, the viewer and the day summary', async () => {
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);
    await database.db.insert(userProfiles).values({
      userId: session.userId,
      firstName: 'Sarah',
      lastName: 'Tremblay',
      displayName: 'Sarah Tremblay',
    });
    const day = resolveDayWindow('America/Toronto', new Date());

    // A completed Visit at the local noon of the requested day, so the assertion does not depend on
    // the hour the test runs at.
    const start = new Date(day.start.getTime() + 12 * 3_600_000);
    const job = await createStoredJob('Furnace repair');
    await createStoredVisit({
      jobId: job.id,
      status: 'COMPLETED',
      scheduledStart: start,
      scheduledEnd: new Date(start.getTime() + 3_600_000),
      actorMembershipId: session.membershipId,
    });

    const response = await request(app.getHttpServer())
      .get('/home/manager')
      .query({ timeZone: 'America/Toronto' })
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.viewer.displayName).toBe('Sarah Tremblay');
    expect(response.body.day.timeZone).toBe('America/Toronto');
    expect(response.body.day.start).toBe(day.start.toISOString());
    expect(response.body.day.end).toBe(day.end.toISOString());
    expect(Number.isNaN(Date.parse(response.body.generatedAt))).toBe(false);

    const visit = response.body.visits.find(
      (row: { jobId: string }) => row.jobId === job.id,
    );
    expect(visit.visitStatus).toBe('COMPLETED');
    expect(visit.jobNumber).toBe(job.jobNumber);
    expect(visit.customerName).toBe('Manager Home Customer');
    expect(visit.address.addressLine1).toBe('987 Cedar Lane');
    expect(visit.overdue).toBe(false);

    // The summary and the list describe the same set (`BR-074`): the counts partition it.
    const summary = response.body.today;
    expect(summary.completed + summary.inProgress + summary.upcoming).toBe(
      summary.total,
    );
    expect(summary.completed).toBeGreaterThanOrEqual(1);
    expect(response.body.attention.items).toBeInstanceOf(Array);
    expect(typeof response.body.attention.total).toBe('number');
  });

  it('resolves the day in UTC when the request names no zone', async () => {
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);
    const day = resolveDayWindow('UTC', new Date());

    const response = await request(app.getHttpServer())
      .get('/home/manager')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.day.timeZone).toBe('UTC');
    expect(response.body.day.start).toBe(day.start.toISOString());
  });

  it('accepts the fixed offset a device reports for a zone with no name', async () => {
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get('/home/manager')
      .query({ timeZone: 'GMT+05:30' })
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.day.timeZone).toBe('+05:30');
  });

  it('rejects a zone the server cannot resolve with 400', async () => {
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get('/home/manager')
      .query({ timeZone: 'Mars/Olympus_Mons' })
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(400);

    expect(response.body.code).toBe('VALIDATION_FAILED');
  });
});

/**
 * The manager home derivation against a real PostgreSQL, with a pinned clock and day.
 *
 * These assert the semantics the screen depends on: which records carry an attention condition
 * (`BR-060`, `BR-061`, `BR-072`, `BR-074`), what the day summary counts, and that neither reaches
 * across the tenant boundary (`BR-001`).
 */
describe('manager home derivation (e2e)', () => {
  let database: FoundationTestDatabase;
  let service: ManagerHomeService;

  const now = new Date('2026-09-14T15:00:00.000Z');
  const day = resolveDayWindow('UTC', now);

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const moduleRef = await Test.createTestingModule({
      imports: [DatabaseModule, ManagerHomeModule],
    }).compile();
    service = moduleRef.get(ManagerHomeService);
  });

  afterAll(async () => {
    await database.dispose();
  });

  async function newOrganization() {
    const organization = await createTestOrganization(database.db);
    database.cleanup.trackOrganization(organization.id);
    return organization;
  }

  /** A member of the organization, optionally with a profile so a name resolves (`BR-068`). */
  async function newMembership(
    organizationId: string,
    name?: { first: string; last: string },
  ) {
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, organizationId);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId: role.id })
      .returning();
    if (name !== undefined) {
      await database.db.insert(userProfiles).values({
        userId: user.id,
        firstName: name.first,
        lastName: name.last,
        displayName: `${name.first} ${name.last}`,
      });
    }
    return member;
  }

  async function newCustomer(
    organizationId: string,
    displayName: string,
    options: { deletedByMembershipId?: string } = {},
  ) {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId,
        type: 'COMPANY',
        displayName,
        ...(options.deletedByMembershipId === undefined
          ? {}
          : {
              status: 'INACTIVE',
              deletedAt: now,
              deletedByMembershipId: options.deletedByMembershipId,
            }),
      })
      .returning();
    return customer;
  }

  async function newJob(values: {
    organizationId: string;
    customerId: string;
    jobNumber: number;
    status: string;
    title?: string;
    withPropertySnapshot?: boolean;
  }) {
    // A Job's preserved address snapshot requires the Property it was taken from (`BR-056`), which
    // the schema enforces, so the fixture creates one when it asks for a snapshot.
    const property =
      values.withPropertySnapshot === true
        ? await newProperty(values.organizationId, '1 Job Street')
        : null;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId: values.organizationId,
        jobNumber: values.jobNumber,
        customerId: values.customerId,
        title: values.title ?? `Job ${values.jobNumber}`,
        status: values.status,
        propertyId: property?.id ?? null,
        propertyAddressSnapshot:
          property === null ? null : { addressLine1: '1 Job Street' },
      })
      .returning();
    return job;
  }

  async function newVisit(values: {
    organizationId: string;
    jobId: string;
    status: string;
    scheduledStart?: Date;
    scheduledEnd?: Date;
    location?: Record<string, string>;
    actorMembershipId: string;
  }) {
    // `BR-072`: a Visit cannot be `SCHEDULED` unless the Job has a Property, and the schema enforces
    // it — so the fixture creates the Property such a Visit requires.
    const property =
      values.status === 'SCHEDULED' || values.location !== undefined
        ? await newProperty(values.organizationId, '1 Visit Way')
        : null;

    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId: values.organizationId,
        jobId: values.jobId,
        status: values.status,
        scheduledStart: values.scheduledStart ?? null,
        scheduledEnd: values.scheduledEnd ?? null,
        propertyId: property?.id ?? null,
        locationAddressSnapshot:
          property === null
            ? null
            : (values.location ?? { addressLine1: '1 Visit Way' }),
        ...(values.status === 'COMPLETED'
          ? {
              outcomeCode: 'RESOLVED',
              outcomeSummary: 'Work completed.',
              outcomeRecordedAt: values.scheduledEnd ?? now,
              outcomeRecordedByMembershipId: values.actorMembershipId,
            }
          : {}),
      })
      .returning();
    return visit;
  }

  async function newProperty(organizationId: string, addressLine1: string) {
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

  function scopeOf(organizationId: string) {
    return { organizationId };
  }

  it('reports a Visit still SCHEDULED after its window as overdue, with its Job and time', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'Overdue Co');
    const job = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 1,
      status: 'SCHEDULED',
      title: 'Water heater replacement',
    });
    const visit = await newVisit({
      organizationId: organization.id,
      jobId: job.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(now.getTime() - 3 * 3_600_000),
      scheduledEnd: new Date(now.getTime() - 2 * 3_600_000),
      actorMembershipId: member.id,
    });

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    expect(read.attention).toHaveLength(1);
    expect(read.attention[0]).toMatchObject({
      kind: 'VISIT_OVERDUE',
      visitId: visit.id,
      jobId: job.id,
      jobTitle: 'Water heater replacement',
      customerName: 'Overdue Co',
    });
    expect(read.attention[0]?.scheduledStart?.toISOString()).toBe(
      new Date(now.getTime() - 3 * 3_600_000).toISOString(),
    );
  });

  it('does not report a Visit that has progressed past SCHEDULED', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'Working Co');
    const job = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 1,
      status: 'IN_PROGRESS',
    });
    await newVisit({
      organizationId: organization.id,
      jobId: job.id,
      status: 'IN_PROGRESS',
      scheduledStart: new Date(now.getTime() - 3 * 3_600_000),
      scheduledEnd: new Date(now.getTime() - 2 * 3_600_000),
      actorMembershipId: member.id,
    });

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    expect(read.attention).toEqual([]);
  });

  it('reports a NEW or IN_PROGRESS Job with no active Visit as needing scheduling', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'Planning Co');

    // No Visit at all (`BR-051`).
    const bare = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 1,
      status: 'NEW',
    });
    // A DRAFT Visit is not yet scheduled work, so the Job still needs scheduling (`BR-060`).
    const withDraft = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 2,
      status: 'IN_PROGRESS',
    });
    await newVisit({
      organizationId: organization.id,
      jobId: withDraft.id,
      status: 'DRAFT',
      actorMembershipId: member.id,
    });
    // A scheduled Visit means the Job is scheduled.
    const scheduled = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 3,
      status: 'SCHEDULED',
    });
    await newVisit({
      organizationId: organization.id,
      jobId: scheduled.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(now.getTime() + 3_600_000),
      scheduledEnd: new Date(now.getTime() + 7_200_000),
      actorMembershipId: member.id,
    });

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    expect(read.attention.map((item) => item.jobId)).toEqual([
      bare.id,
      withDraft.id,
    ]);
    expect(
      read.attention.every((item) => item.kind === 'JOB_NEEDS_SCHEDULING'),
    ).toBe(true);
    expect(read.attention[0]?.visitId).toBeNull();
    expect(read.attention[0]?.scheduledStart).toBeNull();
  });

  it('reports a Job awaiting office review (`BR-061`, `BR-062`)', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'Review Co');
    const job = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 1,
      status: 'PENDING_REVIEW',
    });

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    expect(read.attention).toEqual([
      expect.objectContaining({ kind: 'JOB_PENDING_REVIEW', jobId: job.id }),
    ]);
  });

  it("presents today's Visits operationally, with the Lead first and the located address", async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const lead = await newMembership(organization.id, {
      first: 'Mike',
      last: 'Johnson',
    });
    const helper = await newMembership(organization.id, {
      first: 'Sarah',
      last: 'Kim',
    });
    const customer = await newCustomer(organization.id, 'Schedule Co');

    const completedJob = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 1,
      status: 'COMPLETED',
      title: 'Boiler inspection',
      withPropertySnapshot: true,
    });
    const overdueJob = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 2,
      status: 'SCHEDULED',
      title: 'AC repair',
      withPropertySnapshot: true,
    });
    const activeJob = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 3,
      status: 'IN_PROGRESS',
      title: 'Furnace repair',
      withPropertySnapshot: true,
    });

    const completed = await newVisit({
      organizationId: organization.id,
      jobId: completedJob.id,
      status: 'COMPLETED',
      scheduledStart: new Date(day.start.getTime() + 8 * 3_600_000),
      scheduledEnd: new Date(day.start.getTime() + 9 * 3_600_000),
      actorMembershipId: member.id,
    });
    // Overdue: its window ended before `now`, and it is still SCHEDULED.
    await newVisit({
      organizationId: organization.id,
      jobId: overdueJob.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(now.getTime() - 4 * 3_600_000),
      scheduledEnd: new Date(now.getTime() - 3 * 3_600_000),
      location: { addressLine1: '987 Cedar Lane' },
      actorMembershipId: member.id,
    });
    const active = await newVisit({
      organizationId: organization.id,
      jobId: activeJob.id,
      status: 'ON_SITE',
      scheduledStart: new Date(day.start.getTime() + 14 * 3_600_000),
      scheduledEnd: new Date(day.start.getTime() + 16 * 3_600_000),
      actorMembershipId: member.id,
    });
    // A Visit outside the day is not today's work.
    await newVisit({
      organizationId: organization.id,
      jobId: completedJob.id,
      status: 'COMPLETED',
      scheduledStart: new Date(day.end.getTime() + 24 * 3_600_000),
      scheduledEnd: new Date(day.end.getTime() + 25 * 3_600_000),
      actorMembershipId: member.id,
    });

    await database.db.insert(visitTechnicians).values([
      {
        organizationId: organization.id,
        visitId: active.id,
        technicianMembershipId: helper.id,
        roleCode: 'TECHNICIAN',
      },
      {
        organizationId: organization.id,
        visitId: active.id,
        technicianMembershipId: lead.id,
        roleCode: 'LEAD',
      },
    ]);

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    // Overdue first, then work under way, then completed work.
    expect(read.visits).toHaveLength(3);
    expect(read.visits[0]?.overdue).toBe(true);
    expect(read.visits[1]?.visitId).toBe(active.id);
    expect(read.visits[2]?.visitId).toBe(completed.id);
    expect(read.visits[0]?.address?.addressLine1).toBe('987 Cedar Lane');
    // A Visit with no location of its own falls back to the Job's snapshot (`BR-056`).
    expect(read.visits[1]?.address?.addressLine1).toBe('1 Job Street');
    expect(
      read.visits[1]?.technicians.map((technician) => technician.name),
    ).toEqual(['Mike Johnson', 'Sarah Kim']);
    expect(read.visits[1]?.technicians[0]?.roleCode).toBe('LEAD');

    expect(read.today).toEqual({
      total: 3,
      completed: 1,
      inProgress: 1,
      upcoming: 1,
    });
  });

  it("does not present another organization's work", async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const other = await newOrganization();
    const otherMember = await newMembership(other.id);
    const otherCustomer = await newCustomer(other.id, 'Other Co');
    const otherJob = await newJob({
      organizationId: other.id,
      customerId: otherCustomer.id,
      jobNumber: 1,
      status: 'PENDING_REVIEW',
    });
    await newVisit({
      organizationId: other.id,
      jobId: otherJob.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(day.start.getTime() + 8 * 3_600_000),
      scheduledEnd: new Date(day.start.getTime() + 9 * 3_600_000),
      actorMembershipId: otherMember.id,
    });

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    expect(read.attention).toEqual([]);
    expect(read.visits).toEqual([]);
    expect(read.today.total).toBe(0);
  });

  it('does not present work for a customer the organization deleted (`BR-023`)', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const deletedCustomer = await newCustomer(organization.id, 'Deleted Co', {
      deletedByMembershipId: member.id,
    });
    const deletedJob = await newJob({
      organizationId: organization.id,
      customerId: deletedCustomer.id,
      jobNumber: 1,
      status: 'PENDING_REVIEW',
    });
    await newVisit({
      organizationId: organization.id,
      jobId: deletedJob.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(day.start.getTime() + 8 * 3_600_000),
      scheduledEnd: new Date(day.start.getTime() + 9 * 3_600_000),
      actorMembershipId: member.id,
    });

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    expect(read.attention).toEqual([]);
    expect(read.visits).toEqual([]);
  });

  it('orders attention so what has gone wrong comes before what is waiting to be planned', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'Mixed Co');

    await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 2,
      status: 'PENDING_REVIEW',
    });
    await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 3,
      status: 'NEW',
    });
    const overdueJob = await newJob({
      organizationId: organization.id,
      customerId: customer.id,
      jobNumber: 1,
      status: 'SCHEDULED',
    });
    await newVisit({
      organizationId: organization.id,
      jobId: overdueJob.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(now.getTime() - 4 * 3_600_000),
      scheduledEnd: new Date(now.getTime() - 3 * 3_600_000),
      actorMembershipId: member.id,
    });

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    expect(read.attention.map((item) => item.kind)).toEqual([
      'VISIT_OVERDUE',
      'JOB_PENDING_REVIEW',
      'JOB_NEEDS_SCHEDULING',
    ]);
    expect(read.attentionTotal).toBe(3);
  });

  it('reports the signed-in member as the viewer and not a colleague', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id, {
      first: 'Sarah',
      last: 'Tremblay',
    });
    const colleague = await newMembership(organization.id, {
      first: 'Mike',
      last: 'Johnson',
    });

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    expect(read.viewerDisplayName).toBe('Sarah Tremblay');
    expect(colleague.userId).not.toBe(member.userId);
  });

  it('reports no viewer name when the member has no profile', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);

    const read = await service.readManagerHome(
      scopeOf(organization.id),
      member.userId,
      day,
      now,
    );

    expect(read.viewerDisplayName).toBeNull();
  });
});
