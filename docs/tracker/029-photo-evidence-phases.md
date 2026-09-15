# Tracker 029 — Photo evidence after 028: permissions, picker, viewer, offline and lifecycle

**Status: PHASE 0 — decisions requested. This task wrote documentation only; no code was changed.**

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

**Phase 4 — LANDED (2026-09-15). Next: Phase 5 — blocked on D5.**

- **Phase 4 (full-size viewer, Android) is implemented and verified.** A tap on an accepted photo in
  the Job Activity gallery, or on a photo still waiting in the tray, opens the photo itself over the
  screen: the phase it was recorded in, the technician's whole note, and the photo drawn at the size
  the screen can hold. It was built on **D4 = (a)**, so no caching or thumbnail work was in scope, and
  the caching question the answer did not take is recorded as **D4b** rather than assumed. See the
  Phase log entry and `## Manual QA runbook — Phase 4`.
- **Phase 5 (offline visibility of accepted evidence) is next and NOT startable** — its dependency D5
  is still awaiting a decision, which Phase 0 owns.
- **Phases 1, 2, 3 and 4 are landed** — `EVIDENCE_PERMISSIONS` (`evidence.view`, `evidence.photo.add`),
  migration `0010_evidence_permissions.sql` and `docs/decisions/015-evidence-capabilities.md` (Phase 1);
  the content-type-aware pipeline and its published `FIT_TARGETS` (Phase 2); the two photo sources and
  the client's evidence gate (Phase 3); the viewer and the full-size read (Phase 4).
- **Open and still unstarted:** Phases 5–7 depend on D5–D8 respectively; D2 and D4b remain awaiting as
  well.
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
  viewer over the bytes the tile already reads. The caching and thumbnail options it did not take are
  recorded as **D4b**, still awaiting.
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
| Preparation | both sources go through one pipeline before a draft exists: the bytes are brought to a type Servora accepts (`D3b`, mirrored magic-number sniffer, JPEG conversion when the bytes are neither JPEG nor PNG nor WebP) and under the API's 15 MiB limit (`D3c`, resized/re-compressed as JPEG); a photo either step cannot deliver is refused with **nothing** recorded | `android/.../data/jobs/JobPhotoContentType.kt`, `JobPhotoProcessing.kt`, `JobPhotoSession.kt`, `JobPhotoRecording.kt` |
| Local metadata | Room `pending_job_photos` (draft, survives process death, `submitted` flag) | `android/.../data/jobs/PendingJobPhotoEntity.kt`, `PendingJobPhotoStore.kt`, `data/offline/OfflineMigrations.kt` |
| Upload | queued through the existing outbox (`job.photo.add`, `operationId` = photo id), replayed by the existing engine; the part declares the recorded type and its own file name; local file and row deleted **only** on `201` | `android/.../data/jobs/JobPhotoSession.kt`, `JobPhotoUploadHandler.kt`, `JobPhotoOperations.kt`, `data/offline/OutboxReplayEngine.kt` |
| API | `POST /jobs/:id/photos` (`evidence.photo.add`), `GET /jobs/:id/photos/:photoId/content` (`evidence.view`) | `api/src/jobs/jobs.controller.ts`, `job-photos.service.ts`, `job-photo.dto.ts` |
| Storage | S3-compatible store behind the `ObjectStorage` port; key `evidence/job-photos/{organizationId}/{jobId}/{photoId}.{ext}`; provider selected by `STORAGE_PROVIDER` (`noop` by default, so an upload is refused with `503`) | `api/src/storage/object-storage.ts`, `s3-object-storage.ts`, `storage.module.ts`, `storage-config.ts`, `ADR-013` |
| Database | `job_photos` (`phase` CHECK, content-type CHECK, byte-size CHECK, idempotency unique index) | `api/drizzle/migrations/0009_job_photos.sql`, `api/src/database/schema.ts` |
| Timeline | one Activity projection, newest-first; `JOB_PHOTO_ADDED` is Job-level and carries `photoId`, `photoPhase`, `body` (the note) | `api/src/jobs/job-activity.ts`, `docs/api/job-activity.md` §3.2, `android/.../ui/jobs/JobActivitySection.kt` |
| Gallery | a `LazyRow` of thumbnails at the head of Job Activity, phase badge + note snippet; each tile is a tap target that opens its photo in the viewer | `android/.../ui/jobs/JobPhotoComponents.kt` (`JobPhotoGallery`, `JobPhotoGalleryTile`) |
| Viewer | **exists since Phase 4**: a tap on a gallery tile or on a tray tile opens the photo itself in a full-screen dialog — phase badge, close action, the technician's whole note, and the photo drawn `ContentScale.Fit`; the surface is the theme's own (`surface` / `onSurface`), so no new colour token; a photo that cannot be read says so instead of drawing something else; closed by its action or the platform's back gesture; **no zoom, no gesture, no gallery-wide pager** (`D4`) | `android/.../ui/jobs/JobPhotoViewer.kt`, wired in `ui/jobs/JobDetailsScreen.kt` |
| Pending tray / review | tray of not-yet-accepted photos with upload state, each tile a tap target that opens the viewer; review panel with preview, note and the three phase buttons, which a captured photo **and** a picked photo are each confirmed in; a pick's items are taken one at a time, and skipped items are reported by their place in the pick | `android/.../ui/jobs/JobPhotoTray.kt`, `JobPhotoReviewSheet.kt`, `ui/jobs/JobDetailsViewModel.kt` |
| Thumbnails | decoded on device from the stored bytes (`inSampleSize`, 512 px, 24-entry in-memory `LruCache`); the viewer decodes the photo itself at a bounded longest edge (`VIEWER_MAX_EDGE_PX`, 2560 px, uncached) from the same read; **no derived object server-side and no request cache** (`D4b` is open) | `android/.../data/jobs/JobPhotoImages.kt`, `JobPhotoSampling.kt` |
| Photo picker | **exists since Phase 3**: `PickMultipleVisualMedia` and a `PickVisualMediaRequest(ImageOnly)` launch, with each item's bytes read through `JobPhotoPickedItems`; still no `ACTION_PICK`, `GetContent`, `MediaStore`, CameraX, Coil or Glide | `android/.../ui/jobs/JobPhotoPicker.kt`, `android/.../data/jobs/JobPhotoPickedItems.kt` |
| Tests | API unit + e2e for the contract; Android JVM tests for the pipeline, the handler, the photo ViewModel (both sources), the viewer's decode bound and the viewer's photo resolution; instrumented Compose cases for tray/gallery, the update sheet's two sources and the viewer (opening, full-size read, reading nothing, closing) | `api/src/jobs/job-photo.dto.spec.ts`, `api/test/job-photos.e2e-spec.ts`, `android/app/src/test/.../JobPhotoContentTypeTest.kt`, `JobPhotoSessionTest.kt`, `JobPhotoUploadHandlerTest.kt`, `JobPhotoSamplingTest.kt`, `ui/jobs/JobPhotoCaptureViewModelTest.kt`, `ui/jobs/JobPhotoViewerTest.kt`, `android/app/src/androidTest/.../ui/jobs/JobDetailsScreenTest.kt` |

