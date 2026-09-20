# Tracker 027 — Job photo updates (technician photo evidence)

**Status: IMPLEMENTED for the slice; physical-device QA is the product owner's**

Date: 2026-09-15
Follow-up: `docs/tracker/028-android-unified-job-update.md` replaced the separate "Add photo" action
beside Job Activity with the screen's one Add update action, and made an accepted upload re-read the
Activity on the spot. Everything below still describes this slice; the manual QA runbook and the two
test rows it mentions were updated for the new entry point.
Predecessors: `docs/tracker/022-android-job-activity-timeline.md` (the Activity the photos appear in),
`docs/tracker/023-object-storage-minio.md` (the store), `docs/tracker/025-android-offline-room-outbox.md`
(the offline engine)
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-012`, `BR-013`, `BR-014`, `BR-015`, `BR-027`,
`BR-028`, `BR-031`, `BR-032`, `BR-041`, `BR-042`, `BR-047`, `BR-051`, `BR-067`, `BR-080`
Standard followed: `docs/architecture/offline-first-architecture.md` §5, §6, §9, §10, §13
Decision: `ADR-013` (the S3 contract the storage adapter implements)
API contract: `docs/api/job-photos.md`

## What this slice is

Technicians capture photo evidence for a Job and it appears in the Job's Activity, **without a network
being required to capture it**. The flow is built for repeated capture in a yard rather than for one
tidy upload:

```text
camera action → capture → review panel (preview, optional note, phase) → tray → Save photos → Activity
```

The slice is one vertical: PostgreSQL table and migration, the API contract, the Android data layer and
its offline behaviour, the technician UI, tests on both sides, and documentation.

## What was built

| Part                                                    | Where                                                                                                                                                    |
| ------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `job_photos` table, constraints and indexes              | `api/src/database/schema.ts`, `api/drizzle/migrations/0009_job_photos.sql`                                                                                |
| Provider-neutral object-storage port and key layout      | `api/src/storage/object-storage.ts`                                                                                                                       |
| S3 adapter (any S3-compatible service; MinIO locally)    | `api/src/storage/s3-object-storage.ts`, `storage.module.ts`, `storage-config.ts`                                                                          |
| `POST /jobs/:id/photos`, `GET /jobs/:id/photos/:id/content` | `api/src/jobs/jobs.controller.ts`, `job-photos.service.ts`, `job-photo.dto.ts`                                                                         |
| `JOB_PHOTO_ADDED` in the Activity projection             | `api/src/jobs/job-activity.ts`, `docs/api/job-activity.md` §3.2                                                                                            |
| Storage configuration and the local stack                | `.env.example`, `docker-compose.yml` (`STORAGE_PROVIDER`, `S3_*`)                                                                                          |
| API tests                                                | `api/src/jobs/job-photo.dto.spec.ts`, `api/test/job-photos.e2e-spec.ts`, `api/test/support/fake-object-storage.ts`                                          |
| App-private photo storage                                | `android/.../data/jobs/JobPhotoFiles.kt`, `AndroidManifest.xml` (FileProvider), `res/xml/file_paths.xml`                                                    |
| Durable pending photos (Room v2 + migration)             | `android/.../data/jobs/PendingJobPhotoEntity.kt`, `PendingJobPhotoStore.kt`, `data/offline/OfflineMigrations.kt`, `OfflineDatabase.kt`                      |
| The feature's offline session and upload handler         | `android/.../data/jobs/JobPhotoSession.kt`, `JobPhotoUploadHandler.kt`, `JobPhotoOperations.kt`                                                            |
| Thumbnails for the tray and the gallery                  | `android/.../data/jobs/JobPhotoImages.kt`                                                                                                                 |
| Technician capture UI                                    | `android/.../ui/jobs/JobPhotoCapture.kt`, `JobPhotoReviewSheet.kt`, `JobPhotoTray.kt`, `JobPhotoComponents.kt`                                             |
| Activity gallery and photo timeline entries              | `android/.../ui/jobs/JobActivitySection.kt`                                                                                                               |
| Wiring                                                   | `ui/jobs/JobDetailsScreen.kt`, `ui/jobs/JobDetailsViewModel.kt`, `ui/navigation/ServoraNavHost.kt`, `di/JobsOfflineModule.kt`, `di/OfflineModule.kt`       |

## Behaviour

**Capture never needs a network.** The camera action allocates the photo's id and its app-private file
*before* the camera opens, and the capture is recorded durably as soon as the camera returns — before the
technician confirms anything. A process that dies during the review, or an app left for hours, therefore
cannot lose the photo: the bytes are on disk and the record finds them again (`BR-014`, `BR-015`).

**Reviewing is one panel, three big taps.** The panel shows the preview, an optional note and the three
phases as large segmented buttons rather than radio buttons (`BR-012`). The phase defaults to
`DURING_WORK` and then to the phase the technician chose last, which is what makes a run of photos of the
same phase one tap each. Closing the panel keeps the photo; discarding it is the explicit action.

**The tray holds what the backend has not accepted.** It shows a thumbnail, the phase badge, the note and
the upload's own state. An unsaved photo can be removed with its X — the technician has not committed it —
while a submitted one no longer offers removal, because from that moment the API may already hold the
evidence (`BR-014`, `BR-027`).

**Saving queues the upload; it does not claim one.** "Save photos" writes one outbox operation per photo
through the existing `OutboxStore`, with the photo's own id as the idempotency key and the local file path
in the payload, and then asks the existing `OfflineSync` to replay. Nothing is removed locally and nothing
is shown as uploaded until the API answers (`BR-031`, §5).

**Replay uploads the technician's bytes.** `JobPhotoUploadHandler` reads the file from app-private storage
at replay time, sends the multipart request with the same key on every attempt, and deletes the local
copy **only** once the API has answered `201`. `403`, `404`, `400`/`413`/`422` and `409` are kept as
refusals with the queued row and the file intact (`BR-032`); network and `5xx` retry with the engine's
backoff.

**Local state is scoped by subject.** Pending photos are partitioned by the authenticated subject and are
never shown, uploaded or deleted by another session; a session that cannot be attributed does not open the
camera at all (`§10`).

**Activity shows the accepted photos.** The gallery at the head of Job Activity is built from the
`JOB_PHOTO_ADDED` entries the API returned, and each photo also appears as a timeline entry naming its
phase, with its note as the entry's text (`BR-080`). Nothing on the client invents which evidence exists.
Once an upload is accepted — the moment its row leaves the tray — the Activity is read again, so the
photo appears without the Job having to be re-opened (`docs/tracker/028-android-unified-job-update.md`).

## Where this slice sits in the offline standard (§13)

- **An implemented adopter**: the photo upload is queued through `OutboxStore`, applied by
  `OutboxReplayEngine` through the feature's `JobPhotoUploadHandler`, and triggered by the existing
  session, connectivity and user paths. Its API contract accepts an idempotency key
  (`clientOperationId`), which is what §5 requires before an operation may be queued.
- **A new local store, deliberately**: the pending photos live in their own Room table, because a
  *draft* is not a queued mutation — the technician may still remove it, and it must survive process
  death. It holds paths and metadata, never image bytes, and it is scoped by subject like every other
  local store. It is not a second queue: the upload is queued exactly once, in the outbox.
- **Still online-only**: nothing else in this slice changed. Job and Visit actions, notes, scheduling and
  assignment remain as `§12` describes them.

| Strings (EN/FR) and a camera glyph                       | `res/values/strings.xml`, `res/values-fr/strings.xml`, `res/drawable/ic_camera.xml`                                                                       |

## Verification (2026-09-15)

```text
API
  npm run typecheck    PASS
  npm run lint         PASS   (oxlint, 0 warnings 0 errors)
  npm test             PASS   43 files, 373 tests
  npm run test:e2e     PASS   17 files, 228 tests  (PostgreSQL via `make up`)

