import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { PushModule } from './push.module.js';
import { PushWorkerService } from './push-worker.service.js';

@Module({
  imports: [DatabaseModule, PushModule],
  providers: [PushWorkerService],
})
export class PushWorkerModule {}
