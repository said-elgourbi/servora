import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  assignmentRetryDelayMs,
  withProviderDeadline,
} from './assignment-delivery-worker.js';
import { PushDeliveryError } from './push-provider.js';

afterEach(() => vi.useRealTimers());
describe('assignment delivery retry policy', () => {
  it('uses the approved minute schedule with additive 0–20% jitter', () => {
    [1, 2, 4, 8, 16, 32, 60].forEach((minutes, index) => {
      expect(assignmentRetryDelayMs(index + 1, 0)).toBe(minutes * 60_000);
      expect(assignmentRetryDelayMs(index + 1, 1)).toBe(minutes * 72_000);
    });
    expect(assignmentRetryDelayMs(1, 0, 180_000)).toBe(180_000);
  });
  it('rejects attempts outside the retry budget and invalid jitter', () => {
    for (const attempt of [0, 8, -1, 1.5, NaN])
      expect(() => assignmentRetryDelayMs(attempt)).toThrow();
    for (const jitter of [-1, 1.1, NaN, Infinity])
      expect(() => assignmentRetryDelayMs(1, jitter)).toThrow();
  });
  it('bounds a hung provider to 30 seconds, and ignores late acceptance', async () => {
    vi.useFakeTimers();
    let resolve!: (value: string) => void;
    const result = withProviderDeadline(
      () =>
        new Promise<string>((r) => {
          resolve = r;
        }),
    );
    const assertion = expect(result).rejects.toMatchObject({
      kind: 'retryable',
    });
    await vi.advanceTimersByTimeAsync(30_000);
    await assertion;
    resolve('late');
    expect(vi.getTimerCount()).toBe(0);
  });
  it('clears the deadline on success, controlled rejection and synchronous failure', async () => {
    vi.useFakeTimers();
    await expect(withProviderDeadline(async () => 'accepted')).resolves.toBe(
      'accepted',
    );
    await expect(
      withProviderDeadline(async () => {
        throw new PushDeliveryError('configuration');
      }),
    ).rejects.toMatchObject({ kind: 'configuration' });
    await expect(
      withProviderDeadline(() => {
        throw new Error('fake');
      }),
    ).rejects.toThrow('fake');
    expect(vi.getTimerCount()).toBe(0);
  });
});
