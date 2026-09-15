export const CUSTOMER_PERMISSIONS = {
  VIEW: 'customers.view',
  CREATE: 'customers.create',
  EDIT: 'customers.edit',
  ARCHIVE: 'customers.archive',
} as const;

export type CustomerPermission =
  (typeof CUSTOMER_PERMISSIONS)[keyof typeof CUSTOMER_PERMISSIONS];

/**
 * The Property capability catalogue (`BR-085`).
 *
 * Property capabilities are independent of `customers.*`: a Property capability is never implied by
 * a Customer capability, and vice versa. The codes follow the resource-prefixed `resource.action`
 * convention shared by every permission (`BR-006`, `BR-041`).
 *
 * `VIEW`, `CREATE`, `EDIT`, `ARCHIVE` and `DELETE` are all enforced by the Property routes in
 * `PropertiesController` and `CustomersController`.
 */
export const PROPERTY_PERMISSIONS = {
  VIEW: 'properties.view',
  CREATE: 'properties.create',
  EDIT: 'properties.edit',
  ARCHIVE: 'properties.archive',
  DELETE: 'properties.delete',
} as const;

export type PropertyPermission =
  (typeof PROPERTY_PERMISSIONS)[keyof typeof PROPERTY_PERMISSIONS];

/**
 * The Job capability the Job and Visit **action** routes are guarded by.
 *
 * `BR-008` confirms that the Manager default role updates Jobs, and the foundation catalogue already
 * carries a `JOB_UPDATE` capability granted to that role. The Jobs feature has not defined its own
 * `resource.action` capability set — that is an **OPEN QUESTION** recorded in
 * `docs/tracker/018-android-job-actions.md` and `docs/api/job-actions.md` — so the actions reuse the
 * existing `JOB_UPDATE` capability instead of inventing `jobs.*` codes (`BR-006`, `BR-042`).
 *
 * This is the same interim-authorization pattern the Job read already follows, where `GET /jobs/:id`
 * is guarded by the existing `customers.view` capability until the Jobs capability set is defined.
 */
export const JOB_PERMISSIONS = {
  UPDATE: 'JOB_UPDATE',
} as const;

export type JobPermission =
  (typeof JOB_PERMISSIONS)[keyof typeof JOB_PERMISSIONS];

/**
 * The capability the assignable-technician read is guarded by.
 *
 * Assigning work means choosing from the organization's technicians, so reading that list is the
 * existing `TECHNICIAN_VIEW` capability `BR-008` already names as a Manager default. The Technician
 * capability set has not been re-defined as `technicians.*` — that is an **OPEN QUESTION** recorded
 * in `docs/tracker/018-android-job-actions.md`.
 */
export const TECHNICIAN_PERMISSIONS = {
  VIEW: 'TECHNICIAN_VIEW',
} as const;

export type TechnicianPermission =
  (typeof TECHNICIAN_PERMISSIONS)[keyof typeof TECHNICIAN_PERMISSIONS];

/**
 * The evidence capability catalogue (`BR-006`, `BR-015`, `BR-027`; tracker 029 D1/D1b).
 *
 * Evidence is added and read by the person who records it, so it is authorized by capabilities of
 * its own rather than by the Job or Customer capability the Job photo routes used as an interim
 * decision: the default Technician role (`BR-009`) does not hold `JOB_UPDATE` or `customers.view`,
 * and a technician who may photograph a Job must not be refused by a Manager's capability.
 *
 * The capability is **per kind**, so one kind of evidence can be withdrawn from a member without
 * withdrawing the others. `evidence.audio.add` is the agreed extension point for audio evidence but
 * is deliberately **not** in this catalogue: no product rule defines audio yet (`BR-027`,
 * tracker 029 D8), and a capability for a kind that cannot be added would be invented (`BR-042`).
 * When audio lands it is added here, additively, together with the rules that accept it.
 */
export const EVIDENCE_PERMISSIONS = {
  VIEW: 'evidence.view',
  PHOTO_ADD: 'evidence.photo.add',
} as const;

export type EvidencePermission =
  (typeof EVIDENCE_PERMISSIONS)[keyof typeof EVIDENCE_PERMISSIONS];

export type PermissionCode =
  | CustomerPermission
  | PropertyPermission
  | JobPermission
  | TechnicianPermission
  | EvidencePermission
  | (string & {});
