import { TimeoutError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import type { TranslationKey } from '../../../core/i18n/en';
export function authErrorKey(error: unknown): TranslationKey {
  if (error instanceof TimeoutError) return 'connectionError';
  if (!(error instanceof HttpErrorResponse)) return 'unexpectedError';
  if (error.status === 0 || error.status >= 500) return 'connectionError';
  if (error.status === 429) return 'rateLimited';
  switch (error.error?.code) {
    case 'INVALID_CREDENTIALS':
      return 'invalidCredentials';
    case 'OTP_CODE_INVALID':
      return 'invalidCode';
    default:
      return 'unexpectedError';
  }
}
