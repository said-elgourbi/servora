/**
 * Whether a write failed because the row (or its unique key) already exists.
 *
 * A replay and a genuine write race produce the same database answer — `23505` — so both services that
 * append evidence read it the same way, from one definition rather than two (`BR-031`).
 */
export function isUniqueViolation(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const direct = (error as { code?: unknown }).code;
  if (direct === '23505') {
    return true;
  }
  const cause = (error as { cause?: unknown }).cause;
  return (
    typeof cause === 'object' &&
    cause !== null &&
    (cause as { code?: unknown }).code === '23505'
  );
}
