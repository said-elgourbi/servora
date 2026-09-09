import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';
import { loadConfig } from '../config/configuration.js';

// Compiled to dist/ by `nest build` and executed by `npm run db:migrate`.
const MIGRATIONS_DIR = resolve(process.cwd(), 'drizzle/migrations');
const JOURNAL_FILE = join(MIGRATIONS_DIR, 'meta', '_journal.json');

async function main(): Promise<void> {
  const { databaseUrl } = loadConfig();
  const client = postgres(databaseUrl, { max: 1, connect_timeout: 10 });

  try {
    // Verify connectivity before touching the migration bookkeeping.
    await client`select 1`;

    if (!existsSync(JOURNAL_FILE)) {
      // No migrations yet: drizzle's migrator requires an existing journal.
      // The first business feature generates the journal alongside its first
      // migration, so this is an expected state in the foundation milestone.
      console.log(
        'Database reachable. No migrations to apply yet — the first domain feature will create the migration journal.',
      );
      return;
    }

    await migrate(drizzle(client), { migrationsFolder: MIGRATIONS_DIR });
    console.log('Database migrations applied successfully.');
  } finally {
    await client.end();
  }
}

main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : String(error);
  console.error(`Database migration failed: ${message}`);
  process.exitCode = 1;
});
