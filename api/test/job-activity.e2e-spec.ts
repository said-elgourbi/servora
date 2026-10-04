import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
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
  jobDocuments,
  jobQuoteHistory,
  jobQuoteWorkflows,
  jobStatusHistory,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  properties,
  propertyCustomerRelationships,
  userProfiles,
  visitNotes,
  visits,
  visitStatusHistory,
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
 * `GET /jobs/:id/activity` — the unified, chronological Job Activity read (`BR-080`).
 *
 * The projection is asserted against real records, because what it derives is the read's contract:
 * that Job-level and Visit-level events are merged newest-first, that a Visit-level event carries the
 * Visit's human-readable sequence while a Job-level event carries none, and that member names are
 * resolved from profiles rather than fabricated (`BR-020`, `BR-041`, `BR-080`).
 */
describe('job activity (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Job Activity Org',
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

  async function signInFor(granted: readonly PermissionCode[]) {
    const email = uniqueEmail('job-activity');
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
          deviceId: `job-activity-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return {
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  async function newNamedMembership(firstName: string, lastName: string) {
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, organizationId);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId: role.id })
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

  async function newJob(customerId: string, propertyId: string) {
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId,
        propertyId,
        propertyAddressSnapshot: { addressLine1: '1 Main Street' },
        title: `Job ${jobNumberSequence}`,
      })
      .returning();
    return job;
  }

  async function newVisit(jobId: string, propertyId: string, createdAt: Date) {
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId,
        jobId,
        propertyId,
        locationAddressSnapshot: { addressLine1: '1 Main Street' },
        status: 'SCHEDULED',
        scheduledStart: new Date(Date.now() + 3_600_000),
        scheduledEnd: new Date(Date.now() + 7_200_000),
        createdAt,
      })
      .returning();
    return visit;
  }

  /** Puts a member on a Visit's crew, as a dispatcher would (`BR-068`). */
  async function assign(visitId: string, technicianMembershipId: string) {
    await database.db.insert(visitTechnicians).values({
      organizationId,
      visitId,
      technicianMembershipId,
      roleCode: 'LEAD',
    });
  }

  /** A Job with two Visits and a Job status change, a Visit status change and a Visit note. */
  async function activityFixture() {
    const actor = await newNamedMembership('John', 'Smith');
    const customer = await newCustomer('Martha Reynolds');
    const property = await newProperty('987 Cedar Lane');
    await linkProperty(property.id, customer.id, actor.id);
    const job = await newJob(customer.id, property.id);

    const base = Date.now();
    const firstVisit = await newVisit(
      job.id,
      property.id,
      new Date(base - 2_000),
    );
    const secondVisit = await newVisit(
      job.id,
      property.id,
      new Date(base - 1_000),
    );

    await database.db.insert(jobStatusHistory).values({
      organizationId,
      jobId: job.id,
      fromStatus: 'NEW',
      toStatus: 'ACTIVE',
      actorMembershipId: actor.id,
      recordedAt: new Date(base),
    });

    await database.db.insert(visitStatusHistory).values({
      organizationId,
      visitId: firstVisit.id,
      fromStatus: 'SCHEDULED',
      toStatus: 'EN_ROUTE',
      actorMembershipId: actor.id,
      recordedAt: new Date(base + 1_000),
    });

    const [note] = await database.db
      .insert(visitNotes)
      .values({
        organizationId,
        visitId: secondVisit.id,
        authorMembershipId: actor.id,
        body: 'Found a damaged capacitor.',
        recordedAt: new Date(base + 2_000),
      })
      .returning();

    return { job, firstVisit, secondVisit, note };
  }

  /** A Job with quote workflow history and quote document rows. */
  async function quoteActivityFixture() {
    const actor = await newNamedMembership('Quote', 'Manager');
    const customer = await newCustomer('Quote Activity Co');
    const property = await newProperty('42 Quote Avenue');
    await linkProperty(property.id, customer.id, actor.id);
    const job = await newJob(customer.id, property.id);
    const base = Date.now();
    const [assessment] = await database.db
      .insert(visits)
      .values({
        organizationId,
        jobId: job.id,
        propertyId: property.id,
        locationAddressSnapshot: { addressLine1: '42 Quote Avenue' },
        purposeCode: 'ASSESSMENT',
        status: 'COMPLETED',
        outcomeCode: 'QUOTE_REQUIRED',
        outcomeSummary: 'Customer approval required.',
        outcomeRecordedAt: new Date(base - 4_000),
        outcomeRecordedByMembershipId: actor.id,
        createdAt: new Date(base - 5_000),
      })
      .returning();
    const [workflow] = await database.db
      .insert(jobQuoteWorkflows)
      .values({
        organizationId,
        jobId: job.id,
        sourceVisitId: assessment.id,
        state: 'APPROVED',
        sentAt: new Date(base + 1_000),
        sentByMembershipId: actor.id,
        decidedAt: new Date(base + 2_000),
        decidedByMembershipId: actor.id,
      })
      .returning();
    await database.db.insert(jobQuoteHistory).values([
      {
        organizationId,
        quoteWorkflowId: workflow.id,
        jobId: job.id,
        sourceVisitId: assessment.id,
        event: 'REQUIRED',
        fromState: null,
        toState: 'REQUIRED',
        actorMembershipId: actor.id,
        recordedAt: new Date(base),
      },
      {
        organizationId,
        quoteWorkflowId: workflow.id,
        jobId: job.id,
        sourceVisitId: assessment.id,
        event: 'SENT',
        fromState: 'REQUIRED',
        toState: 'SENT',
        note: 'Sent by email.',
        actorMembershipId: actor.id,
        recordedAt: new Date(base + 1_000),
      },
      {
        organizationId,
        quoteWorkflowId: workflow.id,
        jobId: job.id,
        sourceVisitId: assessment.id,
        event: 'APPROVED',
        fromState: 'SENT',
        toState: 'APPROVED',
        note: 'Approved by phone.',
        actorMembershipId: actor.id,
        recordedAt: new Date(base + 3_000),
      },
    ]);
    const [document] = await database.db
      .insert(jobDocuments)
      .values({
        organizationId,
        jobId: job.id,
        documentType: 'QUOTE',
        displayName: 'Assessment quote.pdf',
        documentVersion: 1,
        objectKey: `jobs/documents/${organizationId}/${job.id}/quote.pdf`,
        contentType: 'application/pdf',
        byteSize: 1234,
        uploadedByMembershipId: actor.id,
        recordedAt: new Date(base + 2_000),
      })
      .returning();

    return { job, assessment, document };
  }
  it('refuses an unauthenticated caller with 401', async () => {
    const fixture = await activityFixture();

    await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}/activity`)
      .expect(401);
  });

  it('refuses a caller without customers.view with 403', async () => {
    const fixture = await activityFixture();
    const session = await signInFor([CUSTOMER_PERMISSIONS.CREATE]);

    await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}/activity`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  /*
   * The field caller (`BR-009`; `ADR-019` D1, D2). The activity is a projection of the same records the
   * Job read answers with, so it is reached through the Job read's guard and carries the same
   * assignment scope: a field caller reads the activity of a Job their own crew includes, and a Job it
   * does not reach is reported as not found rather than forbidden.
   */

  it('answers the activity to a field caller assigned to one of the Job Visits', async () => {
    const fixture = await activityFixture();
    const session = await signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
    await assign(fixture.secondVisit.id, session.membershipId);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}/activity`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.jobId).toBe(fixture.job.id);
    expect(response.body.events).toHaveLength(3);
  });

  it('answers not found for an activity of a Job a field caller is not assigned to', async () => {
    const fixture = await activityFixture();
    const session = await signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);

    await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}/activity`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_NOT_FOUND');
      });
  });

  it('answers not found for a Job the caller organization does not own', async () => {
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);
    const otherOrganization = await createTestOrganization(database.db, {
      name: 'Other Job Activity Org',
    });
    database.cleanup.trackOrganization(otherOrganization.id);

    const [foreignCustomer] = await database.db
      .insert(customers)
      .values({
        organizationId: otherOrganization.id,
        type: 'COMPANY',
        displayName: 'Foreign Co',
      })
      .returning();
    const [foreignJob] = await database.db
      .insert(jobs)
      .values({
        organizationId: otherOrganization.id,
        jobNumber: 9999,
        customerId: foreignCustomer.id,
        title: 'Foreign Job',
      })
      .returning();

    await request(app.getHttpServer())
      .get(`/jobs/${foreignJob.id}/activity`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_NOT_FOUND');
      });
  });

  it('merges Job-level and Visit-level events newest-first, with visit sequences and resolved names', async () => {
    const fixture = await activityFixture();
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}/activity`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.jobId).toBe(fixture.job.id);
    expect(response.body.events).toHaveLength(3);

    // Newest first: the note, then the Visit status change, then the Job status change.
    const [note, visitStatus, jobStatus] = response.body.events as Array<{
      kind: string;
      visitSequence: number | null;
      actorName: string | null;
      body: string | null;
      toStatus: string | null;
      fromStatus: string | null;
    }>;

    expect(note.kind).toBe('VISIT_NOTE_ADDED');
    expect(note.visitSequence).toBe(2);
    expect(note.actorName).toBe('John Smith');
    expect(note.body).toBe('Found a damaged capacitor.');

    expect(visitStatus.kind).toBe('VISIT_STATUS_CHANGED');
    expect(visitStatus.visitSequence).toBe(1);
    expect(visitStatus.fromStatus).toBe('SCHEDULED');
    expect(visitStatus.toStatus).toBe('EN_ROUTE');

    // A Job-level event carries no visit sequence.
    expect(jobStatus.kind).toBe('JOB_STATUS_CHANGED');
    expect(jobStatus.visitSequence).toBeNull();
    expect(jobStatus.fromStatus).toBe('NEW');
    expect(jobStatus.toStatus).toBe('ACTIVE');
  });

  it('lets a manager correct a visit note and reports the edited marker', async () => {
    const fixture = await activityFixture();
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      JOB_PERMISSIONS.UPDATE,
    ]);

    const response = await request(app.getHttpServer())
      .patch(`/jobs/${fixture.job.id}/visits/notes/${fixture.note.id}`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ body: 'Found a damaged blower capacitor.' })
      .expect(200);

    const note = response.body.events.find(
      (event: { id: string }) => event.id === fixture.note.id,
    );
    expect(note).toMatchObject({
      kind: 'VISIT_NOTE_ADDED',
      body: 'Found a damaged blower capacitor.',
      noteRemovedAt: null,
      noteRemovalReason: null,
    });
    expect(note.noteEditedAt).toEqual(expect.any(String));
  });

  it('soft-deletes a visit note from ordinary activity but keeps it in the manager audit read', async () => {
    const fixture = await activityFixture();
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      JOB_PERMISSIONS.UPDATE,
    ]);

    const removal = await request(app.getHttpServer())
      .post(`/jobs/${fixture.job.id}/visits/notes/${fixture.note.id}/removal`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .send({ reason: 'Duplicate note entered by mistake.' })
      .expect(201);

    expect(
      removal.body.events.some(
        (event: { id: string }) => event.id === fixture.note.id,
      ),
    ).toBe(false);

    const ordinary = await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}/activity`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
    expect(
      ordinary.body.events.some(
        (event: { id: string }) => event.id === fixture.note.id,
      ),
    ).toBe(false);

    const audit = await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}/activity?includeRemovedEvidence=true`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    const note = audit.body.events.find(
      (event: { id: string }) => event.id === fixture.note.id,
    );
    expect(note).toMatchObject({
      kind: 'VISIT_NOTE_ADDED',
      body: 'Found a damaged capacitor.',
      noteRemovalReason: 'Duplicate note entered by mistake.',
    });
    expect(note.noteRemovedAt).toEqual(expect.any(String));
  });

  it('projects quote workflow and quote document activity', async () => {
    const fixture = await quoteActivityFixture();
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${fixture.job.id}/activity`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    const events = response.body.events as Array<Record<string, unknown>>;
    expect(events.map((event) => event.kind)).toEqual([
      'JOB_QUOTE_APPROVED',
      'JOB_QUOTE_DOCUMENT_UPLOADED',
      'JOB_QUOTE_SENT',
      'JOB_QUOTE_REQUIRED',
    ]);

    expect(events[0]).toMatchObject({
      kind: 'JOB_QUOTE_APPROVED',
      actorName: 'Quote Manager',
      visitSequence: 1,
      quoteFromState: 'SENT',
      quoteToState: 'APPROVED',
      quoteNote: 'Approved by phone.',
    });
    expect(events[1]).toMatchObject({
      kind: 'JOB_QUOTE_DOCUMENT_UPLOADED',
      actorName: 'Quote Manager',
      visitSequence: null,
      quoteDocumentId: fixture.document.id,
      quoteDocumentDisplayName: 'Assessment quote.pdf',
      quoteDocumentVersion: 1,
      quoteDocumentIsCurrent: true,
      quoteDocumentByteSize: 1234,
    });
    expect(events[2]).toMatchObject({
      kind: 'JOB_QUOTE_SENT',
      visitSequence: 1,
      quoteFromState: 'REQUIRED',
      quoteToState: 'SENT',
      quoteNote: 'Sent by email.',
    });
    expect(events[3]).toMatchObject({
      kind: 'JOB_QUOTE_REQUIRED',
      visitSequence: 1,
      quoteFromState: null,
      quoteToState: 'REQUIRED',
      quoteNote: null,
    });
  });

  it('answers an empty activity for a Job that has no history', async () => {
    const actor = await newNamedMembership('Empty', 'Actor');
    const customer = await newCustomer('No History Co');
    const property = await newProperty('4 Empty Street');
    await linkProperty(property.id, customer.id, actor.id);
    const job = await newJob(customer.id, property.id);
    const session = await signInFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/jobs/${job.id}/activity`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(response.body.events).toEqual([]);
  });
});
