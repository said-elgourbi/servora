# Tracker 029 — Photo evidence after 028: permissions, picker, viewer, offline and lifecycle

**Status: PHASE 4b LANDED (2026-09-15) — re-encoded evidence is stored upright (`D7b` ✓).** Phases 1, 2, 3,
4 and 4b are implemented; **Phase 4c (image stack and preview caching, `D4b` ✓) is next**, then Phase 4d
(viewer gestures, `D9` ✓). Phase 5 stays blocked because `D5` was **deferred** by product ownership, and
Phases 6a and 6 wait on `D6c` ✓ (6a) and on `D6`'s remaining sub-questions (6).

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

**Phase 4c — image stack and preview caching (D4b ✓), then Phase 4d — viewer gestures (D9 ✓). Phase 5 stays blocked on the deferred D5.**

- **The decisions this section was waiting on are recorded (2026-09-15).** Product ownership answered
  **D2, D4b, D6c, D7b, D9** and **D10**, and **deferred D5**. Each answer, its consequences and the
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
- **Phase 4c (Coil 3) then Phase 4d (Telephoto) follow, in that order.** Coil is what makes the preview
  and the full-size decode cached and correct rather than hand-rolled; Telephoto is what makes zoom real
  on top of it, so 4d assumes 4c's stack. Both are Android-only, and neither touches the API, the
  database, the permissions or the upload pipeline.
- **Phase 5 is still blocked and is no longer next.** `D5` was **deferred** by product ownership, so
  accepted evidence and the Activity stay online-only, Phase 5 does not run, and the gap stays in the
  risk table below.
- **Phase 4 (full-size viewer, Android) is implemented and verified.** A tap on an accepted photo in
  the Job Activity gallery, or on a photo still waiting in the tray, opens the photo itself over the
  screen: the phase it was recorded in, the technician's whole note, and the photo drawn at the size
  the screen can hold. It was built on **D4 = (a)**, so no caching or thumbnail work was in scope, and
  the caching question the answer did not take is recorded as **D4b** rather than assumed. See the
  Phase log entry and `## Manual QA runbook — Phase 4`.
- **Phase 5 (offline visibility of accepted evidence) remains NOT startable and is no longer next** —
  product ownership **deferred** D5 on 2026-09-15 rather than answering it, so the phase stays blocked,
  accepted evidence stays online-only, and the gap stays recorded in the risk table rather than being
  assumed away.
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
- **Still open after this round:** `D5` (**deferred**) holds Phase 5; the remaining `D6` sub-questions
  (`D6a`, `D6b`, `D6d`, `D6e`) hold the rest of Phase 6, with `D6c` answered and split out as **Phase 6a**;
  `D7`'s remaining sub-questions (tags, phase persistence, EXIF/GPS policy) hold Phase 7's taxonomy; `D8`
  (audio and files) is untouched. `D2`, `D4b`, `D6c`, `D7b`, `D9` and `D10` are decided and no longer open.
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
- **D5 deferred (2026-09-15)** — the offline-visibility question was not answered, so Phase 5 stays
  blocked and unstarted; the risk it names stays recorded.
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

## State of the feature today (inspection of 2026-09-15, after Phase 4)

Photo evidence exists end to end for **Android + API only**. There is **no Angular application in this
repository**, so there is no web photo surface and no reporting over evidence.

