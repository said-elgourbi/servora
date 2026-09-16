# Tracker 031 — Android photo viewer: the media-viewer chrome (Phase 4f)

A UI/UX refinement of the full-size Job photo viewer. **No API, database, business-rule or domain
change**: what a photo is, where its bytes come from, the order the viewer pages in, its zoom and the
`evidence.view` capability that draws the evidence actions are all exactly as Phase 4e left them.

- Status: **COMPLETE**
- Date: 2026-09-16
- Requested by: product ownership, as the fix for the viewer's layout and for a save that reported
  nothing the technician could see
- Business rules: `BR-012` (field-first, minimal interaction), `BR-015`, `BR-027` (evidence), `BR-028`
  (localization), `BR-042` (do not invent; a failed or refused action says so), `BR-006`/`BR-007`/
  `BR-011` (capability-drawn actions)
- Design: `docs/design/android-design-system.md` — the **Photo viewer** row of the Job Details table
- Rules applied: `Project.md` §11, §30; `dev.md` §1, §9, §14; `qa.md` §6.1, §6.2, §7.2, §7.3, §15, §16
- ADR: **none new.** The architectural decisions are unchanged:
  `docs/decisions/016-android-image-stack-and-viewer-zoom.md` (the viewer, its stack and its gestures)
  and `docs/decisions/017-viewer-paging-save-and-share.md` (paging, save, share) still govern. What
  changed is the chrome and one presentation fact, both recorded here and in the design system;
  ADR-017 carries an amendment note pointing at this entry.

## Problem

Two things were wrong with the viewer, and the second is a defect rather than a preference.

**1. It was a form screen rather than a media viewer.** The photo shared the screen with a top row that
carried the phase badge and the close target, a note stated under the photo in the theme's own body
style, and **two full-width filled buttons** at the bottom — **Save to device** and **Share** — drawn on
the theme's `surface`. On a phone that is a lot of chrome around a photo: the buttons read as the
screen's primary action, the photo was the smaller half of the screen, and the layout changed with the
application's light/dark appearance, so the same photo looked like a different photo.

**2. Saving a photo reported nothing the technician could see.** `JobDetailsScreen` raises the report of
what an action did on its own `SnackbarHost`. The viewer, however, is a full-screen `Dialog` — **a window
of its own, drawn over the activity's window** — so the report was shown on the screen *behind* the
viewer, which is opaque. Tapping **Save to device** therefore appeared to do nothing at all: the photo
was written, the file was in the gallery, the confirmation was rendered, and none of it was visible. The
same was true of **Share** and of every evidence failure (`EXPORT_UNREADABLE`, `EXPORT_FAILED`,
`SHARE_UNAVAILABLE`, `SAVE_PERMISSION_DENIED`). There was also no in-flight signal at all: reading a
photo's bytes can be a download from the API, and the control did not change while it ran.

## Change

