/**
 * A member's name, resolved the one way the whole API resolves it.
 *
 * Two reads present the same member — a Visit's crew (`BR-068`) and the technicians a Job can be
 * assigned to (`BR-024`) — and a name composed differently in each would let the two disagree
 * (`BR-041`). The profile's `display_name` wins; a profile that only has parts is composed from
 * them; a member with no profile contributes no name rather than a fabricated one (`BR-020`).
 */
export function memberName(row: {
  readonly displayName: string | null;
  readonly firstName: string | null;
  readonly lastName: string | null;
}): string | null {
  if (row.displayName !== null) {
    return row.displayName;
  }
  const composed = [row.firstName, row.lastName]
    .filter((part): part is string => part !== null)
    .join(' ')
    .trim();
  return composed.length === 0 ? null : composed;
}