| Concern | Today | Where |
| --- | --- | --- |
| Capture (camera) | the device's **external** camera app through `ACTION_IMAGE_CAPTURE` / `TakePicture`; no camera permission, no in-app camera (declined in D3) | `android/app/src/main/java/com/servora/android/ui/jobs/JobPhotoCapture.kt`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/xml/file_paths.xml` |
| Capture (library) | the device's own **photo picker** (`PickMultipleVisualMedia`, images only, system maximum items), launched from the update sheet; no storage or media-read permission, no new dependency; an item's bytes are read through the `JobPhotoPickedItems` port | `android/.../ui/jobs/JobPhotoPicker.kt`, `android/.../data/jobs/JobPhotoPickedItems.kt` |
| Entry point | one floating **Add update** action → sheet → the note kind (Job update capability) or the photo kind → the two sources: **Take photo** (camera) and **Choose photos** (picker) | `android/.../ui/jobs/JobDetailsScreen.kt`, `ui/jobs/JobUpdateSheet.kt`, `ui/navigation/ServoraNavHost.kt` |
| Client gate | the action and each kind are drawn on the capability the API enforces for it: `evidence.photo.add` for a photo, the Job update capability for a note — so a default Technician reaches the camera and the picker (`BR-009`, `BR-011`) | `android/.../domain/auth/Permission.kt`, `ui/customers/CustomerPermissionsUiState.kt`, `ui/jobs/JobDetailsScreen.kt` |
| Local bytes | `filesDir/job-photos/<subjectId>/<photoId>.<jpg\|png\|webp>`, partitioned by session subject and named for the type the bytes were **proven** to be; never a blob in the database | `android/.../data/jobs/JobPhotoFiles.kt` |
| Preparation | both sources go through one pipeline before a draft exists: the bytes are brought to a type Servora accepts (`D3b`, mirrored magic-number sniffer, JPEG conversion when the bytes are neither JPEG nor PNG nor WebP) and under the API's 15 MiB limit (`D3c`, resized/re-compressed as JPEG); a photo either step cannot deliver is refused with **nothing** recorded. **Since Phase 4b** each step also **turns the decoded pixels** by the orientation the source bytes declare, before scaling and encoding, so the JPEG it writes is upright: `Bitmap.compress` writes no orientation tag, and a photo whose pixels were not turned would be stored sideways with nothing to say otherwise (`D7b`) | `android/.../data/jobs/JobPhotoContentType.kt`, `JobPhotoProcessing.kt`, `JobPhotoExifOrientation.kt`, `JobPhotoSession.kt`, `JobPhotoRecording.kt` |
| Local metadata | Room `pending_job_photos` (draft, survives process death, `submitted` flag) | `android/.../data/jobs/PendingJobPhotoEntity.kt`, `PendingJobPhotoStore.kt`, `data/offline/OfflineMigrations.kt` |
| Upload | queued through the existing outbox (`job.photo.add`, `operationId` = photo id), replayed by the existing engine; the part declares the recorded type and its own file name; local file and row deleted **only** on `201` | `android/.../data/jobs/JobPhotoSession.kt`, `JobPhotoUploadHandler.kt`, `JobPhotoOperations.kt`, `data/offline/OutboxReplayEngine.kt` |
| API | `POST /jobs/:id/photos` (`evidence.photo.add`), `GET /jobs/:id/photos/:photoId/content` (`evidence.view`) | `api/src/jobs/jobs.controller.ts`, `job-photos.service.ts`, `job-photo.dto.ts` |
| Storage | S3-compatible store behind the `ObjectStorage` port; key `evidence/job-photos/{organizationId}/{jobId}/{photoId}.{ext}`; provider selected by `STORAGE_PROVIDER` (`noop` by default, so an upload is refused with `503`) | `api/src/storage/object-storage.ts`, `s3-object-storage.ts`, `storage.module.ts`, `storage-config.ts`, `ADR-013` |
| Database | `job_photos` (`phase` CHECK, content-type CHECK, byte-size CHECK, idempotency unique index) | `api/drizzle/migrations/0009_job_photos.sql`, `api/src/database/schema.ts` |
| Timeline | one Activity projection, newest-first; `JOB_PHOTO_ADDED` is Job-level and carries `photoId`, `photoPhase`, `body` (the note) | `api/src/jobs/job-activity.ts`, `docs/api/job-activity.md` §3.2, `android/.../ui/jobs/JobActivitySection.kt` |
| Gallery | a `LazyRow` of thumbnails at the head of Job Activity, phase badge + note snippet; each tile is a tap target that opens its photo in the viewer | `android/.../ui/jobs/JobPhotoComponents.kt` (`JobPhotoGallery`, `JobPhotoGalleryTile`) |
| Viewer | **exists since Phase 4**: a tap on a gallery tile or on a tray tile opens the photo itself in a full-screen dialog — phase badge, close action, the technician's whole note, and the photo drawn `ContentScale.Fit`; the surface is the theme's own (`surface` / `onSurface`), so no new colour token; a photo that cannot be read says so instead of drawing something else; closed by its action or the platform's back gesture; **no zoom, no gesture and no gallery-wide pager yet** — `D9 = (b)` decides **pinch-zoom and pan** on the one photo (Telephoto) in **Phase 4d**, while swiping between photos and a gallery-wide pager stay undecided (`D4`, `D9`) | `android/.../ui/jobs/JobPhotoViewer.kt`, wired in `ui/jobs/JobDetailsScreen.kt` |
| Pending tray / review | tray of not-yet-accepted photos with upload state, each tile a tap target that opens the viewer; review panel with preview, note and the three phase buttons, which a captured photo **and** a picked photo are each confirmed in; a pick's items are taken one at a time, and skipped items are reported by their place in the pick | `android/.../ui/jobs/JobPhotoTray.kt`, `JobPhotoReviewSheet.kt`, `ui/jobs/JobDetailsViewModel.kt` |
| Thumbnails | decoded on device from the stored bytes (`inSampleSize`, 512 px, 24-entry in-memory `LruCache`); the viewer decodes the photo itself at a bounded longest edge (`VIEWER_MAX_EDGE_PX`, 2560 px, uncached) from the same read; **no derived object server-side** (`ADR-013` open question 3, still open); **Coil 3 replaces the hand-rolled decode and cache in Phase 4c** on the **D4b ✓** answer — one memory + disk cache keyed and cleared per session subject, with sampling and EXIF left to the library. Every decode is turned by the photo's own EXIF orientation first, so a portrait capture is presented upright instead of on its side — the bytes are never rewritten, and the turn is skipped entirely when the file already states the pixels are upright. **Since Phase 4b** that read of the tag and that turn are one shared leaf (`JobPhotoExifOrientation`) that the preparation steps call too, so the display path and what is stored cannot drift; a photo the Phase 2 steps re-encoded has **no tag at all**, which is why those steps turn its pixels themselves (`D7b`, Phase 4b) | `android/.../data/jobs/JobPhotoImages.kt`, `JobPhotoSampling.kt`, `JobPhotoOrientation.kt`, `JobPhotoExifOrientation.kt` |
| Photo picker | **exists since Phase 3**: `PickMultipleVisualMedia` and a `PickVisualMediaRequest(ImageOnly)` launch, with each item's bytes read through `JobPhotoPickedItems`; still no `ACTION_PICK`, `GetContent`, `MediaStore` or CameraX — **`D10 = (a)` keeps the picker the only library source**, so the device's media library is never read and no media permission is requested. **Coil** (D4b) arrives in Phase 4c for **drawing**, not for reading the library | `android/.../ui/jobs/JobPhotoPicker.kt`, `android/.../data/jobs/JobPhotoPickedItems.kt` |
| Tests | API unit + e2e for the contract; Android JVM tests for the pipeline, the handler, the photo ViewModel (both sources), the viewer's decode bound and the viewer's photo resolution; instrumented Compose cases for tray/gallery, the update sheet's two sources and the viewer (opening, full-size read, reading nothing, closing) | `api/src/jobs/job-photo.dto.spec.ts`, `api/test/job-photos.e2e-spec.ts`, `android/app/src/test/.../JobPhotoContentTypeTest.kt`, `JobPhotoSessionTest.kt`, `JobPhotoUploadHandlerTest.kt`, `JobPhotoSamplingTest.kt`, `ui/jobs/JobPhotoCaptureViewModelTest.kt`, `ui/jobs/JobPhotoViewerTest.kt`, `android/app/src/androidTest/.../ui/jobs/JobDetailsScreenTest.kt` |

