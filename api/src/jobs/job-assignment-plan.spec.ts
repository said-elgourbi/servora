import { planAssignmentChanges } from './job-assignment-plan.js';

/**
 * The assignment change plan (`BR-068`, `BR-069`).
 *
 * What is asserted is the recorded history, because that is the part of an assignment change that
 * must not be rewritten: a promotion and a removal are recorded facts, and the one-Lead invariant is
 * held by the order the changes are applied in.
 */
describe('visit assignment changes', () => {
  const mike = { membershipId: 'member-1', roleCode: 'LEAD' as const };
  const sarah = { membershipId: 'member-2', roleCode: 'TECHNICIAN' as const };
  const john = { membershipId: 'member-3', roleCode: 'TECHNICIAN' as const };

  it('records nothing when the crew does not change', () => {
    expect(planAssignmentChanges([mike, sarah], [mike, sarah])).toEqual([]);
  });

  it('records an addition as ASSIGNED with the role the technician now holds', () => {
    expect(planAssignmentChanges([mike], [mike, sarah])).toEqual([
      {
        membershipId: 'member-2',
        event: 'ASSIGNED',
        roleCode: 'TECHNICIAN',
        previousRoleCode: null,
      },
    ]);
  });

  it('records a removal as REMOVED with the role the technician held', () => {
    expect(planAssignmentChanges([mike, sarah], [mike])).toEqual([
      {
        membershipId: 'member-2',
        event: 'REMOVED',
        roleCode: null,
        previousRoleCode: 'TECHNICIAN',
      },
    ]);
  });

  it('records a re-roled technician as ROLE_CHANGED carrying both roles', () => {
    expect(
      planAssignmentChanges(
        [mike, sarah],
        [
          { membershipId: 'member-1', roleCode: 'TECHNICIAN' },
          { membershipId: 'member-2', roleCode: 'LEAD' },
        ],
      ),
    ).toEqual([
      // The outgoing Lead is demoted before the incoming one is promoted, so the one-Lead index is
      // never violated part-way through the change (`BR-068`).
      {
        membershipId: 'member-1',
        event: 'ROLE_CHANGED',
        roleCode: 'TECHNICIAN',
        previousRoleCode: 'LEAD',
      },
      {
        membershipId: 'member-2',
        event: 'ROLE_CHANGED',
        roleCode: 'LEAD',
        previousRoleCode: 'TECHNICIAN',
      },
    ]);
  });

  it('applies removals first and promotions last within one change', () => {
    // `BR-069`'s shape: removing the current Lead and choosing the next one is one user action
    // producing the removal and the promotion, both with the same actor.
    const changes = planAssignmentChanges(
      [mike, sarah],
      [
        { membershipId: 'member-2', roleCode: 'LEAD' },
        { membershipId: 'member-3', roleCode: 'TECHNICIAN' },
      ],
    );

    expect(changes.map((change) => change.event)).toEqual([
      'REMOVED',
      'ASSIGNED',
      'ROLE_CHANGED',
    ]);
    expect(changes[0]?.membershipId).toBe('member-1');
    expect(changes[2]).toMatchObject({
      membershipId: 'member-2',
      roleCode: 'LEAD',
      previousRoleCode: 'TECHNICIAN',
    });
  });

  it('plans from an empty crew and to an empty crew', () => {
    expect(planAssignmentChanges([], [mike])).toEqual([
      {
        membershipId: 'member-1',
        event: 'ASSIGNED',
        roleCode: 'LEAD',
        previousRoleCode: null,
      },
    ]);
    expect(planAssignmentChanges([mike, john], [])).toEqual([
      {
        membershipId: 'member-1',
        event: 'REMOVED',
        roleCode: null,
        previousRoleCode: 'LEAD',
      },
      {
        membershipId: 'member-3',
        event: 'REMOVED',
        roleCode: null,
        previousRoleCode: 'TECHNICIAN',
      },
    ]);
  });
});
