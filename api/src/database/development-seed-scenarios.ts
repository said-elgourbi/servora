/**
 * The realistic local dataset `make seed` writes.
 *
 * Development tooling, never product behaviour (`BR-042`). This module is **pure** — it describes the
 * dataset and checks it, and performs no database work — so the demo a developer signs in to can be
 * verified without a database (`development-seed-scenarios.spec.ts`), and
 * `run-development-seed.ts` writes exactly what this module describes.
 *
 * Two properties make the dataset usable as a demo and safe to re-run:
 *
 * 1. **It is anchored to the moment the seed runs.** A Visit's window is expressed relative to "now",
 *    so a field attempt that has already happened is finished and one that has not happened yet is
 *    merely scheduled. The dataset can never present a future Visit as completed work — the rule is
 *    asserted by `assertSeedScenariosAreCoherent`, not left to whoever edits the data next.
 * 2. **It is checked before it is written.** Schedules, statuses, outcomes, crews, note authorship and
 *    Job/Visit agreement are asserted against the rules the API itself enforces, so an edit to the data
 *    below fails loudly at seed time instead of storing a Job or Visit the product could not produce.
 */

/**
 * The extra Technician members the demo assigns work to.
 *
 * Servora records assignment on the **Visit** and a Visit may carry several technicians with exactly one
 * Lead (`BR-068`), so a demo that only ever assigned the single seeded technician account could not show
 * a crew. These are ordinary `ACTIVE` members holding the organization's Technician role; they are **not**
 * sign-in accounts, so no password is generated or reported for them.
 */
export const SEED_TECHNICIAN_MEMBER_TEMPLATES = [
  {
    key: 'SARAH',
    firstName: 'Sarah',
    lastName: 'Moreau',
    email: 'sarah.moreau@servora.test',
  },
  {
    key: 'JOHN',
    firstName: 'John',
    lastName: 'Tremblay',
    email: 'john.tremblay@servora.test',
  },
  {
    key: 'PRIYA',
    firstName: 'Priya',
    lastName: 'Raman',
    email: 'priya.raman@servora.test',
  },
  {
    key: 'LUC',
    firstName: 'Luc',
    lastName: 'Gagnon',
    email: 'luc.gagnon@servora.test',
  },
] as const;

/** The seeded people work is assigned to. `SEEDED` is the documented technician QA account. */
export type SeedTechnicianKey =
  | 'SEEDED'
  | (typeof SEED_TECHNICIAN_MEMBER_TEMPLATES)[number]['key'];

export type SeedCustomerKind = 'INDIVIDUAL' | 'COMPANY';
export type SeedCustomerStatus = 'ACTIVE' | 'INACTIVE';
export type SeedContactMethod = 'EMAIL' | 'PHONE' | 'SMS' | 'NONE';
export type SeedLanguage = 'en-CA' | 'fr-CA';
export type SeedJobStatus =
  | 'NEW'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'PENDING_REVIEW'
  | 'COMPLETED'
  | 'CANCELED';
export type SeedJobTypeCode =
  | 'REPAIR'
  | 'MAINTENANCE'
  | 'INSTALLATION'
  | 'INSPECTION'
  | 'SERVICE_CALL';
export type SeedVisitStatus =
  | 'DRAFT'
  | 'SCHEDULED'
  | 'EN_ROUTE'
  | 'ON_SITE'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELED'
  | 'NO_SHOW';
export type SeedVisitOutcomeCode =
  | 'RESOLVED'
  | 'NEEDS_PARTS'
  | 'NEEDS_FOLLOWUP'
  | 'NEEDS_QUOTE_APPROVAL'
  | 'UNABLE_TO_COMPLETE';
export type SeedVisitCancellationReason =
  | 'CUSTOMER_RESCHEDULED'
  | 'CUSTOMER_CANCELED'
  | 'WEATHER'
  | 'TECH_UNAVAILABLE'
  | 'DUPLICATE'
  | 'OTHER';

/** Who wrote a seeded Visit note: the office member, or a member of the Visit's crew (`BR-027`). */
export type SeedNoteAuthor = 'MANAGER' | SeedTechnicianKey;

/**
 * When a seeded Visit happens, stated relative to the seed run rather than as a fixed date.
 *
 * `DAYS_AGO` and `IN_DAYS` mean "that day, at that hour" and are always strictly past or strictly
 * future whatever time of day the seed is run, which is what makes the dataset's statuses honest.
 * `HOURS_FROM_NOW` is for the work that is happening today, in flight.
 */
export type SeedSchedule =
  | {
      readonly kind: 'DAYS_AGO';
      readonly days: number;
      readonly hour: number;
      readonly durationHours: number;
    }
  | {
      readonly kind: 'IN_DAYS';
      readonly days: number;
      readonly hour: number;
      readonly durationHours: number;
    }
  | {
      readonly kind: 'HOURS_FROM_NOW';
      readonly hours: number;
      readonly durationHours: number;
    };

/** A structured address, exactly as `BR-049` stores one for a Property. */
export interface SeedAddressPlan {
  readonly addressLine1: string;
  readonly addressLine2?: string | null;
  readonly city: string;
  readonly province: string;
  readonly postalCode: string;
}

export interface SeedPropertyPlan extends SeedAddressPlan {
  readonly name: string;
  readonly notes?: string | null;
}

/** A contact person (`BR-095`). A COMPANY Customer's primary is the contact flagged `isPrimary`. */
export interface SeedContactPlan {
  readonly firstName: string;
  readonly lastName: string;
  readonly role?: string | null;
  readonly email?: string | null;
  readonly phone?: string | null;
  readonly isPrimary?: boolean;
  readonly isBillingContact?: boolean;
  readonly isJobContact?: boolean;
}

/** One Visit note (`BR-027`), placed relative to the Visit it belongs to. */
export interface SeedNotePlan {
  /** Hours relative to the Visit's scheduled start; negative is before the Visit. */
  readonly atHours: number;
  readonly author: SeedNoteAuthor;
  readonly body: string;
}

export interface SeedVisitPlan {
  readonly status: SeedVisitStatus;
  /** `null` only for a `DRAFT` Visit: an unscheduled field attempt has no window (`BR-072`). */
  readonly schedule: SeedSchedule | null;
  /** The crew, first entry is the `LEAD` (`BR-068`). Empty only for a `DRAFT` Visit. */
  readonly crew: readonly SeedTechnicianKey[];
  /** `BR-077` requires it of a `COMPLETED` Visit, and only of one. */
  readonly outcome?: {
    readonly code: SeedVisitOutcomeCode;
    readonly summary: string;
  };
  /** `BR-076`'s structured reason, required of a `CANCELED` Visit. */
  readonly cancellation?: {
    readonly reasonCode: SeedVisitCancellationReason;
    readonly note: string;
  };
  /** A technician who had been assigned and was removed again, recorded as history (`BR-069`). */
  readonly removedTechnician?: SeedTechnicianKey;
  readonly notes?: readonly SeedNotePlan[];
}

export interface SeedJobPlan {
  readonly title: string;
  readonly description: string;
  /** `BR-053`'s illustrative category; the catalogue itself is an open question. */
  readonly typeCode: SeedJobTypeCode;
  readonly status: SeedJobStatus;
  /** Index into the Customer's own `properties`; `null` for a Job with no Property yet (`BR-051`). */
  readonly propertyIndex: number | null;
  /** Whether the office member owns the Job (`BR-055`). An ownerless Job is a legal state. */
  readonly owner: boolean;
  /**
   * The explanation a Job cancellation must carry (`BR-064`). The structured reason catalogue is an
   * open question, so this is where the demo states why the Job was canceled.
   */
  readonly cancellationNote?: string;
  readonly visits: readonly SeedVisitPlan[];
}

export interface SeedCustomerPlan {
  readonly kind: SeedCustomerKind;
  readonly displayName: string;
  /** `INDIVIDUAL` only (`BR-023`). */
  readonly firstName?: string;
  readonly lastName?: string;
  /** `COMPANY` only (`BR-023`). */
  readonly legalName?: string;
  readonly businessName?: string;
  readonly taxNumber?: string;
  readonly email: string;
  readonly phone: string;
  readonly billingEmail?: string | null;
  readonly billingPhone?: string | null;
  readonly notes: string;
  readonly preferredContactMethod: SeedContactMethod;
  readonly language: SeedLanguage;
  readonly status: SeedCustomerStatus;
  /** How long the organization has had this Customer; sets `customers.created_at`. */
  readonly customerSinceDaysAgo: number;
  readonly billingAddress: SeedAddressPlan;
  readonly contacts: readonly SeedContactPlan[];
  readonly properties: readonly SeedPropertyPlan[];
  readonly jobs: readonly SeedJobPlan[];
}

/**
 * How a Visit reached the status it is seeded with (`BR-074`).
 *
 * The path is walked one permitted transition at a time, so the seeded history is a history the
 * lifecycle could actually have produced rather than a single invented row. `CANCELED` carries the
 * structured reason `BR-076` requires; `OTHER` is avoided because it also requires an explanation the
 * demo data does not have to invent.
 */
export const VISIT_STATUS_PATHS: Record<
  SeedVisitStatus,
  {
    readonly path: readonly SeedVisitStatus[];
    readonly cancellationReasonCode?: SeedVisitCancellationReason;
  }
