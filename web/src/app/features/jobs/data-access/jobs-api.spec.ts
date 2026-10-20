import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuthSession } from '../../auth/data-access/auth-session';
import { authInterceptor } from '../../auth/data-access/auth.interceptor';
import { JobsApi } from './jobs-api';
import { jobWriteError } from './job-errors';
let http: HttpTestingController;
let api: JobsApi;
beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      { provide: AuthSession, useValue: { token: () => 'access', recover: vi.fn() } },
    ],
  });
  http = TestBed.inject(HttpTestingController);
  api = TestBed.inject(JobsApi);
});
afterEach(() => http.verify());
it('creates only the work request without a client status, schedule or crew', async () => {
  const input = {
    customerId: 'customer',
    propertyId: 'property',
    title: 'Repair',
    description: null,
  };
  const pending = firstValueFrom(api.create(input));
  const request = http.expectOne('/jobs');
  expect(request.request.body).toEqual(input);
  expect(request.request.headers.get('Authorization')).toBe('Bearer access');
  expect(request.request.headers.get('ngsw-bypass')).toBe('true');
  expect(request.request.cache).toBe('no-store');
  request.flush({});
  await pending;
});
it('sends one versioned transition and leaves a refused completion for explicit review', async () => {
  const pending = firstValueFrom(
    api.status('job/one', { status: 'COMPLETED', note: 'Reviewed', expectedVersion: 8 }),
  );
  const rejected = expect(pending).rejects.toMatchObject({ status: 409 });
  const request = http.expectOne('/jobs/job%2Fone/status');
  expect(request.request.body).toEqual({
    status: 'COMPLETED',
    note: 'Reviewed',
    expectedVersion: 8,
  });
  request.flush({ code: 'JOB_COMPLETION_BLOCKED' }, { status: 409, statusText: 'Conflict' });
  await rejected;
  expect(
    jobWriteError(
      new HttpErrorResponse({ status: 409, error: { code: 'JOB_COMPLETION_BLOCKED' } }),
    ),
  ).toBe('jobCompletionBlocked');
});
it('uses an explicit audit query and a dedicated reasoned evidence removal', async () => {
  const audit = firstValueFrom(api.activity('job', true));
  http
    .expectOne('/jobs/job/activity?includeRemovedEvidence=true')
    .flush({ jobId: 'job', events: [] });
  await audit;
  const pending = firstValueFrom(api.remove('job', 'photo/one', false, 'Duplicate photograph'));
  const request = http.expectOne('/jobs/job/photos/photo%2Fone/removal');
  expect(request.request.body).toEqual({ reason: 'Duplicate photograph' });
  request.flush({});
  await pending;
});
it('does not resend unknown write results and distinguishes refusal from uncertainty', async () => {
  const pending = firstValueFrom(
    api.status('job', { status: 'CANCELED', expectedVersion: 4, note: null }),
  );
  const rejected = expect(pending).rejects.toMatchObject({ status: 0 });
  http.expectOne('/jobs/job/status').error(new ProgressEvent('error'));
  await rejected;
  expect(jobWriteError(new HttpErrorResponse({ status: 0 }))).toBe('jobWriteUncertain');
  expect(jobWriteError(new HttpErrorResponse({ status: 403 }))).toBe('forbiddenDescription');
  expect(
    jobWriteError(new HttpErrorResponse({ status: 409, error: { code: 'VERSION_CONFLICT' } })),
  ).toBe('jobConflict');
});

it('maps an archived-property refusal using the actual API code', () => {
  expect(
    jobWriteError(
      new HttpErrorResponse({
        status: 409,
        error: { code: 'PROPERTY_NOT_AVAILABLE_FOR_NEW_WORK' },
      }),
    ),
  ).toBe('jobPropertyArchived');
});

it('loads assignable technicians and schedules a visit with authenticated uncached requests', async () => {
  const list = firstValueFrom(api.technicians());
  const read = http.expectOne('/technicians');
  expect(read.request.headers.get('Authorization')).toBe('Bearer access');
  read.flush([{ membershipId: 'member', name: 'Alex' }]);
  expect(await list).toHaveLength(1);
  const input = {
    purposeCode: 'WORK' as const,
    scheduledStart: '2026-11-05T14:00:00Z',
    scheduledEnd: '2026-11-05T15:00:00Z',
    technicians: [{ membershipId: 'member', roleCode: 'LEAD' as const }],
    confirmConflicts: false,
  };
  const pending = firstValueFrom(api.schedule('job/one', input));
  const write = http.expectOne('/jobs/job%2Fone/visits');
  expect(write.request.method).toBe('POST');
  expect(write.request.body).toEqual(input);
  expect(write.request.headers.get('Authorization')).toBe('Bearer access');
  expect(write.request.headers.get('ngsw-bypass')).toBe('true');
  expect(write.request.cache).toBe('no-store');
  write.flush({});
  await pending;
});
it('requires reviewing a job that no longer accepts scheduling', () => {
  expect(
    jobWriteError(
      new HttpErrorResponse({ status: 409, error: { code: 'JOB_CLOSED_FOR_FIELD_WORK' } }),
    ),
  ).toBe('jobVisitUnavailable');
});
