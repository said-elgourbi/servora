import {
  ACTIVE_VISIT_STATUSES,
  type JobStatus,
  type JobQuoteWorkflowState,
  type VisitOutcomeCode,
  type VisitPurposeCode,
  type VisitStatus,
} from './job.types.js';

export const JOB_ATTENTION_CODES = [
  'FOLLOW_UP_NEEDS_SCHEDULING',
  'PARTS_REQUIRED',
  'UNABLE_TO_COMPLETE',
  'JOB_WORK_RECOMMENDED',
  'JOB_QUOTE_REQUIRED',
  'JOB_CUSTOMER_DECISION_PENDING',
  'JOB_WORK_READY_TO_SCHEDULE',
  'JOB_CANCELLATION_REQUIRED',
  'JOB_NEEDS_REASSESSMENT',
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
  readonly purposeCode?: VisitPurposeCode;
  readonly outcomeCode: VisitOutcomeCode | null;
}

export interface JobQuoteWorkflowAttentionState {
  readonly state: JobQuoteWorkflowState;
  readonly sourceVisitId: string;
}

export function deriveJobAttention(input: {
  readonly jobStatus: JobStatus;
  readonly visits: readonly JobAttentionVisit[];
  readonly quoteWorkflow?: JobQuoteWorkflowAttentionState | null;
}): JobAttention[] {
  if (input.jobStatus !== 'ACTIVE') {
    return [];
  }

  if (input.quoteWorkflow !== undefined && input.quoteWorkflow !== null) {
    return deriveQuoteWorkflowAttention(input.quoteWorkflow, input.visits);
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
    case 'WORK_RECOMMENDED':
      return [
        {
          code: 'JOB_WORK_RECOMMENDED',
          relevantVisitId: latestCompleted.visitId,
          reasonCode: null,
        },
      ];
    case 'QUOTE_REQUIRED':
      return [
        {
          code: 'JOB_QUOTE_REQUIRED',
          relevantVisitId: latestCompleted.visitId,
          reasonCode: null,
        },
      ];
    case 'UNABLE_TO_ASSESS':
      return [
        {
          code: 'JOB_NEEDS_REASSESSMENT',
          relevantVisitId: latestCompleted.visitId,
          reasonCode: null,
        },
      ];
    default:
      return [];
  }
}

function deriveQuoteWorkflowAttention(
  quoteWorkflow: JobQuoteWorkflowAttentionState,
  visits: readonly JobAttentionVisit[],
): JobAttention[] {
  switch (quoteWorkflow.state) {
    case 'REQUIRED':
      return [
        {
          code: 'JOB_QUOTE_REQUIRED',
          relevantVisitId: quoteWorkflow.sourceVisitId,
          reasonCode: null,
        },
      ];
    case 'SENT':
      return [
        {
          code: 'JOB_CUSTOMER_DECISION_PENDING',
          relevantVisitId: quoteWorkflow.sourceVisitId,
          reasonCode: null,
        },
      ];
    case 'APPROVED':
      return hasFutureActionableWorkVisit(visits, quoteWorkflow.sourceVisitId)
        ? []
        : [
            {
              code: 'JOB_WORK_READY_TO_SCHEDULE',
              relevantVisitId: quoteWorkflow.sourceVisitId,
              reasonCode: null,
            },
          ];
    case 'REJECTED':
      return [
        {
          code: 'JOB_CANCELLATION_REQUIRED',
          relevantVisitId: quoteWorkflow.sourceVisitId,
          reasonCode: null,
        },
      ];
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

function hasFutureActionableWorkVisit(
  visits: readonly JobAttentionVisit[],
  sourceVisitId: string,
): boolean {
  const sourceVisit = visits.find((visit) => visit.visitId === sourceVisitId);
  if (sourceVisit === undefined) {
    return false;
  }
  return visits.some(
    (visit) =>
      visit.sequence > sourceVisit.sequence &&
      visit.purposeCode === 'WORK' &&
      (ACTIVE_VISIT_STATUSES as readonly VisitStatus[]).includes(visit.status),
  );
}
