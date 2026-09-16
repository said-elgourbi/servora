# Tracker 029 — Photo evidence after 028: permissions, picker, viewer, offline and lifecycle

**Status: PHASE 0 ANSWERED IN FULL (2026-09-16) — `D5`, `D6a`–`D6e`, `D7`, `D8`, `D14` and `D15` are
decided; no question in this tracker is awaiting an answer.** Phases 1, 2, 3, 4, 4b, 4c, 4d, 4e, **5**,
**6a**, **6b** and **the API half of 9** are implemented, and the viewer's **chrome** (2026-09-16,
`docs/tracker/031-android-photo-viewer-ui.md`) and
**Job Activity's photo UX** (2026-09-16, `docs/tracker/032-android-job-activity-photo-ux.md`) were refined
without changing a decision or a contract. **Phase 6c (metadata: GPS/EXIF stripping and phase draft state)
is the phase to run**; **Phase 8** (presigned reads) follows from the same round. **Phase 9 (audio
evidence) is now tracked in its own tracker**, `docs/tracker/035-android-audio-evidence.md`, whose Phase 9a
landed the API half on 2026-09-16 and whose Phases 9b/9c carry the Android recorder, offline upload and
playback.

Date: 2026-09-15
Predecessors: `docs/tracker/027-android-job-photo-updates.md` (capture, offline upload, Activity gallery),
`docs/tracker/028-android-unified-job-update.md` (the one Add update action),
`docs/tracker/022-android-job-activity-timeline.md` (the Activity the photos appear in),
`docs/tracker/023-object-storage-minio.md` (the store), `docs/tracker/025-android-offline-room-outbox.md`
(the offline engine)
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-012`, `BR-013`, `BR-014`, `BR-015`, `BR-027`,
`BR-028`, `BR-031`, `BR-032`, `BR-033`, `BR-041`, `BR-042`, `BR-051`, `BR-067`, `BR-080`
Contracts, standards and decisions: `docs/api/job-photos.md`, `docs/api/job-activity.md`,
`docs/architecture/offline-first-architecture.md` §5, §6, §9, §13,
`docs/decisions/013-object-storage-minio-and-s3.md`, `docs/design/android-design-system.md`
Design reference that is **not** yet implemented: `Figma/src/imports/pasted_text/servora-job-details-spec.md`
§6 (**Camera** *directly inside Servora*, **Existing Photos** multi-select, Audio, Files),
§10 (attachment thumbnails inside an activity entry) and §11 (thumbnails, a compact gallery, and
**"Tapping an attachment should open an appropriate preview/viewer"**).

## How this tracker is used

- It is the **only plan of record** for photo-evidence work after tracker `028`.
- **`## Current phase` names the phase to run.** Read that section first. Do not start a later phase
  while a decision an earlier phase depends on is still open (`Project.md` §6, §28).
- **Phase 0 belongs to product ownership.** Every other phase is implementation, and starts only when
  the decisions it depends on are recorded in `## Phase 0 decision log` below.
- No open question may be implemented as an assumption (`BR-042`). If implementation reaches one, stop
  at that boundary and record it here rather than guessing.
- A phase is one vertical slice (`Project.md` §6–§9): database, API, shared values, Android,
  permissions, offline, localization, tests and documentation as that phase requires — not only the
  layer that is easiest to change.
- When a phase lands, append its record to `## Phase log` in the `qa.md` §16 format and repoint
  `## Current phase`. A landed phase's record is never rewritten; a new record is added instead.

## Current phase

**Phase 6c — evidence metadata: no location metadata, phase as draft state (`D7` ✓). Phase 6b landed on
2026-09-16, so this is the earliest phase whose decisions are answered; Phases 8 and 9 follow from the
same 2026-09-16 decision round.**

**Phase 6b (2026-09-16) — removing accepted evidence (`D6a`, `D6b`, `D6d` ✓) is implemented.** Accepted
evidence is immutable, so the API gained no way to edit it: what it gained is the one operation `BR-089`
defines. A new `job_photo_removals` table records **who** removed a photo, **when** and **why**, `job_photos`
stays append-only, and the removal is what takes the evidence out of ordinary use — the photo leaves the Job
Activity's photos, its bytes answer `404`, and the removal itself is history Activity always states
(`JOB_PHOTO_REMOVED`). It is authorized by the new Manager-level `evidence.photo.remove` (the default
Technician role does not hold it) and it is **online-only**, because its route takes no idempotency key and no
conflict policy is decided. The Android viewer offers the action on that capability, confirms it and requires
a reason. Recorded below and in `## Phase log`.

**Phase 6a (2026-09-16) — a photo the API permanently refused is explicitly discardable (`D6c` ✓) is
implemented.** The tray offers the action the refusal leaves behind, beside the reason it reports: a photo
whose upload was refused — the one submitted photo whose upload is finished, so it can never be replayed —
carries a **Discard** control, and discarding removes its local file, its pending row and its queued
refusal **together**, so no file outlives its record, no row outlives its file and the outbox cannot replay
what the technician finished with. A queued or retrying upload is untouched: the API may still accept it, so
the removal is still refused and reported (`BR-014`). Android-only — no API, database, permission, contract
or submitted byte changed, and the local removal is not a removal of evidence (the backend never accepted
it). Recorded below and in `## Phase log`.

**Phase 5 (2026-09-16) — offline visibility of accepted evidence (`D5` ✓) is implemented.** The Job
Details and Job Activity reads are served from the working set when the API cannot be reached, so the Job
and the evidence it holds — which photos, with their phase, note and time — are readable without
connectivity; both are marked on screen as the last answer the backend reported. Photo bytes stay
best-effort: accepted evidence is read from the image stack's bounded, evictable cache when it is there and
through the API when it is not, a pending upload's bytes are never a cache entry, nothing is prefetched, and
a photo that is neither on the device nor cached is reported as **not available offline** rather than as an
error. No decision changed and no API, database or shared contract was touched. Recorded below and in
`## Phase log`.

**Phase 4f (2026-09-16) — the viewer's chrome, recorded in its own tracker
(`docs/tracker/031-android-photo-viewer-ui.md`).** The photo viewer was refined into a media-viewer surface:
its own black ground and white ink rather than the theme's `surface`, a minimal overlay top bar
(close · position · Save/Share icons), the phase badge over the photo, a **Notes** section with **More**,
an in-flight signal on the action that is running — and, the defect it fixes, the report of a save, a share
or a refusal drawn **inside the viewer** instead of behind it. No decision above changed: paging, order,
zoom, the evidence actions and every API contract are untouched. Phase 6a stays startable beside Phase 5
(2026-09-16).

**Job Activity's photo UX (2026-09-16) — a collapsible gallery and each photo inside its own timeline
entry, recorded in its own tracker (`docs/tracker/032-android-job-activity-photo-ux.md`).** The strip
above the timeline became a folded-by-default section headed **Photos · n** (the whole heading is the
target, the chevron turns while the strip is shown, and up to three 32 dp thumbnails preview what it
counts), and a `JOB_PHOTO_ADDED` entry now reads **"Added a photo"** and draws the photo itself with the
phase badge and the note under it — opening the **same** viewer on the same page as a gallery tile. No
decision above changed: the collection, its order, the viewer, its paging, the evidence capabilities and
every API contract are untouched. Phase 6a stays startable beside Phase 5 (2026-09-16).

**Review panel (2026-09-16) — the note field was left under the keyboard and the preview was cropped.**
Two physical-device defects in the panel a capture is confirmed in were fixed together, outside the
phase sequence. Its content now **scrolls**, so the note stays reachable while the keyboard is up: the
sheet already lifts with the keyboard — Material 3's sheet applies the IME inset inside its own window,
which the signed-in shell's inset cannot reach, because the sheet is a window of its own — and what was
missing was a content area that can shrink, that being the resize Compose answers by bringing the
focused field back into view (`docs/tracker/014-android-add-customer.md`). The preview draws the
**whole** photo (`ContentScale.Fit`) instead of a crop of it, because what is being confirmed is the
photograph; the tray and gallery tiles keep cropping, a tile being a square that stands for a photo. No
API, database, contract or permission change, and no decision of this tracker changed. See
`## Manual QA runbook — review panel (2026-09-16)`.

**Phase 0 is answered in full (2026-09-16).** Product ownership answered the questions this tracker was
still carrying and stated the guiding position for evidence: **append-only once accepted, offline-first for
the technician, and a storage lifecycle that follows the Job/evidence lifecycle rather than UI
convenience** — recorded as `BR-088`. The answers are in `## Phase 0 decision log` (D5, D6, D7, D8, and two
new rows D14 and D15) and as business rules `BR-088` – `BR-091` (`BR-040`). In one place, what the round
decides:

- **D5 ✓ — accepted evidence is readable offline.** Its **metadata** (phase, note, time, which photos the
  Job has) must be readable without connectivity; its **bytes** are cached **best-effort** — cached when
  the device already holds them or the technician explicitly opens or downloads them, never by downloading
  every historical photo. A **pending** upload's bytes are guaranteed until synchronization confirms the
  evidence; after that the local copy is an **evictable, size-based LRU cache** managed by the app and the
  platform, not a fixed retention period. Delivered by **Phase 5**.
- **D6a/D6b ✓ — accepted evidence is immutable.** No overwrite, no byte replacement, no phase change, no
  silent delete. A photo can be discarded only **before** submission; afterwards it is removed only through
  an explicit, audited, **Manager-level** `evidence.photo.remove` that soft-removes it from ordinary views
  while the audit record is preserved. **No edit-photo route exists or will be built**; a note or caption
  correction is recorded as new Activity. Delivered by **Phase 6b**.
- **D6d/D6e ✓ — retention and the Job lifecycle.** Evidence belongs to the historical Job record; archiving
  or canceling a Job never deletes it; archived Jobs and their evidence stay readable to members permitted
  to view archived Jobs; v1 retention is **indefinite for the life of the tenant** rather than an invented
  regulatory period; normal product flows do not hard-delete Jobs, so evidence never becomes orphaned, and
  any future administrative or GDPR-style purge is a deliberate cascade (Job → Visits → records → objects)
  with asynchronous, idempotent object deletion. Decided in `BR-090`, with no v1 implementation work left
  beyond what Phase 6b carries.
- **D7 ✓ — metadata.** `phase` (`BEFORE_WORK` / `DURING_WORK` / `AFTER_WORK`) remains the **only** structured
  photo classification; the chosen phase is **draft state for the update in progress** (it survives sheet
  and process recreation) and never a global preference; and **GPS/location EXIF plus other unneeded
  metadata are stripped from uploaded evidence by default**. Delivered by **Phase 6c**.
- **D8 ✓ — audio yes, generic files no.** Audio notes become an evidence kind of their own on the same
  storage, outbox, upload, authorization and immutable-history rules, activating the reserved
  `evidence.audio.add` when it is implemented (**Phase 9**); arbitrary file attachments stay out of v1; and
  a kind's affordance is not exposed before the kind actually works.
- **D14 ✓ — the evidence entry point.** The Add update sheet stays the canonical way to choose the kind
  (and therefore to switch Photo → Audio/Text); inside the photo flow, **Take another launches the camera
  again** rather than bouncing the technician back through the picker. That is what the tray already does,
  so no code changes — the open design-owner item is closed.
- **D15 ✓ — the store's read model.** No server-side derived-thumbnail pipeline in v1: the normalized
  original is stored and clients render from it. Reads move to **short-lived presigned GET URLs issued by
  the API after authorization**, with the bucket private and uploads staying on the API port; no second,
  separately configured storage endpoint is introduced. Delivered by **Phase 8**, whose design must first
  resolve the presigning constraint `ADR-013` D7 records.

**This documentation round implements none of it.** Each phase below names what it changes, and the state
table still describes the code as it is today.

- **Phase 4e landed (2026-09-15): the viewer pages through the Job's photos, and a photo can be saved on
  the device or shared with another application (`D11`, `D12`, `D13` ✓).** The sequence is the one the
  screen already presents — the evidence the backend holds first, then the photos this device still holds
  — a swipe pages only while the photo on screen is at fit (a zoomed photo still pans, `D9`), and the
  chrome follows the photo: its phase badge, a localized position, its note, and two actions that read
  evidence on `evidence.view`. **Save to device** writes into a `Servora` album in the device's own
  gallery (no permission from API 29; `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"` for API
  26–28, asked for when a save needs it and reported if it is declined), and **Share** hands the photo to
  the platform's own chooser through the app's `FileProvider`, extended by one cache path. The bytes are
  read by one shared reader (`JobPhotoContentReader`) that the image stack now uses too, so the
  `401 → renew once` path exists once. Android-only: no API, database, contract or permission change.
  See the Phase log entry, `docs/decisions/017-viewer-paging-save-and-share.md` and
  `## Manual QA runbook — Phase 4e`.
- **Phase 4c landed (2026-09-15): the image stack is Coil 3 behind `JobPhotoImages` (`D4b` ✓).** Every preview and
  the full-size photo are now drawn from a request the feature answers with, and the library fetches, samples,
  turns by the photo's own EXIF orientation, caches and draws them — so a photo is decoded once at the size it is
  drawn and read again from memory or from disk instead of from the API on every cold start. The reader is the
  feature's own fetcher (`JobPhotoFetcher`), which keeps the `401 → renew once` path every other read of evidence
  has and reads a pending photo from the file the device already holds (§9); the cache is keyed **per session
  subject** and released before a different session reads anything (`BR-007`); the hand-rolled decode, the
  viewer's ceiling rule (`jobPhotoFittingSampleSize`) and the 24-entry `LruCache` are retired. No API, database,
  permission or contract change. See the Phase log entry, `docs/decisions/016-…` and `## Manual QA runbook —
  Phase 4c`.
- **Phase 4d landed (2026-09-15): the viewer's photo zooms and pans (`D9` ✓).** The photo is drawn by
  **Telephoto** (`me.saket.telephoto:zoomable-image-coil3:0.19.0`) over 4c's stack: a pinch zooms, a drag pans
  within the photo's bounds, a double-tap goes to the library's zoom ceiling and no scale goes below "fit" — and
  what a zoom shows is the photo's own pixels, because the layer sub-samples the file the stack cached instead of
  scaling a bitmap that was decoded to fit the screen. The phase badge, the note, the close action and the
  platform's back gesture are untouched, because the gestures belong to the photo's own area; and the viewer
  keeps its three states (reading, the photo, "cannot be shown") by observing the stack's own result for the
  request it already has, rather than reading the photo a second time. No API, database, permission or contract
  change. See the Phase log entry, `docs/decisions/016-…` and `## Manual QA runbook — Phase 4d`.
- **Phase 5 is the phase to run (2026-09-16).** `D5` is answered, so accepted evidence's metadata becomes
  readable offline and its bytes are cached best-effort; the phase below states what it changes. It is the
  earliest phase whose decisions are satisfied.
- **Phase 6a (refused photos are explicitly discardable) stays startable beside Phase 5**, and is the
  smallest slice: a photo the API permanently refused gains an explicit discard that removes its file and
  its row together. Android-only, and it touches neither the API nor the pipeline.

- **The decisions this section was waiting on are recorded (2026-09-15, and completed 2026-09-16).** Product
  ownership answered **D2, D4b, D6c, D7b, D9** and **D10** on 2026-09-15, **deferred `D5`** the same day and
  then **answered it, with D6, D7, D8, D14 and D15, on 2026-09-16**. Each answer, its consequences and the
  document it changes are in `## Phase 0 decision log` and in the decision's own section below. Three of
  them shape the work that follows: **D4b = (a)** puts **Coil 3** behind the existing `JobPhotoImages`
  port, **D9 = (b)** adds **pinch-zoom and pan** to the viewer on top of it (Telephoto), and
  **D7b = (a)** makes the preparation steps turn a re-encoded photo's pixels so what Servora **stores** is
  upright. **D10 = (a)** leaves the zero-permission system picker as the only library source, so
  MediaStore is not adopted anywhere.
- **Phase 4b landed (2026-09-15): the preparation steps now store an upright photo.** A photo the Phase 2
  steps re-encoded (the `D3b` conversion or the `D3c` resize) used to be written with landscape pixels and
  **no** orientation tag, so the evidence itself was sideways — on the device and in the bucket. Both steps
  now turn what they decoded by the orientation the **source** bytes declare, before scaling and encoding,
  through **one** shared read and turn (`JobPhotoOrientation`'s pure rule, `JobPhotoExifOrientation`'s
  platform read), which is the same leaf the display path uses — so the display fix of 2026-09-15 and the
  stored fix cannot drift (`D7b` ✓). No API, database, permission, schema or uploaded-byte change: what a
  re-encoded photo is stored as changes only in orientation, not in longest edge or quality. See the Phase
  log entry and `## Manual QA runbook — Phase 4b`.
- **Phase 4 (full-size viewer, Android) is implemented and verified.** A tap on an accepted photo in
  the Job Activity gallery, or on a photo still waiting in the tray, opens the photo itself over the
  screen: the phase it was recorded in, the technician's whole note, and the photo drawn at the size
  the screen can hold. It was built on **D4 = (a)**, so no caching or thumbnail work was in scope, and
  the caching question the answer did not take is recorded as **D4b** and delivered by Phase 4c. See the
  Phase log entry and `## Manual QA runbook — Phase 4`.
- **The portrait-orientation defect is closed on both halves.** The device's camera writes a portrait
  capture as the sensor's own landscape pixel data plus an EXIF `Orientation` tag, and every Android
  decode drew those pixels exactly as stored — so a portrait photo appeared turned on its side in the
  review preview, the pending tray, the gallery tiles and the Phase 4 viewer. The **display** half was
  fixed on 2026-09-15, outside the phase sequence: the decode applies the orientation the file declares
  (`JobPhotoOrientation` + the shared read, no dependency added, and **presentation only** — not a byte of
  stored evidence is rewritten). The **stored** half is Phase 4b above. See
  `## Manual QA runbook — photo orientation fix` (the display half) and `## Manual QA runbook — Phase 4b`
  (the stored half).
- **Phases 1, 2, 3, 4 and 4b are landed** — `EVIDENCE_PERMISSIONS` (`evidence.view`, `evidence.photo.add`),
  migration `0010_evidence_permissions.sql` and `docs/decisions/015-evidence-capabilities.md` (Phase 1);
  the content-type-aware pipeline and its published `FIT_TARGETS` (Phase 2); the two photo sources and
  the client's evidence gate (Phase 3); the viewer and the full-size read (Phase 4); the turnaround of a
  re-encoded photo's pixels and the shared read/turn leaf (Phase 4b).
- **Nothing in Phase 0 is still open (2026-09-16).** `D5` unblocks Phase 5; `D6` in full (including `D6a`,
  `D6b`, `D6d` and `D6e`) narrows Phase 6 to **Phases 6a and 6b** and closes the rest; `D7` in full decides
  the taxonomy, the phase's draft-state behaviour and the EXIF/GPS policy (**Phase 6c**); `D8` puts audio in
  v1's scope (**Phase 9**) and keeps generic files out; `D14` closes the entry-point question with no code
  change; and `D15` decides the store's read model (**Phase 8**) and the absence of derived objects.
  **Phase 7** still waits on the Angular application itself, which is not a product question.
- **Still outstanding from Phase 1:** the `Business Rules.md` `BR-008`/`BR-009` edit is product
  ownership's (`BR-040`). The Android update-action gate was work item 7 of Phase 3 and is **done**: the
  action is drawn on `evidence.photo.add` (or the Job update capability for a note), so a default
  Technician reaches the camera and the picker.

Phase 0 progress, kept for the record:

- **D1 answered** — a dedicated evidence capability, revocable per member. Recorded in D1 and in the
  decision log below; implemented by Phase 1 and recorded in `docs/decisions/015-evidence-capabilities.md`.
- **D1b answered** — the per-kind catalogue: `evidence.view` + `evidence.photo.add` now, held by Manager
  and Technician; `evidence.audio.add` reserved. Phase 1 was unblocked here.
- **D3 answered** — the camera stays as it is and a **multi-select gallery picker** is added beside it.
- **D3b answered** — a picked photo that is not already JPEG/PNG/WebP is **converted on device to JPEG**
  before it becomes a draft; the API, its sniffer and its schema stay unchanged.
- **D3c answered** — a photo over the API's 15 MiB limit is **downscaled/re-compressed on device to fit**;
  the stored evidence is the resized version and the original is not kept.
- **D4 answered** — the viewer is built and the preview/caching work is not: **(a)**, a full-size in-app
  viewer over the bytes the tile already reads. The caching and thumbnail options it did not take were
  recorded as **D4b**, answered below.
- **D2, D4b, D6c, D7b, D9 and D10 answered (2026-09-15)** — evidence stays **Job-level** (D2) and the
  domain document is corrected with the answer; the preview/caching strategy is **Coil 3 behind the
  existing port** (D4b); a refused photo becomes **explicitly discardable** (D6c); the preparation steps
  **turn the pixels** of a re-encoded photo so stored evidence is upright (D7b); the viewer gains
  **pinch-zoom and pan** (D9); and the device's media library is **not** read — the system picker stays
  the only library source (D10). Three new phases follow from those answers: **4b, 4c and 4d**.
- **D5 deferred (2026-09-15), then answered (2026-09-16)** — the deferral carried the gap knowingly for a
  day, with the reason recorded in the offline standard; the answer unblocks **Phase 5**, with accepted
  evidence's metadata readable offline and its bytes cached best-effort.
- **The picker's critical path is decided and delivered: Phases 1, 2, 3 and 4 have all landed.**


When a decision is answered:

1. Record it in `## Phase 0 decision log` (answer, who, date, and the document it changes).
2. Where a decision changes an accepted decision, write or update an ADR in `docs/decisions/` and update
   `docs/api/job-photos.md`, `Business Rules.md` or the offline standard as that decision requires
   (`BR-040`).
3. Repoint `## Current phase` to the earliest phase whose dependencies are satisfied.

## Handoff for a new session

A new chat needs only this file to continue:

1. Read `## Current phase`, then `## Phase 0 decision log`.
2. Read the phase section named there, plus its `Depends on` list — those decisions must already be
   answered; if one is not, the phase is not startable and Phase 0 continues instead.
3. Read the predecessor trackers named for that phase in `## State of the feature today`.
4. Follow `Project.md` §6–§9 (`design before implementation`), `dev.md` §2 and `qa.md` §15–§16, and
   report verification in the `qa.md` §16 format.

## State of the feature today (inspection of 2026-09-15, after Phase 4, rows updated through Phase 4c)

Photo evidence exists end to end for **Android + API only**. There is **no Angular application in this
repository**, so there is no web photo surface and no reporting over evidence.

