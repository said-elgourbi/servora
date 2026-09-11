# ADR-006 — Phone/SMS authentication and password reset

**Status: Accepted** (product owner decisions, 2026-09-10)

Supersedes nothing. Extends `ADR-004` (authentication domain model) and `ADR-005`
(sign-in hardening and the SMS delivery port), and resolves the two flows that
`docs/api/authentication.md` §9 and `docs/tracker/004-authentication-domain-model.md`
recorded as blocked.

References: `BR-018`, `BR-019`, `BR-043`, `BR-044`, `BR-045`, `BR-046`, `BR-042`,
`dev.md` §6–§7 and §10–§11, `qa.md` §4, §8 and §13.

## Context

The approved Figma `Login` export defines seven authentication screens. Five of them
(`login` → `sms-phone` → `sms-otp` and `fp-username` → `fp-verify` → `fp-newpassword` →
`fp-done`) had no implementation in any layer, because `BR-019` left every OTP rule open and no
password-reset rule existed at all. `BR-042` forbids inventing that behaviour, so it was recorded
as an open question instead of built.

Product ownership has now decided the rules. They are canonical in `Business Rules.md`
(`BR-019`, `BR-043`, `BR-044`, `BR-045`, `BR-046`); this ADR records only the **architectural**
decisions needed to implement them, so the same rule is not stated in two places.

## Decisions

### D1 — One-time codes are 6 CSPRNG digits, stored as a keyed digest

Both `BR-019` (SMS OTP) and `BR-043` (password reset) need a short, human-entered code with the
same security properties: random, single-use, time-limited, attempts-limited, never stored or
logged in plaintext (`BR-046`).

* Generation is `crypto.randomInt` over `000000`–`999999` (uniform, CSPRNG-backed).
* Persistence uses `HMAC-SHA256(key = JWT_SECRET, message = "servora-auth-code:v1:" + scope + ":" + ownerId + ":" + code)`,
  hex-encoded, and verification compares digests in constant time.

Why a keyed digest rather than the project's existing plain SHA-256 (`auth-token.ts`): that helper
is documented as sufficient because an opaque token carries 256 bits of entropy. Six digits carry
about 20 bits, so an unkeyed digest would be recoverable from a database disclosure in
microseconds. The domain-separation prefix keeps this use of the key separate from JWT signing,
and the per-owner component means two users can hold the same 6-digit code without colliding.

`JWT_SECRET` is reused deliberately, so no new required secret is introduced: it is already
mandatory (≥ 32 characters, no fallback, `ADR-004` D9) and already high-entropy. Rotating it
invalidates outstanding codes, which is acceptable because their lifetimes are 10 and 30 minutes
and a rotation is an emergency operation in any case.

### D2 — Password reset reuses `password_reset_tokens`, extended by one column

`ADR-004` D10 already models the reset credential: `user_id`, single-use through `used_at`,
`expires_at`, `token_hash`, and no `UNIQUE(user_id)` so a user may request a reset again. The
approved rules add exactly one property that column set cannot express: the maximum of five
failed verification attempts (`BR-043`). Migration `0002` therefore adds `attempts`
(`integer NOT NULL DEFAULT 0`) with a `>= 0` check, rather than introducing a second reset-token
system.

`PASSWORD_RESET_TOKEN_LIFETIME` (default `30m`, `ADR-004` D9) is reused as the approved 30-minute
lifetime; no new lifetime configuration is introduced.

Requesting a reset marks every outstanding credential for that account as used, which implements
"requesting a new credential invalidates the outstanding one" without deleting the old row, so the
history stays auditable (`BR-033`). A successful reset marks the used credential and every other
outstanding credential for the account as used.

### D3 — Reset delivery is a port; the transport is resolved in `ADR-008`

> **Resolved 2026-09-10:** the production transport this decision left open is now decided and
> implemented — email delivery through Resend behind the `EMAIL_PROVIDER` port. See `ADR-008`
> (`docs/decisions/008-transactional-email-and-resend-provider.md`); nothing else in this
> decision changes.

`PasswordResetNotifier` (`api/src/notifications/password-reset-notifier.ts`) is the analogue of
`SmsProvider`: a provider-neutral port that receives a recipient and a message. The dependency
direction is `Authentication -> PasswordResetNotifier -> transport`, so choosing an email
provider later changes one module and no domain code.

