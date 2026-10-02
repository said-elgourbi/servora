import {
  ACTIVE_VISIT_STATUSES,
  JOB_STATUS_TRANSITIONS,
  TERMINAL_VISIT_STATUSES,
  VISIT_OUTCOME_CODES,
  VISIT_STATUSES,
  VISIT_STATUS_TRANSITIONS,
  applicableJobStatusTransitions,
  applicableVisitStatusTransitions,
  expectsFollowUpVisit,
  isPermittedJobStatusTransition,
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
    expect(isPermittedVisitStatusTransition('SCHEDULED', 'IN_PROGRESS')).toBe(true);
    expect(isPermittedVisitStatusTransition('IN_PROGRESS', 'SCHEDULED')).toBe(true);
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

  it('exposes exactly the canonical outcome codes', () => {
    expect([...VISIT_OUTCOME_CODES]).toEqual([
      'RESOLVED',
      'NEEDS_FOLLOW_UP',
      'NEEDS_PARTS',
      'UNABLE_TO_COMPLETE',
    ]);
  });

  it('derives the follow-up expectation from the outcome code', () => {
    expect(expectsFollowUpVisit('RESOLVED')).toBe(false);
    for (const code of ['NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE'] as const) {
      expect(expectsFollowUpVisit(code)).toBe(true);
    }
    expect(expectsFollowUpVisit(null)).toBe(false);
  });
});
