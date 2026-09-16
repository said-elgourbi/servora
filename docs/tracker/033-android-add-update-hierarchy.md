# Tracker 033 — Android Add update: one kind of update, then that kind's controls

A UI/UX restructuring of the **Add update** bottom sheet. **No API, database, business-rule, permission,
contract or domain change**: what a note is, what a photo is, where its bytes come from, which
capabilities gate them, and the flows each one hands over to are exactly as
`docs/tracker/028-android-unified-job-update.md` and `docs/tracker/029-photo-evidence-phases.md` left
them.

- Status: **IMPLEMENTED for the slice; physical-device QA is the product owner's**
- Date: 2026-09-16
- Requested by: product ownership — improve the sheet's action hierarchy and prepare it for the audio
  update feature that comes next
- Business rules: `BR-001` (the backend is the record), `BR-006`/`BR-007`/`BR-011` (permission-based,
  server-authoritative, UI-gated by capability), `BR-012` (field-first, minimal interaction),
  `BR-015`/`BR-027` (evidence), `BR-028` (localization), `BR-041` (one definition of a shared value),
  `BR-042` (do not invent; an action that cannot be performed is not offered), `BR-051` (a Job may have
  no Visit), `BR-067` (explicit actions, complete history), `BR-080` (Job Activity is the derived read
  model)
