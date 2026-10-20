# Tracker 001 — API Foundation

Milestone status for the API foundation (NestJS 12 + PostgreSQL/Drizzle + pino + `/health`).

- Status: **COMPLETE** (API source, local stack, automated verification)
- Latest verification: 2026-09-09
- Decisions: `docs/decisions/001-foundation-stack.md`

## Scope

| Item | Status |
| --- | --- |
| Env config loader + validation (`api/src/config`) | Done |
| Pino logger + Nest adapter (`api/src/logging`) | Done |
| `DatabaseModule` / `DatabaseService` with lazy `postgres.js` pool | Done |
| Drizzle schema placeholder + migration runner | Done |
| `HealthModule` — `GET /health` reflects DB availability | Done |
| `AppModule`, `main.ts`, pino-http request logging | Done |
| Dockerfile + Docker Compose (`postgres` + `api`) | Done |
| Makefile targets (`help up down logs ps migrate api-*`) | Done |
| Root `.env.example` + machine-local `.env` convention | Done |
| Unit tests (config, health controller) | Done |
| Health e2e test against real PostgreSQL | Done |
| README + decision record + this tracker | Done |

## Verification (2026-09-09)

Host (machine-local `.env`: PostgreSQL published on host port 5434):

```text
API unit tests    PASS   16 passed / 2 files
API lint          PASS
API typecheck     PASS
API build         PASS
API e2e (Vitest+Supertest+PostgreSQL)   PASS  1 passed
make migrate      PASS   database reachable, no migrations yet
```

Stack (Docker Compose):

```text
servora-postgres-1  Up (healthy), published 0.0.0.0:5434->5432
servora-api-1       Up, published 0.0.0.0:3000->3000

GET http://localhost:3000/health  →  200 {"status":"ok","database":"up"}
GET http://localhost:3100/health  →  200 {"status":"ok","database":"up"}  (host-run API)
GET http://localhost:3100/health with DB unreachable → 503 {"status":"unavailable","database":"down"}
```

## Fixed during this milestone

- **Container boot crash (fatal):** Nest `abortOnError` masked a runtime exception;
  pino level methods were invoked unbound from the Nest logger adapter. Fixed by binding
  every level method to the pino instance. Debug boot used `abortOnError: false` to
  expose the real error; production boot keeps the default.
- **Stale bootstrap containers** (`servora-db-1`, `servora-web-1` from an earlier
  compose layout) removed; named volume `servora_pgdata` retained.
- **Host port conflict:** development machine runs other projects on 5432/5433, so the
  local `.env` publishes foundation PostgreSQL on 5434 (see ADR-001 D2).

## Out of scope / next

- Domain features (customers → technicians → jobs → …) each become their own feature
  slice with migration, API, authorization and tests.
- No Drizzle migration journal exists yet; the first domain feature creates it.
- Android scaffold exists but is not part of this API milestone; Android tooling targets
  (`make android-*`) require an Android SDK and are not exercised here.
- Angular client is not started (`make e2e-web` intentionally absent).
- Physical-device Android QA: **not applicable** (no Android change in this milestone).
