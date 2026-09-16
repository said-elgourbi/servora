import { DomainValidationError } from '../validation/domain-validation.js';
import { parseJobActivityOptions } from './job-activity-query.dto.js';

/**
 * The query `GET /jobs/:id/activity` accepts (`BR-089`, tracker 029 Phase 6b).
 *
 * The ordinary read excludes evidence that has been removed from ordinary use; the audit/history
 * context asks for it explicitly. The flag is therefore a closed vocabulary rather than a coerced
 * boolean, so a value this API does not know is refused instead of being read as `false` — a caller
 * that asked for the audit context must never be silently answered with the ordinary projection
 * (`BR-042`).
 */
describe('job activity query', () => {
  it('asks for the ordinary projection when no flag is sent', () => {
    expect(parseJobActivityOptions({})).toEqual({
      includeRemovedEvidence: false,
    });
    expect(parseJobActivityOptions(undefined)).toEqual({
      includeRemovedEvidence: false,
    });
  });

  it('reads the explicit flag both ways', () => {
    expect(parseJobActivityOptions({ includeRemovedEvidence: 'true' })).toEqual({
      includeRemovedEvidence: true,
    });
    expect(parseJobActivityOptions({ includeRemovedEvidence: 'false' })).toEqual({
      includeRemovedEvidence: false,
    });
  });

  it('reads a repeated parameter as its first value', () => {
    expect(
      parseJobActivityOptions({ includeRemovedEvidence: ['true', 'false'] }),
    ).toEqual({ includeRemovedEvidence: true });
  });

  it('refuses a value the read does not define', () => {
    expect(() =>
      parseJobActivityOptions({ includeRemovedEvidence: 'yes' }),
    ).toThrow(DomainValidationError);
    expect(() =>
      parseJobActivityOptions({ includeRemovedEvidence: true }),
    ).toThrow(DomainValidationError);
  });
});