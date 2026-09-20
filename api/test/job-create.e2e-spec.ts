import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { and, eq, inArray } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_PERMISSIONS,
  JOB_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import { CustomersService } from '../src/customers/customers.service.js';
import { PropertiesService } from '../src/customers/properties.service.js';
import {
  customerCompanies,
  customers,
  jobStatusHistory,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  propertyCustomerRelationships,
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
  type TestDb,
} from './support/foundation-database.js';

const PASSWORD = 'servora-e2e-password';

/**
 * The permissions the tests sign in with: creating a Job, and the office read of the created Job.
 */
const CREATOR_PERMISSIONS = [
  JOB_PERMISSIONS.CREATE,
  CUSTOMER_PERMISSIONS.VIEW,
] as const satisfies readonly PermissionCode[];

/**
 * `POST /jobs` — creating a Job for a Customer at a Property (`BR-047` – `BR-056`, `BR-094`).
 *
 * The tests assert what the route owes its clients: the Customer and the Property are the caller's and
 * usable, the backend owns everything else it stores, the Job number is allocated per organization
 * without a duplicate under concurrency, the address is frozen, no Visit is created, and the operation
 * is authorized and tenant-scoped. Nothing here asserts a client's behaviour.
 */
describe('job creation (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let customersService: CustomersService;
  let propertiesService: PropertiesService;
  let organizationId: string;
  let membershipId: string;
  let customerId: string;
  let deviceSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Job Create Org',
    });
    organizationId = database.cleanup.trackOrganization(organization.id);

    const member = await createTestMembership(database.db, organizationId);
    membershipId = member.id;

    const customer = await createTestCustomer(database.db, {
      organizationId,
      name: 'Job Create Customer',
    });
    customerId = customer.id;

    const moduleFixture = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleFixture.createNestApplication({ logger: false });
    await app.init();
    customersService = moduleFixture.get(CustomersService);
    propertiesService = moduleFixture.get(PropertiesService);
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

  /** A stored company customer, so the tests do not depend on the customer write path. */
  async function createTestCustomer(
    db: TestDb,
    values: { organizationId: string; name: string; status?: string },
  ) {
    const [customer] = await db
      .insert(customers)
      .values({
        organizationId: values.organizationId,
        type: 'COMPANY',
        displayName: values.name,
        ...(values.status === undefined ? {} : { status: values.status }),
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
    const email = uniqueEmail('job-create');
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
          deviceId: `job-create-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return (response.body as { readonly accessToken: string }).accessToken;
  }

  async function accessTokenFor(
    granted: readonly PermissionCode[],
    organization: string = organizationId,
  ): Promise<string> {
    return signInFor(granted, organization);
  }

  /** A Property created through the API, so it is related to the customer (`BR-050`). */
  async function newProperty(
    overrides: {
      customerId?: string;
      organizationId?: string;
      membershipId?: string;
      addressLine1?: string;
    } = {},
  ) {
    const detail = await customersService.createPropertyForCustomer(
      {
        organizationId: overrides.organizationId ?? organizationId,
        membershipId: overrides.membershipId ?? membershipId,
      },
      overrides.customerId ?? customerId,
      {
        name: 'Cedar Lane Building',
        addressLine1: overrides.addressLine1 ?? '987 Cedar Lane',
        addressLine2: 'Suite 200',
        city: 'Montreal',
        province: 'QC',
        postalCode: 'H3A 2T6',
        notes: 'Mechanical room in basement B1.',
      },
    );
    return detail.property;
  }

  /** The request body `POST /jobs` documents, with [propertyId] and any overrides applied. */
  function body(
    propertyId: string,
    overrides: Record<string, unknown> = {},
  ): Record<string, unknown> {
    return {
      customerId,
      propertyId,
      title: 'Furnace repair',
      description: 'Customer reports the furnace is not producing heat.',
      ...overrides,
    };
  }

  /** One `POST /jobs`, so a test states only what it is about. */
  function createJob(
    token: string,
    propertyId: string,
    overrides: Record<string, unknown> = {},
  ) {
    return request(app.getHttpServer())
      .post('/jobs')
      .set('Authorization', `Bearer ${token}`)
      .send(body(propertyId, overrides));
  }

  /** The organization's highest allocated Job number, or `0` when none exists. */
  async function highestJobNumber(organization: string): Promise<number> {
    const rows = await database.db
      .select({ jobNumber: jobs.jobNumber })
      .from(jobs)
      .where(eq(jobs.organizationId, organization));
    return rows.reduce((highest, row) => Math.max(highest, row.jobNumber), 0);
  }

  /**
   * A fresh organization with its own member, customer and Property, so a test that depends on the
   * organization's numbering is not affected by the order the other tests ran in.
   */
  async function newOrganizationWithProperty() {
    const organization = await createTestOrganization(database.db, {
      name: 'Job Create Numbering Org',
    });
    database.cleanup.trackOrganization(organization.id);
    const member = await createTestMembership(database.db, organization.id);
    const customer = await createTestCustomer(database.db, {
      organizationId: organization.id,
      name: 'Fresh Org Customer',
    });
    const property = await newProperty({
      organizationId: organization.id,
      membershipId: member.id,
      customerId: customer.id,
    });
    const token = await accessTokenFor(
      [JOB_PERMISSIONS.CREATE],
      organization.id,
    );
    return { organization, customer, property, token };
  }

  it('creates a Job with the backend-owned values and no Visit', async () => {
    const property = await newProperty();
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    const response = await createJob(token, property.id).expect(201);
    const created = response.body as Record<string, unknown>;

    expect(created.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
    expect(created.title).toBe('Furnace repair');
    expect(created.description).toBe(
      'Customer reports the furnace is not producing heat.',
    );
    // `BR-058`: a Job is created `NEW`, and `version` 1 is the value the read reports.
    expect(created.status).toBe('NEW');
    expect(created.version).toBe(1);
    expect(created.customerId).toBe(customerId);
    expect(created.propertyId).toBe(property.id);
    // `BR-056`: the address is the Property's, frozen at creation.
    expect(created.address).toEqual({
      propertyName: 'Cedar Lane Building',
      addressLine1: '987 Cedar Lane',
      addressLine2: 'Suite 200',
      city: 'Montreal',
      province: 'QC',
      postalCode: 'H3A 2T6',
      country: 'Canada',
    });
    // A new Job has no Visit: a Visit is one field attempt (`BR-047`, `BR-051`).
    expect(created.selectedVisit).toBeNull();
    expect(created.technicians).toEqual([]);

    const visitsOfJob = await database.db
      .select({ id: visits.id })
      .from(visits)
      .where(eq(visits.jobId, created.id as string));
    expect(visitsOfJob).toHaveLength(0);

    // The initial state is append-only history, entering `NEW` from no status (`BR-033`, `BR-067`).
    const history = await database.db
      .select()
      .from(jobStatusHistory)
      .where(eq(jobStatusHistory.jobId, created.id as string));
    expect(history).toHaveLength(1);
    expect(history[0]?.fromStatus).toBeNull();
    expect(history[0]?.toStatus).toBe('NEW');
  });

  it('never takes the number, status, version or snapshot from the request', async () => {
    const property = await newProperty();
    const token = await accessTokenFor(CREATOR_PERMISSIONS);
    const before = await highestJobNumber(organizationId);

    const response = await createJob(token, property.id, {
      jobNumber: 999_999,
      status: 'COMPLETED',
      version: 42,
      propertyAddressSnapshot: { city: 'Havana' },
      organizationId: 'other-organization',
      typeCode: 'REPAIR',
      ownerMembershipId: 'someone',
    }).expect(201);
    const created = response.body as Record<string, unknown>;

    expect(created.jobNumber).toBe(before + 1);
    expect(created.status).toBe('NEW');
    expect(created.version).toBe(1);
    expect((created.address as { city: string }).city).toBe('Montreal');
    expect(created.typeCode).toBeNull();
  });

  it('keeps the address snapshot when the Property is edited afterwards', async () => {
    const property = await newProperty({ addressLine1: '1 Original Road' });
    const token = await accessTokenFor(CREATOR_PERMISSIONS);
    const created = (await createJob(token, property.id).expect(201)).body as {
      id: string;
    };

    await propertiesService.updateProperty(
      { organizationId },
      customerId,
      property.id,
      {
        name: 'Cedar Lane Building',
        addressLine1: '2 Corrected Road',
        addressLine2: 'Suite 200',
        city: 'Montreal',
        province: 'QC',
        postalCode: 'H3A 2T6',
        notes: null,
      },
    );

    // `BR-056`, `BR-057`: the snapshot is a frozen copy, not a live view of the Property.
    const response = await request(app.getHttpServer())
      .get(`/jobs/${created.id}`)
      .set('Authorization', `Bearer ${token}`)
      .expect(200);
    expect(
      (response.body as { address: { addressLine1: string } }).address
        .addressLine1,
    ).toBe('1 Original Road');
  });

  it('allocates the job number sequentially within one organization', async () => {
    const property = await newProperty();
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    const first = (await createJob(token, property.id).expect(201)).body as {
      jobNumber: number;
    };
    const second = (await createJob(token, property.id).expect(201)).body as {
      jobNumber: number;
    };

    expect(second.jobNumber).toBe(first.jobNumber + 1);
  });

  it('starts numbering at 1 for a new organization (`BR-052`)', async () => {
    const fresh = await newOrganizationWithProperty();

    const created = await request(app.getHttpServer())
      .post('/jobs')
      .set('Authorization', `Bearer ${fresh.token}`)
      .send({
        customerId: fresh.customer.id,
        propertyId: fresh.property.id,
        title: 'First job of this organization',
      })
      .expect(201);

    // The number is organization-scoped, so another organization's jobs do not advance it (`BR-052`).
    expect((created.body as { jobNumber: number }).jobNumber).toBe(1);
  });

  it('issues no duplicate job number under concurrent creation', async () => {
    const property = await newProperty();
    const token = await accessTokenFor(CREATOR_PERMISSIONS);
    const before = await highestJobNumber(organizationId);
    const concurrent = 8;

    const responses = await Promise.all(
      Array.from({ length: concurrent }, () => createJob(token, property.id)),
    );
    const numbers = responses.map((response) => {
      expect(response.status).toBe(201);
      return (response.body as { jobNumber: number }).jobNumber;
    });

    expect(new Set(numbers).size).toBe(concurrent);
    // The counter is the serialization point, so the batch is one contiguous block (`BR-052`,
    // `docs/domain/job-visit-domain-model.md` §13, §15).
    expect([...numbers].sort((left, right) => left - right)).toEqual(
      Array.from({ length: concurrent }, (_, index) => before + 1 + index),
    );
    expect(await highestJobNumber(organizationId)).toBe(before + concurrent);
  });

  it('requires the field values it cannot default', async () => {
    const property = await newProperty();
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    for (const field of ['customerId', 'propertyId', 'title'] as const) {
      const payload: Record<string, unknown> = body(property.id);
      delete payload[field];
      const response = await request(app.getHttpServer())
        .post('/jobs')
        .set('Authorization', `Bearer ${token}`)
        .send(payload)
        .expect(400);
      expect((response.body as { code: string }).code).toBe('VALIDATION_FAILED');
    }
  });

  it('rejects a whitespace-only title', async () => {
    const property = await newProperty();
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    const response = await createJob(token, property.id, {
      title: '   ',
    }).expect(400);
    expect((response.body as { code: string }).code).toBe('VALIDATION_FAILED');
  });

  it('answers a Customer the caller does not have as not found', async () => {
    const property = await newProperty();
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    const response = await createJob(token, property.id, {
      customerId: '00000000-0000-4000-8000-000000000001',
    }).expect(404);
    expect((response.body as { code: string }).code).toBe('CUSTOMER_NOT_FOUND');
  });

  it('answers an inactive Customer as a conflict', async () => {
    const property = await newProperty();
    const inactive = await createTestCustomer(database.db, {
      organizationId,
      name: 'Inactive Customer',
      status: 'INACTIVE',
    });
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    const response = await createJob(token, property.id, {
      customerId: inactive.id,
    }).expect(409);
    expect((response.body as { code: string }).code).toBe('CUSTOMER_INACTIVE');
  });

  it('answers a deleted Customer as not found', async () => {
    const property = await newProperty();
    const deleted = await createTestCustomer(database.db, {
      organizationId,
      name: 'Deleted Customer',
    });
    await database.db
      .update(customers)
      .set({
        status: 'INACTIVE',
        deletedAt: new Date(),
        deletedByMembershipId: membershipId,
      })
      .where(eq(customers.id, deleted.id));
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    const response = await createJob(token, property.id, {
      customerId: deleted.id,
    }).expect(404);
    expect((response.body as { code: string }).code).toBe('CUSTOMER_NOT_FOUND');
  });

  it('answers a Property the caller does not have as not found', async () => {
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    const response = await createJob(
      token,
      '00000000-0000-4000-8000-000000000002',
    ).expect(404);
    expect((response.body as { code: string }).code).toBe('PROPERTY_NOT_FOUND');
  });

  it('answers an archived Property as a conflict (`BR-083`)', async () => {
    const property = await newProperty({ addressLine1: '3 Archived Way' });
    await propertiesService.archiveProperty(
      { organizationId, membershipId },
      customerId,
      property.id,
      {},
    );
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    const response = await createJob(token, property.id).expect(409);
    expect((response.body as { code: string }).code).toBe(
      'PROPERTY_NOT_AVAILABLE_FOR_NEW_WORK',
    );
  });

  it('answers a Property held by another Customer as not found (`BR-050`)', async () => {
    const otherCustomer = await createTestCustomer(database.db, {
      organizationId,
      name: 'Other Customer',
    });
    const otherProperty = await newProperty({ customerId: otherCustomer.id });
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    // The Property exists in the caller's organization but does not belong to the named Customer.
    const response = await createJob(token, otherProperty.id).expect(404);
    expect((response.body as { code: string }).code).toBe('PROPERTY_NOT_FOUND');
  });

  it('answers a Property whose relationship to the Customer has ended as not found', async () => {
    const property = await newProperty({ addressLine1: '4 Ended Way' });
    await database.db
      .update(propertyCustomerRelationships)
      .set({ endedAt: new Date() })
      .where(
        and(
          eq(propertyCustomerRelationships.organizationId, organizationId),
          eq(propertyCustomerRelationships.propertyId, property.id),
        ),
      );
    const token = await accessTokenFor(CREATOR_PERMISSIONS);

    const response = await createJob(token, property.id).expect(404);
    expect((response.body as { code: string }).code).toBe('PROPERTY_NOT_FOUND');
  });

  it('keeps another organization out of the Customer and the Property', async () => {
    const fresh = await newOrganizationWithProperty();
    const foreignProperty = await newProperty();

    // This organization's own Customer with the other organization's Property.
    const foreignPropertyResponse = await request(app.getHttpServer())
      .post('/jobs')
      .set('Authorization', `Bearer ${fresh.token}`)
      .send({
        customerId: fresh.customer.id,
        propertyId: foreignProperty.id,
        title: 'Cross-organization attempt',
      })
      .expect(404);
    expect((foreignPropertyResponse.body as { code: string }).code).toBe(
      'PROPERTY_NOT_FOUND',
    );

    // The other organization's Customer.
    const foreignCustomerResponse = await request(app.getHttpServer())
      .post('/jobs')
      .set('Authorization', `Bearer ${fresh.token}`)
      .send({
        customerId,
        propertyId: fresh.property.id,
        title: 'Cross-organization attempt',
      })
      .expect(404);
    expect((foreignCustomerResponse.body as { code: string }).code).toBe(
      'CUSTOMER_NOT_FOUND',
    );

    // Neither attempt created anything.
    const created = await database.db
      .select({ id: jobs.id })
      .from(jobs)
      .where(
        and(
          eq(jobs.organizationId, fresh.organization.id),
          inArray(jobs.title, ['Cross-organization attempt']),
        ),
      );
    expect(created).toHaveLength(0);
  });

  it('does not disclose another organization’s Job', async () => {
    const property = await newProperty();
    const ownerToken = await accessTokenFor(CREATOR_PERMISSIONS);
    const created = (await createJob(ownerToken, property.id).expect(201)).body as {
      id: string;
    };
    const fresh = await newOrganizationWithProperty();
    // A reader admitted by the office read capability, so the answer is about the Job's scope rather
    // than about the capability (`BR-001`).
    const readerToken = await accessTokenFor(
      [CUSTOMER_PERMISSIONS.VIEW],
      fresh.organization.id,
    );

    await request(app.getHttpServer())
      .get(`/jobs/${created.id}`)
      .set('Authorization', `Bearer ${readerToken}`)
      .expect(404);
  });

  it('requires a session', async () => {
    const property = await newProperty();

    await request(app.getHttpServer())
      .post('/jobs')
      .send(body(property.id))
      .expect(401);
  });

  it('requires the create capability', async () => {
    const property = await newProperty();
    const token = await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await createJob(token, property.id).expect(403);
    expect((response.body as { code: string }).code).toBe('FORBIDDEN');
  });

  it('lets a member holding only the create capability create a Job', async () => {
    const property = await newProperty();
    // Creating a Job needs nothing else, which is what makes the capability a boundary of its own
    // rather than an inference from another (`BR-006`).
    const token = await accessTokenFor([JOB_PERMISSIONS.CREATE]);

    await createJob(token, property.id, {
      title: 'Capability-only create',
    }).expect(201);
  });
});