Android
  ./gradlew testDebugUnitTest          PASS   38 files, 383 tests, 0 failures
  ./gradlew assembleDebugAndroidTest   PASS   (device tests compile and package)
  ./gradlew lintDebug                  PASS   0 errors, 39 warnings (abortOnError is on)
  ./gradlew assembleDebug              PASS
  ./gradlew connectedDebugAndroidTest  NOT RUN — device QA is the product owner's (`qa.md` §7.3)
```

The e2e run supplies `STORAGE_PROVIDER=noop` (`api/vitest.config.e2e.ts`), for the same reason it
supplies a test-only `JWT_SECRET`: a test must not depend on the machine's object-storage
configuration. Found by running the suite against a `.env` that selects `s3`: without it, every e2e
file that boots the app failed while loading the storage configuration.

Local development (and the runbook below) selects `s3` in the repository-root `.env`, which is
git-ignored; `.env.example` documents the values and the defaults.

**The adapter against real MinIO.** The API-side tests replace the object store with an in-memory fake,
so the S3 adapter was additionally checked against the running local stack, because `ADR-013` D6.3/D6.7
predict that path-style addressing and the SDK's default checksum behaviour are exactly what an
S3-compatible service may reject. A `putObject`/`getObject` round trip through `S3ObjectStorage`
against `http://127.0.0.1:9000` reported:

