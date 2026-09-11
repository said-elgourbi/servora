# Servora API — `/auth` Contract

**Status: IMPLEMENTED** (approved by the product owner, 2026-09-10) for `POST /auth/sign-in`,
`POST /auth/refresh`, `POST /auth/sign-out`, `GET /auth/sessions` and — added 2026-09-10 —
`POST /auth/password-reset/request`, `POST /auth/password-reset/verify`,
`POST /auth/password-reset/complete`, `POST /auth/sms/request` and `POST /auth/sms/verify`.
Items listed in §3.5 are
still decisions, not behaviour. This is the slice named in
`docs/tracker/004-authentication-domain-model.md` §Next ("`/auth` endpoints … built against
these tables, with `401`/`403`/authorization coverage at the API boundary") and required by
`docs/decisions/004-authentication-domain-model.md` ("the next slice must define the `/auth`
contracts against these tables").

References: `ADR-004`, `ADR-005`, `ADR-006`, `docs/domain/authentication-domain-model.md`,
`BR-006`, `BR-007`, `BR-018`, `BR-019`, `BR-043`, `BR-044`, `BR-045`, `BR-046`, `qa.md` §4.3,
`dev.md` §7.

## 1. Scope of this slice

| Concern                                             | Decision                          | Source                                                           |
| --------------------------------------------------- | --------------------------------- | ---------------------------------------------------------------- |
| Email/password sign-in                              | In scope                          | `BR-018` CONFIRMED                                               |
| Session issue / refresh / revoke                    | In scope                          | `ADR-004` D2, D4                                                 |
| Session listing for the caller                      | In scope                          | tracker 004 §Next                                                |
| `401` / `403` + authorization at the boundary       | In scope                          | `qa.md` §4.3                                                     |
| Phone/SMS sign-in                                   | In scope — implemented 2026-09-10 | `BR-019` CONFIRMED, `ADR-006`                                    |
| Password reset                                      | In scope — implemented 2026-09-10 | `BR-043` CONFIRMED, `ADR-006`                                    |
| Reset delivery transport (email provider)           | In scope — implemented 2026-09-10 | `BR-043` CONFIRMED, `ADR-008`                                    |
| Organization scope / membership checks in the guard | Out of scope for auth endpoints   | `BR-039` confirmed; owner/admin workflows are later Angular work |
| Retention/pruning of sessions and tokens            | **Out of scope**                  | `ADR-004` D6                                                     |

## 2. Conventions this slice must establish

The repository currently has **no HTTP feature layer** (only `src/health/health.controller.ts`),
no `API.md`, no `docs/api/` and no error-envelope or validation mechanism in place. Because
these become cross-client contracts (`dev.md` §8), each is a decision, not an implementation
detail:

| #   | Missing convention                                                                                                             | Consequence if left implicit                                 |
| --- | ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------ |
| C1  | **Resolved** — Nest-style envelope `{ statusCode, code, message }`; codes in `AUTH_ERROR_CODES` (`api/src/auth/auth-error.ts`) | Clients branch on `code`, never on `message`                 |
| C2  | **Resolved** — hand-written parsers in `api/src/auth/auth-request.dto.ts`; no validation dependency added                      | Rejections are `400 VALIDATION_FAILED` naming the field only |
| C3  | **Resolved** — HS256 JWT via `jose` (`api/src/auth/access-token.ts`); `JWT_SECRET` required, ≥ 32 characters, no fallback      | See §5                                                       |
| C4  | **Resolved** — no path prefix (`docs/versioning.md` §7: do not add `/api/v1…`)                                                 | Routes are `/auth/…`                                         |

## 3. Endpoints (proposed)

All payloads are JSON, `camelCase`, UUID identifiers, ISO-8601 UTC timestamps
(`Project.md` §15). Access tokens are presented as `Authorization: Bearer <accessToken>`.

### 3.1 `POST /auth/sign-in` — public

Request:

```json
{
  "email": "user@example.com",
  "password": "…",
  "device": {
    "platform": "ANDROID",
    "deviceId": "…",
    "deviceName": "Pixel 8",
    "appVersion": "1.0.0"
  }
}
```

Success `200`:

```json
{
  "sessionId": "uuid",
  "accessToken": "…",
  "accessTokenExpiresAt": "2026-09-10T10:15:00.000Z",
  "refreshToken": "…"
}
```

| Status | Code                  | When                                                                                                                 |
| ------ | --------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`   | Missing/malformed field                                                                                              |
| `401`  | `INVALID_CREDENTIALS` | Unknown email **or** wrong password **or** a non-`ACTIVE` account (identical body and cost — no account enumeration) |

Behaviour: verifies `users.password_hash` with Argon2id (`ADR-003` D7), inserts one
`auth_sessions` row (client context, `expires_at = now + SESSION_LIFETIME`) and one
`auth_refresh_tokens` row storing only `hashOpaqueToken(refreshToken)`. The raw refresh
token appears in this response and is never persisted or logged (`ADR-004` D3).

Eligibility: only an `ACTIVE` account signs in (`canUserSignIn`,
`api/src/users/user-status-rules.ts`). Every rejected attempt runs exactly one Argon2id
verification, against `DUMMY_PASSWORD_HASH` when the address is unknown, so an unknown
address, a wrong password and a refused account are indistinguishable in body and cost
(`ADR-005` D1-D3).

### 3.2 `POST /auth/refresh` — public (the refresh token is the credential)

Request: `{ "refreshToken": "…" }`

Success `200`: the same body as sign-in, with a new token pair and the same `sessionId`.

Behaviour: lookup by `hashOpaqueToken`, constant-time compare (`auth-token.ts`), then require
(a) token not revoked, (b) token not expired, (c) bound session active per `isSessionActive`.
Rotation revokes the presented token and inserts its successor linked by
`replaced_by_token_id` (`ADR-004` D4, D5).

| Status | Code                    | When                                                                         |
| ------ | ----------------------- | ---------------------------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`     | Missing token                                                                |
| `401`  | `REFRESH_TOKEN_INVALID` | Unknown, expired, revoked or reused token; or the session is revoked/expired |

**Open (must be confirmed):** whether presenting an already-rotated token must additionally
revoke the whole session family (reuse detection). `ADR-004` D4 makes reuse _detectable_ but
does not state the response.

### 3.3 `POST /auth/sign-out` — Bearer access token

Success `204` (no body). Behaviour: sets `revoked_at = now()` on the session identified by
the token's `sid`; that session's refresh tokens become unusable because the session itself is
inactive (`ADR-004` D6 — rows are recorded, never deleted).

| Status | Code              | When                                                                     |
| ------ | ----------------- | ------------------------------------------------------------------------ |
| `401`  | `UNAUTHENTICATED` | Missing/invalid/expired access token, or its session is already inactive |

### 3.4 `GET /auth/sessions` — Bearer access token

Success `200`: array of `AuthSessionDto` (`src/auth/auth.dto.ts`) for the caller's **own**
user, active sessions first, selected from the `auth_sessions_active_idx` predicate plus
`expires_at > now()` (`ADR-004` D8). No other user's session is ever readable.

### 3.5 Decision required — endpoints deliberately not specified

| Candidate                                          | Why it is not specified here                                                                                                                                                       |
| -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /auth/me`                                     | Permission-driven UI (`BR-011`) needs the caller's effective permissions; the role/permission persistence model exists, but this auth slice has not specified the endpoint payload |
| `POST /auth/sign-out-all`                          | Not named in the tracker; revoking other devices' sessions needs its own confirmation                                                                                              |
| Max concurrent sessions / revoke-oldest-on-sign-in | Explicitly open in the tracker and domain §12                                                                                                                                      |

### 3.6 `POST /auth/password-reset/request` — public

Request:

```json
{ "email": "user@example.com" }
```

Success `202` with **no body**. Behaviour: records the attempt in the rate-limit ledger
(`BR-045`), resolves the account, and — only when an account exists — supersedes its outstanding
reset credential and delivers a new one through `PasswordResetNotifier`.

| Status | Code                | When                                            |
| ------ | ------------------- | ----------------------------------------------- |
| `400`  | `VALIDATION_FAILED` | The body is not an object with an email address |
| `429`  | `TOO_MANY_REQUESTS` | An approved limit is reached (`BR-045`)         |
| `202`  | —                   | Every other case                                |

`202` is the answer whether or not the address belongs to an account, and whether or not
delivery succeeded, so the status and body disclose nothing (`BR-044`). An unknown address is
still counted by the rate limiter, so a `429` cannot be used to enumerate either.

The delivery transport is selected by `PASSWORD_RESET_DELIVERY` (`noop`, `file`, `email`).
`email` binds `EmailPasswordResetNotifier` over the `EMAIL_PROVIDER` port (`ADR-008`); a provider
rejection is logged and contained so the caller still receives the same `202` and the same body,
while any other failure keeps surfacing through the normal error path.

### 3.7 `POST /auth/password-reset/verify` — public

Request: `{ "email": "user@example.com", "code": "123456" }`

Success `204` (no body). Behaviour: checks the outstanding credential without consuming it, so a
client can advance to the new-password step. A failed attempt increments the credential's
attempt counter, so the five-attempt limit covers the whole flow rather than each endpoint.

| Status | Code                 | When                                                                                                            |
| ------ | -------------------- | --------------------------------------------------------------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`  | Missing/malformed field, or a code that is not six digits                                                       |
| `401`  | `RESET_CODE_INVALID` | Unknown identity, no outstanding credential, expired, superseded, consumed, attempts exhausted, or a wrong code |

One code and one message cover every rejection reason: distinguishing them would disclose
whether an account exists, and how far a guess got (`BR-044`).

### 3.8 `POST /auth/password-reset/complete` — public

Request: `{ "email": "user@example.com", "code": "123456", "newPassword": "…" }`

Success `204` (no body). Behaviour: checks the credential as in §3.7, hashes `newPassword` with
the existing Argon2id scheme and policy, stores it, and consumes **every** outstanding credential
for the account.

| Status | Code                 | When                                                                                  |
| ------ | -------------------- | ------------------------------------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`  | Missing/malformed field, or a password shorter than the project policy (8 characters) |
| `401`  | `RESET_CODE_INVALID` | Any credential failure, exactly as §3.7                                               |

No session is created: a reset returns the user to normal authentication (`BR-043`). Whether a
reset revokes existing sessions is an **OPEN QUESTION** and nothing here assumes an answer.

### 3.9 `POST /auth/sms/request` — public

Request: `{ "phone": "+15145550100" }` (E.164; the server normalises and validates, `BR-019`)

Success `202` with **no body**. Behaviour: records the attempt in the rate-limit ledger, which
also enforces the 60-second resend cooldown (`BR-019`, `BR-045`); resolves the account; and only
when exactly one `ACTIVE` account holds the number, supersedes the outstanding challenge and
sends an OTP through `SmsProvider`.

| Status | Code                | When                                                  |
| ------ | ------------------- | ----------------------------------------------------- |
| `400`  | `VALIDATION_FAILED` | The body is not an object, or the number is not E.164 |
| `429`  | `TOO_MANY_REQUESTS` | The resend cooldown or an approved limit is reached   |
| `202`  | —                   | Every other case                                      |

`202` covers "no such number", "more than one account holds it", "the account may not sign in",
"the provider rejected the message" and "the message was accepted" — five situations that must be
indistinguishable to the caller (`BR-044`).

### 3.10 `POST /auth/sms/verify` — public

Request:

```json
{
  "phone": "+15145550100",
  "code": "123456",
  "device": {
    "platform": "ANDROID",
    "deviceId": "…",
    "deviceName": "Pixel 8",
    "appVersion": "1.0.0"
  }
}
```

Success `200`: the **same body as §3.1**. A phone verification is a sign-in, not a second kind of
session: it opens one `auth_sessions` row and one `auth_refresh_tokens` row in the same way.

| Status | Code                | When                                                                                                         |
| ------ | ------------------- | ------------------------------------------------------------------------------------------------------------ |
| `400`  | `VALIDATION_FAILED` | Missing/malformed field, a code that is not six digits, or an invalid device block                           |
| `401`  | `OTP_CODE_INVALID`  | Unknown number, no outstanding challenge, expired, superseded, consumed, attempts exhausted, or a wrong code |

One code and one message cover every rejection reason (`BR-044`). Authentication additionally
follows `BR-018`'s eligibility rule: only an `ACTIVE` account signs in.

### 3.11 One-time codes are stored as keyed digests

Both flows persist only
`HMAC-SHA256(JWT_SECRET, "servora-auth-code:v1:" + scope + ":" + ownerId + ":" + code)`
(`api/src/auth/auth-code.ts`), and no API response, log line or error envelope ever carries a
code (`BR-046`, `ADR-006` D1).

## 4. `401` vs `403`

- `401` — no valid access token, or the session behind it is inactive.
- `403` — authenticated but not permitted. **No `/auth` endpoint in this slice performs a
  permission check**: sign-in, refresh and sign-out act on the caller's own credentials and
  the caller's own session. `403` coverage therefore belongs to the first permission-protected
  resource endpoint (`qa.md` §4.3), together with the guard.

## 5. C3 — access-token format (resolved: HS256 JWT)

`ADR-004` D11 states that no access-token format was committed, and domain §12 repeats that it
is undecided. The implementation lives in `api/src/auth/`:

- `access-token.ts` — HS256 JWT via `jose` (`sub` = userId, `sid` = sessionId, `exp`).
- `auth-config.ts` — requires `JWT_SECRET` (minimum 32 characters, no development fallback).

**Decision (2026-09-10): option 1 — HS256 JWT, confirmed by the product owner.** Option 2 is
not pending work; it would only happen as a deliberate, separately approved change.

1. **Confirm HS256 JWT** (simplest, stateless verification, `jose` already added,
   `JWT_SECRET` already documented in `.env.example`), or
2. **Confirm opaque access tokens** (a second hashed credential, no new dependency, requires a
   lookup per request) — then `access-token.ts`, `auth-config.jwtSecret` and the `jose`
   dependency must be removed.

In both designs the session row remains the authority for revocation: a valid token proves
_who_ and _which session_, never that the session is still active.

## 6. Verification (executed 2026-09-10)

| Level                         | Result                                                                                                  |
| ----------------------------- | ------------------------------------------------------------------------------------------------------- |
| API unit (Vitest)             | PASS — 129 tests / 16 files                                                                             |
| API e2e (Vitest + PostgreSQL) | PASS — 59 tests / 5 files (`test/auth-endpoints.e2e-spec.ts` adds 14)                                   |
| API typecheck / lint / build  | PASS — 0 type errors; oxlint 0 warnings, 0 errors (64 files); `nest build`                              |
| `JWT_SECRET` for e2e          | Supplied by `vitest.config.e2e.ts` (`test.env`), so the suite no longer depends on a developer's `.env` |
| NOT RUN                       | Angular and Android — neither consumes `/auth` yet                                                      |

Coverage delivered:

| Level                  | Coverage                                                                                                                                                                                                                                                                                                                                                                                                                         |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Unit                   | DTO validation; the service's rotation/reuse/session-active branches against a stubbed db                                                                                                                                                                                                                                                                                                                                        |
| API e2e (`qa.md` §4.3) | `401` unauthenticated on `GET /auth/sessions` and `POST /auth/sign-out`; `401 INVALID_CREDENTIALS` for unknown email and for wrong password (identical bodies); sign-in → refresh rotation (old token rejected, `replaced_by_token_id` set); revoked session rejected immediately; refresh after revoke rejected; rows retained after sign-out (`revoked_at` set, not deleted); no hash and no raw refresh token in any response |
| Negative               | Malformed JSON → `400`; expired access token → `401`; session-list isolation between two users                                                                                                                                                                                                                                                                                                                                   |

## 7. Sign-in hardening and the SMS delivery port (implemented 2026-09-10)

`ADR-005` extends §3.1 and adds the port that `BR-019` will need. No OTP endpoint is
specified by this section.

| Behaviour           | Detail                                                                                                                                                                                                                                                                                  |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Account eligibility | Only an `ACTIVE` account signs in (`canUserSignIn`, `api/src/users/user-status-rules.ts`); `INACTIVE` and `SUSPENDED` are refused with the same `401 INVALID_CREDENTIALS` as a wrong password                                                                                           |
| Rejection cost      | Every rejected attempt runs exactly one Argon2id verification, against `DUMMY_PASSWORD_HASH` (`api/src/users/password-hasher.ts`) when the address is unknown, so unknown email, wrong password and refused account are indistinguishable in body and timing                            |
| SMS delivery        | `SmsProvider.send({ to, body })` behind the `SMS_PROVIDER` token, bound in `SmsModule` (`api/src/sms/`). `SMS_PROVIDER` selects `noop` (default), `twilio` (Twilio SDK; `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` and `TWILIO_FROM` required at startup, where `TWILIO_FROM` is an E.164 number or an `MG…` Messaging Service SID) or `file` (development sink, refused in production); every binding retains and logs nothing |
| Email delivery      | `EmailProvider.send({ to, subject, text })` behind the `EMAIL_PROVIDER` token, bound in `EmailModule` (`api/src/email/`). `EMAIL_PROVIDER` selects `noop` (default) or `resend` (credentials required at startup); every binding retains and logs nothing                               |
| Deferred            | OTP code format, expiry, single use, attempt limits, purpose/context, resend and rate limits, phone-number lifecycle, delivery reporting and provider selection — **decided 2026-09-10 and implemented**, see §3.6–§3.11 and `ADR-006`                                                  |

## 8. Local QA accounts (development only)

The foundation database starts empty and no user-creation endpoint exists yet, so
`POST /auth/sign-in` cannot be exercised until test data exists. `make seed`
(`api/src/database/run-development-seed.ts`) upserts two `ACTIVE` accounts — one per foundation
role (`BR-003`) — plus their organization membership:

| Account                   | Role         | Credential                                                          |
| ------------------------- | ------------ | ------------------------------------------------------------------- |
| `manager@servora.test`    | `MANAGER`    | `SEED_MANAGER_PASSWORD`, or generated and printed by `make seed`    |
| `technician@servora.test` | `TECHNICIAN` | `SEED_TECHNICIAN_PASSWORD`, or generated and printed by `make seed` |

No password is committed to the repository (`dev.md` §5); the command is idempotent and refuses
to run with `NODE_ENV=production`. See `docs/development/setup.md` §3 for the full dataset and
credential policy.

## 9. The two flows that had no contract — now implemented (2026-09-10)

The approved Figma `Login` export (`Figma/src/screens/Login.tsx`) defines two authentication flows
that previously had **no route, DTO, error code or response envelope**. They are now specified —
see §3.6–§3.11 — because product ownership decided the rules (`BR-019`, `BR-043`, `BR-044`,
`BR-045`, `BR-046`) and `ADR-006` recorded the architectural consequences.

| Flow              | Figma screens                                              | Contract         |
| ----------------- | ---------------------------------------------------------- | ---------------- |
| Forgot password   | `fp-username` → `fp-verify` → `fp-newpassword` → `fp-done` | §3.6, §3.7, §3.8 |
| Phone/SMS sign-in | `login` → `sms-phone` → `sms-otp`                          | §3.9, §3.10      |

Two decisions the prototype could not answer, recorded rather than guessed:

| Decision                 | Outcome                                                                                                                                                                                                                                                                                      | Reference    |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------ |
| Reset credential shape   | A **six-digit code**, as the design shows, persisted only as a keyed digest; the approved rules require attempts-limited brute-force protection, which a 256-bit link token would make meaningless                                                                                           | `ADR-006` D1 |
| Reset delivery transport | **Resolved 2026-09-10.** `PASSWORD_RESET_DELIVERY=email` routes to `EmailPasswordResetNotifier`, which sends the approved bilingual message through the `EMAIL_PROVIDER` port; `EMAIL_PROVIDER` selects `noop` (default) or `resend` (`RESEND_API_KEY` and `EMAIL_FROM` required at startup) | `ADR-008`    |

None of the prototype's placeholder values (`123456`, a 6-character minimum password, a
30-second resend cooldown, a hard-coded `+1` country code) is a specification: the code is
generated by the API, the password policy is the project's 8-character minimum, the cooldown is
60 seconds, and the phone number is sent in E.164.

`POST /auth/sign-in` (§3.1) therefore remains the credential-exchange endpoint it always was, and
is no longer the only one.

## 10. Behaviour the flows share

| Behaviour       | Detail                                                                                                                                                                                                                                                                    |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Non-disclosure  | Every flow answers identically for a known and an unknown identity — same status, same body, same error code, no avoidable timing difference (`BR-044`)                                                                                                                   |
| Rate limiting   | A persisted rolling window (`auth_rate_limit_events`) shared by both flows; the subject is stored as an HMAC, so no email address or phone number is retained (`BR-045`, `ADR-006` D6)                                                                                    |
| Approved limits | reset request 3/15 min and 10/24 h per identity; OTP request 3/15 min and 10/24 h per number; OTP resend cooldown 60 s. All are configuration (`AUTH_RATE_LIMIT_*`, `SMS_OTP_RESEND_COOLDOWN`, `PASSWORD_RESET_MAX_ATTEMPTS`, `SMS_OTP_LIFETIME`, `SMS_OTP_MAX_ATTEMPTS`) |
| Attempt limits  | 5 failed verifications per reset credential (`PASSWORD_RESET_MAX_ATTEMPTS`) and per OTP (`SMS_OTP_MAX_ATTEMPTS`); the counters are persisted, not in memory                                                                                                               |
| Enumeration     | An unknown identity is still counted by the rate limiter, so a `429` discloses nothing (`BR-044`)                                                                                                                                                                         |
