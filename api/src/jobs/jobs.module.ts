import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { CustomersModule } from '../customers/customers.module.js';
import { DatabaseModule } from '../database/database.module.js';
import { StorageModule } from '../storage/storage.module.js';
import { JobsController } from './jobs.controller.js';
import { JobAudioNotesService } from './job-audio-notes.service.js';
import { JobPhotosService } from './job-photos.service.js';
import { JobsService } from './jobs.service.js';

/**
 * The Job reads, the Job and Visit actions, and the Job evidence routes (photos and audio notes).
 *
 * `CustomersModule` is imported for `PropertiesService`: creating a Job runs the Property's own rules
 * — the Property must belong to the Customer and be `ACTIVE` — through the service that owns them,
 * inside the transaction that inserts the Job, rather than re-implementing them here (`BR-083`,
 * `BR-085`, `BR-041`).
 */
@Module({
  imports: [DatabaseModule, AuthModule, StorageModule, CustomersModule],
  controllers: [JobsController],
  providers: [JobsService, JobPhotosService, JobAudioNotesService],
  exports: [JobsService],
})
export class JobsModule {}
