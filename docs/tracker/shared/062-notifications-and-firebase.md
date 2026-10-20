# Tracker 062 — Notifications and Firebase Cloud Messaging

**Status: First assignment notification flow implemented, including scheduled delivery, retention, Android display and authenticated taps. Awaiting product-owner physical QA and environment activation; no live delivery acceptance claimed.**

Date: 2026-10-15

Source: product-owner discussion about introducing notifications and Firebase, followed by the
request to document the work first and start implementation in a later chat.

Implementation started on 2026-10-15 at the product owner's request. The owner approved the
first-event defaults below in this chat. Remaining recommendations are proposals; the complete
notification feature is not yet implemented or enabled for users.

## Completion implementation — 2026-10-16

Owner requested all remaining Firebase implementation in this session and explicitly excluded heavy
Gradle tests. Added an opt-in scheduled worker (`PUSH_WORKER_ENABLED=false` by default), trusted
keyset enumeration of at most 25 organizations per iteration, bounded scoped batches, non-overlapping
5-second scheduling and shutdown draining before provider/database disposal. Disabled transport
suppresses work when the scheduler is enabled; it never reports acceptance.

Added scoped, locked stale-registration invalidation/target erasure after 30 days, claim cancellation,
30-day terminal delivery deletion, and expired-event cleanup only after delivery history is gone.
No new migration, assignment backfill, inactive-installation deletion policy or notification event.

Registration responses now include server-resolved `organizationId`; Android persists it atomically
with the binding. Legacy registrations without it fail closed until reconciliation. FCM data messages
now restore local session identity and enforce binding, organization, preference, permission/channel,
expiry and durable deduplication before showing a generic bilingual alert. Notifications use a
monochrome icon, stable tags, immutable distinct PendingIntents and event-expiry timeout. AppCompat's
persisted locale is used on background-only starts. Sign-out/account changes clear alerts/pending taps;
reconciled binding changes clear obsolete alerts without erasing receipt history.

Cold/warm taps wait behind the existing authentication gate, revalidate the binding and open the
normal Job/Visit route with a fresh authorized repository read. Existing offline Room/stale-data,
missing-record and denied-access behavior remains authoritative. Superseded details reads are canceled.

Verification: 691 API unit tests and 56 targeted PostgreSQL worker/registration tests pass.
Full API integration: 547 passed / 6 previously recorded unrelated failures (553 total). The final
resource-capped debug APK builds; 16 parser/binding/receipt JVM tests pass without Gradle tests.
Full Android suites/lint and device-test compilation were excluded by the owner's machine constraint.