## Phases at a glance

| Phase | Goal | Depends on | Layers | Status |
| --- | --- | --- | --- | --- |
| 0 | Decide the open product questions below | — | none (product ownership) | **Open — D1, D1b, D3, D3b, D3c, D4 answered; D2, D4b, D5–D8 awaiting** |
| 1 | Evidence capability set (who may add and read evidence) | **D1 ✓, D1b ✓** | API, database, docs | **Landed (2026-09-15)** |
| 2 | Content-type-aware local capture pipeline | **D3 ✓, D3b ✓, D3c ✓** | Android | **Landed (2026-09-15)** |
| 3 | Photo sources in the Add update sheet (gallery picker, multi-select) | **D3 ✓, D3b ✓, D3c ✓, Phase 1, Phase 2** | Android, localization, docs | **Landed (2026-09-15)** |
| 4 | Full-size viewer and a thumbnail strategy | **D4 ✓** | Android, docs | **Landed (2026-09-15)** — the viewer; the thumbnail/caching half is **D4b**, still awaiting |
| 5 | Offline visibility of accepted evidence | D5 | Android, `offline-first-architecture.md` | Blocked on D5 |
| 6 | Evidence lifecycle (retention, delete/edit, refused photos, orphan objects) | D6, D1 | API, database, Android, docs | Blocked on D6 |
| 7 | Angular parity and reporting over evidence | D1–D7, an Angular application | Angular, API | Blocked on D1–D7 |

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

**D4b — the preview and caching strategy is still open.** Raised by the D4 answer, because (b) and (c)
were the options it did not take. Every tile still reads **full-resolution bytes** over the API and
decodes a 512 px preview from them; the OkHttp client is built with no cache
(`android/.../di/NetworkModule.kt`), the API sends no `Cache-Control` on the content route, and the only
cache is a 24-entry in-memory bitmap cache, so a cold start or an eviction re-downloads every photo
again. It blocks no other phase, and it needs its own decision because an HTTP cache, a disk cache and
server-derived thumbnails differ in what they change (client only, client only, `ADR-013`).

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