| Item | Where | What |
| ---- | ----- | ---- |
| Viewer ground and ink | `ui/jobs/JobPhotoViewer.kt` | The viewer is drawn on its **own black ground with white ink** (`JobPhotoViewerBackground`, `JobPhotoViewerContent`, `JobPhotoViewerMutedContent`) instead of the theme's `surface`/`onSurface`. It is a dedicated media surface — the same in both app appearances — so a photo is read as the photo (`docs/design/android-design-system.md`) |
| Photo-first layout | `JobPhotoViewer` | The photo fills everything between the system bars and the note, and the chrome is drawn **over** it, so the chrome takes no height of its own. `systemBarsPadding()` is unchanged, so the system bars are still respected |
| Top bar | `JobPhotoViewerTopBar` | One minimal overlay on a flat `black@62%` scrim (`JobPhotoViewerBarScrim`), so a control's contrast comes from the viewer rather than from whichever photo is under it: the close action on the left, the position centred in the screen, the evidence actions on the right. Every control is a 48 dp target (`JobPhotoViewerControlSize`) |
| Photo work tag | `JobPhotoPhaseBadge` (reused) | The phase stays the badge the tiles already draw — **Before work / During work / After work** (`BR-028`) — now placed over the photo's own bottom-left corner rather than in the top bar. Its colours are unchanged, so the badge, the gallery tile and the tray tile cannot read differently |
| Notes | `JobPhotoViewerNote` | A **Notes** section under the photo: a small `labelMedium` label and the technician's note in white `bodyMedium`. A note longer than three lines is read three lines at a time with an explicit **More**, which expands it, and **Less**, which puts it back. Whether it is that long is answered by the layout it is actually drawn in (`hasVisualOverflow`) rather than by a character count a different device, font scale or language would get wrong. A note that fits draws **no** action and reserves no room |
| Position format | `values/strings.xml`, `values-fr/strings.xml` | `%1$d / %2$d` in both languages, as the design system already stated (`1 / 4`), replacing `1 of 4` / `1 sur 4`. Still drawn only when the Job has more than one photo |
| Evidence actions | `JobPhotoViewerControl` | **Save to device** (`ic_download`) and **Share** (`ic_share`) are 48 dp icons in the top bar, each carrying the action's own localized label as its content description (`BR-028`). The full-width filled buttons are gone (`JobPhotoViewerExportActions`, `RowScope.JobPhotoViewerExportAction` and `JobPhotoViewerActionHeight` were removed) |
| In-flight feedback | `JobDetailsUiState.photoExport`, `JobDetailsViewModel`, `JobPhotoViewerControl` | The ViewModel reports the evidence action it is running (`JobPhotoExportAction.SAVE`/`SHARE`) the moment it is asked for, and clears it on every outcome. The viewer draws its own progress **in place of that action's glyph**, so the action says it is running and cannot be asked for twice (`exportInFlight` already refused the second request; the viewer now shows why) |
| Report inside the viewer | `JobDetailsActions.kt`, `JobDetailsScreen.kt`, `JobPhotoViewer.kt` | One `SnackbarHostState` is hoisted in the screen and rendered by `JobActionSnackbarHost` — **by the viewer while it is open, and by the screen otherwise** — so a save, a share or a refusal is reported in whichever window is on screen. The appearance travels with the message (`JobActionSnackbarVisuals.isError`), so one report is never drawn two different ways in two windows |
| New strings | `values/strings.xml`, `values-fr/strings.xml` | `job_photo_viewer_notes_label` (Notes), `job_photo_viewer_notes_more` (More/**Plus**), `job_photo_viewer_notes_less` (Less/**Moins**). Every string is in both languages |

### Deliberately not changed

- The **pager and its order** (the evidence the backend holds first, then this device's photos), the
  **paging-only-at-fit** rule, pinch-zoom, pan and double-tap zoom, the image stack (Telephoto over Coil
  3 behind `JobPhotoImages`) and every existing test tag (`JobPhotoViewerImageTag`,
  `JobPhotoViewerPagerTag`, `JobPhotoViewerLoadingTag`, `JobPhotoViewerUnavailableTag`,
  `JobPhotoViewerCloseTag`, `jobPhotoViewerStateTag`, …).
- The **store of the photo**: nothing here records, edits, deletes, re-encodes or re-reads a photo, and
  the report is presentation only (`BR-001`, `BR-027`). A pending photo's upload state is still drawn
  under the photo (`§7`), now beneath the note.
- **Tapping the photo to hide the chrome** was not added: a single tap is not free in this viewer — the
  library's double-tap-to-zoom owns the same gesture area — and a chrome the technician can lose is a
  worse trade than one that is simply small (`BR-012`).

## Verification (2026-09-16)

Every check below was run in this working tree, after the change, from `android/`:

```text
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

| Check | Result |
| ----- | ------ |
| Android JVM unit tests (`testDebugUnitTest`) | **PASS — 474 tests, 0 failures, 0 errors** (47 suites) |
| Android lint (`lintDebug`) | **PASS** |
| Debug build (`assembleDebug`) | **PASS** |
| Device-test sources compile (`assembleDebugAndroidTest`) | **PASS** (`qa.md` §7.3) |
| Device tests themselves | **NOT RUN — device QA is the product owner's.** No `adb` and no device/emulator command was run (`qa.md` §7.3) |

The tests added or changed for this slice:

- `JobPhotoCaptureViewModelTest` — *reports the evidence action that is running until it lands* (the state is
  set the moment a save is asked for, and cleared with the outcome) and *does not start a second evidence
  action while one is running* (a share asked for mid-save is not begun).
- `JobDetailsScreenTest` — *keepsTheNotesUnderThePhotoAndExpandsALongOneOnDemand* (the **Notes** label, the
  note, and **More** → **Less**), *drawsNoNoteActionForANoteThatAlreadyFits* (no room reserved for an action a
  short note does not need), *carriesThePhotoChromeInTheTopBarInsteadOfBottomButtons* (close, position,
  Save/Share, the phase badge, and each action's accessible name), *doesNotStartAnEvidenceActionThatIsAlreadyRunning*
  (the in-flight action is disabled and so is the other, because one evidence action at a time is the
  ViewModel's behaviour and a tap that silently did nothing would be worse) and
  *reportsASaveInsideTheViewerWhereTheTechnicianIsLooking* (the report node is a **descendant of the viewer**,
  which is the defect this slice fixes), plus the updated position assertions (`1 / 2`, `2 / 2`).

The viewer's own resolution rules (`JobPhotoViewerTest`), the capture/save/share outcomes and the offline
evidence tests are unchanged and still pass: what this slice touched is presentation, and the rules it did
not touch must not have moved.

**Hygiene** (`dev.md` §18, `qa.md` §7.4/§15): `make android-stop` was run after the last Gradle command and
`make tidy` reports nothing this task left running; the two temporary build logs were deleted; `git status`
lists only the files this slice changed.



**What changed.** The full-size viewer is now a media surface rather than a form screen, and the report of an
evidence action is drawn where the technician is looking.

- the photo is drawn on the viewer's own black ground with white chrome, and fills the screen under the top bar;
- the top bar is one overlay: **X** on the left, the position (`2 / 5`) centred, and **Save**/**Share** as
  48 dp icons on the right;
- the photo's phase (**Before work** / **During work** / **After work**) is a badge over the photo's
  bottom-left corner;
- under the photo, a **Notes** section reads the note; if it does not fit in three lines there is a **More**
  action, which expands it, and **Less**, which puts it back;
- a save or a share shows the viewer's own progress in the place of the icon it was asked from, and then its
  confirmation **over the photo**.

**Before you start.** Sign in as a Technician (or a Manager) on a Job with at least two photos, one of which
has a long note (several lines). The API/none else needs to change; the app works against the same stack as
before.

1. Open the Job, then tap a photo in the Activity gallery.
   **Expected:** the photo fills the screen on a black ground; **X**, a position (`1 / 2`) and two icons are
   in the top bar; the phase badge sits at the photo's bottom-left; the note is under the photo.
2. Tap the top-left **X**, then reopen and use the system back gesture.
   **Expected:** both close the viewer and Job Details is exactly as it was (`BR-067`).
3. Swipe left and right; then pinch to zoom and drag; then double-tap.
   **Expected:** paging, zoom, pan and double-tap zoom are unchanged; a zoomed photo pans instead of paging;
   the top bar, badge and note stay put and legible over a bright and over a dark photo.
4. Open a photo with a long note.
   **Expected:** three lines are read with **More**; tapping it shows the whole note (**Less** collapses it);
   the photo stays on screen. A photo with a short note shows **no** expansion action.
5. Tap **Save**.
   **Expected:** the icon becomes the viewer's own progress for a moment, then *Photo saved on this device.*
   appears **over the photo** (not behind it), and the copy is in the device's gallery in the **Servora**
   album as `Servora-<photo-id>.jpg`.
6. Tap **Save** twice quickly.
   **Expected:** one save only; the second tap does nothing while the first is running.
7. Tap **Share**.
   **Expected:** the system share sheet opens titled *Share photo*; cancelling it changes nothing in Servora,
   and the app reports nothing it did not do.
8. Turn off connectivity (or airplane mode) and open an **accepted** photo, then tap **Save**.
   **Expected:** the action reports that the photo could not be read, over the photo, rather than claiming a
   save. A photo still held by this device (in the tray) saves normally with no connectivity.
9. Switch the app language to French and repeat 1–5.
   **Expected:** the position reads `1 / 2`, the note is labelled *Notes* with *Plus* / *Moins*, and the two
   icons are announced as *Enregistrer sur l'appareil* / *Partager*.
10. **Android 8 or 9 (API 26–28) only**: tap a **Save** for the first time.
    **Expected:** Android asks for the storage permission and the save completes when it is granted; declining
    it makes the app say it needs that permission rather than claiming a save.

**Known limitations, deliberately.**

- The chrome cannot be hidden by tapping the photo (see above).
- Nothing here is a new capability: what a photo is, whose it is and whether it may be read are the API's
  answers, unchanged (`BR-001`, `BR-007`).
- A saved or shared copy is outside Servora's control, exactly as `D13` records.

## Out of scope / next

- **No API, contract, database, permission or domain change.** `docs/api/job-photos.md`, the routes, the
  DTOs and the local evidence store are untouched.
- **Phase 6a (refused photos are explicitly discardable, `D6c` ✓) is still next** in
  `docs/tracker/029-photo-evidence-phases.md`; Phase 5 stays blocked on the deferred `D5`.
- Evidence retention, deletion and lifecycle (`D6a`/`D6b`/`D6d`/`D6e`), thumbnails/presigned reads
  (`ADR-013` open questions 3 and 4), a cross-Job or device-wide gallery (`D11`) and sharing more than the
  photo (`D13`) all remain **open**, and nothing here decided them.

