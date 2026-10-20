import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import type { SavePropertyRequest } from '@servora/shared-types';
import { AuthSession } from '../../auth/data-access/auth-session';
import { authInterceptor } from '../../auth/data-access/auth.interceptor';
import { PropertiesApi } from './properties-api';
import { propertyWriteError } from './property-errors';
import { HttpErrorResponse } from '@angular/common/http';
let http: HttpTestingController;
let api: PropertiesApi;
const input: SavePropertyRequest = {
  addressLine1: '12 Cedar Lane',
  city: 'Montreal',
  province: 'QC',
  postalCode: 'H3A 2T6',
  expectedVersion: 4,
};
beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      { provide: AuthSession, useValue: { token: () => 'access', recover: vi.fn() } },
    ],
  });
  http = TestBed.inject(HttpTestingController);
  api = TestBed.inject(PropertiesApi);
});
afterEach(() => http.verify());
it('addresses a property inside its customer and protects the read without caching', async () => {
  const pending = firstValueFrom(api.detail('customer/one', 'property/one'));
  const request = http.expectOne('/customers/customer%2Fone/properties/property%2Fone');
  expect(request.request.headers.get('Authorization')).toBe('Bearer access');
  expect(request.request.headers.get('ngsw-bypass')).toBe('true');
  expect(request.request.cache).toBe('no-store');
  request.flush({});
  await pending;
});
it('carries the read version and does not resend a conflicting edit', async () => {
  const pending = firstValueFrom(api.update('customer', 'property', input));
  const rejected = expect(pending).rejects.toMatchObject({ status: 409 });
  const request = http.expectOne('/customers/customer/properties/property');
  expect(request.request.body).toEqual(input);
  expect(request.request.body).not.toHaveProperty('country');
  request.flush({ code: 'PROPERTY_VERSION_CONFLICT' }, { status: 409, statusText: 'Conflict' });
  await rejected;
  http.expectNone('/customers/customer/properties/property');
});
it('uses a fresh operation identity and the read version for a lifecycle action', async () => {
  const pending = firstValueFrom(
    api.lifecycle('customer', { id: 'property', version: 7 }, 'archive'),
  );
  const request = http.expectOne('/customers/customer/properties/property/archive');
  expect(request.request.body.expectedVersion).toBe(7);
  expect(request.request.body.clientOperationId).toMatch(/^[0-9a-f-]{36}$/);
  request.flush({});
  await pending;
});
it('performs deletion online and distinguishes references from a version conflict', async () => {
  const pending = firstValueFrom(api.remove('customer', 'property'));
  const rejected = expect(pending).rejects.toMatchObject({ status: 409 });
  const request = http.expectOne('/customers/customer/properties/property');
  expect(request.request.method).toBe('DELETE');
  request.flush({ code: 'PROPERTY_HAS_REFERENCES' }, { status: 409, statusText: 'Conflict' });
  await rejected;
  expect(
    propertyWriteError(
      new HttpErrorResponse({ status: 409, error: { code: 'PROPERTY_HAS_REFERENCES' } }),
    ),
  ).toBe('propertyHasReferences');
  expect(
    propertyWriteError(
      new HttpErrorResponse({ status: 409, error: { code: 'PROPERTY_VERSION_CONFLICT' } }),
    ),
  ).toBe('propertyConflict');
});
