# Tracker 040 — Manager Schedule density: compact controls, multi-technician filter (Android + API)

**Status: implemented; automated verification below; physical-device QA is the product owner's**

Date: 2026-09-17
Predecessors: `docs/tracker/038-android-manager-schedule.md` (the slice that built this screen and its
read), `docs/tracker/039-android-manager-schedule-ux.md` (the first presentation pass)
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-010`, `BR-011`, `BR-012`, `BR-013`, `BR-020`, `BR-024`,
`BR-028`, `BR-041`, `BR-042`, `BR-056`, `BR-067`, `BR-068`, `BR-071`, `BR-072`, `BR-074`, `BR-080`
Domain model: `docs/domain/job-visit-domain-model.md` §8.4, §9.1, §9.3, §10
API contract: `docs/api/schedule.md` — **extended by this slice** (`membershipId` is now repeatable; the
ids are a union)
Design reference: `Figma/src/imports/pasted_text/servora-scheduler-design.md`,
`Figma/src/screens/Schedule.tsx` (the compact filter chip and the checkbox list)
Design system: `docs/design/android-design-system.md` § Manager Schedule — **updated by this slice**
ADR: `docs/decisions/020-manager-schedule-and-unassigned-lane.md` — unchanged. The read, the day
resolution, the unassigned lane and the office-only destination are untouched; this slice adds a filter
that narrows the same read.

## Scope

A density and hierarchy pass over the Manager Schedule screen, plus the one API change the technician
filter genuinely needed. The existing direction — date header, week strip, two lanes, visit cards on a
vertical timeline, month calendar as jump-to-date — is **preserved**. What changes:

| Item | Before | Now |
| --- | --- | --- |
| Lane selector | Two equal-width, brand-filled 48 dp `shapes.large` segments, 8 dp apart — read as two CTAs | A compact segmented control: two content-width segments 2 dp apart inside a 48 dp target, the lane in effect carrying a 32 dp `primaryContainer` pill, the other quiet text |
| Technician filter | A full-width 48 dp surface, single-choice sheet, `ic_check_circle` | A 32 dp chip inside its 48 dp target, summarising the filter in one line and tinted `primaryContainer` while a filter is in effect |
| Controls above the agenda | Two stacked rows (lane 48 dp + gap 6 dp + filter 48 dp) | **One** 48 dp control row holding both |
| Week strip | 56 dp tall, 44 × 52 dp cells | 48 dp tall, 44 × 48 dp cells |
| Strip on scroll | Always visible | **Collapses** once the agenda is scrolled past 16 dp; the date, its Today action and the control row stay |
| Permanent top-section height | 206 dp | **144 dp** at rest, **96 dp** while the agenda is scrolled |
| Technician selection | One technician or the whole organization | **Multi-select** with checkboxes, a draft, `Clear` and `Apply (n)`; the API answers with the union |
| Card density | 16 dp padding, 6/6/4 dp between lines, 10 dp between rows | 12 dp padding, 4/4/2 dp between lines, 8 dp between rows |
| Timeline rail | 14 dp column, 9 dp status dot | 12 dp column, 7 dp status dot; the rail is structure, not a second status display |
| Now cue | `Now` label, 26 dp row | `Now · 2:35 PM` (localized), 28 dp row, a 10 dp brand dot that is visibly not a Visit's, refreshed every 30 s |
| Sheet | Title, `All technicians` row, outlined search field, plain text rows, no actions | Title + subtitle + close, filled search field, checkbox rows (selected first), a 320 dp scrolling list, `Clear` / `Apply (n)` |

## Decisions taken here

1. **Filtering is secondary, so it shares one row with the lane selector.** Both narrow the same read,
   and 48 dp is the smallest a control can be hit at, so the saving comes from putting them on one row
   and drawing *smaller surfaces inside 48 dp targets* rather than from shrinking the targets. The
   Material arrangement is kept: target 48, surface 32.
2. **The week strip is what collapses, not the controls.** The date header and the control row stay at
   all times, because they are how the manager changes the day, the lane or the filter; the strip is the
   tallest part and the one a scrolled dispatcher has already used. The strip is clipped, not disposed,
   so collapsing never resets the week the manager was on, and selecting another date returns the agenda
   to the top — which brings the strip back.
3. **`All technicians` is the absence of the technician predicate, not a list of everybody.** The chip
   summarises as `All technicians`, one name, or `N technicians`; a filter that is in effect is visible
   without opening the sheet.
4. **Multi-select is a draft in the sheet, applied in one operation.** Tapping a technician toggles the
   draft and nothing is read; `Apply (n)` commits it and closes; `Clear` empties the draft and asks for
   the whole organization at once, staying open — the same draft/apply route the customer filter sheet
   already takes, so the two sheets behave alike (`BR-067`).
5. **The API is asked once for the whole group, and the ids are a union.** `membershipId` is repeatable
   (`?membershipId=a&membershipId=b`), the ids are validated and de-duplicated together, and the day is
   narrowed with a single `EXISTS … IN (…)` predicate. A dispatcher's filter asks "whose work am I
   looking at", so a Visit is kept when **any** selected technician is on it, never only when all of
   them are. Issuing one request per technician, or filtering the rows the client already holds, was
   rejected: the first re-derives the day N times, the second would decide assignment on the client
   (`BR-042`).
6. **The unassigned lane is not narrowed by the technician filter.** It already is not, and the reason
   is unchanged: a Visit with no crew has no technician to match, so a filter cannot select it. The API
   contract already stated this; the slice only made the sentence cover several ids
   (`docs/api/schedule.md` §3.4).
7. **The `now` cue states the time and keeps moving.** It gained the localized time (`Now · 2:35 PM` /
   `Maintenant · 14 h 35`) and re-reads the device clock every 30 seconds, and its rail dot is the one
   dot that is not a Visit's. It still decides nothing: status and overdue remain the API's answers.

No status vocabulary, transition, permission, capability, navigation or other screen is touched, and
nothing on the screen schedules, assigns, reschedules or cancels anything.

## Files

- `api/src/schedule/schedule-query.ts`: `readScheduleMembershipsQuery` replaces
  `readScheduleMembershipQuery` — a single value or a repeated one, blank entries skipped, ids
  de-duplicated, a non-UUID refused.
- `api/src/schedule/schedule.service.ts`, `schedule.controller.ts`: the read takes
  `membershipIds: readonly string[]`, and one `assignedToAny` predicate answers the union.
- `api/src/schedule/schedule-query.spec.ts`, `api/test/schedule.e2e-spec.ts`: the parser's cases, and
  the union answered against PostgreSQL on a date the test owns.
- `android/.../data/schedule/ScheduleApi.kt`, `ScheduleRepository.kt`: the repeated `membershipId`.
- `android/.../ui/schedule/ScheduleUiState.kt`: `technicianFilters`, `technicianFilterIds`,
  `hasTechnicianFilter`.
- `android/.../ui/schedule/ScheduleViewModel.kt`: `selectTechnicians` (one read for the group).
- `android/.../ui/schedule/SchedulePresentation.kt`: `orderTechnicianOptions`,
  `technicianFilterSummary`, `chosenTechnicians`, `formatClockTime`.
- `android/.../ui/schedule/ScheduleTopSection.kt`: the compact control row, the collapsing strip, the
  selection sheet, its filled search field and its actions.
- `android/.../ui/schedule/ScheduleScreen.kt`: the agenda's scroll state and collapse threshold, the
  card and rail metrics, the advancing `now` cue.
- `android/.../ui/customers/CustomersScreen.kt`: the shell passes `selectTechnicians`.
- `res/values*/strings.xml`: EN/FR for the sheet, its actions and the `now` cue.
- Tests: `SchedulePresentationTest`, `ScheduleViewModelTest`, `ScheduleRepositoryTest`,
  `ScheduleScreenTest`, `ServoraHomeNavigationTest` (the fake repository's signature).
- `docs/api/schedule.md`, `docs/design/android-design-system.md`.

## Identified, not implemented

1. **The week strip's dot still marks today, not "a date with scheduled work".** The approved design
   reference draws a dot on days that have jobs, and this pass was asked to keep that meaning — but
   `GET /schedule` answers for **one** day and carries no week-level signal, so a client cannot know
   which of the seven days hold work. Implementing it needs a new projection (which days of the
   requested week contain a Visit in a not-cancelled status, under the `BR-074` classification), which
   is an API decision rather than a presentation one. The dot therefore keeps its documented meaning
   ("today") instead of being silently changed, and this is the open item of this slice.
2. **Scheduling conflicts on the day.** Still not shown, for the reason `docs/tracker/039` records: the
   day read does not carry `BR-070`'s conflict condition.
3. **Row-level actions on a card** (assign, reschedule, change status) remain the Job Details screen's,
   unchanged.
4. **The technician chip's label at 360 dp in French.** The control row puts the lane selector and the
   chip side by side; `Tous les techniciens` is the longest of the two languages' default summaries and
   ellipsizes on the narrowest phones. It degrades gracefully (the sheet states the full label), but
   worth the product owner's eye at their device width — see the runbook.

## Manual QA — Android (product owner)

Sign in as a Manager with at least three technicians and several Visits today (one of them with no
crew), then open **Schedule**.

1. Confirm the top section reads as: the date with its chevron, the week strip, and **one** row holding
   `Schedule | Unassigned (n)` on the left and the technician chip on the right. Neither control should
   look like a filled button.
2. Confirm the lane in effect is a small steel-blue pill and the other lane is quiet text, that the
   Unassigned count badge is visible while that lane is not selected, and that switching lanes still
   shows that lane's rows.
3. Tap a day in the strip and confirm the agenda follows it. Swipe the strip a week and confirm the
   agenda does **not** change until a day is tapped.
4. Scroll the agenda down: the week strip should animate away and the date, its **Today** action and the
   control row should stay. Scroll back up and confirm the strip returns to the week you were on (not to
   a distant one). Change the date and confirm the agenda starts at the top of the new day.
5. Open the technician chip. Confirm: the title, the one-line subtitle, a filled search field, the close
   button; `All technicians` first, checked; the organization's technicians with checkboxes.
6. Tap two or three technicians in a row and confirm **the sheet stays open** and each row shows a check
   and a tint.
7. Type a name into the search. Confirm the list narrows, that `All technicians` stays offered, and that
   a technician you selected **before** typing is still selected — apply and confirm they are still in
   the filter.
8. Apply and confirm: the sheet closes, the chip summarises the filter (`Luc Gagnon` for one,
   `3 technicians` for several) and is tinted, and the day narrows to the Visits **any** of them is
   assigned to (a Visit with two of them appears once, not twice).
9. Reopen the chip, tap **Clear**, and confirm the day returns to the whole organization while the sheet
   stays open (`All technicians` checked again).
10. With a filter applied, switch to **Unassigned** and confirm that lane is unchanged — a Visit with no
    crew must still be listed, because the technician filter cannot select work with no technician.
11. On today, confirm the `Now` row reads `Now · <time>` with your device's time, that its dot is bigger
    and steel-blue rather than a status colour, and that it sits between the Visits that have started
    and those that have not. Switch to another date and confirm it disappears.
12. Count how many Visits are visible at once: the cards should be visibly denser than before, and the
    rail should read as a thin line of small dots rather than a second status display.
13. Confirm a tap anywhere on a card still opens that Visit's Job.
14. The technician chip's label is the tightest line on the screen: check it at your device's width, and
    check it in **French** (the sheet's own label is the full `Tous les techniciens`).
15. Switch the device to French and repeat 1, 3, 4, 5, 7, 8 and 11.
16. Confirm light and dark appearance both read correctly, and that no state — lane, filter, status — is
    communicated by colour alone.
17. Background the app, reopen Schedule and confirm the day, lane and technician filter are where you
    left them.

Expected: nothing here schedules, assigns, reschedules or cancels anything; no Job status appears on a
card.

Not covered by the automated tests, so worth your eye: the strip's collapse/expand animation, the chip's
label fitting at your device width in both languages, the now cue's placement and dot, and the sheet with
a long team and the keyboard open (the list scrolls on its own and the actions stay reachable).

## Defects reported from physical-device QA (fixed 2026-09-17)

Two presentation defects were reported by the product owner from the device. Neither changes a business
rule, an API contract, a permission or the day's read: one is where a report is drawn, the other is a
sheet that did not close. Nothing here schedules, assigns, reschedules or cancels anything.

| Defect | Cause | Fix |
| --- | --- | --- |
| The report of an action could not be read: it was covered by the floating action in the bottom-right corner of the Job Details screen. | `JobDetailsScreen` drew its `SnackbarHost` at the screen's own bottom edge, while the floating **Add update** action sits in the bottom-end corner and the evidence tray/notice takes the bottom edge. Whichever surface was there drew over the report — a refusal, which is the action's only report, included (`BR-042`). | The report, the floating action and the unaccepted evidence are now **one bottom-anchored `Column`** in that order, so the report is always drawn above whatever is pinned below it (`JobDetailsReportSpacing` is the gap). The action is `align(Alignment.End)` within that column and the tray stays flush at its end, so the visual arrangement is otherwise unchanged. |
| The technician filter sheet stayed open after **Apply**. | `ScheduleTechnicianFilter` passed `onApply` straight through, so the sheet's own `choosing` flag was never cleared; only **Clear** ever left it, and Clear staying open is deliberate. | Applying commits the draft **and closes** the sheet. **Clear** keeps its documented behaviour of asking for the whole organization at once and staying open, so a different filter can be chosen without reopening the sheet. |

Tests (device-run; they are the product owner's to execute, `qa.md` §7.3):

- `JobDetailsScreenTest.drawsTheReportAboveTheFloatingActionItWouldOtherwiseBeUnder` and
  `...drawsTheReportAboveTheEvidenceWaitingToBeSaved` assert the report's bottom edge is above the
  action's and the tray's top edge, so the defect cannot return unnoticed.
- `ScheduleScreenTest.closesTheSheetWhenTheManagerAppliesTheSelection` asserts the sheet is gone after
  Apply. Clear's own test still asserts it stays.

The runbook's step 8 already required the sheet to close on Apply; the defect was the code not following
the requirement, not the requirement.


## Verification

**Status: Tier B is INCOMPLETE — the full sweep was interrupted at the product owner's request.**

The product owner had to power the machine off mid-sweep: the full Android lint analysis had been running
for minutes, the machine had become hot and unresponsive, and lint is not to be run again until they say
so. What follows is what was actually executed, and nothing else is claimed.

| Check | Command | Result |
| --- | --- | --- |
| API type check | `npm run typecheck` | **PASS with the 4 errors that predate this slice** (`test/technician-home.e2e-spec.ts:351,354` — the two `docs/tracker/039` recorded — and `test/visit-field-lifecycle.e2e-spec.ts:611,636`, from the field-lifecycle work in flight in this worktree). **No error in `src/schedule/` or `test/schedule.e2e-spec.ts`.** |
| API lint | `npm run lint` | **PASS** — 0 warnings, 0 errors. |
| API build | `npm run build` | **PASS**. |
| API unit (full) | `npm test` | **PASS** — 463 tests in 53 files (461 in 53 before this slice: the +2 are `schedule-query.spec.ts`'s new cases). |
| API e2e (targeted) | `npm run test:e2e -- test/schedule.e2e-spec.ts` | **PASS** — 14 tests (13 before), including the new *answers with the union of the technicians the filter names*, against the running foundation stack. |
| Android compile (main) | `./gradlew compileDebugKotlin` | **PASS** at the revision before the three closing corrections below. |
| Android compile (test sources) | `./gradlew compileDebugUnitTestKotlin compileDebugAndroidTestKotlin` | **PASS** at that same revision. |
| Android unit (targeted) | `./gradlew testDebugUnitTest --tests 'com.servora.android.ui.schedule.*' --tests 'com.servora.android.data.schedule.*'` | **PASS** — 43 tests, 0 failures (`SchedulePresentationTest` 17, `ScheduleViewModelTest` 13, `ScheduleRepositoryTest` 8, `ScheduleWeekTest` 5). |
| Android unit (full) | `./gradlew testDebugUnitTest` | **NOT RUN — interrupted.** The task had started in the combined sweep and the build never reported its result before the machine was switched off. |
| Android lint (full) | `./gradlew lintDebug` | **NOT RUN — interrupted, and deferred at the product owner's request.** In the interrupted sweep `lintAnalyzeDebugAndroidTest` reported `FAILED` with no reason written before the build hung; that run also had Android sources edited underneath it, so the failure is not yet attributable and must be re-run against a quiescent tree. |
| Android build | `./gradlew assembleDebug` | **NOT RUN — interrupted** (the task started; no result was reported). |
| Device-test sources | `./gradlew assembleDebugAndroidTest` | **NOT RUN since the closing corrections** — it reported `PASS` at the earlier revision. **No `adb` and no device/emulator command was run** (`qa.md` §7.3). |
| Physical device | — | **NOT RUN — the product owner's**, with the runbook above. |

**The three closing corrections are not yet compiled or tested.** They were made after the last
`compileDebugKotlin`/targeted-unit run and are the first thing to verify when the machine is free:

1. the week strip now collapses by animating its height and clipping it (`animateDpAsState` +
   `clipToBounds`) instead of `AnimatedVisibility` — `AnimatedVisibility` disposes its content, which
   would have reset the week pager on every expand;
2. a collapsed strip is `clearAndSetSemantics {}`, so a screen reader cannot reach a date nobody can see;
3. the agenda's collapse flag is keyed `remember(agendaState, collapseThreshold) { derivedStateOf { … } }`.

**To finish Tier B** (`qa.md` §3.1), when the product owner is ready — preferably with lint on its own,
and with nothing else running on the machine:

```bash
cd android && ./gradlew compileDebugKotlin
cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.schedule.*' \
                                       --tests 'com.servora.android.data.schedule.*'
