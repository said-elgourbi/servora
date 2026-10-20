import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthSession } from '../../auth/data-access/auth-session';
import { BrowserSessionCoordinator } from '../../auth/data-access/browser-session-coordinator';
import { FieldSession } from './field-session';
const scope = {
  userId: '11111111-1111-4111-8111-111111111111',
  organizationId: '22222222-2222-4222-8222-222222222222',
  membershipId: '33333333-3333-4333-8333-333333333333',
};
let boundary: ReturnType<typeof signal<number>>;
let authenticated: ReturnType<typeof signal<boolean>>;
let session: FieldSession;
let http: HttpTestingController;
const invalidate = vi.fn();
beforeEach(() => {
  boundary = signal(0);
  authenticated = signal(true);
  invalidate.mockReset();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: AuthSession,
        useValue: {
          boundary,
          authenticated,
          identity: () => ({ userId: scope.userId }),
          token: () => 'access',
        },
      },
      { provide: BrowserSessionCoordinator, useValue: { invalidate } },
    ],
  });
  session = TestBed.inject(FieldSession);
  http = TestBed.inject(HttpTestingController);
});
afterEach(() => http.verify());
it('requires an online context to unlock and fences it at the synchronous session boundary', async () => {
  expect(session.context()).toBeNull();
  const verify = session.verify();
  http.expectOne('/auth/field-context').flush(scope);
  await verify;
  const live = session.lease();
  expect(live()).toBe(true);
  boundary.set(1);
  expect(session.context()).toBeNull();
  expect(live()).toBe(false);
});
it('never unlocks a late context response after logout/account change', async () => {
  const verify = session.verify();
  boundary.set(1);
  authenticated.set(false);
  http.expectOne('/auth/field-context').flush(scope);
  await expect(verify).rejects.toThrow();
  expect(session.context()).toBeNull();
});
it('keeps established provisional work on transport failure, then locks and broadcasts an explicit refusal', async () => {
  const initial = session.verify();
  http.expectOne('/auth/field-context').flush(scope);
  await initial;
  const disconnected = session.verify();
  http.expectOne('/auth/field-context').error(new ProgressEvent('offline'));
  await expect(disconnected).rejects.toBeDefined();
  expect(session.context()).toEqual(scope);
  const refused = session.verify();
  http
    .expectOne('/auth/field-context')
    .flush({ code: 'FIELD_CONTEXT_AMBIGUOUS' }, { status: 409, statusText: 'Conflict' });
  await expect(refused).rejects.toBeDefined();
  expect(session.context()).toBeNull();
  expect(invalidate).toHaveBeenCalled();
});