## Phase 0 decision log

Filled in by product ownership. An answer here is what unblocks the phase named in `What it blocks`.

| ID | Decision | Answer | Decided by | Date | Recorded in |
| --- | --- | --- | --- | --- | --- |
| D1 | Evidence capability set (codes, bilingual names, default roles) | **Answered — a dedicated evidence capability** (option a), revocable per member, structured by kind. Replaces `JOB_UPDATE` on the write and `customers.view` on the read; the default Technician role holds it, which extends `BR-009` | product ownership | 2026-09-15 | this tracker (D1); **implemented in Phase 1** — `docs/decisions/015-evidence-capabilities.md` and `docs/api/job-photos.md` §2. The `Business Rules.md` `BR-008`/`BR-009` edit is product ownership's and is **still outstanding** (`BR-040`) |
| D1b | Exact catalogue shape (kind-agnostic `add`, or one `add` per kind) and the default role grants | **Answered — per-kind adds:** `evidence.view` + `evidence.photo.add` now (Manager and Technician), `evidence.audio.add` reserved for when audio lands. Per-kind so one kind can be withdrawn from a member | product ownership | 2026-09-15 | this tracker (D1b); implemented in Phase 1 |
| D2 | Evidence scope: Job / Visit / optional Visit link | *awaiting* | — | — | — |
| D3 | Photo sources (picker, multi-select, in-app camera) | **Answered — (a):** the existing camera **plus a multi-select gallery picker** (the system photo picker). No in-app camera, no `CAMERA` permission, no new dependency; each selected photo becomes its own draft. The design's in-app camera request stays open | product ownership | 2026-09-15 | this tracker (D3); implemented in Phase 3 |
| D3b | What a picked photo whose bytes are not JPEG/PNG/WebP (e.g. HEIC) does | **Answered — converted on device to JPEG** before the draft is written; the API, its sniffer and the schema stay unchanged. A failed conversion is an explicit localized refusal with no draft recorded | product ownership | 2026-09-15 | this tracker (D3b); implemented in Phase 2 |
| D3c | What an oversized photo (over the API's 15 MiB limit) does | **Answered — downscaled/re-compressed on device to fit**, so every photo can upload; the stored evidence is the resized version and the original is not kept. Target edge/quality is an implementation detail Phase 2 must record | product ownership | 2026-09-15 | this tracker (D3c); implemented in Phase 2 |
| D4 | Viewer and thumbnail strategy | **Answered — (a): a full-size in-app viewer only.** Tapping a photo (in the Job Activity gallery or in the capture tray) opens the photo in an in-app viewer; the caching/thumbnail items — an HTTP cache, `Cache-Control` on the answer, a disk-backed image cache, and server-derived thumbnails — are **not** built and stay recorded as undecided (`D4b`). The viewer decodes the photo itself, bounded by the screen it is drawn on, from the bytes already read (`JobPhotoImages`) | product ownership | 2026-09-15 | this tracker (D4); implemented in Phase 4. No API change: `docs/api/job-photos.md` is untouched, and `ADR-013`'s open question 3 stays open |
| D4b | Preview and caching strategy (HTTP cache, disk cache, server-derived thumbnails) | *awaiting* — raised by the D4 answer, which took the viewer only. Every tile still downloads full-resolution bytes and decodes a 512 px preview; no `Cache-Control`, no OkHttp cache, no `ADR-013` thumbnail | — | — | this tracker (`D4b`); the risk row "Every gallery tile downloads full-resolution bytes" stays open |
| D5 | Offline visibility of accepted evidence | *awaiting* | — | — | — |
| D6 | Evidence lifecycle (delete/edit, refused photos, Job deletion, retention) | *awaiting* | — | — | — |
| D7 | Metadata beyond the phase | *awaiting* | — | — | — |
| D8 | Audio and files in v1 | *awaiting* | — | — | — |

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

**Status: LANDED (2026-09-15) — the viewer. The thumbnail/caching half is `D4b`, still awaiting.**

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
projection, and whether accepted evidence must be readable without connectivity is **D5**, still
awaiting (`BR-032`). It queues nothing — a read never does — and it keeps nothing of its own once it
closes. `docs/architecture/offline-first-architecture.md` §12 records the photo reads in its online-only
list for exactly this reason.

**Not in this phase.** Zoom, gestures, a gallery-wide pager, video; the request cache, `Cache-Control`
and server-derived thumbnails (**D4b**); offline evidence (`D5`); editing or deleting a photo (`D6`).

## Phase 5 — Offline visibility of accepted evidence

**Goal.** A technician without connectivity can still see the Job's evidence and Activity to the extent
the decision requires.

**Depends on.** D5. If D5 is `(a)`, this phase does not run: the reason is recorded in the offline
standard instead (its §13 requires exactly that).

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

## Phase 6 — Evidence lifecycle

**Goal.** Every state evidence can reach has decided behaviour, including the ones reachable today with
none.

**Depends on.** D6 (each sub-decision separately); D1 for whoever performs a delete or edit.

**Layers.** API, database, Android, documentation.

**Work.** Sized by the decision, and each item is separate work:

1. **Refused photos** (D6c) — the tray currently shows the reason and offers nothing else, so a
   permanently refused photo and its file stay on the device forever. Whatever is decided (retry,
   discard, keep) belongs in `ui/jobs/JobPhotoTray.kt` + `JobPhotoSession.kt` + the outbox's refusal
   handling, with `BR-014`'s "nothing is silently lost" preserved.
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
| Every gallery tile downloads full-resolution bytes; no HTTP cache, no `Cache-Control`, a 24-entry in-memory cache | Repeated multi-megabyte downloads per photo, per cold start. **Still open**: `D4` took the viewer only, so the caching work is the new `D4b` decision | open (`D4b`) |
| Gallery tiles are inert; no viewer exists although the design requires one | The implemented screen cannot show a photo full size. **Delivered by Phase 4**: gallery and tray tiles are tap targets, and the viewer shows the photo, its phase and its note | 4 ✓ |
| The local pipeline hardcodes JPEG (`.jpg`, `image/jpeg`) | A picked PNG/WebP would be refused by the API's declared-versus-sniffed check | 2 |
| No photo picker existed at all | The design's "Existing Photos" and multi-select were unimplemented. **Delivered by Phase 3**: the system photo picker, multi-select, one draft per item, and a per-item report for a photo that cannot be taken | 3 ✓ |
| The tray's "Take another" action opens only the camera | After a pick, a second library photo needs the sheet again, while the design's attachment row pairs the two sources. Whether the tray gains a picker target, or the sheet stays the single way in, is a presentation decision the design owner has not taken — so Phase 3 changed nothing here | open (design owner) |
| Job Details and Activity reads are online-only | Offline, accepted photos and the timeline disappear; only the pending tray remains | 5 |
| A refused upload keeps its file and row with no affordance | Trapped evidence and unbounded device storage | 6 |
| `job_photos` cascade-deletes rows while the objects stay | Orphaned objects in the bucket on Job deletion | 6 |
| Photos are Job-scoped while `docs/domain/job-visit-domain-model.md` §469 says Visit-scoped | The domain document contradicts the implemented model | 0 (D2) |
| `STORAGE_PROVIDER` defaults to `noop` | A local stack that does not select `s3` refuses every upload with `503` | operational, not a phase |
| No retention or delete rule exists; the store has no lifecycle rule | Evidence accumulates permanently | 6 |
| A picked original may be a **HEIC/HEIF** or exceed the API's 15 MiB limit | The API accepts only JPEG/PNG/WebP and refuses oversized bodies. **Both halves decided** (D3b: converted on device; D3c: resized to fit) — the risk is now a Phase 2 implementation duty, not an open question | 2 (D3b ✓, D3c ✓) |
| EXIF/GPS inside a photo is stored and served verbatim | Location policy (`BR-038`) is open | 0 (D7) / 6 |

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

## Not implemented, and not to be assumed

- Audio, files, video and any evidence kind other than photos (`BR-027`).
- Editing, deleting or re-annotating a recorded photo.
- Retention, visibility, audit and versioning of evidence (`BR-027`, `BR-033`).
- Thumbnails or any derived object in the object store (`ADR-013` open question 3), and any request
  cache for the photo read — no `Cache-Control` on the answer and no OkHttp cache (`D4b`, open).
- An **in-app camera** — declined in D3 for now; the technician continues to capture through the device's
  own camera application, and the picker added by Phase 3 is the library source beside it.
- Choosing a photo the device holds no permission for, or any read outside what the picker hands over —
  the picker's grant is the only access Servora has.
- **Zoom, pinching, swiping between photos, and any gallery-wide pager.** A tap opens the one photo it
  was tapped on and the photo is drawn to fit the screen (`D4` left gestures out).
- Any Angular surface for evidence, and any reporting over it (`BR-030`).
- Presigned reads, and any second storage endpoint (`ADR-013` D7 and its open question 4).
- Visit-scoped evidence, until D2 says otherwise.
