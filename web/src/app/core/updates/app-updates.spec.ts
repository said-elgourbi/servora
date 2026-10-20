import { TestBed } from '@angular/core/testing';
import { SwUpdate } from '@angular/service-worker';
import { AppUpdates } from './app-updates';
it('blocks reload/navigation checkpoints when an editor cannot persist and supports recovery', async () => {
  TestBed.configureTestingModule({
    providers: [{ provide: SwUpdate, useValue: { isEnabled: false } }],
  });
  const updates = TestBed.inject(AppUpdates);
  const save = vi
    .fn()
    .mockRejectedValueOnce(new Error('QuotaExceededError'))
    .mockResolvedValue(undefined);
  const unregister = updates.beforeReload(save);
  expect(await updates.checkpoint()).toBe(false);
  expect(updates.reloadBlocked()).toBe(true);
  expect(await updates.checkpoint()).toBe(true);
  unregister();
  expect(await updates.checkpoint()).toBe(true);
  expect(save).toHaveBeenCalledTimes(2);
});
