import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { and, asc, eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_PERMISSIONS,
  JOB_PERMISSIONS,
  VISIT_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
  customers,
  jobStatusHistory,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  properties,
  propertyCustomerRelationships,
  visitNotes,
  visitOutcomeHistory,
  visitStatusHistory,
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
 * The Visit field lifecycle (`BR-074`, `BR-075`, `BR-077`, `BR-078`, `BR-059`, `BR-079`;
 * `ADR-019` D3, D4, D5).
 *
 * The routes are asserted against real records because what they enforce is business rules: only
 * `BR-074`'s transitions and `BR-075`'s one correction are applied, a completion carries the outcome
 * `BR-077` requires, every applied transition is recorded once in append-only history, the Job
 * consequence `ADR-019` D4 decides follows the Visit event, and the assignment scope answers with
 * `404` rather than `403` (`BR-009`, `BR-067`, `BR-001`).
 */
describe('visit field lifecycle (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Visit Field Org',
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
    const email = uniqueEmail('visit-field');
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
          deviceId: `visit-field-device-${deviceSequence}`,
        },
      })
      .expect(200);

    return {
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  /**
   * A field session: exactly the capabilities `BR-009` gives the default Technician role.
   *
   * They are granted directly rather than through a role name, because `BR-006` makes effective
   * permissions the role's plus the member's own and a route must not care which of the two granted
   * them.
   */
  async function signInTechnician(
    granted: readonly PermissionCode[] = [
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
      VISIT_PERMISSIONS.UPDATE_ASSIGNED_STATUS,
      VISIT_PERMISSIONS.ADD_NOTE,
      VISIT_PERMISSIONS.RECORD_OUTCOME,
    ],
  ) {
    return signInFor(granted);
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
        title: `Field job ${jobNumberSequence}`,
        status,
        ...(propertyId === undefined
          ? {}
          : {
              propertyId,
              propertyAddressSnapshot: {
                addressLine1: '12 Field Road',
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
    propertyId?: string;
    scheduledStart?: Date;
    scheduledEnd?: Date;
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
            : { addressLine1: '12 Field Road' },
        status: values.status,
        scheduledStart: values.scheduledStart ?? null,
        scheduledEnd: values.scheduledEnd ?? null,
        ...(values.status === 'COMPLETED'
          ? {
              outcomeCode: 'RESOLVED',
              outcomeSummary: 'Recorded before this test.',
              outcomeRecordedAt: new Date(),
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

  /** A Job with a Property and one Visit, optionally scheduled and crewed (`BR-072`). */
  async function jobWithVisit(options: {
    jobStatus?: string;
    visitStatus: string;
    property?: boolean;
    schedule?: boolean;
    crew?: readonly {
      membershipId: string;
      roleCode: 'LEAD' | 'TECHNICIAN';
    }[];
  }) {
    const actor = await newMembership();
    const customer = await newCustomer('Field Customer');
    const property =
      options.property === false ? null : await newProperty('12 Field Road');
    if (property !== null) {
      await database.db.insert(propertyCustomerRelationships).values({
        organizationId,
        propertyId: property.id,
        customerId: customer.id,
        actorMembershipId: actor.id,
      });
    }

    const job = await newJob(
      customer.id,
      options.jobStatus ?? 'SCHEDULED',
      property?.id,
    );
    const start = new Date(Date.now() + 3_600_000);
    const visit = await newVisit({
      jobId: job.id,
      status: options.visitStatus,
      propertyId: property?.id,
      ...(options.schedule === false
        ? {}
        : {
            scheduledStart: start,
            scheduledEnd: new Date(start.getTime() + 7_200_000),
          }),
    });
    for (const member of options.crew ?? []) {
      await assign({
        visitId: visit.id,
        technicianMembershipId: member.membershipId,
        roleCode: member.roleCode,
      });
    }
    return { job, visit, property, customer };
  }

  const base = '/jobs';

  /** One Visit's append-only status history, oldest first. */
  async function statusHistory(visitId: string) {
    return database.db
      .select({
        fromStatus: visitStatusHistory.fromStatus,
        toStatus: visitStatusHistory.toStatus,
        isCorrection: visitStatusHistory.isCorrection,
        clientOperationId: visitStatusHistory.clientOperationId,
        capturedAt: visitStatusHistory.capturedAt,
        confirmedConflicts: visitStatusHistory.confirmedConflicts,
      })
      .from(visitStatusHistory)
      .where(
        and(
          eq(visitStatusHistory.organizationId, organizationId),
          eq(visitStatusHistory.visitId, visitId),
        ),
      )
      .orderBy(asc(visitStatusHistory.recordedAt), asc(visitStatusHistory.id));
  }

  async function jobHistory(jobId: string) {
    return database.db
      .select({
        fromStatus: jobStatusHistory.fromStatus,
        toStatus: jobStatusHistory.toStatus,
      })
      .from(jobStatusHistory)
      .where(
        and(
          eq(jobStatusHistory.organizationId, organizationId),
          eq(jobStatusHistory.jobId, jobId),
        ),
      )
      .orderBy(asc(jobStatusHistory.recordedAt), asc(jobStatusHistory.id));
  }

  async function visitRow(visitId: string) {
    const [row] = await database.db
      .select()
      .from(visits)
      .where(
        and(eq(visits.organizationId, organizationId), eq(visits.id, visitId)),
      )
      .limit(1);
    return row;
  }

  async function jobRow(jobId: string) {
    const [row] = await database.db
      .select({ status: jobs.status, version: jobs.version })
      .from(jobs)
      .where(and(eq(jobs.organizationId, organizationId), eq(jobs.id, jobId)))
      .limit(1);
    return row;
  }

  /** One field action, as the technician's session performs it. */
  function changeStatus(
    session: { accessToken: string },
    jobId: string,
    visitId: string,
    body: Record<string, unknown>,
  ) {
    return request(app.getHttpServer())
      .patch(`${base}/${jobId}/visits/${visitId}/status`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send(body);
  }

  it('refuses an unauthenticated caller', async () => {
    const { job, visit } = await jobWithVisit({ visitStatus: 'SCHEDULED' });
    await request(app.getHttpServer())
      .patch(`${base}/${job.id}/visits/${visit.id}/status`)
      .send({ status: 'EN_ROUTE' })
      .expect(401);
    expect(await statusHistory(visit.id)).toEqual([]);
  });

  it('refuses a caller holding only the read capability, who may still read the job', async () => {
    const { job, visit } = await jobWithVisit({ visitStatus: 'SCHEDULED' });
    const session = await signInTechnician([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
    await assign({
      visitId: visit.id,
      technicianMembershipId: session.membershipId,
      roleCode: 'LEAD',
    });

    // The read `VISIT_VIEW_ASSIGNED` names works (`BR-009`, `ADR-019` D1) ...
    await request(app.getHttpServer())
      .get(`${base}/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200)
      .expect((response) => {
        expect(response.body.selectedVisit.status).toBe('SCHEDULED');
        // The Visit's own answer is about the caller's place on the crew, not about the capability:
        // this session holds only the read capability, and the action stays the client's own gate
        // (`BR-011`).
        expect(response.body.selectedVisit.fieldActionable).toBe(true);
      });

    // ... and the write it does not name is refused by the API, not by a hidden button (`BR-007`).
    await changeStatus(session, job.id, visit.id, { status: 'EN_ROUTE' }).expect(
      403,
    );
    expect(await statusHistory(visit.id)).toEqual([]);
  });

  it('refuses a caller who is not on the visit current crew, without reporting it as forbidden', async () => {
    const { job, visit } = await jobWithVisit({ visitStatus: 'SCHEDULED' });
    const session = await signInTechnician();

    // The Visit is not the caller's, so it is reported as absent exactly as the read reports a Job the
    // caller's own assignments do not reach (`ADR-019` D2, D3).
    await changeStatus(session, job.id, visit.id, { status: 'EN_ROUTE' }).expect(
      404,
    );

    // "Currently assigned" is evaluated at the moment of the action: being removed before the action is
    // the same scope answer.
    await assign({
      visitId: visit.id,
      technicianMembershipId: session.membershipId,
      roleCode: 'LEAD',
    });
    await changeStatus(session, job.id, visit.id, { status: 'EN_ROUTE' }).expect(
      200,
    );
    await database.db
      .delete(visitTechnicians)
      .where(eq(visitTechnicians.visitId, visit.id));
    await changeStatus(session, job.id, visit.id, { status: 'ON_SITE' }).expect(
      404,
    );
    expect(await statusHistory(visit.id)).toHaveLength(1);
  });
  it('tells a caller the Visit it may not drive, instead of offering a refusal (`BR-041`, `ADR-019` D3)', async () => {
    const session = await signInTechnician();
    const colleague = await newMembership();
    const { job, property } = await jobWithVisit({
      visitStatus: 'DRAFT',
      schedule: false,
    });

    // The colleague's attempt is over and still open, so it is the Job's **selected** Visit — the
    // "overdue" Visit the home and the schedule present with that condition (`BR-081`, `BR-074`). The
    // technician's own attempt is an older one, so the Job read reaches them through it.
    const overdue = await newVisit({
      jobId: job.id,
      propertyId: property?.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(Date.now() - 3_600_000),
      scheduledEnd: new Date(Date.now() - 1_800_000),
    });
    await assign({
      visitId: overdue.id,
      technicianMembershipId: colleague.id,
      roleCode: 'LEAD',
    });
    const own = await newVisit({
      jobId: job.id,
      status: 'CANCELED',
      scheduledStart: new Date(Date.now() - 86_400_000),
      scheduledEnd: new Date(Date.now() - 82_800_000),
    });
    await assign({
      visitId: own.id,
      technicianMembershipId: session.membershipId,
      roleCode: 'LEAD',
    });

    // The read admits them and reports the Visit the Job is represented by — which is not theirs to
    // drive. The answer is the scope the field route enforces, so a client does not have to offer an
    // action the route refuses and then report the refusal as a Visit that is gone.
    const read = await request(app.getHttpServer())
      .get(`${base}/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
    expect(read.body.selectedVisit).toMatchObject({
      id: overdue.id,
      fieldActionable: false,
      // It is offered no destination either: every one of them would be refused, so `BR-093` requires
      // the projection to expose none of them (`BR-041`).
      allowedStatusTransitions: [],
    });

    // The action itself is still refused the way it always was (`ADR-019` D2).
    await changeStatus(session, job.id, overdue.id, {
      status: 'EN_ROUTE',
    }).expect(404);
  });

  it('refuses a session holding the field capabilities but not the office one, on no crew (`BR-093`)', async () => {
    // An office session holds the field write capability too — the seeded Manager role holds the whole
    // catalogue. Holding it is not office authorization: `BR-093` makes crew membership govern a caller
    // who does **not** hold the office capability, so this member writes only a Visit their own crew
    // includes and is refused one they do not (never `403`: the Visit is not disclosed as existing).
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      VISIT_PERMISSIONS.UPDATE_ASSIGNED_STATUS,
      VISIT_PERMISSIONS.RECORD_OUTCOME,
    ]);
    const crew = await newMembership();
    const { job, visit } = await jobWithVisit({
      visitStatus: 'SCHEDULED',
      crew: [{ membershipId: crew.id, roleCode: 'LEAD' }],
    });

    const read = await request(app.getHttpServer())
      .get(`${base}/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
    expect(read.body.selectedVisit).toMatchObject({
      id: visit.id,
      fieldActionable: false,
      allowedStatusTransitions: [],
    });

    await changeStatus(session, job.id, visit.id, {
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Done.',
    }).expect(404);
    expect(await statusHistory(visit.id)).toEqual([]);
  });

  it('lets an office member complete a Visit they are not on the crew of (`BR-093`, `ADR-019` D7)', async () => {
    // The office capability that reaches Visit writes is the route's second authorization: this member
    // is on no crew at all, and `BR-093` exempts them from crew membership without weakening the scope
    // that governs a technician (`BR-066`).
    const office = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      JOB_PERMISSIONS.UPDATE,
      VISIT_PERMISSIONS.RECORD_OUTCOME,
    ]);
    const crew = await newMembership();
    const { job, visit } = await jobWithVisit({
      jobStatus: 'IN_PROGRESS',
      visitStatus: 'IN_PROGRESS',
      crew: [{ membershipId: crew.id, roleCode: 'LEAD' }],
    });

    const read = await request(app.getHttpServer())
      .get(`${base}/${job.id}`)
      .set('Authorization', `Bearer ${office.accessToken}`)
      .expect(200);
    expect(read.body.selectedVisit).toMatchObject({
      id: visit.id,
      fieldActionable: true,
      // The whole working table, the completion included: this caller holds the capability a completion
      // requires of every caller (`BR-009`, `BR-077`).
      allowedStatusTransitions: [
        'DRAFT',
        'SCHEDULED',
        'EN_ROUTE',
        'ON_SITE',
        'COMPLETED',
      ],
    });
    // The Visit keeps the crew it has: the office member driving it is not added to it.
    expect(read.body.technicians).toMatchObject([
      { membershipId: crew.id, roleCode: 'LEAD' },
    ]);

    await changeStatus(office, job.id, visit.id, {
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Closed by the office after the technician called in.',
    }).expect(200);

    // Office completion is a technician completion in every respect short of who may perform it: one
    // recorded transition, `BR-077`'s outcome, and the Job consequence `ADR-019` D4 decides (`BR-093`).
    const history = await statusHistory(visit.id);
    expect(history).toHaveLength(1);
    expect(history[0]).toMatchObject({
      fromStatus: 'IN_PROGRESS',
      toStatus: 'COMPLETED',
      isCorrection: false,
    });
    expect(await visitRow(visit.id)).toMatchObject({
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Closed by the office after the technician called in.',
    });
    const outcomes = await database.db
      .select()
      .from(visitOutcomeHistory)
      .where(eq(visitOutcomeHistory.visitId, visit.id));
    expect(outcomes).toHaveLength(1);
    expect(outcomes[0]).toMatchObject({ outcomeCode: 'RESOLVED' });
    expect(await jobRow(job.id)).toMatchObject({ status: 'PENDING_REVIEW' });

    // Both records name the member who actually performed the action, never the crew's Lead
    // (`BR-033`, `BR-067`, `BR-093`).
    const [statusRow] = await database.db
      .select({ actorMembershipId: visitStatusHistory.actorMembershipId })
      .from(visitStatusHistory)
      .where(eq(visitStatusHistory.visitId, visit.id))
      .limit(1);
    expect(statusRow?.actorMembershipId).toBe(office.membershipId);
    expect(outcomes[0]?.actorMembershipId).toBe(office.membershipId);
    expect(statusRow?.actorMembershipId).not.toBe(crew.id);
  });

  it('offers the office caller every other destination, but the completion only with its own capability (`BR-093`)', async () => {
    const office = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      JOB_PERMISSIONS.UPDATE,
    ]);
    const { job, visit } = await jobWithVisit({ visitStatus: 'SCHEDULED' });

    const read = await request(app.getHttpServer())
      .get(`${base}/${job.id}`)
      .set('Authorization', `Bearer ${office.accessToken}`)
      .expect(200);
    expect(read.body.selectedVisit).toMatchObject({
      fieldActionable: true,
      // The office capability admits the caller, and the completion is absent because the capability a
      // completion requires of **every** caller is not held (`BR-009`, `BR-077`).
      allowedStatusTransitions: ['DRAFT', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'],
    });

    // The destinations the projection offered are the ones the route applies ...
    await changeStatus(office, job.id, visit.id, { status: 'EN_ROUTE' }).expect(200);

    // ... and the one it did not offer is refused by the API rather than by a hidden control
    // (`BR-007`).
    await changeStatus(office, job.id, visit.id, {
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Done.',
    }).expect(403);
    expect(await statusHistory(visit.id)).toHaveLength(1);
  });



  it('applies the whole normal lifecycle, one recorded transition at a time', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      jobStatus: 'NEW',
      visitStatus: 'DRAFT',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    const scheduled = await changeStatus(session, job.id, visit.id, {
      status: 'SCHEDULED',
    }).expect(200);
    // The client draws the technician's action from the server's own lifecycle table (`BR-074`,
    // `BR-041`).
    expect(scheduled.body.selectedVisit).toMatchObject({
      status: 'SCHEDULED',
      version: 2,
      // `BR-074` offers the whole working vocabulary in either direction, read from the server's own
      // table so the client holds no copy of the lifecycle (`BR-041`).
      allowedStatusTransitions: [
        'DRAFT',
        'EN_ROUTE',
        'ON_SITE',
        'IN_PROGRESS',
        'COMPLETED',
      ],
      reschedulable: true,
    });
    // Reaching `SCHEDULED` records nothing on the Job: `ADR-019` D4 gives the scheduling move no Job
    // consequence, because `BR-058` has no Job destination for a field attempt merely being scheduled.
    expect(await jobHistory(job.id)).toEqual([]);

    const enRoute = await changeStatus(session, job.id, visit.id, {
      status: 'EN_ROUTE',
      capturedAt: '2026-09-17T11:00:00.000Z',
      clientOperationId: '55555555-5555-4555-8555-555555555555',
    }).expect(200);
    expect(enRoute.body.selectedVisit).toMatchObject({
      status: 'EN_ROUTE',
      version: 3,
      allowedStatusTransitions: [
        'DRAFT',
        'SCHEDULED',
        'ON_SITE',
        'IN_PROGRESS',
        'COMPLETED',
      ],
    });
    // Work has started, so the Job is in progress (`BR-058`, `ADR-019` D4).
    expect(await jobHistory(job.id)).toEqual([
      { fromStatus: 'NEW', toStatus: 'IN_PROGRESS' },
    ]);

    await changeStatus(session, job.id, visit.id, { status: 'ON_SITE' }).expect(
      200,
    );
    await changeStatus(session, job.id, visit.id, {
      status: 'IN_PROGRESS',
    }).expect(200);
    // A Job already in the status an event implies does not move: a status that does not change is not
    // a transition (`BR-058`, `BR-067`).
    expect(await jobHistory(job.id)).toEqual([
      { fromStatus: 'NEW', toStatus: 'IN_PROGRESS' },
    ]);

    const completed = await changeStatus(session, job.id, visit.id, {
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Replaced the igniter.',
    }).expect(200);
    expect(completed.body.selectedVisit).toMatchObject({
      status: 'COMPLETED',
      version: 6,
      // A completed Visit is reopenable (`BR-074`), so it is the one historical status that is not a
      // dead end.
      allowedStatusTransitions: [
        'DRAFT',
        'SCHEDULED',
        'EN_ROUTE',
        'ON_SITE',
        'IN_PROGRESS',
      ],
    });

    // Every applied transition is recorded once, in order, with the device's own instant beside the
    // server's (`BR-067`, `ADR-019` D5).
    const history = await statusHistory(visit.id);
    expect(
      history.map((row) => [row.fromStatus, row.toStatus, row.isCorrection]),
    ).toEqual([
      ['DRAFT', 'SCHEDULED', false],
      ['SCHEDULED', 'EN_ROUTE', false],
      ['EN_ROUTE', 'ON_SITE', false],
      ['ON_SITE', 'IN_PROGRESS', false],
      ['IN_PROGRESS', 'COMPLETED', false],
    ]);
    expect(history[1]).toMatchObject({
      clientOperationId: '55555555-5555-4555-8555-555555555555',
      capturedAt: new Date('2026-09-17T11:00:00.000Z'),
    });

    // The outcome is recorded with the completion, and the Visit carries it (`BR-077`, `BR-078`).
    expect(await visitRow(visit.id)).toMatchObject({
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Replaced the igniter.',
      version: 6,
    });
    const outcomes = await database.db
      .select()
      .from(visitOutcomeHistory)
      .where(eq(visitOutcomeHistory.visitId, visit.id));
    expect(outcomes).toHaveLength(1);
    expect(outcomes[0]).toMatchObject({
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Replaced the igniter.',
      previousOutcomeCode: null,
    });

    // No Visit remains active and the latest completion resolves the Job, so `BR-061` lets the Job await
    // review — and `BR-062` keeps the API from completing it (`ADR-019` D4).
    expect(await jobRow(job.id)).toMatchObject({ status: 'PENDING_REVIEW' });
    expect(await jobHistory(job.id)).toEqual([
      { fromStatus: 'NEW', toStatus: 'IN_PROGRESS' },
      { fromStatus: 'IN_PROGRESS', toStatus: 'PENDING_REVIEW' },
    ]);
  });

  it('refuses only standing still and the dispatch pair, and names what the visit may take', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      visitStatus: 'SCHEDULED',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    // `BR-074`'s free movement means a working Visit reaches every other working status, so what is left
    // to refuse is standing still (`BR-067` says a status that does not change is not a transition) and
    // the two dispatch actions no field caller may take (`BR-066`, `BR-076`, `ADR-019` D7).
    for (const body of [
      { status: 'SCHEDULED' },
      { status: 'CANCELED' },
      { status: 'NO_SHOW' },
    ]) {
      const response = await changeStatus(
        session,
        job.id,
        visit.id,
        body,
      ).expect(409);
      expect(response.body).toMatchObject({
        code: 'VISIT_STATUS_TRANSITION_NOT_ALLOWED',
        details: {
          from: 'SCHEDULED',
          to: body.status,
          allowed: ['DRAFT', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED'],
        },
      });
    }

    // Nothing was applied and nothing was recorded.
    expect((await visitRow(visit.id))?.status).toBe('SCHEDULED');
    expect(await statusHistory(visit.id)).toEqual([]);
  });

  it('skips a step and steps backward, one recorded transition each', async () => {
    const session = await signInTechnician();
    const crew = [{ membershipId: session.membershipId, roleCode: 'LEAD' as const }];

    // A technician who never tapped "en route" is not made to walk the chain: `SCHEDULED →
    // IN_PROGRESS` is one permitted transition (`BR-074`).
    const skipping = await jobWithVisit({
      jobStatus: 'SCHEDULED',
      visitStatus: 'SCHEDULED',
      crew,
    });
    const started = await changeStatus(
      session,
      skipping.job.id,
      skipping.visit.id,
      { status: 'IN_PROGRESS' },
    ).expect(200);
    expect(started.body.selectedVisit).toMatchObject({
      status: 'IN_PROGRESS',
      version: 2,
    });
    expect(
      (await statusHistory(skipping.visit.id)).map((row) => [
        row.fromStatus,
        row.toStatus,
        row.isCorrection,
      ]),
    ).toEqual([['SCHEDULED', 'IN_PROGRESS', false]]);

    // A technician who tapped too far ahead steps back: `ON_SITE → EN_ROUTE` is one transition, and it
    // is recorded as what it is rather than rewriting the status it corrects (`BR-067`, `BR-075`).
    const backward = await jobWithVisit({
      jobStatus: 'IN_PROGRESS',
      visitStatus: 'ON_SITE',
      crew,
    });
    await changeStatus(session, backward.job.id, backward.visit.id, {
      status: 'EN_ROUTE',
    }).expect(200);
    expect((await visitRow(backward.visit.id))?.status).toBe('EN_ROUTE');
    expect(
      (await statusHistory(backward.visit.id)).map((row) => [
        row.fromStatus,
        row.toStatus,
        row.isCorrection,
      ]),
    ).toEqual([['ON_SITE', 'EN_ROUTE', false]]);
    // Stepping back has no Job consequence: `BR-058` has no backwards Job destination, so the Job keeps
    // the status the forward work gave it (`ADR-019` D4).
    expect(await jobRow(backward.job.id)).toMatchObject({ status: 'IN_PROGRESS' });
    expect(await jobHistory(backward.job.id)).toEqual([]);
  });

  it('reopens a completed visit, clearing its current outcome and taking the job out of review', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      jobStatus: 'NEW',
      visitStatus: 'DRAFT',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    await changeStatus(session, job.id, visit.id, {
      status: 'IN_PROGRESS',
    }).expect(200);
    await changeStatus(session, job.id, visit.id, {
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Done the first time.',
    }).expect(200);
    // No Visit remains active and the outcome resolves the Job, so `BR-061` lets it await review.
    expect(await jobRow(job.id)).toMatchObject({ status: 'PENDING_REVIEW' });

    // The technician completed it by mistake and reopens it into a working status (`BR-074`).
    const reopened = await changeStatus(session, job.id, visit.id, {
      status: 'IN_PROGRESS',
    }).expect(200);
    expect(reopened.body.selectedVisit).toMatchObject({
      status: 'IN_PROGRESS',
    });

    // The reopen is one recorded transition, and every earlier event is still there (`BR-067`, `BR-075`).
    expect(
      (await statusHistory(visit.id)).map((row) => [
        row.fromStatus,
        row.toStatus,
        row.isCorrection,
      ]),
    ).toEqual([
      ['DRAFT', 'IN_PROGRESS', false],
      ['IN_PROGRESS', 'COMPLETED', false],
      ['COMPLETED', 'IN_PROGRESS', false],
    ]);

    // The previous completion and its outcome stay in append-only history, while the Visit holds **no**
    // current outcome until it is completed again (`BR-074`, `BR-079`).
    expect(await visitRow(visit.id)).toMatchObject({
      status: 'IN_PROGRESS',
      outcomeCode: null,
      outcomeSummary: null,
      outcomeRecordedAt: null,
      outcomeRecordedByMembershipId: null,
    });
    const outcomes = await database.db
      .select()
      .from(visitOutcomeHistory)
      .where(eq(visitOutcomeHistory.visitId, visit.id));
    expect(outcomes).toHaveLength(1);
    expect(outcomes[0]).toMatchObject({
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Done the first time.',
    });

    // A Job must not await review while its field work is unfinished, so it returns to `IN_PROGRESS` and
    // the move is recorded (`BR-061`, `BR-074`).
    expect(await jobRow(job.id)).toMatchObject({ status: 'IN_PROGRESS' });
    expect(await jobHistory(job.id)).toEqual([
      { fromStatus: 'NEW', toStatus: 'IN_PROGRESS' },
      { fromStatus: 'IN_PROGRESS', toStatus: 'PENDING_REVIEW' },
      { fromStatus: 'PENDING_REVIEW', toStatus: 'IN_PROGRESS' },
    ]);

    // Completing it again requires a **new** outcome, which becomes the Visit's current one (`BR-077`).
    await changeStatus(session, job.id, visit.id, {
      status: 'COMPLETED',
    }).expect(400);
    await changeStatus(session, job.id, visit.id, {
      status: 'COMPLETED',
      outcomeCode: 'NEEDS_PARTS',
      outcomeSummary: 'Ordered the igniter.',
    }).expect(200);
    expect(await visitRow(visit.id)).toMatchObject({
      status: 'COMPLETED',
      outcomeCode: 'NEEDS_PARTS',
      outcomeSummary: 'Ordered the igniter.',
    });
    expect(
      await database.db
        .select()
        .from(visitOutcomeHistory)
        .where(eq(visitOutcomeHistory.visitId, visit.id)),
    ).toHaveLength(2);
    // The follow-up outcome keeps the Job in progress rather than awaiting review (`BR-061`, `BR-078`).
    expect(await jobRow(job.id)).toMatchObject({ status: 'IN_PROGRESS' });
  });

  it('refuses a completion that carries no outcome (`BR-077`)', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      visitStatus: 'IN_PROGRESS',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    await changeStatus(session, job.id, visit.id, {
      status: 'COMPLETED',
    }).expect(400);
    // The outcome is a pair: a code without its summary is not the record `BR-077` requires.
    await changeStatus(session, job.id, visit.id, {
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
    }).expect(400);

    expect((await visitRow(visit.id))?.status).toBe('IN_PROGRESS');
    expect(await statusHistory(visit.id)).toEqual([]);
  });

  it('refuses a completion by a caller who holds no outcome capability', async () => {
    // The outcome is its own capability (`BR-009`), so a caller trusted with the status alone cannot
    // record one — and the API answers in its own right, not through a hidden control (`BR-007`).
    const session = await signInTechnician([
      VISIT_PERMISSIONS.UPDATE_ASSIGNED_STATUS,
    ]);

    const advancing = await jobWithVisit({
      visitStatus: 'SCHEDULED',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });
    await changeStatus(
      session,
      advancing.job.id,
      advancing.visit.id,
      { status: 'EN_ROUTE' },
    ).expect(200);

    const completing = await jobWithVisit({
      visitStatus: 'IN_PROGRESS',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });
    await changeStatus(session, completing.job.id, completing.visit.id, {
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Done.',
    }).expect(403);
    expect((await visitRow(completing.visit.id))?.status).toBe('IN_PROGRESS');
    expect(await statusHistory(completing.visit.id)).toEqual([]);
  });

  it('refuses a change the client made against a version that moved on (`BR-086`)', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      visitStatus: 'SCHEDULED',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    const response = await changeStatus(session, job.id, visit.id, {
      status: 'EN_ROUTE',
      expectedVersion: 7,
    }).expect(409);
    expect(response.body).toMatchObject({
      code: 'VERSION_CONFLICT',
      details: { record: 'VISIT', currentVersion: 1 },
    });
    expect(await statusHistory(visit.id)).toEqual([]);
  });

  it('refuses a visit that is not ready to be scheduled (`BR-072`)', async () => {
    const session = await signInTechnician();

    const noProperty = await jobWithVisit({
      jobStatus: 'NEW',
      visitStatus: 'DRAFT',
      property: false,
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });
    const propertyRefusal = await changeStatus(
      session,
      noProperty.job.id,
      noProperty.visit.id,
      { status: 'SCHEDULED' },
    ).expect(409);
    expect(propertyRefusal.body).toMatchObject({
      code: 'VISIT_SCHEDULING_CONDITION_NOT_MET',
      details: { reason: 'PROPERTY' },
    });

    const noSchedule = await jobWithVisit({
      jobStatus: 'NEW',
      visitStatus: 'DRAFT',
      schedule: false,
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });
    const scheduleRefusal = await changeStatus(
      session,
      noSchedule.job.id,
      noSchedule.visit.id,
      { status: 'SCHEDULED' },
    ).expect(409);
    expect(scheduleRefusal.body).toMatchObject({
      code: 'VISIT_SCHEDULING_CONDITION_NOT_MET',
      details: { reason: 'SCHEDULE' },
    });

    // `BR-072` requires at least one technician assigned and exactly one Lead (`BR-068`). The caller
    // reaches this Visit through their own assignment, so the crew that fails this condition is one
    // whose technician is not the Lead.
    const noLead = await jobWithVisit({
      jobStatus: 'NEW',
      visitStatus: 'DRAFT',
      crew: [
        { membershipId: session.membershipId, roleCode: 'TECHNICIAN' },
      ],
    });
    const crewRefusal = await changeStatus(
      session,
      noLead.job.id,
      noLead.visit.id,
      { status: 'SCHEDULED' },
    ).expect(409);
    expect(crewRefusal.body).toMatchObject({
      code: 'VISIT_SCHEDULING_CONDITION_NOT_MET',
      details: { reason: 'CREW_LEAD' },
    });

    // None of the three was applied, and the destination was refused as ineligible rather than as
    // non-existent (`BR-058`'s split between structural transitions and runtime conditions).
    expect((await visitRow(noProperty.visit.id))?.status).toBe('DRAFT');
    expect((await visitRow(noSchedule.visit.id))?.status).toBe('DRAFT');
    expect((await visitRow(noLead.visit.id))?.status).toBe('DRAFT');
  });

  it('reports a scheduling conflict before applying it, and records what was confirmed', async () => {
    const session = await signInTechnician();
    const first = await jobWithVisit({
      jobStatus: 'NEW',
      visitStatus: 'DRAFT',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });
    const start = (first.visit.scheduledStart as Date).getTime();

    // The same technician is already booked for an overlapping window on another Visit (`BR-070`).
    const secondJob = await newJob(
      first.customer.id,
      'SCHEDULED',
      first.property?.id,
    );
    const overlapping = await newVisit({
      jobId: secondJob.id,
      status: 'SCHEDULED',
      propertyId: first.property?.id,
      scheduledStart: new Date(start + 1_800_000),
      scheduledEnd: new Date(start + 5_400_000),
    });
    await assign({
      visitId: overlapping.id,
      technicianMembershipId: session.membershipId,
      roleCode: 'LEAD',
    });

    const conflict = await changeStatus(session, first.job.id, first.visit.id, {
      status: 'SCHEDULED',
    }).expect(409);
    expect(conflict.body).toMatchObject({ code: 'SCHEDULE_CONFLICT' });
    expect(conflict.body.details.conflicts).toHaveLength(1);
    expect(conflict.body.details.conflicts[0]).toMatchObject({
      visitId: overlapping.id,
      technicianMembershipId: session.membershipId,
    });
    expect((await visitRow(first.visit.id))?.status).toBe('DRAFT');

    // A conflict is a warning, not a prohibition (`BR-070`): the same request, confirmed, applies — and
    // what was accepted is recorded with the transition.
    await changeStatus(session, first.job.id, first.visit.id, {
      status: 'SCHEDULED',
      confirmConflicts: true,
    }).expect(200);
    const history = await statusHistory(first.visit.id);
    expect(history).toHaveLength(1);
    expect(history[0]?.confirmedConflicts).toHaveLength(1);
  });

  it('records the BR-075 correction as a correction and moves no job', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      jobStatus: 'IN_PROGRESS',
      visitStatus: 'EN_ROUTE',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    const response = await changeStatus(session, job.id, visit.id, {
      status: 'SCHEDULED',
    }).expect(200);
    expect(response.body.selectedVisit).toMatchObject({
      status: 'SCHEDULED',
      version: 2,
      reschedulable: true,
    });

    // The correction preserves the event it corrects and says what it is (`BR-075`, `BR-067`).
    expect(await statusHistory(visit.id)).toEqual([
      {
        fromStatus: 'EN_ROUTE',
        toStatus: 'SCHEDULED',
        isCorrection: true,
        clientOperationId: null,
        capturedAt: null,
        confirmedConflicts: null,
      },
    ]);

    // `BR-058` has no Job destination a correction could mirror, so the Job keeps its status and its
    // history records nothing (`ADR-019` D4).
    expect(await jobRow(job.id)).toMatchObject({ status: 'IN_PROGRESS' });
    expect(await jobHistory(job.id)).toEqual([]);
  });

  it('records every BR-078 outcome and applies the Job consequence it implies', async () => {
    const session = await signInTechnician();
    const codes = [
      'RESOLVED',
      'NEEDS_PARTS',
      'NEEDS_FOLLOWUP',
      'NEEDS_QUOTE_APPROVAL',
      'UNABLE_TO_COMPLETE',
    ] as const;

    for (const code of codes) {
      const { job, visit } = await jobWithVisit({
        jobStatus: 'IN_PROGRESS',
        visitStatus: 'IN_PROGRESS',
        crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
      });

      await changeStatus(session, job.id, visit.id, {
        status: 'COMPLETED',
        outcomeCode: code,
        outcomeSummary: `Outcome ${code}.`,
      }).expect(200);

      expect(await visitRow(visit.id)).toMatchObject({
        status: 'COMPLETED',
        outcomeCode: code,
        outcomeSummary: `Outcome ${code}.`,
      });

      // Only `RESOLVED` expects no follow-up, so only it lets the Job await review (`BR-061`,
      // `BR-078`). Every other outcome leaves the Job in progress — where it already was, so nothing
      // is recorded (`BR-058`).
      if (code === 'RESOLVED') {
        expect((await jobRow(job.id))?.status).toBe('PENDING_REVIEW');
        expect(await jobHistory(job.id)).toEqual([
          { fromStatus: 'IN_PROGRESS', toStatus: 'PENDING_REVIEW' },
        ]);
      } else {
        expect((await jobRow(job.id))?.status).toBe('IN_PROGRESS');
        expect(await jobHistory(job.id)).toEqual([]);
      }
    }
  });

  it('keeps a job out of review while another visit is still scheduled (`BR-061`)', async () => {
    const session = await signInTechnician();
    const first = await jobWithVisit({
      jobStatus: 'IN_PROGRESS',
      visitStatus: 'IN_PROGRESS',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });
    // A second field attempt on the same Job is still scheduled, so the Job has remaining work.
    const start = new Date(Date.now() + 86_400_000);
    const second = await newVisit({
      jobId: first.job.id,
      status: 'SCHEDULED',
      propertyId: first.property?.id,
      scheduledStart: start,
      scheduledEnd: new Date(start.getTime() + 3_600_000),
    });
    await assign({
      visitId: second.id,
      technicianMembershipId: session.membershipId,
      roleCode: 'TECHNICIAN',
    });

    await changeStatus(session, first.job.id, first.visit.id, {
      status: 'COMPLETED',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Diagnosis complete.',
    }).expect(200);

    expect((await jobRow(first.job.id))?.status).toBe('IN_PROGRESS');
    expect(await jobHistory(first.job.id)).toEqual([]);
    // `BR-059`: the Job's state machine and the Visit's stay separate, so the completion left the
    // scheduled Visit exactly as it was.
    expect((await visitRow(second.id))?.status).toBe('SCHEDULED');
  });

  it('applies a replayed operation once and answers with the state it produced (`BR-031`)', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      visitStatus: 'SCHEDULED',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });
    const operationId = '66666666-6666-4666-8666-666666666666';

    const first = await changeStatus(session, job.id, visit.id, {
      status: 'EN_ROUTE',
      clientOperationId: operationId,
    }).expect(200);
    expect(first.body.selectedVisit.status).toBe('EN_ROUTE');

    // A replay is not a second writer (`ADR-019` D5): it records nothing and answers with the state the
    // first attempt produced, rather than being refused as a conflict or applied twice.
    const replay = await changeStatus(session, job.id, visit.id, {
      status: 'EN_ROUTE',
      clientOperationId: operationId,
    }).expect(200);
    expect(replay.body.selectedVisit.status).toBe('EN_ROUTE');

    expect(await statusHistory(visit.id)).toHaveLength(1);
    expect((await visitRow(visit.id))?.version).toBe(2);
  });

  it('refuses an operation id that was already used for another visit', async () => {
    const session = await signInTechnician();
    const crew = [
      { membershipId: session.membershipId, roleCode: 'LEAD' as const },
    ];
    const first = await jobWithVisit({ visitStatus: 'SCHEDULED', crew });
    const second = await jobWithVisit({ visitStatus: 'SCHEDULED', crew });
    const operationId = '77777777-7777-4777-8777-777777777777';

    await changeStatus(session, first.job.id, first.visit.id, {
      status: 'EN_ROUTE',
      clientOperationId: operationId,
    }).expect(200);

    // One operation id names one operation, and that operation addressed one Visit: answering with
    // another Visit's state would report a change the caller did not make (`BR-031`).
    const response = await changeStatus(session, second.job.id, second.visit.id, {
      status: 'EN_ROUTE',
      clientOperationId: operationId,
    }).expect(409);
    expect(response.body).toMatchObject({ code: 'VISIT_OPERATION_REUSED' });
    expect(await statusHistory(second.visit.id)).toEqual([]);
    expect((await visitRow(second.visit.id))?.status).toBe('SCHEDULED');
  });

  it('refuses a repeat that carries no operation id instead of applying it twice', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      visitStatus: 'SCHEDULED',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    await changeStatus(session, job.id, visit.id, {
      status: 'EN_ROUTE',
    }).expect(200);

    // Without a key there is no replay to recognise, so the command is evaluated against the state the
    // Visit actually holds and refused with it — the refuse-and-reconcile answer a client presents and
    // offers to discard (`BR-014`, `ADR-019` D5).
    const repeat = await changeStatus(session, job.id, visit.id, {
      status: 'EN_ROUTE',
    }).expect(409);
    expect(repeat.body).toMatchObject({
      code: 'VISIT_STATUS_TRANSITION_NOT_ALLOWED',
      details: { from: 'EN_ROUTE', to: 'EN_ROUTE' },
    });
    expect(await statusHistory(visit.id)).toHaveLength(1);
    expect((await visitRow(visit.id))?.version).toBe(2);
  });

  it('refuses field work on a job whose record is closed (`BR-079`)', async () => {
    const session = await signInTechnician();
    const crew = [
      { membershipId: session.membershipId, roleCode: 'LEAD' as const },
    ];

    // `BR-062` prevents a Job being closed while it has an open Visit, so this state is inserted
    // directly to assert what the API does if it is ever reached: it fails closed rather than writing
    // field history under a closed Job (`ADR-019` D4).
    for (const jobStatus of ['COMPLETED', 'CANCELED'] as const) {
      const { job, visit } = await jobWithVisit({
        jobStatus,
        visitStatus: 'IN_PROGRESS',
        crew,
      });
      const response = await changeStatus(session, job.id, visit.id, {
        status: 'COMPLETED',
        outcomeCode: 'RESOLVED',
        outcomeSummary: 'Done.',
      }).expect(409);
      expect(response.body).toMatchObject({
        code: 'JOB_CLOSED_FOR_FIELD_WORK',
      });
      expect((await visitRow(visit.id))?.status).toBe('IN_PROGRESS');
      expect(await statusHistory(visit.id)).toEqual([]);
    }
  });

  it('leaves every visit untouched when the office moves the job (`BR-059`)', async () => {
    const office = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      jobStatus: 'IN_PROGRESS',
      visitStatus: 'EN_ROUTE',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    await request(app.getHttpServer())
      .patch(`${base}/${job.id}/status`)
      .set('Authorization', `Bearer ${office.accessToken}`)
      .send({ status: 'SCHEDULED' })
      .expect(200);

    // The Job's business lifecycle and the field execution lifecycle are two state machines
    // (`BR-059`): the Job moved and the Visit did not, and no Visit history was written.
    expect(await jobHistory(job.id)).toEqual([
      { fromStatus: 'IN_PROGRESS', toStatus: 'SCHEDULED' },
    ]);
    expect(await visitRow(visit.id)).toMatchObject({
      status: 'EN_ROUTE',
      version: 1,
    });
    expect(await statusHistory(visit.id)).toEqual([]);
  });

  it('reports a visit of another organization as absent (`BR-001`)', async () => {
    const session = await signInTechnician();
    const other = await createTestOrganization(database.db, {
      name: 'Other Field Org',
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
    jobNumberSequence += 1;
    const [otherJob] = await database.db
      .insert(jobs)
      .values({
        organizationId: other.id,
        jobNumber: jobNumberSequence,
        customerId: otherCustomer.id,
        title: 'Other job',
        status: 'SCHEDULED',
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

    await changeStatus(session, otherJob.id, otherVisit.id, {
      status: 'EN_ROUTE',
    }).expect(404);
    expect((await visitRow(otherVisit.id))?.status).toBeUndefined();
  });

  it('lets an assigned technician add a note to their visit (`VISIT_ADD_NOTE`)', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({
      visitStatus: 'SCHEDULED',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });
    const operationId = '88888888-8888-4888-8888-888888888888';

    const response = await request(app.getHttpServer())
      .post(`${base}/${job.id}/visits/${visit.id}/notes`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({
        body: 'Customer not home; left a card.',
        clientOperationId: operationId,
        capturedAt: '2026-09-17T13:00:00.000Z',
      })
      .expect(201);

    // The note appears in the unified Job Activity timeline (`BR-080`), which is what the route answers.
    expect(response.body.jobId).toBe(job.id);
    expect(
      response.body.events.find(
        (event: { kind: string }) => event.kind === 'VISIT_NOTE_ADDED',
      ),
    ).toMatchObject({
      // The activity read names the Visit by its sequence (`BR-041`, domain model §6).
      visitSequence: 1,
      body: 'Customer not home; left a card.',
    });

    const notes = await database.db
      .select()
      .from(visitNotes)
      .where(eq(visitNotes.visitId, visit.id));
    expect(notes).toHaveLength(1);
    expect(notes[0]).toMatchObject({
      authorMembershipId: session.membershipId,
      clientOperationId: operationId,
      capturedAt: new Date('2026-09-17T13:00:00.000Z'),
    });

    // A note has no update path, so idempotency alone covers a repeat (`ADR-019` D5).
    await request(app.getHttpServer())
      .post(`${base}/${job.id}/visits/${visit.id}/notes`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ body: 'Customer not home; left a card.', clientOperationId: operationId })
      .expect(201);
    expect(
      await database.db
        .select()
        .from(visitNotes)
        .where(eq(visitNotes.visitId, visit.id)),
    ).toHaveLength(1);
  });

  it('refuses a note on a visit the field caller is not assigned to (`ADR-019` D3)', async () => {
    const session = await signInTechnician();
    const { job, visit } = await jobWithVisit({ visitStatus: 'SCHEDULED' });

    await request(app.getHttpServer())
      .post(`${base}/${job.id}/visits/${visit.id}/notes`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ body: 'Not mine.' })
      .expect(404);
    expect(
      await database.db
        .select()
        .from(visitNotes)
        .where(eq(visitNotes.visitId, visit.id)),
    ).toEqual([]);
  });

  it('keeps the office note path it has always had (`BR-008`)', async () => {
    // The note route is reached by `VISIT_ADD_NOTE` or by the office's `JOB_UPDATE`, and the assignment
    // scope applies only to the caller without the office capability (`ADR-019` D2) — so widening the
    // guard takes nothing away from a manager recording a note about a Visit.
    const office = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const { job, visit } = await jobWithVisit({ visitStatus: 'SCHEDULED' });

    await request(app.getHttpServer())
      .post(`${base}/${job.id}/visits/${visit.id}/notes`)
      .set('Authorization', `Bearer ${office.accessToken}`)
      .send({ body: 'Office note.' })
      .expect(201);

    const notes = await database.db
      .select()
      .from(visitNotes)
      .where(eq(visitNotes.visitId, visit.id));
    expect(notes).toHaveLength(1);
    expect(notes[0]).toMatchObject({
      authorMembershipId: office.membershipId,
      body: 'Office note.',
      clientOperationId: null,
      capturedAt: null,
    });
  });

  it('refuses a note from a caller holding neither note capability', async () => {
    const session = await signInTechnician([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
    const { job, visit } = await jobWithVisit({
      visitStatus: 'SCHEDULED',
      crew: [{ membershipId: session.membershipId, roleCode: 'LEAD' }],
    });

    await request(app.getHttpServer())
      .post(`${base}/${job.id}/visits/${visit.id}/notes`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ body: 'Not allowed.' })
      .expect(403);
    expect(
      await database.db
        .select()
        .from(visitNotes)
        .where(eq(visitNotes.visitId, visit.id)),
    ).toEqual([]);
  });
});
