import { Test } from '@nestjs/testing';
import { and, eq } from 'drizzle-orm';
import { CustomersModule } from '../src/customers/customers.module.js';
import {
  CustomerNotFoundError,
  CustomersService,
} from '../src/customers/customers.service.js';
import type { OrganizationScope } from '../src/tenancy/tenant-scope.js';
import { DatabaseModule } from '../src/database/database.module.js';
import {
  jobs,
  organizationMembers,
  properties,
  propertyCustomerRelationships,
  userProfiles,
  visits,
  visitTechnicians,
} from '../src/database/schema.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

/**
 * The customer detail projections (`BR-081`) against a real PostgreSQL.
 *
 * The row values are derived, so these tests assert the derivation itself: which Property values
 * are counted, which single Visit a Job row selects, and which technicians that Visit contributes.
 */
describe('customer detail projections (e2e)', () => {
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

  /** A member with a profile, so the projection can resolve a technician name. */
  async function newNamedMembership(
    organizationId: string,
    firstName: string,
    lastName: string,
  ) {
    const member = await newMembership(organizationId);
    await database.db.insert(userProfiles).values({
      userId: member.userId,
      firstName,
      lastName,
      displayName: `${firstName} ${lastName}`,
    });
    return member;
  }

  async function newCustomer(organizationId: string, displayName: string) {
    const created = await service.createCompanyCustomer(organizationId, {
      type: 'COMPANY',
      displayName,
      company: { legalName: `${displayName} Ltd.` },
    });
    return created.customer;
  }

  async function newProperty(
    organizationId: string,
    addressLine1: string,
    name?: string,
  ) {
    const [property] = await database.db
      .insert(properties)
      .values({
        organizationId,
        name: name ?? null,
        addressLine1,
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .returning();
    return property;
  }

  async function linkProperty(
    organizationId: string,
    propertyId: string,
    customerId: string,
    actorMembershipId: string,
  ) {
    await database.db.insert(propertyCustomerRelationships).values({
      organizationId,
      propertyId,
      customerId,
      actorMembershipId,
    });
  }

  /** A Job, with the address snapshot the schema requires whenever a Property is set. */
  async function newJob(
    organizationId: string,
    customerId: string,
    jobNumber: number,
    propertyId?: string,
  ) {
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId,
        jobNumber,
        customerId,
        title: `Job ${jobNumber}`,
        ...(propertyId === undefined
          ? {}
          : {
              propertyId,
              propertyAddressSnapshot: { addressLine1: '1 Main Street' },
            }),
      })
      .returning();
    return job;
  }

  /** A Visit with an explicit status and schedule, satisfying the table's outcome constraints. */
  async function newVisit(values: {
    organizationId: string;
    jobId: string;
    status: string;
    scheduledStart: Date;
    scheduledEnd: Date;
    propertyId?: string;
    actorMembershipId?: string;
  }) {
    const [visit] = await database.db
      .insert(visits)
      .values({
        organizationId: values.organizationId,
        jobId: values.jobId,
        propertyId: values.propertyId ?? null,
        locationAddressSnapshot:
          values.propertyId === undefined
            ? null
            : { addressLine1: '1 Main Street' },
        status: values.status,
        scheduledStart: values.scheduledStart,
        scheduledEnd: values.scheduledEnd,
        ...(values.status === 'COMPLETED'
          ? {
              outcomeCode: 'RESOLVED',
              outcomeSummary: 'Work completed.',
              outcomeRecordedAt: values.scheduledStart,
              outcomeRecordedByMembershipId: values.actorMembershipId ?? null,
            }
          : {}),
      })
      .returning();
    return visit;
  }

  const scopeOf = (organizationId: string): OrganizationScope => ({
    organizationId,
  });

  it("derives each Property row's job count and last service date", async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'Property Owner Co');

    const alpha = await newProperty(organization.id, '1 Main Street', 'Alpha');
    const beta = await newProperty(organization.id, '2 Main Street');
    const ended = await newProperty(organization.id, '3 Main Street');

    await linkProperty(organization.id, alpha.id, customer.id, member.id);
    await linkProperty(organization.id, beta.id, customer.id, member.id);
    // An ended relationship is history, so it is not one of the customer's Properties (`BR-050`).
    await database.db.insert(propertyCustomerRelationships).values({
      organizationId: organization.id,
      propertyId: ended.id,
      customerId: customer.id,
      actorMembershipId: member.id,
      startedAt: new Date(Date.now() - 86_400_000),
      endedAt: new Date(),
    });

    const now = Date.now();
    const olderService = new Date(now - 86_400_000);
    const newerService = new Date(now - 3_600_000);

    const firstJob = await newJob(organization.id, customer.id, 1, alpha.id);
    const secondJob = await newJob(organization.id, customer.id, 2, alpha.id);
    await newVisit({
      organizationId: organization.id,
      jobId: firstJob.id,
      propertyId: alpha.id,
      status: 'COMPLETED',
      scheduledStart: olderService,
      scheduledEnd: new Date(olderService.getTime() + 3_600_000),
      actorMembershipId: member.id,
    });
    await newVisit({
      organizationId: organization.id,
      jobId: secondJob.id,
      propertyId: alpha.id,
      status: 'COMPLETED',
      scheduledStart: newerService,
      scheduledEnd: new Date(newerService.getTime() + 3_600_000),
      actorMembershipId: member.id,
    });
    // Beta has a Job but no completed Visit, so it has never been serviced.
    await newJob(organization.id, customer.id, 3, beta.id);

    const rows = await service.findCustomerPropertiesInOrganization(
      scopeOf(organization.id),
      customer.id,
    );

    // A named Property sorts before an unnamed one.
    expect(rows.map((row) => row.property.id)).toEqual([alpha.id, beta.id]);
    expect(rows[0]?.jobCount).toBe(2);
    expect(rows[0]?.lastServiceAt?.toISOString()).toBe(
      newerService.toISOString(),
    );
    expect(rows[1]?.jobCount).toBe(1);
    expect(rows[1]?.lastServiceAt).toBeNull();
  });

  it("selects the Job's earliest upcoming non-canceled Visit and its technicians", async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const lead = await newNamedMembership(organization.id, 'Mike', 'Lead');
    const helper = await newNamedMembership(organization.id, 'Sarah', 'Tech');
    const customer = await newCustomer(organization.id, 'Upcoming Co');
    const property = await newProperty(organization.id, '1 Main Street');
    await linkProperty(organization.id, property.id, customer.id, member.id);
    const job = await newJob(organization.id, customer.id, 10, property.id);

    const now = Date.now();
    // An earlier upcoming Visit that was canceled is not eligible.
    await newVisit({
      organizationId: organization.id,
      jobId: job.id,
      propertyId: property.id,
      status: 'CANCELED',
      scheduledStart: new Date(now + 3_600_000),
      scheduledEnd: new Date(now + 7_200_000),
    });
    const selectedStart = new Date(now + 7_200_000);
    const selected = await newVisit({
      organizationId: organization.id,
      jobId: job.id,
      propertyId: property.id,
      status: 'SCHEDULED',
      scheduledStart: selectedStart,
      scheduledEnd: new Date(now + 10_800_000),
    });
    await newVisit({
      organizationId: organization.id,
      jobId: job.id,
      propertyId: property.id,
      status: 'SCHEDULED',
      scheduledStart: new Date(now + 14_400_000),
      scheduledEnd: new Date(now + 18_000_000),
    });
    await database.db.insert(visitTechnicians).values([
      {
        organizationId: organization.id,
        visitId: selected.id,
        technicianMembershipId: helper.id,
        roleCode: 'TECHNICIAN',
      },
      {
        organizationId: organization.id,
        visitId: selected.id,
        technicianMembershipId: lead.id,
        roleCode: 'LEAD',
      },
    ]);

    const jobsForCustomer = await service.findCustomerJobsInOrganization(
      scopeOf(organization.id),
      customer.id,
    );
    const summary = jobsForCustomer[0];

    expect(summary?.job.id).toBe(job.id);
    expect(summary?.selectedVisit?.scheduledStart.toISOString()).toBe(
      selectedStart.toISOString(),
    );
    expect(
      summary?.technicians.map((technician) => technician.roleCode),
    ).toEqual(['LEAD', 'TECHNICIAN']);
    expect(summary?.technicians.map((technician) => technician.name)).toEqual([
      'Mike Lead',
      'Sarah Tech',
    ]);
  });

  it('falls back to the most recent past Visit by scheduled start', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'Past Co');
    const property = await newProperty(organization.id, '1 Main Street');
    await linkProperty(organization.id, property.id, customer.id, member.id);
    const job = await newJob(organization.id, customer.id, 11, property.id);

    const now = Date.now();
    await newVisit({
      organizationId: organization.id,
      jobId: job.id,
      propertyId: property.id,
      status: 'COMPLETED',
      scheduledStart: new Date(now - 86_400_000),
      scheduledEnd: new Date(now - 82_800_000),
      actorMembershipId: member.id,
    });
    const recentPastStart = new Date(now - 3_600_000);
    await newVisit({
      organizationId: organization.id,
      jobId: job.id,
      propertyId: property.id,
      status: 'NO_SHOW',
      scheduledStart: recentPastStart,
      scheduledEnd: new Date(now - 1_800_000),
    });

    const summary = (
      await service.findCustomerJobsInOrganization(
        scopeOf(organization.id),
        customer.id,
      )
    )[0];

    // The most recent past Visit is the fallback, whatever its status (`BR-081`).
    expect(summary?.selectedVisit?.scheduledStart.toISOString()).toBe(
      recentPastStart.toISOString(),
    );
  });

  it('reports no date for Jobs with no eligible Visit and orders them newest first', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'No Visit Co');
    const property = await newProperty(organization.id, '1 Main Street');
    await linkProperty(organization.id, property.id, customer.id, member.id);

    const older = await newJob(organization.id, customer.id, 20, property.id);
    const newer = await newJob(organization.id, customer.id, 21);
    // A DRAFT Visit carries no schedule, so it can never be the selected Visit.
    await database.db.insert(visits).values({
      organizationId: organization.id,
      jobId: older.id,
      status: 'DRAFT',
    });

    const summaries = await service.findCustomerJobsInOrganization(
      scopeOf(organization.id),
      customer.id,
    );

    expect(summaries.map((summary) => summary.job.id)).toEqual([
      newer.id,
      older.id,
    ]);
    for (const summary of summaries) {
      expect(summary.selectedVisit).toBeNull();
      expect(summary.technicians).toEqual([]);
    }
  });

  it('composes the detail with derived counts, subtype and contacts', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'Detail Co');
    await service.addContact(
      scopeOf(organization.id),
      customer.id,
      {
        firstName: 'Owner',
        lastName: 'Detail',
        isPrimary: true,
        isBillingContact: false,
        isJobContact: false,
      },
    );
    const property = await newProperty(organization.id, '1 Main Street');
    await linkProperty(organization.id, property.id, customer.id, member.id);
    await newJob(organization.id, customer.id, 1, property.id);
    await newJob(organization.id, customer.id, 2, property.id);

    const detail = await service.findCustomerDetailInOrganization(
      scopeOf(organization.id),
      customer.id,
    );

    expect(detail?.customer.id).toBe(customer.id);
    expect(detail?.company?.legalName).toBe('Detail Co Ltd.');
    expect(detail?.individual).toBeNull();
    expect(detail?.propertyCount).toBe(1);
    expect(detail?.jobCount).toBe(2);
    expect(detail?.contacts).toHaveLength(1);
  });

  it("refuses to project another organization's customer", async () => {
    const organizationA = await newOrganization();
    const organizationB = await newOrganization();
    const customer = await newCustomer(organizationA.id, 'Private Co');
    const scopeB = scopeOf(organizationB.id);

    await expect(
      service.findCustomerPropertiesInOrganization(scopeB, customer.id),
    ).rejects.toThrow(CustomerNotFoundError);
    await expect(
      service.findCustomerJobsInOrganization(scopeB, customer.id),
    ).rejects.toThrow(CustomerNotFoundError);
    await expect(
      service.findCustomerDetailInOrganization(scopeB, customer.id),
    ).resolves.toBeNull();
  });

  it('creates an organization-owned Property and relates it to the customer', async () => {
    const organization = await newOrganization();
    const member = await newMembership(organization.id);
    const customer = await newCustomer(organization.id, 'New Property Co');

    const summary = await service.createPropertyForCustomer(
      { organizationId: organization.id, membershipId: member.id },
      customer.id,
      {
        name: 'Cedar Lane Building',
        addressLine1: '987 Cedar Lane',
        addressLine2: 'Suite 200',
        city: 'Montreal',
        province: 'QC',
        postalCode: 'H3A 2T6',
        notes: 'Mechanical room in basement B1.',
      },
    );

    // The Property is its own entity, inside the organization boundary and with the stored country
    // the address model already carries.
    expect(summary.property.organizationId).toBe(organization.id);
    expect(summary.property.addressLine2).toBe('Suite 200');
    expect(summary.property.province).toBe('QC');
    expect(summary.property.country).toBe('Canada');
    // A newly created Property has no Jobs and has never been serviced (`BR-081`).
    expect(summary.jobCount).toBe(0);
    expect(summary.lastServiceAt).toBeNull();

    // The customer association is the relationship row, not a column on the Property (`BR-049`).
    const relationships = await database.db
      .select()
      .from(propertyCustomerRelationships)
      .where(
        and(
          eq(propertyCustomerRelationships.organizationId, organization.id),
          eq(propertyCustomerRelationships.propertyId, summary.property.id),
        ),
      );
    expect(relationships).toHaveLength(1);
    expect(relationships[0]?.customerId).toBe(customer.id);
    expect(relationships[0]?.actorMembershipId).toBe(member.id);
    expect(relationships[0]?.endedAt).toBeNull();

    // It is then one of the customer's active Properties with its derived row values.
    const rows = await service.findCustomerPropertiesInOrganization(
      scopeOf(organization.id),
      customer.id,
    );
    expect(rows.map((row) => row.property.id)).toEqual([summary.property.id]);
  });

  it("refuses to create a Property for another organization's customer", async () => {
    const organizationA = await newOrganization();
    const organizationB = await newOrganization();
    const memberB = await newMembership(organizationB.id);
    const customer = await newCustomer(organizationA.id, 'Private Co');

    await expect(
      service.createPropertyForCustomer(
        { organizationId: organizationB.id, membershipId: memberB.id },
        customer.id,
        {
          addressLine1: '1 Main Street',
          city: 'Ottawa',
          province: 'ON',
          postalCode: 'K1A 0B1',
        },
      ),
    ).rejects.toThrow(CustomerNotFoundError);

    // The refused create wrote nothing at all, not even an orphan Property.
    const createdProperties = await database.db
      .select({ id: properties.id })
      .from(properties)
      .where(eq(properties.organizationId, organizationB.id));
    expect(createdProperties).toEqual([]);
  });
});
