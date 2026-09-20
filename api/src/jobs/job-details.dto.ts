import {
  readAddressSnapshot,
  type AddressSnapshot,
} from '../address/address-snapshot.js';
import type { CustomerContact } from '../customers/customer.types.js';
import type {
  AssignmentRoleCode,
  Job,
  JobStatus,
  VisitOutcomeCode,
  VisitStatus,
} from './job.types.js';
import {
  applicableJobStatusTransitions,
  applicableVisitStatusTransitions,
  isReschedulableVisitStatus,
} from './job.types.js';
import type { AssignedTechnician, SelectedVisit } from './visit-assignment.js';

/**
 * The Job Details read (`BR-021`, `BR-058`, `BR-068`, `BR-081`).
 *
 * It is a **projection**: every value is derived from the authoritative Job, Visit, assignment and
 * Customer records and nothing is stored (`BR-001`, `BR-080`, `BR-081`).
 *
 * The screen it serves represents **one Visit of the Job** — the selected Visit `BR-081` defines for
 * the customer-detail Job row — so the Job Details screen and the customer's Job row can never show a
 * different schedule or a different crew for the same Job (`BR-041`).
 */

/** One technician assigned to the represented Visit, as the screen renders them (`BR-068`). */
export interface JobDetailsTechnicianDto {
  membershipId: string;
  /** Resolved from the member's profile; `null` when the member has no profile yet. */
  name: string | null;
  /** The role this technician holds on the Visit: exactly one is `LEAD` (`BR-068`). */
  roleCode: AssignmentRoleCode;
}

/**
 * One Visit of the Job, as the Job Details screen lists the Job's Visits (`BR-047`, `BR-071`).
 *
 * The Job Details read represents **one** Visit of the Job (`BR-081`), which is what `selectedVisit`
 * is for and what the screen's Visit actions address. A Job may have several Visits over time and each
 * one is a field attempt of its own (`BR-051`, `BR-071`), so a screen that presents the Job rather
 * than a single field attempt also has to present the Visits it has had. This is that list: every
 * Visit of the Job, in the Job's own visit sequence, each with the status, schedule and crew the
 * Visit actually carries.
 *
 * It is a **summary** and deliberately narrower than `selectedVisit`: it carries what a Visit's row
 * states about a Visit — which one it is, where it is in its field lifecycle, when it was scheduled
 * for, and who is on its crew. It carries no field action and no reschedule permission, because those
 * belong to the Visit the screen represents and are answered once on `selectedVisit` (`BR-073`,
 * `BR-093`). A Visit's own activity is read from `GET /jobs/:id/activity`, whose events carry the same
 * `visitSequence` (`docs/api/job-activity.md` §3.3).
 */
export interface JobDetailsVisitSummaryDto {
  id: string;
  /** The Visit's stable, human-readable sequence within the Job: `1`, `2`, … (`BR-052`). */
  sequence: number;
  status: VisitStatus;
  /**
   * The outcome the Visit's completion recorded, or `null` when the Visit holds none (`BR-077`,
   * `BR-078`).
   *
   * It is the Visit's **current** outcome, which is what lets a screen state what resulted from a
   * field attempt beside the attempt's own date and crew. A Visit completed and then reopened holds no
   * current outcome until it is completed again, while the outcome it recorded stays in the
   * append-only history `GET /jobs/:id/activity` reports as `VISIT_OUTCOME_RECORDED` (`BR-074`,
   * `BR-079`): the two reads answer different questions, and neither is derived from the other
   * (`BR-001`, `BR-041`).
   *
   * The outcome's **summary** is deliberately not reported here. This summary states a Visit's facts
   * as a row — which field attempt it is, where it is in its lifecycle, when it was for, who is
   * assigned and what it resulted in — while the technician's account of what happened is read in that
   * Visit's own activity, where it is not split from the entry that recorded it (`BR-077`, `BR-080`).
   */
  outcomeCode: VisitOutcomeCode | null;
  /**
   * ISO-8601 UTC instant the Visit is scheduled to start, or `null` when it has no schedule yet.
   *
   * A `DRAFT` Visit exists before it is scheduled (`BR-072`), and the pair is always both present or
   * both absent (`visits_schedule_pair_check`), so a client that has one end has the other.
   */
  scheduledStart: string | null;
  /** ISO-8601 UTC instant the Visit is scheduled to end, or `null` when it has no schedule yet. */
  scheduledEnd: string | null;
  /** The Visit's version, echoed back when it is rescheduled or its crew changes (`BR-086`). */
  version: number;
  /** The technicians currently assigned to **this** Visit, Lead first; empty when none are (`BR-068`). */
  technicians: JobDetailsTechnicianDto[];
}

