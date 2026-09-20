# Tracker 039 — Manager Schedule UI/UX polish (Android)

**Status: implemented; automated verification below; physical-device QA is the product owner's**

Date: 2026-09-17
Predecessor: `docs/tracker/038-android-manager-schedule.md` (the slice that built this screen and its read)
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-010`, `BR-011`, `BR-012`, `BR-013`, `BR-020`, `BR-024`,
`BR-028`, `BR-041`, `BR-042`, `BR-049`, `BR-056`, `BR-059`, `BR-068`, `BR-070`, `BR-071`, `BR-072`,
`BR-074`, `BR-080`
Domain model: `docs/domain/job-visit-domain-model.md` §8.4, §9.1, §9.3, §10
API contract: `docs/api/schedule.md` — **unchanged by this slice**
Design reference: `Figma/src/imports/pasted_text/servora-scheduler-design.md`
Design system: `docs/design/android-design-system.md` § Manager Schedule — **updated by this slice**
ADR: `docs/decisions/020-manager-schedule-and-unassigned-lane.md` — unchanged; this slice implements the
presentation it describes, not a new decision.

## Scope

The Manager Schedule screen's presentation, as a follow-up refinement of tracker 038. The slice is
Android presentation, its strings, its tests and its documentation. It adds **no** API, table, route,
capability, permission or business rule, changes no status vocabulary and no transition, and touches no
other screen.

The existing structure is deliberately preserved and is **not** replaced by a calendar: the title, the
selected date with its **Today** action, the 7-day week strip, the Schedule / Unassigned lanes, the
technician filter, the chronological visit cards and the bottom navigation all stay, because the screen
is a mobile dispatch surface for fast scanning rather than a grid to plan in.

| Item | Status |
| --- | --- |
| Card hierarchy: time → status → Job title → customer/property → crew → address (`BR-012`) | Implemented |
| A crew longer than one line is named up to two members and counted (`%1$s +%2$d`) | Implemented |
| The whole card remains the target and opens the Job | Unchanged |
| Status chips stay the shared `HomeVisitStatusPill` (dot + label, never colour alone) | Unchanged |
| A "now" cue between the Visits that have started and the ones that have not | Implemented |
| The cue appears only on the device's today and only in the Schedule lane | Implemented |
| A subtle vertical timeline rail with a status-coloured dot per row | Implemented |
| No card is drawn as tall as its Visit duration | Unchanged (deliberate) |
| Unassigned badge on the lane selector | Unchanged (already carried the API's total) |
| Technician filter: search over the API's own options, with its no-results state | Implemented |
| Technician filter stays **single-choice** | Unchanged — see "Identified, not implemented" |
| Scheduling conflicts on the day | **Not implemented** — see "Identified, not implemented" |
| Compact top section (52 dp day cells in a 56 dp strip, 6 dp control spacing) | Implemented |
| Collapsing header on scroll | **Not implemented** — see "Identified, not implemented" |
| Week strip behaviour, selected-day state, Today action, month calendar as jump-to-date | Unchanged |
| Empty states reworded, and the search's own empty state added | Implemented |
| EN/FR copy for every new and reworded string | Implemented |
| API, schema, capabilities, permissions, navigation, offline behaviour | Unchanged |

## Decisions taken here

1. **The card states its facts in the order a dispatcher asks for them.** Time first and bold, the
   Visit's status beside it, then the Job title, then whose the work is, then the crew, then the
   address. Every line ellipsizes instead of wrapping the card taller, and a crew longer than one line
   names two members and counts the rest, so a five-technician Visit is never presented as a
   two-technician Visit (`BR-012`, `BR-068`).
2. **The customer line carries the Property's name when the snapshot has one**, joined by a localized
   format rather than a hard-coded separator (`BR-028`, `BR-056`). Comparing the address snapshot is how
   the screen already knows the Property; it invents nothing.
3. **The "now" cue is orientation, not a condition.** It is placed by `nowCueIndex`, a pure function over
   the day's scheduled starts and the device's clock, and it appears only when the selected day is the
   device's today and the lane is the Schedule lane (the unassigned lane holds work that belongs to no
   day, `BR-071`). It never changes a Visit's status and never replaces the API's overdue condition
   (`BR-001`, `BR-074`). The device's today is now read **once** in `ScheduleScreen` and handed to the
   strip and the agenda, so the two cannot disagree about which day is being lived through (`BR-041`).
4. **The timeline is a rail, not a grid.** A 2 dp rule and a 9 dp dot in the Visit's own status colour,
   drawn through the gap between rows and cut at the lane's first and last dots. Card height stays
   independent of Visit duration, because a duration-proportional row would waste the screen a
   dispatcher works on (`BR-012`).
5. **The filter's search narrows the options the read already returned** — accent- and
   case-insensitively, because Servora is bilingual (`BR-028`) — and never asks the API a different
   question (`BR-042`). The whole-organization option stays offered while searching, because clearing a
   filter is not the same question as finding a technician. The search is `matchesTechnicianQuery`, a
   pure function.
6. **No chevron is drawn on the card.** Rejected on measurement: on a 360 dp phone the time window and
   the status pill nearly fill the row, and a trailing chevron's ~26 dp would force the time — the first
   fact a dispatcher reads — to ellipsize. The card is bordered and clickable with the platform's
   ripple, exactly like the manager home's Visit row, so the two screens describe the same row the same
   way (`BR-041`).
7. **The top section is compacted, not collapsed.** The strip went from 66 dp to 52 dp with 48 dp day
   cells and the control spacing from 8 dp to 6 dp, so every control still sits at the platform's 48 dp
   touch minimum while the section gives height back to the day's work.
8. **Empty states are the lane's own words**, and the search has one of its own: *Nothing is scheduled
   for this day.*, *All visits have been assigned.*, *No technicians match your search.*

## Identified, not implemented

Three requests could not be implemented without changing something this slice is not allowed to change.
They are recorded rather than guessed (`BR-042`, `Project.md` §30).

1. **Scheduling conflicts on the day (the request's item 7).** `docs/api/schedule.md` §5.5 and ADR-020's
   open question 5 record that this read deliberately reports **no** conflict state: `BR-070` defines the
   warning a *scheduling action* shows, and presenting a conflict on a read would be a second definition
   of a rule that belongs to the action that schedules. Surfacing conflicts here therefore needs a product
   decision plus a new field on `GET /schedule` — the conflict each Visit has, with the detail the warning
   needs ("Luc Gagnon is also assigned to another visit from 1:30 PM–3:30 PM") — computed by the API, the
   authority for the rule (`BR-001`, `BR-007`). The card is ready for it: its status row has room for a
   compact warning indicator, and the screen would render the API's answer rather than derive one.
   **Blocked on a product decision, not on the UI.**
2. **Multi-technician filtering (part of the request's item 6).** The filter is the vocabulary of one
   assignment (`BR-068`) and `GET /schedule` accepts a single optional `membershipId`
   (`docs/api/schedule.md` §3.1), so selecting a set of technicians and showing "3 technicians" while the
   read is narrowed to one would be a lie. Multi-select needs a repeatable `membershipId` on the route (a
   backward-compatible contract extension), a change to the read's predicate, the Android filter state as
   a set and the collapsed label for a set. The sheet itself is ready for it: its options are independent
   rows with their own selected state, and the search already narrows them. **Blocked on an API contract
   change, which is a decision rather than an implementation detail.**
3. **A collapsing header on scroll (the request's item 8).** The design system documents the top section
   as persistent — the agenda below it scrolls on its own — and `BR-012` asks that date navigation never
   become harder. Letting the strip or the lane selector scroll away also risks hiding an *active* filter
   (the collapsed filter names it, but only while it is on screen), which is the failure mode that makes a
   dispatch board confusing. The compacting in decision 7 was implemented instead. A collapsing header is
   a design change that needs the layout re-validated on a phone, so it is recorded here rather than
   folded in silently.

## What the slice changes

- `android/.../ui/schedule/SchedulePresentation.kt` (new): `crewLine`, `matchesTechnicianQuery`,
  `instantOf`, `nowCueIndex` — the screen's own presentation decisions, as pure functions.
- `android/.../ui/schedule/ScheduleScreen.kt`: the agenda's rows (rail + card + now cue), the card's
  hierarchy, the `today` hand-off, and the lane's empty-state wiring.
- `android/.../ui/schedule/ScheduleTopSection.kt`: `today` as a parameter, the compact strip metrics, and
  the filter sheet's search.
- `android/.../ui/schedule/ScheduleUiState.kt`: `showsNowCue(today)`.
- `res/values/strings.xml`, `res/values-fr/strings.xml`: `schedule_now`, `schedule_crew_more`,
  `schedule_customer_property_format`, `schedule_filter_search`, `schedule_filter_no_results`, and the
  reworded empty states.
- Tests: `app/src/test/.../SchedulePresentationTest.kt` (new),
  `app/src/androidTest/.../ScheduleScreenTest.kt`.
- `docs/design/android-design-system.md`: the Manager Schedule section.

## Manual QA runbook (product owner, on a device)

Android QA — Manager Schedule polish

1. Sign in as a Manager and open **Schedule**. It opens on today.
2. Read the day's cards: the time window and its status pill, the Job title, the customer (with the
   Property's name after it when the Job has a named Property), the crew and the address.
3. Find a Visit with more than two technicians; confirm the crew line reads
   `First Name, Second Name +N`, stays on one line, and that the card is not taller than the others.
4. Confirm the whole card opens that Job's details, and that coming back returns to the same day,
   lane and filter.
5. Scroll the agenda: confirm the timeline rail runs continuously between cards, that it starts and
   ends at the first and last card's dot, and that it never makes a card as tall as its Visit.
6. On today, confirm the **Now** cue sits between the Visits that have started and the ones that have
   not. Switch to another date and confirm the cue disappears.
7. Open the technician filter: confirm `All technicians` leads, then the search field, then the
   technicians. Type part of a name (try one without its accent, e.g. `cote` for `Côté`) and confirm
   the list narrows while `All technicians` stays. Type something that matches nobody and confirm
   *No technicians match your search.* Choose a technician and confirm the day narrows and the
   collapsed control names them.
8. Switch to **Unassigned**: confirm the count on the lane selector, the cards, and that the lane's
   cards show no time rather than an invented one.
9. Empty states: a day with nothing scheduled shows **Nothing scheduled** / *Nothing is scheduled for
   this day.*; a lane with no unassigned work shows **All visits have been assigned** / *Nothing is
   waiting for a technician.*
10. Switch the device to French and repeat 2, 6, 7 and 9; confirm every new string is translated and
    that the layout still fits (the time window, the pill and the crew line are the tight lines).
11. Background the app, reopen Schedule and confirm the day, lane and filter are where they were left.
12. Confirm light and dark appearance both read correctly, and that no status is communicated by
    colour alone (each pill carries its own label).

Expected: nothing on this screen schedules, assigns, reschedules or cancels anything; no Job status
appears on a card; the timeline and the Now cue change no Visit's status.

Not covered by the automated tests, so worth the product owner's eye: the rail's continuity across the
row gap, the Now cue's appearance and position, the crew line's truncation, and the strip's compacted
metrics at the device's font scale.

## Verification

**Tier B for Android** — the slice is a completed presentation change, so the full Android checks were
run after the last edit (`qa.md` §3.1).

| Check | Command | Result |
| --- | --- | --- |
| Compile | `./gradlew compileDebugKotlin` | **PASS** (only the repository's pre-existing warnings) |
| Unit (targeted) | `./gradlew testDebugUnitTest --tests 'com.servora.android.ui.schedule.*'` | **PASS** — `SchedulePresentationTest` 12, `ScheduleViewModelTest` 10, `ScheduleWeekTest` 5, 0 failures |
| Unit (full) | `./gradlew testDebugUnitTest` | **PASS** — 635 tests in 59 files, 0 failures, 0 errors (623 in 58 files before this slice: the +12 are this slice's) |
| Lint (full) | `./gradlew lintDebug` | **PASS** — 0 errors; the report's 49 findings are the pre-existing ones, and **none** is in `ui/schedule/` |
| Build | `./gradlew assembleDebug` | **PASS** |
| Device-test sources | `./gradlew assembleDebugAndroidTest` | **PASS** — the Compose tests compile; **no `adb` and no device/emulator command was run** (`qa.md` §7.3) |
| API / schema / web | — | **Not run — nothing outside Android changed** (no API, contract, migration or Angular change) |
| Physical device | — | **AWAITING PRODUCT OWNER** (runbook above) |

Hygiene: the Gradle/Kotlin daemons this task started were stopped with `make android-stop`
(`dev.md` §18, `qa.md` §7.4), and the task's temporary log files were deleted.

## Phase log

| # | Date | What was done | Files | Verification |
| - | ---- | ------------- | ----- | ------------ |
| 1 | 2026-09-17 | The presentation decisions and their pure functions: the crew line's truncation, the accent-insensitive technician search, and where the "now" cue belongs. | `android/.../ui/schedule/SchedulePresentation.kt` | Reviewed against `BR-012`, `BR-028`, `BR-041`, `BR-042`, `BR-068` |
| 2 | 2026-09-17 | The agenda: the rail, the card hierarchy, the Now cue and the empty states; the state's `showsNowCue`; the top section's compacted strip, the `today` hand-off and the filter's search; EN/FR copy. | `android/.../ui/schedule/ScheduleScreen.kt`, `ScheduleTopSection.kt`, `ScheduleUiState.kt`, `res/values*/strings.xml` | **Tier A:** `compileDebugKotlin` PASS; `testDebugUnitTest --tests 'com.servora.android.ui.schedule.*'` PASS (27 tests); `assembleDebugAndroidTest` PASS |
| 3 | 2026-09-17 | The tests and the documentation. | `app/src/test/.../SchedulePresentationTest.kt`, `app/src/androidTest/.../ScheduleScreenTest.kt`, `docs/design/android-design-system.md`, this tracker | **Tier B:** unit 635/59 PASS, lintDebug PASS (0 errors, no finding in `ui/schedule/`), assembleDebug PASS, assembleDebugAndroidTest PASS; **physical device AWAITING PRODUCT OWNER** |


