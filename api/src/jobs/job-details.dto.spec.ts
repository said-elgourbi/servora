import { toJobDetailsDto, type JobDetails } from './job-details.dto.js';
import type { Job, VisitStatus } from './job.types.js';

const CREATED = new Date('2026-01-01T00:00:00.000Z');
const ORGANIZATION_ID = '00000000-0000-0000-0000-000000000001';
const CUSTOMER_ID = '00000000-0000-0000-0000-000000000010';
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
        // The client draws the technician's field action from the same table the field route validates
        // against (`BR-074`, `BR-041`).
        allowedStatusTransitions: ['EN_ROUTE'],
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
      toJobDetailsDto({
        job: jobRow({ status: 'SCHEDULED' }),
        customerId: CUSTOMER_ID,
        customerName: 'Martha Reynolds',
        selectedVisit: {
          visitId: 'visit-1',
          status,
          scheduledStart: new Date('2026-09-08T13:00:00.000Z'),
          scheduledEnd: new Date('2026-09-08T15:00:00.000Z'),
          version: 1,
        },
        technicians: [],
      }).selectedVisit?.allowedStatusTransitions;

    // The normal lifecycle, one step at a time, plus `BR-075`'s correction (`BR-074`).
    expect(visit('DRAFT')).toEqual(['SCHEDULED']);
    expect(visit('SCHEDULED')).toEqual(['EN_ROUTE']);
    expect(visit('EN_ROUTE')).toEqual(['ON_SITE', 'SCHEDULED']);
    expect(visit('ON_SITE')).toEqual(['IN_PROGRESS']);
    expect(visit('IN_PROGRESS')).toEqual(['COMPLETED']);

    // A historical Visit offers nothing: `BR-074` never returns a Visit to an active status.
    expect(visit('COMPLETED')).toEqual([]);
    expect(visit('CANCELED')).toEqual([]);
    expect(visit('NO_SHOW')).toEqual([]);

    // `CANCELED` and `NO_SHOW` are destinations of no route: `BR-066` makes them dispatch actions and
    // no capability authorizes one today (`BR-042`, `ADR-019` D7).
    for (const status of [
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
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

  it('keeps the known parts of a partly empty address snapshot and reports no address when absent', () => {
    const partlyKnown = toJobDetailsDto({
      job: jobRow({
        propertyId: PROPERTY_ID,
        propertyAddressSnapshot: { city: 'Ottawa' },
      }),
      customerId: CUSTOMER_ID,
      customerName: 'Martha Reynolds',
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
      selectedVisit: null,
      technicians: [],
    });
    expect(unreadable.address).toBeNull();
  });
});
