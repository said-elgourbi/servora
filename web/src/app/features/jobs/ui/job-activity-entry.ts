import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  OnDestroy,
  TemplateRef,
  effect,
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
import { PreferredTime } from '../../../shared/ui/preferred-time';
import { JobPhotoPreview } from './job-photo-preview';
import { activityText } from './job-activity-model';
import { JobsApi } from '../data-access/jobs-api';
import { ToastService } from '../../../core/notifications/toast.service';
import { firstValueFrom } from 'rxjs';

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
    PreferredTime,
    JobPhotoPreview,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './job-activity-entry.html',
  styleUrl: './job-activity-entry.scss',
})
export class JobActivityEntry implements OnDestroy {
  readonly value = input.required<JobActivityEvent>();
  readonly jobId = input.required<string>();
  readonly history = input(false);
  readonly evidenceAvailable = input(false);
  readonly removable = input(false);
  readonly busy = input(false);
  readonly media = input<{ id: string; audio: boolean; url: string } | null>(null);
  readonly opened = output<void>();
  readonly documentOpened = output<void>();
  readonly removed = output<void>();
  readonly editRequested = output<void>();
  readonly deleteRequested = output<void>();
  readonly mediaClosed = output<void>();
  protected readonly i18n = inject(I18n);
  protected readonly activityText = activityText;
  protected readonly expanded = signal(false);
  protected readonly editHistory = signal<{ id: string; previousValue: Record<string, unknown>; recordedAt: string }[]>([]);
  protected readonly historyEvent = signal<JobActivityEvent | null>(null);
  protected readonly historyError = signal(false);
  protected readonly audioUrl = signal<string | null>(null);
  protected readonly longNote = computed(() => {
    const text = activityText(this.value()).join('\n');
    return text.length > 400 || text.split('\n').length > 6;
  });
  private readonly dialogs = inject(MatDialog);
  private readonly destroy = inject(DestroyRef);
  private readonly api = inject(JobsApi);
  private readonly toast = inject(ToastService);
  private audioRequest = 0;
  private activeAudioUrl: string | null = null;
  private recordViewer: MatDialogRef<unknown> | null = null;
  constructor() {
    this.destroy.onDestroy(() => this.recordViewer?.close());
    effect(() => {
      const event = this.value();
      const allowed = this.evidenceAvailable();
      const request = ++this.audioRequest;
      this.releaseAudio();
      if (!allowed || !event.audioNoteId) return;
      void firstValueFrom(this.api.content(this.jobId(), event.audioNoteId, true)).then((blob) => {
        const url = URL.createObjectURL(blob);
        if (request !== this.audioRequest) { URL.revokeObjectURL(url); return; }
        this.activeAudioUrl = url;
        this.audioUrl.set(url);
      }).catch(() => {});
    });
  }
  ngOnDestroy(): void { this.audioRequest++; this.releaseAudio(); }
  private releaseAudio(): void {
    if (this.activeAudioUrl) URL.revokeObjectURL(this.activeAudioUrl);
    this.activeAudioUrl = null;
    this.audioUrl.set(null);
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
    if (event.kind === 'VISIT_OUTCOME_RECORDED')
      return event.previousOutcomeCode ? 'activityCorrectedResult' : 'activityRecordedResult';
    if (event.kind === 'JOB_PHOTO_ADDED') return 'activitySharedPhoto';
    if (event.kind === 'JOB_PHOTO_REMOVED') return 'activityRemovedPhoto';
    if (event.kind === 'JOB_AUDIO_ADDED') return 'activitySharedAudio';
    if (event.kind === 'JOB_AUDIO_REMOVED') return 'activityRemovedAudio';
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
      phase === 'BEFORE' || phase === 'BEFORE_WORK'
        ? 'fieldBefore'
        : phase === 'AFTER' || phase === 'AFTER_WORK'
          ? 'fieldAfter'
          : phase === 'DURING' || phase === 'DURING_WORK'
            ? 'fieldDuring'
            : 'activityNoPhase',
    );
  }
  protected editedAt(event: JobActivityEvent): string | null {
    return event.activityEditedAt || event.noteEditedAt;
  }
  protected historyBody(value: Record<string, unknown>): string {
    return typeof value['body'] === 'string' ? value['body'] : '';
  }
  protected historyPhase(value: Record<string, unknown>): string {
    return typeof value['phase'] === 'string' ? value['phase'] : '';
  }
  protected async showEditHistory(template: TemplateRef<unknown>): Promise<void> {
    const event = this.value();
    if (!event.canEdit || !this.editedAt(event)) return;
    this.historyError.set(false);
    this.historyEvent.set(event);
    try {
      this.editHistory.set(await firstValueFrom(this.api.activityHistory(this.jobId(), event.kind, event.id)));
      this.dialogs.open(template, {
        width: '560px', maxWidth: '94vw',
        data: { event }, ariaLabel: this.i18n.text('jobCommentHistory'),
      });
    } catch {
      this.historyError.set(true);
      this.toast.error(this.i18n.text('jobCommentHistoryUnavailable'));
    }
  }
}
