# Drizzle migrations

This folder is the single controlled home for Servora database migrations.

- Migrations are generated with `npm run db:generate` (wrapped by
  `make migration NAME=<description>` at the repository root).
- Migrations are applied with `npm run db:migrate` (wrapped by `make migrate`),
  which runs the compiled migrator in `src/database/run-migrations.ts`.
- Generated folders/files under `drizzle/migrations` are committed to the
  repository. Never modify an applied migration; add a new one instead.
- The migration journal (`meta/_journal.json`) was created by the first domain
  feature: `0000_foundation_domain_model.sql` (organizations, users, profiles,
  memberships, customers + subtypes, contacts, addresses). See
  `docs/domain/foundation-domain-model.md`.
