import {
  Inject,
  Injectable,
  Logger,
  type OnModuleInit,
  type BeforeApplicationShutdown,
} from '@nestjs/common';
import { asc, gt } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import { organizations } from '../database/schema.js';
import { runAssignmentDeliveryBatch } from './assignment-delivery-worker.js';
import { maintainPushState } from './push-maintenance.js';
import { PUSH_PROVIDER, type PushProvider } from './push-provider.js';
import { loadPushRegistrationConfig } from './push-registration-config.js';

export function pushWorkerEnabled(
  env: NodeJS.ProcessEnv = process.env,
): boolean {
  const value = env.PUSH_WORKER_ENABLED?.trim() || 'false';
  if (!['true', 'false'].includes(value))
    throw new Error('PUSH_WORKER_ENABLED must be true or false.');
  const config = loadPushRegistrationConfig(env);
  if (value === 'true' && (!config.projectId || !config.applicationId))
    throw new Error(
      'Push worker requires FIREBASE_PROJECT_ID and PUSH_ANDROID_APPLICATION_ID.',
    );
  return value === 'true';
}

/** Trusted database enumeration, bounded concurrency and keyset paging; no request-supplied tenants. */
@Injectable()
export class PushWorkerService
  implements OnModuleInit, BeforeApplicationShutdown
{
  private readonly logger = new Logger(PushWorkerService.name);
  private readonly enabled = pushWorkerEnabled();
  private readonly config = loadPushRegistrationConfig();
  private cursor: string | undefined;
  private timer?: ReturnType<typeof setTimeout>;
  private running?: Promise<void>;
  private stopped = false;

  constructor(
    private readonly database: DatabaseService,
    @Inject(PUSH_PROVIDER) private readonly provider: PushProvider,
  ) {}

  onModuleInit(): void {
    if (this.enabled) this.schedule();
  }

  private schedule(): void {
    if (this.stopped) return;
    this.timer = setTimeout(() => {
      this.running = this.tick()
        .catch(() => {
          // SQL/provider exceptions may contain targets. Never log the raw exception.
          this.logger.error(
            'Push worker iteration failed; retrying on next tick.',
          );
        })
        .finally(() => {
          this.running = undefined;
          this.schedule();
        });
    }, 5000);
    this.timer.unref();
  }

  async tick(): Promise<void> {
    const page = await this.database.db
      .select({ id: organizations.id })
      .from(organizations)
      .where(this.cursor ? gt(organizations.id, this.cursor) : undefined)
      .orderBy(asc(organizations.id))
      .limit(25);
    for (const scope of page) {
      if (this.stopped) break;
      try {
        await maintainPushState(this.database.db, scope.id);
        await runAssignmentDeliveryBatch(
          this.database.db,
          this.config,
          this.provider,
          { organizationId: scope.id },
        );
      } catch {
        this.logger.error(
          'Push organization batch failed; continuing bounded enumeration.',
        );
      }
    }
    this.cursor = page.length === 25 ? page[page.length - 1].id : undefined;
  }

  async beforeApplicationShutdown(): Promise<void> {
    this.stopped = true;
    clearTimeout(this.timer);
    await this.running;
  }
}
