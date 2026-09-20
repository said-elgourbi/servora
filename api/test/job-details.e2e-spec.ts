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
  customerContacts,
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

/** The Customer contact columns `BR-092` reports, so a fixture can set them without a second helper. */
interface CustomerContactValues {
  email?: string;
  phone?: string;
  notes?: string;
}

/** The contact details the assigned-customer read is asserted against (`BR-092`). */
const CUSTOMER_CONTACT: CustomerContactValues = {
  email: 'dispatch@fieldcustomer.test',
  phone: '+15145550142',
  notes: 'Gate code 4821. Ring twice.',
};

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
    contact: CustomerContactValues = {},
  ) {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId: targetOrganizationId,
        type: 'COMPANY',
        displayName,
        // The Customer's own contact details, which is what `BR-092` reports and `BR-023`'s billing,
        // preferred-contact and language fields are deliberately not.
        email: contact.email ?? null,
        phone: contact.phone ?? null,
        notes: contact.notes ?? null,
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
    const pastStart = new Date(now - 7_200_000);
    const pastEnd = new Date(now - 3_600_000);
    const pastVisit = await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'COMPLETED',
      scheduledStart: pastStart,
      scheduledEnd: pastEnd,
      actorMembershipId: member.id,
    });
    await assign({
      targetOrganizationId: organizationId,
      visitId: pastVisit.id,
      technicianMembershipId: pastCrew.id,
      roleCode: 'LEAD',
    });

    const upcomingStart = new Date(now + 3_600_000);
    const upcomingEnd = new Date(now + 7_200_000);
    const upcomingVisit = await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      propertyId: property.id,
      status: 'SCHEDULED',
      scheduledStart: upcomingStart,
      scheduledEnd: upcomingEnd,
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

    return {
      job,
      customer,
      pastVisit,
      pastStart,
      pastEnd,
      pastCrew,
      upcomingVisit,
      upcomingStart,
      upcomingEnd,
      lead,
      second,
    };
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

  /*
   * The Job's Visits, which is what a screen reads when it presents the Job rather than one field
   * attempt (`BR-047`, `BR-071`). Every Visit is listed in the Job's own sequence with the crew it
   * actually carries, and the Visit that represents the Job is not privileged: it is simply the entry
   * whose id `selectedVisit` names (`BR-081`, `BR-041`).
   */
  it('lists every Visit of the Job in its own sequence, each with its own crew', async () => {
    const fixture = await jobWithTwoVisits();
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.visits).toEqual([
      {
        id: fixture.pastVisit.id,
        sequence: 1,
        status: 'COMPLETED',
        // The Visit's own **current** outcome travels with it, so a screen can state what resulted
        // from a field attempt beside that attempt's date and crew (`BR-077`, `BR-078`).
        outcomeCode: 'RESOLVED',
        scheduledStart: fixture.pastStart.toISOString(),
        scheduledEnd: fixture.pastEnd.toISOString(),
        version: fixture.pastVisit.version,
        technicians: [
          {
            membershipId: fixture.pastCrew.id,
            name: 'Dave Past',
            roleCode: 'LEAD',
          },
        ],
      },
      {
        id: fixture.upcomingVisit.id,
        sequence: 2,
        status: 'SCHEDULED',
        // A Visit that has not been completed holds no outcome, and none is invented for it
        // (`BR-042`).
        outcomeCode: null,
        scheduledStart: fixture.upcomingStart.toISOString(),
        scheduledEnd: fixture.upcomingEnd.toISOString(),
        version: fixture.upcomingVisit.version,
        technicians: [
          {
            membershipId: fixture.lead.id,
            name: 'Mike Lead',
            roleCode: 'LEAD',
          },
          {
            membershipId: fixture.second.id,
            name: 'Sarah Moreau',
            roleCode: 'TECHNICIAN',
          },
        ],
      },
    ]);
  });

  /*
   * A Visit's outcome is its **current** one and not its history (`BR-079`): reopening a completed
   * Visit clears the outcome it recorded while that outcome stays in append-only history, so the read
   * reports no outcome for the Visit again. A client that derived the outcome from the activity
   * timeline would state one the Visit no longer holds.
   */
  it('reports no outcome for a Visit that was reopened after completion', async () => {
    const fixture = await jobWithTwoVisits();
    await database.db
      .update(visits)
      .set({
        status: 'IN_PROGRESS',
        outcomeCode: null,
        outcomeSummary: null,
        outcomeRecordedAt: null,
        outcomeRecordedByMembershipId: null,
      })
      .where(eq(visits.id, fixture.pastVisit.id));
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    const reopened = response.body.visits.find(
      (visit: { id: string }) => visit.id === fixture.pastVisit.id,
    );
    expect(reopened.status).toBe('IN_PROGRESS');
    // The Visit holds no outcome again, and none is reported for it rather than the one its history
    // still records (`BR-079`, `BR-042`).
    expect(reopened.outcomeCode).toBeNull();
  });

  it('lists a Visit that has no schedule with both ends reported as null', async () => {
    // A Visit exists before it is scheduled (`BR-072`), and it is still a field attempt the Job holds,
    // so it is listed rather than omitted — with no schedule claimed for it (`BR-042`).
    const member = await newMembership(organizationId);
    const customer = await newCustomer(organizationId, 'Draft Visit Co');
    const property = await newProperty(organizationId, '7 Draft Street');
    await linkProperty(organizationId, property.id, customer.id, member.id);
    const job = await newJob(organizationId, customer.id, property.id);
    const draft = await newVisit({
      targetOrganizationId: organizationId,
      jobId: job.id,
      status: 'DRAFT',
    });
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    // A Visit with no schedule is never selected (`BR-081`), so the Job has no represented Visit while
    // still holding one.
    expect(response.body.selectedVisit).toBeNull();
    expect(response.body.visits).toEqual([
      {
        id: draft.id,
        sequence: 1,
        status: 'DRAFT',
        // A Visit that has not been completed holds no outcome (`BR-077`, `BR-079`).
        outcomeCode: null,
        scheduledStart: null,
        scheduledEnd: null,
        version: draft.version,
        technicians: [],
      },
    ]);
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
  async function jobWithCrew(
    memberId: string,
    status = 'SCHEDULED',
    contact: CustomerContactValues = {},
  ) {
    const actor = await newMembership(organizationId);
    const customer = await newCustomer(
      organizationId,
      'Field Customer',
      contact,
    );
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
    return { job, visit, customer, actor };
  }

  /**
   * One contact person of a Customer (`BR-095`, `BR-023`).
   *
   * The row is written directly, the way every other fixture here writes its records, so a test can
   * state what the Customer holds — including a contact that was removed — without going through the
   * write routes this suite does not test.
   */
  async function newContact(
    customerId: string,
    values: {
      firstName: string;
      lastName: string;
      phone?: string;
      email?: string;
      role?: string;
      isPrimary?: boolean;
      isBillingContact?: boolean;
      removedByMembershipId?: string;
    },
  ) {
    const [contact] = await database.db
      .insert(customerContacts)
      .values({
        customerId,
        firstName: values.firstName,
        lastName: values.lastName,
        phone: values.phone ?? null,
        email: values.email ?? null,
        role: values.role ?? null,
        isPrimary: values.isPrimary ?? false,
        isBillingContact: values.isBillingContact ?? false,
        // A removal records the moment and the member together, which is what
        // `customer_contacts_removal_state_check` requires (`ADR-022` D8).
        removedAt:
          values.removedByMembershipId === undefined ? null : new Date(),
        removedByMembershipId: values.removedByMembershipId ?? null,
      })
      .returning();
    return contact;
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

  /**
   * The Customer behind the assigned Job (`BR-092`; `ADR-021` D1, D2).
   *
   * The read asks a **second** authorization question beside the one that admitted the caller to the
   * Job: `customers.view` or `customers.view_assigned` puts the Customer's own contact details in the
   * answer, and a caller holding neither reads the same Job without them rather than being refused the
   * Job they are assigned to (`BR-009`, `BR-011`). The scope is the assignment, never the Customer.
   */

  it('reports the customer of an assigned Job to a field caller holding customers.view_assigned', async () => {
    const session = await signInFor([
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
      CUSTOMER_PERMISSIONS.VIEW_ASSIGNED,
    ]);
    const { job, customer } = await jobWithCrew(
      session.membershipId,
      'SCHEDULED',
      CUSTOMER_CONTACT,
    );
    // A contact person with everything a contact row can hold. What reaches the Technician is the
    // fields `BR-092` names — the name, the phone number, the email address and which contact person is
    // the Customer's flagged primary (`BR-095`) — and the billing-contact flag, the free-text `role` and
    // the rest of the row are not part of the field read.
    await newContact(customer.id, {
      firstName: 'John',
      lastName: 'Smith',
      phone: '+15551234567',
      email: 'john@example.com',
      role: 'Site manager',
      isPrimary: true,
      isBillingContact: true,
    });

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.customerContactDetails).toEqual({
      email: CUSTOMER_CONTACT.email,
      phone: CUSTOMER_CONTACT.phone,
      notes: CUSTOMER_CONTACT.notes,
      contacts: [
        {
          firstName: 'John',
          lastName: 'Smith',
          phone: '+15551234567',
          email: 'john@example.com',
          isPrimary: true,
        },
      ],
    });
    // No billing field is on the wire, and neither is the contact's write token: `BR-092` excludes the
    // billing-contact flag and the free-text `role`, and a read has no use for a `version` (`BR-041`).
    const block = response.body.customerContactDetails as {
      contacts: Record<string, unknown>[];
    };
    expect(Object.keys(block).sort()).toEqual([
      'contacts',
      'email',
      'notes',
      'phone',
    ]);
    expect(Object.keys(block.contacts[0] ?? {}).sort()).toEqual([
      'email',
      'firstName',
      'isPrimary',
      'lastName',
      'phone',
    ]);
  });

  it('orders the contacts of the customer the same way the office reads them', async () => {
    // `ADR-022` D3: the Technician reads the same contacts the office does, in the same order —
    // primary first, then the rest oldest-first (`BR-095`) — so the two surfaces cannot disagree about
    // which contact comes first.
    const session = await signInFor([
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
      CUSTOMER_PERMISSIONS.VIEW_ASSIGNED,
    ]);
    const { job, customer } = await jobWithCrew(
      session.membershipId,
      'SCHEDULED',
      CUSTOMER_CONTACT,
    );
    await newContact(customer.id, { firstName: 'First', lastName: 'Recorded' });
    const primary = await newContact(customer.id, {
      firstName: 'Promoted',
      lastName: 'Primary',
      isPrimary: true,
    });

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    const contacts = (
      response.body.customerContactDetails as {
        contacts: { firstName: string; lastName: string }[];
      }
    ).contacts;
    expect(contacts.map((contact) => contact.lastName)).toEqual([
      primary.lastName,
      'Recorded',
    ]);
  });

  it('does not disclose a removed contact through an assigned Job', async () => {
    // A removal takes a contact out of the ordinary view (`BR-095`; `ADR-022` D8), and the field view
    // is an ordinary view: a Technician is not handed a person the organization has removed.
    const session = await signInFor([
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
      CUSTOMER_PERMISSIONS.VIEW_ASSIGNED,
    ]);
    const { job, customer, actor } = await jobWithCrew(
      session.membershipId,
      'SCHEDULED',
      CUSTOMER_CONTACT,
    );
    await newContact(customer.id, {
      firstName: 'Kept',
      lastName: 'Contact',
      isPrimary: true,
    });
    await newContact(customer.id, {
      firstName: 'Removed',
      lastName: 'Contact',
      removedByMembershipId: actor.id,
    });

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    const contacts = (
      response.body.customerContactDetails as {
        contacts: { firstName: string }[];
      }
    ).contacts;
    expect(contacts.map((contact) => contact.firstName)).toEqual(['Kept']);
  });

  it('reports no customer contact details to a field caller without the customer capability', async () => {
    // `BR-092` adds the capability and `BR-009` keeps the field read of the Job itself where it was: the
    // technician still opens the Job they are assigned to, and the projection reports the block as
    // absent rather than refusing the read (`BR-007`, `BR-011`).
    const session = await signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
    const { job } = await jobWithCrew(
      session.membershipId,
      'SCHEDULED',
      CUSTOMER_CONTACT,
    );

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.customerContactDetails).toBeNull();
    // The block is an addition, never a substitution: the rest of the Job is exactly as it was.
    expect(response.body.customerName).toBe('Field Customer');
    expect(response.body.selectedVisit).not.toBeNull();
  });

  it('reports a partly known customer with the absent fields as null', async () => {
    const session = await signInFor([
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
      CUSTOMER_PERMISSIONS.VIEW_ASSIGNED,
    ]);
    const { job } = await jobWithCrew(session.membershipId, 'SCHEDULED', {
      phone: '+15145550142',
    });

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.customerContactDetails).toEqual({
      email: null,
      phone: '+15145550142',
      notes: null,
      contacts: [],
    });
  });

  it('reports a Customer with no contact persons as an empty list', async () => {
    // Zero contacts is a legal state (`BR-095`), so the block answers the absence with `[]` rather than
    // omitting the member a client reads it from.
    const session = await signInFor([
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
      CUSTOMER_PERMISSIONS.VIEW_ASSIGNED,
    ]);
    const { job } = await jobWithCrew(
      session.membershipId,
      'SCHEDULED',
      CUSTOMER_CONTACT,
    );

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.customerContactDetails.contacts).toEqual([]);
  });

  it('reports the customer through the office capability the organization already reads with', async () => {
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);
    const other = await newMembership(organizationId);
    const { job, customer } = await jobWithCrew(
      other.id,
      'SCHEDULED',
      CUSTOMER_CONTACT,
    );
    // The same contacts the field read reports, read by the same second question: which capability
    // admitted the caller decides the scope of the Job, not what the customer block carries (`BR-092`).
    await newContact(customer.id, {
      firstName: 'Jane',
      lastName: 'Doe',
      isPrimary: true,
    });

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.customerContactDetails).toEqual({
      email: CUSTOMER_CONTACT.email,
      phone: CUSTOMER_CONTACT.phone,
      notes: CUSTOMER_CONTACT.notes,
      contacts: [
        {
          firstName: 'Jane',
          lastName: 'Doe',
          phone: null,
          email: null,
          isPrimary: true,
        },
      ],
    });
  });

  it('does not reach a customer through a Job the field caller is not assigned to', async () => {
    // `BR-092` scopes the read by the assignment, never by the Customer: the capability reads the
    // customer of a Job the caller's own crew includes and nothing else, so a Job it does not reach is
    // not found and no customer is disclosed (`ADR-019` D2).
    const session = await signInFor([
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
      CUSTOMER_PERMISSIONS.VIEW_ASSIGNED,
    ]);
    const other = await newMembership(organizationId);
    const { job } = await jobWithCrew(other.id, 'SCHEDULED', CUSTOMER_CONTACT);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_NOT_FOUND');
      });
  });

  it('does not admit a caller to a Job on the customer capability alone', async () => {
    // The customer capability answers the read's second question; it is never a substitute for the one
    // that answers the first (`BR-006`, `BR-092`; `ADR-021` D2). A caller holding it and nothing else is
    // refused the Job entirely, exactly as before this slice.
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW_ASSIGNED]);
    const other = await newMembership(organizationId);
    const { job } = await jobWithCrew(other.id, 'SCHEDULED', CUSTOMER_CONTACT);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });
});
