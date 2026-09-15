# Tracker 026 — Android offline: the Customers list read (Room working set)

**Status: COMPLETE for the Android implementation; physical-device QA is the product owner's**

Date: 2026-09-15
Predecessor: `docs/tracker/025-android-offline-room-outbox.md` (the engine and its first two adopters)
Standard applied: `docs/architecture/offline-first-architecture.md` §2, §7, §10, §12
Business rules: `BR-001`, `BR-007`, `BR-013`, `BR-031`, `BR-032`, `BR-041`, `BR-042`
**API contract: unchanged by this slice** — no route, DTO, migration, capability or backend file was touched

## What this slice is

The offline engine, the Property archive/restore queue and the Customer Detail read existed. The
**Customers list** did not: without connectivity, a Manager who opened the app could not even reach the
Customer that the working set already knew how to describe, so the list was the highest-value missing
read — it is the entry point to the Customer → Property → Job path that is already Room-backed
downstream. This slice adopts the list read into the same working set.

Nothing else moved: Manager Home, Job Details, Job Activity, every action and every form stay
online-only, and **no mutation was made queued**.

## What was built

| Part | Where |
| --- | --- |
| The list projection's local key | `data/offline/WorkingSetEntry.kt` (`WorkingSetEntityTypes.CUSTOMER_LIST`) |
| The feature-owned payload and its use of the working set | `data/customers/CustomerListCache.kt` |
| The list read: store on answer, fall back only when the backend was not reached | `data/customers/CustomersRepository.kt` |
| The read's outcome now says where it came from | `data/customers/CustomersResult.kt` (`CustomersResult.Success.source`) |
| The one row mapper both reads use | `data/customers/CustomersRepository.kt` (`List<CustomerDto>.toCustomers()`) |
| The screen's honesty about what it is showing | `ui/customers/CustomersUiState.kt`, `CustomersViewModel.kt`, `CustomersScreen.kt` |
| Copy | none added — `R.string.offline_last_reported` (EN/FR) is reused |

## Behaviour

**Stored.** A successful `GET /customers` read writes the rows the API returned, as received, into the
working set under the authenticated subject and the filter it was asked to apply (§10, §2). The wire
rows are kept rather than the mapped domain objects, so the offline read runs the **same** mapper the
online read runs (`BR-041`).

**Served.** When the API could not be reached — no connectivity, a `5xx`, or a renewal that did not
reach the backend — the last answer reported **for that same filter** is mapped and returned with
`ReadSource.WORKING_SET`. The screen says the rows are the last the server reported rather than a new
answer (`§2`, §7); it does not present them as current.

**Never served for a refusal.** `401`, `403`, `400`/`422`, `404` and `409` are the backend's own
answers. A refusal is reported as the failure it is, and a row held on the device is never shown in its
place (`BR-007`, `BR-042`).

**Never served across subjects or filters.** A read under another subject, or under a filter whose
answer was never reported, is the ordinary network failure — not another list.

**Nothing is queued.** The list read is a read. `GET /customers` has no idempotency key and no conflict
policy to decide, and the list is only changed through the feature's own online writes, so this slice
adds no outbox row (`§5`, `BR-032`).

## Decisions taken here

1. **The filter is part of the answer's identity, not a parameter of the read.** The endpoint is
   filtered by the backend (`BR-001`), so a row read for `ACTIVE:ALL` is not the answer to a read for
   `ALL:ALL`. It is stored as its own working-set row keyed by the filter's stable codes, which keeps
   the exact-match `WorkingSetStore` contract untouched and makes serving the wrong filter's rows
   structurally impossible rather than a check someone could forget. This is the one piece of the
   standard's §2 payload the list needs that the two projections before it did not.
2. **The wire rows are stored, not the mapped customers.** §2 says the working set holds what the
   backend reported; `BR-041` says one concept has one definition. Keeping the API's rows and mapping
   them with the same function on both paths is what makes an offline list indistinguishable from an
   online one.
3. **A read whose subject cannot be read is refused.** The same rule the Property and Customer Detail
   reads already follow: local state is scoped by subject (§10), so a read that cannot be attributed is
   reported as `UNAUTHENTICATED` rather than answered without local state. In practice the subject and
   the access token come from the same stored session, so no reachable case changes.
