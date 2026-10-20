/** Office contracts for existing Job endpoints. Office operations remain online-only. */
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
}
import type { WorkspaceJobDetails } from "./workspace.js";
export interface JobActivityEvent extends FieldActivityEvent {
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
  technicians: { membershipId: string; roleCode: 'LEAD' | 'TECHNICIAN' }[];
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