Verification and exact commands: [completion runbook](../../development/firebase.md#completion-verification--2026-10-16).
QA instructions: [assignment notification device QA](../../development/firebase.md#assignment-notification-device-qa).
No `adb`, device command, live push, production configuration change or heavy Gradle test was run.
Earlier entries below are historical component milestones, not current remaining-work lists.

## Implementation update — 2026-10-15

### Scoped delivery worker component — 2026-10-16

- Added `runAssignmentDeliveryBatch(db, config, provider, { organizationId }, batchSize)`, combining
  expansion with bounded due-delivery claims and concurrent data-only sends. No startup hook,
  scheduler, organization enumeration, HTTP endpoint, configuration change or live send was added.
- Claims use `FOR UPDATE OF push_deliveries SKIP LOCKED`, fresh claim UUIDs, 120-second leases,
  registration revisions and the existing eight-attempt budget. Abandoned leases consume attempts;
  exhausted leases become terminal. Claims contain no provider targets.
- Expansion and sending share the same eligibility predicate. Immediately before provider I/O the
  worker checks current tenant/binding/revision, authorization, crew, session, resource state,
  preference, permissions/channel, continuous eligibility and registration freshness. Target reads
  join the event's organization and immutable binding, excluding rebound accounts. TTL uses the
  database expiry with conservative elapsed-time subtraction. Short remaining leases defer sending.
- Provider I/O occurs outside transactions with a 30-second worker deadline. Retries follow the
  approved 1/2/4/8/16/32/60-minute schedule with 0–20% additive jitter and are capped at event expiry.
  A controlled optional Retry-After delay is honored where supplied; the current Admin adapter
  does not expose response headers. Unknown provider exceptions are retryable without raw logging.
- Completions require current claim/lease/binding/revision. Old responses after token rotation,
  unregister, rebind or lease recovery cannot overwrite newer state. Explicit invalid-target errors
  erase only the matching revision, advance it and suppress other outstanding binding deliveries;
  configuration/payload rejection never erases targets. Provider acceptance records ACCEPTED,
  which is distinct from display/read confirmation.
- This is a backend component milestone. Scheduled execution, stale-target erasure, 30-day retention
  and Android presentation/navigation remain pending. The SDK request cannot be canceled by the
  worker deadline; late acceptance is possible, requiring the existing Android deduplication design.
- Tier B API verification: typecheck/build/lint pass (one existing lint warning), all 686 unit tests
  pass, and all 40 new PostgreSQL worker cases pass. Full integration: 543 passed / 6 pre-existing
  failures out of 549; no repository-wide acceptance claimed. Exact commands: [worker runbook](../../development/firebase.md#scoped-delivery-worker-verification).

### Delivery persistence and device expansion — 2026-10-16

- Added additive migration `0028_push_deliveries`: scoped event FK, immutable binding snapshot,
  unique event/installation/binding, attempts bounded to 8, controlled failure codes, claim/revision/
  lease and terminal-state constraints, and pending/lease/terminal indexes. No targets or payload
  copies are stored in delivery rows. Installation rebinding may cross organizations, so the
  installation FK deliberately follows identity only; the event FK preserves recipient tenant scope.
- Added `expandAssignmentEvents`, an explicitly organization-scoped, bounded transaction using
  `FOR UPDATE SKIP LOCKED`. Device snapshots and the expanded marker commit or roll back together;
  zero-device, expired and ineligible events are consumed. Disabled transport creates terminal
  SUPPRESSED records for eligible devices, without leaving a backlog for later activation.
- Eligibility checks current organization/user/member/role, Android session, crew, Job/Visit and
  non-deleted Customer, plus the existing role/direct Job-read grants, project/application,
  target availability, preference, permission, channel and 30-day registration freshness.
- Added nullable `eligible_since` without backfilling historical eligibility. Registration starts
  continuous eligibility on first registration, rebind, re-enable or stale refresh; ordinary active
  token/locale/freshness updates preserve it. Events predating eligibility never expand to that
  device. Disable/unregister/rebind atomically suppress outstanding old-binding deliveries and clear
  claim ownership, while retaining terminal history.
- This is a backend component milestone: **no scheduler invokes expansion, no provider send occurs,
  and delivery claims/retries, target invalidation/retention jobs and Android presentation/taps are
  still pending**. The approved 30-day staleness rule filters eligibility here; erasure of stale
  targets is not yet scheduled. The send worker must recheck all eligibility immediately before I/O.
- Tier B API verification: typecheck/build/lint pass (one existing lint warning), all 682 unit tests
  pass, and all 37 new PostgreSQL expansion cases pass. Full integration: 503 passed / 6 pre-existing
  failures out of 509; no repository-wide acceptance claimed. Commands and failure details are in the
  [runbook](../../development/firebase.md#delivery-persistence-and-expansion-verification).

### Approved delivery limits and transactional event enqueue — 2026-10-16

- Owner explicitly approved 24-hour event expiry, at most 8 attempts per device, 30-day terminal
  delivery retention and registration invalidation after 30 days without refresh. BR-029 and ADR-025
  now record those rules. Earlier entries describing them as proposals are historical.
- Added migration `0027_push_assignment_events` with recipient intent, scoped Job/Visit/member
  relationships, a business-key uniqueness constraint, positive version/type/expiry checks and an
  unexpanded-event index. It adds redundant scoped unique keys to parent tables, with no backfill
  and no historical migration changes. Occurrence/expiry use the database transaction clock.
- Crew edits now lock and reread the Visit and current crew before version validation/diffing.
  New assignments, history, Visit version and recipient events commit or roll back together.
  Both initial scheduled-crew paths (direct creation and follow-up approval) use the same writer.
- Suppresses the actor by user identity. Unchanged crew, role-only changes and removals enqueue
  nothing; remove/re-add creates a new versioned event. Exact writer replay preserves UUID/expiry.
  Historical ad-hoc conversion/linking remain excluded. Assignment does not call Firebase.
- Only the event-expiry policy is implemented in this slice. Attempt budgeting, device expansion,
  delivery claims/sends, stale-registration invalidation, retention and presentation/navigation
  remain for the next milestones. No alert delivery or production activation is claimed.
- Tier B API verification: typecheck/build/lint pass (one existing lint warning), all 682 unit tests
  pass, and the full PostgreSQL suite reports 466 passed / 6 failed out of 472. All 13 new enqueue
  tests and historical ad-hoc exclusion assertions pass. Six failures are in existing evidence-removal
  permission and Job Details `purposeCode` assertions outside this slice; details and exact commands
  are in the [runbook](../../development/firebase.md#assignment-event-migration-and-verification).
  No live Firebase send, Android build or device command was run for this backend milestone.


### Android message parsing and deduplication primitives — 2026-10-16

- Added the [version 1 assignment data contract](../../api/assignment-push.md), strict Android parser,
  normalized opaque UUID routing and stable binding/event notification tags. Unknown schemas,
  missing/extra fields, malformed identifiers and expired messages are rejected.
- Added a singleton receipt ledger with atomic app-private, backup-excluded storage. It reserves
  binding/event pairs before future presentation, survives restart, serializes concurrent callbacks,
  prunes only expired entries and fails closed on corrupt history or failed persistence.
- This is a prerequisite component milestone, **not a receiving/display milestone**. The messaging
  service does not call the ledger yet: consuming a message before display exists would lose alerts.
  Presentation must first obtain a trusted organization association; the current registration
  response and local installation record contain binding/session IDs but no organization ID.
- Assignment events/schema/worker remain pending the numeric policy decision. The owner was asked
  about the proposed 24-hour expiry, 8 attempts, 30-day terminal retention and 30-day registration
  freshness; no approval is inferred from the implementation request or elapsed time.
- No business database migration, provider send, Firebase configuration change, visible alert or
  tap navigation was introduced. Existing unrelated changes were preserved.
- Tier A verification: 39 targeted push JVM tests pass (13 new); debug and device-test APK builds
  pass. AtomicFile device cases are compiled, **NOT RUN — device QA is the product owner's**.
  Exact commands and the owner storage-test command are in the
  [runbook](../../development/firebase.md#message-parsingdeduplication-verification--2026-10-16).
  Full lint/suites were not rerun; the previously recorded failures are not resolved by this slice.

### Android registration lifecycle — 2026-10-16

- Added Firebase Messaging 26.0.0 and Google Services 4.4.4, pinned in the Android version
  catalog. Variant-specific ignored client configuration is used exclusively; an unconfigured
  variant remains buildable with registration unavailable, without a development-project fallback.
- Explicitly selected token compatibility mode (`firebase_messaging_installation_id_enabled=false`)
  for the existing `FCM_TOKEN` API/Admin transport. Current SDK `getToken`/`deleteToken`/`onNewToken`
  remain supported in this mode but are deprecated; FID migration needs a later contract/adapter change.
  No Analytics dependency, Firebase authentication, project provisioning or live send was added.
- Added app-private, backup-excluded atomic installation identity/proof/revision/binding storage.
  Startup/session restoration, sign-in/account switch, token rotation, preference changes, locale,
  resume, connectivity restoration and daily active-process freshness trigger serialized latest-state
  reconciliation. No historical registration writes enter the business mutation outbox.
- Network/5xx/429 failures use exponential backoff capped at 15 minutes and a 30-second request
  deadline. Other failures stop until another trigger. Lost same-session responses recover via GET;
  irreconcilable ownership/revision/target conflicts rotate identity and provider target once per
  trigger rather than taking over a target. Reset intent is durable across process death.
- Observes Android notification permission, app availability and assignment-channel state separately
  from the installation toggle. Settings provides English/French explanation, an explicit Android 13+
  prompt, and access to Android settings after denial/blocking. No automatic startup prompt.
- Session changes cancel reconciliation and discard old responses; best-effort unregister uses the
  captured old credential and cannot delete a newer binding. Offline sign-out immediately clears the
  locally observed session. Added a refresh-response guard against restoring an ended session.
- Assignment enqueue/worker, data-message presentation, deduplication, notification icon and tap
  navigation remain pending. No alerts are displayed by this milestone. Numeric delivery/retention
  policy proposals remain unapproved and unimplemented.
- Automated verification: 65 final targeted push/session tests and both debug APK builds pass;
  full lint reports 14 unrelated errors (13 missing French Job/schedule translations and one
  non-observable locale read). Full-suite attempt: 939 tests, 26 unrelated Job/schedule failures,
  recorded in the runbook; no full-suite acceptance is claimed. Physical-device registration, Google connectivity and
  presentation: **NOT RUN — device QA is the product owner's** (`.clinerules/qa.md` §7.3).
  See the [Android registration QA runbook](../../development/firebase.md#android-registration-qa).


### Installation registration API implemented — 2026-10-16

The owner requested implementation after the design draft. This milestone covers registration;
it does not resolve or implement the proposed delivery expiry/retry/retention limits.

- Added additive migration `0026_push_installations`, with organization/member/user and session/user
  composite ownership constraints, unique provider-target digests and state validation. PostgreSQL
  integration setup applied the migration successfully. No historical migration was changed.
- Added authenticated PUT/GET/DELETE `/push/installations/:installationId`, installation-secret
  proof, optimistic revision checks, atomic newer-session rebinding and independent preference,
  permission and channel state. Older sessions cannot take back or unregister a newer binding.
- Server sign-out now clears targets/disables registrations in the revocation transaction.
  Registration operations recheck active session/user/membership/organization/role in a transaction.
- Explicit project/application configuration selects the accepted environment. Missing configuration
  leaves registration unavailable without preventing unrelated API startup. Registration is allowed
  with transport disabled and accurately reports that state.
- Installation proof is redacted from HTTP logging. SQL errors containing provider targets are
  replaced by controlled API failures. Responses never include provider targets or secret digests.
- Verification: API build/typecheck, 181 targeted push/auth unit tests and 28 PostgreSQL HTTP/integration
  tests passed; targeted lint passed. Production logger redaction smoke check passed with fake values.
  No Firebase send or physical-device verification was performed.
- Updated [registration contract](../../api/push-installations.md), [ADR-025](../../decisions/025-assignment-push-delivery.md),
  environment examples, Compose and the Firebase runbook. Android and unrelated feature work preserved.
- Next: confirm delivery policy choices, add event/delivery persistence and transactional assignment
  enqueue/worker, then integrate Android registration/presentation/navigation. Scheduled retention
  is not implemented. No production activation or end-to-end notification delivery is claimed.

### Registration and delivery design drafted — 2026-10-16

- Added [ADR-025](../../decisions/025-assignment-push-delivery.md) with the first assignment-event
  lifecycle, proposed tables/constraints/indexes and additive migration strategy, installation
  ownership/rebinding, transactional enqueue, worker claims and Android presentation safeguards.
- Added a [draft registration API contract](../../api/push-installations.md), including authenticated
  register/update/read/unregister, revision conflicts, installation proof and account-switch recovery.
  These routes are not implemented or approved contracts yet.
- Recommended policies for review: 24-hour event expiry, 8 attempts per device, 30-day terminal
  delivery retention, and invalidation after 30 days without registration refresh. These are
  explicitly proposals, not owner-approved product rules.
- Checked current official Firebase registration/reception/error guidance. It now documents
  FID-based registration alongside token support; the existing pinned transport is token-based.
  Android SDK selection must verify registration/send compatibility before implementation.
- Documentation only: no migration, runtime, credential, Firebase project or Android changes.
  Reviewed local links and whitespace; runtime/device tests do not apply to this draft milestone.
- Next: review the ADR/contract and confirm policy choices, then implement registration persistence
  and authenticated endpoints with PostgreSQL integration tests. Assignment enqueue/worker and
  Android lifecycle follow. BR-029 remains open for the broader event matrix.

### Development configuration verified — 2026-10-16

- Owner supplied development project ID `servora-5b056`, Android client configuration and a
  service-account credential file. Both configuration files reference that project; the Android
  configuration includes `com.servora.android.debug`.
- Server credential file moved from `.clinerules/.secrets/` to the intended root `.secrets/`
  directory, with file mode `0600`. The credential file, Android configuration and local `.env`
  are all confirmed git-ignored. No credential values are recorded here.
- Local host `.env` configured for Firebase using ADC and the root credential-file path.
- `npm run build`: passed. `npm run firebase:check` with network access: passed; the service
  identity acquired a Google access token without printing it.
- A separate FCM validate-only request (no delivery) failed with `app/network-error` in this
  execution environment. Sending permissions and API availability are therefore not verified.
- Android SDK integration, registration, assignment delivery and physical-device QA remain pending.
  Container ADC wiring and production configuration remain separate work.

### Initial backend milestone

Approved first-slice behavior:

- Notify newly assigned technicians, including when a Visit is created with an initial crew.
- Send to all enabled devices of each eligible recipient; suppress alerts to the assignment actor.
- Keep notification preference per installation.
- Use generic English/French text without customer details; tapping opens the relevant Job through
  authenticated navigation and a current authorized read.
- Crew removal, rescheduling, cancellation and office alerts remain outside this first slice.

Implemented foundation:

- Official Firebase Admin SDK pinned in the backend dependency lockfile.
- Separate push module with disabled-by-default configuration and explicit Firebase project ID.
- ADC-backed Firebase client, data-only Android sending, caller-specified bounded lifetime and
  stable event identifier, redacted failure classification and application-shutdown cleanup.
- Backend credential acquisition check (`npm run firebase:check`, after build).
- Focused transport/configuration tests and [setup instructions](../../development/firebase.md),
  environment examples, Compose configuration and ignored local Firebase files.

At the initial transport milestone, registration, delivery-intent schema, assignment trigger, worker
and Android integration had not landed. See later updates above for registration and development
configuration. Device delivery, production provisioning and activation remain unverified.
The existing local notification toggle still does not control backend push.

Verification for this milestone (targeted API scope):

- `npm run build`: passed.
- `npm test -- src/push`: 24 tests passed across 3 files, including disabled startup,
  project validation, provider error redaction/classification and Firebase shutdown lifecycle.
- `npx oxlint src/push/ src/app.module.ts`: passed.
- `npm run firebase:check` with push disabled: exited with the expected configuration instruction.
  Live credential acquisition, IAM permission and delivery are not verified without project setup.
- Android was not changed or built; physical-device QA remains pending.

Next implementation work: confirm the proposed expiry/retention/retry policy in ADR-025, then add
delivery persistence, enqueue assignment events atomically, implement the worker and connect the
Android lifecycle. Development project configuration and
host ADC acquisition are verified above; container configuration and real delivery remain pending.

## 1. Goal and original state at handoff

Proposed goal: alert a technician when the office assigns a Visit, and let the technician open the
relevant Job from the alert. Build this first complete flow before adding more events.

Repository findings before implementation (see the update above for changes):

- Android is native Kotlin/Compose; the API is NestJS with PostgreSQL/Drizzle.
- Firebase dependencies, configuration, messaging service and push registration are absent.
- `android/app/src/main/java/com/servora/android/data/preferences/NotificationPreferences.kt`
  stores a device-local toggle, defaulting to enabled. It does not request Android notification
  permission, register for push delivery, or update a backend preference.
- `api/src/notifications/notifications.module.ts` binds password-reset delivery. It is not a
  general operational notification pipeline. Existing email and SMS authentication delivery
  must retain their own contracts.
- Android debug uses application ID `com.servora.android.debug`; release uses
  `com.servora.android`. Firebase registration must match each actual build variant.
- `BR-029` requires notification behavior to be defined per event and audience.
- Existing session authentication, membership scope, capabilities and offline working-set rules
  remain authoritative.

Relevant references:

- [Business rules, including BR-029](../../../.clinerules/Business%20Rules.md)
- [Offline-first architecture](../../architecture/offline-first-architecture.md)
- [Session persistence](../../decisions/009-android-session-persistence.md)
- [Technician field experience](../../decisions/019-technician-field-experience.md)
- [Follow-up request contract](../../api/visit-requests.md)

## 2. Proposed scope and event matrix

Feature classification is **OPEN QUESTION** pending owner selection of MVP scope.

| Candidate event | Proposed recipient | Proposed destination | Scope recommendation |
| --- | --- | --- | --- |
| Technician added to a Visit's crew | Newly assigned technician | Relevant Job, with Visit context where supported | First vertical slice |
| Visit rescheduled | Affected assigned technicians | Relevant Job | Later slice |
| Visit cancelled | Affected assigned technicians | Relevant Job | Later slice |
| Follow-up request submitted or technician replies | Members authorized to review that request | Request review surface | Backlog |
| Follow-up request approved, rejected or returned for clarification | Requesting technician | Request details | Backlog |

Each row needs an exact trigger and recipient policy before implementation. In particular:

- Does creating an already-assigned Visit count as an assignment event?
- On crew replacement, do removed technicians receive a separate alert? They may lose read access.
- Should an actor receive an alert for their own action?
- Which devices receive an event when one user has several active sessions?
- Are office notifications in the Android app part of the selected scope?
- What text may appear on the lock screen? Recommend generic localized text without customer names,
  addresses, notes or evidence.

An alert about cancellation or removal must not grant access the recipient no longer holds. Its tap
behavior must handle an unavailable record explicitly.

Scheduled reminders, email/SMS operational alerts, an in-app notification inbox, unread counts,
read receipts, browser push, iOS, Firebase Authentication, Firestore and Analytics are **BACKLOG**
ideas outside the proposed first slice. None is a dependency of that slice.

## 3. Proposed architecture

```text
Authorized Visit assignment
  -> PostgreSQL transaction: assignment + durable delivery intent
  -> API-owned worker: resolve eligible registrations and recheck access
  -> Firebase Cloud Messaging
  -> Android notification
  -> authenticated navigation + current API read
```

- Keep the existing API and PostgreSQL as the system of record. Use Firebase for push transport.
- Keep provider-specific sending behind a small push-delivery interface; business events and
  recipient selection belong to the owning backend feature.
- Persist delivery intent atomically with the accepted business operation. A rollback produces no
  alert; a Firebase outage does not prevent assigning a Visit.
- Start with a PostgreSQL-backed worker in the existing backend architecture. Do not introduce a
  separate broker or microservice without a demonstrated need.
- Use bounded retries with backoff, explicit expiry, invalid-registration cleanup and a visible
  terminal failure state. Decide retry limits and retention before schema implementation.
- Give each event a stable identifier and enforce deduplication for repeated business requests.
  Worker claims must be safe with multiple API instances. A provider timeout can still cause
  duplicate delivery; Android presentation should deduplicate by event identifier.
- Provider acceptance means accepted for sending, not confirmed display or reading.
- Push is an alert, never a business mutation or the only synchronization trigger. The app must
  still refresh through its existing reads when opened or reconnected.

### Persistence and API design to complete before coding

Design and document only the structures needed for the selected event:

| Proposed record | Responsibilities |
| --- | --- |
| Push installation/registration | Installation identity, provider registration identifier, authenticated owner/session association, environment, locale, preference, last registration time and invalidation state |
| Delivery intent and attempts | Event identity/type, tenant and resource scope, intended recipient, expiry, attempt count, next attempt, claim/lease state and redacted failure code |

The exact tables, indexes, uniqueness constraints, lifecycle and retention remain to be designed.
Do not use an FCM registration identifier as proof of user identity. Registration endpoints must
derive ownership from the authenticated session, rather than trusting a submitted user ID.

Specify authenticated register/update/unregister operations, safe reassociation after account
changes, multiple-device behavior, and logout/session-revocation cleanup. Check active membership,
session and resource eligibility again before sending; an old queue row cannot preserve lost access.
Avoid logging raw registration identifiers or credentials.

Endpoint paths and DTOs are deliberately not an API contract yet. Publish the approved contract in
`docs/api/` before implementing either client.

## 4. Android behavior to define

- Add the Firebase SDK and messaging service, notification icon and channels after configuration
  and dependency review. Verify foreground, background and process-not-running behavior.
- Handle initial registration and changes to the provider registration identifier. Retry backend
  registration after connectivity returns and reconcile it after authentication or app startup.
- Request Android 13+ notification permission at an explained, relevant point. The current local
  default of `true` is not OS permission or evidence that the user agreed to a new remote setting.
- Decide whether the toggle applies per installation or per account; recommend per installation
  for the first slice. Keep user preference and actual OS/channel permission state distinct.
- Specify how disabling alerts affects queued deliveries and messages already in flight. Choose
  payload/presentation handling so background system display cannot bypass the intended policy.
- Clear pending navigation and displayed account-specific notifications on sign-out/account change.
  Offline sign-out must suppress local presentation without waiting for API cleanup. Server-side
  cleanup should reconcile when possible, with generic payloads limiting exposure while offline.
- Route taps through session restoration and normal authorization. Signed-out users must authenticate
  before protected content opens; an account switch must not open another account's destination.
- Fetch current data on open. If offline, use only the existing authorized working set and label it
  as the last backend answer; if unavailable or access is refused, show that state explicitly.
- Support English and French with stable event codes/localization keys. Decide whether Android
  resources or backend templates render text and how a changed app language updates registration.

## 5. Firebase and environment preparation

Recommended setup, to be performed in the implementation chat:

1. Establish the owner-controlled development and production Firebase projects and record non-secret
   project identifiers. Add staging separately only if an actual staging environment needs it.
2. Register the appropriate Android application IDs and place each variant's client configuration
   according to the agreed repository/configuration policy. Validate variant/project matching.
3. Enable/configure FCM and give the backend an appropriate server identity. Prefer environment
   credentials/workload identity where supported; keep private credentials in secret management.
   Never package server credentials into Android or commit them.
4. Document validated backend settings, disabled/local development behavior and deployment wiring.
   Add placeholder configuration examples only when actual variable names are implemented.
5. Verify supported device requirements, dependency versions and current Firebase registration APIs
   against official documentation at implementation time. Do not assume an earlier token API is
   still the recommended registration mechanism.
6. Prove delivery on a real development device before production activation.

Do not place secrets or credential JSON in this tracker. Project ownership and environment access
are implementation prerequisites; they do not prevent repository design work.

Official starting references:

- [FCM architecture](https://firebase.google.com/docs/cloud-messaging/fcm-architecture)
- [Android setup and notification permission](https://firebase.google.com/docs/cloud-messaging/android/get-started)
- [Registration lifecycle guidance](https://firebase.google.com/docs/cloud-messaging/manage-tokens)
- [Android message reception](https://firebase.google.com/docs/cloud-messaging/android/receive)

## 6. Implementation phases and completion checks

Phases 0–3 are implemented for the first assignment event. Phase 4 has automated evidence and a
resource-capped debug APK; physical-device delivery/presentation/navigation QA and deployment
activation remain outstanding. Production provisioning and additional events are outside this slice.

| Phase | Deliverable | Completion check |
| --- | --- | --- |
| 0 — Product decisions | Resolve first-event semantics, audience, preference scope, privacy, expiry and tap behavior; update BR-029 through the normal rule-change process | Approved rules distinguish the first slice from backlog |
| 1 — Domain and contract design | ADR, registration/delivery lifecycle, migration design, API contract and Android delivery/presentation choice | Tenant isolation, account changes, retries and deduplication are reviewable before code |
| 2 — Environment and registration | Firebase development setup, variant configuration, backend registration persistence and Android lifecycle integration | Development device registers under the correct authenticated session; permission and toggle states are accurate |
| 3 — Assignment delivery | Transactional delivery intent, worker, FCM adapter, localized notification and authenticated tap navigation | Manager assigns Visit; intended technician receives alert and opens current Job data |
| 4 — Verification and handoff | Focused automated checks, real-device QA, configuration/runbook updates and tracker evidence | Acceptance criteria below pass; production readiness and remaining work are stated explicitly |

First-slice acceptance criteria:

- The selected assignment trigger creates one durable event; a rolled-back or replayed mutation does
  not create a second logical event.
- Only eligible users in the correct organization receive alerts, including after membership changes.
- Provider outage does not fail the business operation; transient errors retry and permanent errors
  stop according to the documented policy.
- Concurrent workers do not send the same claimed delivery simultaneously; retries and duplicate
  incoming messages are handled without duplicate business effects.
- Denied permission, disabled channel and disabled app preference are handled accurately.
- Registration changes, app restart, multiple devices, logout, session revocation and account switching
  follow the approved ownership rules.
- Tapping works in foreground, background and cold start, and handles expired sessions, unavailable
  records, changed assignments and offline reads without bypassing access checks.
- English/French messages work, and lock-screen payloads follow the approved privacy policy.
- Automated coverage uses a fake push provider for domain/worker tests and real PostgreSQL for
  transaction, migration, deduplication and tenant-isolation integration checks.
- Physical-device QA proves delivery/presentation in the supported Android states, including
  permission denial and notification taps. Record Firebase test results separately from unit tests.

## 7. Next-chat handoff

Suggested starting instruction:

> Read `docs/tracker/shared/062-notifications-and-firebase.md` and the applicable project rules. Start
> from the latest implementation update. Core delivery limits are approved; implement the next
> device-delivery/worker milestone. Reinspect the working tree before changing files.

At the time this plan was written, the working tree contained extensive unrelated feature changes,
including pending migrations through `0025_job_quote_workflows`. Registration and assignment events have since added `0026` and `0027`. Recheck actual migration numbering
and ownership before generating anything; preserve that work and do not bundle it into this feature.

The original handoff was documentation-only. See the implementation update at the top for the
current code milestone; no full implementation phase is marked complete yet.

Cleanup: `make tidy` stopped the verification build daemons; the pre-existing API, MinIO and
PostgreSQL services were left running. Temporary verification logs were removed.
