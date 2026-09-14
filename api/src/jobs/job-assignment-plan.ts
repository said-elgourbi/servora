import type { AssignmentRoleCode } from './job.types.js';

/*
 * The Visit assignment-change plan (`BR-068`, `BR-069`).
 *
 * Assignment changes are computed here, as a pure function over the crew a Visit carries and the
 * crew the caller stated, so the rule that produces the history is testable without a database. The
 * API's assignment request always states the whole crew, so the plan is a diff between two complete
 * sets — never a partial "add this one" instruction that would leave the one-Lead invariant
 * ambiguous.
 */

/** One recorded assignment fact, in the vocabulary `visit_technician_history` stores (`BR-069`). */
export interface AssignmentChange {
  readonly membershipId: string;
  readonly event: 'ASSIGNED' | 'REMOVED' | 'ROLE_CHANGED';
  /** The role the technician holds after the change; absent for a removal. */
  readonly roleCode: AssignmentRoleCode | null;
  /** The role the technician held before the change; absent for an addition. */
  readonly previousRoleCode: AssignmentRoleCode | null;
}

/**
 * The changes that turn [current] into [requested], in the order they must be applied.
 *
 * The order is part of the contract rather than a detail: `visit_technicians` allows exactly one
 * `LEAD` per Visit, so the outgoing Lead has to be removed or demoted **before** the incoming Lead is
 * written. Removals come first, then demotions, then additions, then promotions. Within one action
 * that is exactly `BR-069`'s shape: removing the Lead and choosing the next one is one user action
 * producing recorded facts, with no promotion the system invented.
 *
 * A technician who keeps the same role is not a change and produces no history row.
 */
export function planAssignmentChanges(
  current: readonly {
    readonly membershipId: string;
    readonly roleCode: AssignmentRoleCode;
  }[],
  requested: readonly {
    readonly membershipId: string;
    readonly roleCode: AssignmentRoleCode;
  }[],
): readonly AssignmentChange[] {
  const currentByMembership = new Map(
    current.map((technician) => [technician.membershipId, technician]),
  );
  const requestedByMembership = new Map(
    requested.map((technician) => [technician.membershipId, technician]),
  );

  const removals: AssignmentChange[] = [];
  const demotions: AssignmentChange[] = [];
  const additions: AssignmentChange[] = [];
  const promotions: AssignmentChange[] = [];

  for (const technician of current) {
    if (!requestedByMembership.has(technician.membershipId)) {
      removals.push({
        membershipId: technician.membershipId,
        event: 'REMOVED',
        roleCode: null,
        previousRoleCode: technician.roleCode,
      });
    }
  }

  for (const technician of requested) {
    const existing = currentByMembership.get(technician.membershipId);
    if (existing === undefined) {
      additions.push({
        membershipId: technician.membershipId,
        event: 'ASSIGNED',
        roleCode: technician.roleCode,
        previousRoleCode: null,
      });
      continue;
    }
    if (existing.roleCode === technician.roleCode) {
      continue;
    }
    const change: AssignmentChange = {
      membershipId: technician.membershipId,
      event: 'ROLE_CHANGED',
      roleCode: technician.roleCode,
      previousRoleCode: existing.roleCode,
    };
    if (technician.roleCode === 'LEAD') {
      promotions.push(change);
    } else {
      demotions.push(change);
    }
  }

  return [...removals, ...demotions, ...additions, ...promotions];
}