> = {
  DRAFT: { path: [] },
  SCHEDULED: { path: ['DRAFT', 'SCHEDULED'] },
  EN_ROUTE: { path: ['DRAFT', 'SCHEDULED', 'EN_ROUTE'] },
  ON_SITE: { path: ['DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE'] },
  IN_PROGRESS: {
    path: ['DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'],
  },
  COMPLETED: {
    path: [
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
      'COMPLETED',
    ],
  },
  CANCELED: {
    path: ['DRAFT', 'SCHEDULED', 'CANCELED'],
    cancellationReasonCode: 'CUSTOMER_RESCHEDULED',
  },
  NO_SHOW: { path: ['DRAFT', 'SCHEDULED', 'NO_SHOW'] },
};

/**
 * How a Job reached the status it is seeded with (`BR-058`).
 *
 * A Job's status history is what the Activity read projects (`BR-080`), so it is a path the lifecycle
 * permits rather than a single row invented to match the current status.
 */
export const JOB_STATUS_PATHS: Record<SeedJobStatus, readonly SeedJobStatus[]> = {
  NEW: ['NEW'],
  SCHEDULED: ['NEW', 'SCHEDULED'],
  IN_PROGRESS: ['NEW', 'SCHEDULED', 'IN_PROGRESS'],
  PENDING_REVIEW: ['NEW', 'SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW'],
  COMPLETED: ['NEW', 'SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED'],
  CANCELED: ['NEW', 'CANCELED'],
};


/**
 * The demo dataset: 14 customers across Canada, the work the organization holds for them, and the crews
 * assigned to it. Titles, notes and outcomes read the way an office and a technician write them, in the
 * Customer's own language where that Customer is French-speaking (`BR-028`).
 *
 * Two properties of the spread are deliberate: every Job status and every Visit status appears, so each
 * screen and filter has something real to present, and no technician is booked on two overlapping
 * Visits (`BR-070`).
 */
