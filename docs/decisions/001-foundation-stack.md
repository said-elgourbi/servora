# ADR-001 — API Foundation Stack

- Status: Accepted
- Date: 2026-09-09
- Milestone: docs/tracker/001-foundation.md

## Context

Servora starts from an approved greenfield foundation: the backend (NestJS API +
PostgreSQL) is the authoritative system of record, and Android/Angular are replaceable
clients of that backend. The foundation milestone must stand up a working, verifiable
local stack before domain features begin.

Earlier bootstrap attempts in this repository produced a different compose layout
(services `db`/`web`, PostgreSQL 18 on host port 5433). Those were superseded and their
stale containers removed; their named volume `servora_pgdata` was retained.

## Decisions

### D1 — Canonical compose stack is `postgres` + `api`

`docker-compose.yml` defines exactly two services:

- `postgres` — `postgres:17-alpine` with healthcheck, persistent named volume, host port
  configurable via `POSTGRES_PORT` (default 5432).
- `api` — built from `api/Dockerfile`, waits for a healthy database
  (`depends_on: condition: service_healthy`), publishes `API_PORT` (default 3000).

Project name is `servora`; containers are `servora-postgres-1` / `servora-api-1`.

### D2 — Host port collisions are handled by machine-local `.env`, never in code

Other projects on the development machine occupy host ports 5432/5433. The repository
defaults remain 5432/3000 (`.env.example`); the local `.env` publishes PostgreSQL on
5434 and points host tooling at it via `DATABASE_URL`. `.env` is git-ignored.

### D3 — The API must boot without a reachable database

The PostgreSQL pool is lazy (`postgres.js` connects on first use). `/health` therefore
reflects live database availability:

- `200 {"status":"ok","database":"up"}` when the database answers a probe query.
- `503 {"status":"unavailable","database":"down"}` otherwise.

This keeps container orchestration (database starting later, transient outages)
observable instead of crashing the API process.

### D4 — Pino logging with a bound Nest adapter

NestJS 12 logging is routed to pino through a custom `LoggerService` adapter. Pino level
methods (`fatal/error/warn/info/debug/trace`) are `this`-sensitive; passing them to Nest
as extracted callbacks crashes at runtime (`Cannot read properties of undefined`). The
adapter binds every level method to the pino logger instance.

### D5 — `abortOnError` must not mask bootstrap failures

Nest's default `abortOnError` aborts the process and can hide the underlying bootstrap
exception from logs. During debugging, booting with `abortOnError: false` exposed the
pino adapter bug. Production behaviour is unchanged (`abortOnError` default), but
bootstrap errors are logged through pino first so the real cause is never lost.

### D6 — Host tooling configuration follows one convention

`npm start:prod`, `npm run db:migrate` and `npm run test:e2e` load the repo-root `.env`
through `node --env-file-if-exists=../.env`. A machine with default free ports needs no
`.env` at all; a machine with collisions keeps a single local `.env`.

### D7 — Removed unused tooling

`vite-tsconfig-paths` was removed: Vitest 4 no longer supplies the Vite peer dependency
it requires, and the repository uses no path aliases.

## Consequences

- `make up` + `curl :3000/health` is the foundation smoke test.
- `make migrate` runs Drizzle migrations from the host against `DATABASE_URL`.
- Migrations are not auto-run at API boot; migration remains an explicit, reviewed step
  (`make migration` / `make migrate`). No domain migrations exist yet.
- The foundation stack currently has no Drizzle migration journal — the first domain
  feature creates it.
- Android and Angular are untouched by this milestone.
