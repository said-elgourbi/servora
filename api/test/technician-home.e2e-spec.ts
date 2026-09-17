import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_PERMISSIONS,
  VISIT_PERMISSIONS,
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
import { TechnicianHomeModule } from '../src/home/technician-home.module.js';
import { TechnicianHomeService } from '../src/home/technician-home.service.js';
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
 * `GET /home/technician` over HTTP: who may read it and what the wire carries.
 *
 * The read is the technician's own screen (`BR-010`, `BR-012`; `ADR-019` D6), so the assertions that
 * matter most are the ones about **whose** work it answers: only the caller's own current
 * assignments, never a colleague's, and never another organization's (`BR-001`, `BR-007`).
 */
describe('technician home endpoint (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Technician Home Org',
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
    const email = uniqueEmail('technician-home');
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
          deviceId: `technician-home-device-${deviceSequence}`,
        },
      })
      .expect(200);

    return {
      userId: user.id,
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  /**
   * A field session: `VISIT_VIEW_ASSIGNED`, the capability `BR-009` names for a technician's own
   * assigned work (`ADR-019` D6). It is granted directly rather than through a role name, because
   * `BR-006` makes effective permissions the role's plus the member's own.
   */
  async function signInTechnician() {
    return signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
  }

  /** A member of the organization, used as an assignee and as a record's actor. */
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

  async function newCustomer(displayName = 'Technician Home Customer') {
    const [customer] = await database.db
      .insert(customers)
      .values({ organizationId, type: 'COMPANY', displayName })
      .returning();
    return customer;
  }

  async function newProperty(addressLine1 = '987 Cedar Lane') {
    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId,
        addressLine1,
        city: 'Montreal',
        province: 'QC',
        postalCode: 'H3A 2T6',
      })
      .returning();
    return property;
  }

  async function newJob(values: {
    customerId: string;
    propertyId: string;
    title: string;
    status?: string;
  }) {
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId: values.customerId,
        title: values.title,
        status: values.status ?? 'SCHEDULED',
        propertyId: values.propertyId,
        propertyAddressSnapshot: { addressLine1: '987 Cedar Lane' },
      })
      .returning();
    return job;
  }

  /** A scheduled Visit, with the outcome columns a completed one requires (`BR-077`). */
  async function newVisit(values: {
    jobId: string;
    propertyId: string;
    status: string;
    scheduledStart: Date;
    scheduledEnd: Date;
  }) {
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId,
        jobId: values.jobId,
        propertyId: values.propertyId,
        locationAddressSnapshot: { addressLine1: '987 Cedar Lane' },
        status: values.status,
        scheduledStart: values.scheduledStart,
        scheduledEnd: values.scheduledEnd,
        ...(values.status === 'COMPLETED'
          ? {
              outcomeCode: 'RESOLVED',
              outcomeSummary: 'Recorded before this test.',
              outcomeRecordedAt: values.scheduledEnd,
              outcomeRecordedByMembershipId: null,
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

  /** A visite-scheduled Job with a Visit at [start], crewed by [crew]. */
  async function jobWithVisitAt(
    start: Date,
    crew: readonly {
      membershipId: string;
      roleCode: 'LEAD' | 'TECHNICIAN';
    }[],
    title = 'Furnace repair',
  ) {
    const customer = await newCustomer();
    const property = await newProperty();
    const job = await newJob({ customerId: customer.id, propertyId: property.id, title });
    const visit = await newVisit({
      jobId: job.id,
      propertyId: property.id,
      status: 'SCHEDULED',
      scheduledStart: start,
      scheduledEnd: new Date(start.getTime() + 3_600_000),
    });
    for (const member of crew) {
      await assign({
        visitId: visit.id,
        technicianMembershipId: member.membershipId,
        roleCode: member.roleCode,
      });
    }
    return { customer, property, job, visit };
  }

  it('refuses an unauthenticated read with 401', async () => {
    await request(app.getHttpServer()).get('/home/technician').expect(401);
  });

  it('refuses a caller without VISIT_VIEW_ASSIGNED with 403', async () => {
    // The office reads `GET /home/manager`; every row here belongs to one technician, so the office
    // capability does not admit a caller to it (`BR-010`, `BR-012`).
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    await request(app.getHttpServer())
      .get('/home/technician')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  it('rejects a zone the server cannot resolve with 400', async () => {
    const session = await signInTechnician();

    const response = await request(app.getHttpServer())
      .get('/home/technician')
      .query({ timeZone: 'Mars/Olympus_Mons' })
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(400);

    expect(response.body.code).toBe('VALIDATION_FAILED');
  });

  it("answers the caller's own day, next Visit and upcoming work", async () => {
    const session = await signInTechnician();
    await database.db.insert(userProfiles).values({
      userId: session.userId,
      firstName: 'Mike',
      lastName: 'Johnson',
      displayName: 'Mike Johnson',
    });
    const colleague = await newMembership();
    const day = resolveDayWindow('UTC', new Date());

    // Two of the caller's own Visits, and one that belongs to a colleague only: the colleague's
    // Visit sits between them so it cannot be excluded by a boundary.
    const morning = await jobWithVisitAt(
      new Date(day.start.getTime() + 9 * 3_600_000),
      [
        { membershipId: session.membershipId, roleCode: 'LEAD' },
        { membershipId: colleague.id, roleCode: 'TECHNICIAN' },
      ],
      'Morning furnace repair',
    );
    await jobWithVisitAt(
      new Date(day.start.getTime() + 11 * 3_600_000),
      [{ membershipId: colleague.id, roleCode: 'LEAD' }],
      'Colleague boiler service',
    );
    const afternoon = await jobWithVisitAt(
      new Date(day.start.getTime() + 13 * 3_600_000),
      [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
      'Afternoon AC inspection',
    );
    const tomorrow = await jobWithVisitAt(
      new Date(day.end.getTime() + 9 * 3_600_000),
      [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
      'Tomorrow water heater service',
    );

    const response = await request(app.getHttpServer())
      .get('/home/technician')
      .query({ timeZone: 'UTC' })
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.viewer.displayName).toBe('Mike Johnson');
    expect(response.body.day.timeZone).toBe('UTC');
    expect(response.body.day.start).toBe(day.start.toISOString());
    expect(response.body.day.end).toBe(day.end.toISOString());
    expect(Number.isNaN(Date.parse(response.body.generatedAt))).toBe(false);

    // Only the caller's own Visits, in time order.
    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).toEqual([morning.visit.id, afternoon.visit.id]);
    const first = response.body.visits[0];
    expect(first.visitStatus).toBe('SCHEDULED');
    expect(first.jobNumber).toBe(morning.job.jobNumber);
    expect(first.jobTitle).toBe('Morning furnace repair');
    expect(first.customerName).toBe('Technician Home Customer');
    expect(first.address.addressLine1).toBe('987 Cedar Lane');
    // The overdue condition and the attention list are the API's own answers, decided from the
    // schedule and the **server** clock, so they are asserted against those same two facts rather
    // than against a fixed expectation that would depend on the hour the suite runs at (`BR-001`).
    const expectedOverdue = [morning.visit, afternoon.visit].filter(
      (visit) => visit.scheduledEnd.getTime() < Date.now(),
    );
    expect(first.overdue).toBe(
      morning.visit.scheduledEnd.getTime() < Date.now(),
    );
    // The whole crew travels, Lead first (`BR-068`).
    expect(
      first.technicians.map(
        (technician: { roleCode: string }) => technician.roleCode,
      ),
    ).toEqual(['LEAD', 'TECHNICIAN']);

    // Nothing has started, so the next Visit is the nearest one.
    expect(response.body.nextVisit.visitId).toBe(morning.visit.id);
    // After today is a preview, and its total keeps the capped list honest.
    expect(
      response.body.upcoming.map((row: { visitId: string }) => row.visitId),
    ).toEqual([tomorrow.visit.id]);
    expect(response.body.upcomingTotal).toBe(1);
    // Only the caller's own overdue work can be reported, and nothing later than the clock.
    expect(
      response.body.attention.items
        .map((item: { visitId: string }) => item.visitId)
        .sort(),
    ).toEqual(expectedOverdue.map((visit) => visit.id).sort());
    expect(response.body.attention.total).toBe(expectedOverdue.length);
  });

  it("still scopes the read to the caller's own work when they hold the office capability", async () => {
    const session = await signInFor([
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
      CUSTOMER_PERMISSIONS.VIEW,
    ]);
    const colleague = await newMembership();
    const day = resolveDayWindow('UTC', new Date());
    const own = await jobWithVisitAt(
      new Date(day.start.getTime() + 10 * 3_600_000),
      [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
      'Own work',
    );
    await jobWithVisitAt(
      new Date(day.start.getTime() + 11 * 3_600_000),
      [{ membershipId: colleague.id, roleCode: 'LEAD' }],
      'Someone else work',
    );

    const response = await request(app.getHttpServer())
      .get('/home/technician')
      .query({ timeZone: 'UTC' })
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    // The scope follows the caller's own assignments, never their permissions (`ADR-019` D2).
    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).toEqual([own.visit.id]);
    expect(response.body.nextVisit.visitId).toBe(own.visit.id);
  });

  it('answers a technician with no assignment an empty screen, not other work', async () => {
    const session = await signInTechnician();

    const response = await request(app.getHttpServer())
      .get('/home/technician')
      .query({ timeZone: 'UTC' })
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    // The organization holds Visits from the tests above; none of them is this caller's.
    expect(response.body.nextVisit).toBeNull();
    expect(response.body.visits).toEqual([]);
    expect(response.body.upcoming).toEqual([]);
    expect(response.body.upcomingTotal).toBe(0);
    expect(response.body.attention).toEqual({ total: 0, items: [] });
  });

  it('accepts the fixed offset a device reports for a zone with no name', async () => {
    const session = await signInTechnician();

    const response = await request(app.getHttpServer())
      .get('/home/technician')
      .query({ timeZone: 'GMT+05:30' })
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.day.timeZone).toBe('+05:30');
  });
});


/**
 * The technician home derivation against a real PostgreSQL, with a pinned clock and day.
 *
 * These assert the semantics the screen depends on: which of the caller's own Visits are today's,
 * which one is next, what is previewed for after today, which carry an attention condition, and that
 * none of it reaches across the assignment or tenant boundary (`BR-001`, `BR-005`, `BR-012`,
 * `BR-062`, `BR-072`, `BR-074`).
 */
describe('technician home derivation (e2e)', () => {
  let database: FoundationTestDatabase;
  let service: TechnicianHomeService;

  const now = new Date('2026-09-14T15:00:00.000Z');
  const day = resolveDayWindow('UTC', now);
  let derivationJobNumber = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const moduleRef = await Test.createTestingModule({
      imports: [DatabaseModule, TechnicianHomeModule],
    }).compile();
    service = moduleRef.get(TechnicianHomeService);
  });

  afterAll(async () => {
    await database.dispose();
  });

  async function newOrganization() {
    const organization = await createTestOrganization(database.db);
    database.cleanup.trackOrganization(organization.id);
    return organization;
  }

  async function newMembership(organizationId: string) {
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, organizationId);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId: role.id })
      .returning();
    return { member, user };
  }

  /**
   * One assigned Visit of [organizationId], scheduled at [start], with everything a row is read
   * from. The crew is what makes it visible to a technician at all (`ADR-019` D2).
   */
  async function seedVisit(values: {
    organizationId: string;
    crew: readonly string[];
    status: string;
    start: Date;
    title?: string;
    customerDeletedAt?: Date;
  }) {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId: values.organizationId,
        type: 'COMPANY',
        displayName: 'Derivation Customer',
        ...(values.customerDeletedAt === undefined
          ? {}
          : {
              status: 'INACTIVE',
              deletedAt: values.customerDeletedAt,
              // The schema requires the member who deleted it (`BR-023`).
              deletedByMembershipId: values.crew[0],
            }),
      })
      .returning();
    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId: values.organizationId,
        addressLine1: '5 Derivation Way',
        city: 'Montreal',
        province: 'QC',
        postalCode: 'H3A 2T6',
      })
      .returning();
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId: values.organizationId,
        jobNumber: (derivationJobNumber += 1),
        customerId: customer.id,
        title: values.title ?? 'Derivation job',
        status: 'SCHEDULED',
        propertyId: property.id,
        propertyAddressSnapshot: { addressLine1: '5 Derivation Way' },
      })
      .returning();
    const end = new Date(values.start.getTime() + 3_600_000);
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId: values.organizationId,
        jobId: job.id,
        propertyId: property.id,
        locationAddressSnapshot: { addressLine1: '5 Derivation Way' },
        status: values.status,
        scheduledStart: values.start,
        scheduledEnd: end,
        ...(values.status === 'COMPLETED'
          ? {
              outcomeCode: 'RESOLVED',
              outcomeSummary: 'Work completed.',
              outcomeRecordedAt: end,
              outcomeRecordedByMembershipId: values.crew[0],
            }
          : {}),
      })
      .returning();

    for (const [index, membershipId] of values.crew.entries()) {
      await database.db.insert(visitTechnicians).values({
        organizationId: values.organizationId,
        visitId: visit.id,
        technicianMembershipId: membershipId,
        roleCode: index === 0 ? 'LEAD' : 'TECHNICIAN',
      });
    }
    return { customer, property, job, visit };
  }

  /** An hour of the pinned day, so an assertion never depends on when the test runs. */
  function at(hour: number): Date {
    return new Date(day.start.getTime() + hour * 3_600_000);
  }


  it('reads the day in time order and leaves out the attempts that did not happen', async () => {
    const organization = await newOrganization();
    const { member, user } = await newMembership(organization.id);
    const scope = { organizationId: organization.id };

    const completed = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'COMPLETED',
      start: at(8),
    });
    const canceled = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'CANCELED',
      start: at(10),
    });
    const scheduled = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'SCHEDULED',
      start: at(12),
    });
    const noShow = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'NO_SHOW',
      start: at(14),
    });
    const later = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'SCHEDULED',
      start: at(18),
    });

    const read = await service.readTechnicianHome(
      scope,
      user.id,
      member.id,
      day,
      now,
    );

    // Chronological, and a canceled or no-show attempt is not work of the day (`BR-074`). What the
    // day has produced so far stays: it is the technician's own record of it.
    expect(read.visits.map((visit) => visit.visitId)).toEqual([
      completed.visit.id,
      scheduled.visit.id,
      later.visit.id,
    ]);
    expect(read.visits.some((visit) => visit.visitId === canceled.visit.id)).toBe(
      false,
    );
    expect(read.visits.some((visit) => visit.visitId === noShow.visit.id)).toBe(
      false,
    );
  });

  it('chooses the Visit already started as the next one', async () => {
    const organization = await newOrganization();
    const { member, user } = await newMembership(organization.id);
    const scope = { organizationId: organization.id };

    const notStarted = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'SCHEDULED',
      start: at(11),
    });
    const underWay = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'IN_PROGRESS',
      start: at(13),
    });

    const read = await service.readTechnicianHome(
      scope,
      user.id,
      member.id,
      day,
      now,
    );

    expect(read.nextVisit?.visitId).toBe(underWay.visit.id);
    expect(read.nextVisit?.visitId).not.toBe(notStarted.visit.id);
  });

  it('reports an overdue Visit to the technician and offers it as the next one', async () => {
    const organization = await newOrganization();
    const { member, user } = await newMembership(organization.id);
    const scope = { organizationId: organization.id };

    // Yesterday, still SCHEDULED: the attempt never happened and nobody has moved it on.
    const overdue = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'SCHEDULED',
      start: new Date(day.start.getTime() - 15 * 3_600_000),
    });
    const laterToday = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'SCHEDULED',
      start: at(18),
    });

    const read = await service.readTechnicianHome(
      scope,
      user.id,
      member.id,
      day,
      now,
    );

    expect(read.attentionTotal).toBe(1);
    expect(read.attention[0]?.kind).toBe('VISIT_OVERDUE');
    expect(read.attention[0]?.visitId).toBe(overdue.visit.id);
    // The overdue condition is derived from the schedule and the server clock, never stored.
    expect(read.nextVisit?.visitId).toBe(overdue.visit.id);
    // It is not part of today, so the day list and the condition describe different sets.
    expect(read.visits.map((visit) => visit.visitId)).toEqual([
      laterToday.visit.id,
    ]);
  });


  it('previews what comes after today and reports how many there are', async () => {
    const organization = await newOrganization();
    const { member, user } = await newMembership(organization.id);
    const scope = { organizationId: organization.id };

    for (let index = 0; index < 7; index += 1) {
      await seedVisit({
        organizationId: organization.id,
        crew: [member.id],
        status: 'SCHEDULED',
        start: new Date(day.end.getTime() + (index + 1) * 3_600_000),
      });
    }

    const read = await service.readTechnicianHome(
      scope,
      user.id,
      member.id,
      day,
      now,
    );

    // The next Visit is the nearest one even though it is not today's, and the preview is capped
    // while `upcomingTotal` keeps the caller's whole workload honest.
    expect(read.visits).toEqual([]);
    expect(read.nextVisit).not.toBeNull();
    expect(read.upcoming).toHaveLength(5);
    expect(read.upcomingTotal).toBe(7);
    expect(read.upcoming[0]!.scheduledStart.getTime()).toBeLessThan(
      read.upcoming[1]!.scheduledStart.getTime(),
    );
  });

  it('never reports another technician, another tenant or a deleted customer', async () => {
    const organization = await newOrganization();
    const otherOrganization = await newOrganization();
    const { member, user } = await newMembership(organization.id);
    const colleague = await newMembership(organization.id);
    const outsider = await newMembership(otherOrganization.id);
    const scope = { organizationId: organization.id };

    const own = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'SCHEDULED',
      start: at(9),
    });
    const colleagueOnly = await seedVisit({
      organizationId: organization.id,
      crew: [colleague.member.id],
      status: 'SCHEDULED',
      start: at(10),
    });
    const otherTenant = await seedVisit({
      organizationId: otherOrganization.id,
      crew: [outsider.member.id],
      status: 'SCHEDULED',
      start: at(11),
    });
    const deletedCustomer = await seedVisit({
      organizationId: organization.id,
      crew: [member.id],
      status: 'SCHEDULED',
      start: at(12),
      customerDeletedAt: now,
    });

    const read = await service.readTechnicianHome(
      scope,
      user.id,
      member.id,
      day,
      now,
    );

    const ids = [
      ...read.visits.map((visit) => visit.visitId),
      ...(read.nextVisit === null ? [] : [read.nextVisit.visitId]),
      ...read.upcoming.map((visit) => visit.visitId),
      ...read.attention.map((item) => item.visitId),
    ];
    expect(ids).toContain(own.visit.id);
    expect(ids).not.toContain(colleagueOnly.visit.id);
    expect(ids).not.toContain(otherTenant.visit.id);
    // `BR-023` hides a deleted customer's Jobs by default, as the manager home does.
    expect(ids).not.toContain(deletedCustomer.visit.id);
  });

  it('leaves out a draft Visit the office has not scheduled yet', async () => {
    const organization = await newOrganization();
    const { member, user } = await newMembership(organization.id);
    const scope = { organizationId: organization.id };

    // A DRAFT Visit with no schedule carries no time to be "next" at and is not work of any day
    // (`BR-060`, `BR-072`).
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId: organization.id,
        type: 'COMPANY',
        displayName: 'Unscheduled Customer',
      })
      .returning();
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId: organization.id,
        jobNumber: 999_001,
        customerId: customer.id,
        title: 'Unscheduled job',
        status: 'NEW',
      })
      .returning();
    const [draft] = await database.db
      .insert(visits)
      .values({
        organizationId: organization.id,
        jobId: job.id,
        status: 'DRAFT',
      })
      .returning();
    await database.db.insert(visitTechnicians).values({
      organizationId: organization.id,
      visitId: draft.id,
      technicianMembershipId: member.id,
      roleCode: 'LEAD',
    });

    const read = await service.readTechnicianHome(
      scope,
      user.id,
      member.id,
      day,
      now,
    );

    expect(read.nextVisit).toBeNull();
    expect(read.visits).toEqual([]);
    expect(read.upcoming).toEqual([]);
    expect(read.attentionTotal).toBe(0);
  });
});