/** The Visit this Job is represented by, with the schedule it carries (`BR-072`). */
export interface JobDetailsVisitDto {
  id: string;
  status: VisitStatus;
  scheduledStart: string;
  scheduledEnd: string;
  /** The Visit's version, echoed back when the Visit is rescheduled or its crew changes (`BR-086`). */
  version: number;
  /**
   * Whether this Visit may be rescheduled (`BR-073`).
   *
   * `BR-073` permits a reschedule only while the Visit is `SCHEDULED`, and that rule is defined once
   * here so a client disables or hides the action from the server's answer rather than holding its
   * own copy of the lifecycle (`BR-041`).
   */
  reschedulable: boolean;
  /**
   * The statuses **this caller** may move this Visit to in its field lifecycle (`BR-074`, `BR-075`,
   * `BR-093`).
   *
   * It is the structural table the Visit status route validates against — read from the server so a
   * client draws the action from the backend's own answer instead of holding a second copy of the
   * lifecycle (`BR-022`, `BR-041`) — narrowed to the destinations the calling member is authorized to
   * execute, which is what `BR-093` requires the projection to report:
   *
   * - **Nothing** when the caller may not drive this Visit at all (`fieldActionable` is `false`). A
   *   destination this caller's request would be refused for is not offered, so a client never has to
   *   present a refusal as an action that failed.
   * - **`COMPLETED` omitted** unless the caller holds `VISIT_RECORD_OUTCOME`. The completion's own
   *   capability is required of **every** caller, the office included (`BR-009`, `BR-093`), so the
   *   destination is simply absent rather than offered and then refused with `403`.
   * - `BR-074` permits free movement between the working statuses in either direction, so a working
   *   Visit is offered every other working status and a `COMPLETED` Visit is offered the five working
   *   ones — which is how the reopen reaches the menu. `CANCELED` and `NO_SHOW` are absent because they
   *   are dispatch actions no capability authorizes (`BR-066`).
   *
   * Where the list is **not** filtered is **eligibility**, deliberately: `BR-072`'s conditions for
   * becoming `SCHEDULED` and `BR-070`'s conflicts are runtime questions answered when the action is
   * performed, and `BR-074` forbids expressing them by removing a structurally valid destination.
   */
  allowedStatusTransitions: VisitStatus[];
  /**
   * Whether **this caller** is authorized to drive the Visit's field lifecycle right now (`BR-074`,
   * `BR-066`, `BR-093`).
   *
   * Two authorizations reach the Visit status route, and `BR-093` keeps them distinct (`ADR-019` D7):
   *
   * - **Crew authorization** — the caller's own membership is on this Visit's current crew, evaluated
   *   at the moment of the action (`ADR-019` D3). Being removed from the crew before the action is the
   *   same refusal, and a Visit their crew does not include is reported as not found rather than
   *   forbidden.
   * - **Office capability authorization** — the caller holds the office capability that reaches Visit
   *   writes (`JOB_UPDATE`), so the assignment scope does not bound them and crew membership is not
   *   required. This is what makes office completion from Job Details possible.
   *
   * It exists because a client cannot answer either half for itself: it knows its user, not its
   * membership or its organization's roles. `allowedStatusTransitions` is derived from the same answer,
   * so the two fields can never disagree (`BR-041`).
   *
   * The capability the **session** holds is still the client's own gate and the route's guard, exactly
   * as it is for the Job's own `allowedStatusTransitions` (`BR-007`, `BR-011`): this field answers what
   * this caller is authorized for on **this** Visit, not whether a given session holds the capability at
   * all.
   */
  fieldActionable: boolean;
}

/**
 * One of the Customer's contact persons, as the field read reports them (`BR-092`).
 *
 * `BR-092` names exactly what a Technician may read of a Customer's contact persons: their **name,
 * phone number and email address**, and **which of them is primary** (`BR-095`). This type therefore
 * carries those five fields and nothing else. The billing-contact flag is excluded because `BR-092`
 * excludes it; the free-text `role`, the job-contact flag, the contact's `version` and its timestamps are
 * absent for the same reason — the least disclosure that satisfies the field read is the correct
 * implementation of it. The write token in particular has no place in a read: a Technician holds no
 * contact write capability at all (`BR-009`, `BR-095`).
 *
 * `isPrimary` is the contact row's own flag, not a resolution of the Customer's primary: when no contact
 * is flagged the field is `false` for every one of them and the Customer itself is the effective primary,
 * which `BR-095` defines and a client presents.
 *
 * The list itself is not filtered down to the primary contact: a Technician reads the same contacts
 * the office does, in the same order, primary first (`ADR-022` D3), and the client presents the ones
 * it needs.
 */
