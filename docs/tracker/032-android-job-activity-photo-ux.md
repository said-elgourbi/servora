# Tracker 032 — Android Job Activity photo UX: a collapsible gallery and photos in the timeline

A UI/UX refinement of the two photo surfaces inside **Job Details → Job Activity**. **No API, database,
business-rule, permission, contract or domain change**: what a photo is, where its bytes come from,
which photos the Job has and in what order, and the viewer that opens them are exactly as
`docs/tracker/029-photo-evidence-phases.md` and `docs/tracker/031-android-photo-viewer-ui.md` left them.

- Status: **COMPLETE**
- Date: 2026-09-16
- Requested by: product ownership, as a refinement of how the Job's photos are presented in Job Activity
- Business rules: `BR-001` (the backend is the record), `BR-012` (field-first, minimal interaction),
  `BR-015` and `BR-027` (evidence), `BR-028` (localization), `BR-042` (do not invent; a failed or refused
  thing says so), `BR-080` (Job Activity is a derived, unified read model), `BR-041` (one definition of a
  shared value)
- Design: `docs/design/android-design-system.md` — the Job Details table's Activity rows and the
  **Collapsible section headings (disclosure)** section
- Rules applied: `Project.md` §11, §30; `dev.md` §1, §9, §14, §15, §18; `qa.md` §6.1, §6.2, §7.2, §7.3,
  §15, §16
- ADR: **none new.** No architecture changed: the image stack (`docs/decisions/016-…`), the viewer's
  paging, save and share (`docs/decisions/017-…`) and the offline standard are untouched.

## Problem

The photos were presented as one horizontal strip above the timeline, and as one line of text inside it.

**1. The strip was always open and had no heading.** A Job with a dozen photos therefore pushed the
account of what happened — the thing the Activity exists for (`BR-080`) — a full strip's height down the
screen, with nothing saying how many photos there were or that the strip was the same collection the
timeline was describing.

**2. A photo's own timeline entry said only "Added a photo (During work)".** The photo itself was not in
it. The user read that a photo existed, in the account of what happened, and could not see it there; the
only way to see the evidence was to scroll back up to the strip and guess which tile the entry meant.

## Change

| Item | Where | What |
| ---- | ----- | ---- |
| Collapsible gallery | `ui/jobs/JobPhotoComponents.kt` (`JobPhotoGallerySection`, `JobPhotoGalleryHeading`) | The strip sits under a heading that states the collection — **`Photos · n`** from the shared `section_count_format` string — and the **whole heading is the control** that folds it away (`≥ 48 dp`, `shapes.medium` ripple, `ic_chevron_right` rotated 90° while the strip is shown). Its content description names the action (`Show photos` / `Hide photos`), never the state. The affordance is the one the manager home's **Needs attention** section and the customer detail's archived-Property disclosure already use (`docs/design/android-design-system.md`) |
| Compact folded preview | `JobPhotoGalleryHeading` | While folded, the heading previews up to three of the photos it counts, at 32 dp, `ContentScale.Crop`. They are decorative (`contentDescription = null`): the heading's own label and count say what they are, and the row is the target. Evidence stays visible without opening anything, and the folded section stays one row tall |
| Gallery order and collection | `JobPhotoGallery` (unchanged shape), `jobActivityPhotos`, `jobPhotoGalleryPreview` | One collection, in one order: the `JOB_PHOTO_ADDED` entries the API returned. The preview is that collection shortened, never a second list (`BR-001`, `BR-080`) |
| Photo in the timeline | `ui/jobs/JobActivitySection.kt` (`ActivityPhotoEvidence`) | A photo entry now draws the photo itself under its actor/time metadata: a 176 × 132 dp thumbnail (`ContentScale.Crop`, so it is a summary rather than a distortion), the **phase badge the gallery, the tray and the viewer already draw** over its bottom-left corner, and the technician's note under it. Tapping the photo opens the **same** viewer on the same page a gallery tile opens (`D4`, `D11`) |
| Title without the phase | `JobActivitySection.activityTitle` | A photo entry reads **"Added a photo"**. The phase is not repeated in the title, because the badge on the photo states it (`BR-012`, `BR-028`); `job_photo_activity_added` loses its `%1$s` |
| Note under the photo | `JobPhotoNote` (new, shared) | One implementation of a photo's note, used by the timeline entry and by the viewer: three lines, `More`/`Less` only when the note actually overflows the layout it is drawn in (`hasVisualOverflow`), and a bounded, scrollable area only where something else needs its room (the viewer's photo). A note that fits draws no action and reserves no room. The strings (`More`/`Less`) are the shared `job_photo_notes_more` / `job_photo_notes_less` |
| Timeline rail | `JobActivitySection` | The rail is narrower (28 dp marker and rail, 8 dp gap): 36 dp of indent instead of 44 dp, so the line organizes the entries rather than stealing width from the photo and the note (`BR-012`) |
| Viewer's note | `ui/jobs/JobPhotoViewer.kt` (`JobPhotoViewerNote`) | Now draws through the shared `JobPhotoNote`. Same tags, same three lines, same `More`/`Less`, same 160 dp bound — behaviour unchanged, one implementation |
| Localization | `values/strings.xml`, `values-fr/strings.xml` | `job_photo_gallery_label` (`Photos`), `job_photo_gallery_expand` (`Show photos` / `Afficher les photos`), `job_photo_gallery_collapse` (`Hide photos` / `Masquer les photos`), the shared `job_photo_notes_more` / `job_photo_notes_less`, and one `job_photo_activity_added` |

