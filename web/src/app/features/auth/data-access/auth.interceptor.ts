import { HttpContextToken, HttpErrorResponse, type HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthSession } from './auth-session';
/** Opt in on protected API requests; credentials never attach to assets or third-party URLs. */
export const AUTHENTICATED_REQUEST = new HttpContextToken<boolean>(() => false);
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.context.get(AUTHENTICATED_REQUEST)) return next(request);
  const destination = new URL(request.url, window.location.origin);
  if (
    destination.origin !== window.location.origin ||
    !request.url.startsWith('/') ||
    request.url.startsWith('//') ||
    (destination.pathname.toLowerCase().startsWith('/auth/') &&
      destination.pathname !== '/auth/field-context')
  ) {
    return throwError(
      () => new Error('Protected API requests must use same-origin resource paths'),
    );
  }
  const session = inject(AuthSession);
  const token = session.token();
  if (!token) return throwError(() => new HttpErrorResponse({ status: 401 }));
  const authenticated = (value: string) =>
    request.clone({
      setHeaders: { Authorization: `Bearer ${value}`, 'ngsw-bypass': 'true' },
      cache: 'no-store',
    });
  return next(authenticated(token)).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401)
        return throwError(() => error);
      return from(session.recover(token)).pipe(
        switchMap((fresh) => (fresh ? next(authenticated(fresh)) : throwError(() => error))),
      );
    }),
  );
};
