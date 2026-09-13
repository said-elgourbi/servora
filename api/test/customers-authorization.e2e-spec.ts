import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
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
  organizationMemberPermissions,
  organizationMembers,
  permissions,
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

describe('customer endpoint authorization (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Customer Auth Org',
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

  async function accessTokenFor(
    granted: readonly PermissionCode[],
  ): Promise<string> {
    return (await signInFor(granted)).accessToken;
  }

  async function signInFor(
    granted: readonly PermissionCode[],
    options: { extraEmptyMembership?: boolean } = {},
  ) {
    const email = uniqueEmail('customer-auth');
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(PASSWORD),
    });
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, organizationId);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({
        organizationId,
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
        organizationId,
        memberId: member.id,
        permissionId: permission.id,
      });
    }
    if (options.extraEmptyMembership === true) {
      const extraOrganization = await createTestOrganization(database.db, {
        name: 'Empty Permission Org',
      });
      database.cleanup.trackOrganization(extraOrganization.id);
      const extraRole = await createTestOrganizationRole(
        database.db,
        extraOrganization.id,
      );
      await database.db.insert(organizationMembers).values({
        organizationId: extraOrganization.id,
        userId: user.id,
        roleId: extraRole.id,
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
          deviceId: `customer-auth-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return response.body as {
      readonly accessToken: string;
      readonly permissions: readonly string[];
    };
  }

  async function createStoredCustomer(name = 'Authorization Customer') {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId,
        type: 'COMPANY',
        displayName: name,
      })
      .returning();
    await database.db.insert(customerCompanies).values({
      customerId: customer.id,
      legalName: name,
    });
    return customer;
  }

  it('requires authentication before customer permissions are evaluated', async () => {
    await request(app.getHttpServer()).get('/customers').expect(401);
  });

  it('returns effective customer permissions in the sign-in response', async () => {
    const response = await signInFor([CUSTOMER_PERMISSIONS.VIEW], {
      extraEmptyMembership: true,
    });

    expect(response.permissions).toContain(CUSTOMER_PERMISSIONS.VIEW);
  });

  it('returns effective customer permissions from the authenticated user endpoint', async () => {
    const response = await signInFor([CUSTOMER_PERMISSIONS.VIEW], {
      extraEmptyMembership: true,
    });

    const me = await request(app.getHttpServer())
      .get('/auth/me')
      .set('Authorization', `Bearer ${response.accessToken}`)
      .expect(200);

    expect(me.body.permissions).toContain(CUSTOMER_PERMISSIONS.VIEW);
  });

  it('allows and forbids listing customers with customers.view', async () => {
    await createStoredCustomer('Listable Customer');
    const allowed = await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW]);
    const forbidden = await accessTokenFor([]);

    await request(app.getHttpServer())
      .get('/customers')
      .set('Authorization', `Bearer ${allowed}`)
      .expect(200);

    const response = await request(app.getHttpServer())
      .get('/customers')
      .set('Authorization', `Bearer ${forbidden}`)
      .expect(403);
    expect(response.body.code).toBe('FORBIDDEN');
  });

  it('accepts the documented customer list filters', async () => {
    const allowed = await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW]);

    await request(app.getHttpServer())
      .get('/customers?status=ACTIVE&jobs=HAS_OPEN_JOBS')
      .set('Authorization', `Bearer ${allowed}`)
      .expect(200);
  });

  it('rejects an unknown customer list filter value', async () => {
    const allowed = await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW]);

    const response = await request(app.getHttpServer())
      .get('/customers?jobs=HAS_JOBS')
      .set('Authorization', `Bearer ${allowed}`)
      .expect(400);
    expect(response.body.code).toBe('VALIDATION_FAILED');
  });

  it('allows and forbids retrieving customer details with customers.view', async () => {
    const customer = await createStoredCustomer('Readable Customer');
    const allowed = await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW]);
    const forbidden = await accessTokenFor([]);

    const response = await request(app.getHttpServer())
      .get(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${allowed}`)
      .expect(200);
    expect(response.body.customer.displayName).toBe('Readable Customer');

    await request(app.getHttpServer())
      .get(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${forbidden}`)
      .expect(403);
  });

  it('allows and forbids creating customers with customers.create', async () => {
    const allowed = await accessTokenFor([CUSTOMER_PERMISSIONS.CREATE]);
    const forbidden = await accessTokenFor([]);
    const body = {
      type: 'COMPANY',
      displayName: 'Created Customer',
      company: { legalName: 'Created Customer Ltd.' },
    };

    await request(app.getHttpServer())
      .post('/customers')
      .set('Authorization', `Bearer ${allowed}`)
      .send(body)
      .expect(201);

    await request(app.getHttpServer())
      .post('/customers')
      .set('Authorization', `Bearer ${forbidden}`)
      .send(body)
      .expect(403);
  });

  it('allows and forbids editing customers with customers.edit', async () => {
    const customer = await createStoredCustomer('Editable Customer');
    const allowed = await accessTokenFor([CUSTOMER_PERMISSIONS.EDIT]);
    const forbidden = await accessTokenFor([]);

    const response = await request(app.getHttpServer())
      .patch(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${allowed}`)
      .send({ displayName: 'Edited Customer' })
      .expect(200);
    expect(response.body.customer.displayName).toBe('Edited Customer');

    await request(app.getHttpServer())
      .patch(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${forbidden}`)
      .send({ displayName: 'Forbidden Edit' })
      .expect(403);
  });

  it('allows and forbids adding a Property with properties.create', async () => {
    const customer = await createStoredCustomer('Property Owner Customer');
    const allowed = await accessTokenFor([
      PROPERTY_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.CREATE,
    ]);
    // A customer capability must not stand in for a Property capability (`BR-085`).
    const customerEditOnly = await accessTokenFor([CUSTOMER_PERMISSIONS.EDIT]);
    const forbidden = await accessTokenFor([]);
    const body = {
      name: 'Cedar Lane Building',
      addressLine1: '987 Cedar Lane',
      addressLine2: 'Suite 200',
      city: 'Montreal',
      province: 'QC',
      postalCode: 'H3A 2T6',
      notes: 'Mechanical room in basement B1.',
    };

    const response = await request(app.getHttpServer())
      .post(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${allowed}`)
      .send(body)
      .expect(201);
    expect(response.body.addressLine1).toBe('987 Cedar Lane');
    expect(response.body.addressLine2).toBe('Suite 200');
    expect(response.body.province).toBe('QC');
    expect(response.body.country).toBe('Canada');
    expect(response.body.jobCount).toBe(0);
    expect(response.body.lastServiceAt).toBeNull();

    // It is then one of the customer's active Properties (`BR-050`, `BR-081`).
    const listed = await request(app.getHttpServer())
      .get(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${allowed}`)
      .expect(200);
    expect(listed.body.map((row: { id: string }) => row.id)).toEqual([
      response.body.id,
    ]);

    await request(app.getHttpServer())
      .post(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${customerEditOnly}`)
      .send(body)
      .expect(403);
    await request(app.getHttpServer())
      .post(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${forbidden}`)
      .send(body)
      .expect(403);
  });

  it('rejects a Property province outside the Canadian vocabulary', async () => {
    const customer = await createStoredCustomer('Invalid Province Customer');
    const allowed = await accessTokenFor([PROPERTY_PERMISSIONS.CREATE]);

    const response = await request(app.getHttpServer())
      .post(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${allowed}`)
      .send({
        addressLine1: '987 Cedar Lane',
        city: 'Montreal',
        province: 'Quebec',
        postalCode: 'H3A 2T6',
      })
      .expect(400);
    expect(response.body.code).toBe('VALIDATION_FAILED');
  });

  it('answers not found when adding a Property to a customer outside the caller organization', async () => {
    const allowed = await accessTokenFor([PROPERTY_PERMISSIONS.CREATE]);
    const unknownId = '00000000-0000-0000-0000-0000000000ee';

    // An id the caller's organization does not own is "not found", never "forbidden" (`BR-001`).
    await request(app.getHttpServer())
      .post(`/customers/${unknownId}/properties`)
      .set('Authorization', `Bearer ${allowed}`)
      .send({
        addressLine1: '1 Main Street',
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .expect(404);
  });

  it('allows and forbids archiving customers with customers.archive', async () => {
    const allowedCustomer = await createStoredCustomer('Archivable Customer');
    const forbiddenCustomer = await createStoredCustomer(
      'Not Archivable Customer',
    );
    const allowed = await accessTokenFor([CUSTOMER_PERMISSIONS.ARCHIVE]);
    const forbidden = await accessTokenFor([]);

    const response = await request(app.getHttpServer())
      .post(`/customers/${allowedCustomer.id}/archive`)
      .set('Authorization', `Bearer ${allowed}`)
      .send({ reason: 'No longer active.' })
      .expect(201);
    expect(response.body.status).toBe('INACTIVE');
    expect(response.body.deletedAt).toEqual(expect.any(String));

    await request(app.getHttpServer())
      .post(`/customers/${forbiddenCustomer.id}/archive`)
      .set('Authorization', `Bearer ${forbidden}`)
      .send({})
      .expect(403);
  });

  it('allows and forbids the customer detail read paths with customers.view', async () => {
    const customer = await createStoredCustomer('Projected Customer');
    const allowed = await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW]);
    const forbidden = await accessTokenFor([]);

    await request(app.getHttpServer())
      .get(`/customers/${customer.id}/jobs`)
      .set('Authorization', `Bearer ${allowed}`)
      .expect(200);

    // The Job projection may not be read without the permission, and not without a session.
    await request(app.getHttpServer())
      .get(`/customers/${customer.id}/jobs`)
      .set('Authorization', `Bearer ${forbidden}`)
      .expect(403);
    await request(app.getHttpServer())
      .get(`/customers/${customer.id}/jobs`)
      .expect(401);
  });

  it('allows the Property projection only with properties.view', async () => {
    const customer = await createStoredCustomer('Property Read Customer');
    const allowed = await accessTokenFor([PROPERTY_PERMISSIONS.VIEW]);
    // A customer capability must not open a Property read (`BR-085`).
    const customerViewOnly = await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW]);
    const forbidden = await accessTokenFor([]);

    await request(app.getHttpServer())
      .get(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${allowed}`)
      .expect(200);

    await request(app.getHttpServer())
      .get(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${customerViewOnly}`)
      .expect(403);
    await request(app.getHttpServer())
      .get(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${forbidden}`)
      .expect(403);
    await request(app.getHttpServer())
      .get(`/customers/${customer.id}/properties`)
      .expect(401);
  });

  it('answers not found for a customer outside the caller organization', async () => {
    const allowed = await accessTokenFor([
      CUSTOMER_PERMISSIONS.VIEW,
      PROPERTY_PERMISSIONS.VIEW,
    ]);
    const unknownId = '00000000-0000-0000-0000-0000000000ff';

    // An id the caller's organization does not own is "not found", never "forbidden" (`BR-001`).
    for (const path of [
      `/customers/${unknownId}`,
      `/customers/${unknownId}/properties`,
      `/customers/${unknownId}/jobs`,
    ]) {
      await request(app.getHttpServer())
        .get(path)
        .set('Authorization', `Bearer ${allowed}`)
        .expect(404);
    }
  });
});