export interface JobDetailsContactPersonDto {
  firstName: string;
  lastName: string;
  phone: string | null;
  email: string | null;
  /** Whether this contact person is the Customer's flagged primary (`BR-095`). */
  isPrimary: boolean;
}

/**
 * The Job's Customer contact details, as the field read may report them (`BR-092`; `ADR-021` D3).
 *
 * It carries the Customer's **own** fields together with the Customer's **contact persons**, and
 * nothing else. `billingEmail`, `billingPhone`, `preferredContactMethod`, `language` and the
 * Customer's addresses are deliberately absent: `BR-092` is a field contact read, and the least
 * disclosure that satisfies "view-only" is the correct implementation of it.
 *
 * The block carries no identifier of its own — `customerId` and `customerName` are already on the
 * response and are unchanged — so the contract stays backward compatible.
 */
export interface JobDetailsCustomerContactDto {
  email: string | null;
  phone: string | null;
  notes: string | null;
  /**
   * The Customer's contact persons, in ordinary use, primary first (`BR-092`, `BR-095`).
   *
   * `[]` for a Customer with none — including an individual Customer, who is their own primary and
   * holds no contact person row at all (`BR-095`). A removed contact is not part of an ordinary view
   * and is never listed here (`ADR-022` D8).
   */
  contacts: JobDetailsContactPersonDto[];
}

export interface JobDetailsDto {
  id: string;
  /** The organization-scoped, human-readable Job number (`BR-052`). */
  jobNumber: number;
  title: string;
  description: string | null;
  typeCode: string | null;
  status: JobStatus;
  /**
   * The statuses this Job may move to (`BR-058`).
   *
   * The list is **structural**: the destinations `BR-058` permits for the Job's current status, from
   * which the client draws its status actions rather than holding a second copy of the lifecycle
   * (`BR-041`). Whether the Job qualifies for one of them *now* is its own rule's answer — `BR-061`
   * for `PENDING_REVIEW` and `BR-062` for `COMPLETED` — so those destinations stay listed and the API
   * refuses an attempt the Job does not support with that rule's error. `CANCELED` is absent while
   * `BR-064`'s cancellation-reason catalogue remains an open question.
   */
  allowedStatusTransitions: JobStatus[];
  /** The Job's version, echoed back when its status changes (`BR-086`). */
  version: number;
  customerId: string;
  customerName: string;
  /**
   * The Customer's own contact details, or `null` when this caller was not admitted to them
   * (`BR-092`).
   *
   * Whether they are included is a **second** authorization question beside the one that admitted the
   * caller to the read — `customers.view` **or** `customers.view_assigned` (`ADR-021` D2). A caller
   * holding neither reads the same Job without the block rather than being refused the Job, because
   * the Job is still theirs to work (`BR-009`, `BR-011`).
   */
  customerContactDetails: JobDetailsCustomerContactDto | null;
  /** The Job's Property, or `null` when the Job has none yet (`BR-051`, `BR-056`). */
  propertyId: string | null;
  /** The address the Job preserves for its Property (`BR-056`, `BR-057`). */
  address: AddressSnapshot | null;
  /** The selected Visit, or `null` when the Job has no Visit with a schedule (`BR-081`). */
  selectedVisit: JobDetailsVisitDto | null;
  /** The selected Visit's technicians, Lead first; empty when it has none (`BR-068`, `BR-081`). */
  technicians: JobDetailsTechnicianDto[];
  /**
   * Every Visit of the Job, in the Job's own visit sequence (`BR-047`, `BR-051`, `BR-071`).
   *
   * `[]` for a Job that has no Visit. It is the list a screen presents **beside** the represented
   * Visit, so a Job's earlier and later field attempts are readable without a second read; the
   * represented one is the entry whose `id` is `selectedVisit.id` and is not marked specially, because
   * which Visit represents a Job is already `selectedVisit`'s answer (`BR-081`, `BR-041`).
   */
  visits: JobDetailsVisitSummaryDto[];
}

/** The Customer's own contact details a Job Details read may have been asked for (`BR-092`). */
export interface JobCustomerContact {
  readonly email: string | null;
  readonly phone: string | null;
  readonly notes: string | null;
  /**
   * The Customer's contact persons, in ordinary use, primary first (`BR-092`, `BR-095`).
   *
   * Resolved as authoritative contact rows and narrowed by the projection, so an ordinary edit or a
   * removal is reflected here without a second definition of what a contact is (`BR-080`, `BR-041`).
   */
  readonly contacts: readonly CustomerContact[];
}

