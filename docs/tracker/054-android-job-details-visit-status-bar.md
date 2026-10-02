# Tracker 054 — The Visit's working-status control becomes one bar

**Status: IMPLEMENTED** (Android only; the compile and the device-test sources are what the agent ran —
**Android physical-device QA is the product owner's**, `qa.md` §7)

Date: 2026-09-20

Type: **presentation refinement of one control.** No business rule, API contract, database schema, shared
contract or offline/outbox behaviour changes.

Business rules: `BR-006`, `BR-007`, `BR-011`, `BR-012`, `BR-028`, `BR-041`, `BR-058`, `BR-074`, `BR-075`,
`BR-077`, `BR-093` (all respected, none changed)

Refines, and does not replace: `docs/tracker/037-technician-field-experience.md` (the working statuses are
the control, and the technician drives them in one tap) and
`docs/tracker/052-android-technician-job-details-redesign.md` (the Visit card's hierarchy). The control's
**behaviour** is unchanged; only its shape is.

## Why

The four working states were drawn as a `FlowRow` of four `FilterChip`s, each sized to its own label and
each led by a colour dot. Product ownership reported the row as ugly, and the causes are concrete:

1. **Four ragged widths that wrap.** The labels differ in length (`Scheduled`, `En route`, `On site`,
   `In progress`) and a `FlowRow` gives each chip its intrinsic width, so the row is uneven from the first
   frame and — at a larger text scale, or in French, where `En cours`/`Sur place`/`Prévue` differ again —
   it silently becomes **two** rows of objects. A field control that changes shape as the text grows is not
   one control (`BR-012`, `BR-028`).
2. **Two signals for one fact.** The leading dot *and* Material's selected fill both said "this one", and
   the other three dots were decoration.
3. **The selected chip was rendered from a disabled palette** (it is deliberately not tappable), with its
   colours remapped by hand, so the strongest element on the row was technically a disabled control.
4. **No rank of its own.** It sat under a `labelSmall` **Visit status** heading, as the fourth thing inside
   a card that already carries the date, the crew, the completion action and two text actions.

## What changed

| Before | After |
| ------ | ----- |
| A `FlowRow` of four `FilterChip`s, each sized to its own label, each with a 16 dp colour dot | One `SingleChoiceSegmentedButtonRow` across the card's width: four **equal** segments, sharing one outline, with no dots |
| `labelLarge` chip labels, under a `labelSmall` **Visit status** group label | `labelSmall` bold, centred segment labels and **no** group label |
| Selected = disabled `FilterChip` with remapped disabled colours | Selected = `SegmentedButton` with the disabled-and-selected colours stated as the selected ones (`primaryContainer`/`onPrimaryContainer`), a check glyph deliberately **not** drawn |
| Content padding Material's default for a chip | `PaddingValues(horizontal = 4.dp, vertical = 8.dp)`, because four segments must hold the widest label of both languages on a phone |

Everything the control **decides** is unchanged, deliberately (`BR-041`):

* The four states are still `VisitStatus.technicianWorkingStates`, and the destinations are still exactly
  the `allowedStatusTransitions` the **API** reported for this Visit — no lifecycle rule is re-implemented
  and no destination is derived.
* The state the Visit already holds is still the selected one and still cannot be tapped, because moving a
  Visit to where it already is is not a transition (`BR-074`).
* One tap is still one transition, forwards or backwards (`BR-074`, `BR-075`), and completion is still not
  one of the states (`BR-077`).
* The bar is still drawn only when the caller may drive the Visit (`BR-006`, `BR-007`, `BR-093`); a session
  that may not is still shown the Visit's badge on the date row.
* The tags the device tests use are unchanged: `JobDetailsVisitStatusSelectorTag` on the row and
  `jobDetailsVisitStatusOptionTag(state)` on each segment, so `JobDetailsScreenTest`'s assertions
  (`assertIsSelected`, `assertIsEnabled`, `assertIsNotEnabled`) still hold on the shared control.

Removed with the chips, because nothing else drew them: `VisitWorkingStatusChip`,
`workingStatusChipColors`, `StatusChipDot`, `visitStatusIndicatorColor` and `VisitStatusChipSpacing`. The
`StatusChipDot`/`visitStatusIndicatorColor` pair had been the only place a working state's own colour was
resolved, and the Visit badge (`VisitStatusPill`) resolves it independently, so nothing lost its colour
mapping. A stale block comment that still described a chip-that-opens-a-menu (replaced by chips in
`tracker 037`) was removed with them (`dev.md` §15).

**The frozen previous screen inherits the new shape, and that is deliberate.** `ui/jobs/JobDetailsScreen.kt`
— the presentation `tracker 044`/`052` keep as the screen the destination used to draw — draws the same
`VisitWorkingStatusSelector`, so restoring that presentation now restores the bar rather than the chips. The
file itself was not touched. Its device test (`JobDetailsScreenTest`) asserts the control's own contract
rather than its shape — the selector tag, the per-state option tags, `assertIsSelected`, `assertIsEnabled`
and `assertIsNotEnabled` — and every one of those still holds, because `SegmentedButton` carries the same
selectable and enabled semantics a `FilterChip` carries and the tags were not removed.

## Files

| File | Change |
| ---- | ------ |
| `android/app/src/main/java/com/servora/android/ui/jobs/JobDetailsActions.kt` | `VisitWorkingStatusSelector` rewritten as the segmented bar; `workingStatusColors` added; the chip, its colours, the dot, the indicator colour and the chip spacing removed, with the imports they needed |
| `android/app/src/androidTest/java/com/servora/android/ui/jobs/JobDetailsOverviewScreenTest.kt` | `drawsTheVisitsWorkingStatesAsOneBarInsideTheVisitSection` |
| `docs/design/android-design-system.md` | the **Visit working-status bar** row in the Job Details table |
| `docs/tracker/054-android-job-details-visit-status-bar.md` | this entry |

## Tests

| Level | What is covered | Where |
| ----- | --------------- | ----- |
| Android UI (Compose, device) | The bar is inside the Visit section, the Visit's own state is the selected segment, a destination the API reported is enabled, and one it did not report is not | `JobDetailsOverviewScreenTest` (`drawsTheVisitsWorkingStatesAsOneBarInsideTheVisitSection`) |
| Android UI (Compose, device), inherited | The same contract through the frozen previous screen: the selector is displayed/absent as the session allows, each option tag exists, the Visit's state is selected and the states the API did not report are not enabled | `JobDetailsScreenTest` (`offersExactlyTheVisitsWorkingStatesToASessionThatMayDriveIt`, and the absence cases) — unchanged, and still satisfied because the tags and the semantics are the same |

No JVM unit test covers this: it is a Compose presentation change, and the control's coverage is its device
tests, which the product owner runs (`qa.md` §7.3).

## Verification

Scope (`qa.md` §3.1): **Tier A — targeted, Android only.** Nothing outside `android/` and the documentation
changed, so no API, Angular or database command was required.

| Check | Command | Result |
| ----- | ------- | ------ |
| Android compile (main) | `cd android && ./gradlew compileDebugKotlin` | **PASS** |
| Android device-test sources | `cd android && ./gradlew assembleDebugAndroidTest` | **PASS** — the new test compiles into the androidTest APK |
| Android JVM unit tests, the changed package | `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.*'` | **PASS** — 10 classes, **173 tests, 0 failures** (run for `tracker 053`, which changed the same two source files; no unit test covers either change) |
| Android physical device | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run |
| Android lint | **not run, by the product owner's instruction** | The machine is not strong enough to carry a full `lintDebug` alongside the other work; the tier defers lint to the feature's Tier B sweep in any case (`qa.md` §3.1). No lint finding is expected from a change that removes code and replaces one Material control with another |
| API / Angular / database | — | **NOT RUN — nothing outside `android/` and the documentation changed** |

## Manual QA runbook (product owner)

1. Sign in as a **Technician** with a Visit assigned today and open that Job.
2. In the Visit card, confirm the four working states are **one bar of four equal segments** on one line —
   `Scheduled`, `En route`, `On site`, `In progress` — with no separate **Visit status** heading above them
   and no coloured dot inside any segment.
3. Confirm the state the Visit is in is the **filled** segment, and that tapping it does nothing.
4. Tap a state the API offered (for example **On site**): the Visit moves in one tap, the filled segment
   follows, and the status is reported as updated.
5. Confirm the moved-to state is now the filled (non-tappable) one and the state it came from became
   tappable, so a backward correction is one tap (`BR-074`).
6. Open a Job whose Visit the API offers no destination for: the bar still states where the Visit is and
   none of the states can be tapped.
7. Open a Job the office **canceled**: no bar is drawn — the notice above the work is the whole answer.
8. Switch the device to French: the bar reads `Prévue`, `En route`, `Sur place`, `En cours`, each on **one**
   line.
9. With TalkBack on, traverse the bar: each segment is announced as a radio button with its state's name,
   and the Visit's own state is announced as selected.

**Expected:** one control, four equal segments, one line in both languages, one tap per transition, and the
Visit's state always the obvious one.

## Hygiene

The verification log this task wrote under `/tmp` was removed, and the Gradle/Kotlin build daemons it started
were stopped with `make android-stop` (see the completion summary). The foundation stack (`make up`) was not
touched, no database or storage volume was changed, and no editor tab or file was opened in the product
owner's editor.

