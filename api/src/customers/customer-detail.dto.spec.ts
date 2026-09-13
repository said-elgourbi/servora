import {
  toCustomerDetailDto,
  toCustomerJobDto,
  toCustomerPropertyDto,
  type CustomerDetail,
} from './customer-detail.dto.js';
import type {
  Customer,
  CustomerCompany,
  CustomerContact,
  Job,
  Property,
} from './customer.types.js';

const CREATED = new Date('2026-01-01T00:00:00.000Z');
const CUSTOMER_ID = '00000000-0000-0000-0000-000000000010';
const ORGANIZATION_ID = '00000000-0000-0000-0000-000000000001';
const PROPERTY_ID = '00000000-0000-0000-0000-000000000030';

function customerRow(overrides: Partial<Customer> = {}): Customer {
  return {
    id: CUSTOMER_ID,
    organizationId: ORGANIZATION_ID,
    type: 'COMPANY',
    displayName: 'Acme',
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
    createdAt: CREATED,
    updatedAt: CREATED,
    ...overrides,
  };
}

function companyRow(): CustomerCompany {
  return {
    customerId: CUSTOMER_ID,
    legalName: 'Acme Ltd.',
    businessName: null,
    taxNumber: null,
  };
}

function jobRow(overrides: Partial<Job> = {}): Job {
  return {
    id: '00000000-0000-0000-0000-000000000020',
    organizationId: ORGANIZATION_ID,
    jobNumber: 1042,
    customerId: CUSTOMER_ID,
    propertyId: null,
    propertyAddressSnapshot: null,
    title: 'HVAC Maintenance',
    description: null,
    typeCode: null,
    status: 'SCHEDULED',
    ownerMembershipId: null,
    finalOutcomeCode: null,
    version: 1,
    createdAt: CREATED,
    updatedAt: CREATED,
    ...overrides,
  };
}

function propertyRow(): Property {
  return {
    id: PROPERTY_ID,
    organizationId: ORGANIZATION_ID,
    name: 'Cedar Lane Building',
    addressLine1: '987 Cedar Lane',
    addressLine2: null,
    city: 'Montreal',
    province: 'QC',
    postalCode: 'H3A 2T6',
    country: 'Canada',
    notes: null,
    status: 'ACTIVE',
    archivedAt: null,
    archivedByMembershipId: null,
    version: 1,
    createdAt: CREATED,
    updatedAt: CREATED,
  };
}

describe('customer detail projections', () => {
  it('maps the selected Visit onto the Job row', () => {
    const scheduledStart = new Date('2026-09-08T13:00:00.000Z');

    const dto = toCustomerJobDto({
      job: jobRow(),
      selectedVisit: { scheduledStart },
      technicians: [
        { membershipId: 'm-1', name: 'Mike Lead', roleCode: 'LEAD' },
      ],
    });

    expect(dto.scheduledStart).toBe(scheduledStart.toISOString());
    expect(dto.status).toBe('SCHEDULED');
    expect(dto.jobNumber).toBe(1042);
    expect(dto.technicians).toEqual([
      { membershipId: 'm-1', name: 'Mike Lead', roleCode: 'LEAD' },
    ]);
  });

  it('reports no date and no technicians when no Visit was selected', () => {
    const dto = toCustomerJobDto({
      job: jobRow(),
      selectedVisit: null,
      technicians: [],
    });

    expect(dto.scheduledStart).toBeNull();
    expect(dto.technicians).toEqual([]);
  });

  it("maps the Job's address snapshot", () => {
    const dto = toCustomerJobDto({
      job: jobRow({
        propertyId: PROPERTY_ID,
        propertyAddressSnapshot: {
          propertyName: 'Cedar Lane Building',
          addressLine1: '987 Cedar Lane',
          addressLine2: null,
          city: 'Montreal',
          province: 'QC',
          postalCode: 'H3A 2T6',
          country: 'Canada',
        },
      }),
      selectedVisit: null,
      technicians: [],
    });

    expect(dto.propertyAddress).toEqual({
      propertyName: 'Cedar Lane Building',
      addressLine1: '987 Cedar Lane',
      addressLine2: null,
      city: 'Montreal',
      province: 'QC',
      postalCode: 'H3A 2T6',
      country: 'Canada',
    });
  });

  it('reports no address for a snapshot it cannot read', () => {
    expect(
      toCustomerJobDto({
        job: jobRow({ propertyAddressSnapshot: 'not-an-object' }),
        selectedVisit: null,
        technicians: [],
      }).propertyAddress,
    ).toBeNull();

    // A snapshot with a non-string member must not be reported as a partly invented address.
    expect(
      toCustomerJobDto({
        job: jobRow({ propertyAddressSnapshot: { city: 42 } }),
        selectedVisit: null,
        technicians: [],
      }).propertyAddress,
    ).toEqual({
      propertyName: null,
      addressLine1: null,
      addressLine2: null,
      city: null,
      province: null,
      postalCode: null,
      country: null,
    });
  });

  it("maps a Property row's derived last service date", () => {
    const lastServiceAt = new Date('2026-08-28T13:00:00.000Z');

    const dto = toCustomerPropertyDto({
      property: propertyRow(),
      jobCount: 3,
      lastServiceAt,
    });

    expect(dto.id).toBe(PROPERTY_ID);
    expect(dto.jobCount).toBe(3);
    expect(dto.lastServiceAt).toBe(lastServiceAt.toISOString());

    expect(
      toCustomerPropertyDto({
        property: propertyRow(),
        jobCount: 0,
        lastServiceAt: null,
      }).lastServiceAt,
    ).toBeNull();
  });

  it('carries the derived counts, subtype and contacts onto the detail', () => {
    const contact: CustomerContact = {
      id: '00000000-0000-0000-0000-000000000040',
      customerId: CUSTOMER_ID,
      firstName: 'Owner',
      lastName: 'Person',
      email: null,
      phone: null,
      role: null,
      isPrimary: true,
      isBillingContact: false,
      isJobContact: false,
      createdAt: CREATED,
      updatedAt: CREATED,
    };
    const detail: CustomerDetail = {
      customer: customerRow(),
      individual: null,
      company: companyRow(),
      propertyCount: 2,
      jobCount: 5,
      contacts: [contact],
    };

    const dto = toCustomerDetailDto(detail);

    expect(dto.customer.propertyCount).toBe(2);
    expect(dto.customer.jobCount).toBe(5);
    expect(dto.company?.legalName).toBe('Acme Ltd.');
    expect(dto.individual).toBeNull();
    expect(dto.contacts).toHaveLength(1);
    expect(dto.contacts[0]?.firstName).toBe('Owner');
  });
});
