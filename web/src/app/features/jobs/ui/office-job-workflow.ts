import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  TemplateRef,
  untracked,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import type { JobActivity, JobActivityEvent, OfficeJobDetails } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { WorkLabel } from '../../../shared/ui/work-label';
import { AuthSession } from '../../auth/data-access/auth-session';
import { JobsApi } from '../data-access/jobs-api';
import { jobReadError, jobWriteError } from '../data-access/job-errors';
import { projectActivity, type ActivityEntry, type ActivityView } from './job-activity-model';
import { JobActivityEntry } from './job-activity-entry';
import { JobActionConfirm } from './job-action-confirm';
import { ScheduleVisit } from './schedule-visit';
@Component({
  selector: 'app-office-job-workflow',
  imports: [
    DatePipe,
    MatButtonToggleModule,
    MatButtonModule,
    MatDialogModule,
    WorkLabel,
    JobActivityEntry,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './office-job-workflow.html',
  styleUrl: './office-job-workflow.scss',
})
export class OfficeJobWorkflow {
  readonly job = input.required<OfficeJobDetails>();
  readonly changed = output<void>();
  readonly working = output<boolean>();
  protected readonly auth = inject(AuthSession);
  protected readonly i18n = inject(I18n);
  private readonly api = inject(JobsApi);
  private readonly dialogs = inject(MatDialog);
  private readonly destroy = inject(DestroyRef);
  protected readonly activity = signal<JobActivity | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly writeError = signal<TranslationKey | null>(null);
  protected readonly busy = signal(false);
  protected readonly retainedNote = signal<string | null>(null);
  protected readonly blocked = signal(false);
  protected readonly audit = signal(false);
  protected readonly media = signal<{
    id: string;
    audio: boolean;
    url: string;
    event: JobActivityEvent;
  } | null>(null);
  protected readonly mediaError = signal<TranslationKey | null>(null);
  protected readonly mediaLoading = signal(false);
  private readonly revision = signal(0);
  protected readonly view = signal<ActivityView>('updates');
  protected readonly visibleCount = signal(12);
  protected readonly feed = computed(() =>
    projectActivity(this.activity()?.events ?? [], this.view(), this.visibleCount()),
  );
  private viewer: MatDialogRef<unknown> | null = null;
  protected changeView(value: ActivityView): void {
    if (!['all', 'updates', 'history'].includes(value)) return;
    this.clearMedia();
    this.view.set(value);
    this.visibleCount.set(12);
  }
  protected showOlder(): void {
    this.visibleCount.update((count) => count + 12);
  }
  protected summaryLabel(entry: ActivityEntry): string {
    const count = entry.events.length;
    const labels = {
      crew: ['activityCrewChange', 'activityCrewChanges'],
      status: ['activityStatusChange', 'activityStatusChanges'],
      removal: ['activityEvidenceChange', 'activityEvidenceChanges'],
      other: ['activitySystemChange', 'activitySystemChanges'],
    } as const;
    return `${count} ${this.i18n.text(labels[entry.category][count === 1 ? 0 : 1])}`;
  }
  private generation = 0;
  private mediaGeneration = 0;
  protected readonly canUpdate = computed(() => this.auth.hasPermission('JOB_UPDATE'));
  protected readonly canRead = computed(() => this.auth.hasPermission('customers.view'));
  protected readonly canViewEvidence = computed(() => this.auth.hasPermission('evidence.view'));
  protected readonly canAudit = computed(
    () =>
      this.auth.hasPermission('JOB_UPDATE') ||
      this.auth.hasPermission('evidence.photo.remove') ||
      this.auth.hasPermission('evidence.audio.remove'),
  );
  constructor() {
    effect(() => this.working.emit(this.busy()));
    effect((cleanup) => {
      const id = this.job().id,
        boundary = this.auth.boundary(),
        audit = this.audit() && this.canAudit();
      this.revision();
      this.clearMedia();
      this.mediaError.set(null);
      this.activity.set(null);
      this.visibleCount.set(12);
      this.error.set(null);
      const generation = ++this.generation;
      if (!this.canRead()) {
        this.loading.set(false);
        return;
      }
      this.loading.set(true);
      const request = this.api.activity(id, audit).subscribe({
        next: (activity) => {
          if (boundary === this.auth.boundary() && generation === this.generation) {
            this.activity.set(activity);
            this.loading.set(false);
          }
        },
        error: (error: unknown) => {
          if (boundary === this.auth.boundary() && generation === this.generation) {
            this.error.set(jobReadError(error));
            this.loading.set(false);
          }
        },
      });
      cleanup(() => request.unsubscribe());
    });
    effect(() => {
      if (!this.canViewEvidence()) this.clearMedia();
    });
    this.destroy.onDestroy(() => {
      this.generation++;
      this.clearMedia();
    });
  }
  protected reloadActivity() {
    if (!this.busy()) this.revision.update((v) => v + 1);
  }
  protected reload() {
    if (!this.busy()) this.changed.emit();
  }
  protected toggleAudit() {
    if (!this.busy()) this.audit.update((v) => !v);
  }
  private current(id: string, boundary: number) {
    return !this.destroy.destroyed && id === this.job().id && boundary === this.auth.boundary();
  }
  protected async scheduleVisit(): Promise<void> {
    const job = this.job(),
      boundary = this.auth.boundary();
    if (this.busy() || this.blocked() || !job.canScheduleVisit) return;
    this.busy.set(true);
    try {
      const dialog = this.dialogs.open<
        ScheduleVisit,
        OfficeJobDetails,
        'scheduled' | 'reload' | undefined
      >(ScheduleVisit, {
        data: job,
        width: '680px',
        maxWidth: '94vw',
        disableClose: true,
      });
      this.destroy.onDestroy(() => dialog.close());
      const result = await firstValueFrom(dialog.afterClosed());
      if (result && this.current(job.id, boundary)) this.changed.emit();
    } finally {
      if (this.current(job.id, boundary)) this.busy.set(false);
    }
  }
  protected async status(status: string) {
    const job = this.job(),
      boundary = this.auth.boundary();
    if (
      this.busy() ||
      this.blocked() ||
      !this.canUpdate() ||
      !job.allowedStatusTransitions.includes(status)
    )
      return;
    this.busy.set(true);
    try {
      const confirmation = await firstValueFrom(
        this.dialogs
          .open<JobActionConfirm, unknown, { note: string | null } | null>(JobActionConfirm, {
            data: { job, status },
            width: '520px',
          })
          .afterClosed(),
      );
      if (!confirmation || !this.current(job.id, boundary) || !this.canUpdate()) return;
      this.retainedNote.set(confirmation.note);
      this.writeError.set(null);
      await firstValueFrom(
        this.api.status(job.id, { status, note: confirmation.note, expectedVersion: job.version }),
      );
      if (this.current(job.id, boundary)) this.changed.emit();
    } catch (error) {
      if (this.current(job.id, boundary)) {
        this.writeError.set(jobWriteError(error));
        this.blocked.set(true);
      }
    } finally {
      this.busy.set(false);
    }
  }
  protected removable(event: JobActivityEvent): boolean {
    return (
      (event.kind === 'JOB_PHOTO_ADDED' &&
        !!event.photoId &&
        this.auth.hasPermission('evidence.photo.remove') &&
        !this.removed(event)) ||
      (event.kind === 'JOB_AUDIO_ADDED' &&
        !!event.audioNoteId &&
        this.auth.hasPermission('evidence.audio.remove') &&
        !this.removed(event))
    );
  }
  protected removed(event: JobActivityEvent): boolean {
    return (
      this.activity()?.events.some(
        (e) =>
          (event.photoId && e.photoId === event.photoId && e.kind === 'JOB_PHOTO_REMOVED') ||
          (event.audioNoteId &&
            e.audioNoteId === event.audioNoteId &&
            e.kind === 'JOB_AUDIO_REMOVED'),
      ) ?? false
    );
  }
  protected async remove(event: JobActivityEvent) {
    const job = this.job(),
      boundary = this.auth.boundary(),
      audio = !!event.audioNoteId,
      id = event.audioNoteId || event.photoId;
    if (!id || this.busy() || this.blocked() || !this.removable(event)) return;
    this.busy.set(true);
    try {
      const confirmation = await firstValueFrom(
        this.dialogs
          .open<JobActionConfirm, unknown, { note: string | null } | null>(JobActionConfirm, {
            data: { job, removal: true, audio },
            width: '520px',
          })
          .afterClosed(),
      );
      if (!confirmation?.note || !this.current(job.id, boundary) || !this.removable(event)) return;
      this.retainedNote.set(confirmation.note);
      await firstValueFrom(this.api.remove(job.id, id, audio, confirmation.note));
      if (this.current(job.id, boundary)) {
        this.clearMedia();
        this.revision.update((v) => v + 1);
      }
    } catch (error) {
      if (this.current(job.id, boundary)) {
        this.writeError.set(jobWriteError(error));
        this.blocked.set(true);
      }
    } finally {
      this.busy.set(false);
    }
  }
  protected clearMedia() {
    this.mediaGeneration++;
    this.viewer?.close();
    this.viewer = null;
    const content = untracked(this.media);
    if (content) URL.revokeObjectURL(content.url);
    this.media.set(null);
    this.mediaLoading.set(false);
  }
  protected async open(event: JobActivityEvent, viewer: TemplateRef<unknown>) {
    const id = event.audioNoteId || event.photoId,
      audio = !!event.audioNoteId;
    if (!id || !this.canViewEvidence() || this.removed(event)) return;
    const jobId = this.job().id,
      boundary = this.auth.boundary();
    this.clearMedia();
    this.mediaError.set(null);
    this.mediaLoading.set(true);
    const generation = this.mediaGeneration;
    try {
      const blob = await firstValueFrom(this.api.content(jobId, id, audio));
      if (
        !this.current(jobId, boundary) ||
        generation !== this.mediaGeneration ||
        !this.canViewEvidence()
      )
        return;
      this.media.set({ id, audio, url: URL.createObjectURL(blob), event });
      if (!audio) {
        const dialog = this.dialogs.open(viewer, {
          width: '960px',
          maxWidth: '94vw',
          ariaLabel: this.i18n.text('fieldViewPhoto'),
        });
        this.viewer = dialog;
        dialog.afterClosed().subscribe(() => {
          if (this.viewer === dialog) this.clearMedia();
        });
      }
    } catch (error) {
      if (this.current(jobId, boundary) && generation === this.mediaGeneration)
        this.mediaError.set(jobReadError(error));
    } finally {
      if (generation === this.mediaGeneration) this.mediaLoading.set(false);
    }
  }
}