## Phases at a glance

| Phase | Goal | Depends on | Layers | Status |
| --- | --- | --- | --- | --- |
| 0 | Decide the open product questions below | — | none (product ownership) | **Partly answered — D1, D1b, D2, D3, D3b, D3c, D4, D4b, D6c, D7b, D9 and D10 answered (2026-09-15); D5 deferred; D6a/D6b/D6d/D6e, D7's remaining sub-questions and D8 awaiting** |
| 1 | Evidence capability set (who may add and read evidence) | **D1 ✓, D1b ✓** | API, database, docs | **Landed (2026-09-15)** |
| 2 | Content-type-aware local capture pipeline | **D3 ✓, D3b ✓, D3c ✓** | Android | **Landed (2026-09-15)** |
| 3 | Photo sources in the Add update sheet (gallery picker, multi-select) | **D3 ✓, D3b ✓, D3c ✓, Phase 1, Phase 2** | Android, localization, docs | **Landed (2026-09-15)** |
| 4 | Full-size viewer and a thumbnail strategy | **D4 ✓** | Android, docs | **Landed (2026-09-15)** — the viewer; its thumbnail/caching half is now **Phase 4c** on the **D4b ✓** answer |
| **4b** | **Re-encoded evidence is stored upright** | **D7b ✓** | Android, tests, docs | **Landed (2026-09-15)** — the preparation steps turn what they re-encode, through the shared read/turn leaf |
| **4c** | **Image stack and preview caching (Coil 3)** | **D4b ✓** | Android, tests, `docs/decisions/`, docs | **Next — startable.** Coil replaces the hand-rolled decode and cache behind `JobPhotoImages` |
| **4d** | **Viewer gestures (pinch-zoom and pan, Telephoto)** | **D9 ✓**, Phase 4c | Android, tests, design docs | Startable; after 4c |
| 5 | Offline visibility of accepted evidence | D5 — **deferred** | Android, `offline-first-architecture.md` | **Blocked — D5 was deferred 2026-09-15** |
| **6a** | **Refused photos are explicitly discardable** | **D6c ✓** | Android, tests, docs | Startable as its own slice |
| 6 | The rest of the evidence lifecycle (retention, delete/edit, orphan objects) | the remaining D6 sub-questions, D1 | API, database, Android, docs | Blocked on `D6a`/`D6b`/`D6d`/`D6e` |
| 7 | Angular parity and reporting over evidence | D1–D7, an Angular application | Angular, API | Blocked on D1–D7 and on the Angular application |

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
- **Server-derived thumbnails were not taken** (option (d)): `ADR-013`'s open question 3 stays open, so a
  tile may still download a full-resolution object — but **once** rather than on every cold start, because
  the client-side cache is what changes.
