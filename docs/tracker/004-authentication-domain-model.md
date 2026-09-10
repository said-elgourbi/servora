# Tracker 004 — Authentication Domain Model

Milestone status for the Servora authentication domain model: sessions, refresh tokens and
password reset tokens, their stable codes, configuration and the Android mirror.

- Status: **COMPLETE** (backend + database + Android models + documentation)
- Date: 2026-09-10
- Decisions: `docs/decisions/004-authentication-domain-model.md`
- Domain reference: `docs/domain/authentication-domain-model.md`

## Scope

| Item | Status |
| --- | --- |
| Drizzle tables `auth_sessions`, `auth_refresh_tokens`, `password_reset_tokens` + relations (`api/src/database/schema.ts`) | Done |
| Migration `0001_secret_master_chief.sql` (journal + snapshot updated) | Done |
| Domain types, stable platform codes, `isSessionActive` (`api/src/auth/auth.types.ts`) | Done |
| Opaque token generation, SHA-256 hashing, constant-time comparison (`api/src/auth/auth-token.ts`) | Done |
| Token lifetimes + defaults (`api/src/auth/auth-config.ts`, `api/src/config/duration.ts`) | Done |
| Public DTO — no hash is ever serializable (`api/src/auth/auth.dto.ts`) | Done |
| Unit tests (auth config, duration, tokens, types, DTO) | Done |
| Integration tests (constraints, cascades, uniqueness, rotation, index predicates) | Done |
| Android domain models (`AuthSession.kt`, `AuthTokens.kt`) | Done (not compiled — see below) |
| Documentation (domain model, ADR, README) | Done |

## Verification (2026-09-10, host tooling → PostgreSQL on host port 5434)

```text
API typecheck (tsc)       PASS  0 errors
API lint (oxlint)         PASS  0 warnings / 0 errors (48 files)
API unit tests (Vitest)   PASS  96 passed / 11 files
API e2e (Vitest+PG)       PASS  45 passed / 4 files
API build (nest build)    PASS
Migration 0001            PASS  applied by the Drizzle migrator in the e2e global setup
```

The authentication integration suite (`api/test/auth-foundation.e2e-spec.ts`, 18 tests)
exercises the generated migration rather than a hand-built expectation of it:

| Group | Tests | Coverage |
| --- | --- | --- |
| `auth_sessions` | 6 | generated id + default timestamps, round-tripped client context, several sessions for one user/device, platform CHECK rejection, unknown-user FK rejection (`23503`), cascade on user delete |
| `auth_refresh_tokens` | 5 | hash-only storage, duplicate hash rejection (`23505`), unknown-session FK rejection, cascade on session delete, rotation history kept when the successor is removed (`ON DELETE SET NULL`) |
| `password_reset_tokens` | 5 | unused single-use token, several outstanding tokens for one user, duplicate hash rejection, unknown-user FK rejection, cascade on user delete |
| `index integrity` | 2 | index predicates are immutable; the active-session index is keyed on revocation only |

## Not verified (honest limitations)

- **Android build/lint/tests: NOT RUN.** The on-disk `android/` tree still has no Gradle
  project (ADR-002 / tracker 001). `AuthSession.kt` and `AuthTokens.kt` mirror the wire
  contracts but cannot be compiled until the Android Gradle foundation slice lands. No
  physical-device QA applies yet.
- **Angular: not started** (out of scope).
- **No authentication endpoint exists yet**, so no sign-in/refresh/sign-out behaviour was
  implemented or tested — this milestone is persistence and domain contracts only.

## Open questions carried forward

- OTP expiry, retry, rate limiting, recovery and phone-number lifecycle (`BR-019`).
- Retention/pruning of revoked and expired sessions, refresh tokens and reset tokens
  (`BR-027`, `BR-033`).
- Maximum concurrent sessions per user, and whether a new sign-in must revoke an older
  session.
- Password reset delivery channel and request throttling (`BR-029`).

## Sign-in hardening and the SMS delivery port (2026-09-10)

Decided and implemented after the `/auth` slice was verified, recorded in `ADR-005`:

- `POST /auth/sign-in` refuses a non-`ACTIVE` account (`api/src/users/user-status-rules.ts`)
  with the same `401 INVALID_CREDENTIALS` as a wrong password, and every rejected attempt
  performs exactly one Argon2id verification (`DUMMY_PASSWORD_HASH` when the address is
  unknown), so neither the body nor the timing reveals whether an address is registered.
- SMS delivery exists as a provider-neutral port (`SmsProvider` + `SMS_PROVIDER`, `SmsModule`)
  with a no-op binding. No OTP flow is implemented: `BR-019` still leaves code format,
  expiry, single use, attempt limits, rate limiting, phone-number lifecycle and delivery
  reporting open (`ADR-005` D6).

Contract updated in `docs/api/authentication.md` §1, §3.1 and §7. Verification: `npm run
typecheck`, `npm run test` (18 files, 137 tests), the full `npm run test:e2e` suite, `npm run
lint` (0 warnings, 0 errors) and `prettier --check` on the touched files all pass. No client
change was made or verified: neither Android nor Angular calls `/auth/sign-in` yet (no
client references the route), and physical-device QA remains with the product owner
(`qa.md` §7).

## Next


- `/auth` endpoints (sign-in, refresh, sign-out, session listing, password reset) built
  against these tables, with `401`/`403`/authorization coverage at the API boundary.
  - Slice 2 status: **implemented** — `POST /auth/sign-in`, `POST /auth/refresh`,
    `POST /auth/sign-out`, `GET /auth/sessions`; contract and verification in
    `docs/api/authentication.md`. Conventions confirmed with the product owner: Nest-style
    error envelope with stable codes (`AUTH_ERROR_CODES`), hand-written request parsers (no new
    validation dependency), HS256 JWT with a required `JWT_SECRET` (≥ 32 characters, no
    fallback) and no path prefix. `GET /auth/me`, `POST /auth/sign-out-all` and any maximum
    concurrent-session rule remain open decisions and are not implemented.
- Organization membership and permission enforcement in guards (`BR-006`, `BR-007`).
