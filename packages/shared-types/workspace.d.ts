/** Public read contracts consumed by the web workspace. API DTOs extend these shapes. */
export interface WorkspaceAddress {
  propertyName: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  province: string | null;
  postalCode: string | null;
  country: string | null;
}
export interface WorkspaceTechnician {
  membershipId: string;
  name: string | null;
  roleCode: string;
}
export interface HomeVisitDto {
  visitId: string;
  visitNumber?: number;
  visitStatus: string;
  purposeCode: string;
  scheduledStart: string;
  scheduledEnd: string;
  jobId: string;
  jobNumber: number;
  jobTitle: string;
  jobStatus: string;
  customerId: string;
  customerName: string;
  address: WorkspaceAddress | null;
  technicians: WorkspaceTechnician[];
  overdue: boolean;
}
export interface HomeAttentionDto {
  kind: string;
  jobId: string;
  jobNumber: number;
  jobTitle: string;
  customerName: string;
  visitId: string | null;
  visitNumber?: number | null;
  purposeCode?: string | null;
  visitStatus?: string | null;
  technicians?: WorkspaceTechnician[];
  scheduledStart: string | null;
  scheduledEnd: string | null;
}
export interface HomeDto {
  generatedAt: string;
  day: { timeZone: string; start: string; end: string };
  viewer: { displayName: string | null };
  attention: { total: number; items: HomeAttentionDto[] };
  visits: HomeVisitDto[];
}
export interface ManagerHomeResponse extends HomeDto {
  attention: HomeDto['attention'] & { countsByKind?: Record<string, number> };
  today: {
    total: number;
    completed: number;
    inProgress: number;
    upcoming: number;
  };
}
export interface TechnicianHomeResponse extends HomeDto {
  nextVisit: (HomeVisitDto & { staleStarted: boolean }) | null;
  upcoming: (HomeVisitDto & { staleStarted: boolean })[];
  upcomingTotal: number;
  visits: (HomeVisitDto & { staleStarted: boolean })[];
}
/** Read-only subset of the existing job projection. Writes retain their existing DTOs. */
export interface WorkspaceJobDetails {
  id: string;
  jobNumber: number;
  title: string;
  description: string | null;
  status: string;
  customerName: string;
  address: WorkspaceAddress | null;
  customerContactDetails: {
    email: string | null;
    phone: string | null;
    notes: string | null;
    contacts: {
      firstName: string;
      lastName: string;
      phone: string | null;
      email: string | null;
      isPrimary: boolean;
    }[];
  } | null;
  selectedVisit: {
    id: string;
    status: string;
    purposeCode: string;
    scheduledStart: string;
    scheduledEnd: string;
    version: number;
    reschedulable: boolean;
    requestFollowUpAllowed: boolean;
    allowedStatusTransitions: string[];
    completionAllowed: boolean;
    addUpdateAllowed: boolean;
    fieldActionable: boolean;
  } | null;
  visits: {
    id: string;
    sequence: number;
    status: string;
    version: number;
    purposeCode: string;
    outcomeCode: string | null;
    scheduledStart: string | null;
    scheduledEnd: string | null;
    technicians: WorkspaceTechnician[];
  }[];
}
