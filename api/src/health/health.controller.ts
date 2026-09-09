import {
  Controller,
  Get,
  Logger,
  ServiceUnavailableException,
} from '@nestjs/common';
import { DatabaseService } from '../database/database.service.js';

export interface HealthStatus {
  status: 'ok' | 'unavailable';
  database: 'up' | 'down';
}

@Controller('health')
export class HealthController {
  private readonly logger = new Logger(HealthController.name);

  constructor(private readonly database: DatabaseService) {}

  @Get()
  async check(): Promise<HealthStatus> {
    try {
      await this.database.ping();
      return { status: 'ok', database: 'up' };
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      this.logger.error(`Health check failed: ${detail}`);
      throw new ServiceUnavailableException({
        status: 'unavailable',
        database: 'down',
      } satisfies HealthStatus);
    }
  }
}
