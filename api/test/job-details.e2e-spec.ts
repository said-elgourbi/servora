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
import { JobsService } from '../src/jobs/jobs.service.js';
import {
  customers,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  properties,
  propertyCustomerRelationships,
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
 * `GET /jobs/:id` — who may read a Job Details screen, and what the read projects.
 *
 * The projection is asserted against the service with real records, because what it derives is a
 * business rule: which single Visit represents a Job (`BR-081`) and which technicians that Visit
 * contributes (`BR-068`). The crew is always read from `visit_technicians`, never from the Job.
 */
describe('job details (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let jobsService: JobsService;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Job Details Org',
    });
    database.cleanup.trackOrganization(organization.id);
    organizationId = organization.id;

    const moduleFixture = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleFixture.createNestApplication({ logger: false });
    await app.init();
    jobsService = app.get(JobsService);
  });

  afterAll(async () => {
    await app.close();
    await database.dispose();
  });

  /**
   * A signed-in member holding exactly [granted], in [targetOrganizationId].
   *
   * The organization is a parameter because the tenant boundary is one of the things the field read
   * has to be asserted against (`BR-001`); it defaults to this suite's organization.
   */
  async function signInFor(
    granted: readonly PermissionCode[],
    targetOrganizationId = organizationId,
  ) {
    const email = uniqueEmail('job-details');
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(PASSWORD),
    });
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(
      database.db,
      targetOrganizationId,
    );
    const [member] = await database.db
      .insert(organizationMembers)
      .values({
        organizationId: targetOrganizationId,
        userId: user.id,
        roleId: role.id,
      })
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
        organizationId: targetOrganizationId,
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
          deviceId: `job-details-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return {
      userId: user.id,
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  /** A member of an organization, needed as the actor of a relationship and as an assignee. */
  async function newMembership(targetOrganizationId: string) {
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(
      database.db,
      targetOrganizationId,
    );
    const [member] = await database.db
      .insert(organizationMembers)
      .values({
        organizationId: targetOrganizationId,
        userId: user.id,
        roleId: role.id,
      })
      .returning();
    return member;
  }

  /** A member with a profile, so an assignment can resolve a name. */
  async function newNamedMembership(
    targetOrganizationId: string,
    firstName: string,
    lastName: string,
  ) {
    const member = await newMembership(targetOrganizationId);
    await database.db.insert(userProfiles).values({
      userId: member.userId,
      firstName,
      lastName,
      displayName: `${firstName} ${lastName}`,
    });
    return member;
  }

  async function newCustomer(
    targetOrganizationId: string,
    displayName: string,
  ) {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId: targetOrganizationId,
        type: 'COMPANY',
        displayName,
      })
      .returning();
    return customer;
  }

  async function newProperty(
    targetOrganizationId: string,
    addressLine1: string,
  ) {
    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId: targetOrganizationId,
        addressLine1,
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .returning();
    return property;
  }

  async function linkProperty(
    targetOrganizationId: string,
    propertyId: string,
    customerId: string,
    actorMembershipId: string,
  ) {
    await database.db.insert(propertyCustomerRelationships).values({
      organizationId: targetOrganizationId,
      propertyId,
      customerId,
      actorMembershipId,
    });
  }

  /** A Job, with the address snapshot the schema requires whenever a Property is set. */
  async function newJob(
    targetOrganizationId: string,
    customerId: string,
    propertyId?: string,
  ) {
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId: targetOrganizationId,
        jobNumber: jobNumberSequence,
        customerId,
        title: `Job ${jobNumberSequence}`,
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

  /** A Visit with an explicit status and schedule, satisfying the table's constraints. */
  async function newVisit(values: {
    targetOrganizationId: string;
    jobId: string;
    status: string;
    scheduledStart?: Date;
    scheduledEnd?: Date;
    propertyId?: string;
    actorMembershipId?: string;
  }) {
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId: values.targetOrganizationId,
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
              outcomeCode: 'RESOLVED',
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
    targetOrganizationId: string;
    visitId: string;
    technicianMembershipId: string;
    roleCode: 'LEAD' | 'TECHNICIAN';
  }) {
    await database.db.insert(visitTechnicians).values({
      organizationId: values.targetOrganizationId,
      visitId: values.visitId,
      technicianMembershipId: values.technicianMembershipId,
      roleCode: values.roleCode,
    });
  }

  /**
   * A Job with two Visits: a past one that was already worked, and an upcoming one that represents
   * the Job. They carry different crews, so a read that looked at the wrong Visit could not pass.
   */
  async function jobWithTwoVisits() {
    const member = await newMembership(organizationId);
    const lead = await newNamedMembership(organizationId, 'Mike', 'Lead');
    const second = await newNamedMembership(organizationId, 'Sarah', 'Moreau');
    const pastCrew = await newNamedMembership(organizationId, 'Dave', 'Past');
    const customer = await newCustomer(organizationId, 'Martha Reynolds');
    const property = await newProperty(organizationId, '987 Cedar Lane');
    await linkProperty(organizationId, property.id, customer.id, member.id);
    const job = await newJob(organizationId, customer.id, property.id);

    const now = Date.now();
    const pastVisit = await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'COMPLETED',
      scheduledStart: new Date(now - 7_200_000),
      scheduledEnd: new Date(now - 3_600_000),
      actorMembershipId: member.id,
    });
    await assign({
      targetOrganizationId: organizationId,
      visitId: pastVisit.id,
      technicianMembershipId: pastCrew.id,
      roleCode: 'LEAD',
    });

    const upcomingStart = new Date(now + 3_600_000);
    const upcomingVisit = await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'SCHEDULED',
      scheduledStart: upcomingStart,
      scheduledEnd: new Date(now + 7_200_000),
    });
    // Assigned in the order a dispatcher would not necessarily choose, so the read's own Lead-first
    // ordering is what the assertions check (`BR-068`).
    await assign({
      targetOrganizationId: organizationId,
      visitId: upcomingVisit.id,
      technicianMembershipId: second.id,
      roleCode: 'TECHNICIAN',
    });
    await assign({
      targetOrganizationId: organizationId,
      visitId: upcomingVisit.id,
      technicianMembershipId: lead.id,
      roleCode: 'LEAD',
    });

    return { job, customer, upcomingVisit, upcomingStart, lead, second };
  }

  it('refuses an unauthenticated caller with 401', async () => {
    const fixture = await jobWithTwoVisits();

    await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}`)
      .expect(401);
  });

  it('refuses a caller without customers.view with 403', async () => {
    const fixture = await jobWithTwoVisits();
    const session = await signInFor([CUSTOMER_PERMISSIONS.CREATE]);

    await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  it('answers the Job with the represented Visit and only that Visit crew', async () => {
    const fixture = await jobWithTwoVisits();
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body).toMatchObject({
      id: fixture.job.id,
      jobNumber: fixture.job.jobNumber,
      customerId: fixture.customer.id,
      customerName: 'Martha Reynolds',
      selectedVisit: {
        id: fixture.upcomingVisit.id,
        status: 'SCHEDULED',
        scheduledStart: fixture.upcomingStart.toISOString(),
      },
      technicians: [
        { membershipId: fixture.lead.id, name: 'Mike Lead', roleCode: 'LEAD' },
        {
          membershipId: fixture.second.id,
          name: 'Sarah Moreau',
          roleCode: 'TECHNICIAN',
        },
      ],
      address: { addressLine1: '1 Main Street' },
    });
    expect(response.body.propertyId).toBeTruthy();
    // The past Visit's crew is history and never leaks into the represented Visit's crew.
    expect(
      response.body.technicians.map(
        (technician: { name: string }) => technician.name,
      ),
    ).not.toContain('Dave Past');
  });

  it('falls back to the most recent past Visit when the Job has no upcoming one', async () => {
    const member = await newMembership(organizationId);
    const crew = await newNamedMembership(organizationId, 'Real', 'Crew');
    const customer = await newCustomer(organizationId, 'Past Only Co');
    const property = await newProperty(organizationId, '5 Past Street');
    await linkProperty(organizationId, property.id, customer.id, member.id);
    const job = await newJob(organizationId, customer.id, property.id);

    const now = Date.now();
    await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'COMPLETED',
      scheduledStart: new Date(now - 604_800_000),
      scheduledEnd: new Date(now - 603_600_000),
      actorMembershipId: member.id,
    });
    const mostRecentStart = new Date(now - 86_400_000);
    const mostRecent = await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'COMPLETED',
      scheduledStart: mostRecentStart,
      scheduledEnd: new Date(now - 82_800_000),
      actorMembershipId: member.id,
    });
    await assign({
      targetOrganizationId: organizationId,
      visitId: mostRecent.id,
      technicianMembershipId: crew.id,
      roleCode: 'LEAD',
    });

    const details = await jobsService.findJobDetailsInOrganization(
      { organizationId },
      job.id,
    );

    expect(details?.selectedVisit?.visitId).toBe(mostRecent.id);
    expect(details?.selectedVisit?.scheduledStart.toISOString()).toBe(
      mostRecentStart.toISOString(),
    );
    expect(details?.technicians.map((technician) => technician.name)).toEqual([
      'Real Crew',
    ]);
  });

  it('skips a canceled upcoming Visit when choosing the represented Visit', async () => {
    const member = await newMembership(organizationId);
    const customer = await newCustomer(organizationId, 'Cancelled Visit Co');
    const property = await newProperty(organizationId, '6 Cancel Street');
    await linkProperty(organizationId, property.id, customer.id, member.id);
    const job = await newJob(organizationId, customer.id, property.id);

    const now = Date.now();
    await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'CANCELED',
      scheduledStart: new Date(now + 3_600_000),
      scheduledEnd: new Date(now + 7_200_000),
    });
    const kept = await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(now + 7_200_000),
      scheduledEnd: new Date(now + 10_800_000),
    });

    const details = await jobsService.findJobDetailsInOrganization(
      { organizationId },
      job.id,
    );

    expect(details?.selectedVisit?.visitId).toBe(kept.id);
  });

  it('reports no Visit and no crew for a Job that has none', async () => {
    const customer = await newCustomer(organizationId, 'No Visit Co');
    const job = await newJob(organizationId, customer.id);

    const details = await jobsService.findJobDetailsInOrganization(
      { organizationId },
      job.id,
    );

    expect(details?.job.id).toBe(job.id);
    expect(details?.selectedVisit).toBeNull();
    expect(details?.technicians).toEqual([]);
    expect(details?.job.propertyId).toBeNull();
  });

  it('reports an empty crew for a represented Visit nobody is assigned to', async () => {
    const member = await newMembership(organizationId);
    const customer = await newCustomer(organizationId, 'Unassigned Co');
    const property = await newProperty(organizationId, '7 Nobody Street');
    await linkProperty(organizationId, property.id, customer.id, member.id);
    const job = await newJob(organizationId, customer.id, property.id);
    const now = Date.now();
    await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(now + 3_600_000),
      scheduledEnd: new Date(now + 7_200_000),
    });

    const details = await jobsService.findJobDetailsInOrganization(
      { organizationId },
      job.id,
    );

    expect(details?.selectedVisit).not.toBeNull();
    expect(details?.technicians).toEqual([]);
  });

  it('contributes no name for an assigned member who has no profile yet', async () => {
    const member = await newMembership(organizationId);
    const profileless = await newMembership(organizationId);
    const customer = await newCustomer(organizationId, 'Profileless Co');
    const property = await newProperty(organizationId, '8 No Profile Street');
    await linkProperty(organizationId, property.id, customer.id, member.id);
    const job = await newJob(organizationId, customer.id, property.id);
    const now = Date.now();
    const visit = await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(now + 3_600_000),
      scheduledEnd: new Date(now + 7_200_000),
    });
    await assign({
      targetOrganizationId: organizationId,
      visitId: visit.id,
      technicianMembershipId: profileless.id,
      roleCode: 'LEAD',
    });

    const details = await jobsService.findJobDetailsInOrganization(
      { organizationId },
      job.id,
    );

    expect(details?.technicians).toEqual([
      { membershipId: profileless.id, name: null, roleCode: 'LEAD' },
    ]);
  });

  it('answers not found for a Job the caller organization does not own', async () => {
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);
    const otherOrganization = await createTestOrganization(database.db, {
      name: 'Other Job Details Org',
    });
    database.cleanup.trackOrganization(otherOrganization.id);
    const otherCustomer = await newCustomer(otherOrganization.id, 'Other Co');
    const otherJob = await newJob(otherOrganization.id, otherCustomer.id);

    await request(app.getHttpServer())
      .get(`/jobs/${otherJob.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_NOT_FOUND');
      });
  });

  it('answers not found for an id that is not a Job', async () => {
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    await request(app.getHttpServer())
      .get('/jobs/00000000-0000-0000-0000-000000000000')
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404);
  });

  it('answers not found for a Job whose customer the organization deleted', async () => {
    const member = await newMembership(organizationId);
    const customer = await newCustomer(organizationId, 'Deleted Customer Co');
    const job = await newJob(organizationId, customer.id);
    // Deleting a customer records who did it (`BR-023`), which the schema enforces.
    await database.db
      .update(customers)
      .set({
        status: 'INACTIVE',
        deletedAt: new Date(),
        deletedByMembershipId: member.id,
      })
      .where(eq(customers.id, customer.id));

    const details = await jobsService.findJobDetailsInOrganization(
      { organizationId },
      job.id,
    );

    expect(details).toBeNull();
  });

  /*
   * The field caller (`BR-009`; `ADR-019` D1, D2).
   *
   * A technician reaches a Job through `VISIT_VIEW_ASSIGNED` and reads **the same** projection the
   * office reads — one contract, one parser (`BR-041`). What that capability admits them to is decided
   * by their own current assignments, and a Job their crew does not include is reported as not found
   * rather than forbidden, so a Job id cannot be probed for existence (`BR-001`).
   */

  /** A Job with a Property and one Visit whose crew is [memberId]. */
  async function jobWithCrew(memberId: string, status = 'SCHEDULED') {
    const actor = await newMembership(organizationId);
    const customer = await newCustomer(organizationId, 'Field Customer');
    const property = await newProperty(organizationId, '12 Field Road');
    await linkProperty(organizationId, property.id, customer.id, actor.id);
    const job = await newJob(organizationId, customer.id, property.id);
    // A `SCHEDULED` Visit requires a Property (`visits_scheduled_requirements_check`, `BR-072`).
    const visit = await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status,
      scheduledStart: new Date(Date.now() + 3_600_000),
      scheduledEnd: new Date(Date.now() + 7_200_000),
      ...(status === 'COMPLETED' ? { actorMembershipId: actor.id } : {}),
    });
    await assign({
      targetOrganizationId: organizationId,
      visitId: visit.id,
      technicianMembershipId: memberId,
      roleCode: 'LEAD',
    });
    return { job, visit };
  }

  it('answers the Job to a field caller assigned to its Visit', async () => {
    const session = await signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
    const { job, visit } = await jobWithCrew(session.membershipId);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.id).toBe(job.id);
    expect(response.body.jobNumber).toBe(job.jobNumber);
    expect(response.body.selectedVisit.id).toBe(visit.id);
    expect(response.body.technicians).toEqual([
      { membershipId: session.membershipId, name: null, roleCode: 'LEAD' },
    ]);
  });

  it('answers a Job whose crew still includes the caller after the Visit completed', async () => {
    // A completed assignment stays a current assignment until a manager removes it (`BR-069`), so the
    // technician who did the work can still open the Job.
    const session = await signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
    const { job } = await jobWithCrew(session.membershipId, 'COMPLETED');

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
  });

  it('answers not found for a Job a field caller is not assigned to', async () => {
    const session = await signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
    const other = await newMembership(organizationId);
    const { job } = await jobWithCrew(other.id);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_NOT_FOUND');
      });
  });

  it('stops answering a Job once the caller is removed from its crew', async () => {
    const session = await signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
    const { job, visit } = await jobWithCrew(session.membershipId);
    await database.db
      .delete(visitTechnicians)
      .where(
        and(
          eq(visitTechnicians.visitId, visit.id),
          eq(visitTechnicians.technicianMembershipId, session.membershipId),
        ),
      );

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404);
  });

  it('refuses a caller holding a field write capability but no read capability with 403', async () => {
    // The guard asks one question — which **read** capability admitted the caller — and a write
    // capability is not a substitute for it (`BR-006`, `BR-009`).
    const session = await signInFor([
      VISIT_PERMISSIONS.UPDATE_ASSIGNED_STATUS,
    ]);
    const other = await newMembership(organizationId);
    const { job } = await jobWithCrew(other.id);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  it('reads the organization when the caller holds the office capability as well', async () => {
    // D2's scope follows the capability that admits the caller: a member who also holds
    // `customers.view` is an office caller, exactly as before this slice.
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
    ]);
    const other = await newMembership(organizationId);
    const { job } = await jobWithCrew(other.id);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
  });

  it('keeps a field caller inside their own organization', async () => {
    const otherOrganization = await createTestOrganization(database.db, {
      name: 'Other Field Org',
    });
    database.cleanup.trackOrganization(otherOrganization.id);
    const session = await signInFor(
      [VISIT_PERMISSIONS.VIEW_ASSIGNED],
      otherOrganization.id,
    );
    // A Job of this suite's organization, requested by the other organization's member: the field
    // capability is held in **their** organization and reaches nothing here (`BR-001`).
    const other = await newMembership(organizationId);
    const { job } = await jobWithCrew(other.id);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404);
  });
});
