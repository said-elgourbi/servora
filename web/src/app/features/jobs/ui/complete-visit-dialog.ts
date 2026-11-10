import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { I18n } from '../../../core/i18n/i18n';
import { WorkLabel } from '../../../shared/ui/work-label';
import { Icon } from '../../../shared/ui/icon';
import { JobsApi } from '../data-access/jobs-api';
import { jobWriteError } from '../data-access/job-errors';
import type { TranslationKey } from '../../../core/i18n/en';

export interface CompleteVisitDialogData {
  jobId: string;
  visitId: string;
  visitSequence: number;
  purposeCode: string;
  expectedVersion: number;
}

@Component({
  selector: 'app-complete-visit-dialog',
  imports: [MatDialogModule, MatButtonModule, WorkLabel, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ i18n.text('visitCompleteAction') }} · {{ i18n.text('visit') }} {{ data.visitSequence }}</h2>
    <mat-dialog-content>
      <p class="intro">{{ data.purposeCode | workLabel }}</p>
      <label for="visit-completion-outcome">{{ i18n.text('visitOutcomeLabel') }}</label>
      <select id="visit-completion-outcome" [value]="outcome()" (change)="outcome.set($any($event.target).value)">
        <option value="">{{ i18n.text('visitChooseOutcome') }}</option>
        @for (option of outcomes; track option) { <option [value]="option">{{ option | workLabel }}</option> }
      </select>
      <label for="visit-completion-summary">{{ i18n.text(summaryLabel()) }}</label>
      <textarea id="visit-completion-summary" maxlength="2000" rows="4" [value]="summary()" (input)="summary.set($any($event.target).value)" [placeholder]="i18n.text(summaryLabel())"></textarea>
      @if (error(); as message) { <p class="error" role="alert">{{ i18n.text(message) }}</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [disabled]="busy()" (click)="ref.close()">{{ i18n.text('cancel') }}</button>
      <button mat-flat-button class="primary" type="button" [disabled]="busy() || !outcome() || !summary().trim()" (click)="complete()">
        @if (busy()) { <app-icon name="refresh" />{{ i18n.text('jobUpdatePosting') }} } @else { {{ i18n.text('visitCompleteAction') }} }
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { display:grid; gap:9px; min-width:min(460px,76vw); padding-top:8px; }
    .intro { margin:0 0 4px; color:#526782; }
    label { font-size:13px; font-weight:600; }
    select, textarea { box-sizing:border-box; width:100%; padding:11px 12px; border:1px solid #cbd8e8; border-radius:7px; background:white; color:#15234d; font:inherit; }
    textarea { resize:vertical; }
    .error { color:#a62828; font-size:13px; }
    .primary { background:#3825ed; color:white; }
    app-icon { width:16px; height:16px; margin-right:7px; }
    @media(max-width:600px) { mat-dialog-content { min-width:0; } }
  `],
})
export class CompleteVisitDialog {
  readonly data = inject<CompleteVisitDialogData>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18n);
  protected readonly ref = inject(MatDialogRef<CompleteVisitDialog, boolean>);
  private readonly api = inject(JobsApi);
  protected readonly outcomes = this.data.purposeCode === 'ASSESSMENT'
    ? ['NO_WORK_REQUIRED', 'WORK_RECOMMENDED', 'QUOTE_REQUIRED', 'UNABLE_TO_ASSESS']
    : ['RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE'];
  protected readonly outcome = signal('');
  protected readonly summary = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected summaryLabel(): TranslationKey {
    return this.outcome() === 'UNABLE_TO_COMPLETE' || this.outcome() === 'UNABLE_TO_ASSESS'
      ? 'visitCompletionReason'
      : 'visitCompletionSummary';
  }
  protected async complete(): Promise<void> {
    if (this.busy() || !this.outcome() || !this.summary().trim()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.completeVisit(this.data.jobId, this.data.visitId, {
        outcomeCode: this.outcome(),
        outcomeSummary: this.summary().trim(),
        expectedVersion: this.data.expectedVersion,
      }));
      this.ref.close(true);
    } catch (error) {
      this.error.set(jobWriteError(error));
    } finally {
      this.busy.set(false);
    }
  }
}
