// Generic duration parsing for environment-provided configuration values.
//
// Durations are written as `<amount><unit>` (`500ms`, `15m`, `2h`, `30d`) so each
// value documents its own unit instead of relying on an implicit convention per
// variable. A malformed duration throws: a misconfigured lifetime must stop the
// process rather than silently fall back to a different security posture.

export type DurationUnit = 'ms' | 's' | 'm' | 'h' | 'd';

const MILLISECONDS_PER_UNIT: Record<DurationUnit, number> = {
  ms: 1,
  s: 1_000,
  m: 60_000,
  h: 3_600_000,
  d: 86_400_000,
};

const DURATION_PATTERN = /^(\d+)(ms|s|m|h|d)$/;

/** Parses a `<amount><unit>` duration into whole milliseconds. */
export function parseDuration(value: string, field: string): number {
  const match = DURATION_PATTERN.exec(value.trim());
  if (match === null) {
    throw new Error(
      `Invalid ${field} "${value}". Expected a duration such as 500ms, 15m, 2h or 30d.`,
    );
  }

  const [, amount, unit] = match;
  const milliseconds =
    Number(amount) * MILLISECONDS_PER_UNIT[unit as DurationUnit];
  if (milliseconds <= 0) {
    throw new Error(
      `Invalid ${field} "${value}". The duration must be greater than zero.`,
    );
  }

  return milliseconds;
}
