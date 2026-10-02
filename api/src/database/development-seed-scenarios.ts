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
  'SEEDED' | (typeof SEED_TECHNICIAN_MEMBER_TEMPLATES)[number]['key'];

export type SeedCustomerKind = 'INDIVIDUAL' | 'COMPANY';
export type SeedCustomerStatus = 'ACTIVE' | 'INACTIVE';
export type SeedContactMethod = 'EMAIL' | 'PHONE' | 'SMS' | 'NONE';
export type SeedLanguage = 'en-CA' | 'fr-CA';
export type SeedJobStatus = 'NEW' | 'ACTIVE' | 'COMPLETED' | 'CANCELED';
export type SeedJobTypeCode =
  'REPAIR' | 'MAINTENANCE' | 'INSTALLATION' | 'INSPECTION' | 'SERVICE_CALL';
export type SeedVisitStatus =
  | 'DRAFT'
  | 'SCHEDULED'
  | 'EN_ROUTE'
  | 'ON_SITE'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELED';
export type SeedVisitOutcomeCode =
  'RESOLVED' | 'NEEDS_FOLLOW_UP' | 'NEEDS_PARTS' | 'UNABLE_TO_COMPLETE';
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
 * The path is a sequence of statuses the Visit really passed through, written as append-only history, so
 * the seeded history is one the lifecycle could have produced. A Visit the API creates directly in a
 * status — `POST /jobs/:id/visits` schedules one — has no earlier event, so its path begins at that
 * status and the first recorded row's previous status is `null`, the same shape `BR-094` gives a Job's
 * initial `NEW`. `DRAFT` is a stored, unscheduled attempt: nothing has happened to it yet.
 *
 * `CANCELED` carries the structured reason `BR-076` requires; `OTHER` is avoided because it also
 * requires an explanation the demo data does not have to invent.
 */
export const VISIT_STATUS_PATHS: Record<
  SeedVisitStatus,
  {
    readonly path: readonly SeedVisitStatus[];
    readonly cancellationReasonCode?: SeedVisitCancellationReason;
  }
> = {
  DRAFT: { path: [] },
  SCHEDULED: { path: ['SCHEDULED'] },
  EN_ROUTE: { path: ['SCHEDULED', 'EN_ROUTE'] },
  ON_SITE: { path: ['SCHEDULED', 'ON_SITE'] },
  IN_PROGRESS: { path: ['SCHEDULED', 'IN_PROGRESS'] },
  COMPLETED: { path: ['SCHEDULED', 'IN_PROGRESS', 'COMPLETED'] },
  CANCELED: {
    path: ['SCHEDULED', 'CANCELED'],
    cancellationReasonCode: 'CUSTOMER_RESCHEDULED',
  },
};

/**
 * How a Job reached the status it is seeded with (`BR-058`).
 *
 * A Job's status history is what the Activity read projects (`BR-080`), so it is a path the lifecycle
 * permits rather than a single row invented to match the current status.
 */
export const JOB_STATUS_PATHS: Record<SeedJobStatus, readonly SeedJobStatus[]> =
  {
    NEW: ['NEW'],
    ACTIVE: ['NEW', 'ACTIVE'],
    COMPLETED: ['NEW', 'ACTIVE', 'COMPLETED'],
    CANCELED: ['NEW', 'CANCELED'],
  };

/**
 * The demo dataset `make seed` writes: five Canadian Customers spanning both kinds, with Properties,
 * Jobs across every status, and Visits across every status. Relative schedules keep the dataset honest
 * whatever time of day it runs; `assertSeedScenariosAreCoherent` refuses it the moment a future field
 * attempt is presented as finished work or a technician is double-booked.
 */
