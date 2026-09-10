import { resolve } from 'node:path';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';
import { loadConfig } from '../src/config/configuration.js';

/**
 * Vitest global setup for the PostgreSQL e2e suite.
 *
 * Applies every pending Drizzle migration exactly once per run so parallel test
 * files never race each other. Requires PostgreSQL at DATABASE_URL (`make up`).
 */
export default async function setup(): Promise<void> {
  const { databaseUrl } = loadConfig();
  const client = postgres(databaseUrl, {
    max: 1,
    connect_timeout: 10,
    onnotice: () => undefined,
  });

  try {
    await client`select 1`;
    await migrate(drizzle(client), {
      migrationsFolder: resolve(process.cwd(), 'drizzle/migrations'),
    });
  } finally {
    await client.end();
  }
}
