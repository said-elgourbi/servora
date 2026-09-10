import { Test } from '@nestjs/testing';
import { toIndividualCustomerDto } from '../src/customers/customer.dto.js';
import { CustomersModule } from '../src/customers/customers.module.js';
import {
  CustomerNotFoundError,
  CustomersService,
} from '../src/customers/customers.service.js';
import { DatabaseModule } from '../src/database/database.module.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
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

  it('creates an individual customer with its subtype record atomically', async () => {
    const organization = await newOrganization();

    const created = await service.createIndividualCustomer(organization.id, {
      type: 'INDIVIDUAL',
      displayName: 'Jane Doe',
      email: 'jane@example.com',
      individual: { firstName: 'Jane', lastName: 'Doe', dateOfBirth: '1990-05-01' },
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
        status: 'ACTIVE',
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

    expect(dto.customer.organizationId).toBe('00000000-0000-0000-0000-000000000001');
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
    expect(listA[0].displayName).toBe('A1');
  });

  it("refuses to read or add contacts for another organization's customer", async () => {
    const organizationA = await newOrganization();
    const organizationB = await newOrganization();
    const created = await service.createCompanyCustomer(organizationA.id, {
      type: 'COMPANY',
      displayName: 'A Co',
      company: { legalName: 'A Co Ltd.' },
    });
    await service.addContact({ organizationId: organizationA.id }, created.customer.id, {
      firstName: 'Owner',
      lastName: 'A',
      isPrimary: true,
      isBillingContact: false,
      isJobContact: false,
    });

    await expect(
      service.listContacts({ organizationId: organizationB.id }, created.customer.id),
    ).rejects.toThrow(CustomerNotFoundError);
    await expect(
      service.addContact({ organizationId: organizationB.id }, created.customer.id, {
        firstName: 'Intruder',
        lastName: 'B',
        isPrimary: false,
        isBillingContact: false,
        isJobContact: false,
      }),
    ).rejects.toThrow(CustomerNotFoundError);
  });

  it('adds and lists addresses for a scoped customer', async () => {
    const organization = await newOrganization();
    const created = await service.createIndividualCustomer(organization.id, {
      type: 'INDIVIDUAL',
      displayName: 'Addr Person',
      individual: { firstName: 'Addr', lastName: 'Person' },
    });

    await service.addAddress({ organizationId: organization.id }, created.customer.id, {
      type: 'SERVICE',
      addressLine1: '1 Main',
      city: 'Ottawa',
      province: 'ON',
      postalCode: 'K1A 0B1',
      country: 'Canada',
      isDefault: true,
    });

    const addresses = await service.listAddresses(
      { organizationId: organization.id },
      created.customer.id,
    );

    expect(addresses).toHaveLength(1);
    expect(addresses[0].type).toBe('SERVICE');
    expect(addresses[0].isDefault).toBe(true);
  });

});