Bindings, selected by the `PASSWORD_RESET_DELIVERY` configuration value:

| Value | Implementation | Use |
| --- | --- | --- |
| `noop` (default) | `NoOpPasswordResetNotifier` | Production default until a provider is approved |
| `file` | `FilePasswordResetNotifier` | Local development and manual QA only |

`FilePasswordResetNotifier` writes the message to `var/dev-notifications/` (git-ignored) so a
developer or the product owner can complete the flow on a real device without an email account.
The API **refuses to start** with `PASSWORD_RESET_DELIVERY=file` when `NODE_ENV=production`
(`dev.md` §11: a development affordance must not be able to operate in production). No code is
ever logged on either path (`BR-046`).

Choosing the production transport (SMTP, a transactional email API, …) and its credentials
remains a deferred infrastructure decision; nothing in this ADR presumes one.

### D4 — Twilio is bound through the existing `SmsProvider` port

`ADR-005` D5–D7 created `SmsProvider` with a no-op binding and deliberately left provider
selection open. `BR-019` approves Twilio as the production provider, so `TwilioSmsProvider`
implements the same port and `SmsModule` selects it from `SMS_PROVIDER`
(`noop` | `twilio` | `file`).

> **Provider replaced (2026-09-11):** this decision originally bound **Sinch**. Product ownership
> replaced it with **Twilio**, and the Sinch implementation, its `SINCH_*` configuration and its
> tests were deleted rather than kept as a second, unused provider (`dev.md` §15: no dead code).
> The port, the OTP rules and every credential requirement below are unchanged — only `SmsModule`,
> `sms-config` and one implementation file changed, which is exactly what `ADR-005` D5–D7 designed
> the port for.

Twilio's official SDK is used rather than raw HTTP. Twilio's REST API requires request signing
(an HMAC over the URL and the sorted parameters, keyed with the auth token), and the port owns OTP
delivery, so hand-writing a signer would be security-sensitive code the vendor already maintains.
The dependency stays inside `createTwilioClient`; nothing above the port imports it, and the
provider is tested through an injected client seam rather than over the network.

Credentials come from configuration (`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM`) and
are validated by `loadSmsConfig` **at startup**, so a misconfigured deployment fails loudly on boot
instead of at the first OTP request. `TWILIO_FROM` is either an E.164 number (sent as `from`) or an
`MG…` Messaging Service SID (sent as `messagingServiceSid`, the normal A2P-registered deployment);
a value that is neither is refused at startup. The client is built once and reused, and its request
timeout is capped at 10 seconds so a hung provider cannot hold a technician's request open.

The provider never logs the message body, because the body carries the OTP, and it maps every
provider rejection to `SmsDeliveryError` rather than leaking provider terminology or a provider
error body upward — a provider error can quote the message it was given (`BR-046`).

**Deliberate interpretation:** Twilio is used as the *delivery* provider for a code Servora
generates. The approved rules require Servora to own generation, the 10-minute expiry, the
five-attempt limit, hashed storage and immediate single-use invalidation (`BR-019`, `BR-046`);
a provider-side code generator (Twilio Verify) cannot satisfy those properties, so the provider is
confined to transport. The port keeps that choice reversible: adopting Twilio Verify, or replacing
Twilio, is a change to `SmsModule` and one implementation file.

`FakeSmsProvider` (in-memory, test-only) is added so the automated suite never sends a real
message.

`FileSmsProvider` is added as a development and manual-QA sink (`SMS_PROVIDER=file`): it writes
each message to a git-ignored directory, so the flow can be exercised on a real device without a
provider account. `loadSmsConfig` refuses it under `NODE_ENV=production`, exactly as
`PASSWORD_RESET_DELIVERY=file` is refused, and it logs nothing.

### D5 — A phone number identifies at most one account; ambiguity fails closed

`BR-019` makes a phone number an authentication credential. `users.phone` is nullable,
`varchar(50)` and **not** unique, so the database currently permits a number to appear on more
than one account, which would make phone sign-in ambiguous.

Rather than inventing a uniqueness rule the product has not approved (sharing a number may be
legitimate for non-authentication uses), the API resolves eligibility at authentication time:

