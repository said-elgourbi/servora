import { Pipe, inject, type PipeTransform } from '@angular/core';
import { I18n } from '../../core/i18n/i18n';
import type { TranslationKey } from '../../core/i18n/en';
const labels: Record<string, TranslationKey> = {
  JOB_STATUS_CHANGED: 'fieldEventJobStatus',
  JOB_PROPERTY_CHANGED: 'fieldEventProperty',
  JOB_CUSTOMER_CHANGED: 'fieldEventCustomer',
  JOB_PHOTO_ADDED: 'fieldEventPhoto',
  JOB_PHOTO_REMOVED: 'fieldEventPhotoRemoved',
  JOB_AUDIO_ADDED: 'fieldEventAudio',
  JOB_AUDIO_REMOVED: 'fieldEventAudioRemoved',
  JOB_QUOTE_REQUIRED: 'fieldEventQuoteRequired',
  JOB_QUOTE_SENT: 'fieldEventQuoteSent',
  JOB_QUOTE_APPROVED: 'fieldEventQuoteApproved',
  JOB_QUOTE_REJECTED: 'fieldEventQuoteRejected',
  JOB_QUOTE_CORRECTED: 'fieldEventQuoteCorrected',
  JOB_QUOTE_DOCUMENT_UPLOADED: 'fieldEventQuoteDocument',
  VISIT_STATUS_CHANGED: 'fieldEventVisitStatus',
  VISIT_SCHEDULED: 'fieldEventScheduled',
  VISIT_RESCHEDULED: 'fieldEventRescheduled',
  VISIT_TECHNICIAN_ASSIGNED: 'fieldEventAssigned',
  VISIT_TECHNICIAN_REMOVED: 'fieldEventUnassigned',
  VISIT_TECHNICIAN_ROLE_CHANGED: 'fieldEventRole',
  VISIT_OUTCOME_RECORDED: 'fieldEventOutcome',
  VISIT_NOTE_ADDED: 'fieldEventNote',
};
@Pipe({ name: 'fieldActivityLabel', pure: false })
export class JobActivityLabel implements PipeTransform {
  private readonly i18n = inject(I18n);
  transform(kind: string): string {
    return this.i18n.text(labels[kind] ?? 'fieldActivity');
  }
}
