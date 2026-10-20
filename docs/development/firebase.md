# Firebase Cloud Messaging setup

Firebase is the push transport. Servora authentication and PostgreSQL remain authoritative.
The first assignment notification flow is implemented: registration, transactional events, scoped
expansion/send/retry, opt-in scheduling, retention/target erasure and Android display/authenticated
taps. Physical-device acceptance and deployment activation remain outstanding in
[tracker 062](../tracker/shared/062-notifications-and-firebase.md). Transport and worker are separate switches.
See the current [device QA](#assignment-notification-device-qa) and [completion evidence](#completion-verification--2026-10-16);
component milestone notes further below are historical.

Local setup verified on 2026-10-16: development project `servora-5b056`, debug Android configuration
and server ADC are present. The backend credential check passed. FCM validate-only sending reached
a network error in the execution environment; sending permissions and device delivery are unverified.
Private credentials and environment values remain in ignored local files.

## Development project details needed

Create an owner-controlled development Firebase project in the
[Firebase console](https://console.firebase.google.com/). Supply its **project ID** (not project number
or display name). Register the Android app with package name **`com.servora.android.debug`** and
download its `google-services.json` to:

```text
android/app/src/debug/google-services.json
```

That path is git-ignored. Firebase Messaging and Google Services build integration consume only the
configuration for the selected variant. Without it the app builds and runs with push registration
unavailable. A mismatched package fails the Google Services task; release never falls back to debug.
Removing configuration also removes previously generated Firebase resources on the next build.
Keep production in a separate Firebase project and register **`com.servora.android`** there, using
`android/app/src/release/google-services.json`. A production file is not needed for development.
Analytics, Firebase Authentication and Firestore are not required for this push integration.

An Android Firebase API key does not authorize server sending. The backend needs a Google service
identity authorized to send to the selected project. Enable the **Firebase Cloud Messaging API
(HTTP v1)** and grant the server identity the **Firebase Cloud Messaging API Admin** role on that
project. Use workload identity/attached service credentials where the deployment supports them.

## Backend configuration

The backend uses official `firebase-admin` with Application Default Credentials (ADC).
Set these in the repository-root, git-ignored `.env` for a host process:

```dotenv
PUSH_PROVIDER=firebase
PUSH_WORKER_ENABLED=true
FIREBASE_PROJECT_ID=your-development-project-id
PUSH_ANDROID_APPLICATION_ID=com.servora.android.debug
```

For local development that uses a service-account JSON key, store it outside the repository or
in the ignored `.secrets/` directory, restrict its file permissions, and set its absolute path:

```dotenv
GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/private-service-account.json
```

Do not paste a private key into chat, put it into Android, or commit it. ADC can also resolve
credentials from the runtime environment, so a key file is optional when that environment already
provides a suitable identity. `FIREBASE_PROJECT_ID` is explicit to avoid accidental project selection.

The default `PUSH_PROVIDER=disabled` needs no Firebase credentials and reports delivery as disabled,
never accepted. Unknown providers and missing/malformed project IDs fail configuration validation
at API startup. ADC initialization checks local credential configuration; actual credential exchange,
IAM permission and provider connectivity require runtime checks.

From `api/`, run:

```bash
npm run build
npm run firebase:check
```

The check acquires an access token without sending a push or printing tokens/credentials. Success
proves credential acquisition only; it does not prove FCM IAM permissions, Android registration,
notification presentation or delivery. Those checks follow when the device registration flow exists.
Keep `PUSH_PROVIDER=disabled` until the server identity is configured.

## Registration API configuration

Apply additive migration `0026_push_installations` through the normal migration runner before
starting the updated API. Existing installations are not backfilled. The new endpoints require
both `FIREBASE_PROJECT_ID` and `PUSH_ANDROID_APPLICATION_ID`; missing settings return `503` on
registration without preventing unrelated API startup. The application ID must be exactly
`com.servora.android.debug` for debug, or `com.servora.android` for release. Production refuses
the debug ID. A server accepts one configured Android variant; use separate environments/projects
for debug and release.

Registrations can reconcile with `PUSH_PROVIDER=disabled`; their response reports delivery disabled.
The configured project/variant is recorded by the backend, not trusted from client ownership fields.
No send happens during registration. Do not manually paste a device target or installation secret
into logs or documentation. Android now reconciles these endpoints; physical-device registration
against Firebase and the backend remains awaiting product-owner QA.

## Assignment event migration and verification

Migration `0027_push_assignment_events` adds durable recipient intent for new crew assignments,
including initial scheduled crew. It adds scoped unique keys to parent tables and scoped foreign
keys, with no assignment backfill or data rewrite. Adding the parent constraints takes table locks;
apply through the normal migration runner during an appropriate deployment window. Historical
migrations remain unchanged. The local PostgreSQL integration setup applied `0027` successfully.

Events store routing IDs, actor user, accepted Visit version and database-owned occurrence/expiry.
The approved expiry is 24 hours from the database transaction clock. Exact business-key replays
keep their original UUID and expiry. Crew/version/history/event writes share one transaction;
the write path performs no Firebase request. Historical ad-hoc conversion/linking emits nothing.

The other approved limits are at most 8 attempts per device, 30-day terminal delivery retention,
and invalidation after 30 days without registration refresh. Those mechanisms await the worker
milestone. This migration creates **no delivery table, worker, send, cleanup schedule or alert**.
Events expire while that milestone is pending. Deploy the eventual worker disabled first so it can
suppress expired/disabled work before transport activation; do not turn existing intent into a
backlog for later activation. Activation still requires the Android presentation and tap safeguards.

Tier B API verification on 2026-10-16, from `api/`:

```bash
npm run typecheck
npm run build
npm run lint
npm test
npm run test:e2e
```

Typecheck/build pass. Lint exits successfully with one existing unused-variable warning in
`test/ad-hoc-work-report-options.e2e-spec.ts` (`assignedProperty`). All 682 unit tests pass.
Full PostgreSQL suite: **472 tests, 466 passed, 6 failed**. All 13 new transactional event cases
pass, including concurrent versioned/unversioned edits, rollback of edits and initial Visit creation,
replay identity, actor suppression, follow-up approval replay and relational constraints. Existing
ad-hoc HTTP cases now also verify that conversion/linking creates no assignment events and pass.

The six full-suite failures are outside the changed enqueue paths: four photo/audio evidence-removal
tests expect `403` but receive `201`, and two Job Details exact-object assertions omit the existing
`purposeCode: WORK` field. No evidence-removal permission or Job read behavior/assertion was changed
by this milestone. Repository-wide API acceptance is not claimed. No live Firebase request, device
command, Android rebuild or production activation was performed. Verification left no new process;
existing API, MinIO and PostgreSQL services were preserved.

## Docker and deployment

Compose passes `PUSH_PROVIDER`, `PUSH_WORKER_ENABLED`, `FIREBASE_PROJECT_ID` and `PUSH_ANDROID_APPLICATION_ID` into the API. It deliberately does not pass
a host's `GOOGLE_APPLICATION_CREDENTIALS` path: that path is not a container credential mount.
For the first local credential check, run the compiled API tooling on the host.

A container deployment must provide ADC through its runtime identity or a private read-only secret
mount, with `GOOGLE_APPLICATION_CREDENTIALS` pointing to the **container** path. Configure the mount
in a private deployment override; do not copy credentials into the image. Without that setup, leave
Compose push disabled. No production project or IAM configuration has been provisioned by this work.

For local development, Make automatically includes `.secrets/compose.push.yml` when that private
file exists. It can mount the host `.env` credential path read-only and override the API's
`GOOGLE_APPLICATION_CREDENTIALS` with the container path. Run the API with a non-root UID/GID
that can read the host credential file; keep the file private. After changing `.env`, recreate the
API (a restart alone does not reload environment values):

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml \
  -f .secrets/compose.push.yml up -d --no-deps api
docker compose exec -T api node dist/push/check-firebase.js
```

Credential acquisition does not prove FCM send permission or phone delivery. Register the phone
before creating a fresh assignment; events expanded with no eligible installations are not replayed.

## Transport contract and verification

`api/src/push/` is separate from password-reset notification delivery. It sends one data-only Android
message, carrying a stable `eventId` and caller-supplied opaque routing fields. The event ID overrides
any conflicting data field. Lifetime must be a positive integer no greater than FCM's 28-day limit;
event owners must supply the remaining event lifetime. High priority is for events intended to
produce a visible alert, subject to Android's permission, preference and session checks.

No notification payload is supplied for automatic background display: the future Android receiver
must check the local session and device preference before presentation. Callers must not include
customer names, addresses, notes or evidence. The transport does not select recipients, recheck
resource access or retry; the durable delivery worker will own those responsibilities.

Provider acceptance is distinct from display/read confirmation. Failures expose a controlled kind:
`invalid-registration`, `retryable`, `configuration`, or `rejected`. Raw provider errors are not
propagated or logged. Only explicit invalid/unregistered-token errors authorize token invalidation;
an invalid payload or wrong project must not delete a device registration.

Targeted automated checks:

```bash
cd api
npm test -- src/push
npm run build
```

Tests use an injected messaging client; they neither contact Firebase nor require credentials.
Real delivery and physical-device acceptance remain pending.

Official references:

- [Firebase Admin setup and ADC](https://firebase.google.com/docs/admin/setup)
- [Server sending and cross-project permissions](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)
- [Android setup](https://firebase.google.com/docs/cloud-messaging/android/get-started)
- [Google Services configuration](https://firebase.google.com/docs/android/google-services-plugin-and-file)


## Android registration lifecycle

Android message parsing and durable binding/event deduplication primitives are also implemented;
see the [assignment data contract](../api/assignment-push.md). They are not connected to reception,
presentation or navigation yet, and do not change the registration-only QA expectations below.

The Android version catalog pins Messaging **26.0.0** and Google Services **4.4.4**. The current
Messaging SDK supports FID registration and token compatibility. The backend pins Admin **14.5.0**.
Servora explicitly sets
`firebase_messaging_installation_id_enabled=false` and uses the deprecated but supported
`getToken`/`deleteToken`/`onNewToken` APIs to match backend `FCM_TOKEN` and Admin token sends.
Do not replace a token with a Firebase Installation ID. FID targeting needs a reviewed backend
contract and transport change. Compatibility was checked against the
[SDK implementation](https://github.com/firebase/firebase-android-sdk/blob/main/firebase-messaging/src/main/java/com/google/firebase/messaging/FirebaseMessaging.java)
and [release notes](https://firebase.google.com/support/release-notes/android); automated builds
are distinct from physical-device compatibility/delivery verification.

FCM auto-init and notification delegation are disabled. Token acquisition begins when a restored
Servora session reconciles. Session restoration also runs when a service starts the process without
an activity, through the same idempotent authentication owner. No Analytics SDK is included. Token callbacks request reconciliation
of current SDK state, rather than replaying their historical value. Data-message display is not
implemented in this milestone; do not use Firebase Console notification messages as an acceptance
test for Servora's future preference/binding presentation safeguards.

The app keeps the Servora installation UUID, 32-byte random proof, acknowledged revision,
binding/session identity and pending target-reset flag together in an atomic file under
`noBackupFilesDir`. The provider target is fetched from the SDK and is not copied into that file.
Losing or corrupting installation state first persists a fresh identity with pending reset, then
deletes/reacquires the provider target. Losing a PUT response in the same session recovers through
GET. A new session uses the last persisted revision; an inaccessible binding or irreconcilable
conflict causes a single identity/target rotation per trigger. It never repeatedly takes over an
existing target. Provider values/proof/request bodies are not logged.

Reconciliation runs on session restoration/sign-in, token/preference/locale changes, resume,
network restoration, and once daily while the process lives. It carries current state only, with
no business outbox writes or background heartbeat. Transport failures retry with exponential
backoff from one second to fifteen minutes; each attempt has a thirty-second deadline. Permanent
errors wait for another trigger. New state cancels obsolete work and starts from the current
session/preferences; responses from another session are discarded. Best-effort unregister has a
ten-second deadline and uses the ending session's captured access token. Backend revocation also
clears its registrations; offline cleanup cannot promise an immediate remote deletion.

The local toggle remains per installation, defaulting to enabled, and does not grant Android
permission. Settings shows OS availability separately, provides an English/French explanation
before the Android 13+ prompt, and opens Android settings after denial or channel blocking.
Permission history is stored locally; it is never inferred from the toggle. Startup does not
prompt. The `visit_assignments` channel is created and observed without resetting user choices.

## Android registration QA

Status: **Awaiting physical QA**. The agent does not run device commands (`.clinerules/qa.md` §7.3).
Debug APK after a successful build: `android/app/build/outputs/apk/debug/app-debug.apk`.
Prerequisites: a Google Play services device, ignored debug Firebase configuration matching
`com.servora.android.debug`, reachable API configured for that same project/application, and applied
migration `0026`. `PUSH_PROVIDER=disabled` is sufficient for registration testing and returns
`deliveryEnabled=false`; transport activation is a separate step. Never expose registration targets,
proof, session tokens or full request bodies while observing these checks.

1. Install the debug APK and sign in. Confirm the backend has one installation for the active
   Android session (inspect only safe fields: ID, session/binding ID, revision, preference,
   permission, channel state and registration timestamp). Startup must show no permission prompt.
2. Open Settings. On Android 13+, allow notifications using the explicit action and explanation.
   Confirm permission/channel availability changes independently of the Servora toggle. Repeat with
   denial, then grant in Android settings and return; the next registration must reflect each state.
3. Toggle Servora alerts off/on; verify only this installation's backend preference changes.
   Block the assignment channel or all app notifications in Android settings and return. Verify
   channel availability becomes false for a blocked channel and permission becomes denied for an
   app-wide block, without changing the Servora toggle.
4. Change app language English/French; verify `en-CA`/`fr-CA` and localized Settings explanation.
5. Disconnect networking, change the toggle, restart, then reconnect. The final registration must
   reflect the current toggle/locale/OS state without replaying intermediate preferences. Reopen
   after process death and verify stable installation identity and an advancing acknowledged revision.
6. Sign out offline, then sign into another account and reconnect. Verify the new registration binds
   to the new active session, and delayed old-session cleanup cannot disable it. Sign out online and
   verify the backend clears that session's target/registration.
7. Test with a second device: each must retain its own installation identity and preference.
   Clear app data/reinstall on one device and sign in; verify fresh identity/target acquisition and
   no takeover of the other device's registration.

Expected: registration only, with accurate session/preference/OS state. Assignment sends, alert
presentation, notification taps, foreground/background/cold-start receipt and production activation
remain pending. No successful registration response proves a real-device alert was delivered.


### Automated verification for this milestone

Scope: Tier B attempted for Android, including shared session persistence/renewal regression.
The final targeted registration/session run passes **65 tests** (26 registration tests and 39 session
regression tests). Commands from `android/`:

```bash
./gradlew testDebugUnitTest --tests 'com.servora.android.data.push.*' --tests 'com.servora.android.data.session.*' assembleDebug assembleDebugAndroidTest lintDebug --max-workers=2
./gradlew processReleaseGoogleServices --max-workers=2
```

Debug APK and device-test APK assembly pass. The release Google Services task passes without a
release client configuration; it does not use development configuration. Full `lintDebug` ran and
reports **14 errors** in existing Job/schedule work: 13 missing French translations and one
`NonObservableLocale` read in `AdHocWorkReportForm.kt`. There are no push-code lint errors. Two
`UseKtx` warnings in the new permission observer were corrected before the final APK rebuild.
The full lint check remains failing; no baseline or lint suppression was added.
The three installation-storage instrumented cases are compiled, **not run**; they use isolated
storage in the test APK and require product-owner device QA.

A full `./gradlew testDebugUnitTest` was also attempted as part of the initial combined build:
**939 tests, 26 failures**. The failures are in existing unrelated Job/schedule behavior assertions:
`JobPhotoCaptureViewModelTest` (24; its fake Job has no selected Visit),
`JobDetailsOverviewModelTest` (1; current-Visit expansion), and
`TechnicianSchedulePresentationTest` (1; JDK Canadian month abbreviation `Sep.` versus `Sep`).
These do not depend on the push/session implementations. Their owning feature tasks retain those
behavior assertions. The full suite is not green and this milestone does not claim repository-wide
acceptance. Stale instrumented-test adapters were updated to current Job note/review/navigation
interfaces so device-test compilation could proceed.

Cleanup: `make tidy` stopped the verification build daemons; the pre-existing API, MinIO and
PostgreSQL services were left running. Temporary verification logs were removed.

### Message parsing/deduplication verification — 2026-10-16

Tier A, Android component milestone only. From `android/`:

```bash
./gradlew testDebugUnitTest --tests 'com.servora.android.data.push.*' assembleDebug assembleDebugAndroidTest --max-workers=2
```

Result: 39 push JVM tests pass, including 13 new parser/ledger tests. Both APK builds pass.
No full lint or full suite was repeated for this component; the prior milestone's unrelated
full-suite/lint failures remain recorded above. The new isolated AtomicFile tests compile but are
**NOT RUN — device QA is the product owner's**. The owner can run only these storage cases with:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.servora.android.data.push.PushReceiptStorageTest
```

Those cases check reopening and recovery after interrupted replacement. They do not test FCM
delivery, local presentation or navigation. The component is deliberately not consuming messages
until those paths exist. No live Firebase request or device command was run for this milestone.
Cleanup: `make tidy` stopped the Gradle daemons; a follow-up process check confirmed Gradle/Kotlin
daemons exited. Existing API, MinIO and PostgreSQL containers were left running.

## Delivery persistence and expansion verification

Migration `0028_push_deliveries` adds delivery records and nullable `push_installations.eligible_since`.
It takes an ALTER TABLE lock while adding the nullable column, does not rewrite/backfill installation
eligibility, and creates an empty delivery table with its constraints/indexes. Apply it through the
normal migration runner. Historical migrations remain unchanged. Existing clients refresh through
the same registration API; the wire contract is unchanged. Until that refresh, existing installations
are conservatively excluded from expansion. The local integration setup applied `0028` successfully.

`expandAssignmentEvents(db, config, { organizationId }, batchSize)` performs one atomic snapshot
(default 25 events, maximum 100) with scoped row locking and SKIP LOCKED. It has no startup/scheduler
hook or provider dependency. Calling with disabled transport consumes eligible work as SUPPRESSED;
expired/no-device/ineligible events also get an expanded marker. Re-enabling transport or registering
a device later does not replay those events. The caller must enumerate authorized organization scopes
when the worker is implemented; tests always use their own fixture organization.

Registration now preserves continuous eligibility during active refresh and restarts it on re-enable,
rebind or stale refresh. Disable/unregister/rebind terminalize outstanding old-binding deliveries.
The schema prepares the attempt/claim lifecycle, but **claiming, sending, retries, scheduled retention,
stale-target erasure and Android display/taps remain unimplemented**. No production activation or
live Firebase delivery is claimed. A send worker must recheck eligibility and binding/revision after
claims and immediately before network I/O, including sessions revoked independently of registration.

Tier B API verification (schema/registration changes), from `api/`:

```bash
npm run typecheck
npm run build
npm run lint
npm test
npm run test:e2e
```

Typecheck and build pass. Lint exits successfully with one existing unused-variable warning in
`test/ad-hoc-work-report-options.e2e-spec.ts` (`assignedProperty`). All **682 unit tests pass**.
Full PostgreSQL integration run: **509 tests, 503 passed / 6 failed**. All **37 new delivery-expansion
cases pass**, together with the existing registration and assignment-event coverage. The six
pre-existing failures remain four evidence-removal permission assertions (expected 403, got 201)
and two exact Job Details assertions missing `purposeCode: WORK`; none is in the changed paths.
Repository-wide acceptance is not claimed. The initial targeted expansion run passed all 34 initial
cases; the final full run includes three additional isolation/freshness/claim-cancellation cases.

The first sandboxed integration attempt could not reach local PostgreSQL (`EPERM`); the authorized
rerun used local database access. Verification was corrected to require explicit fixture tenant scope.
A read-only audit found no non-fixture events modified during the initial test window, so no repair
was needed. No new long-lived process or device interaction was introduced.

The expansion integration cases use real PostgreSQL and fake provider targets without provider I/O.
They cover all-device snapshots, concurrent expansion, SKIP LOCKED, atomic rollback, tenant isolation,
late registration, expiry, stale registrations, session/member/resource/capability changes, role and
direct grants, disabled transport, refresh/re-enable/rebind/unregister, claim cancellation, immutable
terminal history, batch bounds and SQL state/relationship constraints. No Android or device commands
are needed for this backend component milestone.


## Scoped delivery worker verification

`runAssignmentDeliveryBatch(db, config, provider, { organizationId }, batchSize)` is an explicit
backend component, defaulting to 25 events/deliveries and accepting 1–100. It expands events, commits
claims, then starts the bounded send batch concurrently. Callers supply a trusted tenant scope and
an injected provider; no scheduler, startup hook, HTTP endpoint or manual live-send command is wired.
No environment values, credentials, Firebase project settings, dependencies or migrations changed.
The production transport is not invoked by these tests.

The component uses the same current eligibility checks as expansion and reads targets only for
matching event organization/binding. Due PENDING and abandoned CLAIMED rows are locked with
SKIP LOCKED. Claims advance attempts, capture revision and have a new UUID/120-second lease.
Expired leases retain their consumed attempt; eight attempts are never reset. Provider I/O runs
outside transactions with a 30-second worker deadline and freshly computed positive remaining TTL.
A lease with at most 30 seconds remaining cannot start another send. Completion locks installation
before delivery, matching registration lock order, and checks live claim/lease/binding/revision.
A rotated revision leaves an in-flight result ignored until lease recovery; no old-target error
can erase a new token. Preflight revision changes return eligible work to PENDING if budget remains.

Transient failures retry in 1/2/4/8/16/32/60 minutes plus 0–20% additive jitter, capped at event expiry.
`PushDeliveryError.retryAfterMs` supports a longer controlled provider delay when available; the
pinned Admin adapter exposes no Retry-After header, so it uses the approved schedule. Failure logs
contain only delivery ID and controlled classification. Explicit invalid-registration errors erase
the exact target revision and advance its revision; other pending/claimed binding deliveries are
suppressed. Configuration/payload failures become FAILED without invalidating the installation.
Disabled configuration or a disabled provider result becomes SUPPRESSED, never ACCEPTED.

The worker deadline cannot abort the underlying Firebase SDK request. A timed-out send may still
be accepted, and abandoned leases can resend an event. Stable event/binding identifiers and durable
Android deduplication remain essential; ACCEPTED proves neither display nor read. Concurrent
revocation can race provider acceptance, so Android session checks remain required.

Remaining: scheduling with bounded trusted organization enumeration, stale target erasure, approved
30-day terminal retention, Android presentation/taps and product-owner physical-device QA. Do not
activate a scheduler before the receiving/presentation path and rollout are ready.

Tier B API verification commands, from `api/`:

```bash
npm run typecheck
npm run build
npm run lint
npm test
npm run test:e2e
```

Targeted component checks:

```bash
npm test -- src/push
npm run test:e2e -- test/assignment-delivery-worker.e2e-spec.ts test/push-delivery-expansion.e2e-spec.ts
```

Results: typecheck and build pass. Full lint exits successfully with the existing unused-variable
warning for `assignedProperty` in `test/ad-hoc-work-report-options.e2e-spec.ts`; no new lint warnings.
All **686 unit tests pass**, including the four new retry/deadline tests. The full PostgreSQL suite
reports **549 tests: 543 passed / 6 pre-existing failures**. All **40 worker integration cases pass**,
including three final lease/provider-result cases added after the initial targeted 74-case run
(37 worker + 37 expansion). The six existing failures remain four evidence-removal permission
assertions (expected 403, got 201) and two Job Details exact-object assertions missing `purposeCode`.
No repository-wide acceptance is claimed. Changed TypeScript files pass `prettier --check`; all
ten changed files pass scoped whitespace checks. Repository-wide `git diff --check` still reports
an existing extra blank line in unrelated `docs/tracker/shared/061-assessment-quote-workflow.md`, left intact.

The PostgreSQL retry initially hit sandbox `EPERM`; the rerun used authorized local database access.
The first implementation run caught retry timestamp serialization and incomplete test fixtures;
those were corrected before final verification. Tests use only fixture tenant scopes, real local
PostgreSQL and fake providers. No live Firebase request, device command, Android rebuild or project
configuration change was performed. Verification starts no long-lived service; existing API,
PostgreSQL and MinIO services are preserved. Temporary verification logs were removed.

Coverage includes all-device expansion/send, exact wire fields and TTL, concurrent workers,
SKIP LOCKED and batch limits, due times, lease recovery and exhausted budgets, short leases,
pre-send eligibility changes, role/direct grants, expiry, disabled configuration/provider, controlled
terminal errors, retries and Retry-After/expiry bounds, token rotation before/during I/O, unregister,
rebind, late invalidation after lease recovery and cancellation of other outstanding binding work.
The four unit tests verify all seven retry delays, jitter/input bounds and provider deadline cleanup.
No Android or physical-device acceptance is claimed.

Provider guidance rechecked 2026-10-16:
[FCM error handling](https://firebase.google.com/docs/cloud-messaging/error-codes) and
[message lifetime](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan).

## Assignment notification device QA

**Status: Awaiting product-owner physical QA.** The agent never runs `adb`, installs on the phone
or starts a device test. Install the current `android/app/build/outputs/apk/debug/app-debug.apk`
with Android Studio or by transferring/opening the APK on your phone. An already installed APK from
an earlier milestone does not contain the receiving path.

Environment:

1. Use a device with Google Play services and the debug Firebase client for
   `com.servora.android.debug`. The device must reach the API URL baked into the APK
   (`servora.api.baseUrl` in ignored `android/local.properties`, or the build override).
   This session's APK uses **`http://127.0.0.1:3000/`**. With your phone connected over USB,
   you can run `adb reverse tcp:3000 tcp:3000` yourself to map the phone's port to the host API.
   Reapply the mapping after disconnecting. The agent did not run that device command.
2. Apply migrations through `0028` using the normal migration tooling. This completion adds no new
   migration. Use the development project matching the debug client and backend registration.
3. Run the new backend with `PUSH_PROVIDER=firebase`, `FIREBASE_PROJECT_ID` set to that project,
   `PUSH_ANDROID_APPLICATION_ID=com.servora.android.debug`, and valid server ADC with FCM HTTP v1
   enabled and permission to send. Keep `PUSH_WORKER_ENABLED=false` while preparing/installing.
4. After the receiving APK is installed and signed in, set `PUSH_WORKER_ENABLED=true` in the host
   API's environment and restart that API. For the repository's host command: from `api/`,
   `npm run start:prod` loads the root `.env`; stop the old host process first to avoid a port clash.
   Do not use the default production Compose API with debug application configuration: its startup
   intentionally rejects the debug application ID and host credential paths are not mounted.
   Use a separately configured development process/container with appropriate ADC instead.

For this workspace, the existing Compose API on port 3000 was left running. To test the newly
compiled backend without replacing it, start a second **host** API on port 3001 from `api/` after
checking the development Firebase/ADC settings above:

```bash
PORT=3001 PUSH_WORKER_ENABLED=true npm run start:prod
```

For that setup, your phone connection is `adb reverse tcp:3000 tcp:3001` (run by you). This maps
the APK's port 3000 to the new host API's port 3001. Point both manager and technician installations
at this same updated backend. Close the host API with Ctrl+C when QA ends; its worker drains on
shutdown. This is an optional QA setup instruction, not a service started by the agent.

The worker waits five seconds, processes a keyset page of up to 25 trusted organizations, then waits
five seconds after the page completes. One organization batch expands/claims at most 25 events/
deliveries; sends in that batch run together with existing deadlines. A multi-page/busy environment
can take longer. Every organization also gets a bounded maintenance batch. Maintenance runs only
when the worker is enabled. Multiple instances use database SKIP LOCKED/claim fencing.
With `PUSH_WORKER_ENABLED=true` and `PUSH_PROVIDER=disabled`, eligible events are **consumed and
suppressed**, not saved for a future enable. Create fresh assignments after enabling real transport.
Do not use Firebase Console's notification composer for this flow; it creates a different message
kind that bypasses the app's data-message presentation path.

Exact phone checks (use a manager account on a second installation and the technician on the phone):

1. Technician: sign in, open Settings, keep Servora notifications on and explicitly allow Android
   notifications. There must be no permission prompt just from startup/sign-in. Wait for registration.
   Manager: schedule a future Visit with this technician newly added to its crew. The assigning
   manager must be a different user. Expected: one generic “New Visit assignment” alert, with no
   customer name/address/note. Tap it: the correct Job and assigned Visit open.
2. Repeat with the technician app visible, in background, and after swiping it away normally.
   Tap each alert. Expected: the same protected destination, including when the app process starts
   cold. Do not use Android Force stop for the cold-start delivery check; reopen after a Force stop.
3. Leave that Job open, then remove/readd the technician using the manager account. Tap the new alert.
   Expected: a fresh Job read and correct Visit context. Role-only crew changes, removals, unchanged
   crew and assignments the technician makes to themselves must not produce an assignment alert.
4. Switch the technician app to French. Repeat a fresh assignment while backgrounded, including a
   normal process restart. Expected: French generic alert and the same destination. Return to English
   and repeat. Check the notification's monochrome status icon and lock-screen privacy.
5. Turn the Servora toggle off, then make a fresh assignment. Expected: no alert. Reenable: no old
   suppressed alert replays; a new assignment works. Repeat with Android notification permission or
   the Visit assignments channel blocked, then restore it and return to the app.
6. Receive an alert, sign out offline, and check the tray. Expected: that account's alerts disappear
   immediately. Sign into another account and reconnect: no pending destination from the old account
   opens and no delayed old-binding message displays. Sign out/in to the original account: only fresh
   assignments for its new binding should notify.
7. Receive an alert, then remove the technician's access/assignment before tapping. Expected: the
   normal authorized Job read shows the current permitted result or explicit denial/unavailability.
   For offline taps, first cache an accessible Job, disconnect and tap: only the authorized Room
   working set may appear, labeled as the last backend answer. An uncached Job must show unavailable.
8. With two technician devices, verify both eligible installations receive a fresh assignment.
   Disable one device's toggle: the other still receives fresh assignments. Backend fake-provider
   tests cover retries/duplicate receipts; if a real message is retried, it must not alert twice for
   the same event/binding, including across process restart.

Record device/Android version, language, foreground/background/cold-start mode, step and outcome.
Provider ACCEPTED is not phone delivery/read evidence. Google connectivity, IAM and OEM background
restrictions must be checked on the real device. Production project/release credentials and rollout
remain separate deployment work; no production activation or successful live send is claimed here.

If no alert arrives, check only safe backend fields: event expanded/expiry, delivery state/attempts/
controlled last failure, installation state/revision/preference/permission/channel/registered time.
Do not print targets, installation proof, credentials or HTTP bodies. PENDING means not yet sent or
awaiting retry; FAILED configuration needs ADC/project/IAM review; SUPPRESSED means an eligibility or
transport gate blocked it; ACCEPTED means Google accepted it, not that Android displayed it.

## Completion verification — 2026-10-16

Scope: full API checks plus a deliberately reduced Android check at the owner's request to avoid
heavy Gradle tests. No Gradle unit/instrumented test task, full Android lint or device command ran.

From `api/`:

```bash
npm run typecheck
npm run build
npm run lint
npm test -- --maxWorkers=1
npm run test:e2e -- test/assignment-delivery-worker.e2e-spec.ts test/push-installations.e2e-spec.ts --maxWorkers=1
npm run test:e2e -- --maxWorkers=1
```

Typecheck/build pass. Full API lint passes with the existing unused `assignedProperty` warning in
`test/ad-hoc-work-report-options.e2e-spec.ts`. All **691 API unit tests pass** (54 push tests).
Full PostgreSQL regression: **553 tests, 547 passed / 6 previously recorded failures**: two exact
Job Details Visit-object assertions omit `purposeCode`, and four existing evidence-removal permission
assertions expect 403 but receive 201. These remain outside this Firebase change; repository-wide
acceptance is not claimed. All push event/expansion/worker/registration cases pass.
The targeted worker/registration PostgreSQL run passes **56 tests**, including authenticated
organization scope, stale-target erasure/claim cancellation, tenant isolation, bounded cleanup and
retention preserving pending/recent history. The first new retention fixture violated the existing
accepted-attempt constraint; it was corrected to represent a consumed attempt before the passing run.
A sandboxed PostgreSQL retry encountered EPERM; the authorized local-database run passed.
Integration tests explicitly disable the scheduler and use fake providers, never live Firebase.

Resource-capped debug APK build, from `android/`:

```bash
./gradlew assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.parallel=false \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

Only one Gradle worker, 1.5 GB build heap and in-process Kotlin; no long-lived Kotlin daemon.
The initial sandbox lacked writable Gradle cache access; the authorized build passed. A final
rebuild includes the lifecycle, language and explicit permission checks and passes without new
compiler warnings. Final APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
All builds used `--no-daemon`; no Kotlin compile daemon was started.

From the repository root, the small parser/binding/receipt tests run directly against the compiled
debug classes without Gradle tests:

```bash
python3 android/tools/check-push-jvm.py
```

All **16 tests pass**, with a 384 MB compiler heap and 128 MB test heap. The script uses cached pinned
Kotlin/JUnit/serialization dependencies, creates temporary test classes and removes them on exit.
It tests strict wire parsing, binding/session/organization isolation, expiry, durable deduplication,
concurrency, corruption and storage failures. It does not exercise Android notification APIs, FCM,
Compose or ViewModels. The new JobDetailsViewModel notification reread test is present but **NOT RUN**
under the owner's heavy-Gradle-test constraint, as are device test-source compilation and Android lint.
Prior Android full-suite/lint failures documented above have not been reclassified as fixed.

Physical delivery, notification taps, permissions/channel UX and background/cold-start behavior:
**NOT RUN — device QA is the product owner's**, using the steps above. Existing services and private
Firebase/environment files were preserved. The worker remains off unless explicitly enabled by the
operator after installing the receiving app. No live Firebase request was made during completion.

References rechecked for receiving and locale behavior:
[FCM data-message reception](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
[notification navigation](https://developer.android.com/develop/ui/views/notifications/navigation),
[localized context outside an Activity](https://developer.android.com/reference/androidx/core/content/ContextCompat#getContextForLanguage(android.content.Context)).

Completion hygiene: `make android-stop` reports no running Gradle daemons after the final
`--no-daemon` build. Temporary compiler/test outputs and verification logs were removed. No
long-lived process or device interaction was started; existing backend/storage/database services
were left in place. The private root `.env` was not changed or used to activate scheduled sends.
