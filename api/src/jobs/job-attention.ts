import {
  ACTIVE_VISIT_STATUSES,
  type JobStatus,
  type VisitOutcomeCode,
  type VisitStatus,
} from './job.types.js';

export const JOB_ATTENTION_CODES = [
  'FOLLOW_UP_NEEDS_SCHEDULING',
  'PARTS_REQUIRED',
  'UNABLE_TO_COMPLETE',
] as const;

export type JobAttentionCode = (typeof JOB_ATTENTION_CODES)[number];

export interface JobAttention {
  readonly code: JobAttentionCode;
  readonly relevantVisitId: string;
  readonly reasonCode: string | null;
}

export interface JobAttentionVisit {
  readonly visitId: string;
  readonly sequence: number;
  readonly status: VisitStatus;
  readonly outcomeCode: VisitOutcomeCode | null;
}

export function deriveJobAttention(input: {
  readonly jobStatus: JobStatus;
  readonly visits: readonly JobAttentionVisit[];
}): JobAttention[] {
  if (input.jobStatus !== 'ACTIVE') {
    return [];
  }

  const latestCompleted = [...input.visits]
    .filter(
      (visit) => visit.status === 'COMPLETED' && visit.outcomeCode !== null,
    )
    .sort((left, right) => right.sequence - left.sequence)[0];

  if (latestCompleted === undefined || latestCompleted.outcomeCode === null) {
    return [];
  }

  switch (latestCompleted.outcomeCode) {
    case 'NEEDS_FOLLOW_UP':
      return hasFutureActionableVisit(input.visits, latestCompleted.sequence)
        ? []
        : [
            {
              code: 'FOLLOW_UP_NEEDS_SCHEDULING',
              relevantVisitId: latestCompleted.visitId,
              reasonCode: null,
            },
          ];
    case 'NEEDS_PARTS':
      return [
        {
          code: 'PARTS_REQUIRED',
          relevantVisitId: latestCompleted.visitId,
          reasonCode: null,
        },
      ];
    case 'UNABLE_TO_COMPLETE':
      return [
        {
          code: 'UNABLE_TO_COMPLETE',
          relevantVisitId: latestCompleted.visitId,
          reasonCode: null,
        },
      ];
    default:
      return [];
  }
}

function hasFutureActionableVisit(
  visits: readonly JobAttentionVisit[],
  afterSequence: number,
): boolean {
  return visits.some(
    (visit) =>
      visit.sequence > afterSequence &&
      (ACTIVE_VISIT_STATUSES as readonly VisitStatus[]).includes(visit.status),
  );
}
