import { DatePipe } from '@angular/common';
import { DomSanitizer } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { TemplateRef } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { Subscription } from 'rxjs';
import type { EvidencePhase, JobActivityEvent, OfficeJobDetails } from '@servora/shared-types';
import { AuthSession } from '../../auth/data-access/auth-session';
import { I18n } from '../../../core/i18n/i18n';
import { ToastService } from '../../../core/notifications/toast.service';
import { Icon } from '../../../shared/ui/icon';
import { PageTitle } from '../../../shared/ui/page-title';
import { WorkLabel } from '../../../shared/ui/work-label';
import { WorkStatus } from '../../../shared/ui/work-status';
import { PreferredTime } from '../../../shared/ui/preferred-time';
import { addressText, directionsUrl } from '../../../shared/ui/work-format';
import { JobsApi } from '../data-access/jobs-api';
import { jobWriteError } from '../data-access/job-errors';
import { FieldSession } from '../../field/data-access/field-session';
import { JobActionConfirm } from '../ui/job-action-confirm';
import { JobActivityEntry } from '../ui/job-activity-entry';
import { ScheduleVisit } from '../ui/schedule-visit';
import { VisitUpdateDialog } from '../ui/visit-update-dialog';
import { CompleteVisitDialog } from '../ui/complete-visit-dialog';
import { CorrectVisitOutcomeDialog } from '../ui/correct-visit-outcome-dialog';
import { EditJobCommentDialog, type EditJobCommentResult } from '../ui/edit-job-comment-dialog';
import { DeleteJobCommentDialog } from '../ui/delete-job-comment-dialog';
import { AudioRecorder } from '../ui/audio-recorder';
import type { TranslationKey } from '../../../core/i18n/en';

