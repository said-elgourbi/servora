import { TestBed } from '@angular/core/testing';
import { formatTimeInstant, TimeFormatPreference } from './time-format';

afterEach(() => localStorage.clear());

it('parses and formats the same canonical minutes in both display formats', () => {
  const preference = TestBed.inject(TimeFormatPreference);
  expect(preference.parseMinutes('14:30', '24H')).toBe(870);
  expect(preference.formatMinutes(870, '12H')).toBe('02:30 PM');
  expect(preference.parseMinutes('02:30 PM', '12H')).toBe(870);
  expect(preference.formatMinutes(870, '24H')).toBe('14:30');
  expect(preference.parseMinutes('14:30', '12H')).toBeNull();
});

it('persists the selected display format across service instances', () => {
  const first = TestBed.inject(TimeFormatPreference);
  first.setFormat('12H');
  expect(localStorage.getItem('servora.time-format')).toBe('12H');
  TestBed.resetTestingModule();
  expect(TestBed.inject(TimeFormatPreference).format()).toBe('12H');
});

it('formats instants in the requested timezone without changing the instant', () => {
  const instant = '2026-11-05T19:30:00.000Z';
  expect(formatTimeInstant(instant, 'en-CA', 'America/Toronto', '24H')).toBe('14:30');
  expect(formatTimeInstant(instant, 'en-CA', 'America/Toronto', '12H')).toBe('02:30 PM');
});
