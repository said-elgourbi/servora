import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { and, eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_PERMISSIONS,
  PROPERTY_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
  customerCompanies,
  customers,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  properties,
  propertyCustomerRelationships,
  propertyLifecycleHistory,
  visits,
} from '../src/database/schema.js';
import {
  PropertiesService,
  PropertyNotAvailableForNewWorkError,
} from '../src/customers/properties.service.js';
import { CustomersService } from '../src/customers/customers.service.js';
import { hashPassword } from '../src/users/password-hasher.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  uniqueEmail,
  type FoundationTestDatabase,
  type TestDb,
} from './support/foundation-database.js';

const PASSWORD = 'servora-e2e-password';

const ALL_PROPERTY_PERMISSIONS = [
  PROPERTY_PERMISSIONS.VIEW,
  PROPERTY_PERMISSIONS.CREATE,
  PROPERTY_PERMISSIONS.EDIT,
  PROPERTY_PERMISSIONS.ARCHIVE,
  PROPERTY_PERMISSIONS.DELETE,
] as const;

/**
 * The Property lifecycle over HTTP and PostgreSQL (`BR-082` – `BR-086`).
 *
 * The tests assert the rules the API owes its clients: an edit leaves history alone, archiving
 * stops new work without disturbing existing work, permanent deletion is refused while any business
 * record references the Property, and every operation is bounded by the caller's organization and
 * permissions. Nothing here asserts a client's behaviour.
 */
