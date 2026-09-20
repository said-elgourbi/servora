import { SCHEDULE_PERMISSIONS } from '../auth/permissions.js';
import type { PermissionCode } from '../auth/permissions.js';

/*
 * The scope a schedule read is resolved for (`BR-006`, `BR-009`, `ADR-019` D2).
 *
 * A schedule answers "what is happening, for whom". Two audiences reach the same day through it and
 * they must not be answered the same work:
 *
 * - the **office** caller (`schedule.view_org`) reads the operation's day, whichever
 *   technicians it is narrowed to;
 * - the **field** caller (`VISIT_VIEW_ASSIGNED`, `BR-009`) reads their own assigned work, and only
 *   that.
 *
 * The scope is therefore decided from the capability the route was reached with and enforced here,
 * in the service — never in a client, and never by the guard alone (`BR-001`, `BR-007`). It is a
 * third scope that product ownership has not defined: a **team** scope, in which a field member
 * reads a colleague's day without holding the office capability, has no capability in the catalogue
 * to authorize it (`BR-042`). It is recorded as an open question in `docs/api/schedule.md` §5 and
 * `docs/domain/job-visit-domain-model.md` §21 rather than invented here.
 */

/**
 * Which work a schedule read was resolved for.
 *
 * `ORGANIZATION` is the operation's day (`schedule.view_org`); `SELF` is the caller's own assigned
 * work (`VISIT_VIEW_ASSIGNED`). `TEAM` is deliberately absent: it would need a capability that does
 * not exist yet (`BR-042`).
 */
export type ScheduleScopeKind = 'ORGANIZATION' | 'SELF';

export interface ScheduleScope {
  readonly kind: ScheduleScopeKind;
  /**
   * The organization membership the read was resolved for.
   *
   * It is the caller's own membership, and it is the only identity a `SELF` scope names. A client
   * uses it to recognise the caller among a Visit's crew — never to decide anything about the work
   * (`BR-068`).
   */
  readonly membershipId: string;
}

/** The caller a route was reached with, as the permission guard resolved them. */
export interface ScheduleCaller {
  readonly membershipId: string;
  readonly permissions: readonly PermissionCode[];
}

/**
 * The scope [caller] reads the schedule in.
 *
 * The office capability is what widens the read to the operation's day, because that is the
 * capability the API already authorizes the organization's Job and Visit data with
 * (`GET /customers/:id/jobs`, `GET /home/manager`, `docs/api/schedule.md` §2). Every other caller the
 * route admits is a field caller and reads their own work (`BR-009`).
 */
export function resolveScheduleScope(caller: ScheduleCaller): ScheduleScope {
  return {
    kind: caller.permissions.includes(SCHEDULE_PERMISSIONS.VIEW_ORG)
      ? 'ORGANIZATION'
      : 'SELF',
    membershipId: caller.membershipId,
  };
}

/** Whether the resolved scope reaches [membershipId]'s work. */
export function scheduleScopeReaches(
  scope: ScheduleScope,
  membershipId: string,
): boolean {
  return scope.kind === 'ORGANIZATION' || scope.membershipId === membershipId;
}

/**
 * Whether every requested technician is work [scope] reaches.
 *
 * The filter is authorized, not assumed: a `SELF` caller asking for a colleague's day is asking for
 * work their capability does not cover, and the read is refused rather than answered with a
 * different technician's day substituted for the one that was named (`BR-007`, `BR-042`). An empty
 * list names nobody, so it is always within scope — and for `SELF` it means "my own", which is the
 * scope's own answer rather than a widened one.
 */
export function scheduleScopeAdmitsFilter(
  scope: ScheduleScope,
  requestedMembershipIds: readonly string[],
): boolean {
  return requestedMembershipIds.every((membershipId) =>
    scheduleScopeReaches(scope, membershipId),
  );
}

/**
 * The memberships the day is narrowed to once the scope is applied.
 *
 * A `SELF` scope is the caller's own membership, whatever the request asked for: the filter can
 * only narrow the read, and the scope is what decides whose work the day may hold at all. An
 * `ORGANIZATION` scope keeps the request's own filter, where an empty list is the whole
 * organization (`BR-068`).
 */
export function scheduleScopedMembershipIds(
  scope: ScheduleScope,
  requestedMembershipIds: readonly string[],
): readonly string[] {
  return scope.kind === 'SELF' ? [scope.membershipId] : requestedMembershipIds;
}
