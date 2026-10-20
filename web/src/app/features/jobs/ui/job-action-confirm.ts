import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { I18n } from '../../../core/i18n/i18n';
import { WorkLabel } from '../../../shared/ui/work-label';
import { AuthSession } from '../../auth/data-access/auth-session';
import type { OfficeJobDetails } from '@servora/shared-types';
export interface JobActionConfirmation {
  job: OfficeJobDetails;
  status?: string;
  audio?: boolean;
  removal?: boolean;
}
@Component({
  selector: 'app-job-action-confirm',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
    WorkLabel,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h2 mat-dialog-title>
      {{
        i18n.text(
          data.removal
            ? 'jobRemoveEvidence'
            : data.status === 'CANCELED'
              ? 'jobCancel'
              : 'jobChangeStatus'
        )
      }}
    </h2>
    <mat-dialog-content>
      <p>#{{ data.job.jobNumber }} · {{ data.job.title }}</p>
      @if (data.status) {
        <p>{{ data.job.status | workLabel }} → {{ data.status | workLabel }}</p>
      }
      @if (data.status === 'CANCELED') {
        <p>{{ i18n.text('jobCancelWarning') }}</p>
        @for (visit of openVisits; track visit.id) {
          <p>{{ i18n.text('visit') }} {{ visit.sequence }} · {{ visit.status | workLabel }}</p>
          @for (member of visit.technicians; track member.membershipId) {
            <p>{{ member.name || i18n.text('unnamedMember') }}</p>
          }
        }
      } @else if (data.status === 'COMPLETED') {
        <p>{{ i18n.text('jobCompleteWarning') }}</p>
      }
      @if (data.removal) {
        <p>{{ i18n.text('jobRemoveDescription') }}</p>
      }
      <mat-form-field appearance="outline"
        ><mat-label>{{
          i18n.text(
            data.removal
              ? 'jobRemovalReason'
              : data.status === 'CANCELED'
                ? 'jobCancelExplanation'
                : 'jobStatusNote'
          )
        }}</mat-label>
        <textarea
          matInput
          [formControl]="note"
          maxlength="2000"
          rows="4"
          [required]="!!data.removal || data.status === 'CANCELED'"
        ></textarea>
        <mat-error>{{ i18n.text('required') }}</mat-error>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end"
      ><button mat-button [mat-dialog-close]="null" cdkFocusInitial>
        {{ i18n.text('cancel') }}
      </button>
      <button mat-flat-button [disabled]="note.invalid" (click)="confirm()">
        {{ i18n.text('customerConfirm') }}
      </button></mat-dialog-actions
    >`,
})
export class JobActionConfirm {
  protected readonly data = inject<JobActionConfirmation>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18n);
  private readonly ref = inject(MatDialogRef<JobActionConfirm>);
  protected readonly note = new FormControl('', {
    nonNullable: true,
    validators:
      this.data.removal || this.data.status === 'CANCELED'
        ? [Validators.required, Validators.pattern(/\S/), Validators.maxLength(2000)]
        : [Validators.maxLength(2000)],
  });
  protected readonly openVisits = this.data.job.visits.filter(
    (v) => !['COMPLETED', 'CANCELED'].includes(v.status),
  );
  constructor() {
    const auth = inject(AuthSession),
      boundary = auth.boundary();
    effect(() => {
      if (auth.boundary() !== boundary) this.ref.close(null);
    });
  }
  protected confirm() {
    if (this.note.valid) this.ref.close({ note: this.note.value.trim() || null });
  }
}
