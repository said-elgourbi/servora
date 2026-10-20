import { randomUUID } from 'node:crypto';
import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  VISIT_PERMISSIONS,
  CUSTOMER_PERMISSIONS,
  PROPERTY_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
  adHocWorkReports,
  customers,
  jobs,
  organizationJobNumberCounters,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  properties,
  propertyCustomerRelationships,
  pushAssignmentEvents,
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
 * Technician ad-hoc work reporting (Phase 2): the API foundation.
 *
 * These tests stay at the HTTP boundary because the guarantees are submission-only creation,
 * idempotency by client operation id, server-enforced customer/property/job discoverability,
 * read scoping, and authorization — the invariants `ADR-024` fixes.
 */
describe('ad-hoc work reports (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Ad-Hoc Reports Org',
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

  async function signInFor(
    granted: readonly PermissionCode[],
    scopedOrganizationId = organizationId,
  ) {
    const email = uniqueEmail('ad-hoc-reports');
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(PASSWORD),
    });
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(
      database.db,
      scopedOrganizationId,
    );
    const [member] = await database.db
      .insert(organizationMembers)
      .values({
        organizationId: scopedOrganizationId,
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
        organizationId: scopedOrganizationId,
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
          deviceId: `ad-hoc-reports-device-${deviceSequence}`,
        },
      })
      .expect(200);

    return {
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
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
        addressLine1: '412 Ad-Hoc Road',
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

  async function newJob(
    customerId: string,
    property: {
      id: string;
      addressLine1: string;
      city: string;
      province: string;
      postalCode: string;
    },
    status: 'NEW' | 'ACTIVE' | 'COMPLETED' | 'CANCELED' = 'ACTIVE',
  ) {
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId,
        propertyId: property.id,
        propertyAddressSnapshot: {
          addressLine1: property.addressLine1,
          city: property.city,
          province: property.province,
          postalCode: property.postalCode,
        },
        title: `Ad-Hoc Job ${jobNumberSequence}`,
        status,
      })
      .returning();
    // Keep the organization's job-number counter in step with the directly-inserted row so the
    // convert route's counter-based allocation cannot collide with a job number already in use.
    await database.db
      .insert(organizationJobNumberCounters)
      .values({ organizationId, lastJobNumber: jobNumberSequence })
      .onConflictDoUpdate({
        target: organizationJobNumberCounters.organizationId,
        set: { lastJobNumber: jobNumberSequence, updatedAt: new Date() },
      });
    return job;
  }

  async function readJobStatus(jobId: string) {
    const [job] = await database.db
      .select({ status: jobs.status })
      .from(jobs)
      .where(eq(jobs.id, jobId))
      .limit(1);
    return job?.status;
  }

  function submitReport(token: string, body: Record<string, unknown>) {
    return request(app.getHttpServer())
      .post('/jobs/ad-hoc-work-reports')
      .set('Authorization', `Bearer ${token}`)
      .send(body);
  }

  function validReportBody(overrides: Record<string, unknown> = {}) {
    return {
      workStartedAt: '2026-10-01T13:00:00.000Z',
      workEndedAt: '2026-10-01T15:00:00.000Z',
      outcomeCode: 'RESOLVED',
      summary: 'Replaced the leaking valve.',
      ...(overrides.customerId
        ? {}
        : {
            reportedCustomerName: 'Maple Leaf Bakery',
            reportedCustomerPhone: '+16135550123',
            reportedCustomerAddress: '210 Sparks Street, Ottawa',
          }),
      ...overrides,
    };
  }

  const resolutionPermissions = [
    VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK,
    CUSTOMER_PERMISSIONS.VIEW,
    CUSTOMER_PERMISSIONS.CREATE,
    PROPERTY_PERMISSIONS.VIEW,
    PROPERTY_PERMISSIONS.CREATE,
  ] as const;
  const propertyInput = {
    addressLine1: '210 Sparks Street',
    city: 'Ottawa',
    province: 'ON',
    postalCode: 'K1P 5B4',
  };
  const customerInput = {
    type: 'COMPANY',
    displayName: 'Edited Bakery',
    phone: '+16135550999',
    company: { legalName: 'Edited Bakery Inc.' },
  };

  it('resolves an unknown report atomically, preserves provenance and converts exactly once', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor(resolutionPermissions);
    const created = await submitReport(
      reporter.accessToken,
      validReportBody(),
    ).expect(201);
    const beforeCustomers = await database.db
      .select({ id: customers.id })
      .from(customers)
      .where(eq(customers.organizationId, organizationId));
    const beforeProperties = await database.db
      .select({ id: properties.id })
      .from(properties)
      .where(eq(properties.organizationId, organizationId));
    const body = {
      newCustomer: customerInput,
      newProperty: propertyInput,
      expectedStatus: 'PENDING',
      expectedVersion: created.body.version,
    };
    const resolve = () =>
      request(app.getHttpServer())
        .post(`/jobs/ad-hoc-work-reports/${created.body.id}/resolve-location`)
        .set('Authorization', `Bearer ${reviewer.accessToken}`)
        .send(body);
    const responses = await Promise.all([resolve(), resolve()]);
    expect(responses.map((response) => response.status).sort()).toEqual([
      200, 409,
    ]);
    const resolved = responses.find(
      (response) => response.status === 200,
    )!.body;
    expect(resolved).toMatchObject({
      status: 'PENDING',
      version: created.body.version + 1,
      reportedCustomerName: 'Maple Leaf Bakery',
      reportedCustomerPhone: '+16135550123',
      reportedCustomerAddress: '210 Sparks Street, Ottawa',
      customerName: 'Edited Bakery',
      createdJobId: null,
      createdVisitId: null,
      reviewedAt: null,
    });
    expect(
      (
        await database.db
          .select()
          .from(customers)
          .where(eq(customers.organizationId, organizationId))
      ).length,
    ).toBe(beforeCustomers.length + 1);
    expect(
      (
        await database.db
          .select()
          .from(properties)
          .where(eq(properties.organizationId, organizationId))
      ).length,
    ).toBe(beforeProperties.length + 1);
    await resolve().expect(409);
    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/resolve-location`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ ...body, expectedVersion: resolved.version })
      .expect(409);
    const convert = () =>
      request(app.getHttpServer())
        .post(`/jobs/ad-hoc-work-reports/${created.body.id}/convert`)
        .set('Authorization', `Bearer ${reviewer.accessToken}`)
        .send({
          title: 'Reconciled work',
          expectedStatus: 'PENDING',
          expectedVersion: resolved.version,
        });
    const conversions = await Promise.all([convert(), convert()]);
    expect(conversions.map((response) => response.status).sort()).toEqual([
      201, 409,
    ]);
    const job = conversions.find((response) => response.status === 201)!.body;
    expect(
      await database.db
        .select()
        .from(pushAssignmentEvents)
        .where(eq(pushAssignmentEvents.jobId, job.id)),
    ).toHaveLength(0);
    jobNumberSequence = Math.max(jobNumberSequence, job.jobNumber);
    expect(
      (await database.db.select().from(visits).where(eq(visits.jobId, job.id)))
        .length,
    ).toBe(1);
    const [report] = await database.db
      .select()
      .from(adHocWorkReports)
      .where(eq(adHocWorkReports.id, created.body.id));
    expect(report.status).toBe('CONVERTED');
    expect(report.createdJobId).toBe(job.id);
  });

  it('selects an existing customer/property and can create a property for a known customer', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor(resolutionPermissions);
    const customer = await newCustomer('Resolution Match');
    const property = await newProperty(customer.id, reviewer.membershipId);
    for (const selection of [
      { existingPropertyId: property.id },
      { newProperty: propertyInput },
    ]) {
      const created = await submitReport(
        reporter.accessToken,
        validReportBody(),
      ).expect(201);
      const resolved = await request(app.getHttpServer())
        .post(`/jobs/ad-hoc-work-reports/${created.body.id}/resolve-location`)
        .set('Authorization', `Bearer ${reviewer.accessToken}`)
        .send({
          existingCustomerId: customer.id,
          ...selection,
          expectedStatus: 'PENDING',
          expectedVersion: created.body.version,
        })
        .expect(200);
      expect(resolved.body.customerId).toBe(customer.id);
      expect(resolved.body.status).toBe('PENDING');
      expect(resolved.body.propertyId).toBeTruthy();
    }
    const options = await request(app.getHttpServer())
      .get('/jobs/ad-hoc-work-reports/review-customer-options?q=Resolution')
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .expect(200);
    expect(
      options.body.some((option: { id: string }) => option.id === customer.id),
    ).toBe(true);
    await request(app.getHttpServer())
      .get(
        `/jobs/ad-hoc-work-reports/review-property-options?customerId=${customer.id}`,
      )
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .expect(200);
  });

  it('requires branch permissions and a version before creating canonical records', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const created = await submitReport(
      reporter.accessToken,
      validReportBody(),
    ).expect(201);
    for (const granted of [
      [],
      [VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK],
      [VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK, CUSTOMER_PERMISSIONS.CREATE],
      [VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK, PROPERTY_PERMISSIONS.CREATE],
    ] as PermissionCode[][]) {
      const reviewer = await signInFor(granted);
      await request(app.getHttpServer())
        .post(`/jobs/ad-hoc-work-reports/${created.body.id}/resolve-location`)
        .set('Authorization', `Bearer ${reviewer.accessToken}`)
        .send({
          newCustomer: customerInput,
          newProperty: propertyInput,
          expectedStatus: 'PENDING',
          expectedVersion: created.body.version,
        })
        .expect(403);
      await request(app.getHttpServer())
        .get('/jobs/ad-hoc-work-reports/review-customer-options?q=Maple')
        .set('Authorization', `Bearer ${reviewer.accessToken}`)
        .expect(403);
    }
    const reviewer = await signInFor(resolutionPermissions);
    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/resolve-location`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({
        newCustomer: customerInput,
        newProperty: propertyInput,
        expectedStatus: 'PENDING',
      })
      .expect(400);
    const [pending] = await database.db
      .select()
      .from(adHocWorkReports)
      .where(eq(adHocWorkReports.id, created.body.id));
    expect(pending.customerId).toBeNull();
    expect(pending.status).toBe('PENDING');
  });

  it('rejects foreign records and wrong customer/property pairs and rolls back creation', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor(resolutionPermissions);
    const created = await submitReport(
      reporter.accessToken,
      validReportBody(),
    ).expect(201);
    const customer = await newCustomer('Resolution owner');
    const other = await newCustomer('Other owner');
    const property = await newProperty(other.id, reviewer.membershipId);
    const foreignOrg = await createTestOrganization(database.db, {
      name: 'Foreign resolution org',
    });
    database.cleanup.trackOrganization(foreignOrg.id);
    const [foreignCustomer] = await database.db
      .insert(customers)
      .values({
        organizationId: foreignOrg.id,
        type: 'COMPANY',
        displayName: 'Foreign',
      })
      .returning();
    const foreignReporter = await signInFor(
      [VISIT_PERMISSIONS.REPORT_AD_HOC_WORK],
      foreignOrg.id,
    );
    const foreignReport = await submitReport(
      foreignReporter.accessToken,
      validReportBody(),
    ).expect(201);
    for (const body of [
      { existingCustomerId: customer.id, existingPropertyId: property.id },
      { existingCustomerId: foreignCustomer.id, newProperty: propertyInput },
      { existingCustomerId: customer.id, existingPropertyId: randomUUID() },
    ]) {
      await request(app.getHttpServer())
        .post(`/jobs/ad-hoc-work-reports/${created.body.id}/resolve-location`)
        .set('Authorization', `Bearer ${reviewer.accessToken}`)
        .send({ ...body, expectedStatus: 'PENDING', expectedVersion: 1 })
        .expect(404);
    }
    await request(app.getHttpServer())
      .post(
        `/jobs/ad-hoc-work-reports/${foreignReport.body.id}/resolve-location`,
      )
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({
        newCustomer: customerInput,
        newProperty: propertyInput,
        expectedStatus: 'PENDING',
        expectedVersion: 1,
      })
      .expect(404);
    const [pending] = await database.db
      .select()
      .from(adHocWorkReports)
      .where(eq(adHocWorkReports.id, created.body.id));
    expect(pending.version).toBe(1);
    expect(pending.customerId).toBeNull();
  });

  it('creates a PENDING report and reflects the selected customer and property', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customer = await newCustomer('Report Customer A');
    const property = await newProperty(customer.id, reviewer.membershipId);
    const job = await newJob(customer.id, property);

    const created = await submitReport(
      reporter.accessToken,
      validReportBody({
        customerId: customer.id,
        propertyId: property.id,
        knownJobId: job.id,
      }),
    ).expect(201);

    expect(created.body).toMatchObject({
      status: 'PENDING',
      customerId: customer.id,
      propertyId: property.id,
      knownJobId: job.id,
      customerName: customer.displayName,
      knownJobNumber: job.jobNumber,
      reportedCustomerName: null,
      reportedCustomerPhone: null,
      reportedCustomerAddress: null,
    });
  });

  it('captures unknown-customer provenance facts without creating canonical records', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);

    const created = await submitReport(
      reporter.accessToken,
      validReportBody({
        reportedCustomerName: 'Maple Leaf Bakery',
        reportedCustomerPhone: '+16135550123',
        reportedCustomerAddress: '210 Sparks Street, Ottawa',
      }),
    ).expect(201);

    expect(created.body).toMatchObject({
      customerId: null,
      propertyId: null,
      knownJobId: null,
      reportedCustomerName: 'Maple Leaf Bakery',
      reportedCustomerPhone: '+16135550123',
      reportedCustomerAddress: '210 Sparks Street, Ottawa',
    });
  });

  it.each([
    'reportedCustomerName',
    'reportedCustomerPhone',
    'reportedCustomerAddress',
  ])('refuses blank %s without a selected customer', async (field) => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    await submitReport(
      reporter.accessToken,
      validReportBody({ [field]: '   ' }),
    ).expect(400);
  });

  it('refuses a report with neither a selected customer nor reported details', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    await submitReport(
      reporter.accessToken,
      validReportBody({
        reportedCustomerName: null,
        reportedCustomerPhone: null,
        reportedCustomerAddress: null,
      }),
    ).expect(400);
  });

  it('refuses a customer id that does not exist in the organization', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);

    await submitReport(
      reporter.accessToken,
      validReportBody({
        customerId: randomUUID(),
      }),
    ).expect(404);
  });

  it('refuses a property that is not related to the selected customer', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customerA = await newCustomer('Report Customer A2');
    const customerB = await newCustomer('Report Customer B2');
    const propertyB = await newProperty(customerB.id, reviewer.membershipId);

    await submitReport(
      reporter.accessToken,
      validReportBody({
        customerId: customerA.id,
        propertyId: propertyB.id,
      }),
    ).expect(404);
  });

  it('refuses a property without a selected customer', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customer = await newCustomer('Report Customer A3');
    const property = await newProperty(customer.id, reviewer.membershipId);

    await submitReport(
      reporter.accessToken,
      validReportBody({
        propertyId: property.id,
      }),
    ).expect(404);
  });

  it('refuses a related job that belongs to a different customer', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customerA = await newCustomer('Report Customer A4');
    const customerB = await newCustomer('Report Customer B4');
    const propertyA = await newProperty(customerA.id, reviewer.membershipId);
    const jobA = await newJob(customerA.id, propertyA);

    await submitReport(
      reporter.accessToken,
      validReportBody({
        customerId: customerB.id,
        knownJobId: jobA.id,
      }),
    ).expect(404);
  });

  it('is idempotent by client operation id', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const clientOperationId = randomUUID();
    const body = validReportBody({ clientOperationId });

    const first = await submitReport(reporter.accessToken, body).expect(201);
    const second = await submitReport(reporter.accessToken, body).expect(201);

    expect(second.body.id).toBe(first.body.id);

    const rows = await database.db
      .select({ id: adHocWorkReports.id })
      .from(adHocWorkReports)
      .where(eq(adHocWorkReports.clientOperationId, clientOperationId));
    expect(rows).toHaveLength(1);
  });

  it('enforces the report and review capabilities', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const outsider = await signInFor([VISIT_PERMISSIONS.REQUEST_FOLLOW_UP]);

    await request(app.getHttpServer())
      .post('/jobs/ad-hoc-work-reports')
      .send(validReportBody())
      .expect(401);

    await submitReport(outsider.accessToken, validReportBody()).expect(403);

    const created = await submitReport(
      reporter.accessToken,
      validReportBody(),
    ).expect(201);

    await request(app.getHttpServer())
      .get(`/jobs/ad-hoc-work-reports/${created.body.id}`)
      .set('Authorization', `Bearer ${outsider.accessToken}`)
      .expect(403);
  });

  it('scopes the list and detail reads to the caller', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const otherReporter = await signInFor([
      VISIT_PERMISSIONS.REPORT_AD_HOC_WORK,
    ]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);

    const own = await submitReport(
      reporter.accessToken,
      validReportBody(),
    ).expect(201);
    const other = await submitReport(
      otherReporter.accessToken,
      validReportBody(),
    ).expect(201);

    const ownList = await request(app.getHttpServer())
      .get('/jobs/ad-hoc-work-reports')
      .set('Authorization', `Bearer ${reporter.accessToken}`)
      .expect(200);
    expect(ownList.body.map((row: { id: string }) => row.id)).toContain(
      own.body.id,
    );
    expect(ownList.body.map((row: { id: string }) => row.id)).not.toContain(
      other.body.id,
    );

    const reviewList = await request(app.getHttpServer())
      .get('/jobs/ad-hoc-work-reports')
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .expect(200);
    expect(reviewList.body.map((row: { id: string }) => row.id)).toContain(
      own.body.id,
    );
    expect(reviewList.body.map((row: { id: string }) => row.id)).toContain(
      other.body.id,
    );

    await request(app.getHttpServer())
      .get(`/jobs/ad-hoc-work-reports/${own.body.id}`)
      .set('Authorization', `Bearer ${reporter.accessToken}`)
      .expect(200);

    await request(app.getHttpServer())
      .get(`/jobs/ad-hoc-work-reports/${other.body.id}`)
      .set('Authorization', `Bearer ${reporter.accessToken}`)
      .expect(404);

    await request(app.getHttpServer())
      .get(`/jobs/ad-hoc-work-reports/${own.body.id}`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .expect(200);
  });

  it('rejects a pending report as not legitimate work, terminal and audited', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);

    const created = await submitReport(
      reporter.accessToken,
      validReportBody(),
    ).expect(201);

    const rejected = await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/reject`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ note: 'Not legitimate Servora work.' })
      .expect(200);

    expect(rejected.body).toMatchObject({
      status: 'REJECTED',
      reviewerMembershipId: reviewer.membershipId,
      reviewNote: 'Not legitimate Servora work.',
    });
    expect(rejected.body.createdVisitId).toBeNull();
    expect(rejected.body.createdJobId).toBeNull();

    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/reject`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({})
      .expect(409);
  });

  it('refuses a reject from a caller without the review capability', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);

    const created = await submitReport(
      reporter.accessToken,
      validReportBody(),
    ).expect(201);

    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/reject`)
      .set('Authorization', `Bearer ${reporter.accessToken}`)
      .send({ note: 'Not allowed.' })
      .expect(403);
  });

  it('links a resolved report into an open Job and closes the Job', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customer = await newCustomer('Link Open Customer');
    const property = await newProperty(customer.id, reviewer.membershipId);
    const job = await newJob(customer.id, property, 'ACTIVE');

    const created = await submitReport(
      reporter.accessToken,
      validReportBody({
        customerId: customer.id,
        propertyId: property.id,
        outcomeCode: 'RESOLVED',
      }),
    ).expect(201);

    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/link`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ jobId: job.id })
      .expect(201);

    expect(await readJobStatus(job.id)).toBe('COMPLETED');

    const [report] = await database.db
      .select({
        status: adHocWorkReports.status,
        createdVisitId: adHocWorkReports.createdVisitId,
      })
      .from(adHocWorkReports)
      .where(eq(adHocWorkReports.id, created.body.id));
    expect(report.status).toBe('LINKED');
    expect(report.createdVisitId).toBeTruthy();
    expect(
      await database.db
        .select()
        .from(pushAssignmentEvents)
        .where(eq(pushAssignmentEvents.jobId, job.id)),
    ).toHaveLength(0);
  });

  it('links a resolved report into a CANCELED Job and moves it to COMPLETED', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customer = await newCustomer('Link Canceled Customer');
    const property = await newProperty(customer.id, reviewer.membershipId);
    const job = await newJob(customer.id, property, 'CANCELED');

    const created = await submitReport(
      reporter.accessToken,
      validReportBody({ outcomeCode: 'RESOLVED' }),
    ).expect(201);

    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/link`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ jobId: job.id })
      .expect(201);

    expect(await readJobStatus(job.id)).toBe('COMPLETED');
  });

  it('keeps a COMPLETED Job completed on a resolved historical link', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customer = await newCustomer('Link Completed Customer');
    const property = await newProperty(customer.id, reviewer.membershipId);
    const job = await newJob(customer.id, property, 'COMPLETED');

    const created = await submitReport(
      reporter.accessToken,
      validReportBody({ outcomeCode: 'RESOLVED' }),
    ).expect(201);

    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/link`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ jobId: job.id })
      .expect(201);

    expect(await readJobStatus(job.id)).toBe('COMPLETED');
  });

  it('reopens a COMPLETED Job to ACTIVE on a follow-up historical link', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customer = await newCustomer('Link Follow-Up Customer');
    const property = await newProperty(customer.id, reviewer.membershipId);
    const job = await newJob(customer.id, property, 'COMPLETED');

    const created = await submitReport(
      reporter.accessToken,
      validReportBody({ outcomeCode: 'NEEDS_PARTS' }),
    ).expect(201);

    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/link`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ jobId: job.id })
      .expect(201);

    expect(await readJobStatus(job.id)).toBe('ACTIVE');
  });

  it('converts a resolved report into a new completed Job', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customer = await newCustomer('Convert Resolved Customer');
    const property = await newProperty(customer.id, reviewer.membershipId);

    const created = await submitReport(
      reporter.accessToken,
      validReportBody({
        customerId: customer.id,
        propertyId: property.id,
        outcomeCode: 'RESOLVED',
      }),
    ).expect(201);

    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/convert`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ title: 'Emergency valve replacement' })
      .expect(201);

    const [report] = await database.db
      .select({
        status: adHocWorkReports.status,
        createdJobId: adHocWorkReports.createdJobId,
      })
      .from(adHocWorkReports)
      .where(eq(adHocWorkReports.id, created.body.id));
    expect(report.status).toBe('CONVERTED');
    expect(report.createdJobId).toBeTruthy();
    expect(await readJobStatus(report.createdJobId as string)).toBe(
      'COMPLETED',
    );
  });

  it('converts a follow-up report into a new active Job', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const customer = await newCustomer('Convert Follow-Up Customer');
    const property = await newProperty(customer.id, reviewer.membershipId);

    const created = await submitReport(
      reporter.accessToken,
      validReportBody({
        customerId: customer.id,
        propertyId: property.id,
        outcomeCode: 'NEEDS_FOLLOW_UP',
      }),
    ).expect(201);

    await request(app.getHttpServer())
      .post(`/jobs/ad-hoc-work-reports/${created.body.id}/convert`)
      .set('Authorization', `Bearer ${reviewer.accessToken}`)
      .send({ title: 'Inspection found more work' })
      .expect(201);

    const [report] = await database.db
      .select({
        status: adHocWorkReports.status,
        createdJobId: adHocWorkReports.createdJobId,
      })
      .from(adHocWorkReports)
      .where(eq(adHocWorkReports.id, created.body.id));
    expect(report.status).toBe('CONVERTED');
    expect(report.createdJobId).toBeTruthy();
    expect(await readJobStatus(report.createdJobId as string)).toBe('ACTIVE');
  });
});
