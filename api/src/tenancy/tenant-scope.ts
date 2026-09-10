/**
 * Servora tenancy rule.
 *
 * `organization_id` is the tenant boundary for every organisation-owned entity
 * (customers today; jobs, technicians and later entities as they are added).
 *
 * An organisation-owned row must always be addressed by the composite key
 * `(organizationId, id)` — never by `id` alone. Fetching by primary key only is
 * a cross-tenant data-leak defect.
 *
 * The data-access layer takes this scope explicitly so the constraint is visible
 * at every call site and can be unit-tested.
 */
export interface OrganizationScope {
  readonly organizationId: string;
}
