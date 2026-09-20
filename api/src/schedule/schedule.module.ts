import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database/database.module.js';
import { TechniciansModule } from '../technicians/technicians.module.js';
import { ScheduleController } from './schedule.controller.js';
import { ScheduleService } from './schedule.service.js';

/** The day schedule read (`GET /schedule`). */
@Module({
  imports: [DatabaseModule, AuthModule, TechniciansModule],
  controllers: [ScheduleController],
  providers: [ScheduleService],
})
export class ScheduleModule {}
