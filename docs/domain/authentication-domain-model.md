# Servora — Authentication Domain Model

Reference for the authentication persistence model introduced by
`docs/tracker/004-authentication-domain-model.md` and `docs/decisions/004-authentication-domain-model.md`.

It extends `docs/domain/foundation-domain-model.md` (organizations, users, profiles,
memberships/roles, customers): authentication answers *"who is this user and on which
device are they signed in"*, while the foundation doc answers *"what may they see"*.

Scope of this document: the authentication persistence tables, their stable codes, token
storage rules, lifetimes and lifecycle. The authentication **endpoints** (sign-in,
refresh, sign-out, password reset, OTP) are documented separately in
`docs/api/authentication.md`.

## 1. Sessions are user-scoped, not organization-scoped

A session belongs to a **user** (`auth_sessions.user_id → users.id`) and never to an
organization. Signing in happens once per device/app installation; a user who belongs to
several organizations is still one authenticated principal (`BR-001`, `BR-006`). The
organization boundary is applied per request afterwards, through organization
memberships — exactly as in the foundation model.

A session therefore carries only **client context** (platform, device, optional
IP/user-agent) and **validity** (`expires_at`, `revoked_at`). It carries no permissions:
authorization is derived from the user's roles on every request (`BR-006`, `BR-007`).

## 2. `auth_sessions`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` PK | `DEFAULT gen_random_uuid()` |
| `user_id` | `uuid` NOT NULL | → `users.id`, `ON DELETE CASCADE` |
| `platform` | `varchar(20)` NOT NULL | stable code, see §6 |
| `device_id` | `varchar(200)` NOT NULL | client-generated installation identifier |
| `device_name` | `varchar(200)` | presentational label supplied by the client |
| `app_version` | `varchar(50)` | client build that opened the session |
| `ip_address` | `inet` | client address observed by the server |
| `user_agent` | `varchar(500)` | verbatim client agent string |
| `created_at` | `timestamptz` NOT NULL | `DEFAULT now()` |
| `updated_at` | `timestamptz` NOT NULL | refreshed by the DB clock |
| `last_used_at` | `timestamptz` NOT NULL | last accepted authenticated request |
| `expires_at` | `timestamptz` NOT NULL | absolute session lifetime |
| `revoked_at` | `timestamptz` | NULL while active |

Why `device_id` rather than `(user_id, device_id)` uniqueness: a re-install or a second
app profile mints a new installation id, and a user may legitimately hold several
concurrent sessions. Re-authenticating an existing installation is a refresh/rotation
concern, not a uniqueness constraint — so no unique index is placed on
`(user_id, device_id)`.

## 3. `auth_refresh_tokens`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` PK | `DEFAULT gen_random_uuid()` |
| `session_id` | `uuid` NOT NULL | → `auth_sessions.id`, `ON DELETE CASCADE` |
| `token_hash` | `varchar(255)` NOT NULL | **UNIQUE**; hash only, never the token |
| `created_at` / `updated_at` | `timestamptz` NOT NULL | DB clock |
| `expires_at` | `timestamptz` NOT NULL | independent of the session expiry |
| `revoked_at` | `timestamptz` | set when the token is rotated or revoked |
| `replaced_by_token_id` | `uuid` | → `auth_refresh_tokens.id`, `ON DELETE SET NULL` |

Refresh tokens form a **rotation chain**: rotation revokes the presented token
(`revoked_at`) and points it at its successor (`replaced_by_token_id`). The self-reference
uses `ON DELETE SET NULL` rather than `CASCADE` so that pruning a successor never deletes
the audit trail of the predecessor; a reused, already-revoked token stays traceable
instead of silently reappearing as valid.

There is no `UNIQUE(session_id)` constraint: a session legitimately holds one active token
plus the history of rotated tokens.

## 4. `password_reset_tokens`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` PK | `DEFAULT gen_random_uuid()` |
| `user_id` | `uuid` NOT NULL | → `users.id`, `ON DELETE CASCADE` |
| `token_hash` | `varchar(255)` NOT NULL | **UNIQUE**; hash only |
| `created_at` / `updated_at` | `timestamptz` NOT NULL | DB clock |
| `expires_at` | `timestamptz` NOT NULL | short-lived (see §7) |
| `used_at` | `timestamptz` | NULL until the token is redeemed |

Reset tokens are **single-use**: redeeming one sets `used_at`, so a second presentation of
the same token is rejected by comparing `used_at` rather than by deleting the row.
Requesting a new reset credential invalidates any outstanding credential for the same account
(`BR-043`). Historical used, expired or superseded rows may remain for traceability, but at most
one reset credential is usable for an account at a time.

Because reset tokens are per-user and independent of sessions, they are not revoked when a
session ends. They disappear with the user (`ON DELETE CASCADE`).

