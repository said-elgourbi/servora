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

## Pending flows — resolved and implemented 2026-09-10

Two authentication flows that exist in the approved Figma `Login` export
(`Figma/src/screens/Login.tsx`, 7 screens) have **no implementation in any layer** and are **not
described by any product decision**. They were found by comparing the design against the Android
client and the `/auth` surface, and are recorded here instead of built: `BR-019` and `BR-029` are
OPEN QUESTION and `BR-042` forbids inventing the behaviour (`Project.md` §30).

| Flow | Figma screens | Evidence of the gap | Status |
| --- | --- | --- | --- |
| Forgot password | `fp-username` → `fp-verify` → `fp-newpassword` → `fp-done` | Android `SignInScreen.kt` renders only email + password (no "Forgot password?" affordance); `AuthApi.kt` declares only `POST auth/sign-in`; `docs/api/authentication.md` §1 records "Password reset endpoints — Out of scope" | **IMPLEMENTED** |
| Phone/SMS sign-in | `login` ("Sign in with SMS") → `sms-phone` → `sms-otp` | `SignInScreen.kt` has no SMS affordance; `AuthController` exposes only `sign-in`/`refresh`/`sign-out`/`sessions`; `docs/api/authentication.md` §7 binds the SMS port to a no-op with no flow | **IMPLEMENTED** |

### Implementation (2026-09-10)

Product ownership decided the rules, so both flows are now built as vertical slices rather than
recorded as blocked. Canonical rules: `BR-019` (phone/SMS, rewritten), `BR-043` (password reset),
`BR-044` (non-disclosure), `BR-045` (rate limiting), `BR-046` (code storage). Architecture:
`ADR-006`. Contract: `docs/api/authentication.md` §3.6–§3.11 and §10. Android:
`ui/auth/AuthFlowScreen.kt`, `ui/passwordreset/`, `ui/sms/`, plus a `Forgot your password?` and
a `Sign in with SMS` affordance on the sign-in screen.

| Layer | Slice A — password reset | Slice B — phone/SMS sign-in |
| --- | --- | --- |
| Database | Migration `0002_auth_flows.sql`: `password_reset_tokens.attempts` (+ `>= 0` check, outstanding-row index) | Migration `0002_auth_flows.sql`: `phone_otp_challenges`; `auth_rate_limit_events` |
| API | `POST /auth/password-reset/{request,verify,complete}` | `POST /auth/sms/{request,verify}` |
| Security | Keyed code digest, 30-minute lifetime, single use, 5 attempts, supersession, `202` non-disclosure, persisted rate limiting | 10-minute lifetime, single use, 5 attempts, supersession, 60-second resend cooldown, fail-closed on an ambiguous number, provider failure that stays non-disclosing |
| Ports | `PasswordResetNotifier` (no-op by default, development-only file sink; `PASSWORD_RESET_DELIVERY=email` binds `EmailPasswordResetNotifier` over the `EMAIL_PROVIDER` port — `ADR-008`) | `SmsProvider` selected by `SMS_PROVIDER` (`noop` default, `twilio`, or a development file sink); `FakeSmsProvider` for tests |
| Android | `fp-*` flow as a four-step state machine | `sms-phone` → `sms-otp` as a two-step state machine |
| Localization | EN/FR strings for both flows, and one shared failure vocabulary | Same |

The production email transport, which was on this list, was decided and implemented on 2026-09-10
(`ADR-008`, below).

Not implemented, and left open on purpose: whether a reset revokes existing sessions (`BR-043`
Notes), and how a phone number is first associated with, changed on, or released from an account
(`BR-019` Notes).

#### Verification (2026-09-10, host tooling → PostgreSQL on host port 5434 + Android debug build)

```text
API typecheck (tsc)        PASS  0 errors
API lint (oxlint)          PASS  0 warnings / 0 errors
API unit tests (Vitest)    PASS  230 passed / 27 files
API e2e (Vitest+PG)        PASS  95 passed / 7 files
API build (nest build)     PASS
Migration 0002             PASS  applied by the Drizzle migrator (e2e global setup and container boot)
Android assembleDebug      PASS  app-debug.apk (13.4 MB)
Android testDebugUnitTest  PASS  53 tests / 5 classes, 0 failures
Android lintDebug          PASS
Stack smoke test           PASS  /health 200; reset request (unknown) 202; sms request (unknown) 202;
                                 sms request (malformed number) 400 VALIDATION_FAILED;
                                 reset verify (wrong code) 401 RESET_CODE_INVALID
```

