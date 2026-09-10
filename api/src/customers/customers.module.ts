import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { CustomersService } from './customers.service.js';

@Module({
  imports: [DatabaseModule],
  providers: [CustomersService],
  exports: [CustomersService],
})
export class CustomersModule {}
