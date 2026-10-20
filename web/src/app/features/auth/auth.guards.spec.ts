import { TestBed } from '@angular/core/testing';
import {
  provideRouter,
  Router,
  type ActivatedRouteSnapshot,
  type RouterStateSnapshot,
} from '@angular/router';
import { AuthSession } from './data-access/auth-session';
import { authenticatedGuard, permissionGuard, safeReturnUrl } from './auth.guards';

it.each([
  'https://evil.example',
  '//evil.example',
  '/\\evil.example',
  '/sign-in',
  '/sign-in?returnUrl=/',
  '/sign-in/extra',
  '/\n/evil.example',
])('rejects unsafe return URL %s', (value) => expect(safeReturnUrl(value)).toBe('/'));
it('preserves local deep links', () =>
  expect(safeReturnUrl('/jobs/123?tab=activity')).toBe('/jobs/123?tab=activity'));
it('redirects a signed-out caller and checks effective permissions', () => {
  const session = {
    authenticated: () => false,
    hasPermission: (code: string) => code === 'customers.view',
  };
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: AuthSession, useValue: session }],
  });
  TestBed.runInInjectionContext(() => {
    const route = {} as ActivatedRouteSnapshot;
    const state = { url: '/jobs/123' } as RouterStateSnapshot;
    const denied = authenticatedGuard(route, state);
    expect(TestBed.inject(Router).serializeUrl(denied as ReturnType<Router['createUrlTree']>)).toBe(
      '/sign-in?returnUrl=%2Fjobs%2F123',
    );
    expect(permissionGuard('customers.view')(route, state)).toBe(true);
    expect(permissionGuard('unknown')(route, state)).not.toBe(true);
  });
});
