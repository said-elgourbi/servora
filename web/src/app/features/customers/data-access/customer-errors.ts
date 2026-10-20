import { HttpErrorResponse } from '@angular/common/http';
import type { TranslationKey } from '../../../core/i18n/en';
export function customerReadError(error: unknown): TranslationKey {
  return error instanceof HttpErrorResponse && error.status === 403
    ? 'forbiddenDescription'
    : error instanceof HttpErrorResponse && error.status === 404
      ? 'customerUnavailable'
      : 'connectionError';
}
export function customerWriteError(error: unknown): TranslationKey {
  return error instanceof HttpErrorResponse && error.status === 409
    ? 'customerConflict'
    : error instanceof HttpErrorResponse && error.status === 403
      ? 'forbiddenDescription'
      : error instanceof HttpErrorResponse && error.status === 404
        ? 'customerUnavailable'
        : error instanceof HttpErrorResponse && error.status === 400
          ? 'customerValidationFailed'
          : 'customerWriteUncertain';
}