## Presentation choices this slice made

They are presentation, not product behaviour: no business rule, contract or stored value is decided by
them (`BR-042`), and each is one value in one place if product ownership wants it different.

| Choice | Value | Why |
| ------ | ----- | --- |
| The gallery starts **folded** | folded | The timeline now draws each photo in the entry that recorded it, so the gallery is for browsing the whole collection — a deliberate act rather than the default reading order. Folding it keeps the account of what happened at the top of the section, and the compact preview keeps the evidence visible while it is closed |
| Preview size | 3 photos at 32 dp | Enough to say evidence exists; small enough that the folded section is one row |
| Folded/expanded state | `rememberSaveable(state.jobId)` | Survives recomposition and a configuration change, resets for another Job, and is stored nowhere beyond the screen. No preference and no database (`BR-042`) |
| Timeline photo size | 176 × 132 dp | Recognizable at a glance and smaller than the viewer, so a Job with dozens of photo entries stays scannable (`BR-012`) |
| The phase badge | the existing `JobPhotoPhaseBadge` | The badge, the gallery tile, the tray tile and the viewer cannot read differently (`BR-028`) |
| The count | the photos the **backend** reported | Photos still on the device are the tray's, because the backend has not accepted them yet (`BR-001`). The gallery draws exactly what the Activity reported |

## What this deliberately does not do

- **One photo per entry.** The Activity contract carries one `photoId` per `JOB_PHOTO_ADDED` event, and a
  multi-photo update is several events — so no `+3` grid is drawn, because no such entry exists. If the
  contract ever gains an entry holding several photos, that entry needs its own compact pattern, and it
  is not invented here (`BR-042`).
- **No second collection, no second viewer, no second badge.** Both entry points resolve to the same
  sequence (`viewedJobPhotoSequence` / `jobPhotoViewerInitialPage`), so the position in the viewer
  (`4 / 10`) is the Job's own order whichever surface was tapped (`D11`).
- **No new image request.** The timeline photo, the folded preview, the tile and the viewer all ask
  `JobPhotoImages`; the same photo is the same request and the same cache key, so nothing is downloaded
  or decoded twice (`D4b`).
- **Nothing else in Job Details was redesigned.** The identity, Visit card, technicians, status control,
  the tray, the review panel and the viewer's own chrome are untouched.

## Tests

