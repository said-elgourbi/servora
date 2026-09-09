import { defineConfig } from 'drizzle-kit';

// Drizzle configuration used by `npm run db:generate` and `npm run db:migrate`.
// Migrations are applied by src/database/run-migrations.ts (compiled into dist/)
// and are tracked under drizzle/migrations.
export default defineConfig({
  dialect: 'postgresql',
  schema: './src/database/schema.ts',
  out: './drizzle/migrations',
  dbCredentials: {
    url:
      process.env.DATABASE_URL ??
      'postgres://servora:servora@localhost:5432/servora',
  },
});
