import { describe, expect, it } from 'vitest';
import type { VisitStatus } from '../jobs/job.types.js';
import { isVisitOverdue } from './home-visit-conditions.js';

/**
 * The one derived Visit condition both home reads present (`BR-072`, `BR-074`).
 *
 * It is asserted here rather than in either read's spec, because both reads ask this same question
 * and must not be able to answer it differently (`BR-041`). The clock is pinned so the assertion
 * does not depend on when the test runs.
 */
const NOW = new Date('2026-09-14T15:00:00.000Z');

function visit(
  visitStatus: VisitStatus,
  scheduledEnd: Date,
): { readonly visitStatus: VisitStatus; readonly scheduledEnd: Date } {
  return { visitStatus, scheduledEnd };
}

const ENDED = new Date('2026-09-14T14:00:00.000Z');
const STILL_OPEN = new Date('2026-09-14T16:00:00.000Z');

describe('isVisitOverdue', () => {
  it('reports a Visit still SCHEDULED after its window as overdue', () => {
    expect(isVisitOverdue(visit('SCHEDULED', ENDED), NOW)).toBe(true);
  });

  it('does not report a Visit whose window is still open', () => {
    expect(isVisitOverdue(visit('SCHEDULED', STILL_OPEN), NOW)).toBe(false);
  });

  it('does not report a Visit that has progressed past SCHEDULED', () => {
    // A technician who has started work is not "late"; the status is the authority (`BR-074`).
    expect(isVisitOverdue(visit('EN_ROUTE', ENDED), NOW)).toBe(false);
    expect(isVisitOverdue(visit('ON_SITE', ENDED), NOW)).toBe(false);
    expect(isVisitOverdue(visit('IN_PROGRESS', ENDED), NOW)).toBe(false);
  });

  it('does not report a completed Visit as overdue', () => {
    expect(isVisitOverdue(visit('COMPLETED', ENDED), NOW)).toBe(false);
  });
});