/**
 * What a Job Details read is asked for beside the projection's base (`ADR-021` D2).
 *
 * It exists because `GET /jobs/:id` answers **two** authorization questions with one guard: the guard
 * decides who may read the Job at all, and this decides whether the Customer's contact details are part
 * of the answer. `ADR-019` D1 fixed that shape — a route declares either an all-of requirement or an
 * any-of one and never both, so the second question is asked at the boundary — and the activity read's
 * `includeRemovedEvidence` flag already follows it.
 *
 * It is asked **at the projection** rather than in the service because every route that returns a Job
 * must answer it the same way: the four Job and Visit action routes return the identical projection, so
 * deciding it in one place is what keeps a technician's customer block from disappearing the moment
 * they move a Visit's status.
 */
export interface JobDetailsReadOptions {
  /**
   * Whether the Customer's contact details are included in this read (`BR-092`).
   *
   * The route answers it from the capabilities the caller holds: `customers.view` (the office read) or
   * `customers.view_assigned` (the field read `BR-092` adds). A caller holding neither is given the Job
   * with `null` for the block rather than being refused the Job, because the Job is still theirs to
   * work (`BR-009`, `BR-011`).
   */
  readonly includeCustomerContact?: boolean;
  /**
   * The membership the request was authorized for, so the projection can answer the Visit's own
   * question about **this caller** (`ADR-019` D3).
   *
   * `undefined` means "no caller to answer for", and the Visit is then reported as one this caller may
   * not drive — the fail-closed answer, because the field route would refuse an unnamed caller anyway
   * (`BR-042`, `BR-007`). It is the caller's **membership**, not their user: the Visit's crew holds
   * memberships (`BR-068`), and the API's own scope resolves the membership from the session.
   */
  readonly callerMembershipId?: string;
  /**
   * Whether the caller holds the office capability that reaches Visit writes (`BR-093`; `ADR-019` D7).
   *
   * `JOB_UPDATE` is the office capability every Visit action on a Job already requires
   * (`docs/api/job-actions.md` §2), and `BR-093` makes it the alternative to crew membership: an office
   * caller holding it may drive any Visit of the organization, completion included, without being
   * assigned to the Visit's crew.
   *
   * `true` therefore answers the Visit's authorization question for the office caller who is on no crew,
   * and `false`/`undefined` leaves the crew membership (`callerMembershipId`) as the only answer — the
   * fail-closed reading, because the route would refuse a caller with neither (`BR-007`, `BR-042`).
   */
  readonly officeVisitWriter?: boolean;
  /**
   * Whether the caller holds `VISIT_RECORD_OUTCOME`, the capability a **completion** requires
   * (`BR-009`, `BR-077`, `BR-093`).
   *
   * The destination `COMPLETED` is exposed only when this is `true`, for every caller — the office
   * included, because `BR-093` keeps the completion's capability as its own question rather than folding
   * it into the authorization that admits the caller. A caller without it may still take every other
   * working destination the Visit offers.
   */
  readonly recordsVisitOutcome?: boolean;
}

/**
 * One Visit of the Job as the projection resolves it, with the crew it carries (`BR-068`, `BR-071`).
 *
 * [sequence] is derived from the Visit's creation order — the one definition of `Visit N` this
 * codebase keeps (`src/jobs/visit-assignment.ts`).
 */
export interface JobVisitSummary {
  readonly visitId: string;
  readonly sequence: number;
  readonly status: VisitStatus;
  /** The Visit's **current** outcome, or `null` when it holds none (`BR-077`, `BR-079`). */
  readonly outcomeCode: VisitOutcomeCode | null;
  readonly scheduledStart: Date | null;
  readonly scheduledEnd: Date | null;
  readonly version: number;
  readonly technicians: readonly AssignedTechnician[];
}

/** Everything the Job Details projection resolves for one Job. */
export interface JobDetails {
  readonly job: Job;
  readonly customerId: string;
  readonly customerName: string;
  /**
   * The Customer's own contact details (`BR-092`).
   *
   * The read resolves them from the Customer row it already joins, and whether they are **exposed** is
   * the route's second authorization question — see `JobDetailsReadOptions`.
   */
  readonly customerContactDetails: JobCustomerContact;
  readonly selectedVisit: SelectedVisit | null;
  readonly technicians: readonly AssignedTechnician[];
  /**
   * Every Visit of the Job in its own sequence, each with its own crew (`BR-047`, `BR-071`).
   *
   * The field is optional on this **input** only for the benefit of the projection's own callers: the
   * projection always emits `visits` on the wire, as `[]` when nothing was resolved, so the response's
   * shape does not depend on which route built it.
   */
  readonly visits?: readonly JobVisitSummary[];
}

