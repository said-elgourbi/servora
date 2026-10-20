import { HttpErrorResponse } from '@angular/common/http';
import type { TranslationKey } from '../../../core/i18n/en';
export function jobReadError(error: unknown): TranslationKey {
  return error instanceof HttpErrorResponse && error.status === 403
    ? 'forbiddenDescription'
    : error instanceof HttpErrorResponse && error.status === 404
      ? 'jobUnavailable'
      : 'connectionError';
}
export function jobWriteError(error: unknown): TranslationKey {
  if (!(error instanceof HttpErrorResponse)) return 'jobWriteUncertain';
  const code: unknown = error.error?.code;
  if (code === 'JOB_CLOSED_FOR_FIELD_WORK' || code === 'VISIT_SCHEDULING_CONDITION_NOT_MET')
    return 'jobVisitUnavailable';
  if (code === 'TECHNICIANS_NOT_ASSIGNABLE') return 'jobConflict';
  if (code === 'CUSTOMER_INACTIVE') return 'jobCustomerInactive';
  if (code === 'JOB_COMPLETION_BLOCKED') return 'jobCompletionBlocked';
  if (code === 'PROPERTY_NOT_AVAILABLE_FOR_NEW_WORK') return 'jobPropertyArchived';
  if (error.status === 409) return 'jobConflict';
  if (error.status === 403) return 'forbiddenDescription';
  if (error.status === 404) return 'jobUnavailable';
  if (error.status === 400 || error.status === 422) return 'customerValidationFailed';
  return 'jobWriteUncertain';
}
