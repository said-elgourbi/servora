# Servora API — `/auth` Contract

**Status: IMPLEMENTED** (approved by the product owner, 2026-09-10) for `POST /auth/sign-in`,
`POST /auth/refresh`, `POST /auth/sign-out` and `GET /auth/sessions`. Items listed in §3.5 are
still decisions, not behaviour. This is the slice named in
`docs/tracker/004-authentication-domain-model.md` §Next ("`/auth` endpoints … built against
these tables, with `401`/`403`/authorization coverage at the API boundary") and required by
`docs/decisions/004-authentication-domain-model.md` ("the next slice must define the `/auth`
contracts against these tables").

References: `ADR-004`, `docs/domain/authentication-domain-model.md`, `BR-006`, `BR-007`,
`BR-018`, `BR-019`, `qa.md` §4.3, `dev.md` §7.

## 1. Scope of this slice

| Concern | Decision | Source |
| --- | --- | --- |
| Email/password sign-in | In scope | `BR-018` CONFIRMED |
| Session issue / refresh / revoke | In scope | `ADR-004` D2, D4 |
| Session listing for the caller | In scope | tracker 004 §Next |
| `401` / `403` + authorization at the boundary | In scope | `qa.md` §4.3 |
| SMS/OTP | **Port only** — `SmsProvider` behind `SMS_PROVIDER`, bound to a no-op implementation | `BR-019` OPEN QUESTION, `ADR-005` |
| Password reset endpoints | **Out of scope** | `BR-019`, `BR-029` OPEN QUESTION |
| Organization scope / membership checks in the guard | **Out of scope** (next slice) | `BR-039` OPEN QUESTION |
| Retention/pruning of sessions and tokens | **Out of scope** | `ADR-004` D6 |

## 2. Conventions this slice must establish

The repository currently has **no HTTP feature layer** (only `src/health/health.controller.ts`),
no `API.md`, no `docs/api/` and no error-envelope or validation mechanism in place. Because
these become cross-client contracts (`dev.md` §8), each is a decision, not an implementation
detail:

| # | Missing convention | Consequence if left implicit |
| --- | --- | --- |
| C1 | **Resolved** — Nest-style envelope `{ statusCode, code, message }`; codes in `AUTH_ERROR_CODES` (`api/src/auth/auth-error.ts`) | Clients branch on `code`, never on `message` |
| C2 | **Resolved** — hand-written parsers in `api/src/auth/auth-request.dto.ts`; no validation dependency added | Rejections are `400 VALIDATION_FAILED` naming the field only |
| C3 | **Resolved** — HS256 JWT via `jose` (`api/src/auth/access-token.ts`); `JWT_SECRET` required, ≥ 32 characters, no fallback | See §5 |
| C4 | **Resolved** — no path prefix (`docs/versioning.md` §7: do not add `/api/v1…`) | Routes are `/auth/…` |

## 3. Endpoints (proposed)

All payloads are JSON, `camelCase`, UUID identifiers, ISO-8601 UTC timestamps
(`Project.md` §15). Access tokens are presented as `Authorization: Bearer <accessToken>`.

### 3.1 `POST /auth/sign-in` — public

Request:
```json
{ "email": "user@example.com", "password": "…",
  "device": { "platform": "ANDROID", "deviceId": "…", "deviceName": "Pixel 8", "appVersion": "1.0.0" } }
```

Success `200`:
```json
{ "sessionId": "uuid", "accessToken": "…", "accessTokenExpiresAt": "2026-09-10T10:15:00.000Z", "refreshToken": "…" }
```

| Status | Code | When |
| --- | --- | --- |
| `400` | `VALIDATION_FAILED` | Missing/malformed field |
| `401` | `INVALID_CREDENTIALS` | Unknown email **or** wrong password **or** a non-`ACTIVE` account (identical body and cost — no account enumeration) |

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

| Status | Code | When |
| --- | --- | --- |
| `400` | `VALIDATION_FAILED` | Missing token |
| `401` | `REFRESH_TOKEN_INVALID` | Unknown, expired, revoked or reused token; or the session is revoked/expired |

**Open (must be confirmed):** whether presenting an already-rotated token must additionally
revoke the whole session family (reuse detection). `ADR-004` D4 makes reuse *detectable* but
does not state the response.

### 3.3 `POST /auth/sign-out` — Bearer access token

Success `204` (no body). Behaviour: sets `revoked_at = now()` on the session identified by
the token's `sid`; that session's refresh tokens become unusable because the session itself is
inactive (`ADR-004` D6 — rows are recorded, never deleted).

| Status | Code | When |
| --- | --- | --- |
| `401` | `UNAUTHENTICATED` | Missing/invalid/expired access token, or its session is already inactive |

### 3.4 `GET /auth/sessions` — Bearer access token

Success `200`: array of `AuthSessionDto` (`src/auth/auth.dto.ts`) for the caller's **own**
user, active sessions first, selected from the `auth_sessions_active_idx` predicate plus
`expires_at > now()` (`ADR-004` D8). No other user's session is ever readable.

### 3.5 Decision required — endpoints deliberately not specified

| Candidate | Why it is not specified here |
| --- | --- |
| `GET /auth/me` | Permission-driven UI (`BR-011`) needs the caller's permissions, but the permission-representation contract is not defined yet (`BR-006`), and the tracker's endpoint list does not include it |
| `POST /auth/sign-out-all` | Not named in the tracker; revoking other devices' sessions needs its own confirmation |
| Max concurrent sessions / revoke-oldest-on-sign-in | Explicitly open in the tracker and domain §12 |

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
*who* and *which session*, never that the session is still active.

## 6. Verification (executed 2026-09-10)

| Level | Result |
| --- | --- |
| API unit (Vitest) | PASS — 129 tests / 16 files |
| API e2e (Vitest + PostgreSQL) | PASS — 59 tests / 5 files (`test/auth-endpoints.e2e-spec.ts` adds 14) |
| API typecheck / lint / build | PASS — 0 type errors; oxlint 0 warnings, 0 errors (64 files); `nest build` |
| `JWT_SECRET` for e2e | Supplied by `vitest.config.e2e.ts` (`test.env`), so the suite no longer depends on a developer's `.env` |
| NOT RUN | Angular and Android — neither consumes `/auth` yet |

Coverage delivered:

| Level | Coverage |
| --- | --- |
| Unit | DTO validation; the service's rotation/reuse/session-active branches against a stubbed db |
| API e2e (`qa.md` §4.3) | `401` unauthenticated on `GET /auth/sessions` and `POST /auth/sign-out`; `401 INVALID_CREDENTIALS` for unknown email and for wrong password (identical bodies); sign-in → refresh rotation (old token rejected, `replaced_by_token_id` set); revoked session rejected immediately; refresh after revoke rejected; rows retained after sign-out (`revoked_at` set, not deleted); no hash and no raw refresh token in any response |
| Negative | Malformed JSON → `400`; expired access token → `401`; session-list isolation between two users |
## 7. Sign-in hardening and the SMS delivery port (implemented 2026-09-10)

`ADR-005` extends §3.1 and adds the port that `BR-019` will need. No OTP endpoint is
specified by this section.

| Behaviour | Detail |
| --- | --- |
| Account eligibility | Only an `ACTIVE` account signs in (`canUserSignIn`, `api/src/users/user-status-rules.ts`); `INACTIVE` and `SUSPENDED` are refused with the same `401 INVALID_CREDENTIALS` as a wrong password |
| Rejection cost | Every rejected attempt runs exactly one Argon2id verification, against `DUMMY_PASSWORD_HASH` (`api/src/users/password-hasher.ts`) when the address is unknown, so unknown email, wrong password and refused account are indistinguishable in body and timing |
| SMS delivery | `SmsProvider.send({ to, body })` behind the `SMS_PROVIDER` token, bound in `SmsModule` (`api/src/sms/`); the bound implementation delivers, retains and logs nothing |
| Deferred | OTP code format, expiry, single use, attempt limits, purpose/context, resend and rate limits, phone-number lifecycle, delivery reporting and provider selection (`BR-019` OPEN QUESTION, `ADR-005` D6) |

