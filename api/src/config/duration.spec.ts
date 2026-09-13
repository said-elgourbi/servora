import { parseDuration } from './duration.js';

describe('parseDuration', () => {
  it.each([
    { value: '500ms', expected: 500 },
    { value: '15s', expected: 15_000 },
    { value: '15m', expected: 900_000 },
    { value: '2h', expected: 7_200_000 },
    { value: '30d', expected: 2_592_000_000 },
  ])('parses $value into milliseconds', ({ value, expected }) => {
    expect(parseDuration(value, 'TEST_LIFETIME')).toBe(expected);
  });

  it('ignores surrounding whitespace', () => {
    expect(parseDuration('  15m  ', 'TEST_LIFETIME')).toBe(900_000);
  });

  it.each(['15', '15 minutes', '1.5h', '-15m', 'm', '', '15M', '0m'])(
    'rejects an invalid duration (%s)',
    (value) => {
      expect(() => parseDuration(value, 'TEST_LIFETIME')).toThrow(
        /Invalid TEST_LIFETIME/,
      );
    },
  );

  it('names the field so a misconfigured variable is easy to locate', () => {
    expect(() => parseDuration('forever', 'SESSION_LIFETIME')).toThrow(
      /Invalid SESSION_LIFETIME "forever"/,
    );
  });
});
