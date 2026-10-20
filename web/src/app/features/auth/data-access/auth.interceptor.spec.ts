import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuthSession } from './auth-session';
import { AUTHENTICATED_REQUEST, authInterceptor } from './auth.interceptor';
let http: HttpTestingController;
let client: HttpClient;
const session = { token: () => 'old-access', recover: vi.fn() };
const context = () => new HttpContext().set(AUTHENTICATED_REQUEST, true);
beforeEach(() => {
  session.recover.mockReset();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      { provide: AuthSession, useValue: session },
    ],
  });
  http = TestBed.inject(HttpTestingController);
  client = TestBed.inject(HttpClient);
});
afterEach(() => http.verify());
it('does not attach credentials to public or third-party requests', async () => {
  const pending = firstValueFrom(client.get('https://example.org/public'));
  const request = http.expectOne('https://example.org/public');
  expect(request.request.headers.has('Authorization')).toBe(false);
  request.flush({});
  await pending;
});
it.each([
  'https://evil.example/jobs',
  '//evil.example/jobs',
  '/\\evil.example/jobs',
  '/auth/web/refresh',
  '/AUTH/web/refresh',
])('rejects an unsafe authenticated URL %s', async (url) => {
  await expect(firstValueFrom(client.get(url, { context: context() }))).rejects.toThrow();
  http.expectNone(url);
});
it('refreshes once on 401, bypasses caches, and does not recurse on a second 401', async () => {
  session.recover.mockResolvedValue('fresh-access');
  const pending = firstValueFrom(client.get('/jobs', { context: context() }));
  const rejected = expect(pending).rejects.toMatchObject({ status: 401 });
  const first = http.expectOne('/jobs');
  expect(first.request.headers.get('Authorization')).toBe('Bearer old-access');
  expect(first.request.cache).toBe('no-store');
  expect(first.request.headers.get('ngsw-bypass')).toBe('true');
  first.flush({}, { status: 401, statusText: 'Unauthorized' });
  await Promise.resolve();
  const second = http.expectOne('/jobs');
  expect(second.request.headers.get('Authorization')).toBe('Bearer fresh-access');
  second.flush({}, { status: 401, statusText: 'Unauthorized' });
  await rejected;
  expect(session.recover).toHaveBeenCalledExactlyOnceWith('old-access');
});
it('does not refresh forbidden requests', async () => {
  const pending = firstValueFrom(client.get('/jobs', { context: context() }));
  const rejected = expect(pending).rejects.toMatchObject({ status: 403 });
  http.expectOne('/jobs').flush({}, { status: 403, statusText: 'Forbidden' });
  await rejected;
  expect(session.recover).not.toHaveBeenCalled();
});