- **What the library replaces retires with its tests:** the viewer's ceiling rule
  (`jobPhotoFittingSampleSize`, `JobPhotoSamplingTest`) and the hand-rolled in-memory `LruCache`. The
  **pure orientation rule is not deleted** — the preparation pipeline needs exactly it for `D7b`, so its
  JVM coverage moves rather than disappearing.
- **The measurement `D4` named belongs here** and is reported by Phase 4c: bytes downloaded per photo per
  cold start, before and after.
- **It is a dependency, so it needs an ADR** (`dev.md` §4, `Project.md` §31): `docs/decisions/016-…`
  records the choice, the alternatives (an OkHttp-level cache; the platform decoder as it is today), what
  was deliberately not built, and that the library is maintained and compatible with the pinned toolchain
  (Kotlin, Compose BOM, OkHttp, `minSdk 26`).
- **A cache is not a retention policy.** The cached bytes are a cache: they may be evicted at any time and
  they decide nothing about offline evidence, which is `D5` and was deferred.

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
  `D4b = (a)` introduces (Phase 4c) is **not** an offline-evidence policy: it is evictable, has no
  retention rule, and must not be presented as answering this question.
- **No document changes beyond this tracker**: the offline standard already records these reads as
  online-only, which is still true, so the deferral adds nothing to it.

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
- **The rest of D7 is unchanged**: categories/tags versus `phase` only, phase persistence across process
  death, and the EXIF/GPS policy (`BR-038`) remain open.

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
  the library's sub-sampled decode is what provides detail, so `jobPhotoFittingSampleSize` and
  `JobPhotoSamplingTest` retire across Phase 4c/4d, and `D4`'s "the decode is not cached" consequence is
  replaced by the D4b cache.
- **The viewer's identity stays resolution, not drawing.** `viewedJobPhoto` — which photo, from which
  source (the device's bytes while it holds them, the backend's evidence otherwise) — is unaffected, and
  `JobPhotoViewerTest` keeps its six cases; what changes is the state the drawing has (reading, ready,
  unavailable) and the gesture layer above it.
- **Gestures must not fight the surface they are on**: the close action, the platform back gesture and the
  photo's panning have to coexist, and the phase badge and the note stay legible with a zoomed photo. That
  belongs to Phase 4d and to `docs/design/android-design-system.md`, whose viewer row currently records
  that zoom is deliberately not drawn.
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
| D5 | Offline visibility of accepted evidence | **Deferred** — option (d): revisited later rather than answered now. Accepted evidence and the Activity stay **online-only**, so Phase 5 does not run and the risk row stays open. Nothing is decided about photo bytes on the device, and a cache is not a retention policy | product ownership | 2026-09-15 | this tracker (D5); no other document changes — the offline standard already records these reads as online-only |
| D6 | Evidence lifecycle (delete/edit, refused photos, Job deletion, retention) | **D6c answered — (a): a refused photo may be explicitly discarded** — local file and queued row together, with the refusal reason shown — which satisfies `BR-014` on both sides. **`D6a`/`D6b` (delete/edit a recorded photo), `D6d` (retention) and `D6e` (Job deletion and orphan objects) remain awaiting** | product ownership | 2026-09-15 | this tracker (D6); implemented by **Phase 6a** — the rest of Phase 6 stays blocked |
| D7 | Metadata beyond the phase | **D7b answered — (a): the preparation steps turn the pixels** of a photo they re-encode, so stored evidence is upright and needs no orientation tag. Evidence already stored or uploaded is **historical and is not repaired**. **The rest of D7 (tags versus `phase` only, phase persistence, EXIF/GPS policy) remains awaiting** | product ownership | 2026-09-15 | this tracker (D7); **implemented in Phase 4b (landed 2026-09-15)**. No API, schema or uploaded-byte change; the pixel turn's verification is device-bound |
| D8 | Audio and files in v1 | *awaiting* | — | — | — |
| D9 | Viewer gestures and photo navigation | **Answered — (b): pinch-zoom and pan** on the one photo, Telephoto on top of the Coil 3 stack. Swiping between photos and a gallery-wide pager are **not** decided | product ownership | 2026-09-15 | this tracker (D9); `docs/decisions/016-…`; implemented by **Phase 4d**; the viewer row of `docs/design/android-design-system.md` changes with it |
| D10 | Device media access and the in-app gallery surface | **Answered — (a): the system photo picker stays the only library source.** No media-read permission, no in-app `MediaStore` grid and no in-app camera; the design's camera request stays open exactly as `D3` left it | product ownership | 2026-09-15 | this tracker (D10); no manifest, permission or design change |

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

