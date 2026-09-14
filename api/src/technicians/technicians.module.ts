import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database/database.module.js';
import { TechniciansController } from './technicians.controller.js';
import { TechniciansService } from './technicians.service.js';

/** The technician read (`GET /technicians`). */
@Module({
  imports: [DatabaseModule, AuthModule],
  controllers: [TechniciansController],
  providers: [TechniciansService],
  exports: [TechniciansService],
})
export class TechniciansModule {}