1. the submitted value is normalised and validated as E.164 (server-authoritative, `BR-019`);
2. accounts whose `users.phone` equals the normalised value are selected;
3. authentication proceeds only when **exactly one** such account exists **and** it is `ACTIVE`.

Zero matches and more than one match are both reported through the generic non-disclosing
response (`BR-044`) and produce no SMS. No `users` column or constraint is added, so no
speculative schema is introduced; if product ownership later decides that a number identifies
exactly one account, that decision becomes a migration.

`users.phone` is read as stored, so the write path must store E.164. How a number is associated
with an account, changed or released remains **OPEN QUESTION**.

### D6 — Rate limiting is a persisted rolling window over hashed subjects

`BR-045` requires limits that survive a restart and apply to unknown identities, and the project
has no rate-limiting infrastructure. `auth_rate_limit_events` is therefore one small table
(`scope`, `subject_hash`, `created_at`) shared by both flows, counted with a rolling-window query
and indexed on `(scope, subject_hash, created_at)`.

* `subject_hash` is `HMAC-SHA256(JWT_SECRET, scope + ":" + canonical identity)`, so the table
  holds no email address and no phone number — it counts attempts without becoming a second copy
  of personal data, and identical subjects are still countable.
* Windows and maxima are configuration (`AUTH_RATE_LIMIT_*`, `SMS_OTP_RESEND_COOLDOWN`), as
  `BR-045` requires, with the approved values as defaults.
* Because an unknown identity is hashed and counted like a known one, a `429` cannot be used to
  discover which identities exist.
* Events older than the longest configured window are deleted opportunistically after each
  recorded attempt, so the table stays bounded without introducing a retention policy that
  product ownership has not decided.

### D7 — Phone OTP challenges are persisted per user, superseded rather than reused

`phone_otp_challenges` stores `user_id`, `code_hash`, `attempts`, `expires_at` and `consumed_at`.
One row per request (not one row per user) so a superseded challenge is provably unusable instead
of being overwritten: requesting a new OTP consumes the outstanding one (`BR-019`), and a
successful verification consumes it immediately. `attempts` is checked before comparison and
incremented on every failed verification, so the five-attempt limit is enforced against
persistence rather than process memory.

### D8 — The Android authentication flow is state-driven, and adds no dependency

The Android client has no navigation library and no signed-in destination yet. The auth flow is
modelled as one `AuthDestination` state held by a composable in `android/.../ui/auth/`, with
`BackHandler` returning to sign-in. No dependency is added for a five-screen flow (`dev.md` §4),
and the existing `by viewModels()` wiring in `MainActivity` continues to work.

### D9 — Authentication never works offline

Authentication and password reset require network access, and no password, OTP or reset
credential is stored on the device for offline authentication. This is consistent with `BR-001`
and `BR-031`: only the resulting session participates in the offline model, exactly as it does
for `BR-018`.

## Consequences

* The seven approved Figma authentication screens now map to real, contract-defined flows.
* The two flows share one code-digest helper, one rate limiter and one port pattern, so the rule
  "one-time codes are never stored in plaintext" (`BR-046`) has exactly one implementation.
* The reset flow cannot be exercised end-to-end in production until a delivery transport is
  approved; the API is complete and testable now because the port is injectable.
* Phone sign-in cannot create accounts, so a number must already be on an account. How it gets
  there is out of scope and recorded as an open question.
* No session is created or revoked by a password reset, because that behaviour is not decided
  (`BR-043` Notes).

## Deferred decisions

| Decision | Why it is deferred | Reference |
| --- | --- | --- |
| Production email transport for reset delivery | **Resolved 2026-09-10** — email delivery through Resend behind the `EMAIL_PROVIDER` port (`ADR-008`); this ADR no longer carries the item as open | `BR-043` Notes, `ADR-008` |
| Whether a password reset revokes existing sessions | Not decided | `BR-043` Notes |
| How a phone number is associated with, changed on or released from an account | Profile management not in scope | `BR-019` Notes, `BR-039` |
| Delivery reporting, provider webhooks and OTP delivery failure UX | Not decided | `BR-019`, `ADR-005` |
| Retention/pruning of expired challenges, reset tokens and rate-limit events | `BR-027`/`BR-033` open | `BR-027`, `BR-033` |
| Notification behaviour for a security-relevant event | `BR-029` open | `BR-029` |
