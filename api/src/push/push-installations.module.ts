import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database/database.module.js';
import { PushInstallationsController } from './push-installations.controller.js';
import { PushInstallationsService } from './push-installations.service.js';

@Module({
  imports: [AuthModule, DatabaseModule],
  controllers: [PushInstallationsController],
  providers: [PushInstallationsService],
})
export class PushInstallationsModule {}