export const SEED_CUSTOMERS: readonly SeedCustomerPlan[] = [
  {
    kind: 'COMPANY',
    displayName: 'Northwood Dental Group',
    legalName: 'Northwood Dental Group Inc.',
    businessName: 'Northwood Dental Group',
    taxNumber: 'GST-7102-4821',
    email: 'office@northwooddental.test',
    phone: '+1 613 555 0141',
    billingEmail: 'accounts@northwooddental.test',
    billingPhone: '+1 613 555 0142',
    notes:
      'Two clinics. Both sites ask the technician to call ahead so the room can be cleared.',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 420,
    billingAddress: {
      addressLine1: '1420 Bank Street',
      city: 'Ottawa',
      province: 'Ontario',
      postalCode: 'K1V 7H7',
    },
    contacts: [
      {
        firstName: 'Danielle',
        lastName: 'Roy',
        role: 'Practice Manager',
        email: 'danielle.roy@northwooddental.test',
        phone: '+1 613 555 0143',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Isabelle',
        lastName: 'Grant',
        role: 'Office Administrator',
        email: 'isabelle.grant@northwooddental.test',
        phone: '+1 613 555 0144',
        isBillingContact: true,
      },
    ],
    properties: [
      {
        name: 'Northwood Dental — Bank Street',
        addressLine1: '1420 Bank Street',
        city: 'Ottawa',
        province: 'Ontario',
        postalCode: 'K1V 7H7',
        notes: 'Suite entrance on the south side; the reception desk holds the key.',
      },
      {
        name: 'Northwood Dental — Kanata',
        addressLine1: '300 Eagleson Road',
        addressLine2: 'Unit 12',
        city: 'Kanata',
        province: 'Ontario',
        postalCode: 'K2M 1C9',
      },
    ],
    jobs: [
      {
        title: 'Sterilizer room ceiling leak',
        description:
          'Water dripping from the ceiling tile above the sterilizer. Staff have a bucket under it and the room is still in use.',
        typeCode: 'REPAIR',
        status: 'IN_PROGRESS',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 3, hour: 13, durationHours: 2 },
            crew: ['SEEDED'],
            outcome: {
              code: 'NEEDS_PARTS',
              summary:
                'Ceiling opened and the leak traced to a failed shut-off valve above the sterilizer. Line drained; replacement valve ordered.',
            },
            notes: [
              {
                atHours: -20,
                author: 'MANAGER',
                body: 'Patient rooms are directly below. Keep the area tarped and call Danielle before the ceiling comes down.',
              },
              {
                atHours: 1,
                author: 'SEEDED',
                body: 'Leak isolated and the line drained. The valve is a half-inch sweat fit — part ordered.',
              },
            ],
          },
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 2, hour: 13, durationHours: 2 },
            crew: ['SEEDED', 'JOHN'],
            notes: [
              {
                atHours: -72,
                author: 'MANAGER',
                body: 'Valve is in. Danielle booked the return so the room can stay closed for the afternoon.',
              },
            ],
          },
        ],
      },
      {
        title: 'Autoclave not reaching sterilization temperature',
        description:
          'The Kanata autoclave faults out two minutes into a cycle and never reaches temperature.',
        typeCode: 'REPAIR',
        status: 'COMPLETED',
        propertyIndex: 1,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 12, hour: 9, durationHours: 3 },
            crew: ['SARAH'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Heating element replaced and the cycle verified at 134 °C twice before the room was handed back.',
            },
            notes: [
              {
                atHours: 2,
                author: 'SARAH',
                body: 'Ran two test cycles with a load and left the printouts at the front desk.',
              },
            ],
          },
        ],
      },
      {
        title: 'Annual backflow preventer test — both clinics',
        description:
          'Municipal requirement. Certificates have to be filed for both locations before the end of the quarter.',
        typeCode: 'INSPECTION',
        status: 'NEW',
        propertyIndex: 0,
        owner: false,
        visits: [
          {
            status: 'DRAFT',
            schedule: null,
            crew: [],
          },
        ],
      },
    ],
  },

  {
    kind: 'COMPANY',
    displayName: 'Harbourview Property Management',
    legalName: 'Harbourview Property Management Ltd.',
    businessName: 'Harbourview Property Management',
    taxNumber: 'GST-4471-9036',
    email: 'operations@harbourviewpm.test',
    phone: '+1 604 555 0161',
    billingEmail: 'ap@harbourviewpm.test',
    billingPhone: '+1 604 555 0162',
    notes:
      'Four buildings under contract. Security desks sign contractors in and the loading bay is off Richards Street.',
    preferredContactMethod: 'PHONE',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 880,
    billingAddress: {
      addressLine1: '555 West Hastings Street',
      addressLine2: 'Suite 900',
      city: 'Vancouver',
      province: 'British Columbia',
      postalCode: 'V6B 1L6',
    },
    contacts: [
      {
        firstName: 'Ken',
        lastName: 'Nakamura',
        role: 'Building Operations Lead',
        email: 'ken.nakamura@harbourviewpm.test',
        phone: '+1 604 555 0163',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Priya',
        lastName: 'Desai',
        role: 'Accounts Payable',
        email: 'priya.desai@harbourviewpm.test',
        phone: '+1 604 555 0164',
        isBillingContact: true,
      },
    ],
    properties: [
      {
        name: 'Marina Pointe — Tower A',
        addressLine1: '1088 Marinaside Crescent',
        city: 'Vancouver',
        province: 'British Columbia',
        postalCode: 'V6Z 2Z4',
        notes: 'Mechanical room is in the parkade, level P2, beside the bike storage.',
      },
      {
        name: 'Marina Pointe — Tower B',
        addressLine1: '1090 Marinaside Crescent',
        city: 'Vancouver',
        province: 'British Columbia',
        postalCode: 'V6Z 2Z4',
      },
      {
        name: 'Harbourview Commercial Centre',
        addressLine1: '555 West Hastings Street',
        city: 'Vancouver',
        province: 'British Columbia',
        postalCode: 'V6B 1L6',
      },
    ],
    jobs: [

      {
        title: 'Boiler losing pressure in Tower A',
        description:
          'The boiler drops from 18 psi to 8 psi overnight and the building manager tops it up by hand each morning.',
        typeCode: 'REPAIR',
        status: 'IN_PROGRESS',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'EN_ROUTE',
            schedule: { kind: 'HOURS_FROM_NOW', hours: -1, durationHours: 5 },
            crew: ['PRIYA'],
            notes: [
              {
                atHours: -2,
                author: 'MANAGER',
                body: 'Ken is meeting the crew at the P2 mechanical room and has the isolation valves tagged.',
              },
            ],
          },
        ],
      },
      {
        title: 'Elevator machine room cooling unit service',
        description:
          'Annual service on the machine room cooler. The freight elevator is reserved for the morning.',
        typeCode: 'MAINTENANCE',
        status: 'SCHEDULED',
        propertyIndex: 1,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 2, hour: 8, durationHours: 3 },
            crew: ['JOHN', 'LUC'],
            notes: [
              {
                atHours: -72,
                author: 'MANAGER',
                body: 'Ken reserved the freight elevator from 08:00 to 11:00. Bring the extension ladder.',
              },
            ],
          },
        ],
      },
      {
        title: 'Lobby door closer replacement',
        description:
          'The main lobby door slams and does not latch on the second sweep. Replacement parts are on the service van.',
        typeCode: 'REPAIR',
        status: 'PENDING_REVIEW',
        propertyIndex: 2,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 1, hour: 10, durationHours: 2 },
            crew: ['SEEDED'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Closer replaced and adjusted; the door latches fully on the second sweep.',
            },
            notes: [
              {
                atHours: 1,
                author: 'SEEDED',
                body: 'The concierge watched it cycle a dozen times and is happy with the adjustment.',
              },
            ],
          },
        ],
      },
      {
        title: 'Parkade exhaust fan inspection — both towers',
        description:
          'Semi-annual inspection of the parkade exhaust fans, including the CO sensors they interlock with.',
        typeCode: 'INSPECTION',
        status: 'SCHEDULED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 5, hour: 13, durationHours: 3 },
            crew: ['SARAH', 'PRIYA'],
            removedTechnician: 'LUC',
            notes: [
              {
                atHours: -144,
                author: 'MANAGER',
                body: 'Ken asked for both towers on one afternoon so the parkade notices only go out once.',
              },
            ],
          },
        ],
      },
      {
        title: 'Loading dock door off track',
        description:
          'The centre dock door came off its track. The building is using the side door until it is repaired.',
        typeCode: 'REPAIR',
        status: 'IN_PROGRESS',
        propertyIndex: 2,
        owner: true,
        visits: [
          {
            status: 'IN_PROGRESS',
            schedule: { kind: 'HOURS_FROM_NOW', hours: -2, durationHours: 5 },
            crew: ['LUC', 'JOHN'],
            notes: [
              {
                atHours: -1,
                author: 'LUC',
                body: 'Top rollers are bent and the track is bowed. Replacing the roller set and straightening the track.',
              },
            ],
          },
        ],
      },
    ],
  },

  {
    kind: 'INDIVIDUAL',
    displayName: 'Amélie Tremblay',
    firstName: 'Amélie',
    lastName: 'Tremblay',
    email: 'amelie.tremblay@servora.test',
    phone: '+1 514 555 0102',
    notes:
      'Préfère un texto avant l’arrivée du technicien. Le chien est amical mais reste dans la cour.',
    preferredContactMethod: 'SMS',
    language: 'fr-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 210,
    billingAddress: {
      addressLine1: '4285 rue Saint-Denis',
      city: 'Montréal',
      province: 'Québec',
      postalCode: 'H2J 2K8',
    },
    contacts: [],
    properties: [
      {
        name: 'Maison Tremblay',
        addressLine1: '4285 rue Saint-Denis',
        city: 'Montréal',
        province: 'Québec',
        postalCode: 'H2J 2K8',
        notes: 'Porte arrière accessible par la ruelle; code de la remise 4821.',
      },
    ],
    jobs: [
      {
        title: "Fuite sous l'évier de la cuisine",
        description:
          "La cliente a remarqué de l'eau sous l'armoire depuis deux jours. Un seau est en place.",
        typeCode: 'REPAIR',
        status: 'COMPLETED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 9, hour: 9, durationHours: 1 },
            crew: ['LUC'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Joint du siphon et tuyau de vidange remplacés. Aucune fuite après un essai de trente minutes.',
            },
            notes: [
              {
                atHours: 1,
                author: 'LUC',
                body: 'Sous l’armoire asséché et vérifié; la cliente a confirmé que tout est sec.',
              },
            ],
          },
        ],
      },
      {
        title: 'Entretien annuel de la fournaise',
        description:
          'Entretien annuel avant l’hiver : nettoyage des brûleurs, filtre et vérification du ventilateur.',
        typeCode: 'MAINTENANCE',
        status: 'SCHEDULED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 11, hour: 9, durationHours: 2 },
            crew: ['LUC'],
            notes: [
              {
                atHours: -288,
                author: 'MANAGER',
                body: 'Rendez-vous confirmé par texto avec la cliente, comme elle le préfère.',
              },
            ],
          },
        ],
      },
    ],
  },

  {
    kind: 'COMPANY',
    displayName: 'Riverside Logistics',
    legalName: 'Riverside Logistics Inc.',
    businessName: 'Riverside Logistics',
    taxNumber: 'GST-2288-5174',
    email: 'dispatch@riversidelogistics.test',
    phone: '+1 204 555 0171',
    billingEmail: 'ap@riversidelogistics.test',
    billingPhone: '+1 204 555 0172',
    notes:
      'Report to the dispatch office before entering the yard. Hearing protection is required past the gate.',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 1460,
    billingAddress: {
      addressLine1: '1250 Inkster Boulevard',
      city: 'Winnipeg',
      province: 'Manitoba',
      postalCode: 'R2X 1P5',
    },
    contacts: [
      {
        firstName: 'Marc',
        lastName: 'Beaulieu',
        role: 'Facilities Supervisor',
        email: 'marc.beaulieu@riversidelogistics.test',
        phone: '+1 204 555 0173',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Dana',
        lastName: 'White',
        role: 'Accounts Payable',
        email: 'dana.white@riversidelogistics.test',
        phone: '+1 204 555 0174',
        isBillingContact: true,
      },
    ],
    properties: [
      {
        name: 'Riverside Logistics — Dock 4',
        addressLine1: '1200 Inkster Boulevard',
        city: 'Winnipeg',
        province: 'Manitoba',
        postalCode: 'R2X 1P5',
        notes: 'Dock 4 is the last door on the north wall; the yard jockey escorts contractors.',
      },
      {
        name: 'Riverside Logistics — Office',
        addressLine1: '1250 Inkster Boulevard',
        city: 'Winnipeg',
        province: 'Manitoba',
        postalCode: 'R2X 1P5',
      },
    ],
    jobs: [
      {
        title: 'Dock leveller hydraulics leaking',
        description:
          'Hydraulic oil is pooling under the dock leveller and the deck drifts down under a loaded forklift.',
        typeCode: 'REPAIR',
        status: 'IN_PROGRESS',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 6, hour: 7, durationHours: 3 },
            crew: ['JOHN'],
            outcome: {
              code: 'NEEDS_FOLLOWUP',
              summary:
                'Hydraulic line replaced and topped up, but the deck still drifts under load — the pump needs a rebuild.',
            },
            notes: [
              {
                atHours: 2,
                author: 'JOHN',
                body: 'Recommend booking the pump rebuild with two technicians and the yard closed at that door.',
              },
            ],
          },
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 4, hour: 7, durationHours: 4 },
            crew: ['JOHN', 'LUC'],
            notes: [
              {
                atHours: -120,
                author: 'MANAGER',
                body: 'Seal kit is in. Marc booked the morning shift and will have the door blocked off.',
              },
            ],
          },
        ],
      },
      {
        title: 'Overhead door annual inspection — docks 1 to 4',
        description:
          'Annual inspection of the four overhead doors, including springs, sensors and the manual release.',
        typeCode: 'INSPECTION',
        status: 'COMPLETED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 21, hour: 9, durationHours: 4 },
            crew: ['PRIYA', 'LUC'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Four doors inspected, springs and safety sensors adjusted. No defects recorded.',
            },
            notes: [
              {
                atHours: 2,
                author: 'PRIYA',
                body: 'Dock 2 sensor was misaligned and is corrected; all four doors cycle cleanly now.',
              },
            ],
          },
        ],
      },
      {
        title: 'Warehouse LED retrofit — phase one',
        description:
          'Replace the high-bay fixtures over aisles 1 to 6 with LED. Phase two covers the mezzanine.',
        typeCode: 'INSTALLATION',
        status: 'SCHEDULED',
        propertyIndex: 1,
        owner: false,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 13, hour: 6, durationHours: 8 },
            crew: ['SARAH', 'JOHN', 'LUC'],
            notes: [
              {
                atHours: -336,
                author: 'MANAGER',
                body: 'Lift is rented for the day. Aisles 1 to 6 must be clear of pallets before 06:00.',
              },
            ],
          },
        ],
      },
    ],
  },

  {
    kind: 'INDIVIDUAL',
    displayName: 'Grace Liu',
    firstName: 'Grace',
    lastName: 'Liu',
    email: 'grace.liu@servora.test',
    phone: '+1 416 555 0116',
    notes:
      'The concierge holds the key and has to be told before the technician goes up.',
    preferredContactMethod: 'SMS',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 55,
    billingAddress: {
      addressLine1: '88 Harbour Street',
      addressLine2: 'Unit 2103',
      city: 'Toronto',
      province: 'Ontario',
      postalCode: 'M5J 0C3',
    },
    contacts: [],
    properties: [
      {
        name: 'Suite 2103 — 88 Harbour Street',
        addressLine1: '88 Harbour Street',
        addressLine2: 'Unit 2103',
        city: 'Toronto',
        province: 'Ontario',
        postalCode: 'M5J 0C3',
        notes: 'Fan coil is behind the hallway ceiling panel; bring the step ladder.',
      },
    ],
    jobs: [
      {
        title: 'Air conditioner running but not cooling',
        description:
          'The fan coil runs continuously but blows warm air. Building management says the riser temperature is normal.',
        typeCode: 'REPAIR',
        status: 'SCHEDULED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 1, hour: 10, durationHours: 2 },
            crew: ['SEEDED'],
            notes: [
              {
                atHours: -48,
                author: 'MANAGER',
                body: 'Concierge will let the technician up. The unit is a fan coil above the hallway ceiling.',
              },
            ],
          },
        ],
      },
      {
        title: 'Bathroom exhaust fan rattling',
        description:
          'Owner reports a rattle that starts a few minutes after the bathroom light is switched on.',
        typeCode: 'SERVICE_CALL',
        status: 'NEW',
        propertyIndex: 0,
        owner: false,
        visits: [],
      },
    ],
  },

  {
    kind: 'COMPANY',
    displayName: 'Cedar & Pine Hotel',
    legalName: 'Cedar and Pine Hospitality Inc.',
    businessName: 'Cedar & Pine Hotel',
    taxNumber: 'GST-9930-2218',
    email: 'engineering@cedarandpine.test',
    phone: '+1 902 555 0126',
    billingEmail: 'finance@cedarandpine.test',
    billingPhone: '+1 902 555 0127',
    notes:
      'Service entrance is the loading dock off the laneway. Sign in at the front desk before going to the kitchen.',
    preferredContactMethod: 'PHONE',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 1120,
    billingAddress: {
      addressLine1: '1269 Barrington Street',
      city: 'Halifax',
      province: 'Nova Scotia',
      postalCode: 'B3J 1Y2',
    },
    contacts: [
      {
        firstName: 'Nadine',
        lastName: 'Cormier',
        role: 'Chief Engineer',
        email: 'nadine.cormier@cedarandpine.test',
        phone: '+1 902 555 0128',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Tom',
        lastName: 'Ashford',
        role: 'Purchasing',
        email: 'tom.ashford@cedarandpine.test',
        phone: '+1 902 555 0129',
        isBillingContact: true,
      },
    ],
    properties: [
      {
        name: 'Cedar & Pine Hotel — Main Building',
        addressLine1: '1269 Barrington Street',
        city: 'Halifax',
        province: 'Nova Scotia',
        postalCode: 'B3J 1Y2',
        notes: 'Kitchen refrigeration is on the mezzanine; the chef holds the panel key.',
      },
      {
        name: 'Cedar & Pine Hotel — Annex',
        addressLine1: '1271 Barrington Street',
        city: 'Halifax',
        province: 'Nova Scotia',
        postalCode: 'B3J 1Y2',
      },
    ],
    jobs: [
      {
        title: 'Walk-in cooler not holding temperature in the main kitchen',
        description:
          'The cooler is sitting at 8 °C with the door closed. Product has been moved to the annex cooler.',
        typeCode: 'REPAIR',
        status: 'IN_PROGRESS',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'HOURS_FROM_NOW', hours: -7, durationHours: 3 },
            crew: ['JOHN', 'PRIYA'],
            outcome: {
              code: 'NEEDS_PARTS',
              summary:
                'Compressor contactor burnt out. Cooler running on the spare fan at 8 °C; replacement contactor ordered.',
            },
            notes: [
              {
                atHours: -5,
                author: 'JOHN',
                body: 'The chef moved product to the annex as a precaution. Contactor is on the morning delivery.',
              },
            ],
          },
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 1, hour: 7, durationHours: 2 },
            crew: ['SEEDED'],
            notes: [
              {
                atHours: -48,
                author: 'MANAGER',
                body: 'Nadine booked first thing tomorrow and will have the panel open when the crew arrives.',
              },
            ],
          },
        ],
      },
      {
        title: 'Guest room 412 — shower valve replacement',
        description:
          'Both the hot and cold handles were replaced last month; the valve body itself is now leaking behind the wall.',
        typeCode: 'REPAIR',
        status: 'PENDING_REVIEW',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 1, hour: 8, durationHours: 2 },
            crew: ['SEEDED'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Valve body replaced and the wall closed. Hot and cold verified at the fixture.',
            },
            notes: [
              {
                atHours: 1,
                author: 'SEEDED',
                body: 'Ran the shower for ten minutes with the access panel open — no weeping at the new joints.',
              },
            ],
          },
        ],
      },
      {
        title: 'Quarterly fire alarm verification',
        description:
          'Quarterly verification of the annex panel, including the corridor sounders and the stairwell pulls.',
        typeCode: 'INSPECTION',
        status: 'SCHEDULED',
        propertyIndex: 1,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 7, hour: 8, durationHours: 5 },
            crew: ['PRIYA', 'SARAH'],
            notes: [
              {
                atHours: -192,
                author: 'MANAGER',
                body: 'Nadine will post the notice and asked the crew to start on the upper floors.',
              },
            ],
          },
        ],
      },
    ],
  },

  {
    kind: 'COMPANY',
    displayName: 'Summit Ridge School Board',
    legalName: 'Summit Ridge School Board',
    businessName: 'Summit Ridge Schools',
    taxNumber: 'GST-1042-7783',
    email: 'facilities@summitridgeschools.test',
    phone: '+1 403 555 0181',
    billingEmail: 'accounts@summitridgeschools.test',
    billingPhone: '+1 403 555 0182',
    notes:
      'Work at any school must finish before 15:00 and be cleared with the front office on arrival.',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 1980,
    billingAddress: {
      addressLine1: '1400 8 Avenue NW',
      city: 'Calgary',
      province: 'Alberta',
      postalCode: 'T2N 1B9',
    },
    contacts: [
      {
        firstName: 'Alan',
        lastName: 'Whitehorse',
        role: 'Facilities Director',
        email: 'alan.whitehorse@summitridgeschools.test',
        phone: '+1 403 555 0183',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Rosa',
        lastName: 'Mendoza',
        role: 'Maintenance Coordinator',
        email: 'rosa.mendoza@summitridgeschools.test',
        phone: '+1 403 555 0184',
        isJobContact: true,
      },
    ],
    properties: [
      {
        name: 'Summit Ridge Elementary',
        addressLine1: '1400 8 Avenue NW',
        city: 'Calgary',
        province: 'Alberta',
        postalCode: 'T2N 1B9',
        notes: 'Boiler room is through the gym; the caretaker meets contractors at the front office.',
      },
      {
        name: 'Summit Ridge Middle School',
        addressLine1: '2200 Richmond Road SW',
        city: 'Calgary',
        province: 'Alberta',
        postalCode: 'T2T 5C6',
      },
      {
        name: 'Summit Ridge High School',
        addressLine1: '800 32 Street NE',
        city: 'Calgary',
        province: 'Alberta',
        postalCode: 'T2A 7X6',
        notes: 'Rooftop units are reached from the stairwell beside the drama room.',
      },
    ],
    jobs: [
      {
        title: 'Gymnasium rooftop unit — no heat',
        description:
          'The gym unit locks out on high limit and never heats. A tournament is booked in the gym this weekend.',
        typeCode: 'REPAIR',
        status: 'SCHEDULED',
        propertyIndex: 2,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 1, hour: 8, durationHours: 4 },
            crew: ['SARAH', 'JOHN'],
            notes: [
              {
                atHours: -48,
                author: 'MANAGER',
                body: 'Tournament is Saturday. Alan asked for the unit to be proven before the school day ends.',
              },
            ],
          },
        ],
      },
      {
        title: 'Annual boiler inspection — three sites',
        description:
          'Annual inspection of the three school boilers. Certificates are due to the board at the end of the month.',
        typeCode: 'INSPECTION',
        status: 'SCHEDULED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 6, hour: 9, durationHours: 6 },
            crew: ['PRIYA', 'LUC'],
            notes: [
              {
                atHours: -168,
                author: 'MANAGER',
                body: 'Rosa booked all three sites on one day; the caretakers will have the boiler rooms open.',
              },
            ],
          },
        ],
      },
      {
        title: 'Classroom thermostat replacement — wing B',
        description:
          'Twelve thermostats in wing B are reading several degrees off and cannot be adjusted from the BMS.',
        typeCode: 'INSTALLATION',
        status: 'COMPLETED',
        propertyIndex: 1,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 16, hour: 8, durationHours: 5 },
            crew: ['LUC', 'PRIYA'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Twelve thermostats replaced and paired with the BMS; each room verified against the setpoint.',
            },
            notes: [
              {
                atHours: 3,
                author: 'PRIYA',
                body: 'Showed two of the teachers how to adjust the setpoint on the new wall units.',
              },
            ],
          },
        ],
      },
    ],
  },

  {
    kind: 'COMPANY',
    displayName: 'Evergreen Physiotherapy',
    legalName: 'Evergreen Physiotherapy Inc.',
    businessName: 'Evergreen Physiotherapy',
    taxNumber: 'GST-5518-3372',
    email: 'clinic@evergreenphysio.test',
    phone: '+1 250 555 0107',
    billingEmail: 'admin@evergreenphysio.test',
    billingPhone: '+1 250 555 0108',
    notes:
      'Inactive: the clinic changed owners and no new work is being booked. Kept for service history.',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'INACTIVE',
    customerSinceDaysAgo: 690,
    billingAddress: {
      addressLine1: '3020 Cook Street',
      city: 'Victoria',
      province: 'British Columbia',
      postalCode: 'V8X 1A6',
    },
    contacts: [
      {
        firstName: 'Helen',
        lastName: 'Osei',
        role: 'Clinic Owner',
        email: 'helen.osei@evergreenphysio.test',
        phone: '+1 250 555 0109',
        isPrimary: true,
      },
      {
        firstName: 'Megan',
        lastName: 'Clarke',
        role: 'Billing',
        isBillingContact: true,
      },
    ],
    properties: [
      {
        name: 'Evergreen Physiotherapy — Cook Street',
        addressLine1: '3020 Cook Street',
        city: 'Victoria',
        province: 'British Columbia',
        postalCode: 'V8X 1A6',
        notes: 'Treatment rooms are at the back; the exhaust grilles sit above each door.',
      },
    ],
    jobs: [
      {
        title: 'Treatment room exhaust vent cleaning',
        description:
          'Annual cleaning of the four treatment room exhaust grilles and their duct runs.',
        typeCode: 'MAINTENANCE',
        status: 'COMPLETED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 34, hour: 11, durationHours: 2 },
            crew: ['PRIYA'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Grilles and duct runs cleaned; airflow measured at each of the four rooms and recorded.',
            },
            notes: [
              {
                atHours: 2,
                author: 'PRIYA',
                body: 'Airflow improved from 40 to 190 cfm at the far treatment room.',
              },
            ],
          },
        ],
      },
    ],
  },

  {
    kind: 'COMPANY',
    displayName: 'Atlas Fitness Clubs',
    legalName: 'Atlas Fitness Clubs Inc.',
    businessName: 'Atlas Fitness',
    taxNumber: 'GST-8813-4460',
    email: 'facilities@atlasfitness.test',
    phone: '+1 416 555 0198',
    billingEmail: 'accounting@atlasfitness.test',
    billingPhone: '+1 416 555 0199',
    notes:
      'Two clubs. Staff door is on the laneway at Queen West; both clubs share the Liberty Village loading bay.',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 1310,
    billingAddress: {
      addressLine1: '700 Queen Street West',
      city: 'Toronto',
      province: 'Ontario',
      postalCode: 'M6J 1E9',
    },
    contacts: [
      {
        firstName: 'Sam',
        lastName: 'Whitfield',
        role: 'Facilities Manager',
        email: 'sam.whitfield@atlasfitness.test',
        phone: '+1 416 555 0197',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Jo-Anne',
        lastName: 'Peters',
        role: 'Accounting',
        email: 'jo-anne.peters@atlasfitness.test',
        phone: '+1 416 555 0196',
        isBillingContact: true,
      },
    ],
    properties: [
      {
        name: 'Atlas Fitness — Queen West',
        addressLine1: '700 Queen Street West',
        city: 'Toronto',
        province: 'Ontario',
        postalCode: 'M6J 1E9',
        notes: 'Staff door is on the laneway; the locker rooms are one level down.',
      },
      {
        name: 'Atlas Fitness — Liberty Village',
        addressLine1: '171 East Liberty Street',
        city: 'Toronto',
        province: 'Ontario',
        postalCode: 'M6K 3P6',
      },
    ],
    jobs: [
      {
        title: "Men's locker room — intermittent hot water",
        description:
          'Hot water drops out for a few seconds at a time, mostly in the morning. Six showers share the line.',
        typeCode: 'REPAIR',
        status: 'PENDING_REVIEW',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 2, hour: 7, durationHours: 4 },
            crew: ['JOHN', 'PRIYA'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Mixing valve replaced; hot water stable across six fixtures with a ten-minute draw at each.',
            },
            notes: [
              {
                atHours: 3,
                author: 'JOHN',
                body: 'Ran all six showers at once and then one at a time — no temperature swing either way.',
              },
            ],
          },
        ],
      },
      {
        title: 'HVAC filter replacement — both clubs',
        description:
          'Quarterly filter change on the rooftop units at both clubs, including the two make-up air units.',
        typeCode: 'MAINTENANCE',
        status: 'SCHEDULED',
        propertyIndex: 1,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 4, hour: 7, durationHours: 4 },
            crew: ['SARAH', 'PRIYA'],
            notes: [
              {
                atHours: -120,
                author: 'MANAGER',
                body: 'Sam will have the roof hatch unlocked and a cart for the used filters.',
              },
            ],
          },
        ],
      },
      {
        title: 'Spin studio ceiling fan installation',
        description:
          'Install four ceiling fans in the spin studio. The ceiling was scheduled for painting first.',
        typeCode: 'INSTALLATION',
        status: 'CANCELED',
        propertyIndex: 1,
        owner: true,
        cancellationNote:
          'The club postponed the fan installation until the ceiling painting is finished.',
        visits: [
          {
            status: 'CANCELED',
            schedule: { kind: 'DAYS_AGO', days: 5, hour: 8, durationHours: 3 },
            crew: ['JOHN'],
            cancellation: {
              reasonCode: 'CUSTOMER_RESCHEDULED',
              note: 'The club asked to postpone until the ceiling is painted.',
            },
            notes: [
              {
                atHours: -26,
                author: 'MANAGER',
                body: 'Sam moved the painting ahead of the fan install; we will be asked to rebook once it is dry.',
              },
            ],
          },
        ],
      },
    ],
  },

  {
    kind: 'INDIVIDUAL',
    displayName: 'Marie-Claude Fortin',
    firstName: 'Marie-Claude',
    lastName: 'Fortin',
    email: 'marie-claude.fortin@servora.test',
    phone: '+1 418 555 0123',
    notes:
      'Préfère être appelée avant l’arrivée. Accès par la porte de côté, près de la remise.',
    preferredContactMethod: 'PHONE',
    language: 'fr-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 145,
    billingAddress: {
      addressLine1: '1180 avenue Cartier',
      city: 'Québec',
      province: 'Québec',
      postalCode: 'G1R 2S9',
    },
    contacts: [],
    properties: [
      {
        name: 'Maison Fortin',
        addressLine1: '1180 avenue Cartier',
        city: 'Québec',
        province: 'Québec',
        postalCode: 'G1R 2S9',
        notes: 'Le chauffe-eau est au sous-sol, derrière la fournaise.',
      },
    ],
    jobs: [
      {
        title: "Chauffe-eau — plus d'eau chaude",
        description:
          "Le chauffe-eau ne fournit plus d'eau chaude depuis la veille; aucun voyant allumé.",
        typeCode: 'REPAIR',
        status: 'COMPLETED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 4, hour: 8, durationHours: 2 },
            crew: ['LUC'],
            outcome: {
              code: 'RESOLVED',
              summary:
                "Élément chauffant et thermostat remplacés; eau chaude rétablie à 49 °C.",
            },
            notes: [
              {
                atHours: 2,
                author: 'LUC',
                body: "Tension et mise à la terre vérifiées; la cliente a confirmé l'eau chaude en soirée.",
              },
            ],
          },
        ],
      },
    ],
  },

  {
    kind: 'COMPANY',
    displayName: 'Bluebird Grocers',
    legalName: 'Bluebird Grocers Ltd.',
    businessName: 'Bluebird Grocers',
    taxNumber: 'GST-6620-1195',
    email: 'maintenance@bluebirdgrocers.test',
    phone: '+1 306 555 0121',
    billingEmail: 'payables@bluebirdgrocers.test',
    billingPhone: '+1 306 555 0122',
    notes:
      'Night work only at the store; the last customer leaves at 22:00. Warehouse is keyed differently.',
    preferredContactMethod: 'PHONE',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 790,
    billingAddress: {
      addressLine1: '2934 Gordon Road',
      city: 'Regina',
      province: 'Saskatchewan',
      postalCode: 'S4S 6H2',
    },
    contacts: [
      {
        firstName: 'Kevin',
        lastName: "O'Brien",
        role: 'Store Operations Manager',
        email: 'kevin.obrien@bluebirdgrocers.test',
        phone: '+1 306 555 0123',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Lisa',
        lastName: 'Tran',
        role: 'Administration',
        email: 'lisa.tran@bluebirdgrocers.test',
        phone: '+1 306 555 0124',
        isBillingContact: true,
      },
    ],
    properties: [
      {
        name: 'Bluebird Grocers — Southland',
        addressLine1: '2934 Gordon Road',
        city: 'Regina',
        province: 'Saskatchewan',
        postalCode: 'S4S 6H2',
        notes: 'Refrigeration rack is behind the dairy wall; night manager opens the panel.',
      },
      {
        name: 'Bluebird Grocers — Warehouse',
        addressLine1: '1050 Winnipeg Street',
        city: 'Regina',
        province: 'Saskatchewan',
        postalCode: 'S4R 8P8',
      },
    ],
    jobs: [
      {
        title: 'Refrigeration rack alarm — aisle 3',
        description:
          'The rack has been alarming since this morning and the aisle 3 case is running warm.',
        typeCode: 'REPAIR',
        status: 'IN_PROGRESS',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'ON_SITE',
            schedule: { kind: 'HOURS_FROM_NOW', hours: -1, durationHours: 3 },
            crew: ['SEEDED'],
            notes: [
              {
                atHours: -1,
                author: 'SEEDED',
                body: 'Night manager has the panel open. Checking the condenser fan and the suction pressure.',
              },
            ],
          },
        ],
      },
      {
        title: 'Annual refrigeration leak inspection',
        description:
          'Annual leak inspection across the store rack and the two walk-in freezers. Requires the store to be closed.',
        typeCode: 'INSPECTION',
        status: 'SCHEDULED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 8, hour: 22, durationHours: 4 },
            crew: ['JOHN', 'LUC'],
            notes: [
              {
                atHours: -216,
                author: 'MANAGER',
                body: 'Kevin confirmed the store closes at 22:00. Park at the back and sign in with the night manager.',
              },
            ],
          },
        ],
      },
      {
        title: 'Automatic entrance door sensor replacement',
        description:
          'The entrance door is slow to open and occasionally closes on a customer. Sensor was flagged as faulty last month.',
        typeCode: 'REPAIR',
        status: 'COMPLETED',
        propertyIndex: 1,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 10, hour: 13, durationHours: 2 },
            crew: ['SARAH'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Sensor replaced and tuned; the door opens and holds from both approach directions.',
            },
            notes: [
              {
                atHours: 1,
                author: 'SARAH',
                body: 'Cycled the door forty times with a cart and walked it from both sides.',
              },
            ],
          },
        ],
      },
    ],
  },

  {
    kind: 'COMPANY',
    displayName: 'Maple Ridge Office Park',
    legalName: 'Maple Ridge Office Park Inc.',
    businessName: 'Maple Ridge Office Park',
    taxNumber: 'GST-3390-7741',
    email: 'admin@mapleridgeoffice.test',
    phone: '+1 905 555 0131',
    billingEmail: 'billing@mapleridgeoffice.test',
    billingPhone: '+1 905 555 0132',
    notes:
      'Two office buildings. Rooftop access is through the penthouse stairwell; keys are at the security desk.',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 610,
    billingAddress: {
      addressLine1: '2450 Meadowvale Boulevard',
      addressLine2: 'Suite 100',
      city: 'Mississauga',
      province: 'Ontario',
      postalCode: 'L5N 6M1',
    },
    contacts: [
      {
        firstName: 'Owen',
        lastName: 'Clarke',
        role: 'Property Administrator',
        email: 'owen.clarke@mapleridgeoffice.test',
        phone: '+1 905 555 0133',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Hana',
        lastName: 'Sato',
        role: 'Billing Coordinator',
        email: 'hana.sato@mapleridgeoffice.test',
        phone: '+1 905 555 0134',
        isBillingContact: true,
      },
    ],
    properties: [
      {
        name: 'Maple Ridge — Building 300',
        addressLine1: '2450 Meadowvale Boulevard',
        city: 'Mississauga',
        province: 'Ontario',
        postalCode: 'L5N 6M1',
        notes: 'Suite 302 is on the top floor; the tenant leaves a key with the security desk.',
      },
      {
        name: 'Maple Ridge — Building 400',
        addressLine1: '2500 Meadowvale Boulevard',
        city: 'Mississauga',
        province: 'Ontario',
        postalCode: 'L5N 6M1',
      },
    ],
    jobs: [
      {
        title: 'Suite 302 rooftop unit short cycling',
        description:
          'The unit starts and stops every few minutes and the suite never reaches its setpoint.',
        typeCode: 'REPAIR',
        status: 'PENDING_REVIEW',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'HOURS_FROM_NOW', hours: -5, durationHours: 3 },
            crew: ['SEEDED'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Return-air sensor relocated out of the supply stream and filters replaced; the unit now runs a full cycle.',
            },
            notes: [
              {
                atHours: -3,
                author: 'SEEDED',
                body: 'Tenant confirmed the suite held its setpoint for the rest of the afternoon.',
              },
            ],
          },
        ],
      },
      {
        title: 'Common area lighting upgrade — building 400',
        description:
          'Replace the corridor and lobby fixtures with LED panels. Scope to be confirmed with the property administrator.',
        typeCode: 'INSTALLATION',
        status: 'NEW',
        propertyIndex: 1,
        owner: false,
        visits: [
          {
            status: 'DRAFT',
            schedule: null,
            crew: [],
          },
        ],
      },
      {
        title: 'Parking garage sump pump inspection',
        description:
          'Inspect and test the two garage sump pumps before the fall rain. One float has been sticking.',
        typeCode: 'MAINTENANCE',
        status: 'SCHEDULED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 3, hour: 9, durationHours: 2 },
            crew: ['LUC'],
            notes: [
              {
                atHours: -96,
                author: 'MANAGER',
                body: 'Owen will have the pit lids off and the wet vac beside the lower pit.',
              },
            ],
          },
        ],
      },
    ],
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Noah Singh',
    firstName: 'Noah',
    lastName: 'Singh',
    email: 'noah.singh@servora.test',
    phone: '+1 905 555 0105',
    notes: 'New request taken by phone. Prefers a text the day before.',
    preferredContactMethod: 'SMS',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 40,
    billingAddress: {
      addressLine1: '3475 Fieldgate Drive',
      city: 'Mississauga',
      province: 'Ontario',
      postalCode: 'L4X 2J6',
    },
    contacts: [],
    properties: [
      {
        name: 'Noah Singh — Residence',
        addressLine1: '3475 Fieldgate Drive',
        city: 'Mississauga',
        province: 'Ontario',
        postalCode: 'L4X 2J6',
        notes: 'Furnace and humidifier are in the basement; the side gate is unlocked.',
      },
    ],
    jobs: [
      {
        title: 'Furnace maintenance before winter',
        description:
          'Booked by phone for a furnace cleaning and a humidifier check before the heating season.',
        typeCode: 'MAINTENANCE',
        status: 'NEW',
        propertyIndex: 0,
        owner: false,
        visits: [],
      },
    ],
  },

  {
    kind: 'COMPANY',
    displayName: 'Lakeside Condominium Corporation',
    legalName: 'Lakeside Condominium Corporation No. 118',
    businessName: 'Lakeside Condominiums',
    taxNumber: 'GST-7756-2003',
    email: 'board@lakesidecondos.test',
    phone: '+1 613 555 0151',
    billingEmail: 'treasurer@lakesidecondos.test',
    billingPhone: '+1 613 555 0152',
    notes:
      'Two towers. Garage access needs a fob and the board has to be told which sub-contractor is coming.',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 1690,
    billingAddress: {
      addressLine1: '250 Lett Street',
      city: 'Ottawa',
      province: 'Ontario',
      postalCode: 'K1R 7R7',
    },
    contacts: [
      {
        firstName: 'Gilles',
        lastName: 'Poirier',
        role: 'Board President',
        email: 'gilles.poirier@lakesidecondos.test',
        phone: '+1 613 555 0153',
        isPrimary: true,
      },
      {
        firstName: 'Michelle',
        lastName: 'Adams',
        role: 'Property Manager',
        email: 'michelle.adams@lakesidecondos.test',
        phone: '+1 613 555 0154',
        isJobContact: true,
      },
    ],
    properties: [
      {
        name: 'Lakeside Condominiums — Tower 1',
        addressLine1: '250 Lett Street',
        city: 'Ottawa',
        province: 'Ontario',
        postalCode: 'K1R 7R7',
        notes: 'Garage exhaust fans are above the P1 ramp; access through the garage elevator.',
      },
      {
        name: 'Lakeside Condominiums — Tower 2',
        addressLine1: '252 Lett Street',
        city: 'Ottawa',
        province: 'Ontario',
        postalCode: 'K1R 7R7',
      },
    ],
    jobs: [
      {
        title: 'Garage exhaust fan — noise complaint from unit 704',
        description:
          'The owner in 704 reports a rhythmic noise through the night. Fan runs against a closed damper.',
        typeCode: 'SERVICE_CALL',
        status: 'IN_PROGRESS',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'NO_SHOW',
            schedule: { kind: 'DAYS_AGO', days: 1, hour: 13, durationHours: 2 },
            crew: ['SARAH'],
            notes: [
              {
                atHours: 0,
                author: 'SARAH',
                body: 'Attended at 13:05 but the garage fob was not programmed and nobody from the board answered. Left after twenty minutes.',
              },
            ],
          },
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 3, hour: 13, durationHours: 2 },
            crew: ['SARAH'],
            notes: [
              {
                atHours: -96,
                author: 'MANAGER',
                body: 'Board has programmed a fob for us and Gilles will meet the technician at the P1 ramp.',
              },
            ],
          },
        ],
      },
      {
        title: 'Fire pump annual test',
        description:
          'Annual flow test on the Tower 2 fire pump with the sprinkler contractor present.',
        typeCode: 'INSPECTION',
        status: 'SCHEDULED',
        propertyIndex: 1,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 11, hour: 9, durationHours: 4 },
            crew: ['PRIYA', 'JOHN'],
            notes: [
              {
                atHours: -288,
                author: 'MANAGER',
                body: 'Michelle posted the test notice and booked the sprinkler contractor for the same morning.',
              },
            ],
          },
        ],
      },
    ],
  },
];

