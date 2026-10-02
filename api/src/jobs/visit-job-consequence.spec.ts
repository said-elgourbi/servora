import {
  VISIT_OUTCOME_CODES,
  VISIT_STATUSES,
  isPermittedJobStatusTransition,
  isTerminalVisitStatus,
  type JobStatus,
  type VisitOutcomeCode,
  type VisitStatus,
} from './job.types.js';
import {
  adHocReconciliationJobConsequence,
  jobStatusConsequenceForVisitTransition,
} from './visit-job-consequence.js';

const OPEN_JOB_STATUSES: readonly JobStatus[] = ['NEW', 'ACTIVE'];

const FIELD_DESTINATIONS: readonly VisitStatus[] = VISIT_STATUSES.filter(
  (status) => status === 'COMPLETED' || !isTerminalVisitStatus(status),
);

describe('the Visit to Job consequence', () => {
  it('activates the Job when a real Visit becomes actionable or worked', () => {
    for (const jobStatus of OPEN_JOB_STATUSES) {
      for (const to of ['SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'] as const) {
        expect(jobStatusConsequenceForVisitTransition(to, null, jobStatus)).toBe(
          'ACTIVE',
        );
      }
    }
  });

  it('completes the Job when a Visit completes as resolved', () => {
    for (const jobStatus of OPEN_JOB_STATUSES) {
      expect(
        jobStatusConsequenceForVisitTransition(
          'COMPLETED',
          'RESOLVED',
          jobStatus,
        ),
      ).toBe('COMPLETED');
    }
  });

  it('keeps the Job active when a resolving completion leaves another Visit open', () => {
    // `BR-062`: a Job is never `COMPLETED` while it still has an open Visit, so a resolving completion
    // closes the Job only when the attempt that just ended was its last remaining work.
    for (const jobStatus of OPEN_JOB_STATUSES) {
      expect(
        jobStatusConsequenceForVisitTransition(
          'COMPLETED',
          'RESOLVED',
          jobStatus,
          { hasOtherOpenVisit: true },
        ),
      ).toBe('ACTIVE');
    }
  });

  it('ignores the open-Visit context for every event that cannot close the Job', () => {
    // The guard is `BR-062`'s alone: no other consequence consults it, so the answer is the same
    // whether or not another Visit is open.
    for (const to of ['SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'] as const) {
      expect(
        jobStatusConsequenceForVisitTransition(to, null, 'ACTIVE', {
          hasOtherOpenVisit: true,
        }),
      ).toBe('ACTIVE');
    }
    for (const outcomeCode of ['NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE'] as const) {
      expect(
        jobStatusConsequenceForVisitTransition(
          'COMPLETED',
          outcomeCode,
          'ACTIVE',
          { hasOtherOpenVisit: true },
        ),
      ).toBe('ACTIVE');
    }
  });

  it('keeps the Job active for every non-resolving outcome', () => {
    const nonResolvingOutcomes: readonly VisitOutcomeCode[] =
      VISIT_OUTCOME_CODES.filter((code) => code !== 'RESOLVED');
    expect(nonResolvingOutcomes).toEqual([
      'NEEDS_FOLLOW_UP',
      'NEEDS_PARTS',
      'UNABLE_TO_COMPLETE',
    ]);

    for (const outcomeCode of nonResolvingOutcomes) {
      expect(
        jobStatusConsequenceForVisitTransition(
          'COMPLETED',
          outcomeCode,
          'ACTIVE',
        ),
      ).toBe('ACTIVE');
    }
  });

  it('does not mutate terminal Jobs through field consequences', () => {
    for (const jobStatus of ['COMPLETED', 'CANCELED'] as const) {
      for (const to of FIELD_DESTINATIONS) {
        for (const outcomeCode of [null, ...VISIT_OUTCOME_CODES]) {
          expect(
            jobStatusConsequenceForVisitTransition(to, outcomeCode, jobStatus),
          ).toBeNull();
        }
      }
    }
  });

  it('only ever produces a permitted Job transition', () => {
    for (const jobStatus of OPEN_JOB_STATUSES) {
      for (const to of FIELD_DESTINATIONS) {
        for (const outcomeCode of [null, ...VISIT_OUTCOME_CODES]) {
          const destination = jobStatusConsequenceForVisitTransition(
            to,
            outcomeCode,
            jobStatus,
          );
          if (destination === null || destination === jobStatus) {
            continue;
          }
          expect(isPermittedJobStatusTransition(jobStatus, destination)).toBe(
            true,
          );
        }
      }
    }
  });
});

describe('the ad-hoc reconciliation Job consequence (`BR-AH-008`)', () => {
  const FOLLOW_UP_OUTCOMES: readonly VisitOutcomeCode[] = [
    'NEEDS_FOLLOW_UP',
    'NEEDS_PARTS',
    'UNABLE_TO_COMPLETE',
  ];

  it('closes an open Job on a resolving outcome with no other Visit open', () => {
    for (const jobStatus of OPEN_JOB_STATUSES) {
      expect(
        adHocReconciliationJobConsequence('RESOLVED', jobStatus, false),
      ).toBe('COMPLETED');
    }
  });

  it('keeps an open Job active when a resolving outcome leaves another Visit open', () => {
    for (const jobStatus of OPEN_JOB_STATUSES) {
      expect(
        adHocReconciliationJobConsequence('RESOLVED', jobStatus, true),
      ).toBe('ACTIVE');
    }
  });

  it('keeps an open Job active for every follow-up-required outcome', () => {
    for (const jobStatus of OPEN_JOB_STATUSES) {
      for (const outcomeCode of FOLLOW_UP_OUTCOMES) {
        expect(
          adHocReconciliationJobConsequence(outcomeCode, jobStatus, false),
        ).toBe('ACTIVE');
      }
    }
  });

  it('leaves a COMPLETED Job completed on a resolving outcome', () => {
    expect(adHocReconciliationJobConsequence('RESOLVED', 'COMPLETED', false)).toBeNull();
  });

  it('moves a CANCELED Job to COMPLETED on a resolving outcome', () => {
    // `BR-AH-008`: the work actually happened, so the request was not truly canceled. This is the
    // privileged exception `BR-AH-006` grants; it is deliberately outside `BR-058`'s transition list.
    expect(adHocReconciliationJobConsequence('RESOLVED', 'CANCELED', false)).toBe(
      'COMPLETED',
    );
  });

  it('moves a terminal Job to ACTIVE for every follow-up-required outcome', () => {
    for (const jobStatus of ['COMPLETED', 'CANCELED'] as const) {
      for (const outcomeCode of FOLLOW_UP_OUTCOMES) {
        expect(
          adHocReconciliationJobConsequence(outcomeCode, jobStatus, false),
        ).toBe('ACTIVE');
      }
    }
  });
});
