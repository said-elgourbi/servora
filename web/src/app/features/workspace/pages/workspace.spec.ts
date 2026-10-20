import { FieldStore } from '../../field/data-access/field-store';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { Workspace } from './workspace';
import { AuthSession } from '../../auth/data-access/auth-session';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
const day = {
  generatedAt: '2026-10-18T12:00:00Z',
  day: { timeZone: 'America/Toronto', start: '2026-10-18T04:00:00Z', end: '2026-10-19T04:00:00Z' },
  viewer: { displayName: 'Alex' },
  attention: { total: 23, items: [] },
  visits: [],
};
let permissions: string[];
let query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
beforeEach(() => {
  permissions = ['VISIT_VIEW_ASSIGNED'];
  query = new BehaviorSubject(convertToParamMap({}));
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: FieldStore,
        useValue: {
          snapshot: async () => undefined,
          records: async () => [],
          invalidateJob: async () => undefined,
          failure: signal(false),
        },
      },
      {
        provide: AuthSession,
        useValue: {
          authenticated: () => true,
          identity: signal({ userId: '11111111-1111-4111-8111-111111111111' }),
          token: () => 'test-access',
          boundary: () => 0,
          hasPermission: (code: string) => permissions.includes(code),
        },
      },
      { provide: ActivatedRoute, useValue: { queryParamMap: query } },
    ],
  });
});
afterEach(() => TestBed.inject(HttpTestingController).verify());
it('requests only assigned work with authenticated context and preserves server totals', async () => {
  const fixture = TestBed.createComponent(Workspace);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  await verifyField(http);
  const request = http.expectOne((req) => req.url.startsWith('/home/technician'));
  expect(request.request.context.get(AUTHENTICATED_REQUEST)).toBe(true);
  expect(request.request.urlWithParams).toContain('timeZone=');
  expect(request.request.headers.get('X-Servora-Field-Membership')).toBe(
    '33333333-3333-4333-8333-333333333333',
  );
  request.flush({ ...day, nextVisit: null, upcoming: [], upcomingTotal: 8 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  await fixture.whenStable();
  fixture.detectChanges();
  expect(fixture.nativeElement.textContent).toContain('23');
  expect(fixture.nativeElement.textContent).toContain('8');
  expect(fixture.nativeElement.textContent).not.toContain('Completed');
  http.expectNone((req) => req.url === '/home/manager');
});
it('cancels old requests on view changes and clears office data before field loading', async () => {
  permissions = ['customers.view', 'VISIT_VIEW_ASSIGNED'];
  const fixture = TestBed.createComponent(Workspace);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  const old = http.expectOne((req) => req.url === '/home/manager');
  query.next(convertToParamMap({ view: 'field' }));
  fixture.detectChanges();
  expect(old.cancelled).toBe(true);
  await verifyField(http);
  http
    .expectOne((req) => req.url.startsWith('/home/technician'))
    .flush({ ...day, nextVisit: null, upcoming: [], upcomingTotal: 0 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  await fixture.whenStable();
  fixture.detectChanges();
  expect(fixture.nativeElement.textContent).not.toContain('Completed');
});
it('does not request business data when no Home permission exists', async () => {
  permissions = [];
  const fixture = TestBed.createComponent(Workspace);
  fixture.detectChanges();
  TestBed.inject(HttpTestingController).expectNone((req) => req.url.startsWith('/home/'));
  expect(fixture.nativeElement.textContent).toContain('No permissions');
});
it('shows a forbidden error without retaining data and retries explicitly', async () => {
  const fixture = TestBed.createComponent(Workspace);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  await verifyField(http);
  http
    .expectOne((req) => req.url.startsWith('/home/technician'))
    .flush({}, { status: 403, statusText: 'Forbidden' });
  await new Promise((resolve) => setTimeout(resolve, 0));
  await fixture.whenStable();
  fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
    'does not have permission',
  );
  fixture.nativeElement.querySelector('[role="alert"] button').click();
  fixture.detectChanges();
  await verifyField(http);
  http
    .expectOne((req) => req.url.startsWith('/home/technician'))
    .error(new ProgressEvent('offline'));
  await new Promise((resolve) => setTimeout(resolve, 0));
  await fixture.whenStable();
  fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
    'Unable to connect',
  );
});

async function verifyField(http: HttpTestingController): Promise<void> {
  const requests = http.match('/auth/field-context');
  for (const request of requests)
    request.flush({
      userId: '11111111-1111-4111-8111-111111111111',
      organizationId: '22222222-2222-4222-8222-222222222222',
      membershipId: '33333333-3333-4333-8333-333333333333',
    });
  for (let count = 0; count < 6; count++) await Promise.resolve();
}
