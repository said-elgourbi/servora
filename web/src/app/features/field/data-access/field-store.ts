import { Injectable, effect, inject, signal } from '@angular/core';
import type {
  FieldContextDto,
  FieldJobDetails,
  FieldActivityDto,
  TechnicianHomeResponse,
} from '@servora/shared-types';
import { FieldSession, fieldPartition } from './field-session';

export type CommandKind = 'status' | 'completion' | 'note' | 'photo' | 'audio' | 'ad-hoc';
export type CommandState = 'pending' | 'uploading' | 'accepted' | 'attention';
export interface FieldDraft {
  note: string;
  outcome: string;
  summary: string;
  followup?: { start: string; end: string; reason: string };
  replies?: Record<string, string>;
  attachments: {
    id: string;
    kind: 'photo' | 'audio';
    blob: Blob;
    phase: string;
    capturedAt: string;
  }[];
}
interface StoredBase {
  key: string;
  partition: string;
  jobId: string;
  savedAt: number;
}
export interface Snapshot extends StoredBase {
  kind: 'snapshot';
  resource: string;
  data: FieldJobDetails | FieldActivityDto | TechnicianHomeResponse;
}
export interface DraftRecord extends StoredBase {
  kind: 'draft';
  visitId: string;
  data: FieldDraft;
}
export interface FieldCommand extends StoredBase {
  kind: 'command';
  operation: CommandKind;
  operationId: string;
  visitId: string;
  payload: Readonly<Record<string, string | number | boolean | null>>;
  blob?: Blob;
  dependencies: string[];
  state: CommandState;
  attempts: number;
  retryAt: number;
  ambiguous: boolean;
  error: string | null;
  reviewed?: boolean;
  retryable?: boolean;
}
export interface ReportDraftRecord extends StoredBase {
  kind: 'report-draft';
  data: Record<string, string>;
}
export type FieldRecord = Snapshot | DraftRecord | ReportDraftRecord | FieldCommand;
export const FIELD_DATABASE = 'servora-field-v1';
const week = 7 * 24 * 60 * 60 * 1000;

