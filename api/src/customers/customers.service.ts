import { Injectable } from '@nestjs/common';
import {
  and,
  asc,
  desc,
  eq,
  exists,
  getTableColumns,
  inArray,
  notExists,
  sql,
} from 'drizzle-orm';
import type { SQL } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  customerAddresses,
  customerCompanies,
  customerContacts,
  customerIndividuals,
  customerLifecycleHistory,
  customers,
  jobs,
  properties,
  propertyCustomerRelationships,
  visits,
} from '../database/schema.js';
import { DomainValidationError } from '../validation/domain-validation.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import {
  readAssignedTechnicians,
  selectVisitsForJobs,
} from '../jobs/visit-assignment.js';
import {
  DEFAULT_CUSTOMER_LIST_FILTERS,
  type CustomerJobFilter,
  type CustomerListFilters,
} from './customer-list-filter.dto.js';
import type { CreateCustomerAddressDto } from './customer-address.dto.js';
import type { CreateCustomerContactDto } from './customer-contact.dto.js';
import {
  DEFAULT_PROPERTY_LIST_FILTERS,
  PROPERTY_COUNTRY,
  type CreatePropertyDto,
  type PropertyListFilters,
} from './property.dto.js';
import type {
  CustomerDetail,
  CustomerJobSummary,
  CustomerPropertySummary,
} from './customer-detail.dto.js';
import type {
  CreateCompanyCustomerDto,
  CreateIndividualCustomerDto,
  UpdateCustomerDto,
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
    super(
      `Customer ${customerId} was not found in the requested organization.`,
    );
    this.name = 'CustomerNotFoundError';
  }
}

/**
 * The Job statuses that count as an *open Job* for the customer list filter.
 *
 * This is the Job lifecycle's non-terminal set (`BR-058`): every status except `COMPLETED` and
 * `CANCELED`. It defines a derived filter condition and is not a new Job status (`BR-042`).
 */
const OPEN_JOB_STATUSES = [
  'NEW',
  'SCHEDULED',
  'IN_PROGRESS',
  'PENDING_REVIEW',
] as const;

/**
 * The Property lifecycle state the customer-detail Property projection shows (`BR-081`, `BR-082`).
 *
 * Archiving is how a Property leaves active use, so the default projection over the customer's
 * current relationships shows `ACTIVE` Properties only and `propertyCount` counts the same set, so
 * the header and the list agree. An archived Property stays a retrievable business record; it is
 * reached through the explicit archived/history views rather than the default projection.
 */
const ACTIVE_PROPERTY_STATUS = 'ACTIVE';

