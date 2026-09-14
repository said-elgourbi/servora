import {
  readAddressSnapshot,
  type AddressSnapshot,
} from '../address/address-snapshot.js';
import type {
  AssignmentRoleCode,
  Job,
  JobStatus,
  VisitStatus,
} from './job.types.js';
import {
  applicableJobStatusTransitions,
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
   * The client draws its status actions from this list rather than holding a second copy of the
   * lifecycle (`BR-041`), and `CANCELED` is absent while `BR-064`'s cancellation-reason catalogue
   * remains an open question.
   */
  allowedStatusTransitions: JobStatus[];
  /** The Job's version, echoed back when its status changes (`BR-086`). */
  version: number;
  customerId: string;
  customerName: string;
  /** The Job's Property, or `null` when the Job has none yet (`BR-051`, `BR-056`). */
  propertyId: string | null;
  /** The address the Job preserves for its Property (`BR-056`, `BR-057`). */
  address: AddressSnapshot | null;
  /** The selected Visit, or `null` when the Job has no Visit with a schedule (`BR-081`). */
  selectedVisit: JobDetailsVisitDto | null;
  /** The selected Visit's technicians, Lead first; empty when it has none (`BR-068`, `BR-081`). */
  technicians: JobDetailsTechnicianDto[];
}

/** Everything the Job Details projection resolves for one Job. */
export interface JobDetails {
  readonly job: Job;
  readonly customerId: string;
  readonly customerName: string;
  readonly selectedVisit: SelectedVisit | null;
  readonly technicians: readonly AssignedTechnician[];
}

export function toJobDetailsDto(details: JobDetails): JobDetailsDto {
  const job = details.job;
  const status = job.status as JobStatus;
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
          },
    technicians: details.technicians.map((technician) => ({
      membershipId: technician.membershipId,
      name: technician.name,
      roleCode: technician.roleCode,
    })),
  };
}