/** One technician on the wire, which every crew in this projection is reported through (`BR-068`). */
function toTechnicianDto(
  technician: AssignedTechnician,
): JobDetailsTechnicianDto {
  return {
    membershipId: technician.membershipId,
    name: technician.name,
    roleCode: technician.roleCode,
  };
}

/**
 * One of the Customer's contact persons on the wire (`BR-092`).
 *
 * The narrowing happens here and only here: the five fields `BR-092` names, so the field read cannot
 * drift into reporting a contact's billing flag, its write token or anything else the Customer read
 * holds (`BR-041`).
 */
function toContactPersonDto(
  contact: CustomerContact,
): JobDetailsContactPersonDto {
  return {
    firstName: contact.firstName,
    lastName: contact.lastName,
    phone: contact.phone,
    email: contact.email,
    isPrimary: contact.isPrimary,
  };
}

export function toJobDetailsDto(
  details: JobDetails,
  options: JobDetailsReadOptions = {},
): JobDetailsDto {
  const job = details.job;
  const status = job.status as JobStatus;
  // The Visit's own authorization answer, resolved once so the two fields the projection reports about
  // it can never disagree (`BR-041`, `BR-093`): a caller drives a Visit through their own crew
  // membership (`ADR-019` D3), or through the office capability that reaches Visit writes without being
  // on a crew at all (`BR-093`, `ADR-019` D7).
  const drivesSelectedVisit =
    options.officeVisitWriter === true ||
    (options.callerMembershipId !== undefined &&
      details.technicians.some(
        (technician) => technician.membershipId === options.callerMembershipId,
      ));
  return {
    id: job.id,
    jobNumber: job.jobNumber,
    title: job.title,
    description: job.description,
    typeCode: job.typeCode,
    status,
    allowedStatusTransitions: [...applicableJobStatusTransitions(status)],
    version: job.version,
    customerId: details.customerId,
    customerName: details.customerName,
    customerContactDetails:
      options.includeCustomerContact === true
        ? {
            email: details.customerContactDetails.email,
            phone: details.customerContactDetails.phone,
            notes: details.customerContactDetails.notes,
            contacts:
              details.customerContactDetails.contacts.map(toContactPersonDto),
          }
        : null,
    propertyId: job.propertyId,
    address: readAddressSnapshot(job.propertyAddressSnapshot),
    selectedVisit:
      details.selectedVisit === null
        ? null
        : {
            id: details.selectedVisit.visitId,
            status: details.selectedVisit.status,
            scheduledStart: details.selectedVisit.scheduledStart.toISOString(),
            scheduledEnd: details.selectedVisit.scheduledEnd.toISOString(),
            version: details.selectedVisit.version,
            reschedulable: isReschedulableVisitStatus(
              details.selectedVisit.status,
            ),
            // The destinations **this caller** may execute (`BR-093`): the Visit's structural table
            // (`BR-074`) minus the completion unless the caller holds the capability a completion
            // requires of every caller (`BR-009`, `BR-077`), and nothing at all when the caller may not
            // drive this Visit. Eligibility is deliberately not filtered: `BR-072`'s conditions and
            // `BR-070`'s conflicts stay runtime answers (`BR-074`).
            allowedStatusTransitions: drivesSelectedVisit
              ? applicableVisitStatusTransitions(
                  details.selectedVisit.status,
                ).filter(
                  (destination) =>
                    destination !== 'COMPLETED' ||
                    options.recordsVisitOutcome === true,
                )
              : [],
            fieldActionable: drivesSelectedVisit,
          },
    technicians: details.technicians.map(toTechnicianDto),
    // Every Visit the Job holds, in its own sequence, each with the crew it actually carries
    // (`BR-047`, `BR-068`, `BR-071`). A Visit with no schedule is reported with both ends `null`
    // rather than omitted, because the Visit is still a field attempt the Job holds (`BR-051`,
    // `BR-072`).
    visits: (details.visits ?? []).map((visit) => ({
      id: visit.visitId,
      sequence: visit.sequence,
      status: visit.status,
      outcomeCode: visit.outcomeCode,
      scheduledStart: visit.scheduledStart?.toISOString() ?? null,
      scheduledEnd: visit.scheduledEnd?.toISOString() ?? null,
      version: visit.version,
      technicians: visit.technicians.map(toTechnicianDto),
    })),
  };
}
