# Tracker 020 — The Job's status chip is the status control (Android)

**Status: COMPLETE for the Android implementation; physical-device QA is the product owner's**

**Refined by `docs/tracker/021-android-job-details-status-polish.md`:** the chip is still the status
control, but it now leads with a status dot under a **Job status** label, and its menu is a compact list
of the permitted transitions with the current status stated at its head rather than a row of status
chips. Decision 8 below and the menu description in `docs/design/android-design-system.md` describe the
screen as it was before that refinement.

Date: 2026-09-14
Predecessor: `docs/tracker/019-android-job-details-hierarchy.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-010`, `BR-012`, `BR-028`, `BR-041`, `BR-042`,
`BR-056`, `BR-058`, `BR-059`, `BR-064`, `BR-066`, `BR-067`
Domain model: `docs/domain/job-visit-domain-model.md` §5 – §7
API contract: `docs/api/job-details.md`, `docs/api/job-actions.md` — **unchanged by this slice**
Design reference: `Figma/src/screens/JobDetails.tsx` (Manager view), `servora-job-details-spec.md`
Design system: `docs/design/android-design-system.md`

## Scope

Two presentation items the product owner asked for on the Manager Job Details screen, from the design:

1. the Job's address row is marked as opening the device's map application, as the design's own row is;
2. the Job's status chip **is** the control that changes the status, instead of a separate
   "Change status" action drawn beside it.

The slice is presentation plus its tests. It adds no API, no table, no capability and no business rule,
and it touches no other screen.

| Item                                                                      | Status      |
| ------------------------------------------------------------------------- | ----------- |
| The address row ends in the design's navigation glyph (`ic_navigation`)    | Implemented |
| The glyph is drawn only where the row is a control, and it names the action | Implemented |
| The Job's status chip is the control that moves the Job's status           | Implemented |
| The chip wears the current status's own colour and label                   | Implemented |
| The chip's trailing chevron says it opens something (`ic_chevron_down`)    | Implemented |
| The menu lists only the transitions the backend reported (`BR-058`)        | Implemented |
| Cancellation is not folded into the status control (`BR-064`)              | Unchanged — not offered by the API, see below |
| The chip is interactive only where the capability allows (`BR-007`)        | Implemented — otherwise the status is presented and does not act |
| EN/FR copy for the one new string                                          | Implemented |
| API, schema, capabilities and offline behaviour                           | Unchanged |


## Decisions taken here

1. **The status chip is the status control.** The header presented the status twice: as a chip, and as a
   "Change status" action beside it that described the same state in different words. A state a user can
   move is now a state they tap, so the chip is the control — colour, label and action in one control,
   which is the reading the design's own header invites: the status line is what the header is read for.
2. **The chip's colour language did not change, and did not split.** `JobStatusPill` is still the single
   definition of what a Job status looks like (`BR-041`), and the interactive state is the *same* chip:
   one colour mapping (`jobStatusColors`), one border, one label source. Nothing was introduced to make
   a control look like a chip, because the chip is the control.
3. **The permitted transitions still come from the backend, so nothing arbitrary can be offered.** The
   menu is built from the Job's `allowedStatusTransitions` exactly as before (`BR-058`, `BR-041`). The
   client holds no copy of the lifecycle and invents no transition; a Job with no permitted transition
   does not get an interactive chip at all.
4. **Cancellation stays out of the status control.** `BR-064` requires a structured cancellation reason
   and a mandatory explanation, and that catalogue is an open question, so the API refuses cancellation
   (`409 JOB_CANCELLATION_UNAVAILABLE`) and leaves `CANCELED` out of `allowedStatusTransitions`
   (`docs/api/job-actions.md` §3). Folding it into the chip's menu would offer an action the API refuses
   and would hide a destructive, confirming action inside a one-tap list. When the catalogue exists,
   cancelling is a separate, confirming action — not a menu entry here.
5. **Without the capability the status is still presented.** `BR-007` means the *action* is not offered;
   it does not mean the Job's status is hidden. A caller who may not move the Job sees the static chip
   the customer screens already draw, and the read they are authorized for is unaffected.
6. **The chip is a Material 3 clickable `Surface`, not a hand-rolled target.** That is the platform's own
   statement of a control: the chip keeps its measured size, the touch target the platform expects is
   reserved around it, the ripple is confined to the chip's shape, and it works with TalkBack and the
   platform's dimming rather than beside them.
7. **The glyph on the address row is the design's, and it is drawn only where it is true.** A Job with no
   address has nothing to navigate to, so that row is neither tappable nor marked (`BR-056`). The glyph
   is tinted `primary` as the design tints it, and carries a content description, because the row's own
   text states the address and not what tapping it does (`BR-028`).
8. **The menu item states the status once.** The item previously drew the status chip and then the same
   status's label beside it. The chip already carries the status's colour and its localized label, so
   the item is now the chip alone (`BR-028`, `BR-041`).

## What this slice did not change

