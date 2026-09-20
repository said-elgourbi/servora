import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database/database.module.js';
import { TechnicianHomeController } from './technician-home.controller.js';
import { TechnicianHomeService } from './technician-home.service.js';

/** The technician home read (`GET /home/technician`). */
@Module({
  imports: [DatabaseModule, AuthModule],
  controllers: [TechnicianHomeController],
  providers: [TechnicianHomeService],
})
export class TechnicianHomeModule {}
