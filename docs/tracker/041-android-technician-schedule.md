# Tracker 041 — Technician Schedule ("My Schedule"): the Android screen and the read's scope

**Status: implemented; API verified (Tier B for the API); Android verification NOT RUN — the product
owner asked for no Gradle/Android commands on this machine; physical-device QA is the product owner's**

Date: 2026-09-17
Predecessors: `docs/tracker/038-android-manager-schedule.md` (the read and the office screen),
`docs/tracker/039-android-manager-schedule-ux.md`, `docs/tracker/040-android-manager-schedule-density.md`
(the presentation passes), `docs/tracker/037-technician-field-experience.md` (the field read's scope,
`ADR-019` D2)
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-010`, `BR-011`, `BR-012`, `BR-013`, `BR-020`,
`BR-024`, `BR-028`, `BR-041`, `BR-042`, `BR-044`, `BR-052`, `BR-056`, `BR-057`, `BR-059`, `BR-066`,
`BR-067`, `BR-068`, `BR-071`, `BR-072`, `BR-074`
Domain model: `docs/domain/job-visit-domain-model.md` §8.4, §9.1, §10, §21
API contract: `docs/api/schedule.md` — **extended by this slice** (the route's permissions, the resolved
`scope`, the absent lane for a field scope, the `403`)
ADR: `docs/decisions/020-manager-schedule-and-unassigned-lane.md` — **decision update recorded**
Design system: `docs/design/android-design-system.md` § Technician Schedule — **added by this slice**
Offline standard: `docs/architecture/offline-first-architecture.md` §12 adopter 10, §13

## Scope

The Schedule destination served the office only (`ADR-020` D5): its read was guarded by `customers.view`
and a session holding the field capability alone was not offered the tab. This slice makes the
technician's own schedule real — the screen the field actually uses, and the read's **scope** that makes
reading it safe.

| Item | Status |
| --- | --- |
| `GET /schedule` accepts `customers.view` **or** `VISIT_VIEW_ASSIGNED` (`@RequireAnyPermission`) | Implemented |
| The service resolves the scope: `ORGANIZATION` (office) or `SELF` (the caller's own membership) | Implemented |
| A `membershipId` outside the resolved scope is refused `403`, uniformly whether or not it names anybody | Implemented |
| The payload reports the resolved scope (`scope.kind`, `scope.membershipId`) | Implemented |
| A `SELF` answer carries no unassigned lane (`null`) and no technician options (`[]`) | Implemented |
| "My Schedule": week strip + day agenda for the caller's own Visits | Implemented |
| Week navigation: swipe, week arrows, tap a day, a **Today** action | Implemented |
| Visit card: start time, Job title, customer/property, address, Visit status, window, crew | Implemented |
| `You` / `You + 2` crew line, using the membership the read was resolved for | Implemented |
| Emphasis on the first Visit that has not completed; completed Visits drawn quieter | Implemented |
| The shared `ScheduleWeekStrip` extracted so both schedules use one strip | Implemented |
| The Schedule destination offered on either capability; the office one kept for a both-capability member | Implemented |
| Offline: a field-scoped day kept in the existing working set, one row per subject per date | Implemented |
| EN/FR copy for every new string | Implemented |
| API unit specs and e2e (the scope, the refusals, the field day) | Implemented |
| Android JVM tests (repository+cache, ViewModel, presentation) and Compose device tests | Written |
| **A technician filter/selector on the technician screen** | **Not implemented — the capability that would authorize it does not exist; OPEN QUESTION below** |
| **Per-day work indicators on the week strip** | **Not implemented — needs a week-scoped count read** |
| **Any scheduling action (assign, reschedule, cancel) on this screen** | **Not implemented — the Job/Visit flows own those** |

## What the slice adds

- **API**: `api/src/schedule/schedule-scope.ts` (the scope vocabulary, the resolution from the caller's
  capabilities, the filter authorization and the narrowed membership set) and its spec;
  `schedule.controller.ts` (the any-of guard, the scope, the `403`); `schedule.service.ts` (the scope
  applied to the day, the lane and the filter options); `schedule.dto.ts` (the `scope` field and the
  nullable lane); `test/schedule.e2e-spec.ts` (the field scope over HTTP).
- **Android data**: `data/schedule/ScheduleCache.kt` (the field day's working-set row, keyed by subject
  and date); `DefaultScheduleRepository` (the scope-aware mapping and the offline fallback);
  `ScheduleDtos`/`ScheduleResult`; `WorkingSetEntityTypes.TECHNICIAN_SCHEDULE`.
- **Android UI**: `ui/schedule/TechnicianScheduleScreen.kt`, `TechnicianScheduleViewModel.kt`,
  `TechnicianScheduleUiState.kt`, `TechnicianSchedulePresentation.kt`, and the extracted
  `ScheduleWeekStrip.kt`; the shell draws the destination on either capability and picks the screen.
- **Docs**: the API contract, the ADR decision update, the design system, the offline standard's adopter
  list, the domain model's open-question register, and this tracker.

## Decisions taken here

1. **One read, two scopes — not a second route.** `ADR-020` D5 called the technician's schedule "a
   different read"; the slice landed it as the same read with a resolved scope, because the day
   resolution, the ordering, the address fallback and the overdue derivation are one projection, and a
   second contract would let them drift (`BR-041`, `ADR-019` D1's reasoning for the Job read). Recorded
   as an ADR-020 decision update under `BR-040`.
2. **The scope is a server answer, and the client presents it.** The payload carries
   `scope { kind, membershipId }`. The client never decides whose work it may read, and the off-scope
   refusal is the API's own (`BR-007`).
3. **A field scope has no unassigned lane and no technician options.** Both are office questions, and
   `null` on the lane says "not this read's question" rather than "nothing is waiting" (`BR-042`).
4. **The screen is simpler on purpose.** No lanes, no filter, no calendar, no dispatch actions: the
   technician's screen is day browsing over their own work (`BR-010`, `BR-012`), and the office screen
   already exists for the rest.
5. **Emphasis is presentation, not a second status.** The next outstanding Visit carries a primary
   border and completed Visits are quieter; nothing derived is shown as a status, and the pill stays the
   API's own answer (`BR-042`, `BR-074`).
6. **Offline by the existing engine, not a second cache.** A field-scoped day is kept in the working set
   — one row per subject per local date — and served only when the backend cannot be reached; a refusal
   is never replaced by a local copy. An office-scoped answer is not kept at all (`BR-013`,
   `ADR-020` D6 still holds for the office board).
7. **The week strip is shared.** It was extracted from the manager screen rather than copied, so the two
   schedules cannot disagree about which day a swipe reaches (`dev.md` §1, `BR-041`).

## OPEN QUESTION — a field member's visibility of another technician's schedule

The requirement is that the architecture **support** viewing another technician's schedule when the
caller holds the appropriate capability, and that the API — not the client — authorize the scope. Both
are implemented and tested: the scope is resolved server-side, an off-scope request is refused `403`, and
a caller holding the office capability reads the organization's day (with the technician filter) exactly
as before.

What does **not** exist is a capability that grants a **field** member visibility of a colleague's day.
The catalogue's candidates do not fit and none was invented (`BR-042`):

- `VISIT_VIEW_ASSIGNED` is by definition the caller's *own* assignments (`BR-009`).
- `TECHNICIAN_VIEW` authorizes the technician **records** — the directory an assignee is chosen from
  (`BR-024`). Using it for another member's *work* would extend a catalogue code's meaning, which is a
  product decision.

**What it blocks:** the technician Schedule screen does not offer a technician selector, because the only
scope that admits other technicians today is `customers.view`, and a member holding it is routed to the
office schedule (which has that filter). If product ownership decides a team scope — for example a Lead
Tech custom role — the change is: the capability is created with its bilingual catalogue row and grant,
`resolveScheduleScope` gains its branch (returning a `TEAM` scope with the memberships it reaches), and
the screen gains the single-choice selector it is shaped for. Recorded in `docs/api/schedule.md` §5.4 and
`docs/domain/job-visit-domain-model.md` §21 (item 27).

## Verification

**API — Tier B for the API** (the change is complete, and it changes authorization and a shared
contract, so `qa.md` §3.1 escalates it).

| Check | Command | Result |
| --- | --- | --- |
| Typecheck | `npm run typecheck` | **PASS** — only the repository's four pre-existing errors in `test/technician-home.e2e-spec.ts` (351, 354) and `test/visit-field-lifecycle.e2e-spec.ts` (611, 636), none in a file this slice touched |
| Unit (targeted) | `npx vitest run src/schedule` | **PASS** — 3 files, 30 tests (the new `schedule-scope.spec.ts`'s 12 among them) |
| API e2e (targeted) | `npm run test:e2e -- test/schedule.e2e-spec.ts` | **PASS** — 18 tests against the running foundation stack, including the field scope, the own-membership filter, the three off-scope refusals, the office scope, and the caller holding neither capability |

**Android — NOT RUN.** The product owner asked that no Gradle/Android command run on this machine (it
overheats and freezes under the build daemons), so no compile, unit, lint, build or device-source compile
was executed for this slice. **The Android change is therefore unverified**, and this tracker does not
claim otherwise. Nothing was run through `adb` either (`qa.md` §7.3).

Commands to run when the machine is free, in order:

```bash
cd android && ./gradlew compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin
cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.schedule.*'
cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.data.schedule.*'
# Tier B, at the feature's end
make android-lint && make android-test && make android-build
make android-stop
```

## Manual QA runbook (product owner)

1. Sign in as a **technician** (the default Technician role).
2. Confirm the bottom navigation now offers **Schedule** and that opening it titles the screen **My
   Schedule**.
3. Confirm today's agenda lists the Visits assigned to the caller, with the start time, the Job title, the
   customer/property, the address, the Visit status and the time window.
4. Confirm the crew line reads `You` when the caller is alone on the Visit and `You + 1`/`You + 2` when
   others are assigned with them (`BR-068`).
5. Confirm the first Visit that is not completed is the visually strongest row, and that completed Visits
   are quieter.
6. Swipe the strip to another week, tap a day, and confirm the agenda follows the day and the heading
   names it (`Today …`, `Tomorrow …`, or the date).
7. Move to another day and confirm **Today** appears; tap it and confirm today is selected again.
8. Tap a Visit card and confirm the Job Details screen opens for that Job.
9. Turn connectivity off, reopen the screen for a day already read, and confirm the agenda is still there
   with the **last reported** notice (`BR-013`); then confirm a day never read shows the failure state
   with **Try again**.
10. Switch the device to French and repeat 2–5; confirm every string is translated and the card still fits.
11. Sign in as a **Manager** and confirm the office Schedule is unchanged: lanes, the technician filter,
    the unassigned lane, the month calendar.
12. Confirm the technician screen offers **no** technician selector, no lane selector and no way to change
    a Visit (there is no capability for a colleague's day yet — the OPEN QUESTION above).

## Phase log

| # | Date | What was done | Files | Verification |
| - | ---- | ------------- | ----- | ------------ |
| 1 | 2026-09-17 | The read's scope: the vocabulary, the resolution from the caller's capabilities, the filter authorization and the narrowed membership set. | `api/src/schedule/schedule-scope.ts`, `schedule-scope.spec.ts` | **Tier A:** `npx vitest run src/schedule` PASS (12 new tests) |
| 2 | 2026-09-17 | The read's payload and service: the reported scope, the nullable lane, the scope applied to the day, the lane and the filter options; the any-of guard and the `403`. | `schedule.dto.ts`, `schedule.service.ts`, `schedule.controller.ts`, `schedule.dto.spec.ts` | **Tier A:** `npm run typecheck` (no new error), `npx vitest run src/schedule` PASS |
| 3 | 2026-09-17 | The read's e2e: the field scope, the own-membership filter, the three off-scope refusals, the office scope, and the caller holding neither capability. | `test/schedule.e2e-spec.ts` | **Tier B (API):** `npm run test:e2e -- test/schedule.e2e-spec.ts` PASS (18) |
| 4 | 2026-09-17 | The Android data layer: the scope in the DTOs and the domain model, the working-set row, the scope-aware repository with its offline fallback. | `data/schedule/*`, `domain/model/Schedule.kt`, `data/offline/WorkingSetEntry.kt` | **NOT RUN** (no Android build) |
| 5 | 2026-09-17 | The screen: the shared week strip extracted, the header, the day heading, the Visit card, the crew line, the emphasis, and the empty/loading/failure states; EN/FR copy. | `ui/schedule/ScheduleWeekStrip.kt`, `TechnicianScheduleScreen.kt`, `TechnicianScheduleViewModel.kt`, `TechnicianScheduleUiState.kt`, `TechnicianSchedulePresentation.kt`, `res/values*/strings.xml` | **NOT RUN** (no Android build) |
| 6 | 2026-09-17 | The shell: the destination offered on either capability, the presentation chosen by capability, the title, the ViewModel wiring and its session reset. | `ui/customers/CustomersScreen.kt`, `ui/auth/AuthFlowScreen.kt`, `MainActivity.kt` | **NOT RUN** (no Android build) |
| 7 | 2026-09-17 | The tests: the repository's scope and offline cases, the ViewModel's day behaviour, the presentation's own decisions, the screen's device tests and the navigation test. | `app/src/test/…/schedule/*`, `app/src/androidTest/…/schedule/*`, `…/navigation/ServoraHomeNavigationTest.kt`, `…/schedule/ScheduleScreenTest.kt` | **NOT RUN** (no Android build) |
| 8 | 2026-09-17 | The documentation: the API contract, the ADR decision update, the design system's Technician Schedule section, the offline standard, the domain open-question register, this tracker and the README pointer. | `docs/api/schedule.md`, `docs/decisions/020-…md`, `docs/design/android-design-system.md`, `docs/architecture/offline-first-architecture.md`, `docs/domain/job-visit-domain-model.md`, `README.md`, this tracker | Reviewed against `BR-009`, `BR-041`, `BR-042`, `BR-044`, `BR-068`, `BR-074`; the Android tier is pending on the machine |

**Hygiene:** no Gradle or Android command was run, so no build daemon was started and none needed stopping
(`dev.md` §18, `qa.md` §7.4); the temporary log files this slice's API runs wrote were deleted; the
foundation stack (`make up`) and its volumes were left exactly as they were.