| Level | What is covered | Where |
| ----- | --------------- | ----- |
| JVM unit | The photo collection (only the photos the API reported, in its order; a photo this build cannot place is left out), the folded preview (the collection shortened, and short collections unchanged), and the phase rule (a code this build cannot read is not guessed) | `android/app/src/test/java/com/servora/android/ui/jobs/JobActivityPhotosTest.kt` (new) |
| Compose (instrumented) | The heading states the count and the gallery starts folded with a preview; folding and unfolding from the heading (including the chevron's action-labelled description); a photo entry drawing its own photo, badge and note with the actor/time metadata; a long note behind `More`/`Less` with the photo still on screen; a photo with no note reserving no caption area; an entry's photo opening the shared viewer on the page that photo is on. The eight existing cases that opened the viewer from a gallery tile now unfold the gallery first | `android/app/src/androidTest/java/com/servora/android/ui/jobs/JobDetailsScreenTest.kt` |

The viewer's own cases are unchanged: its note behaviour, paging, zoom, save, share and in-viewer report
are the same behaviour, drawn through the shared note piece.

## Verification

**Android** — JVM unit: **PASS** (`make android-test`: **479 tests, 0 failures, 0 skipped**, up from 474,
with the new `JobActivityPhotosTest` — 5 cases: the collection keeps only the photos the API reported and
in its order, a photo this build cannot place is left out, an unread or empty Activity answers no photos,
the folded preview is the collection shortened to its first few, a short collection is previewed whole,
and a phase code this build does not have is not guessed); lint: **PASS** (`make android-lint`, `BUILD
SUCCESSFUL`, 44 warnings — the same set as before this slice, none from the changed code beyond the
pre-existing `ModifierParameter` note on `JobActivitySection`'s signature); debug build: **PASS**
(`make android-build`); device-test sources: **PASS** (`./gradlew assembleDebugAndroidTest`, so the
Compose cases are known to build and the APK is produced).

**No device or emulator command was run, and no `adb` was used (`qa.md` §7.3)**, so the Compose cases are
**NOT RUN — device QA is the product owner's**; the runbook below is the hand-off. Nothing was changed in
`api/`, so no API command was run.

## Manual QA runbook — Job Activity photo UX

**Before you start.** Sign in as a Technician (or Manager) on a Job with **at least four** photos, one of
them carrying a long note (several lines) and one with no note. The API, the database and the rest of the
stack are unchanged; only the app needs the new build.

1. Open the Job and scroll to **Job Activity**.
   **Expected:** the section reads **Job Activity · n**, then a heading **Photos · 4** with up to three
   small thumbnails beside it and a chevron, and the timeline directly below — no photo strip between
   them.
2. Tap the **Photos** heading (anywhere on the row, not only the chevron).
   **Expected:** the strip of 132 dp tiles appears under the heading, each with its phase badge and its
   note snippet; the chevron has turned a quarter turn; the small preview thumbnails are gone.
3. Scroll the strip sideways, then tap a tile.
   **Expected:** the strip scrolls naturally and the tile opens the full-size viewer on that photo, with
   the position reading e.g. `3 / 4`.
4. Close the viewer and tap the heading again.
   **Expected:** the strip folds away, the three preview thumbnails return, and the heading still reads
   `Photos · 4`.
5. Rotate the device, and separately switch the app language, while the gallery is folded and again while
   it is open.
   **Expected:** the folded/expanded state survives the rotation; the new copy is translated
   (*Afficher les photos* / *Masquer les photos*); the count reads the same in both languages.
6. Scroll through the timeline to a photo's own entry.
   **Expected:** **Added a photo**, then the member and time (`Visit N · member · time` where the photo
   belongs to a Visit), then the photo itself with its phase badge over its bottom-left corner, then the
   note under it. **No** `(During work)` in the title. A photo with no note shows no empty caption area.
7. Tap the timeline photo.
   **Expected:** the same viewer opens, on that same photo — the position matches the photo's place in
   the Job's own order (the gallery's order), and swiping left and right continues through the other
   photos.
8. Open the entry with the long note and tap **More**; then tap **Less**.
   **Expected:** three lines are read first; **More** shows the whole note with the photo still on
   screen; **Less** puts it back. An entry with a short note draws no expansion action at all.
9. Turn on TalkBack and focus the heading, then a timeline photo.
   **Expected:** the heading is announced as the action with its count (*Show photos* / *Hide photos*,
   `Photos · 4`); each photo is one control announced as its photo and phase and the action *View photo*.
10. Switch to the dark theme and repeat 1, 2 and 6.
    **Expected:** the heading, the preview, the badges and the notes keep their contrast; nothing is
    drawn with a hard-coded colour.

**Known limitations, deliberately.**

- One photo per timeline entry is what the Activity carries today, so no `+n` grid is drawn (see above).
- Photos still waiting to upload are the tray's, and the gallery counts only what the backend accepted
  (`BR-001`).
- Nothing here is a new capability: which photos exist, whose they are and whether they may be read are
  the API's answers, unchanged (`BR-001`, `BR-007`).

## Out of scope / next

- **No API, contract, database, permission, domain or offline change.** `docs/api/job-activity.md`,
  `docs/api/job-photos.md`, the routes, the DTOs, the schema and the local evidence store are untouched.
- **Phase 6a (refused photos are explicitly discardable, `D6c` ✓) is still next** in
  `docs/tracker/029-photo-evidence-phases.md`; Phase 5 stays blocked on the deferred `D5`.
- Evidence retention, deletion and lifecycle (`D6a`/`D6b`/`D6d`/`D6e`), thumbnails/presigned reads
  (`ADR-013` open questions 3 and 4), a cross-Job or device-wide gallery (`D11`) and sharing more than the
  photo (`D13`) all remain **open**, and nothing here decided them.
