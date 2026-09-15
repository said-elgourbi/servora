# Tracker 025 — Android offline engine (Room working set and outbox)

**Status: COMPLETE for the Android implementation; physical-device QA is the product owner's**

Date: 2026-09-14
Predecessors: `docs/tracker/013-property-lifecycle.md` (the server half of the contract),
`docs/decisions/012-property-lifecycle-and-permissions.md` (D7, which named the standard)
Business rules: `BR-001`, `BR-007`, `BR-013`, `BR-014`, `BR-015`, `BR-031`, `BR-032`, `BR-039`,
`BR-041`, `BR-082`, `BR-086`
Standard implemented: `docs/architecture/offline-first-architecture.md` §2–§7, §10
Decision record: `docs/decisions/014-android-offline-engine.md`
API contract: **unchanged by this slice** (`docs/api/customers.md` §4.2 was already the contract)

## What this slice is

`BR-086` requires Property archive and restore to be offline-capable, and `ADR-012` D7 recorded the
offline/outbox standard they follow. The standard was written but nothing of its client half existed:
no local store, no queue, no connectivity awareness. This slice implements it and adopts it in two
places:

1. **Property archive and restore** — queued when the API cannot be reached, replayed when it can.
2. **The Customer Detail read** — served from the last answer the backend reported when the API cannot
   be reached, because a Property is reached through the Customer Detail and a queued archive is
   worthless if the path to it cannot be read offline.

## What was built

| Part | Where |
| --- | --- |
| Room dependency, KSP schema export, committed schema (`1.json`) | `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `android/app/schemas/` |
| Outbox row and its states, refusals and retry | `data/offline/OutboxOperation.kt`, `OutboxOperationEntity.kt`, `OutboxDao.kt`, `OutboxStore.kt` |
| Working set: the last answer the backend reported | `data/offline/WorkingSetEntry.kt`, `WorkingSetEntryEntity.kt`, `WorkingSetDao.kt`, `WorkingSetStore.kt` |
| The local database | `data/offline/OfflineDatabase.kt` (`servora-offline.db`, version 1) |
| Session subject local state is scoped by | `data/session/AuthenticatedSubject.kt` |
| Replay: ordering, retention, retry, outcomes | `data/offline/OutboxReplayEngine.kt`, `OfflineOperationHandler.kt`, `ReplayBackoff.kt` |
| Triggers: session, connectivity, user | `data/offline/OfflineSync.kt`, `ConnectivityObserver.kt`, `OfflineSessionLifecycle.kt` |
| Property lifecycle adoption | `data/customers/PropertyOfflineStore.kt`, `PropertyLifecycleHandler.kt`, `PropertyLifecycleOffline.kt`, `PropertyRepository.kt` |
| Customer Detail adoption | `data/customers/CustomerDetailCache.kt`, `CustomersRepository.kt` |
| Screens | `ui/customers/PropertyDetailScreen.kt`, `CustomerDetailScreen.kt`, `ui/components/OfflineNotice.kt`, EN/FR strings |
| Wiring | `di/OfflineModule.kt`, `di/OfflineSyncModule.kt`, `di/CustomersOfflineModule.kt`, `AndroidManifest.xml` (`ACCESS_NETWORK_STATE`) |

## Behaviour

**Queued.** An archive or restore the API cannot be reached for is recorded in the outbox with the
idempotency key the screen generated, the device instant, and the version the screen saw. An edit is
**not** queued — the API accepts no idempotency key for it, which is what §5 requires — and neither is
permanent deletion (`BR-086`).

**Replayed.** When a session becomes available, when connectivity returns, or when the user asks, the
engine replays the queue oldest first, one operation at a time, and stops at the first operation that
may still succeed, so a later operation never overtakes an earlier one. Each replay carries the same
idempotency key, reads the Property's current version immediately before the mutation, and records the
answer the backend gives. Nothing is deleted because a request failed: a refusal is kept and shown, a
`401` waits for the next sign-in, and network or `5xx` failures retry with bounded backoff (30 s,
doubling, capped at an hour).

**Shown.** The Property screen draws the last state the backend reported, marked as such, plus the
queued action: waiting while the backend has not answered, and refused with the reason when it has.
Nothing on screen presents a queued action as applied (`BR-086`, §7). When a queued action is finally
accepted, an open screen re-reads the record from the API.

**Sign-out.** The working set of the ending session is dropped, so the next user cannot read it;
pending work is **kept**, because `BR-014` forbids losing it, and it stays attributed to its subject so
another session never replays it. What should happen to that work at sign-out is §10's open question
and is recorded rather than decided (`docs/decisions/014-android-offline-engine.md` D7).

## Files touched

```text
android/gradle/libs.versions.toml                                        (Room 2.8.5 pinned)
android/app/build.gradle.kts                                             (Room, KSP schema, room-testing)
android/app/schemas/com.servora.android.data.offline.OfflineDatabase/1.json  (new, committed)
android/app/src/main/AndroidManifest.xml                                 (ACCESS_NETWORK_STATE)
android/app/src/main/java/com/servora/android/data/offline/…             (new package)
android/app/src/main/java/com/servora/android/data/session/AuthenticatedSubject.kt
android/app/src/main/java/com/servora/android/data/session/SessionManager.kt
android/app/src/main/java/com/servora/android/data/customers/…           (adopters)
android/app/src/main/java/com/servora/android/di/…                       (OfflineModule, OfflineSyncModule, CustomersOfflineModule, DataModule)
android/app/src/main/java/com/servora/android/ui/customers/…             (Property detail, customer detail)
android/app/src/main/java/com/servora/android/ui/components/OfflineNotice.kt
android/app/src/main/res/values/strings.xml, values-fr/strings.xml       (EN/FR copy)
android/app/src/test/…, android/app/src/androidTest/…                    (tests below)
```

## Tests added

| Test | Covers |
| --- | --- |
| `data/session/AuthenticatedSubjectTest` | Reading the subject claim, including an unpadded token and everything unreadable |
| `data/offline/OutboxOperationMappingTest` | The queued row round-trips; a state or reason this build cannot read is never guessed at |
| `data/offline/OutboxReplayEngineTest` | Order, retention, refusal, retry, backoff, an operation with no handler, an interrupted replay, another subject's work |
| `data/offline/OfflineSyncCoordinatorTest` | Replay on connectivity and on request; two triggers never apply the same operation twice |
| `data/offline/ReplayBackoffTest` | The wait halves nothing and is bounded |
| `data/offline/SessionScopedOfflineStateTest` | A session's end drops its working set and keeps its pending work |
| `data/session/SessionManagerTest` (extended) | A restored session and a fresh sign-in are reported available; a sign-out is reported ending |
| `data/customers/PropertyRepositoryOfflineTest` | Queueing archive and restore, refusing to queue what the API answered, an edit staying online-only, the working set on an unreachable read, deletion forgetting the copy, the queued-action projection |
| `data/customers/PropertyLifecycleHandlerTest` | The replayed key and freshly read version, the restored/archived answer being recorded, `409`/`404`/network/`401` outcomes, an unreadable payload |
| `data/customers/CustomersRepositoryTest` (extended) | The customer detail served from the last reported answer, a refusal never masked, a later read replacing the copy |
| `ui/customers/CustomersViewModelTest` (extended) | The detail is marked as the last reported answer, and not marked when the backend answered |
| `androidTest/data/offline/OfflineDatabaseTest` | The store on SQLite: queue order, terminal refusal, interrupted replay, subject isolation, one reported answer per entity, eviction (`NOT RUN` on the JVM — device, `qa.md` §7.3) |

## Verification (2026-09-14)

```text
Android
  ./gradlew compileDebugKotlin             PASS
  ./gradlew testDebugUnitTest              PASS   345 tests / 36 files, 0 failures, 0 skipped
  ./gradlew compileDebugAndroidTestKotlin  PASS
  ./gradlew lintDebug                      PASS   (abortOnError is on)
  ./gradlew assembleDebug                  PASS
  ./gradlew assembleDebugAndroidTest       PASS
  ./gradlew connectedDebugAndroidTest      NOT RUN — device QA is the product owner's

