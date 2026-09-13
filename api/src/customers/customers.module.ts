import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module.js';
import { DatabaseModule } from '../database/database.module.js';
import { CustomersController } from './customers.controller.js';
import { CustomersService } from './customers.service.js';
import { PropertiesController } from './properties.controller.js';
import { PropertiesService } from './properties.service.js';

@Module({
  imports: [DatabaseModule, AuthModule],
  controllers: [CustomersController, PropertiesController],
  providers: [CustomersService, PropertiesService],
  exports: [CustomersService, PropertiesService],
})
export class CustomersModule {}