**Status: LANDED (2026-09-15) — the viewer. The thumbnail/caching half is `D4b` ✓ and is now Phase 4c.**

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
from: it reads the photo through the route that already exists, that read has no `WorkingSetStore`
projection, and whether accepted evidence must be readable without connectivity is **D5**, **deferred on
2026-09-15** (`BR-032`). It queues nothing — a read never does — and it keeps nothing of its own once it
closes. `docs/architecture/offline-first-architecture.md` §12 records the photo reads in its online-only
list for exactly this reason.

**Not in this phase.** Zoom, gestures, a gallery-wide pager, video; the request cache, `Cache-Control`
and server-derived thumbnails (**D4b** — now answered and carried by Phase 4c); offline evidence (**D5**,
deferred); editing or deleting a photo (**D6**).

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

**Goal.** Previews and the full-size photo stop being decoded and re-downloaded by hand: one image stack
draws them, with a memory and disk cache, and the API read keeps the session behaviour it has today.

**Depends on.** **D4b ✓ = (a)**. Phase 4b is independent but should land first, because the stack's EXIF
handling reads the same tag the preparation steps stop relying on.

**Layers.** Android, tests, `docs/decisions/` (the dependency ADR), documentation. **No API change**: no
`Cache-Control`, no server-derived thumbnail, no permission change.

**Work.**

1. Add the dependency to `gradle/libs.versions.toml` + `app/build.gradle.kts`, following that file's
   "pinned deliberately" convention, and record the choice in `docs/decisions/016-…` with its justification
   and its alternatives (`dev.md` §4).
2. `android/.../data/jobs/JobPhotoImages.kt` — keep the interface and both sources; move decoding, sampling
   and orientation to the library. The **API read keeps the `401 → renew once` path** through
   `SessionAuthenticator` (a fetch/key path rather than a bare URL), so authorization behaves exactly as it
   does today (`BR-007`).
3. Cache partitioning: keys carry the **session subject** and the cache is cleared when the session ends, so
   a member signing in on the same device can never be served another member's evidence bytes from disk
   (`BR-007`, and the position `JobPhotoFiles` already takes with `filesDir/job-photos/<subjectId>/`).
