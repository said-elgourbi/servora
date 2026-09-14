# Tracker 021 — Job Details status polish, and naming each status for what it belongs to (Android)

**Status: COMPLETE for the Android implementation; physical-device QA is the product owner's**

**Supersedes in part `docs/tracker/020-android-job-details-status-control.md`:** the status control is
still the Job's status chip, but it now carries a leading status dot and a labelled **Job status**
heading, and its menu is a compact list of the permitted transitions rather than a second row of chips.
Tracker 020's "the menu item is the status chip alone" (decision 8) and the design system's "one item
per transition drawn as the shared status chip alone" no longer describe the screen.

Date: 2026-09-14
Predecessor: `docs/tracker/020-android-job-details-status-control.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-028`, `BR-041`, `BR-042`, `BR-058`, `BR-059`, `BR-064`,
`BR-066`, `BR-074`
Domain model: `docs/domain/job-visit-domain-model.md` §5 – §7
API contract: `docs/api/job-details.md`, `docs/api/job-actions.md` — **unchanged by this slice**
Design reference: `Figma/src/screens/JobDetails.tsx` (Manager view), `servora-job-details-spec.md`
Design system: `docs/design/android-design-system.md`

## Scope

The Manager Job Details screen's status presentation, as a follow-up refinement of tracker 020. The
slice is presentation plus its tests. It adds no API, no table, no capability and no business rule, it
changes no status that may be offered and no transition, and it touches no other screen.

| Item                                                                             | Status      |
| -------------------------------------------------------------------------------- | ----------- |
| The header's status is labelled **Job status** (`BR-028`, `BR-059`)               | Implemented |
| The Job's number, title and description are one group, with no status above them  | Implemented |
| The labelled status control sits in the job information/header area               | Implemented |
| The status control leads with a dot in the status's own colour (`BR-041`)          | Implemented |
| The control keeps its 16 dp chevron, so it still says it opens something           | Implemented |
| The menu is a compact column anchored at the control, not a row of chips           | Implemented |
| The menu states the status the Job is in now, marked and not acting (`BR-058`)     | Implemented |
| Only the transitions the backend reported are offered (`BR-058`, `BR-041`)         | Unchanged   |
| Job cancellation is not folded into the control (`BR-064`)                          | Unchanged   |
| The chip is interactive only where the capability allows (`BR-006`, `BR-007`)      | Unchanged   |
| The Visit card's date row is labelled **Visit date**, not "Scheduled"              | Implemented |
| The Visit's status badge stays on the right of that row (`BR-074`)                 | Unchanged   |
| Visit status behaviour and every Visit business rule                               | Unchanged   |
| EN/FR copy for the new and renamed strings                                         | Implemented |
| API, schema, capabilities and offline behaviour                                    | Unchanged   |


## Decisions taken here

1. **Each status is named for what it belongs to.** The header's chip and the Visit card's badge both
   read as states of the screen, and a Job and its Visit are two state machines (`BR-059`) that neither
   replace nor contradict each other. The header's status is now labelled **Job status** and the Visit's
   status stays inside the Visit card, so the two are read as independent rather than as competing
   answers to one question (`BR-028`).
2. **The Job's identity is one group, and the status is part of the job information rather than a banner
   above it.** The chip used to sit alone above the Job number, which made the Job's own identity start
   with a state and separated the number from the title and description. The number, title and
   description now lead, and the labelled status control follows them in the same header area, where the
   Job's data is read.
3. **The menu belongs to the control, and it is compact.** The menu opened as a wide surface of status
   chips, which read as a second presentation of the status vocabulary rather than as one control's
   options. It is now one fixed 216 dp column anchored at the chip: the status the Job is in, a divider,
   then the permitted transitions as plain labels with a dot in each status's own accent colour.
4. **The status the Job is already in is stated, never offered.** `BR-058` is a transition table, so the
   current status is not a transition and offering it would be offering a move the API does not permit.
   It is drawn as a marked, non-acting row at the head of the menu — its own dot, its own localized
   label and a check in its own accent colour — so the user chooses from a stated position without the
   client inventing a selectable enum.
5. **What may be offered is still only what the backend reported.** The menu is built from the Job's
   `allowedStatusTransitions` exactly as in tracker 020: no copy of the lifecycle exists in the client,
   no status is added to the list, and a Job with no permitted transition still gets no interactive
   control. `BR-064`'s cancellation is absent because the API refuses it while the reason catalogue is
   open (`docs/api/job-actions.md` §3).
6. **The status dot is one shared definition, not a second colour language.** `StatusDot` is drawn in
   the colour it is handed, and the control and its menu hand it `jobStatusAccent`, which reads the same
   `jobStatusColors` mapping the chip wears. One mapping, one label source, one border rule (`BR-041`).
7. **The read-only state kept the same dot.** A caller who may see the Job but not move it sees the same
   dot-led chip without a chevron and without acting, so the status looks the same whether or not it can
   be changed and only the affordance differs (`BR-006`, `BR-007`).