/** A Visit's resolved window: what `BR-072` stores as the internal scheduled start and end. */
export interface ResolvedSeedWindow {
  readonly scheduledStart: Date;
  readonly scheduledEnd: Date;
}

/** Shifts an instant by whole or fractional hours; the dataset's own way of placing an event. */
export function addHours(value: Date, hours: number): Date {
  return new Date(value.getTime() + hours * 60 * 60 * 1000);
}

/** Midnight at the start of the seed's own day, in the machine's local time. */
function startOfLocalDay(now: Date): Date {
  return new Date(now.getFullYear(), now.getMonth(), now.getDate());
}

/**
 * Resolves a Visit's window against the moment the seed runs.
 *
 * `DAYS_AGO` and `IN_DAYS` are counted from the local midnight of the seed's own day, so a Visit placed
 * a day or more away is always strictly past or strictly future however late in the day the seed runs —
 * which is what lets the dataset assert that a future field attempt is never finished work.
 */
export function resolveSeedWindow(
  schedule: SeedSchedule,
  now: Date,
): ResolvedSeedWindow {
  switch (schedule.kind) {
    case 'DAYS_AGO': {
      const scheduledStart = addHours(
        startOfLocalDay(now),
        -schedule.days * 24 + schedule.hour,
      );
      return {
        scheduledStart,
        scheduledEnd: addHours(scheduledStart, schedule.durationHours),
      };
    }
    case 'IN_DAYS': {
      const scheduledStart = addHours(
        startOfLocalDay(now),
        schedule.days * 24 + schedule.hour,
      );
      return {
        scheduledStart,
        scheduledEnd: addHours(scheduledStart, schedule.durationHours),
      };
    }
    case 'HOURS_FROM_NOW': {
      const scheduledStart = addHours(now, schedule.hours);
      return {
        scheduledStart,
        scheduledEnd: addHours(scheduledStart, schedule.durationHours),
      };
    }
  }
}


