/** Office customer contracts. Business records remain online-only in the web client. */
export type CustomerType = "INDIVIDUAL" | "COMPANY";
export type CustomerStatus = "ACTIVE" | "INACTIVE";
export type CustomerStatusFilter = CustomerStatus | "ALL";
export type CustomerJobFilter =
  "ALL" | "HAS_OPEN_JOBS" | "NO_OPEN_JOBS" | "HAS_OVERDUE_VISITS" | "NO_JOBS";
export interface CustomerRecord {
  id: string;
  organizationId: string;
  type: CustomerType;
  displayName: string;
  email: string | null;
  phone: string | null;
  billingEmail: string | null;
  billingPhone: string | null;
  notes: string | null;
  preferredContactMethod: "EMAIL" | "PHONE" | "SMS" | "NONE";
  language: "en-CA" | "fr-CA";
  status: CustomerStatus;
  deletedAt: string | null;
  deletedByMembershipId: string | null;
  deleteReason: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface CustomerSummary extends CustomerRecord {
  propertyCount: number;
  jobCount: number;
}
/** Operational counts derived by the customer list endpoint. */
export interface CustomerListSummary extends CustomerSummary {
  openJobCount: number;
  overdueVisitCount: number;
}
export interface CustomerIndividual {
  customerId: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string | null;
}
export interface CustomerCompany {
  customerId: string;
  legalName: string;
  businessName: string | null;
  taxNumber: string | null;
}
export interface CustomerContact {
  id: string;
  customerId: string;
  firstName: string;
  lastName: string;
  email: string | null;
  phone: string | null;
  role: string | null;
  isPrimary: boolean;
  isBillingContact: boolean;
  isJobContact: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}
export interface CustomerDetails {
  customer: CustomerSummary;
  individual: CustomerIndividual | null;
  company: CustomerCompany | null;
  contacts: CustomerContact[];
}
interface CustomerHeaderInput {
  displayName: string;
  email?: string | null;
  phone?: string | null;
  notes?: string | null;
  preferredContactMethod?: CustomerRecord["preferredContactMethod"];
  language?: CustomerRecord["language"];
}
export type CreateCustomerRequest = CustomerHeaderInput &
  (
    | {
        type: "INDIVIDUAL";
        individual: {
          firstName: string;
          lastName: string;
          dateOfBirth?: string | null;
        };
      }
    | {
        type: "COMPANY";
        company: {
          legalName: string;
          businessName?: string | null;
          taxNumber?: string | null;
        };
      }
  );
/** The browser deliberately sends a complete matching subtype when editing or converting. */
export type SaveCustomerRequest = CreateCustomerRequest & {
  status?: CustomerStatus;
};
export interface CustomerWriteResult {
  customer: CustomerRecord;
  individual?: CustomerIndividual | null;
  company?: CustomerCompany | null;
}
export interface SaveContactRequest {
  firstName: string;
  lastName: string;
  email: string | null;
  phone: string | null;
  isPrimary: boolean;
}
export interface CustomerProperty {
  id: string;
  name: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  province: string;
  postalCode: string;
  country: string;
  status: "ACTIVE" | "ARCHIVED";
  jobCount: number;
  lastServiceAt: string | null;
}
export interface CustomerJob {
  id: string;
  jobNumber: number;
  title: string;
  description: string | null;
  typeCode: string | null;
  status: import("./workspace.js").WorkspaceJobDetails["status"];
  propertyId: string | null;
  propertyAddress: import("./workspace.js").WorkspaceAddress | null;
  scheduledStart: string | null;
  technicians: import("./workspace.js").WorkspaceTechnician[];
}

export type PropertyStatusFilter = "ACTIVE" | "ARCHIVED" | "ALL";
export type CanadianProvinceCode =
  | "AB"
  | "BC"
  | "MB"
  | "NB"
  | "NL"
  | "NS"
  | "NT"
  | "NU"
  | "ON"
  | "PE"
  | "QC"
  | "SK"
  | "YT";
export interface PropertyDetails extends CustomerProperty {
  notes: string | null;
  version: number;
  archivedAt: string | null;
  archiveImpact: { activeJobCount: number; activeVisitCount: number };
  canBePermanentlyDeleted: boolean;
}
export interface SavePropertyRequest {
  name?: string | null;
  addressLine1: string;
  addressLine2?: string | null;
  city: string;
  province: CanadianProvinceCode;
  postalCode: string;
  notes?: string | null;
  expectedVersion?: number;
}
export interface PropertyLifecycleRequest {
  expectedVersion: number;
  clientOperationId: string;
}