```text
key            evidence/job-photos/{org}/{job}/{photo}.jpg
storedBytes    36
sameBytes      true
contentType    image/jpeg
missing key    ObjectNotFoundError
```

The check object was removed afterwards (the bucket is left as it was). It is evidence rather than a
committed test: it needs the local stack, and the committed coverage is the e2e suite plus the fake.

The agent ran **no** `adb` and no device or emulator command: the instrumented suites
(`JobDetailsScreenTest` with its new photo cases, `OfflineDatabaseTest` with its new pending-photo cases
and its migration test) are compiled and handed to the product owner to run (`qa.md` §7.3).

## Tests added, and what each one pins

| Test                                 | What it protects                                                                                     |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| `api/src/jobs/job-photo.dto.spec.ts` | Phase vocabulary is closed, the note is optional and bounded, the idempotency key is required, and the image type is decided from the bytes (`BR-015`, `BR-041`, `BR-042`) |
| `api/test/job-photos.e2e-spec.ts`    | `401`/`403` by capability, tenant isolation, the API-derived object key, the `201` projection, a replay that stores the evidence **once**, `409` for a key reused on another Job, byte validation, `503` with nothing recorded, and the content read |
| `data/jobs/JobPhotoUploadHandlerTest.kt` | Success, network retries, `403`/`404`/`413` refusals, a missing local file, an unreadable phase, one session renewal, and the same idempotency key across attempts |
| `ui/jobs/JobPhotoCaptureViewModelTest.kt` | The tray, the optional note, the phase default and its persistence, removal of an unsaved photo, queueing on save, an honest failure when the camera wrote nothing, no capture without a session, and the tray following an accepted upload |
| `ui/jobs/JobDetailsViewModelTest.kt` | The Job read and its existing actions are unchanged by the new constructor dependencies             |
| `androidTest/.../JobDetailsScreenTest.kt` | The tray with phase/note/remove, a queued photo reporting its state and offering no removal, the review panel with the three phase buttons, the gallery, and the update action gated on the update capability (now the one Add update action — `docs/tracker/028-android-unified-job-update.md`) |
| `androidTest/.../OfflineDatabaseTest.kt` | The pending-photo store on SQLite: subject and Job scoping, the recorded review, the queued upload on save, and a review refused once queued |

## Open questions preserved (not implemented)

1. **The Jobs/evidence capability set** (`BR-006`, `BR-009`). The upload requires `JOB_UPDATE`, which the
   default Technician role does not hold, so a default-Technician session gets `403` until product
   ownership decides the capability set (or decides that field evidence is covered by an existing field
   capability). This is the pre-existing open question recorded for the Job actions
   (`docs/tracker/018-android-job-actions.md`), and the photo slice makes it user-visible.
   **Decided and implemented in tracker 029 Phase 1 (2026-09-15)**: evidence has capabilities of its own
   — `evidence.photo.add` on the write and `evidence.view` on the read, both held by the default
   Technician role — so a default-Technician session is no longer refused
   (`docs/decisions/015-evidence-capabilities.md`). The `jobs.*` set remains open for the other Job and
   Visit routes (`docs/tracker/018-android-job-actions.md`).
2. **What may be done with a refused photo** (`BR-032`, offline standard §11.5). A refusal keeps the
   photo, its file and the queued row, and the tray shows the reason; there is **no** retry or discard
   affordance, because no product rule defines one. A permanently refused photo therefore stays on the
   device until that is decided.
3. **Evidence retention and deletion** (`BR-027`, `BR-033`). Nothing removes a photo, and the `BR-082`
   Property deletion model was not extended to evidence.
4. **Thumbnails** (`ADR-013`, open question 3). The client decodes a thumbnail from the stored bytes; no
   derived object is stored.
5. **Editing or deleting a recorded photo.** Both are undefined, so neither exists: a recorded photo is
   append-only (`BR-067`).
6. **Audio, and other evidence types** (`BR-027`). Not implemented — see below.
7. **A Visit-scoped photo stream.** This slice records photos on the Job (`docs/api/job-photos.md` §1).
8. **A photo captured but never submitted, when the app is uninstalled.** The bytes belong to the device;
   after re-installation the gallery shows what the backend holds.


