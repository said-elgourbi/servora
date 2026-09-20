import { toJobDetailsDto, type JobDetails } from './job-details.dto.js';
import type { CustomerContact } from '../customers/customer.types.js';
import type { Job, VisitStatus } from './job.types.js';

const CREATED = new Date('2026-01-01T00:00:00.000Z');
const ORGANIZATION_ID = '00000000-0000-0000-0000-000000000001';
const CUSTOMER_ID = '00000000-0000-0000-0000-000000000010';
const CONTACT_ID = '00000000-0000-0000-0000-000000000015';
const JOB_ID = '00000000-0000-0000-0000-000000000020';
const PROPERTY_ID = '00000000-0000-0000-0000-000000000030';

function jobRow(overrides: Partial<Job> = {}): Job {
  return {
    id: JOB_ID,
    organizationId: ORGANIZATION_ID,
    jobNumber: 1042,
    customerId: CUSTOMER_ID,
    propertyId: null,
    propertyAddressSnapshot: null,
    title: 'Furnace repair',
    description: null,
    typeCode: null,
    status: 'NEW',
    ownerMembershipId: null,
    finalOutcomeCode: null,
    version: 1,
    createdAt: CREATED,
    updatedAt: CREATED,
    ...overrides,
  };
}

/**
 * A `customer_contacts` row as the Job Details read resolves it (`BR-095`).
 *
 * The fixture carries every column a contact holds — the billing and job-contact flags, the free-text
 * `role`, the soft-removal pair and the write token — so a test can assert that the field read's
 * projection reports none of them (`BR-092`).
 */
function contactRow(overrides: Partial<CustomerContact> = {}): CustomerContact {
  return {
    id: CONTACT_ID,
    customerId: CUSTOMER_ID,
    firstName: 'John',
    lastName: 'Smith',
    email: null,
    phone: null,
    role: null,
    isPrimary: false,
    isBillingContact: false,
    isJobContact: false,
    removedAt: null,
    removedByMembershipId: null,
    version: 1,
    createdAt: CREATED,
    updatedAt: CREATED,
    ...overrides,
  };
}

