# Tracker 035 — Audio evidence on a Job (tracker 029 Phase 9)

**Status: IN PROGRESS — Phases 9a (API) and 9b (Android: record, draft, queue, upload) are implemented;
9c (playback and the Activity surface) and 9d (device QA) follow.**

Date: 2026-09-16
Predecessors: `docs/tracker/029-photo-evidence-phases.md` (Phase 9 is this slice; Phases 1, 5, 6a, 6b and
the evidence lifecycle are its dependencies), `docs/tracker/027-android-job-photo-updates.md` (the photo
evidence slice this mirrors), `docs/tracker/033-android-add-update-hierarchy.md` (the sheet's audio seam),
`docs/tracker/025-android-offline-room-outbox.md` (the offline engine)
Decision record: `docs/decisions/018-audio-evidence.md` (A1–A10) — **read it first**
Contract: `docs/api/job-audio.md`, `docs/api/job-activity.md` §3.2
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-011`, `BR-012`, `BR-013`, `BR-014`, `BR-028`,
`BR-031`, `BR-041`, `BR-042`, `BR-051`, `BR-067`, `BR-080`, `BR-088`, `BR-089`, `BR-090`, `BR-091`
Design: `Figma/…/servora-job-details-spec.md` §"Audio" (Record, Stop, Playback/review,
Delete/re-record, Attach to update), §"Audio attachments" (a compact audio attachment in the timeline)

## Scope

A technician records an audio note on a Job; it uploads like a photo (offline-safe, idempotent,
authorized), appears in the Job's Activity and plays back — as evidence of its own kind, immutable once
accepted, removable only through an explicit audited operation.

| Item | Status |
| --- | --- |
| The evidence model, vocabulary and capability | **Decided** — `ADR-018` A1–A10 |
| `job_audio_notes` + `job_audio_note_removals`, `evidence.audio.add`/`evidence.audio.remove` | **Landed (9a)** |
| The three API routes and the Activity kinds `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` | **Landed (9a)** |
| Recorder, draft, queued upload, the sheet's audio kind becoming reachable | **Landed (9b)** |
| Playback (the sheet's review and the timeline), timeline entry, EN/FR copy, design system | Phase 9c |
| Physical-device QA | Product owner |

## Phases

| # | Phase | Depends on | Layers |
| --- | --- | --- | --- |
| 9a | Audio evidence API: table, capability, routes, Activity kinds | `ADR-018` ✓, Phase 1 ✓ | database, API, permissions, tests, contracts |
| 9b | Android: record, draft, queue, upload, the reachable kind | 9a ✓ | Android, offline, localization, tests |
| 9c | Playback and the Activity surface | 9b ✓ | Android, design system, tests |
| 9d | Device QA runbook and the landing record | 9c | docs, `qa.md` §7.2 |

### Phase 9a — the API half

**Goal.** A recording can be accepted as evidence, read back and taken out of ordinary use, authorized
per kind, with the Job Activity projection carrying it.

**Work.** The two tables and the two capability rows; `evidence-phase.ts` (the phase vocabulary photos and
audio now share, defined once); `mp4-audio.ts` (container sniffing and the duration read from `mvhd`);
`job-audio.dto.ts` (the multipart fields, the limits and the byte validation); `job-audio-notes.service.ts`
(idempotent append, content read, removal); the three routes and their error mapping; the two Activity
kinds and their event fields; the audit/history context covering removed audio notes; the development
seed's grants; `docs/api/job-audio.md` and `docs/api/job-activity.md`.

**Not in 9a.** Any Android change; playback; MP4 metadata stripping (tracker 029 Phase 6c owns where the
strip runs — `ADR-018` open question 2).

**Verification.** API unit tests for the byte/duration rules and the DTO; e2e tests for
`401`/`403`/success, a refused type, a refused length, idempotent replay, immutability and removal;
`npm run lint`, `npm test`, `npm run test:e2e` against the running foundation stack.

### Phase 9b — Android: record, draft, queue, upload (landed 2026-09-16)

**Goal.** The technician records a note, it survives process death, uploads when connectivity allows and
never uploads twice.

**Landed.** The microphone as a runtime permission asked for when the technician records; the audio kind's
own controls in the Add update sheet — record, stop, review, delete/re-record, attach, the design's own
list; a durable Room draft for a take the API has not accepted (`pending_job_audio_notes`, a Room
migration); the outbox handler for `job.audio.add` beside `JobPhotoUploadHandler`; the sheet's gate reading
`evidence.audio.add`, so the kind is offered exactly to a session the API would accept a recording from;
and the notice that reports an unaccepted take with the two actions it leaves (attach, discard).

**Decided by this phase (tracker open question 3).** A Job holds **one unattached take per session**: the
sheet adds one update at a time, so a second recording is a re-record after the first is deleted rather
than several drafts behind one update. The take is nevertheless a durable row, so it survives a process
death (`BR-014`).

**Not in 9b, recorded rather than left to be discovered.** Playback, the timeline entry
(`JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` are not mirrored in the Android Activity vocabulary yet, so the
mapper drops them rather than drawing them) and the removal of an *accepted* recording are Phase 9c. A
recording the **process kills before it stops** is not recoverable — nothing has been recorded and the
recorder belonged to that process — so the technician records again.

**Verification.** See the phase log.

### Phase 9c — Playback and the Activity surface

**Goal.** A recording plays back in the Job's Activity, and the evidence reads as evidence.

**Work.** The `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` timeline entries with their localized labels and
duration, and the Activity vocabulary that mirrors them in the client; **one** player behind one port
(`ADR-018` A9) serving both the sheet's review of a local take and the timeline's accepted evidence —
which is why the sheet's review control lands here rather than beside the recorder; the removal affordance
for a session holding `evidence.audio.remove`, behind the same confirmed, reason-carrying confirmation the
photo removal uses, online-only as that one is; EN/FR copy; `docs/design/android-design-system.md`.

**Verification.** JVM tests for the Activity mapping, the player's own state and the copy resolution;
Compose cases compiled; runbook for playback, offline recording and a refusal.

## Open questions carried by this tracker

1. **Unifying photos and audio into one kind-based evidence model** (`ADR-018` A1). Not decided; the
   triggers are in the ADR.
2. **MP4 container metadata** (`udta`/`©xyz`) and where the strip runs — tracker 029 Phase 6c owns the
   decision for evidence as a whole (`ADR-018` open question 2).
3. **Where the recording draft lives** if the technician records several notes for one update.
   **Answered by Phase 9b (2026-09-16):** a Job holds **one unattached take per session**. The sheet adds
   one update at a time and the design's own flow is record → review → delete/re-record → attach, so a
   second recording is a re-record after the first is deleted rather than several drafts behind one update
   — taken from the sheet's own draft-state rule (`BR-091`) rather than invented. The take is still a
   durable row and its bytes are app-private files, so it survives a process death (`BR-014`).

## Phase log

Phase records are appended here when a phase lands, in the `qa.md` §16 format, and are never rewritten.

| Phase | Date | What landed | Files | Verification |
| --- | --- | --- | --- | --- |
| 9a | 2026-09-16 | **The audio evidence API.** `job_audio_notes` and `job_audio_note_removals` (append-only, the removal added beside the record), `evidence.audio.add`/`evidence.audio.remove` with their bilingual catalogue rows and default-role grants (Manager both, Technician `add` only), the three `/jobs/:id/audio-notes` routes (`add`, `content`, `removal`), `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` with `audioNoteId`/`audioPhase`/`audioDurationSeconds`/`audioRemovalReason`, the audit/history context covering removed recordings, and the byte rule: an audio-only MP4 whose **length is read from its own container** (`moov/mvhd`) and bounded at 1–300 s, at most 10 MiB, validated before anything is stored. The phase vocabulary photos and audio share is now defined once (`evidence-phase.ts`). Decided by `ADR-018` A1–A10; contracted in `docs/api/job-audio.md`. | `api/drizzle/migrations/0012_job_audio_notes.sql` (+ its snapshot), `api/src/database/schema.ts`, `api/src/auth/permissions.ts`, `api/src/database/run-development-seed.ts`, `api/src/jobs/evidence-phase.ts`, `mp4-audio.ts`, `job-audio.dto.ts`, `job-audio-notes.service.ts`, `job-activity.ts`, `jobs.controller.ts`, `jobs.module.ts`, `api/src/storage/object-storage.ts`, `api/src/database/unique-violation.ts` (the photo service's private helper, now shared), `docs/api/job-audio.md`, `docs/api/job-activity.md`, `docs/api/job-photos.md`, `docs/decisions/015-…`, `docs/decisions/018-…`, this tracker, tracker 029 | **API unit: PASS** (`npm test` — 408 tests, 47 files, including the new `mp4-audio.spec.ts` 7, `job-audio.dto.spec.ts` 14 and the rewritten `permissions.spec.ts` 3). **API e2e: PASS** (`npm run test:e2e` — 267 tests, 18 files, including the new `test/job-audio-notes.e2e-spec.ts` 21: `401`/`403` per route, the default Technician role recording and reading but not removing, the API-derived key and the Activity projection, idempotent replay, a refused type/phase/length, the tenant boundary, the bytes round trip, storage failure with nothing recorded, removal with the audit/history context, a second removal refused). **Typecheck: PASS** (`npm run typecheck`). **Lint: PASS** (oxlint, 0 warnings, 0 errors, 179 files). **Android: NOT RUN — no Android file was changed by this phase.** **No `adb` and no device or emulator command was run** (`qa.md` §7.3). Left running: the product owner's foundation stack (untouched); no Gradle or Kotlin daemon was started by this phase, so there was nothing to stop |
| 9b | 2026-09-16 | **The audio evidence Android half.** Recording, reviewing, deleting/re-recording and attaching a take, with the upload offline-safe. `RECORD_AUDIO` as a runtime permission asked for when the technician records (never at launch); the audio kind's own controls in the Add update sheet (`JobUpdateAudioContent`: the phase selector, the live-microphone row with **Stop**, the **Record** action that is the only control asking for the permission, and the review — the take's length, its note, **Delete** / **Attach to update**); a durable Room draft for a take the API has not accepted (`pending_job_audio_notes`, Room **2 → 3** with a migration and the exported `3.json`); `JobAudioFiles` (the bytes in app-private storage, never a blob in the database); `JobAudioRecorder` over the platform's `MediaRecorder` (mono AAC in an MP4 container — the one the API sniffs, `ADR-018` A2 — released whenever a take is dropped); `JobAudioSession` (durable draft, discard, attach, the subject scoping of §10); `JobAudioUploadHandler` for `job.audio.add` beside the photo handler (multipart, idempotent on the take's own id, bytes read at replay time, the local copy deleted only once the API answers that it holds the recording); `Permission.EVIDENCE_AUDIO_ADD` gating the kind the API enforces on the route; the notice that reports an unaccepted take with the two actions it leaves, stacked with the photo tray at the bottom of the screen while the device holds unaccepted evidence; EN/FR copy for all of it. **Decided here (tracker open question 3):** one unattached take per Job and session — a re-record is a delete and a new take, taken from the sheet's own draft-state rule (`BR-091`) rather than invented. **Shared vocabulary:** the phase code both evidence kinds carry is now one client definition (`EvidencePhase`, `ADR-018` A4) and the refused-upload classifier both handlers use is one (`uploadFailureReason`). Not in 9b: playback, the `JOB_AUDIO_*` timeline entries and the removal of accepted evidence — Phase 9c. | `android/app/src/main/java/com/servora/android/data/jobs/` (`JobAudioOperations`, `JobAudioFiles`, `JobAudioRecorder`, `JobAudioRecording`, `JobAudioSession`, `JobAudioUploadHandler`, `PendingJobAudioNoteEntity`, `PendingJobAudioNoteStore`, `UploadFailureReason`), `domain/model/` (`JobAudioNote`, `EvidencePhase`), `ui/jobs/` (`JobAudioCapture`, `JobAudioComponents`, `JobAudioNotice`, `JobUpdateSheet`, `JobUpdateKind`, `JobDetailsScreen`, `JobDetailsUiState`, `JobDetailsViewModel`), `domain/auth/Permission.kt`, `ui/customers/CustomerPermissionsUiState.kt`, `ui/navigation/ServoraNavHost.kt`, `data/offline/` (`OfflineDatabase`, `OfflineMigrations`), `di/` (`OfflineModule`, `JobsOfflineModule`), `AndroidManifest.xml`, `res/values/strings.xml` + `res/values-fr/strings.xml`, `android/app/schemas/com.servora.android.data.offline.OfflineDatabase/3.json`, tests (`JobAudioUploadHandlerTest`, `AudioCollaborators`, `FakeJobAudioFiles`, `FakeJobAudioRecorder`, `InMemoryPendingJobAudioNoteStore`, `JobDetailsViewModelTest`, `JobPhotoCaptureViewModelTest`, `CustomerPermissionsUiStateTest`, `JobUpdateKindsTest`, `InertJobAudioSession`, `OfflineDatabaseTest`, `JobDetailsScreenTest`, `ServoraHomeNavigationTest`), docs (this tracker, tracker 033, `docs/architecture/offline-first-architecture.md` §12, `docs/design/android-design-system.md`, `README.md`) | **Android JVM unit: PASS** (`./gradlew testDebugUnitTest` — 539 tests, 51 classes, 0 failures; this phase adds 21: 12 audio cases in `JobDetailsViewModelTest`, 8 in the new `JobAudioUploadHandlerTest`, 1 in `CustomerPermissionsUiStateTest`). **Compilation: PASS** — main, unit-test and device-test Kotlin sources (`compileDebugKotlin`, `compileDebugUnitTestKotlin`, `compileDebugAndroidTestKotlin`). **Builds: PASS** — `assembleDebug` and `assembleDebugAndroidTest` produced `app-debug.apk` and `app-debug-androidTest.apk`. **Lint: NOT RUN — stated rather than claimed.** `lintDebug` was started twice and stopped both times: this project lints its dependencies as well (`lint { checkDependencies = true }`), so the task drives this machine's load average above 35 for minutes and heats it, and the product owner asked for a strategic approach to it. The command to run when the machine is free is `make android-lint`; it passed at the previous checkpoint (commit `a6a9889`) for the code as it stood before this phase. **Compose and device cases: NOT RUN — device QA is the product owner's** (`qa.md` §7.3); no `adb`, emulator or Gradle device task was run, and the device-test sources were compiled instead. **Hygiene:** the Gradle and Kotlin daemons this phase started were stopped (`./gradlew --stop`, `make android-stop`); the foundation stack was left untouched. |


**Phase 9a's own limitation, stated rather than left to be discovered.** The API accepts and stores an
audio note, so a recording can be submitted and read today, but **no client can produce one yet**: the
Android recorder, the draft, the queued upload and playback are Phases 9b/9c, and the Add update sheet
keeps `canAddAudio = false` until then (`BR-042` — a kind nothing can add stays unoffered). The kind's
metadata is also not yet mirrored in the Android DTOs, which is 9b's work with the upload.

**Phase 9b's own limitation, stated the same way.** A technician can record, review, delete, re-record and
attach a take, and the upload is offline-safe — but **a recording cannot be played back yet** and **an
accepted recording is not drawn in the Job's Activity**: playing a take is Phase 9c, and the
`JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` kinds are not in the Android Activity vocabulary yet, so the mapper
drops those events rather than drawing them (a recording uploaded by 9b becomes visible in the timeline
when 9c lands). A recording the process kills before it stops is not recoverable, and the removal of an
*accepted* recording (the `evidence.audio.remove` affordance) is 9c's work as well.

**Phase 9b — manual QA on a physical device (`qa.md` §7.2; the full runbook is Phase 9d).** Sign in as a
Technician. Open a Job. Tap **Add update** and the **Add audio** segment: choose a phase, tap **Record**,
allow the microphone when Android asks, speak, tap **Stop**. Expect the take to appear in the review with
its length, that the tap on **Record** asked for the permission rather than failing silently, and that a
declined permission reports itself and changes nothing else.

Then: type a note, tap **Attach to update**. Expect the sheet to close, the notice **Audio to save** to
appear at the bottom with *Waiting to upload*, and — with connectivity — the notice to disappear once the
API accepts it. Turn airplane mode on before attaching instead and expect the same queueing, with the
notice still there after killing and reopening the app: the take survives a process death (`BR-014`).
Finally, check the two refusals: delete the take (the draft and its bytes go, nothing was ever sent), and
let a take be refused by the server (the notice reports it and offers the discard).
