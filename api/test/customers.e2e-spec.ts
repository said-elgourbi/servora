import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import { toIndividualCustomerDto } from '../src/customers/customer.dto.js';
import { CustomersModule } from '../src/customers/customers.module.js';
import {
  CustomerNotFoundError,
  CustomersService,
} from '../src/customers/customers.service.js';
import type { CustomerWithCounts } from '../src/customers/customers.service.js';
import { DatabaseModule } from '../src/database/database.module.js';
import {
  customers,
  jobs,
  organizationMembers,
  properties,
  propertyCustomerRelationships,
  visits,
} from '../src/database/schema.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

// Verifies tenant scoping and subtype integrity against a real PostgreSQL.
describe('CustomersService (e2e)', () => {
  let database: FoundationTestDatabase;
  let service: CustomersService;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const moduleRef = await Test.createTestingModule({
      imports: [DatabaseModule, CustomersModule],
    }).compile();
    service = moduleRef.get(CustomersService);
  });

  afterAll(async () => {
    await database.dispose();
  });

  async function newOrganization() {
    const organization = await createTestOrganization(database.db);
    database.cleanup.trackOrganization(organization.id);
    return organization;
  }

  /** A member of the organization, needed as the actor of a Property relationship (`BR-050`). */
  async function newMembership(organizationId: string) {
    const user = await createTestUser(database.db);
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, organizationId);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId: role.id })
      .returning();
    return member;
  }

  /** Creates a company customer whose only required subtype record is filled in. */
  async function createCustomer(organizationId: string, displayName: string) {
    const created = await service.createCompanyCustomer(organizationId, {
      type: 'COMPANY',
      displayName,
      company: { legalName: `${displayName} Ltd.` },
    });
    return created.customer;
  }

  /** The customer ids in a list result, sorted so assertions do not depend on ordering. */
  function ids(rows: CustomerWithCounts[]): string[] {
    return rows.map((row) => row.customer.id).sort();
  }

  /** A `SCHEDULED` Visit whose window is offset from the present, so it can be overdue or not. */
  function scheduledVisit(
    organizationId: string,
    jobId: string,
    propertyId: string,
    startOffsetMs: number,
    endOffsetMs: number,
  ) {
    const now = Date.now();
    return {
      organizationId,
      jobId,
      propertyId,
      locationAddressSnapshot: { addressLine1: '1 Main Street' },
      status: 'SCHEDULED',
      scheduledStart: new Date(now + startOffsetMs),
      scheduledEnd: new Date(now + endOffsetMs),
    };
  }

  it('creates an individual customer with its subtype record atomically', async () => {
    const organization = await newOrganization();

    const created = await service.createIndividualCustomer(organization.id, {
      type: 'INDIVIDUAL',
      displayName: 'Jane Doe',
      email: 'jane@example.com',
      individual: {
        firstName: 'Jane',
        lastName: 'Doe',
        dateOfBirth: '1990-05-01',
      },
    });

    expect(created.customer.organizationId).toBe(organization.id);
    expect(created.customer.type).toBe('INDIVIDUAL');
    expect(created.individual.customerId).toBe(created.customer.id);
    expect(created.individual.dateOfBirth).toBe('1990-05-01');
  });

  it('creates a company customer with its subtype record', async () => {
    const organization = await newOrganization();

    const created = await service.createCompanyCustomer(organization.id, {
      type: 'COMPANY',
      displayName: 'Acme Inc.',
      company: {
        legalName: 'Acme Incorporated',
        businessName: 'Acme',
        taxNumber: '123456789',
      },
    });

    expect(created.customer.type).toBe('COMPANY');
    expect(created.company.customerId).toBe(created.customer.id);
    expect(created.company.taxNumber).toBe('123456789');
  });

  it('rejects a customer whose subtype does not match its type', async () => {
    const organization = await newOrganization();

    // Model a hostile/buggy caller that omits the required subtype record.
    await expect(
      service.createIndividualCustomer(organization.id, {
        type: 'INDIVIDUAL',
        displayName: 'Broken',
        individual: undefined as never,
      }),
    ).rejects.toThrow(/INDIVIDUAL requires an individual record/);
  });

  it('maps an individual customer to a DTO that still carries the tenant scope', () => {
    const dto = toIndividualCustomerDto({
      customer: {
        id: '00000000-0000-0000-0000-000000000010',
        organizationId: '00000000-0000-0000-0000-000000000001',
        type: 'INDIVIDUAL',
        displayName: 'Dto Person',
        email: null,
        phone: null,
        billingEmail: null,
        billingPhone: null,
        notes: null,
        preferredContactMethod: 'NONE',
        language: 'en-CA',
        status: 'ACTIVE',
        deletedAt: null,
        deletedByMembershipId: null,
        deleteReason: null,
        createdAt: new Date('2026-01-01T00:00:00.000Z'),
        updatedAt: new Date('2026-01-01T00:00:00.000Z'),
      },
      individual: {
        customerId: '00000000-0000-0000-0000-000000000010',
        firstName: 'Dto',
        lastName: 'Person',
        dateOfBirth: null,
      },
    });

    expect(dto.customer.organizationId).toBe(
      '00000000-0000-0000-0000-000000000001',
    );
    expect(dto.individual.firstName).toBe('Dto');
  });

  it('scopes customer lookups to the owning organization', async () => {
    const organizationA = await newOrganization();
    const organizationB = await newOrganization();
    const created = await service.createCompanyCustomer(organizationA.id, {
      type: 'COMPANY',
      displayName: 'Only In A',
      company: { legalName: 'Only In A Ltd.' },
    });

    await expect(
      service.findCustomerInOrganization(
        { organizationId: organizationA.id },
        created.customer.id,
      ),
    ).resolves.toMatchObject({ id: created.customer.id });

    // The same customer id under another tenant is "not found", not "forbidden".
    await expect(
      service.findCustomerInOrganization(
        { organizationId: organizationB.id },
        created.customer.id,
      ),
    ).resolves.toBeNull();
  });

  it('lists only the customers owned by the requested organization', async () => {
    const organizationA = await newOrganization();
    const organizationB = await newOrganization();
    await service.createCompanyCustomer(organizationA.id, {
      type: 'COMPANY',
      displayName: 'A1',
      company: { legalName: 'A1' },
    });
    await service.createCompanyCustomer(organizationB.id, {
      type: 'COMPANY',
      displayName: 'B1',
      company: { legalName: 'B1' },
    });

    const listA = await service.listCustomersInOrganization({
      organizationId: organizationA.id,
    });

    expect(listA).toHaveLength(1);
    expect(listA[0].customer.displayName).toBe('A1');
  });

  it('derives the property and job counts each list row renders', async () => {
    const organization = await newOrganization();
    const counted = await service.createCompanyCustomer(organization.id, {
      type: 'COMPANY',
      displayName: 'Counted Co',
      company: { legalName: 'Counted Co Ltd.' },
    });
    await service.createCompanyCustomer(organization.id, {
      type: 'COMPANY',
      displayName: 'Empty Co',
      company: { legalName: 'Empty Co Ltd.' },
    });
    const member = await newMembership(organization.id);

    const [activeProperty] = await database.db
      .insert(properties)
      .values({
        organizationId: organization.id,
        addressLine1: '1 Main Street',
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .returning();
    await database.db.insert(propertyCustomerRelationships).values({
      organizationId: organization.id,
      propertyId: activeProperty.id,
      customerId: counted.customer.id,
      actorMembershipId: member.id,
    });

    // An ended relationship is history, so it is not one of the customer's properties.
    const [endedProperty] = await database.db
      .insert(properties)
      .values({
        organizationId: organization.id,
        addressLine1: '2 Main Street',
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B2',
      })
      .returning();
    await database.db.insert(propertyCustomerRelationships).values({
      organizationId: organization.id,
      propertyId: endedProperty.id,
      customerId: counted.customer.id,
      actorMembershipId: member.id,
      // Explicit start so `ended_at >= started_at` holds against the microsecond-precision
      // `now()` default (`property_customer_relationships_time_check`).
      startedAt: new Date(Date.now() - 86_400_000),
      endedAt: new Date(),
    });

    for (const jobNumber of [1, 2]) {
      await database.db.insert(jobs).values({
        organizationId: organization.id,
        jobNumber,
        customerId: counted.customer.id,
        title: `Job ${jobNumber}`,
      });
    }

    const summaries = await service.listCustomersInOrganization({
      organizationId: organization.id,
    });
    const countedRow = summaries.find(
      (row) => row.customer.id === counted.customer.id,
    );
    const emptyRow = summaries.find(
      (row) => row.customer.displayName === 'Empty Co',
    );

    expect(countedRow?.propertyCount).toBe(1);
    expect(countedRow?.jobCount).toBe(2);
    expect(emptyRow?.propertyCount).toBe(0);
    expect(emptyRow?.jobCount).toBe(0);
  });

  it('filters the list by customer status', async () => {
    const organization = await newOrganization();
    const active = await createCustomer(organization.id, 'Active Co');
    const inactive = await createCustomer(organization.id, 'Inactive Co');
    await database.db
      .update(customers)
      .set({ status: 'INACTIVE' })
      .where(eq(customers.id, inactive.id));

    const scope = { organizationId: organization.id };

    expect(
      ids(
        await service.listCustomersInOrganization(scope, {
          filters: { status: 'ACTIVE', jobs: 'ALL' },
        }),
      ),
    ).toEqual([active.id]);
    expect(
      ids(
        await service.listCustomersInOrganization(scope, {
          filters: { status: 'INACTIVE', jobs: 'ALL' },
        }),
      ),
    ).toEqual([inactive.id]);
    expect(
      ids(
        await service.listCustomersInOrganization(scope, {
          filters: { status: 'ALL', jobs: 'ALL' },
        }),
      ),
    ).toEqual([active.id, inactive.id].sort());
  });

  it('filters the list by open jobs and by having no jobs', async () => {
    const organization = await newOrganization();
    const open = await createCustomer(organization.id, 'Open Co');
    const closed = await createCustomer(organization.id, 'Closed Co');
    const none = await createCustomer(organization.id, 'No Jobs Co');

    // An open Job is any non-terminal Job (`BR-058`); a COMPLETED Job is not open, and a customer
    // with no Jobs at all has no open Jobs either.
    await database.db.insert(jobs).values({
      organizationId: organization.id,
      jobNumber: 1,
      customerId: open.id,
      title: 'Open job',
      status: 'IN_PROGRESS',
    });
    await database.db.insert(jobs).values({
      organizationId: organization.id,
      jobNumber: 2,
      customerId: closed.id,
      title: 'Closed job',
      status: 'COMPLETED',
    });

    const scope = { organizationId: organization.id };

    expect(
      ids(
        await service.listCustomersInOrganization(scope, {
          filters: { status: 'ALL', jobs: 'HAS_OPEN_JOBS' },
        }),
      ),
    ).toEqual([open.id]);
    expect(
      ids(
        await service.listCustomersInOrganization(scope, {
          filters: { status: 'ALL', jobs: 'NO_OPEN_JOBS' },
        }),
      ),
    ).toEqual([closed.id, none.id].sort());
    expect(
      ids(
        await service.listCustomersInOrganization(scope, {
          filters: { status: 'ALL', jobs: 'NO_JOBS' },
        }),
      ),
    ).toEqual([none.id]);
  });

  it('filters the list by overdue visits', async () => {
    const organization = await newOrganization();
    const overdue = await createCustomer(organization.id, 'Overdue Co');
    const upcoming = await createCustomer(organization.id, 'Upcoming Co');

    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId: organization.id,
        addressLine1: '1 Main Street',
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .returning();
    const [overdueJob] = await database.db
      .insert(jobs)
      .values({
        organizationId: organization.id,
        jobNumber: 1,
        customerId: overdue.id,
        title: 'Overdue job',
      })
      .returning();
    const [upcomingJob] = await database.db
      .insert(jobs)
      .values({
        organizationId: organization.id,
        jobNumber: 2,
        customerId: upcoming.id,
        title: 'Upcoming job',
      })
      .returning();

    // Only a Visit that is still `SCHEDULED` past its scheduled end is overdue.
    await database.db
      .insert(visits)
      .values([
        scheduledVisit(organization.id, overdueJob.id, property.id, -7_200_000, -3_600_000),
        scheduledVisit(organization.id, upcomingJob.id, property.id, 3_600_000, 7_200_000),
      ]);

    expect(
      ids(
        await service.listCustomersInOrganization(
          { organizationId: organization.id },
          { filters: { status: 'ALL', jobs: 'HAS_OVERDUE_VISITS' } },
        ),
      ),
    ).toEqual([overdue.id]);
  });

  it("refuses to read or add contacts for another organization's customer", async () => {
    const organizationA = await newOrganization();
    const organizationB = await newOrganization();
    const created = await service.createCompanyCustomer(organizationA.id, {
      type: 'COMPANY',
      displayName: 'A Co',
      company: { legalName: 'A Co Ltd.' },
    });
    await service.addContact(
      { organizationId: organizationA.id },
      created.customer.id,
      {
        firstName: 'Owner',
        lastName: 'A',
        isPrimary: true,
        isBillingContact: false,
        isJobContact: false,
      },
    );

    await expect(
      service.listContacts(
        { organizationId: organizationB.id },
        created.customer.id,
      ),
    ).rejects.toThrow(CustomerNotFoundError);
    await expect(
      service.addContact(
        { organizationId: organizationB.id },
        created.customer.id,
        {
          firstName: 'Intruder',
          lastName: 'B',
          isPrimary: false,
          isBillingContact: false,
          isJobContact: false,
        },
      ),
    ).rejects.toThrow(CustomerNotFoundError);
  });

  it('adds and lists addresses for a scoped customer', async () => {
    const organization = await newOrganization();
    const created = await service.createIndividualCustomer(organization.id, {
      type: 'INDIVIDUAL',
      displayName: 'Addr Person',
      individual: { firstName: 'Addr', lastName: 'Person' },
    });

    await service.addAddress(
      { organizationId: organization.id },
      created.customer.id,
      {
        type: 'SERVICE',
        addressLine1: '1 Main',
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
        country: 'Canada',
        isDefault: true,
      },
    );

    const addresses = await service.listAddresses(
      { organizationId: organization.id },
      created.customer.id,
    );

    expect(addresses).toHaveLength(1);
    expect(addresses[0].type).toBe('SERVICE');
    expect(addresses[0].isDefault).toBe(true);
  });
});