4. **A failed read still clears the list.** Unchanged from the online-only behaviour: a failure empties
   the rows and clears the last-reported mark, because leaving one filter's rows under another filter's
   heading is exactly the mistake this slice's keying prevents.
5. **No new copy.** The notice reuses `offline_last_reported`, already translated, because "these are
   the last values the server reported" is the same statement on a list as on a detail.

## Deliberately not done

**The pending Property archive/restore overlay on Customers / Customer Detail rows was checked and left
out.** `docs/decisions/014-android-offline-engine.md` D7 records it as a presentation decision for a
later slice, and the standard's §7 requires a queued action to be *shown*, not to be shown in one
particular place. It is not a small change that fits the existing patterns:

- There is no outbox query for "the queued operations of this Customer's Properties"; the store reads
  by `targetId` (one Property) today, so a customer-scoped marker would add a new DAO query and a new
  index pattern, by Property ids or by a scope the outbox does not carry.
- It needs a product/design decision that is not made: whether the row carries a count, a dot, a
  per-Property marker or a statement above the section; how a `REJECTED` row is surfaced on a row the
  user cannot act on from there; and whether the marker may appear when the acting user is not the
  reader.
- `BR-086` and the standard's §7 already satisfy the requirement where the action was taken: the
  Property screen shows the queued action, its state and any refusal.

It stays an **OPEN QUESTION**, recorded here and in `ADR-014` D7, rather than being invented in this
slice. **The one visible gap it leaves:** on the Customer Detail, a Property whose archive is queued
still renders as `ACTIVE` with no local indication; the user sees the queued state only after opening
the Property.

## Files changed

```text
android/…/data/offline/WorkingSetEntry.kt                 CUSTOMER_LIST entity type
android/…/data/customers/CustomerListCache.kt             new: the list's working-set use
android/…/data/customers/CustomersRepository.kt           store on answer; fall back on unreachable; toCustomers()
android/…/data/customers/CustomersResult.kt               Success.source
android/…/ui/customers/CustomersUiState.kt                showingLastReported
android/…/ui/customers/CustomersViewModel.kt              carries ReadSource onto the state
android/…/ui/customers/CustomersScreen.kt                 the last-reported notice, its tag
android/…/test/…/data/customers/CustomersRepositoryTest.kt    +8 tests
android/…/test/…/ui/customers/CustomersViewModelTest.kt       +4 tests
android/…/androidTest/…/ui/customers/CustomersScreenTest.kt   +2 device tests (compiled only)
android/…/androidTest/…/data/offline/OfflineDatabaseTest.kt   +1 device test (compiled only)
docs/architecture/offline-first-architecture.md           §12 adopters, §11.1, status banner
docs/tracker/026-android-offline-customers-list.md        this slice
```

## Tests added

| Test | Covers |
| --- | --- |
| `CustomersRepositoryTest.stores the rows the backend reported in the working set` | The answer is written under the subject, the projection and the filter's key, holding the API's rows |
| `…serves the last reported list when the API cannot be reached` | The offline fallback, marked `ReadSource.WORKING_SET` |
| `…serves the last reported list when the backend fails` | A `5xx` is a backend that could not be reached, not a refusal |
| `…reports the network failure for the list when nothing was reported yet` | No local row ⇒ the existing failure, unchanged |
| `…does not serve the local rows when the API refuses the list` | `403` is never masked by a local copy |
| `…does not serve another subject's rows` | Subject scoping |
| `…does not answer one filter with the list the backend reported for another` | Filter scoping |
| `…replaces the local rows with the answer of a later read` | §10: a successful read replaces, never merges |
| `CustomersViewModelTest.marks the rows as the last the server reported` | `showingLastReported` is set |
| `…does not mark the rows when the backend answered` | and is not set without a fallback |
| `…clears the mark when a later read is not the last reported answer` | and is cleared |
| `…clears the mark when the list could not be read at all` | and is cleared by a failure |
| `CustomersScreenTest.theRowsAreMarkedWhenTheyAreTheLastTheServerReported` | The notice renders (`NOT RUN` on the JVM — device, `qa.md` §7.3) |
| `CustomersScreenTest.theRowsAreNotMarkedWhenTheBackendAnswered` | and is absent otherwise (`NOT RUN` on the JVM — device) |
| `OfflineDatabaseTest.keepsOneReportedAnswerPerFilterOfTheSameProjection` | The new row shape on SQLite: two filters of one projection are two rows, and an unread filter has none (`NOT RUN` on the JVM — device) |

