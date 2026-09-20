import { describe, expect, it } from 'vitest';
import { CUSTOMER_PERMISSIONS, VISIT_PERMISSIONS } from '../auth/permissions.js';
import type { PermissionCode } from '../auth/permissions.js';
import {
  resolveScheduleScope,
  scheduleScopeAdmitsFilter,
  scheduleScopeReaches,
  scheduleScopedMembershipIds,
} from './schedule-scope.js';

/*
 * The scope a schedule read is resolved for (`BR-006`, `BR-009`, `ADR-019` D2).
 *
 * These tests cover the rule the API enforces: the office capability reads the operation's day, the
 * field capability reads the caller's own work, and a filter naming a technician outside the scope
 * is refused rather than quietly answered with somebody else's day (`BR-007`, `BR-042`).
 */
describe('resolveScheduleScope', () => {
  it("reads the operation’s day for a caller holding the office capability", () => {
    expect(
      resolveScheduleScope({
        membershipId: 'member-1',
        permissions: [CUSTOMER_PERMISSIONS.VIEW],
      }),
    ).toEqual({ kind: 'ORGANIZATION', membershipId: 'member-1' });
  });

  it("reads the caller’s own work for a caller holding only the field capability", () => {
    expect(
      resolveScheduleScope({
        membershipId: 'member-7',
        permissions: [VISIT_PERMISSIONS.VIEW_ASSIGNED],
      }),
    ).toEqual({ kind: 'SELF', membershipId: 'member-7' });
  });

  it('stays the field scope for a caller holding the field capabilities alone', () => {
    const permissions: PermissionCode[] = [
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
      VISIT_PERMISSIONS.UPDATE_ASSIGNED_STATUS,
      VISIT_PERMISSIONS.ADD_NOTE,
      VISIT_PERMISSIONS.RECORD_OUTCOME,
      'evidence.photo.add',
    ];
    expect(
      resolveScheduleScope({ membershipId: 'member-7', permissions }).kind,
    ).toBe('SELF');
  });

  it('widens for a member holding both, because the office capability is what widens it', () => {
    expect(
      resolveScheduleScope({
        membershipId: 'member-3',
        permissions: [
          VISIT_PERMISSIONS.VIEW_ASSIGNED,
          CUSTOMER_PERMISSIONS.VIEW,
        ],
      }).kind,
    ).toBe('ORGANIZATION');
  });
});

describe('scheduleScopeReaches', () => {
  it("reaches every membership in the organization scope, including the caller’s own", () => {
    const scope = { kind: 'ORGANIZATION', membershipId: 'member-1' } as const;
    expect(scheduleScopeReaches(scope, 'member-1')).toBe(true);
    expect(scheduleScopeReaches(scope, 'member-2')).toBe(true);
  });

  it("reaches only the caller’s own membership in the field scope", () => {
    const scope = { kind: 'SELF', membershipId: 'member-7' } as const;
    expect(scheduleScopeReaches(scope, 'member-7')).toBe(true);
    expect(scheduleScopeReaches(scope, 'member-2')).toBe(false);
  });
});

describe('scheduleScopeAdmitsFilter', () => {
  const self = { kind: 'SELF', membershipId: 'member-7' } as const;
  const organization = { kind: 'ORGANIZATION', membershipId: 'member-1' } as const;

  it('admits an empty filter, which names nobody', () => {
    expect(scheduleScopeAdmitsFilter(self, [])).toBe(true);
    expect(scheduleScopeAdmitsFilter(organization, [])).toBe(true);
  });

  it('admits the field caller naming their own membership, which narrows nothing', () => {
    expect(scheduleScopeAdmitsFilter(self, ['member-7'])).toBe(true);
  });

  it('refuses the field caller naming a colleague, however many are named', () => {
    expect(scheduleScopeAdmitsFilter(self, ['member-2'])).toBe(false);
    expect(scheduleScopeAdmitsFilter(self, ['member-7', 'member-2'])).toBe(false);
  });

  it('admits any technician for the office scope', () => {
    expect(scheduleScopeAdmitsFilter(organization, ['member-2', 'member-3'])).toBe(
      true,
    );
  });
});

describe('scheduleScopedMembershipIds', () => {
  it("is the caller’s own membership for the field scope, whatever was requested", () => {
    expect(
      scheduleScopedMembershipIds(
        { kind: 'SELF', membershipId: 'member-7' },
        [],
      ),
    ).toEqual(['member-7']);
    expect(
      scheduleScopedMembershipIds(
        { kind: 'SELF', membershipId: 'member-7' },
        ['member-7'],
      ),
    ).toEqual(['member-7']);
  });

  it("keeps the requested filter for the office scope, where an empty list is the organization", () => {
    expect(
      scheduleScopedMembershipIds(
        { kind: 'ORGANIZATION', membershipId: 'member-1' },
        [],
      ),
    ).toEqual([]);
    expect(
      scheduleScopedMembershipIds(
        { kind: 'ORGANIZATION', membershipId: 'member-1' },
        ['member-2'],
      ),
    ).toEqual(['member-2']);
  });
});