/** A customer plus the derived counts the list renders. */
export interface CustomerWithCounts {
  readonly customer: Customer;
  readonly propertyCount: number;
  readonly jobCount: number;
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
    options: { includeDeleted?: boolean } = {},
  ): Promise<Customer | null> {
    const predicates = [
      eq(customers.organizationId, scope.organizationId),
      eq(customers.id, customerId),
    ];
    if (options.includeDeleted !== true) {
      predicates.push(sql`${customers.deletedAt} is null`);
    }

    const [row] = await this.db
      .select()
      .from(customers)
      .where(and(...predicates))
      .limit(1);
    return row ?? null;
  }

  /** Fetches one customer with its required subtype scoped to the caller's organization. */
  async findCustomerDetailsInOrganization(
    scope: OrganizationScope,
    customerId: string,
  ): Promise<IndividualCustomer | CompanyCustomer | null> {
    const customer = await this.findCustomerInOrganization(scope, customerId);
    if (customer === null) {
      return null;
    }

    if (customer.type === 'INDIVIDUAL') {
      const [individual] = await this.db
        .select()
        .from(customerIndividuals)
        .where(eq(customerIndividuals.customerId, customer.id))
        .limit(1);
      if (individual === undefined) {
        return null;
      }
      return { customer, individual };
    }

    const [company] = await this.db
      .select()
      .from(customerCompanies)
      .where(eq(customerCompanies.customerId, customer.id))
      .limit(1);
    if (company === undefined) {
      return null;
    }
    return { customer, company };
  }

  /**
   * Lists the customers owned by the caller's organization with the counts the list renders.
   *
   * `propertyCount` counts the customer's active Property relationships (`BR-050`); an ended
   * relationship stays in history but is no longer one of the customer's properties. `jobCount`
   * counts the Jobs that belong to the customer, whatever their status (`BR-048`). Both are
   * derived, never stored on the customer.
   *
   * [options.filters] narrows the list **in the database** rather than on the client, so the
   * backend stays the authority for which rows a caller receives (`BR-001`, `BR-007`). `ALL`
   * applies no constraint on a dimension.
   */
  async listCustomersInOrganization(
    scope: OrganizationScope,
    options: {
      includeDeleted?: boolean;
      filters?: CustomerListFilters;
    } = {},
  ): Promise<CustomerWithCounts[]> {
    const filters = options.filters ?? DEFAULT_CUSTOMER_LIST_FILTERS;
    const predicates = [eq(customers.organizationId, scope.organizationId)];
    if (options.includeDeleted !== true) {
      predicates.push(sql`${customers.deletedAt} is null`);
    }
    if (filters.status !== 'ALL') {
      predicates.push(eq(customers.status, filters.status));
    }
    const jobsPredicate = this.jobsFilterPredicate(scope, filters.jobs);
    if (jobsPredicate !== undefined) {
      predicates.push(jobsPredicate);
    }

    return this.db
      .select({
        customer: getTableColumns(customers),
        // `distinct` stops the two left joins from multiplying each other's rows.
        propertyCount: sql<number>`count(distinct ${properties.id})`.mapWith(
          Number,
        ),
        jobCount: sql<number>`count(distinct ${jobs.id})`.mapWith(Number),
      })
      .from(customers)
      .leftJoin(
        propertyCustomerRelationships,
        and(
          eq(
            propertyCustomerRelationships.organizationId,
            scope.organizationId,
          ),
          eq(propertyCustomerRelationships.customerId, customers.id),
          sql`${propertyCustomerRelationships.endedAt} is null`,
        ),
      )
      .leftJoin(
        properties,
        and(
          eq(properties.organizationId, scope.organizationId),
          eq(properties.id, propertyCustomerRelationships.propertyId),
          eq(properties.status, ACTIVE_PROPERTY_STATUS),
        ),
      )
      .leftJoin(
        jobs,
        and(
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.customerId, customers.id),
        ),
      )
      .where(and(...predicates))
      .groupBy(customers.id)
      .orderBy(desc(customers.createdAt));
  }

  /**
   * The predicate a job dimension adds to the customer list (`GET /customers?jobs=`).
   *
   * The conditions are derived, never stored (`BR-080`): an *open Job* is a Job in the Job
   * lifecycle's non-terminal states (`BR-058`), and an *overdue Visit* is a Visit still
   * `SCHEDULED` (`BR-074`) whose scheduled end has passed. A Visit that has progressed
   * (`EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`) is field work in progress, not overdue, and a terminal
   * Visit is history.
   *
   * Each condition is a semi-join (`EXISTS` / `NOT EXISTS`) so it filters customers without
   * multiplying the grouped count rows the list also selects.
   */
  private jobsFilterPredicate(
    scope: OrganizationScope,
    filter: CustomerJobFilter,
  ): SQL | undefined {
    switch (filter) {
      case 'ALL':
        return undefined;
      case 'NO_JOBS':
        return notExists(this.customerJobs(scope));
      case 'HAS_OPEN_JOBS':
        return exists(this.customerOpenJobs(scope));
      case 'NO_OPEN_JOBS':
        return notExists(this.customerOpenJobs(scope));
      case 'HAS_OVERDUE_VISITS':
        return exists(this.customerOverdueVisits(scope));
    }
  }

  /** Matches the customer's Jobs, whatever their status (`BR-048`). */
  private customerJobs(scope: OrganizationScope) {
    return this.db
      .select({ present: sql`1` })
      .from(jobs)
      .where(this.customerJobsCondition(scope));
  }

  /** Matches the customer's Jobs that are not terminal (`BR-058`). */
  private customerOpenJobs(scope: OrganizationScope) {
    return this.db
      .select({ present: sql`1` })
      .from(jobs)
      .where(
        and(
          this.customerJobsCondition(scope),
          inArray(jobs.status, [...OPEN_JOB_STATUSES]),
        ),
      );
  }

  /** Matches the customer's Visits that are still `SCHEDULED` past their scheduled end. */
  private customerOverdueVisits(scope: OrganizationScope) {
    return this.db
      .select({ present: sql`1` })
      .from(jobs)
      .innerJoin(
        visits,
        and(
          eq(visits.organizationId, scope.organizationId),
          eq(visits.jobId, jobs.id),
        ),
      )
      .where(
        and(
          this.customerJobsCondition(scope),
          eq(visits.status, 'SCHEDULED'),
          sql`${visits.scheduledEnd} < now()`,
        ),
      );
  }

  /** Scopes a Job subquery to the caller's organization and one customer of the outer query. */
  private customerJobsCondition(scope: OrganizationScope) {
    return and(
      eq(jobs.organizationId, scope.organizationId),
      eq(jobs.customerId, customers.id),
    );
  }

  /**
   * Updates a customer's own data, converting it between individual and company when the edit
   * states the other type (`BR-087`).
   *
   * A conversion changes `customers.type`, removes the previous subtype record and writes the new
   * matching one in the same transaction, so the customer's type and its subtype record can never
   * disagree; the conversion is recorded in append-only lifecycle history. No value is carried
   * between the two subtype concepts — the edit supplies the new type's fields.
   */
  async updateCustomer(
    scope: OrganizationScope & { readonly membershipId: string },
    customerId: string,
    input: UpdateCustomerDto,
  ): Promise<IndividualCustomer | CompanyCustomer> {
    const existing = await this.requireCustomerInScope(scope, customerId);
    const convertedType =
      input.type !== undefined && input.type !== existing.type
        ? input.type
        : null;

    await this.db.transaction(async (tx) => {
      const header = {
        ...(input.type === undefined ? {} : { type: input.type }),
        ...(input.displayName === undefined
          ? {}
          : { displayName: input.displayName }),
        ...(input.email === undefined ? {} : { email: input.email }),
        ...(input.phone === undefined ? {} : { phone: input.phone }),
        ...(input.billingEmail === undefined
          ? {}
          : { billingEmail: input.billingEmail }),
        ...(input.billingPhone === undefined
          ? {}
          : { billingPhone: input.billingPhone }),
        ...(input.notes === undefined ? {} : { notes: input.notes }),
        ...(input.preferredContactMethod === undefined
          ? {}
          : { preferredContactMethod: input.preferredContactMethod }),
        ...(input.language === undefined ? {} : { language: input.language }),
        ...(input.status === undefined ? {} : { status: input.status }),
      };

      if (Object.keys(header).length > 0) {
        await tx
          .update(customers)
          .set(header)
          .where(
            and(
              eq(customers.organizationId, scope.organizationId),
              eq(customers.id, customerId),
            ),
          );
      }

      if (convertedType !== null) {
        // The previous subtype record is the other table's row, so it is removed here and the new
        // type's record is written in its place — in this transaction, never in a second one.
        if (convertedType === 'INDIVIDUAL') {
          const individual = input.individual;
          if (
            individual?.firstName === undefined ||
            individual.lastName === undefined
          ) {
            throw new DomainValidationError([
              'individual.firstName and individual.lastName are required to convert to an individual',
            ]);
          }
          await tx
            .delete(customerCompanies)
            .where(eq(customerCompanies.customerId, customerId));
          await tx.insert(customerIndividuals).values({
            customerId,
            firstName: individual.firstName,
            lastName: individual.lastName,
            dateOfBirth: individual.dateOfBirth ?? null,
          });
        } else {
          const company = input.company;
          if (company?.legalName === undefined) {
            throw new DomainValidationError([
              'company.legalName is required to convert to a company',
            ]);
          }
          await tx
            .delete(customerIndividuals)
            .where(eq(customerIndividuals.customerId, customerId));
          await tx.insert(customerCompanies).values({
            customerId,
            legalName: company.legalName,
            businessName: company.businessName ?? null,
            taxNumber: company.taxNumber ?? null,
          });
        }

        await tx.insert(customerLifecycleHistory).values({
          organizationId: scope.organizationId,
          customerId,
          action: 'TYPE_CONVERTED',
          fromType: existing.type,
          toType: convertedType,
          actorMembershipId: scope.membershipId,
        });
        return;
      }

      if (input.individual !== undefined) {
        if (existing.type !== 'INDIVIDUAL') {
          throw new DomainValidationError([
            'individual must not be supplied for a COMPANY customer',
          ]);
        }
        const individual = {
          ...(input.individual.firstName === undefined
            ? {}
            : { firstName: input.individual.firstName }),
          ...(input.individual.lastName === undefined
            ? {}
            : { lastName: input.individual.lastName }),
          ...(input.individual.dateOfBirth === undefined
            ? {}
            : { dateOfBirth: input.individual.dateOfBirth }),
        };
        if (Object.keys(individual).length > 0) {
          await tx
            .update(customerIndividuals)
            .set(individual)
            .where(eq(customerIndividuals.customerId, customerId));
        }
      }

      if (input.company !== undefined) {
        if (existing.type !== 'COMPANY') {
          throw new DomainValidationError([
            'company must not be supplied for an INDIVIDUAL customer',
          ]);
        }
        const company = {
          ...(input.company.legalName === undefined
            ? {}
            : { legalName: input.company.legalName }),
          ...(input.company.businessName === undefined
            ? {}
            : { businessName: input.company.businessName }),
          ...(input.company.taxNumber === undefined
            ? {}
            : { taxNumber: input.company.taxNumber }),
        };
        if (Object.keys(company).length > 0) {
          await tx
            .update(customerCompanies)
            .set(company)
            .where(eq(customerCompanies.customerId, customerId));
        }
      }
    });

    const updated = await this.findCustomerDetailsInOrganization(
      scope,
      customerId,
    );
    if (updated === null) {
      throw new CustomerNotFoundError(customerId);
    }
    return updated;
  }

  /** Archives a customer by soft deletion, preserving historical references. */
  async archiveCustomer(
    scope: OrganizationScope & { membershipId: string },
    customerId: string,
    input: { reason?: string | null },
    now: Date = new Date(),
  ): Promise<Customer> {
    await this.requireCustomerInScope(scope, customerId);

    const [archived] = await this.db
      .update(customers)
      .set({
        status: 'INACTIVE',
        deletedAt: now,
        deletedByMembershipId: scope.membershipId,
        deleteReason: input.reason ?? null,
      })
      .where(
        and(
          eq(customers.organizationId, scope.organizationId),
          eq(customers.id, customerId),
        ),
      )
      .returning();
    if (archived === undefined) {
      throw new CustomerNotFoundError(customerId);
    }
    return archived;
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
    return this.selectContacts(scope, customerId);
  }

  /**
   * Reads a customer's contacts for a caller that has already resolved the customer in scope.
   *
   * Kept separate so composing the customer detail does not repeat the customer lookup:
   * `findCustomerDetailInOrganization` has already verified the customer exists in scope.
   */
  private async selectContacts(
    scope: OrganizationScope,
    customerId: string,
  ): Promise<CustomerContact[]> {
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

  /**
   * Fetches the customer detail the detail view renders: the header with the derived counts its
   * sections show, its required subtype record and its contacts (`BR-023`, `BR-081`).
   *
   * The counts are derived from the authoritative tables, never stored on the customer (`BR-080`).
   */
  async findCustomerDetailInOrganization(
    scope: OrganizationScope,
    customerId: string,
  ): Promise<CustomerDetail | null> {
    const details = await this.findCustomerDetailsInOrganization(
      scope,
      customerId,
    );
    if (details === null) {
      return null;
    }
    const [counts, contacts] = await Promise.all([
      this.customerCounts(scope, customerId),
      this.selectContacts(scope, customerId),
    ]);
    return {
      customer: details.customer,
      individual: 'individual' in details ? details.individual : null,
      company: 'company' in details ? details.company : null,
      propertyCount: counts.propertyCount,
      jobCount: counts.jobCount,
      contacts,
    };
  }

  /** The derived Property and Job counts the customer detail sections render (`BR-081`). */
  private async customerCounts(
    scope: OrganizationScope,
    customerId: string,
  ): Promise<{ propertyCount: number; jobCount: number }> {
    const [row] = await this.db
      .select({
        propertyCount: sql<number>`count(distinct ${properties.id})`.mapWith(
          Number,
        ),
        jobCount: sql<number>`count(distinct ${jobs.id})`.mapWith(Number),
      })
      .from(customers)
      .leftJoin(
        propertyCustomerRelationships,
        and(
          eq(
            propertyCustomerRelationships.organizationId,
            scope.organizationId,
          ),
          eq(propertyCustomerRelationships.customerId, customers.id),
          sql`${propertyCustomerRelationships.endedAt} is null`,
        ),
      )
      .leftJoin(
        properties,
        and(
          eq(properties.organizationId, scope.organizationId),
          eq(properties.id, propertyCustomerRelationships.propertyId),
          eq(properties.status, ACTIVE_PROPERTY_STATUS),
        ),
      )
      .leftJoin(
        jobs,
        and(
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.customerId, customers.id),
        ),
      )
      .where(
        and(
          eq(customers.organizationId, scope.organizationId),
          eq(customers.id, customerId),
        ),
      );
    return {
      propertyCount: row?.propertyCount ?? 0,
      jobCount: row?.jobCount ?? 0,
    };
  }

  /**
   * Lists the Properties the customer is related to, each with the values its row renders
   * (`BR-050`, `BR-081`, `BR-082`).
   *
   * `jobCount` counts every Job the Property is associated with, whatever its status.
   * `lastServiceAt` is the scheduled start of the most recent `COMPLETED` Visit across those Jobs,
   * or `null` when there is none. Both are derived; neither is stored (`BR-081`).
   *
   * The default filter is `ACTIVE`, because archiving is how a Property leaves active use
   * (`BR-083`); an archived Property is still one of the customer's relationships and is requested
   * explicitly with `status=ARCHIVED` or `status=ALL`.
   */
  async findCustomerPropertiesInOrganization(
    scope: OrganizationScope,
    customerId: string,
    filters: PropertyListFilters = DEFAULT_PROPERTY_LIST_FILTERS,
  ): Promise<CustomerPropertySummary[]> {
    await this.requireCustomerInScope(scope, customerId);

    const rows = await this.db
      .select({
        property: getTableColumns(properties),
        jobCount: sql<number>`count(distinct ${jobs.id})`.mapWith(Number),
        lastServiceAt: sql<Date | string | null>`max(${visits.scheduledStart})`,
      })
      .from(propertyCustomerRelationships)
      .innerJoin(
        properties,
        and(
          eq(properties.organizationId, scope.organizationId),
          eq(properties.id, propertyCustomerRelationships.propertyId),
        ),
      )
      .leftJoin(
        jobs,
        and(
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.propertyId, properties.id),
        ),
      )
      .leftJoin(
        visits,
        and(
          eq(visits.organizationId, scope.organizationId),
          eq(visits.jobId, jobs.id),
          eq(visits.status, 'COMPLETED'),
        ),
      )
      .where(
        and(
          eq(
            propertyCustomerRelationships.organizationId,
            scope.organizationId,
          ),
          eq(propertyCustomerRelationships.customerId, customerId),
          sql`${propertyCustomerRelationships.endedAt} is null`,
          filters.status === 'ALL'
            ? undefined
            : eq(properties.status, filters.status),
        ),
      )
      .groupBy(properties.id)
      .orderBy(asc(properties.name), asc(properties.addressLine1));

    return rows.map((row) => ({
      property: row.property,
      jobCount: row.jobCount,
      lastServiceAt: asDate(row.lastServiceAt),
    }));
  }

  /**
   * Creates an organization-owned Property and relates it to one of the organization's customers
   * (`BR-049`, `BR-050`).
   *
   * The Property is its own entity and is **not** stored on the customer: the customer association
   * is the active row in `property_customer_relationships`, whose actor is the calling membership
   * (`BR-049`, `BR-050`, `docs/domain/job-visit-domain-model.md` §3.2). Creating the Property and
   * establishing that relationship are one atomic operation, so a Property is never left with no
   * owner.
   *
   * A brand-new Property has no Jobs and no completed Visit, so its row projection is zero/absent
   * (`BR-081`); it is not read back and re-derived because that could not differ.
   */
  async createPropertyForCustomer(
    scope: OrganizationScope & { membershipId: string },
    customerId: string,
    input: CreatePropertyDto,
  ): Promise<CustomerPropertySummary> {
    return this.db.transaction(async (tx) => {
      const [customer] = await tx
        .select({ id: customers.id })
        .from(customers)
        .where(
          and(
            eq(customers.organizationId, scope.organizationId),
            eq(customers.id, customerId),
            sql`${customers.deletedAt} is null`,
          ),
        )
        .limit(1);
      if (customer === undefined) {
        throw new CustomerNotFoundError(customerId);
      }

      const [property] = await tx
        .insert(properties)
        .values({
          organizationId: scope.organizationId,
          name: input.name ?? null,
          addressLine1: input.addressLine1,
          addressLine2: input.addressLine2 ?? null,
          city: input.city,
          province: input.province,
          postalCode: input.postalCode,
          country: PROPERTY_COUNTRY,
          notes: input.notes ?? null,
        })
        .returning();

      await tx.insert(propertyCustomerRelationships).values({
        organizationId: scope.organizationId,
        propertyId: property.id,
        customerId,
        actorMembershipId: scope.membershipId,
      });

      return { property, jobCount: 0, lastServiceAt: null };
    });
  }

  /**
   * Lists the customer's Jobs, most recently created first, each with its selected Visit's schedule
   * and technicians (`BR-048`, `BR-081`).
   *
   * The selected Visit and its crew are resolved by `jobs/visit-assignment.ts`, which the Job Details
   * read shares: the customer's Job row and the Job's own screen must never disagree about which Visit
   * represents a Job or who is assigned to it (`BR-041`).
   */
  async findCustomerJobsInOrganization(
    scope: OrganizationScope,
    customerId: string,
  ): Promise<CustomerJobSummary[]> {
    await this.requireCustomerInScope(scope, customerId);

    const rows = await this.db
      .select(getTableColumns(jobs))
      .from(jobs)
      .where(
        and(
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.customerId, customerId),
        ),
      )
      .orderBy(desc(jobs.jobNumber));
    if (rows.length === 0) {
      return [];
    }

    const selectedVisits = await selectVisitsForJobs(
      this.db,
      scope,
      rows.map((job) => job.id),
    );
    const technicians = await readAssignedTechnicians(
      this.db,
      scope,
      [...selectedVisits.values()].map((visit) => visit.visitId),
    );

    return rows.map((job) => {
      const selected = selectedVisits.get(job.id);
      return {
        job,
        selectedVisit:
          selected === undefined
            ? null
            : { scheduledStart: selected.scheduledStart },
        technicians:
          selected === undefined
            ? []
            : (technicians.get(selected.visitId) ?? []),
      };
    });
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
      preferredContactMethod: input.preferredContactMethod ?? 'NONE',
      language: input.language ?? 'en-CA',
    };
  }
}

/**
 * Normalises a date the driver returned for an aggregate expression.
 *
 * PostgreSQL hands a column value back as a `Date`, but an expression such as `max()` can arrive as
 * text depending on how the driver types it. Both shapes describe the same instant, so both are
 * accepted here rather than trusted to be one.
 */
export function asDate(value: Date | string | null): Date | null {
  if (value === null) {
    return null;
  }
  return value instanceof Date ? value : new Date(value);
}
