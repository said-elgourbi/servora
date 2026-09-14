import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database/database.module.js';
import { ManagerHomeController } from './manager-home.controller.js';
import { ManagerHomeService } from './manager-home.service.js';

/** The manager home read (`GET /home/manager`). */
@Module({
  imports: [DatabaseModule, AuthModule],
  controllers: [ManagerHomeController],
  providers: [ManagerHomeService],
})
export class ManagerHomeModule {}
