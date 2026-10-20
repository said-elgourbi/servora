import {
  ACTIVE_VISIT_STATUSES,
  JOB_STATUS_TRANSITIONS,
  JOB_QUOTE_WORKFLOW_HISTORY_EVENTS,
  JOB_QUOTE_WORKFLOW_STATES,
  TERMINAL_VISIT_STATUSES,
  ASSESSMENT_VISIT_OUTCOME_CODES,
  VISIT_OUTCOME_CODES,
  VISIT_PURPOSE_CODES,
  VISIT_STATUSES,
  VISIT_STATUS_TRANSITIONS,
  WORK_VISIT_OUTCOME_CODES,
  applicableJobStatusTransitions,
  applicableVisitStatusTransitions,
  canCorrectJobQuoteDecision,
  closesJobWhenLastOpenVisit,
  expectsFollowUpVisit,
  canScheduleAfterFinishedVisits,
  isPermittedJobStatusTransition,
  isVisitOutcomeValidForPurpose,
  isPermittedVisitStatusTransition,
  isReschedulableVisitStatus,
  isTerminalVisitStatus,
  isVisitStatusCorrection,
} from './job.types.js';

describe('job lifecycle vocabulary', () => {
  it('permits exactly the canonical Job transitions', () => {
    expect(JOB_STATUS_TRANSITIONS).toEqual({
      NEW: ['ACTIVE', 'COMPLETED', 'CANCELED'],
      ACTIVE: ['COMPLETED', 'CANCELED'],
      COMPLETED: ['ACTIVE'],
      CANCELED: ['ACTIVE'],
    });
  });

  it('keeps technician working states out of the Job lifecycle', () => {
    expect(isPermittedJobStatusTransition('NEW', 'ACTIVE')).toBe(true);
    expect(isPermittedJobStatusTransition('ACTIVE', 'COMPLETED')).toBe(true);
    expect(isPermittedJobStatusTransition('COMPLETED', 'ACTIVE')).toBe(true);
    expect(applicableJobStatusTransitions('ACTIVE')).toEqual([
      'COMPLETED',
      'CANCELED',
    ]);
  });

  it('treats exactly scheduled and started Visit statuses as active work', () => {
    expect([...ACTIVE_VISIT_STATUSES]).toEqual([
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
    ]);
  });
  it('exposes exactly the canonical Visit purpose codes', () => {
    expect([...VISIT_PURPOSE_CODES]).toEqual(['WORK', 'ASSESSMENT']);
  });

  it('keeps the Assessment quote workflow out of the Job lifecycle vocabulary', () => {
    expect([...JOB_QUOTE_WORKFLOW_STATES]).toEqual([
      'REQUIRED',
      'SENT',
      'APPROVED',
      'REJECTED',
    ]);
    for (const state of JOB_QUOTE_WORKFLOW_STATES) {
      expect(JOB_STATUS_TRANSITIONS).not.toHaveProperty(state);
    }
  });

  it('exposes the append-only Assessment quote workflow history events', () => {
    expect([...JOB_QUOTE_WORKFLOW_HISTORY_EVENTS]).toEqual([
      'REQUIRED',
      'SENT',
      'APPROVED',
      'REJECTED',
      'CORRECTED',
    ]);
  });

  it('allows quote decision corrections only before downstream Work exists', () => {
    const assessment = {
      visitId: 'assessment-1',
      sequence: 1,
      purposeCode: 'ASSESSMENT',
    } as const;

    expect(
      canCorrectJobQuoteDecision({
        state: 'APPROVED',
        sourceVisitId: assessment.visitId,
        visits: [assessment],
      }),
    ).toBe(true);
    expect(
      canCorrectJobQuoteDecision({
        state: 'REJECTED',
        sourceVisitId: assessment.visitId,
        visits: [assessment],
      }),
    ).toBe(true);
    expect(
      canCorrectJobQuoteDecision({
        state: 'SENT',
        sourceVisitId: assessment.visitId,
        visits: [assessment],
      }),
    ).toBe(false);
    expect(
      canCorrectJobQuoteDecision({
        state: 'REQUIRED',
        sourceVisitId: assessment.visitId,
        visits: [assessment],
      }),
    ).toBe(false);
  });

  it('blocks quote decision corrections after approval has produced a Work Visit', () => {
    const assessment = {
      visitId: 'assessment-1',
      sequence: 1,
      purposeCode: 'ASSESSMENT',
    } as const;

    expect(
      canCorrectJobQuoteDecision({
        state: 'APPROVED',
        sourceVisitId: assessment.visitId,
        visits: [
          assessment,
          { visitId: 'work-1', sequence: 2, purposeCode: 'WORK' },
        ],
      }),
    ).toBe(false);
    expect(
      canCorrectJobQuoteDecision({
        state: 'REJECTED',
        sourceVisitId: assessment.visitId,
        visits: [
          { visitId: 'work-before', sequence: 0, purposeCode: 'WORK' },
          assessment,
        ],
      }),
    ).toBe(true);
  });

  it('refuses quote decision corrections when the source Assessment Visit is unavailable', () => {
    expect(
      canCorrectJobQuoteDecision({
        state: 'APPROVED',
        sourceVisitId: 'missing-assessment',
        visits: [{ visitId: 'work-1', sequence: 2, purposeCode: 'WORK' }],
      }),
    ).toBe(false);
  });

  it('classifies completed and canceled Visits as terminal while preserving internal draft', () => {
    expect([...TERMINAL_VISIT_STATUSES]).toEqual(['COMPLETED', 'CANCELED']);
    expect(isTerminalVisitStatus('COMPLETED')).toBe(true);
    expect(isTerminalVisitStatus('CANCELED')).toBe(true);
    expect(isTerminalVisitStatus('DRAFT')).toBe(false);
  });

  it('permits rescheduling a Visit only while it is scheduled', () => {
    expect(isReschedulableVisitStatus('SCHEDULED')).toBe(true);
    for (const status of [
      'DRAFT',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
      'COMPLETED',
      'CANCELED',
    ] as const) {
      expect(isReschedulableVisitStatus(status)).toBe(false);
    }
  });
});

