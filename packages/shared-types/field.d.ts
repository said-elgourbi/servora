/** Verified partition keys only; never a persisted identity or permission grant. */
export interface FieldContextDto {
  readonly userId: string;
  readonly organizationId: string;
  readonly membershipId: string;
}
export type FieldScopeErrorCode =
  | "FIELD_CONTEXT_UNAVAILABLE"
  | "FIELD_CONTEXT_AMBIGUOUS"
  | "FIELD_SCOPE_INVALID"
  | "FIELD_SCOPE_CHANGED"
  | "FIELD_SCOPE_UNSUPPORTED";

export interface FieldVisitDto {
  id: string;
  status: string;
  purposeCode: string;
  scheduledStart: string;
  scheduledEnd: string;
  version: number;
  fieldActionable: boolean;
  allowedStatusTransitions: string[];
  completionAllowed: boolean;
  addUpdateAllowed: boolean;
  requestFollowUpAllowed: boolean;
}
export interface FieldJobDetails extends Omit<
  import("./workspace.js").WorkspaceJobDetails,
  "selectedVisit"
> {
  selectedVisit: FieldVisitDto | null;
}
export interface FieldActivityEvent {
  id: string;
  authorMembershipId?: string | null;
  canEdit?: boolean;
  canDelete?: boolean;
  activityEditedAt?: string | null;
  activityRemovedAt?: string | null;
  kind: string;
  recordedAt: string;
  actorName: string | null;
  technicianName?: string | null;
  roleCode?: string | null;
  previousRoleCode?: string | null;
  visitSequence: number | null;
  fromStatus: string | null;
  toStatus: string | null;
  outcomeCode: string | null;
  outcomeSummary: string | null;
  body: string | null;
  photoId: string | null;
  photoPhase: string | null;
  audioNoteId: string | null;
  audioPhase: string | null;
  audioDurationSeconds: number | null;
}
export interface FieldActivityDto {
  jobId: string;
  events: FieldActivityEvent[];
}
export interface FieldFollowUpDto {
  id: string;
  jobId: string;
  jobNumber: number;
  jobTitle: string;
  customerName: string;
  address: import("./workspace.js").WorkspaceAddress | null;
  sourceVisitId: string | null;
  sourceVisitScheduledStart: string | null;
  sourceVisitStatus: string | null;
  sourceVisitOutcomeCode: string | null;
  requestingTechnicianMembershipId: string;
  proposedStart: string;
  proposedEnd: string;
  sameTechnicianPreferred: boolean;
  status: string;
  version: number;
  reason: string;
  reviewNote: string | null;
  createdVisitId: string | null;
  createdAt: string;
  messages: {
    id: string;
    authorMembershipId: string;
    authorKind: string;
    body: string;
    recordedAt: string;
  }[];
}