API
  unchanged by this slice: no API source, contract, migration or test was touched
```

## Open questions preserved (not implemented)

1. **Which operations are offline-capable** (`BR-032`, standard §11.1). Job and Visit actions are not
   queued: `api/src/jobs/jobs.controller.ts` accepts no idempotency key, so §5 forbids it until it
   does. That is an API slice, then a client one.
2. **The disposition of a non-empty outbox at sign-out** (standard §10). Pending work is kept.
3. **Whether a refused operation can be discarded by the user** (standard §11.5). It is retained and
   shown; no discard affordance exists yet.
4. **The per-operation conflict strategy for other entities** (standard §8). The Property lifecycle's
   own strategy is `ADR-014` D5.
5. **Local retention for the working set** (standard §11.4).
6. **Evidence and files** (standard §9, `BR-015`). No attachment upload exists yet, so nothing queues
   a photo; the store is ready for one.
7. **The Property address-history table** (`BR-057`) — still deferred.

## Manual QA runbook (product owner)

Requires the API reachable from the device, and a customer with a Property.

```text
1. Sign in as a Manager, open Customers → a customer → a Property.
2. Turn the device's network off (airplane mode or Wi-Fi off).
   Expect: the Property shows an "Offline - showing the last update the server reported." notice
   when it is re-opened, and Archive Property is still offered.
3. Press Archive, confirm.
   Expect: the screen keeps the Property as ACTIVE, with "Archive saved on this device. It will be
   applied when the server can be reached." - it must NOT show the Property as archived.
4. Force-close the app and reopen it while still offline, then open the same Property.
   Expect: the queued action is still shown, and the customer/Property navigation still works.
5. Turn the network back on and wait for the screen to refresh (or leave and re-open the Property).
   Expect: the Property is ARCHIVED, the queued notice is gone, and the backend agrees (check Job
   Activity of any Job at that Property, or the API).
6. Repeat with Restore while offline, then reconnect.
   Expect: the same in the other direction.
7. While offline, archive a Property and then restore it before reconnecting; then reconnect.
   Expect: both operations are applied in order and the Property ends ACTIVE (the second operation
   must not be refused as a conflict).
8. While offline, try to edit a Property (Edit → Save Changes).
   Expect: an honest "can't reach the server" failure - an edit is never queued.
9. Sign out while an operation is still queued, sign in again.
   Expect: the queued action is still there and is replayed; the working set from the previous
   session does not leak into the new one.
```