describe('visit lifecycle vocabulary', () => {
  it('permits free movement between working statuses while completion stays explicit', () => {
    expect(VISIT_STATUS_TRANSITIONS).toEqual({
      DRAFT: [],
      SCHEDULED: ['EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'],
      EN_ROUTE: ['SCHEDULED', 'ON_SITE', 'IN_PROGRESS'],
      ON_SITE: ['SCHEDULED', 'EN_ROUTE', 'IN_PROGRESS'],
      IN_PROGRESS: ['SCHEDULED', 'EN_ROUTE', 'ON_SITE'],
      COMPLETED: [],
      CANCELED: [],
    });
  });

  it('moves a Visit directly between working statuses', () => {
    expect(isPermittedVisitStatusTransition('SCHEDULED', 'IN_PROGRESS')).toBe(
      true,
    );
    expect(isPermittedVisitStatusTransition('IN_PROGRESS', 'SCHEDULED')).toBe(
      true,
    );
    expect(isPermittedVisitStatusTransition('ON_SITE', 'EN_ROUTE')).toBe(true);
    expect(isPermittedVisitStatusTransition('SCHEDULED', 'DRAFT')).toBe(false);
    expect(isPermittedVisitStatusTransition('DRAFT', 'COMPLETED')).toBe(false);
  });

  it('records EN_ROUTE to SCHEDULED as the named correction and nothing else', () => {
    expect(isVisitStatusCorrection('EN_ROUTE', 'SCHEDULED')).toBe(true);
    expect(isVisitStatusCorrection('ON_SITE', 'EN_ROUTE')).toBe(false);
    expect(isVisitStatusCorrection('COMPLETED', 'IN_PROGRESS')).toBe(false);
  });

  it('refuses standing still and reaches nothing from canceled', () => {
    for (const status of VISIT_STATUSES) {
      expect(isPermittedVisitStatusTransition(status, status)).toBe(false);
      expect(applicableVisitStatusTransitions(status)).not.toContain(status);
    }
    expect(applicableVisitStatusTransitions('CANCELED')).toEqual([]);
  });

  it('exposes exactly the purpose-aware canonical outcome codes', () => {
    expect([...WORK_VISIT_OUTCOME_CODES]).toEqual([
      'RESOLVED',
      'NEEDS_FOLLOW_UP',
      'NEEDS_PARTS',
      'UNABLE_TO_COMPLETE',
    ]);
    expect([...ASSESSMENT_VISIT_OUTCOME_CODES]).toEqual([
      'NO_WORK_REQUIRED',
      'WORK_RECOMMENDED',
      'QUOTE_REQUIRED',
      'UNABLE_TO_ASSESS',
    ]);
    expect([...VISIT_OUTCOME_CODES]).toEqual([
      'RESOLVED',
      'NEEDS_FOLLOW_UP',
      'NEEDS_PARTS',
      'UNABLE_TO_COMPLETE',
      'NO_WORK_REQUIRED',
      'WORK_RECOMMENDED',
      'QUOTE_REQUIRED',
      'UNABLE_TO_ASSESS',
    ]);
  });

  it('validates outcomes against the Visit purpose', () => {
    expect(isVisitOutcomeValidForPurpose('WORK', 'RESOLVED')).toBe(true);
    expect(isVisitOutcomeValidForPurpose('WORK', 'QUOTE_REQUIRED')).toBe(false);
    expect(
      isVisitOutcomeValidForPurpose('ASSESSMENT', 'WORK_RECOMMENDED'),
    ).toBe(true);
    expect(isVisitOutcomeValidForPurpose('ASSESSMENT', 'RESOLVED')).toBe(false);
  });

  it('derives the follow-up expectation from the outcome code', () => {
    expect(expectsFollowUpVisit('RESOLVED')).toBe(false);
    for (const code of [
      'NEEDS_FOLLOW_UP',
      'NEEDS_PARTS',
      'UNABLE_TO_COMPLETE',
    ] as const) {
      expect(expectsFollowUpVisit(code)).toBe(true);
    }
    expect(expectsFollowUpVisit('WORK_RECOMMENDED')).toBe(false);
    expect(expectsFollowUpVisit('QUOTE_REQUIRED')).toBe(false);
    expect(expectsFollowUpVisit(null)).toBe(false);
  });

  it('treats resolved work and no-work-required assessments as closing outcomes', () => {
    expect(closesJobWhenLastOpenVisit('RESOLVED')).toBe(true);
    expect(closesJobWhenLastOpenVisit('NO_WORK_REQUIRED')).toBe(true);
    for (const code of [
      'NEEDS_FOLLOW_UP',
      'NEEDS_PARTS',
      'UNABLE_TO_COMPLETE',
      'WORK_RECOMMENDED',
      'QUOTE_REQUIRED',
      'UNABLE_TO_ASSESS',
    ] as const) {
      expect(closesJobWhenLastOpenVisit(code)).toBe(false);
    }
    expect(closesJobWhenLastOpenVisit(null)).toBe(false);
  });
});