4. The call sites move from a hand-held `ImageBitmap` to the loader's composable — `JobPhotoThumbnail`
   (`ui/jobs/JobPhotoComponents.kt`), the tray tile, the review preview and the viewer — keeping their test
   tags, their sizes (512 px tiles, the viewer's request bound) and their "cannot be shown" report
   (`BR-042`).
5. Retire what the library replaces: `jobPhotoFittingSampleSize` and `JobPhotoSamplingTest` (the viewer's
   ceiling rule) and the in-memory `LruCache`. The **pure orientation rule stays**, and so does the one
   read/turn leaf it now shares with the preparation steps — `data/jobs/JobPhotoExifOrientation.kt`,
   **landed in Phase 4b** — because the preparation pipeline still needs it after Coil takes over the
   display decode.
6. Report the measurement `D4` named: bytes downloaded per photo per cold start, before and after.
7. Documentation: the state table's "Thumbnails" row, `README.md`'s Android stack line, the ADR, and
   `docs/design/android-design-system.md` if it fixes how a preview is produced.

**Verification.** JVM: the `JobPhotoImages` fakes and `JobPhotoViewerTest` keep passing, and a case pins that
a cached photo is not re-read (the `RecordingJobPhotoImages` pattern in `JobDetailsScreenTest` moves to
whatever records a load). Instrumented Compose cases for tile, tray, preview and viewer still compile
(`./gradlew assembleDebugAndroidTest`) and are **NOT RUN — device QA is the product owner's** (`qa.md` §7.3).
Commands: `make android-test`, `make android-lint`, `make android-build`.

**Not in this phase.** Server-derived thumbnails (`ADR-013` open question 3); `Cache-Control` on the content
route; zoom and gestures (Phase 4d); offline visibility (`D5`, deferred).

## Phase 4d — Viewer gestures (D9)

**Goal.** The viewer's photo can be zoomed and panned, so the detail the photo was taken for is legible on
the device.

**Depends on.** **D9 ✓ = (b)** and **Phase 4c** — the zoom is a sub-sampled decode over the same stack, so
it is built on it rather than beside it.

**Layers.** Android, tests, `docs/design/android-design-system.md`.

**Work.**

1. Add `Telephoto` (its Compose zoom modifiers and its Coil 3 integration) to the version catalogue and the
   module, with its justification recorded in `docs/decisions/016-…` beside Coil (`dev.md` §4).
2. `android/.../ui/jobs/JobPhotoViewer.kt` — the photo becomes zoomable and pannable: double-tap to a scale,
   pinch to zoom, drag to pan within bounds, and no scale below "fit". The phase badge, the note and the
   close action stay where they are and stay legible.
3. Gesture coexistence: the platform back gesture and the close action must keep working while the photo
   pans, and the dialog must not swallow them.
4. Documentation: the viewer row of `docs/design/android-design-system.md` (it currently records that zoom is
   deliberately not drawn) and this tracker's viewer row.

**Verification.** Instrumented Compose cases for opening, zooming and closing, compiled by
`./gradlew assembleDebugAndroidTest` and **NOT RUN here — device QA is the product owner's** (`qa.md` §6.2,
§7.3); device-free checks `make android-test`, `make android-lint`, `make android-build`. A runbook for the
product owner covering a portrait and a landscape photo, a double-tap, a pinch, panning to each edge, and
closing from a zoomed state.

**Not in this phase.** Swiping between photos and any gallery-wide pager (not decided, `D9`); video; offline
evidence (`D5`, deferred).

## Phase 5 — Offline visibility of accepted evidence

**Goal.** A technician without connectivity can still see the Job's evidence and Activity to the extent
the decision requires.

**Depends on.** D5 — **deferred on 2026-09-15**, so this phase stays blocked and does not run: the current
behaviour stands and the reason is recorded in the offline standard (its §13 requires exactly that). It
becomes startable only when D5 is answered.

**Layers.** Android; `docs/architecture/offline-first-architecture.md`.

**Work.**

1. Adopt the existing stores for the read the decision names — the working set for the Activity
   projection (`data/offline/WorkingSetStore.kt`), following the Customer Detail precedent in tracker
   `025` (`data/customers/CustomerDetailCache.kt`).
2. `android/.../data/jobs/JobDetailsRepository.kt` states its read policy today; the cached answer is
   served when the API cannot be reached, and the screen reports that it is showing a last-known answer
   (`ui/components/OfflineNotice.kt` is the existing component for that).
3. If D5 is `(c)`, the photo **bytes** are cached too, which is a separate decision with its own
   retention rules — do not fold it in silently.
4. Update `docs/architecture/offline-first-architecture.md` §12 (the adopters list) and §13 (what this
   read now uses).

**Verification.** Offline/synchronization tests per `qa.md` §8: a first read offline, a cached read
offline, a refresh when connectivity returns, and — where the local database version changes — the Room
migration test. Commands as in Phase 2.

**Not in this phase.** Offline photo capture (already works); offline evidence upload (already works).

## Phase 6a — Refused photos are explicitly discardable (D6c)

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

**Verification.** JVM tests for the discard (file and row removed together, the refusal no longer replayable),
extending `JobPhotoSessionTest` and `JobPhotoCaptureViewModelTest`; a Compose case for the discard action
compiling in `JobDetailsScreenTest` and marked **NOT RUN — device QA is the product owner's**. Commands:
`make android-test`, `make android-lint`, `make android-build`.

**Not in this phase.** Deleting or editing a **recorded** photo (`D6a`/`D6b`), retention (`D6d`), Job deletion
and orphaned objects (`D6e`).

## Phase 6 — The rest of the evidence lifecycle

**Goal.** Every state evidence can reach has decided behaviour, including the ones reachable today with
none.

**Depends on.** The remaining D6 sub-questions separately — `D6a`/`D6b`, `D6d`, `D6e` — and D1 for whoever
performs a delete or edit. **`D6c` is answered and split out as Phase 6a**, so this phase no longer covers
refused photos.

**Layers.** API, database, Android, documentation.

**Work.** Sized by the decisions, and each item is separate work:

