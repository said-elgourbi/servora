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
  propertyCustomerRelationships,
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
 * The discoverability options the ad-hoc work report form draws (`BR-AH-009`).
 *
 * These stay at the HTTP boundary because the guarantees are scoping and authorization: a reporter
 * searches only the Customers they are allowed to reach, Property and Job choices are derived from
 * the selected Customer, and an id outside the caller's scope is reported as not found.
 */
describe('ad-hoc work report options (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Ad-Hoc Options Org',
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

  async function signInFor(granted: readonly PermissionCode[]) {
    const email = uniqueEmail('ad-hoc-options');
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
          deviceId: `ad-hoc-options-device-${deviceSequence}`,
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
        addressLine1: '99 Option Street',
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

  async function newJob(customerId: string) {
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber: jobNumberSequence,
        customerId,
        title: `Option Job ${jobNumberSequence}`,
        status: 'ACTIVE',
      })
      .returning();
    return job;
  }

  /** Puts [membershipId] on the crew of a Visit of [jobId], establishing BR-092 assignment scope. */
  async function assignToJob(membershipId: string, jobId: string) {
    const [visit] = await database.db
      .insert(visits)
      .values({ organizationId, jobId })
      .returning();
    await database.db.insert(visitTechnicians).values({
      organizationId,
      visitId: visit.id,
      technicianMembershipId: membershipId,
      roleCode: 'LEAD',
    });
  }

  function search(token: string, q: string) {
    return request(app.getHttpServer())
      .get('/jobs/ad-hoc-work-reports/customer-options')
      .query({ q })
      .set('Authorization', `Bearer ${token}`);
  }

  function propertyOptions(token: string, customerId: string) {
    return request(app.getHttpServer())
      .get('/jobs/ad-hoc-work-reports/property-options')
      .query({ customerId })
      .set('Authorization', `Bearer ${token}`);
  }

  function jobOptions(token: string, customerId: string) {
    return request(app.getHttpServer())
      .get('/jobs/ad-hoc-work-reports/job-options')
      .query({ customerId })
      .set('Authorization', `Bearer ${token}`);
  }

  it('requires the report capability', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    const outsider = await signInFor([VISIT_PERMISSIONS.REQUEST_FOLLOW_UP]);

    await request(app.getHttpServer())
      .get('/jobs/ad-hoc-work-reports/customer-options')
      .query({ q: 'Acme' })
      .expect(401);

    await search(outsider.accessToken, 'Acme').expect(403);

    await search(reporter.accessToken, 'Acme').expect(200);
  });

  it('refuses a customer search below the minimum length', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);

    await search(reporter.accessToken, 'A').expect(400);
  });

  it('answers no customer for a reporter with no customer read capability', async () => {
    const reporter = await signInFor([VISIT_PERMISSIONS.REPORT_AD_HOC_WORK]);
    await newCustomer('Nope Bakery');

    const response = await search(reporter.accessToken, 'Bakery').expect(200);
    expect(response.body).toEqual([]);
  });

  it('searches the whole organization for an office reporter', async () => {
    const reporter = await signInFor([
      VISIT_PERMISSIONS.REPORT_AD_HOC_WORK,
      CUSTOMER_PERMISSIONS.VIEW,
    ]);
    await newCustomer('Acme Plumbing');
    await newCustomer('Acme Heating');
    await newCustomer('Other Roofing');

    const response = await search(reporter.accessToken, 'Acme').expect(200);
    const names = response.body.map(
      (row: { displayName: string }) => row.displayName,
    );
    expect(names).toEqual(['Acme Heating', 'Acme Plumbing']);
  });

  it('scopes the customer search to assigned customers for a field reporter', async () => {
    const reporter = await signInFor([
      VISIT_PERMISSIONS.REPORT_AD_HOC_WORK,
      CUSTOMER_PERMISSIONS.VIEW_ASSIGNED,
    ]);

    const assigned = await newCustomer('Assigned Bakery');
    await newCustomer('Unassigned Bakery');
    const job = await newJob(assigned.id);
    await assignToJob(reporter.membershipId, job.id);

    const response = await search(reporter.accessToken, 'Bakery').expect(200);
    const names = response.body.map(
      (row: { displayName: string }) => row.displayName,
    );
    expect(names).toContain('Assigned Bakery');
    expect(names).not.toContain('Unassigned Bakery');
  });

  it('derives property options from a selectable customer and hides others', async () => {
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const reporter = await signInFor([
      VISIT_PERMISSIONS.REPORT_AD_HOC_WORK,
      CUSTOMER_PERMISSIONS.VIEW_ASSIGNED,
    ]);

    const assigned = await newCustomer('Assigned Property Co');
    const other = await newCustomer('Other Property Co');
    const assignedProperty = await newProperty(
      assigned.id,
      reviewer.membershipId,
    );
    const job = await newJob(assigned.id);
    await assignToJob(reporter.membershipId, job.id);

    const response = await propertyOptions(
      reporter.accessToken,
      assigned.id,
    ).expect(200);
    expect(response.body.map((row: { id: string }) => row.id)).toContain(
      assignedProperty.id,
    );

    await propertyOptions(reporter.accessToken, other.id).expect(404);
  });

  it('derives job options from a selectable customer and hides others', async () => {
    const reviewer = await signInFor([VISIT_PERMISSIONS.REVIEW_AD_HOC_WORK]);
    const reporter = await signInFor([
      VISIT_PERMISSIONS.REPORT_AD_HOC_WORK,
      CUSTOMER_PERMISSIONS.VIEW_ASSIGNED,
    ]);

    const assigned = await newCustomer('Assigned Job Co');
    const other = await newCustomer('Other Job Co');
    const assignedProperty = await newProperty(
      assigned.id,
      reviewer.membershipId,
    );
    const assignedJob = await newJob(assigned.id);
    await assignToJob(reporter.membershipId, assignedJob.id);

    const response = await jobOptions(reporter.accessToken, assigned.id).expect(
      200,
    );
    expect(response.body.map((row: { id: string }) => row.id)).toContain(
      assignedJob.id,
    );

    await jobOptions(reporter.accessToken, other.id).expect(404);
  });
});

