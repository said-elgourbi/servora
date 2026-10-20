import { describe, expect, it } from 'vitest';
import { deriveJobAttention, type JobAttentionVisit } from './job-attention.js';
import type { JobStatus } from './job.types.js';

function visit(
  overrides: Partial<JobAttentionVisit> & {
    visitId: string;
    sequence: number;
  },
): JobAttentionVisit {
  return {
    status: 'COMPLETED',
    purposeCode: 'WORK',
    outcomeCode: 'RESOLVED',
    ...overrides,
  };
}

function attention(jobStatus: JobStatus, visits: readonly JobAttentionVisit[]) {
  return deriveJobAttention({ jobStatus, visits });
}

describe('deriveJobAttention', () => {
  it('derives follow-up scheduling attention when the latest completed Visit needs follow-up and no future Visit exists', () => {
    expect(
      attention('ACTIVE', [
        visit({
          visitId: 'visit-1',
          sequence: 1,
          outcomeCode: 'NEEDS_FOLLOW_UP',
        }),
      ]),
    ).toEqual([
      {
        code: 'FOLLOW_UP_NEEDS_SCHEDULING',
        relevantVisitId: 'visit-1',
        reasonCode: null,
      },
    ]);
  });

  it('clears follow-up scheduling attention when a future actionable Visit exists', () => {
    expect(
      attention('ACTIVE', [
        visit({
          visitId: 'visit-1',
          sequence: 1,
          outcomeCode: 'NEEDS_FOLLOW_UP',
        }),
        visit({
          visitId: 'visit-2',
          sequence: 2,
          status: 'SCHEDULED',
          outcomeCode: null,
        }),
      ]),
    ).toEqual([]);
  });

  it('derives parts-required attention without falling back to generic scheduling', () => {
    expect(
      attention('ACTIVE', [
        visit({
          visitId: 'visit-1',
          sequence: 1,
          outcomeCode: 'NEEDS_PARTS',
        }),
      ]),
    ).toEqual([
      {
        code: 'PARTS_REQUIRED',
        relevantVisitId: 'visit-1',
        reasonCode: null,
      },
    ]);
  });

  it('clears active attention when the latest completed Visit resolved the Job', () => {
    expect(
      attention('COMPLETED', [
        visit({
          visitId: 'visit-1',
          sequence: 1,
          outcomeCode: 'RESOLVED',
        }),
      ]),
    ).toEqual([]);
  });

  it('does not report stale active-work attention for a canceled Job', () => {
    expect(
      attention('CANCELED', [
        visit({
          visitId: 'visit-1',
          sequence: 1,
          outcomeCode: 'NEEDS_FOLLOW_UP',
        }),
      ]),
    ).toEqual([]);
  });

  it('reports unable-to-complete attention without inventing unsupported reason subcodes', () => {
    expect(
      attention('ACTIVE', [
        visit({
          visitId: 'visit-1',
          sequence: 1,
          outcomeCode: 'UNABLE_TO_COMPLETE',
        }),
      ]),
    ).toEqual([
      {
        code: 'UNABLE_TO_COMPLETE',
        relevantVisitId: 'visit-1',
        reasonCode: null,
      },
    ]);
  });

  it('reports assessment recommendations as office attention without follow-up requests', () => {
    for (const [outcomeCode, code] of [
      ['WORK_RECOMMENDED', 'JOB_WORK_RECOMMENDED'],
      ['QUOTE_REQUIRED', 'JOB_QUOTE_REQUIRED'],
      ['UNABLE_TO_ASSESS', 'JOB_NEEDS_REASSESSMENT'],
    ] as const) {
      expect(
        attention('ACTIVE', [
          visit({
            visitId: 'visit-1',
            sequence: 1,
            outcomeCode,
          }),
        ]),
      ).toEqual([
        {
          code,
          relevantVisitId: 'visit-1',
          reasonCode: null,
        },
      ]);
    }
  });

  it('derives quote attention from the explicit quote workflow when present', () => {
    for (const [state, code] of [
      ['REQUIRED', 'JOB_QUOTE_REQUIRED'],
      ['SENT', 'JOB_CUSTOMER_DECISION_PENDING'],
      ['APPROVED', 'JOB_WORK_READY_TO_SCHEDULE'],
      ['REJECTED', 'JOB_CANCELLATION_REQUIRED'],
    ] as const) {
      expect(
        deriveJobAttention({
          jobStatus: 'ACTIVE',
          quoteWorkflow: { state, sourceVisitId: 'visit-1' },
          visits: [
            visit({
              visitId: 'visit-1',
              sequence: 1,
              purposeCode: 'ASSESSMENT',
              outcomeCode: 'QUOTE_REQUIRED',
            }),
          ],
        }),
      ).toEqual([
        {
          code,
          relevantVisitId: 'visit-1',
          reasonCode: null,
        },
      ]);
    }
  });

  it('clears approved quote scheduling attention once a future Work Visit exists', () => {
    expect(
      deriveJobAttention({
        jobStatus: 'ACTIVE',
        quoteWorkflow: { state: 'APPROVED', sourceVisitId: 'visit-1' },
        visits: [
          visit({
            visitId: 'visit-1',
            sequence: 1,
            purposeCode: 'ASSESSMENT',
            outcomeCode: 'QUOTE_REQUIRED',
          }),
          visit({
            visitId: 'visit-2',
            sequence: 2,
            status: 'SCHEDULED',
            purposeCode: 'WORK',
            outcomeCode: null,
          }),
        ],
      }),
    ).toEqual([]);
  });

  it('does not derive quote attention from the latest Visit outcome when workflow state exists', () => {
    expect(
      deriveJobAttention({
        jobStatus: 'ACTIVE',
        quoteWorkflow: { state: 'SENT', sourceVisitId: 'visit-1' },
        visits: [
          visit({
            visitId: 'visit-1',
            sequence: 1,
            purposeCode: 'ASSESSMENT',
            outcomeCode: 'QUOTE_REQUIRED',
          }),
        ],
      }),
    ).toEqual([
      {
        code: 'JOB_CUSTOMER_DECISION_PENDING',
        relevantVisitId: 'visit-1',
        reasonCode: null,
      },
    ]);
  });

  it('does not report attention for no-work-required assessments', () => {
    expect(
      attention('COMPLETED', [
        visit({
          visitId: 'visit-1',
          sequence: 1,
          outcomeCode: 'NO_WORK_REQUIRED',
        }),
      ]),
    ).toEqual([]);
  });
});
