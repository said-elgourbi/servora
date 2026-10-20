# ADR-025 — Assignment push registration and durable delivery

**Status: First assignment flow implemented, including scheduler, cleanup and Android display/taps, 2026-10-16. Awaiting physical QA and environment activation.**

Date: 2026-10-16. Scope: [tracker 062](../tracker/shared/062-notifications-and-firebase.md).
The event, audience, per-installation preference and generic bilingual text were approved in
that tracker. On 2026-10-16 the owner approved 24-hour event expiry, at most 8 attempts per device,
30-day terminal delivery retention and registration invalidation after 30 days without refresh.
Other lifecycle proposals remain distinguished below. The first assignment slice is implemented
through scheduled delivery, maintenance and Android presentation/navigation; physical QA and
operational activation remain outstanding. Earlier milestone descriptions are retained as history.

## Current implementation — 2026-10-16

All first-slice repository components below are now implemented. The earlier component descriptions
of pending scheduler/presentation work are historical. The scheduler is opt-in through
`PUSH_WORKER_ENABLED` (default false), pages up to 25 database-owned organization IDs, runs one
bounded batch per organization and schedules the next iteration five seconds after completion.
Before application shutdown it stops scheduling and drains its running batch before transport/DB
shutdown hooks. Multiple processes retain the database claim/lease fencing.

Maintenance locks stale registrations with SKIP LOCKED, erases targets and increments revision,
suppresses outstanding matching-binding deliveries, deletes terminal rows after 30 days and deletes
expired expanded events only when no delivery remains. Empty expanded events age 30 days after
expiry. It does not delete installation tombstones or backfill historical assignments.

