import { DestroyRef, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';
import type { FieldContextDto } from '@servora/shared-types';
import { FieldSession, fieldPartition } from './field-session';
import {
  FieldStore,
  type FieldCommand,
  type CommandKind,
  type FieldDraft,
  type DraftRecord,
} from './field-store';

export function retryDelay(attempts: number, retryAfter: string | null, now = Date.now()): number {
  const seconds = retryAfter === null ? NaN : Number(retryAfter);
  const requested = Number.isFinite(seconds)
    ? seconds * 1000
    : retryAfter
      ? Date.parse(retryAfter) - now
      : 0;
  return Math.max(
    1000,
    Math.min(60000, 1000 * 2 ** Math.min(attempts, 6)),
    Number.isFinite(requested) ? requested : 0,
  );
}
export function dependenciesAccepted(
  command: FieldCommand,
  commands: readonly FieldCommand[],
): boolean {
  return command.dependencies.every((id) =>
    commands.some((row) => row.operationId === id && row.state === 'accepted'),
  );
}
export function commandDependencies(
  commands: readonly FieldCommand[],
  jobId: string,
  visitId: string,
  operation: CommandKind,
): string[] {
  if (operation === 'ad-hoc') return [];
  return commands
    .filter(
      (row) =>
        row.jobId === jobId && row.visitId === visitId && row.state !== 'accepted' && !row.reviewed,
    )
    .map((row) => row.operationId);
}
@Injectable({ providedIn: 'root' })
export class FieldQueue {
  private readonly session = inject(FieldSession);
  private readonly store = inject(FieldStore);
  private readonly http = inject(HttpClient);
  readonly busy = signal(false);
  private rerun = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  constructor() {
    const resume = () => {
      if (document.visibilityState !== 'hidden') void this.replay();
    };
    window.addEventListener('online', resume);
    document.addEventListener('visibilitychange', resume);
    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('online', resume);
      document.removeEventListener('visibilitychange', resume);
      if (this.timer) clearTimeout(this.timer);
    });
    effect(() => {
      if (this.session.context()) untracked(() => void this.replay());
    });
  }
  async submit(
    jobId: string,
    visitId: string,
    operation: CommandKind,
    payload: FieldCommand['payload'],
    draft?: FieldDraft,
  ): Promise<void> {
    const scope = this.session.context();
    if (!scope) throw new Error('FIELD_CONTEXT_UNAVAILABLE');
    const existing = (await this.store.records(scope)).filter(
      (row): row is FieldCommand => row.kind === 'command',
    );
    const dependencies = commandDependencies(existing, jobId, visitId, operation);
    const commands: FieldCommand[] = [];
    const make = (kind: CommandKind, body: FieldCommand['payload'], blob?: Blob) => {
      const operationId = crypto.randomUUID();
      const command: FieldCommand = {
        key: this.store.key(scope, `command/${operationId}`),
        partition: fieldPartition(scope),
        kind: 'command',
        jobId,
        visitId,
        savedAt: Date.now(),
        operation: kind,
        operationId,
        payload: {
          ...body,
          clientOperationId: operationId,
          capturedAt: body['capturedAt'] ?? new Date().toISOString(),
        },
        dependencies: [...dependencies],
        state: 'pending',
        attempts: 0,
        retryAt: 0,
        ambiguous: false,
        error: null,
        ...(blob ? { blob } : {}),
      };
      commands.push(command);
      dependencies.push(operationId);
    };
    for (const attachment of draft?.attachments ?? [])
      make(
        attachment.kind,
        { visitId, phase: attachment.phase, capturedAt: attachment.capturedAt },
        attachment.blob,
      );
    if (draft?.note.trim()) make('note', { body: draft.note });
    if (operation !== 'note' || !draft) make(operation, payload);
    if (!commands.length) return;
    // Online-only follow-up editors remain editable when the field update is submitted.
    const retained: DraftRecord[] =
      draft &&
      (Object.values(draft.followup ?? {}).some(Boolean) ||
        Object.values(draft.replies ?? {}).some(Boolean))
        ? [
            {
              kind: 'draft',
              key: this.store.key(scope, `draft/${jobId}/${visitId}`),
              partition: fieldPartition(scope),
              jobId,
              visitId,
              savedAt: Date.now(),
              data: {
                note: '',
                outcome: '',
                summary: '',
                attachments: [],
                followup: draft.followup,
                replies: draft.replies,
              },
            },
          ]
        : [];
    // Submitted payloads and their blobs replace the editable draft atomically.
    await this.store.write(
      scope,
      [...commands, ...retained],
      draft && !retained.length
        ? [this.store.key(scope, `draft/${jobId}/${visitId}`)]
        : operation === 'ad-hoc'
          ? [this.store.key(scope, 'report-draft/current')]
          : [],
    );
    void this.replay();
  }
  async replay(): Promise<void> {
    if (this.busy()) {
      this.rerun = true;
      return;
    }
    if (!this.session.context() || !navigator.onLine || !navigator.locks) return;
    this.busy.set(true);
    try {
      await navigator.locks.request('servora.field.replay', async () => {
        const scope = await this.session.verify();
        const live = this.session.lease();
        const rows = (await this.store.records(scope))
          .filter((row): row is FieldCommand => row.kind === 'command')
          .sort((a, b) => a.savedAt - b.savedAt);
        // A previous tab may have closed after sending, before recording the result.
        for (const row of rows)
          if (row.state === 'uploading') {
            row.state = 'pending';
            row.ambiguous = true;
          }
        for (const row of rows) {
          if (!live()) return;
          if (
            row.state !== 'pending' ||
            row.retryAt > Date.now() ||
            !dependenciesAccepted(row, rows)
          )
            continue;
          row.state = 'uploading';
          row.attempts++;
          await this.store.write(scope, [row]);
          try {
            await this.send(scope, row);
            if (!live()) return;
            // Current Job details cannot prove the version produced by a response-lost status command.
            row.state =
              row.ambiguous && ['status', 'completion'].includes(row.operation)
                ? 'attention'
                : 'accepted';
            row.error = row.state === 'attention' ? 'STATUS_RESULT_REVIEW' : null;
            delete row.blob;
          } catch (error) {
            if (!live()) return;
            const status = error instanceof HttpErrorResponse ? error.status : 0;
            row.ambiguous = row.ambiguous || status === 0 || status >= 500;
            const scopeRefused =
              error instanceof HttpErrorResponse &&
              typeof error.error?.code === 'string' &&
              error.error.code.startsWith('FIELD_');
            if ([401, 403, 404].includes(status) || scopeRefused) {
              await this.store.invalidateJob(scope, row.jobId);
              for (const affected of rows)
                if (affected.jobId === row.jobId && affected.state !== 'accepted') {
                  affected.state = 'attention';
                  affected.error = 'ACCESS_REFUSED';
                }
              row.state = 'attention';
              row.error = 'ACCESS_REFUSED';
              await this.store.write(scope, [row]);
              if (status !== 404 || scopeRefused) this.session.invalidate();
              continue;
            }
            const retryable = status === 0 || status >= 500 || status === 429;
            row.retryable = retryable;
            row.state = retryable && row.attempts < 5 ? 'pending' : 'attention';
            row.error =
              error instanceof HttpErrorResponse && typeof error.error?.code === 'string'
                ? error.error.code
                : retryable
                  ? 'CONNECTION_RETRY'
                  : 'REQUEST_REFUSED';
            row.retryAt =
              Date.now() +
              retryDelay(
                row.attempts,
                error instanceof HttpErrorResponse ? error.headers.get('Retry-After') : null,
              );
          }
          if (live()) await this.store.write(scope, [row]);
        }
        if (live()) {
          const due = rows.filter(
            (row) => row.state === 'pending' && dependenciesAccepted(row, rows),
          );
          if (this.timer) {
            clearTimeout(this.timer);
            this.timer = null;
          }
          if (due.length)
            this.timer = setTimeout(
              () => {
                this.timer = null;
                void this.replay();
              },
              Math.max(1000, Math.min(...due.map((row) => row.retryAt)) - Date.now()),
            );
        }
      });
    } catch {
      // Verification or storage failed; retained commands remain locked/pending and the editor shows recovery.
      this.store.revision.update((value) => value + 1);
    } finally {
      this.busy.set(false);
      if (this.rerun) {
        this.rerun = false;
        queueMicrotask(() => void this.replay());
      }
    }
  }
  async retry(command: FieldCommand): Promise<void> {
    if (!command.retryable && command.error !== 'CONNECTION_RETRY') return;
    const scope = this.session.context();
    if (!scope) return;
    await this.store.write(scope, [
      { ...command, state: 'pending', attempts: 0, retryAt: Math.max(Date.now(), command.retryAt) },
    ]);
    await this.replay();
  }
  private send(scope: FieldContextDto, command: FieldCommand): Promise<unknown> {
    const base = `/jobs/${encodeURIComponent(command.jobId)}`;
    const visit = `${base}/visits/${encodeURIComponent(command.visitId)}`;
    const options = this.session.options(scope);
    let body: FieldCommand['payload'] | FormData = command.payload;
    let path =
      command.operation === 'ad-hoc'
        ? '/jobs/ad-hoc-work-reports'
        : command.operation === 'note'
          ? `${visit}/notes`
          : command.operation === 'status'
            ? `${visit}/status`
            : command.operation === 'completion'
              ? `${visit}/completion`
              : `${base}/${command.operation === 'photo' ? 'photos' : 'audio-notes'}`;
    if (command.operation === 'photo' || command.operation === 'audio') {
      if (!command.blob) throw new Error('MISSING_PENDING_BYTES');
      const form = new FormData();
      for (const [key, value] of Object.entries(command.payload))
        if (value !== null) form.set(key, String(value));
      const extensions: Record<string, string> = {
        'image/jpeg': 'jpg',
        'image/png': 'png',
        'image/webp': 'webp',
        'audio/mp4': 'm4a',
        'audio/webm': 'webm',
      };
      const filename =
        command.blob instanceof File
          ? command.blob.name
          : `evidence.${extensions[command.blob.type] ?? 'bin'}`;
      form.set('file', command.blob, filename);
      body = form;
    }
    path = String(path);
    return firstValueFrom(
      (command.operation === 'status'
        ? this.http.patch(path, body, options)
        : this.http.post(path, body, options)
      ).pipe(timeout(60000)),
    );
  }
}
