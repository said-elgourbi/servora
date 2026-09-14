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
  customerIndividuals,
  customerLifecycleHistory,
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
const UNKNOWN_CUSTOMER_ID = '6a1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c';

/**
 * Converting a customer between individual and company (`BR-087`).
 *
 * The conversion is an edit that changes `customers.type` and replaces the subtype record in one
 * transaction, records the conversion in lifecycle history, and leaves the customer's identity, its
 * other data and its Properties untouched.
 */
describe('customer type conversion (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let organizationId: string;
  let deviceSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Customer Conversion Org',
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

  /** A session holding exactly [granted], with the membership it belongs to. */
  async function signInFor(
    granted: readonly PermissionCode[],
  ): Promise<{ token: string; membershipId: string }> {
    const email = uniqueEmail('customer-conversion');
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
          deviceId: `customer-conversion-device-${deviceSequence}`,
        },
      })
      .expect(200);
    return {
      token: (response.body as { readonly accessToken: string }).accessToken,
      membershipId: member.id,
    };
  }

  /** Everything a conversion and its consequences need to be observed. */
  const authorPermissions: readonly PermissionCode[] = [
    CUSTOMER_PERMISSIONS.VIEW,
    CUSTOMER_PERMISSIONS.CREATE,
    CUSTOMER_PERMISSIONS.EDIT,
    PROPERTY_PERMISSIONS.VIEW,
    PROPERTY_PERMISSIONS.CREATE,
  ];

  async function createCustomer(
    token: string,
    body: Record<string, unknown>,
  ): Promise<{ id: string }> {
    const response = await request(app.getHttpServer())
      .post('/customers')
      .set('Authorization', `Bearer ${token}`)
      .send(body)
      .expect(201);
    return { id: (response.body as { customer: { id: string } }).customer.id };
  }

  /** The subtype rows a customer has, read straight from the tables. */
  async function subtypeRows(customerId: string) {
    const [stored] = await database.db
      .select({
        type: customers.type,
        individualId: customerIndividuals.customerId,
        firstName: customerIndividuals.firstName,
        companyId: customerCompanies.customerId,
        legalName: customerCompanies.legalName,
      })
      .from(customers)
      .leftJoin(
        customerIndividuals,
        eq(customerIndividuals.customerId, customers.id),
      )
      .leftJoin(
        customerCompanies,
        eq(customerCompanies.customerId, customers.id),
      )
      .where(eq(customers.id, customerId))
      .limit(1);
    return stored;
  }

  async function lifecycleRows(customerId: string) {
    return database.db
      .select()
      .from(customerLifecycleHistory)
      .where(
        and(
          eq(customerLifecycleHistory.organizationId, organizationId),
          eq(customerLifecycleHistory.customerId, customerId),
        ),
      );
  }

  it('requires authentication', async () => {
    await request(app.getHttpServer())
      .patch(`/customers/${UNKNOWN_CUSTOMER_ID}`)
      .send({ displayName: 'Unauthenticated Edit' })
      .expect(401);
  });

  it('converts an individual into a company, records it and keeps the rest', async () => {
    const { token, membershipId } = await signInFor(authorPermissions);
    const customer = await createCustomer(token, {
      type: 'INDIVIDUAL',
      displayName: 'Jordan Lee',
      phone: '+15551234567',
      notes: 'Gate code 4412.',
      individual: {
        firstName: 'Jordan',
        lastName: 'Lee',
        dateOfBirth: '1985-04-02',
      },
    });

    // A Property the customer already has, so the conversion can be shown not to disturb it.
    await request(app.getHttpServer())
      .post(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${token}`)
      .send({
        name: 'Cedar Lane Building',
        addressLine1: '987 Cedar Lane',
        city: 'Montreal',
        province: 'QC',
        postalCode: 'H3A 2T6',
      })
      .expect(201);

    const response = await request(app.getHttpServer())
      .patch(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({
        type: 'COMPANY',
        displayName: 'Cedar Property Management',
        company: { legalName: 'Cedar Property Management Ltd.' },
      })
      .expect(200);

    const body = response.body as Record<string, unknown>;
    expect(body).toMatchObject({
      customer: {
        id: customer.id,
        type: 'COMPANY',
        displayName: 'Cedar Property Management',
        phone: '+15551234567',
        notes: 'Gate code 4412.',
      },
      company: { legalName: 'Cedar Property Management Ltd.' },
    });
    // The subtype the customer left is gone, not carried along.
    expect(body.individual).toBeUndefined();

    expect(await subtypeRows(customer.id)).toMatchObject({
      type: 'COMPANY',
      individualId: null,
      firstName: null,
      companyId: customer.id,
      legalName: 'Cedar Property Management Ltd.',
    });

    const history = await lifecycleRows(customer.id);
    expect(history).toHaveLength(1);
    expect(history[0]).toMatchObject({
      action: 'TYPE_CONVERTED',
      fromType: 'INDIVIDUAL',
      toType: 'COMPANY',
      actorMembershipId: membershipId,
    });
    expect(history[0].recordedAt).toBeInstanceOf(Date);

    // The customer's Property survived the conversion and its scoped read still resolves.
    const properties = await request(app.getHttpServer())
      .get(`/customers/${customer.id}/properties`)
      .set('Authorization', `Bearer ${token}`)
      .expect(200);
    expect(properties.body).toHaveLength(1);
  });

  it('converts a company into an individual and keeps the other data', async () => {
    const { token, membershipId } = await signInFor(authorPermissions);
    const customer = await createCustomer(token, {
      type: 'COMPANY',
      displayName: 'ABC Property Management',
      email: 'hello@abc.example',
      company: {
        legalName: 'ABC Property Management Ltd.',
        taxNumber: '123456789',
      },
    });

    const response = await request(app.getHttpServer())
      .patch(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({
        type: 'INDIVIDUAL',
        displayName: 'Alex Morgan',
        individual: { firstName: 'Alex', lastName: 'Morgan' },
      })
      .expect(200);

    const body = response.body as Record<string, unknown>;
    expect(body).toMatchObject({
      customer: {
        id: customer.id,
        type: 'INDIVIDUAL',
        displayName: 'Alex Morgan',
        email: 'hello@abc.example',
      },
      individual: {
        customerId: customer.id,
        firstName: 'Alex',
        lastName: 'Morgan',
        dateOfBirth: null,
      },
    });
    expect(body.company).toBeUndefined();

    expect(await subtypeRows(customer.id)).toMatchObject({
      type: 'INDIVIDUAL',
      individualId: customer.id,
      firstName: 'Alex',
      companyId: null,
      legalName: null,
    });

    const history = await lifecycleRows(customer.id);
    expect(history).toHaveLength(1);
    expect(history[0]).toMatchObject({
      fromType: 'COMPANY',
      toType: 'INDIVIDUAL',
      actorMembershipId: membershipId,
    });
  });

  it('treats re-stating the current type as an edit, not a conversion', async () => {
    // `PUT` is the same edit as `PATCH` here, and an edit that keeps the customer's type writes no
    // lifecycle event (`BR-087`).
    const { token } = await signInFor(authorPermissions);
    const customer = await createCustomer(token, {
      type: 'COMPANY',
      displayName: 'Stated Company',
      company: { legalName: 'Stated Company Ltd.' },
    });

    await request(app.getHttpServer())
      .put(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({
        type: 'COMPANY',
        displayName: 'Stated Company',
        company: { legalName: 'Stated Company Inc.' },
      })
      .expect(200);

    expect(await subtypeRows(customer.id)).toMatchObject({
      type: 'COMPANY',
      legalName: 'Stated Company Inc.',
    });
    expect(await lifecycleRows(customer.id)).toHaveLength(0);
  });

  it('rejects a conversion that omits the required fields of the new type', async () => {
    const { token } = await signInFor(authorPermissions);
    const customer = await createCustomer(token, {
      type: 'INDIVIDUAL',
      displayName: 'Rejected Conversion',
      individual: { firstName: 'Casey', lastName: 'Nguyen' },
    });

    const response = await request(app.getHttpServer())
      .patch(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ type: 'COMPANY', displayName: 'Rejected Conversion' })
      .expect(400);
    expect(response.body).toMatchObject({ code: 'VALIDATION_FAILED' });

    // Nothing was converted: the customer is still the individual it was (`BR-087`).
    expect(await subtypeRows(customer.id)).toMatchObject({
      type: 'INDIVIDUAL',
      firstName: 'Casey',
      companyId: null,
    });
    expect(await lifecycleRows(customer.id)).toHaveLength(0);
  });

  it('rejects the subtype the customer is leaving', async () => {
    const { token } = await signInFor(authorPermissions);
    const customer = await createCustomer(token, {
      type: 'INDIVIDUAL',
      displayName: 'Contradictory Body',
      individual: { firstName: 'Dana', lastName: 'Roy' },
    });

    await request(app.getHttpServer())
      .patch(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({
        type: 'COMPANY',
        displayName: 'Contradictory Body',
        company: { legalName: 'Contradictory Body Ltd.' },
        individual: { firstName: 'Dana', lastName: 'Roy' },
      })
      .expect(400);
  });

  it('rejects a subtype payload that contradicts the stored type', async () => {
    const { token } = await signInFor(authorPermissions);
    const customer = await createCustomer(token, {
      type: 'COMPANY',
      displayName: 'Stored Company',
      company: { legalName: 'Stored Company Ltd.' },
    });

    // No type was stated, so this is not a conversion and the payload cannot be applied: it is
    // reported rather than silently dropped (`BR-042`).
    await request(app.getHttpServer())
      .patch(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ individual: { firstName: 'Sam', lastName: 'Curran' } })
      .expect(400);

    expect(await subtypeRows(customer.id)).toMatchObject({
      type: 'COMPANY',
      individualId: null,
      legalName: 'Stored Company Ltd.',
    });
  });

  it('records no lifecycle event for an ordinary edit', async () => {
    const { token } = await signInFor(authorPermissions);
    const customer = await createCustomer(token, {
      type: 'INDIVIDUAL',
      displayName: 'Ordinary Edit',
      individual: { firstName: 'Robin', lastName: 'Blake' },
    });

    await request(app.getHttpServer())
      .patch(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ displayName: 'Renamed Customer', status: 'INACTIVE' })
      .expect(200);

    expect(await lifecycleRows(customer.id)).toHaveLength(0);
    expect(await subtypeRows(customer.id)).toMatchObject({
      type: 'INDIVIDUAL',
      firstName: 'Robin',
    });
  });

  it('forbids a caller without customers.edit', async () => {
    const editor = await signInFor(authorPermissions);
    const customer = await createCustomer(editor.token, {
      type: 'INDIVIDUAL',
      displayName: 'Forbidden Conversion',
      individual: { firstName: 'Kim', lastName: 'Tremblay' },
    });
    // customers.create must not stand in for customers.edit (`BR-007`).
    const createOnly = await signInFor([CUSTOMER_PERMISSIONS.CREATE]);

    await request(app.getHttpServer())
      .patch(`/customers/${customer.id}`)
      .set('Authorization', `Bearer ${createOnly.token}`)
      .send({
        type: 'COMPANY',
        displayName: 'Forbidden Conversion',
        company: { legalName: 'Forbidden Conversion Ltd.' },
      })
      .expect(403);

    expect(await subtypeRows(customer.id)).toMatchObject({
      type: 'INDIVIDUAL',
      companyId: null,
    });
  });

  it('reports a customer outside the caller organization as not found', async () => {
    const { token } = await signInFor(authorPermissions);

    await request(app.getHttpServer())
      .patch(`/customers/${UNKNOWN_CUSTOMER_ID}`)
      .set('Authorization', `Bearer ${token}`)
      .send({
        type: 'COMPANY',
        displayName: 'Other Organization',
        company: { legalName: 'Other Organization Ltd.' },
      })
      .expect(404);
  });
});
