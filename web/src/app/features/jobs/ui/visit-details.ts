import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { OfficeJobDetails } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { WorkLabel } from '../../../shared/ui/work-label';
import { WorkStatus } from '../../../shared/ui/work-status';
import { PreferredTime } from '../../../shared/ui/preferred-time';

export interface VisitDetailsData {
  jobId: string;
  visit: OfficeJobDetails['visits'][number];
  actions: Pick<NonNullable<OfficeJobDetails['selectedVisit']>, 'allowedStatusTransitions' | 'completionAllowed' | 'addUpdateAllowed'> | null;
  followUpVisitRequest: OfficeJobDetails['followUpVisitRequest'] | null;
}

@Component({
  selector: 'app-visit-details',
  imports: [DatePipe, MatDialogModule, MatButtonModule, WorkLabel, WorkStatus, PreferredTime],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ i18n.text('visitDetailsTitle') }}</h2>
    <mat-dialog-content>
      <p>{{ i18n.text('visitNumber') }} {{ data.visit.sequence }} · {{ data.visit.purposeCode | workLabel }}</p>
      <p><app-work-status [code]="data.visit.status" /></p>
      <p>{{ i18n.text('scheduleTime') }}:
        @if (data.visit.scheduledStart) {
          {{ data.visit.scheduledStart | date:'mediumDate' : undefined : i18n.language() }}
          {{ data.visit.scheduledStart | preferredTime }} – {{ data.visit.scheduledEnd | preferredTime }}
        } @else { {{ i18n.text('unscheduled') }} }
      </p>
      <p>{{ i18n.text('jobVisitTechnicians') }}:
        @for (technician of data.visit.technicians; track technician.membershipId) {
          {{ technician.name || i18n.text('unnamedMember') }}{{ $last ? '' : ', ' }}
        } @empty { {{ i18n.text('noCrew') }} }
      </p>
      @if (data.visit.outcomeCode) { <p>{{ i18n.text('visitOutcomeLabel') }}: {{ data.visit.outcomeCode | workLabel }}</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button mat-dialog-close>{{ i18n.text('activityClose') }}</button></mat-dialog-actions>
  `,
  styles: [`:host{display:block;width:min(760px,90vw)}mat-dialog-content{line-height:1.6}p{margin:8px 0}`],
})
export class VisitDetails {
  protected readonly data = inject<VisitDetailsData>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18n);
  protected readonly dialogRef = inject(MatDialogRef<VisitDetails, 'changed' | undefined>);
}
