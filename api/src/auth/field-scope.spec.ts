import { describe, it, expect } from 'vitest';
import { readFieldScope } from './field-scope.js';
const headers = {
  'x-servora-field-user': '11111111-1111-4111-8111-111111111111',
  'x-servora-field-organization': '22222222-2222-4222-8222-222222222222',
  'x-servora-field-membership': '33333333-3333-4333-8333-333333333333',
};
describe('field scope transport', () => {
  it('leaves header-free requests unchanged and parses all partition assertions', () => {
    expect(readFieldScope({})).toBeNull();
    expect(readFieldScope(headers)).toEqual({
      userId: headers['x-servora-field-user'],
      organizationId: headers['x-servora-field-organization'],
      membershipId: headers['x-servora-field-membership'],
    });
  });
  it('refuses partial, repeated, comma-merged and malformed assertions', () => {
    for (const value of [
      undefined,
      ['11111111-1111-4111-8111-111111111111'],
      'bad',
      `${headers['x-servora-field-user']}, ${headers['x-servora-field-user']}`,
    ]) {
      expect(() =>
        readFieldScope({ ...headers, 'x-servora-field-user': value }),
      ).toThrow();
    }
  });
});