/** A Visit status a field attempt may carry while its window is still ahead (`BR-074`). */
const FUTURE_VISIT_STATUSES: ReadonlySet<SeedVisitStatus> = new Set([
  'DRAFT',
  'SCHEDULED',
  'CANCELED',
]);

/** A Visit status that reports work under way, so its window has already started. */
const IN_FLIGHT_VISIT_STATUSES: ReadonlySet<SeedVisitStatus> = new Set([
  'EN_ROUTE',
  'ON_SITE',
  'IN_PROGRESS',
]);

/** A Visit status where the field attempt is over (`BR-074`). */
const TERMINAL_VISIT_STATUSES: ReadonlySet<SeedVisitStatus> = new Set([
  'COMPLETED',
  'CANCELED',
  'NO_SHOW',
]);

/** A Visit status that means somebody actually worked, which is what starts a Job (`BR-058`). */
const WORKED_VISIT_STATUSES: ReadonlySet<SeedVisitStatus> = new Set([
  'EN_ROUTE',
  'ON_SITE',
  'IN_PROGRESS',
  'COMPLETED',
]);

const ONE_MINUTE = 60 * 1000;

/**
 * Keeps a history's instants strictly ordered and entirely in the past.
 *
 * A seeded history is walked backwards from its last event, so the event that decides the record's
 * current state keeps the place the plan gave it and the events before it are pulled earlier — never the
 * other way round. The last event is itself capped at "a minute ago", which is why a Visit scheduled
 * months ahead still records a booking that happened yesterday rather than one in the future.
 */
