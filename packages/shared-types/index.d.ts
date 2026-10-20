/** Public wire contracts only. No Angular, NestJS, database, or credential storage code. */
export type AuthSessionPlatform = "ANDROID" | "WEB";
export interface AuthDeviceContext {
  readonly platform: AuthSessionPlatform;
  readonly deviceId: string;
  readonly deviceName: string | null;
  readonly appVersion: string | null;
}
export interface SignInRequestDto {
  readonly email: string;
  readonly password: string;
  readonly device: AuthDeviceContext;
}
export interface SmsOtpRequestDto {
  readonly phone: string;
}
export interface SmsOtpVerifyRequestDto extends SmsOtpRequestDto {
  readonly code: string;
  readonly device: AuthDeviceContext;
}
export interface AuthMeDto {
  readonly userId: string;
  readonly permissions: readonly string[];
}
export interface BrowserAuthResponse {
  readonly accessToken: string;
  readonly accessTokenExpiresAt: string;
  readonly sessionId: string;
}
export type AuthErrorCode =
  | "VALIDATION_FAILED"
  | "INVALID_CREDENTIALS"
  | "REFRESH_TOKEN_INVALID"
  | "UNAUTHENTICATED"
  | "FORBIDDEN"
  | "RESET_CODE_INVALID"
  | "OTP_CODE_INVALID"
  | "TOO_MANY_REQUESTS";
export interface AuthErrorBody {
  readonly statusCode: number;
  readonly code: AuthErrorCode;
  readonly message: string;
}

/** Explicitly supported capability codes; future server codes stay unrecognized in older clients. */
export type KnownPermissionCode = keyof typeof import("./permissions.json");

export type {
  WorkspaceAddress,
  WorkspaceTechnician,
  HomeVisitDto,
  HomeAttentionDto,
  HomeDto,
  ManagerHomeResponse,
  TechnicianHomeResponse,
  WorkspaceJobDetails,
} from "./workspace.js";
export type { FieldContextDto, FieldScopeErrorCode } from "./field.js";
export type {
  FieldVisitDto,
  FieldJobDetails,
  FieldActivityEvent,
  FieldActivityDto,
  FieldFollowUpDto,
} from "./field.js";

export type {
  CustomerType,
  CustomerStatus,
  CustomerStatusFilter,
  CustomerJobFilter,
  CustomerRecord,
  CustomerSummary,
  CustomerListSummary,
  CustomerIndividual,
  CustomerCompany,
  CustomerContact,
  CustomerDetails,
  CreateCustomerRequest,
  SaveCustomerRequest,
  CustomerWriteResult,
  SaveContactRequest,
  CustomerProperty,
  CustomerJob,
} from "./customers.js";
export type {
  PropertyStatusFilter,
  CanadianProvinceCode,
  PropertyDetails,
  SavePropertyRequest,
  PropertyLifecycleRequest,
} from "./customers.js";

export type {
  ScheduleVisitRequest,
  AssignableTechnician,
  VisitScheduleConflict,
  CreateJobRequest,
  ChangeJobStatusRequest,
  OfficeJobDetails,
  JobActivityEvent,
  JobActivity,
} from "./jobs.js";

export type { ScheduleResponse, ScheduleVisit } from './schedule.js';
