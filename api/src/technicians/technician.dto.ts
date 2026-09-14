/*
 * The technician read that makes assignment possible (`BR-024`, `BR-068`).
 *
 * Assigning field work means choosing from the organization's technicians, so the assign sheet needs
 * the list. This is deliberately the smallest read that serves that need: the technician's stable
 * membership id and their name. Detailed technician profile, skills, territory, availability and
 * lifecycle rules are an **OPEN QUESTION** (`BR-024`) and are not modelled here (`BR-042`).
 */

/** One technician a Job's Visit may be assigned to. */
export interface AssignableTechnicianDto {
  /** The organization membership id an assignment names (`BR-068`). */
  membershipId: string;
  /** Resolved from the member's profile; `null` when the member has no profile yet. */
  name: string | null;
}

/** One technician as the read resolves them. */
export interface AssignableTechnician {
  readonly membershipId: string;
  readonly name: string | null;
}

export function toAssignableTechnicianDto(
  technician: AssignableTechnician,
): AssignableTechnicianDto {
  return {
    membershipId: technician.membershipId,
    name: technician.name,
  };
}
