import { Module } from '@nestjs/common';
import { AuthModule } from './auth/auth.module.js';
import { CustomersModule } from './customers/customers.module.js';
import { DatabaseModule } from './database/database.module.js';
import { HealthModule } from './health/health.module.js';
import { SmsModule } from './sms/sms.module.js';

@Module({
  imports: [
    DatabaseModule,
    AuthModule,
    CustomersModule,
    HealthModule,
    SmsModule,
  ],
})
export class AppModule {}
