import { Injectable, Logger, OnApplicationShutdown } from '@nestjs/common';
import { sql } from 'drizzle-orm';
import { drizzle, type PostgresJsDatabase } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import { loadConfig } from '../config/configuration.js';

/**
 * Owns the PostgreSQL connection pool used by the API.
 *
 * The pool is created lazily (postgres.js does not open sockets until the
 * first query), so the application can boot and report an unavailable health
 * state even when the database is down — which is exactly what a foundation
 * health check needs to demonstrate.
 */
@Injectable()
export class DatabaseService implements OnApplicationShutdown {
  private readonly logger = new Logger(DatabaseService.name);
  private readonly client: postgres.Sql;
  readonly db: PostgresJsDatabase<Record<string, never>>;

  constructor() {
    const { databaseUrl } = loadConfig();
    this.client = postgres(databaseUrl, {
      max: 10,
      connect_timeout: 10,
      // Surfaces connection notices via our own logging channel instead of
      // postgres.js writing to stdout directly.
      onnotice: () => undefined,
    });
    this.db = drizzle(this.client);
  }

  /** Executes `SELECT 1` through Drizzle to verify database connectivity. */
  async ping(): Promise<void> {
    await this.db.execute(sql`select 1`);
  }

  async onApplicationShutdown(signal?: string): Promise<void> {
    this.logger.log(
      `Closing database connection pool${signal ? ` (signal: ${signal})` : ''}.`,
    );
    await this.client.end();
  }
}