cd android && ./gradlew lintDebug          # alone, not alongside the test/build tasks
cd android && ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
make android-stop
```

Avoid editing Android sources while a sweep is in progress: the interrupted run had sources changed
underneath it, which is the most likely cause of the lint analysis hanging.

Hygiene: no Gradle or Kotlin build daemon is running now (`pgrep -af
'gradle-wrapper.jar|GradleDaemon|KotlinCompileDaemon'` lists nothing), the daemons the earlier runs left
were released with `gradlew --stop`, and this task's temporary log files in `/tmp` were deleted
(`dev.md` §18, `qa.md` §7.4). The foundation stack (`make up`) was left untouched.

**The two QA defect fixes recorded above are NOT COMPILED.** The product owner asked for Android builds and tests
to stop, because they are heavy enough to make their laptop unworkable; the daemon the attempt started was
released and the temporary log deleted. What has been done instead is a review of the diff, and the change
is static: one composition reorganization in `JobDetailsScreen` and one callback in
`ScheduleTopSection`. `./gradlew compileDebugKotlin compileDebugUnitTestKotlin
compileDebugAndroidTestKotlin` is the first thing to run when the machine is free, before the rest of
Tier B.

## Phase log

| # | Date | What was done | Files | Verification |
| - | ---- | ------------- | ----- | ------------ |
| 1 | 2026-09-17 | The read's filter: `membershipId` repeatable, the ids validated and de-duplicated together, and one `EXISTS … IN (…)` answering the union. | `api/src/schedule/schedule-query.ts`, `schedule.service.ts`, `schedule.controller.ts` | **Tier A:** `npm run typecheck` (no new error), `vitest run src/schedule/*` PASS |
| 2 | 2026-09-17 | The read's tests and contract: the parser's cases, the union answered against PostgreSQL, and the query/filter sections of the contract. | `api/src/schedule/schedule-query.spec.ts`, `api/test/schedule.e2e-spec.ts`, `docs/api/schedule.md` | **Tier A:** `npm test -- src/schedule` PASS; `npm run test:e2e -- test/schedule.e2e-spec.ts` PASS (14) |
| 3 | 2026-09-17 | The client's filter: the repeated query, the state's list of technicians, `selectTechnicians`, and the presentation decisions (option order, chip summary, chosen technicians, clock). | `android/.../data/schedule/ScheduleApi.kt`, `ScheduleRepository.kt`, `ui/schedule/ScheduleUiState.kt`, `ScheduleViewModel.kt`, `SchedulePresentation.kt` | **Tier A:** `compileDebugKotlin` PASS |
| 4 | 2026-09-17 | The density pass: one compact control row, the compact segmented lane control, the summarising chip, the collapsing strip, the selection sheet with its actions, and the tighter card/rail/now cue. | `android/.../ui/schedule/ScheduleTopSection.kt`, `ScheduleScreen.kt`, `CustomersScreen.kt`, `res/values*/strings.xml` | **Tier A:** `compileDebugKotlin` PASS |
| 5 | 2026-09-17 | The tests: the presentation decisions, the multi-select behaviour, the repeated query, the sheet's screens. | `android/app/src/test/.../schedule/*`, `ScheduleRepositoryTest.kt`, `androidTest/.../ScheduleScreenTest.kt`, `ServoraHomeNavigationTest.kt` | **Tier A:** `compileDebugUnitTestKotlin compileDebugAndroidTestKotlin` PASS; the targeted unit tests PASS (43) |
| 6 | 2026-09-17 | The closing corrections: the strip collapses by animating a clipped height (so the pager is not disposed), a collapsed strip leaves the semantics tree, and the collapse flag is keyed. | `android/.../ui/schedule/ScheduleTopSection.kt`, `ScheduleScreen.kt` | **NOT VERIFIED — the machine had to be switched off during the Tier B sweep.** Compile these two files first when the machine is free (see Verification). |
| 7 | 2026-09-17 | The documentation: the API contract, the design system's Manager Schedule section, this tracker and the README pointer. | `docs/api/schedule.md`, `docs/design/android-design-system.md`, `README.md`, this tracker | Reviewed against `BR-012`, `BR-024`, `BR-041`, `BR-042`, `BR-067`, `BR-068`, `BR-071`; Tier B completion pending |
| 8 | 2026-09-17 | The two defects physical-device QA reported: the technician sheet not closing on **Apply**, and the action report being covered by the floating action (and by the evidence tray) on Job Details. | `android/.../ui/schedule/ScheduleTopSection.kt`, `ui/jobs/JobDetailsScreen.kt`, `androidTest/.../ScheduleScreenTest.kt`, `JobDetailsScreenTest.kt`, `docs/design/android-design-system.md` | **NOT COMPILED — Android builds stopped at the product owner's request** (`qa.md` §3.1 Tier A would be `compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin`, then `testDebugUnitTest --tests 'com.servora.android.ui.schedule.*' --tests 'com.servora.android.ui.jobs.*'`). Diff reviewed; the two new Job Details tests and the schedule test are device-run and are the product owner's (`qa.md` §7.3) |
