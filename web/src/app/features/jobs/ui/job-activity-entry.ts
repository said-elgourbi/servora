import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  TemplateRef,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { MatMenuModule } from '@angular/material/menu';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { Icon } from '../../../shared/ui/icon';
import { MatButtonModule } from '@angular/material/button';
import type { JobActivityEvent } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { WorkLabel } from '../../../shared/ui/work-label';
import { JobActivityLabel } from '../../../shared/ui/job-activity-label';
import { JobPhotoPreview } from './job-photo-preview';
import { activityText } from './job-activity-model';

@Component({
  selector: 'app-job-activity-entry',
  imports: [
    DatePipe,
    Icon,
    MatMenuModule,
    MatDialogModule,
    NgTemplateOutlet,
    MatButtonModule,
    WorkLabel,
    JobActivityLabel,
    JobPhotoPreview,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './job-activity-entry.html',
  styleUrl: './job-activity-entry.scss',
})
export class JobActivityEntry {
  readonly value = input.required<JobActivityEvent>();
  readonly jobId = input.required<string>();
  readonly history = input(false);
  readonly evidenceAvailable = input(false);
  readonly removable = input(false);
  readonly busy = input(false);
  readonly media = input<{ id: string; audio: boolean; url: string } | null>(null);
  readonly opened = output<void>();
  readonly removed = output<void>();
  readonly mediaClosed = output<void>();
  protected readonly i18n = inject(I18n);
  protected readonly activityText = activityText;
  protected readonly expanded = signal(false);
  protected readonly longNote = computed(() => {
    const text = activityText(this.value()).join('\n');
    return text.length > 400 || text.split('\n').length > 6;
  });
  private readonly dialogs = inject(MatDialog);
  private readonly destroy = inject(DestroyRef);
  private recordViewer: MatDialogRef<unknown> | null = null;
  constructor() {
    this.destroy.onDestroy(() => this.recordViewer?.close());
  }
  protected showRecord(template: TemplateRef<unknown>): void {
    this.recordViewer = this.dialogs.open(template, {
      width: '520px',
      maxWidth: '94vw',
      ariaLabel: this.i18n.text('activityEventDetails'),
    });
  }
  protected action(event: JobActivityEvent): TranslationKey {
    if (event.kind === 'VISIT_NOTE_ADDED') return 'activityAddedNote';
    if (event.kind === 'VISIT_OUTCOME_RECORDED') return 'activityRecordedResult';
    if (event.kind === 'JOB_PHOTO_ADDED') return 'activitySharedPhoto';
    if (event.kind === 'JOB_AUDIO_ADDED') return 'activitySharedAudio';
    if (event.kind === 'VISIT_SCHEDULED') return 'activityScheduledVisit';
    if (event.kind === 'VISIT_RESCHEDULED') return 'activityRescheduledVisit';
    return 'activitySharedUpdate';
  }
  protected entryIcon(event: JobActivityEvent): 'check' | 'photo' | 'audio' | 'calendar' | 'note' {
    if (event.outcomeCode) return 'check';
    if (event.photoId) return 'photo';
    if (event.audioNoteId) return 'audio';
    if (event.kind === 'VISIT_SCHEDULED' || event.kind === 'VISIT_RESCHEDULED') return 'calendar';
    return 'note';
  }
  protected phaseLabel(phase: string | null) {
    return this.i18n.text(
      phase === 'BEFORE'
        ? 'fieldBefore'
        : phase === 'AFTER'
          ? 'fieldAfter'
          : phase === 'DURING'
            ? 'fieldDuring'
            : 'activityNoPhase',
    );
  }
}