export function orderedPastEventTimes(
  candidates: readonly Date[],
  now: Date,
): readonly Date[] {
  const events: Date[] = [];
  let limit = now.getTime() - ONE_MINUTE;
  for (let index = candidates.length - 1; index >= 0; index -= 1) {
    const candidate = candidates[index];
    const time = Math.min(candidate?.getTime() ?? limit, limit);
    events[index] = new Date(time);
    limit = time - ONE_MINUTE;
  }
  return events;
}

/** Shifts a candidate into the past: at least `minutesBeforeNow` before the seed ran. */
export function pastInstant(
  candidate: Date,
  now: Date,
  minutesBeforeNow: number,
): Date {
  return new Date(
    Math.min(candidate.getTime(), now.getTime() - minutesBeforeNow * ONE_MINUTE),
  );
}

export interface ResolvedSeedNote {
  readonly plan: SeedNotePlan;
  readonly recordedAt: Date;
}

export interface ResolvedSeedVisit {
  readonly plan: SeedVisitPlan;
  /** `null` only for a `DRAFT` Visit. */
  readonly window: ResolvedSeedWindow | null;
  /** One instant per step of `VISIT_STATUS_PATHS[status].path`, in the same order. */
  readonly statusEventTimes: readonly Date[];
  /** When the outcome was recorded, for a `COMPLETED` Visit only. */
  readonly outcomeRecordedAt: Date | null;
  readonly notes: readonly ResolvedSeedNote[];
}

