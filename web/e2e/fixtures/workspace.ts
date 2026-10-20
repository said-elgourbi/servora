export const visit = {
  visitId: 'visit-one',
  visitStatus: 'SCHEDULED',
  purposeCode: 'WORK',
  scheduledStart: '2026-10-18T13:00:00Z',
  scheduledEnd: '2026-10-18T14:00:00Z',
  jobId: 'job-one',
  jobNumber: 1042,
  jobTitle: 'Furnace repair',
  jobStatus: 'ACTIVE',
  customerId: 'customer-one',
  customerName: 'Cedar Properties',
  address: {
    propertyName: 'Cedar House',
    addressLine1: '987 Cedar Lane',
    addressLine2: null,
    city: 'Montreal',
    province: 'QC',
    postalCode: 'H3A 2T6',
    country: 'Canada',
  },
  technicians: [{ membershipId: 'member-one', name: 'Alex Tremblay', roleCode: 'LEAD' }],
  overdue: true,
  staleStarted: false,
};
const common = {
  generatedAt: '2026-10-18T12:00:00Z',
  day: { timeZone: 'America/Toronto', start: '2026-10-18T04:00:00Z', end: '2026-10-19T04:00:00Z' },
  viewer: { displayName: 'Alex Tremblay' },
  attention: { total: 0, items: [] },
  visits: [visit],
};
export const officeHome = {
  ...common,
  today: { total: 1, completed: 0, inProgress: 0, upcoming: 1 },
};
export const fieldHome = { ...common, nextVisit: visit, upcoming: [], upcomingTotal: 0 };
export const job = {
  id: 'job-one',
  canScheduleVisit: false,
  customerId: 'customer-one',
  propertyId: 'property-one',
  version: 3,
  allowedStatusTransitions: ['COMPLETED', 'CANCELED'],
  attention: [],
  jobNumber: 1042,
  title: 'Furnace repair',
  description: 'Inspect the furnace.',
  status: 'ACTIVE',
  customerName: 'Cedar Properties',
  address: visit.address,
  selectedVisit: { id: visit.visitId },
  customerContactDetails: {
    phone: '+15145551234',
    email: 'customer@example.com',
    notes: 'Use the side entrance.',
    contacts: [
      {
        firstName: 'Jo',
        lastName: 'Tremblay',
        phone: '+15145554321',
        email: null,
        isPrimary: true,
      },
    ],
  },
  visits: [
    {
      id: visit.visitId,
      sequence: 1,
      status: visit.visitStatus,
      purposeCode: 'WORK',
      outcomeCode: null,
      scheduledStart: visit.scheduledStart,
      scheduledEnd: visit.scheduledEnd,
      technicians: visit.technicians,
    },
  ],
};
