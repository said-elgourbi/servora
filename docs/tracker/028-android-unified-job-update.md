# Tracker 028 — One Add update action for Job Activity

**Status: IMPLEMENTED for the slice; physical-device QA is the product owner's**

Date: 2026-09-15
Predecessors: `docs/tracker/017-android-job-details.md` (the screen), `docs/tracker/022-android-job-activity-timeline.md`
(the Activity), `docs/tracker/024-android-job-activity-refresh.md` (when the timeline is read again),
`docs/tracker/027-android-job-photo-updates.md` (the photo flow this hands over to)
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-012`, `BR-014`, `BR-015`, `BR-027`, `BR-028`, `BR-031`,
`BR-041`, `BR-042`, `BR-051`, `BR-067`, `BR-080`
Standard followed: `docs/architecture/offline-first-architecture.md` §9, §13
Design reference: `Figma/src/screens/JobDetails.tsx` (`AddUpdateSheet`)
Design system: `docs/design/android-design-system.md`

## What this slice is

Job Details had **two** entry points for adding to the Job's Activity: a floating **Add update** action
that opened a text-only composer, and a separate **Add photo** action drawn beside "Job Activity". A
technician therefore chose a *place* before choosing *what* they were recording, and the two actions
looked equally relevant to the whole Activity.

This slice replaces both with **one** floating action that opens one sheet stating what kind of update it
is. It adds no endpoint, no table, no capability and no business rule; it removes an entry point, adds a
kind to the one that remains, and fixes a refresh gap the photo slice left.

> **The sheet's hierarchy was restructured by
> `docs/tracker/033-android-add-update-hierarchy.md` (2026-09-16).** The one action, the one sheet and the
> kinds are as this slice left them; what changed is inside the sheet — the kinds are peers in one
> selector, and the photo sources are the photo kind's own subordinate rows rather than a second row of
> kind-sized targets. This document stays the record of why the sheet is one action.

| Item                                                                        | Status      |
| --------------------------------------------------------------------------- | ----------- |
| One floating **Add update** action, for a Job with or without a Visit         | Implemented |
| `Add update` sheet with the update kind: **Write note** and **Add photo**     | Implemented |
| The note is the kind in effect, so its field is ready to type in             | Implemented |
| The photo kind hands over to the existing capture flow (camera → review → tray) | Implemented |
| The separate **Add photo** action beside Job Activity                        | Removed     |
| An accepted photo upload re-reads the Activity, so it appears without re-opening the Job | Fixed |
| Audio                                                                       | Not implemented — still out of scope (`027`) |
| EN/FR copy for the sheet's kind labels                                       | Implemented |
| Android ViewModel and Compose tests                                          | Implemented |
| API, schema, capabilities and the offline engine                              | Unchanged   |

## Decisions taken here

1. **One action, and it states the kind.** The floating action is the only way in, and the sheet names
   the kinds in it. Two entry points for the same write made the technician read the screen before
   recording anything; one action with a stated kind is the smallest change that removes that choice
   (`BR-012`).

2. **The note is the kind in effect, not a first step.** A chooser that requires a tap before the note
   field appears would make the everyday case — writing a note — slower than it was. The sheet therefore
   opens with the note field ready and "Write note" drawn as the kind in effect; tapping it puts the
   cursor in the field. Text stays the default and the most prominent option, as the requirement asked.

3. **The photo kind hands over to the flow that exists.** Capturing evidence is a flow of its own — the
   camera, a review panel and a tray that survives process death (`027`) — so the sheet closes and starts
   it rather than growing a second photo UI. The capture, review, tray and save behaviour is unchanged,
   including its offline path (`BR-014`, `BR-015`, `BR-031`).

4. **A Job with no represented Visit is offered a photo, not nothing.** A note belongs to a Visit
   (`POST /jobs/:id/visits/:visitId/notes`), but a photo is Job-level evidence that needs no Visit
   (`BR-015`, `BR-051`). The action is therefore offered for such a Job and the sheet offers the kind the
   API can accept — presenting a note for it would be presenting an action that cannot be performed
   (`BR-042`). This keeps the capability the removed "Add photo" action had; the floating action alone
   used to require a represented Visit.

5. **Audio is not drawn.** Voice notes are not modelled (`BR-027`, `027`), so the sheet presents no audio
   action at all rather than a control that cannot work (`BR-042`). The kind row is where it will appear
   once the product decision and the API exist.
   **Extended by `docs/tracker/033-android-add-update-hierarchy.md` (2026-09-16):** audio is a
   first-class kind of the sheet — with its glyph, its label and the composable its recorder lands in —
   and it is still not offered, because `docs/decisions/015-evidence-capabilities.md` D2 reserves its
   capability and deliberately does not create it. The **kind selector** replaced the kind row this
   entry names.

6. **An accepted upload re-reads the Activity.** The upload is written by the outbox rather than by an
   action on this screen, so nothing re-read the timeline when the API accepted the evidence: the photo
   appeared in the gallery and the timeline only after the Job was re-opened. A queued photo leaves the
   tray for exactly one reason — the upload handler removed it because the backend confirmed it holds the
   bytes (`JobPhotoUploadHandler`, §9) — so that event is now what reads the Activity again (`BR-001`,
   `BR-080`). A photo the technician removed locally was never submitted, records nothing, and triggers no
   read (`BR-014`).

## Files changed

```text
android/.../ui/jobs/JobUpdateSheet.kt          the sheet, its kinds and its test tags (new)
android/.../ui/jobs/JobDetailsScreen.kt         one floating action; the sheet replaces the text-only dialog
android/.../ui/jobs/JobActivitySection.kt       the Add photo action beside the Activity is removed
android/.../ui/jobs/JobPhotoComponents.kt       the removed action's test tag is deleted
android/.../ui/jobs/JobDetailsViewModel.kt      an accepted upload re-reads the Activity
android/app/src/main/res/values/strings.xml     action and kind labels (EN)
android/app/src/main/res/values-fr/strings.xml  action and kind labels (FR)
android/app/src/test/.../JobDetailsViewModelTest.kt        the refresh, and no refresh without a submission
android/app/src/androidTest/.../JobDetailsScreenTest.kt    the one action, the sheet, the hand-over, the no-Visit Job
docs/tracker/027-android-job-photo-updates.md   the new entry point in its runbook
docs/design/android-design-system.md            the Job Activity surface it describes
```

## Verification (2026-09-15)

```text
Android
- Unit tests (`./gradlew testDebugUnitTest`): PASS — `JobDetailsViewModelTest` 21/21, whole suite green
- Device-test sources compile (`./gradlew assembleDebugAndroidTest`): PASS
- Lint (`./gradlew lintDebug`): PASS
- Debug build (`./gradlew assembleDebug`): PASS — `app/build/outputs/apk/debug/app-debug.apk`
- Instrumented Compose tests: NOT RUN — device QA is the product owner's (`qa.md` §7.3)
```

## Open questions preserved (not implemented)

1. **The Jobs/evidence capability set** (`BR-006`, `BR-009`) — unchanged by this slice. The write still
   needs `JOB_UPDATE`, which the default Technician role does not hold (`027`).
   **Decided and implemented in tracker 029 Phase 1 (2026-09-15)**: the API's photo write requires
   `evidence.photo.add` and its read `evidence.view`, both held by the default Technician role
   (`docs/decisions/015-evidence-capabilities.md`). The **client's** gate is unchanged and still reads
   `JOB_UPDATE` (`android/.../ui/customers/CustomerPermissionsUiState.kt`), so a default Technician sees
   no update action yet; that half is tracker 029 Phase 3 (`BR-011`).
2. **The action while the tray holds unsaved photos** (`BR-012`). The tray takes the bottom of the screen
   while it holds anything, so the floating action is not drawn then — as in `027`, because the action
   would sit on top of the tray. A technician who saved photos while offline therefore cannot add a note
   until the uploads are accepted. Whether the action should move above the tray, or the tray should
   collapse once its photos are queued, is a presentation decision for the design owner, so nothing was
   changed here.
3. **A gallery source for a photo.** This slice keeps the camera-only capture flow (`027`): "Add photo"
   means "open the camera", and a picker that read another application's storage would be a new
   capability with its own decision (`§9`).
   **Closed in tracker 029 Phase 3 (2026-09-15):** D3 decided a **multi-select system photo picker**
   beside the camera, and Phase 3 implemented it — "Add photo" now states the photo kind and offers both
   sources, each item becoming its own draft. See `docs/tracker/029-photo-evidence-phases.md`.
4. **Editing or deleting a recorded note.** No product rule defines it, so notes remain append-only
   (`BR-067`).

## Manual QA runbook (product owner)

The runbook for this screen's update flow is
`docs/tracker/027-android-job-photo-updates.md`, rewritten for the single action. The two steps that are
new here:

```text
1. Open a Job and tap "Add update".
   Expect: one sheet titled "Add update", with the note field ready to type in and the two kinds stated.

2. Open a Job that has no Visit and tap "Add update".
   Expect: the sheet offers "Add photo" only, and no note field is drawn.
```
