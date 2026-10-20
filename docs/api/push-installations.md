# Push installation registration

**Status: Registration and the first assignment flow implemented, 2026-10-16. Awaiting physical-device QA and environment activation.**

Registration implementation and the four core delivery limits in
[ADR-025](../decisions/025-assignment-push-delivery.md) are owner-approved. Android-only first slice;
no notification inbox, client send endpoint or Firebase authentication. JSON uses camelCase,
UUIDs and UTC ISO-8601 timestamps. Paths have no version prefix.

## Authentication and installation proof

All routes require the existing Bearer access token and an active ANDROID session. Resolve
organization, membership and user from current server authentication/authorization. No submitted
owner, organization, project or environment field is accepted. Scope installations by organization
and ID, except explicit secret-proven rebinding in the PUT transaction. Select the first
active membership by joined time then ID, requiring an active user, organization and role.
Registration requires explicit `FIREBASE_PROJECT_ID` and `PUSH_ANDROID_APPLICATION_ID`; without
both, PUT returns `503 PUSH_REGISTRATION_UNAVAILABLE`. Only the configured application ID is
accepted. Production rejects the debug application ID at startup.

Send `X-Push-Installation-Secret` containing exactly 32 random bytes encoded as 43-character
unpadded base64url. Persist it app-privately with the installation UUID; never log this header,
provider targets or request bodies. It is additional installation proof, never session auth.
The backend stores its SHA-256 digest. Incorrect proof for an existing ID returns generic `404`
without ownership information. TLS is required outside controlled local development.

## `PUT /push/installations/:installationId`

Creates, reconciles or rebinds this installation. Client serializes requests and uses the latest
acknowledged revision. Example:

```json
{
  "expectedRevision": 0,
  "applicationId": "com.servora.android.debug",
  "registration": { "kind": "FCM_TOKEN", "value": "opaque-provider-target" },
  "locale": "en-CA",
  "alertsEnabled": true,
  "notificationPermission": "GRANTED",
  "channelEnabled": true
}
```

`expectedRevision` is an integer from 0 through 2147483646; zero means create only. Every accepted update increments
the positive server revision, including a refresh of the same target, and updates server freshness.
Concurrent initial creates serialize by installation ID. Existing records require matching proof
and revision. New-session binding replaces owner/session atomically and generates a new binding UUID;
same-session reconciliation retains binding UUID. Rebinding requires a session created strictly
later than the existing bound session, compared using database timestamp precision. Older-session
requests receive `409` even if they know the current revision. Same-session organization changes
also replace the binding. Disable or rebind atomically suppresses outstanding old-binding deliveries
and clears their claims; terminal history is preserved. The send worker also fences
obsolete bindings and target revisions immediately before provider I/O.

The server stores continuous eligibility time privately. New bindings, re-enable and refresh after
30-day staleness start eligibility at the database transaction clock. Ordinary active refreshes
preserve it. Expansion excludes events that predate eligibility, so later registration/re-enable
cannot replay old assignments. Migration `0028` leaves this field null on pre-existing installations
until refreshed; it is not added to the wire contract.

Validate unknown fields, required types and lengths. Installation ID is a UUID. Allow application
IDs configured for this server environment only. `registration.kind` is exactly `FCM_TOKEN`;
value is a nonblank opaque string of at most 4096 UTF-8 bytes, with no whitespace/control characters.
Do not infer Firebase project or user identity from the target. Locale is `en-CA` or `fr-CA`;
Android maps supported English/French locales accordingly. Permission is `GRANTED`, `DENIED`,
or `NOT_REQUESTED`; app preference and channel state are booleans, independently stored.
Clients must reconcile true observed state; a local default toggle does not represent OS consent.

Success is `200` for create/update/rebind, with no secret or provider target echoed:

```json
{
  "installationId": "00000000-0000-4000-8000-000000000001",
  "bindingId": "00000000-0000-4000-8000-000000000002",
  "organizationId": "00000000-0000-4000-8000-000000000003",
  "revision": 1,
  "alertsEnabled": true,
  "deliveryEnabled": true,
  "registeredAt": "2026-10-16T16:00:00.000Z"
}
```

`organizationId` is the authenticated, server-resolved binding scope. Android stores it with the
binding and checks it on receipt/tap; it never comes from the registration request. Older client
records without it must reconcile before presentation.