export interface ResolvedSeedJob {
  readonly plan: SeedJobPlan;
  readonly visits: readonly ResolvedSeedVisit[];
  /** One instant per step of `JOB_STATUS_PATHS[status]`, in the same order. */
  readonly statusEventTimes: readonly Date[];
  /** The Job's `created_at`: the instant its `NEW` status was recorded. */
  readonly createdAt: Date;
}

/** Resolves one Visit's window, history instants, outcome instant and note instants. */
export function resolveSeedVisit(
  visit: SeedVisitPlan,
  now: Date,
): ResolvedSeedVisit {
  const window = visit.schedule ? resolveSeedWindow(visit.schedule, now) : null;
  return {
    plan: visit,
    window,
    statusEventTimes:
      window === null
        ? []
        : orderedPastEventTimes(
            VISIT_STATUS_PATHS[visit.status].path.map((to) =>
              visitEventCandidate(to, window),
            ),
            now,
          ),
    outcomeRecordedAt:
      visit.status === 'COMPLETED' && window !== null
        ? window.scheduledEnd
        : null,
    notes:
      window === null
        ? []
        : (visit.notes ?? []).map((note) => ({
            plan: note,
            recordedAt: addHours(window.scheduledStart, note.atHours),
          })),
  };
}

/** Where one step of a Visit's path happens relative to the Visit's window (`BR-074`). */
function visitEventCandidate(
  to: SeedVisitStatus,
  window: ResolvedSeedWindow,
): Date {
  switch (to) {
    case 'DRAFT':
      return addHours(window.scheduledStart, -72);
    case 'SCHEDULED':
      return addHours(window.scheduledStart, -48);
    case 'EN_ROUTE':
      return addHours(window.scheduledStart, -0.25);
    case 'ON_SITE':
      return addHours(window.scheduledStart, 0.25);
    case 'IN_PROGRESS':
      return addHours(window.scheduledStart, 0.5);
    case 'COMPLETED':
      return window.scheduledEnd;
    case 'CANCELED':
      return addHours(window.scheduledStart, -24);
    case 'NO_SHOW':
      return addHours(window.scheduledEnd, 1);
  }
}

/** Resolves a Job's own history and every Visit it holds. */
export function resolveSeedJob(job: SeedJobPlan, now: Date): ResolvedSeedJob {
  const visits = job.visits.map((visit) => resolveSeedVisit(visit, now));
  const starts = visits
    .map((visit) => visit.window?.scheduledStart)
    .filter((start): start is Date => start !== undefined);
  const ends = visits
    .map((visit) => visit.window?.scheduledEnd)
    .filter((end): end is Date => end !== undefined);
  const workedStarts = visits
    .filter(
      (visit) =>
        visit.window !== null && WORKED_VISIT_STATUSES.has(visit.plan.status),
    )
    .map((visit) => visit.window?.scheduledStart)
    .filter((start): start is Date => start !== undefined);

  const statusEventTimes = jobStatusEventTimes({
    jobStatus: job.status,
    earliestVisitStart: minimumDate(starts),
    workStartedAt: minimumDate(workedStarts),
    latestVisitEnd: maximumDate(ends),
    now,
  });

  return {
    plan: job,
    visits,
    statusEventTimes,
    createdAt: statusEventTimes[0] ?? now,
  };
}

interface SeedJobTimeline {
  readonly jobStatus: SeedJobStatus;
  readonly earliestVisitStart: Date | null;
  readonly workStartedAt: Date | null;
  readonly latestVisitEnd: Date | null;
  readonly now: Date;
}

/** Where each step of a Job's path happened, given what its Visits did (`BR-058`, `BR-080`). */
function jobStatusEventTimes(timeline: SeedJobTimeline): readonly Date[] {
  const candidates = JOB_STATUS_PATHS[timeline.jobStatus].map((to) => {
    switch (to) {
      case 'NEW':
        return addHours(timeline.earliestVisitStart ?? timeline.now, -72);
      case 'SCHEDULED':
        return addHours(timeline.earliestVisitStart ?? timeline.now, -48);
      case 'IN_PROGRESS':
        return timeline.workStartedAt ?? addHours(timeline.now, -6);
      case 'PENDING_REVIEW':
        return addHours(timeline.latestVisitEnd ?? timeline.now, 1);
      case 'COMPLETED':
        return addHours(timeline.latestVisitEnd ?? timeline.now, 2);
      case 'CANCELED':
        return addHours(timeline.earliestVisitStart ?? timeline.now, -24);
    }
  });
  return orderedPastEventTimes(candidates, timeline.now);
}

function minimumDate(values: readonly Date[]): Date | null {
  if (values.length === 0) {
    return null;
  }
  return new Date(Math.min(...values.map((value) => value.getTime())));
}

function maximumDate(values: readonly Date[]): Date | null {
  if (values.length === 0) {
    return null;
  }
  return new Date(Math.max(...values.map((value) => value.getTime())));
}


/** One technician's resolved booking, used to check that nobody is double-booked (`BR-070`). */
interface SeedAssignment {
  readonly technician: SeedTechnicianKey;
  readonly window: ResolvedSeedWindow;
  readonly label: string;
}

/**
 * Refuses the dataset unless it describes work the product could actually hold.
 *
 * It is called by the seeding command **before** anything is written, and by the unit test that covers
 * this module. The rules it applies are the ones a wrong edit would otherwise turn into plausible
 * looking but impossible demo data — above all that a field attempt which has not happened yet is never
 * presented as finished work.
 */
export function assertSeedScenariosAreCoherent(
  customers: readonly SeedCustomerPlan[],
  now: Date,
): void {
  const assignments: SeedAssignment[] = [];

  for (const customer of customers) {
    assertCustomerIsCoherent(customer, now, assignments);
  }
  assertNoTechnicianIsDoubleBooked(assignments);
}

function fail(message: string): never {
  throw new Error(`The development seed dataset is not coherent: ${message}`);
}

