/** Office contracts for existing Job endpoints. Office operations remain online-only. */
export type EvidencePhase = 'BEFORE_WORK' | 'DURING_WORK' | 'AFTER_WORK';

export interface CreateJobRequest {
  customerId: string;
  propertyId: string;
  title: string;
  description: string | null;
}
export interface ChangeJobStatusRequest {
  status: string;
  note: string | null;
  expectedVersion: number | null;
}
export interface OfficeJobDetails extends WorkspaceJobDetails {
  canScheduleVisit: boolean;
  customerId: string;
  propertyId: string | null;
  version: number;
  allowedStatusTransitions: string[];
  attention: {
    code: string;
    relevantVisitId: string;
    reasonCode: string | null;
  }[];
  followUpVisitRequest?: {
    id: string;
    jobId: string;
    sourceVisitId: string | null;
    createdVisitId: string | null;
    requestingTechnicianMembershipId: string;
    status: string;
    version: number;
  } | null;
  quote?: {
    id: string;
    state: string;
    version: number;
    currentDocument: { id: string; displayName: string | null; documentVersion: number; isCurrent: boolean } | null;
    allowedActions: string[];
  } | null;
}
export interface JobScheduleSearchOption {
  id: string;
  jobNumber: number;
  title: string;
  status: string;
  customerName: string;
  propertyName: string | null;
}
export type AdHocWorkReportStatus = 'PENDING' | 'LINKED' | 'CONVERTED' | 'REJECTED';
export interface AdHocWorkReport {
  id: string;
  reportingTechnicianMembershipId: string;
  customerId: string | null;
  customerName: string | null;
  propertyId: string | null;
  propertyAddress: import('./workspace.js').WorkspaceAddress | null;
  knownJobId: string | null;
  knownJobNumber: number | null;
  knownJobTitle: string | null;
  workStartedAt: string;
  workEndedAt: string;
  outcomeCode: string;
  summary: string;
  notes: string | null;
  reportedCustomerName: string | null;
  reportedCustomerPhone: string | null;
  reportedCustomerAddress: string | null;
  status: AdHocWorkReportStatus;
  reviewerMembershipId: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  createdJobId: string | null;
  createdJobNumber: number | null;
  createdJobTitle: string | null;
  createdVisitId: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}
export interface AdHocReviewCustomerOption { id: string; displayName: string; phone: string | null }
export interface AdHocReviewPropertyOption {
  id: string; name: string | null; addressLine1: string; addressLine2: string | null;
  city: string; province: string; postalCode: string; country: string;
}
export interface AdHocReviewJobOption { id: string; jobNumber: number; title: string }
import type { WorkspaceJobDetails } from "./workspace.js";
export interface JobActivityEvent extends FieldActivityEvent {
  authorMembershipId: string | null;
  canEdit: boolean;
  canDelete: boolean;
  activityEditedAt: string | null;
  activityRemovedAt: string | null;
  photoRemovalReason: string | null;
  audioRemovalReason: string | null;
  noteEditedAt: string | null;
  noteRemovedAt: string | null;
  noteRemovalReason: string | null;
  quoteFromState: string | null;
  quoteToState: string | null;
  quoteNote: string | null;
  quoteDocumentDisplayName: string | null;
  quoteDocumentVersion: number | null;
  quoteDocumentId: string | null;
  quoteDocumentIsCurrent: boolean | null;
  quoteDocumentByteSize: number | null;
  documentId: string | null;
  documentDisplayName: string | null;
  documentVersion: number | null;
  documentByteSize: number | null;
}
import type { FieldActivityEvent } from "./field.js";
export interface JobActivity {
  jobId: string;
  events: JobActivityEvent[];
}

/** Direct scheduling uses the existing POST /jobs/:id/visits endpoint. */
export interface ScheduleVisitRequest {
  purposeCode: "WORK" | "ASSESSMENT";
  scheduledStart: string;
  scheduledEnd: string;
  technicians: { membershipId: string; roleCode: "LEAD" | "TECHNICIAN" }[];
  confirmConflicts: boolean;
}
/** Reschedules an existing scheduled Visit through PATCH /jobs/:id/visits/:visitId/schedule. */
export interface RescheduleVisitRequest {
  scheduledStart: string;
  scheduledEnd: string;
  reason: string | null;
  confirmConflicts: boolean;
  expectedVersion: number | null;
}
/** States the complete crew for an existing Visit through PUT /jobs/:id/visits/:visitId/technicians. */
export interface AssignVisitTechniciansRequest {
  technicians: { membershipId: string; roleCode: "LEAD" | "TECHNICIAN" }[];
  confirmConflicts: boolean;
  expectedVersion: number | null;
}
export interface AssignableTechnician {
  membershipId: string;
  name: string | null;
}
export interface VisitScheduleConflict {
  visitId: string;
  jobId: string;
  jobNumber: number;
  technicianMembershipId: string;
  technicianName: string | null;
  scheduledStart: string;
  scheduledEnd: string;
}
