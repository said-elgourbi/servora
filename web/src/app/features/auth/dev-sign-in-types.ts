export type DevSignInRole = 'manager' | 'technician';
export interface DevSignInAccount {
  readonly role: DevSignInRole;
  readonly email: string;
  readonly password: string;
}