Existing coverage this slice leans on rather than duplicating: the list classification test
(`classifies transport, server and contract failures`), the session renewal tests, the detail's own
offline tests, `SessionScopedOfflineStateTest` (a session end clears every working-set row of that
subject, which includes the new ones), and the filter request tests.

## Verification (2026-09-15)

```text
Android
  ./gradlew testDebugUnitTest --tests 'com.servora.android.data.customers.*'
                            --tests 'com.servora.android.data.offline.*'
                            --tests 'com.servora.android.ui.customers.*'   PASS  184 tests / 15 classes, 0 failures
  ./gradlew testDebugUnitTest (full suite)                                PASS  364 tests / 36 classes, 0 failures, 0 skipped
  ./gradlew compileDebugAndroidTestKotlin                                 PASS
  ./gradlew lintDebug                                                     PASS  (abortOnError is on)
  ./gradlew assembleDebug                                                 PASS
  ./gradlew assembleDebugAndroidTest                                      PASS
  ./gradlew connectedDebugAndroidTest                                     NOT RUN — device QA is the product owner's

API
  unchanged by this slice: no API source, contract, migration or test was touched
```

No `adb` and no device/emulator command was run (`qa.md` §7.3). The three new device tests are
compiled only; they are the product owner's to run with `connectedDebugAndroidTest`.

## What remains online-only (unchanged by this slice)

Manager Home, Job Details, Job Activity, technician assignment, scheduling and rescheduling, Job and
Visit status actions, Visit notes, Customer and Property writes (including a Property edit), Property
permanent deletion, Add/Edit Property, Add/Edit Customer, password reset and SMS sign-in. None of them
is served from a local copy, and none of them is queued.

## Open questions preserved (not implemented)

1. **Which other operations are offline-capable** (`BR-032`, standard §11.1). Job and Visit actions
   still accept no idempotency key, so queueing them remains forbidden until the API does.
2. **The pending-action overlay on Customers / Customer Detail rows** (`ADR-014` D7) — checked in this
   slice and left out, for the reasons above.
3. **The disposition of a non-empty outbox at sign-out** (standard §10). Pending work is kept.
4. **Whether a refused operation can be discarded by the user** (standard §11.5).
5. **The per-operation conflict strategy** for every entity (standard §8). The Property lifecycle's own
   strategy is `ADR-014` D5.
6. **Local retention for the working set** (standard §11.4). The list adds one row per filter per
   subject and nothing expires them yet.
7. **Which other read projections are worth caching.** Manager Home aggregates across Jobs and Visits
   and would present stale counts; Job Details is reachable through the already-cached Customer path.
   Neither was assumed here.

## Manual QA runbook (product owner)

Requires the API reachable from the device and at least two customers.

```text
1. Sign in as a Manager and open Customers.
   Expect: the list loads with no notice under the header.
2. Open a customer, go back, and open Customers again.
   Expect: still no notice — the backend answered.
3. Turn the network off (airplane mode or Wi-Fi off), then leave and reopen Customers.
   Expect: the same rows as the last successful load, with "Offline - showing the last update the
   server reported." under the header, and the last reported counts on the rows.
4. While still offline, tap a row, open the customer, then a Property.
   Expect: both render from their own last-reported answers (each with its own notice) and Archive
   Property is still offered.
5. While still offline, apply a filter that was never loaded (Filter - another status or jobs option).
   Expect: an honest "Couldn't load customers" failure with Try again - a filter whose answer was
   never reported must not be filled in from another filter's rows.
6. Force-close the app and reopen it while still offline, then open Customers.
   Expect: the same offline rows and notice, and any action queued in step 4 is still shown when its
   Property is opened.
7. Turn the network back on and reopen the list (or press Try again).
   Expect: the notice is gone, and rows added or changed on the backend since the last load appear.
8. Sign out and sign in as a different user.
   Expect: the first user's rows never appear for the second user, online or offline.
9. Switch to French and dark mode and repeat step 3.
   Expect: the notice is French and follows the dark scheme.
```
