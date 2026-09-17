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
 * The evidence capability catalogue (`BR-006`, `BR-015`, `BR-027`, `BR-091`; tracker 029 D1/D1b,
 * `ADR-018` A7).
 *
 * Evidence is added and read by the person who records it, so it is authorized by capabilities of
 * its own rather than by the Job or Customer capability the Job photo routes used as an interim
 * decision: the default Technician role (`BR-009`) does not hold `JOB_UPDATE` or `customers.view`,
 * and a technician who may photograph a Job must not be refused by a Manager's capability.
 *
 * The capability is **per kind**, so one kind of evidence can be withdrawn from a member without
 * withdrawing the others. `evidence.audio.add` was reserved by `ADR-015` D2 and is in this catalogue
 * now that the rules that accept a recording exist (`ADR-018`): a capability for a kind that cannot be
 * added would be invented (`BR-042`), and one for a kind that can be added is what authorizes it.
 *
 * **Taking accepted evidence out of ordinary use** is its own capability, and a Manager-level one
 * (`BR-089`). Evidence is immutable once the API has accepted it (`BR-088`), so the only operation
 * that reaches it is an explicit, audited removal; a technician discards their own unsubmitted draft
 * instead, which is a local action needing no capability. Removal is therefore deliberately **not**
 * implied by `evidence.photo.add` or `evidence.view`, and the default Technician role does not hold
 * it (tracker 029 D6a/D6b).
 */
export const EVIDENCE_PERMISSIONS = {
  VIEW: 'evidence.view',
  PHOTO_ADD: 'evidence.photo.add',
  PHOTO_REMOVE: 'evidence.photo.remove',
  /**
   * Recording an audio note is a kind of its own (`BR-091`, `ADR-018` A7). The code was reserved by
   * `ADR-015` D2 and is created by the slice that accepts a recording, which is the only point at which
   * it could be exercised (`BR-042`).
   */
  AUDIO_ADD: 'evidence.audio.add',
  /**
   * Taking accepted audio evidence out of ordinary use, granted to the default Manager role alone as
   * `evidence.photo.remove` is (`BR-089`, `ADR-018` A7). It is separate from the photo capability because
   * the catalogue is per kind: a member may be trusted with one kind's removals and not the other kind's.
   */
  AUDIO_REMOVE: 'evidence.audio.remove',
} as const;

export type EvidencePermission =
  (typeof EVIDENCE_PERMISSIONS)[keyof typeof EVIDENCE_PERMISSIONS];

/**
 * The field capabilities the default Technician role holds (`BR-009`; `ADR-019` D1, D3).
 *
 * **No capability is invented here.** Migration `0004_woozy_spitfire` already creates these four codes
 * with their bilingual catalogue rows and grants exactly them to the default Technician role
 * (`api/drizzle/migrations/0004_woozy_spitfire.sql:75-78`, `:104-118`); the development seed mirrors the
 * grants. What was missing is the other half `BR-006` and `BR-007` require — a route guarded by them —
 * which is what this catalogue entry makes possible.
 *
 * The spellings are kept as they are rather than re-spelled `visit.*`: renaming capabilities that live
 * roles already hold is a catalogue migration and a `BR-040` change record with no product decision
 * behind it (`ADR-019` D1, open question 6).
 *
 * `VIEW_ASSIGNED` is a **read** capability and is deliberately not implied by the three write ones: a
 * company may let a member act on a Visit it may not read, and the reverse, exactly as the evidence
 * catalogue keeps adding and reading apart (`ADR-015` D2).
 */
export const VISIT_PERMISSIONS = {
  /** Read the Visits a caller is assigned to (`BR-009`) — the capability a field caller reads a Job by (`ADR-019` D2). */
  VIEW_ASSIGNED: 'VISIT_VIEW_ASSIGNED',
  /** Advance the field status of an assigned Visit (`BR-074`, `ADR-019` D4). */
  UPDATE_ASSIGNED_STATUS: 'VISIT_UPDATE_ASSIGNED_STATUS',
  /** Add a note to an assigned Visit (`BR-077`, `ADR-019` D3). */
  ADD_NOTE: 'VISIT_ADD_NOTE',
  /** Record the outcome a completed Visit requires (`BR-077`, `BR-078`, `ADR-019` D3). */
  RECORD_OUTCOME: 'VISIT_RECORD_OUTCOME',
} as const;

export type VisitPermission =
  (typeof VISIT_PERMISSIONS)[keyof typeof VISIT_PERMISSIONS];

export type PermissionCode =
  | CustomerPermission
  | PropertyPermission
  | JobPermission
  | TechnicianPermission
  | EvidencePermission
  | VisitPermission
  | (string & {});
