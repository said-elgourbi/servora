import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { and, asc, eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  JOB_PERMISSIONS,
  VISIT_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
  customers,
  jobDocuments,
  jobQuoteHistory,
  jobQuoteWorkflows,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  visits,
  visitTechnicians,
} from '../src/database/schema.js';
import { hashPassword } from '../src/users/password-hasher.js';
import { FakeObjectStorage } from './support/fake-object-storage.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  uniqueEmail,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

const PASSWORD = 'servora-e2e-password';
const PDF_V1 = Buffer.from('%PDF-1.7\nquote version one\n%%EOF\n');
const PDF_V2 = Buffer.from('%PDF-1.7\nquote version two\n%%EOF\n');

describe('job documents (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let storage: FakeObjectStorage;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Job Document Org',
    });
    database.cleanup.trackOrganization(organization.id);
    organizationId = organization.id;

    storage = new FakeObjectStorage();
    const moduleFixture = await Test.createTestingModule({
      imports: [AppModule],
    })
      .overrideProvider(FakeObjectStorage.token)
      .useValue(storage)
      .compile();
    app = moduleFixture.createNestApplication({ logger: false });
    await app.init();
  });

  afterAll(async () => {
    await app.close();
    await database.dispose();
  });

  beforeEach(() => {
    storage.clear();
  });

  async function signInFor(granted: readonly PermissionCode[]) {
    const email = uniqueEmail('job-document');
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
      await database.db.insert(organizationMemberPermissions).values({
        organizationId,
        memberId: member.id,
        permissionId: await permissionIdFor(code),
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
          deviceId: `job-document-device-${deviceSequence}`,
        },
      })
      .expect(200);

    return {
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  async function permissionIdFor(code: PermissionCode): Promise<string> {
    const [permission] = await database.db
      .select({ id: permissions.id })
      .from(permissions)
      .where(eq(permissions.code, code))
      .limit(1);
    if (permission === undefined) {
      throw new Error(`Missing permission seed: ${code}`);
    }
    return permission.id;
  }

  async function jobWithQuoteWorkflow(
    actorMembershipId: string,
    state: 'REQUIRED' | 'SENT' = 'REQUIRED',
  ) {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId,
        type: 'COMPANY',
        displayName: 'Quote Document Customer',
      })
      .returning();
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId: customer.id,
        title: `Quote document job ${jobNumberSequence}`,
        status: 'ACTIVE',
      })
      .returning();
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId,
        jobId: job.id,
        purposeCode: 'ASSESSMENT',
        status: 'COMPLETED',
        outcomeCode: 'QUOTE_REQUIRED',
        outcomeSummary: 'Authorization required.',
        outcomeRecordedAt: new Date(),
        outcomeRecordedByMembershipId: actorMembershipId,
      })
      .returning();
    const [workflow] = await database.db
      .insert(jobQuoteWorkflows)
      .values({
        organizationId,
        jobId: job.id,
        sourceVisitId: visit.id,
        state,
        ...(state === 'SENT'
          ? {
              sentAt: new Date(),
              sentByMembershipId: actorMembershipId,
            }
          : {}),
      })
      .returning();
    return { job, visit, workflow };
  }

  function uploadDocument(
    session: { accessToken: string },
    jobId: string,
    file: Buffer = PDF_V1,
    fields: Record<string, string> = {
      documentType: 'QUOTE',
      displayName: 'Assessment quote.pdf',
    },
  ) {
    let builder = request(app.getHttpServer())
      .post(`/jobs/${jobId}/documents`)
      .set('Authorization', `Bearer ${session.accessToken}`);
    for (const [key, value] of Object.entries(fields)) {
      builder = builder.field(key, value);
    }
    return builder.attach('file', file, {
      filename: 'quote.pdf',
      contentType: 'application/pdf',
    });
  }

  it('uploads and reads the current quote PDF', async () => {
    const office = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const { job } = await jobWithQuoteWorkflow(office.membershipId);

    const uploaded = await uploadDocument(office, job.id).expect(201);
    expect(uploaded.body).toMatchObject({
      jobId: job.id,
      documentType: 'QUOTE',
      displayName: 'Assessment quote.pdf',
      documentVersion: 1,
      isCurrent: true,
      contentType: 'application/pdf',
      byteSize: PDF_V1.byteLength,
      uploadedByMembershipId: office.membershipId,
      supersededAt: null,
      supersededByJobDocumentId: null,
    });
    expect(storage.puts).toHaveLength(1);
    expect(storage.puts[0]).toMatchObject({
      body: PDF_V1,
      contentType: 'application/pdf',
    });
    expect(storage.puts[0]?.key).toContain(
      `jobs/documents/${organizationId}/${job.id}/`,
    );

    const byId = await request(app.getHttpServer())
      .get(`/jobs/${job.id}/documents/${uploaded.body.id}/content`)
      .set('Authorization', `Bearer ${office.accessToken}`)
      .expect(200);
    expect(byId.headers['content-type']).toContain('application/pdf');
    expect(Buffer.from(byId.body)).toEqual(PDF_V1);

    const current = await request(app.getHttpServer())
      .get(`/jobs/${job.id}/documents/quote/content`)
      .set('Authorization', `Bearer ${office.accessToken}`)
      .expect(200);
    expect(Buffer.from(current.body)).toEqual(PDF_V1);
  });

  it('allows an assigned technician to read the current quote PDF without quote write access', async () => {
    const office = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const technician = await signInFor([VISIT_PERMISSIONS.VIEW_ASSIGNED]);
    const { job, visit } = await jobWithQuoteWorkflow(office.membershipId);
    const uploaded = await uploadDocument(office, job.id).expect(201);
    await database.db.insert(visitTechnicians).values({
      organizationId,
      visitId: visit.id,
      technicianMembershipId: technician.membershipId,
      roleCode: 'LEAD',
    });

    await uploadDocument(technician, job.id).expect(403);

    const details = await request(app.getHttpServer())
      .get(`/jobs/${job.id}`)
      .set('Authorization', `Bearer ${technician.accessToken}`)
      .expect(200);
    expect(details.body.quote.currentDocument.id).toBe(uploaded.body.id);
    expect(details.body.quote.allowedActions).toEqual(['READ_DOCUMENT']);

    const current = await request(app.getHttpServer())
      .get(`/jobs/${job.id}/documents/quote/content`)
      .set('Authorization', `Bearer ${technician.accessToken}`)
      .expect(200);
    expect(Buffer.from(current.body)).toEqual(PDF_V1);

    const byId = await request(app.getHttpServer())
      .get(`/jobs/${job.id}/documents/${uploaded.body.id}/content`)
      .set('Authorization', `Bearer ${technician.accessToken}`)
      .expect(200);
    expect(Buffer.from(byId.body)).toEqual(PDF_V1);
  });

  it('replaces the current quote PDF and requires a sent quote to be sent again', async () => {
    const office = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const { job } = await jobWithQuoteWorkflow(office.membershipId, 'REQUIRED');

    const first = await uploadDocument(office, job.id, PDF_V1).expect(201);
    await database.db
      .update(jobQuoteWorkflows)
      .set({
        state: 'SENT',
        sentAt: new Date(),
        sentByMembershipId: office.membershipId,
        version: 1,
      })
      .where(
        and(
          eq(jobQuoteWorkflows.organizationId, organizationId),
          eq(jobQuoteWorkflows.jobId, job.id),
        ),
      );

    const second = await uploadDocument(office, job.id, PDF_V2, {
      documentType: 'QUOTE',
      displayName: 'Updated quote.pdf',
    }).expect(201);

    expect(second.body).toMatchObject({
      documentVersion: 2,
      isCurrent: true,
      displayName: 'Updated quote.pdf',
    });

    const rows = await database.db
      .select({
        id: jobDocuments.id,
        documentVersion: jobDocuments.documentVersion,
        isCurrent: jobDocuments.isCurrent,
        supersededByJobDocumentId: jobDocuments.supersededByJobDocumentId,
      })
      .from(jobDocuments)
      .where(
        and(
          eq(jobDocuments.organizationId, organizationId),
          eq(jobDocuments.jobId, job.id),
        ),
      )
      .orderBy(asc(jobDocuments.documentVersion));
    expect(rows).toEqual([
      {
        id: first.body.id,
        documentVersion: 1,
        isCurrent: false,
        supersededByJobDocumentId: second.body.id,
      },
      {
        id: second.body.id,
        documentVersion: 2,
        isCurrent: true,
        supersededByJobDocumentId: null,
      },
    ]);

    const [workflow] = await database.db
      .select({
        state: jobQuoteWorkflows.state,
        version: jobQuoteWorkflows.version,
      })
      .from(jobQuoteWorkflows)
      .where(
        and(
          eq(jobQuoteWorkflows.organizationId, organizationId),
          eq(jobQuoteWorkflows.jobId, job.id),
        ),
      )
      .limit(1);
    expect(workflow).toEqual({ state: 'REQUIRED', version: 2 });

    const history = await database.db
      .select({
        event: jobQuoteHistory.event,
        fromState: jobQuoteHistory.fromState,
        toState: jobQuoteHistory.toState,
        actorMembershipId: jobQuoteHistory.actorMembershipId,
      })
      .from(jobQuoteHistory)
      .where(
        and(
          eq(jobQuoteHistory.organizationId, organizationId),
          eq(jobQuoteHistory.jobId, job.id),
        ),
      );
    expect(history).toEqual([
      {
        event: 'REQUIRED',
        fromState: 'SENT',
        toState: 'REQUIRED',
        actorMembershipId: office.membershipId,
      },
    ]);

    const current = await request(app.getHttpServer())
      .get(`/jobs/${job.id}/documents/quote/content`)
      .set('Authorization', `Bearer ${office.accessToken}`)
      .expect(200);
    expect(Buffer.from(current.body)).toEqual(PDF_V2);
  });

  it('refuses a quote document before the quote workflow exists', async () => {
    const office = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId,
        type: 'COMPANY',
        displayName: 'No Quote Customer',
      })
      .returning();
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId: customer.id,
        title: 'No quote workflow job',
      })
      .returning();

    const response = await uploadDocument(office, job.id).expect(404);
    expect(response.body).toMatchObject({ code: 'QUOTE_WORKFLOW_NOT_FOUND' });
  });

  it('refuses non-PDF quote bytes', async () => {
    const office = await signInFor([JOB_PERMISSIONS.UPDATE]);
    const { job } = await jobWithQuoteWorkflow(office.membershipId);

    const response = await uploadDocument(
      office,
      job.id,
      Buffer.from('not a pdf'),
    ).expect(400);
    expect(response.body).toMatchObject({ code: 'VALIDATION_FAILED' });
    expect(storage.puts).toHaveLength(0);
  });
});
