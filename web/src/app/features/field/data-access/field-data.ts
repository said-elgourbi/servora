import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout, TimeoutError } from 'rxjs';
import type {
  FieldJobDetails,
  FieldActivityDto,
  TechnicianHomeResponse,
  FieldContextDto,
} from '@servora/shared-types';
import { FieldSession } from './field-session';
import { FieldStore, type Snapshot } from './field-store';

export function offlineFallback(error: unknown): boolean {
  return (
    error instanceof TimeoutError ||
    (error instanceof HttpErrorResponse && (error.status === 0 || error.status >= 500))
  );
}
export interface FieldRead<T> {
  data: T;
  fetchedAt: number;
  stale: boolean;
}
@Injectable({ providedIn: 'root' })
export class FieldData {
  private readonly http = inject(HttpClient);
  private readonly session = inject(FieldSession);
  private readonly store = inject(FieldStore);
  async read<T extends Snapshot['data']>(
    jobId: string,
    resource: string,
    path: string,
  ): Promise<FieldRead<T>> {
    const scope = this.session.context() ?? (await this.session.verify());
    const live = this.session.lease();
    try {
      const data = await firstValueFrom(
        this.http.get<T>(path, this.session.options(scope)).pipe(timeout(20000)),
      );
      if (!live()) throw new Error('SESSION_CHANGED');
      // A failed cache write does not turn a successful authoritative read into a failed request.
      await this.store
        .snapshot(scope, jobId, resource, data)
        .catch(() => this.store.failure.set(true));
      if (!live()) throw new Error('SESSION_CHANGED');
      return { data, fetchedAt: Date.now(), stale: false };
    } catch (error) {
      if (!live()) throw error;
      if (offlineFallback(error)) {
        const rows = await this.store.records(scope);
        const snapshots = rows.filter((row): row is Snapshot => row.kind === 'snapshot');
        const snapshot = snapshots.find((row) => row.resource === resource);
        if (
          snapshot &&
          (Date.now() - snapshot.savedAt <= 7 * 86400000 ||
            rows.some(
              (row) =>
                row.jobId === jobId &&
                (row.kind === 'draft' || (row.kind === 'command' && row.state !== 'accepted')),
            ))
        )
          return { data: snapshot.data as T, fetchedAt: snapshot.savedAt, stale: true };
      } else if (
        error instanceof HttpErrorResponse &&
        [401, 403, 404, 409].includes(error.status)
      ) {
        await this.store.invalidateJob(scope, jobId).catch(() => this.store.failure.set(true));
        if ([401, 403, 409].includes(error.status)) this.session.invalidate();
      }
      throw error;
    }
  }
  job(id: string, visitId: string | null): Promise<FieldRead<FieldJobDetails>> {
    return this.read(
      id,
      `job/${id}/${visitId ?? 'selected'}/v1`,
      `/jobs/${encodeURIComponent(id)}${visitId ? `?visitId=${encodeURIComponent(visitId)}` : ''}`,
    );
  }
  activity(id: string): Promise<FieldRead<FieldActivityDto>> {
    return this.read(id, `activity/${id}/v1`, `/jobs/${encodeURIComponent(id)}/activity`);
  }
  home(timeZone: string): Promise<FieldRead<TechnicianHomeResponse>> {
    return this.read(
      '',
      `home/${timeZone}/v1`,
      `/home/technician?timeZone=${encodeURIComponent(timeZone)}`,
    );
  }
  async content(
    scope: FieldContextDto,
    jobId: string,
    kind: 'photos' | 'audio-notes',
    id: string,
  ): Promise<Blob> {
    const live = this.session.lease();
    const blob = await firstValueFrom(
      this.http.get(
        `/jobs/${encodeURIComponent(jobId)}/${kind}/${encodeURIComponent(id)}/content`,
        { ...this.session.options(scope), responseType: 'blob' },
      ),
    );
    if (!live()) throw new Error('SESSION_CHANGED');
    return blob;
  }
}
