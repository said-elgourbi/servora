# Tracker 053 — The technician's Add update action becomes the page's floating action

**Status: IMPLEMENTED** (Android only; the compile and the device-test sources are what the agent ran —
**Android physical-device QA is the product owner's**, `qa.md` §7)

Date: 2026-09-20

Type: **presentation refinement of an existing screen.** No business rule, API contract, database schema,
shared contract or offline/outbox behaviour changes.

Business rules: `BR-006`, `BR-007`, `BR-011`, `BR-012`, `BR-014`, `BR-027`, `BR-041`, `BR-062`, `BR-079`,
`BR-080` (all respected, none changed)

Refines, and does not replace: `docs/tracker/052-android-technician-job-details-redesign.md` — the page's
hierarchy, its sections, its wording and its reads are unchanged; this slice moves one action.

## Why

The redesign put **Add update** inside the Current Visit card and kept the same action floating over the page
**only while that card's action row had scrolled out of view**. In the field the entry point to the page's
most frequent write therefore appeared and disappeared as the technician scrolled, and the action they came
to use sat in a card row that has to be found first.

Product ownership directed that **Add update** be a floating action: one control, in the same place at every
scroll position.

## What changed

| Before (`tracker 052`) | After |
| ---------------------- | ----- |
| **Add update** was the left-hand action of the Current Visit card's own row, beside **Complete visit**, both `FilledTonalButton`s of equal width | The card's action row holds **Complete visit** alone, across the card's width |
| The floating **Add update** action was drawn **only** while the card's action row had scrolled out of view: the row reported its position inside the scrolling content and the page compared it with the scroll offset | The floating **Add update** action is drawn at **every** scroll position |
| Two entry points existed for one write, alternating by scroll position | One entry point: the floating action |

Everything else is unchanged. The action is still gated on the API's `addUpdateAllowed` for the represented
Visit (`BR-062`, `BR-079`) and on the session's own capabilities (`BR-006`, `BR-007`), it still opens the
same `JobUpdateSheet`, it is still withheld while the device holds evidence the API has not accepted
(`BR-014`), and the page still reserves bottom clearance so the last Activity entry is never covered
(`BR-012`, `BR-080`). Completing a Visit stays where the field attempt is, because completion is an action
**on** that Visit rather than an entry point into the Activity (`BR-077`).

Removing the conditional gate also removed the scroll-position plumbing that existed only to compute it: the
`onGloballyPositioned` measurement on the card's action row, the `visitActionsTop`/`visitActionsHeight`
state, the derived "off screen" flag and the `LaunchedEffect` that reported it to the screen. That is dead
code once the action is unconditional, so it was deleted rather than left behind (`dev.md` §15, §17).

## Files

| File | Change |
| ---- | ------ |
| `android/app/src/main/java/com/servora/android/ui/jobs/JobDetailsOverviewScreen.kt` | the floating action's gate and its doc comment; the card's action row; the removed scroll-position plumbing and the four imports it needed |
| `android/app/src/main/java/com/servora/android/ui/jobs/JobDetailsActions.kt` | `VisitAddUpdateAction` and `JobDetailsVisitAddUpdateTag` removed (no caller left); `JobDetailsVisitFieldActionsTag`'s doc comment |
| `android/app/src/androidTest/java/com/servora/android/ui/jobs/JobDetailsOverviewScreenTest.kt` | the two tests below |
| `docs/design/android-design-system.md` | **Where that one action is drawn** — the placement this change lands |
| `docs/tracker/052-android-technician-job-details-redesign.md` | the superseded decision row and the runbook step point here |
| `docs/tracker/053-android-job-details-add-update-floating-action.md` | this entry |

## Tests

| Level | What is covered | Where |
| ----- | --------------- | ----- |
| Android UI (Compose, device) | The floating action is offered as soon as the Job is read, carries a click action, and is the **only** Add update control on the page; and it is absent when the API reports the represented Visit takes no update | `JobDetailsOverviewScreenTest` (`offersAddUpdateAsThePagesFloatingAction`, `withholdsTheFloatingAddUpdateWhenTheVisitTakesNoUpdate`) |

No JVM unit test covers this change: it is a Compose presentation change, and the screen's coverage is its
device test, which the product owner runs (`qa.md` §7.3).

## Verification

Scope (`qa.md` §3.1): **Tier A — targeted, Android only.** Nothing outside `android/` and the documentation
changed, so no API, Angular or database command was required.

| Check | Command | Result |
| ----- | ------- | ------ |
| Android compile (main) | `cd android && ./gradlew compileDebugKotlin` | **PASS** |
| Android device-test sources | `cd android && ./gradlew assembleDebugAndroidTest` | **PASS** — the two new tests compile into the androidTest APK |
| Android JVM test sources compile | `cd android && ./gradlew compileDebugUnitTestKotlin` | **PASS** — no unit test referenced the removed `VisitAddUpdateAction` / `JobDetailsVisitAddUpdateTag` |
| Android JVM unit tests (the changed package) | `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.*'` | **PASS** — 10 classes, **173 tests, 0 failures** |
| Android physical device | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run. Command the product owner may run: `cd android && ./gradlew connectedDebugAndroidTest` |
| Android lint | deferred | **NOT RUN at this tier** — the full `make android-lint` belongs to the feature's Tier B sweep (`qa.md` §3.1) |
| API / Angular / database | — | **NOT RUN — nothing outside `android/` and the documentation changed** |

## Manual QA runbook (product owner)

1. Sign in as a **Technician** with a Visit assigned today and open that Job.
2. Confirm **Add update** is a floating action at the bottom right of the page **as soon as the Job is
   read** — before scrolling.
3. Confirm the Current Visit card no longer carries its own **Add update** button, and that **Complete
   visit** is still there, across the card's width.
4. Scroll the page from top to bottom and back: the floating action stays on the bottom right.
5. Tap it, add a note, and confirm the sheet that opens is the same one as before (`Write note` /
   `Add photo` / `Add audio`, as the session may add them).
6. Take a photo and leave it in the tray: confirm the floating action is **withheld** while the tray is on
   screen, and returns once the tray is empty.
7. Open a Job the office has **canceled**, and one it has **completed**: confirm no floating action is
   offered on either.
8. Switch the device to French: the floating action reads `Ajouter une mise à jour` and nothing else moved.

**Expected:** one Add update entry point, in the same place at every scroll position, offered only when the
API and the session's capabilities allow the write.

## Hygiene

The verification logs this task wrote under `/tmp` were removed, and the Gradle/Kotlin build daemons were
stopped with `make android-stop` (see the completion summary). The foundation stack (`make up`) was not
touched, no database or storage volume was changed, and no editor tab or file was opened in the product
owner's editor.
