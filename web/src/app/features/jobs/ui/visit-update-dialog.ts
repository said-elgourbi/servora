import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { firstValueFrom } from 'rxjs';
import { I18n } from '../../../core/i18n/i18n';
import { ToastService } from '../../../core/notifications/toast.service';
import type { EvidencePhase } from '@servora/shared-types';
import { AuthSession } from '../../auth/data-access/auth-session';
import { Icon } from '../../../shared/ui/icon';
import { JobsApi } from '../data-access/jobs-api';
import { jobWriteError } from '../data-access/job-errors';
import { AudioRecorder } from './audio-recorder';
import type { TranslationKey } from '../../../core/i18n/en';

export interface VisitUpdateDialogData { jobId: string; visitId: string; visitSequence: number; }
@Component({
  selector: 'app-visit-update-dialog',
  imports: [MatDialogModule, MatButtonModule, MatInputModule, MatFormFieldModule, Icon, AudioRecorder],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ i18n.text('jobVisitAddUpdate') }} · {{ i18n.text('visit') }} {{ data.visitSequence }}</h2>
    <mat-dialog-content>
      <textarea matInput rows="4" [value]="note()" (input)="note.set($any($event.target).value)" [placeholder]="i18n.text('jobUpdatePlaceholder')" [attr.aria-label]="i18n.text('jobUpdatePlaceholder')"></textarea>
      <div class="evidence-phase-control"><label for="visit-evidence-phase">{{ i18n.text('jobUpdateEvidencePhase') }}</label><select id="visit-evidence-phase" (change)="phase.set($any($event.target).value)">
        <option value="BEFORE_WORK" [selected]="phase() === 'BEFORE_WORK'">{{ i18n.text('jobPhaseBeforeWork') }}</option>
        <option value="DURING_WORK" [selected]="phase() === 'DURING_WORK'">{{ i18n.text('jobPhaseDuringWork') }}</option>
        <option value="AFTER_WORK" [selected]="phase() === 'AFTER_WORK'">{{ i18n.text('jobPhaseAfterWork') }}</option>
      </select></div>
      <div class="attachment-actions">
        @if (auth.hasPermission('evidence.photo.add')) { <button mat-stroked-button type="button" (click)="photoInput.click()"><app-icon name="photo" />{{ photo()?.name || i18n.text('jobUpdatePhotos') }}</button>
        <input #photoInput type="file" accept="image/jpeg,image/png,image/webp" (change)="select($event, 'photo')" /> }
        @if (auth.hasPermission('evidence.audio.add')) { <app-audio-recorder [fileName]="audio()?.name ?? null" (recorded)="audio.set($event)" (discarded)="audio.set(null)" /> }
        <button mat-stroked-button type="button" (click)="documentInput.click()"><app-icon name="document" />{{ document()?.name || i18n.text('jobUpdateFile') }}</button>
        <input #documentInput type="file" accept="application/pdf,.pdf" (change)="select($event, 'document')" />
      </div>
      @if (error(); as message) { <p class="error" role="alert">{{ i18n.text(message) }}</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [disabled]="busy()" (click)="ref.close()">{{ i18n.text('cancel') }}</button>
      <button mat-flat-button class="primary" type="button" [disabled]="busy() || !hasContent()" (click)="post()">{{ i18n.text(busy() ? 'jobUpdatePosting' : 'jobUpdatePost') }}</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { display:grid; gap:14px; width:100%; min-width:0; box-sizing:border-box; padding-top:8px; }
    mat-dialog-actions { flex-wrap:wrap; gap:8px; }
    textarea { box-sizing:border-box; width:100%; min-height:112px; resize:vertical; padding:12px; border:1px solid #d2deeb; border-radius:7px; font:inherit; }
    textarea:focus { border-color:#1767ef; outline:0; box-shadow:inset 0 0 0 1px #1767ef; }
    .attachment-actions { display:flex; flex-wrap:wrap; gap:8px; }
    .attachment-actions button { min-height:38px; max-width:190px; overflow:hidden; text-overflow:ellipsis; }
    .attachment-actions app-icon { width:16px; height:16px; margin-right:6px; }
    input[type=file] { display:none; }
    .error { color:#a62828; font-size:13px; }
    .primary { background:#0862e9; color:white; }
    @media(max-width:600px) { mat-dialog-content { min-width:0; } .attachment-actions { display:grid; grid-template-columns:1fr; } }
  `],
})
export class VisitUpdateDialog {
  readonly data = inject<VisitUpdateDialogData>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  protected readonly note = signal('');
  protected readonly photo = signal<File | null>(null);
  protected readonly audio = signal<File | null>(null);
  protected readonly phase = signal<EvidencePhase>('DURING_WORK');
  protected readonly document = signal<File | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly ref = inject(MatDialogRef<VisitUpdateDialog, boolean>);
  private readonly api = inject(JobsApi);
  private readonly toast = inject(ToastService);
  protected select(event: Event, kind: 'photo' | 'document'): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    if (kind === 'photo') this.photo.set(file);
    else this.document.set(file);
  }
  protected hasContent(): boolean { return !!(this.note().trim() || this.photo() || this.audio() || this.document()); }
  protected async post(): Promise<void> {
    if (this.busy() || !this.hasContent()) return;
    this.busy.set(true); this.error.set(null);
    try {
      const body = this.note().trim();
      if (body) await firstValueFrom(this.api.addVisitNote(this.data.jobId, this.data.visitId, body));
      if (this.photo()) await firstValueFrom(this.api.addPhoto(this.data.jobId, this.photo()!, body, this.data.visitId, this.phase()));
      if (this.audio()) await firstValueFrom(this.api.addAudio(this.data.jobId, this.audio()!, body, this.data.visitId, this.phase()));
      if (this.document()) await firstValueFrom(this.api.addDocument(this.data.jobId, this.document()!, this.data.visitId));
      this.toast.success(this.i18n.text('jobUpdateSent'));
      this.ref.close(true);
    } catch (error) { this.error.set(jobWriteError(error)); }
    finally { this.busy.set(false); }
  }
}
