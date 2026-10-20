import type { WorkspaceAddress, WorkspaceTechnician } from "./workspace.js";
/** Existing GET /schedule JSON projection. Dates are ISO instants; localDate is YYYY-MM-DD. */
export interface ScheduleVisit {
  visitId: string;
  visitStatus: string;
  purposeCode: string;
  scheduledStart: string | null;
  scheduledEnd: string | null;
  jobId: string;
  jobNumber: number;
  jobTitle: string;
  customerId: string;
  customerName: string;
  address: WorkspaceAddress | null;
  technicians: WorkspaceTechnician[];
  overdue: boolean;
}
export interface ScheduleResponse {
  generatedAt: string;
  day: { localDate: string; timeZone: string; start: string; end: string };
  scope: { kind: "ORGANIZATION" | "SELF"; membershipId: string };
  technicians: { membershipId: string; name: string | null }[];
  visits: ScheduleVisit[];
  unassigned: { total: number; items: ScheduleVisit[] } | null;
}
