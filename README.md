# Servora

Field-service management platform.

Servora is a monorepo built feature by feature as controlled vertical slices.

- **API** — NestJS (TypeScript ESM) + PostgreSQL (Drizzle ORM). The backend is the authoritative system of record (BR-001).
- **Android** — Kotlin / Jetpack Compose native field application (offline-first). *Not yet implemented.*
- **Angular** — management/office client. *Not yet implemented.*

Initial supported languages are **English** and **French**. Development rules live in
`Project.md`, `dev.md`, `qa.md` and the application rules in the project ruleset.

> The repository currently contains the API foundation and the **foundation domain model**
> (organizations, users, profiles, memberships, customers and their contacts/addresses).
> See `docs/tracker/001-foundation.md`, `docs/tracker/002-repository-foundation.md`,
> `docs/tracker/003-foundation-domain-model.md` and
> `docs/tracker/004-authentication-domain-model.md` for status.
> Android app appearance controls (app language, light/dark) are covered by
> `docs/tracker/005-android-appearance-controls.md`.
>
> Authentication persistence (sessions, refresh tokens, password reset tokens) is modelled in
> `docs/domain/authentication-domain-model.md`.

## Layout

```text
api/        NestJS REST API (system of record)
android/    Android client (scaffold — not yet implemented)
docs/
  decisions/   Architecture decision records
  tracker/     Feature milestone tracker
  domain/      Domain model documentation
docker-compose.yml   Foundation local stack (PostgreSQL + API)
Makefile             Local development orchestration
```

## Prerequisites

- Node.js ≥ 24 (see `api/package.json`)
- npm
- Docker with the Compose plugin
- JDK + Android SDK (only required for Android targets)

## Quick start

```bash
cp .env.example .env        # then adjust local values if ports 5432/3000 are taken
make up                     # build + start PostgreSQL and the API
curl http://localhost:3000/health
```

`/health` reports API and database status:

```json
{"status":"ok","database":"up"}            // HTTP 200
{"status":"unavailable","database":"down"} // HTTP 503 when PostgreSQL is unreachable
```

The API boots even when PostgreSQL is down (the database pool is lazy) and reports
`down` via `/health` until the database becomes reachable.

## Common commands

```bash
make ps                  # foundation service status
make logs                # follow foundation logs
make migrate             # apply pending migrations (host tooling → .env DATABASE_URL)
make migration NAME=…    # generate a new Drizzle migration
make db-shell            # psql shell into the development PostgreSQL
make api-test            # API unit tests (Vitest)
make api-test-e2e        # API e2e tests against PostgreSQL (needs `make up`)
make api-lint            # API lint (oxlint)
make api-start           # run the compiled API on the host
make android-build       # Android debug APK (requires Android SDK)
```

`make help` lists every target.

## Configuration

- `docker-compose.yml` is driven by `.env` at the repository root (copy from `.env.example`).
- `POSTGRES_PORT` / `API_PORT` select the host ports published by the stack.
- `DATABASE_URL` is used by **host-side** tooling (`make migrate`, `npm run start:prod`,
  `npm run test:e2e`). The compose `api` service ignores it and reaches PostgreSQL over
  the compose network.
- When a non-default `DATABASE_URL`/`POSTGRES_PORT` is required on a machine where the
  default ports are taken, set them in the local `.env` — never commit machine-specific
  values (`.env` is git-ignored).

- Authentication token lifetimes are configurable per environment: `ACCESS_TOKEN_LIFETIME`
  (default `15m`), `SESSION_LIFETIME` (default `30d`) and `PASSWORD_RESET_TOKEN_LIFETIME`
  (default `30m`). Durations accept `500ms`, `15m`, `2h`, `30d`; the API refuses to start on
  an unusable value. See `docs/domain/authentication-domain-model.md` §7.


## Repository & versioning

- Canonical repository: `https://github.com/said-elgourbi/servora.git` (remote `origin`).
- `main` — stable, release-ready; releases are tagged from `main` (`v0.1.0`, …).
- `develop` — primary integration and development branch (default working branch).
- Foundation status: `docs/tracker/001-foundation.md`,
  `docs/tracker/002-repository-foundation.md`, `docs/tracker/003-foundation-domain-model.md`,
  `docs/tracker/004-authentication-domain-model.md`.
- Domain model: `docs/domain/foundation-domain-model.md`.
- Authentication domain model: `docs/domain/authentication-domain-model.md`
  (decision: `docs/decisions/004-authentication-domain-model.md`).
- Android appearance controls: `docs/tracker/005-android-appearance-controls.md`
  (decision: `docs/decisions/007-android-appearance-controls.md`).
- Full ruleset: `docs/versioning.md` — read it before branching or committing.

