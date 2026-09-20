import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_CONTACT_PERMISSIONS,
  CUSTOMER_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
  customerContacts,
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

/** Every capability the office write tests need, so each test names only its own difference. */
const OFFICE_PERMISSIONS = [
  CUSTOMER_PERMISSIONS.VIEW,
  CUSTOMER_PERMISSIONS.CREATE,
  CUSTOMER_CONTACT_PERMISSIONS.CREATE,
  CUSTOMER_CONTACT_PERMISSIONS.EDIT,
  CUSTOMER_CONTACT_PERMISSIONS.REMOVE,
] as const satisfies readonly PermissionCode[];

/**
 * The contact-person routes and the office read that reports them (`BR-095`; `ADR-022` D4, D8, D9).
 *
 * `POST /customers/:id/contacts` records a contact person with its own phone and email,
 * `PATCH /customers/:id/contacts/:contactId` edits one, and
 * `DELETE /customers/:id/contacts/:contactId` removes one softly. The tests cover the writes, the
 * one-primary invariant the database enforces, the version guard, the authorization each route
 * declares, and what the customer detail read makes of the rows afterwards: the order they come back
 * in, that a removed one is hidden without being deleted, and that reading them needs no contact
 * capability. The field read's own block is asserted in `job-details.e2e-spec.ts`, where the Job
 * Details response is the subject.
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

  /** Adds a contact through the API and returns the row the route answered with. */
  async function addContact(
    token: string,
    customerId: string,
    body: Record<string, unknown>,
  ): Promise<{ id: string; version: number; isPrimary: boolean }> {
    const response = await request(app.getHttpServer())
      .post(`/customers/${customerId}/contacts`)
      .set('Authorization', `Bearer ${token}`)
      .send(body)
      .expect(201);
    return response.body as {
      id: string;
      version: number;
      isPrimary: boolean;
    };
  }

  /** Reads one contact row straight from the database, so a soft removal is observable. */
  async function readContact(contactId: string) {
    const [row] = await database.db
      .select()
      .from(customerContacts)
      .where(eq(customerContacts.id, contactId))
      .limit(1);
    return row;
  }

  /** Reads the `contacts` array the customer detail returns, in the order it returns them (`BR-095`). */
  async function readDetailContacts(
    token: string,
    customerId: string,
  ): Promise<
    { id: string; firstName: string; isPrimary: boolean; version: number }[]
  > {
    const response = await request(app.getHttpServer())
      .get(`/customers/${customerId}`)
      .set('Authorization', `Bearer ${token}`)
      .expect(200);
    return (
      response.body as {
        contacts: {
          id: string;
          firstName: string;
          isPrimary: boolean;
          version: number;
        }[];
      }
    ).contacts;
  }

  it('requires authentication', async () => {
    await request(app.getHttpServer())
      .post('/customers/6a1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c/contacts')
      .send({ firstName: 'John', lastName: 'Smith' })
      .expect(401);
  });

  it('adds a contact person with their own phone and email', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Primary Contact Co');

    const response = await request(app.getHttpServer())
      .post(`/customers/${customerId}/contacts`)
      .set('Authorization', `Bearer ${token}`)
      .send({
        firstName: 'John',
        lastName: 'Smith',
        email: 'john@example.com',
        phone: '+15551234567',
        isPrimary: true,
      })
      .expect(201);

    expect(response.body).toMatchObject({
      customerId,
      firstName: 'John',
      lastName: 'Smith',
      email: 'john@example.com',
      phone: '+15551234567',
      isPrimary: true,
      isBillingContact: false,
      isJobContact: false,
      version: 1,
    });
    expect(typeof (response.body as { id: unknown }).id).toBe('string');

    // The contact is part of the customer's detail read (`BR-023`).
    const detail = await request(app.getHttpServer())
      .get(`/customers/${customerId}`)
      .set('Authorization', `Bearer ${token}`)
      .expect(200);
    const contacts = (
      detail.body as { contacts: { firstName: string; version: number }[] }
    ).contacts;
    expect(contacts).toHaveLength(1);
    expect(contacts[0].firstName).toBe('John');
    expect(contacts[0].version).toBe(1);
  });

  it('does not accept customers.edit as authorization for a contact write', async () => {
    const editor = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(editor, 'Forbidden Contact Co');
    // `customers.edit` maintains the customer's own records; it no longer authorizes a contact
    // write (`BR-095`; `ADR-022` D4).
    const customerEditorOnly = await accessTokenFor([
      CUSTOMER_PERMISSIONS.VIEW,
      CUSTOMER_PERMISSIONS.EDIT,
    ]);

    await request(app.getHttpServer())
      .post(`/customers/${customerId}/contacts`)
      .set('Authorization', `Bearer ${customerEditorOnly}`)
      .send({ firstName: 'Jane', lastName: 'Doe' })
      .expect(403);
  });

  it('reports a customer outside the caller organization as not found', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);

    await request(app.getHttpServer())
      .post('/customers/6a1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c/contacts')
      .set('Authorization', `Bearer ${token}`)
      .send({ firstName: 'John', lastName: 'Smith' })
      .expect(404);
  });

  it('rejects a body without a name', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Invalid Contact Co');

    const response = await request(app.getHttpServer())
      .post(`/customers/${customerId}/contacts`)
      .set('Authorization', `Bearer ${token}`)
      .send({ firstName: 'John' })
      .expect(400);

    expect((response.body as { code: string }).code).toBe('VALIDATION_FAILED');
  });

  it('gives a customer exactly one primary contact when a second one is created', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'One Primary Co');
    const first = await addContact(token, customerId, {
      firstName: 'First',
      lastName: 'Primary',
      isPrimary: true,
    });

    const second = await addContact(token, customerId, {
      firstName: 'Second',
      lastName: 'Primary',
      isPrimary: true,
    });

    expect(second.isPrimary).toBe(true);
    // The contact that lost the flag is a changed row, so its version moved on too (`ADR-022` D9).
    expect((await readContact(first.id))?.isPrimary).toBe(false);
    expect((await readContact(first.id))?.version).toBe(2);

    const detail = await request(app.getHttpServer())
      .get(`/customers/${customerId}`)
      .set('Authorization', `Bearer ${token}`)
      .expect(200);
    const contacts = (detail.body as { contacts: { isPrimary: boolean }[] })
      .contacts;
    expect(contacts.filter((contact) => contact.isPrimary)).toHaveLength(1);
  });

  it('orders the customer detail with the primary contact first, then the rest oldest-first', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Ordered Contacts Co');
    const first = await addContact(token, customerId, {
      firstName: 'First',
      lastName: 'Recorded',
    });
    const second = await addContact(token, customerId, {
      firstName: 'Second',
      lastName: 'Recorded',
    });
    // The primary is recorded **last**, so only `BR-095`'s primary-first rule can put it at the front:
    // the list cannot be showing creation order.
    const primary = await addContact(token, customerId, {
      firstName: 'Promoted',
      lastName: 'Primary',
      isPrimary: true,
    });

    const contacts = await readDetailContacts(token, customerId);

    expect(contacts.map((contact) => contact.id)).toEqual([
      primary.id,
      first.id,
      second.id,
    ]);
  });

  it('reads the contacts with customers.view and no contact capability', async () => {
    // `ADR-022` D4 adds no read capability: the contacts are part of the customer projection
    // `customers.view` already authorizes, so a member holding only it reads them.
    const writer = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(writer, 'Read Only Contacts Co');
    await addContact(writer, customerId, {
      firstName: 'John',
      lastName: 'Smith',
      isPrimary: true,
    });
    const viewerOnly = await accessTokenFor([CUSTOMER_PERMISSIONS.VIEW]);

    const contacts = await readDetailContacts(viewerOnly, customerId);

    expect(contacts.map((contact) => contact.firstName)).toEqual(['John']);
  });

  it('does not accept a contact capability as authorization to read the customer', async () => {
    // Capabilities are never substitutes for one another (`BR-006`, `BR-007`): a member trusted to add
    // a contact person is not thereby admitted to the customer's records.
    const writer = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(writer, 'Write Only Contacts Co');
    const contactWriterOnly = await accessTokenFor([
      CUSTOMER_CONTACT_PERMISSIONS.CREATE,
    ]);

    await request(app.getHttpServer())
      .get(`/customers/${customerId}`)
      .set('Authorization', `Bearer ${contactWriterOnly}`)
      .expect(403);
  });

  it('edits only what the request states and moves the version on', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Edit Contact Co');
    const contact = await addContact(token, customerId, {
      firstName: 'John',
      lastName: 'Smith',
      phone: '+15550000000',
      role: 'Site manager',
    });

    const response = await request(app.getHttpServer())
      .patch(`/customers/${customerId}/contacts/${contact.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({
        phone: '+15559999999',
        email: 'john@example.com',
        expectedVersion: 1,
      })
      .expect(200);

    expect(response.body).toMatchObject({
      id: contact.id,
      firstName: 'John',
      lastName: 'Smith',
      phone: '+15559999999',
      email: 'john@example.com',
      // A field the caller did not state keeps the value the contact holds (`BR-095`).
      role: 'Site manager',
      version: 2,
    });
  });

  it('clears the previous primary in the same transaction as a promotion', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Promote Contact Co');
    const first = await addContact(token, customerId, {
      firstName: 'First',
      lastName: 'Primary',
      isPrimary: true,
    });
    const second = await addContact(token, customerId, {
      firstName: 'Second',
      lastName: 'Person',
    });

    await request(app.getHttpServer())
      .patch(`/customers/${customerId}/contacts/${second.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ isPrimary: true, expectedVersion: second.version })
      .expect(200);

    expect((await readContact(second.id))?.isPrimary).toBe(true);
    expect((await readContact(first.id))?.isPrimary).toBe(false);

    const detail = await request(app.getHttpServer())
      .get(`/customers/${customerId}`)
      .set('Authorization', `Bearer ${token}`)
      .expect(200);
    const contacts = (detail.body as { contacts: { isPrimary: boolean }[] })
      .contacts;
    expect(contacts.filter((contact) => contact.isPrimary)).toHaveLength(1);
  });

  it('refuses an edit that names a version the contact has left', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Version Conflict Co');
    const contact = await addContact(token, customerId, {
      firstName: 'John',
      lastName: 'Smith',
    });

    await request(app.getHttpServer())
      .patch(`/customers/${customerId}/contacts/${contact.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ phone: '+15551111111', expectedVersion: 1 })
      .expect(200);

    const response = await request(app.getHttpServer())
      .patch(`/customers/${customerId}/contacts/${contact.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ phone: '+15552222222', expectedVersion: 1 })
      .expect(409);

    expect(response.body).toMatchObject({
      code: 'CONTACT_VERSION_CONFLICT',
      details: { currentVersion: 2 },
    });
    // The refused edit changed nothing.
    expect((await readContact(contact.id))?.phone).toBe('+15551111111');
  });

  it('refuses an edit that states no version', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Missing Version Co');
    const contact = await addContact(token, customerId, {
      firstName: 'John',
      lastName: 'Smith',
    });

    const response = await request(app.getHttpServer())
      .patch(`/customers/${customerId}/contacts/${contact.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ phone: '+15551111111' })
      .expect(400);

    expect((response.body as { code: string }).code).toBe('VALIDATION_FAILED');
  });

  it('removes a contact softly and refuses further writes to it', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Removed Contact Co');
    const contact = await addContact(token, customerId, {
      firstName: 'John',
      lastName: 'Smith',
      isPrimary: true,
    });

    await request(app.getHttpServer())
      .delete(`/customers/${customerId}/contacts/${contact.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ expectedVersion: 1 })
      .expect(204);

    // The removal is soft: the record, its actor and its moment survive (`BR-033`; `ADR-022` D8).
    const removed = await readContact(contact.id);
    expect(removed?.removedAt).toBeInstanceOf(Date);
    expect(removed?.removedByMembershipId).not.toBeNull();
    expect(removed?.version).toBe(2);

    // A removed contact is not part of an ordinary view, so it cannot be written again.
    await request(app.getHttpServer())
      .patch(`/customers/${customerId}/contacts/${contact.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ phone: '+15551111111', expectedVersion: 2 })
      .expect(404);
    await request(app.getHttpServer())
      .delete(`/customers/${customerId}/contacts/${contact.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ expectedVersion: 2 })
      .expect(404);
  });

  it('hides a removed contact from the detail and promotes no replacement', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Removed Primary Co');
    const primary = await addContact(token, customerId, {
      firstName: 'First',
      lastName: 'Primary',
      isPrimary: true,
    });
    const other = await addContact(token, customerId, {
      firstName: 'Second',
      lastName: 'Person',
    });

    await request(app.getHttpServer())
      .delete(`/customers/${customerId}/contacts/${primary.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ expectedVersion: primary.version })
      .expect(204);

    const contacts = await readDetailContacts(token, customerId);

    // Only the contact still in ordinary use is listed (`BR-095`; `ADR-022` D8) ...
    expect(contacts.map((contact) => contact.id)).toEqual([other.id]);
    // ... and removing the primary leaves **zero** primary: no other contact is promoted automatically
    // (`BR-095`; `ADR-022` D8, the analogue of `BR-069` for a Lead).
    expect(contacts.filter((contact) => contact.isPrimary)).toEqual([]);
    // The removed record survives, so "who removed this person, and when" stays answerable (`BR-033`).
    expect((await readContact(primary.id))?.removedAt).toBeInstanceOf(Date);
  });

  it('refuses a removal that names a version the contact has left', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Removal Version Co');
    const contact = await addContact(token, customerId, {
      firstName: 'John',
      lastName: 'Smith',
    });

    const response = await request(app.getHttpServer())
      .delete(`/customers/${customerId}/contacts/${contact.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ expectedVersion: 7 })
      .expect(409);

    expect(response.body).toMatchObject({
      code: 'CONTACT_VERSION_CONFLICT',
      details: { currentVersion: 1 },
    });
  });

  it('reports a contact of another customer as not found', async () => {
    const token = await accessTokenFor(OFFICE_PERMISSIONS);
    const customerId = await createCustomer(token, 'Contact Scope Co');
    const otherCustomerId = await createCustomer(token, 'Other Contact Co');
    const contact = await addContact(token, otherCustomerId, {
      firstName: 'John',
      lastName: 'Smith',
    });

    const response = await request(app.getHttpServer())
      .patch(`/customers/${customerId}/contacts/${contact.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ phone: '+15551111111', expectedVersion: 1 })
      .expect(404);

    expect((response.body as { code: string }).code).toBe('CONTACT_NOT_FOUND');
  });
});
