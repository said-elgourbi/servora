import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';
import { AuthSession } from './data-access/auth-session';
export const authenticatedGuard: CanActivateFn = (_route, state) => {
  const session = inject(AuthSession);
  return (
    session.authenticated() ||
    inject(Router).createUrlTree(['/sign-in'], { queryParams: { returnUrl: state.url } })
  );
};
export const signedOutGuard: CanActivateFn = () =>
  !inject(AuthSession).authenticated() || inject(Router).createUrlTree(['/']);
export function permissionGuard(permission: string): CanActivateFn {
  return () =>
    inject(AuthSession).hasPermission(permission) || inject(Router).createUrlTree(['/forbidden']);
}
/** Never accept protocol-relative, external, or auth-loop destinations from a query parameter. */
export function safeReturnUrl(value: string | null): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || /[\\\x00-\x20]/.test(value))
    return '/';
  const url = new URL(value, 'https://servora.invalid');
  return url.origin === 'https://servora.invalid' && !/^\/sign-in(?:\/|$)/.test(url.pathname)
    ? value
    : '/';
}
