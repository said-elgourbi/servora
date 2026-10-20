import { Pipe, PipeTransform, inject } from '@angular/core';
import { I18n } from '../../core/i18n/i18n';
import type { TranslationKey } from '../../core/i18n/en';
const keys: Record<string, TranslationKey> = {
  LEAD: 'jobRoleLead',
  TECHNICIAN: 'jobRoleTechnician',
  REQUIRED: 'quoteRequired',
  SENT: 'jobQuoteSent',
  PENDING: 'fieldPending',
  NEEDS_CLARIFICATION: 'fieldClarification',
  APPROVED: 'fieldApproved',
  REJECTED: 'fieldRejected',
  NEW: 'statusNew',
  ACTIVE: 'statusActive',
  DRAFT: 'statusDraft',
  SCHEDULED: 'statusScheduled',
  EN_ROUTE: 'statusEnRoute',
  ON_SITE: 'statusOnSite',
  IN_PROGRESS: 'statusInProgress',
  COMPLETED: 'statusCompleted',
  CANCELED: 'statusCanceled',
  WORK: 'purposeWork',
  ASSESSMENT: 'purposeAssessment',
  VISIT_OVERDUE: 'overdue',
  VISIT_STALE_STARTED: 'staleStarted',
  JOB_NEEDS_SCHEDULING: 'needsScheduling',
  FOLLOW_UP_NEEDS_SCHEDULING: 'followUpScheduling',
  PARTS_REQUIRED: 'partsRequired',
  UNABLE_TO_COMPLETE: 'unableToComplete',
  JOB_WORK_RECOMMENDED: 'workRecommended',
  JOB_QUOTE_REQUIRED: 'quoteRequired',
  JOB_CUSTOMER_DECISION_PENDING: 'decisionPending',
  JOB_WORK_READY_TO_SCHEDULE: 'workReady',
  JOB_CANCELLATION_REQUIRED: 'cancellationRequired',
  JOB_NEEDS_REASSESSMENT: 'reassessment',
  RESOLVED: 'resolved',
  NEEDS_FOLLOW_UP: 'followUpScheduling',
  NEEDS_PARTS: 'partsRequired',
  NO_WORK_REQUIRED: 'noWorkRequired',
  WORK_RECOMMENDED: 'workRecommended',
  QUOTE_REQUIRED: 'quoteRequired',
  UNABLE_TO_ASSESS: 'unableToAssess',
};
@Pipe({ name: 'workLabel', pure: false })
export class WorkLabel implements PipeTransform {
  private readonly i18n = inject(I18n);
  transform(code: string): string {
    return this.i18n.text(keys[code] ?? 'unknownWorkState');
  }
}