export const SEED_CUSTOMERS: readonly SeedCustomerPlan[] = [
  {
    kind: 'COMPANY',
    displayName: 'Maple Ridge Property Management',
    legalName: 'Maple Ridge Property Management Ltd.',
    businessName: 'Maple Ridge PM',
    email: 'office@mapleridgepm.test',
    phone: '+1 604 555 0140',
    billingEmail: 'ap@mapleridgepm.test',
    billingPhone: '+1 604 555 0141',
    notes:
      'Largest portfolio client. Prefers email for scheduling and a courtesy call the day before each visit.',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 410,
    billingAddress: {
      addressLine1: '900 West Georgia Street',
      addressLine2: 'Suite 1400',
      city: 'Vancouver',
      province: 'British Columbia',
      postalCode: 'V6C 1P9',
    },
    contacts: [
      {
        firstName: 'Nadia',
        lastName: 'Patel',
        role: 'Operations Manager',
        email: 'nadia.patel@mapleridgepm.test',
        phone: '+1 604 555 0142',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Tom',
        lastName: 'Whitfield',
        role: 'Site Coordinator',
        email: 'tom.whitfield@mapleridgepm.test',
        phone: '+1 604 555 0143',
        isJobContact: true,
      },
    ],
    properties: [
      {
        name: 'Cedar Court Apartments',
        addressLine1: '1280 Cedar Street',
        city: 'Vancouver',
        province: 'British Columbia',
        postalCode: 'V6J 2K4',
      },
      {
        name: 'Birchfield Townhomes',
        addressLine1: '412 Birchfield Way',
        city: 'Burnaby',
        province: 'British Columbia',
        postalCode: 'V5G 1T2',
      },
    ],
    jobs: [
      {
        title: 'Annual boiler inspection — Cedar Court',
        description:
          'Yearly boiler inspection for the Cedar Court apartment building, including safety certification for the strata.',
        typeCode: 'INSPECTION',
        status: 'COMPLETED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 12, hour: 9, durationHours: 3 },
            crew: ['SEEDED', 'SARAH'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Boiler passed inspection; annual safety certificate filed with the strata.',
            },
            notes: [
              {
                atHours: -1,
                author: 'MANAGER',
                body: 'Send the certificate to the strata manager once the visit closes.',
              },
              {
                atHours: 1,
                author: 'SEEDED',
                body: 'Replaced the pressure relief valve gasket while on site; logged it in the report.',
              },
            ],
          },
        ],
      },
      {
        title: 'Replace lobby HVAC thermostat — Birchfield',
        description:
          'The Birchfield lobby thermostat stopped responding. Diagnose and replace the unit.',
        typeCode: 'REPAIR',
        status: 'ACTIVE',
        propertyIndex: 1,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 3, hour: 13, durationHours: 2 },
            crew: ['JOHN'],
            outcome: {
              code: 'NEEDS_PARTS',
              summary:
                'Thermostat model is obsolete; ordered the replacement and scheduled the install.',
            },
          },
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 2, hour: 10, durationHours: 3 },
            crew: ['JOHN', 'PRIYA'],
            removedTechnician: 'LUC',
          },
        ],
      },
      {
        title: 'Leak repair — Cedar Court unit 3A',
        description:
          'Resident reported water damage under the kitchen sink in unit 3A. Site visit to be scheduled.',
        typeCode: 'REPAIR',
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
    displayName: 'Boucher & Fils Plomberie',
    legalName: 'Boucher & Fils Plomberie Inc.',
    businessName: 'Boucher & Fils Plomberie',
    email: 'info@boucherplomberie.test',
    phone: '+1 514 555 0160',
    billingEmail: 'facturation@boucherplomberie.test',
    billingPhone: '+1 514 555 0161',
    notes:
      "Client commercial francophone. Appeler Claude avant chaque intervention à l'atelier.",
    preferredContactMethod: 'PHONE',
    language: 'fr-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 180,
    billingAddress: {
      addressLine1: '1240 Rue Saint-Denis',
      city: 'Montréal',
      province: 'Québec',
      postalCode: 'H2X 3J6',
    },
    contacts: [
      {
        firstName: 'Claude',
        lastName: 'Boucher',
        role: 'Directeur',
        email: 'claude.boucher@boucherplomberie.test',
        phone: '+1 514 555 0162',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Isabelle',
        lastName: 'Fortin',
        role: 'Adjointe administrative',
        email: 'isabelle.fortin@boucherplomberie.test',
        phone: '+1 514 555 0163',
        isBillingContact: true,
      },
    ],
    properties: [
      {
        name: 'Atelier principal',
        addressLine1: '1240 Rue Saint-Denis',
        city: 'Montréal',
        province: 'Québec',
        postalCode: 'H2X 3J6',
      },
      {
        name: 'Entrepôt',
        addressLine1: '77 Rue Saint-Jacques',
        city: 'Longueuil',
        province: 'Québec',
        postalCode: 'J4H 2V6',
      },
    ],
    jobs: [
      {
        title: 'Remplacement du chauffe-eau',
        description:
          "Remplacement du chauffe-eau défectueux de l'atelier principal.",
        typeCode: 'INSTALLATION',
        status: 'ACTIVE',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'IN_PROGRESS',
            schedule: { kind: 'HOURS_FROM_NOW', hours: -3, durationHours: 4 },
            crew: ['PRIYA', 'LUC'],
            removedTechnician: 'SARAH',
            notes: [
              {
                atHours: 0,
                author: 'PRIYA',
                body: 'Ancien chauffe-eau vidangé; le nouveau modèle est en place, branchement en cours.',
              },
            ],
          },
        ],
      },
      {
        title: "Déblocage du drain de l'entrepôt",
        description:
          "Drain de plancher de l'entrepôt obstrué; déblocage et inspection caméra.",
        typeCode: 'REPAIR',
        status: 'COMPLETED',
        propertyIndex: 1,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: { kind: 'DAYS_AGO', days: 5, hour: 8, durationHours: 2 },
            crew: ['SARAH'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Drain débloqué; recommandé une inspection caméra au printemps.',
            },
          },
        ],
      },
    ],
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Diane Rousseau',
    firstName: 'Diane',
    lastName: 'Rousseau',
    email: 'diane.rousseau@servora.test',
    phone: '+1 819 555 0170',
    notes:
      "Cliente résidentielle. Préfère être prévenue par texto avant l'arrivée du technicien.",
    preferredContactMethod: 'SMS',
    language: 'fr-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 95,
    billingAddress: {
      addressLine1: '38 Chemin du Lac',
      city: 'Gatineau',
      province: 'Québec',
      postalCode: 'J8P 4R2',
    },
    contacts: [],
    properties: [
      {
        name: 'Résidence Rousseau',
        addressLine1: '38 Chemin du Lac',
        city: 'Gatineau',
        province: 'Québec',
        postalCode: 'J8P 4R2',
      },
    ],
    jobs: [
      {
        title: 'Inspection de toiture après tempête',
        description:
          'Inspection de la toiture suite à la tempête de grêle; vérifier les bardeaux et les solins.',
        typeCode: 'INSPECTION',
        status: 'ACTIVE',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'SCHEDULED',
            schedule: { kind: 'IN_DAYS', days: 1, hour: 9, durationHours: 2 },
            crew: ['SEEDED'],
          },
        ],
      },
      {
        title: 'Nettoyage des gouttières',
        description: 'Nettoyage annuel des gouttières de la résidence.',
        typeCode: 'MAINTENANCE',
        status: 'CANCELED',
        propertyIndex: 0,
        owner: false,
        cancellationNote:
          'Diane a annulé; le nettoyage sera reporté au printemps.',
        visits: [
          {
            status: 'CANCELED',
            schedule: { kind: 'IN_DAYS', days: 3, hour: 14, durationHours: 2 },
            crew: ['LUC'],
            cancellation: {
              reasonCode: 'CUSTOMER_CANCELED',
              note: 'Diane a annulé; report au printemps demandé.',
            },
          },
        ],
      },
    ],
  },
  {
    kind: 'COMPANY',
    displayName: 'Harbourview Dental Clinic',
    legalName: 'Harbourview Dental Clinic Ltd.',
    businessName: 'Harbourview Dental',
    email: 'frontdesk@harbourviewdental.test',
    phone: '+1 250 555 0180',
    billingEmail: 'accounts@harbourviewdental.test',
    billingPhone: '+1 250 555 0181',
    notes:
      'Clinic occupies a heritage building; after-hours access requires a site escort.',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 230,
    billingAddress: {
      addressLine1: '1100 Government Street',
      city: 'Victoria',
      province: 'British Columbia',
      postalCode: 'V8W 1Y2',
    },
    contacts: [
      {
        firstName: 'Elena',
        lastName: 'Vasquez',
        role: 'Owner Dentist',
        email: 'elena.vasquez@harbourviewdental.test',
        phone: '+1 250 555 0182',
        isPrimary: true,
        isJobContact: true,
      },
      {
        firstName: 'Mark',
        lastName: 'Lee',
        role: 'Clinic Manager',
        email: 'mark.lee@harbourviewdental.test',
        phone: '+1 250 555 0183',
        isJobContact: true,
      },
    ],
    properties: [
      {
        name: 'Harbourview Dental Clinic',
        addressLine1: '1100 Government Street',
        city: 'Victoria',
        province: 'British Columbia',
        postalCode: 'V8W 1Y2',
      },
    ],
    jobs: [
      {
        title: 'Autoclave maintenance',
        description:
          'Preventive maintenance on the clinic autoclave to keep sterilization certifications current.',
        typeCode: 'MAINTENANCE',
        status: 'ACTIVE',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'EN_ROUTE',
            schedule: { kind: 'HOURS_FROM_NOW', hours: -1, durationHours: 2 },
            crew: ['JOHN'],
          },
        ],
      },
      {
        title: 'Replace dental chair compressor',
        description:
          'Compressor on operatory chair 2 failed. Replace the unit and verify the chair.',
        typeCode: 'REPAIR',
        status: 'COMPLETED',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'COMPLETED',
            schedule: {
              kind: 'DAYS_AGO',
              days: 20,
              hour: 10,
              durationHours: 3,
            },
            crew: ['SEEDED', 'PRIYA'],
            outcome: {
              code: 'RESOLVED',
              summary:
                'Compressor replaced and tested; chair restored to full function.',
            },
            notes: [
              {
                atHours: 0,
                author: 'MANAGER',
                body: 'Mark confirmed the chair holds pressure after the swap.',
              },
            ],
          },
        ],
      },
    ],
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Marcus Chen',
    firstName: 'Marcus',
    lastName: 'Chen',
    email: 'marcus.chen@servora.test',
    phone: '+1 778 555 0190',
    notes:
      'Homeowner with a service contract. Leave the furnace room as found.',
    preferredContactMethod: 'PHONE',
    language: 'en-CA',
    status: 'ACTIVE',
    customerSinceDaysAgo: 60,
    billingAddress: {
      addressLine1: '2211 Sea Island Way',
      city: 'Richmond',
      province: 'British Columbia',
      postalCode: 'V7B 1E9',
    },
    contacts: [],
    properties: [
      {
        name: 'Chen Residence',
        addressLine1: '2211 Sea Island Way',
        city: 'Richmond',
        province: 'British Columbia',
        postalCode: 'V7B 1E9',
      },
    ],
    jobs: [
      {
        title: 'Furnace service call',
        description:
          'Furnace short-cycles overnight. Diagnose the cause and service the unit.',
        typeCode: 'SERVICE_CALL',
        status: 'ACTIVE',
        propertyIndex: 0,
        owner: true,
        visits: [
          {
            status: 'ON_SITE',
            schedule: { kind: 'HOURS_FROM_NOW', hours: -2, durationHours: 3 },
            crew: ['SARAH'],
            notes: [
              {
                atHours: 0,
                author: 'SARAH',
                body: 'Arrived and found the flame sensor fouled; cleaning and testing now.',
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
    Math.min(
      candidate.getTime(),
      now.getTime() - minutesBeforeNow * ONE_MINUTE,
    ),
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
      case 'ACTIVE':
        return timeline.workStartedAt ?? addHours(timeline.now, -6);
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
 * A Job's status must agree with the work its Visits describe (`BR-058`, `BR-062`, `BR-065`).
 *
 * Without this the demo could show a Job closed over an open Visit, or a canceled Job whose field work
 * is still running — states the API refuses to create.
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
      // A Job that has not entered execution holds no real work: a stored `DRAFT` Visit does not make
      // it active by itself (tracker 051).
      if (statuses.some((status) => status !== 'DRAFT')) {
        fail(`${label} is NEW but holds started or scheduled work (BR-058).`);
      }
      break;
    case 'ACTIVE':
      if (job.visits.length === 0) {
        fail(`${label} is ACTIVE without any Visit (BR-058).`);
      }
      if (!statuses.some((status) => status !== 'DRAFT')) {
        fail(
          `${label} is ACTIVE while every Visit is still a draft (BR-058, tracker 051).`,
        );
      }
      break;
    case 'COMPLETED':
      if (open.length > 0) {
        fail(
          `${label} is COMPLETED while ${open.length} Visit(s) are still open (BR-062).`,
        );
      }
      break;
    case 'CANCELED':
      // Canceling a Job cancels its open Visits (`BR-065`), so a canceled Job holds no open one.
      if (open.length > 0) {
        fail(
          `${label} is CANCELED while ${open.length} Visit(s) are still open (BR-065).`,
        );
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
