import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthSession } from './auth-session';
import { BrowserSessionCoordinator } from './browser-session-coordinator';

const tokens = {
  accessToken: 'access-one',
  accessTokenExpiresAt: '2099-01-01T00:00:00Z',
  sessionId: 'session',
};
const identity = { userId: 'user', permissions: ['customers.view', 'unknown.permission'] };
let session: AuthSession;
let http: HttpTestingController;
const coordinator = {
  epochKey: 'servora.session-epoch',
  epoch: () => '',
  invalidate: vi.fn(),
  logoutPending: () => false,
  setLogoutPending: vi.fn(),
  run: <T>(work: () => Promise<T>) => work(),
};
const tick = async () => {
  await Promise.resolve();
  await Promise.resolve();
};

beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: BrowserSessionCoordinator, useValue: coordinator },
    ],
  });
  session = TestBed.inject(AuthSession);
  http = TestBed.inject(HttpTestingController);
});
afterEach(() => {
  http.verify();
});
async function restore() {
  const pending = session.restore();
  http.expectOne('/auth/web/refresh').flush(tokens);
  await tick();
  const me = http.expectOne('/auth/me');
  expect(me.request.headers.get('Authorization')).toBe('Bearer access-one');
  me.flush(identity);
  await pending;
}
it('does not expose identity or permissions until /me succeeds', async () => {
  const pending = session.restore();
  expect(session.status()).toBe('checking');
  expect(session.hasPermission('customers.view')).toBe(false);
  http.expectOne('/auth/web/refresh').flush(tokens);
  await tick();
  expect(session.token()).toBeNull();
  http.expectOne('/auth/me').flush(identity);
  await pending;
  expect(session.authenticated()).toBe(true);
  expect(session.hasPermission('customers.view')).toBe(true);
  expect(session.hasPermission('unknown.permission')).toBe(false);
});
it('distinguishes rejected credentials from network restoration failure', async () => {
  let pending = session.restore();
  http.expectOne('/auth/web/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
  await pending;
  expect(session.status()).toBe('signed-out');
  pending = session.restore();
  http.expectOne('/auth/web/refresh').error(new ProgressEvent('offline'));
  await pending;
  expect(session.status()).toBe('error');
  expect(session.identity()).toBeNull();
});
it('coalesces simultaneous unauthorized requests and replaces permission state', async () => {
  await restore();
  const first = session.recover('access-one');
  const second = session.recover('access-one');
  expect(first).toBe(second);
  http.expectOne('/auth/web/refresh').flush({ ...tokens, accessToken: 'access-two' });
  await tick();
  http.expectOne('/auth/me').flush({ ...identity, permissions: [] });
  expect(await first).toBe('access-two');
  expect(session.hasPermission('customers.view')).toBe(false);
  expect(await session.recover('access-one')).toBe('access-two');
});
it('cannot restore a session after logout during refresh', async () => {
  await restore();
  const refresh = session.recover('access-one');
  const request = http.expectOne('/auth/web/refresh');
  const logout = session.signOut();
  expect(session.identity()).toBeNull();
  expect(session.token()).toBeNull();
  http.expectOne('/auth/web/sign-out').flush(null);
  request.flush({ ...tokens, accessToken: 'late-token' });
  await refresh;
  await logout;
  expect(session.authenticated()).toBe(false);
  expect(session.token()).toBeNull();
});
it('clears protected state across tabs', async () => {
  await restore();
  window.dispatchEvent(
    new StorageEvent('storage', { key: 'servora.session-epoch', newValue: 'new' }),
  );
  expect(session.status()).toBe('signed-out');
  expect(session.identity()).toBeNull();
});
it('keeps local logout on revocation failure and permits retry', async () => {
  await restore();
  const logout = session.signOut();
  http.expectOne('/auth/web/sign-out').error(new ProgressEvent('offline'));
  await logout;
  expect(session.logoutFailed()).toBe(true);
  expect(session.status()).toBe('signed-out');
  const retry = session.signOut();
  http.expectOne('/auth/web/sign-out').flush(null);
  await retry;
  expect(session.logoutFailed()).toBe(false);
});
it('fails closed when /me is unavailable after refresh', async () => {
  const pending = session.restore();
  http.expectOne('/auth/web/refresh').flush(tokens);
  await tick();
  http.expectOne('/auth/me').flush({}, { status: 503, statusText: 'Unavailable' });
  await pending;
  expect(session.status()).toBe('error');
  expect(session.token()).toBeNull();
});
it('cancels sign-in when logout occurs during previous-session revocation', async () => {
  const signingIn = session.signIn('office@example.com', 'secret');
  const rejected = expect(signingIn).rejects.toThrow('Session changed');
  const previousRevocation = http.expectOne('/auth/web/sign-out');
  const logout = session.signOut();
  http.expectOne('/auth/web/sign-out').flush(null);
  previousRevocation.flush(null);
  await rejected;
  await logout;
  http.expectNone('/auth/web/sign-in');
  expect(session.authenticated()).toBe(false);
});
it('retains an established provisional session when refresh is unavailable, but locks on rejection', async () => {
  await restore();
  const generation = session.boundary();
  const disconnected = session.recover('access-one');
  await tick();
  http.expectOne('/auth/web/refresh').error(new ProgressEvent('offline'));
  await expect(disconnected).rejects.toBeDefined();
  expect(session.authenticated()).toBe(true);
  expect(session.boundary()).toBe(generation);
  await tick();
  const rejected = session.recover('access-one');
  await tick();
  http.expectOne('/auth/web/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
  await expect(rejected).rejects.toBeDefined();
  expect(session.authenticated()).toBe(false);
  expect(session.boundary()).toBeGreaterThan(generation);
});
