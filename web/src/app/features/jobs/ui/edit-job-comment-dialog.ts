import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { EvidencePhase, JobActivityEvent } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';

export interface EditJobCommentData { event: JobActivityEvent }
export interface EditJobCommentResult { body: string; file: File | null; phase: EvidencePhase }

@Component({
  selector: 'app-edit-job-comment-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>{{ i18n.text('jobEditComment') }}</h2>
    <mat-dialog-content class="edit-comment-form">
      <label for="edit-comment-body">{{ i18n.text('jobCommentText') }}</label>
      <textarea id="edit-comment-body" rows="5" [(ngModel)]="body" [maxlength]="4000"></textarea>
      @if (event.photoId || event.audioNoteId || event.documentId) {
        <label for="replace-comment-file">{{ i18n.text('jobReplaceAttachment') }}</label>
        <input id="replace-comment-file" type="file" [accept]="accept" (change)="selectFile($event)" />
        <small>{{ selectedFile()?.name || event.documentDisplayName || i18n.text('jobKeepCurrentAttachment') }}</small>
      }
      @if (event.photoPhase || event.audioPhase) {
        <label for="edit-comment-phase">{{ i18n.text('jobUpdateEvidencePhase') }}</label>
        <select id="edit-comment-phase" [(ngModel)]="phase">
          <option value="BEFORE_WORK">{{ i18n.text('jobPhaseBeforeWork') }}</option>
          <option value="DURING_WORK">{{ i18n.text('jobPhaseDuringWork') }}</option>
          <option value="AFTER_WORK">{{ i18n.text('jobPhaseAfterWork') }}</option>
        </select>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>{{ i18n.text('cancel') }}</button>
      <button mat-flat-button type="button" [disabled]="!body.trim() && !selectedFile() && !event.photoId && !event.audioNoteId && !event.documentId" (click)="save()">{{ i18n.text('customerSave') }}</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .edit-comment-form { display:grid; gap:8px; min-width:min(440px, 76vw); }
    textarea, select { width:100%; box-sizing:border-box; border:1px solid var(--mat-sys-outline); border-radius:7px; padding:10px; font:inherit; color:inherit; }
    textarea:focus, select:focus { outline:2px solid #3457bd; outline-offset:1px; }
    input[type=file] { max-width:100%; }
    small { color:var(--mat-sys-on-surface-variant); overflow-wrap:anywhere; }
    @media (max-width:600px) { .edit-comment-form { min-width:0; } }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EditJobCommentDialog {
  protected readonly i18n = inject(I18n);
  protected readonly data = inject<EditJobCommentData>(MAT_DIALOG_DATA);
  protected readonly event = this.data.event;
  private readonly ref = inject(MatDialogRef<EditJobCommentDialog, EditJobCommentResult>);
  protected body = this.event.body ?? this.event.documentDisplayName ?? '';
  protected phase: EvidencePhase = (this.event.photoPhase || this.event.audioPhase || 'DURING_WORK') as EvidencePhase;
  protected readonly selectedFile = signal<File | null>(null);
  protected readonly accept = this.event.photoId ? 'image/jpeg,image/png,image/webp' : this.event.audioNoteId ? 'audio/mp4,audio/webm' : '.pdf,application/pdf';
  protected selectFile(event: Event): void { this.selectedFile.set((event.target as HTMLInputElement).files?.[0] ?? null); }
  protected save(): void { this.ref.close({ body: this.body.trim(), file: this.selectedFile(), phase: this.phase }); }
}
