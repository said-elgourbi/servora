import { describe, expect, it } from 'vitest';
import {
  readScheduleDateQuery,
  readScheduleMembershipsQuery,
} from './schedule-query.js';

/**
 * The schedule read's narrowing parameters (`GET /schedule`).
 *
 * A date the API cannot resolve a day for is refused rather than silently answered for another day,
 * and an id that is not a UUID never reaches the query. The technician parameter may be repeated, so
 * one technician, a group of them and the whole organization are parsed by the same rule.
 */

describe('readScheduleDateQuery', () => {
  it('answers no date when the request names none', () => {
    expect(readScheduleDateQuery({})).toBeNull();
    expect(readScheduleDateQuery(undefined)).toBeNull();
    expect(readScheduleDateQuery({ date: '' })).toBeNull();
  });

  it('accepts a calendar date and trims it', () => {
    expect(readScheduleDateQuery({ date: '2026-09-07' })).toEqual({
      year: 2026,
      month: 9,
      day: 7,
    });
    expect(readScheduleDateQuery({ date: ' 2026-09-07 ' })).toEqual({
      year: 2026,
      month: 9,
      day: 7,
    });
  });

  it('rejects anything that is not the wire form', () => {
    for (const value of [
      '2026-9-7',
      '07/09/2026',
      '2026-09-07T00:00:00Z',
      'September 7, 2026',
      20260907,
    ]) {
      expect(() => readScheduleDateQuery({ date: value })).toThrow(
        /must be a calendar date/,
      );
    }
  });

  it('rejects a date that is not a day in the calendar', () => {
    // The round trip catches both, so the API cannot resolve a window for a day that does not exist.
    expect(() => readScheduleDateQuery({ date: '2026-02-30' })).toThrow(
      /must be a calendar date/,
    );
    expect(() => readScheduleDateQuery({ date: '2026-13-01' })).toThrow(
      /must be a calendar date/,
    );
  });

  it('accepts a leap day', () => {
    expect(readScheduleDateQuery({ date: '2028-02-29' })).toEqual({
      year: 2028,
      month: 2,
      day: 29,
    });
  });
});

describe('readScheduleMembershipsQuery', () => {
  const mike = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
  const sarah = '9c1f9a54-4bd1-4b2f-9a2f-6f2c1f8a1b77';

  it('answers the whole organization when the request names no technician', () => {
    expect(readScheduleMembershipsQuery({})).toEqual([]);
    expect(readScheduleMembershipsQuery({ membershipId: '' })).toEqual([]);
    expect(readScheduleMembershipsQuery({ membershipId: ['', '  '] })).toEqual([]);
  });

  it('accepts the membership id an assignment names', () => {
    expect(readScheduleMembershipsQuery({ membershipId: mike })).toEqual([mike]);
  });

  it('accepts the repeated parameter as the group of technicians it names', () => {
    // A dispatcher's filter is one question with several names in it, and the ids keep the order the
    // caller sent (`BR-068`).
    expect(readScheduleMembershipsQuery({ membershipId: [mike, sarah] })).toEqual([
      mike,
      sarah,
    ]);
    expect(readScheduleMembershipsQuery({ membershipId: [mike] })).toEqual([mike]);
  });

  it('ignores a repeated id and a blank entry inside the list', () => {
    expect(
      readScheduleMembershipsQuery({ membershipId: [mike, '', sarah, mike] }),
    ).toEqual([mike, sarah]);
  });

  it('rejects an id that is not a UUID', () => {
    expect(() => readScheduleMembershipsQuery({ membershipId: 'Mike' })).toThrow(
      /must be a UUID/,
    );
    // One malformed id refuses the request rather than quietly answering for the others, so a filter
    // never narrows to something other than what was asked.
    expect(() =>
      readScheduleMembershipsQuery({ membershipId: [mike, 'Sarah'] }),
    ).toThrow(/must be a UUID/);
  });
});