New API coverage: `auth-code`, `phone-number`, `auth-message`, `auth-limits-config`,
`notifications-config`, `sms-config`, `twilio-sms-provider`, `auth-flow-requests` (unit);
`password-reset.e2e-spec.ts` (16) and `sms-authentication.e2e-spec.ts` (18) against real
PostgreSQL, covering non-disclosure, expiry, single use, supersession, attempt limits, throttling,
resend cooldown, ambiguous numbers, provider failure and "no session on failure".

New Android coverage: `DefaultAuthRepositoryFlowTest` (error classification for the new codes),
`PasswordResetViewModelTest` (12) and `SmsSignInViewModelTest` (11) over the flow state machines.

**Not verified: Android physical-device behaviour.** `qa.md` §7 leaves that to the product owner;
the manual checklist is below.

#### Manual Android QA checklist (product owner)

Both flows need a delivery channel locally, because no SMS/email provider is configured. To use
the development sinks, run the API **on the host** (the container runs `NODE_ENV=production`,
where both sinks are refused by design):

```bash
docker compose stop api          # free port 3000
cd api && npm run build
cd api && SMS_PROVIDER=file PASSWORD_RESET_DELIVERY=file npm run start:prod
```

Codes then appear as `to:`/body text files in `api/var/dev-notifications/` (one line per message).

Forgotten password (`fp-username` → `fp-verify` → `fp-newpassword` → `fp-done`):

1. Sign-in screen → **Forgot your password?**
2. Enter the email of an existing account → **Send reset code**.
3. Expected: the code step appears with generic copy ("if an account exists…"), and a reset
   message file appears in `api/var/dev-notifications/`.
4. Enter the 6-digit code → **Verify code** → the new-password step appears.
5. Enter a new password of at least 8 characters → **Save new password** → a confirmation appears.
6. Return to sign-in and sign in with the new password. Expected: it works, and the old password
   is refused.

Phone/SMS sign-in (`login` → `sms-phone` → `sms-otp`):

0. An account must already have the number on file (`BR-019` never creates an account and there is
   no profile screen yet). Give the seeded technician one:
   `make db-shell` then
   `update users set phone = '+15145550100' where email = 'technician@servora.test';`
1. Sign-in screen → **Sign in with SMS**.
2. Enter a phone number in E.164 (`+1514…`) that belongs to an existing `ACTIVE` account →
   **Send code**. Expected: the code step appears, and an SMS file appears in
   `api/var/dev-notifications/`.
3. Enter the 6-digit code → **Verify and sign in**. Expected: signed in.
4. Repeat and enter a wrong code. Expected: a generic "code is not valid" message, no session.
5. Tap **Resend code** twice in a row. Expected: the second attempt reports "too many attempts"
   (the 60-second cooldown).
6. Enter a phone number that has no account. Expected: the same code step, no message file, and
   no way to complete — the screen must not say "no account found".

### The decisions that were open — kept for the audit trail

**Both flows**