Authenticated registration responses now include the resolved organization ID, stored beside the
Android binding. Legacy records without this association cannot present/tap until refreshed. The
receiver does bounded local work in FCM's callback: session-store restoration, strict parsing,
eligibility checks, atomic receipt reservation and localized notification posting. Distinct immutable
PendingIntents target MainActivity directly. Normal authentication gates navigation and a fresh Job
repository read uses existing API/authorized Room behavior. Sign-out/account-switch cleanup clears
visible alerts and pending taps; receipt history persists until expiry. See the
[QA/activation runbook](../development/firebase.md#assignment-notification-device-qa).
No live delivery or physical-device acceptance is implied by implementation or automated checks.

## Event and ownership

Emit `VISIT_ASSIGNED` for each membership newly added to a Visit, including initial crew at
creation. Compare before/after membership sets; changing LEAD/TECHNICIAN alone emits nothing.
Exclude the actor's user ID. Removing and later adding someone is a new event. Do not notify
historical ad-hoc reconciliation, crew removals, schedule changes or office audiences.

The accepted assignment transaction inserts recipient events using its persisted Visit version. Crew
edits lock and reread the Visit and its crew inside the transaction before diffing or checking the
expected version.
Unique `(organization_id, visit_id, assignment_version, recipient_membership_id, event_type)`
prevents a replay from creating another event. A version conflict or rollback inserts nothing.
Both scheduled-Visit creation paths (direct scheduling and follow-up approval) call the same event
writer inside their transaction. Historical ad-hoc reconciliation deliberately does not.
Assignment success never depends on Firebase availability.

A recipient event has a server UUID `eventId`. Resolve eligible installations once in a separate
worker transaction and mark the event expanded, including when there are zero devices. This
snapshot prevents a later login/device registration from receiving old assignment alerts. The
worker creates one delivery per `(event_id, installation_id, binding_id)`.

Before expansion and every send require active organization, user, recipient membership, current
technician assignment to this Visit, current Job read capability, and active installation-bound
session. Apply existing authorization rules, not the stored role name alone. Check Job/Visit
ownership with organization scope. A canceled/completed Visit or terminal Job suppresses pending
alerts. Resource access is checked again on tap; an accepted push grants no access.

## Installation lifecycle and concurrency

A Servora installation is an app-private random UUID, distinct from Firebase's identifier and
auth session device metadata. Persist a random 32-byte installation secret with it, excluded from
backup. The backend stores only SHA-256 of this high-entropy secret. It authorizes updates to
that installation together with session authentication; neither an FCM token nor installation ID
proves a user's identity. Never log the secret, registration identifier or request body.

The first registration binds to the authenticated user/session and resolved organization membership.
A rebind requires the installation secret and optimistic revision. It generates a new server UUID
`bindingId`, invalidates old deliveries, and never revives old bindings. Same-session token updates
increment the revision and keep the binding; a new auth session gets a new binding even for the same
user. Each request supplies its expected revision; stale writes receive `409`, without changing
ownership. Old-session update/unregister operations cannot affect a new-session binding. Rebind also requires
a session created strictly later than the existing session (compared in PostgreSQL), so an older
login cannot take back the binding even after learning a newer revision.

The app serializes reconciliation per installation and discards responses from a previous local
session generation. Secret loss requires a new installation identity and provider registration.
A provider target already attached to another installation returns a generic conflict, without
transferring it or exposing its owner. Recover by deleting/reacquiring that provider registration;
never steal a target based on its submitted value.

Migration `0028` adds `eligible_since`: the server-owned start of continuous installation eligibility.
It is null on existing rows until registration refresh and on disabled/unregistered bindings. New
bindings, re-enabling and refresh after 30-day staleness start a new interval; ordinary eligible
same-binding refreshes preserve the database timestamp. Expansion requires occurrence at or after
that interval, preventing later registration or re-enabling from receiving older events. Existing
rows are deliberately not backfilled. Registration disable/unregister/rebind suppress outstanding
old-binding deliveries atomically, clear claims and retain terminal records.

Registration stores app preference separately from observed OS permission/channel availability.
Effective eligibility is their conjunction. Disable/unregister cancels outstanding deliveries;
re-enabling applies only to future events. The receiver checks the same local state for in-flight
messages. A changed provider target increments registration revision; a failed old-target send
must invalidate only the exact revision it used.

Sign-out clears local presentation eligibility, displayed notifications and pending taps first,
including offline. Best-effort unregister uses the captured session; server sign-out/revocation
also disables its bindings in the revocation transaction. All sends still check session expiry
and revocation even if cleanup has not run. Account changes never inherit pending destinations.

## Persistence and migration

All identifiers are UUIDs, times server-owned TIMESTAMPTZ. Use VARCHAR + CHECK for codes, following
the existing schema. Migration `0026` implements installations. Additive migration `0027` implements
recipient assignment events, scoped composite ownership keys and the unexpanded-event index. Migration `0028` implements delivery persistence and nullable installation eligibility time.
Scoped expansion and the claim/send batch are callable components. No scheduler or startup hook invokes them; deployment configuration and live delivery remain unchanged.
There is no assignment or eligibility backfill.

| Table | Required fields and constraints |
| --- | --- |
| `push_installations` | ID, secret digest, organization/member/user/session FKs, binding UUID, positive revision, provider project/application ID, target kind/value/digest, locale, app preference, OS permission, channel enabled, state, last registration time, nullable continuous eligibility time, created/updated times. Unique project/application/kind/target digest for non-null targets. States ACTIVE, DISABLED, INVALID, UNREGISTERED. |
| `push_assignment_events` | ID, organization, Job, Visit, recipient membership, actor user, assignment version, fixed event type, occurred/expiry times, nullable expanded time. Unique business key above; expiry after occurrence. |
| `push_deliveries` | ID, organization, event, installation, binding UUID, state, attempts, next-attempt time, claim UUID/lease expiry/positive registration revision, last controlled failure code, terminal time, timestamps. Unique event/installation/binding; attempts 0–8; lease fields present together only for CLAIMED; terminal time required only for terminal states. |

Use composite `(organization_id, id)` unique keys/FKs for Job, Visit, membership, event and
delivery relationships. Ensure the event's Visit belongs to its Job through a composite key;
binding writes verify membership user and auth-session user in the transaction. Installation
ownership can change, so deliveries retain their event's organization and immutable binding
snapshot; never infer recipient scope from a rebound installation. All reads/writes use explicit
organization scope. Global target uniqueness checks must not disclose another tenant's data.

Indexes: unexpanded events by occurrence; pending deliveries by next-attempt time; claimed
deliveries by lease expiry; terminal deliveries by terminal time; installation session and
organization/member lookups; stale installations by last registration time. Controlled failure
codes contain no provider text. Do not store payload copies, tokens in deliveries, or per-attempt
history; attempt count and latest result meet the first slice's diagnostic needs.

Use additive tables with no backfill of assignments. Existing installations remain unregistered
until the new client reconciles. Recheck migration numbering after existing pending `0025` work;
do not edit historical migrations. New table children cascade on parent deletion; application
resource deletion cancels events before deleting resources. Normal retention is described below.
Keep push disabled through migration and deploy backend support before Android reconciliation.

## Worker design and approved core limits

Approved event expiry is **24 hours**, with at most **8 attempts per device**, terminal record
retention **30 days** and registration invalidation after **30 days** without refresh. These are Servora
policy, not Firebase defaults. Migration `0027` timestamps occurrence and expiry from the database
transaction clock; exact writer replays keep the original UUID and expiry. Do not send after expiry;
FCM TTL is the positive remaining lifetime, recalculated immediately before each send.

Expand events using row locks and `FOR UPDATE SKIP LOCKED`. Claim a small bounded batch of due
deliveries the same way, commit, then perform network I/O outside the transaction. Use a 120-second
lease and a 30-second provider deadline. Reclaim abandoned leases; each claim has a fresh UUID.
Finalize only with matching claim UUID, binding and registration revision. The revision is captured
when claiming; it is null outside CLAIMED, together with the claim and lease fields. A timed-out request
may still reach Firebase: lease ownership cannot promise exactly-once delivery. Android must
persistently deduplicate `(bindingId, eventId)` until event expiry using a stable notification ID.

States: PENDING → CLAIMED → ACCEPTED, SUPPRESSED, EXPIRED, FAILED, or back to PENDING.
Reclaimed leases consume the already incremented attempt; never reset the attempt budget.
Immediately before sending, reread eligibility, preference and binding. Concurrent revocation
can still race provider acceptance; local session/binding checks limit that exposure.

Retry transient errors using delays after attempts 1–7 of 1, 2, 4, 8, 16, 32 and 60 minutes,
with additive random jitter of 0–20%. Respect a longer provider Retry-After where available,
without exceeding event expiry. Attempt 8 failure is terminal FAILED. Explicit invalid-target
errors invalidate only that target revision. Configuration/payload rejection is terminal FAILED
and visible through controlled logs/metrics. Disabled transport suppresses delivery and is never
reported accepted; do not build a backlog for later activation.

Retain terminal deliveries for 30 days from terminal time; delete an expired expanded event only
when all its delivery rows are eligible for deletion (including events with no deliveries).
Never delete the only record of a pending/claimed delivery. Invalidate registrations not refreshed
for 30 days and erase their target values immediately. Removing inactive installation records after
30 more days when no pending delivery references remain is still a proposal, outside the approved
four core limits. No scheduled cleanup or stale-target erasure is implemented yet; expansion excludes stale registrations. Startup/sign-in/target changes reconcile
registration; daily reconciliation while active maintains freshness. No background heartbeat is
required. Expired binding IDs remain unusable even after retention cleanup.

## Android presentation and Firebase compatibility

The [version 1 data contract](../api/assignment-push.md) now defines exact wire keys and the implemented
Android parser/receipt ledger. These primitives are not yet connected to message reception/display.
The presentation milestone must establish a trusted local organization association in addition to
the existing binding/session association before accepting an inbound organization ID.

Use data-only messages with version, event type, event ID, binding ID, organization ID, Job ID,
Visit ID and expiry, all strings. Never send names, addresses, notes or evidence. Android chooses
English/French resources at presentation time; locale registration is diagnostic, not a template.
Present only after local session restoration, matching binding/organization, enabled preference,
OS/channel permission, unexpired event and successful deduplication. Drop unknown payload versions.
A tap waits for authentication, verifies the same binding and reads the Job through normal APIs.
Offline, use only the existing authorized Room working set and its stale-data UX. An unavailable
Job or refused access is explicit. Push is never the sole synchronization trigger.

Firebase's current registration guidance describes FID-based registration and continued token
support. The existing pinned Admin transport accepts a registration token. Preserve a typed
provider target (`FCM_TOKEN` initially); do not pass a Firebase Installation ID as a token or
conflate it with the Servora installation UUID. Before adding Android dependencies, verify the
selected Android/Admin SDK pair's registration/send APIs and record the tested choice. A move
to FID targeting requires a separate adapter and contract revision, not reinterpretation of data.

References checked 2026-10-16:
[registration lifecycle](https://firebase.google.com/docs/cloud-messaging/manage-tokens),
[Android reception](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
[error handling](https://firebase.google.com/docs/cloud-messaging/error-codes).

## Implementation acceptance

Use fake-provider unit tests plus real PostgreSQL integration tests for transaction rollback,
mutation replay, concurrent expansion/claims, lease recovery and stale completion fencing.
Cover initial crew, role-only change, remove/re-add, actor suppression, tenant isolation,
revoked/expired sessions, lost membership/access, token rotation, rebind races, no-device expansion,
disable/re-enable, target conflict, expiry, retries and cleanup without losing pending rows.
Android tests cover persistent deduplication, session restoration/account switching, permission
and channel checks, and protected navigation. Physical-device delivery remains a separate gate.

Next: schedule the scoped worker, implement retention/stale-target cleanup and Android
presentation/navigation using the [registration contract](../api/push-installations.md). Registration
persistence/API, sign-out cleanup and transactional event enqueue are implemented with PostgreSQL
integration coverage. Registration disable/unregister/rebind cancellation is implemented; revocation/freshness checks before sends are implemented by the worker component. Scheduled execution and retention remain pending.
BR-029 records the approved first slice; additional events and audiences remain open questions.
