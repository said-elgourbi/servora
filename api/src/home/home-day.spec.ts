import { describe, expect, it } from 'vitest';
import {
  DEFAULT_HOME_TIME_ZONE,
  normaliseTimeZone,
  resolveDayWindow,
} from './home-day.js';

/**
 * The local-day window a home read is resolved for.
 *
 * The window decides which Visits count as "today", so it is asserted directly: the boundary
 * instants of a day in a zone whose offset is not UTC, and a day that contains a daylight-saving
 * transition.
 */
describe('resolveDayWindow', () => {
  it('resolves the local day in the requested zone, not in UTC', () => {
    // 02:30 UTC on 2026-06-15 is 22:30 on 2026-06-14 in Toronto (EDT, UTC-4).
    const window = resolveDayWindow(
      'America/Toronto',
      new Date('2026-06-15T02:30:00.000Z'),
    );

    expect(window.timeZone).toBe('America/Toronto');
    expect(window.start.toISOString()).toBe('2026-06-14T04:00:00.000Z');
    expect(window.end.toISOString()).toBe('2026-06-15T04:00:00.000Z');
  });

  it('resolves a winter day with the zone offset in effect', () => {
    const window = resolveDayWindow(
      'America/Toronto',
      new Date('2026-01-15T12:00:00.000Z'),
    );

    // EST is UTC-5, so midnight local is 05:00 UTC.
    expect(window.start.toISOString()).toBe('2026-01-15T05:00:00.000Z');
    expect(window.end.toISOString()).toBe('2026-01-16T05:00:00.000Z');
  });

  it('resolves a day that crosses a daylight-saving change to 24 hours of local time', () => {
    // 2026-03-08 is the spring-forward date in America/Toronto: the local day is 23 hours long.
    const window = resolveDayWindow(
      'America/Toronto',
      new Date('2026-03-08T15:00:00.000Z'),
    );

    expect(window.start.toISOString()).toBe('2026-03-08T05:00:00.000Z');
    expect(window.end.toISOString()).toBe('2026-03-09T04:00:00.000Z');
  });

  it('resolves UTC as a fixed-offset zone', () => {
    const window = resolveDayWindow(
      'UTC',
      new Date('2026-06-15T23:59:59.000Z'),
    );

    expect(window.start.toISOString()).toBe('2026-06-15T00:00:00.000Z');
    expect(window.end.toISOString()).toBe('2026-06-16T00:00:00.000Z');
  });

  it('resolves a month boundary without a hand-rolled calendar', () => {
    const window = resolveDayWindow(
      'UTC',
      new Date('2026-12-31T08:00:00.000Z'),
    );

    expect(window.start.toISOString()).toBe('2026-12-31T00:00:00.000Z');
    expect(window.end.toISOString()).toBe('2027-01-01T00:00:00.000Z');
  });

  it('is half-open, so a Visit at the day boundary belongs to exactly one day', () => {
    const day = resolveDayWindow('UTC', new Date('2026-06-15T08:00:00.000Z'));
    const next = resolveDayWindow('UTC', new Date('2026-06-16T08:00:00.000Z'));

    expect(day.end.getTime()).toBe(next.start.getTime());
  });

  it('resolves the same boundaries whatever fraction of a second the request arrives in', () => {
    // A request carries the millisecond it was made at, and the boundaries must not move with it.
    const atWholeSecond = resolveDayWindow(
      'America/Toronto',
      new Date('2026-09-14T15:00:00.000Z'),
    );
    const atFraction = resolveDayWindow(
      'America/Toronto',
      new Date('2026-09-14T15:00:00.987Z'),
    );

    expect(atFraction.start.toISOString()).toBe(
      atWholeSecond.start.toISOString(),
    );
    expect(atFraction.end.toISOString()).toBe(atWholeSecond.end.toISOString());
    expect(atWholeSecond.start.toISOString()).toBe('2026-09-14T04:00:00.000Z');
  });
});

describe('normaliseTimeZone', () => {
  it('accepts a canonical IANA identifier unchanged', () => {
    expect(normaliseTimeZone('America/Toronto')).toBe('America/Toronto');
    expect(normaliseTimeZone('Europe/Paris')).toBe('Europe/Paris');
  });

  it('normalises the fixed offset a device reports into one this runtime resolves', () => {
    // Android reports `GMT+05:30` for an offset with no named zone.
    expect(normaliseTimeZone('GMT+05:30')).toBe('+05:30');
    expect(normaliseTimeZone('UTC-05:00')).toBe('-05:00');
    expect(normaliseTimeZone('-0500')).toBe('-05:00');
  });

  it('trims surrounding whitespace', () => {
    expect(normaliseTimeZone('  America/Toronto ')).toBe('America/Toronto');
  });

  it('rejects an identifier this runtime cannot resolve', () => {
    expect(normaliseTimeZone('Mars/Olympus_Mons')).toBeNull();
  });

  it('rejects a value that is not a zone identifier', () => {
    expect(normaliseTimeZone(42)).toBeNull();
    expect(normaliseTimeZone(null)).toBeNull();
    expect(normaliseTimeZone('')).toBeNull();
    expect(normaliseTimeZone(' '.repeat(3))).toBeNull();
    expect(normaliseTimeZone('A'.repeat(200))).toBeNull();
  });
});

describe('the default zone', () => {
  it('is UTC, so a request that names no zone is answered rather than guessed', () => {
    expect(DEFAULT_HOME_TIME_ZONE).toBe('UTC');
  });
});