| Concern | Today | Where |
| --- | --- | --- |
| Capture (camera) | the device's **external** camera app through `ACTION_IMAGE_CAPTURE` / `TakePicture`; no camera permission, no in-app camera (declined in D3) | `android/app/src/main/java/com/servora/android/ui/jobs/JobPhotoCapture.kt`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/xml/file_paths.xml` |
| Capture (library) | the device's own **photo picker** (`PickMultipleVisualMedia`, images only, system maximum items), launched from the update sheet; no storage or media-read permission, no new dependency; an item's bytes are read through the `JobPhotoPickedItems` port | `android/.../ui/jobs/JobPhotoPicker.kt`, `android/.../data/jobs/JobPhotoPickedItems.kt` |
| Entry point | one floating **Add update** action → sheet → the note kind (Job update capability) or the photo kind → the two sources: **Take photo** (camera) and **Choose from device** (picker) | `android/.../ui/jobs/JobDetailsScreen.kt`, `ui/jobs/JobUpdateSheet.kt`, `ui/navigation/ServoraNavHost.kt` |
| Client gate | the action and each kind are drawn on the capability the API enforces for it: `evidence.photo.add` for a photo, the Job update capability for a note — so a default Technician reaches the camera and the picker (`BR-009`, `BR-011`) | `android/.../domain/auth/Permission.kt`, `ui/customers/CustomerPermissionsUiState.kt`, `ui/jobs/JobDetailsScreen.kt` |
| Local bytes | `filesDir/job-photos/<subjectId>/<photoId>.<jpg\|png\|webp>`, partitioned by session subject and named for the type the bytes were **proven** to be; never a blob in the database | `android/.../data/jobs/JobPhotoFiles.kt` |
| Preparation | both sources go through one pipeline before a draft exists: the bytes are brought to a type Servora accepts (`D3b`, mirrored magic-number sniffer, JPEG conversion when the bytes are neither JPEG nor PNG nor WebP) and under the API's 15 MiB limit (`D3c`, resized/re-compressed as JPEG); a photo either step cannot deliver is refused with **nothing** recorded. **Since Phase 4b** each step also **turns the decoded pixels** by the orientation the source bytes declare, before scaling and encoding, so the JPEG it writes is upright: `Bitmap.compress` writes no orientation tag, and a photo whose pixels were not turned would be stored sideways with nothing to say otherwise (`D7b`) | `android/.../data/jobs/JobPhotoContentType.kt`, `JobPhotoProcessing.kt`, `JobPhotoExifOrientation.kt`, `JobPhotoSession.kt`, `JobPhotoRecording.kt` |
| Local metadata | Room `pending_job_photos` (draft, survives process death, `submitted` flag) | `android/.../data/jobs/PendingJobPhotoEntity.kt`, `PendingJobPhotoStore.kt`, `data/offline/OfflineMigrations.kt` |
| Upload | queued through the existing outbox (`job.photo.add`, `operationId` = photo id), replayed by the existing engine; the part declares the recorded type and its own file name; local file and row deleted **only** on `201` | `android/.../data/jobs/JobPhotoSession.kt`, `JobPhotoUploadHandler.kt`, `JobPhotoOperations.kt`, `data/offline/OutboxReplayEngine.kt` |
| API | `POST /jobs/:id/photos` (`evidence.photo.add`), `GET /jobs/:id/photos/:photoId/content` (`evidence.view`), `POST /jobs/:id/photos/:photoId/removal` (`evidence.photo.remove`, and the read's `includeRemovedEvidence` audit context). **Decided (`D15` ✓, 2026-09-16) and not implemented:** reads answer with a **short-lived presigned GET URL** instead of the bytes (Phase 8), uploads stay on the API port | `api/src/jobs/jobs.controller.ts`, `job-photos.service.ts`, `job-photo.dto.ts`, `job-activity.ts` |
| Storage | S3-compatible store behind the `ObjectStorage` port; key `evidence/job-photos/{organizationId}/{jobId}/{photoId}.{ext}`; provider selected by `STORAGE_PROVIDER` (`noop` by default, so an upload is refused with `503`). **No derived object is stored** (`D15` ✓, 2026-09-16), and **no store lifecycle rule exists**: retention is indefinite for the life of the tenant (`BR-090`) | `api/src/storage/object-storage.ts`, `s3-object-storage.ts`, `storage.module.ts`, `storage-config.ts`, `ADR-013` |
| Database | `job_photos` (`phase` CHECK, content-type CHECK, byte-size CHECK, idempotency unique index) | `api/drizzle/migrations/0009_job_photos.sql`, `api/src/database/schema.ts` |
| Timeline | one Activity projection, newest-first; `JOB_PHOTO_ADDED` is Job-level and carries `photoId`, `photoPhase`, `body` (the note). **Since 2026-09-16** a photo entry reads *Added a photo* and draws the photo itself (176 × 132 dp thumbnail, the phase badge over it, the note under it, the rest of a long note behind **More**), opening the same viewer on the same page a gallery tile does | `api/src/jobs/job-activity.ts`, `docs/api/job-activity.md` §3.2, `android/.../ui/jobs/JobActivitySection.kt`, `docs/tracker/032-android-job-activity-photo-ux.md` |
| Gallery | **since 2026-09-16** a collapsible section at the head of Job Activity: heading `Photos · n` (`labelSmall` bold, whole row a ≥ 48 dp target, rotating chevron, action-labelled description), folded by default with up to three 32 dp preview thumbnails; unfolded, a `LazyRow` of 132 dp tiles (phase badge + note snippet), each a tap target that opens its photo in the viewer. It draws exactly the `JOB_PHOTO_ADDED` entries the API reported, in that order — never a second list | `android/.../ui/jobs/JobPhotoComponents.kt` (`JobPhotoGallerySection`, `JobPhotoGalleryHeading`, `JobPhotoGallery`), `docs/tracker/032-android-job-activity-photo-ux.md` |
| Viewer | **exists since Phase 4**: a tap on a gallery tile or on a tray tile opens the photo itself in a full-screen dialog — phase badge, close action, the technician's whole note, and the photo drawn `ContentScale.Fit`; the surface is the theme's own (`surface` / `onSurface`), so no new colour token; a photo that cannot be read says so instead of drawing something else; closed by its action or the platform's back gesture. **Since Phase 4c (2026-09-15)** the photo is drawn by the image stack, and the three states it presents are the stack's own — reading, the photo, and "cannot be shown" — so a photo already drawn once comes back from memory instead of being read again (`D4b`). **Since Phase 4d (2026-09-15, `D9` ✓) the photo zooms and pans**: it is drawn through **Telephoto** over the stack — a pinch zooms, a drag pans within the photo's own bounds, a double-tap goes to the library's zoom ceiling and no scale goes below "fit" — and what a zoom shows is the photo's own pixels, because the layer sub-samples the file the stack cached. **Since Phase 4e (2026-09-15, `D11`/`D12`/`D13` ✓) the viewer pages, saves and shares**: it draws the Job's photos as one sequence in the order the screen presents them (evidence the backend holds first, then the photos this device still holds, one entry per photo id), the position says which of them is on screen, a swipe pages **only while the photo is at fit** (a zoomed photo pans, so the two gestures never compete), the note pages with its photo, a pending photo keeps reporting its upload state, and two actions read evidence on `evidence.view` — **Save to device** and **Share**. The phase badge, the position, the note and the 48 dp actions stay outside the gesture area, and the platform's back gesture still closes the viewer | `android/.../ui/jobs/JobPhotoViewer.kt`, wired in `ui/jobs/JobDetailsScreen.kt` |
| Evidence out of Servora | **exists since Phase 4e (2026-09-15, `D12`/`D13` ✓)**: the viewer's two actions. **Save to device** writes the photo the technician is looking at into a `Servora` album in the device's own shared image collection — API 29+ with no permission and `IS_PENDING` until its bytes are complete, API 26–28 through `WRITE_EXTERNAL_STORAGE` (`android:maxSdkVersion="28"`), which the screen asks for when a save needs it and reports if it is declined. **Share** hands the photo to the platform's own chooser (`ACTION_SEND`, the photo's own MIME type) from one app-private cache directory cleared before each export and exposed by a single added `FileProvider` `cache-path`, so the receiving application is granted that one file and nothing else. The copy is named `Servora-<photoId>.<ext>`; only the photo travels (no note, no Job or customer detail), and both actions are available for evidence the backend holds and for a photo this device still holds | `android/.../data/jobs/JobPhotoExport.kt`, `AndroidJobPhotoExportTarget.kt`, `JobPhotoExportTest.kt`, `ui/jobs/JobPhotoViewer.kt`, `res/xml/file_paths.xml`, `AndroidManifest.xml` |
| Pending tray / review | tray of not-yet-accepted photos with upload state, each tile a tap target that opens the viewer; review panel with preview, note and the three phase buttons, which a captured photo **and** a picked photo are each confirmed in; a pick's items are taken one at a time, and skipped items are reported by their place in the pick. The preview draws the **whole** photo and the panel's content **scrolls**, so the note stays reachable while the keyboard is up (both fixed 2026-09-16). **Since Phase 6a (2026-09-16, `D6c` ✓)** a photo whose upload the backend **refused** carries the refusal's own **Discard** action (`jobPhotoRefusedDiscardTag`) beside the reason the tile reports: it removes the device's file, the pending row and the queued refusal together, and it is the only control that tile draws — a queued or retrying upload still offers none, because the API may yet accept it (`BR-014`) | `android/.../ui/jobs/JobPhotoTray.kt`, `JobPhotoReviewSheet.kt`, `ui/jobs/JobDetailsViewModel.kt`, `data/jobs/JobPhotoSession.kt` |
| Thumbnails | **the image stack since Phase 4c (2026-09-15, `D4b` ✓)**: the library (Coil 3) decodes, samples, turns by the photo's own EXIF orientation and caches, so a photo is decoded once at the size it is drawn and read again from memory or disk instead of from the API on every cold start. A tile asks for a **512 px** decode and the viewer asks for one at `VIEWER_DECODE_EDGE_PX` — half of the 2560 px bound, because the library samples in powers of two and a request for the bound itself would leave a 4032 px capture unsampled. Both are drawn from a request the feature answers with (`JobPhotoImages`, `DefaultJobPhotoImages`), never from bytes it decoded itself. The memory and disk caches are keyed **per session subject** (`jobPhotoImageCacheKey`) and released once per session on a read under a different subject (`JobPhotoImageCacheScope`), so a member signing in on the same device can never be served another member's evidence (`BR-007`, and the position `JobPhotoFiles` takes with `filesDir/job-photos/<subjectId>/`). The bytes are read by `JobPhotoFetcher`: a pending photo from the app-private file that already holds it (§9) with no second cache **on that path** (the viewer's zoom layer does have the stack cache a copy so it can sub-sample — Phase 4d), and the backend's evidence through the API with the same `401 → renew once` path as every other read (`BR-018`), cached on disk so each photo is downloaded **once** rather than once per cold start. **The viewer's own photo is drawn by the zoom layer since Phase 4d (2026-09-15)**: the layer sub-samples the file the stack cached, so the decode a request bounds is the layer's fit-size preview and a zoomed photo is read from the cached file at the size it is drawn — which is what makes zoom legible rather than a scale-up of a screen-sized bitmap (`D9`). **No derived object server-side** (**`D15` answered on 2026-09-16: none in v1** — the normalized original is stored and clients render from it). `Cache-Control` on the content answer is not added. **Retired by this phase**: the hand-rolled decode path, the 24-entry in-memory `LruCache`, the viewer's ceiling rule `jobPhotoFittingSampleSize` and its cases, and the EXIF turn applied to a *decode* (the library applies the tag itself); the **shared read/turn leaf `JobPhotoExifOrientation` stays**, because the preparation steps still need it (`D7b`, Phase 4b), and the pure orientation rule keeps its JVM coverage | `android/.../data/jobs/JobPhotoImages.kt`, `JobPhotoImage.kt`, `JobPhotoFetcher.kt`, `JobPhotoImageCacheScope.kt`, `JobPhotoSampling.kt`, `JobPhotoOrientation.kt`, `JobPhotoExifOrientation.kt` |
| Photo picker | **exists since Phase 3**: `PickMultipleVisualMedia` and a `PickVisualMediaRequest(ImageOnly)` launch, with each item's bytes read through `JobPhotoPickedItems`; still no `ACTION_PICK`, `GetContent`, `MediaStore` or CameraX — **`D10 = (a)` keeps the picker the only library source**, so the device's media library is never read and no media permission is requested. **Coil** (D4b) arrived in Phase 4c for **drawing**, not for reading the library | `android/.../ui/jobs/JobPhotoPicker.kt`, `android/.../data/jobs/JobPhotoPickedItems.kt` |
| Offline visibility | **exists since Phase 5 (2026-09-16, `D5` ✓)**: the **Job Details** and **Job Activity** reads are the app's fifth and sixth working-set adopters, so without connectivity the Job and the evidence it holds — which photos, with their phase, note and time — and the Activity that projects them are still readable. Both answers are kept as the backend's **wire response** (`JobEvidenceCache`) and mapped on the way out, replaced by every successful read, partitioned by session subject, and served **only** when the failure could not reach the backend (a refusal is never masked). Each is marked on screen as the last reported answer (`OfflineNotice`, its own tag per read). Photo **bytes** are not held here: a pending upload's bytes stay in app-private storage and are given **no disk-cache key at all** (`jobPhotoImageDiskCacheKey`), while accepted evidence is read from the image stack's disk cache when it is there and through the API when it is not. Nothing prefetches, and a photo that is on neither the device nor the cache is reported as **not available offline** (the viewer's own report; an offline glyph on a tile) rather than as a failure | `android/.../data/jobs/JobEvidenceCache.kt`, `JobDetailsRepository.kt`, `data/offline/WorkingSetEntry.kt`, `ui/jobs/JobDetailsScreen.kt`, `ui/jobs/JobActivitySection.kt`, `ui/jobs/JobPhotoViewer.kt`, `ui/jobs/JobPhotoComponents.kt`, `data/jobs/JobPhotoFetcher.kt`, `data/jobs/JobPhotoBytesUnavailable.kt` |
| Tests | API unit + e2e for the contract; Android JVM tests for the pipeline, the handler, the photo ViewModel (both sources), the image stack's read path and its cache rules, the viewer's photo resolution, and (since Phase 5) the Job read's offline fallback and the reason a photo's bytes could not be produced; instrumented Compose cases for tray/gallery, the update sheet's two sources and the viewer (opening, full-size read, reading nothing, a photo the stack cannot read, zooming and panning without losing the photo's phase, note or close action, and closing from a zoomed state) | `api/src/jobs/job-photo.dto.spec.ts`, `api/test/job-photos.e2e-spec.ts`, `android/app/src/test/.../JobPhotoContentTypeTest.kt`, `JobPhotoSessionTest.kt`, `JobPhotoUploadHandlerTest.kt`, `JobPhotoSamplingTest.kt`, `JobPhotoFetcherTest.kt`, `JobPhotoImageCacheKeyTest.kt`, `JobPhotoImageCacheScopeTest.kt`, `JobPhotoOrientationTest.kt`, `ui/jobs/JobPhotoCaptureViewModelTest.kt`, `ui/jobs/JobPhotoViewerTest.kt`, `ui/jobs/JobDetailsRepositoryTest.kt`, `data/jobs/JobPhotoFetcherTest.kt`, `ui/jobs/JobDetailsViewModelTest.kt`, `android/app/src/androidTest/.../ui/jobs/JobDetailsScreenTest.kt` |

## Phases at a glance

| Phase | Goal | Depends on | Layers | Status |
| --- | --- | --- | --- | --- |
| 0 | Decide the open product questions below | — | none (product ownership) | **Answered in full — D1, D1b, D2, D3, D3b, D3c, D4, D4b, D6c, D7b, D9, D10, D11, D12 and D13 (2026-09-15); D5, D6a–D6e, D7, D8, D14 and D15 (2026-09-16). Nothing below is awaiting an answer** |
| 1 | Evidence capability set (who may add and read evidence) | **D1 ✓, D1b ✓** | API, database, docs | **Landed (2026-09-15)** |
| 2 | Content-type-aware local capture pipeline | **D3 ✓, D3b ✓, D3c ✓** | Android | **Landed (2026-09-15)** |
| 3 | Photo sources in the Add update sheet (gallery picker, multi-select) | **D3 ✓, D3b ✓, D3c ✓, Phase 1, Phase 2** | Android, localization, docs | **Landed (2026-09-15)** |
| 4 | Full-size viewer and a thumbnail strategy | **D4 ✓** | Android, docs | **Landed (2026-09-15)** — the viewer; its thumbnail/caching half is **Phase 4c** on the **D4b ✓** answer, and derived thumbnails are decided **out of v1** (`D15` ✓) |
| **4b** | **Re-encoded evidence is stored upright** | **D7b ✓** | Android, tests, docs | **Landed (2026-09-15)** — the preparation steps turn what they re-encode, through the shared read/turn leaf |
| **4c** | **Image stack and preview caching (Coil 3)** | **D4b ✓** | Android, tests, `docs/decisions/`, docs | **Landed (2026-09-15)** — Coil 3 sits behind `JobPhotoImages`: the feature answers with a request, the library samples, turns by EXIF and caches it, the reader keeps the `401 → renew once` path, and the cache is keyed and released per session subject |
| **4d** | **Viewer gestures (pinch-zoom and pan, Telephoto)** | **D9 ✓**, Phase 4c (landed) | Android, tests, design docs | **Landed (2026-09-15)** — the photo is drawn through Telephoto over the Coil 3 stack: a pinch to zoom, a drag to pan within bounds, a double-tap to the library's zoom ceiling and no scale below fit, with the phase badge, the note, the close action and the platform's back gesture unchanged, and a zoomed photo read from the file the stack cached |
| **4e** | **Viewer paging, save and share** | **D11 ✓, D12 ✓, D13 ✓**, Phase 4d (landed) | Android, tests, `docs/decisions/`, docs | **Landed (2026-09-15)** — the viewer pages through the Job's photos in the order the screen presents them, paging only while the photo is at fit; the chrome carries the photo's phase, a localized position and its note, and two actions read evidence on `evidence.view`: **Save to device** (a `Servora` album in the shared gallery; `WRITE_EXTERNAL_STORAGE` capped at API 28 and asked for when needed) and **Share** (the platform's chooser, through one added `FileProvider` cache path) |
| 5 | Offline visibility of accepted evidence | **D5 ✓** | Android, `offline-first-architecture.md` | **Landed (2026-09-16)** — the Job Details and Job Activity reads adopt the working set (wire responses, mapped on the way out, refuses never masked) and both are marked as the last reported answer; the photo-byte cache is stated as one rule (a pending photo gets no disk key, accepted evidence is cached), nothing prefetches, and a photo that is neither held nor cached is reported as **not available offline** |
| 6a | Refused photos are explicitly discardable | **D6c ✓** | Android, tests, docs | **Landed (2026-09-16)** — the tray's refused tile carries the refusal's own **Discard** action, and one action removes the device's file, the draft row and the queued refusal together (`§9`, `BR-014`) |
| **6b** | **Removing accepted evidence (soft, audited, Manager-level)** | **D6a ✓, D6b ✓, D6d ✓, D1 ✓** | API, database, Android, permissions, docs | **Landed (2026-09-16)** — `job_photo_removals` + `evidence.photo.remove` (Manager only), `POST /jobs/:id/photos/:photoId/removal` recording actor/time/reason, ordinary reads excluding the evidence while `JOB_PHOTO_REMOVED` stays in the timeline, the `includeRemovedEvidence` audit context, and the viewer's confirmed removal |
| **6c** | **Evidence metadata: strip GPS/EXIF, phase as draft state** | **D7 ✓, D7b ✓** | Android, API (validation), tests, docs | Startable |
| 6 | The rest of the evidence lifecycle | **D6 answered in full; `D6e` ✓** | — | **Closed** — no hard Job deletion in normal flows, so there is no orphaned object to fix; what remains is carried by 6b and 6c |
| 7 | Angular parity and reporting over evidence | D1–D15, Phases 5/6b/6c/8/9, an Angular application | Angular, API | Blocked on the Angular application, which does not exist in this repository |
| 8 | Evidence reads through short-lived presigned URLs | **D15 ✓**, Phase 5 (the read path it changes) | API, Android, `docs/decisions/`, `docs/api/` | Startable once the design states the resolution `ADR-013` D7 requires |
| 9 | Audio evidence (`evidence.audio.add`) | **D8 ✓**, Phase 1 | API, database, Android, offline, localization, tests, docs | **API half landed (2026-09-16)** — see `docs/tracker/035-android-audio-evidence.md` (Phase 9a): `job_audio_notes` + `job_audio_note_removals`, `evidence.audio.add`/`evidence.audio.remove`, the three `/jobs/:id/audio-notes` routes and `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED`, decided by `ADR-018`. Android (record, draft, queue, playback) is Phases 9b/9c of that tracker |

## Phase 0 — decisions requested

No code is written in this phase. Each decision below states the question, why it matters, the options
as they present themselves in the code today, what it blocks, and an **agent recommendation** that is
*not* a decision. Nothing here may be implemented until it is answered and logged below (`BR-042`).

### D1 — Who may add and read evidence (the capability set)

**Question.** Which stable permissions authorize adding a photo and reading its bytes, and which
default system roles hold them?

**Why it matters.** Today `POST /jobs/:id/photos` requires `JOB_UPDATE` and the read requires
`customers.view` (`api/src/jobs/jobs.controller.ts`; `docs/api/job-photos.md` §2). `BR-009` gives the
default Technician role `VISIT_ADD_NOTE`, `VISIT_UPDATE_ASSIGNED_STATUS` and `VISIT_RECORD_OUTCOME`
only — so **the technician `BR-015` assumes captures evidence cannot add a photo**; a default-Technician
session receives `403`. The two photo routes are also inconsistent by interim design.

**Options.**

- **(a) Evidence is field execution:** a dedicated evidence capability (for example `evidence.add`, in
  `BR-006`'s `resource.action` convention) granted to the Technician role and to Manager.
- **(b) A Job capability set:** define the `jobs.*` catalogue (`jobs.view`, `jobs.update`, …) and make
  evidence part of it, granting the evidence subset to the Technician role. This also resolves the
  interim `JOB_UPDATE` / `customers.view` reuse on the Job routes.
- **(c) Manager-only in v1:** keep `JOB_UPDATE` for the upload and accept that field evidence capture is
  a Manager workflow, with technician capture recorded as out of scope.
- **(d) Defer:** leave the guards as they are and postpone all technician-facing photo work.

**What it blocks.** Phase 1; and through it Phase 3 (a technician cannot exercise a picker without the
capability) and Phase 6 (delete/edit permissions).

**Agent recommendation (not a decision).** (a) or (b) — decide once, and record in the same decision:
the exact permission code(s), their bilingual names and descriptions (`BR-005`, `BR-006`), and which
default system roles receive them (`BR-008`, `BR-009`). Whatever is decided must be granted through the
permission model, never by a role-name check (`BR-004`, `BR-006`).

**Decision (2026-09-15, product ownership).** Evidence is authorized by a **dedicated evidence
capability** — option **(a)** — not by a Manager capability borrowed from the Job routes. The vocabulary
must be **future-proof and structured by kind** (D1b pins the exact codes): audio has to fit it without a
rename or a restructuring, and the capability must be grantable and **revocable per member**, so one
member who abuses it can be restricted without changing their role.

Consequences recorded with the decision:

- Phase 1 replaces the interim guards: the write stops depending on `JOB_UPDATE` and the read stops
  depending on `customers.view`.
- The default **Technician** role holds the evidence capability. That is the point of the decision —
  today's `JOB_UPDATE` guard is exactly what blocks a technician from adding a photo — and it extends
  `BR-009`, which is product ownership's edit to `Business Rules.md` (`BR-040`).
- The vocabulary is sized for audio from the start: audio uses its own already-decided, reserved code
  (`evidence.audio.add`, D1b), so it needs no rename and no new architecture — which makes **D8** a
  data-model and scope question rather than a permission question.
- The capability is granted through the normal permission model (`BR-006`): a role may hold it and a
  member may also hold it directly, which is how an abusing member is restricted under this decision.

**Decision (2026-09-15, product ownership) — D1b, the catalogue shape.** The add capability is
**per kind**, so one kind can be restricted without withholding the others:

| Code | Capability | When |
| --- | --- | --- |
| `evidence.view` | View evidence and read its bytes. | Now (Phase 1) |
| `evidence.photo.add` | Add photo evidence. | Now (Phase 1) |
| `evidence.audio.add` | Add audio evidence. | Reserved: created when audio is implemented, **not** now |

- Both current codes are held by **Manager** and **Technician** by default, which is what extends
  `BR-009` for the Technician role.
- `evidence.audio.add` is the **agreed extension point**, not a row to create today: no product rule
  defines audio (`BR-027`, `D8`), and creating a permission for a feature that does not exist would be
  invented behaviour (`BR-042`). When audio lands, the code is already decided, so it is additive.
- A member who abuses one kind is restricted by withdrawing that kind's capability — as a direct member
  grant or a role change — without touching the other kinds.
- Codes follow the existing `resource.action` convention with a kind segment (`BR-006`, `BR-041`), and
  their bilingual names and descriptions are added with them (`BR-005`).

### D2 — Evidence scope: the Job or the Visit

**Question.** Does a photo belong to the Job (as built) or to a Visit, or may it carry an optional Visit?

**Why it matters.** `job_photos.job_id` makes evidence Job-level, which is what the Activity projection
uses and what allows a Job with no Visit to carry evidence (`BR-051`, `docs/api/job-photos.md` §1).
`docs/domain/job-visit-domain-model.md` §469 still says evidence is attached to Visits and out of scope,
so the domain document and the implemented model disagree.

**Options.** (a) Keep Job-level and correct the domain document. (b) Move to Visit-level (evidence would
require a Visit). (c) Job-level with an optional `visit_id` link for grouping and reporting.

**What it blocks.** Nothing immediately; it blocks Visit-scoped grouping/filtering and evidence reporting
by Visit (Phase 7), and would change Phase 6's data model if it changes at all.

**Agent recommendation (not a decision).** (a) for v1 — the existing contract, the Activity projection
and `BR-051` all assume it; add (c) only when a real requirement needs it.

**Decision (2026-09-15, product ownership).** Option **(a)** — evidence stays **Job-level**, exactly as
implemented, and `docs/domain/job-visit-domain-model.md` §469 is corrected to say so.

Consequences recorded with the decision:

- **No schema, API or Android change.** `job_photos.job_id` stays the scope, the Activity projection
  stays Job-level (`JOB_PHOTO_ADDED`), and `BR-051` — a Job may carry evidence with no Visit — remains
  satisfiable. The contradiction was in the document, not in the model.
- **The domain document is corrected in the same change as this decision**, which is what closes it: §469
  no longer claims evidence is Visit-scoped and out of scope.
- **A Visit link stays available later** (option (c)) if grouping or reporting ever names a requirement
  for it; it is not added now, because nothing does (`BR-042`, `dev.md` §6).

### D3 — Photo sources and the capture surface

**Question.** Which sources may a technician record evidence from?

**Why it matters.** Only the **external** camera is implemented: `ACTION_IMAGE_CAPTURE` writing into
app-private storage, with no camera permission. There is no gallery source at all — no `ACTION_PICK`,
`GetContent`, `GetMultipleContents`, `PickVisualMedia` or `MediaStore` anywhere in `android/`. The
design spec asks for an in-app camera ("The technician should not need to leave Servora"), for
**Existing Photos** with multi-select, and for an attachment row
`[ Camera ] [ Photos ] [ Audio ] [ File ]` (`Figma/.../servora-job-details-spec.md` §6). Tracker `028`
recorded the picker as an open question precisely because "a picker that read another application's
storage would be a new capability with its own decision".

**Options.**

- **(a) Gallery picker, multi-select** (`PickVisualMedia` / `GetMultipleContents`), reusing the existing
  draft → review → tray → outbox pipeline. The modern system photo picker needs no storage permission.
- **(b) Picker plus an in-app camera** (CameraX or Camera2), which adds a dependency and changes the
  permission surface (`CAMERA`, and a camera-preview lifecycle).
- **(c) Keep the external camera only.**

**What it blocks.** Phase 2 (only needed once non-JPEG sources are possible) and Phase 3.

**Agent recommendation (not a decision).** (a) first: it reuses everything that exists, needs no new
permission and closes the largest gap between the design and the app. Treat (b) as its own decision,
because a new dependency must be justified (`dev.md` §4) and an in-app camera is a different permission
story from the current "the camera app owns the capture" model.

**Decision (2026-09-15, product ownership).** Option **(a)**: "Add photo" offers **two sources** — the
existing camera action and a **gallery picker with multi-select** — with the camera unchanged.

Consequences recorded with the decision:

- **The camera stays exactly as it is:** `ACTION_IMAGE_CAPTURE` through the device's own camera app, no
  `CAMERA` permission, no CameraX/Camera2 dependency, no in-app preview (`dev.md` §4 — nothing new is
  pulled in).
- **The picker is the system photo picker** (`PickVisualMedia` / `GetMultipleContents`), so no storage or
  media-read permission is requested and Servora reads nothing outside what the user selects.
- **Multi-select:** one action may produce several photos. Each selected item becomes **its own draft** —
  its own id, its own app-private file, its own draft row, its own outbox operation — so a photo that
  fails to copy or is refused by the API cannot block the others (`BR-014`).
- **Bytes are copied into app-private storage before anything is recorded**, exactly as a capture is, so
  the picker's URI is never the record and offline work keeps working (§9).
- **The design's in-app camera request is explicitly declined for now** (`Figma/…/servora-job-details-spec.md`
  §6, "The technician should not need to leave Servora"). It remains an open question, not a defect.
- **D3 unblocks Phase 3's dependency**, but Phase 3 still waits on Phase 1 (the `evidence.photo.add`
  capability) and Phase 2 (a picked photo is not necessarily a JPEG).

**Two consequences this decision creates, which Phase 2 needs answered (raised as D3b and D3c):**

- **D3b — content types a picker can produce.** A gallery item may be a **HEIC/HEIF** (or another type
  Servora does not accept), while the API accepts only JPEG, PNG and WebP and refuses a declared type
  that disagrees with the bytes. Phase 2 can only be written once it is decided whether such a photo is
  **refused with a clear reason** or **converted on device** before it becomes a draft.
- **D3c — size.** The API caps a photo at **15 MiB** (`MAX_JOB_PHOTO_BYTES`), and the camera app's JPEGs
  are small, but a picked original (a 48 MP photo, a large PNG, a panorama) can exceed it. Phase 2/3 must
  know whether an oversized photo is **downscaled/re-compressed on device** or **refused with a clear
  reason** and left for the technician to choose another.

**Decision (D3b, 2026-09-15, product ownership).** A picked photo whose bytes are not already
JPEG/PNG/WebP is **converted on device to JPEG** before it becomes a draft. Every pick therefore lands,
and the API's byte-sniffing rule, its content-type `CHECK` and the object-key extensions stay exactly as
they are — no schema change and no contract change.

Consequences recorded with the decision:

- The conversion happens **before the draft row is written**, so the stored file, the recorded
  `mimeType` and the draft row all describe the same JPEG — the invariant Phase 2 exists to create.
- It runs **off the main thread and per item**, because a large photo takes time to decode and re-encode;
  a multi-select must not freeze the screen while it works through the selection.
- A conversion that **fails** is an explicit, localized refusal at pick time, and **no draft is
  recorded** — nothing is silently dropped and nothing un-uploadable is created (`BR-014`).
- The API is unchanged: JPEG/PNG/WebP stay the accepted types, and a PNG/WebP picked as-is is uploaded
  as-is (only a non-accepted type is converted).
- **Evidence fidelity is a recorded consequence:** the stored evidence for a converted photo is the
  re-encoded JPEG, not the original bytes. If the EXIF/GPS question (`BR-038`, D7) is decided later, this
  decision matters to it — a converted photo loses most EXIF while an already-JPEG pick keeps it, so a
  later decision must state whether that asymmetry is acceptable or whether picked photos are re-encoded
  (or EXIF-stripped) uniformly.
- **D3c was left open by this:** a JPEG re-encode does **not** bound the file size, so a converted 48 MP
  original can still exceed the API's 15 MiB limit. **D3c is answered below.**
- **Landed (Phase 4b, 2026-09-15).** The conversion turns the pixels it decoded by the orientation the
  **source** bytes declare, before it encodes, so a converted photo is stored upright (`D7b`). Without it
  a converted portrait capture — which `Bitmap.compress` writes with no orientation tag — would have been
  stored sideways with nothing in the file saying otherwise. The conversion still produces a JPEG under
  the same quality and the same limit.

**Decision (D3c, 2026-09-15, product ownership).** A photo (picked or captured) that is larger than the
API's limit is **downscaled/re-compressed on device to fit**, so it can be uploaded. The stored evidence
is the resized version; the original is **not** kept.

Consequences recorded with the decision:

- **The pipeline order in Phase 2 becomes:** read the source bytes → apply the D3b type step (convert to
  JPEG when the bytes are not JPEG/PNG/WebP) → apply the D3c size step (resize when the result exceeds the
  limit) → write the app-private file → write the draft row. Everything before the draft is created, so
  the file, the recorded type and the row always agree.
- **The limit has one owner.** The API's `MAX_JOB_PHOTO_BYTES` (15 MiB) and `docs/api/job-photos.md` §3 are
  the authority; the client mirrors the value as its own constant and must not invent a different,
  stricter limit of its own.
- **A photo that cannot be fitted** (undecodable bytes, a failure in the resize) is an explicit, localized
  refusal at pick time with **no draft recorded** — the `BR-014` rule again: nothing silently dropped,
  nothing un-uploadable created.
- **Evidence fidelity is recorded a second time.** A large picked photo may now go through both a
  conversion and a resize, so the stored evidence is a re-encoded, resized JPEG and the original bytes are
  gone. The exact target (maximum edge and encoder quality) is an **implementation detail** chosen in
  Phase 2 to satisfy this decision; Phase 2's tracker entry must state the values it chose and the measured
  result, so the choice is reviewable rather than hidden.
- **Whether the technician is told that a photo was resized** is a presentation question for Phase 3 and
  is not decided here.
- **`BR-038`/D7 (EXIF and location) must consider both lossy steps:** a converted or resized photo loses
  most EXIF while an already-small JPEG pick keeps it.
- **Landed (Phase 4b, 2026-09-15).** The resize turns the pixels it decoded by the orientation the
  **source** bytes declare, before it scales and encodes, so a resized photo is stored upright instead of
  depending on a tag the re-encode discards (`D7b`). The measured target list is unaffected: a turn never
  changes which edge is the longest, so the rung that produced the bytes still reports the same longest
  edge and quality — what a resized photo is stored as changed only in orientation. The stored evidence
  still **is** the resized version, and the original is still not kept.

### D4 — Viewer and thumbnails

**Question.** What happens when a technician or manager taps a photo, and how are previews produced?

**Why it matters.** The gallery tiles are inert: `JobPhotoGalleryTile` has no click handler and no
full-size view exists, while the design spec says "Tapping an attachment should open an appropriate
preview/viewer" (§11). Separately, every tile fetches **full-resolution bytes** over the API
(`JobPhotoImages` → `GET /jobs/:id/photos/:photoId/content`), the OkHttp client is built with no cache
(`android/.../di/NetworkModule.kt`) and the API sends no `Cache-Control`, so the only cache is a
24-entry in-memory bitmap cache. A cold start or an eviction re-downloads every image again. `ADR-013`
leaves server-side thumbnails open (its open question 3).

**Options.** (a) An in-app viewer over the bytes already available, no API change. (b) Viewer plus a
client-side thumbnail strategy (HTTP cache / disk cache). (c) Viewer plus server-derived thumbnails
(a derived object or a second route), which is an `ADR-013` decision. (d) Any combination of the above.

**What it blocks.** Phase 4.

**Agent recommendation (not a decision).** Decide the viewer itself first (a); size the caching fix (b)
and server thumbnails (c) as separate items, because (c) changes the storage contract and (b) does not.

**Decision (2026-09-15, product ownership).** Option **(a)** — the full-size viewer, and nothing else:
what happens today when a photo is tapped, and no caching or thumbnail work.

Consequences recorded with the decision:

- **The viewer is a viewer.** A tap on a gallery tile or on a tray tile opens the photo over the screen
  it was tapped from, with the phase it was recorded in and the technician's whole note, and it is
  closed by its own action or the platform's back gesture. Zoom and gesture behaviour is not part of it
  (`D4` leaves it out, and the design asks only for "an appropriate preview/viewer").
- **It adds no route and no permission.** The photo is read through the read that already exists —
  `GET /jobs/:id/photos/:photoId/content` for evidence the backend holds, and the device's own file for
  a photo it still holds — so `docs/api/job-photos.md` and the permission catalogue are unchanged, and a
  session the API refuses is refused exactly as it is on a tile (`BR-007`).
- **The viewer's decode is bounded, and this is a recorded consequence.** A photo is decoded no larger
  than the widest screen Servora draws on (2560 px on its longest edge, `VIEWER_MAX_EDGE_PX`), because a
  full-size decode of a 4032 px capture is roughly 48 MB. The stored evidence is unchanged — this is how
  much of it is drawn, and the file the backend holds is still the photo that was uploaded (`D3c`).
- **The decode is not cached.** One full-size bitmap would evict the tray's worth of previews the
  existing cache holds, so the viewer re-reads when it is reopened. That cost is exactly the caching
  question this decision left open, now recorded as **D4b** rather than treated as an implementation
  detail of the viewer.

**D4b — decided (2026-09-15, product ownership).** Option **(a)** — an image-loading library is adopted
**behind the existing port**: **Coil 3**, with its memory and disk cache, and sampling and EXIF handling
left to the library. The API, the database, the permissions and the upload pipeline are unchanged.

Consequences recorded with the decision:

- **The port stays.** `data/jobs/JobPhotoImages` (four methods: local thumbnail, backend thumbnail, local
  full size, backend full size) is what the UI already talks to, so every tile, the review preview and the
  viewer keep their call sites and their test doubles (`JobPhotoImages.None`); what changes is the
  implementation behind it and the drawing state at the call sites. That is why this is a slice (Phase 4c)
  and not a rewrite.
- **The API read keeps its authorization behaviour.** The content route is read with the session token and
  a `401` is answered by renewing the session once through `SessionAuthenticator` — what the hand-rolled
  `download()` does today. The library's fetch of that route must be wired to the same path, or the swap
  would silently lose the renewal (`BR-007`, `BR-018`).
- **The cache is partitioned by session subject and cleared when the session ends.** Cached bytes are one
  subject's evidence; a cache keyed only by job id and photo id would let a second member signing in on the
  same device read the previous member's photos without the API answering — a read around `evidence.view`
  (`BR-007`). The local store already takes this position (`filesDir/job-photos/<subjectId>/`, whose own
  comment says one signed-in user's pending evidence is never read by another), and the cache follows it.
- **Server-derived thumbnails were not taken** (option (d)): `ADR-013`'s open question 3 stayed open that day
  and was **closed by `D15` ✓ on 2026-09-16 (no derived object in v1)**, so a
  tile may still download a full-resolution object — but **once** rather than on every cold start, because
  the client-side cache is what changes.
- **What the library replaces retires with its tests:** the viewer's ceiling rule
  (`jobPhotoFittingSampleSize`, `JobPhotoSamplingTest`) and the hand-rolled in-memory `LruCache`. The
  **pure orientation rule is not deleted** — the preparation pipeline needs exactly it for `D7b`, so its
  JVM coverage moves rather than disappearing.
- **The measurement `D4` named belongs here** and is reported by Phase 4c: bytes downloaded per photo per
  cold start, before and after. **Landed 2026-09-15**: the work is done and the expectation is recorded in
  Phase 4c's section, but reporting the numbers themselves needs a device and the API's log, so the
  measurement is handed to the product owner in `## Manual QA runbook — Phase 4c` (step 2).
- **It is a dependency, so it needs an ADR** (`dev.md` §4, `Project.md` §31): `docs/decisions/016-…`
  records the choice, the alternatives (an OkHttp-level cache; the platform decoder as it is today), what
  was deliberately not built, and that the library is maintained and compatible with the pinned toolchain
  (Kotlin, Compose BOM, OkHttp, `minSdk 26`).
- **A cache is not a retention policy.** The cached bytes are a cache: they may be evicted at any time and
  they decide nothing about offline evidence, which is `D5` — deferred on 2026-09-15 (option (d)) and
  answered on 2026-09-16 (Phase 5).

### D5 — May accepted evidence be read offline?

**Question.** Must a photo the backend already holds, and the Activity that projects it, be readable
without connectivity?

**Why it matters.** Today the Job read and the Activity read are **online-only** — the Job Details
screen has no offline working set (`android/.../data/jobs/JobDetailsRepository.kt`). Offline, a
technician sees only the pending tray: the gallery, every uploaded photo and the whole timeline
disappear. Photos captured offline are safe, but nothing already committed is visible. The offline
standard's §13 requires a new Android read to be checked against the existing stores and to record why
a feature stays online-only.

**Options.** (a) Stay online-only and record the reason in the standard (§13). (b) A metadata-only
working set for the Activity projection, or for the photo entries only, following the Customer Detail
precedent. (c) Photo **bytes** cached on the device as well, so the images themselves render offline.

**What it blocks.** Phase 5; and it decides whether Phase 4's caching work is a nice-to-have or the
foundation of offline image display.

**Agent recommendation (not a decision).** (b) first — it is the pattern tracker `025` already
established for a read, and it makes the timeline honest offline. (c) is a much larger decision
(device storage, retention and privacy) and should be taken only if field work genuinely needs the
images themselves.

**Decision (2026-09-15, product ownership).** **Deferred** — option (d): the question is revisited later
rather than answered now.

Consequences recorded with the deferral:

- **Phase 5 does not run**, and current behaviour stands: accepted evidence and the Activity are readable
  **online only**, so offline a technician still sees the pending tray and nothing already committed.
- **The gap stays a recorded risk** rather than becoming an assumption: the risk row "Job Details and
  Activity reads are online-only" remains, so the next session sees it instead of rediscovering it.
- **Nothing is decided about photo bytes on the device.** Option (c) — the images themselves readable
  offline — stays entirely open, including its retention rules. In particular, the Coil disk cache that
  `D4b = (a)` introduced (Phase 4c) is **not** an offline-evidence policy: it is evictable, has no
  retention rule, and must not be presented as answering this question.
- **No document changes beyond this tracker**: the offline standard already records these reads as
  online-only, which is still true, so the deferral adds nothing to it.

**Decision (2026-09-16, product owner instruction) — the deferral is lifted; `D5` is answered.** Accepted
evidence **is** readable offline, and photo bytes are cached **best-effort**:

- **Metadata is required offline.** The evidence a Job holds — its phase, its note, its time and the
  gallery/Activity projection around it — must be readable without connectivity, following the existing
  read rules: the working set, an answer marked as the last reported one, and a refusal never masked.
- **Bytes are best-effort, and never eagerly fetched.** A photo's bytes are cached locally when the device
  already holds them (a pending or just-uploaded photo) or when the technician explicitly opens or
  downloads that photo. Servora must **not** download every historical photo to make a screen complete.
- **A pending upload's bytes are guaranteed, not cache.** They are kept until the upload is confirmed and
  the evidence record is synchronized (`BR-014`, `BR-015`).
- **After acceptance the local copy is an evictable cache** managed by the app and the platform on a
  reasonable **size-based LRU** policy rather than a fixed retention period. Offline availability of a
  previously viewed photo is therefore best-effort and is never presented as a promise.
- **The Coil disk cache is not this policy.** The deferral's caution stands: an evictable image cache with
  no retention rule must not be presented as an answer to this question. Phase 5 states the cache policy
  the feature adopts.

Delivered by **Phase 5**. Documents changed with this answer:
`docs/architecture/offline-first-architecture.md` §9, §11 (items 1 and 4) and §12.

### D6 — Evidence lifecycle: retention, delete/edit, refused photos, orphan objects

**Question.** What may happen to evidence after it is recorded — and what happens to evidence nobody
can see?

**Why it matters.** Several currently-reachable situations have no decided behaviour:

- `BR-027` and `BR-033` leave retention, visibility and audit open; nothing deletes a photo.
- A **refused** upload (`403`/`404`/`400`/`413`/`422`/`409`) keeps its local file and queued row with
  **no retry or discard affordance** in the tray, so a permanently refused photo occupies the device
  indefinitely and the technician cannot act on it (tracker `027`, open question 2).
- `job_photos` cascades on Job deletion, but the stored **objects do not**: deleting a Job would orphan
  objects with no lifecycle rule. Deleting a Job is itself an open question (`BR-021`).
- Editing or deleting a recorded photo is undefined in every client, so evidence is append-only today
  (`BR-067`).

**Options.** Decide each separately, since they are separate product questions: (a) may a Manager
delete or edit a recorded photo; (b) may a technician remove a photo within a window; (c) what the tray
offers for a refused photo (retry, discard, keep); (d) whether a retention period exists and who may
see old evidence; (e) whether Job deletion removes evidence and its objects, or blocks on it.

**What it blocks.** Phase 6, and any product statement about evidence being removable.

**Agent recommendation (not a decision).** Decide (c) and (e) first: both are reachable in ordinary
operations today and both currently leave a mess (a trapped photo on a device; orphaned objects in the
bucket). (a), (b) and (d) can stay open for v1, consistent with evidence being append-only.

**Decision (2026-09-15, product ownership) — D6c only.** Option **(a)**: a photo the API permanently
refused may be **explicitly discarded** by the technician — the local file and the queued row go together,
with the refusal reason shown until it is.

Consequences recorded with the decision:

- **`BR-014` is satisfied on both sides.** Nothing was silently lost before (a refused photo is kept and
  its state is visible); nothing is silently trapped now either, because the removal is the technician's
  own explicit action.
- **It is a local action, not an API operation.** The photo was never accepted, so there is nothing to
  delete server-side and no new route or permission; it mirrors the removal that already exists for a photo
  that was never submitted (`PendingJobPhoto.isRemovable`).
- **A discarded refusal is finished, not pending.** The outbox must not replay it (`BR-031`), and its row
  and file go together so no file outlives its record and no row outlives its file (offline standard §9).
- **The rest of D6 stays open**, so Phase 6 narrows to what remains — `D6a`/`D6b` (delete or edit a
  recorded photo), `D6d` (retention) and `D6e` (Job deletion and orphaned objects). Its first work item,
  refused photos, is unblocked and is split out as **Phase 6a**.
- **Nothing is retained after a discard** beyond the app's own record of the refusal: the API never held
  those bytes, so there is no evidence to preserve.

**Decision (2026-09-16, product owner instruction) — `D6a`, `D6b`, `D6d` and `D6e` are answered; the
remaining D6 sub-questions are closed.**

- **`D6a`/`D6b` — accepted evidence is immutable, and there is no edit operation.** A technician must not
  overwrite a photo, replace its bytes, change its phase, or silently delete it once it has been accepted
  into the Activity/evidence history. Before submission it is not evidence, and the existing draft
  removal already applies. After submission, removal is an explicit **Remove evidence** operation available
  only to a Manager-level capability, **`evidence.photo.remove`**: it soft-removes the evidence from
  ordinary UI, records **who** removed it, **when** and **why**, and preserves the audit record; the stored
  object may be physically purged later under retention. **An edit-photo route is avoided entirely**, and a
  caption/note correction is recorded as another Activity/update rather than rewriting historical evidence.
- **`D6d` — retention and visibility.** Evidence belongs to the historical Job record. Archiving a Job must
  not delete its evidence, and archived Jobs and their evidence remain readable to users permitted to view
  archived Jobs. For v1, retention is **indefinite while the tenant/account exists**, rather than a
  regulatory period Servora cannot universally justify; configurable retention for tenants with their own
  compliance requirements is a later capability. Soft-removed evidence disappears from ordinary technician
  views but stays visible in an audit/history context to authorized managers.
- **`D6e` — no hard Job deletion in normal product flows.** Jobs are archived/canceled, not deleted, so
  evidence objects never become orphaned through a Job deletion. If a future administrative or GDPR-style
  hard purge exists, it must be a deliberate cascading purge — Job → Visits → evidence records → storage
  objects/derived objects — with object deletion handled asynchronously and idempotently. A storage object
  must never outlive its database ownership accidentally.

Delivered by **Phase 6b** (the remove operation and the visibility split; `D6d` and `D6e` need no v1 work
of their own beyond it). Documents changed: `Business Rules.md` (`BR-088` – `BR-090`, and `BR-027`'s
exceptions), `docs/api/job-photos.md` §1 and §7, `docs/decisions/015-…` (the new remove capability) and
`docs/decisions/013-…` (object lifetime).

Consequences recorded with the decisions:

- The permission catalogue gains **`evidence.photo.remove`** when Phase 6b implements it — a third per-kind
  capability beside `evidence.view` + `evidence.photo.add` (`ADR-015`). Its bilingual name and description
  (`BR-005`) and its default-role grant are recorded then; the default **Technician** role does not receive
  it.
- **No route rewrites evidence.** There is no edit route to design, and the remove route records a removal
  rather than mutating what was recorded.
- **`job_photos` stays append-only**, with the removal expressed as new history (`docs/api/job-photos.md`
  §7).
- A **structured removal-reason catalogue** is not defined by these answers. If one is wanted, it is a
  further product decision rather than something Phase 6b invents (`BR-042`) — Phase 6b records the reason
  as free text alongside actor and timestamp.

### D7 — Metadata beyond the phase

**Question.** Is `phase` plus a free-text note enough to describe a photo?

**Why it matters.** The photo row carries exactly: `phase` (`BEFORE_WORK` / `DURING_WORK` /
`AFTER_WORK`, a closed CHECK-constrained vocabulary), `note` (≤2000 characters), `captured_at` (device
instant, provenance only) and `recorded_at` (the backend's own, which Activity orders by). There is no
category, tag, Visit link, caption, or "who it is for". `phase` is **chosen by the technician** and
defaults to `DURING_WORK`; it is never derived from Visit timing and is not validated against the Visit
lifecycle. The chosen phase is remembered only in the screen's UI state, so it resets on process death.

**Options.** (a) Keep `phase` only, but persist the technician's last phase per session. (b) Add
controlled tags/categories (a shared vocabulary with codes and bilingual labels, `BR-041`). (c) Derive
or validate the phase from the Visit lifecycle (`BR-074`) instead of trusting the choice. (d) Add a
free-text caption separate from the note.

**What it blocks.** Phase 2/3 only if the review panel changes; Phase 7 for any reporting taxonomy.

**Agent recommendation (not a decision).** Keep `phase` as the only tag in v1 and fix the small
persistence gap; any taxonomy must be a shared-vocabulary decision rather than free text (`BR-041`), and
deriving a phase from Visit timing is a business rule that does not exist yet (`BR-042`).

**Noted 2026-09-15 (orientation fix).** Servora now *applies* the EXIF orientation tag when it presents a
photo: the display path turns the decoded pixels (`JobPhotoOrientation`, `JobPhotoImages`), while the
stored bytes keep whatever metadata their source had. That is deliberately **not** this decision — it
changes no stored data and no API answer, and it is what `BR-015` requires (the technician sees the photo
they took, not a rotated one). Two things here do belong to D7, and the fix leaves both to it: a photo
rewritten by the Phase 2 steps (the `D3c` resize or the `D3b` conversion) loses the orientation tag with
the rest of its metadata, so its stored pixels stay landscape and every client shows it sideways — which
**D7b** below answers; and any policy that reads, strips or rewrites EXIF must state whether orientation
counts as presentation metadata or as evidence, which stays open.

**Decision (2026-09-15, product ownership) — D7b only.** Option **(a)**: the preparation steps **turn the
pixels** of a photo they re-encode, so the file Servora stores is upright and needs no orientation tag.

Consequences recorded with the decision:

- **The stored evidence becomes self-describing.** What the `D3b` conversion or the `D3c` resize writes is
  upright, so the orientation tag stops being load-bearing for anything Servora records from now on —
  including whatever presents the photo later, on another device or in Angular.
- **The turn happens where the encode happens**, in `JobPhotoProcessing`, using the platform
  `android.media.ExifInterface` and the **existing pure `JobPhotoOrientation` rule** — no dependency is
  added, and the rule keeps its JVM coverage (`JobPhotoOrientationTest`). It is the same choice the display
  fix made, so the two paths must not drift: one shared read of the tag, not two.
- **It is not a re-display of already-recorded evidence.** Evidence already stored or already uploaded from
  a re-encoded photo keeps its sideways pixels and is **historical**; repairing it would be a backfill
  against the object store (option (c)) and was **not** taken. That is recorded so no later session
  "fixes" history by accident.
- **The API, the schema, the permissions and the uploaded-byte contract are unchanged** — a re-encoded
  photo is still a JPEG under the same limit; only its orientation is now correct.
- **Verification is partly device-bound**: the pixel turn needs `BitmapFactory`/`Bitmap`, so it runs behind
  the port and its on-device behaviour is the product owner's QA (`qa.md` §7.3), while the rule that decides
  the turn stays a JVM test.
- **The rest of D7 was unchanged by this answer** — categories/tags versus `phase` only, phase persistence
  across process death, and the EXIF/GPS policy (`BR-038`) stayed open here **and were answered on
  2026-09-16** (below).

**Landed (Phase 4b, 2026-09-15).** Both steps of `DefaultJobPhotoProcessing` turn what they decoded by the
orientation the **source** bytes declare, before scaling and encoding, so the JPEG they write is upright.
The read of the tag and the turn are **one shared leaf** (`data/jobs/JobPhotoExifOrientation.kt`) that the
display path (`JobPhotoImages`) calls too — `JobPhotoImages`' own private copy was deleted with it — so the
2026-09-15 display fix and what is stored cannot drift. It uses the **platform** `android.media.ExifInterface`
and the existing **pure** `JobPhotoOrientation` rule, so **no dependency was added** and the rule keeps its
JVM coverage (`JobPhotoOrientationTest`, 5 cases). The API, the schema, the permissions, the limit and the
uploaded-byte contract are unchanged. A turn the device cannot perform refuses the photo (`BR-042`), the
same answer the display path gives — the unturned pixels are never written as if they were correct. The
pixel turn itself needs `BitmapFactory`/`Bitmap`, so its on-device behaviour is **device QA**, handed over
as `## Manual QA runbook — Phase 4b`; and evidence already stored or uploaded from a re-encoded photo stays
**historical** (`D7b` option (c) was not taken).

**Decision (2026-09-16, product owner instruction) — the rest of D7 is answered.** `phase` stays the only
structured classification, it becomes draft state, and uploaded evidence carries no location metadata:

- **Classification.** `phase` — `BEFORE_WORK`, `DURING_WORK`, `AFTER_WORK` — remains the **only** structured
  photo classification in v1. No arbitrary tags or categories are introduced: captions and notes cover
  anything else without creating taxonomy complexity.
- **Phase persistence.** The currently selected phase is persisted across Activity-sheet recreation and
  process death **only as draft state for that in-progress update**. It must not become a global preference
  that leaks into the next Job or the next update.
- **EXIF/GPS.** Servora **strips GPS/location EXIF and other unnecessary metadata** from uploaded evidence by
  default, preserving only what the application explicitly needs — normalized orientation and dimensions,
  and Servora's own server-side capture/upload timestamps. **EXIF GPS is never used as Job-location
  evidence**; using location at all would need an explicit product feature with clear disclosure
  (`BR-038`, still open).

Delivered by **Phase 6c**. Documents changed: `Business Rules.md` (`BR-091`), `docs/api/job-photos.md` §7.
What this does **not** change: the display path already turns a photo by its orientation tag, and Phase 4b
already stores a re-encoded photo upright (`D7b`) — stripping is about which metadata travels, not about
how a photo is presented. One consequence to design carefully: the API accepts a photo whose bytes are
already a JPEG under the limit **without** re-encoding it, so a strip that only happens in a re-encoding
step would leave an untouched capture's metadata intact. Phase 6c must state where the strip runs (client
pipeline and/or API) and prove it covers both paths, including the one that never re-encodes.

### D8 — Audio and files

**Question.** Do audio recordings and file attachments join evidence in this roadmap, or stay out of v1?

**Why it matters.** Neither exists: no table, no content-type vocabulary beyond JPEG/PNG/WebP, no
playback, no activity kind. `BR-027` leaves their retention open, and neither client has a player.
Tracker `027` §"What remains for audio later" already lists what a decision would have to cover (whether
voice notes are evidence, length and size limits, transcription, a content-type vocabulary, playback in
both clients, and a shared Activity kind).

**D1 already settled the permission half:** the vocabulary is per-kind and revocable per member, so audio
uses the already-decided, **reserved** `evidence.audio.add` when it lands. What remains for D8 is the
**data model** (a kind on the evidence row versus a separate table), the content-type and size vocabulary,
and whether v1 includes audio at all.

**Options.** (a) Out of scope for v1, decided explicitly. (b) Plan an evidence-kind model now (one
evidence table with a `kind`), so photos are its first kind. (c) Audio only, later.

**What it blocks.** Nothing in Phases 1–6 if it is out of scope; it changes the data model if it is in.

**Agent recommendation (not a decision).** (a) — decide explicitly that v1 evidence is photos, so the
question closes instead of being re-opened by each slice; if evidence types come later, (b) is the
shape that avoids a second parallel table.

**Decision (2026-09-16, product owner instruction) — audio is in v1's scope; generic files are not.**

- **Audio: yes.** Voice/audio notes fit the technician workflow, so audio is implemented as another
  evidence/activity kind using the **same** storage abstraction, offline outbox, upload lifecycle,
  authorization and immutable-history principles as photos (`BR-088` – `BR-091`).
- **The reserved capability is activated when the feature is implemented**: `evidence.audio.add` is created
  by the audio slice, in the same per-kind shape as `evidence.view` + `evidence.photo.add` (`ADR-015`).
- **The dead affordance is not exposed.** The Add update sheet draws the audio kind only when a session
  actually holds the capability, and no session holds it today (`canAddAudio = false`), so nothing is
  offered before audio works — which is exactly what this decision requires. The seam
  `docs/tracker/033-android-add-update-hierarchy.md` built (kind, glyph, label, empty content composable and
  its JVM/Compose coverage) stays as the place audio lands. If product ownership wants the seam itself
  removed until then, that is a separate instruction, because removing it would also remove the tests that
  prove the sheet's hierarchy.
- **Generic files: no.** Arbitrary PDF/document/file attachments are left for a later phase: they bring MIME
  validation, previews, malware considerations, file-size rules and a substantially broader UX.

Delivered by **Phase 9**. Documents changed: `Business Rules.md` (`BR-091`) and `docs/decisions/015-…`
(the audio capability is created by that slice). What the audio slice must still decide for itself — and
record in an ADR rather than invent (`BR-042`): the evidence row model for a kind (a `kind` on one model
versus a parallel structure), the audio content-type and size vocabulary, length limits, playback in both
clients, and the shared Activity kind. One question this decision's shape raises rather than answers:
whether an audio update also offers *choose an existing recording* beside *record* (the photo kind's two
sources), and if so what capability covers reading a file — recorded by tracker `033`, still open.

### D9 — Viewer gestures and photo navigation

**Question.** Does the full-size viewer gain zoom, pan, or swiping between a Job's photos?

**Why it matters.** `D4` delivered a viewer without gestures, and the design spec asks only for "an
appropriate preview/viewer" and "useful thumbnails", so nothing decided asked for zoom. A photo is evidence
of a machine, a panel or a serial number, though, and a bounded decode on a phone screen is not always
enough to read the detail the photo was taken for. `Telephoto` — the Compose zoom library that pairs with
the Coil 3 stack D4b adopts — is what makes zoom real rather than a scale-up of a bitmap that was decoded
for the screen.

**Options.** (a) No gestures; `D4` stands. (b) Pinch-zoom and pan on the single photo (Telephoto). (c) Zoom
plus swipe between the Job's photos (a pager, so the viewer becomes a gallery). (d) Defer to the design
owner.

**What it blocks.** The viewer's gesture work (Phase 4d). It does not block D4b's caching work.

**Agent recommendation (not a decision).** (a) or (d): zoom is a product/design decision and Telephoto buys
nothing without it, so if it is wanted it should be decided deliberately rather than added by a library.

**Decision (2026-09-15, product ownership).** Option **(b)** — **pinch-zoom and pan** on the one photo it
was opened on, using Telephoto on top of the Coil 3 stack (`D4b = (a)`).

Consequences recorded with the decision:

- **It is a viewer, not a gallery.** Swiping between the Job's photos (option (c)) and a gallery-wide pager
  are **not** decided and stay unimplemented, so the gallery strip remains how photos are browsed and a tap
  still opens exactly the photo it was tapped on.
- **The viewer's decode changes shape, and the old bound retires with it.** Today the photo is decoded
  itself, bounded by `VIEWER_MAX_EDGE_PX` (2560 px), precisely because zooming was not decided; with zoom,
  the library's sub-sampled decode is what provides detail, so `jobPhotoFittingSampleSize` and its cases
  **retired in Phase 4c** (the surviving rule's cases moved to `jobPhotoSampleSize`), and `D4`'s "the decode
  is not cached" consequence is replaced by the D4b cache.
- **The viewer's identity stays resolution, not drawing.** `viewedJobPhoto` — which photo, from which
  source (the device's bytes while it holds them, the backend's evidence otherwise) — is unaffected, and
  `JobPhotoViewerTest` keeps its six cases; what changes is the state the drawing has (reading, ready,
  unavailable) and the gesture layer above it.
- **Gestures must not fight the surface they are on**: the close action, the platform back gesture and the
  photo's panning have to coexist, and the phase badge and the note stay legible with a zoomed photo. That
  belongs to Phase 4d and to `docs/design/android-design-system.md`, whose viewer row currently records
  that zoom is deliberately not drawn. **(Addressed by Phase 4d, 2026-09-15 — the row records the zoom now, and
  the coexistence is that the gestures belong to the photo's own area, so the badge, the note, the close
  action and the dialog's back gesture are outside them.)**
- **No API, database, permission or pipeline change.** The bytes read are the same bytes.
- **Verification is partly device-bound** (`qa.md` §6.2, §7.3): gesture behaviour is Compose-instrumented
  and is the product owner's device QA; the agent compiles the test sources and runs the device-free checks.

### D10 — Device media access and the in-app gallery surface

**Question.** Does Servora read the device's media library itself, or keep the system photo picker?

**Why it matters.** `D3 = (a)` chose the system picker deliberately: it needs **no** storage or media-read
permission, and Servora reads nothing outside what the technician selects. An in-app gallery browser
(`MediaStore`, a thumbnail grid and multi-select) would replace that posture with `READ_MEDIA_IMAGES` /
`READ_MEDIA_VISUAL_USER_SELECTED`, a rationale flow and a new browsing surface — and the design's §6 asks
for **Existing Photos** and an attachment row mixing camera and library, so the question is real. Two things
changed since `D3`: the picker contract already falls back to the document provider where the system picker
is absent, so compatibility is not a reason to move; and with `D4b = (a)` adopting an image-loading library,
an in-app grid would be cheaper to build than it was — which makes this a decision about permission and
surface, not about machinery.

**Options.** (a) The system picker stays the only library source. (b) An in-app `MediaStore` browser (grid,
multi-select) beside the picker, with the media-read permission. (c) (b) plus an in-app camera (CameraX),
i.e. the design's whole attachment row. (d) Defer.

**What it blocks.** Nothing currently scheduled; it would have been a Phase 4e slice if taken.

**Agent recommendation (not a decision).** (a): the picker covers the library source with no permission and
no new surface, and the design's camera request is a separate question (`D3`) that this decision does not
re-open.

**Decision (2026-09-15, product ownership).** Option **(a)** — `D3` stands: the **system photo picker**
remains the only library source, and the device's media library is not read.

Consequences recorded with the decision:

- **No media-read permission is requested**, so `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`, a
  rationale flow and partial-access handling are all out of scope, and the manifest keeps its current
  permission set.
- **No browsing surface is built.** The photo sources stay exactly the two `D3` decided — the device's
  camera and the picker — and the picker's own items remain the only bytes Servora may read.
- **The design's in-app camera request stays open** exactly as `D3` left it; this decision neither delivers
  nor closes it, and the design row for the sheet's sources is unchanged.
- **Coil 3 (`D4b`) is unaffected**: it draws app-private files and the API's content route, so the image
  library needs no media access to do its job.

## Phase 0 decision log

Filled in by product ownership. An answer here is what unblocks the phase named in `What it blocks`.

| ID | Decision | Answer | Decided by | Date | Recorded in |
| --- | --- | --- | --- | --- | --- |
| D1 | Evidence capability set (codes, bilingual names, default roles) | **Answered — a dedicated evidence capability** (option a), revocable per member, structured by kind. Replaces `JOB_UPDATE` on the write and `customers.view` on the read; the default Technician role holds it, which extends `BR-009` | product ownership | 2026-09-15 | this tracker (D1); **implemented in Phase 1** — `docs/decisions/015-evidence-capabilities.md` and `docs/api/job-photos.md` §2. The `Business Rules.md` `BR-008`/`BR-009` edit is product ownership's and is **still outstanding** (`BR-040`) |
| D1b | Exact catalogue shape (kind-agnostic `add`, or one `add` per kind) and the default role grants | **Answered — per-kind adds:** `evidence.view` + `evidence.photo.add` now (Manager and Technician), `evidence.audio.add` reserved for when audio lands. Per-kind so one kind can be withdrawn from a member | product ownership | 2026-09-15 | this tracker (D1b); implemented in Phase 1 |
| D2 | Evidence scope: Job / Visit / optional Visit link | **Answered — (a): Job-level, as implemented.** `job_photos.job_id` stays the scope, so a Job may carry evidence with no Visit (`BR-051`); the contradiction was in the domain document, which is corrected with this answer. No schema, API or Android change, and an optional Visit link stays available if a requirement ever names it | product ownership | 2026-09-15 | this tracker (D2); `docs/domain/job-visit-domain-model.md` §469 corrected |
| D3 | Photo sources (picker, multi-select, in-app camera) | **Answered — (a):** the existing camera **plus a multi-select gallery picker** (the system photo picker). No in-app camera, no `CAMERA` permission, no new dependency; each selected photo becomes its own draft. The design's in-app camera request stays open | product ownership | 2026-09-15 | this tracker (D3); implemented in Phase 3 |
| D3b | What a picked photo whose bytes are not JPEG/PNG/WebP (e.g. HEIC) does | **Answered — converted on device to JPEG** before the draft is written; the API, its sniffer and the schema stay unchanged. A failed conversion is an explicit localized refusal with no draft recorded | product ownership | 2026-09-15 | this tracker (D3b); implemented in Phase 2 |
| D3c | What an oversized photo (over the API's 15 MiB limit) does | **Answered — downscaled/re-compressed on device to fit**, so every photo can upload; the stored evidence is the resized version and the original is not kept. Target edge/quality is an implementation detail Phase 2 must record | product ownership | 2026-09-15 | this tracker (D3c); implemented in Phase 2 |
| D4 | Viewer and thumbnail strategy | **Answered — (a): a full-size in-app viewer only.** Tapping a photo (in the Job Activity gallery or in the capture tray) opens the photo in an in-app viewer; the caching/thumbnail items — an HTTP cache, `Cache-Control` on the answer, a disk-backed image cache, and server-derived thumbnails — are **not** built and stay recorded as undecided (`D4b`). The viewer decodes the photo itself, bounded by the screen it is drawn on, from the bytes already read (`JobPhotoImages`) | product ownership | 2026-09-15 | this tracker (D4); implemented in Phase 4. No API change: `docs/api/job-photos.md` is untouched, and `ADR-013`'s open question 3 stays open |
| D4b | Preview and caching strategy (HTTP cache, disk cache, server-derived thumbnails) | **Answered — (a): Coil 3 behind the existing `JobPhotoImages` port**, with its memory and disk cache and its sampling/EXIF handling; the API read keeps the session-renewal path, and the cache is keyed and cleared **per session subject**. Server-derived thumbnails were **not** taken (`ADR-013` open question 3 stays open), and there is no API, database, permission or pipeline change | product ownership | 2026-09-15 | this tracker (D4b); `docs/decisions/016-…`; implemented by **Phase 4c**. Retires the hand-rolled decode/sampling rules and their tests; the risk row "Every gallery tile downloads full-resolution bytes" closes as a per-cold-start cost and stays as a per-object download |
| D5 | Offline visibility of accepted evidence | **Answered (2026-09-16) — accepted evidence is readable offline: metadata is required, bytes are best-effort.** Bytes are cached when the device already holds them or the technician opens/downloads them, never by downloading every historical photo; a pending upload's bytes are **guaranteed** until synchronization confirms the evidence, and after that the local copy is an **evictable, size-based LRU cache** rather than a fixed retention period. Offline availability of a previously viewed photo is best-effort and is never a promise | product owner instruction | 2026-09-16 | this tracker (D5); **delivered by Phase 5**; `docs/architecture/offline-first-architecture.md` §9, §11, §12 |
| D6 | Evidence lifecycle (delete/edit, refused photos, Job deletion, retention) | **Answered in full (2026-09-16).** `D6c`: a refused photo may be explicitly discarded — local file and queued row together, with the refusal reason shown. `D6a`/`D6b`: accepted evidence is **immutable** — no overwrite, no byte replacement, no phase change, no silent delete; removal is an explicit, audited, **Manager-level** `evidence.photo.remove` that soft-removes it while the audit record is preserved, and **no edit-photo route exists** (a note correction is new Activity). `D6d`: evidence belongs to the historical Job record — archiving a Job never deletes it, archived Jobs and their evidence stay readable to those permitted to view archived Jobs, and v1 retention is **indefinite for the life of the tenant**. `D6e`: normal flows never hard-delete a Job, so evidence is never orphaned; any future administrative purge is a deliberate cascade with asynchronous, idempotent object deletion | product owner instruction | 2026-09-16 | this tracker (D6); `BR-088` – `BR-090`; `docs/api/job-photos.md` §1, §7; `docs/decisions/015-…`, `013-…`. **Phase 6a** (D6c) stays startable; **Phase 6b** carries the rest; Phase 6 is closed |
| D7 | Metadata beyond the phase | **Answered in full.** `D7b` (2026-09-15): the preparation steps turn the pixels of a photo they re-encode, so stored evidence is upright and needs no orientation tag; evidence already stored or uploaded stays **historical and is not repaired** (Phase 4b, landed). **2026-09-16:** `phase` stays the **only** structured classification (no tags or categories), the selected phase is **draft state for the in-progress update** only and never a global preference, and **GPS/location EXIF plus other unneeded metadata are stripped from uploaded evidence by default**, keeping only normalized orientation/dimensions and Servora's own timestamps; EXIF GPS is never used as Job-location evidence | product owner instruction | 2026-09-16 | this tracker (D7); `BR-091`; `docs/api/job-photos.md` §7. `D7b` was **implemented in Phase 4b (2026-09-15)**, with no API, schema or uploaded-byte change; the rest is **Phase 6c** |
| D8 | Audio and files in v1 | **Answered — audio: yes; generic files: no.** Audio notes become an evidence kind of their own on the same storage, outbox, upload, authorization and immutable-history rules, and `evidence.audio.add` is activated when that slice is implemented; arbitrary file attachments stay out of v1 (MIME validation, previews, malware and size rules, broader UX). A kind's affordance is not exposed before the kind actually works, which the sheet already satisfies (`canAddAudio = false`) | product owner instruction | 2026-09-16 | this tracker (D8); `BR-091`; `docs/decisions/015-…`; **delivered by Phase 9** |
| D9 | Viewer gestures and photo navigation | **Answered — (b): pinch-zoom and pan** on the one photo, Telephoto on top of the Coil 3 stack. Swiping between photos and a gallery-wide pager are **not** decided | product ownership | 2026-09-15 | this tracker (D9); `docs/decisions/016-…`; implemented by **Phase 4d**; the viewer row of `docs/design/android-design-system.md` changes with it |
| D10 | Device media access and the in-app gallery surface | **Answered — (a): the system photo picker stays the only library source.** No media-read permission, no in-app `MediaStore` grid and no in-app camera; the design's camera request stays open exactly as `D3` left it | product ownership | 2026-09-15 | this tracker (D10); no manifest, permission or design change |
| D11 | Swiping between photos in the viewer | **Answered — the viewer pages through the Job's photos in the order Job Details presents them**: evidence the backend holds first (as the Activity reports it), then the photos this device still holds (oldest first), a photo both records hold listed once. A swipe pages **only while the photo is at fit**; a zoomed photo pans instead. This answers the item `D4`/`D9` left undecided, and it is scoped to the Job on screen — a cross-Job gallery stays open | product owner instruction | 2026-09-15 | this tracker (D11); `docs/decisions/017-…`; **implemented by Phase 4e**; the viewer row of `docs/design/android-design-system.md` changes with it |
| D12 | Saving a photo on the device | **Answered — (a): the photo is written into the device's own gallery**, in a `Servora` album beside the platform's Pictures folders. API 29+ needs no permission (`IS_PENDING` until its bytes are all there); API 26–28 declares `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"` and asks for it when a save needs it, retrying the same save once granted and reporting a declined one. `D10` is unchanged: this is a **write** with a two-release ceiling, and no media-read permission is added | product owner instruction | 2026-09-15 | this tracker (D12); `docs/decisions/017-…`; **implemented by Phase 4e**; `AndroidManifest.xml` and its permission change with it |
| D13 | Sharing a photo with another application | **Answered — the platform's own share sheet** (`ACTION_SEND` with the photo's own MIME type), so WhatsApp or anything else on the device is the user's choice and Servora names no provider. The photo only: no note text, no Job or customer detail. The bytes are staged in one app-private cache directory exposed through the app's existing `FileProvider` (one added `cache-path`), and a device that accepts no image is reported | product owner instruction | 2026-09-15 | this tracker (D13); `docs/decisions/017-…`; **implemented by Phase 4e**; `res/xml/file_paths.xml` changes with it |
| D14 | The evidence entry point, and the tray's **Take another** action | **Answered — the Add update sheet stays the canonical entry point for choosing the evidence kind** (and therefore for switching Photo → Audio/Text), while inside the photo flow **Take another launches the same camera target again** rather than bouncing the technician back through the picker: the intent is unambiguous once a photo is already being added. This is the behaviour the tray already has, so no code, copy or contract changes | product owner instruction | 2026-09-16 | this tracker (D14); `docs/design/android-design-system.md` (the update action's row); the design-owner risk row is closed |
| D15 | The object store's read model and derived objects (`ADR-013` open questions 3 and 4) | **Answered — no derived objects in v1, and reads move to short-lived presigned GET URLs.** The normalized original is stored and clients render from it; a thumbnail pipeline is revisited only when bandwidth or gallery performance proves it necessary. For reads the API issues **short-lived presigned GET URLs** after authorization, with the bucket private, no client credentials and no second configured storage endpoint; uploads stay on the API port. **A recorded constraint must be resolved by that phase:** `ADR-013` D7 records that a signature is bound to its `Host`, that presigning needs `S3_PUBLIC_ENDPOINT` (with a second `adb reverse` locally) and that a plain-HTTP URL is debug-build-only — and a presigned URL is served from the provider's own host, which is in tension with clients not assuming a vendor | product owner instruction | 2026-09-16 | this tracker (D15); `docs/decisions/013-…` (its 2026-09-16 update); `docs/api/job-photos.md` §4; **delivered by Phase 8**, whose design states the resolution first |

Every answer must name the document it changes: `Business Rules.md` (`BR-008`, `BR-009`, `BR-027`),
`docs/api/job-photos.md`, `docs/architecture/offline-first-architecture.md` §12/§13, an ADR in
`docs/decisions/`, and this tracker (`BR-040`).

## Phase 1 — Evidence capability set (API)

**Goal.** Authorize adding and reading evidence on permissions that exist for that purpose, so a
technician who may record field evidence is not refused by an unrelated Manager capability (`BR-006`,
`BR-009`).

**Depends on.** D1 ✓ and D1b ✓ — both answered, so this phase is **writable**. It implements the decided
per-kind catalogue: `evidence.view` + `evidence.photo.add`, held by Manager and Technician, replacing the
interim `JOB_UPDATE` (write) and `customers.view` (read) guards. `evidence.audio.add` stays reserved and
is **not** created here (`BR-042`, D8).

**Layers.** API, database (permission and role-grant rows), documentation.

**Work.**

1. Add a typed `EVIDENCE_PERMISSIONS` catalogue to `api/src/auth/permissions.ts`, following the
   `PROPERTY_PERMISSIONS` pattern rather than inline strings: `VIEW: 'evidence.view'` and
   `PHOTO_ADD: 'evidence.photo.add'`. `evidence.audio.add` is documented there as the reserved extension
   point but is **not** added to the catalogue until audio exists (D1b, `BR-042`).
2. A **new** migration under `api/drizzle/migrations/` adding the two permission rows with bilingual
   names and descriptions (`BR-005`) and granting both to the **Manager** and **Technician** default
   roles. `0004_woozy_spitfire.sql` is where the current catalogue and the role grants live;
   `0005_customer_permissions.sql` and `0007_property_lifecycle_and_permissions.sql` are the precedents
   for adding a capability set. An applied migration is never modified (`dev.md` §6).
3. Point the two Job photo routes in `api/src/jobs/jobs.controller.ts` at the new capabilities:
   `POST :id/photos` requires `evidence.photo.add`, and `GET :id/photos/:photoId/content` requires
   `evidence.view` — so the write stops depending on `JOB_UPDATE` and the read stops depending on
   `customers.view`.
4. `api/src/database/run-development-seed.ts` if the local QA accounts must demonstrate the new grants.
5. Documentation: `docs/api/job-photos.md` §2 and its open question 1 (the open question closes);
   `docs/api/job-actions.md` §2 and `docs/api/job-details.md` §2 keep their own `jobs.*` note, because
   this phase decides the evidence capability only. `Business Rules.md` `BR-008`/`BR-009` is product
   ownership's edit (`BR-040`) — the implementation follows the recorded decision, it does not write it.

**Verification.** API unit tests for the catalogue; authorization e2e for both routes covering
unauthenticated `401`, a caller without the capability `403` **and nothing stored**, and a caller with it
succeeding — `api/test/job-photos.e2e-spec.ts` already has that shape and
`api/test/customers-authorization.e2e-spec.ts` is the pattern. Two cases are new because the capability is
now **per kind**: a default **Technician** must be able to add a photo and read one, and a caller holding
`evidence.photo.add` without `evidence.view` must still be refused the read. Commands: `make api-test`,
`make api-test-e2e` (needs `make up`), `make api-lint`, `make api-build`.

**Not in this phase.** Any Android change; any new route; any change to how evidence is stored.

## Phase 2 — Content-type-aware local capture pipeline

**Goal.** Stop the device pipeline assuming JPEG, so a PNG or WebP the technician selects is uploaded as
what it is instead of being refused by the API's declared-versus-sniffed check.

**Depends on.** D3 ✓, **D3b ✓** (a non-JPEG pick is converted on device) and **D3c ✓** (an oversized photo
is resized to fit) — **this phase is now fully writable**. Without a second source it would have no reason
to exist, because the camera always writes a small JPEG.

**Layers.** Android.

**Work.** The API already sniffs bytes and refuses a disagreement, so the client only has to stop
mis-declaring the type and ship bytes the API accepts (`api/src/jobs/job-photo.dto.ts`). The pipeline
order D3c fixes is: **read the source bytes → type step (D3b) → size step (D3c) → app-private file →
draft row**, all before the draft exists so the file, the recorded type and the row agree.

1. `android/.../data/jobs/JobPhotoFiles.kt` — the `PHOTO_EXTENSION = "jpg"` constant and `fileFor()`
   must take the type of the source file rather than hardcoding it.
2. `android/.../data/jobs/JobPhotoSession.kt` — `recordCapture()` records
   `mimeType = JPEG_CONTENT_TYPE` for every photo; it must record the source's type, and a picked item
   must be copied into app-private storage **before** the draft row is written, so the file and the row
   stay one identity (offline standard §9).
3. `android/.../data/jobs/JobPhotoUploadHandler.kt` — the multipart part type and the file name come from
   the payload (`JobPhotoOperationPayload.mimeType` / `fileName` already exist) instead of the `photo.jpg`
   default in `JobPhotoPayloads`.
4. `android/.../data/jobs/JobPhotoImages.kt` needs no change: it decodes whatever bytes it is given.
5. **The D3b conversion (decided).** A picked item whose bytes are not JPEG/PNG/WebP is decoded and
   re-encoded to JPEG **before the draft row is written**, so the file, the recorded `mimeType` and the
   draft agree. It belongs beside `JobPhotoFiles` / `JobPhotoImages` in `android/.../data/jobs/` behind its
   own small interface, because `BitmapFactory`/`ImageDecoder` need the Android runtime and a port is what
   keeps the rule JVM-testable (`FakeJobPhotoFiles` is the existing pattern for that). It runs off the main
   thread and per item, and a conversion that fails is a localized refusal that records **nothing**
   (`BR-014`).
6. **The D3c size step (decided).** After the type step, a photo whose bytes exceed `MAX_JOB_PHOTO_BYTES`
   is downscaled/re-compressed to fit. It is the same device-side port as the D3b step (so both are one
   small image-processing port with two rules, both JVM-testable behind a fake), it runs off the main
   thread per item, and a photo that cannot be fitted is a localized refusal that records **nothing**
   (`BR-014`). The client mirrors the API's 15 MiB as its own constant pointing at the API's value and
   `docs/api/job-photos.md` §3 — it does **not** invent a stricter limit. Phase 2's tracker entry must
   record the target edge/quality it chose and the measured result, because the stored evidence is the
   resized version and that choice is part of the evidence record.

**Verification.** JVM tests: the upload handler sends the recorded type, the session records the source's
type, and the API's mismatch rule is exercised in `api/test/job-photos.e2e-spec.ts` (already covered — a
declared type that disagrees with the bytes is a refusal, not corruption). Commands: `make android-test`,
`make android-lint`, `make android-build`, plus `./gradlew assembleDebugAndroidTest` so device-test
sources still compile (`qa.md` §7.3 — the agent runs no `adb`).

**Not in this phase.** Any UI; the picker itself (Phase 3).

## Phase 3 — Photo sources in the Add update sheet

**Goal.** "Add photo" offers the two decided sources, with multi-select for existing photos, feeding the
pipeline that already exists.

**Depends on.** D3 ✓, **D3b ✓**, **D3c ✓**; Phase 1 (the caller must hold `evidence.photo.add`); Phase 2
(a picked photo may not be a JPEG and may be oversized — this phase consumes the pipeline it builds, and
its conversion/resize step must be refactored to the decided type+size pipeline); D7 only if the review
panel's fields change.

**Layers.** Android, localization, design documentation.

**Status: LANDED (2026-09-15).** What was built, where it deviated from the plan above, and its
verification are recorded in `## Phase log`; the product-owner runbook is
`## Manual QA runbook — Phase 3`. Two things the work items did not settle were decided during
implementation and are recorded as such:

- **A picked photo's phase** (the work items are silent; the API requires one). Phase 2's
  `recordPickedPhoto` recorded none, which `JobPhotoUploadHandler` refuses as `INVALID` — so every
  picked photo would have been trapped in the tray, uploadable never and removable not at all once
  queued. Phase 3 reads D3's own option text — "reusing the existing **draft → review → tray →
  outbox** pipeline" — together with D7's statement that "`phase` is chosen by the technician": each
  picked photo is recorded with the **phase in effect**, exactly as a capture's draft is, and opens the
  review panel the camera path already owns, where the technician states their own phase and note.
  Nothing is attributed silently, the API and its schema are unchanged, and a photo a technician never
  finishes reviewing is still one the API can accept (`BR-014`, `BR-027`, `BR-067`). **Product
  ownership confirmed this reading on 2026-09-15** rather than moving the review or the schema.
- **Naming a skipped photo.** A pick hands over several items, so a photo that could not be taken is
  reported by its place in the pick ("Photo 2 of 5: …"), and each skipped photo gets its own report
  rather than the last one overwriting the others (`D3`).

**Work.**

1. `android/.../ui/jobs/JobUpdateSheet.kt` — state the two source kinds the decision approves: **Take
   photo** (camera, as today) and **Choose photos** (picker), following the sheet's existing "state what
   kind of update it is" decision (`028`).
2. A new picker flow beside `android/.../ui/jobs/JobPhotoCapture.kt`, using
   `ActivityResultContracts.PickVisualMedia` (with multi-select) or `GetMultipleContents` — no storage
   permission for the system picker — handing **each** picked item through the same allocate-id-and-file
   path as the camera, so every photo has its own id and app-private file before anything is recorded.
3. **Multi-select is per item, not per action** (D3): picking five photos creates five independent drafts,
   five tray tiles and five outbox operations. One item failing to copy, being refused for its type (D3b)
   or being refused for its size (D3c) must not stop the others, and the technician must be told which
   item was skipped and why.
4. `android/.../ui/jobs/JobDetailsViewModel.kt` — one entry point per source, with the same
   not-signed-in and capture-failed reporting the camera path already has (`JobPhotoFailure`), plus a
   reason for an item that could not be taken.
5. `android/.../ui/navigation/ServoraNavHost.kt` — wiring; EN/FR strings in
   `android/app/src/main/res/values{,-fr}/strings.xml`; the sheet described in
   `docs/design/android-design-system.md`; tracker `028`'s open question 3 closes with a pointer here.
6. The **in-app camera** is explicitly **not** in this phase (D3 declined it). It stays an open question;
   if it is ever decided, it is its own work item with its own dependency justification and
   permission/manifest change (`dev.md` §4).
7. **The screen's permission gate.** Phase 1 changed the API only, so the client still gates the update
   action on `JOB_UPDATE` (`android/.../ui/customers/CustomerPermissionsUiState.kt`): a default
   Technician holds `evidence.photo.add` but sees no action, and the camera path that already exists is
   unreachable to them. The gate has to read the evidence capability (and the note path keep its own)
   for Phase 1's decision to be visible on the device (`BR-011`, `docs/decisions/015-evidence-capabilities.md`).

**Verification.** JVM ViewModel tests (multi-select producing one draft per item, one item failing to
copy, an item refused for its type or size, cancel, no session), the existing handler tests unchanged and
green, Compose tests for the source sheet where practical, and the `qa.md` §7.3 handoff:
`make android-test` / `make android-lint` / `make android-build`,
`./gradlew assembleDebugAndroidTest`, and a manual runbook for the product owner (pick one photo, pick
several, pick while offline, cancel).

**Not in this phase.** Audio and files (D8); the viewer (Phase 4); offline reads (Phase 5).

## Phase 4 — Full-size viewer and a thumbnail strategy

**Status: LANDED (2026-09-15) — the viewer. The thumbnail/caching half is `D4b` ✓ and landed as Phase 4c.**

**Goal.** A tap on a photo shows the photo, and previews stop re-downloading full-resolution bytes on
every cold start.

**Depends on.** **D4 ✓ = (a)**, so **only the viewer was in scope**: work items 3 and 4 were not built,
and the caching/thumbnail question they carried is recorded as **D4b** in the decision log rather than
assumed. `BR-042`.

**Layers.** Android, localization, tests, docs. **No API change and no database change**: the photo is
read through the route that already exists, so `docs/api/job-photos.md`, `ADR-013` and the permission
catalogue are untouched (Phase 1 already gave the read its `evidence.view`).

**What landed.**

1. `android/.../ui/jobs/JobPhotoViewer.kt` (new) — the viewer: a full-screen `Dialog`
   (`usePlatformDefaultWidth = false`) over the screen, drawn on the theme's own `surface`/`onSurface`
   so it introduces no colour token. It shows the photo (`ContentScale.Fit`), the phase badge, a close
   action (`ic_close`, its own 48 dp target and content description) and the technician's whole note;
   the platform's back gesture closes it. It holds **only the photo id**: the phase, the note and the
   bytes are resolved from the records that already state them (`viewedJobPhoto`), so the viewer never
   becomes a second copy of the evidence and a photo neither the device nor the Activity holds any more
   shows nothing (`BR-001`, `BR-042`). A photo that cannot be read or decoded is **reported** rather
   than replaced by another picture.
2. `android/.../ui/jobs/JobPhotoComponents.kt` — `JobPhotoGalleryTile` is a tap target whose whole
   column (photo, badge and note snippet) opens the photo, with a localized `onClickLabel` naming the
   action for a screen reader (`BR-012`, `BR-028`).
3. `android/.../ui/jobs/JobPhotoTray.kt` — the tray tile is a tap target too, so a photo the backend
   has not accepted yet can be inspected on the device that holds it (`§9`); the X that removes a
   never-submitted photo stays its own control.
4. `android/.../data/jobs/JobPhotoImages.kt` — a **full-size path beside the 512 px thumbnail**
   (`localFullSize`, `jobPhotoFullSize`), reading the same two sources the tile reads (the device's own
   file, or the API's content route) and answering `null` for a photo it cannot decode (`BR-042`). It is
   deliberately **not** cached, and its decode is bounded by `VIEWER_MAX_EDGE_PX` (2560 px).
5. `android/.../data/jobs/JobPhotoSampling.kt` — `jobPhotoFittingSampleSize`, the *ceiling* rule the
   viewer needs (the existing `jobPhotoSampleSize` is a *floor* rule for the encode pipeline). It takes
   plain bounds so the arithmetic is verifiable on the JVM (`qa.md` §6.1).
6. `values/strings.xml`, `values-fr/strings.xml` — `job_photo_viewer_open`, `job_photo_viewer_close`,
   `job_photo_viewer_unavailable` in both languages (`BR-028`).
7. `android/.../ui/jobs/JobDetailsScreen.kt`, `JobActivitySection.kt` — the screen holds the viewed
   photo's id in `rememberSaveable`, so a rotation does not lose the viewer, and draws the viewer over
   everything else.

**Verification (executed).** `make android-test` — PASS (JVM), including the new
`ui/jobs/JobPhotoViewerTest` (6 cases: device bytes, backend evidence, the device's copy preferred, a
photo nothing holds, an unreadable phase code, a blank note) and `data/jobs/JobPhotoSamplingTest`
(5 cases: fits as-is, halved to fit, either orientation, never over the ceiling, no declared bounds).
`make android-lint` — PASS. `make android-build` — PASS. `./gradlew assembleDebugAndroidTest` — PASS, so
the new Compose cases in `ui/jobs/JobDetailsScreenTest` compile (opening an accepted photo and closing
it, opening a pending photo from the device's bytes, and reporting a photo it cannot show); they are
**NOT RUN here — device QA is the product owner's** (`qa.md` §7.3). The request-per-photo measurement
`D4` named belongs to the caching change, which this decision did not take, so it is not reported here.

**Offline (the offline standard's §13).** The viewer is an **online-only feature**, like the tiles it opens
from: it reads the photo through the route that already exists and that read has no `WorkingSetStore`
projection. Whether accepted evidence must be readable without connectivity is **D5**, **deferred on
2026-09-15** and **answered on 2026-09-16**: metadata becomes readable offline and the bytes are cached
best-effort, which is **Phase 5**'s work (`BR-032`) — until Phase 5 lands, the behaviour described here
stands. The viewer queues nothing — a read never does — and it keeps nothing of its own once it
closes. `docs/architecture/offline-first-architecture.md` §12 records the photo reads in its online-only
list for exactly this reason.

**Not in this phase.** Zoom, gestures, a gallery-wide pager, video; the request cache, `Cache-Control`
and server-derived thumbnails (**D4b** — answered and carried by Phase 4c, landed, and **D15** now decides
there are no derived objects in v1); offline evidence (**D5** — answered 2026-09-16, Phase 5); editing or
deleting a photo (**D6** — answered 2026-09-16: no edit route, and the removal is Phase 6b).

## Phase 4b — Re-encoded evidence is stored upright (D7b)

**Status: LANDED (2026-09-15).**

**Goal.** A photo the preparation steps rewrite is stored the way it must be presented, so the evidence
itself is upright instead of depending on a tag the rewrite discards.

**Depends on.** **D7b ✓ = (a)** — answered, so this phase is writable. It fixes what Servora **stores**;
the display-path fix of 2026-09-15 already made what is **drawn** correct, and the two must not drift.

**Layers.** Android, tests, documentation. **No API, database, permission or contract change**: what a
re-encoded photo produces is still a JPEG under the same limit, and `docs/api/job-photos.md` is untouched.

**What landed.**

1. `android/.../data/jobs/JobPhotoExifOrientation.kt` (**new**) — the **one** read of the EXIF
   `Orientation` tag and the **one** turn of decoded pixels.
   `jobPhotoExifOrientation(bytes)` answers the orientation the **source** bytes declare, and `Normal` when
   the file states none, so a photo is never guessed into a rotation (`BR-042`); the read is wrapped, so a
   malformed tag is a property of the file rather than a failure of the caller. `Bitmap.turnedBy(orientation)`
   copies nothing when the photo is already upright (the common case), releases the unturned original when
   it is not, and answers `null` when the device cannot turn it. It sits **beside** the rule rather than
   inside it because `JobPhotoOrientation` is deliberately free of the Android runtime so the JVM can check
   it (`qa.md` §6.1), while the read needs the platform `android.media.ExifInterface` and the turn needs
   `Bitmap`/`Matrix`. **No dependency was added** — the platform reader is the one the display fix chose.
2. `android/.../data/jobs/JobPhotoProcessing.kt` — both steps turn what they decoded, by the orientation the
   **source** bytes declare, **before** scaling and encoding, through one private
   `decodeUpright(bytes, maxEdgePx, orientation)`. `fitToUploadLimit` reads the tag once for the whole
   target loop, because the tag describes the source bytes every target decodes again. A turn the device
   cannot perform answers `null` from the step, so the photo is refused with nothing recorded — the same
   answer the display path gives — rather than evidence stored as pixels known to be wrong
   (`BR-014`, `BR-042`).
3. `android/.../data/jobs/JobPhotoImages.kt` — its private `jobPhotoExifOrientation` and `Bitmap.oriented`
   were **deleted**; the display decode calls the shared leaf. Presentation is unchanged (same sample size,
   same "cannot be presented" answer), and the display path and the stored path are now one implementation,
   which is what stops them drifting.
4. `FIT_TARGETS` and Phase 2's measurement are untouched: a turn never changes which edge is the longest,
   so what a resized photo is stored as changed only in orientation — same rung, same longest edge, same
   quality, same JPEG under the same limit.
5. Documentation: this tracker (the `Preparation` and `Thumbnails` rows, the `D3b`/`D3c`/`D7b` consequence
   notes, the risk row, `## Current phase`, the phases table and this entry) and `README.md`.
   **`docs/design/android-design-system.md` was checked and needed no change**: its photo rows describe how
   a tile and the viewer are **drawn**, not how a re-encoded photo is presented or stored, so it states
   nothing this phase made untrue. Nothing else documents the stored-orientation behaviour, and no API,
   domain, schema or permission document is affected.

**Verification (executed).**

```
Android
- Unit (JVM):              PASS  ./gradlew testDebugUnitTest --rerun → 430 tests, 0 failures, 0 errors, 0 skipped
                                 (includes JobPhotoOrientationTest, 5 cases — the turn the pipeline applies)
- Lint:                    PASS  ./gradlew lintDebug (abortOnError = true)
- Debug build:             PASS  ./gradlew assembleDebug → app-debug.apk
- Device-test sources:     PASS  ./gradlew assembleDebugAndroidTest (compiles; every case in it NEEDS A DEVICE)
- Physical device:         NOT RUN — device QA is the product owner's (qa.md §7.3)
                                 runbook: ## Manual QA runbook — Phase 4b
API / database:            Not run — nothing under api/ was changed (no contract, schema, permission or scope change)
```

The whole battery was run twice, the second time on the final tree after the last (comment-only) edit, so
the reported result is the tree that would be committed. **No `adb` and no device or emulator command was
run** (`qa.md` §7.3). The one behaviour this phase changes — turning decoded pixels — needs
`BitmapFactory`/`Bitmap` and therefore cannot be exercised by a JVM test: no test dependency is added for
it (that would be a Robolectric-style dependency the project does not use), so it stays **device QA** and is
handed over as the Phase 4b runbook. `JobPhotoProcessing` stays behind its port, so `FakeJobPhotoProcessing`
and `PhotoCollaborators` still test the session's decisions about *when* each step runs, and that coverage
is unchanged and passing.

**Offline (the offline standard's §13).** This phase adds **no read and no mutation**: it changes what two
steps on the existing path *write*, for the operation `job.photo.add` that already runs through the outbox.
The queued operation, its idempotency key (the photo id), the replay engine and the conflict model are
untouched, and a re-prepared photo is still one JPEG under the API's limit with the same declared type — so
there is no new local state, nothing is stored twice, and no synchronization question is opened (`§5`,
`§6`, `§13`). `JobPhotoSession`'s decisions (which step runs when, and the refusal when one cannot deliver)
are unchanged, which is why its JVM tests still describe this change correctly.

**Not in this phase.** Repairing evidence already stored or uploaded (`D7b` option (c), not taken); the rest
of `D7` (tags, phase persistence, the EXIF/GPS policy); the display path, which the 2026-09-15 fix already
covers; and any change to the API, the schema, the permissions or the upload contract.

## Phase 4c — Image stack and preview caching (D4b)

**Status: LANDED (2026-09-15).**

**Goal.** Previews and the full-size photo stop being decoded and re-downloaded by hand: one image stack
draws them, with a memory and disk cache, and the API read keeps the session behaviour it has today.

**Depends on.** **D4b ✓ = (a)**. Phase 4b is independent but should land first, because the stack's EXIF
handling reads the same tag the preparation steps stop relying on.

**Layers.** Android, tests, `docs/decisions/` (the dependency ADR), documentation. **No API change**: no
`Cache-Control`, no server-derived thumbnail, no permission change.

**What landed.**

1. **The dependency is pinned and recorded**: `io.coil-kt.coil3:coil-compose:3.6.2` in
   `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`, with its compatibility against the
   pinned toolchain (Kotlin 2.3.21, Compose BOM 2026.08.00, OkHttp 5.5.0, `minSdk 26`) recorded in
   `docs/decisions/016-android-image-stack-and-viewer-zoom.md` (`dev.md` §4).
2. **The port kept its shape and changed its answer.** `JobPhotoImages` still has its four methods and both
   sources, and `JobPhotoImages.None` still draws nothing; what the methods return is now the **request** the
   stack loads (`coil3.request.ImageRequest?`), built in `DefaultJobPhotoImages` from the photo's identity
   (`data/jobs/JobPhotoImage.kt`), a size and the session's own cache key — or `null` when there is no session
   to attribute the photo to. Decoding, sampling, the EXIF turn and caching are the library's.
   `Scale.FIT` + `Precision.INEXACT` reproduce what the retired rules did: sample the decode to the size it is
   drawn at, and never upscale a smaller photo.
3. **The bytes are read by one new reader**, `data/jobs/JobPhotoFetcher.kt` (`Fetcher.Factory<JobPhotoImage>`):
   a pending photo comes from the app-private file that already holds it (no second cache), and evidence comes
   from this session's disk-cache entry when it is there and from the API when it is not — through the same
   Retrofit route, with the same `401 → renew once` renewal every other read of evidence uses. A photo that
   cannot be read answers with nothing to draw rather than with an exception (`BR-042`). The reader is handed
   the few values a read needs rather than Coil's `Options`, so the whole read path is verifiable on the JVM.
4. **The stack is the app's own.** `di/JobsOfflineModule.kt` (`JobPhotoImageModule`) builds one `ImageLoader`
   with the feature's fetcher and a disk cache in `cacheDir/job-photo-cache`, and `ServoraApplication` installs
   it as the singleton the composables draw with — so no second stack with caches of its own can appear.
5. **Cache partitioning and release (`BR-007`).** `jobPhotoImageCacheKey(subject, image)` carries the session
   subject into the memory entry and the disk entry (as `JobPhotoFiles` already does with
   `filesDir/job-photos/<subjectId>/`), and `JobPhotoImageCacheScope` releases the stack's caches on the first
   read of a session that is not the one they were cached for. It is a release **before a different session
   reads**, not at the instant of sign-out: the session owner does not depend on this feature's display cache
   (the offline layer's own session seam releases offline state), so this is the latest moment at which those
   bytes could be served, and a session that returns later releases again because its own entries are gone. What
   the decision requires — a member signing in on the same device is never served another member's evidence —
   holds by the keys alone; the release is what stops the bytes outliving the session.

**Verification (this tree).** `make android-test` — **PASS** (`./gradlew testDebugUnitTest`: 46 suites,
**450 tests, 0 failures, 0 errors, 0 skipped**, of which the new `JobPhotoFetcherTest` (11 cases),
`JobPhotoImageCacheKeyTest` (5) and `JobPhotoImageCacheScopeTest` (4) are this phase's, and
`JobPhotoSamplingTest`'s 5 cases now pin the surviving sampling rule). `make android-lint` — **PASS**
(`lintDebug`, `abortOnError = true`). `make android-build` — **PASS** (`assembleDebug`, `app-debug.apk`
produced). Device-test sources — **PASS** (`./gradlew assembleDebugAndroidTest` compiles). **No `adb` and no
device or emulator command was run** (`qa.md` §7.3): the library's on-device decoding, the cells' actual
drawing and the cache's behaviour across a real cold start are **NOT RUN — device QA is the product owner's**,
handed over as `## Manual QA runbook — Phase 4c`, which includes the measurement work item 6 owes (content
requests per photo per cold start, before and after) because only a device plus the API's log can report it.
Nothing under `api/` changed, so no API command was run.

**Not in this phase.** Server-derived thumbnails (`ADR-013` open question 3, which **`D15` answered on
2026-09-16: none in v1**); `Cache-Control` on the content route; zoom and gestures (Phase 4d); offline
visibility (**`D5`** — answered 2026-09-16, Phase 5).

## Phase 4d — Viewer gestures (D9)

**Status: LANDED (2026-09-15).**

**Goal.** The viewer's photo can be zoomed and panned, so the detail the photo was taken for is legible on
the device.

**Depends on.** **D9 ✓ = (b)** and **Phase 4c** — the zoom is a sub-sampled decode over the same stack, so
it is built on it rather than beside it.

**Layers.** Android, tests, `docs/design/android-design-system.md`.

**What landed.**

1. **The dependency is pinned and recorded**: `me.saket.telephoto:zoomable-image-coil3:0.19.0` in
   `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`, with its compatibility against the
   pinned toolchain (Kotlin 2.3.21, Compose BOM 2026.08.00, the Coil 3.6.2 this module already pins, `minSdk 26`)
   recorded in `docs/decisions/016-android-image-stack-and-viewer-zoom.md`, which closes that ADR's open
   question 4 (`dev.md` §4). It is Telephoto's **Coil 3** integration (`…-coil3`, not the Coil 2 one), so it
   loads the request the port already answers with, through the app's own `ImageLoader` — the feature's fetcher,
   the `401 → renew once` read and the session-keyed caches are all the ones Phase 4c built, and no second
   loader, cache or HTTP path exists.
2. **The viewer's photo zooms and pans** (`android/.../ui/jobs/JobPhotoViewer.kt`): the photo is drawn through
   `ZoomableAsyncImage` instead of `SubcomposeAsyncImage`, so a pinch zooms, a drag pans within the photo's own
   bounds, a double-tap goes to the library's zoom ceiling (`DoubleClickToZoomListener.cycle()`, its default)
   and no scale goes below "fit" (`ZoomSpec`'s minimum factor is 1, i.e. the fit scale). The ceiling is the
   library's own recommended spec (`ZoomSpec(maxZoomFactor = 2f)` adapted to the container) rather than a number
   this phase invented: because the capture pipeline stores evidence at most 2048 px wide (`D3c`), twice the fit
   scale already reaches roughly the photo's own pixels on a phone. The gesture surface is the area the photo
   occupies — the screen between the top row and the note — so the phase badge, the note and the 48 dp close
   action stay exactly where they were, and closing is unchanged: this viewer's own action, or the platform's
   back gesture, which the dialog still holds, so panning cannot swallow it.
3. **The viewer's three states survive the gesture layer**, which is the one design question the phase had to
   answer rather than assume: the layer draws the photo but reports nothing about the read, and it has no
   loading or error slot. So `JobPhotoViewerImageState` (reading / the photo / "cannot be shown") is observed
   from the stack's **own result for the request the viewer already has**, through Coil's `ImageRequest.Listener`
   — the same request, not a second read, and Coil reports every request's outcome through it, memory- and
   disk-cache hits included. A photo that cannot be read is still reported instead of being replaced by another
   picture (`BR-042`), and the photo's test tag is applied only once the stack has drawn it, so "shown" and
   "cannot be shown" are never claimed by the same node.
4. **Documentation**: the viewer row of `docs/design/android-design-system.md`, this tracker's Viewer,
   Thumbnails and Tests rows plus the risk table, the `D9` implementation record in `docs/decisions/016-…`, and
   `README.md`.

**Verification.** Instrumented Compose cases for opening, zooming and closing, compiled by
`./gradlew assembleDebugAndroidTest` and **NOT RUN here — device QA is the product owner's** (`qa.md` §6.2,
§7.3); device-free checks `make android-test`, `make android-lint`, `make android-build`. A runbook for the
product owner covering a portrait and a landscape photo, a double-tap, a pinch, panning to each edge, and
closing from a zoomed state.

**Not in this phase.** Swiping between photos and any gallery-wide pager (`D11` later decided the within-Job
pager, delivered by Phase 4e); video; offline evidence (**`D5`** — answered 2026-09-16, Phase 5).

## Phase 4e — Viewer paging, save and share (D11, D12, D13)

**Goal.** The viewer becomes the place a technician actually uses the evidence: they can move through the
Job's photos without going back to the screen each time, and they can take a photo out of Servora — into
the device's own gallery, or to another application — as an explicit action of their own.

**Depends on.** **D11 ✓, D12 ✓, D13 ✓** — raised by product ownership on 2026-09-15 and recorded in
`## Phase 0 decision log`, with `D4`/`D9` (the viewer and its gestures, landed in Phases 4 and 4d) as the
surface it extends. **The API changes nothing**: a photo that has not been accepted has no server-side
object, and a photo that has is read through the route that already exists (`BR-015`).

**Layers.** Android, tests, `docs/decisions/`, documentation.

**Work.**

1. **Paging.** `ui/jobs/JobPhotoViewer.kt` draws the Job's photos as one `HorizontalPager`:
   `viewedJobPhotoSequence` builds the order the screen already presents (evidence the backend holds, as
   the Activity reports it, then the photos this device still holds, oldest first, one entry per photo id),
   `jobPhotoViewerInitialPage` says which page the tapped photo is on, and `jobPhotoViewerPagingEnabled`
   allows a swipe only while the settled page reports a fit-sized `zoomFraction` — so a zoomed photo pans
   (`D9`) and a page the pager has left resets its zoom. Each page owns its own zoomable state, and the
   chrome follows the photo: phase badge, a localized position (`3 / 12`, drawn only when there is more
   than one), the note (bounded and scrollable, so a long one cannot push the photo out), and — for a photo
   the backend has not accepted yet — the upload state its tray tile shows.
2. **Reading the bytes for an export.** `data/jobs/JobPhotoContentReader.kt` is extracted from
   `JobPhotoFetcher`, and both now use it: one evidence read, with the one `401 → renew once` path
   (`BR-007`, `BR-018`). The device's own file is read through the existing `JobPhotoFiles`.
3. **Save to device.** `data/jobs/JobPhotoExport.kt` proves the bytes' type (`JobPhotoContentType.ofBytes`,
   the rule the upload path uses) and names the copy `Servora-<photoId>.<ext>`;
   `data/jobs/AndroidJobPhotoExportTarget.kt` writes it into a `Servora` album in the shared image
   collection — API 29+ with no permission and `IS_PENDING` until the bytes are complete, API 26–28 with
   `WRITE_EXTERNAL_STORAGE`, which `AndroidManifest.xml` declares with `android:maxSdkVersion="28"`.
4. **Share.** The same target stages the bytes in one app-private cache directory, cleared before each
   export, exposed by the one `cache-path` added to `res/xml/file_paths.xml`, and starts the platform's
   chooser with the photo's own MIME type and a read grant for that file alone.
5. **Wiring.** `JobPhotoExporter` (a new Hilt binding) is injected into `JobDetailsViewModel`, which gains
   `savePhotoToDevice`, `sharePhoto` and `onSavePermissionResult` and reports through the existing photo
   message/failure channels; `JobDetailsUiState` gains `photoSaveAwaitingPermission` and the export
   failures/confirmations; `JobDetailsScreen` resolves the chooser's title from resources, owns the
   permission request, and draws the two actions only when the session holds `evidence.view`
   (`Permission.EVIDENCE_VIEW`, `canViewEvidence`).
6. **Documentation.** This tracker (the decision log, this section, the state table, the risk table, the
   "not implemented" list, the phase log and the runbook below), `docs/decisions/017-…`, the viewer row of
   `docs/design/android-system.md`'s sibling (`docs/design/android-design-system.md`) and `README.md`.

**Verification.** JVM tests for the new pure rules and the export (`JobPhotoViewerTest` gains the ordering,
initial-page and paging-rule cases; `JobPhotoExportTest` covers which bytes are read from where, the name and
the type proven from the bytes, what is refused, and every target outcome; `JobPhotoCaptureViewModelTest`
covers saving, sharing, the permission round trip and each report). The image stack's own tests are unchanged
and still green, because the reader's behaviour is the same path they already asserted. Compose cases in
`JobDetailsScreenTest` cover the position, the two actions and their absence without the capability, and are
reported **NOT RUN — device QA is the product owner's**. Commands: `make android-test`, `make android-lint`,
`make android-build`, plus `./gradlew assembleDebugAndroidTest` for the device-test sources. **No `adb` and no
device command is run** (`qa.md` §7.3), so swipe/pan arbitration, the gallery write and the chooser are
device-bound and handed over as `## Manual QA runbook — Phase 4e`. Nothing under `api/` changes, so no API
command is run.

**Not in this phase.** Deleting or editing a recorded photo (`D6a`/`D6b` — answered 2026-09-16: no edit route
exists, and the removal is Phase 6b), retention (`D6d` — answered 2026-09-16: indefinite for the life of the
tenant), Job deletion and orphaned objects (`D6e` — answered 2026-09-16: normal flows never hard-delete a
Job), the refused-photo discard (`D6c`, Phase 6a), swiping across Jobs or a device-wide gallery (`D11`
scoped paging to the open Job), sharing the note or Job context (`D13` decided the photo only), offline
visibility of accepted evidence (`D5` — answered 2026-09-16, Phase 5), and any Angular surface (`BR-030`).

## Phase 5 — Offline visibility of accepted evidence (D5)

**Status: LANDED 2026-09-16 (`D5` ✓).** Recorded in `## Phase log`; the runbook is
`## Manual QA runbook — Phase 5`.

**Goal.** A technician without connectivity still sees the Job's evidence **metadata** and its Activity, and
a photo's bytes are readable offline when the device already holds them or the technician asked for them —
never because a screen prefetched every photo.

**Depends on.** **`D5` ✓**: metadata required offline, bytes best-effort, pending uploads guaranteed,
accepted bytes an evictable cache. Its read rules are the offline standard's (§2, §3, §7, §13), which this
phase follows rather than reinvents.

**Layers.** Android; `docs/architecture/offline-first-architecture.md`; tests; docs.

**Work.**

1. **The Job Details and Job Activity metadata reads adopted the working set** — the existing store
   (`data/offline/WorkingSetStore.kt`), following the Customer Detail precedent in tracker `025`
   (`data/customers/CustomerDetailCache.kt`), through a new `data/jobs/JobEvidenceCache.kt` and two new
   rows, `WorkingSetEntityTypes.JOB_DETAILS` and `JOB_ACTIVITY`. Each answer is the backend's **wire
   response**, stored under the authenticated subject and served **only** when the backend could not be
   reached, marked as the last reported answer on screen (`ui/components/OfflineNotice.kt`, with
   `JobDetailsLastReportedTag` and `JobActivityLastReportedTag`), and never replacing a refusal (§12.2).
   This covers the Activity projection — which photo ids the Job has, with their phase, note and time — not
   only the Job header.
2. **`android/.../data/jobs/JobDetailsRepository.kt` states its read policy.** Both reads now report the
   reason (`JobRead`/`ActivityRead`) so the DTO the backend answered with can be kept while the mapped
   object is returned, and each falls back only for a failure that could not reach the backend
   (`couldNotReachBackend()`: `NETWORK` or `SERVER`). An action stays online-only (`BR-001`).
3. **The photo bytes.** A photo this device holds is read from the file that holds it, as before; a photo
   whose bytes this device does **not** hold is read from the image stack's cache when that cache still has
   it, and through the API when it does not. The cache policy is now stated **explicitly** — a bounded,
   size-based LRU over the existing Coil disk cache rather than a new store (`D5` ✓, §9) — in the code's own
   terms (`JobPhotoImageModule`'s documented constants) and as one rule for which photos may be cache
   entries at all: `jobPhotoImageDiskCacheKey` answers `null` for a `Local` photo, so **a pending upload's
   bytes can never be evicted** (`BR-014`, `BR-015`), and the session's own key for evidence, so accepted
   bytes are exactly what the bounded cache holds. What going offline must **not** do is asserted too: no
   eager download of the Job's historical photos (nothing prefetches), and a failed read leaves the cache
   empty.
4. **A photo that is neither held locally nor cached is reported as unavailable offline.** The reader now
   says **why** bytes did not arrive (`JobPhotoContentRead`: `Unreachable` for no connection, a `5xx` or a
   renewal that was never answered — the offline standard's own classification — and `Unavailable` for no
   session, a refusal or a missing record), the fetcher carries that out as
   `JobPhotoBytesUnavailableException`, and the surfaces report it: the viewer has its own
   **"This photo is not available offline. Connect to view it."** report (`JobPhotoViewerOfflineTag`), and a
   tile draws an offline glyph with a localized description. Never a different picture, and never a failure
   the technician cannot act on (`BR-042`); a photo this device no longer holds stays the generic
   "could not be shown", since reconnecting would not fix it.
5. **Documentation**: `docs/architecture/offline-first-architecture.md` §9 (the one rule), §11 items 1 and
   4, §12's adopter list and its online-only paragraph, and the header's adopter count; this tracker's
   status, phase table, state table, risk table and the runbook below.

**Verification.** Offline/synchronization tests per `qa.md` §8, on the JVM where the behaviour is: the Job
read served from the working set when the API is unreachable (a `5xx` included), a refusal never replaced by
a local copy, a newer answer replacing the older one, another session's row never served, and the same for
the activity read; the reason a photo's bytes could not be produced (offline, refused, gone from the device,
a `5xx`), and that a failed read writes nothing and a photo this device holds is never a cache entry. Device
bound work was left to the product owner (`qa.md` §7.3): no `adb` and no device or emulator command was run,
and the manual runbook below is the hand-off.

**Not in this phase.** Offline photo capture (already works); offline evidence upload (already works);
downloading every historical photo (explicitly excluded by `D5`); presigned reads (Phase 8, which changes
where these bytes are read **from**); the offline posture of the removal operation (Phase 6b decides it
against the offline standard's §13.2 — an idempotency key and a conflict policy first).

## Phase 6a — Refused photos are explicitly discardable (D6c)

**Status: LANDED 2026-09-16 (`D6c` ✓).** Recorded in `## Phase log`; the runbook is
`## Manual QA runbook — Phase 6a`.

**Goal.** A photo the API permanently refused stops being trapped on the device.

**Depends on.** **D6c ✓ = (a)** — but **not** on the rest of D6, which is why it is its own slice.

**Layers.** Android, tests, documentation. **No API change**: the photo was never accepted, so there is no
server-side object to remove and no route or permission to add.

**Work.**

1. `android/.../ui/jobs/JobPhotoTray.kt` — a refused photo offers an explicit **discard** action beside the
   reason it already shows, in both languages (`BR-028`); the upload-state text and the count stay as they
   are.
2. `android/.../data/jobs/JobPhotoSession.kt` + `PendingJobPhotoStore` — discarding removes the local file
   and the queued row **together**, so no file outlives its record and no row outlives its file (offline
   standard §9), mirroring the removal that already exists for a never-submitted photo.
3. The outbox must not replay a discarded refusal: it is finished, not pending (`BR-031`).
4. Documentation: this tracker's state table ("Pending tray / review" row), `docs/design/android-design-system.md`
   if it fixes what the tray offers, and the offline standard if the removal changes what §9/§12 record.

**Landed (2026-09-16).** The action belongs to the refused photo: `JobPhotoPendingTile` draws a **Discard**
`TextButton` (`jobPhotoRefusedDiscardTag`) whenever the tray's upload state is `REFUSED`, beside the
`Refused by the server` reason it already reports — and the X a draft carries is **not** drawn as well, so
one action clears the photo. `JobPhotoSession.discard` now decides from the **queue** rather than from the
caller's own record: `PendingJobPhoto.isDiscardable(upload)` — `!submitted || REFUSED`, with
`isRemovable`/`isRefusedUpload` beside it in `domain/model/JobPhoto.kt`, because the data layer and the UI
must apply one rule (`BR-041`) — is the whole gate, and a submitted photo that passes it has its queue row
removed with the file and the pending row through the new `OutboxStore.discardRefused`, whose delete carries
the state in its own predicate, so only a **refused** row can ever be removed that way and waiting work
cannot be dropped through it (`BR-014`). The screen also stops reading the tray's loss of a refused photo as
the API accepting it: `JobDetailsViewModel` remembers the removal it performed (`discardedLocally`), and only
for a photo the tray reports as refused, so the timeline is not re-read for evidence the backend never
accepted — while an accepted upload still reads it (`BR-001`, `BR-080`). No API, database, permission,
contract or submitted byte changed.

**Verification.** JVM tests for the discard (file and row removed together, the refusal no longer replayable),
extending `JobPhotoSessionTest` and `JobPhotoCaptureViewModelTest`; a Compose case for the discard action
compiling in `JobDetailsScreenTest` and marked **NOT RUN — device QA is the product owner's**. Commands:
`make android-test`, `make android-lint`, `make android-build`.

**Not in this phase.** Deleting or editing a **recorded** photo (`D6a`/`D6b` — answered 2026-09-16: no edit
route, and the removal is Phase 6b), retention (`D6d` — answered 2026-09-16: indefinite, `BR-090`), Job
deletion and orphaned objects (`D6e` — answered 2026-09-16: no hard Job deletion in normal flows).

## Phase 6b — Removing accepted evidence (D6a, D6b, D6d)

**Status: LANDED 2026-09-16 (`D6a` ✓, `D6b` ✓, `D6d` ✓).** Recorded in `## Phase log`; the runbook is
`## Manual QA runbook — Phase 6b`.

**Goal.** Accepted evidence stays immutable, and taking it out of ordinary use is an explicit, authorized,
audited action instead of a mutation — with no edit path at all.

**Depends on.** **`D6a` ✓, `D6b` ✓** (immutability, no edit route, soft removal behind a Manager-level
capability) and **`D6d` ✓** (soft-removed evidence leaves ordinary views but stays in an audit/history
context for authorized managers). **`D1` ✓** for how the capability is authorized.

**Layers.** API, database (a new migration), permissions, Android, tests, docs.

**Work.**

1. **The capability.** `evidence.photo.remove` in the API's permission catalogue, following the
   `evidence.view` / `evidence.photo.add` shape (`ADR-015`), with a bilingual name and description
   (`BR-005`) and a **new** migration granting it to the default **Manager** role and not to **Technician**
   (`BR-089`; `dev.md` §6 — a new migration, never an edit of an applied one).
2. **The route.** An explicit **Remove evidence** operation on the Job photo, authorized by
   `evidence.photo.remove`, recording **actor, timestamp and reason** and soft-removing the evidence: the
   record and its history are preserved, and the removal is **added history**, not a rewrite (`BR-067`,
   `BR-089`). `docs/api/job-photos.md` §1/§7 change from "nothing deletes a photo" to exactly what this
   operation does. **No edit route is built, in this phase or later** (`BR-088`).
3. **Reads respect the removal.** Ordinary reads — the technician's field views in particular — exclude
   soft-removed evidence; the audit/history context for authorized managers includes it and states that it
   was removed, by whom, when and why (`D6d` ✓). The Job's Activity must not silently drop the event
   (`BR-080`).
4. **The Android surface.** The removal is offered where an authorized Manager acts on a Job's evidence,
   drawn on the capability the API enforces (`BR-007`, `BR-011`), localized (`BR-028`) and confirmed before
   it is applied (`BR-067`). A technician's app offers **nothing** for accepted evidence — only the existing
   pre-submission discard (`BR-088`).
5. **Offline posture, decided rather than assumed.** A removal is a mutation, so it may be queued **only**
   if its route accepts a client-generated idempotency key **and** its conflict policy is decided (offline
   standard §5, §8, §13.2). If either is missing when this phase is designed, the operation is
   **online-only** and the phase records that reason instead of inventing a queue.
6. **Retention is not this phase** (`D6d` ✓, `BR-090`): nothing physically purges an object here, and no
   store lifecycle rule is added.

**Verification.** API authorization e2e for the new route: `401` unauthenticated, `403` without
`evidence.photo.remove`, successful authorized removal, a session that may add evidence but not remove it,
the removed photo excluded from ordinary reads, present in the audit context with actor, time and reason,
and no path that rewrites the recorded bytes. Migration test for the permission rows (`make api-test`,
`make test-api-e2e`). Android JVM tests for the capability gate and the confirmation; the Compose case is
compiled and **NOT RUN — device QA is the product owner's** (`qa.md` §7.3).

**Not in this phase.** Any edit of accepted evidence (excluded by `D6a`/`D6b`); physical purge of the stored
object (`BR-090`); a structured removal-reason catalogue, which is not defined.

**Landed (2026-09-16).** The slice is the API's first operation on a **recorded** photo, and it adds no way
to change one. **The capability**: `EVIDENCE_PERMISSIONS.PHOTO_REMOVE` (`evidence.photo.remove`) with a
bilingual name and description, added by the new migration `0011_job_photo_removals.sql`, which grants it to
the default **Manager** system role and **not** to **Technician** — the same additive shape `0010` used
(`BR-005`, `BR-089`). **The record**: `job_photo_removals` (`organization_id`, `job_photo_id`,
`actor_membership_id`, `reason`, `recorded_at`) with a non-empty-reason CHECK and a unique index on
`(organization_id, job_photo_id)`, so one photo has at most one removal; `job_photos` is untouched and stays
append-only, and the removal is **added history** (`BR-067`, `BR-089`). **The route**:
`POST /jobs/:id/photos/:photoId/removal`, guarded by `evidence.photo.remove`, taking `{ reason }` (required,
≤2000 characters, free text — no catalogue is invented, `BR-042`) and answering with the refreshed Job
Activity projection exactly as the add-photo and add-note writes do (`BR-001`, `BR-080`). A second removal is
refused with its own outcome, `409 JOB_PHOTO_ALREADY_REMOVED`, rather than recorded as a state change that did
not happen. **Reads respect it**: the photo leaves the Activity's `JOB_PHOTO_ADDED` events, so it leaves every
gallery drawn from them, and the content route answers `404 JOB_PHOTO_NOT_FOUND` for it — while
`JOB_PHOTO_REMOVED`, a new activity kind carrying `photoId` and `photoRemovalReason`, is part of **every** read,
because Activity must not silently drop the fact (`BR-080`). The **audit/history context** `D6d` requires is
the read's own `includeRemovedEvidence=true` value: it adds the removed record back — with the removal's actor,
instant and reason — and it is authorized by `evidence.photo.remove` in addition to the route's
`customers.view`, so a technician who may read the timeline is refused rather than quietly answered with the
ordinary projection (`BR-007`, `BR-089`). The value is a closed vocabulary of `true`/`false`, so a caller can
never be silently answered with the ordinary read. **Online-only, decided rather than assumed**: the route
takes no client-generated idempotency key and no conflict policy is decided for it, so it is never queued
(`offline-first-architecture.md` §5, §8, §13.2). **Android**: `Permission.EVIDENCE_PHOTO_REMOVE` and
`canRemoveEvidence` gate a **Remove evidence** action in the viewer's top bar (its own `ic_trash` glyph, drawn
after Save and Share so it is never under the technician's thumb on the way to them), a **confirmation dialog**
that requires a reason before it can be applied (`BR-067`, `BR-089`), a progress state on the control that
started it, and honest reports: not permitted, no longer available, needs the server, failed. A refused removal
is reported through the same channel a save or a share is, inside the viewer, and changes nothing on screen.
The new activity kind is labelled in both languages and its reason is drawn as the entry's secondary text.
**The local copy follows the write**: a successful activity write now replaces the working set's activity row
with the answer the backend gave, exactly as a successful read does (`offline-first-architecture.md` §2,
`BR-041`) — a device that removed evidence and then lost connectivity would otherwise serve an activity that
predates its own change and still lists the photo. Nothing in the slice rewrites a byte: the stored object
stays where it is (`BR-090`), and the photo's row is unchanged. The `ActivityWriteResult` rename
(`VisitNoteResult` before it) records that two writes now answer with the refreshed timeline.

**What this phase does *not* do, recorded so it is not assumed:** it does **not** purge the stored object, and
it does **not** evict the device's image cache — a photo that was removed may still have bytes in the bounded,
evictable Coil disk cache until the LRU drops them (`D5`), which is not a view of the evidence and can never be
re-drawn because nothing asks for it and the content route answers `404`. It does not restore removed evidence
(no restore is defined) and it does not add a removal-reason catalogue.

## Phase 6c — Evidence metadata: no location metadata, phase as draft state (D7)

**Status: STARTABLE.**

**Goal.** What Servora stores carries no location or unnecessary metadata, and the chosen phase survives an
interrupted update without becoming a global preference.

**Depends on.** **`D7` ✓** — `phase` is the only structured classification, the selected phase is draft
state for the in-progress update, and GPS/location EXIF plus unneeded metadata are stripped by default.

**Layers.** Android (pipeline and draft state), API (validation and the contract's own statement), tests,
docs.

**Work.**

1. **Strip on the way in, for every path.** The preparation pipeline already re-encodes some photos (the
   `D3b` conversion and the `D3c` resize), but a capture that is already JPEG/PNG/WebP and under the limit
   travels **unchanged** today, with whatever metadata its source carried — GPS included. This phase states
   where the strip runs (client pipeline, API, or both) and proves it covers **both** paths, keeping only
   what the application needs: normalized orientation and dimensions, and Servora's own server-side
   capture/upload timestamps (`BR-091`). Phase 4b's rule stands: the stored pixels are upright, and the
   strip must not reintroduce a dependence on an orientation tag.
2. **Nothing beyond what is needed**: no GPS/location, no device identifiers, no free-form EXIF the
   application does not read. A strip the device cannot perform **refuses** the photo rather than storing
   bytes with metadata Servora did not intend to keep (`BR-042`).
3. **The phase is draft state, not a preference.** The selected phase persists across Activity-sheet
   recreation and process death **for the update in progress**, and is never carried into another Job or
   another update (`BR-091`). Today it lives in the screen's UI state alone, so a process death mid-update
   loses it.
4. **No taxonomy.** No tags or categories are added; the note remains the free-text place for anything more
   (`BR-091`, `D7` ✓).
5. **Documentation**: `docs/api/job-photos.md` §3.3/§7, the review-panel row of
   `docs/design/android-design-system.md` if the draft state changes what it states, this tracker's state
   table (**Preparation** row) and its risk row on EXIF/GPS.

**Verification.** JVM tests for the strip's decision rule (pure where possible, as `JobPhotoOrientation` is)
and for the phase's draft-state behaviour; API tests if validation changes; a device case for a capture
carrying GPS EXIF — compiled, **NOT RUN — device QA is the product owner's** (`qa.md` §7.3), with a runbook
stating how to confirm a stored photo carries no location metadata.

**Not in this phase.** Repairing metadata on evidence **already stored** — it is historical (`D7b` option
(c) was not taken, `BR-067`); using location as a product feature (`BR-038` remains open); audio metadata or
transcription (Phase 9).

## Phase 6 — The rest of the evidence lifecycle

**Status: CLOSED by the 2026-09-16 decisions; its work is carried by Phases 6a, 6b and 6c.**

Every item this section listed is now decided, and two of them need no v1 work at all:

1. **Refused photos** — `D6c` ✓, delivered by **Phase 6a**.
2. **Delete or edit a recorded photo** — `D6a`/`D6b` ✓: no edit route exists or will be built, and the
   removal is **Phase 6b**.
3. **Retention** — `D6d` ✓: indefinite for the life of the tenant, with configurable retention a later
   capability for tenants that need it; recorded in `BR-090`. **No v1 work.**
4. **Job deletion and objects** — `D6e` ✓: normal product flows never hard-delete a Job, so a Job deletion
   cannot orphan objects today. A future administrative purge has a decided shape (a deliberate cascade with
   asynchronous, idempotent object deletion), but **no v1 work**, and `BR-021` still leaves Job deletion
   itself open.

**Not in this phase.** Angular; any retention policy that has not been decided.

## Phase 7 — Angular parity and reporting over evidence

**Goal.** The management surface sees the same evidence the Android client does, governed by the same
permissions, with reporting where the product wants it.

**Depends on.** **D1–D15** — every Phase 0 question is now answered — and the Angular application itself,
**which does not exist in this repository yet** (`Project.md` §23 names Angular, but there is no web project
to change and no Playwright configuration to extend). It also consumes what Phases 5, 6b, 6c, 8 and 9
decide, so it follows them rather than leading them.

**Layers.** Angular, API (only as Phases 1–6 decided).

**Work.** To be written when the Angular application exists and its own rules apply. What this tracker
fixes now is the contract it must consume: `docs/api/job-photos.md` and `docs/api/job-activity.md` are the
authority, the same stable codes are used (`BR-041`), the same labels are resolved client-side
(`BR-028`), and evidence reporting must respect the requesting user's permissions (`BR-030`) — no report
may expose evidence a user may not read.

**Verification.** Angular unit/component tests, Playwright journeys for the evidence surfaces, and the
shared contract verified against affected applications (`qa.md` §12).

**Not in this phase.** Any change to the evidence model that Phases 1–6 did not decide.

## Phase 8 — Evidence reads through short-lived presigned URLs (D15)

**Status: STARTABLE once the design states the resolution `ADR-013` D7 requires.**

**Goal.** A client reads accepted evidence through a short-lived, API-issued URL instead of streaming the
bytes through the API, with the bucket private, uploads unchanged and no derived object stored.

**Depends on.** **`D15` ✓** (presigned GET for reads, private bucket, no second configured endpoint, no
derived objects) and **Phase 5**, because the read path it changes is the one Phase 5 makes offline-capable.

**Layers.** API, `docs/decisions/013-…`, `docs/api/job-photos.md`, Android, shared types, tests, docs.

**Work.**

1. **Resolve the recorded constraint first** (`ADR-013` D7, and its 2026-09-16 update): a SigV4 signature is
   bound to its `Host`, so a device-reachable storage host is needed (`S3_PUBLIC_ENDPOINT` locally plus a
   second `adb reverse`), and a plain-HTTP URL is usable only by a debug build. The design also states what
   a client learns about the provider, because the same decision asks that clients not assume a particular
   vendor. **No implementation starts before that is written down** (`BR-042`).
2. **The API answers a read with a short-lived URL** for the evidence record the session may read
   (`evidence.view`), instead of streaming the bytes; `docs/api/job-photos.md` §4 changes with it, and the
   URL's lifetime is stated there (`dev.md` §7).
3. **Android reads through the returned URL** while keeping the security path: the authorization is what
   produced the URL, a refusal is still a refusal, and the discovery call keeps a `401 → renew once` path
   (`BR-007`, `BR-018`). The image stack and its per-subject cache stay as Phases 4c/5 left them, and
   evidence stays immutable and still cached (`BR-088`).
4. **Uploads do not change**: `POST /jobs/:id/photos` still validates and stores through the API (`BR-015`).
5. **No derived objects**, and no new client-visible endpoint configuration: storage stays an API concern
   (`D15` ✓).

**Verification.** API unit + e2e: the URL is short-lived, a session without `evidence.view` is refused, the
bucket stays private (an unsigned direct fetch fails), and the object key is still derived from the record.
Android JVM tests for reading through a returned URL and for the refusal path; the on-device round trip is
handed over (`qa.md` §7.3). `ADR-013` and the offline standard's §12 are updated with the read's new shape.

**Not in this phase.** Presigned **uploads** (`D15` keeps them on the API port); a public bucket, a CDN or a
second configured endpoint; derived objects (decided against in `D15`); any change to what a photo is.

## Phase 9 — Audio evidence (D8)

**Status: STARTABLE — the largest of these slices.**

**Goal.** A technician records an audio note on a Job; it uploads like a photo (offline-safe, idempotent,
authorized); it appears in the Job's Activity and can be played back — as evidence of its own kind,
immutable once accepted, removable only through Phase 6b's audited operation.

**Depends on.** **`D8` ✓** (audio is in scope on the same abstraction and rules; generic files are out) and
**Phase 1** (the capability catalogue this activates).

**Layers.** Database (the evidence model), API, permissions, storage, offline, Android, localization, tests,
docs, an ADR.

**Work.**

1. **An ADR first** (`Project.md` §31, `dev.md` §14): the evidence model for a **kind** — a `kind` on one
   model versus a parallel structure — plus the audio content-type and size vocabulary, length limits, the
   Activity kind and playback. Everything this tracker has **not** decided belongs there, before the
   migration is written (`BR-042`).
2. **The capability**: create `evidence.audio.add` (the reserved code, `ADR-015`) with a bilingual name and
   description, granted to the default Technician and Manager roles as `evidence.photo.add` is, in a new
   migration (`BR-005`, `BR-009`).
3. **The record and the object**: an evidence row for a recording with its own provider-agnostic key under
   the evidence prefix (`ADR-013` D6.5), the same validate-before-evidence rule (`BR-015`), the same
   immutability from acceptance (`BR-088`), and the same removal through `evidence.photo.remove` — or its
   own kind-specific removal capability if the ADR decides the per-kind shape requires one, recorded rather
   than assumed.
4. **Capture, offline and upload**: recording through the platform's own recorder, stored app-private first,
   queued through the **existing** outbox with a client operation id and a **decided** conflict policy
   (offline standard §5, §8, §13.2), never lost while pending (`BR-014`).
5. **Activity and playback**: the recording is its own Activity entry (`BR-080`) and plays back in the client
   that recorded it, with localized labels (`BR-028`) and nothing invented about the audio itself.
6. **The affordance arrives with the capability**: the Add update sheet's audio kind becomes reachable
   because `evidence.audio.add` now exists — and only then (`D8` ✓; today `canAddAudio = false`). Whether
   the kind also offers *choose an existing recording* beside *record* is the question tracker `033`
   recorded; it is decided here or explicitly left out.
7. **Generic files stay out** (`D8` ✓): no PDF/document attachment surface.

**Verification.** API unit + e2e: authorization (`401`/`403`/success), content-type and size validation,
idempotent replay, immutability after acceptance, and removal through Phase 6b's operation. Android JVM
tests for the recorder's state machine, the outbox handler and the Activity mapping; Compose cases compiled
and **NOT RUN — device QA is the product owner's** (`qa.md` §7.3), with a runbook covering an offline
recording, a restart with a pending recording, playback and a refusal. Offline/synchronization tests per
`qa.md` §8.

**Not in this phase.** Generic file attachments; transcription; audio in Angular (Phase 7, once the Angular
application exists); any retention change (`BR-090` stands).

## Risks carried into these phases

Recorded from the implementation inspection. Each one is either why a phase exists or a hazard a phase
must not step on.

| Risk | Why it matters | Phase that addresses it |
| --- | --- | --- |
| The default Technician role cannot add a photo (`JOB_UPDATE` guard, `BR-009`) | The audience `BR-015` names gets `403`; a picker is useless to them. **Decided by D1/D1b; Phase 1 implements the fix** | 1 |
| `/photos/:id/content` reads use `customers.view` while the write uses `JOB_UPDATE` | Interim and inconsistent authorization on one feature. **Decided by D1b** (`evidence.view` / `evidence.photo.add`); Phase 1 implements it | 1 |
| Every gallery tile downloads full-resolution bytes; no HTTP cache, no `Cache-Control`, a 24-entry in-memory cache | Repeated multi-megabyte downloads per photo, per cold start. **Decided (`D4b` ✓) and delivered by Phase 4c (landed 2026-09-15)**: Coil 3 with a memory and disk cache, keyed and released per session subject, so the per-cold-start re-download stops. A full-resolution object may still be downloaded **once**, because server-derived thumbnails were not taken (`ADR-013` open question 3) | **4c ✓** |
| An image cache of evidence on a shared device | Cached bytes are one subject's evidence, so a cache keyed only by job and photo id would let a second member signing in on the same device read the previous member's photos without the API answering (`BR-007`) | **Decided (`D4b` ✓) and delivered by Phase 4c (landed 2026-09-15)** — the cache is keyed per session subject (`jobPhotoImageCacheKey`) and released on the first read of a different session (`JobPhotoImageCacheScope`); the release happens before a different session reads rather than at the instant of sign-out, which is recorded in Phase 4c and in `docs/decisions/016-…` | **4c ✓** |
| Gallery tiles are inert; no viewer exists although the design requires one | The implemented screen cannot show a photo full size. **Delivered by Phase 4**: gallery and tray tiles are tap targets, and the viewer shows the photo, its phase and its note | 4 ✓ |
| The viewer showed a photo but could not zoom it, and a scale-up would show no more detail than the fit view | The design asks an attachment to open "an appropriate preview/viewer" and a photo is evidence whose detail is the point. **Decided (`D9` ✓) and delivered by Phase 4d (landed 2026-09-15)**: the photo is drawn through Telephoto over the Coil 3 stack, which sub-samples the file the stack cached, so a zoom reads the photo's own pixels rather than scaling a bitmap that was decoded to fit the screen | **4d ✓** |
| The local pipeline hardcodes JPEG (`.jpg`, `image/jpeg`) | A picked PNG/WebP would be refused by the API's declared-versus-sniffed check | 2 |
| No photo picker existed at all | The design's "Existing Photos" and multi-select were unimplemented. **Delivered by Phase 3**: the system photo picker, multi-select, one draft per item, and a per-item report for a photo that cannot be taken | 3 ✓ |
| The tray's "Take another" action opens only the camera | After a pick, a second library photo needs the sheet again, while the design's attachment row pairs the two sources. **Answered (`D14` ✓, 2026-09-16)**: the sheet stays the canonical entry point for the evidence kind, and inside the photo flow **Take another goes straight to the camera again** — which is what the tray already does, so no code changes | **0 (`D14` ✓)** |
| Job Details and Activity reads are online-only | Offline, accepted photos and the timeline disappear; only the pending tray remains. **`D5` was deferred on 2026-09-15, answered on 2026-09-16, and delivered by Phase 5 (landed 2026-09-16)**: the Job and its Activity are served from the working set when the API is unreachable, the metadata is therefore readable offline, and the bytes are cached best-effort — never eagerly — with a pending upload's bytes never a cache entry | **5 ✓** |
| A photo that is on neither the device nor the image stack's cache | Offline, a tile would silently show a camera glyph and the viewer a generic "could not be shown", which names no cause the technician can act on. **Delivered by Phase 5 (landed 2026-09-16)**: the read reports *why* (`JobPhotoContentRead` → `JobPhotoBytesUnavailableException`), so the viewer says the photo is **not available offline** and a tile draws an offline glyph — never a different picture (`BR-042`) | **5 ✓** |
| A refused upload keeps its file and row with no affordance | Trapped evidence and unbounded device storage. **Decided (`D6c` ✓) and closed by Phase 6a (landed 2026-09-16)**: a permanently refused photo carries an explicit discard in the tray, which removes its file, its draft row and its queued refusal together | **6a — landed 2026-09-16 (`D6c` ✓)** |
| The viewer showed one photo and nothing else | After the first photo the technician had to close the viewer, find the next tile and tap it — the Job's evidence could not be read as a run. **Decided (`D11` ✓) and delivered by Phase 4e (landed 2026-09-15)**: one sequence in the order the screen presents the photos, a swipe to page and a zoomed photo that pans instead. A cross-Job or device-wide gallery stays undecided |
| Evidence could only be looked at inside Servora | A technician who needs to send a photo to the office, a customer or a supplier had no way to, and no way to keep a copy on their own device. **Decided (`D12` ✓, `D13` ✓) and delivered by Phase 4e (landed 2026-09-15)**: Save to device (the shared gallery, `Servora` album) and Share (the platform's chooser), both explicit actions the technician takes |
| A copy of evidence leaves Servora with nothing recorded | Saving and sharing write bytes outside the API's control. **Accepted by `D13` and recorded rather than mitigated**: the action is the technician's own, the copy carries only the photo (no note or Job context), the file is named after the photo's record id, and Servora neither tracks nor claims anything about where it ends up (`BR-027`) |
| `WRITE_EXTERNAL_STORAGE` is needed on API 26–28 for a share-sheet-style save | A save on those two releases needs a runtime permission; a silent failure would look like a successful save. **Decided (`D12` ✓), delivered by Phase 4e and bounded**: the declaration carries `android:maxSdkVersion="28"`, the screen asks when a save needs it, the same save is retried when it is granted, and a declined one is reported. No media-**read** permission exists, so `D10` is unchanged |
| Photos are Job-scoped while `docs/domain/job-visit-domain-model.md` §469 says Visit-scoped | The domain document contradicted the implemented model. **Decided (`D2` ✓)**: evidence is Job-level and §469 is corrected with the decision | **0 (`D2` ✓)** |
| `STORAGE_PROVIDER` defaults to `noop` | A local stack that does not select `s3` refuses every upload with `503` | operational, not a phase |
| No retention or delete rule exists; the store has no lifecycle rule | Evidence accumulates permanently. **Answered (`D6d` ✓, 2026-09-16)**: v1 retention is **indefinite for the life of the tenant** — a deliberate decision, not a carried gap — configurable retention is a later capability, and a removal soft-removes the record while any object purge follows retention | **0 (`D6d` ✓); `BR-090`** |
| A picked original may be a **HEIC/HEIF** or exceed the API's 15 MiB limit | The API accepts only JPEG/PNG/WebP and refuses oversized bodies. **Both halves decided** (D3b: converted on device; D3c: resized to fit) — the risk is now a Phase 2 implementation duty, not an open question | 2 (D3b ✓, D3c ✓) |
| EXIF/GPS inside a photo is stored and served verbatim | Location policy (`BR-038`) is open. **Answered (`D7` ✓, 2026-09-16)**: GPS/location EXIF and other unneeded metadata are **stripped from uploaded evidence by default**, keeping only what the application needs; `BR-038` stays open for any deliberate *product* use of location | **6c (`D7` ✓)** |
| The decode ignored a photo's EXIF orientation tag | Every portrait capture drew rotated 90° in the review preview, the tray, the gallery tiles and the viewer. **Fixed 2026-09-15 on the display path** (`JobPhotoImages` applies `JobPhotoOrientation` to what it decodes). **Landed for the stored half by Phase 4b (2026-09-15, `D7b` ✓)**: the preparation steps turn what they re-encode — through the shared `JobPhotoExifOrientation` read and turn the display path uses — so what Servora stores from now on is upright. Evidence already stored or uploaded stays historical and is **not** repaired | fixed (display, 2026-09-15) / fixed for new evidence (stored, Phase 4b 2026-09-15) |
| Accepted evidence cannot be removed at all | A manager who must take an accepted photo out of ordinary use — a wrong photo, a customer request — has no operation, and improvising one would mutate history. **Answered (`D6a`/`D6b` ✓, 2026-09-16) and implemented by Phase 6b (landed 2026-09-16)**: an audited, Manager-level `evidence.photo.remove` soft-removes it with actor, time and reason, the audit record is preserved, and **no edit route is built** | closed (`BR-089`) |
| A removed photo's bytes are not purged | A removal takes evidence out of ordinary use; it does not shred it. The object stays in the bucket (`BR-090`: physical purge is a retention concern, and nothing implements one) and the device's bounded image cache may keep a copy until the LRU evicts it (`D5`). Neither is a view: the photo is never drawn again and the content route answers `404` for it (`docs/api/job-photos.md` §4.1). If a tenant's compliance rule ever needs shredding, that is a deliberate retention capability, not a silent purge here | accepted (`BR-089`, `BR-090`, `D6d`) |
| Every read of evidence streams bytes through the API | Bandwidth and API load grow with the gallery, and the client cannot read at the edge. **Answered (`D15` ✓, 2026-09-16)**: reads move to **short-lived presigned GET URLs** issued after authorization, with the bucket private and no derived objects. The phase must first resolve the `Host`/plain-HTTP constraint `ADR-013` D7 records | **8 (`D15` ✓)** |
| The Add update sheet draws an audio kind no session can reach | A kind with no capability, no record and no playback could be mistaken for a working feature. **Answered (`D8` ✓, 2026-09-16)**: audio is implemented as its own evidence kind and `evidence.audio.add` is created **with** it; until then the kind is not offered anywhere (`canAddAudio = false`) | **9 (`D8` ✓)** |

## Phase log

| Entry | Date | What was done | Verification |
| --- | --- | --- | --- |
| 029 — Phase 0 opened | 2026-09-15 | This phase plan written: the state of the photo feature captured from a read-only inspection of `api/` and `android/`; the phased plan (0–7) defined; Phase 0 decisions D1–D8 recorded as questions with options, blockers and non-binding recommendations | **Documentation only.** No code changed, so no build, test, lint or e2e was run — there was nothing to run. The only applicable `qa.md` §15 item is the documentation one. |
| 029 — D1 answered | 2026-09-15 | D1 recorded from product ownership: evidence is authorized by a **dedicated evidence capability**, revocable per member, replacing `JOB_UPDATE` on the write and `customers.view` on the read. The default Technician role holds it, which extends `BR-009`. Raised **D1b** for the exact catalogue shape | **Documentation only.** No code changed. `Business Rules.md` `BR-008`/`BR-009`, `docs/api/job-photos.md` §2 and an ADR are to be updated in Phase 1, when the capability is implemented (`BR-040`) |
| 029 — D1b answered | 2026-09-15 | The per-kind catalogue recorded: `evidence.view` + `evidence.photo.add` implemented in Phase 1 and held by Manager and Technician; `evidence.audio.add` **agreed but reserved** and deliberately not created until audio exists (`BR-042`). Phase 1's work items, dependencies and verification were made concrete against those codes | **Documentation only.** No code changed. Phase 1 remains not started, and is now writable |
| 029 — D3 answered | 2026-09-15 | D3 recorded: the external camera stays unchanged and a **multi-select system photo picker** is added beside it (no `CAMERA` permission, no new dependency); each selected photo becomes its own draft and its own outbox operation. The design's in-app camera request was explicitly declined and stays open. Two consequences raised as **D3b** (non-JPEG picked bytes, e.g. HEIC) and **D3c** (a picked photo over the 15 MiB limit), which Phases 2 and 3 depend on; both phases and the risk table were updated for them | **Documentation only.** No code changed. Phases 2 and 3 remain not started and are not yet writable (D3b/D3c pending) |
| 029 — D3b answered | 2026-09-15 | D3b recorded: a picked photo that is not already JPEG/PNG/WebP is **converted on device to JPEG before the draft is written**, so the API, its byte sniffer, its content-type `CHECK` and the key extensions are untouched. Recorded with it: the conversion runs off the main thread per item, a failure is a localized refusal that records nothing (`BR-014`), the stored evidence for a converted photo is the re-encoded JPEG (which matters to the later EXIF/GPS decision), and a JPEG re-encode does **not** bound size, so **D3c remains open**. Phase 2's type half is now writable and its work item was made concrete | **Documentation only.** No code changed. Phase 2 remains not started; its type half is writable, its size item waits on D3c |
| 029 — D3c answered | 2026-09-15 | D3c recorded: an oversized photo is **downscaled/re-compressed on device to fit** the API's 15 MiB limit, the stored evidence is the resized version and the original is not kept. Recorded with it: the fixed pipeline order (source bytes → type step → size step → app-private file → draft row), that `MAX_JOB_PHOTO_BYTES`/`docs/api/job-photos.md` §3 remain the single owner of the limit and the client mirrors it, that an unfit photo is a localized refusal recording nothing, that the chosen target edge/quality is an implementation detail Phase 2 must publish with its measured result, and that both lossy steps bear on the later EXIF/GPS decision. **Phases 1, 2 and 3 are now fully writable** | **Documentation only.** No code changed. Phases 1–3 remain not started |
| 029 — Phase 1 landed | 2026-09-15 | The evidence capability set implemented (D1 ✓, D1b ✓). `EVIDENCE_PERMISSIONS` added to `api/src/auth/permissions.ts` following the `PROPERTY_PERMISSIONS` pattern and included in `PermissionCode`; `evidence.audio.add` documented there as the reserved extension point and **not** created (`BR-042`). New migration `api/drizzle/migrations/0010_evidence_permissions.sql` inserts both permission rows with bilingual names and descriptions (`BR-005`) and grants them to the default `MANAGER` and `TECHNICIAN` system roles, with the `meta/_journal.json` entry (`dev.md` §6 — no applied migration modified). Both Job photo routes repointed in `api/src/jobs/jobs.controller.ts`: `POST :id/photos` → `evidence.photo.add` (was `JOB_UPDATE`), `GET :id/photos/:photoId/content` → `evidence.view` (was `customers.view`), with the class and route docs updated. `api/src/database/run-development-seed.ts` seeds both capabilities for the QA accounts and adds them to the Technician role template. Docs: **new** `docs/decisions/015-evidence-capabilities.md` records D1–D4; `docs/api/job-photos.md` §2 rewritten for the new codes and its open question 1 closed for evidence (`docs/api/job-actions.md` §2 and `docs/api/job-details.md` §2 keep their own `jobs.*` note). **`BR-008`/`BR-009` remain product ownership's edit and are still outstanding (`BR-040`)**. Carried forward: the Android gate still reads `JOB_UPDATE` and hides the action from a default Technician, so the client half lands in Phase 3 (work item 7, `BR-011`). | **API** — unit: PASS (`make api-test`, 44 files / 375 tests, including the new `src/auth/permissions.spec.ts`); API e2e: PASS (`make api-test-e2e` against the local stack, 17 files / 229 tests, including `test/job-photos.e2e-spec.ts` 13 cases: unauthenticated `401`, `evidence.view`-only refused the upload with `403` **and nothing stored**, `evidence.photo.add`-only refused the read with `403`, a member of the default **Technician** role adding a photo and reading it back, the API-derived key, replay, foreign-tenant `404`, `503` with nothing recorded); lint: PASS (`make api-lint`); typecheck: PASS (`npm run typecheck`); build: PASS (`make api-build`); migration applied by the e2e global setup and the result inspected in PostgreSQL (both codes present, both granted to `MANAGER` and `TECHNICIAN`). **Android: not applicable** — this phase changes no Android source; no `adb` and no device command was run (`qa.md` §7.3). **Not run:** Android physical-device QA, which this phase does not require |
| 029 — Phase 2 landed | 2026-09-15 | The content-type-aware local capture pipeline (D3b ✓, D3c ✓). **New** `JobPhotoContentType` mirrors the API's three accepted types and its magic-number sniffer, so the client decides a photo's type exactly as the API will. **New** `JobPhotoProcessing` is the device-side port for the two preparation steps (`convertToJpeg`, `fitToUploadLimit`) with `DefaultJobPhotoProcessing` behind it, chosen targets **2048 px @ q85 → 2048 @ 70 → 1280 @ 70 → 1024 @ 60**, refusing the photo when none fits (only a lossy JPEG's size can be bounded by quality, so a resized photo is stored as JPEG — recorded with the decision). `JobPhotoSession` now runs **both** sources through one pipeline in the decided order — bytes → type step (`D3b`) → size step (`D3c`) → app-private file → draft row — records `mimeType` as the type the bytes were **proven** to be (`beginCapture` still names the camera's target `.jpg` because that is what the camera writes), writes the prepared bytes to the file the prepared type resolves to and removes the camera's file when the type moved it, and returns a typed `JobPhotoRecordResult` instead of a nullable photo: `NOT_SIGNED_IN`, `NO_BYTES`, `TYPE_NOT_ACCEPTED`, `TOO_LARGE`, `NOT_STORED`. A refusal records **nothing** — no row and no bytes left behind, so a file nothing could ever upload, remove or show is not left on the device (`BR-014`, §9). `JobPhotoFiles.fileFor` takes the type and gains `write(path, bytes): Boolean`, which **reports** whether the bytes were stored so no draft is filed for bytes the device does not hold — and a capture the camera already wrote is **not rewritten** when neither step changed its bytes, so a failed write can never destroy a valid capture. `recordPickedPhoto(jobId, bytes)` is the data-layer entry point Phase 3's picker calls (reading a picked content URI stays the picker's business; a picked photo's `capturedAt` is the device instant it was recorded, not an invented EXIF read — that is D7). A picked photo becomes the same kind of draft, queued by the same `job.photo.add` operation with the photo's own id as its idempotency key, so the offline standard's §13 is met with no new contract and no new conflict policy (`§5`, `§6`). `JobPhotoPayloads` names the upload's file part from the recorded type instead of assuming `photo.jpg`, so the part's declared type and name agree with the bytes the API sniffs. The `inSampleSize` rule moved into one `jobPhotoSampleSize` used by both the tray thumbnails and the new steps (`JobPhotoImages`' decoding behaviour is unchanged). **Two deviations from the written plan, recorded as such:** (1) the session does **not** switch dispatchers — it stays on the caller's, as it did before, because the expensive work (decode/scale/encode) is dispatched inside `JobPhotoProcessing` and a hard `Dispatchers.IO` hop made the ViewModel JVM tests unauthoritative; (2) the refusals are reported through the entry point that exists today, so three `JobPhotoFailure` codes and their EN/FR strings were added — without them a refused capture would still claim "the camera did not save a photo", which is exactly the `BR-042` misreporting the failure codes exist to prevent. Docs: this tracker's `Current phase`, its state-of-the-feature table (the Phase 1 capability repointing was stale there too) and `README.md`. | **Android** — unit: PASS (`make android-test`: **406 tests, 0 failures**, including the new `JobPhotoContentTypeTest` (5 cases: JPEG/PNG/WebP magic numbers, a non-WebP RIFF container, arrays too short to declare anything, the stored codes) and `JobPhotoSessionTest` (12 cases: an accepted type recorded as-is with neither step asked and the camera's own file kept unwritten, conversion recorded as JPEG, an oversized photo stored as the resized version, refusal for each of type/size/storage/no-bytes with nothing recorded and no bytes left, a picked photo through the same pipeline, a pick refused with no session or with no bytes handed over), plus a new `JobPhotoUploadHandlerTest` case pinning that the upload declares the recorded type (`image/png`) and its own file name); device-test sources compile: PASS (`./gradlew assembleDebugAndroidTest`); lint: PASS (`make android-lint`); debug build: PASS (`make android-build`, `app-debug.apk`). **No `adb` and no device or emulator command was run (`qa.md` §7.3).** **Physical-device QA: NOT RUN — device QA is the product owner's** (`qa.md` §7.3); the manual runbook for this phase's changed behaviour is handed over with it. |
| 029 — Phase 2 targets published | 2026-09-15 | The size step's targets and their measured result, recorded because the stored evidence for a resized photo **is** the resized version (`D3c`) and because the rung that produced it is part of the evidence record. The targets are `FIT_TARGETS` in `android/.../data/jobs/JobPhotoProcessing.kt` — **2048 px @ q85, 2048 @ q70, 1280 @ q70, 1024 @ q60** — tried in order, with a refusal if none fits. The on-device numbers are the product owner's physical QA; what was measured here is stated as what it is. | **Measured (indicative, off-device):** the JDK's JPEG encoder on a synthetic 4032×3024 image gives **0.52 MiB** at 2048 px / q85, 0.29 MiB at 2048 / q70, 0.13 MiB at 1280 / q70 and 0.07 MiB at 1024 / q60, against 2.54 MiB for the untouched full-size photo and the API's 15 MiB limit — so the first rung has roughly 30× headroom and the later rungs exist for content that compresses badly. This is **not** a device measurement: the Android encoder differs, and the on-device result is the product owner's QA. |
| 029 — Phase 3 landed | 2026-09-15 | The two photo sources in the Add update sheet (D3 ✓, and the phase question the plan left open). **New** `ui/jobs/JobPhotoPicker.kt` — `PickMultipleVisualMedia` at the system's own item limit, `PickVisualMediaRequest(ImageOnly)` and therefore **no storage or media-read permission and no new dependency**; an empty answer is a dismissed picker (nothing read, nothing recorded) and a launch no application can answer is reported rather than passing as a cancellation (`dev.md` §1). **New** `data/jobs/JobPhotoPickedItems.kt` — the port that reads an item's bytes through the device's content resolver, off the main thread, with `ContentResolverJobPhotoPickedItems` bound in `di/JobsOfflineModule.kt`, `FakeJobPhotoPickedItems` for the JVM and `inertJobPhotoPickedItems()` for the device tests. `JobPhotoSession.recordPickedPhoto` gained the **`phase`** it records the draft with (§ "Status" above: the phase in effect, as a capture's draft has, so a pick can never be trapped unuploadable), and an item that handed over nothing is its own refusal `JobPhotoRefusal.NOT_READ` rather than the camera's `NO_BYTES`, so the screen does not claim "the camera did not save a photo". `JobDetailsViewModel` gained `photosPicked(uris)`: a private queue takes **one item at a time** — read, prepared, written to app-private storage, recorded as its own draft with its own id — then opens the review panel the camera path already owns, and takes the next item when that review ends (`confirmCapturedPhoto` / `discardCapturedPhoto` / `keepCapturedPhoto`); a skipped item is reported **by its place in the pick** (`PhotoItemPosition`, "Photo 2 of 5: …") and the pass continues, with each skip held so every one is reported rather than the last one overwriting the others; the queue and the reports are dropped when the screen resets or reads another Job. `photoPickerUnavailable()` and `JobPhotoFailure.PHOTO_NOT_READ` / `PICKER_UNAVAILABLE` were added with EN/FR strings, and the screen composes the item's place into the report. **Work item 7 (the client gate):** `Permission.EVIDENCE_PHOTO_ADD` (`evidence.photo.add`) was added to the Android catalogue with `CustomerPermissionsUiState.canAddEvidencePhoto`; the update action is drawn on `canUpdateJob` **or** `canAddEvidencePhoto`, the note kind additionally requires `canUpdateJob` **and** a represented Visit, and the photo kinds require `evidence.photo.add` — so a default Technician reaches the camera and the picker without being given a Manager capability (`BR-009`, `BR-011`, `ADR-015`). `ui/jobs/JobUpdateSheet.kt` states the photo kind and then its two sources as targets of the same height (`Take photo`, `Choose photos`) with a new `ic_photo_library` glyph (lucide `Images`), because three targets in one 56 dp row cannot carry localized labels; a Job with no Visit is offered the sources directly. Docs: `docs/design/android-design-system.md` (the sheet row), `README.md`, tracker `028`'s open question 3 (closed with a pointer here), and this tracker's `Current phase`, state table and Phase 3 section. **Not changed, deliberately:** the evidence **read** side — the gallery tiles still fetch through the API, which refuses without `evidence.view` and draws the tile's placeholder, and nothing in the client gates that read (no capability is inferred from another). | **Android** — unit: PASS (`make android-test`: **414 tests, 0 failures**, up from 406, with 8 new `JobPhotoCaptureViewModelTest` cases pinning a two-item pick recorded and reviewed one at a time with its own ids and files, the phase in effect as the picked draft's phase, an unreadable item named by its place with the pass continued, an unprepared item refused with the rest recorded, every skipped item reported one at a time as the screen acknowledges them, a dismissed picker recording nothing, a pick with no session, and an unopenable picker, plus `JobPhotoSessionTest`'s picked-photo cases updated to the recorded phase and the new `NOT_READ` refusal); device-test sources compile: PASS (`./gradlew assembleDebugAndroidTest`); lint: PASS (`make android-lint`); debug build: PASS (`make android-build`, `app-debug.apk`). No API file was changed, so no API command was run. **No `adb` and no device or emulator command was run (`qa.md` §7.3).** **Physical-device QA: NOT RUN — device QA is the product owner's** (`qa.md` §7.3); the runbook below is the hand-off. |
| 029 — Phase 4 landed | 2026-09-15 | The full-size viewer, on **D4 = (a)**: the viewer only, with the caching/thumbnail options recorded as the new **`D4b`** rather than assumed. **New** `ui/jobs/JobPhotoViewer.kt` — a full-screen `Dialog` (`usePlatformDefaultWidth = false`) drawn on the theme's own `surface`/`onSurface` (no new colour token), showing the photo (`ContentScale.Fit`), the phase badge, a close action with its own 48 dp target and content description, and the technician's whole note, closed by its action or the platform's back gesture. It holds **only the photo id**: `viewedJobPhoto(jobId, photoId, pendingPhotos, activity)` resolves the phase, the note and the source from the records that already state them (the device's own file while it holds the photo — the id is the device's operation id — and the API's evidence once it does not), and answers nothing for a photo neither holds, so the viewer is never a second copy of the evidence and never shows a photo it cannot place (`BR-001`, `BR-042`). A photo that cannot be read or decoded is **reported** (`job_photo_viewer_unavailable`), not replaced. **`JobPhotoImages`** gained `localFullSize` / `jobPhotoFullSize` beside the 512 px thumbnail path, sharing one `decodeImage` with a per-caller sample-size rule, answering `null` for bytes this build cannot decode and deliberately **not** caching (one full-size bitmap would evict the tray's worth of previews). **`data/jobs/JobPhotoSampling.kt`** gained `jobPhotoFittingSampleSize(widthPx, heightPx, maxEdgePx)` — the *ceiling* rule the viewer needs, where the existing `jobPhotoSampleSize` is a *floor* rule for the encode pipeline — taking plain bounds so the arithmetic is verifiable on the JVM; the decode is bounded by `VIEWER_MAX_EDGE_PX` (2560 px, the widest supported screen), so a 4032 px capture is drawn at 2016 px instead of decoded at ~48 MB. **Tiles became tap targets**: `JobPhotoGalleryTile` (its whole column, with a localized `onClickLabel`) and the tray tile (the X that removes a never-submitted photo stays its own control); `JobActivitySection` and `JobPhotoTray` carry the tap through, and `JobDetailsScreen` holds the viewed photo's id in `rememberSaveable` so a rotation does not lose the viewer. **Localization:** `job_photo_viewer_open` / `_close` / `_unavailable` in EN and FR. **No API, database, permission or `ADR-013` change** — the read is the route Phase 1 already guards with `evidence.view`, so a session the API refuses is refused here exactly as on a tile (`BR-007`). Docs: `docs/design/android-design-system.md` (the viewer row and the tap targets), `README.md`, the tracker's `Current phase`, state table, Phase 0 log (D4 answered, D4b raised), Phase 4 section, risks and "not implemented" list. | **Android** — unit: PASS (`make android-test`: **425 tests, 0 failures**, up from 414, with the new `ui/jobs/JobPhotoViewerTest` — 6 cases: the device's own bytes with their phase and note, the backend's evidence once the device no longer has it, the device's copy preferred while both exist, nothing for a photo neither holds, no phase for a code this build cannot read, and a blank note as no note — and `data/jobs/JobPhotoSamplingTest` — 5 cases: a photo that already fits decoded as it is, a phone capture halved to fit, either orientation bounded, never over the ceiling across six shapes, and no declared bounds decoded at 1); device-test sources compile: PASS (`./gradlew assembleDebugAndroidTest`) with the three new Compose cases in `ui/jobs/JobDetailsScreenTest` (an accepted photo opened full size from the gallery, read through the full-size path and closed again; a pending photo opened from the bytes the device still holds; a photo that cannot be read reported rather than drawn); lint: PASS (`make android-lint`); debug build: PASS (`make android-build`). **No device or emulator command was run and no `adb` was used (`qa.md` §7.3)**, so the three Compose cases are **NOT RUN — device QA is the product owner's**; the runbook below is the hand-off. Nothing was changed in `api/`, so no API command was run. The requests-per-photo measurement `D4` named belongs to the caching change this decision did not take and is not claimed. |
| 029 — Photo orientation defect fixed | 2026-09-15 | Reported by product ownership: a photo captured in **portrait** appeared **landscape** in the review preview and in the gallery when a tile was tapped. Cause: `BitmapFactory` does not apply a file's EXIF `Orientation`, and the device's camera stores a portrait capture as sensor-landscape pixels plus that tag, so every decode drew it on its side. **New** `data/jobs/JobPhotoOrientation.kt` — the EXIF code (`1`–`8`) as a **pure** rule over a plain `Int` (clockwise degrees plus an optional mirror, following the mapping image loaders use), JVM-verifiable and free of the Android runtime; a code the build does not know (`0`, a value outside `1`–`8`, or no tag at all) is `Normal` rather than a guessed rotation (`BR-042`). **`data/jobs/JobPhotoImages.kt`** — the one `decodeImage` the 512 px thumbnails and the viewer's bounded full-size decode share now reads the file's tag with the **platform** `android.media.ExifInterface` (no new dependency, the fix option product ownership selected) and turns what it decoded: the turn copies nothing when a photo is already upright, releases the unturned original when it is not, and answers `null` — "cannot be presented" — rather than drawing unturned pixels if the turn itself fails (`BR-042`). One leaf layer is why the review preview, the pending tray, the gallery tiles and the Phase 4 viewer now all turn a photo the same way. The sample size is unchanged: a turn never changes which edge is longest, so the decode bounds (`VIEWER_MAX_EDGE_PX`, the 512 px thumbnail edge) still hold. **Presentation only** — not a byte is rewritten, and the API, the database, the permissions, `MAX_JOB_PHOTO_BYTES` and `docs/api/job-photos.md` are untouched; stored evidence that went through the Phase 2 re-encode keeps its known gap (no tag, landscape pixels), which is recorded as D7's question rather than silently changed here. Docs: the tracker's `Current phase`, state table, D7 note, risk table and this entry; `README.md`. | **Android** — unit: PASS (`make android-test`: **430 tests, 0 failures**, up from 425, with the new `data/jobs/JobPhotoOrientationTest` — 5 cases: all eight EXIF codes mapped to the pixel mapping each one means, the two portrait codes `6`/`8` turned a quarter turn with no mirror, `1` left exactly as stored, every code a quarter turn and never a scale, and an unknown code never invented into a rotation); device-test sources compile: PASS (`./gradlew assembleDebugAndroidTest`); lint: PASS (`make android-lint`); debug build: PASS (`make android-build`). **No `adb` and no device or emulator command was run (`qa.md` §7.3)**, so every on-device result is **NOT RUN — device QA is the product owner's**; the runbook below is the hand-off. Nothing was changed in `api/`, so no API command was run. |
| 029 — Phase 4b landed | 2026-09-15 | Re-encoded evidence is stored upright (`D7b` ✓). **New** `data/jobs/JobPhotoExifOrientation.kt` — the **one** EXIF read and the **one** pixel turn: `jobPhotoExifOrientation(bytes)` answers the orientation the source bytes declare (and `Normal` when the file states none, so nothing is guessed into a rotation, `BR-042`), and `Bitmap.turnedBy(orientation)` copies nothing when the photo is already upright, releases the unturned original when it is not, and answers `null` when the device cannot turn it. It sits **beside** the pure rule rather than inside it because `JobPhotoOrientation` is deliberately free of the Android runtime so the JVM keeps checking it (`qa.md` §6.1) — **no dependency was added**; the platform `android.media.ExifInterface` is the reader the display fix already chose. **`data/jobs/JobPhotoProcessing.kt`** — `convertToJpeg` and `fitToUploadLimit` now decode, turn by the **source** bytes' own orientation, **then** scale and encode, through one private `decodeUpright`; the size step reads the tag once for its whole target loop, and a turn the device cannot perform refuses the photo with nothing recorded instead of writing pixels known to be wrong (`BR-014`, `BR-042`). Because the size step's source is the type step's output — an upright JPEG that carries no tag — a converted-then-resized photo is turned exactly once. **`data/jobs/JobPhotoImages.kt`** — its private `jobPhotoExifOrientation` and `Bitmap.oriented` were **deleted** and the display decode calls the shared leaf, so the 2026-09-15 display fix and what is stored are one implementation and cannot drift; presentation is unchanged (same sample size, same "cannot be presented" answer). `FIT_TARGETS` and Phase 2's measurement are untouched: a turn never changes which edge is the longest, so what a resized photo is stored as differs only in orientation. **No API, database, permission, schema or uploaded-byte change**: a re-encoded photo is still a JPEG under the same limit. Docs: this tracker (`## Current phase`, the state table's `Preparation` and `Thumbnails` rows, the phases table, the `D3b`/`D3c`/`D7b` consequence notes, the risk row, the Phase 4b section, the orientation-fix runbook's closing note and the new `## Manual QA runbook — Phase 4b`), `docs/decisions/016-android-image-stack-and-viewer-zoom.md` (the shared read it promised now exists, and Phase 4c must keep it for the pipeline) and `README.md`. `docs/design/android-design-system.md` was **checked and needed no change**: its photo rows are about drawing a tile and the viewer, not about stored orientation. | **Android** — unit (JVM): **PASS** (`./gradlew testDebugUnitTest --rerun`: **430 tests, 0 failures, 0 errors, 0 skipped**, including `JobPhotoOrientationTest`'s 5 cases, which pin the turn the pipeline applies); lint: **PASS** (`lintDebug`, `abortOnError = true`); debug build: **PASS** (`assembleDebug`, `app-debug.apk`); device-test sources: **PASS** (`assembleDebugAndroidTest` compiles — its cases are the product owner's to run). The battery was run twice, the second time on the final tree, so the reported result is the tree committed. **No `adb` and no device or emulator command was run** (`qa.md` §7.3): the pixel turn needs `BitmapFactory`/`Bitmap`, so it cannot be exercised by a JVM test and is **NOT RUN — device QA is the product owner's**, handed over as `## Manual QA runbook — Phase 4b`. `JobPhotoProcessing` stays behind its port, so `FakeJobPhotoProcessing`/`PhotoCollaborators` still cover the session's decisions unchanged. Nothing under `api/` was changed, so no API command was run. |
| 029 — Phase 0 decisions recorded (D2, D4b, D5, D6c, D7b, D9, D10) | 2026-09-15 | Product ownership answered six questions and deferred one, so the roadmap no longer waits on Phase 0. **D4b = (a)** — the image stack is **Coil 3 behind the existing `JobPhotoImages` port** (memory + disk cache, sampling and EXIF left to the library), with the API read keeping its `401 → renew once` path and the cache keyed and cleared **per session subject**; server-derived thumbnails were not taken, so `ADR-013`'s open question 3 stays open. **D9 = (b)** — the viewer gains **pinch-zoom and pan** (Telephoto) on top of that stack; swiping between photos and a gallery-wide pager stay undecided. **D10 = (a)** — the zero-permission system picker remains the only library source (no `MediaStore`, no media-read permission, no in-app camera). **D7b = (a)** — the preparation steps turn the pixels of a photo they re-encode, so stored evidence is upright and needs no orientation tag, while evidence already recorded stays historical and is not repaired. **D6c = (a)** — a photo the API permanently refused may be explicitly discarded (file and row together). **D2 = (a)** — evidence is Job-level, as implemented. **D5 deferred** — Phase 5 stays blocked. Recorded with them: three new phases (**4b** stored orientation, **4c** image stack, **4d** viewer gestures) and a split-out **6a** (refused photos), so Phase 5 and the rest of Phase 6 stay blocked on `D5` and on `D6`'s remaining sub-questions; the new **`docs/decisions/016-android-image-stack-and-viewer-zoom.md`** (the decisions, the two `BR-007` constraints, the alternatives not taken, and what is deliberately not built); `docs/domain/job-visit-domain-model.md` §469 corrected; `docs/decisions/015-evidence-capabilities.md`'s open-question list updated with what is now decided; the viewer row of `docs/design/android-design-system.md`; and `README.md` | **Documentation only** (`BR-042`) — no production code was changed by this round, because the decisions are recorded before the phases that implement them. The pending EXIF-orientation fix was verified in the same session and landed first: `make android-test` — **PASS (430 tests, 0 failures**, run with `--rerun` so the result is this working tree's and not a cache hit); `make android-lint` — PASS; `make android-build` — PASS; `./gradlew assembleDebugAndroidTest` — PASS (device-test sources compile, `qa.md` §7.3). **No `adb` and no device or emulator command was run** (`qa.md` §7.3), so every on-device result is **NOT RUN — device QA is the product owner's**. Commits: `114c6d4` (the fix), `76ea0ae` (the decision record — this entry follows in its own commit) |
| 029 — Phase 4c landed | 2026-09-15 | **The image stack is Coil 3 behind `JobPhotoImages` (`D4b` ✓).** `io.coil-kt.coil3:coil-compose:3.6.2` is pinned and its compatibility recorded in `docs/decisions/016-…` (deliberately not `coil-network-okhttp`: the API keeps its one Retrofit transport). The port keeps its four names and both sources and now answers with the **request the stack loads**, built from the photo's identity (`data/jobs/JobPhotoImage.kt`), a size (512 px tiles, the viewer's bounded decode) and the session's cache key — or `null` when there is no session to attribute the photo to. `data/jobs/JobPhotoFetcher.kt` is the one reader: a pending photo comes from the app-private file that holds it, evidence from this session's disk-cache entry or from `GET /jobs/:id/photos/:photoId/content` with the `401 → renew once` renewal it already had, and anything unreadable answers nothing to draw (`BR-042`). `data/jobs/JobPhotoImageCacheScope.kt` releases the stack's caches on the first read of a session that is not the one they were cached for, and `jobPhotoImageCacheKey` puts the subject in the memory and disk keys (`BR-007`). One `ImageLoader` is built in `di/JobsOfflineModule.kt` (`JobPhotoImageModule`) and installed in `ServoraApplication`, so the composables draw through the app's own stack. `JobPhotoThumbnail`, the tray tile, the review preview and the viewer draw through `SubcomposeAsyncImage`, keeping their tags, sizes and "cannot be shown" state, which retires the viewer's hand-held `JobPhotoView` state machine (`JobPhotoViewerTest`'s resolution logic is untouched). Retired with it: the hand-rolled decode, the 24-entry `LruCache` and `jobPhotoFittingSampleSize` with its five cases; **`JobPhotoExifOrientation` stays** (the preparation steps need it), and the surviving sampling rule `jobPhotoSampleSize` now takes plain bounds so a JVM test can pin it. **Two deviations recorded rather than silent**: the viewer's request is `VIEWER_DECODE_EDGE_PX` (half of the 2560 px bound, because the library samples in powers of two and a request for the bound itself would decode a 4032 px capture at full size), and the release happens on the first read under a different session rather than at the instant of sign-out (the session owner does not depend on this feature's display cache). Documentation: this tracker (state table, phases at a glance, the Phase 4c section, this entry and `## Manual QA runbook — Phase 4c`), `docs/decisions/016-…` and `README.md`. Commit: `bc2a9a4` (this entry follows in its own commit) | `make android-test` — **PASS**: `./gradlew testDebugUnitTest --rerun` **450 tests, 0 failures, 0 errors, 0 skipped** across 46 suites, the result of this working tree and not a cache hit; the new cases are `JobPhotoFetcherTest` (11: a pending photo read from the file with the API never asked, evidence downloaded once and then served from the cache, the key per session, the release once per session, the `401 → renew once` retry, and every unreadable case answering nothing), `JobPhotoImageCacheKeyTest` (5) and `JobPhotoImageCacheScopeTest` (4). `make android-lint` — **PASS** (`lintDebug`, `abortOnError = true`). `make android-build` — **PASS** (`assembleDebug`, `app-debug.apk`). `./gradlew assembleDebugAndroidTest` — **PASS** (device-test sources compile). **No `adb` and no device or emulator command was run** (`qa.md` §7.3), so the library's on-device decodes, the cells as drawn and the cold-start cache measurement of work item 6 are **NOT RUN — device QA is the product owner's**, handed over as `## Manual QA runbook — Phase 4c`. Nothing under `api/` changed, so no API command was run. |
| 029 — Phase 4d landed | 2026-09-15 | **The viewer's photo zooms and pans (`D9` ✓).** `me.saket.telephoto:zoomable-image-coil3:0.19.0` is pinned in `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`, with its compatibility recorded in `docs/decisions/016-…` — which closes that ADR's open question 4. It is Telephoto's **Coil 3** integration (not the Coil 2 one): its transitive `coil-compose:3.2.0` resolves up to the pinned 3.6.2, its Kotlin-stdlib and Compose floors (2.1.21 / 1.8.0) are below what this module already compiles with (Kotlin 2.3.21, Compose BOM 2026.08.00), and it declares `minSdk 21` against this module's `minSdk 26` — so nothing is downgraded. Because it is the Coil 3 build, the request the port answers with is executed by the app's **own** `ImageLoader`: the feature's fetcher, the `401 → renew once` read and the session-keyed caches of Phase 4c are the ones in use, and no second loader, cache or HTTP path exists. `ui/jobs/JobPhotoViewer.kt` draws that request through `ZoomableAsyncImage` instead of `SubcomposeAsyncImage`: a pinch zooms, a drag pans within the photo's own bounds, a double-tap goes to the library's zoom ceiling (`DoubleClickToZoomListener.cycle()`, its default) and no scale goes below fit (`ZoomSpec`'s minimum factor is 1), with the ceiling left at the library's default rather than a number this phase invented — because the capture pipeline prepares evidence at no more than 2048 px on its longest edge (`D3c`), twice the fit scale already reaches roughly the photo's own pixels on a phone. The gesture surface is the area between the top row and the note, so the phase badge, the whole note and the 48 dp close action stay exactly where they were and the `Dialog` keeps the platform's back gesture. **The one design question the phase had to answer rather than assume** — the layer draws the photo but exposes no loading or error slot — is settled by `JobPhotoViewerImageState` (reading / shown / "cannot be shown") observed from the stack's **own result for the request the viewer already has**, through Coil's `ImageRequest.Listener`: one read rather than two (Coil reports cache hits through it as well, so a cached photo still leaves the reading state), a photo that cannot be read is still reported instead of being replaced (`BR-042`), and the photo's test tag is applied only once the stack has drawn it. The alternative not taken was accepting a blank area after a failed read. **Consequence recorded rather than left implicit**: to sub-sample, the layer needs a file and maps a disabled disk-cache policy to a *write*, so a photo the device still holds is also written into the feature's cache directory — a cache entry beside the authoritative app-private file, evictable at any time and session-keyed, with `BR-014`/`BR-015` unaffected. Nothing under `api/` changed, and the viewer's resolution logic (`viewedJobPhoto`) and its JVM cases are untouched. Documentation: the viewer row of `docs/design/android-design-system.md`, this tracker's Viewer/Thumbnails/Tests rows, its risk table, runbook 4c's superseded zoom item and the new runbook below, `docs/decisions/016-…` and `README.md`. Commit: `f071f49` (this entry follows in its own commit) | `make android-test` — **PASS**: `./gradlew testDebugUnitTest --rerun lintDebug assembleDebug assembleDebugAndroidTest` on this tree, **450 tests, 0 failures, 0 errors, 0 skipped across 46 suites** (no new JVM case — the phase added no pure logic, and `JobPhotoViewerTest`'s 6 resolution cases are unchanged and green); `make android-lint` — **PASS** (`lintDebug` with `abortOnError = true`, `checkDependencies = true`, so the new dependency was linted too); `make android-build` — **PASS** (`assembleDebug`, `app-debug.apk`); device-test sources — **PASS** (`assembleDebugAndroidTest` compiles, including the two new cases in `JobDetailsScreenTest`: zooming/panning without losing the phase, note or close action, and a photo the stack cannot read being reported). **No `adb` and no device or emulator command was run** (`qa.md` §7.3), so the gestures as drawn, the library's on-device sub-sampled decode and the shapes in each form factor are **NOT RUN — device QA is the product owner's**, handed over as `## Manual QA runbook — Phase 4d`. Nothing under `api/` changed, so no API command was run. |
| 029 — Phase 4e landed | 2026-09-15 | **The viewer pages through the Job's photos, and a photo can be saved on the device or shared with another application (`D11` ✓, `D12` ✓, `D13` ✓).** `ui/jobs/JobPhotoViewer.kt` draws the Job's photos as one `HorizontalPager`, built by three new **pure rules**: `viewedJobPhotoSequence` (the evidence the backend holds first, in the order Job Activity reports it, then the photos this device still holds, oldest first — one entry per photo id, each still resolved through `viewedJobPhoto`, so the device's own bytes keep winning while they exist), `jobPhotoViewerInitialPage` (the page the tapped photo is on, or `null` when no record holds it any more, which closes the viewer as before), and `jobPhotoViewerPagingEnabled` (a swipe is allowed only while the settled page reports a fit-sized zoom, ≤ 0.1 of Telephoto's own `zoomFraction` — the property the library's documentation uses for "is this zoomed in?"). Each page owns its own `rememberZoomableImageState()`, reports its fraction only while it is the **settled** page, and forgets its zoom when the pager leaves it — the library's own recipe for a pager. The chrome follows the photo: the phase badge, a localized position (`3 / 12`, drawn only when the Job has more than one photo), the note — now inside the page, so it pages with its photo, bounded at 160 dp and scrollable so a long one cannot push the photo out — the upload state of a photo the backend has not accepted yet (its own tag, because the tray is still composed behind the viewer), and two 48 dp actions. **The export** is `data/jobs/JobPhotoContentReader.kt` (extracted from `JobPhotoFetcher`, which now delegates to it: one evidence read, one `401 → renew once` path — `BR-007`, `BR-018`), `data/jobs/JobPhotoExport.kt` (`JobPhotoExportSource`, `JobPhotoExportOutcome`, `JobPhotoExporter`; `JobPhotoExportContent` reads the device's own file or the API, proves the type **from the bytes** with the served type as fallback and refuses bytes Servora does not accept rather than writing a file it cannot account for; the copy is `Servora-<photoId>.<ext>`; `DefaultJobPhotoExporter` does the device work on `Dispatchers.IO`), and `data/jobs/AndroidJobPhotoExportTarget.kt` (the only file that touches `Intent`/`ContentResolver` here): **save** inserts into the shared image collection as `Pictures/Servora` with `IS_PENDING` until the bytes are complete from API 29, and writes the file plus its `DATA` row on API 26–28; **share** stages the bytes in one app-private cache directory cleared before each export, hands `ACTION_SEND` the photo's own MIME type with a read grant for that one file, and reports a device with nothing to share with. Wiring: the `JobPhotoExporter`/`JobPhotoExportTarget` bindings, `Permission.EVIDENCE_VIEW` + `CustomerPermissionsUiState.canViewEvidence` (the client's first read-evidence gate, mirrored from the API's catalogue), `JobDetailsViewModel.savePhotoToDevice`/`sharePhoto`/`onSavePermissionResult` with `photoSaveAwaitingPermission` and four new failures plus two confirmations on the existing channels, `JobDetailsScreen`'s permission launcher and chooser title resolved from resources (`BR-028`), `AndroidManifest.xml`'s `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"`, the `evidence-share` `cache-path` in `res/xml/file_paths.xml`, the `ic_download`/`ic_share` glyphs, and 10 strings in both languages. Documentation: this tracker (the decision log's D11–D13, the Phase 4e section, the state table's viewer and new "Evidence out of Servora" rows, four risk rows, the "not implemented" list, `## Current phase`, this entry and `## Manual QA runbook — Phase 4e`), `docs/decisions/017-viewer-paging-save-and-share.md` (which also answers `dev.md` §10 / offline standard §13 for the slice: no mutation, no new local store, no synchronization — a read and a copy), the viewer row of `docs/design/android-design-system.md` and `README.md`. Commit: `7e0fafe` (this entry follows in its own commit) | `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks` — **PASS**: the JVM unit tests are **472 tests, 0 failures, 0 errors, 0 skipped across 47 suites**, up from 450/46 in Phase 4d — 22 new cases and one new suite (`JobPhotoExportTest`), with `JobPhotoViewerTest` (11: the old 6 resolution cases plus the sequence order, the single entry for a photo both records hold, an entry neither record can place being dropped, the initial page and the paging/zoom rule), `JobPhotoExportTest` (9: where each photo's bytes are read from, the name and the type proven from the bytes, what is refused, and every target outcome) and `JobPhotoCaptureViewModelTest` (8 more: save from the device, save from the API, the permission round trip retrying the same save, a declined permission, share including the chooser's title, an unreadable photo, a device with nothing to share with, and a photo no record holds exporting nothing); `lintDebug` — **PASS** (`abortOnError = true`, so the new manifest permission and both new sources were linted); `assembleDebug` — **PASS** (`app-debug.apk`); device-test sources — **PASS** (`assembleDebugAndroidTest` compiles, including the three new `JobDetailsScreenTest` cases: the position and both actions acting on the photo on screen, a tapped tray photo opening its own page of the one sequence, and the two actions being absent without `evidence.view`). **No `adb` and no device or emulator command was run** (`qa.md` §7.3), so the swipe/pan arbitration as felt, the gallery write on a real MediaStore, the share sheet and the API 26–28 permission prompt are **NOT RUN — device QA is the product owner's**, handed over as `## Manual QA runbook — Phase 4e`. Nothing under `api/` changed, so no API command was run. |
| 029 — Phase 0 answered in full (D5, D6, D7, D8, D14, D15) | 2026-09-16 | Product ownership answered every question this tracker was still carrying and stated the guiding position for evidence — **append-only once accepted, offline-first for the technician, and a storage lifecycle that follows the Job/evidence lifecycle rather than UI convenience**. **`D5`**: accepted evidence is readable offline — metadata required, bytes cached best-effort when the device already holds them or the technician opens/downloads them (never a full backfill), pending bytes guaranteed until synchronization, accepted bytes an evictable size-based LRU cache rather than a retention period. **`D6a`/`D6b`**: accepted evidence is **immutable** — no overwrite, byte replacement, phase change or silent delete; removal only through an explicit, audited, Manager-level `evidence.photo.remove` that soft-removes it while the audit record is preserved; **no edit-photo route**, and a note correction is new Activity. **`D6d`**: evidence belongs to the historical Job record, archiving a Job never deletes it, archived Jobs' evidence stays readable to those permitted, and v1 retention is **indefinite for the life of the tenant**. **`D6e`**: no hard Job deletion in normal flows, so nothing is orphaned, and a future administrative purge is a deliberate cascade with asynchronous, idempotent object deletion. **`D7`**: `phase` stays the only structured classification, the phase is draft state for that update only, and **GPS/location EXIF plus unneeded metadata are stripped from uploaded evidence by default**. **`D8`**: audio is in scope as its own evidence kind on the same abstraction, `evidence.audio.add` is created **with** it, generic file attachments stay out, and the dead audio affordance stays unoffered. **`D14`**: the sheet stays the canonical way to choose the kind, and **Take another** goes straight back to the camera — the current behaviour, so no code change. **`D15`** (`ADR-013` open questions 3 and 4): no derived thumbnails in v1; reads move to **short-lived presigned GET URLs** issued after authorization, bucket private, no second configured endpoint, uploads unchanged. Recorded as business rules **`BR-088` – `BR-091`**, with `BR-013`, `BR-015` and `BR-027` updated (`BR-040`); the new phases **5, 6b, 6c, 8 and 9**; **D6 closed** (Phase 6 keeps no work of its own) and Phase 7's dependencies widened to D1–D15. Documents: this tracker (header, `## Current phase`, the D5–D8 decision sections, the decision log's D5–D8 plus new **D14**/**D15**, the phases table, the five new or rewritten phase sections, the state table, four updated risk rows and three new ones, the `Not implemented` list and two QA runbooks), `Business Rules.md`, `docs/decisions/013-…` (a 2026-09-16 update answering its open questions 2–4 and recording the `Host`/plain-HTTP conflict Phase 8 must resolve) and `docs/decisions/015-…` (its open-question list), `docs/architecture/offline-first-architecture.md` (§9, §11.1, §11.4, §12), `docs/api/job-photos.md` (§1, §4, §7), `docs/design/android-design-system.md` and `README.md` | **Documentation only** (`BR-042`) — no production code, migration, permission, contract, schema or design was changed by this round, because the decisions are recorded before the phases that implement them. **No test, lint, build, e2e or Android command was run, and none applies**: nothing under `api/`, `android/` or the database changed. **No `adb` and no device or emulator command was run** (`qa.md` §7.3). What is owed next is implementation, not verification: **Phase 5** is the phase to run, with **6a**, **6b**, **6c**, **8** and **9** startable behind it |

| 029 — Phase 5 landed | 2026-09-16 | **The Job and its evidence are readable offline (`D5` ✓).**  **New** `data/jobs/JobEvidenceCache.kt` — the Job read's use of the working set: two rows (`WorkingSetEntityTypes.JOB_DETAILS`, `JOB_ACTIVITY`) holding the backend's own **wire response**, written by every successful read and mapped on the way out, so an offline answer runs the same mapper the online one ran (`BR-041`).  **`data/jobs/JobDetailsRepository.kt`** now reports the reason of each read through private `JobRead`/`ActivityRead` (the DTO is kept while the mapped object is returned) and falls back **only** when the failure could not reach the backend (`couldNotReachBackend()`: `NETWORK`/`SERVER`); a refusal is never replaced, and a read nothing has been reported for still reports its failure. `JobDetailsResult.Success`/`JobActivityResult.Success` carry a `ReadSource`, the ViewModel keeps `detailsSource`/`activitySource` (reset to `BACKEND` by an action's own answer, a write's refreshed timeline, or a failed read), and the screen marks both halves with the existing `OfflineNotice` (`JobDetailsLastReportedTag`, `JobActivityLastReportedTag`).  **The byte-cache policy is now one rule**: `jobPhotoImageDiskCacheKey` (new, pure, JVM-tested) answers `null` for a photo this device holds — so a pending upload's bytes can never be a cache entry and no size policy can evict them (`BR-014`, `BR-015`) — and the session's own key for accepted evidence, which is what the bounded, size-based LRU over the Coil disk cache holds (`JobPhotoImageModule` documents the constants and that nothing in the app prefetches).  **A photo that is neither held nor cached says why**: `JobPhotoContentReader.read` returns `JobPhotoContentRead` (`Bytes`/`Unreachable`/`Unavailable`, with an `IOException`, a `5xx` and an unanswered renewal classed as unreachable — the offline standard's own §13 rule, and `Unreachable` never used for a `401`/`403`/`404`/`422`), the fetcher carries it out as `JobPhotoBytesUnavailableException`, the viewer has its own report (`job_photo_viewer_unavailable_offline`, `JobPhotoViewerOfflineTag`) and a tile draws `ic_cloud_off` with a localized description — never a different picture; a photo this device no longer holds still reports the generic "could not be shown", since reconnecting would not fix it.  **No wire change**: no API, database, permission or contract was touched, so no API command was run. Docs: `offline-first-architecture.md` §9 (the one rule), §11 items 1/4, §12's adopters and online-only list, and its header; this tracker's status, `Current phase`, phase table, state table (new **Offline visibility** row), risk table, the Phase 5 section, and the Phase 5 runbook; `README.md`.  | **Android** — unit: PASS (`make android-test`: **505 tests, 0 failures**, with the new coverage: **8** cases in `data/jobs/JobDetailsRepositoryTest` — the Job and the activity each served from the last reported answer on an unreachable backend **and** on a `5xx`, a `403`/`404`/`422` never replaced, nothing reported yet, the newest answer replacing the older one, and another subject's row never served; **6** in `data/jobs/JobPhotoFetcherTest` — the reason carried out of a failed read (offline, a `5xx`, a refused session, a refusal that is not a `401`, a photo this device no longer holds), a failed read writing **nothing** to the cache, and a photo this device holds never entering it; **2** in `data/jobs/JobPhotoImageCacheKeyTest` — a `Local` photo given no disk key and evidence's disk key equal to its memory key; **4** in `ui/jobs/JobDetailsViewModelTest` — both sources reported, and a failure reporting neither; **2** in `ui/jobs/JobPhotoViewerTest` — the offline reason read back from the failure and everything else read as "could not be shown"). Device-test sources compile: PASS (`./gradlew assembleDebugAndroidTest`, with **2** new Compose cases in `ui/jobs/JobDetailsScreenTest`: both last-reported notices drawn for a device answer, and neither drawn for a backend answer). Lint: PASS (`make android-lint`, 46 findings, all pre-existing categories — **the first run crashed inside lint itself**, `Unexpected failure during lint analysis … this is a bug in lint or one of the libraries it depends on` while analysing `JobActivitySection.kt`; the identical re-run completed, and the compiled classes are newer than every source, so the report describes this build). Debug build: PASS (`make android-build`). **No device or emulator command was run and no `adb` was used (`qa.md` §7.3)**, so every on-device step is **NOT RUN — device QA is the product owner's**; the runbook above is the hand-off. |
| 029 — Phase 6a landed | 2026-09-16 | **A photo the API permanently refused is explicitly discardable (`D6c` ✓).**  `ui/jobs/JobPhotoTray.kt`: `JobPhotoPendingTile` draws a **Discard** `TextButton` (`jobPhotoRefusedDiscardTag`) exactly when the tray's upload state is `REFUSED`, under the `Refused by the server` reason it already reports — and the X a draft carries is **not** drawn as well, so one control clears the photo; the upload-state text and the count are unchanged.  `domain/model/JobPhoto.kt` now owns the three rules the data layer and the UI must share (`BR-041`): `isRemovable()` (`!submitted`), `isRefusedUpload(upload)` (`submitted && REFUSED`) and `isDiscardable(upload)` (`isRemovable() || isRefusedUpload(…)`), moved there from `ui/jobs/JobPhotoComponents.kt`, which no longer defines the photo's local-removal rule.  `data/jobs/JobPhotoSession.discard` decides from the **queue** instead of the caller's own record: it reads the photo's own upload state through a new private `uploadState(photo)` (over `uploadStates`), applies `isDiscardable`, deletes the file, removes the queued refusal when the upload was refused, and removes the draft row — so a refused photo loses file, row and queue row together, a queued or retrying one loses nothing, and a stale caller-supplied `submitted` flag cannot change the outcome.  `data/offline/OutboxStore.kt` gains `discardRefused(operationId)`: `RoomOutboxStore` deletes through the new `OutboxDao.deleteRefused(operationId, rejected)`, whose predicate carries the state, and `InMemoryOutboxStore` mirrors it — so only a terminal refusal can be removed through that route and waiting work can never be dropped by it (`BR-014`, offline standard §11 item 5).  `ui/jobs/JobDetailsViewModel.kt` stops reading the tray's loss of a refused photo as an acceptance: `discardedLocally` records it **before** the row leaves and only for a photo the tray reports as refused, is pruned once the tray has reported it, and is consulted by `anUploadWasAccepted` — so the Activity is not re-read for evidence the backend never accepted, while an accepted upload still is (`BR-001`, `BR-080`).  **Localization**: `job_photo_tray_discard` (EN *Discard* / FR *Supprimer*).  **No API, database, permission, contract or submitted byte changed** — the photo was never accepted, so there is no server-side object, route or capability to touch, and nothing removes evidence (that is Phase 6b).  Docs: this tracker's `Current phase`, phases-at-a-glance table, state table (**Pending tray / review**), risk table (the trapped-photo risk closed), Phase 6a section, phase log and the new runbook; `docs/design/android-design-system.md` (a refused tray tile's own action); `docs/architecture/offline-first-architecture.md` §9 (the file and the row go together), §11 item 5 (answered for evidence) and §12 adopter 4; `README.md`. | **Android** — unit: PASS (`make android-test`: **509 tests, 0 failures**, up from 505: **2** new cases in `data/jobs/JobPhotoSessionTest` — a refused photo discarded with its file, its draft row and its queued refusal, with nothing left replayable, and a photo whose upload is still waiting kept with nothing removed; **2** in `ui/jobs/JobPhotoCaptureViewModelTest` — a refusal the tray reports discarded without re-reading the timeline, and a queued photo the screen was asked to remove kept and reported. `PhotoJobRepository` now counts its timeline reads, so the accepted-upload case asserts the one re-read it earns while the discard case asserts none). Device-test sources compile: PASS (`./gradlew assembleDebugAndroidTest`, with one new Compose case in `ui/jobs/JobDetailsScreenTest`: the discard offered only for a refused photo and asking for that photo's removal, and neither control for a queued one). Lint: PASS (`make android-lint`: 47 findings, every one of a pre-existing category and none in a file this phase touched). Debug build: PASS (`make android-build`). **No device or emulator command was run and no `adb` was used (`qa.md` §7.3)**, so the Compose case is **NOT RUN — device QA is the product owner's**; the runbook above is the hand-off. Nothing was changed in `api/`, so no API command was run. |
| 029 — Phase 6b landed | 2026-09-16 | **Accepted evidence can be taken out of ordinary use, audited and without an edit route (`D6a` ✓, `D6b` ✓, `D6d` ✓).**  **Database**: `job_photo_removals` (`organization_id`, `job_photo_id`, `actor_membership_id`, `reason`, `recorded_at`; non-empty-reason CHECK; unique `(organization_id, job_photo_id)`) — the removal is **added history**, so `job_photos` stays append-only and holds every value it held (`BR-067`, `BR-089`). Migration `0011_job_photo_removals.sql` creates the table (regenerated `meta/0011_snapshot.json`), adds `evidence.photo.remove` with its bilingual name and description and grants it to the default **MANAGER** role **only**; `run-development-seed.ts` seeds the same row and grant.  **`auth/permissions.ts`**: `EVIDENCE_PERMISSIONS.PHOTO_REMOVE`.  **`jobs/job-photo.dto.ts`**: `parseRemoveJobPhotoDto` + `MAX_JOB_PHOTO_REMOVAL_REASON_LENGTH` (2000) — the reason is **required** (`BR-089` records it) and free text, because no catalogue is defined (`BR-042`).  **`jobs/job-photos.service.ts`**: `removeJobPhoto` resolves the Job and the photo through one left-joined `findPhoto`, refuses a photo that is gone (`JOB_PHOTO_NOT_FOUND`) or already removed (`JOB_PHOTO_ALREADY_REMOVED`, also on the unique-violation race), appends the removal row and answers `readJobActivity`; `readJobPhotoContent` left-joins the removal and answers `404` for removed evidence, so an ordinary read never discloses it.  **`jobs/job-activity.ts`**: the `JOB_PHOTO_REMOVED` kind, `JobActivityOptions.includeRemovedEvidence`, the removal rows read by joining `job_photos` (so a removal is bounded to the Job), `photoRows` filtered by the removed set for an ordinary read, and `photoRemovalReason` as the removal's own response field (normalized to `null` on every other event).  **`jobs/job-activity-query.dto.ts`** (new): `parseJobActivityOptions` — a closed `true`/`false` vocabulary, refused otherwise.  **`jobs/jobs.controller.ts`**: `POST :id/photos/:photoId/removal` on `evidence.photo.remove` answering the refreshed timeline (`BR-001`, `BR-080`), the activity route's audit-context gate (`403` when `includeRemovedEvidence=true` without `evidence.photo.remove`), and `photoAlreadyRemoved()` → `409 JOB_PHOTO_ALREADY_REMOVED`.  **Android**: `Permission.EVIDENCE_PHOTO_REMOVE` + `canRemoveEvidence`; `JobActivityKind.JOB_PHOTO_REMOVED`, `JobActivityEvent.photoRemovalReason`, the DTO field and the timeline label (its reason drawn as the entry's secondary text); `ActivityWriteResult` (renamed from `VisitNoteResult`) with `JobDetailsApi.removeJobPhoto`, the repository's `removeJobPhoto` and the `JOB_PHOTO_ALREADY_REMOVED` classification; `JobDetailsViewModel.removeEvidencePhoto` (refused without a reason, refused for a photo the screen does not hold as evidence, online-only) with `photoRemoval` in-flight state, `JobPhotoMessage.EVIDENCE_REMOVED` and the removal failures (`REMOVAL_NOT_PERMITTED`, `REMOVAL_NO_LONGER_AVAILABLE`, `REMOVAL_UNREACHABLE`, `REMOVAL_FAILED`); the viewer's `Remove evidence` control (new `ic_trash`, drawn last in the top bar and only on the capability), the `JobPhotoRemovalDialog` confirmation that requires a reason, and the screen/NavHost wiring; strings in both languages.  **Docs**: `docs/api/job-photos.md` §1/§2/§4/§5/§7, `docs/api/job-activity.md` §2/§3.1/§3.2/§3.4/§3.5/§4, `ADR-015` (the third capability), the design system's viewer row, the offline standard's §12 and the README. | **API unit**: PASS (`make api-test`, 386 tests). **API e2e**: PASS (`make api-test-e2e`, 246 tests, 17 files) — the new cases cover `401`, `403` without the capability (and nothing recorded), a role that holds the field capabilities being refused, the recorded removal with actor/reason, the evidence excluded from the ordinary activity read and its bytes answering `404`, the audit context containing the removed record with actor/time/reason, `403` for the audit context without the capability, `409` on a second removal, `404` for a photo the Job does not hold and for another organization, `400` with no reason, and the seeded bilingual catalogue row. **Android JVM**: PASS (`make android-test`, 518 tests) — the repository (trimmed reason, mapped events, the already-removed classification, the local copy following the write), the ViewModel (removal recorded and the timeline taken from its answer, a refusal reported without changing anything, no reason sent, a photo this screen does not hold) and the capability mapping. **Android lint**: PASS. **Android build**: PASS (`app-debug.apk`). **Device/emulator tests**: compiled (`assembleDebugAndroidTest`) and **NOT RUN — device QA is the product owner's**; the runbook is `## Manual QA runbook — Phase 6b`. **Left running**: the Gradle/Kotlin daemons were released (`make android-stop`); the foundation stack was already running and was left untouched. |



## Manual QA runbook — Phase 3 (product owner)

Requires the API reachable from the device (`adb reverse tcp:3000 tcp:3000`) and `make up`. The cases
that check the capability gate need a **default Technician** session and one whose role holds neither
capability; everything else works as a Manager.

```text
1. Sign in as a Manager and open a Job that a Visit represents.
2. Tap "Add update".
   Expect: the sheet opens with "Write note" in effect and its field ready to type in — no photo source
   is drawn yet, because the photo kind is not the kind in effect.
3. Tap "Add photo".
   Expect: the two sources appear — "Take photo" and "Choose from device" — and no note field is drawn.
4. Tap "Take photo".
   Expect: the sheet closes and the device's camera opens exactly as before; no permission prompt.
5. Capture a photo and tap Add in the review panel.
   Expect: the photo is in the tray with its phase badge.
6. Tap "Add update", then "Add photo", then "Choose from device".
   Expect: the device's own photo picker opens, it offers multi-selection, and no storage or media
   permission is asked for.
7. Select three photos and confirm.
   Expect: the review panel opens for the first of them, with the phase the last photo used already
   selected, and the photos after it are not in the tray yet.
8. Tap Add.
   Expect: the panel reopens for the second selected photo.
9. Set the phase to "Before work" and tap Add, then tap Add again for the third.
   Expect: after the last one the panel closes and all three are in the tray, each with its own badge —
   the first in the phase that was in effect, the second and third in "Before work".
10. Tap "Save photos", then wait or leave and re-open the Job.
    Expect: the photos appear in the gallery and the Activity, one entry each, and the tray empties as
    their uploads are accepted.
11. Tap "Add update" -> "Add photo" -> "Choose from device" and dismiss the picker.
    Expect: nothing is added, nothing is reported and the tray is unchanged.
12. In airplane mode, pick two photos and save them.
    Expect: both are kept on the device with their own upload state, and nothing is claimed as uploaded
    (the upload itself is covered by tracker 027's steps 10-12).
13. Sign in as a default Technician and open an assigned Job.
    Expect: "Add update" is present, the sheet offers the two photo sources and no note kind.
14. Sign in as a session that may neither update Jobs nor add evidence (a custom role).
    Expect: no "Add update" action, so neither the camera nor the picker is reachable.
15. Pick a 48 MP original, and a HEIC if the device produces one.
    Expect: each is taken after the device prepares it (Phase 2), or a report names the photo by its
    place in the pick ("Photo 2 of 3: ...") and says why it could not be added — never a silent drop.
```

Two things worth knowing while testing: a photo that could not be taken is named by its place in the
pick, and every skipped photo is reported in turn (dismiss one report and the next appears). Nothing is
recorded on the device for a skipped photo, so the tray holds exactly the photos that were taken.

## Manual QA runbook — Phase 4 (product owner)

Build: `make android-build` (`android/app/build/outputs/apk/debug/app-debug.apk`). The API and the
object store must be up (`make up`) so an accepted photo has bytes to read back; the capability cases
need a **default Technician** session, and one whose role holds neither `evidence.photo.add` nor
`evidence.view`.

```text
1. Sign in as a Manager and open a Job that already has accepted photos.
2. Tap a photo in the gallery at the head of Job Activity.
   Expect: the photo opens over the screen, full size; the phase badge and the whole note are
   readable; the rest of the screen is covered.
3. Tap the X in the top-right corner.
   Expect: the viewer closes and the Job Details screen is exactly as it was.
4. Tap the same photo again and press the device's back gesture instead.
   Expect: the viewer closes; the app does not leave the Job.
5. Tap a photo whose note is longer than a tile's snippet.
   Expect: the viewer shows the whole note, not the tile's shortened form.
6. Rotate the device while the viewer is open.
   Expect: the viewer is still showing the same photo.
7. Open "Add update" -> "Add photo" -> "Take photo", capture a photo and add it, then tap its tile in
   the tray.
   Expect: the viewer opens the photo the device holds, before it has been uploaded.
8. Save the photos, wait for the upload to be accepted, and tap the photo in the gallery.
   Expect: the same photo opens from the backend's copy, with the phase and note it was recorded with.
9. Sign in as a session whose role holds neither evidence capability and open a Job with photos.
   Expect: taps still open the viewer, and it reports that the photo could not be shown rather than
   drawing anything — the API's refusal, reported honestly (`BR-007`, `BR-042`).
10. Tap three different photos in turn.
    Expect: each opens the photo it was tapped on — never a different one, and never a tile's
    low-resolution preview stretched to the screen.
```

Two things worth knowing while testing: zooming is not implemented, so the photo is drawn to fit the
screen and cannot be pinched (`D4` left zoom out); and a photo is read each time the viewer opens, so a
slow connection shows a brief spinner before the picture appears (`D4b` is the open caching question).

## Manual QA runbook — photo orientation fix (product owner)

Build: `make android-build` (`android/app/build/outputs/apk/debug/app-debug.apk`). Steps 1–4 and 6–8 need
no API; step 5 needs the API and the object store up (`make up`), because the gallery reads the backend's
copy of the photo.

```text
1. Sign in and open a Job. "Add update" -> "Add photo" -> "Take photo".
2. Hold the phone in portrait and capture a photo.
   Expect: the review preview shows it upright, the way it was framed — not turned on its side.
3. Add it, then look at its tile in the pending tray.
   Expect: the tray tile is upright.
4. Tap that tile to open the photo full size.
   Expect: the viewer shows it upright, filling the screen at its longest edge.
5. Save the photo, wait for the upload to be accepted, then tap it in the gallery at the head of Job
   Activity.
   Expect: still upright — this is the backend's bytes, not the device's copy.
6. Repeat steps 2-4 with the phone held in landscape.
   Expect: a landscape photo is landscape, exactly as framed.
7. Repeat with a photo chosen from the device's own picker (an already-upright library photo), and with a
   front-camera photo if the device has one.
   Expect: each is presented the way the device's own gallery shows it — nothing turned or mirrored that
   should not be.
8. Capture a photo of something whose orientation is unmistakable (a door, a page of text) in portrait and
   read it in the viewer.
   Expect: it reads top to bottom, not sideways.
```

Worth knowing while testing: this is a **display** fix, so a photo's stored bytes are exactly what the
camera wrote — an uploaded portrait photo still carries its EXIF orientation tag, which is what any other
viewer (including a browser) uses. The **known exception at the time** was a photo the preparation steps had
to rewrite: one over the API's 15 MiB limit (resized) or one whose bytes were not JPEG/PNG/WebP (converted).
Those lose the tag with the rest of their metadata. **That exception is closed for new evidence by Phase 4b
(2026-09-15)**, which turns such a photo's pixels before encoding it, so there is no longer a sideways case
this build creates — see `## Manual QA runbook — Phase 4b` below. Evidence **already** stored or uploaded
from a re-encoded photo is historical and stays exactly as it is.

## Manual QA runbook — Phase 4b (product owner)

**What changed.** A photo the preparation steps rewrite — the `D3b` conversion (bytes that are not
JPEG/PNG/WebP, e.g. a HEIC pick) or the `D3c` resize (over the API's 15 MiB limit) — is now **stored
upright**: the step turns the pixels it decoded, by the orientation the source bytes declared, before it
scales and encodes. `Bitmap.compress` writes no EXIF orientation tag, so before this phase such a photo was
stored sideways with nothing in the file saying otherwise, and every client drew it sideways. A photo that
needs no rewriting is **unchanged**: it is stored byte for byte as the source wrote it, tag included.

Requires the API reachable from the device (`adb reverse tcp:3000 tcp:3000`) and `make up`. It is worth
re-running the first case of this runbook even if the display fix was already accepted, because it is the
path this phase must not have touched.

```text
1. Camera capture, portrait (no rewriting — the unchanged path).
   Sign in, open a Job, "Add update" -> "Add photo" -> "Take photo". Hold the phone in portrait and capture
   something whose orientation is unmistakable (a door, a page of text).
   Expect: upright in the review preview, in the tray tile, and in the viewer when the tile is tapped — as
   before this phase. Save it, wait for the upload to be accepted, then open it in the gallery at the head
   of Job Activity.
   Expect: still upright, and this is now the backend's own bytes.

2.  A picked photo that has to be converted (D3b), portrait.
    "Add update" -> "Add photo" -> "Choose from device", and pick a **HEIC/HEIF** portrait photo from the
    device's library (on many phones the camera's own library items are HEIC).
    Expect: the review panel opens for it and shows it upright; the tray tile is upright; tapping it opens
    it upright in the viewer.

3.  Save it and wait for the upload to be accepted, then tap it in the gallery.
    Expect: upright. This is the stored evidence — the converted JPEG has no orientation tag at all, so
    upright here means the pixels themselves are upright, which is what this phase changed.

4.  A picked photo that has to be resized (D3c), portrait.
    Pick a **portrait original larger than 15 MiB** (a 48 MP photo, a large PNG, a panorama).
    Expect: exactly as in steps 2-3 — upright in the review preview, the tray, the viewer, and in the
    gallery after the upload is accepted — and it uploads at all, so the resize still brings it under the
    API's limit.

5.  The same two cases in landscape (a landscape HEIC pick, a landscape oversized photo), and a
    front-camera photo if the device has one.
    Expect: each is stored and drawn the way the device's own gallery shows it — a landscape photo stays
    landscape and nothing that should not be mirrored is.

6.  Optional, outside Servora: fetch one of the re-encoded photos from the object store and open it in an
    image viewer or utility that ignores EXIF orientation (the local MinIO Console is at `make
    minio-console`; the key is `evidence/job-photos/{organizationId}/{jobId}/{photoId}.jpg`).
    Expect: it opens upright with no orientation metadata at all.
```

Worth knowing while testing: the two cases above are the only ones this phase can affect. A camera capture
or an already-JPEG/PNG/WebP pick that fits under the limit never enters a preparation step, so it is stored
byte for byte as the camera or the picker produced it, EXIF tag and all — which the display path still
honours. A photo that comes out **sideways** after an upload is a defect of this phase: note which case it
was (HEIC pick or oversized photo) and keep the source photo for the report.

## Manual QA runbook — Phase 4c (product owner)

**What changed.** Drawing a photo goes through the app's own image stack (Coil 3) instead of the hand-rolled
decode: a tile, the review preview and the viewer ask the feature what to load, and the library reads it (the
device's file for a photo not yet accepted, the API for evidence), samples it to the size it is drawn at, applies
the photo's own EXIF orientation, and keeps what it decoded in memory and on disk. **Nothing a technician sees
should change**, with two exceptions worth knowing before testing:

- a photo that has already been drawn **once in this session, or in an earlier one**, is drawn again from the
  device's cache rather than re-downloaded. Everything else about reads is unchanged: the Job and Activity reads
  are still online-only (`D5` is answered but **Phase 5 has not landed**, so nothing changes yet), so offline
  the screen still shows no Activity and no gallery at all — a
  cache is not offline evidence;
- the cache is **per signed-in member** and is released when a different member reads. That is what these steps
  check.

Requires the API reachable from the device (`adb reverse tcp:3000 tcp:3000`) and `make up`, with `make logs`
running so the content requests are visible. `adb` steps are yours to run (`qa.md` §7.3).

```text
1. The unchanged path: capture, upload, gallery, viewer.
   Sign in as a Technician, open a Job, "Add update" -> "Add photo" -> "Take photo" (portrait, something whose
   orientation is unmistakable), confirm it, save it, and wait until the gallery at the head of Job Activity
   shows it.
   Expect: the review preview and the tray tile show the photo upright; tapping the tray tile opens it upright
   in the viewer; after the upload is accepted the gallery tile shows it and tapping it opens it upright.
   Expect the same for "Choose from device" (a library pick), and for a photo large enough to be resized.

2. The cache is used instead of the API (this is the measurement the phase owes).
   With `make logs` visible, open the Job and let the gallery's tiles load, then close the screen, reopen it,
   and open one photo in the viewer.
   Expect: the first load reads each photo's content route once; reopening the Job and opening the same photo
   again produces **no new content requests** while the photos still appear. Before this phase every cold start
   and every eviction downloaded full-resolution bytes again.

3. The cache survives a cold start.
   Force-stop the app and reopen the Job.
   Expect: the photos appear, and the content route is asked for nothing (the entries are in the app's cache
   directory). If the Android system has reclaimed the cache in between, re-downloading is correct behaviour.

4. The cache is one member's, not the device's.
   Sign out, sign in as another member of the same organization who may also see the Job's evidence, and open
   the same Job, then the first member again.
   Expect: neither member is shown the other's photos from the cache; each member's own photos load from the
   API the first time they look. (Both members can legitimately see the same Job's evidence — what must not
   happen is one member's *cached bytes* being served to the other without the API answering.)

5. What cannot be shown is still reported, not replaced.
   Stop the API (`docker compose stop api`), then open a Job whose photos this device has never drawn in this
   session (or clear the app's storage first if unsure) and tap a tile.
   Expect: the gallery tiles present their phase/note without a preview, and the viewer states that the photo
   cannot be shown rather than drawing anything else — exactly as before this phase (`BR-042`). This is the
   image stack's own error state, so it is worth one look even though the visible behaviour is unchanged.

6. Zoom is still absent ~~(Phase 4d is next)~~. **Superseded by `## Manual QA runbook — Phase 4d`** (landed
   2026-09-15): zoom exists now, so this item is history rather than a step to run.
   Open any photo and pinch it.
   Expect (at the time of this phase): nothing zooms; the viewer closes with its X and with the platform's back
   gesture, as before.
```

Worth knowing while testing: the cache lives in the app's own cache directory (`cache/job-photo-cache`) and is a
**cache** — Android may reclaim it at any time, so a photo re-downloading after a long gap is correct and not a
defect. Nothing about evidence itself changed in this phase: the bytes uploaded, the API contract and the
database are untouched, so an upload from an older build and an upload from this one are the same evidence.

## Manual QA runbook — Phase 4d (product owner)

**What changed.** The full-size viewer's photo can now be **zoomed and panned**. It is drawn by
**Telephoto** (a Compose zoom library) over the same Coil 3 stack Phase 4c installed, which is what makes the
zoom real: what a zoomed photo shows is read from the file the stack cached, i.e. the photo's own pixels, rather
than a blow-up of the bitmap that was decoded to fit the screen. Nothing else about the viewer changed — the same
phase badge, the same whole note, the same close action — and the viewer still closes with the platform's back
gesture. No API, database, permission, pipeline or evidence changed.

Requires the API reachable from the device (`adb reverse tcp:3000 tcp:3000`) and `make up`. `adb` steps are yours
to run (`qa.md` §7.3).

```text
1. An accepted photo: zoom in, pan, and read the detail.
   Sign in as a Technician (or a Manager), open a Job with an accepted photo in its gallery, and tap the tile.
   Expect: the photo opens at fit, as before. Pinch outwards — the photo zooms under your fingers — and drag it
   while it is zoomed.
   Expect: zooming is smooth, the photo can be moved around while zoomed, moving stops at the photo's edges, and
   the photo cannot be made smaller than "fit". Zoom right in on something with fine detail (a label, a serial
   number, a scratch) and confirm the detail is legible rather than a soft enlargement.

2. Double-tap, and back to fit.
   Double-tap the photo.
   Expect: it zooms in (to the library's ceiling: twice the fit scale) and a further double-tap returns it to fit.

3. The photo's surroundings are untouched by the gestures.
   While the photo is zoomed and panned, look at the phase badge (top row), the note under the photo and the
   close action.
   Expect: all three are exactly where they were and legible — the gestures belong to the photo's own area. Then
   tap the close action from that zoomed state.
   Expect: the viewer closes, and reopening the same photo shows it at fit again (the viewer holds no zoom once
   it is closed).

4. The platform's back gesture still closes a zoomed viewer.
   Open a photo, zoom it, then use the back gesture from the screen's edge.
   Expect: the viewer closes. If panning near an edge feels like it is competing with the system gesture, please
   note what happened rather than working around it — the close action is always there as well.

5. A photo this device still holds (not accepted yet).
   With the network off (or the API stopped), capture a photo and open it from the capture tray.
   Expect: it opens and zooms exactly as an accepted one does, because it is read from the file the device
   already holds. Viewing it must not affect the pending upload, which still goes through when connectivity
   returns (`BR-014`, `BR-015`).

6. What cannot be shown is still reported, not replaced (`BR-042`).
   Stop the API (`docker compose stop api`), then open a Job whose photos this device has never drawn in this
   session (or clear the app's storage first if unsure) and tap a tile.
   Expect: the viewer states that the photo cannot be shown rather than drawing anything else, and nothing spins
   forever. This is now the viewer's own report, observed from the stack's result, rather than the stack's error
   slot — the zoom layer has no such slot.

7. Both form factors.
   Repeat 1–3 on a phone in portrait, on a phone in landscape, and on a tablet if one is available.
   Expect: the photo occupies the area between the top row and the note in each, zooming and panning behave the
   same way, and nothing is stretched, clipped or unreachable.
```

Worth knowing while testing: the evidence itself is untouched by this phase — a zoomed photo is the same bytes
the API served and the database holds; only how much of them is drawn changed. Because the capture pipeline
prepares a photo at no more than **2048 px on its longest edge**, twice the fit scale already reaches roughly the
photo's own pixels on a phone, which is why the ceiling is the library's default rather than a larger number. If
it feels too low in practice, say so: raising it is a one-line change and a product decision, not a defect.

## Manual QA runbook — Phase 4e (product owner)

**What changed.** The full-size viewer now **pages** through the Job's photos and carries two **evidence
actions**:

- swiping left and right moves to the next or previous photo of the **same Job** — the evidence the backend
  holds first (newest first, as the gallery lists it), then the photos this device still holds (oldest first,
  as the tray lists them), each photo appearing once;
- the top bar shows a position (`1 / 2`, `2 / 2`, …) centred with **Save to device** and **Share** as icons
  beside it, the photo's phase is a badge over the photo itself, and the note under the photo belongs to the
  photo on screen;
- **a swipe only pages while the photo is at fit.** Zoomed in, a drag pans the photo (as it already did) and
  the pager stays put; pinch back out and swiping pages again.

**Before you start.** Sign in as a Technician (or a Manager) with one Job that holds more than one photo: two
or more **accepted** photos (they appear in the Activity gallery), and — to see the mixed sequence — a photo
captured but not yet saved (it sits in the tray).

1. Open the Job, then open the **first** photo in the Activity gallery.
   **Expected:** the viewer shows the photo on its own black surface, its phase badge over the photo, a
   position such as `1 / 4` in the top bar, its note under the photo, and the two evidence actions as icons
   in the top bar. *(The chrome was refined on 2026-09-16 — `docs/tracker/031-android-photo-viewer-ui.md`.)*
2. Swipe left and right through the photos.
   **Expected:** each swipe shows the next or previous photo **with its own phase, note and position**, and the
   sequence carries on from the gallery into the tray's not-yet-uploaded photos. The first photo does not
   swipe before itself, and the last does not swipe past itself.
3. Open a photo from the **tray** tile instead, then swipe.
   **Expected:** the viewer opens on that photo's own page, and swiping reaches the accepted evidence in both
   directions.
4. Pinch to zoom in, then drag sideways.
   **Expected:** the photo **pans** and the pager does not move. Zoom back out to fit, then swipe.
   **Expected:** paging works again. The back gesture right after zooming still closes the viewer.
5. Open a photo whose note is several lines long.
   **Expected:** three lines of note are read under the photo with a **More** action; tapping it expands the
   complete note (**Less** puts it back), and the photo, the position and both actions stay on screen. A note
   that already fits draws no action at all.
6. Tap **Save to device**.
   **Expected:** the icon is briefly replaced by the viewer's own progress, then a confirmation appears **over
   the photo**, and the photo appears in the device's own gallery/Pictures app in a **Servora** album as
   `Servora-<photo-id>.jpg`. Nothing about the Job changes.
7. Tap **Share**.
   **Expected:** the system share sheet opens titled *Share photo*; choosing **WhatsApp** (or any other app)
   hands it the image. Cancelling the sheet changes nothing in Servora.
8. Close the viewer with its own action, and again with the platform's back gesture.
   **Expected:** Job Details is exactly as it was, and the Job, its Activity and the tray are unchanged.
9. Switch the app language to French and repeat steps 1–4.
   **Expected:** the same behaviour, with the position such as `1 / 4`, *Notes*, *Plus* / *Moins* under the
   note, and *Enregistrer sur l'appareil* / *Partager* as the two icons' accessibility names.
10. **Android 8 or 9 (API 26–28) only**: tap **Save to device** for the first time.
    **Expected:** Android asks for the storage permission and the save completes as soon as it is granted.
    Decline it on a later attempt and the app says it needs that permission rather than claiming a save.

**Known limitations, deliberately.**

- **A swipe is scoped to one Job.** There is no cross-Job or device-wide gallery.
- **Only the photo travels** when shared — no note, no Job or customer detail. A copy Servora saves or shares
  is outside its control: the app does not list, version, revoke or delete it.
- **A photo whose bytes cannot be read** (evidence with no connectivity, a session the API refuses) is still
  shown, and save/share report that it could not be read rather than writing an empty file.
- **A save or share is online-only.** Both read evidence through the API, and neither is queued (`D5` left
  the export path as it is). A photo the device still holds can be viewed, saved and shared with no
  connectivity at all. Offline, a photo whose bytes are on neither the device nor the image stack's cache
  is now reported as **not available offline** rather than as a generic failure (**Phase 5, landed
  2026-09-16**).
- Nothing here edits or deletes a recorded photo, and nothing deletes a saved or shared copy (`D6` ✓: no edit
  route, and the audited removal is Phase 6b).

## Manual QA runbook — review panel (2026-09-16)

The review panel a captured or picked photo is confirmed in. The API and the object store must be up
(`make up`); everything here is on the device.

1. Open a Job, tap **Add update** → **Add photo** → **Take photo**, and capture a photo.
   **Expected:** the review panel opens with the photo, the phase label and its three targets, the note
   field and the **Discard** / **Add** foot.
2. Tap the note field and type a line or two.
   **Expected:** the keyboard opens, the panel's content moves so the field stays **above** the
   keyboard, and the **Discard** / **Add** foot can be brought back into view by scrolling the panel.
   The panel must never leave the field under the keyboard.
3. With the keyboard open, scroll the panel up and down, then dismiss the keyboard.
   **Expected:** the content scrolls with nothing clipped behind the keyboard; after the keyboard is
   dismissed the whole panel is readable again.
4. Scroll back to the top and look at the photo.
   **Expected:** the **whole** photo is shown — for a portrait photo its top and bottom edges and for a
   landscape one its left and right edges are visible inside the panel's box, letterboxed inside the
   card rather than cropped. Compare it with the same photo's tile in the tray: the **tile** still
   crops, because a tile is a square that stands for the photo.
5. Fill the note, choose a phase and tap **Add**.
   **Expected:** the photo joins the tray with that phase and note; nothing else about the panel
   changed.
6. Repeat 1–4 from **Add update** → **Add photo** → **Choose from device**, picking a photo already on the
   device.
   **Expected:** the same panel behaves the same way for a picked photo.
7. A very long note (several lines) and a landscape photo, then the same in French.
   **Expected:** the field scrolls into view the same way with a tall field, and the labels stay
   English / French as before.

**Known limitations, deliberately.**

- The panel's content scrolls rather than fitting: on a short phone with the keyboard up, the foot
  (**Discard** / **Add**) is reached by scrolling, as the customer filter sheet's own foot already is.
- Cropping was not removed everywhere: the tray and gallery **tiles** still crop, deliberately.
- Nothing about what a photo is, whose it is or whether it may be added changed — the panel is still
  presentation over the same API (`BR-001`, `BR-007`).

## Manual QA runbook — Phase 5 (product owner)

Offline visibility of accepted evidence (`D5` ✓). Everything here is on the device except the one case
that needs the API unreachable; the API and the object store must be up (`make up`) to create the photos
and to read one back.

**Setup (online).** Sign in, open a Job that already has accepted photos on it (or add one through
**Add update** → **Add photo** and let it upload), then **open each photo in the viewer once** and go back.
That is the step that puts the bytes on the device: nothing prefetches them (`D5`).

1. **The Job and its timeline are readable offline.**
   Turn the device's connectivity off (airplane mode), and from the Jobs list open **the same Job**.
   **Expected:** the Job opens with its number, title, status, Visit and crew, the Activity timeline is
   there with its photo entries, and each of the two halves says **"Offline — showing the last update the
   server reported."** — the Job's notice above the Visit card, the timeline's under the **Activity**
   label. Nothing claims to be current.
2. **A photo the technician already opened is still readable offline.**
   Offline, tap a photo in the Activity gallery (or its timeline entry).
   **Expected:** the photo opens full size in the viewer with its phase badge and its note, exactly as
   online. This is the cache, and it is best-effort — a photo this device never opened is the next case.
3. **A photo the device does not hold is reported as not available offline.**
   Offline, open a **different** Job's accepted photo that this device has never opened (clearing the app's
   cache in the system settings for Servora makes this certain).
   **Expected:** the viewer says **"This photo is not available offline. Connect to view it."** — not a blank
   screen, not a different photo, and not the generic "could not be shown". Its tile in the gallery shows
   the crossed-cloud glyph with the description "Not available offline".
4. **Nothing is lost by reading offline.**
   Still offline, queue a photo through **Add update** → **Add photo** (camera or picker), then re-open the
   Job and leave it.
   **Expected:** the pending photo is in the tray exactly as before, its bytes readable in the review
   panel and the viewer, and it still uploads when connectivity returns. A pending photo must never be
   dropped or reported as unavailable offline — its bytes are guaranteed until the server accepts them.
5. **Connectivity returning restores the current answer.**
   Turn connectivity back on and re-open the Job (or pull the screen again by leaving and returning).
   **Expected:** both notices are gone, the Job and timeline are the backend's current answers again, and a
   photo that was reported as not available offline opens normally.
6. **A refusal is never replaced by a local copy.**
   With the Job cached offline, remove or change that session's permission so the Job read is refused
   (that case needs a role without the Job permission, or a signed-out session).
   **Expected:** the screen reports the failure it was given — never the cached Job presented as if the
   backend had answered.
7. **Another session does not read the first session's Job.**
   Sign out (which clears the working set), then sign in as a different member with no connection.
   **Expected:** no Job from the previous session is shown; the read reports that it could not reach the
   server.
8. **French.**
   Repeat 1 and 3 with the app in French (`Paramètres` → langue).
   **Expected:** both notices and the offline photo report are in French.

**Known limitations, deliberately.**

- **The offline answer is the last one reported, not current.** A change another user made while the
  device was offline will appear once the backend answers again; there is no background refresh.
- **Only the Job and its Activity are cached.** The Jobs list, Manager Home, the photo picker and every
  action stay as they are, and a save or share of accepted evidence is still online-only.
- **The byte cache is a cache.** It is bounded by size and evicted least-recently-used, so a photo opened
  earlier may need the backend again. Nothing downloads the Job's photos to keep them: offline bytes are
  best-effort by decision (`D5`).
- **A `5xx` is treated as unreachable** (the offline standard's own classification, §13), so a server
  failure shows the last reported answer with its notice rather than an error screen.

## Manual QA runbook — Phase 6a (product owner)

Build: `make android-build` (`android/app/build/outputs/apk/debug/app-debug.apk`). The API must be up
(`make up`) and reachable from the device (`adb reverse tcp:3000 tcp:3000`). Nothing else changed: the
camera, the picker, the review panel, the tray's other states and the viewer are all as they were, so these
steps are about a refusal and what the technician can do with it.

A **permanent** refusal is what these steps need: run the app against a session whose role does not hold
`evidence.photo.add` (a custom role without it), so the upload is answered `403` and the queue settles on
"Refused by the server". A refusal the engine classifies as **retryable** ("Will retry") is deliberately
**not** discardable, so it is the wrong case for these steps.

```text
1. Sign in as a Manager and open a Job with no photos.
2. Tap "Add update" -> "Add photo" -> "Take photo", capture a photo, tap Add in the review panel.
   Expect: the photo is in the tray with the X that removes it (`BR-027`).
3. Tap "Save photos".
   Expect: the tile reports "Waiting to upload" (then "Uploading…") and the X is gone — the API may hold it
   now, so the device no longer removes it (`BR-014`).
4. Make the upload refuse permanently (a session whose role lacks the capability, as above).
   Expect: the tray keeps reporting the state it last read, and nothing is lost.
5. Leave the Job and open it again.
   Expect: the tile reports "Refused by the server" with a "Discard" action under it. The X is still
   absent: the refusal is why the action exists, and it is the tile's only control.
6. Tap "Discard".
   Expect: the tile leaves the tray and no failure is reported — the action did what it said.
7. Leave the Job and open it again.
   Expect: the tile is still gone, and it does not come back. Nothing is uploaded again: the refusal was
   finished, not pending.
8. Switch the app language to French, produce another refusal and repeat steps 5-6.
   Expect: "Refusé par le serveur" with "Supprimer" under it, and the same outcome.
9. Open a Job whose photo is merely queued or retrying, and try to remove it.
   Expect: no removal control is drawn at all; nothing the technician can tap removes a photo the API may
   still accept.
10. Open the Job's Activity after a discard.
    Expect: the timeline is unchanged — the photo was never accepted, so the Job never held it (`BR-001`).
```

**Known limitations, deliberately.**

- The tray reports the queue **as it read it**: a refusal that happens while the Job is already on screen
  becomes visible when the tray next reports, which is why step 5 reopens the Job. That is how the tray has
  always read the queue (`§7`); this phase did not change when that read runs.
- Only an upload the backend **refused** can be discarded. A queued or retrying one is never removed for
  the technician, because the API may still accept it (`BR-014`).
- Nothing here removes **evidence**: the backend never accepted the photo, so there is no server-side
  object, no route and no capability involved (`BR-088`; removing accepted evidence is Phase 6b).

## Manual QA runbook — Phase 6b (product owner)

Build: `make android-build` (`android/app/build/outputs/apk/debug/app-debug.apk`). The API and the object
store must be up (`make up`) and reachable from the device (`adb reverse tcp:3000 tcp:3000`). The capability
cases need **two sessions**: a **Manager** (whose role holds `evidence.photo.remove`) and one whose role does
not — a default **Technician** is exactly that, and a custom role without the capability works too.

```text
1. Sign in as a Technician and open a Job that has at least one photo in its Activity.
2. Open the photo in the gallery.
   Expect: the viewer's top bar carries Close, the position, Save to device and Share — and NO trash icon.
   Removing recorded evidence is a Manager action, so the technician is not offered it (`BR-089`).
3. Sign in as a Manager and open the same Job's photo.
   Expect: a trash icon is drawn after Save and Share.
4. Tap the trash icon.
   Expect: a confirmation dialog titled "Remove this photo?" states that the photo cannot be deleted, and
   the reason field is empty. The Remove action is greyed out (disabled) until a reason is typed.
5. Type a reason and tap "Remove".
   Expect: the viewer closes, a report says the evidence was removed, and the photo is gone from the
   gallery and from the Activity's photos — while a new timeline entry says a photo was removed, naming
   you and the reason you gave.
6. Leave the Job and open it again.
   Expect: the photo is still gone from the photos and the removal entry is still in the timeline
   (`BR-080`, `BR-089`).
7. Tap the removal entry's photo area or try to reopen the photo from any surface.
   Expect: there is nothing to open — the removed photo is not drawn anywhere, and asking the API for its
   bytes answers 404. Nothing is served in its place.
8. Repeat step 4 on a photo that has already been removed (the timeline shows the removal but no photo).
   Expect: it cannot be reached from the UI at all; if the API is asked directly, it answers 409
   "That photo has already been removed." — one photo has one removal, and no restore exists.
9. Switch the app language to French, open another Job's photo and start a removal.
   Expect: "Retirer la preuve" on the action, "Retirer cette photo ?" as the dialog title, "Pourquoi ce
   retrait ?" on the reason field, and the same behaviour.
10. Sign in as a Manager, open the same photo, and turn the network off (airplane mode) before confirming.
    Expect: "Removing evidence needs the server. Connect and try again." — the removal is online-only and
    is never queued (`BR-089`, offline standard §5, §8, §13.2).
11. Turn the network back on and confirm the removal.
    Expect: it applies exactly as in step 5.
```

**Known limitations, deliberately.**

- **Nothing purges the bytes.** The stored object stays in the bucket and the device's bounded image cache
  may keep a copy of what it drew until the LRU evicts it (`D5`, `BR-090`). Neither is a view of the
  evidence: it is never drawn again, and the API refuses its bytes (`docs/api/job-photos.md` §4.1).
- **A removal reason is free text.** `BR-089` requires a reason; its catalogue is not defined, so no picker
  or validation beyond "non-empty, ≤2000 characters" exists (`BR-042`).
- **There is no restore.** Removal is one-way in v1: a photo taken out of ordinary use stays out of it. A
  second removal is refused rather than recorded twice.
- **The removal is not an offline operation.** It is a Manager action that records a historical decision, so
  it needs the backend's answer (`BR-001`).

## Not implemented, and not to be assumed

- **Any evidence kind other than photos and audio.** Audio is decided **in scope** on the same abstraction
  (`D8` ✓, 2026-09-16) and its **API half is implemented** — `docs/tracker/035-android-audio-evidence.md`,
  Phase 9a landed 2026-09-16 (`ADR-018`, `docs/api/job-audio.md`); the Android recorder, offline upload and
  playback are that tracker's Phases 9b/9c. Generic files — PDFs, documents — are **out of scope in v1**
  (`BR-091`); video is not decided.
- **Editing or re-annotating accepted evidence.** `D6a`/`D6b` ✓ (2026-09-16) decide there is **no edit
  route**, in any client, ever, and that a caption/note correction is a new Activity. The audited **soft
  removal** a Manager performs is **implemented** (`BR-089`; tracker 029 Phase 6b landed 2026-09-16), and it
  is a removal rather than an edit: it appends a record and changes nothing the photo holds. **No restore**
  and no removal-reason catalogue exist.
- **Retention, visibility and audit of evidence.** Decided (`BR-090`: indefinite for the life of the tenant,
  no content versioning, and a future administrative purge only as a deliberate cascade) but **not
  implemented** — nothing purges an object today, and configurable per-tenant retention is a later
  capability.
- Thumbnails or any derived object in the object store (**decided by `D15` ✓ on 2026-09-16: none in v1**),
  and any `Cache-Control` on the content answer or OkHttp cache. The **request and preview cache is decided**
  (`D4b` ✓): Coil 3's memory and disk cache, keyed per session subject and released when a different session
  reads — **implemented by Phase 4c (landed 2026-09-15)**, in `JobPhotoFetcher`/`JobPhotoImageCacheScope` and
  the app's own `ImageLoader`.
- An **in-app camera** — declined in D3 for now, and **`D10 = (a)` does not re-open it**; the technician
  continues to capture through the device's own camera application, and the picker added by Phase 3 is the
  library source beside it.
- Choosing a photo the device holds no permission for, or any read outside what the picker hands over — the
  picker's grant is the only access Servora has, and **`D10 = (a)` keeps it that way**: no `MediaStore`, no
  media-read permission and no in-app gallery grid.
- **Swiping between the photos of different Jobs, or a device-wide gallery.** `D11` decides that the viewer
  pages **within the Job whose screen is open** — nothing pages past that Job's own photos, and no surface
  lists evidence across Jobs. **Paging inside the Job exists since Phase 4e (2026-09-15)**, as do
  **pinch-zoom and pan** (`D9 = (b)`, Phase 4d) and the two **export** actions (`D12`/`D13`, Phase 4e):
  together they change no API, no permission and no evidence.
- **Sharing more than the photo.** No note text, no Job or customer detail and no Servora link travel with a
  shared photo — `D13` decided the photo only.
- **Any copy Servora knows about.** A photo saved into the device's gallery or shared with another
  application is outside the app's control: it is not recorded, listed, versioned, revoked or deleted by
  Servora, and it is not evidence the API can answer for (`BR-027`; the evidence lifecycle is `BR-088` –
  `BR-090`).
- Any Angular surface for evidence, and any reporting over it (`BR-030`).
- **Presigned reads and any second configured storage endpoint.** `D15` ✓ (2026-09-16) decides reads move to
  short-lived presigned GET URLs issued behind the API's authorization, with the bucket private and **no
  second configured endpoint**; it is **not implemented** (Phase 8), which must first resolve `ADR-013` D7's
  `Host` and plain-HTTP constraints.
- **Repairing the orientation of evidence the backend already holds.** Servora presents a photo the way the
  file's own EXIF orientation says it should be, and it never rewrites bytes it has already stored or sent,
  so evidence recorded from a re-encoded photo stays as it is and is **historical** (`D7b`'s option (c) was
  not taken). From **Phase 4b** on, the preparation steps turn the pixels of a photo they re-encode, so a new
  resized or converted photo is stored upright rather than depending on a tag the re-encode discards.
- Visit-scoped evidence — **`D2` decides evidence is Job-level**, as implemented, and the domain document is
  corrected with that answer. An optional Visit link stays available later if a requirement ever names one.
