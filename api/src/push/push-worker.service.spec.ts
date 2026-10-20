import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DatabaseService } from '../database/database.service.js';
import { PushWorkerService, pushWorkerEnabled } from './push-worker.service.js';
import { runAssignmentDeliveryBatch } from './assignment-delivery-worker.js';
import { maintainPushState } from './push-maintenance.js';
vi.mock('./assignment-delivery-worker.js', () => ({
  runAssignmentDeliveryBatch: vi.fn().mockResolvedValue(undefined),
}));
vi.mock('./push-maintenance.js', () => ({
  maintainPushState: vi.fn().mockResolvedValue(undefined),
}));
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});
function fixture(pages: { id: string }[][] = []) {
  const query = {
    where: vi.fn().mockReturnThis(),
    orderBy: vi.fn().mockReturnThis(),
    limit: vi.fn(),
  };
  pages.forEach((page) => query.limit.mockResolvedValueOnce(page));
  query.limit.mockResolvedValue([]);
  const db = { select: vi.fn(() => ({ from: vi.fn(() => query) })) };
  const provider = { send: vi.fn() };
  return {
    db,
    query,
    provider,
    worker: new PushWorkerService(
      { db } as unknown as DatabaseService,
      provider,
    ),
  };
}
describe('scheduled push worker', () => {
  it('defaults off and rejects invalid opt-in and incomplete environment', () => {
    expect(pushWorkerEnabled({})).toBe(false);
    expect(() => pushWorkerEnabled({ PUSH_WORKER_ENABLED: 'yes' })).toThrow();
    expect(() => pushWorkerEnabled({ PUSH_WORKER_ENABLED: 'true' })).toThrow();
    expect(
      pushWorkerEnabled({
        PUSH_WORKER_ENABLED: 'true',
        FIREBASE_PROJECT_ID: 'servora-test-123',
        PUSH_ANDROID_APPLICATION_ID: 'com.servora.android.debug',
      }),
    ).toBe(true);
  });
  it('does not create timers or query when disabled', async () => {
    vi.useFakeTimers();
    vi.stubEnv('PUSH_WORKER_ENABLED', 'false');
    const { worker, db } = fixture();
    worker.onModuleInit();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(db.select).not.toHaveBeenCalled();
    await worker.beforeApplicationShutdown();
  });
  it('enumerates bounded trusted pages and wraps the cursor', async () => {
    const page = Array.from({ length: 25 }, (_, i) => ({ id: `org-${i}` }));
    const { worker, query, db, provider } = fixture([
      page,
      [{ id: 'last' }],
      [],
    ]);
    await worker.tick();
    await worker.tick();
    await worker.tick();
    expect(query.limit).toHaveBeenCalledWith(25);
    expect(query.where.mock.calls[0][0]).toBeUndefined();
    expect(query.where.mock.calls[1][0]).toBeDefined();
    expect(query.where.mock.calls[2][0]).toBeUndefined();
    expect(maintainPushState).toHaveBeenCalledTimes(26);
    expect(runAssignmentDeliveryBatch).toHaveBeenLastCalledWith(
      db,
      expect.anything(),
      provider,
      { organizationId: 'last' },
    );
    await worker.beforeApplicationShutdown();
  });
  it('never overlaps ticks and drains before shutdown', async () => {
    vi.useFakeTimers();
    vi.stubEnv('PUSH_WORKER_ENABLED', 'true');
    vi.stubEnv('FIREBASE_PROJECT_ID', 'servora-test-123');
    vi.stubEnv('PUSH_ANDROID_APPLICATION_ID', 'com.servora.android.debug');
    let release!: () => void;
    vi.mocked(runAssignmentDeliveryBatch).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    const { worker, db } = fixture([[{ id: 'one' }]]);
    worker.onModuleInit();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(db.select).toHaveBeenCalledTimes(1);
    let stopped = false;
    const closing = worker.beforeApplicationShutdown().then(() => {
      stopped = true;
    });
    await Promise.resolve();
    expect(stopped).toBe(false);
    release();
    await closing;
    await vi.advanceTimersByTimeAsync(60_000);
    expect(db.select).toHaveBeenCalledTimes(1);
  });
  it('continues other organizations after a scoped failure', async () => {
    vi.mocked(maintainPushState).mockRejectedValueOnce(
      new Error('private SQL omitted'),
    );
    const { worker } = fixture([[{ id: 'bad' }, { id: 'good' }]]);
    await worker.tick();
    expect(runAssignmentDeliveryBatch).toHaveBeenCalledTimes(1);
    expect(runAssignmentDeliveryBatch).toHaveBeenCalledWith(
      expect.anything(),
      expect.anything(),
      expect.anything(),
      { organizationId: 'good' },
    );
  });
});
