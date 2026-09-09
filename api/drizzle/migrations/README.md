# Drizzle migrations

This folder is the single controlled home for Servora database migrations.

- Migrations are generated with `npm run db:generate` (wrapped by
  `make migration NAME=<description>` at the repository root).
- Migrations are applied with `npm run db:migrate` (wrapped by `make migrate`),
  which runs the compiled migrator in `src/database/run-migrations.ts`.
- Generated folders/files under `drizzle/migrations` are committed to the
  repository. Never modify an applied migration; add a new one instead.
- Business/domain tables are deliberately absent in this foundation milestone
  (see docs/tracker/001-foundation.md). This folder exists so the migration
  mechanism and bookkeeping are in place before the first domain feature.
