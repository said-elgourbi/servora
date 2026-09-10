# Tracker 003 — Foundation Domain Model

Milestone status for the Servora foundation domain model (organizations, users,
profiles, memberships/roles, customers + subtypes, contacts, addresses).

- Status: **COMPLETE** (backend + database + Android models + documentation)
- Date: 2026-09-10
- Decisions: `docs/decisions/003-foundation-domain-model.md`
- Domain reference: `docs/domain/foundation-domain-model.md`

## Scope

| Item | Status |
| --- | --- |
| Drizzle schema for 9 tables + relations (`api/src/database/schema.ts`) | Done |
| Migration `0000_foundation_domain_model.sql` (journal created) | Done |
| Domain types + stable status codes (orgs, users, members, customers) | Done |
| DTOs + parsers; `password_hash` never exposed | Done |
| Shared validation helpers (`api/src/validation`) | Done |
| Argon2id password hashing (`api/src/users/password-hasher.ts`) | Done |
| Tenant scope type + tenant-scoped `CustomersService` | Done |
| Customer subtype/type integrity validation | Done |
| Unit tests (validation, hashing, DTO leakage, subtype integrity) | Done |
| Integration tests (constraints, cascades, uniqueness, tenant isolation) | Done |
| Android domain/data models (Kotlin, no UI) | Done (not compiled — see below) |
| Documentation (domain model, ADR, README) | Done |

## Verification (2026-09-10, host tooling → PostgreSQL on host port 5434)

```text
make migrate              PASS  0000_foundation_domain_model applied
API typecheck (tsc)       PASS  0 errors
API lint (oxlint)         PASS  0 warnings / 0 errors (37 files)
API unit tests (Vitest)   PASS  57 passed / 6 files
API e2e (Vitest+PG)       PASS  27 passed / 3 files
API build (nest build)    PASS
```

Schema assertions on the generated migration: 9 tables, 14 named indexes, 7 CHECK
constraints, 2 UNIQUE constraints, 8 cascading foreign keys, 16 timezone-aware timestamp
columns — all present.

## Not verified (honest limitations)

- **Android build/lint/tests: NOT RUN.** The on-disk `android/` tree still has no Gradle
  project (ADR-002 / tracker 001). The Kotlin models were added but cannot be compiled until
  the Android Gradle foundation slice lands. No physical-device QA applies yet.
- **Angular: not started** (out of scope).

## Out of scope / next

- Jobs and job lifecycle (BR-022 OPEN QUESTION) — the next domain slice once product rules are
  defined; contains the first `organization_id`-scoped mutable, offline-capable entity.
- Assignment (BR-025), scheduling (BR-026), contacts already modelled but job-facing behaviour
  deferred.
- Custom roles / permission catalogue (BR-004/BR-006) beyond the two system roles.
