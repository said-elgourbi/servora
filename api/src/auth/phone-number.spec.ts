import { describe, expect, it } from 'vitest';
import { AuthApiError } from './auth-error.js';
import { normalizePhoneNumber } from './phone-number.js';

describe('normalizePhoneNumber', () => {
  it('accepts an E.164 number and returns it unchanged', () => {
    expect(normalizePhoneNumber('+15145550100')).toBe('+15145550100');
  });

  it('supports numbers beyond the North American numbering plan', () => {
    expect(normalizePhoneNumber('+442071838750')).toBe('+442071838750');
    expect(normalizePhoneNumber('+33612345678')).toBe('+33612345678');
  });

  it('accepts a number a person typed, by removing meaningless separators', () => {
    expect(normalizePhoneNumber(' +1 (514) 555-0100 ')).toBe('+15145550100');
    expect(normalizePhoneNumber('+1.514.555.0100')).toBe('+15145550100');
    expect(normalizePhoneNumber('+1/514/555/0100')).toBe('+15145550100');
  });

  it.each([
    ['without a country code', '5145550100'],
    ['with a national trunk prefix only', '05145550100'],
    ['with a leading zero country code', '+01514555010'],
    ['too short', '+1514555'],
    ['too long', '+1514555010012345678'],
    ['with letters', '+1514555ABCD'],
    ['empty', '   '],
    ['not a string', 5145550100],
  ])('rejects a number %s', (_reason, value) => {
    expect(() => normalizePhoneNumber(value)).toThrowError(AuthApiError);
  });

  it('rejects without echoing the submitted value', () => {
    const submitted = '+1514555999999999';

    try {
      normalizePhoneNumber(submitted);
      throw new Error('expected a rejection');
    } catch (error) {
      expect(error).toBeInstanceOf(AuthApiError);
      // A phone number is an identifier: a rejection must not reflect it back (`BR-044`).
      expect(
        JSON.stringify((error as AuthApiError).getResponse()),
      ).not.toContain(submitted);
      expect((error as AuthApiError).code).toBe('VALIDATION_FAILED');
    }
  });

  it('names the caller-supplied field so two phone fields stay distinguishable', () => {
    try {
      normalizePhoneNumber('nope', 'device.phone');
      throw new Error('expected a rejection');
    } catch (error) {
      expect(JSON.stringify((error as AuthApiError).getResponse())).toContain(
        'device.phone',
      );
    }
  });
});
