import { Injectable } from '@nestjs/common';
import { and, desc, eq, getTableColumns } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  customerAddresses,
  customerCompanies,
  customerContacts,
  customerIndividuals,
  customers,
} from '../database/schema.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type { CreateCustomerAddressDto } from './customer-address.dto.js';
import type { CreateCustomerContactDto } from './customer-contact.dto.js';
import type {
  CreateCompanyCustomerDto,
  CreateIndividualCustomerDto,
} from './customer.dto.js';
import { assertCustomerSubtypeIntegrity } from './customer-subtype.js';
import type {
  CompanyCustomer,
  Customer,
  CustomerAddress,
  CustomerContact,
  CustomerType,
  IndividualCustomer,
} from './customer.types.js';

/**
 * Raised when an organisation-owned customer is not visible to the caller's
 * tenant scope. Callers must treat this as "not found" (never "forbidden") so
 * the API does not leak the existence of another organization's records.
 */
export class CustomerNotFoundError extends Error {
  constructor(customerId: string) {
    super(`Customer ${customerId} was not found in the requested organization.`);
    this.name = 'CustomerNotFoundError';
  }
}

/**
 * Owns customer persistence.
 *
 * TENANCY: every read/write of an organisation-owned entity is scoped by
 * `(organization_id, id)`. There is deliberately no method that fetches a
 * customer, contact or address by primary key alone.
 */
@Injectable()
export class CustomersService {
  constructor(private readonly database: DatabaseService) {}

  private get db() {
    return this.database.db;
  }

  /** Creates a customer header plus its required individual subtype record atomically. */
  async createIndividualCustomer(
    organizationId: string,
    input: CreateIndividualCustomerDto,
  ): Promise<IndividualCustomer> {
    assertCustomerSubtypeIntegrity({
      type: 'INDIVIDUAL',
      hasIndividual: Boolean(input.individual),
      hasCompany: false,
    });

    return this.db.transaction(async (tx) => {
      const [customer] = await tx
        .insert(customers)
        .values(this.headerValues(organizationId, 'INDIVIDUAL', input))
        .returning();
      const [individual] = await tx
        .insert(customerIndividuals)
        .values({
          customerId: customer.id,
          firstName: input.individual.firstName,
          lastName: input.individual.lastName,
          dateOfBirth: input.individual.dateOfBirth ?? null,
        })
        .returning();
      return { customer, individual };
    });
  }

  /** Creates a customer header plus its required company subtype record atomically. */
  async createCompanyCustomer(
    organizationId: string,
    input: CreateCompanyCustomerDto,
  ): Promise<CompanyCustomer> {
    assertCustomerSubtypeIntegrity({
      type: 'COMPANY',
      hasIndividual: false,
      hasCompany: Boolean(input.company),
    });

    return this.db.transaction(async (tx) => {
      const [customer] = await tx
        .insert(customers)
        .values(this.headerValues(organizationId, 'COMPANY', input))
        .returning();
      const [company] = await tx
        .insert(customerCompanies)
        .values({
          customerId: customer.id,
          legalName: input.company.legalName,
          businessName: input.company.businessName ?? null,
          taxNumber: input.company.taxNumber ?? null,
        })
        .returning();
      return { customer, company };
    });
  }

  /** Fetches one customer scoped to the caller's organization. */
  async findCustomerInOrganization(
    scope: OrganizationScope,
    customerId: string,
  ): Promise<Customer | null> {
    const [row] = await this.db
      .select()
      .from(customers)
      .where(
        and(
          eq(customers.organizationId, scope.organizationId),
          eq(customers.id, customerId),
        ),
      )
      .limit(1);
    return row ?? null;
  }

  /** Lists the customers owned by the caller's organization. */
  async listCustomersInOrganization(scope: OrganizationScope): Promise<Customer[]> {
    return this.db
      .select()
      .from(customers)
      .where(eq(customers.organizationId, scope.organizationId))
      .orderBy(desc(customers.createdAt));
  }

  /** Adds a contact to a customer the caller's organization owns. */
  async addContact(
    scope: OrganizationScope,
    customerId: string,
    input: CreateCustomerContactDto,
  ): Promise<CustomerContact> {
    await this.requireCustomerInScope(scope, customerId);

    const [contact] = await this.db
      .insert(customerContacts)
      .values({
        customerId,
        firstName: input.firstName,
        lastName: input.lastName,
        email: input.email ?? null,
        phone: input.phone ?? null,
        role: input.role ?? null,
        isPrimary: input.isPrimary,
        isBillingContact: input.isBillingContact,
        isJobContact: input.isJobContact,
      })
      .returning();
    return contact;
  }

  /** Lists a customer's contacts, scoped to the caller's organization. */
  async listContacts(
    scope: OrganizationScope,
    customerId: string,
  ): Promise<CustomerContact[]> {
    await this.requireCustomerInScope(scope, customerId);

    return this.db
      .select(getTableColumns(customerContacts))
      .from(customerContacts)
      .innerJoin(customers, eq(customers.id, customerContacts.customerId))
      .where(
        and(
          eq(customerContacts.customerId, customerId),
          eq(customers.organizationId, scope.organizationId),
        ),
      )
      .orderBy(desc(customerContacts.createdAt));
  }

  /** Adds an address to a customer the caller's organization owns. */
  async addAddress(
    scope: OrganizationScope,
    customerId: string,
    input: CreateCustomerAddressDto,
  ): Promise<CustomerAddress> {
    await this.requireCustomerInScope(scope, customerId);

    const [address] = await this.db
      .insert(customerAddresses)
      .values({
        customerId,
        type: input.type,
        addressLine1: input.addressLine1,
        addressLine2: input.addressLine2 ?? null,
        city: input.city,
        province: input.province,
        postalCode: input.postalCode,
        country: input.country,
        isDefault: input.isDefault,
      })
      .returning();
    return address;
  }

  /** Lists a customer's addresses, scoped to the caller's organization. */
  async listAddresses(
    scope: OrganizationScope,
    customerId: string,
  ): Promise<CustomerAddress[]> {
    await this.requireCustomerInScope(scope, customerId);

    return this.db
      .select(getTableColumns(customerAddresses))
      .from(customerAddresses)
      .innerJoin(customers, eq(customers.id, customerAddresses.customerId))
      .where(
        and(
          eq(customerAddresses.customerId, customerId),
          eq(customers.organizationId, scope.organizationId),
        ),
      )
      .orderBy(desc(customerAddresses.createdAt));
  }

  /** Resolves a customer within the tenant boundary or fails closed. */
  private async requireCustomerInScope(
    scope: OrganizationScope,
    customerId: string,
  ): Promise<Customer> {
    const customer = await this.findCustomerInOrganization(scope, customerId);
    if (customer === null) {
      throw new CustomerNotFoundError(customerId);
    }
    return customer;
  }

  private headerValues(
    organizationId: string,
    type: CustomerType,
    input: CreateIndividualCustomerDto | CreateCompanyCustomerDto,
  ) {
    return {
      organizationId,
      type,
      displayName: input.displayName,
      email: input.email ?? null,
      phone: input.phone ?? null,
      billingEmail: input.billingEmail ?? null,
      billingPhone: input.billingPhone ?? null,
      notes: input.notes ?? null,
    };
  }
}

