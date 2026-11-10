/** Local development shortcuts only. Production builds replace this module with an empty list. */
import type { DevSignInAccount } from './dev-sign-in-types';

export const DEV_SIGN_IN_ACCOUNTS: readonly DevSignInAccount[] = [
  { role: 'manager', email: 'manager@servora.test', password: 'Servora!Manager2026' },
  { role: 'technician', email: 'technician@servora.test', password: 'Servora!Tech2026' },
];
