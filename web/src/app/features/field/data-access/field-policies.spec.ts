import { HttpErrorResponse } from '@angular/common/http';
import { offlineFallback } from './field-data';
import { commandDependencies, dependenciesAccepted, retryDelay } from './field-queue';
import {
  retainedSnapshots,
  type FieldCommand,
  type Snapshot,
  type FieldRecord,
} from './field-store';
const now = Date.now();
const command = (
  id: string,
  dependencies: string[] = [],
  state: FieldCommand['state'] = 'pending',
): FieldCommand => ({
  key: id,
  kind: 'command',
  operation: 'note',
  operationId: id,
  jobId: 'pinned',
  visitId: 'visit',
  partition: 'scope',
  savedAt: now,
  payload: { body: 'work' },
  dependencies,
  state,
  attempts: 0,
  retryAt: 0,
  ambiguous: false,
  error: null,
});
const snapshot = (jobId: string, savedAt: number): Snapshot => ({
  key: jobId,
  kind: 'snapshot',
  partition: 'scope',
  jobId,
  savedAt,
  resource: `job/${jobId}`,
  data: {} as Snapshot['data'],
});
it('bounds unpinned snapshots by seven days and 50 jobs without evicting pending context', () => {
  const rows: FieldRecord[] = Array.from({ length: 55 }, (_, index) =>
    snapshot(`job-${index}`, now - index),
  );
  rows.push(
    snapshot('expired', now - 8 * 86400000),
    snapshot('pinned', now - 10 * 86400000),
    command('pending'),
  );
  const retained = retainedSnapshots(rows, now);
  expect(retained.size).toBe(51);
  expect(retained.has('job-0')).toBe(true);
  expect(retained.has('job-54')).toBe(false);
  expect(retained.has('expired')).toBe(false);
  expect(retained.has('pinned')).toBe(true);
});
it('never treats an explicit refusal as an offline fallback', () => {
  for (const status of [400, 401, 403, 404, 409, 413, 415, 422, 429])
    expect(offlineFallback(new HttpErrorResponse({ status }))).toBe(false);
  for (const status of [0, 500, 503])
    expect(offlineFallback(new HttpErrorResponse({ status }))).toBe(true);
});
it('blocks dependency descendants unless all predecessors are accepted', () => {
  const upload = command('upload', [], 'attention');
  const completion = command('completion', ['upload']);
  expect(dependenciesAccepted(completion, [upload, completion])).toBe(false);
  expect(dependenciesAccepted(completion, [{ ...upload, state: 'accepted' }, completion])).toBe(
    true,
  );
  expect(dependenciesAccepted(completion, [completion])).toBe(false);
});
it('bounds backoff and honors both forms of Retry-After', () => {
  expect(retryDelay(50, null)).toBe(60000);
  expect(retryDelay(0, '180')).toBe(180000);
  expect(retryDelay(0, new Date(now + 120000).toUTCString(), now)).toBeGreaterThan(119000);
});

it('keeps independent ad-hoc reports moving while refused Visit work blocks its own descendants', () => {
  const refused = command('refused', [], 'attention');
  expect(commandDependencies([refused], 'pinned', 'visit', 'note')).toEqual(['refused']);
  expect(commandDependencies([refused], 'pinned', 'other-visit', 'note')).toEqual([]);
  expect(commandDependencies([refused], 'pinned', 'visit', 'ad-hoc')).toEqual([]);
  expect(commandDependencies([{ ...refused, reviewed: true }], 'pinned', 'visit', 'note')).toEqual(
    [],
  );
});