## What remains for audio later

Audio was explicitly out of scope for this slice. Before it can follow the same shape, each of the
following has to be decided or built — none of it is implemented here:

1. **The product decision** (`BR-027`, `BR-042`): whether voice notes are evidence at all, how long one
   may be, its size limit, and whether it is ever transcribed. None of that is defined today, so nothing
   audio-related was modelled.
2. **A content-type and container vocabulary** in the API validator. The current rule sniffs JPEG, PNG
   and WebP signatures and enforces one maximum size; audio would add its own signatures and its own
   limit, in the same place (`api/src/jobs/job-photo.dto.ts` is the pattern, not a place to extend
   blindly — the table, the key layout and the phase vocabulary would each need a decision).
3. **Playback in both clients.** Android and Angular each need a player and a way to present a duration,
   which has no counterpart in the photo slice.
4. **Activity kinds.** `JOB_AUDIO_ADDED` (or an equivalent) must be one shared vocabulary decision
   (`BR-041`) rather than a per-client invention, exactly as `JOB_PHOTO_ADDED` was.
5. **The offline path**, which needs no new mechanism: a queued upload with an idempotency key and a local
   file reference is what `JobPhotoUploadHandler` already does. Whether a second handler is written or the
   existing one generalises is a design choice to take when the product decision exists.

## Manual QA runbook (product owner)

Requires the API reachable from the device (`adb reverse tcp:3000 tcp:3000`) and `make up` for MinIO.
The API must run with `STORAGE_PROVIDER=s3` to store photos locally; with the default `noop`, an upload
is refused with `503` by design.

```text
1. Sign in as a Manager and open a Job.
2. Tap "Add update" (the floating action at the bottom of the screen).
   Expect: the Add update sheet opens with "Write note" in effect and the note field ready to type in.
3. Tap "Add photo" in the sheet.
   Expect: the sheet closes and the device's camera opens; no camera permission prompt is needed.
4. Take a photo.
   Expect: the review panel opens with the preview, an empty note and "During work" selected.
5. Choose "Before work", type a note, tap Add.
   Expect: the panel closes and the photo is in the tray with its phase badge, its note and an X.
6. Tap "Take another" and capture a second photo without changing the phase.
   Expect: "Before work" is already selected - the phase is remembered.
7. Tap Add, then tap "Save photos".
   Expect: a report that the photos are saved on this device; both tiles show an upload state and their
   X buttons are gone.
8. Wait a moment, or leave and re-open the Job.
   Expect: the photos appear in the gallery at the top of Job Activity with their phase and note, and
   their timeline entries read "Added a photo (Before work)". Once an upload is accepted the timeline
   is read again on the spot, so waiting in place is enough.
9. Check the store: `make minio-ls` lists objects under `evidence/job-photos/...`.
10. Turn the network off (airplane mode). Capture a photo, Add it, Save photos.
    Expect: the same as step 7 - the photo is kept and nothing is claimed as uploaded.
11. Force-close the app and reopen it while still offline, then open the same Job.
    Expect: the pending photo is still in the tray, with its state.
12. Turn the network back on and wait for the replay (or re-open the Job).
    Expect: the photo uploads, leaves the tray and appears in the gallery without re-opening the Job -
    one activity entry, and the local file is gone.
13. While offline, capture a photo and tap Discard in the panel.
    Expect: no photo in the tray and no queued upload.
14. Sign out and sign back in as a different account.
    Expect: the previous session's pending photos are not shown.
15. With a session that may not update Jobs (a default Technician), open a Job.
    Expect: no "Add update" action.
16. Open a Job that has no Visit and tap "Add update".
    Expect: the sheet offers "Add photo" only - a note belongs to a Visit - and no note field is drawn.
```


## Not in this slice

1. Audio, voice notes and any other evidence type (see above).
2. Editing, deleting or re-annotating a recorded photo.
3. Thumbnails or any derived object in the store.
4. Retention, lifecycle and versioning of stored objects (`BR-027`, `BR-033`).
5. Reporting over evidence, and an Angular view of it.

| Android tests                                            | `data/jobs/JobPhotoUploadHandlerTest.kt`, `ui/jobs/JobPhotoCaptureViewModelTest.kt`, `androidTest/.../JobDetailsScreenTest.kt` (new cases), `OfflineDatabaseTest.kt` (new cases) |
