export interface TechnicianPermission {
  readonly code: string;
  readonly nameEn: string;
  readonly nameFr: string;
  readonly descriptionEn: string;
  readonly descriptionFr: string;
}

export interface ManagedTechnician {
  readonly membershipId: string;
  readonly userId: string;
  readonly email: string;
  readonly phone: string | null;
  readonly firstName: string;
  readonly lastName: string;
  readonly status: string;
  readonly rolePermissionCodes: readonly string[];
  readonly directPermissionCodes: readonly string[];
}

export interface TechnicianManagement {
  readonly technicians: readonly ManagedTechnician[];
  readonly permissions: readonly TechnicianPermission[];
}

export interface UpdateTechnicianRequest {
  readonly email: string;
  readonly phone: string | null;
  readonly firstName: string;
  readonly lastName: string;
  readonly directPermissionCodes: readonly string[];
}
