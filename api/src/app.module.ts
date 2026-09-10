import { Module } from '@nestjs/common';
import { CustomersModule } from './customers/customers.module.js';
import { DatabaseModule } from './database/database.module.js';
import { HealthModule } from './health/health.module.js';

@Module({
  imports: [DatabaseModule, CustomersModule, HealthModule],
})
export class AppModule {}
