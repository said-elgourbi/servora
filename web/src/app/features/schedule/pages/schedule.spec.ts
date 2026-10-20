import { describe, expect, it } from 'vitest';
import { localDate, shiftDate, validDate } from './schedule';
describe('schedule calendar dates', () => {
  it('keeps local dates across month, year and leap-day boundaries', () => {
    expect(shiftDate('2026-12-31', 1)).toBe('2027-01-01');
    expect(shiftDate('2024-03-01', -1)).toBe('2024-02-29');
    expect(shiftDate('2026-03-08', 1)).toBe('2026-03-09');
    expect(localDate(new Date(2026, 9, 20, 23, 59))).toBe('2026-10-20');
  });
  it('rejects missing dates and impossible dates rather than reading another day', () => {
    for (const value of ['', '2026-02-29', '2026-04-31', '2026-13-01', '2026-1-01'])
      expect(validDate(value)).toBe(false);
    expect(validDate('2024-02-29')).toBe(true);
  });
});
