import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { I18n } from '../../../core/i18n/i18n';

@Component({
  selector: 'app-delete-job-comment-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ i18n.text('jobDeleteComment') }}</h2>
    <mat-dialog-content>{{ i18n.text('jobConfirmDeleteComment') }}</mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>{{ i18n.text('cancel') }}</button>
      <button mat-flat-button color="warn" [mat-dialog-close]="true">{{ i18n.text('jobDeleteComment') }}</button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeleteJobCommentDialog {
  protected readonly i18n = inject(I18n);
}
