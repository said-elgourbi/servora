import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuthSession } from '../../auth/data-access/auth-session';
import { authInterceptor } from '../../auth/data-access/auth.interceptor';
import { CustomersApi } from './customers-api';
let http: HttpTestingController;
let api: CustomersApi;
beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      { provide: AuthSession, useValue: { token: () => 'access', recover: vi.fn() } },
    ],
  });
  http = TestBed.inject(HttpTestingController);
  api = TestBed.inject(CustomersApi);
});
afterEach(() => http.verify());
it('uses server filters and protects office reads without field assertions or caches', async () => {
  const pending = firstValueFrom(api.list('INACTIVE', 'HAS_OVERDUE_VISITS'));
  const request = http.expectOne('/customers?status=INACTIVE&jobs=HAS_OVERDUE_VISITS');
  expect(request.request.headers.get('Authorization')).toBe('Bearer access');
  expect(request.request.headers.get('ngsw-bypass')).toBe('true');
  expect(request.request.cache).toBe('no-store');
  expect(request.request.headers.has('X-Servora-Field-Organization')).toBe(false);
  request.flush([]);
  await pending;
});
it('states the read contact version and omits fields the client does not manage', async () => {
  const contact = { id: 'contact', version: 7 };
  const input = { firstName: 'Jo', lastName: 'Roy', phone: null, email: null, isPrimary: true };
  const pending = firstValueFrom(api.saveContact('customer', input, contact));
  const request = http.expectOne('/customers/customer/contacts/contact');
  expect(request.request.method).toBe('PATCH');
  expect(request.request.body).toEqual({ ...input, expectedVersion: 7 });
  request.flush({});
  await pending;
});
it('does not retry a version-conflicted contact removal', async () => {
  const pending = firstValueFrom(api.removeContact('customer', { id: 'contact', version: 2 }));
  const rejected = expect(pending).rejects.toMatchObject({ status: 409 });
  const request = http.expectOne('/customers/customer/contacts/contact');
  expect(request.request.method).toBe('DELETE');
  expect(request.request.body).toEqual({ expectedVersion: 2 });
  request.flush({ code: 'CONTACT_VERSION_CONFLICT' }, { status: 409, statusText: 'Conflict' });
  await rejected;
  http.expectNone('/customers/customer/contacts/contact');
});
