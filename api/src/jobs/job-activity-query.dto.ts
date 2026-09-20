import { requireEnum } from '../validation/domain-validation.js';
import type { JobActivityOptions } from './job-activity.js';

/*
 * The query `GET /jobs/:id/activity` accepts (`docs/api/job-activity.md` §3.5).
 *
 * The read is ordinary unless a caller explicitly asks for more. `includeRemovedEvidence` is that
 * request: it asks for the audit/history context of evidence a Manager has taken out of ordinary use
 * (`BR-089`, tracker 029 D6d). Express hands a query value over as a string, so the flag is a closed
 * vocabulary of `true`/`false` rather than a loosely coerced boolean — a value this API does not know
 * is refused rather than read as "false" (`BR-042`).
 */

/** The values the `includeRemovedEvidence` flag accepts. */
const ACTIVITY_FLAG_VALUES = ['true', 'false'] as const;

/** Reads a single query value; a repeated parameter is read as its first value. */
function firstValue(value: unknown): unknown {
  return Array.isArray(value) ? value[0] : value;
}

/** Validates untrusted query input into `JobActivityOptions`. */
export function parseJobActivityOptions(input: unknown): JobActivityOptions {
  const source = (input ?? {}) as Record<string, unknown>;
  const includeRemovedEvidence = firstValue(source.includeRemovedEvidence);

  return {
    includeRemovedEvidence:
      includeRemovedEvidence === undefined
        ? false
        : requireEnum(
            includeRemovedEvidence,
            ACTIVITY_FLAG_VALUES,
            'includeRemovedEvidence',
          ) === 'true',
  };
}