- **The API, its contracts and the capabilities** are untouched: the same read answers the status and the
  transitions it permits, and the same `JOB_UPDATE` capability gates the action
  (`docs/api/job-actions.md`).
- **The Visit and the crew actions** are untouched. A reschedule still edits the Visit on the Visit's own
  card, and the crew is still stated with the crew (`BR-066`), because those are different state machines
  and different records (`BR-059`).
- **Job cancellation** does not exist as an action on any client; it becomes one when `BR-064`'s reason
  catalogue is decided, and it is then a separate confirmed action (decision 4).
- **The actions remain online-only**, unchanged from trackers 018 and 019: the screen holds no offline
  working set yet (`BR-031`, `BR-032`, `BR-086`).

## Verification

```text
Android
- JVM unit tests (`make android-test`): PASS — 275 tests, 0 failures
- Lint (`make android-lint`): PASS
- Debug build (`make android-build`): PASS
- Device-test sources (`./gradlew assembleDebugAndroidTest`): PASS (compiled)
- Physical device: AWAITING PRODUCT OWNER

API
- No API, schema or capability change in this slice; nothing was re-run for it.
```

The Compose test of this screen (`JobDetailsScreenTest`) is a device test, so the agent compiled its
sources and left it to the product owner's device (`qa.md` §7.3). It now covers:

- the chip being the control it presents — the Job's current status is on the tapped node, and the menu
  offers only what the backend permits (`COMPLETED` for a Job awaiting review, and neither `CANCELED`
  nor a status the Job cannot move to);
- the capability gate hiding the action while still presenting the Job's status;
- the address row carrying the navigation glyph, and not carrying it when the Job has no address.

## Manual QA (product owner, physical device)

```text
Android QA — Job Details status control and the address glyph

Sign in as the Manager development account (`make seed`, API running).

1. Open a Job from Home.
   Expected: the header's first line is the Job's status chip and it now ends in a small chevron. There
   is no "Change status" action anywhere beside it.

2. Tap the status chip.
   Expected: a menu opens listing exactly the transitions the API permits for that Job's status (for
   example "Completed" on a Job awaiting review), each drawn as its own status chip. There is no
   "Canceled" entry and no status the Job cannot move to.

3. Choose the offered transition.
   Expected: the menu closes, the chip takes the new status's colour and label, and "Job status updated
   successfully" appears briefly.

4. Open a Job whose status has no permitted transition (its chip shows no chevron) and tap the chip.
   Expected: nothing opens; the status is still presented.

5. Sign in as a user who may view Jobs but not update them.
   Expected: the Job's status is still shown, and the chip has no chevron and opens nothing.

6. Read the Address row of the Visit card.
   Expected: the address and, at its end, a navigation glyph. Tapping the row opens the address in the
   device's map application (or the browser if no map application handles `geo:`).

7. Open a Job with no address.
   Expected: the row says "No address yet", has no navigation glyph, and does nothing when tapped.

8. Switch the app language to French.
   Expected: the chip's label, the menu entries and "Ouvrir dans la carte" are French.

9. Switch the device to dark mode.
   Expected: the chip's colour, the chevron, the menu and the navigation glyph follow the dark scheme.
```

Expected limitations
- Job cancellation is still unavailable: the API refuses it while `BR-064`'s reason catalogue is open, so
  no client offers it.
- The actions remain online-only, unchanged from tracker 018.

## Open questions recorded by this slice

| # | Question | Rule | How it is handled now | Blocks |
| - | -------- | ---- | --------------------- | ------ |
| 1 | The Job cancellation reason catalogue, and whether cancelling is a separate confirming action | `BR-064` | Cancellation is not offered by the API and is therefore absent from the chip's menu; nothing destructive is folded into the status control | Cancelling a Job from any client |
| 2 | Whether a status chip may be a control on other screens | `BR-006`, `BR-041` | Only the Job Details screen draws an interactive chip; the customer screens keep the static chip, because they present a status and offer no action on it | Reusing the interactive chip elsewhere |
| 3 | What an interactive chip should announce for a screen reader | `BR-028` | The chip's label is the status and its click action is named by the existing "Change status" string, so the announcement is "In progress, button, double tap to change status" | Confirming accessibility copy with the product owner |

## Files changed

```text
android/app/src/main/java/com/servora/android/ui/components/ServoraStatusPill.kt     chip may be a control
android/app/src/main/java/com/servora/android/ui/jobs/JobDetailsActions.kt            status action is the chip
android/app/src/main/java/com/servora/android/ui/jobs/JobDetailsScreen.kt            identity + address row
android/app/src/main/res/drawable/ic_navigation.xml                                   new glyph
android/app/src/main/res/values/strings.xml                                           job_details_open_in_maps
android/app/src/main/res/values-fr/strings.xml                                        job_details_open_in_maps
android/app/src/androidTest/java/com/servora/android/ui/jobs/JobDetailsScreenTest.kt  Compose coverage
docs/design/android-design-system.md                                                  status control row, address glyph
docs/tracker/020-android-job-details-status-control.md                                this slice
```