function assertCustomerIsCoherent(
  customer: SeedCustomerPlan,
  now: Date,
  assignments: SeedAssignment[],
): void {
  const label = `customer "${customer.displayName}"`;

  if (customer.customerSinceDaysAgo < 1) {
    fail(`${label} must have been created at least a day ago.`);
  }
  if (customer.kind === 'COMPANY') {
    if (!customer.legalName || customer.legalName.trim().length === 0) {
      fail(`${label} is a COMPANY and needs a legal name (BR-023).`);
    }
    const primaries = customer.contacts.filter(
      (contact) => contact.isPrimary === true,
    );
    if (primaries.length > 1) {
      fail(
        `${label} has ${primaries.length} primary contacts; at most one is allowed (BR-095).`,
      );
    }
  } else {
    if (!customer.firstName || !customer.lastName) {
      fail(
        `${label} is an INDIVIDUAL and needs a first and last name (BR-023).`,
      );
    }
    if (customer.contacts.length > 0) {
      fail(
        `${label} is an INDIVIDUAL: the person is their own primary and holds no contact person row (BR-095).`,
      );
    }
  }

  customer.jobs.forEach((job, jobIndex) => {
    const jobLabel = `${label}, job ${jobIndex + 1} "${job.title}"`;
    if (
      job.propertyIndex !== null &&
      (job.propertyIndex < 0 || job.propertyIndex >= customer.properties.length)
    ) {
      fail(
        `${jobLabel} refers to a Property index outside its Customer's list.`,
      );
    }
    if (job.propertyIndex === null && job.visits.length > 0) {
      fail(
        `${jobLabel} has no Property: a Visit cannot be scheduled without one (BR-071, BR-072).`,
      );
    }

    const resolved = resolveSeedJob(job, now);
    assertEventTimesAreOrdered(
      resolved.statusEventTimes,
      now,
      jobLabel,
      'Job status history',
    );
    if (resolved.createdAt.getTime() > now.getTime()) {
      fail(`${jobLabel} would be created in the future.`);
    }

    resolved.visits.forEach((visit, visitIndex) => {
      const visitLabel = `${jobLabel}, visit ${visitIndex + 1}`;
      assertVisitIsCoherent(visit, now, visitLabel);
      if (visit.window !== null && visit.plan.status !== 'CANCELED') {
        for (const technician of visit.plan.crew) {
          assignments.push({
            technician,
            window: visit.window,
            label: visitLabel,
          });
        }
      }
    });

    assertJobStatusMatchesItsVisits(job, resolved, jobLabel);
  });
}


function assertVisitIsCoherent(
  resolved: ResolvedSeedVisit,
  now: Date,
  label: string,
): void {
  const status = resolved.plan.status;
  const { window } = resolved;

  if (status === 'DRAFT') {
    if (window !== null) {
      fail(
        `${label} is DRAFT but carries a window; a draft is unscheduled (BR-072).`,
      );
    }
    if (resolved.plan.crew.length > 0) {
      fail(
        `${label} is DRAFT but carries a crew; a draft may exist without one (BR-068).`,
      );
    }
  } else if (window === null) {
    fail(`${label} is ${status} without a window (BR-072).`);
  }

  if (window !== null) {
    const started = window.scheduledStart.getTime();
    const ended = window.scheduledEnd.getTime();
    if (started > now.getTime() && !FUTURE_VISIT_STATUSES.has(status)) {
      // The rule this dataset exists to keep: a field attempt that has not happened yet is never
      // presented as work that has been done.
      fail(
        `${label} is scheduled in the future and cannot already be ${status}; a Visit that has not happened yet is DRAFT, SCHEDULED or CANCELED (BR-074).`,
      );
    }
    if (status === 'COMPLETED' && ended >= now.getTime()) {
      fail(`${label} is COMPLETED but its window has not finished yet.`);
    }
    if (status === 'NO_SHOW' && ended >= now.getTime()) {
      fail(`${label} is NO_SHOW but its window has not finished yet (BR-074).`);
    }
    if (IN_FLIGHT_VISIT_STATUSES.has(status) && started > now.getTime()) {
      fail(`${label} is ${status} but has not started yet (BR-074).`);
    }
  }

  if (status === 'COMPLETED') {
    if (resolved.plan.outcome === undefined) {
      fail(`${label} is COMPLETED without an outcome (BR-077).`);
    }
    if (resolved.plan.outcome.summary.trim().length === 0) {
      fail(`${label} records an outcome with an empty summary (BR-077).`);
    }
  } else if (resolved.plan.outcome !== undefined) {
    fail(
      `${label} is ${status} but carries an outcome; only a completed Visit has one (BR-077).`,
    );
  }

  if (status === 'CANCELED') {
    if (resolved.plan.cancellation === undefined) {
      fail(`${label} is CANCELED without a structured reason (BR-076).`);
    }
  } else if (resolved.plan.cancellation !== undefined) {
    fail(`${label} is ${status} but carries a cancellation reason (BR-076).`);
  }

  if (status !== 'DRAFT' && resolved.plan.crew.length === 0) {
    fail(
      `${label} is ${status} without a crew; a scheduled field attempt needs one (BR-072).`,
    );
  }
  if (new Set(resolved.plan.crew).size !== resolved.plan.crew.length) {
    fail(`${label} assigns the same technician twice (BR-068).`);
  }
  if (
    resolved.plan.removedTechnician !== undefined &&
    resolved.plan.crew.includes(resolved.plan.removedTechnician)
  ) {
    fail(
      `${label} records ${resolved.plan.removedTechnician} as removed but still carries them on the crew (BR-069).`,
    );
  }

  assertEventTimesAreOrdered(
    resolved.statusEventTimes,
    now,
    label,
    'Visit status history',
  );

  for (const note of resolved.notes) {
    if (note.recordedAt.getTime() > now.getTime()) {
      fail(`${label} has a note recorded in the future.`);
    }
    if (
      note.plan.author !== 'MANAGER' &&
      !resolved.plan.crew.includes(note.plan.author)
    ) {
      fail(
        `${label} has a note by ${note.plan.author}, who is not on its crew (BR-093).`,
      );
    }
    if (note.plan.body.trim().length === 0) {
      fail(`${label} has an empty note.`);
    }
  }
}


/**
 * A Job's status must agree with the work its Visits describe (`BR-058`, `BR-061`, `BR-062`).
 *
 * Without this the demo could show a job awaiting review over an open Visit, or a completed Job whose
 * field work is still running — states the API refuses to create.
 */
function assertJobStatusMatchesItsVisits(
  job: SeedJobPlan,
  resolved: ResolvedSeedJob,
  label: string,
): void {
  const statuses = job.visits.map((visit) => visit.status);
  const open = statuses.filter(
    (status) => !TERMINAL_VISIT_STATUSES.has(status),
  );

  switch (job.status) {
    case 'NEW':
      if (statuses.some((status) => status !== 'DRAFT')) {
        fail(`${label} is NEW but holds started or scheduled work (BR-058).`);
      }
      break;
    case 'SCHEDULED':
      if (!statuses.includes('SCHEDULED')) {
        fail(
          `${label} is SCHEDULED without a scheduled Visit (BR-058, BR-060).`,
        );
      }
      if (
        statuses.some(
          (status) =>
            status === 'DRAFT' || IN_FLIGHT_VISIT_STATUSES.has(status),
        )
      ) {
        fail(
          `${label} is SCHEDULED while a Visit is still a draft or already under way (BR-058).`,
        );
      }
      break;
    case 'IN_PROGRESS':
      if (job.visits.length === 0) {
        fail(`${label} is IN_PROGRESS without any Visit (BR-058).`);
      }
      if (statuses.includes('DRAFT')) {
        fail(`${label} is IN_PROGRESS but still holds a draft Visit (BR-058).`);
      }
      break;
    case 'PENDING_REVIEW': {
      if (open.length > 0) {
        fail(
          `${label} awaits review while ${open.length} Visit(s) are still open (BR-061).`,
        );
      }
      const completed = resolved.visits
        .filter((visit) => visit.plan.status === 'COMPLETED')
        .sort(
          (left, right) =>
            (left.window?.scheduledEnd.getTime() ?? 0) -
            (right.window?.scheduledEnd.getTime() ?? 0),
        );
      const latest = completed.at(-1);
      if (latest === undefined) {
        fail(`${label} awaits review without a completed Visit (BR-061).`);
      } else if (latest.plan.outcome?.code !== 'RESOLVED') {
        fail(
          `${label} awaits review but its latest completed Visit did not resolve the Job (BR-061, BR-078).`,
        );
      }
      break;
    }
    case 'COMPLETED':
      if (open.length > 0) {
        fail(
          `${label} is COMPLETED while ${open.length} Visit(s) are still open (BR-062).`,
        );
      }
      break;
    case 'CANCELED':
      if (statuses.some((status) => IN_FLIGHT_VISIT_STATUSES.has(status))) {
        fail(`${label} is CANCELED while a Visit is under way (BR-064).`);
      }
      break;
  }
}

/** Every seeded history is strictly ordered and entirely in the past (`BR-033`, `BR-067`). */
function assertEventTimesAreOrdered(
  times: readonly Date[],
  now: Date,
  label: string,
  history: string,
): void {
  let previous = Number.NEGATIVE_INFINITY;
  for (const time of times) {
    if (time.getTime() <= previous) {
      fail(`${label}: ${history} would not be in order.`);
    }
    if (time.getTime() > now.getTime()) {
      fail(`${label}: ${history} would contain an event in the future.`);
    }
    previous = time.getTime();
  }
}

/**
 * No technician is booked on two overlapping Visits (`BR-070`).
 *
 * `BR-070` makes an overlap a warning rather than a prohibition, so an accidental one would be a
 * mistake in the data rather than a scenario the demo means to present.
 */
function assertNoTechnicianIsDoubleBooked(
  assignments: readonly SeedAssignment[],
): void {
  for (let left = 0; left < assignments.length; left += 1) {
    for (let right = left + 1; right < assignments.length; right += 1) {
      const first = assignments[left];
      const second = assignments[right];
      if (
        first === undefined ||
        second === undefined ||
        first.technician !== second.technician
      ) {
        continue;
      }
      const overlaps =
        first.window.scheduledStart.getTime() <
          second.window.scheduledEnd.getTime() &&
        second.window.scheduledStart.getTime() <
          first.window.scheduledEnd.getTime();
      if (overlaps) {
        fail(
          `${first.technician} is assigned to overlapping Visits: ${first.label} and ${second.label} (BR-070).`,
        );
      }
    }
  }
}