export function retainedSnapshots(records: readonly FieldRecord[], now: number): Set<string> {
  const pinned = new Set(
    records
      .filter((row) => row.kind === 'draft' || (row.kind === 'command' && row.state !== 'accepted'))
      .map((row) => row.jobId),
  );
  const fetched = new Map<string, number>();
  for (const row of records)
    if (row.kind === 'snapshot' && row.jobId && !pinned.has(row.jobId) && now - row.savedAt <= week)
      fetched.set(row.jobId, Math.max(row.savedAt, fetched.get(row.jobId) ?? 0));
  for (const row of records)
    if (row.kind === 'snapshot' && row.resource.startsWith('home/') && now - row.savedAt <= week) {
      const home = row.data as TechnicianHomeResponse;
      for (const visit of [
        ...home.visits,
        ...home.upcoming,
        ...(home.nextVisit ? [home.nextVisit] : []),
      ])
        if (!pinned.has(visit.jobId))
          fetched.set(visit.jobId, Math.max(row.savedAt, fetched.get(visit.jobId) ?? 0));
    }
  return new Set([
    ...pinned,
    ...[...fetched]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 50)
      .map(([id]) => id),
  ]);
}
@Injectable({ providedIn: 'root' })
export class FieldStore {
  private readonly session = inject(FieldSession);
  private opening: Promise<IDBDatabase> | null = null;
  private readonly transactions = new Set<IDBTransaction>();
  readonly revision = signal(0);
  readonly failure = signal(false);
  readonly persistent = signal(false);
  constructor() {
    let previous: string | null = null;
    effect(() => {
      const context = this.session.context();
      const current = context ? fieldPartition(context) : null;
      if (previous !== current) {
        for (const transaction of this.transactions) transaction.abort();
        if (previous) void this.clearSnapshots(previous).catch(() => this.failure.set(true));
        previous = current;
      }
    });
  }
  private open(): Promise<IDBDatabase> {
    if (this.opening) return this.opening;
    this.opening = new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(FIELD_DATABASE, 1);
      let refused = false;
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains('records'))
          db.createObjectStore('records', { keyPath: 'key' });
      };
      request.onblocked = () => {
        refused = true;
        reject(new Error('FIELD_STORAGE_BLOCKED'));
      };
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result;
        if (refused) {
          db.close();
          return;
        }
        db.onversionchange = () => {
          db.close();
          this.opening = null;
          this.failure.set(true);
        };
        resolve(db);
      };
    }).catch((error: unknown) => {
      this.opening = null;
      this.failure.set(true);
      throw error;
    });
    return this.opening;
  }
  async records(scope: FieldContextDto): Promise<FieldRecord[]> {
    const live = this.session.lease();
    const db = await this.open();
    const current = this.session.context();
    if (!live() || !current || fieldPartition(current) !== fieldPartition(scope))
      throw new Error('SESSION_CHANGED');
    return new Promise((resolve, reject) => {
      const tx = db.transaction('records', 'readonly');
      const request = tx.objectStore('records').getAll();
      tx.oncomplete = () =>
        live()
          ? resolve(
              (request.result as FieldRecord[]).filter(
                (row) => row.partition === fieldPartition(scope),
              ),
            )
          : reject(new Error('SESSION_CHANGED'));
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error ?? new Error('SESSION_CHANGED'));
    });
  }
  async write(
    scope: FieldContextDto,
    rows: readonly FieldRecord[],
    remove: readonly string[] = [],
  ): Promise<void> {
    const live = this.session.lease();
    const db = await this.open();
    if (
      !live() ||
      this.session.context() === null ||
      fieldPartition(this.session.context()!) !== fieldPartition(scope)
    )
      throw new Error('SESSION_CHANGED');
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('records', 'readwrite');
      this.transactions.add(tx);
      const store = tx.objectStore('records');
      for (const key of remove) store.delete(key);
      for (const row of rows) {
        if (row.partition !== fieldPartition(scope)) {
          tx.abort();
          reject(new Error('FIELD_SCOPE_CHANGED'));
          return;
        }
        store.put(row);
      }
      tx.oncomplete = () => {
        this.transactions.delete(tx);
        if (live()) resolve();
        else reject(new Error('SESSION_CHANGED'));
      };
      tx.onerror = () => {
        this.transactions.delete(tx);
        this.failure.set(true);
        reject(tx.error);
      };
      tx.onabort = () => {
        this.transactions.delete(tx);
        reject(tx.error ?? new Error('SESSION_CHANGED'));
      };
    });
    this.failure.set(false);
    this.revision.update((value) => value + 1);
  }
  key(scope: FieldContextDto, resource: string): string {
    return `${fieldPartition(scope)}/${resource}`;
  }
  async saveDraft(
    scope: FieldContextDto,
    jobId: string,
    visitId: string,
    data: FieldDraft,
  ): Promise<void> {
    await this.write(scope, [
      {
        key: this.key(scope, `draft/${jobId}/${visitId}`),
        partition: fieldPartition(scope),
        jobId,
        visitId,
        savedAt: Date.now(),
        kind: 'draft',
        data,
      },
    ]);
    try {
      if (navigator.storage?.persist)
        void navigator.storage.persist().then(
          (granted) => this.persistent.set(granted),
          () => this.persistent.set(false),
        );
    } catch {
      this.persistent.set(false);
    }
  }
  async snapshot(
    scope: FieldContextDto,
    jobId: string,
    resource: string,
    data: Snapshot['data'],
  ): Promise<void> {
    if (resource.startsWith('home/'))
      data = { ...(data as TechnicianHomeResponse), viewer: { displayName: null } };
    const row: Snapshot = {
      key: this.key(scope, `snapshot/${resource}`),
      partition: fieldPartition(scope),
      jobId,
      resource,
      savedAt: Date.now(),
      kind: 'snapshot',
      data,
    };
    await this.write(scope, [row]);
    const records = await this.records(scope);
    const keep = retainedSnapshots(records, Date.now());
    const remove = records.filter(
      (item): item is Snapshot =>
        item.kind === 'snapshot' &&
        ((item.jobId && !keep.has(item.jobId)) ||
          (!item.jobId && Date.now() - item.savedAt > week)),
    );
    const homes: Snapshot[] = records
      .filter(
        (item): item is Snapshot => item.kind === 'snapshot' && item.resource.startsWith('home/'),
      )
      .map((item) => {
        const home = item.data as TechnicianHomeResponse;
        return {
          ...item,
          data: {
            ...home,
            viewer: { displayName: null },
            visits: home.visits.filter((visit) => keep.has(visit.jobId)),
            upcoming: home.upcoming.filter((visit) => keep.has(visit.jobId)),
            nextVisit: home.nextVisit && keep.has(home.nextVisit.jobId) ? home.nextVisit : null,
            attention: {
              ...home.attention,
              items: home.attention.items.filter((entry) => keep.has(entry.jobId)),
            },
          },
        };
      });
    await this.write(
      scope,
      homes.filter((item) => !remove.some((deleted) => deleted.key === item.key)),
      remove.map((item) => item.key),
    );
  }
  async invalidateJob(scope: FieldContextDto, jobId: string): Promise<void> {
    const rows = await this.records(scope);
    await this.write(
      scope,
      rows
        .filter(
          (row): row is FieldCommand =>
            row.kind === 'command' && row.jobId === jobId && row.state !== 'accepted',
        )
        .map((row) => ({ ...row, state: 'attention', error: 'ACCESS_REFUSED' })),
      rows
        .filter(
          (row) =>
            row.kind === 'snapshot' && (row.jobId === jobId || row.resource.startsWith('home/')),
        )
        .map((row) => row.key),
    );
  }
  private async clearSnapshots(partition: string): Promise<void> {
    const db = await this.open();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('records', 'readwrite');
      const store = tx.objectStore('records');
      const request = store.getAll();
      request.onsuccess = () => {
        const rows = (request.result as FieldRecord[]).filter((row) => row.partition === partition);
        const receipts = new Set(
          rows.flatMap((row) =>
            row.kind === 'command' && row.state !== 'accepted' ? row.dependencies : [],
          ),
        );
        for (const row of rows) {
          if (row.kind === 'snapshot') store.delete(row.key);
          if (row.kind === 'command' && row.state === 'accepted') {
            if (receipts.has(row.operationId)) {
              const receipt = { ...row, payload: {} };
              delete receipt.blob;
              store.put(receipt);
            } else store.delete(row.key);
          }
        }
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }
}
