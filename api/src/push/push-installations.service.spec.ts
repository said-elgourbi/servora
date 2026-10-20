import { HttpException } from '@nestjs/common';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DatabaseService } from '../database/database.service.js';
import { PushInstallationsService } from './push-installations.service.js';

afterEach(() => vi.unstubAllEnvs());
describe('push registration failure containment', () => {
  it('does not propagate database SQL parameters or causes into framework logging', async () => {
    const raw = new Error('SQL values: provider-target, installation-secret', {
      cause: new Error('private SQL details'),
    });
    const database = {
      db: { transaction: vi.fn().mockRejectedValue(raw) },
    } as unknown as DatabaseService;
    const service = new PushInstallationsService(database);
    let caught: unknown;
    try {
      await service.get(
        { userId: 'user', sessionId: 'session' },
        'installation',
        'secret',
      );
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpException);
    expect((caught as HttpException).getStatus()).toBe(500);
    expect((caught as HttpException).cause).toBeUndefined();
    expect(JSON.stringify((caught as HttpException).getResponse())).not.toMatch(
      /provider-target|installation-secret|private SQL/,
    );
  });
  it('refuses registration until a project and Android application are selected', async () => {
    vi.stubEnv('FIREBASE_PROJECT_ID', '');
    vi.stubEnv('PUSH_ANDROID_APPLICATION_ID', '');
    const transaction = vi.fn();
    const service = new PushInstallationsService({
      db: { transaction },
    } as unknown as DatabaseService);
    await expect(
      service.register(
        { userId: 'user', sessionId: 'session' },
        'id',
        'secret',
        {
          expectedRevision: 0,
          applicationId: 'com.servora.android.debug',
          registration: { kind: 'FCM_TOKEN', value: 'target' },
          locale: 'en-CA',
          alertsEnabled: true,
          notificationPermission: 'GRANTED',
          channelEnabled: true,
        },
      ),
    ).rejects.toMatchObject({ status: 503 });
    expect(transaction).not.toHaveBeenCalled();
  });
});
