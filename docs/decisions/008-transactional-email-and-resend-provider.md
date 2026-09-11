# ADR-008 — Transactional email delivery and the Resend provider

**Status: Accepted** (product-owner decision, 2026-09-10)

Supersedes `ADR-006` D3 **in part**: the reset-delivery port stays exactly as designed there, and
the production transport that D3 recorded as open is decided here. Extends `ADR-006`
(phone/SMS authentication and password reset), `ADR-005` (the SMS delivery port, whose shape this
decision mirrors) and `ADR-004` (authentication domain model).

References: `BR-028`, `BR-042`, `BR-043`, `BR-044`, `BR-045`, `BR-046`, `dev.md` §4, §5, §7,
§8, §11 and §12, `qa.md` §4, §11, §13 and §15.

## Context

`BR-043` requires a password-reset credential to reach the user through the delivery channel
configured for that account, and the approved prototype states the reset code is sent **by
email**. `ADR-006` D3 therefore bound `PasswordResetNotifier` to a no-op by default and to a
development-only file sink outside production, and recorded the production email transport as an
**open decision** rather than inventing one (`BR-042`).

That decision is now made: transactional email is delivered through **Resend**. Nothing about the
credential changes — the 6-digit code (`BR-019`/`BR-043`), its keyed digest storage (`BR-046`),
the supersession and attempt rules, the persisted rate limits (`BR-045`) and the non-disclosing
`202` (`BR-044`) are all untouched. This ADR adds a transport, and only a transport.

The delivery channel was the **only** blocker here. The reset flow already composed the message:
`auth-message.ts` resolves the subject and body for the recipient's language (`BR-028`), so the
transport has no copy, template or localization concern of its own.

### D1 — Transactional email is a port, and it mirrors `SmsProvider`

`EmailProvider` (`api/src/email/email-provider.ts`) is the provider-neutral port, bound behind the
`EMAIL_PROVIDER` token and reached only by `PasswordResetNotifier`:

`Authentication -> PasswordResetNotifier -> EmailProvider -> provider implementation -> Resend`

`OutboundEmailMessage` is deliberately the smallest thing that can carry a transactional message:
one `to`, one `subject` and one plain-text `text`. Recipients, cc/bcc, HTML, templates,
attachments, provider-specific terminology and delivery reporting are **not** specified by any
approved rule, so none of them is designed or assumed (`BR-042`).

`ADR-005` established this shape for SMS and `ADR-006` D4 reused it; reusing it again keeps the
authentication domain free of vendor vocabulary and makes the provider replaceable by changing one
module binding and one implementation file.

### D2 — `EMAIL_PROVIDER` selects the binding, and `noop` stays the default

`EMAIL_PROVIDER_KINDS` is `['noop', 'resend']` with `noop` as the default, mirroring
`SMS_PROVIDER`:

* `noop` keeps the port resolvable and delivers nothing, so a machine with no provider account
  still boots and the reset endpoint still answers its contract response.
* `resend` requires `RESEND_API_KEY` and `EMAIL_FROM`. `loadEmailConfig` validates them at
  **startup** and throws when one is missing, so a misconfigured deployment fails loudly on boot
  instead of at the first reset request.

`EMAIL_FROM` is a required input rather than a hard-coded constant because the sending address
belongs to the deployment (the domain must be verified in Resend), not to the source tree
(`dev.md` §5).

### D3 — Resend is called over HTTP, without a vendor SDK

`ResendEmailProvider` posts to `https://api.resend.com/emails` with `fetch`, an
`Authorization: Bearer <RESEND_API_KEY>` header, a JSON body (`from`, `to`, `subject`, `text`) and
`AbortSignal.timeout(10s)`.

This mirrors the reasoning `ADR-006` D4 applied to the SMS provider: the port is one endpoint with
four fields, so a provider package would add a dependency, its own error vocabulary and its own
release cycle to gain nothing (`dev.md` §4). The SMS provider was later moved from Sinch to Twilio
and *does* use the vendor SDK, because Twilio's API requires request signing — `ADR-006` D4 records
that difference. The `FetchLike` type and the fetch implementation here are injected, which is how
the unit test asserts the request without a network.

Every non-2xx response, timeout and network failure becomes `EmailDeliveryError`. That error
carries **no** provider detail: a provider's error body can echo the message it was given — for a
reset, that message contains the code itself — so nothing from the provider's response is kept,
logged or surfaced (`BR-044`, `BR-046`).

### D4 — The reset transport is chosen by `PASSWORD_RESET_DELIVERY`, not by `EMAIL_PROVIDER`

`PASSWORD_RESET_DELIVERY` gains `email` alongside `noop` and `file`, and `email` binds
`EmailPasswordResetNotifier`, which maps the message onto the port and adds no copy of its own.

The two settings answer different questions and are therefore kept separate:
`PASSWORD_RESET_DELIVERY` says *which port delivers a reset*, while `EMAIL_PROVIDER` says *which
service sends email at all*. A `file` email provider kind was deliberately **not** added:
`ADR-006` D3's file sink already covers local development and manual QA one level up, and
duplicating it inside the email module would create two sinks to keep in step.

### D5 — A delivery failure is contained, and never becomes an account oracle

`PasswordResetService.deliverResetMessage` logs and **swallows** `EmailDeliveryError` only; any
other error keeps propagating.

An address that has an account must not behave differently from one that does not (`BR-044`), so a
provider rejection cannot change the response: the endpoint still answers `202` with the same body.
Anything the port did not classify as a delivery failure is a deployment fault, and it stays loud.
Nothing about the message is logged — not the subject, not the body, not the code (`BR-046`).

### D6 — What is deliberately not implemented

`BR-042` requires the unspecified parts to stay unspecified, so this slice adds no HTML or
template layer, no attachments, no cc/bcc, no retry or backoff policy (retrying a security message
is a business decision, and `BR-029` is still open), no bounce or delivery webhook, and no other
email type. Verifying the sender domain in Resend is an operational step, not code.

## Consequences

* The password-reset flow has a production transport, so `ADR-006` D3's open question is closed.
  What remains open there is session revocation on reset (`BR-043` Notes).
* `noop` stays the default, so a deployment that selects `resend` without credentials fails at
  startup instead of silently dropping reset messages.
* The provider is replaceable without touching authentication, notifications or the API contract: a
  new provider is one file plus one `EMAIL_PROVIDER_KINDS` value.
* `EmailProvider` exposes no delivery reporting, so "was the message delivered?" cannot be answered
  from the API — that is a deferred decision, not an oversight.

## Deferred decisions

* Retry, backoff and bounce handling for a failed reset message.
* Delivery reporting/webhooks, and whether any surface should ever show message status.
* Other transactional email types (invitations, `BR-039`; notifications, `BR-029`) — none is
  approved yet, and the port must not grow to anticipate them.
* Whether a reset revokes existing sessions (`BR-043` Notes), which stays product's decision.


