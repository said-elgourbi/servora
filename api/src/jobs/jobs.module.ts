import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database/database.module.js';
import { StorageModule } from '../storage/storage.module.js';
import { JobsController } from './jobs.controller.js';
import { JobPhotosService } from './job-photos.service.js';
import { JobsService } from './jobs.service.js';

/** The Job reads, the Job and Visit actions, and the Job photo evidence routes. */
@Module({
  imports: [DatabaseModule, AuthModule, StorageModule],
  controllers: [JobsController],
  providers: [JobsService, JobPhotosService],
  exports: [JobsService],
})
export class JobsModule {}