@Component({
  selector: 'app-job-details',
  imports: [
    DatePipe, RouterLink, MatButtonModule, MatDialogModule, MatMenuModule,
    PageTitle, Icon, WorkLabel, WorkStatus, JobActivityEntry, AudioRecorder, PreferredTime,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './job-details.html',
  styleUrl: './job-details.scss',
})
export class JobDetails {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  private readonly route = inject(ActivatedRoute);
  private readonly params = toSignal(this.route.paramMap);
  protected readonly query = toSignal(this.route.queryParamMap);
  private readonly api = inject(JobsApi);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly dialogs = inject(MatDialog);
  private followUpDialogRef: MatDialogRef<unknown> | null = null;
  private readonly toast = inject(ToastService);
  private readonly fieldSession = inject(FieldSession);
  private readonly revision = signal(0);
  protected readonly job = signal<OfficeJobDetails | null>(null);
  protected readonly activity = signal<JobActivityEvent[]>([]);
  protected readonly draft = signal('');
  protected readonly photoFile = signal<File | null>(null);
  protected readonly audioFile = signal<File | null>(null);
  protected readonly documentFile = signal<File | null>(null);
  protected readonly evidencePhase = signal<EvidencePhase>('DURING_WORK');
  private readonly photoOperationId = signal<string | null>(null);
  private readonly audioOperationId = signal<string | null>(null);
  private readonly documentOperationId = signal<string | null>(null);
  protected readonly updateVisitId = signal<string | 'job' | null>(null);
  protected readonly busy = signal(false);
  protected readonly posting = signal(false);
  protected readonly loading = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly postError = signal<TranslationKey | null>(null);
  protected readonly sortNewest = signal(true);
  protected readonly timelineTab = signal<'visits' | 'general'>('visits');
  protected readonly followUpStart = signal('');
  protected readonly followUpEnd = signal('');
  protected readonly followUpReason = signal('');
  protected readonly address = addressText;
  protected readonly directions = directionsUrl;
  protected readonly fieldReady = computed(() => !this.fieldView() || !!this.fieldSession.context());
  protected readonly officeAllowed = computed(() => this.auth.hasPermission('customers.view'));
  private readonly fieldAllowed = computed(() => this.auth.hasPermission('VISIT_VIEW_ASSIGNED'));
  protected fieldView(): boolean {
    return this.query()?.get('view') === 'field' || (!this.officeAllowed() && this.fieldAllowed());
  }
  protected jobId(): string { return this.params()?.get('jobId') ?? ''; }
  protected updateVisits(job: OfficeJobDetails) {
    return [...job.visits].sort((a, b) => b.sequence - a.sequence);
  }
  protected selectedUpdateVisit(job: OfficeJobDetails): string | null {
    if (this.fieldView()) return job.selectedVisit?.id ?? null;
    const selected = this.updateVisitId();
    if (selected === 'job') return null;
    if (selected && job.visits.some((visit) => visit.id === selected)) return selected;
    const current = this.updateVisits(job).find((visit) => !['COMPLETED', 'CANCELED'].includes(visit.status))
      ?? this.updateVisits(job)[0];
    return current?.id ?? null;
  }
  protected updateDestinationChanged(value: string): void { this.updateVisitId.set(value || 'job'); }
  protected orderedActivity(): JobActivityEvent[] {
    return [...this.activity()].sort((a, b) =>
      (this.sortNewest() ? -1 : 1) * (Date.parse(a.recordedAt) - Date.parse(b.recordedAt)),
    );
  }
  protected generalActivity(): JobActivityEvent[] {
    return this.orderedActivity().filter((event) =>
      event.visitSequence == null || !this.isVisitUpdate(event),
    );
  }
  protected attentionSummary(
    job: OfficeJobDetails,
    attention: OfficeJobDetails['attention'][number],
  ): string | null {
    const visit = job.visits.find((candidate) => candidate.id === attention.relevantVisitId);
    if (!visit) return null;
    const result = this.activity()
      .filter((event) => event.visitSequence === visit.sequence && event.kind === 'VISIT_OUTCOME_RECORDED')
      .sort((left, right) => Date.parse(right.recordedAt) - Date.parse(left.recordedAt))[0];
    return result?.outcomeSummary?.trim() ?? null;
  }
  protected visitActivity(sequence: number): JobActivityEvent[] {
    return this.orderedActivity().filter((event) =>
      event.visitSequence === sequence && this.isVisitUpdate(event),
    );
  }
  private isVisitUpdate(event: JobActivityEvent): boolean {
    return [
      'VISIT_NOTE_ADDED',
      'VISIT_OUTCOME_RECORDED',
      'JOB_PHOTO_ADDED',
      'JOB_PHOTO_REMOVED',
      'JOB_AUDIO_ADDED',
      'JOB_AUDIO_REMOVED',
      'JOB_DOCUMENT_ATTACHED',
    ]
      .includes(event.kind);
  }
  protected activityVisits(job: OfficeJobDetails) {
    return [...job.visits].sort((a, b) => {
      const aDate = a.scheduledStart ? Date.parse(a.scheduledStart) : 0;
      const bDate = b.scheduledStart ? Date.parse(b.scheduledStart) : 0;
      return (this.sortNewest() ? -1 : 1) * (aDate - bDate || a.sequence - b.sequence);
    });
  }
  protected visitStatusOptions(status: string): string[] {
    const selected = this.job()?.selectedVisit;
    return selected?.status === status ? selected.allowedStatusTransitions : [];
  }
  protected visitUpdateAllowed(visit: OfficeJobDetails['visits'][number]): boolean {
    const selected = this.job()?.selectedVisit;
    return !!selected && selected.id === visit.id && selected.addUpdateAllowed &&
      (this.auth.hasPermission('JOB_UPDATE') || this.auth.hasPermission('VISIT_ADD_NOTE'));
  }
  protected canScheduleVisit(job: OfficeJobDetails): boolean {
    if (this.fieldView()) return false;
    const hasOpenVisit = job.visits.some((visit) =>
      ['SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS'].includes(visit.status),
    );
    return job.canScheduleVisit && !hasOpenVisit;
  }
  constructor() {
    if (this.fieldView()) void this.fieldSession.verify().catch(() => {});
    effect((cleanup) => {
      const id = this.params()?.get('jobId');
      this.revision(); this.auth.boundary();
      this.job.set(null); this.activity.set([]); this.error.set(null);
      if (!id) return;
      if (!this.officeAllowed() && !this.fieldAllowed()) { this.error.set('forbiddenDescription'); return; }
      this.loading.set(true);
      let activityRequest: Subscription | null = null;
      const requestedVisit = this.fieldView() ? this.query()?.get('visit') : null;
      const request = this.api.detail(id, requestedVisit).subscribe({
        next: (job) => {
          this.job.set(job); this.loading.set(false);
          activityRequest = this.api.activity(job.id).subscribe({ next: (feed) => this.activity.set(feed.events), error: () => {} });
        },
        error: (err: unknown) => {
          this.loading.set(false);
          this.error.set(err instanceof HttpErrorResponse
            ? err.status === 403 ? 'forbiddenDescription' : err.status === 404 ? 'jobUnavailable' : err.status === 0 ? 'connectionError' : 'unexpectedError'
            : 'unexpectedError');
        },
      });
      cleanup(() => { request.unsubscribe(); activityRequest?.unsubscribe(); });
    });
  }
  protected refresh(): void { if (!this.busy()) this.revision.update((v) => v + 1); }
  protected setPhoto(event: Event): void { this.photoFile.set((event.target as HTMLInputElement).files?.[0] ?? null); this.photoOperationId.set(crypto.randomUUID()); }
  protected setRecordedAudio(file: File): void { this.audioFile.set(file); this.audioOperationId.set(crypto.randomUUID()); }
  protected clearRecordedAudio(): void { this.audioFile.set(null); this.audioOperationId.set(null); }
  protected setDocument(event: Event): void { this.documentFile.set((event.target as HTMLInputElement).files?.[0] ?? null); this.documentOperationId.set(crypto.randomUUID()); }
  protected async scheduleVisit(): Promise<void> {
    const job = this.job();
    if (!job || this.busy() || !this.canScheduleVisit(job)) return;
    this.busy.set(true);
    let reload = false;
    try {
      const dialog = this.dialogs.open<ScheduleVisit, OfficeJobDetails, 'scheduled' | 'reload' | undefined>(ScheduleVisit, {
        data: job, width: '680px', maxWidth: '94vw', disableClose: true,
      });
      const result = await firstValueFrom(dialog.afterClosed());
      reload = !!result;
    } finally { this.busy.set(false); }
    if (reload) this.refresh();
  }
  protected async changeStatus(status: string): Promise<void> {
    const job = this.job();
    if (!job || this.busy() || !this.auth.hasPermission('JOB_UPDATE') || !job.allowedStatusTransitions.includes(status)) return;
    const confirmation = await firstValueFrom(this.dialogs.open<JobActionConfirm, unknown, { note: string | null } | null>(JobActionConfirm, {
      data: { job, status }, width: '520px', maxWidth: '94vw',
    }).afterClosed());
    if (!confirmation) return;
    this.busy.set(true);
    try {
      await firstValueFrom(this.api.status(job.id, { status, note: confirmation.note, expectedVersion: job.version }));
    } finally { this.busy.set(false); }
    this.refresh();
  }
  protected async changeVisitStatus(visitId: string, status: string, expectedVersion: number): Promise<void> {
    const job = this.job();
    if (!job || this.busy() || !(this.auth.hasPermission('JOB_UPDATE') || this.auth.hasPermission('VISIT_UPDATE_ASSIGNED_STATUS')) ||
      job.selectedVisit?.id !== visitId || !job.selectedVisit.allowedStatusTransitions.includes(status)) return;
    this.busy.set(true);
    try { await firstValueFrom(this.api.visitStatus(job.id, visitId, status, expectedVersion)); }
    catch (error) { this.postError.set(jobWriteError(error)); }
    finally { this.busy.set(false); }
    this.refresh();
  }
  protected async addVisitUpdate(visit: OfficeJobDetails['visits'][number]): Promise<void> {
    const job = this.job();
    if (!job || this.busy() || !this.visitUpdateAllowed(visit)) return;
    const result = await firstValueFrom(this.dialogs.open<VisitUpdateDialog, { jobId: string; visitId: string; visitSequence: number }, boolean>(VisitUpdateDialog, {
      data: { jobId: job.id, visitId: visit.id, visitSequence: visit.sequence }, width: '620px', maxWidth: '94vw',
    }).afterClosed());
    if (result) this.refresh();
  }
  protected visitCompletionAllowed(visit: OfficeJobDetails['visits'][number]): boolean {
    const selected = this.job()?.selectedVisit;
    return !!selected && selected.id === visit.id && selected.completionAllowed &&
      this.auth.hasPermission('VISIT_RECORD_OUTCOME');
  }
  protected visitOutcomeCorrectionAllowed(visit: OfficeJobDetails['visits'][number]): boolean {
    const selected = this.job()?.selectedVisit;
    return !!selected && selected.id === visit.id && selected.outcomeCorrectionAllowed &&
      this.currentOutcomeEvent(visit) !== null &&
      this.auth.hasPermission('VISIT_RECORD_OUTCOME');
  }
  private currentOutcomeEvent(visit: OfficeJobDetails['visits'][number]): JobActivityEvent | null {
    const latest = this.activity()
      .filter((event) => event.visitSequence === visit.sequence && event.kind === 'VISIT_OUTCOME_RECORDED')
      .sort((left, right) => Date.parse(right.recordedAt) - Date.parse(left.recordedAt))[0];
    return latest?.outcomeCode === visit.outcomeCode ? latest : null;
  }
  protected async completeVisit(visit: OfficeJobDetails['visits'][number]): Promise<void> {
    const job = this.job();
    const selected = job?.selectedVisit;
    if (!job || !selected || selected.id !== visit.id || !this.visitCompletionAllowed(visit) || this.busy()) return;
    const completed = await firstValueFrom(this.dialogs.open<CompleteVisitDialog, {
      jobId: string; visitId: string; visitSequence: number; purposeCode: string; expectedVersion: number;
    }, boolean>(CompleteVisitDialog, {
      data: {
        jobId: job.id,
        visitId: visit.id,
        visitSequence: visit.sequence,
        purposeCode: visit.purposeCode,
        expectedVersion: visit.version,
      },
      width: '540px',
      maxWidth: '94vw',
    }).afterClosed());
    if (completed) this.refresh();
  }
  protected async correctVisitOutcome(visit: OfficeJobDetails['visits'][number]): Promise<void> {
    const job = this.job();
    const selected = job?.selectedVisit;
    if (!job || !selected || selected.id !== visit.id || !this.visitOutcomeCorrectionAllowed(visit) || this.busy()) return;
    const latest = this.currentOutcomeEvent(visit);
    if (!latest?.outcomeCode || !latest.outcomeSummary) return;
    const corrected = await firstValueFrom(this.dialogs.open<CorrectVisitOutcomeDialog, {
      jobId: string; visitId: string; visitSequence: number; purposeCode: string;
      outcomeCode: string; outcomeSummary: string; expectedVersion: number;
    }, boolean>(CorrectVisitOutcomeDialog, {
      data: {
        jobId: job.id,
        visitId: visit.id,
        visitSequence: visit.sequence,
        purposeCode: visit.purposeCode,
        outcomeCode: latest.outcomeCode,
        outcomeSummary: latest.outcomeSummary,
        expectedVersion: visit.version,
      },
      width: '540px',
      maxWidth: '94vw',
    }).afterClosed());
    if (corrected) this.refresh();
  }
  protected async requestFollowUp(): Promise<void> {
    const job = this.job();
    const visit = job?.selectedVisit;
    const start = this.followUpStart();
    const end = this.followUpEnd();
    const reason = this.followUpReason().trim();
    const fieldScope = this.fieldView() ? this.fieldSession.context() : null;
    if (!job || !visit?.requestFollowUpAllowed || !this.auth.hasPermission('visits.request_follow_up') ||
      !fieldScope || !start || !end || !reason || Date.parse(start) >= Date.parse(end) || this.busy()) return;
    this.busy.set(true); this.postError.set(null);
    try {
      const submitted = await firstValueFrom(this.api.requestFollowUp(job.id, {
        sourceVisitId: visit.id,
        proposedStart: new Date(start).toISOString(),
        proposedEnd: new Date(end).toISOString(),
        reason,
        sameTechnicianPreferred: true,
      }, fieldScope));
      this.job.update((current) => current?.id === job.id ? {
        ...current,
        followUpVisitRequest: {
          id: submitted.id,
          jobId: submitted.jobId,
          sourceVisitId: submitted.sourceVisitId,
          createdVisitId: submitted.createdVisitId,
          requestingTechnicianMembershipId: submitted.requestingTechnicianMembershipId,
          status: submitted.status,
          version: submitted.version,
        },
      } : current);
      this.followUpStart.set(''); this.followUpEnd.set(''); this.followUpReason.set('');
      this.followUpDialogRef?.close();
      this.followUpDialogRef = null;
      this.toast.success(this.i18n.text('visitRequestCreated'));
    } catch (error) { this.postError.set(jobWriteError(error)); }
    finally { this.busy.set(false); }
  }
  protected followUpStatusMessage(status: string): TranslationKey {
    switch (status) {
      case 'PENDING': return 'fieldRequestAwaitingOffice';
      case 'NEEDS_CLARIFICATION': return 'fieldReplyHint';
      case 'APPROVED': return 'fieldRequestApproved';
      case 'REJECTED': return 'fieldRequestRejected';
      default: return 'fieldRequestAwaitingOffice';
    }
  }
  protected openFollowUpRequest(template: TemplateRef<unknown>): void {
    if (this.followUpDialogRef) return;
    this.postError.set(null);
    this.followUpDialogRef = this.dialogs.open(template, {
      width: '540px', maxWidth: '94vw', ariaLabel: this.i18n.text('fieldRequestAnotherVisit'),
    });
    this.followUpDialogRef.afterClosed().subscribe(() => {
      this.followUpDialogRef = null;
      this.followUpStart.set(''); this.followUpEnd.set(''); this.followUpReason.set('');
      this.postError.set(null);
    });
  }
  protected closeFollowUpRequest(): void { this.followUpDialogRef?.close(); }
  protected async postUpdate(): Promise<void> {
    const job = this.job();
    const body = this.draft().trim();
    const photo = this.photoFile(), audio = this.audioFile(), document = this.documentFile();
    const mayPost = this.auth.hasPermission('JOB_UPDATE') ||
      (this.auth.hasPermission('VISIT_ADD_NOTE') && !!job?.selectedVisit);
    if (!job || (!body && !photo && !audio && !document) || this.busy() || !mayPost) return;
    const visitId = this.selectedUpdateVisit(job);
    if (!this.auth.hasPermission('JOB_UPDATE') && !visitId) return;
    const fieldScope = this.fieldView() ? this.fieldSession.context() ?? undefined : undefined;
    if (this.fieldView() && !fieldScope) return;
    this.busy.set(true); this.posting.set(true); this.postError.set(null);
    let latestFeed: JobActivityEvent[] | null = null;
    try {
      if (body && !photo && !audio) {
        if (visitId) {
          const feed = await firstValueFrom(this.api.addVisitNote(job.id, visitId, body, fieldScope));
          latestFeed = feed.events; this.activity.set(feed.events);
        } else {
          const feed = await firstValueFrom(this.api.addJobNote(job.id, body));
          latestFeed = feed.events; this.activity.set(feed.events);
        }
      }
      if (photo) {
        const feed = await firstValueFrom(this.api.addPhoto(job.id, photo, body, visitId, this.evidencePhase(), this.photoOperationId() ?? undefined, fieldScope));
        latestFeed = feed.events; this.photoFile.set(null); this.photoOperationId.set(null); this.activity.set(feed.events);
      }
      if (audio) {
        const feed = await firstValueFrom(this.api.addAudio(job.id, audio, photo ? '' : body, visitId, this.evidencePhase(), this.audioOperationId() ?? undefined, fieldScope));
        latestFeed = feed.events; this.audioFile.set(null); this.audioOperationId.set(null); this.activity.set(feed.events);
      }
      if (document) {
        await firstValueFrom(this.api.addDocument(job.id, document, visitId, this.documentOperationId() ?? undefined, fieldScope));
        this.documentFile.set(null); this.documentOperationId.set(null);
      }
      if (body) this.draft.set('');
      if (!latestFeed || document) {
        try {
          const feed = await firstValueFrom(this.api.activity(job.id));
          this.activity.set(feed.events);
        } catch { /* The write succeeded; a failed timeline refresh must not report it as a failed post. */ }
      }
      this.toast.success(this.i18n.text('jobUpdateSent'));
    } catch (error) { this.postError.set(jobWriteError(error)); }
    finally { this.posting.set(false); this.busy.set(false); }
  }

  protected async editActivity(event: JobActivityEvent): Promise<void> {
    const job = this.job();
    if (!job || !event.canEdit || this.busy()) return;
    const result = await firstValueFrom(this.dialogs.open<
      EditJobCommentDialog, { event: JobActivityEvent }, EditJobCommentResult | undefined
    >(EditJobCommentDialog, {
      data: { event }, width: '560px', maxWidth: '94vw',
    }).afterClosed());
    if (!result) return;
    this.busy.set(true); this.postError.set(null);
    try {
      let response;
      if (event.kind === 'JOB_NOTE_ADDED') response = await firstValueFrom(this.api.editJobNote(job.id, event.id, result.body));
      else if (event.kind === 'VISIT_NOTE_ADDED') response = await firstValueFrom(this.api.editVisitNote(job.id, event.id, result.body));
      else if (event.photoId) response = await firstValueFrom(this.api.editPhoto(job.id, event.photoId, result.file, result.body, result.phase));
      else if (event.audioNoteId) response = await firstValueFrom(this.api.editAudio(job.id, event.audioNoteId, result.file, result.body, result.phase));
      else if (event.documentId) response = await firstValueFrom(this.api.editDocument(job.id, event.documentId, result.file, result.body));
      else return;
      this.activity.set(response.events);
      this.toast.success(this.i18n.text('jobCommentSaved'));
    } catch (error) { this.postError.set(jobWriteError(error)); }
    finally { this.busy.set(false); }
  }

  protected async deleteActivity(event: JobActivityEvent): Promise<void> {
    const job = this.job();
    if (!job || !event.canDelete || this.busy()) return;
    const confirmed = await firstValueFrom(this.dialogs.open<DeleteJobCommentDialog, undefined, boolean>(
      DeleteJobCommentDialog, { width: '420px', maxWidth: '94vw' },
    ).afterClosed());
    if (!confirmed) return;
    this.busy.set(true); this.postError.set(null);
    try {
      let response;
      if (event.kind === 'JOB_NOTE_ADDED' || event.kind === 'VISIT_NOTE_ADDED') {
        response = await firstValueFrom(this.api.removeNote(job.id, event.id, event.kind === 'VISIT_NOTE_ADDED'));
      } else if (event.photoId) {
        response = await firstValueFrom(this.api.remove(job.id, event.photoId, false, this.i18n.text('jobRemovalReasonFromAuthor')));
      } else if (event.audioNoteId) {
        response = await firstValueFrom(this.api.remove(job.id, event.audioNoteId, true, this.i18n.text('jobRemovalReasonFromAuthor')));
      } else if (event.documentId) {
        response = await firstValueFrom(this.api.removeDocument(job.id, event.documentId));
      } else return;
      this.activity.set(response.events);
      this.toast.success(this.i18n.text('jobCommentDeleted'));
    } catch (error) { this.postError.set(jobWriteError(error)); }
    finally { this.busy.set(false); }
  }

  protected async openPhoto(event: JobActivityEvent, viewer: TemplateRef<unknown>): Promise<void> {
    if (!event.photoId || !this.auth.hasPermission('evidence.view')) return;
    try {
      const blob = await firstValueFrom(this.api.content(this.jobId(), event.photoId, false));
      const url = URL.createObjectURL(blob);
      const dialog = this.dialogs.open(viewer, {
        width: 'min(1000px, 94vw)', maxWidth: '94vw', data: { url },
        ariaLabel: this.i18n.text('fieldViewPhoto'),
      });
      dialog.afterClosed().subscribe(() => URL.revokeObjectURL(url));
    } catch {
      this.toast.error(this.i18n.text('jobPhotoUnavailable'));
    }
  }

  protected async openDocument(event: JobActivityEvent, viewer: TemplateRef<unknown>): Promise<void> {
    if (!event.documentId) return;
    try {
      const blob = await firstValueFrom(this.api.documentContent(this.jobId(), event.documentId));
      const url = URL.createObjectURL(blob);
      const dialog = this.dialogs.open(viewer, {
        width: 'min(1000px, 94vw)', maxWidth: '94vw', data: {
          url, safeUrl: this.sanitizer.bypassSecurityTrustResourceUrl(url),
          name: event.documentDisplayName || 'document.pdf',
        },
        ariaLabel: this.i18n.text('jobOpenPdf'),
      });
      dialog.afterClosed().subscribe(() => URL.revokeObjectURL(url));
    } catch { this.postError.set('unexpectedError'); }
  }
}