- Design reference: `Figma/src/screens/JobDetails.tsx` (`AddUpdateSheet` — its attachment buttons are a
  three-across grid of glyph-over-label targets, which is the shape this sheet's kind selector takes),
  `Figma/src/imports/pasted_text/servora-job-details-spec.md` §6
- Design system: `docs/design/android-design-system.md` — the **Job update action and sheet** row
- Rules applied: `ADR-007` D6 (`selectableGroup` + `Role.RadioButton` for a segmented choice),
  `docs/decisions/015-evidence-capabilities.md` D2 (the reserved audio capability), `Project.md` §11,
  §30; `dev.md` §1, §9, §14, §15, §18; `qa.md` §6.1, §6.2, §7.2, §7.3, §15, §16
- ADR: **none new.** No architecture changed: the offline standard, the image stack and the evidence
  capability set are untouched.

## Problem

The sheet stated its kind of update as one row of large targets, and then drew the **photo kind's two
sources in a second row of exactly the same size**. With the photo kind in effect the sheet therefore
presented four equally prominent actions:

```text
Write note      Add photo
Take photo      Choose photos
```

A technician reading that cannot tell which two of the four are the alternatives and which two are *how
the second one happens*, and the sheet appears to offer two ways to add a photo rather than one kind with
two sources. `BR-012` asks the sheet to make the primary decision obvious; the sources were competing
with the kinds for that role.

Two smaller defects sat in the same code:

1. The sheet had no structure to grow into. What the sheet offers is decided by two booleans
   (`canWriteNote`, `canAddPhoto`), the kind is a boolean (`photoInEffect`), and both kinds' controls are
   drawn by nested conditionals inside one composable — so the audio kind this request asks the sheet to
   account for would have been a third boolean in the same block.
2. Tapping **Write note** after **Add photo** called `FocusRequester.requestFocus()` in the same event
   handler that changed the kind. The note's field had just left the composition, so the requester had no
   attached target, and `requestFocus()` on an unattached requester throws. That path was one tap from
   the sheet's own default state.

## Change

| Item | Where | What |
| ---- | ----- | ---- |
| The kinds, as peers | **New** `ui/jobs/JobUpdateKind.kt` | `JobUpdateKind` (`NOTE`, `PHOTO`, `AUDIO`) states the three kinds as one decision, with `offeredUpdateKinds(canWriteNote, canAddPhoto, canAddAudio)` answering the kinds a session may actually add (everyday case first) and `updateKindLabelRes` / `updateKindIconRes` mapping a kind to its localized label and glyph. It holds no Compose, so which kinds can be reached is verified on the JVM (`qa.md` §6.1) |
| A kind selector | `ui/jobs/JobUpdateSheet.kt` (`JobUpdateKindSelector`) | One `labelSmall` group label (**Update type**) over a `selectableGroup` row of equal-width segments, one per offered kind: the glyph over the label, the selected one filled `primary`/`onPrimary`, the others `secondary`/`onSurfaceVariant` with a 1 dp `outlineVariant` hairline. The row is `height(IntrinsicSize.Min)`, so the segments share the tallest label's height and grow with a long translation or a larger text size instead of being squeezed into a fixed 56 dp row (`BR-028`, `ADR-007` D6) |
| The controls, per kind | `ui/jobs/JobUpdateSheet.kt` (`JobUpdateNoteContent`, `JobUpdatePhotoContent`, `JobUpdateAudioContent`) | Each kind owns its own controls and its own state below the selector; the sheet coordinates only which kind is in effect and the submit state they share, so another kind is an addition rather than an edit to the other two |
| The photo sources, subordinate | `JobUpdatePhotoContent`, `JobUpdatePhotoSourceRow` | The photo kind draws the `labelSmall` label **Add photos from** and, under it, its two sources as quiet rows — `≥ 56 dp`, the whole row the target, a 22 dp leading glyph in `primary`, the label in `bodyLarge`, an 18 dp `ic_chevron_right`, and **no fill, border or card** — so they read as that kind's own controls rather than as two more things to add (`BR-012`) |
| Copy | `values/strings.xml`, `values-fr/strings.xml` | **New:** `job_activity_update_type_label` (*Update type* / *Type de mise à jour*), `job_activity_update_audio` (*Add audio* / *Ajouter un audio*), `job_update_photo_sources_label` (*Add photos from* / *Ajouter des photos depuis*). **Changed:** `job_update_photo_library`, *Choose photos* → *Choose from device* / *Choisir sur l'appareil*, which is what the request asks that source to say |
| A glyph | **New** `res/drawable/ic_mic.xml` | The microphone the audio kind is drawn with, geometry from lucide `Mic` like every other glyph in the set. It is referenced by the kind's mapping and is not drawn until the kind is offered |
| The screen's gate | `ui/jobs/JobDetailsScreen.kt` | `canAddAudio = false`, with the reason stated at the call site: the API's capability for audio is reserved and not created (`ADR-015` D2), so no session may add one and no kind is offered for it (`BR-042`). The note's gate (`canUpdateJob && details.selectedVisit != null`) and the photo's (`canAddEvidencePhoto`) are unchanged |
| The focus request | `JobUpdateSheet` | Tapping a kind sets a request that a `LaunchedEffect` applies **after** the kind is drawn, so it lands on a control that exists — which is what defect 2 above needed. No behaviour changed: *Write note* still puts the cursor in the note |
| Each kind's draft | `JobUpdateSheet` | `rememberSaveableStateHolder` keeps each kind's state while the sheet is open, so a technician who checks the photo sources mid-sentence and comes back does not lose what they were typing |

## Naming: "update type" in the request, "update kind" in the client

The request calls these three things *update types*. The client already models this decision as the
sheet's update **kind** — `JobUpdateNoteKindTag` / `JobUpdatePhotoKindTag` since tracker 028, the same
word in the design system, and *"this sheet states what kind of update it is"* in the sheet's own
documentation — and `BR-041` keeps one definition per shared concept. The existing word was therefore
kept, so nothing that reads the sheet's structure had to be renamed for a synonym. The visible group
label is the request's own wording (**Update type**), because that is user-facing copy, not a code
concept.

## Presentation choices this slice made

Presentation, not product behaviour: no business rule, contract or stored value is decided by them
(`BR-042`), and each is one value in one place if product ownership wants it different.

| Choice | Value | Why |
| ------ | ----- | --- |
| The selector is three equal segments, not a scrollable chip row | one row, equal widths | Three peers must be visible at once — a third kind behind a scroll would not be an equal, and a technician would not know it exists (`BR-012`) |
| Glyph over label rather than label beside glyph | vertical | Three labels in one row do not fit side by side in either language; a vertical segment carries a two-line French label and a 48 dp+ target without shrinking the text (`BR-028`) |
| The segment's height follows its label | `height(IntrinsicSize.Min)` + `≥ 76 dp` | A fixed height clips a long translation or a large text size; the tallest label decides the row's height, so the three stay equal and never truncated |
| The group label | **Update type**, `labelSmall` bold on `onSurfaceVariant` | The same labelled-control pattern the customer forms and the filter sheet use, and it is what makes the segments read as one choice rather than as a toolbar |
| Unselected fill | `secondary` on `onSurfaceVariant` with a 1 dp `outlineVariant` hairline | The quiet neutral the customer form's segmented control already uses for its unselected option, so the selected kind is the only brand-filled thing in the sheet |
| The photo sources | rows, no container | Rows are read as a list belonging to the selected kind; cards, borders or shadows would make them look like a second set of choices — the exact defect this slice fixes |
| The photo-source leading glyph | `primary` tint | `docs/design/android-design-system.md` gives `primary` to icons that act (6.44:1 on `surface` in the light appearance), while the neutral ink would read as decoration |
| The source label | `bodyLarge` on `onSurface` | A row's label is content rather than a segment's label, so it takes the body scale — which is also what makes it unmistakably not a kind |
| The note's foot | unchanged (*Cancel* `TextButton`, *Save update* `Button`) | Text is the everyday update; the task was the hierarchy above it, not the composer's foot |
| A kind's draft survives a kind switch | `rememberSaveableStateHolder` | What the technician typed is their work; a look at the photo sources must not discard it (`BR-012`, and the spirit of `BR-014` without claiming its offline guarantee) |

## What this deliberately does not do

- **No audio recorder.** Audio is a first-class kind, and its controls are `JobUpdateAudioContent` —
  a composable that draws nothing today, because no rule accepts an audio recording: the API has no
  `evidence.audio.add` (`ADR-015` D2 deliberately does not create it), no stored evidence, no content
  type, no Activity kind and no playback. A recorder whose recording could not be submitted would be
  invented behaviour, and the kind is not offered at all in production (`BR-042`).
- **No permission code invented.** Neither the API catalogue nor the Android `Permission` enum gains an
  audio code: the decision that reserves it explicitly says it is not created until audio exists, and a
  client constant for a code the API does not define would be a second source of truth (`BR-041`).
- **No new capability, endpoint, table or migration.** The API is untouched; this is a client slice.
- **Nothing about the photo flow changed.** Both sources still close the sheet and hand over to the
  camera/picker → review panel → tray flow the photo slice owns (`BR-015`, `D3`), and both gates are the
  same capabilities as before.
- **Nothing else in Job Details was redesigned.** The identity, Visit card, technicians, status control,
  the tray, the review panel, the viewer and the gallery are untouched.
- **The sheet's title and the note's copy are unchanged**, except the two new labels and the one source
  label the request names (*Choose from device*).

## Tests

| Level | What is covered | Where |
| ----- | --------------- | ----- |
| Android unit (JVM) | Which kinds a session is offered, and in what order: the everyday case first, only the kinds the stated capabilities allow, audio as a peer of the other two when the capability is stated (the seam, proven reachable before the recorder exists), and no kind at all when nothing may be added | `android/app/src/test/java/com/servora/android/ui/jobs/JobUpdateKindsTest.kt` (new) |
| Android UI (Compose, device) | The sheet offers the two kinds this session may add and the selector that holds them; the note's field is the only control below the kinds while the note is in effect; choosing the photo kind draws its sources **outside** the kind selector, with no note field; coming back to the note restores its controls **and** the draft typed before; the audio kind is not offered through the screen; and a session that may add audio reaches the audio kind, whose controls are neither the note's nor the photo's | `android/app/src/androidTest/java/com/servora/android/ui/jobs/JobDetailsScreenTest.kt` (`keepsTheKindsAndThePhotoSourcesInTheirOwnRanks`, `drawsTheAudioKindsOwnControlsWhenASessionMayAddAudio`, and the extended `theAddUpdateActionOffersANoteAndAPhotoAndSavesTheNote`) |

The existing sheet tests were kept and their assertions tightened rather than replaced: the one action
still opens one sheet, the note is still the kind in effect wherever a note can be taken, both photo
sources still hand over to their flows, a session without the Job update capability still gets the photo
sources only, and a Job with no represented Visit still gets no note.

## Verification (2026-09-16)

```text
Android
- Unit tests (`make android-test`): PASS — the whole JVM suite green, including the new
  JobUpdateKindsTest (4 cases)
- Device-test sources compile (`./gradlew assembleDebugAndroidTest`): PASS
- Lint (`make android-lint`): PASS
- Debug build (`make android-build`): PASS — app/build/outputs/apk/debug/app-debug.apk
- Instrumented Compose tests: NOT RUN — device QA is the product owner's (`qa.md` §7.3)
```

No API file was changed, so no API command was run. **No `adb` and no device or emulator command was
run** (`qa.md` §7.3). **Physical-device QA: NOT RUN — device QA is the product owner's**; the runbook
above is the hand-off.

## Open questions preserved (not implemented)

1. **Audio evidence** (`BR-027`, tracker 029 D8) — **answered 2026-09-16** and now tracked by
   `docs/tracker/035-android-audio-evidence.md`: the storage, the content type and size vocabulary, the
   length limits, the Activity kind and playback are decided in `docs/decisions/018-audio-evidence.md`
   (A1–A10) and the API half is implemented (`docs/api/job-audio.md`). This slice's kind, glyph, label and
   composable are the seam that lands in that tracker's Phase 9b — which is what it was built for.
2. **Whether the audio kind will also have two sources.** **Answered 2026-09-16** (`ADR-018` A8): the
   audio kind offers **record only**. The design specifies record, stop, playback/review,
   delete/re-record and attach, and no picker; choosing an existing audio file is out of scope for v1, so
   no capability for reading a file from the device is introduced (`BR-042`).
3. **The update action while the tray holds unsaved photos** (`BR-012`) — still open as tracker 028 left
   it. The tray still takes the bottom of the screen and the action is still not drawn while it holds
   anything, so a technician who saved photos offline still cannot add a note until the uploads are
   accepted. Unchanged here.
4. **Editing or deleting a recorded note** — no product rule defines it, so notes remain append-only
   (`BR-067`). Unchanged here.

## Manual QA runbook (product owner)

Only the sheet changed: the note, camera, picker, review, tray, gallery and viewer flows are the same, so
these steps are about the sheet's hierarchy rather than about recording an update.

```text
1. Sign in as a Manager, open a Job that has a Visit, and tap "Add update".
   Expect: the sheet is titled "Add update" and states "Update type" once, with "Write note"
   (brand-filled) and "Add photo" (quiet, outlined) side by side, each with its glyph above its
   label. "Add audio" is absent — no capability accepts a recording yet.
   Expect: the note's field is below the selector and ready to type in, with Cancel and Save update.

2. Tap "Add photo".
   Expect: "Add photo" becomes brand-filled and "Write note" turns quiet; the note's field and its
   two actions are gone; "Add photos from" appears, with "Take photo" and "Choose from device" as
   two rows under it, each with a leading glyph and a chevron.
   Expect: those two rows are visibly quieter than the kind segments above them — no filled
   surface and no border — so they do not read as a third and fourth kind of update.

3. Tap "Write note" again, type a few words, tap "Add photo", then tap "Write note" again.
   Expect: no crash, and what you typed is still there.

4. Tap "Add photo", then "Take photo", capture a photo and save it.
   Expect: unchanged — the review panel opens, the photo joins the tray and uploads.

5. Tap "Add update", "Add photo", then "Choose from device" and pick a photo.
   Expect: unchanged (this source was labelled "Choose photos").

6. With the sheet open on the note kind, rotate the device.
   Expect: the sheet is still open with the note kind in effect.

7. Switch the app to French and repeat steps 1 and 2.
   Expect: "Type de mise à jour", "Écrire une note", "Ajouter une photo", "Ajouter des photos
   depuis", "Prendre une photo", "Choisir sur l'appareil" — the three segments still fit one row and
   no label is clipped or truncated.

8. Set the device's font size to its largest setting and repeat step 1.
   Expect: the three segments stay equal in height and every label is fully readable; the row grows
   rather than cutting the text off.

9. Sign in as a default Technician, open an assigned Job and tap "Add update".
   Expect: unchanged — the photo kind only, with its two sources and no note kind.

10. Open a Job that has no Visit as a Manager and tap "Add update".
    Expect: unchanged — the photo kind only, no note field.

11. With TalkBack on, open the sheet and swipe through the selector.
    Expect: the three kinds are announced as a set of radio buttons with their selected state, each
    named by its own label, and the two photo sources are announced as buttons that leave the sheet.
```

