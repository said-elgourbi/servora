# Tracker 055 — Returning to Home reads the day again

**Status: IMPLEMENTED** (Android; **compile only** was run, at the product owner's instruction — no test
execution and no lint — and **Android physical-device QA is the product owner's**, `qa.md` §7)

Date: 2026-09-20

Type: **defect fix in a client read.** No business rule, API contract, database schema, shared contract or
offline/outbox behaviour changes.

Business rules: `BR-001`, `BR-011`, `BR-012`, `BR-013`, `BR-042`, `BR-074` (all respected, none changed)

## The report

Product ownership, on a physical device: *as a technician I changed the status of a Visit and went back to
the home page — the old status was still showing, and I had to sign out and sign in to see the new one.*

## What was wrong

The home read was issued by one `LaunchedEffect(selectedTab)` in `ServoraHomeScreen`
(`ui/customers/CustomersScreen.kt`) — the composable that owns the signed-in shell. The shell stays
composed for as long as the session lasts, so that effect ran only when the **selected bottom-navigation
tab changed**. Entering the bottom-navigation area again after a drill-down therefore never asked the API
for the day.

The area's content (`rootContent`) is what leaves and re-enters composition when a drill-down screen is
pushed and popped — the very fact the area already relied on to release the Customer a detail screen had
read (`customersViewModel.closeCustomerDetail()`). The day itself was not re-read there, so
`TechnicianHomeViewModel` kept reporting the answer it had read *before* the Visit was moved, and the
re-read `JobDetailsViewModel` performs after an action only refreshed the Job Details screen.

## The fix

| Before | After |
| ------ | ----- |
| `LaunchedEffect(selectedTab)` held the whole read: which home to read **and** the read itself | One `readHome` lambda holds *which* home to read (the session's own capability decides — `BR-011`, `ADR-019` D6); the tab effect calls it, and the area's existing `LaunchedEffect(Unit)` calls it as well |
| Returning from a Job, a Customer or any other drill-down left the day exactly as it stood when the drill-down was opened | Entering the bottom-navigation area reads the day again, so the change the technician just made in the field — or the office made on the Job — is what the home reports when they come back to it |
| The workaround was a sign-out and sign-in, which reads the day only because the ViewModel is reset | The day is read at the moment it can have changed (`BR-001`) |

Nothing else moves. The ViewModel's contract is unchanged: a read already in flight is not started twice,
so entering Home costs one request (the existing behaviour, now pinned by a JVM test); a read that fails
keeps the day already known and reports the reason beside it (`BR-013`); and a day served from the
last-reported copy is still marked as such (`offline-first-architecture.md` §7). No route, capability,
permission, API contract, projection, table, migration or outbox behaviour is touched.

## Files

| File | Change |
| ---- | ------ |
| `android/app/src/main/java/com/servora/android/ui/customers/CustomersScreen.kt` | `readHome` extracted from the tab effect; the area's re-entry effect reads the day |
| `android/app/src/test/java/com/servora/android/ui/home/TechnicianHomeViewModelTest.kt` | the new JVM test: a Home entered after the first read finished reads the day again, and the newer answer is what the screen reports |
| `android/app/src/androidTest/java/com/servora/android/ui/navigation/ServoraHomeNavigationTest.kt` | the new device test, and the fake day it needs: the field home's answer states a different Visit status after its first read, so only a re-read can produce it |
| `docs/design/android-design-system.md` | § Home screens — **When the day is read** |
| `README.md` | the bullet recording the defect and its fix |
| `docs/tracker/055-android-home-refresh-on-return.md` | this entry |

## Tests

| Level | What is covered | Where |
| ----- | --------------- | ----- |
| Android JVM | Entering Home again after the first read finished issues a second read, and the Visit's newly reported status is what the ViewModel holds | `TechnicianHomeViewModelTest` — `reads the day again when Home is entered after the first read finished` (new) |
| Android UI (Compose, device) | Returning from the Job the field home opened reads the day again, and the day states the Visit's new status | `ServoraHomeNavigationTest` — `returningFromAJobReadsTheFieldDayAgain` (new; the product owner runs it, `qa.md` §7.3) |

## Verification

Scope (`qa.md` §3.1): **Tier A — targeted, Android only.** Nothing outside `android/` and the
documentation changed, so no API, Angular or database command was required.

The Android verification here is **compile only**, at the product owner's instruction: the machine cannot
carry a Gradle test run or a lint pass alongside their own work. No test was executed and no lint was run.

| Check | Command | Result |
| ----- | ------- | ------ |
| Android compile (main) | `cd android && ./gradlew compileDebugKotlin` | **PASS** — `BUILD SUCCESSFUL` |
| Android JVM test sources | `cd android && ./gradlew compileDebugUnitTestKotlin` | **PASS** — the new JVM test compiles |
| Android device-test sources | `cd android && ./gradlew compileDebugAndroidTestKotlin` | **PASS** — the new device test compiles |
| Android JVM unit tests | `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.home.*'` | **NOT RUN — at the product owner's instruction**, the machine is not to carry test runs |
| Android device tests | `cd android && ./gradlew connectedDebugAndroidTest` | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run |
| Android lint | — | **NOT RUN — at the product owner's instruction**; the tier defers lint to the feature's Tier B sweep in any case (`qa.md` §3.1) |
| API / Angular / database | — | **NOT RUN — nothing outside `android/` and the documentation changed** |

## Manual QA runbook (product owner)

```text
Android QA — the day is read again on return

1. Sign in as a Technician and read the status the home states for the next Visit.
2. Open that Job from the home and move the Visit to another working state (for example En route → On site).
3. Go back to the home with the system Back control.

Expected: the Visit is stated with the state the field left it in, without signing out and back in.
```

4. Repeat with the office set: sign in as a Manager, change the Job or its Visit from the Job Details
   screen the home reports, and return to the home. The manager's day is read again in the same way.
5. Open a Job and return without changing anything: the day already known stays on screen while the read
   is in flight (the loading state is not drawn over it), and the screen is not blanked.
6. With the device in airplane mode, open a Job and return: the day already read stays on screen and is
   marked as the last one the backend reported.

**Expected:** coming back to Home asks the backend for the day and states what it answers; nothing else on
the screen changes.

## Hygiene

The Gradle/Kotlin build daemons the compiles started were stopped with `make android-stop`, and the compile
log this task wrote under `/tmp` was removed; `git status` lists only the files this change touched on top
of the work already in progress. The foundation stack (`make up`) was not touched, no database or storage
volume was changed, and no file was opened in the product owner's editor.