## 5. Token storage — hashes only, no retrieval

Two different token categories exist and must not be confused:

| Purpose | Mechanism | Where |
| --- | --- | --- |
| User passwords | Argon2id (deliberately slow, salted) | `api/src/users/password-hasher.ts` |
| Opaque session tokens (refresh, reset) | 256-bit CSPRNG token, SHA-256 hash stored | `api/src/auth/auth-token.ts` |

Opaque tokens are high-entropy random strings, not user-chosen secrets: they are hashed
with SHA-256 so that a database leak does not yield usable tokens, while verification
stays a fast, deterministic lookup by `token_hash` plus a constant-time comparison of the
candidate hash. Argon2id is *not* used for them, because its per-verification cost would
buy nothing against a 256-bit random value (ADR-003 D7 covers passwords).

Consequences carried into the schema:

- There is **no column that stores a raw token**; only `*_token_hash` exists.
- `UNIQUE(token_hash)` makes a token globally unambiguous — one lookup identifies exactly
  one row, which is what makes hash-only storage workable.
- Tokens are single-use through `revoked_at` / `used_at`, never through silent deletion.

## 6. Controlled vocabularies

`auth_sessions.platform` is a stable machine-readable code guarded by a CHECK constraint:

| Code | Meaning |
| --- | --- |
| `ANDROID` | Android client (`BR-010`, `BR-013`) |
| `WEB` | Angular management client (`BR-016`) |

The API exports `AUTH_SESSION_PLATFORMS = ['ANDROID', 'WEB']`, and the Android domain
model mirrors it as `AuthSessionPlatform`. Adding a platform is a deliberate schema change
(new CHECK value + new code), never a translated label (`Project.md` §10).

## 7. Lifetimes are configuration; rows store absolute instants

| Variable | Default | Config field | Governs |
| --- | --- | --- | --- |
| `ACCESS_TOKEN_LIFETIME` | `15m` | `accessTokenLifetimeMs` | access token handed to clients |
| `SESSION_LIFETIME` | `30d` | `sessionLifetimeMs` | `auth_sessions.expires_at` |
| `PASSWORD_RESET_TOKEN_LIFETIME` | `30m` | `passwordResetTokenLifetimeMs` | `password_reset_tokens.expires_at` |

Durations are parsed by `parseDuration` (`500ms`, `15m`, `2h`, `30d`) in
`api/src/config/duration.ts` and loaded by `loadAuthConfig` in `api/src/auth/auth-config.ts`,
which fails fast on an unusable value rather than defaulting silently.

Rows always persist an **absolute instant** (`timestamptz`). Relative lifetimes exist only
in configuration, so expiry is never recomputed from a stored duration and a configuration
change never retroactively reinterprets existing rows. A session is active when

```text
revoked_at IS NULL AND expires_at > now()
```

which is exactly `isSessionActive(session, now)` on the API side.

## 8. Indexes

| Index | Serves |
| --- | --- |
| `auth_sessions_user_id_idx` | listing a user's sessions ("signed-in devices") |
| `auth_sessions_device_id_idx` | resolving a client installation |
| `auth_sessions_expires_at_idx` | expiry-based maintenance/pruning |
| `auth_sessions_active_idx` | partial: `(user_id) WHERE revoked_at IS NULL` |
| `auth_refresh_tokens_session_id_idx` | tokens of a session |
| `auth_refresh_tokens_expires_at_idx` | expiry-based maintenance |
| `auth_refresh_tokens_replaced_by_token_id_idx` | following a rotation chain |
| `password_reset_tokens_user_id_idx` | outstanding resets of a user |
| `password_reset_tokens_expires_at_idx` | expiry-based maintenance |

The partial index predicate uses **only** `revoked_at IS NULL`. `now()` was deliberately
excluded: it is not immutable (it is evaluated per statement), so PostgreSQL would reject
it in an index predicate, and a time-dependent predicate would make the index silently stop
matching rows as time passes. `expires_at` is therefore always compared at query time, not
encoded into the index.

## 9. Lifecycle

```text
sign in            → insert auth_sessions + first auth_refresh_tokens row
authenticated call → update last_used_at
refresh            → revoke predecessor, insert successor, link replaced_by_token_id
password reset     → insert password_reset_tokens row; redeem sets used_at
sign out           → set auth_sessions.revoked_at (session stops being active)
expiry             → expires_at passes; the row remains, the session is simply inactive
user deleted       → sessions, refresh tokens and reset tokens cascade away
```

Two deliberate properties:

- **Revocation is not deletion.** `revoked_at` is recorded so that "was this session
  revoked, and when" remains answerable (`BR-033`). A revoked session's refresh tokens are
  unusable because the session itself is inactive — no token rewriting is required.
