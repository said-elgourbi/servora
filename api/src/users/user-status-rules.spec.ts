import { USER_STATUSES } from './user.types.js';
import { canUserSignIn } from './user-status-rules.js';

describe('sign-in eligibility by account status', () => {
  it('allows an ACTIVE account to sign in', () => {
    expect(canUserSignIn('ACTIVE')).toBe(true);
  });

  it('refuses an INACTIVE account', () => {
    expect(canUserSignIn('INACTIVE')).toBe(false);
  });

  it('refuses a SUSPENDED account', () => {
    expect(canUserSignIn('SUSPENDED')).toBe(false);
  });

  it('decides every status in the vocabulary', () => {
    // A new status must be given a deliberate sign-in decision, not a default.
    expect(USER_STATUSES.filter(canUserSignIn)).toEqual(['ACTIVE']);
  });
});