`deliveryEnabled` means current registration/session/preference/OS/channel eligibility with an
operationally enabled transport. It does not promise resource eligibility, delivery or display.
Registering while transport is disabled is allowed and returns `deliveryEnabled: false`.

## `DELETE /push/installations/:installationId`

Require matching installation proof and ownership by the current authenticated session.
No request body. Idempotently return `204` for its already-unregistered binding, and atomically
clear target, disable registration, increment revision and suppress outstanding old-binding deliveries. A missing
record or another session's binding returns generic `404`; it cannot unregister a later account's
binding. Retain the proof/revision tombstone. Scheduled cleanup erases stale targets and retains installation tombstones. Re-registration uses PUT and
the current revision; if the local revision is lost, recover as described below.

## Failures and recovery

Use the existing API error envelope; no raw database or Firebase error text.

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | Malformed ID/header/body, unsupported application or target kind |
| 401 | `UNAUTHENTICATED` | Missing/invalid authentication or inactive session |
| 403 | `FORBIDDEN` | Non-Android session or no eligible active membership |
| 404 | `PUSH_INSTALLATION_NOT_FOUND` | Missing/inaccessible installation or failed proof |
| 409 | `PUSH_REGISTRATION_CONFLICT` | Stale revision, older-session rebind or target already bound elsewhere |
| 500 | `PUSH_REGISTRATION_FAILED` | Database failure, with SQL/provider values redacted |
| 503 | `PUSH_REGISTRATION_UNAVAILABLE` | Project/application configuration missing |

A conflict never transfers a target. For stale revision recovery, use
`GET /push/installations/:installationId`: same auth, proof and current-session ownership checks;
returns the PUT response with the current revision, no target/secret. Another-session binding
remains `404`, so a new-session rebind must use the last locally persisted revision. If that
revision/proof is lost or irreconcilable, regenerate installation identity and provider target;
do not blindly repeat a takeover request. A lost PUT response in the same session can recover
via GET. Persist ID, proof, revision and binding together to avoid partial local state.

No general offline mutation outbox is used: registrations reconcile the latest session, target,
preference and observed permission state when connectivity returns. Replaying historical register
writes could restore obsolete ownership/preferences. Retry network/server failure with bounded
backoff; cancel reconciliation on session change and restart from current local state. Local
sign-out or disabling alerts suppresses presentation immediately. Unregister remains best-effort
when offline; server session eligibility checks and the generic payload limit residual delivery.

## Data message version 1 — future delivery contract

All values are strings: `payloadVersion="1"`, `eventType="VISIT_ASSIGNED"`, `eventId`, `bindingId`,
`organizationId`, `jobId`, `visitId`, `expiresAt`. UUIDs and expiry come from the persisted event
and binding; TTL is remaining lifetime. No automatic-display notification block. Android renders
localized generic text, checks binding/session/preferences and deduplicates before presentation.
The payload is routing input, never proof of permission or a replacement for authorized API reads.

## Verification and remaining work

PostgreSQL integration coverage exercises HTTP authentication, owner/tenant isolation, concurrent
create/update, newer-session rebinding, delayed old-session writes, target conflicts, independent
preference/OS state, unregister/reconciliation, session expiry and sign-out cleanup, plus database
ownership/state constraints. Unit checks cover boundary validation, configuration and failure
redaction. No Firebase send or Android device registration is performed by these tests.

Migration `0026_push_installations` is additive and does not backfill assignments. Server sign-out
clears the session's provider targets and disables its installations in the same transaction as
revocation. Session expiry is checked on each endpoint; a future worker must check it before send.
The provider target must remain retrievable for sending and is stored in the private backend
table; installation proof is stored only as a digest. Neither is returned in DTOs or logs.

Assignment enqueue, delivery persistence and explicitly scoped device expansion are implemented.
Pending: scheduled expansion/send worker, claims/retries, scheduled retention/stale-target erasure,
Android presentation/navigation and physical-device verification. Android registration reconciliation,
permission observation and token-refresh service are implemented; see the Firebase runbook. Approved
limits are 24-hour events, at most 8 attempts, 30-day terminal retention and 30-day registration
freshness. The schema bounds attempts and expansion filters expiry/freshness; scheduled enforcement
and delivery still await the worker.