1. **Refused photos** — **answered (`D6c` ✓) and delivered by Phase 6a**, not here.
2. **Job deletion and objects** (D6e) — `job_photos` cascades on Job deletion while the stored objects do
   not, so a Job deletion orphans objects. Whatever is decided lands in the Job deletion path (once
   `BR-021` decides Job deletion itself) and, if objects must go, in the `ObjectStorage` port, which has
   no deletion operation today.
3. **Delete or edit a recorded photo** (D6a/D6b) — only if decided: a route, an append-only history, a
   permission from Phase 1's catalogue, and `docs/api/job-photos.md` §7's "nothing in this contract edits
   or deletes a photo" changes with it.
4. **Retention** (D6d) — only if decided: what is kept, for how long, and who may see it (`BR-027`,
   `BR-033`).

**Verification.** Authorization e2e for any new route; append-only history tests where a change records
history; Android JVM tests for whatever the tray gains; the object lifetime stated explicitly against a
real local stack (`make minio-ls`).

**Not in this phase.** Angular; any retention policy that has not been decided.

## Phase 7 — Angular parity and reporting over evidence

**Goal.** The management surface sees the same evidence the Android client does, governed by the same
permissions, with reporting where the product wants it.

**Depends on.** D1–D7; and the Angular application itself, **which does not exist in this repository
yet** (`Project.md` §23 names Angular, but there is no web project to change and no Playwright
configuration to extend).

**Layers.** Angular, API (only as Phases 1–6 decided).

**Work.** To be written when the Angular application exists and its own rules apply. What this tracker
fixes now is the contract it must consume: `docs/api/job-photos.md` and `docs/api/job-activity.md` are the
authority, the same stable codes are used (`BR-041`), the same labels are resolved client-side
(`BR-028`), and evidence reporting must respect the requesting user's permissions (`BR-030`) — no report
may expose evidence a user may not read.

**Verification.** Angular unit/component tests, Playwright journeys for the evidence surfaces, and the
shared contract verified against affected applications (`qa.md` §12).

**Not in this phase.** Any change to the evidence model that Phases 1–6 did not decide.

## Risks carried into these phases

Recorded from the implementation inspection. Each one is either why a phase exists or a hazard a phase
must not step on.

| Risk | Why it matters | Phase that addresses it |
| --- | --- | --- |
| The default Technician role cannot add a photo (`JOB_UPDATE` guard, `BR-009`) | The audience `BR-015` names gets `403`; a picker is useless to them. **Decided by D1/D1b; Phase 1 implements the fix** | 1 |
| `/photos/:id/content` reads use `customers.view` while the write uses `JOB_UPDATE` | Interim and inconsistent authorization on one feature. **Decided by D1b** (`evidence.view` / `evidence.photo.add`); Phase 1 implements it | 1 |
| Every gallery tile downloads full-resolution bytes; no HTTP cache, no `Cache-Control`, a 24-entry in-memory cache | Repeated multi-megabyte downloads per photo, per cold start. **Decided (`D4b` ✓)**: Coil 3 with a memory and disk cache lands in Phase 4c, so the per-cold-start re-download stops. A full-resolution object may still be downloaded **once**, because server-derived thumbnails were not taken (`ADR-013` open question 3) | **4c (`D4b` ✓)** |
| An image cache of evidence on a shared device | Cached bytes are one subject's evidence, so a cache keyed only by job and photo id would let a second member signing in on the same device read the previous member's photos without the API answering (`BR-007`) | **Decided (`D4b` ✓)** — the cache is keyed and cleared per session subject; Phase 4c must implement it |
| Gallery tiles are inert; no viewer exists although the design requires one | The implemented screen cannot show a photo full size. **Delivered by Phase 4**: gallery and tray tiles are tap targets, and the viewer shows the photo, its phase and its note | 4 ✓ |
| The local pipeline hardcodes JPEG (`.jpg`, `image/jpeg`) | A picked PNG/WebP would be refused by the API's declared-versus-sniffed check | 2 |
| No photo picker existed at all | The design's "Existing Photos" and multi-select were unimplemented. **Delivered by Phase 3**: the system photo picker, multi-select, one draft per item, and a per-item report for a photo that cannot be taken | 3 ✓ |
| The tray's "Take another" action opens only the camera | After a pick, a second library photo needs the sheet again, while the design's attachment row pairs the two sources. Whether the tray gains a picker target, or the sheet stays the single way in, is a presentation decision the design owner has not taken — so Phase 3 changed nothing here | open (design owner) |
| Job Details and Activity reads are online-only | Offline, accepted photos and the timeline disappear; only the pending tray remains. **`D5` was deferred on 2026-09-15**, so the gap is knowingly carried rather than closed | **5 — blocked (`D5` deferred)** |
| A refused upload keeps its file and row with no affordance | Trapped evidence and unbounded device storage. **Decided (`D6c` ✓)**: the tray gains an explicit discard, delivered by Phase 6a | **6a (`D6c` ✓)** |
| `job_photos` cascade-deletes rows while the objects stay | Orphaned objects in the bucket on Job deletion | 6 |
| Photos are Job-scoped while `docs/domain/job-visit-domain-model.md` §469 says Visit-scoped | The domain document contradicted the implemented model. **Decided (`D2` ✓)**: evidence is Job-level and §469 is corrected with the decision | **0 (`D2` ✓)** |
| `STORAGE_PROVIDER` defaults to `noop` | A local stack that does not select `s3` refuses every upload with `503` | operational, not a phase |
| No retention or delete rule exists; the store has no lifecycle rule | Evidence accumulates permanently | 6 |
| A picked original may be a **HEIC/HEIF** or exceed the API's 15 MiB limit | The API accepts only JPEG/PNG/WebP and refuses oversized bodies. **Both halves decided** (D3b: converted on device; D3c: resized to fit) — the risk is now a Phase 2 implementation duty, not an open question | 2 (D3b ✓, D3c ✓) |
| EXIF/GPS inside a photo is stored and served verbatim | Location policy (`BR-038`) is open | 0 (D7) / 6 |
| The decode ignored a photo's EXIF orientation tag | Every portrait capture drew rotated 90° in the review preview, the tray, the gallery tiles and the viewer. **Fixed 2026-09-15 on the display path** (`JobPhotoImages` applies `JobPhotoOrientation` to what it decodes). **Landed for the stored half by Phase 4b (2026-09-15, `D7b` ✓)**: the preparation steps turn what they re-encode — through the shared `JobPhotoExifOrientation` read and turn the display path uses — so what Servora stores from now on is upright. Evidence already stored or uploaded stays historical and is **not** repaired | fixed (display, 2026-09-15) / fixed for new evidence (stored, Phase 4b 2026-09-15) |

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
   Expect: the two sources appear — "Take photo" and "Choose photos" — and no note field is drawn.