8. **The Visit's date row is labelled for the date.** "Scheduled" above a date read as the Visit's
   status, and the card's badge beside it then read as a second, contradicting status. The row is now
   **Visit date** (`BR-028`), the badge stays where it is, and nothing about the Visit's status, its
   lifecycle or its business rules changed (`BR-074`).

## What this slice did not change

- **The API, its contracts, the capabilities and the offline behaviour** are untouched: the same read
  answers the status and its permitted transitions, the same `JOB_UPDATE` capability gates the action,
  and the actions remain online-only.
- **Every Job status transition rule** is untouched: the control offers what `allowedStatusTransitions`
  holds and nothing more (`BR-058`).
- **Every Visit rule** is untouched, including the Visit's status vocabulary, its lifecycle and the
  badge that presents it (`BR-074`).
- **Job cancellation** still does not exist as an action on any client (`BR-064`).
- **The static status chip on other screens** did not change: `JobStatusPill` gained an optional leading
  slot that the customer screens do not use, so their chips render exactly as before.

## Verification

```text
Android
- Kotlin compile (`./gradlew compileDebugKotlin`): PASS
- Device-test sources (`./gradlew compileDebugAndroidTestKotlin`, `assembleDebugAndroidTest`): PASS (compiled)
- JVM unit tests (`make android-test`): PASS — 275 tests, 0 failures
- Lint (`make android-lint`): PASS
- Debug build (`make android-build`): PASS
- Physical device: AWAITING PRODUCT OWNER

API
- No API, schema or capability change in this slice; nothing was re-run for it.
```

The Compose test of this screen (`JobDetailsScreenTest`) is a device test, so the agent compiled its
sources and left it to the product owner's device (`qa.md` §7.3). This slice added coverage for:

- the Job's status being labelled as the Job's, with the control present;
- the menu opening on the status the Job is in now, offering the permitted transition and no other;
- the Visit card labelling its date and stating the Visit's status once, on its own badge, while the
  Job's status is stated separately.


## Manual QA (product owner, physical device)

```text
Android QA — Job Details status polish

Sign in as the Manager development account (`make seed`, API running).

1. Open a Job from Home.
   Expected: the header reads "Job #1042" first, then the title and the description. Under them, a small
   "Job status" heading and the status chip: a dot, the status's label and a chevron (for example
   "● Pending review ⌄"). No status chip sits above the Job number.

2. Read the Visit card.
   Expected: the row above the date is labelled "Visit date" — not "Scheduled" — and the Visit's own
   status badge is still at the right-hand end of that row. The card reads as one date and one status,
   and the Job's status is clearly a different thing from the Visit's.

3. Tap the Job status chip.
   Expected: a compact menu opens at the chip. Its first row states the status the Job is in now with a
   dot and a check, and does nothing when tapped. Below a divider, each permitted transition is a plain
   label with a colour dot — no chips. No "Canceled" entry and no status the Job cannot move to.

4. Choose an offered transition.
   Expected: the menu closes, the chip takes the new status's dot, colour and label, and "Job status
   updated successfully" appears briefly.

5. Open a Job with no permitted transition (the chip has no chevron) and tap the chip.
   Expected: nothing opens; the status is still presented, with its dot.

6. Sign in as a user who may view Jobs but not update them.
   Expected: the Job's status is still shown with its dot and label, and the chip has no chevron and
   opens nothing.

7. Switch the app language to French.
   Expected: "Statut de l'intervention", "Date de la visite", "Statut actuel" and the status labels are
   French.

8. Switch the device to dark mode.
   Expected: the chip's colours, the dot, the chevron, the menu and its check glyph follow the dark
   scheme.
```

Expected limitations
- Job cancellation is still unavailable: the API refuses it while `BR-064`'s reason catalogue is open, so
  no client offers it.
- The actions remain online-only, unchanged from trackers 018 – 020.

## Open questions recorded by this slice

| # | Question | Rule | How it is handled now | Blocks |
| - | -------- | ---- | --------------------- | ------ |
| 1 | Whether the status menu should park the current status at its head on every screen that offers a status change | `BR-041`, `BR-058` | Only the Job Details status control does it; the customer screens present a status and offer no change, so they have no menu | Reusing the control elsewhere |
| 2 | What the status dot should announce to a screen reader on its own | `BR-028` | The dot is decorative and carries no content description: the chip's own text states the status, and the "Current status" copy only marks the menu's current-status row | Confirming accessibility copy with the product owner |

## Files changed

```text
android/app/src/main/java/com/servora/android/ui/components/ServoraStatusPill.kt    leading slot, StatusDot, accent
android/app/src/main/java/com/servora/android/ui/jobs/JobDetailsActions.kt           compact status menu, current-status row
android/app/src/main/java/com/servora/android/ui/jobs/JobDetailsScreen.kt            labelled status, identity group, Visit date label
android/app/src/main/res/values/strings.xml                                          Job status label, Visit date label, current-status copy
android/app/src/main/res/values-fr/strings.xml                                       the same in French
android/app/src/androidTest/java/com/servora/android/ui/jobs/JobDetailsScreenTest.kt Compose coverage
docs/design/android-design-system.md                                                 identity group, Job status label, status control, Visit card row
docs/tracker/021-android-job-details-status-polish.md                                this slice
```
