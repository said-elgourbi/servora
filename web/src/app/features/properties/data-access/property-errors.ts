import { HttpErrorResponse } from '@angular/common/http';
import type { TranslationKey } from '../../../core/i18n/en';
export function propertyReadError(error: unknown): TranslationKey {
  return error instanceof HttpErrorResponse && error.status === 403
    ? 'forbiddenDescription'
    : error instanceof HttpErrorResponse && error.status === 404
      ? 'propertyUnavailable'
      : 'connectionError';
}
export function propertyWriteError(error: unknown): TranslationKey {
  if (!(error instanceof HttpErrorResponse)) return 'propertyWriteUncertain';
  if (error.status === 409)
    return error.error?.code === 'PROPERTY_HAS_REFERENCES'
      ? 'propertyHasReferences'
      : 'propertyConflict';
  if (error.status === 403) return 'forbiddenDescription';
  if (error.status === 404) return 'propertyUnavailable';
  if (error.status === 400) return 'customerValidationFailed';
  return 'propertyWriteUncertain';
}
