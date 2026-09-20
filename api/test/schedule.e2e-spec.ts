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
 * `GET /schedule` over HTTP: who may read it, and what one local day carries.
 *
 * The read is the manager's schedule (`BR-072`, `BR-074`), so the assertions that matter are the
 * ones about **which** Visits a day holds — the day's own window, the statuses a day excludes, the
 * technician filter and the unassigned lane — and about the boundary between organizations
 * (`BR-001`, `BR-007`).
 */
describe('schedule endpoint (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;
  /** The member the fixtures record their own events as, created on first use. */
  let fixtureActor: { membershipId: string } | null = null;
  /** The default Technician role of the suite's organization, created on first use (`BR-003`). */
  let technicianRole: { id: string } | null = null;

  /** A local date whose day is entirely in the past, so a `SCHEDULED` Visit on it is overdue. */
  const PAST_DATE = '2020-01-15';

  /** A local date far in the future, so nothing on it can be overdue. */
  const FUTURE_DATE = '2099-06-01';

  /**
   * A past local date the multi-technician filter test owns.
   *
   * It is deliberately its own date: the suite shares one organization, so a day no other test
   * schedules on is what makes "the union of these technicians" an exact answer rather than a
   * `toContain` that would hide an over-match.
   */
  const FILTER_DATE = '2020-02-15';

  /**
   * The local dates the field-scope tests own.
   *
   * They are their own dates for the same reason [FILTER_DATE] is: the suite shares one
   * organization, and a field caller reads their own work, so a day no other test schedules on is
   * what makes "exactly my Visit" an exact answer rather than a `toContain` that hides an over-match.
   */
  const FIELD_DATE = '2021-03-10';

  /** The local date the field caller's own-membership filter test owns. */
  const FIELD_FILTER_DATE = '2021-03-11';

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Schedule Org',
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
    const email = uniqueEmail('schedule');
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
          deviceId: `schedule-device-${deviceSequence}`,
        },
      })
      .expect(200);

    return {
      userId: user.id,
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  /** An office session: the capability the schedule read is guarded by. */
  async function signInManager() {
    return signInFor([CUSTOMER_PERMISSIONS.VIEW]);
  }

  /** A field session: the technician capability, which does not open the schedule. */
  async function signInTechnician() {
    return signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
  }

  /** A member of the organization holding the default Technician role (`BR-003`). */
  async function newTechnicianMember(displayName = 'Mike Johnson') {
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    await database.db.insert(userProfiles).values({
      userId: user.id,
      firstName: displayName.split(' ')[0] ?? displayName,
      lastName: displayName.split(' ')[1] ?? 'Technician',
      displayName,
    });
    // One organization has one role per system code, so the default Technician role is created once
    // and every technician fixture joins it (`BR-003`).
    technicianRole ??= await createTestOrganizationRole(
      database.db,
      organizationId,
      { systemCode: 'TECHNICIAN' },
    );
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId: technicianRole.id })
      .returning();
    return member;
  }

  async function newCustomer(displayName = 'Schedule Customer') {
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

  async function newJob(input: {
    customerId: string;
    title: string;
    /** The Job's own preserved location (`BR-056`); the schema requires the pair together. */
    propertyAddress?: Record<string, string> | null;
  }) {
    const property =
      input.propertyAddress == null ? null : await newProperty();
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId: input.customerId,
        title: input.title,
        propertyId: property?.id ?? null,
        propertyAddressSnapshot: input.propertyAddress ?? null,
      })
      .returning();
    return job;
  }

  /** A Visit on the fixture's own terms: status, optional schedule, optional location. */
  async function newVisit(input: {
    jobId: string;
    status: string;
    scheduledStart?: Date | null;
    scheduledEnd?: Date | null;
    /** The Visit's own preserved location; `null` for an attempt that has none (`BR-056`). */
    location: Record<string, string> | null;
  }) {
    // `BR-072`: a `SCHEDULED` Visit must carry an operational location, and the schema enforces it —
    // so such a Visit always has its own snapshot, and only another status can lack one.
    const property = input.location === null ? null : await newProperty('1 Visit Way');

    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId,
        jobId: input.jobId,
        status: input.status,
        scheduledStart: input.scheduledStart ?? null,
        scheduledEnd: input.scheduledEnd ?? null,
        propertyId: property?.id ?? null,
        locationAddressSnapshot: input.location,
        ...(input.status === 'COMPLETED'
          ? {
              outcomeCode: 'RESOLVED',
              outcomeSummary: 'Work completed.',
              outcomeRecordedAt: input.scheduledEnd ?? new Date(),
              outcomeRecordedByMembershipId: await fixtureActorMembershipId(),
            }
          : {}),
      })
      .returning();
    return visit;
  }

  async function assign(
    visitId: string,
    technicianMembershipId: string,
    roleCode: 'LEAD' | 'TECHNICIAN',
  ) {
    await database.db.insert(visitTechnicians).values({
      organizationId,
      visitId,
      technicianMembershipId,
      roleCode,
    });
  }

  /** One Visit for a Job of its own, with the customer it needs. */
  async function newFixtureVisit(input: {
    title: string;
    status?: string;
    scheduledStart?: Date | null;
    crew?: readonly {
      membershipId: string;
      roleCode: 'LEAD' | 'TECHNICIAN';
    }[];
    customerName?: string;
    /**
     * The Job's own preserved address, when the fixture needs the Job's snapshot to be told apart
     * from the Visit's (`BR-056`, `BR-057`).
     */
    jobAddress?: Record<string, string>;
  }) {
    const status = input.status ?? 'SCHEDULED';
    const customer = await newCustomer(input.customerName);
    const job = await newJob({
      customerId: customer.id,
      title: input.title,
      propertyAddress: input.jobAddress ?? null,
    });
    const start = input.scheduledStart ?? null;
    // A `SCHEDULED` Visit has its own location by construction (the schema requires it); an attempt
    // in another status carries none, which is how a test asks for the Job's snapshot as the
    // fallback (`BR-056`, `BR-057`).
    const location =
      status === 'SCHEDULED' ? { addressLine1: '1 Visit Way' } : null;
    const visit = await newVisit({
      jobId: job.id,
      status,
      scheduledStart: start,
      scheduledEnd:
        start === null ? null : new Date(start.getTime() + 60 * 60 * 1000),
      location,
    });
    for (const technician of input.crew ?? []) {
      await assign(visit.id, technician.membershipId, technician.roleCode);
    }
    return { customer, job, visit };
  }

  /**
   * A membership the fixtures record their own events as.
   *
   * A `COMPLETED` Visit has to name who recorded its outcome (`BR-077`), so the fixtures need one
   * real member; signing in once keeps the suite from creating an account per fixture.
   */
  async function fixtureActorMembershipId() {
    fixtureActor ??= await signInManager();
    return fixtureActor.membershipId;
  }


  it('refuses an unauthenticated read with 401', async () => {
    await request(app.getHttpServer()).get('/schedule').expect(401);
  });

  it('refuses a caller holding neither capability with 403', async () => {
    // The route admits the office capability or the field one. A member holding neither cannot read
    // a schedule at all (`BR-006`, `BR-009`).
    const session = await signInFor(['evidence.view']);

    await request(app.getHttpServer())
      .get('/schedule')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  it('refuses a zone, a date or a technician it cannot resolve with 400', async () => {
    const session = await signInManager();

    await request(app.getHttpServer())
      .get('/schedule?timeZone=Mars/Olympus_Mons')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(400);

    await request(app.getHttpServer())
      .get('/schedule?date=2026-02-30')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(400);

    await request(app.getHttpServer())
      .get('/schedule?membershipId=Mike')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(400);

    // One malformed id among several refuses the whole request: a filter must never answer for
    // something narrower than what was asked.
    await request(app.getHttpServer())
      .get(`/schedule?membershipId=3f2504e0-4f89-41d3-9a0c-0305e82c3301&membershipId=Mike`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(400);
  });

  it('answers the requested local day, chronologically, with the row a card needs', async () => {
    const session = await signInManager();
    const lead = await newTechnicianMember('Mike Johnson');
    const helper = await newTechnicianMember('Sarah Kim');

    const afternoon = await newFixtureVisit({
      title: 'Boiler service',
      scheduledStart: new Date('2020-01-15T14:00:00.000Z'),
      crew: [
        { membershipId: lead.id, roleCode: 'LEAD' },
        { membershipId: helper.id, roleCode: 'TECHNICIAN' },
      ],
    });
    const morning = await newFixtureVisit({
      title: 'Furnace repair',
      scheduledStart: new Date('2020-01-15T09:00:00.000Z'),
      crew: [{ membershipId: lead.id, roleCode: 'LEAD' }],
    });
    // The day after the one asked for, so it must not appear.
    await newFixtureVisit({
      title: 'Another day',
      scheduledStart: new Date('2020-01-16T09:00:00.000Z'),
      crew: [{ membershipId: lead.id, roleCode: 'LEAD' }],
    });

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.day).toEqual({
      localDate: PAST_DATE,
      timeZone: 'UTC',
      start: '2020-01-15T00:00:00.000Z',
      end: '2020-01-16T00:00:00.000Z',
    });
    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).toEqual([morning.visit.id, afternoon.visit.id]);

    const first = response.body.visits[0];
    expect(first.visitStatus).toBe('SCHEDULED');
    expect(first.jobId).toBe(morning.job.id);
    expect(first.jobNumber).toBe(morning.job.jobNumber);
    expect(first.jobTitle).toBe('Furnace repair');
    expect(first.customerId).toBe(morning.customer.id);
    expect(first.customerName).toBe('Schedule Customer');
    expect(first.scheduledStart).toBe('2020-01-15T09:00:00.000Z');
    expect(first.scheduledEnd).toBe('2020-01-15T10:00:00.000Z');
    // The Visit's own preserved location is the one the attempt is for (`BR-056`).
    expect(first.address.addressLine1).toBe('1 Visit Way');
    expect(first.technicians).toEqual([
      { membershipId: lead.id, name: 'Mike Johnson', roleCode: 'LEAD' },
    ]);
    // The condition is the API's own answer, decided from the schedule and the server clock.
    expect(first.overdue).toBe(true);
    expect(
      response.body.visits[1].technicians.map(
        (row: { roleCode: string }) => row.roleCode,
      ),
    ).toEqual(['LEAD', 'TECHNICIAN']);
  });


  it('does not report a future day\u2019s work as overdue', async () => {
    const session = await signInManager();
    const lead = await newTechnicianMember('Mike Johnson');
    const future = await newFixtureVisit({
      title: 'Future work',
      scheduledStart: new Date('2099-06-01T09:00:00.000Z'),
      crew: [{ membershipId: lead.id, roleCode: 'LEAD' }],
    });

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${FUTURE_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    const row = response.body.visits.find(
      (item: { visitId: string }) => item.visitId === future.visit.id,
    );
    expect(row).toBeDefined();
    expect(row.overdue).toBe(false);
  });

  it('excludes the attempts that did not happen and keeps what the day produced', async () => {
    const session = await signInManager();
    const lead = await newTechnicianMember('Mike Johnson');
    const completed = await newFixtureVisit({
      title: 'Completed work',
      status: 'COMPLETED',
      scheduledStart: new Date('2020-01-15T11:00:00.000Z'),
      crew: [{ membershipId: lead.id, roleCode: 'LEAD' }],
    });
    const canceled = await newFixtureVisit({
      title: 'Canceled work',
      status: 'CANCELED',
      scheduledStart: new Date('2020-01-15T12:00:00.000Z'),
    });
    const noShow = await newFixtureVisit({
      title: 'No show',
      status: 'NO_SHOW',
      scheduledStart: new Date('2020-01-15T13:00:00.000Z'),
    });

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    const ids = response.body.visits.map(
      (row: { visitId: string }) => row.visitId,
    );
    expect(ids).toContain(completed.visit.id);
    expect(ids).not.toContain(canceled.visit.id);
    expect(ids).not.toContain(noShow.visit.id);
  });

  it('narrows the day to the technician the filter names', async () => {
    const session = await signInManager();
    const mike = await newTechnicianMember('Mike Johnson');
    const sarah = await newTechnicianMember('Sarah Kim');

    const mikes = await newFixtureVisit({
      title: 'Mikes work',
      scheduledStart: new Date('2020-01-15T09:00:00.000Z'),
      crew: [{ membershipId: mike.id, roleCode: 'LEAD' }],
    });
    const sarahs = await newFixtureVisit({
      title: 'Sarahs work',
      scheduledStart: new Date('2020-01-15T10:00:00.000Z'),
      crew: [{ membershipId: sarah.id, roleCode: 'LEAD' }],
    });

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC&membershipId=${mike.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).toEqual([mikes.visit.id]);
    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).not.toContain(sarahs.visit.id);

    // An id that matches no assignment answers with an empty day rather than a refusal: the filter
    // narrows the caller's own data, and having assigned nobody is not an error (`BR-042`).
    const nobody = await newTechnicianMember('Nobody Assigned');
    const empty = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC&membershipId=${nobody.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
    expect(empty.body.visits).toEqual([]);
  });

  it('answers with the union of the technicians the filter names', async () => {
    const session = await signInManager();
    const mike = await newTechnicianMember('Mike Johnson');
    const sarah = await newTechnicianMember('Sarah Kim');
    const priya = await newTechnicianMember('Priya Raman');

    const mikes = await newFixtureVisit({
      title: 'Mikes work',
      scheduledStart: new Date('2020-02-15T09:00:00.000Z'),
      crew: [{ membershipId: mike.id, roleCode: 'LEAD' }],
    });
    const sarahs = await newFixtureVisit({
      title: 'Sarahs work',
      scheduledStart: new Date('2020-02-15T10:00:00.000Z'),
      crew: [{ membershipId: sarah.id, roleCode: 'LEAD' }],
    });
    const shared = await newFixtureVisit({
      title: 'Shared work',
      scheduledStart: new Date('2020-02-15T11:00:00.000Z'),
      crew: [
        { membershipId: mike.id, roleCode: 'LEAD' },
        { membershipId: sarah.id, roleCode: 'TECHNICIAN' },
      ],
    });
    const priyas = await newFixtureVisit({
      title: 'Priyas work',
      scheduledStart: new Date('2020-02-15T12:00:00.000Z'),
      crew: [{ membershipId: priya.id, roleCode: 'LEAD' }],
    });

    const response = await request(app.getHttpServer())
      .get(
        `/schedule?date=${FILTER_DATE}&timeZone=UTC&membershipId=${mike.id}&membershipId=${sarah.id}`,
      )
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    // ANY-match, in the day's own chronological order (`BR-068`, `BR-072`): a Visit is kept when one
    // of the selected technicians is on it, and a Visit nobody selected is not.
    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).toEqual([mikes.visit.id, sarahs.visit.id, shared.visit.id]);
    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).not.toContain(priyas.visit.id);

    // The whole team is the same read with no names in it, so clearing the filter restores the day.
    const wholeTeam = await request(app.getHttpServer())
      .get(`/schedule?date=${FILTER_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
    expect(
      wholeTeam.body.visits.map((row: { visitId: string }) => row.visitId),
    ).toEqual([
      mikes.visit.id,
      sarahs.visit.id,
      shared.visit.id,
      priyas.visit.id,
    ]);
  });

  it('answers the technician filter\u2019s options with the organization\u2019s own technicians', async () => {
    const session = await signInManager();
    const mike = await newTechnicianMember('Mike Johnson');

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.technicians).toContainEqual({
      membershipId: mike.id,
      name: 'Mike Johnson',
    });
  });


  it('answers the unassigned lane with the open work that has no crew', async () => {
    const session = await signInManager();
    const lead = await newTechnicianMember('Mike Johnson');

    // An attempt nobody has been given yet: a `DRAFT` Visit with no schedule and no crew
    // (`BR-071`, `BR-072`).
    const waiting = await newFixtureVisit({
      title: 'Furnace repair',
      status: 'DRAFT',
      customerName: 'Unassigned Customer',
    });

    // Crewed work is assigned work, and a finished attempt does not need anybody.
    const crewed = await newFixtureVisit({
      title: 'Crewed work',
      scheduledStart: new Date('2020-01-15T09:00:00.000Z'),
      crew: [{ membershipId: lead.id, roleCode: 'LEAD' }],
    });
    const finished = await newFixtureVisit({
      title: 'Finished work',
      status: 'COMPLETED',
      scheduledStart: new Date('2020-01-15T11:00:00.000Z'),
    });

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    const ids = response.body.unassigned.items.map(
      (row: { visitId: string }) => row.visitId,
    );
    expect(ids).toContain(waiting.visit.id);
    expect(ids).not.toContain(crewed.visit.id);
    expect(ids).not.toContain(finished.visit.id);
    expect(response.body.unassigned.total).toBe(ids.length);

    const row = response.body.unassigned.items.find(
      (item: { visitId: string }) => item.visitId === waiting.visit.id,
    );
    expect(row.visitStatus).toBe('DRAFT');
    expect(row.scheduledStart).toBeNull();
    expect(row.scheduledEnd).toBeNull();
    expect(row.technicians).toEqual([]);
    expect(row.overdue).toBe(false);

    // The lane is not day-scoped: a Visit with no agreed time belongs to no day, and it is still
    // waiting for a crew whatever date the screen is showing (`BR-071`, `BR-072`).
    const otherDay = await request(app.getHttpServer())
      .get(`/schedule?date=${FUTURE_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
    expect(
      otherDay.body.unassigned.items.map(
        (item: { visitId: string }) => item.visitId,
      ),
    ).toContain(waiting.visit.id);
  });

  it('falls back to the Job\u2019s preserved address when the Visit has none', async () => {
    const session = await signInManager();
    // An attempt under way whose Visit carries no location of its own, for a Job that does.
    const fixture = await newFixtureVisit({
      title: 'Job address work',
      status: 'IN_PROGRESS',
      scheduledStart: new Date('2020-01-15T15:00:00.000Z'),
      jobAddress: { addressLine1: '55 Job Street' },
    });

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    const row = response.body.visits.find(
      (item: { visitId: string }) => item.visitId === fixture.visit.id,
    );
    expect(row.address.addressLine1).toBe('55 Job Street');
  });

  it('never answers with another organization\u2019s work', async () => {
    const session = await signInManager();
    const other = await createTestOrganization(database.db, {
      name: 'Schedule Org Other',
    });
    database.cleanup.trackOrganization(other.id);

    const [otherCustomer] = await database.db
      .insert(customers)
      .values({
        organizationId: other.id,
        type: 'COMPANY',
        displayName: 'Other Customer',
      })
      .returning();
    const [otherJob] = await database.db
      .insert(jobs)
      .values({
        organizationId: other.id,
        jobNumber: 9001,
        customerId: otherCustomer.id,
        title: 'Other work',
      })
      .returning();
    const [otherVisit] = await database.db
      .insert(visits)
      .values({
        organizationId: other.id,
        jobId: otherJob.id,
        status: 'DRAFT',
      })
      .returning();

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(
      response.body.unassigned.items.map(
        (row: { visitId: string }) => row.visitId,
      ),
    ).not.toContain(otherVisit.id);
    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).not.toContain(otherVisit.id);
  });

  it('never answers with work for a customer the organization archived', async () => {
    const session = await signInManager();
    const fixture = await newFixtureVisit({
      title: 'Archived work',
      status: 'DRAFT',
    });
    await database.db
      .update(customers)
      .set({
        deletedAt: new Date(),
        deletedByMembershipId: await fixtureActorMembershipId(),
      })
      .where(eq(customers.id, fixture.customer.id));

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(
      response.body.unassigned.items.map(
        (row: { visitId: string }) => row.visitId,
      ),
    ).not.toContain(fixture.visit.id);
  });

  it('answers an office caller who also holds the field capability', async () => {
    // Holding both capabilities must still open the office schedule: the capability set decides
    // what a session may read, never a role name (`BR-006`, `BR-007`).
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
    ]);

    await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
  });

  it('answers a field caller with their own assigned work and the scope they read in', async () => {
    // The field scope (`BR-009`, `ADR-019` D2): the caller reads the Visits their own membership is
    // on, and nothing else of the organization's day reaches them.
    const session = await signInTechnician();
    const colleague = await newTechnicianMember('Sarah Kim');

    const mine = await newFixtureVisit({
      title: 'My furnace repair',
      scheduledStart: new Date(`${FIELD_DATE}T09:00:00.000Z`),
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });
    const theirs = await newFixtureVisit({
      title: 'A colleague furnace repair',
      scheduledStart: new Date(`${FIELD_DATE}T11:00:00.000Z`),
      crew: [{ membershipId: colleague.id, roleCode: 'LEAD' }],
    });
    // A Visit the caller is on, in a status the day excludes: it must not appear either, because the
    // day's own classification applies to the field scope exactly as it does to the office one.
    await newFixtureVisit({
      title: 'A canceled attempt',
      status: 'CANCELED',
      scheduledStart: new Date(`${FIELD_DATE}T15:00:00.000Z`),
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${FIELD_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.scope).toEqual({
      kind: 'SELF',
      membershipId: session.membershipId,
    });
    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).toEqual([mine.visit.id]);
    expect(
      response.body.visits.map((row: { visitId: string }) => row.visitId),
    ).not.toContain(theirs.visit.id);
    // The two office-only projections are absent from a field answer rather than empty: the
    // technician filter's options belong to a read that may name other technicians, and the
    // "waiting for a crew" lane is the office's question (`BR-009`, `BR-024`).
    expect(response.body.technicians).toEqual([]);
    expect(response.body.unassigned).toBeNull();
  });

  it('answers a field caller naming their own membership, which narrows nothing', async () => {
    // The filter is within scope when it names the caller's own membership, so it is answered
    // rather than refused (`BR-042`).
    const session = await signInTechnician();
    await newFixtureVisit({
      title: 'My own work',
      scheduledStart: new Date(`${FIELD_FILTER_DATE}T09:00:00.000Z`),
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    const response = await request(app.getHttpServer())
      .get(
        `/schedule?date=${FIELD_FILTER_DATE}&timeZone=UTC&membershipId=${session.membershipId}`,
      )
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.scope.kind).toBe('SELF');
    expect(response.body.visits).toHaveLength(1);
  });

  it('refuses a field caller asking for another technician, however the id is spelled', async () => {
    // The scope is authorized by the API, never by the client: a field caller cannot widen their
    // read by naming somebody else (`BR-007`). The refusal is the same for a colleague who exists
    // and for an id that names nobody, so it cannot be used to learn who the organization employs
    // (`BR-042`, `BR-044`).
    const session = await signInTechnician();
    const colleague = await newTechnicianMember('Sarah Kim');

    await request(app.getHttpServer())
      .get(`/schedule?timeZone=UTC&membershipId=${colleague.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);

    await request(app.getHttpServer())
      .get(
        '/schedule?timeZone=UTC&membershipId=3f2504e0-4f89-41d3-9a0c-0305e82c3301',
      )
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);

    // One colleague among several refuses the whole request: a filter must never answer for
    // something other than what was asked.
    await request(app.getHttpServer())
      .get(
        `/schedule?timeZone=UTC&membershipId=${session.membershipId}&membershipId=${colleague.id}`,
      )
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  it('answers the office scope for a caller holding the office capability alone', async () => {
    // The same read, the other scope: an office caller is told the day is the organization's, which
    // is what lets a client present the operation's schedule rather than one technician's
    // (`BR-006`, `BR-041`).
    const session = await signInManager();

    const response = await request(app.getHttpServer())
      .get(`/schedule?date=${PAST_DATE}&timeZone=UTC`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.scope).toEqual({
      kind: 'ORGANIZATION',
      membershipId: session.membershipId,
    });
    expect(response.body.unassigned).not.toBeNull();
  });
});