describe('job details projection', () => {
  it('projects the Job with the selected Visit and the technicians assigned to it', () => {
    const details: JobDetails = {
      job: jobRow({
        status: 'SCHEDULED',
        description: 'Annual inspection.',
        propertyId: PROPERTY_ID,
        propertyAddressSnapshot: {
          propertyName: 'Cedar Lane Building',
          addressLine1: '987 Cedar Lane',
          city: 'Montreal',
          province: 'QC',
          postalCode: 'H3A 2T6',
          country: 'Canada',
        },
      }),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      customerContactDetails: {
        email: null,
        phone: null,
        notes: null,
        contacts: [],
      },
      selectedVisit: {
        visitId: 'visit-1',
        status: 'SCHEDULED',
        scheduledStart: new Date('2026-09-08T13:00:00.000Z'),
        scheduledEnd: new Date('2026-09-08T15:00:00.000Z'),
        version: 4,
      },
      technicians: [
        { membershipId: 'member-1', name: 'Mike Lead', roleCode: 'LEAD' },
        {
          membershipId: 'member-2',
          name: 'John Support',
          roleCode: 'TECHNICIAN',
        },
        { membershipId: 'member-3', name: null, roleCode: 'TECHNICIAN' },
      ],
    };

    const dto = toJobDetailsDto(details);

    expect(dto).toMatchObject({
      id: JOB_ID,
      jobNumber: 1042,
      title: 'Furnace repair',
      description: 'Annual inspection.',
      status: 'SCHEDULED',
      // The client draws its status actions from the server's own lifecycle table (`BR-058`,
      // `BR-041`): every structurally permitted destination for the Job's status, whether or not the
      // Job qualifies for it right now, and cancellation is absent while its reason catalogue is open
      // (`BR-061`, `BR-062`, `BR-064`).
      allowedStatusTransitions: ['IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED'],
      version: 1,
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      propertyId: PROPERTY_ID,
      selectedVisit: {
        id: 'visit-1',
        status: 'SCHEDULED',
        scheduledStart: '2026-09-08T13:00:00.000Z',
        scheduledEnd: '2026-09-08T15:00:00.000Z',
        // `BR-073` permits rescheduling only while the Visit is `SCHEDULED`.
        reschedulable: true,
        // No caller was named for this projection, so the Visit is reported as one this caller may not
        // drive and **no** destination is offered: every one of them would be refused, and `BR-093`
        // requires the projection to expose only what the caller may execute (`BR-042`). The structural
        // table itself is asserted below, through a caller who may drive the Visit.
        allowedStatusTransitions: [],
        // The fail-closed answer a caller the API cannot place receives (`BR-042`).
        fieldActionable: false,
      },
      address: {
        propertyName: 'Cedar Lane Building',
        addressLine1: '987 Cedar Lane',
        city: 'Montreal',
        province: 'QC',
        postalCode: 'H3A 2T6',
        country: 'Canada',
        addressLine2: null,
      },
      // The Lead is first because the read orders the assignments; the projection preserves that
      // order rather than re-deriving it (`BR-068`).
      technicians: [
        { membershipId: 'member-1', name: 'Mike Lead', roleCode: 'LEAD' },
        {
          membershipId: 'member-2',
          name: 'John Support',
          roleCode: 'TECHNICIAN',
        },
        { membershipId: 'member-3', name: null, roleCode: 'TECHNICIAN' },
      ],
    });
  });

  it('reports the Visit lifecycle to the client as the API itself enforces it', () => {
    const visit = (status: VisitStatus) =>
      toJobDetailsDto(
        {
          job: jobRow({ status: 'SCHEDULED' }),
          customerId: CUSTOMER_ID,
          customerName: 'Martha Reynolds',
          customerContactDetails: {
            email: null,
            phone: null,
            notes: null,
            contacts: [],
          },
          selectedVisit: {
            visitId: 'visit-1',
            status,
            scheduledStart: new Date('2026-09-08T13:00:00.000Z'),
            scheduledEnd: new Date('2026-09-08T15:00:00.000Z'),
            version: 1,
          },
          technicians: [],
        },
        // Read through a caller who may drive the Visit and holds the completion's own capability, so
        // the list asserted here is the structural table `BR-074` defines (`BR-093` narrows it to what
        // the caller may execute, which the field-action test below asserts).
        { officeVisitWriter: true, recordsVisitOutcome: true },
      ).selectedVisit?.allowedStatusTransitions;

    // `BR-074` permits free movement between the working statuses in either direction, so every working
    // destination is offered and a `COMPLETED` Visit is reopenable.
    expect(visit('DRAFT')).toEqual([
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
      'COMPLETED',
    ]);
    expect(visit('SCHEDULED')).toEqual([
      'DRAFT',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
      'COMPLETED',
    ]);
    expect(visit('EN_ROUTE')).toEqual([
      'DRAFT',
      'SCHEDULED',
      'ON_SITE',
      'IN_PROGRESS',
      'COMPLETED',
    ]);
    expect(visit('ON_SITE')).toEqual([
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'IN_PROGRESS',
      'COMPLETED',
    ]);
    expect(visit('IN_PROGRESS')).toEqual([
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'COMPLETED',
    ]);
    expect(visit('COMPLETED')).toEqual([
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
    ]);

    // A Visit never stands still: the status it already holds is not a destination (`BR-074`).
    for (const status of [
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
      'COMPLETED',
    ] as const) {
      expect(visit(status)).not.toContain(status);
    }

    // `CANCELED` and `NO_SHOW` are truly terminal and remain destinations of no field route: `BR-066`
    // makes them dispatch actions and no capability authorizes one (`BR-042`, `ADR-019` D7).
    expect(visit('CANCELED')).toEqual([]);
    expect(visit('NO_SHOW')).toEqual([]);
    for (const status of [
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
      'COMPLETED',
    ] as const) {
      expect(visit(status)).not.toContain('CANCELED');
      expect(visit(status)).not.toContain('NO_SHOW');
    }
  });

  it('reports no Visit and no technicians for a Job that has none', () => {
    const dto = toJobDetailsDto({
      job: jobRow(),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      customerContactDetails: {
        email: null,
        phone: null,
        notes: null,
        contacts: [],
      },
      selectedVisit: null,
      technicians: [],
    });

    expect(dto.selectedVisit).toBeNull();
    expect(dto.technicians).toEqual([]);
    // A Job with no Property has no address, and the API reports that rather than inventing one
    // (`BR-051`, `BR-056`).
    expect(dto.propertyId).toBeNull();
    expect(dto.address).toBeNull();
  });

  it('reports an empty crew for a selected Visit nobody is assigned to', () => {
    const dto = toJobDetailsDto({
      job: jobRow({ status: 'SCHEDULED' }),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      customerContactDetails: {
        email: null,
        phone: null,
        notes: null,
        contacts: [],
      },
      selectedVisit: {
        visitId: 'visit-1',
        status: 'DRAFT',
        scheduledStart: new Date('2026-09-08T13:00:00.000Z'),
        scheduledEnd: new Date('2026-09-08T15:00:00.000Z'),
        version: 1,
      },
      technicians: [],
    });

    expect(dto.selectedVisit?.status).toBe('DRAFT');
    expect(dto.selectedVisit?.version).toBe(1);
    expect(dto.technicians).toEqual([]);
  });

  it('answers the field action for the caller, from the same crew the field route checks', () => {
    const details = (): JobDetails => ({
      job: jobRow({ status: 'SCHEDULED' }),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      customerContactDetails: {
        email: null,
        phone: null,
        notes: null,
        contacts: [],
      },
      selectedVisit: {
        visitId: 'visit-1',
        status: 'SCHEDULED',
        scheduledStart: new Date('2026-09-08T13:00:00.000Z'),
        scheduledEnd: new Date('2026-09-08T15:00:00.000Z'),
        version: 1,
      },
      technicians: [
        { membershipId: 'member-1', name: 'Mike Lead', roleCode: 'LEAD' },
        { membershipId: 'member-2', name: null, roleCode: 'TECHNICIAN' },
      ],
    });

    // Any technician on the crew may drive the Visit, not only the Lead (`ADR-019` D3).
    expect(
      toJobDetailsDto(details(), { callerMembershipId: 'member-2' }).selectedVisit
        ?.fieldActionable,
    ).toBe(true);

    // A caller the Job read admits through **another** Visit of the Job may not: the field route
    // refuses a Visit that crew does not include, so the action is not offered (`ADR-019` D2, D3) and
    // no destination is either, because every one of them would be refused (`BR-093`).
    const otherVisit = toJobDetailsDto(details(), {
      callerMembershipId: 'member-9',
    }).selectedVisit;
    expect(otherVisit?.fieldActionable).toBe(false);
    expect(otherVisit?.allowedStatusTransitions).toEqual([]);

    // An office caller on no crew drives the Visit through the second authorization `BR-093` gives the
    // route — the office capability that reaches Visit writes (`ADR-019` D7) — and is offered exactly
    // the destinations it may execute. The completion is absent here because that caller does not hold
    // the capability a completion requires of every caller, office included (`BR-009`, `BR-077`).
    const office = toJobDetailsDto(details(), {
      officeVisitWriter: true,
    }).selectedVisit;
    expect(office?.fieldActionable).toBe(true);
    expect(office?.allowedStatusTransitions).toEqual([
      'DRAFT',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
    ]);

    // With the completion's own capability the destination is exposed, and the authorization is
    // unchanged: `BR-093` calls the outcome capability its own question rather than folding it into the
    // office capability.
    expect(
      toJobDetailsDto(details(), {
        officeVisitWriter: true,
        recordsVisitOutcome: true,
      }).selectedVisit?.allowedStatusTransitions,
    ).toEqual(['DRAFT', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED']);

    // A caller the API can place nowhere — the fail-closed answer (`BR-042`).
    expect(toJobDetailsDto(details()).selectedVisit?.fieldActionable).toBe(false);
  });

  it('reports no field action for a Job with no Visit at all', () => {
    const dto = toJobDetailsDto(
      {
        job: jobRow(),
        customerId: CUSTOMER_ID,
        customerName: 'Martha Reynolds',
        customerContactDetails: {
          email: null,
          phone: null,
          notes: null,
          contacts: [],
        },
        selectedVisit: null,
        technicians: [],
      },
      { callerMembershipId: 'member-1' },
    );

    expect(dto.selectedVisit).toBeNull();
  });


  it('keeps the known parts of a partly empty address snapshot and reports no address when absent', () => {
    const partlyKnown = toJobDetailsDto({
      job: jobRow({
        propertyId: PROPERTY_ID,
        propertyAddressSnapshot: { city: 'Ottawa' },
      }),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      customerContactDetails: {
        email: null,
        phone: null,
        notes: null,
        contacts: [],
      },
      selectedVisit: null,
      technicians: [],
    });
    expect(partlyKnown.address).toEqual({
      propertyName: null,
      addressLine1: null,
      addressLine2: null,
      city: 'Ottawa',
      province: null,
      postalCode: null,
      country: null,
    });

    const unreadable = toJobDetailsDto({
      job: jobRow({ propertyAddressSnapshot: 'not a snapshot' }),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      customerContactDetails: {
        email: null,
        phone: null,
        notes: null,
        contacts: [],
      },
      selectedVisit: null,
      technicians: [],
    });
    expect(unreadable.address).toBeNull();
  });

  it('reports the Customer contact details only when the read was asked for them', () => {
    const details: JobDetails = {
      job: jobRow({ status: 'SCHEDULED' }),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      customerContactDetails: {
        email: 'martha@example.com',
        phone: '+15145550142',
        notes: 'Prefers mornings.',
        contacts: [],
      },
      selectedVisit: null,
      technicians: [],
    };

    // `BR-092`'s answer is the read's **second** authorization question (`ADR-021` D2): the guard decided
    // who may read the Job, and this decides whether the Customer's own contact details are part of the
    // answer. A caller holding `customers.view` or `customers.view_assigned` is given them.
    expect(
      toJobDetailsDto(details, { includeCustomerContact: true })
        .customerContactDetails,
    ).toEqual({
      email: 'martha@example.com',
      phone: '+15145550142',
      notes: 'Prefers mornings.',
      contacts: [],
    });

    // A caller holding neither capability reads the **same** Job with the block reported as absent,
    // rather than being refused the Job they are assigned to (`BR-009`, `BR-011`).
    expect(
      toJobDetailsDto(details, { includeCustomerContact: false })
        .customerContactDetails,
    ).toBeNull();

    // The answer defaults to **not** including them, so a caller that never asked is refused them
    // rather than handed them.
    expect(toJobDetailsDto(details).customerContactDetails).toBeNull();
  });

  it('keeps the block to the Customer fields BR-092 names, whatever the Customer holds', () => {
    const dto = toJobDetailsDto(
      {
        job: jobRow(),
        customerId: CUSTOMER_ID,
        customerName: 'Martha Reynolds',
        // A Customer with no email or notes still has a block: what is absent is a field's value, not
        // the block itself.
        customerContactDetails: {
          email: null,
          phone: '+15145550142',
          notes: null,
          contacts: [],
        },
        selectedVisit: null,
        technicians: [],
      },
      { includeCustomerContact: true },
    );

    expect(dto.customerContactDetails).toEqual({
      email: null,
      phone: '+15145550142',
      notes: null,
      contacts: [],
    });
    // `BR-092` names the Customer's own phone, email and notes beside its contact persons. The billing
    // fields, the preferred contact method and the language are deliberately not part of the read
    // (`ADR-021` D3), so the block carries exactly these keys.
    expect(Object.keys(dto.customerContactDetails ?? {}).sort()).toEqual([
      'contacts',
      'email',
      'notes',
      'phone',
    ]);
  });

  it('reports the Customer contact persons with their own name, phone, email and primary flag only', () => {
    // What a Technician may read of a contact person is `BR-092`'s answer: a name, a phone number, an
    // email address and which of them is the Customer's flagged primary (`BR-095`). Everything else a
    // contact row holds — the billing-contact and job-contact flags, the free-text `role`, the write
    // token `version` and the timestamps — is not part of the field read, so the projection narrows the
    // resolved row to those five fields (`BR-041`, `BR-042`).
    const dto = toJobDetailsDto(
      {
        job: jobRow(),
        customerId: CUSTOMER_ID,
        customerName: 'Martha Reynolds',
        customerContactDetails: {
          email: 'office@abc.example',
          phone: null,
          notes: null,
          contacts: [
            contactRow({
              id: 'contact-1',
              firstName: 'John',
              lastName: 'Smith',
              email: 'john@example.com',
              phone: '+15551234567',
              role: 'Site manager',
              isPrimary: true,
              isBillingContact: true,
              version: 4,
            }),
            // A second contact none of whose flags is set: `isPrimary` is the contact row's own flag, so
            // a read that reports it must report `false` rather than omit it, which is what lets a client
            // tell a Customer with a flagged primary from one with none (`BR-095`).
            contactRow({
              id: 'contact-2',
              firstName: 'Marie',
              lastName: 'Tremblay',
              phone: '+15559876543',
            }),
          ],
        },
        selectedVisit: null,
        technicians: [],
      },
      { includeCustomerContact: true },
    );

    expect(dto.customerContactDetails?.contacts).toEqual([
      {
        firstName: 'John',
        lastName: 'Smith',
        phone: '+15551234567',
        email: 'john@example.com',
        isPrimary: true,
      },
      {
        firstName: 'Marie',
        lastName: 'Tremblay',
        phone: '+15559876543',
        email: null,
        isPrimary: false,
      },
    ]);
    // The block reports the contacts in the order the read resolved them — primary first (`BR-095`) —
    // and the projection must not reorder or filter them.
    expect(
      Object.keys(dto.customerContactDetails?.contacts[0] ?? {}).sort(),
    ).toEqual(['email', 'firstName', 'isPrimary', 'lastName', 'phone']);
  });

  it('reports a Customer with no contact persons as an empty list', () => {
    // Zero contacts is a legal state (`BR-095`), and an individual Customer is their own primary and
    // holds no contact row at all, so the block states the absence as `[]` rather than omitting the
    // member a client reads it from.
    const dto = toJobDetailsDto(
      {
        job: jobRow(),
        customerId: CUSTOMER_ID,
        customerName: 'Martha Reynolds',
        customerContactDetails: {
          email: null,
          phone: null,
          notes: null,
          contacts: [],
        },
        selectedVisit: null,
        technicians: [],
      },
      { includeCustomerContact: true },
    );

    expect(dto.customerContactDetails?.contacts).toEqual([]);
  });

  /*
   * The Job's Visits (`BR-047`, `BR-071`). The read represents one Visit, and a screen that presents
   * the Job also has to present the Visits it has had, so the projection carries both: `selectedVisit`
   * answers which Visit represents the Job, and `visits` answers which Visits the Job holds — each in
   * the Job's own sequence, each with the crew it actually carries.
   */
  it('reports every Visit of the Job with its sequence, its schedule and its own crew', () => {
    const dto = toJobDetailsDto({
      job: jobRow({ status: 'SCHEDULED' }),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      customerContactDetails: {
        email: null,
        phone: null,
        notes: null,
        contacts: [],
      },
      selectedVisit: {
        visitId: 'visit-2',
        status: 'SCHEDULED',
        scheduledStart: new Date('2026-09-08T13:00:00.000Z'),
        scheduledEnd: new Date('2026-09-08T15:00:00.000Z'),
        version: 2,
      },
      technicians: [
        { membershipId: 'member-1', name: 'Mike Lead', roleCode: 'LEAD' },
      ],
      visits: [
        {
          visitId: 'visit-1',
          sequence: 1,
          status: 'COMPLETED',
          // The Visit's own **current** outcome (`BR-077`): a Visit that has one reports it, and a
          // Visit that holds none reports `null` rather than the outcome its history once recorded
          // (`BR-079`, `BR-042`).
          outcomeCode: 'RESOLVED',
          scheduledStart: new Date('2026-09-01T13:00:00.000Z'),
          scheduledEnd: new Date('2026-09-01T15:00:00.000Z'),
          version: 7,
          technicians: [
            { membershipId: 'member-9', name: 'Dave Past', roleCode: 'LEAD' },
          ],
        },
        {
          visitId: 'visit-2',
          sequence: 2,
          status: 'SCHEDULED',
          outcomeCode: null,
          scheduledStart: new Date('2026-09-08T13:00:00.000Z'),
          scheduledEnd: new Date('2026-09-08T15:00:00.000Z'),
          version: 2,
          technicians: [
            { membershipId: 'member-1', name: 'Mike Lead', roleCode: 'LEAD' },
            { membershipId: 'member-2', name: null, roleCode: 'TECHNICIAN' },
          ],
        },
      ],
    });

    expect(dto.visits).toEqual([
      {
        id: 'visit-1',
        sequence: 1,
        status: 'COMPLETED',
        outcomeCode: 'RESOLVED',
        scheduledStart: '2026-09-01T13:00:00.000Z',
        scheduledEnd: '2026-09-01T15:00:00.000Z',
        version: 7,
        technicians: [
          { membershipId: 'member-9', name: 'Dave Past', roleCode: 'LEAD' },
        ],
      },
      {
        id: 'visit-2',
        sequence: 2,
        status: 'SCHEDULED',
        outcomeCode: null,
        scheduledStart: '2026-09-08T13:00:00.000Z',
        scheduledEnd: '2026-09-08T15:00:00.000Z',
        version: 2,
        technicians: [
          { membershipId: 'member-1', name: 'Mike Lead', roleCode: 'LEAD' },
          // A member with no profile yet is reported by assignment, without a fabricated name
          // (`BR-020`).
          { membershipId: 'member-2', name: null, roleCode: 'TECHNICIAN' },
        ],
      },
    ]);
    // The represented Visit is not privileged in the list: which Visit it is stays `selectedVisit`'s
    // answer (`BR-081`, `BR-041`).
    expect(dto.selectedVisit?.id).toBe(dto.visits[1]?.id);
  });

  it('reports a Visit with no schedule as both ends null, and no Visits as an empty list', () => {
    const base: JobDetails = {
      job: jobRow({ status: 'NEW' }),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
      customerContactDetails: {
        email: null,
        phone: null,
        notes: null,
        contacts: [],
      },
      selectedVisit: null,
      technicians: [],
    };

    // A Visit exists before it is scheduled (`BR-072`): it is still listed, and no schedule is claimed
    // for it rather than one being invented (`BR-042`).
    expect(
      toJobDetailsDto({
        ...base,
        visits: [
          {
            visitId: 'visit-1',
            sequence: 1,
            status: 'DRAFT',
            outcomeCode: null,
            scheduledStart: null,
            scheduledEnd: null,
            version: 1,
            technicians: [],
          },
        ],
      }).visits,
    ).toEqual([
      {
        id: 'visit-1',
        sequence: 1,
        status: 'DRAFT',
        outcomeCode: null,
        scheduledStart: null,
        scheduledEnd: null,
        version: 1,
        technicians: [],
      },
    ]);

    // A Job with no Visit reports an empty list rather than an absent field, so the response's shape
    // does not depend on whether the Job has any Visit (`BR-051`).
    expect(toJobDetailsDto(base).visits).toEqual([]);
  });
});
