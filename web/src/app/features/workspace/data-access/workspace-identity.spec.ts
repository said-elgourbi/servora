import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthSession } from '../../auth/data-access/auth-session';
import { WorkspaceIdentity } from './workspace-identity';
it('never displays a previous account’s name after session changes', () => {
  const identity = signal({ userId: 'one' });
  const authenticated = signal(true);
  TestBed.configureTestingModule({
    providers: [{ provide: AuthSession, useValue: { identity, authenticated } }],
  });
  const profile = TestBed.inject(WorkspaceIdentity);
  profile.update('Alex');
  expect(profile.displayName()).toBe('Alex');
  authenticated.set(false);
  expect(profile.displayName()).toBeNull();
  TestBed.tick();
  identity.set({ userId: 'two' });
  authenticated.set(true);
  expect(profile.displayName()).toBeNull();
  profile.update('Jo');
  expect(profile.displayName()).toBe('Jo');
});