describe('scheduling after finished Visits', () => {
  const resolved = {
    sequence: 1,
    status: 'COMPLETED' as const,
    outcomeCode: 'RESOLVED' as const,
  };
  it('permits a new attempt after resolved work, no work required or cancellation', () => {
    expect(canScheduleAfterFinishedVisits([resolved])).toBe(true);
    expect(
      canScheduleAfterFinishedVisits([
        { ...resolved, outcomeCode: 'NO_WORK_REQUIRED' },
      ]),
    ).toBe(true);
    expect(
      canScheduleAfterFinishedVisits([
        { ...resolved, status: 'CANCELED', outcomeCode: null },
      ]),
    ).toBe(true);
  });
  it('requires all existing attempts to be finished', () => {
    for (const status of [
      'DRAFT',
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
    ] as const) {
      expect(
        canScheduleAfterFinishedVisits([
          resolved,
          { sequence: 2, status, outcomeCode: null },
        ]),
      ).toBe(false);
    }
  });
  it('keeps Assessment office decisions in their own workflow and uses sequence rather than list order', () => {
    for (const outcomeCode of [
      'QUOTE_REQUIRED',
      'WORK_RECOMMENDED',
      'UNABLE_TO_ASSESS',
    ] as const) {
      expect(
        canScheduleAfterFinishedVisits([
          { ...resolved, sequence: 2, outcomeCode },
          resolved,
        ]),
      ).toBe(false);
    }
    expect(canScheduleAfterFinishedVisits([])).toBe(false);
  });
});
