import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
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

/**
 * `POST /customers/:id/contacts` (`BR-023`).
 *
 * The route exists so a business customer's primary contact can be recorded while the customer is
 * created, without inventing a customer field the model does not have. The tests cover the write, the
 * tenant boundary and the authorization the route declares.
 */
describe('customer contacts (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Customer Contacts Org',
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
    const email = uniqueEmail('customer-contacts');
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
          deviceId: `customer-contacts-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return (response.body as { readonly accessToken: string }).accessToken;
  }

  /** Creates a company customer over HTTP, so the create contract is exercised too. */
  async function createCustomer(
    token: string,
    displayName = 'Contacts Customer',
  ): Promise<string> {
    const response = await request(app.getHttpServer())
      .post('/customers')
      .set('Authorization', `Bearer ${token}`)
      .send({
        type: 'COMPANY',
        displayName,
        company: { legalName: `${displayName} Ltd.` },
        phone: '+15551234567',
      })
      .expect(201);
    return (response.body as { customer: { id: string } }).customer.id;
  }

  it('requires authentication', async () => {
    await request(app.getHttpServer())
      .post('/customers/6a1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c/contacts')
      .send({ firstName: 'John', lastName: 'Smith' })
      .expect(401);
  });

  it('adds a primary contact to the customer with customers.edit', async () => {
    const token = await accessTokenFor([
      CUSTOMER_PERMISSIONS.VIEW,
      CUSTOMER_PERMISSIONS.CREATE,
      CUSTOMER_PERMISSIONS.EDIT,
    ]);
    const customerId = await createCustomer(token, 'Primary Contact Co');

    const response = await request(app.getHttpServer())
      .post(`/customers/${customerId}/contacts`)
      .set('Authorization', `Bearer ${token}`)
      .send({ firstName: 'John', lastName: 'Smith', isPrimary: true })
      .expect(201);

    expect(response.body).toMatchObject({
      customerId,
      firstName: 'John',
      lastName: 'Smith',
      isPrimary: true,
      isBillingContact: false,
      isJobContact: false,
    });
    expect(typeof (response.body as { id: unknown }).id).toBe('string');

    // The contact is part of the customer's detail read (`BR-023`).
    const detail = await request(app.getHttpServer())
      .get(`/customers/${customerId}`)
      .set('Authorization', `Bearer ${token}`)
      .expect(200);
    const contacts = (detail.body as { contacts: { firstName: string }[] })
      .contacts;
    expect(contacts).toHaveLength(1);
    expect(contacts[0].firstName).toBe('John');
  });

  it('forbids a caller without customers.edit', async () => {
    const editor = await accessTokenFor([
      CUSTOMER_PERMISSIONS.VIEW,
      CUSTOMER_PERMISSIONS.CREATE,
      CUSTOMER_PERMISSIONS.EDIT,
    ]);
    const customerId = await createCustomer(editor, 'Forbidden Contact Co');
    // customers.create must not stand in for customers.edit.
    const createOnly = await accessTokenFor([CUSTOMER_PERMISSIONS.CREATE]);

    await request(app.getHttpServer())
      .post(`/customers/${customerId}/contacts`)
      .set('Authorization', `Bearer ${createOnly}`)
      .send({ firstName: 'Jane', lastName: 'Doe' })
      .expect(403);
  });

  it('reports a customer outside the caller organization as not found', async () => {
    const token = await accessTokenFor([
      CUSTOMER_PERMISSIONS.VIEW,
      CUSTOMER_PERMISSIONS.EDIT,
    ]);

    await request(app.getHttpServer())
      .post('/customers/6a1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c/contacts')
      .set('Authorization', `Bearer ${token}`)
      .send({ firstName: 'John', lastName: 'Smith' })
      .expect(404);
  });

  it('rejects a body without a name', async () => {
    const token = await accessTokenFor([
      CUSTOMER_PERMISSIONS.VIEW,
      CUSTOMER_PERMISSIONS.CREATE,
      CUSTOMER_PERMISSIONS.EDIT,
    ]);
    const customerId = await createCustomer(token, 'Invalid Contact Co');

    const response = await request(app.getHttpServer())
      .post(`/customers/${customerId}/contacts`)
      .set('Authorization', `Bearer ${token}`)
      .send({ firstName: 'John' })
      .expect(400);

    expect((response.body as { code: string }).code).toBe('VALIDATION_FAILED');
  });
});