- **Expiry is not deletion.** Expired rows are retained as history; whether and when they
  are pruned is a product decision (see §12), not something the schema decides by itself.

## 10. Where this lives in the code

| Concern | Location |
| --- | --- |
| Tables, constraints, indexes, relations | `api/src/database/schema.ts` |
| Migration | `api/drizzle/migrations/0001_secret_master_chief.sql` |
| Domain types (`AuthSession`, `AuthRefreshToken`, `PasswordResetToken`, …) | `api/src/auth/auth.types.ts` |
| Stable platform codes + `isSessionActive` | `api/src/auth/auth.types.ts` |
| Opaque token generation/hashing/comparison | `api/src/auth/auth-token.ts` |
| Lifetimes + defaults | `api/src/auth/auth-config.ts`, `api/src/config/duration.ts` |
| Public session representation | `api/src/auth/auth.dto.ts` |
| Password hashing | `api/src/users/password-hasher.ts` |
| Constraint/cascade/uniqueness/index verification | `api/test/auth-foundation.e2e-spec.ts` |
| Android domain models | `android/app/src/main/java/com/servora/android/domain/model/AuthSession.kt`, `AuthTokens.kt` |

## 11. Verification

```text
API typecheck (tsc --noEmit)    PASS  0 errors
API lint (oxlint src/ test/)    PASS  0 warnings / 0 errors (48 files)
API build (nest build)          PASS
API unit tests (Vitest)         PASS  96 passed / 11 files
API e2e (Vitest + PostgreSQL)   PASS  45 passed / 4 files
Migration 0001                  PASS  applied by the e2e global setup (drizzle migrator)
Auth e2e detail                 PASS  18 tests: platform CHECK, unknown-FK rejection
                                      (23503), duplicate token hashes (23505), rotation
                                      chain with ON DELETE SET NULL, cascades from user and
                                      session, index predicate immutability
```

```bash
cd api && npm run typecheck   # or: make lint / make test / make build
cd api && npm run lint
cd api && npm run build
cd api && npm test
cd api && npm run test:e2e
```

## 12. Out of scope and open questions

**Out of scope (this slice is persistence + domain only):**

- **Access token wire format is undecided here.** The schema constrains only stored
  secrets; `IssuedAuthTokens` describes the pair a future endpoint returns.
- **Android ships models only** — no Gradle project exists yet (`ADR-002`), so the Kotlin
  file is not compiled.
- **Nothing enforces a session limit.** Maximum concurrent sessions per user, and whether
  signing in on a new device must revoke an older session, are product decisions and are
  not encoded.

**Open questions carried forward (must not be implemented by assumption):**

| Question | Rule |
| --- | --- |
| Phone-number lifecycle outside sign-in | `BR-019` |
| Whether existing sessions are revoked when a password is reset | `BR-043` |
| Retention/pruning of expired sessions, refresh tokens and reset tokens | `BR-027`, `BR-033` |
| Whether revocation events must be independently audited | `BR-033` |
| Whether additional platforms (`platform` codes beyond `ANDROID`/`WEB`) are needed | `BR-010`, `BR-016` |

> OTP expiry, OTP attempts, OTP resend cooldown, authentication rate limits, password-reset expiry,
> password-reset attempt limits and reset delivery have been decided by `BR-019` and `BR-043`–`BR-046`.
> They are not open questions.

## Password reset and phone/SMS (added 2026-09-10)

The rules for the two additional authentication flows are canonical in `BR-019` (phone/SMS) and
`BR-043`–`BR-046` (password reset, non-disclosure, rate limiting, code storage); the architectural
decisions are in `docs/decisions/006-authentication-flows-and-sms-provider.md`, and the wire
contract is in `docs/api/authentication.md` §3.6–§3.11. Three model changes implement them:

| Change | Purpose |
| --- | --- |
| `password_reset_tokens.attempts` (+ `>= 0` check, outstanding-row index) | Counts failed verifications so the five-attempt limit (`BR-043`) survives the request that observed them |
| `phone_otp_challenges` (new) | One row per SMS OTP request; a new request or a successful verification sets `consumed_at`, so a superseded or used code is provably unusable (`BR-019`) |
| `auth_rate_limit_events` (new) | The rolling-window ledger behind `BR-045`, shared by both flows. The subject is stored as an HMAC, so the table counts attempts without retaining an email address or a phone number |

The reset delivery transport that `ADR-006` D3 left open is decided (`ADR-008`): the message leaves
through the provider-neutral `EMAIL_PROVIDER` port, and the production binding sends it through
Resend. No model change was needed — how a message travels is not a domain concept.

`users.phone` is unchanged: phone authentication resolves eligibility at request time and fails
closed when the number matches no account or more than one (`ADR-006` D5).
