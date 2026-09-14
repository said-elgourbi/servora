import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database/database.module.js';
import { JobsController } from './jobs.controller.js';
import { JobsService } from './jobs.service.js';

/** The Job reads (`GET /jobs/:id`). */
@Module({
  imports: [DatabaseModule, AuthModule],
  controllers: [JobsController],
  providers: [JobsService],
  exports: [JobsService],
})
export class JobsModule {}