4. Tap "Take photo".
   Expect: the sheet closes and the device's camera opens exactly as before; no permission prompt.
5. Capture a photo and tap Add in the review panel.
   Expect: the photo is in the tray with its phase badge.
6. Tap "Add update", then "Add photo", then "Choose photos".
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
11. Tap "Add update" -> "Add photo" -> "Choose photos" and dismiss the picker.
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
    "Add update" -> "Add photo" -> "Choose photos", and pick a **HEIC/HEIF** portrait photo from the
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

## Not implemented, and not to be assumed

- Audio, files, video and any evidence kind other than photos (`BR-027`).
- Editing, deleting or re-annotating a recorded photo.
- Retention, visibility, audit and versioning of evidence (`BR-027`, `BR-033`).
- Thumbnails or any derived object in the object store (`ADR-013` open question 3, **still open**), and any
  `Cache-Control` on the content answer or OkHttp cache. The **request and preview cache is decided**
  (`D4b` ✓): Coil 3's memory and disk cache, keyed and cleared per session subject, in Phase 4c.
- An **in-app camera** — declined in D3 for now, and **`D10 = (a)` does not re-open it**; the technician
  continues to capture through the device's own camera application, and the picker added by Phase 3 is the
  library source beside it.
- Choosing a photo the device holds no permission for, or any read outside what the picker hands over — the
  picker's grant is the only access Servora has, and **`D10 = (a)` keeps it that way**: no `MediaStore`, no
  media-read permission and no in-app gallery grid.
- **Swiping between photos, and any gallery-wide pager.** A tap opens the one photo it was tapped on (`D4`
  left gestures out). **Pinch-zoom and pan are now decided** (`D9 = (b)`, Phase 4d) and are **not** part of
  what exists today.
- Any Angular surface for evidence, and any reporting over it (`BR-030`).
- Presigned reads, and any second storage endpoint (`ADR-013` D7 and its open question 4).
- **Repairing the orientation of evidence the backend already holds.** Servora presents a photo the way the
  file's own EXIF orientation says it should be, and it never rewrites bytes it has already stored or sent,
  so evidence recorded from a re-encoded photo stays as it is and is **historical** (`D7b`'s option (c) was
  not taken). From **Phase 4b** on, the preparation steps turn the pixels of a photo they re-encode, so a new
  resized or converted photo is stored upright rather than depending on a tag the re-encode discards.
- Visit-scoped evidence — **`D2` decides evidence is Job-level**, as implemented, and the domain document is
  corrected with that answer. An optional Visit link stays available later if a requirement ever names one.