describe('property lifecycle (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let service: PropertiesService;
  let customersService: CustomersService;
  let organizationId: string;
  let customerId: string;
  let membershipId: string;
  let jobNumber = 5000;
  let deviceSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Property Lifecycle Org',
    });
    organizationId = database.cleanup.trackOrganization(organization.id);

    const member = await createTestMembership(database.db, organizationId);
    membershipId = member.id;

    const customer = await createTestCustomer(database.db, {
      organizationId,
      name: 'Property Lifecycle Customer',
    });
    customerId = customer.id;

    const moduleFixture = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleFixture.createNestApplication({ logger: false });
    await app.init();
    service = moduleFixture.get(PropertiesService);
    customersService = moduleFixture.get(CustomersService);
  });

  afterAll(async () => {
    await app.close();
    await database.dispose();
  });

  /** A role and member inside an organization, so a Property relationship has an actor. */
  async function createTestMembership(db: TestDb, organization: string) {
    const user = await createTestUser(db);
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(db, organization);
    const [member] = await db
      .insert(organizationMembers)
      .values({
        organizationId: organization,
        userId: user.id,
        roleId: role.id,
      })
      .returning();
    return member;
  }

  /** A stored company customer, so the test does not depend on the customer write path. */
  async function createTestCustomer(
    db: TestDb,
    values: { organizationId: string; name: string },
  ) {
    const [customer] = await db
      .insert(customers)
      .values({
        organizationId: values.organizationId,
        type: 'COMPANY',
        displayName: values.name,
      })
      .returning();
    await db
      .insert(customerCompanies)
      .values({ customerId: customer.id, legalName: values.name });
    return customer;
  }

  /** A session holding exactly [granted] in [organization]. */
  async function signInFor(
    granted: readonly PermissionCode[],
    organization: string = organizationId,
  ) {
    const email = uniqueEmail('property-lifecycle');
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(PASSWORD),
    });
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, organization);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({
        organizationId: organization,
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
        organizationId: organization,
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
          deviceId: `property-lifecycle-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return response.body as { readonly accessToken: string };
  }

  async function accessTokenFor(
    granted: readonly PermissionCode[],
    organization: string = organizationId,
  ): Promise<string> {
    return (await signInFor(granted, organization)).accessToken;
  }

  /** A Property created through the API, so it is related to the customer (`BR-050`). */
  async function newProperty(
    overrides: Partial<{
      name: string;
      addressLine1: string;
      city: string;
      province: string;
      postalCode: string;
      organizationId: string;
      customerId: string;
      membershipId: string;
    }> = {},
  ) {
    const organization = overrides.organizationId ?? organizationId;
    const detail = await customersService.createPropertyForCustomer(
      {
        organizationId: organization,
        membershipId: overrides.membershipId ?? membershipId,
      },
      overrides.customerId ?? customerId,
      {
        name: overrides.name ?? 'Cedar Lane Building',
        addressLine1: overrides.addressLine1 ?? '987 Cedar Lane',
        addressLine2: 'Suite 200',
        city: overrides.city ?? 'Montreal',
        province: (overrides.province ?? 'QC') as 'QC',
        postalCode: overrides.postalCode ?? 'H3A 2T6',
        notes: 'Mechanical room in basement B1.',
      },
    );
    return detail.property;
  }

  /** A Property row with no relationship and no work: unreferenced, so deletable (`BR-082`). */
  async function newUnusedProperty(organization: string = organizationId) {
    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId: organization,
        addressLine1: '1 Unused Road',
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .returning();
    return property;
  }

  /** A Job at [property], stored directly because no Job write path exists in this slice. */
  async function newJob(
    propertyId: string,
    status: string,
    options: { customerId?: string; organizationId?: string } = {},
  ) {
    jobNumber += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId: options.organizationId ?? organizationId,
        jobNumber,
        customerId: options.customerId ?? customerId,
        propertyId,
        propertyAddressSnapshot: {
          propertyName: 'Cedar Lane Building',
          addressLine1: '987 Cedar Lane',
          addressLine2: 'Suite 200',
          city: 'Montreal',
          province: 'QC',
          postalCode: 'H3A 2T6',
          country: 'Canada',
        },
        title: 'HVAC Maintenance',
        status,
      })
      .returning();
    return job;
  }

  /** A Visit at [property], stored directly because no Visit write path exists in this slice. */
  async function newVisit(
    jobId: string,
    propertyId: string,
    status: string,
    schedule: { start?: Date; end?: Date } = {},
  ) {
    const start = schedule.start ?? new Date('2026-10-01T13:00:00.000Z');
    const end = schedule.end ?? new Date('2026-10-01T15:00:00.000Z');
    const scheduled =
      status === 'DRAFT' ? {} : { scheduledStart: start, scheduledEnd: end };
    // A completed Visit must carry the outcome that completes it (`BR-077`).
    const outcome =
      status === 'COMPLETED'
        ? {
            outcomeCode: 'RESOLVED',
            outcomeSummary: 'Repaired the unit.',
            outcomeRecordedAt: end,
            outcomeRecordedByMembershipId: membershipId,
          }
        : {};
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId,
        jobId,
        propertyId,
        locationAddressSnapshot: {
          propertyName: 'Cedar Lane Building',
          addressLine1: '987 Cedar Lane',
          city: 'Montreal',
          province: 'QC',
          postalCode: 'H3A 2T6',
          country: 'Canada',
        },
        status,
        ...scheduled,
        ...outcome,
      })
      .returning();
    return visit;
  }

  function body(overrides: Record<string, unknown> = {}) {
    return {
      name: 'Cedar Lane Building',
      addressLine1: '987 Cedar Lane',
      addressLine2: 'Suite 200',
      city: 'Montreal',
      province: 'QC',
      postalCode: 'H3A 2T6',
      notes: 'Mechanical room in basement B1.',
      ...overrides,
    };
  }

  it('requires a session on every Property lifecycle route', async () => {
    const property = await newProperty();

    await request(app.getHttpServer())
      .get(`/customers/${customerId}/properties/${property.id}`)
      .expect(401);
    await request(app.getHttpServer())
      .patch(`/customers/${customerId}/properties/${property.id}`)
      .send(body())
      .expect(401);
    await request(app.getHttpServer())
      .post(`/customers/${customerId}/properties/${property.id}/archive`)
      .send({})
      .expect(401);
    await request(app.getHttpServer())
      .post(`/customers/${customerId}/properties/${property.id}/restore`)
      .send({})
      .expect(401);
    await request(app.getHttpServer())
      .delete(`/customers/${customerId}/properties/${property.id}`)
      .expect(401);
  });

  it('enforces the Property capability each lifecycle route requires', async () => {
    const property = await newProperty();
    const viewOnly = await accessTokenFor([PROPERTY_PERMISSIONS.VIEW]);
    const editOnly = await accessTokenFor([PROPERTY_PERMISSIONS.EDIT]);
    const archiveOnly = await accessTokenFor([PROPERTY_PERMISSIONS.ARCHIVE]);
    const deleteOnly = await accessTokenFor([PROPERTY_PERMISSIONS.DELETE]);
    const customerEditOnly = await accessTokenFor([CUSTOMER_PERMISSIONS.EDIT]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    await request(app.getHttpServer())
      .get(url)
      .set('Authorization', `Bearer ${viewOnly}`)
      .expect(200);
    // A customer capability never opens a Property action (`BR-085`).
    await request(app.getHttpServer())
      .get(url)
      .set('Authorization', `Bearer ${customerEditOnly}`)
      .expect(403);

    await request(app.getHttpServer())
      .patch(url)
      .set('Authorization', `Bearer ${editOnly}`)
      .send(body({ notes: 'Edited by the authorized caller.' }))
      .expect(200);
    await request(app.getHttpServer())
      .patch(url)
      .set('Authorization', `Bearer ${viewOnly}`)
      .send(body())
      .expect(403);
    await request(app.getHttpServer())
      .patch(url)
      .set('Authorization', `Bearer ${customerEditOnly}`)
      .send(body())
      .expect(403);

    await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${archiveOnly}`)
      .send({})
      .expect(200);
    await request(app.getHttpServer())
      .post(`${url}/restore`)
      .set('Authorization', `Bearer ${viewOnly}`)
      .send({})
      .expect(403);
    await request(app.getHttpServer())
      .post(`${url}/restore`)
      .set('Authorization', `Bearer ${archiveOnly}`)
      .send({})
      .expect(200);

    await request(app.getHttpServer())
      .delete(url)
      .set('Authorization', `Bearer ${archiveOnly}`)
      .expect(403);
    // Deletion is refused by the API's own rule, not by authorization, for a referenced Property.
    const refused = await request(app.getHttpServer())
      .delete(url)
      .set('Authorization', `Bearer ${deleteOnly}`)
      .expect(409);
    expect(refused.body.code).toBe('PROPERTY_HAS_REFERENCES');
  });

  it('retrieves one Property by its own identifier with its derived values', async () => {
    const property = await newProperty();
    const token = await accessTokenFor([PROPERTY_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/customers/${customerId}/properties/${property.id}`)
      .set('Authorization', `Bearer ${token}`)
      .expect(200);

    expect(response.body).toMatchObject({
      id: property.id,
      name: 'Cedar Lane Building',
      addressLine1: '987 Cedar Lane',
      addressLine2: 'Suite 200',
      city: 'Montreal',
      province: 'QC',
      postalCode: 'H3A 2T6',
      country: 'Canada',
      notes: 'Mechanical room in basement B1.',
      status: 'ACTIVE',
      version: 1,
      archivedAt: null,
      jobCount: 0,
      lastServiceAt: null,
      archiveImpact: { activeJobCount: 0, activeVisitCount: 0 },
      // The create established a Customer relationship, which is a business reference.
      canBePermanentlyDeleted: false,
    });
  });

  it('answers not found for a Property the caller organization does not own', async () => {
    const property = await newProperty();
    const token = await accessTokenFor([PROPERTY_PERMISSIONS.VIEW]);
    const editToken = await accessTokenFor([PROPERTY_PERMISSIONS.EDIT]);
    const unknown = '00000000-0000-0000-0000-0000000000cc';

    await request(app.getHttpServer())
      .get(`/customers/${customerId}/properties/${unknown}`)
      .set('Authorization', `Bearer ${token}`)
      .expect(404);
    await request(app.getHttpServer())
      .patch(`/customers/${customerId}/properties/${unknown}`)
      .set('Authorization', `Bearer ${editToken}`)
      .send(body())
      .expect(404);
    expect(property.id).toBeDefined();
  });

  it('isolates another organization from every lifecycle route', async () => {
    const property = await newProperty();
    const otherOrganization = await createTestOrganization(database.db, {
      name: 'Other Property Org',
    });
    database.cleanup.trackOrganization(otherOrganization.id);
    const otherCustomer = await createTestCustomer(database.db, {
      organizationId: otherOrganization.id,
      name: 'Other Org Customer',
    });
    const otherToken = await accessTokenFor(
      [...ALL_PROPERTY_PERMISSIONS],
      otherOrganization.id,
    );
    const url = `/customers/${otherCustomer.id}/properties/${property.id}`;

    await request(app.getHttpServer())
      .get(url)
      .set('Authorization', `Bearer ${otherToken}`)
      .expect(404);
    await request(app.getHttpServer())
      .patch(url)
      .set('Authorization', `Bearer ${otherToken}`)
      .send(body({ addressLine1: 'Hijacked Lane' }))
      .expect(404);
    await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${otherToken}`)
      .send({})
      .expect(404);
    await request(app.getHttpServer())
      .delete(url)
      .set('Authorization', `Bearer ${otherToken}`)
      .expect(404);

    // Nothing leaked across the tenant boundary.
    const [unchanged] = await database.db
      .select()
      .from(properties)
      .where(eq(properties.id, property.id));
    expect(unchanged?.addressLine1).toBe('987 Cedar Lane');
    expect(unchanged?.status).toBe('ACTIVE');
  });

  it('refuses a Property addressed under a different customer of the same organization', async () => {
    const property = await newProperty();
    const otherCustomer = await createTestCustomer(database.db, {
      organizationId,
      name: 'Second Customer',
    });
    const token = await accessTokenFor([PROPERTY_PERMISSIONS.VIEW]);

    await request(app.getHttpServer())
      .get(`/customers/${otherCustomer.id}/properties/${property.id}`)
      .set('Authorization', `Bearer ${token}`)
      .expect(404);
  });

  it('updates an active Property without changing its lifecycle state', async () => {
    const property = await newProperty();
    const token = await accessTokenFor([PROPERTY_PERMISSIONS.EDIT]);

    const response = await request(app.getHttpServer())
      .patch(`/customers/${customerId}/properties/${property.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send(
        body({
          name: 'Cedar Lane Annex',
          addressLine1: '991 Cedar Lane',
          addressLine2: null,
          city: 'Laval',
          postalCode: 'H7T 1A1',
          notes: null,
          expectedVersion: 1,
        }),
      )
      .expect(200);

    expect(response.body).toMatchObject({
      name: 'Cedar Lane Annex',
      addressLine1: '991 Cedar Lane',
      addressLine2: null,
      city: 'Laval',
      postalCode: 'H7T 1A1',
      notes: null,
      status: 'ACTIVE',
      version: 2,
    });
  });

  it('updates an archived Property without restoring it', async () => {
    const property = await newProperty();
    const admin = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.EDIT,
      PROPERTY_PERMISSIONS.ARCHIVE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);

    const updated = await request(app.getHttpServer())
      .patch(url)
      .set('Authorization', `Bearer ${admin}`)
      .send(body({ addressLine1: '993 Cedar Lane' }))
      .expect(200);

    expect(updated.body).toMatchObject({
      addressLine1: '993 Cedar Lane',
      status: 'ARCHIVED',
    });
    expect(updated.body.archivedAt).toEqual(expect.any(String));
  });

  it("does not rewrite a Job's preserved address snapshot when the Property is edited", async () => {
    const property = await newProperty();
    const job = await newJob(property.id, 'SCHEDULED');
    const token = await accessTokenFor([PROPERTY_PERMISSIONS.EDIT]);

    await request(app.getHttpServer())
      .patch(`/customers/${customerId}/properties/${property.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send(body({ addressLine1: '5 Rewritten Road', city: 'Gatineau' }))
      .expect(200);

    const [stored] = await database.db
      .select()
      .from(jobs)
      .where(eq(jobs.id, job.id));
    expect(stored?.propertyAddressSnapshot).toMatchObject({
      addressLine1: '987 Cedar Lane',
      city: 'Montreal',
    });

    // The customer's Job projection still reports the snapshot the Job was created with (`BR-056`).
    const jobsResponse = await request(app.getHttpServer())
      .get(`/customers/${customerId}/jobs`)
      .set(
        'Authorization',
        `Bearer ${await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW])}`,
      )
      .expect(200);
    const row = (
      jobsResponse.body as {
        id: string;
        propertyAddress: { addressLine1: string };
      }[]
    ).find((candidate) => candidate.id === job.id);
    expect(row?.propertyAddress.addressLine1).toBe('987 Cedar Lane');
  });

  it('rejects a mutation that names a version the Property has moved past', async () => {
    const property = await newProperty();
    const token = await accessTokenFor([
      PROPERTY_PERMISSIONS.EDIT,
      PROPERTY_PERMISSIONS.ARCHIVE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    const patch = await request(app.getHttpServer())
      .patch(url)
      .set('Authorization', `Bearer ${token}`)
      .send(body({ expectedVersion: 99 }))
      .expect(409);
    expect(patch.body).toMatchObject({
      code: 'PROPERTY_VERSION_CONFLICT',
      details: { currentVersion: 1 },
    });

    const archive = await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${token}`)
      .send({ expectedVersion: 99 })
      .expect(409);
    expect(archive.body.code).toBe('PROPERTY_VERSION_CONFLICT');

    // The refused mutations left the Property as it was.
    const [unchanged] = await database.db
      .select()
      .from(properties)
      .where(eq(properties.id, property.id));
    expect(unchanged?.version).toBe(1);
    expect(unchanged?.status).toBe('ACTIVE');
  });

  it('archives a Property, hides it from the default list and keeps it retrievable', async () => {
    const property = await newProperty();
    const admin = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.CREATE,
      PROPERTY_PERMISSIONS.ARCHIVE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    const archived = await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({ note: 'No longer serviced.' })
      .expect(200);
    expect(archived.body).toMatchObject({
      status: 'ARCHIVED',
      version: 2,
    });
    expect(archived.body.archivedAt).toEqual(expect.any(String));

    const active = await request(app.getHttpServer())
      .get(`/customers/${customerId}/properties`)
      .set('Authorization', `Bearer ${admin}`)
      .expect(200);
    expect(active.body.map((row: { id: string }) => row.id)).not.toContain(
      property.id,
    );

    const explicit = await request(app.getHttpServer())
      .get(`/customers/${customerId}/properties?status=ARCHIVED`)
      .set('Authorization', `Bearer ${admin}`)
      .expect(200);
    const archivedRow = (
      explicit.body as { id: string; status: string }[]
    ).find((row) => row.id === property.id);
    expect(archivedRow).toMatchObject({ id: property.id, status: 'ARCHIVED' });
    // No active row appears in the archived projection.
    expect(
      (explicit.body as { status: string }[]).every(
        (row) => row.status === 'ARCHIVED',
      ),
    ).toBe(true);

    const all = await request(app.getHttpServer())
      .get(`/customers/${customerId}/properties?status=ALL`)
      .set('Authorization', `Bearer ${admin}`)
      .expect(200);
    expect(all.body.map((row: { id: string }) => row.id)).toContain(
      property.id,
    );

    // The record itself is still retrievable by its own identifier, and through its Jobs.
    await request(app.getHttpServer())
      .get(url)
      .set('Authorization', `Bearer ${admin}`)
      .expect(200);

    // The customer header counts the same `ACTIVE` set as the default list (`BR-081`, `BR-083`).
    const detail = await request(app.getHttpServer())
      .get(`/customers/${customerId}`)
      .set(
        'Authorization',
        `Bearer ${await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW])}`,
      )
      .expect(200);
    const activeProperty = await newProperty();
    const withActive = await request(app.getHttpServer())
      .get(`/customers/${customerId}`)
      .set(
        'Authorization',
        `Bearer ${await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW])}`,
      )
      .expect(200);
    expect(withActive.body.customer.propertyCount).toBe(
      detail.body.customer.propertyCount + 1,
    );
    expect(activeProperty.id).toBeDefined();
  });

  it('rejects an unknown Property lifecycle list filter', async () => {
    const token = await accessTokenFor([PROPERTY_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get(`/customers/${customerId}/properties?status=DELETED`)
      .set('Authorization', `Bearer ${token}`)
      .expect(400);
    expect(response.body.code).toBe('VALIDATION_FAILED');
  });

  it('reports the open work the archive warning needs, and archiving leaves it untouched', async () => {
    const property = await newProperty();
    const jobScheduled = await newJob(property.id, 'SCHEDULED');
    await newVisit(jobScheduled.id, property.id, 'SCHEDULED');
    const jobDone = await newJob(property.id, 'COMPLETED');
    await newVisit(jobDone.id, property.id, 'COMPLETED');
    const draftJob = await newJob(property.id, 'NEW');
    const draftVisit = await newVisit(draftJob.id, property.id, 'DRAFT');

    const admin = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.ARCHIVE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    const before = await request(app.getHttpServer())
      .get(url)
      .set('Authorization', `Bearer ${admin}`)
      .expect(200);
    // `archiveWarningOpenWork`: a non-terminal Job and a non-terminal Visit, including `DRAFT`.
    expect(before.body.archiveImpact).toEqual({
      activeJobCount: 2,
      activeVisitCount: 2,
    });
    expect(before.body.jobCount).toBe(3);

    const archived = await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);
    // The counts are reported with the archive answer too, so a client need not guess.
    expect(archived.body.archiveImpact).toEqual({
      activeJobCount: 2,
      activeVisitCount: 2,
    });

    // Archiving changed the Property and nothing else (`BR-083`).
    const [jobAfter] = await database.db
      .select()
      .from(jobs)
      .where(eq(jobs.id, jobScheduled.id));
    expect(jobAfter).toMatchObject({
      status: 'SCHEDULED',
      propertyId: property.id,
      version: 1,
    });
    const [visitAfter] = await database.db
      .select()
      .from(visits)
      .where(eq(visits.id, draftVisit.id));
    expect(visitAfter).toMatchObject({
      status: 'DRAFT',
      propertyId: property.id,
    });
    const completedVisits = await database.db
      .select()
      .from(visits)
      .where(and(eq(visits.jobId, jobDone.id), eq(visits.status, 'COMPLETED')));
    expect(completedVisits).toHaveLength(1);
  });

  it('blocks new work at an archived Property while leaving existing work changeable', async () => {
    const property = await newProperty();
    const job = await newJob(property.id, 'SCHEDULED');
    const visit = await newVisit(job.id, property.id, 'SCHEDULED');
    const admin = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.ARCHIVE,
    ]);
    const scope = { organizationId };

    // An active Property accepts new work.
    await expect(
      service.assertPropertyAvailableForNewWork(scope, property.id),
    ).resolves.toMatchObject({ id: property.id });

    await request(app.getHttpServer())
      .post(`/customers/${customerId}/properties/${property.id}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);

    // New work is refused by the backend, not by a client hiding a selector (`BR-083`).
    await expect(
      service.assertPropertyAvailableForNewWork(scope, property.id),
    ).rejects.toBeInstanceOf(PropertyNotAvailableForNewWorkError);

    // The archived Property is no longer offered as a location for new work.
    const selector = await request(app.getHttpServer())
      .get(`/customers/${customerId}/properties`)
      .set('Authorization', `Bearer ${admin}`)
      .expect(200);
    expect(selector.body.map((row: { id: string }) => row.id)).not.toContain(
      property.id,
    );

    // Existing work continues: the Visit can be rescheduled, and a further Visit may be added to
    // the same open Job (`BR-083`).
    const rescheduledStart = new Date('2026-11-02T13:00:00.000Z');
    await database.db
      .update(visits)
      .set({
        scheduledStart: rescheduledStart,
        scheduledEnd: new Date('2026-11-02T15:00:00.000Z'),
      })
      .where(eq(visits.id, visit.id));
    const [rescheduled] = await database.db
      .select()
      .from(visits)
      .where(eq(visits.id, visit.id));
    expect(rescheduled?.scheduledStart).toEqual(rescheduledStart);

    const added = await newVisit(job.id, property.id, 'DRAFT');
    expect(added.propertyId).toBe(property.id);
  });

  it('restores an archived Property to active use without changing its identity', async () => {
    const property = await newProperty();
    const admin = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.ARCHIVE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    const archived = await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);

    const restored = await request(app.getHttpServer())
      .post(`${url}/restore`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);

    expect(restored.body).toMatchObject({
      id: property.id,
      status: 'ACTIVE',
      archivedAt: null,
      version: archived.body.version + 1,
    });

    // It is offered as a location for new work again.
    const selector = await request(app.getHttpServer())
      .get(`/customers/${customerId}/properties`)
      .set('Authorization', `Bearer ${admin}`)
      .expect(200);
    expect(selector.body.map((row: { id: string }) => row.id)).toContain(
      property.id,
    );
    await expect(
      service.assertPropertyAvailableForNewWork(
        { organizationId },
        property.id,
      ),
    ).resolves.toMatchObject({ id: property.id });
  });

  it('keeps archive and restore idempotent and their history append-only', async () => {
    const property = await newProperty();
    const admin = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.ARCHIVE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    const firstArchive = await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);
    const secondArchive = await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);
    // Archiving an archived Property changes nothing, not even its version.
    expect(secondArchive.body).toMatchObject({
      status: 'ARCHIVED',
      version: firstArchive.body.version,
    });

    const firstRestore = await request(app.getHttpServer())
      .post(`${url}/restore`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);
    const secondRestore = await request(app.getHttpServer())
      .post(`${url}/restore`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);
    expect(secondRestore.body).toMatchObject({
      status: 'ACTIVE',
      version: firstRestore.body.version,
    });

    const history = await database.db
      .select()
      .from(propertyLifecycleHistory)
      .where(eq(propertyLifecycleHistory.propertyId, property.id));
    expect(history.map((row) => row.action)).toEqual(['ARCHIVED', 'RESTORED']);
  });

  it('recognises a replayed offline archive as already applied', async () => {
    const property = await newProperty();
    const admin = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.ARCHIVE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;
    const clientOperationId = '3f1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c';

    await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({ clientOperationId, capturedAt: '2026-09-13T10:15:00.000Z' })
      .expect(200);
    await request(app.getHttpServer())
      .post(`${url}/restore`)
      .set('Authorization', `Bearer ${admin}`)
      .send({})
      .expect(200);

    // The device never learned the first attempt succeeded, so it replays it.
    const replayed = await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({ clientOperationId, capturedAt: '2026-09-13T10:15:00.000Z' })
      .expect(200);

    // The replay is recognised and not applied a second time (`BR-031`).
    expect(replayed.body.status).toBe('ACTIVE');
    const history = await database.db
      .select()
      .from(propertyLifecycleHistory)
      .where(
        and(
          eq(propertyLifecycleHistory.propertyId, property.id),
          eq(propertyLifecycleHistory.clientOperationId, clientOperationId),
        ),
      );
    expect(history).toHaveLength(1);
    expect(history[0]?.capturedAt).toEqual(
      new Date('2026-09-13T10:15:00.000Z'),
    );
  });

  it('accepts the device time precision an Android client sends', async () => {
    const property = await newProperty();
    const admin = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.ARCHIVE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    // Kotlin's `Instant.toString()` reports microseconds on Android, which must not be rejected as
    // a validation failure: that surfaced as the generic validation message on the archive screen.
    const archived = await request(app.getHttpServer())
      .post(`${url}/archive`)
      .set('Authorization', `Bearer ${admin}`)
      .send({
        clientOperationId: '9c2f5b7a-1d4e-4f60-8a3b-2c5d7e9f1a2b',
        capturedAt: '2026-09-13T14:39:21.123456Z',
      })
      .expect(200);

    expect(archived.body.status).toBe('ARCHIVED');
    const history = await database.db
      .select()
      .from(propertyLifecycleHistory)
      .where(eq(propertyLifecycleHistory.propertyId, property.id));
    expect(history[0]?.capturedAt).toEqual(
      new Date('2026-09-13T14:39:21.123456Z'),
    );
  });

  it('permanently deletes a Property that nothing references, removing only its own history', async () => {
    const property = await newUnusedProperty();
    const admin = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.ARCHIVE,
      PROPERTY_PERMISSIONS.DELETE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;
    const scope = { organizationId, membershipId };

    // The Property's own archive/restore lifecycle is not a reference (`BR-082`, ADR-012 D5).
    await service.archiveProperty(
      scope,
      customerId,
      property.id,
      {},
      new Date(),
    );
    await service.restoreProperty(
      scope,
      customerId,
      property.id,
      {},
      new Date(),
    );

    const detail = await request(app.getHttpServer())
      .get(url)
      .set('Authorization', `Bearer ${admin}`)
      .expect(200);
    expect(detail.body.canBePermanentlyDeleted).toBe(true);

    await request(app.getHttpServer())
      .delete(url)
      .set('Authorization', `Bearer ${admin}`)
      .expect(204);

    const rows = await database.db
      .select()
      .from(properties)
      .where(eq(properties.id, property.id));
    expect(rows).toEqual([]);
    const history = await database.db
      .select()
      .from(propertyLifecycleHistory)
      .where(eq(propertyLifecycleHistory.propertyId, property.id));
    expect(history).toEqual([]);
  });

  it('refuses permanent deletion while a business record references the Property', async () => {
    const property = await newProperty();
    const token = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.DELETE,
    ]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    const detail = await request(app.getHttpServer())
      .get(url)
      .set('Authorization', `Bearer ${token}`)
      .expect(200);
    expect(detail.body.canBePermanentlyDeleted).toBe(false);

    const conflict = await request(app.getHttpServer())
      .delete(url)
      .set('Authorization', `Bearer ${token}`)
      .expect(409);
    expect(conflict.body).toMatchObject({
      code: 'PROPERTY_HAS_REFERENCES',
      details: { referenceKinds: ['customerRelationships'] },
    });
  });

  it('refuses deletion for Job and Visit references and never deletes them by cascade', async () => {
    const property = await newProperty();
    const job = await newJob(property.id, 'SCHEDULED');
    const visit = await newVisit(job.id, property.id, 'SCHEDULED');
    const token = await accessTokenFor([PROPERTY_PERMISSIONS.DELETE]);
    const url = `/customers/${customerId}/properties/${property.id}`;

    const conflict = await request(app.getHttpServer())
      .delete(url)
      .set('Authorization', `Bearer ${token}`)
      .expect(409);
    expect(conflict.body.details.referenceKinds).toEqual(
      expect.arrayContaining(['jobs', 'visits', 'customerRelationships']),
    );

    // Nothing was cascaded: the Job, the Visit and the relationship are all still there.
    expect(
      await database.db.select().from(jobs).where(eq(jobs.id, job.id)),
    ).toHaveLength(1);
    expect(
      await database.db.select().from(visits).where(eq(visits.id, visit.id)),
    ).toHaveLength(1);
    expect(
      await database.db
        .select()
        .from(propertyCustomerRelationships)
        .where(eq(propertyCustomerRelationships.propertyId, property.id)),
    ).toHaveLength(1);
    expect(
      await database.db
        .select()
        .from(properties)
        .where(eq(properties.id, property.id)),
    ).toHaveLength(1);
  });

  it('serialises a concurrent deletion against a reference being created', async () => {
    const property = await newUnusedProperty();
    const deletion = service.deleteProperty(
      { organizationId },
      customerId,
      property.id,
    );
    // A reference is created while the delete is in flight. The row lock makes one of the two
    // fail: either the reference lands first and the delete is refused, or the delete commits and
    // the reference cannot be created because the Property no longer exists (`BR-082`).
    const reference = database.db
      .insert(propertyCustomerRelationships)
      .values({
        organizationId,
        propertyId: property.id,
        customerId,
        actorMembershipId: membershipId,
      })
      .then(() => 'created' as const)
      .catch(() => 'failed' as const);

    const [deleteOutcome, referenceOutcome] = await Promise.all([
      deletion.then(
        () => 'deleted' as const,
        () => 'refused' as const,
      ),
      reference,
    ]);

    if (referenceOutcome === 'created') {
      expect(deleteOutcome).toBe('refused');
    } else {
      expect(deleteOutcome).toBe('deleted');
    }

    const remaining = await database.db
      .select()
      .from(properties)
      .where(eq(properties.id, property.id));
    const relationships = await database.db
      .select()
      .from(propertyCustomerRelationships)
      .where(eq(propertyCustomerRelationships.propertyId, property.id));
    // The invariant: a reference never outlives its Property, and the Property is removed only
    // when nothing referenced it.
    if (deleteOutcome === 'deleted') {
      expect(remaining).toHaveLength(0);
      expect(relationships).toHaveLength(0);
    } else {
      expect(remaining).toHaveLength(1);
      expect(relationships).toHaveLength(1);
    }
  });
});
