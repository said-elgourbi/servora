# ADR-004 — Authentication Domain Model

- Status: Accepted
- Date: 2026-09-10
- Milestone: `docs/tracker/004-authentication-domain-model.md`
- Supersedes: nothing

## Context

`ADR-003` modelled the platform foundation (organizations, users, profiles, memberships,
customers). `users` therefore holds the credential (`password_hash`) but nothing about
*being signed in*: no session, no refresh token, no password reset token.

`BR-018` (email/password) and `BR-019` (SMS/OTP) confirm the authentication methods, and
`BR-013`–`BR-015` require the Android client to keep working offline across restarts —
which means sign-in state must be revocable and inspectable per device, and pending work
must never depend on an access token that quietly changed meaning. That cannot be
delivered by stateless access tokens alone.

This slice adds the authentication persistence model. It deliberately stops before the
authentication *endpoints*: `BR-042` forbids inventing request/response behaviour, and
`BR-019` explicitly leaves OTP details open.

## Decisions

### D1 — A session belongs to the user, never to the organization

`auth_sessions.user_id → users.id`. Signing in happens once per device/installation, while
a user may belong to several organizations; organization scope is applied per request from
memberships, exactly as in `ADR-003` D8. No permission is stored on the session — that
would duplicate the role model and could go stale (`BR-006`, `BR-007`).

### D2 — Sessions are persisted, so sign-out is observable

A session row is created at sign-in and carries `expires_at`/`revoked_at`. This gives
"which devices are signed in", per-device revocation (needed when a device is lost) and
rotation of refresh tokens. A purely stateless design would make those questions
unanswerable without a second source of truth.

### D3 — Opaque tokens are hashed with SHA-256; Argon2id stays for passwords

Refresh and reset tokens are 256-bit CSPRNG values. They are stored as SHA-256 hashes and
compared in constant time (`api/src/auth/auth-token.ts`). Argon2id (`ADR-003` D7) remains
the password scheme: it exists to slow down guessing of low-entropy human secrets, and
would add cost per verification without adding protection against a 256-bit random value.
Because hashes are stored, `token_hash` is globally `UNIQUE` so a token identifies exactly
one row.

### D4 — Refresh tokens are rotated; history is preserved

Refreshing revokes the presented token (`revoked_at`) and inserts a successor linked by
`replaced_by_token_id`, rather than overwriting a single token per session. Presenting an
already-rotated token therefore remains detectable instead of being indistinguishable from
a valid one.

### D5 — `replaced_by_token_id` uses `ON DELETE SET NULL`

The self-reference is not `CASCADE`: removing a successor must not erase the predecessor's
audit trail. `SET NULL` keeps the rotation history intact and is verified by test.

### D6 — Revocation and expiry are recorded, not deleted

Revoking sets `revoked_at`; expiry simply passes. "Was this session revoked, and when"
stays answerable (`BR-033`), and no cleanup policy is smuggled into the schema. Retention
and pruning remain open product questions.

### D7 — `platform` is a `VARCHAR` + `CHECK` code

Consistent with `ADR-003` D3. `ANDROID` and `WEB` mirror `BR-010`/`BR-016` and are exported
as `AUTH_SESSION_PLATFORMS`; a future platform is a deliberate schema change, and no
display label is ever stored (`Project.md` §10).

### D8 — The partial active-session index encodes revocation, never time

`auth_sessions_active_idx` is `(user_id) WHERE revoked_at IS NULL`. `now()` is not
immutable (it is re-evaluated per statement), so PostgreSQL rejects it inside an index
predicate, and a predicate containing it would silently stop matching rows as time passes.
Expiry is therefore always compared at query time (`expires_at > now()`), and the index
encodes only the revocation filter. `api/test/auth-foundation.e2e-spec.ts` ("index
integrity") asserts both properties.

### D9 — Lifetimes are configuration; rows store absolute instants

`ACCESS_TOKEN_LIFETIME` (default `15m`), `SESSION_LIFETIME` (default `30d`) and
`PASSWORD_RESET_TOKEN_LIFETIME` (default `30m`) are parsed by `parseDuration`
(`api/src/config/duration.ts`) and validated by `loadAuthConfig`
(`api/src/auth/auth-config.ts`), which fails startup on an unusable value instead of
silently falling back. Tables persist `timestamptz` instants only, so changing
configuration never retroactively reinterprets existing rows.

### D10 — Password reset tokens are per-user and single-use through `used_at`

There is no `UNIQUE(user_id)`: a user may legitimately request a reset more than once, and
the latest request must not invalidate an older but still-pending one. `UNIQUE(token_hash)`
identifies the row; redemption sets `used_at` rather than deleting, so a replay attempt is
distinguishable from an unknown token and is auditable (`BR-033`).

### D11 — This slice adds persistence only, no authentication endpoints

No route is added under `/auth`, and no request/response contract is invented for sign-in,
refresh, sign-out or reset. `BR-019` explicitly leaves the OTP rules open, and the
sign-in response shape is a product decision; `IssuedAuthTokens`
(`api/src/auth/auth.types.ts`) describes only the pair a future endpoint must return,
without committing to an access-token format.

### D12 — The Android mirror is added without the Gradle foundation

`AuthSession.kt` and `AuthTokens.kt` mirror the wire contracts in
`android/.../domain/model/`, next to the models added by `ADR-003` D10. The tree still has
no Gradle project, so they are not compiled and no Android QA applies yet.

## Consequences

- Sign-in, refresh, sign-out and password reset can be implemented as a later slice without
  schema changes: the persistence contract is fixed and covered by integration tests.
- Revocation is per device and per session, which is what `BR-013`/`BR-014` need: the
  durable offline identity of the technician is the *session*, not a short-lived access
  token, so pending field work is not invalidated by access-token expiry.
- A stolen database yields neither passwords (Argon2id, `ADR-003` D7) nor usable tokens
  (SHA-256 hashes only).
- Revoked and expired rows accumulate until a retention policy is decided (`BR-027`,
  `BR-033`). That is deliberately not implemented, and the schema does not pretend to
  answer it.
- The next slice must define the `/auth` contracts against these tables and re-verify
  authorization at the API boundary (`qa.md` §4.3).
