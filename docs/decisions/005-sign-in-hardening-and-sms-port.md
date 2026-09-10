# ADR-005 — Sign-in hardening and the SMS delivery port

**Status: Accepted** (product owner decision, 2026-09-10)

Supersedes nothing. Extends `ADR-004` (authentication domain model) and the `/auth` slice
described in `docs/api/authentication.md`.

References: `BR-006`, `BR-007`, `BR-018`, `BR-019`, `BR-042`, `dev.md` §7 and §11,
`qa.md` §4.3 and §13.

## Context

The `/auth` slice implemented `POST /auth/sign-in` against `users`, `auth_sessions` and
`auth_refresh_tokens`. Three gaps remained inside that slice's own boundary:

1. `users.password_hash` was verified, but `users.status` was never consulted. The reason
   recorded in the code was honest: no rule decided which account states may sign in, so
   enforcing one would have invented business behaviour (`BR-042`). The effect was that an
   `INACTIVE` or `SUSPENDED` account could authenticate normally.
2. Sign-in returned early when the email address was unknown, so no Argon2id verification
   ran on that path. The response body was already identical to a wrong password, but the
   response *time* was not: an attacker could ask "is this address registered?" by
   measuring it.
3. `BR-019` makes SMS/OTP an authentication method, with provider selection still open. If
   the authentication domain called a provider SDK directly, choosing or replacing a
   provider would change the domain.

The product owner decided, on 2026-09-10, that the authentication domain must be ready for
SMS/OTP through a provider-neutral port, and that sign-in must enforce account status
without revealing account state.

## Decisions

### D1 — Only `ACTIVE` accounts sign in

`INACTIVE` and `SUSPENDED` accounts are refused at sign-in, whatever the password. This
applies to every password based authentication method, because eligibility is about the
account, not the credential.

### D2 — A refused account is reported as an invalid credential

The refusal returns the same `401` and the same stable code `INVALID_CREDENTIALS` as a wrong
password. No new error code, message or status is introduced: a distinct response for a
suspended account would tell an unauthenticated caller that the address exists, and the
existing error vocabulary was already sufficient (`BR-007`, `BR-042`).

### D3 — Every rejected attempt costs exactly one Argon2id verification

When the email address is unknown, verification runs against `DUMMY_PASSWORD_HASH`
(`api/src/users/password-hasher.ts`) instead of being skipped. The constant is a real
Argon2id hash produced with the same `ARGON2ID_OPTIONS` as stored passwords
(`m=19456, t=2, p=1`), verified against a value no caller can supply, so it always fails
while costing the same work. It protects no account and contains no plaintext, so it is not
a secret.

Unknown email, wrong password and refused account are therefore one outcome with one cost:
the response body and the response time both stop being an account oracle.

### D4 — Eligibility is one pure function

The rule lives in `canUserSignIn` (`api/src/users/user-status-rules.ts`), a pure function
over the status vocabulary, rather than as an inline condition in `AuthService`. A new
status must be given a deliberate decision: the specification asserts the eligible subset of
`USER_STATUSES` explicitly, so adding a status without deciding its sign-in behaviour fails
a test instead of silently gaining access.

### D5 — SMS is a provider-neutral port, not a provider

`SmsProvider` (`api/src/sms/sms-provider.ts`) is the smallest thing that can send a message:

```
send(message: { to: string; body: string }): Promise<void>
```

The dependency direction is `Authentication -> SmsProvider -> provider implementation`, and
only an implementation may reference a provider SDK. The bound implementation is selected by
the `SMS_PROVIDER` token inside `SmsModule`, so replacing a provider is a change to that
module, never to the authentication domain.

### D6 — No OTP flow is invented

`BR-019` leaves the OTP rules open: code format and length, generation, storage, expiry,
single use, attempt limits, purpose/context, resend and rate limits, phone-number lifecycle
and delivery reporting are all undecided. None of them is implemented, and no OTP endpoint
is specified. The port carries a message; it does not define a flow.

### D7 — The bound implementation is a no-op

`NoOpSmsProvider` accepts messages and delivers nothing. It retains nothing and logs
nothing, including the recipient and the body, because a body may contain a one-time code.
It exists so the port is resolvable and testable before provider selection; it is not a
production delivery path, and a real provider must be bound before any OTP flow ships.

## Consequences

- An inactive or suspended account can no longer authenticate, and cannot be distinguished
  from an unknown address or a wrong password by the caller.
- Sign-in costs one Argon2id verification on every rejected attempt, including unknown
  addresses. This is deliberate: the cost matches a legitimate failed attempt and stays
  bounded by the existing Argon2id parameters.
- Provider selection is now a module and configuration concern. No provider credentials,
  account identifiers or SDK dependencies exist in the repository yet, so no configuration
  contract is added either.
- The authentication domain still has no dependency on any SMS provider, and `BR-019`
  remains OPEN QUESTION for the flow rules.
- No client is affected yet: neither Android nor Angular calls `/auth/sign-in` (no client
  references the route), so this hardening is backend-only and changes no client contract.
  A future client must treat `INVALID_CREDENTIALS` as the single rejection for an unknown
  email, a wrong password and a non-`ACTIVE` account.

## Deferred decisions

| Decision | Why it is deferred | Reference |
| --- | --- | --- |
| SMS provider selection and its configuration | No provider is approved | `BR-019` |
| OTP code format, length, expiry, single use, attempt limits | Not specified by `BR-019` | `BR-019` |
| OTP purpose/context, resend and rate limiting | Not specified by `BR-019` | `BR-019` |
| Phone-number lifecycle (verification, change, reuse) | Not specified by `BR-019` | `BR-019` |
| Delivery reporting and provider webhooks | Not specified by `BR-019` | `BR-019` |
| Who may set `users.status`, and how status changes are audited | Not specified | `BR-004`, `BR-033` |
| Notification behaviour when an account is refused | `BR-029` remains open | `BR-029` |

## Verification (executed 2026-09-10, API on the host with PostgreSQL on port 5434)

| Check | Command | Result |
| --- | --- | --- |
| Type checking | `npm run typecheck` | PASS |
| API unit tests | `npm run test` | PASS |
| API E2E (full suite) | `npm run test:e2e` | PASS |
| Lint | `npm run lint` | PASS |
| Formatting of touched files | `npx prettier --check` | PASS |

New coverage: sign-in eligibility per status, every status in `USER_STATUSES` decided, the
dummy hash never matching and carrying the same Argon2id parameters as a stored hash, the
`SMS_PROVIDER` binding resolving to the no-op implementation, and an E2E case that creates
an `INACTIVE` and a `SUSPENDED` account with a correct password, asserts the stored status,
asserts `401 INVALID_CREDENTIALS`, and asserts that no session row was created.

Not verified: no client behaviour. Neither Android nor Angular calls `/auth/sign-in` yet, so
there is nothing to exercise in a running client; physical-device QA belongs to the product
owner (`qa.md` §7).
