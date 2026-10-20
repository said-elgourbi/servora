import { Injectable, Module, type OnApplicationShutdown } from '@nestjs/common';
import { deleteApp, type App } from 'firebase-admin/app';
import { DisabledPushProvider } from './disabled-push-provider.js';
import { createFirebasePushApp, firebaseMessaging } from './firebase-client.js';
import { FirebasePushProvider } from './firebase-push-provider.js';
import { loadPushConfig } from './push-config.js';
import {
  PUSH_PROVIDER,
  type OutboundPushMessage,
  type PushDeliveryResult,
  type PushProvider,
} from './push-provider.js';

@Injectable()
class PushTransport implements PushProvider, OnApplicationShutdown {
  private readonly app: App | null;
  private readonly provider: PushProvider;

  constructor() {
    const config = loadPushConfig();
    if (config.provider === 'disabled') {
      this.app = null;
      this.provider = new DisabledPushProvider();
    } else {
      this.app = createFirebasePushApp(config.projectId);
      this.provider = new FirebasePushProvider(firebaseMessaging(this.app));
    }
  }

  send(message: OutboundPushMessage): Promise<PushDeliveryResult> {
    return this.provider.send(message);
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.app) await deleteApp(this.app);
  }
}

@Module({
  providers: [
    PushTransport,
    { provide: PUSH_PROVIDER, useExisting: PushTransport },
  ],
  exports: [PUSH_PROVIDER],
})
export class PushModule {}
