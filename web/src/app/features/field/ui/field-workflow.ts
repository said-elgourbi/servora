import { directionsUrl } from '../../../shared/ui/work-format';
import { JobActivityLabel } from '../../../shared/ui/job-activity-label';
import { mp4AudioBlob } from '../data-access/field-media';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import type {
  FieldJobDetails,
  FieldActivityDto,
  FieldFollowUpDto,
  FieldContextDto,
} from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { AppUpdates } from '../../../core/updates/app-updates';
import { WorkLabel } from '../../../shared/ui/work-label';
import { WorkStatus } from '../../../shared/ui/work-status';
import { JobContact } from '../../jobs/ui/job-contact';
import { JobVisit } from '../../jobs/ui/job-visit';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
import { AuthSession } from '../../auth/data-access/auth-session';
import { FieldSession } from '../data-access/field-session';
import { FieldData } from '../data-access/field-data';
import { FieldQueue } from '../data-access/field-queue';
import {
  FieldStore,
  type FieldCommand,
  type FieldDraft,
  type DraftRecord,
} from '../data-access/field-store';

const blankDraft = (): FieldDraft => ({ note: '', outcome: '', summary: '', attachments: [] });
@Component({
  selector: 'app-field-workflow',
  imports: [
    JobActivityLabel,
    DatePipe,
    RouterLink,
    ReactiveFormsModule,
    WorkLabel,
    WorkStatus,
    JobContact,
    JobVisit,
  ],
  templateUrl: './field-workflow.html',
  styleUrl: './field-workflow.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FieldWorkflow {
  readonly jobId = input.required<string>();
  readonly visitId = input<string | null>(null);
  protected readonly directions = directionsUrl;
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  protected readonly session = inject(FieldSession);
  protected readonly store = inject(FieldStore);
  protected readonly queue = inject(FieldQueue);
  private readonly data = inject(FieldData);
  private readonly http = inject(HttpClient);
  private readonly updates = inject(AppUpdates);
  private scope: FieldContextDto | null = null;
  private generation = 0;
  private loadingDraft = false;
  private recorder: MediaRecorder | null = null;
  private stream: MediaStream | null = null;
  private recordTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly urls = new Map<string, string>();
  protected readonly job = signal<FieldJobDetails | null>(null);
  protected readonly activity = signal<FieldActivityDto | null>(null);
  protected readonly requests = signal<FieldFollowUpDto[]>([]);
  protected readonly commands = signal<FieldCommand[]>([]);
  protected readonly attachments = signal<FieldDraft['attachments']>([]);
  protected readonly selected = computed(() => {
    const selected = this.job()?.selectedVisit;
    return selected && (!this.visitId() || selected.id === this.visitId()) ? selected : null;
  });
  protected hasRequestedVisit(): boolean {
    return !this.visitId() || !!this.job()?.visits.some((visit) => visit.id === this.visitId());
  }
  protected readonly outcomes = computed(() =>
    this.selected()?.purposeCode === 'ASSESSMENT'
      ? ['NO_WORK_REQUIRED', 'WORK_RECOMMENDED', 'QUOTE_REQUIRED', 'UNABLE_TO_ASSESS']
      : ['RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE'],
  );
  protected readonly busy = signal(false);
  protected readonly loading = signal(false);
  protected readonly recording = signal(false);
  protected readonly preparing = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<
    | 'fieldContextUnavailable'
    | 'fieldStorageFailure'
    | 'fieldRequestFailed'
    | 'fieldAudioUnsupported'
    | 'fieldMediaRefused'
    | 'fieldMediaRetained'
    | 'jobUnavailable'
    | 'connectionError'
    | 'unexpectedError'
    | 'forbiddenDescription'
    | null
  >(null);
  protected readonly fetchedAt = signal<number | null>(null);
  protected readonly stale = signal(false);
  protected readonly media = signal<{ id: string; url: string; audio: boolean } | null>(null);
  protected readonly form = new FormGroup({
    note: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(2000)] }),
    outcome: new FormControl('', { nonNullable: true }),
    summary: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(2000)] }),
  });
  protected readonly follow = new FormGroup({
    start: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    end: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    reason: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(2000)],
    }),
  });
  private readonly replyControls = new Map<string, FormControl<string>>();
  private readonly destroy = inject(DestroyRef);
  private replyDraft: Record<string, string> = {};
  protected replyFor(id: string): FormControl<string> {
    let control = this.replyControls.get(id);
    if (!control) {
      control = new FormControl(this.replyDraft[id] ?? '', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(2000)],
      });
      this.replyControls.set(id, control);
      const changes = control.valueChanges.subscribe((value) => {
        this.replyDraft[id] = value;
        this.saved.set(false);
        void this.save().catch(() => this.error.set('fieldStorageFailure'));
      });
      this.destroy.onDestroy(() => changes.unsubscribe());
    }
    return control;
  }
  protected readonly canRecord =
    typeof MediaRecorder !== 'undefined' &&
    ['audio/mp4;codecs=mp4a.40.2', 'audio/mp4'].some((type) => MediaRecorder.isTypeSupported(type));
  protected readonly unresolved = computed(() =>
    this.commands().some((command) => command.state !== 'accepted' && !command.reviewed),
  );

  constructor() {
    effect(() => {
      this.jobId();
      this.visitId();
      untracked(() => void this.load());
    });
    let listedJob: string | null = null;
    effect(() => {
      this.store.revision();
      const jobId = this.jobId();
      const scope = this.session.context();
      untracked(() => {
        if (listedJob !== jobId) {
          listedJob = jobId;
          this.commands.set([]);
        }
        if (scope)
          void this.store
            .records(scope)
            .then((rows) => {
              if (jobId !== this.jobId()) return;
              this.commands.set(
                rows.filter(
                  (row): row is FieldCommand => row.kind === 'command' && row.jobId === jobId,
                ),
              );
            })
            .catch(() => this.error.set('fieldStorageFailure'));
        else {
          this.job.set(null);
          this.activity.set(null);
          this.commands.set([]);
          this.attachments.set([]);
          this.resetEditor();
          this.requests.set([]);
          this.stopRecording();
          this.clearUrls();
        }
      });
    });
    const subscription = this.form.valueChanges.subscribe(() => {
      if (!this.loadingDraft) {
        this.saved.set(false);
        void this.save().catch(() => this.error.set('fieldStorageFailure'));
      }
    });
    const followChanges = this.follow.valueChanges.subscribe(() => {
      if (!this.loadingDraft) {
        this.saved.set(false);
        void this.save().catch(() => this.error.set('fieldStorageFailure'));
      }
    });
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (this.recording() || this.preparing() || (!this.saved() && this.hasDraft())) {
        event.preventDefault();
      }
    };
    window.addEventListener('beforeunload', beforeUnload);
    const removeReload = this.updates.beforeReload(() => this.save());
    inject(DestroyRef).onDestroy(() => {
      ++this.generation;
      subscription.unsubscribe();
      followChanges.unsubscribe();
      removeReload();
      window.removeEventListener('beforeunload', beforeUnload);
      this.stopRecording();
      this.clearUrls();
    });
  }
  protected async load(): Promise<void> {
    if (!this.saved() && this.hasDraft()) {
      try {
        await this.save();
      } catch {
        this.error.set('fieldStorageFailure');
        return;
      }
    }
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set(null);
    this.job.set(null);
    this.activity.set(null);
    this.resetEditor();
    this.stopRecording();
    this.clearUrls();
    this.attachments.set([]);
    try {
      if (!this.session.context()) await this.session.verify();
      this.scope = this.session.context();
      const live = this.session.lease();
      const result = await this.data.job(this.jobId(), this.visitId());
      if (!live() || generation !== this.generation) return;
      this.job.set(result.data);
      this.stale.set(result.stale);
      this.fetchedAt.set(result.fetchedAt);
      const visit = result.data.selectedVisit;
      if (visit && this.scope) {
        const rows = await this.store.records(this.scope);
        if (!live() || generation !== this.generation) return;
        const draft = rows.find(
          (row): row is DraftRecord =>
            row.kind === 'draft' && row.jobId === this.jobId() && row.visitId === visit.id,
        );
        const value = draft?.data ?? blankDraft();
        this.loadingDraft = true;
        this.form.reset({ note: value.note, outcome: value.outcome, summary: value.summary });
        this.follow.reset(value.followup ?? { start: '', end: '', reason: '' });
        this.replyDraft = value.replies ?? {};
        for (const [id, control] of this.replyControls)
          control.reset(this.replyDraft[id] ?? '', { emitEvent: false });
        this.loadingDraft = false;
        this.attachments.set(value.attachments);
        this.saved.set(!!draft);
      }
      const activity = await this.data.activity(this.jobId());
      if (!live() || generation !== this.generation) return;
      this.activity.set(activity.data);
      if (!result.stale) await this.loadRequests();
    } catch (error) {
      if (generation === this.generation) {
        this.error.set(
          error instanceof HttpErrorResponse && error.status === 404
            ? 'jobUnavailable'
            : this.session.context()
              ? 'fieldRequestFailed'
              : 'fieldContextUnavailable',
        );
        if (!this.session.context()) {
          try {
            const job = await firstValueFrom(
              this.http.get<FieldJobDetails>(`/jobs/${encodeURIComponent(this.jobId())}`, {
                context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
              }),
            );
            if (generation === this.generation && this.auth.authenticated()) this.job.set(job);
          } catch (error) {
            this.job.set(null);
            this.error.set(
              error instanceof HttpErrorResponse
                ? error.status === 404
                  ? 'jobUnavailable'
                  : error.status === 403
                    ? 'forbiddenDescription'
                    : error.status === 0 || error.status >= 500
                      ? 'connectionError'
                      : 'unexpectedError'
                : 'unexpectedError',
            );
          }
        }
      }
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
  private resetEditor(): void {
    this.loadingDraft = true;
    this.form.reset();
    this.follow.reset();
    this.replyDraft = {};
    for (const control of this.replyControls.values()) control.reset('', { emitEvent: false });
    this.attachments.set([]);
    this.saved.set(false);
    this.loadingDraft = false;
  }
  private hasDraft(): boolean {
    const value = this.form.getRawValue();
    return !!(
      value.note ||
      value.outcome ||
      value.summary ||
      this.attachments().length ||
      Object.values(this.follow.getRawValue()).some(Boolean) ||
      Object.values(this.replyDraft).some(Boolean)
    );
  }
  private draft(): FieldDraft {
    return {
      ...this.form.getRawValue(),
      attachments: this.attachments(),
      followup: this.follow.getRawValue(),
      replies: { ...this.replyDraft },
    };
  }
  protected async save(): Promise<void> {
    const scope = this.session.context();
    const visit = this.selected();
    if (this.recording() || this.preparing()) throw new Error('MEDIA_PREPARATION_IN_PROGRESS');
    if (!this.hasDraft()) {
      if (scope && visit)
        await this.store.write(
          scope,
          [],
          [this.store.key(scope, `draft/${this.jobId()}/${visit.id}`)],
        );
      return;
    }
    if (!scope || !visit) {
      this.saved.set(false);
      throw new Error('FIELD_DRAFT_UNSAVED');
    }
    const generation = this.generation;
    const draft = this.draft();
    try {
      await this.store.saveDraft(scope, this.jobId(), visit.id, draft);
      if (
        generation === this.generation &&
        draft.note === this.form.controls.note.value &&
        draft.summary === this.form.controls.summary.value &&
        draft.outcome === this.form.controls.outcome.value &&
        draft.attachments === this.attachments() &&
        JSON.stringify(draft.replies) === JSON.stringify(this.replyDraft) &&
        JSON.stringify(draft.followup) === JSON.stringify(this.follow.getRawValue())
      )
        this.saved.set(true);
    } catch (error) {
      this.saved.set(false);
      this.error.set('fieldStorageFailure');
      throw error;
    }
  }
  protected async discardDraft(): Promise<void> {
    if (!window.confirm(this.i18n.text('fieldDiscardConfirm'))) return;
    const scope = this.session.context();
    const visit = this.selected();
    if (!scope || !visit) return;
    try {
      await this.store.write(
        scope,
        [],
        [this.store.key(scope, `draft/${this.jobId()}/${visit.id}`)],
      );
      this.loadingDraft = true;
      this.form.reset();
      this.follow.reset();
      this.replyDraft = {};
      for (const control of this.replyControls.values()) control.reset('', { emitEvent: false });
      this.loadingDraft = false;
      this.attachments.set([]);
      this.clearUrls();
      this.saved.set(false);
    } catch {
      this.error.set('fieldStorageFailure');
    }
  }
  protected async submit(
    operation: 'note' | 'completion' | 'status',
    status?: string,
  ): Promise<void> {
    const visit = this.selected();
    if (!visit || this.busy() || this.recording() || this.preparing() || this.form.invalid) return;
    if (
      operation === 'completion' &&
      (!this.outcomes().includes(this.form.controls.outcome.value) ||
        !this.form.controls.summary.value.trim())
    ) {
      this.error.set('fieldRequestFailed');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const payload: FieldCommand['payload'] =
        operation === 'status'
          ? { status: status ?? '', expectedVersion: visit.version }
          : operation === 'completion'
            ? {
                outcomeCode: this.form.controls.outcome.value,
                outcomeSummary: this.form.controls.summary.value,
                expectedVersion: visit.version,
              }
            : {};
      await this.queue.submit(
        this.jobId(),
        visit.id,
        operation,
        payload,
        operation === 'status' ? undefined : this.draft(),
      );
      if (operation !== 'status') {
        this.loadingDraft = true;
        this.form.reset();
        this.loadingDraft = false;
        this.attachments.set([]);
        this.saved.set(false);
        this.clearUrls();
      }
    } catch {
      this.error.set('fieldStorageFailure');
    } finally {
      this.busy.set(false);
    }
  }
  protected async choose(event: Event, kind: 'photo' | 'audio'): Promise<void> {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    const live = this.session.lease();
    this.preparing.set(true);
    try {
      for (const file of Array.from(target.files ?? [])) {
        if (
          kind === 'audio'
            ? !['audio/mp4', 'audio/x-m4a'].includes(file.type) || file.size > 10 * 1024 * 1024
            : !['image/jpeg', 'image/png', 'image/webp'].includes(file.type) ||
              file.size > 15 * 1024 * 1024
        ) {
          this.error.set('fieldMediaRetained');
        }
        let blob: Blob = file;
        try {
          if (kind === 'audio') blob = await mp4AudioBlob(file);
        } catch {
          this.error.set('fieldMediaRetained');
        }
        if (!live()) return;
        this.attachments.update((rows) => [
          ...rows,
          {
            id: crypto.randomUUID(),
            kind,
            blob,
            phase: 'DURING_WORK',
            capturedAt: new Date().toISOString(),
          },
        ]);
        this.saved.set(false);
      }
      target.value = '';
      this.preparing.set(false);
      try {
        await this.save();
      } catch {
        /* The editor and bytes remain available for retry. */
      }
    } finally {
      this.preparing.set(false);
    }
  }
  protected preview(id: string, blob: Blob): string {
    let url = this.urls.get(id);
    if (!url) {
      url = URL.createObjectURL(blob);
      this.urls.set(id, url);
    }
    return url;
  }
  protected removeAttachment(id: string): void {
    this.attachments.update((rows) => rows.filter((row) => row.id !== id));
    this.saved.set(false);
    void this.save().catch(() => this.error.set('fieldStorageFailure'));
  }
  protected phase(id: string, event: Event): void {
    if (!(event.target instanceof HTMLSelectElement)) return;
    const phase = event.target.value;
    this.attachments.update((rows) => rows.map((row) => (row.id === id ? { ...row, phase } : row)));
    this.saved.set(false);
    void this.save().catch(() => this.error.set('fieldStorageFailure'));
  }
  protected async record(): Promise<void> {
    if (!this.canRecord || this.recording()) return;
    const live = this.session.lease();
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (!live()) {
        this.stopRecording();
        return;
      }
      const mimeType = ['audio/mp4;codecs=mp4a.40.2', 'audio/mp4'].find((type) =>
        MediaRecorder.isTypeSupported(type),
      );
      if (!mimeType) throw new Error('UNSUPPORTED_CODEC');
      const recorder = new MediaRecorder(this.stream, { mimeType });
      this.recorder = recorder;
      const chunks: Blob[] = [];
      recorder.ondataavailable = (event) => {
        if (event.data.size) chunks.push(event.data);
      };
      recorder.onerror = () => {
        this.error.set('fieldAudioUnsupported');
        this.stopRecording();
      };
      recorder.onstop = async () => {
        this.recording.set(false);
        this.stream?.getTracks().forEach((track) => track.stop());
        this.stream = null;
        if (this.recordTimer) clearTimeout(this.recordTimer);
        if (!live()) return;
        let blob: Blob = new Blob(chunks, { type: recorder.mimeType });
        try {
          blob = await mp4AudioBlob(blob);
        } catch {
          this.error.set('fieldMediaRetained');
        }
        if (!live()) return;
        if (!blob.size) {
          this.error.set('fieldMediaRefused');
          return;
        }
        if (!blob.type.startsWith('audio/mp4') || blob.size > 10 * 1024 * 1024)
          this.error.set('fieldMediaRetained');
        this.attachments.update((rows) => [
          ...rows,
          {
            id: crypto.randomUUID(),
            kind: 'audio',
            blob,
            phase: 'DURING_WORK',
            capturedAt: new Date().toISOString(),
          },
        ]);
        this.saved.set(false);
        void this.save().catch(() => this.error.set('fieldStorageFailure'));
      };
      recorder.start(1000);
      this.recording.set(true);
      this.saved.set(false);
      this.recordTimer = setTimeout(() => this.stopRecording(), 299000);
    } catch {
      this.error.set('fieldAudioUnsupported');
      this.stopRecording();
    }
  }
  protected stopRecording(): void {
    if (this.recorder && this.recorder.state !== 'inactive') this.recorder.stop();
    this.recorder = null;
    this.stream?.getTracks().forEach((track) => track.stop());
    this.recording.set(false);
    if (this.recordTimer) clearTimeout(this.recordTimer);
  }
  protected async review(command: FieldCommand): Promise<void> {
    const scope = this.session.context();
    if (!scope || this.busy() || command.jobId !== this.jobId()) return;
    this.busy.set(true);
    try {
      const current = await this.data.job(this.jobId(), this.visitId());
      if (current.stale) throw new Error('ONLINE_REVIEW_REQUIRED');
      const rows = (await this.store.records(scope)).filter(
        (row): row is FieldCommand => row.kind === 'command',
      );
      const blocked = new Set([command.operationId]);
      let changed = true;
      while (changed) {
        changed = false;
        for (const row of rows)
          if (!blocked.has(row.operationId) && row.dependencies.some((id) => blocked.has(id))) {
            blocked.add(row.operationId);
            changed = true;
          }
      }
      await this.store.write(
        scope,
        rows
          .filter((row) => blocked.has(row.operationId) && row.state !== 'accepted')
          .map((row) => ({
            ...row,
            state: 'attention',
            reviewed: true,
            error: row.operationId === command.operationId ? row.error : 'DEPENDENCY_REFUSED',
          })),
      );
      this.job.set(current.data);
      this.stale.set(false);
      this.fetchedAt.set(current.fetchedAt);
    } catch {
      this.error.set('fieldRequestFailed');
    } finally {
      this.busy.set(false);
    }
  }
  protected async openMedia(id: string, audio: boolean): Promise<void> {
    const scope = this.session.context();
    if (!scope) return;
    try {
      const blob = await this.data.content(
        scope,
        this.jobId(),
        audio ? 'audio-notes' : 'photos',
        id,
      );
      this.media.set({ id, audio, url: this.preview(id, blob) });
    } catch {
      this.error.set('fieldRequestFailed');
    }
  }
  private async loadRequests(): Promise<void> {
    const scope = this.session.context();
    if (!scope || !this.auth.hasPermission('visits.request_follow_up')) return;
    const live = this.session.lease();
    const requests = await firstValueFrom(
      this.http.get<FieldFollowUpDto[]>('/jobs/visit-requests', this.session.options(scope)),
    );
    if (live()) this.requests.set(requests.filter((row) => row.jobId === this.jobId()));
  }
  protected async requestFollowUp(): Promise<void> {
    const scope = this.session.context();
    const visit = this.selected();
    if (!scope || !visit || this.follow.invalid || this.busy()) return;
    this.busy.set(true);
    try {
      const form = this.follow.getRawValue();
      await firstValueFrom(
        this.http.post(
          `/jobs/${this.jobId()}/visit-requests`,
          {
            sourceVisitId: visit.id,
            proposedStart: new Date(form.start).toISOString(),
            proposedEnd: new Date(form.end).toISOString(),
            reason: form.reason,
            sameTechnicianPreferred: true,
          },
          this.session.options(scope),
        ),
      );
      this.follow.reset();
      await this.save();
      await this.loadRequests();
    } catch {
      this.error.set('fieldRequestFailed');
    } finally {
      this.busy.set(false);
    }
  }
  protected async sendReply(request: FieldFollowUpDto): Promise<void> {
    const scope = this.session.context();
    const reply = this.replyFor(request.id);
    if (!scope || reply.invalid || this.busy()) return;
    this.busy.set(true);
    try {
      await firstValueFrom(
        this.http.post(
          `/jobs/${this.jobId()}/visit-requests/${request.id}/reply`,
          {
            body: reply.value,
            expectedStatus: request.status,
            expectedVersion: request.version,
          },
          this.session.options(scope),
        ),
      );
      reply.reset('', { emitEvent: false });
      this.replyDraft[request.id] = '';
      await this.save();
      await this.loadRequests();
    } catch {
      this.error.set('fieldRequestFailed');
    } finally {
      this.busy.set(false);
    }
  }
  private clearUrls(): void {
    for (const url of this.urls.values()) URL.revokeObjectURL(url);
    this.urls.clear();
    this.media.set(null);
  }
}
