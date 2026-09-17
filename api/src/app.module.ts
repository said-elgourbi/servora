import { Module } from '@nestjs/common';
import { AuthModule } from './auth/auth.module.js';
import { CustomersModule } from './customers/customers.module.js';
import { DatabaseModule } from './database/database.module.js';
import { HealthModule } from './health/health.module.js';
import { ManagerHomeModule } from './home/manager-home.module.js';
import { TechnicianHomeModule } from './home/technician-home.module.js';
import { JobsModule } from './jobs/jobs.module.js';
import { SmsModule } from './sms/sms.module.js';
import { TechniciansModule } from './technicians/technicians.module.js';

@Module({
  imports: [
    DatabaseModule,
    AuthModule,
    CustomersModule,
    ManagerHomeModule,
    TechnicianHomeModule,
    JobsModule,
    TechniciansModule,
    HealthModule,
    SmsModule,
  ],
})
export class AppModule {}