- The sign-in identity field. The Figma login screen labels it **Username**
  (`placeholder="your.username"`, errors "Incorrect username or password." and "No account found with
  that username."), but `BR-018` defines email/password, `users` has no `username` column and
  `POST /auth/sign-in` takes `email`. Deciding whether sign-in is by email, by username, or by either
  is a prerequisite for both flows.
- Whether these code-based flows are rate limited, and on which axis (per account, per phone, per
  device, per IP).
- Notification behaviour for a security-relevant message (`BR-029` remains open).

**Forgot password**

- The identity a reset is requested with, and whether the response reveals whether an account exists
  (sign-in is deliberately non-enumerating per `ADR-005` D1–D3).
- The delivery channel. The prototype states the reset code is sent **by email** ("A 6-digit reset
  code was sent to the email on file for …", line 507), but no email/notification delivery port
  exists in `api/src` — only `SmsProvider`. A channel, and a port to carry it, must be decided.
- The reset credential's shape. `ADR-004` D10 persists a 256-bit CSPRNG
  `password_reset_tokens.token_hash` with `PASSWORD_RESET_TOKEN_LIFETIME` (default `30m`); the
  prototype verifies a **6-digit code**. Those are different credentials with different entropy,
  attempt-limit and throttling needs, so the stored token, its lifetime and its `used_at` single-use
  semantics must be confirmed or revised — the answer changes the table's compatibility.
- Password policy for the new password: the prototype enforces **6 characters** while
  `MIN_PASSWORD_LENGTH` (`api/src/validation/domain-validation.ts`) is **8**. The Figma figure is
  prototype copy, not a rule.
- Whether an existing session is revoked when a password is reset.
- The complete error vocabulary (unknown identity, expired code, wrong code, too many attempts,
  reused code, weak password) and its EN/FR copy.

**Phone/SMS sign-in**

- Phone-number handling: E.164 normalisation, the country code the design hard-codes as `+1`
  (NANP only, or international?), and the minimum-length check the prototype applies (10 digits).
- Whether `users.phone` (nullable, `varchar(50)`, **not unique**) is the identifier, and its
  lifecycle: who may set it, how it is verified and changed, how it is released, and whether two
  accounts may share a number.
- OTP rules in full: code format and length (the prototype uses 6 digits), generation, storage
  (hashed?), expiry, single use, maximum attempts, resend and rate limits, purpose/context.
- Provider selection, provider configuration and delivery reporting (`ADR-005` D5–D7).
- Whether SMS sign-in issues the same session/refresh-token pair as `POST /auth/sign-in`.
- The complete error vocabulary and EN/FR copy, including the "expired code" and "resend in Ns"
  states the prototype shows.

### Prototype values that are not rules

Read from `Figma/src/screens/Login.tsx`. Every one of these is placeholder code in a design
prototype and must not be copied into the implementation as behaviour:

| Prototype value | Line | Why it is not a rule |
| --- | --- | --- |
| `otp !== '123456'`, `fpCode !== '123456'` | 165, 189 | Hard-coded stub standing in for a verification call |
| `password.length < 6`, `fpNewPw.length < 6` | 148, 195 | Conflicts with `MIN_PASSWORD_LENGTH = 8` |
| `setResendCooldown(30)` | 157 | A UI delay, not an approved resend policy |
| `+1` prefix, `555-123-4567` placeholder, `digits.length < 10` | 367, 374, 154 | NANP-shaped placeholder; no rule chooses a country |

## Transactional email delivery — implemented 2026-09-10 (`ADR-008`)

The production email transport that `ADR-006` D3 left open is decided and implemented. It is a
transport change only: the 6-digit code, its digest storage, the supersession/attempt/rate rules
and the non-disclosing `202` are untouched.

| Layer | Change |
| --- | --- |
| Configuration | `EMAIL_PROVIDER` (`noop` default, `resend`), `RESEND_API_KEY`, `EMAIL_FROM` (`api/src/email/email-config.ts`) |
| Port | `EmailProvider` + `EMAIL_PROVIDER` token + `EmailDeliveryError` (`api/src/email/email-provider.ts`) |
| Providers | `NoOpEmailProvider` (default), `ResendEmailProvider` (direct HTTP `POST api.resend.com/emails`, injected `FetchLike`, 10s timeout, no vendor SDK), `FakeEmailProvider` (tests) |
| Notifications | `PASSWORD_RESET_DELIVERY=email` binds `EmailPasswordResetNotifier`, which maps the already-localized message onto `OutboundEmailMessage` |
| Auth | `PasswordResetService.deliverResetMessage` logs and swallows `EmailDeliveryError` only — a provider rejection still answers the documented `202` (`BR-044`), and nothing about the message is logged (`BR-046`) |

`EMAIL_PROVIDER=resend` without `RESEND_API_KEY`/`EMAIL_FROM` fails at startup. Verifying the
sending domain in Resend is operational, not code; retry/backoff, bounce handling and any other
email type stay deferred (`ADR-008` D6).

### Verification

```text
API unit (Vitest)               PASS  31 files, 248 tests  (email + notifications: 5 files, 22 tests)
API e2e (Vitest + PostgreSQL)   PASS   7 files,  97 tests  (password-reset.e2e-spec.ts: 18 tests)
API lint (oxlint)               PASS   0 warnings, 0 errors
API typecheck (tsc --noEmit)    PASS
API build (nest build)          PASS
Prettier (email, notifications) PASS
```

`prettier --check` still reports pre-existing drift in `api/src` and `api/test` files outside this
slice (for example `src/customers/`, `src/health/`, `src/database/schema.ts`). Those files were
left untouched (`dev.md` §3, no unrelated changes); this slice's own files are formatted.

Not verified: delivery against a live Resend account — no production credential exists in this
environment.

## Keyboard dismissal and field focus (2026-09-10)

`ClearFocusWhenImeHidden` (`ui/auth`) releases focus from the focused field when the keyboard goes
away, on every authentication screen with text input (`SignInScreen`, `SmsSignInScreen`,
`PasswordResetScreen`). Material derives the label and placeholder appearance from focus, so a field
left focused after the keyboard was dismissed kept its floated label and a visible placeholder even
though the layout had already returned to its resting position. The fix is therefore a focus-state
fix: no padding, offset or spacing value is involved anywhere.

- Verified on the reference device (Pixel 10, Android 17) with a witness table of every semantic
  node carrying text or a content description, compared across baseline → keyboard shown → after the
  system back key dismissed the keyboard. Post-fix results: `max |after - base| = 0` in every
  measured regime (native 1080x2424 and overflow 1080x1000, email and password fields) and no field
  left focused. The same measurement before the fix showed a 66px residual (the 24dp floated label)
  inside the focused field.
- Automated cover: `app/src/androidTest/java/com/servora/android/ui/auth/ClearFocusWhenImeHiddenTest.kt`
  (8 Compose UI tests) — focus and label state after a dismissal, the guard that a field the keyboard
  never appeared for keeps its focus, repeated open/dismiss cycles, the sign-in, SMS and
  password-reset screens, and the password field's Done action. Run it with
  `cd android && ./gradlew connectedDebugAndroidTest`, or directly with
  `adb shell am instrument -w -e class com.servora.android.ui.auth.ClearFocusWhenImeHiddenTest com.servora.android.debug.test/androidx.test.runner.AndroidJUnitRunner`.
  Force-stop the debug package (and press Home) before a run: the suite is sensitive to device state
  and can otherwise fail with `No compose hierarchies found in the app` while an activity is being
  torn down.
- Known gap carried forward, not changed here: tapping outside a field does not dismiss the keyboard
  on these screens at all (the surrounding surface does not clear focus), so that dismissal path does
  not exist yet and needs its own product decision.


## Confirmation entry and the completion step (2026-09-11)

The reset form now asks for the new password twice. The pair is compared on the client, so a typo
never reaches `POST /auth/password-reset/confirm`; a mismatch is reported on the confirmation field
(`PasswordResetFieldError.CONFIRM_PASSWORD`) and **both** entries are kept, so the user corrects the
mistake instead of retyping it. Two new strings carry the copy in both languages
(`password_reset_confirm_password_label`, `password_reset_error_passwords_do_not_match`, in
`values/strings.xml` and `values-fr/strings.xml`).

Two smaller corrections shipped with it:

- **Returning to sign-in clears the attempt.** `AuthFlowScreen` calls `PasswordResetViewModel.reset()`
  when it shows the sign-in destination, so an abandoned attempt no longer leaves its email, code or
  password in the state the next user of the device would see, and a response still in flight is
  discarded through the same `attemptId` guard the other attempts use.
- **One way back from the completion step.** `PasswordResetScreen` rendered the primary action for
  every step, so `DONE` showed "Back to sign in" twice — once as the button, once as the footer link.
  The primary action is now gated on steps that actually submit, leaving exactly one affordance; the
  `submitLabel`/`submitPendingLabel` mapping keeps its `DONE` entry only because the enum stays total.

Verification (2026-09-11, host tooling, Android debug build):

- `./gradlew :app:testDebugUnitTest` — 71 tests, 0 failures, 0 errors across 7 suites.
  `PasswordResetViewModelTest` covers the gate: a mismatch blocks submission without calling the
  repository, the rejection keeps both fields, and `reset()` clears the attempt.
- `./gradlew :app:lintDebug :app:assembleDebug` — `BUILD SUCCESSFUL`.
- `./gradlew :app:compileDebugAndroidTestKotlin` — `BUILD SUCCESSFUL` (the instrumentation sources
  compile; one `createComposeRule` deprecation warning, same as the existing suite).

Automated cover added:
`app/src/androidTest/java/com/servora/android/ui/passwordreset/PasswordResetScreenTest.kt`
(4 Compose UI tests) — the two password fields at the new-password step, the mismatch message under
the confirmation field, exactly one "Back to sign in" on the completion step (clicking it fires the
callback once), and the new copy rendered from a French locale. The French test asserts the French
lookup differs from the English copy first, so a missing `values-fr` entry fails instead of
comparing English with itself.

Not run — no device or emulator is attached to this environment:

```bash
cd android && ./gradlew connectedDebugAndroidTest
```

Manual Android QA checklist (product owner):

1. Sign in screen → **Forgot password?** → enter the email → **Send reset code**.
2. Enter the 6-digit code → **Verify code**.
3. At **New password**, type a password, then a different confirmation.
   Expected: "Passwords do not match." under the confirmation field, and **Save new password** does
   not submit. Correcting the confirmation clears the message.
4. Enter matching passwords and save. Expected: the completion step shows the updated message and
   exactly one **Back to sign in**.
5. Tap **Back to sign in**, then reopen **Forgot password?**.
   Expected: an empty form — no email, code or password from step 1–3.
6. Switch the in-app language to French and repeat step 3.
   Expected: the confirmation label and the mismatch message are French.


## SMS provider replaced: Sinch → Twilio (2026-09-11)

`ADR-006` D4 originally bound **Sinch** through the `SmsProvider` port. Product ownership replaced
it with **Twilio**, which required no domain change: the port, the OTP rules and the request
contract are untouched.

| Layer | Change |
| --- | --- |
| Configuration | `SmsConfig.twilio` (`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM`) replaces the `SINCH_*` values. `loadSmsConfig` still refuses to start when a selected provider is missing its credentials. |
| Sender | `TWILIO_FROM` is validated when the configuration loads: an E.164 number is sent as `from`, an `MG…` Messaging Service SID as `messagingServiceSid` (how an A2P-registered deployment sends), and anything else is rejected before the API serves a request instead of on the first OTP. |
| Binding | `SmsModule` selects `noop` (default), `twilio` or `file`; the `file` sink is still refused under `NODE_ENV=production`. |
| Implementation | `TwilioSmsProvider` uses Twilio's official SDK behind an injected client seam, builds the client once, caps the request timeout at 10 seconds and maps every provider rejection to `SmsDeliveryError` without keeping the provider's own error. `SinchSmsProvider` and its spec were deleted rather than left unused (`dev.md` §15). |
| Deployment | `docker-compose.yml` and `.env.example` pass the `TWILIO_*` values through; local development still defaults to `SMS_PROVIDER=noop`, so nothing changes without credentials. |
| Docs | `ADR-006` D4 rewritten with the supersession recorded in place, `ADR-005` D7's note and `ADR-008` D3's comparison updated, `BR-019`'s note now names Twilio, and `docs/api/authentication.md` §7 lists the binding and its variables. |

Twilio remains only the *delivery* provider for a code Servora generates: `BR-019` and `BR-046`
require Servora to own generation, the expiry, the attempt limit, hashed storage and single-use
invalidation, so Twilio Verify is not used.

Verification (2026-09-11, host tooling → PostgreSQL on host port 5434):

```text
API typecheck (tsc --noEmit)   PASS  0 errors
API lint (oxlint)              PASS  0 warnings / 0 errors
API unit tests (Vitest)        PASS  248 passed / 31 files (sms: 18 passed / 3 files)
API e2e (Vitest + PostgreSQL)  PASS  98 passed / 7 files
API build (nest build)         PASS
Provider import smoke test     PASS  dist/sms/providers/twilio-sms-provider.js loads and exports TwilioSmsProvider
```

Automated cover rewritten or added: `sms-config.spec.ts` (11 tests — each missing `TWILIO_*` value
and an invalid sender, both refused at load), `sms.module.spec.ts` (3 tests — the `noop`, `twilio`
and `file` bindings) and `twilio-sms-provider.spec.ts` (4 tests — the request built for a number
and for a Messaging Service SID, and the failure mapping).

Not verified: no message was sent through Twilio's live API. That needs real credentials and a
sender the account is allowed to use, neither of which exists in this environment; the suite
verifies the request the provider builds and how it reports failure, not Twilio's delivery.

