import { describe, expect, it } from 'vitest';
import type { VisitStatus } from '../jobs/job.types.js';
import {
  isVisitOverdue,
  isVisitStaleStarted,
} from './home-visit-conditions.js';
import { resolveDayWindow } from './home-day.js';

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

describe('isVisitStaleStarted', () => {
  it.each(['EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'] as const)(
    'flags %s at the next local midnight, allowing same-day overruns',
    (status) => {
      const ended = visit(status, new Date('2026-09-30T16:20:00Z'));
      const beforeMidnight = resolveDayWindow(
        'America/Toronto',
        new Date('2026-10-01T03:59:59.999Z'),
      );
      const atMidnight = resolveDayWindow(
        'America/Toronto',
        new Date('2026-10-01T04:00:00Z'),
      );
      expect(isVisitStaleStarted(ended, beforeMidnight.start)).toBe(false);
      expect(isVisitStaleStarted(ended, atMidnight.start)).toBe(true);
    },
  );

  it.each(['DRAFT', 'SCHEDULED', 'COMPLETED', 'CANCELED'] as const)(
    'does not flag %s from a previous day',
    (status) => {
      expect(
        isVisitStaleStarted(
          visit(status, ENDED),
          new Date('2026-09-15T00:00:00Z'),
        ),
      ).toBe(false);
    },
  );

  it('uses the day of scheduled end for overnight work, including an end exactly at midnight', () => {
    const day = resolveDayWindow(
      'America/Toronto',
      new Date('2026-10-01T12:00:00Z'),
    );
    expect(
      isVisitStaleStarted(visit('IN_PROGRESS', day.start), day.start),
    ).toBe(false);
    expect(
      isVisitStaleStarted(
        visit('IN_PROGRESS', new Date('2026-10-01T06:00:00Z')),
        day.start,
      ),
    ).toBe(false);
  });

  it('uses local calendar midnight across a daylight-saving change', () => {
    const day = resolveDayWindow(
      'America/Toronto',
      new Date('2026-11-02T05:00:00Z'),
    );
    expect(day.start.toISOString()).toBe('2026-11-02T05:00:00.000Z');
    expect(
      isVisitStaleStarted(
        visit('IN_PROGRESS', new Date('2026-11-02T04:30:00Z')),
        day.start,
      ),
    ).toBe(true);
  });
});
