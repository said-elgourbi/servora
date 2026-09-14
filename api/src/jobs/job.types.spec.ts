import {
  ACTIVE_VISIT_STATUSES,
  JOB_STATUS_TRANSITIONS,
  applicableJobStatusTransitions,
  isPermittedJobStatusTransition,
  isReschedulableVisitStatus,
} from './job.types.js';

/**
 * The Job and Visit lifecycle vocabulary (`BR-058`, `BR-073`).
 *
 * These tables are the validation contract the API enforces and the list a client draws its actions
 * from, so they are asserted directly rather than only through a route.
 */
describe('job lifecycle vocabulary', () => {
  it('permits exactly the transitions BR-058 lists', () => {
    expect(JOB_STATUS_TRANSITIONS).toEqual({
      NEW: ['SCHEDULED', 'CANCELED'],
      SCHEDULED: ['IN_PROGRESS', 'CANCELED'],
      IN_PROGRESS: ['PENDING_REVIEW', 'CANCELED'],
      PENDING_REVIEW: ['COMPLETED', 'CANCELED'],
      COMPLETED: ['NEW'],
      CANCELED: ['NEW'],
    });
  });

  it('refuses a transition BR-058 does not list', () => {
    // Skipping the lifecycle, moving backwards through the work, and "changing" to the status the
    // Job is already in are all not transitions (`BR-058`).
    expect(isPermittedJobStatusTransition('NEW', 'COMPLETED')).toBe(false);
    expect(isPermittedJobStatusTransition('SCHEDULED', 'NEW')).toBe(false);
    expect(isPermittedJobStatusTransition('COMPLETED', 'PENDING_REVIEW')).toBe(
      false,
    );
    expect(isPermittedJobStatusTransition('IN_PROGRESS', 'IN_PROGRESS')).toBe(
      false,
    );
  });

  it('permits reopening a completed or canceled job only to NEW', () => {
    // Reopening never produces IN_PROGRESS: it does not imply work is underway (`BR-063`).
    expect(isPermittedJobStatusTransition('COMPLETED', 'NEW')).toBe(true);
    expect(isPermittedJobStatusTransition('CANCELED', 'NEW')).toBe(true);
    expect(isPermittedJobStatusTransition('COMPLETED', 'IN_PROGRESS')).toBe(
      false,
    );
  });

  it('offers a client every permitted transition except cancellation', () => {
    // Cancellation is a permitted transition whose structured reason catalogue is undefined, so it
    // is not offered until product ownership defines the catalogue (`BR-064`, `BR-042`).
    expect(applicableJobStatusTransitions('NEW')).toEqual(['SCHEDULED']);
    expect(applicableJobStatusTransitions('SCHEDULED')).toEqual([
      'IN_PROGRESS',
    ]);
    expect(applicableJobStatusTransitions('PENDING_REVIEW')).toEqual([
      'COMPLETED',
    ]);
    expect(applicableJobStatusTransitions('COMPLETED')).toEqual(['NEW']);
    expect(applicableJobStatusTransitions('CANCELED')).toEqual(['NEW']);
    for (const from of Object.keys(JOB_STATUS_TRANSITIONS)) {
      expect(
        applicableJobStatusTransitions(
          from as keyof typeof JOB_STATUS_TRANSITIONS,
        ),
      ).not.toContain('CANCELED');
    }
  });

  it('treats exactly the scheduled and started Visit statuses as active work', () => {
    // A DRAFT Visit is not scheduled work, and a COMPLETED, CANCELED or NO_SHOW Visit is historical
    // (`BR-060`, `BR-061`).
    expect([...ACTIVE_VISIT_STATUSES]).toEqual([
      'SCHEDULED',
      'EN_ROUTE',
      'ON_SITE',
      'IN_PROGRESS',
    ]);
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
      'NO_SHOW',
    ] as const) {
      expect(isReschedulableVisitStatus(status)).toBe(false);
    }
  });
});
