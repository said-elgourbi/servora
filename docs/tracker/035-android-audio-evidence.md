# Tracker 035 — Audio evidence on a Job (tracker 029 Phase 9)

**Status: IN PROGRESS — Phases 9a (API), 9b (Android: record, draft, queue, upload), 9c (playback
and the Activity surface) and 9e (a seekable playhead) are implemented; 9d (device QA) follows.**

Date: 2026-09-16 (9c landed 2026-09-17)
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
| Playback (the sheet's review and the timeline), the timeline entry, the removal of accepted evidence, EN/FR copy, design system | **Landed (9c)** |
| A seekable playhead: seeking, the position stated (`0:04 / 0:18`), a pause that holds its position | **Landed (9e)** |
| Physical-device QA | Product owner |

## Phases

| # | Phase | Depends on | Layers |
| --- | --- | --- | --- |
| 9a | Audio evidence API: table, capability, routes, Activity kinds | `ADR-018` ✓, Phase 1 ✓ | database, API, permissions, tests, contracts |
| 9b | Android: record, draft, queue, upload, the reachable kind | 9a ✓ | Android, offline, localization, tests |
| 9c | Playback and the Activity surface | 9b ✓ | Android, design system, tests |
| 9d | Device QA runbook and the landing record | 9c | docs, `qa.md` §7.2 |
| 9e | A seekable playhead: seeking and the position stated (`ADR-018` A11) | 9c ✓ | Android, design system, tests |

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

### Phase 9c — Playback and the Activity surface (landed 2026-09-17)

**Goal.** A recording plays back in the Job's Activity, and the evidence reads as evidence.

**Work.** The `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` timeline entries with their localized labels and
duration, and the Activity vocabulary that mirrors them in the client; **one** player behind one port
(`ADR-018` A9) serving both the sheet's review of a local take and the timeline's accepted evidence —
which is why the sheet's review control lands here rather than beside the recorder; the removal affordance
for a session holding `evidence.audio.remove`, behind the same confirmed, reason-carrying confirmation the
photo removal uses, online-only as that one is; EN/FR copy; `docs/design/android-design-system.md`.

**Landed.** `JobActivityKind.JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` with `audioNoteId`, `audioPhase`,
`audioDurationSeconds` and `audioRemovalReason` on the domain event and the wire DTO, so the mapper no
longer drops the API's two audio kinds (`BR-041`, `BR-080`); the timeline entry that draws, under the
`JOB_AUDIO_ADDED` event, the **play/pause control**, the length the API read from the container, the shared
phase badge and the recording's note, with the removal control beside them for a session the API would let
remove it; `JobAudioPlayer` and its `MediaPlayer` implementation — **one port, one player** for the sheet's
review and the timeline (`A9`) — reporting through a flow which recording is playing, so a control that
started playback returns to *Play* when the recording ends, fails or is replaced; `JobAudioEvidenceCache`
with `JobAudioContentReader`, which read an accepted recording's bytes through the API **once** into a
session-scoped, evictable cache file and play it from there, so a recording already played is playable
without connectivity (`BR-013`, `A9`); `Permission.EVIDENCE_AUDIO_REMOVE` and the removal through
`POST /jobs/:id/audio-notes/:audioNoteId/removal`, confirmed in a shared reason-carrying dialog whose copy
and tags are now per evidence kind (`BR-089`, `A7`); the audio failure and outcome vocabulary with EN/FR
copy for playback and removal; and the two device-test cases the timeline's recording draws.

**Decided by this phase.**

1. **Playback's bytes are a session-scoped, evictable cache** (`A9`'s "one player" left the source open):
   an accepted recording is read through the API on its first play and written to the app's own cache
   directory under the session's subject, and every later play reads that file. It is a **cache**, not a
   store: the platform may reclaim it, nothing is promised to survive, and a recording whose bytes are gone
   is read again — which is exactly what `BR-013`/`BR-088` ask of evidence bytes (metadata readable
   offline, bytes best-effort). The **draft**'s bytes stay in app-private storage and are never evictable,
   because they are the only copy of evidence the API has not accepted yet (`BR-014`).
2. **One action plays either source**: the screen's ViewModel decides whether an id names a take still on
   the device or accepted evidence, so the sheet and the timeline ask one action and cannot diverge.

**Not in 9c, recorded rather than left to be discovered.** A recording the device has never played is
**not** playable offline — its bytes are read through the API on the first play (`BR-013`, and the refusal
says so rather than substituting anything). The player is the platform's own: **play/pause and the
length**, one recording at a time, with no scrubber, waveform or position, and no streaming from a
presigned URL — a richer player remains `A9`'s later decision. The audit/history context
(`?includeRemovedEvidence=true`) is still not surfaced in Android for either evidence kind, so a removed
recording is visible only through its `JOB_AUDIO_REMOVED` entry; that is tracker 029 `D6d`'s remaining
Android work, not this phase's.

**Verification.** See the phase log. Device QA is Phase 9d.

**Phase 9c — manual QA on a physical device (`qa.md` §7.2; the full runbook is Phase 9d).** Sign in as a
Manager and open a Job that already holds an accepted recording. In the Job's Activity, tap **Play** on the
recording: expect it to be read once (the first play may take a moment, with the control reporting that it
is working) and to start playing; tap it again to pause, and let one play to its end and check the control
returns to **Play** on its own. Then, with the recording still listed, turn airplane mode on and play it
again: a recording this device has already played plays with no connectivity, and one it has never played
reports that it needs the server. Finally tap the removal control, check the confirmation names the
recording and refuses to apply anything until a reason is typed, and confirm: expect the entry to leave
ordinary use, the `JOB_AUDIO_REMOVED` entry to state the reason, and its playback to stop. Sign in as a
Technician and check the same recording draws **Play** but **no** removal control (`BR-009`, `BR-089`).

### Phase 9e — A seekable playhead (landed 2026-09-17)

**Goal.** The decision product ownership made on 2026-09-17 on top of `A9`: *"fill/playahead with seeking …
also show length of audio and where we are at the moment"*, explicitly **without amplitude**.

**Work.** `ADR-018` `A11` with `A9` amended; the player's own position as a second answer beside the
recording it holds; a seek track and a position label shared by both surfaces that play a recording; a pause
that keeps where it stopped; EN/FR copy; `docs/design/android-design-system.md`.

**Landed.** As recorded in the phase log below. What it deliberately did **not** touch: no API route, table,
payload or Activity kind — a position is a device fact — no outbox entry, because playing a recording is not
a mutation (`BR-001`, `BR-031`, offline standard §13) — and the waveform and streaming from a presigned URL
remain undecided (`BR-042`).

**Not in 9e.** Anything about what a recording *is* or how it is uploaded (`A1`–`A10` are unchanged), and any
notion of a position as business data: where a technician listened to inside a recording is never recorded,
stored or sent (`BR-001`, `BR-091`).

**A defect found and corrected.** The platform's completion and error callbacks were registered inside
`MediaPlayer.apply { … }`, where an unqualified `release()` is the platform's own `MediaPlayer.release()`: the
sound stopped, but the feature kept reporting the recording as still playing, so its control stayed on
*Pause*. It is reachable only on a device, which is why the JVM tests could not see it and why Phase 9d's
runbook must check it.

**Verification.** See the phase log.

**Phase 9e — manual QA on a physical device (`qa.md` §7.2; the full runbook is Phase 9d).** Sign in as a
Manager and open a Job that holds an accepted recording. Tap **Play**: expect the playhead to move along the
track, the position to count up (`0:04 / 0:18`) and the fill to follow it. Drag the thumb: expect the
recording to move to where the thumb was put, and a tap elsewhere on the track to move it there too. Tap
**Pause**: expect the control to return to **Play** with the playhead staying where it was, and **Play** again
to continue from there rather than from the beginning. Let one play to its end: expect the control to return
to **Play** on its own — **the defect corrected in this phase**. If the Job holds a second recording, its
track is drawn quietly and does not respond until that recording is played. Finally open the sheet on a fresh
take, play it and move through it the same way, and check the track and the position are there in both
languages (`BR-028`).

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
| 9c | 2026-09-17 | **Playback and the Activity surface.** `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` are part of the client's Activity vocabulary now, with `audioNoteId`/`audioPhase`/`audioDurationSeconds`/`audioRemovalReason`, so a recording the API recorded no longer disappears from the timeline; the entry that holds a recording draws the **play/pause control**, the length the API read from the recording's own container, the shared phase badge and the recording's own note, with the **removal control** beside them for a session holding `evidence.audio.remove`; **one player behind one port** serves the timeline and the Add update sheet's review (`ADR-018` A9) — `JobAudioPlayer` over the platform's `MediaPlayer`, reporting which recording is playing so a control returns to *Play* when a recording ends, fails or is replaced — and `JobAudioEvidenceCache` with `JobAudioContentReader` read an accepted recording's bytes through the API **once** into a session-scoped, evictable cache file, so a recording already played is playable offline (`BR-013`); `Permission.EVIDENCE_AUDIO_REMOVE` and the removal route behind **one** confirmed, reason-carrying dialog whose copy and tags are now per evidence kind (`BR-089`, `ADR-018` A7), online-only as the photo removal is (`A10`); EN/FR copy for every new state; the design system's recording entry, player and removal. **Decided here:** playback's bytes are a **session-scoped evictable cache** (`A9` left the source open), and **one screen action plays whichever source an id names** — a take still on the device from its own file, accepted evidence through the API — so the sheet and the timeline cannot diverge. **Shared:** the removal confirmation is now one dialog with per-kind copy and tags (`EvidenceRemovalDialog`), and the note treatment is one piece for both evidence kinds (`EvidenceNote`, renamed from the photo-only `JobPhotoNote`). | `android/app/src/main/java/com/servora/android/data/jobs/` (`JobAudioPlayer`, `JobAudioContentReader`, `JobAudioEvidenceCache`, `JobActivityDtos`, `JobDetailsApi`, `JobDetailsRepository`, `JobActionDtos`, `JobDetailsResult`), `domain/model/JobActivity.kt`, `domain/auth/Permission.kt`, `ui/jobs/` (`JobEvidenceAudio`, `EvidenceRemovalDialog`, `JobActivitySection`, `JobAudioComponents`, `JobDetailsScreen`, `JobDetailsUiState`, `JobDetailsViewModel`, `JobUpdateSheet`, `JobPhotoViewer`, `JobPhotoComponents`, `JobDetailsActions`), `ui/customers/CustomerPermissionsUiState.kt`, `ui/navigation/ServoraNavHost.kt`, `di/JobsOfflineModule.kt`, `res/drawable/ic_play.xml`, `res/drawable/ic_pause.xml`, `res/values/strings.xml` + `res/values-fr/strings.xml`, tests (`JobActivityAudioTest`, `JobAudioEvidenceCacheTest`, `AudioPlaybackCollaborators`, `JobDetailsViewModelTest`, `JobDetailsRepositoryTest`, `JobPhotoCaptureViewModelTest`, `PhotoUploadApi`, `InertJobAudioSession`, `JobDetailsScreenTest`, `ServoraHomeNavigationTest`), docs (`docs/design/android-design-system.md`, this tracker, `README.md`) | **Android JVM unit: PASS.** Targeted (`./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.*' --tests 'com.servora.android.data.jobs.*'` — 237 tests, 19 classes) and then the **full suite** (`make android-test` — **566 tests, 53 classes, 0 failures**; this phase adds 27: the new `JobActivityAudioTest` 7 and `JobAudioEvidenceCacheTest` 5, 10 audio cases in `JobDetailsViewModelTest`, 3 in `JobDetailsRepositoryTest` — the audio activity mapping, the removal request, the already-removed code — and 2 in `CustomerPermissionsUiStateTest` — `evidence.audio.remove` mapped on its own and never inferred from the photo removal). **Builds: PASS** — `make android-build` (`assembleDebug`, `app-debug.apk`) and `assembleDebugAndroidTest`, including the four new `JobDetailsScreenTest` cases. **Compilation: PASS** — main, unit-test and device-test Kotlin sources (`compileDebugKotlin`, `compileDebugUnitTestKotlin`, `compileDebugAndroidTestKotlin`). **Lint: NOT RUN — stated rather than claimed.** `lintDebug` is the one Tier B item not run: this project lints its dependencies too (`lint { checkDependencies = true }`), so it drives this machine's load average above 35 for minutes, and Phase 9b recorded the same finding and the product owner's request for a strategic approach to it. The command to run when the machine is free is `make android-lint`; it passed at the previous checkpoint (commit `a6a9889`) for the code as it stood then. **Device cases: NOT RUN — device QA is the product owner's** (`qa.md` §7.3); no `adb`, emulator or Gradle device task was run, and the device-test sources were compiled instead. **Hygiene:** the Gradle and Kotlin daemons this phase started were stopped (`make android-stop`, `make tidy` — nothing left running); the foundation stack was left untouched. |
| 9c — correction | 2026-09-17 | **Playing an accepted recording from the Activity no longer crashes, and the recording is drawn as one control rather than a row of glyphs.** The bug: a recording's bytes are fetched on its first play, and the response is **streamed** (`@Streaming`), so the body is read by the *caller* — the screen's own coroutine, which runs on the main thread (`JobAudioEvidenceCache`'s caller is `JobDetailsViewModel.toggleAudioPlayback`). Reading a socket there is what a device refuses to do, and the exception it raises (a `RuntimeException`, unlike the `IOException`/`HttpException` the reader maps) escaped the coroutine and took the app down. Fixed at the source, not at the symptom: `PrivateJobAudioEvidenceCache.read` — the coordinator that owns playback's device work, as `DefaultJobPhotoExporter` owns the export's — now runs the whole read on `Dispatchers.IO` (`withContext`), so neither the network read nor the cache write happens on the thread that asked; the reader's KDoc states the contract (it reads a streamed body and must not be called from the main thread), and `JobAudioContentReader` is unchanged. **No tap can take the screen down any more (`BR-042`, `BR-012`):** `toggleAudioPlayback` reads through a private `readPlaybackBytes` that reports a failure this build cannot name as `PLAYBACK_UNAVAILABLE` (*"That recording could not be read."*) instead of throwing, rethrowing `CancellationException` so leaving the screen is still just a cancelled read (`dev.md` §1). An entry holding **no bytes** is no longer kept in the cache (an empty answer is reported and deleted rather than played), and the audio failure vocabulary is otherwise unchanged. **The attachment itself (no amplitude — the design spec's "waveform" is not drawn, and `A9`'s no-scrubber/no-position decision stands):** the play/pause control is now a filled `primary` circle (40 dp) with an `onPrimary` lucide `Play`/`Pause` glyph (22 dp) inside a 48 dp target, shared by the timeline and the sheet's review, with a `surfaceContainerHighest`/`onSurfaceVariant` fill when it cannot be used and a `primary` progress ring while the bytes are being read; the timeline row keeps a `secondary` `shapes.medium` container with a 4 dp inset, a 56 dp least height and the length drawn `bodyLarge`/`FontWeight.Medium`, and the sheet's review row keeps the same length treatment and the 56 dp the kind's other states keep, so switching *Record* ↔ live microphone ↔ review no longer moves the foot of the sheet. **Shared vocabulary:** the `tint` parameter is gone from `JobAudioPlayControl` — one filled circle on both surfaces, so the two cannot diverge (`BR-041`). | `android/app/src/main/java/com/servora/android/data/jobs/JobAudioEvidenceCache.kt`, `JobAudioContentReader.kt` (doc), `android/app/src/main/java/com/servora/android/ui/jobs/JobEvidenceAudio.kt`, `JobUpdateSheet.kt`, `JobDetailsViewModel.kt`, `android/app/src/test/java/com/servora/android/data/jobs/JobAudioEvidenceCacheTest.kt`, `AudioPlaybackCollaborators.kt`, `android/app/src/test/java/com/servora/android/ui/jobs/JobDetailsViewModelTest.kt`, `docs/design/android-design-system.md`, this tracker | **Android targeted (Tier A, `qa.md` §3.1): PASS** — `compileDebugKotlin`, `compileDebugUnitTestKotlin`, `assembleDebugAndroidTest` (the device-test sources compile; no `adb`) and `testDebugUnitTest --tests 'com.servora.android.ui.jobs.*' --tests 'com.servora.android.data.jobs.*'` (240 tests, 0 failures, 0 errors, including the 3 new cases: `JobAudioEvidenceCacheTest` 5 → 7, `JobDetailsViewModelTest` 50 → 51). **Regression proof (`qa.md` §11):** with the two fixed parts reverted, exactly the two new playback cases fail — `reads a recording off the thread that asked for it` (the read ran on the caller's thread) and `reports a read that failed in a way this build did not foresee instead of crashing` (the exception escaped the coroutine, which is the crash) — and nothing else; restored, all 240 pass. **Lint: NOT RUN — stated rather than claimed**: `make android-lint` is the Tier B item and is still the one command that saturates this machine (`lint { checkDependencies = true }`), as Phases 9b/9c recorded; run it when the machine is free. **Device QA: NOT RUN — the product owner's** (`qa.md` §7.3): the runbook is the Phase 9c block above, and the recording must now play from the Activity without the app closing. **Hygiene:** the Gradle and Kotlin daemons this task started were stopped (`make android-stop`, confirmed with `pgrep` — nothing left); the foundation stack was left untouched; no scratch file remains. |
| 9e | 2026-09-17 | **A recording can be moved through, and it says where it is.** Product ownership decided `ADR-018` `A11` on top of `A9` — *"fill/playahead with seeking … also show length of audio and where we are at the moment"*, explicitly **without amplitude**. `JobAudioPlayer` gains `pause`, `resume` and `seekTo`, and where a recording has got to becomes a **second answer**: `JobAudioPlayback(audioNoteId, isPlaying)` says which recording the player holds and whether it is moving, and `JobAudioPlaybackProgress(audioNoteId, positionMillis, durationMillis)` says how far it has got — reported apart and read where it is drawn, so a moving playhead does not recompose Job Details, the timeline or the entry around the recording (`BR-012`); `MediaPlayerJobAudioPlayer` owns the read loop (one ticker, ten reads a second, running only while something plays, cancelled on pause/stop/replacement), states a seek's destination until the platform reports the seek complete so a released playhead cannot jump backwards, and **releases the feature's own player from the platform's completion and error callbacks** — inside `MediaPlayer.apply { … }` an unqualified `release()` is the platform's own `MediaPlayer.release()`, so until now the sound stopped while the feature kept reporting the recording as playing and left the control on *Pause* (`BR-042`; found and corrected here, reachable only on a device); the timeline entry and the sheet's review draw the shared **seek track** (`JobAudioSeekTrack`: the platform's Material 3 `Slider` with the theme's own thin track, a 48 dp interaction height and an opt-in documented for the custom track) and the shared **position label** (*"0:04 / 0:18"* — the total still the length the API read from the container, so a recording does not state its length two ways, `BR-041`, and the seconds derived so they change once a second while the playhead moves ten times), the playhead **commits as the thumb moves** — including through the accessibility action that moves a slider (`BR-042`) — a second tap on the held recording **pauses it where it got to** and a third continues from there (`A11`), and a recording the player does not hold is drawn **quietly** rather than as a track that would not respond (`BR-042`); `JobActivityAudio.progress`/`onSeek` and the sheet's `audioProgress`/`onSeekAudioDraft` carry both, the position reaching them as a `State` collected once where the screen's state is (`ServoraNavHost`) rather than as a value; EN/FR copy (`job_audio_position_format`, `job_audio_seek`); `docs/decisions/018-audio-evidence.md` (`A11`, `A9` amended), `docs/design/android-design-system.md`, `README.md`. **Not changed:** no API route, table, payload, Activity kind or outbox entry — a position is a device fact and playing is not a mutation (`BR-001`, `BR-031`, offline standard §13) — and the waveform, streaming from a presigned URL and transcription remain undecided (`BR-042`). | `android/app/src/main/java/com/servora/android/data/jobs/JobAudioPlayer.kt`, `ui/jobs/` (`JobEvidenceAudio`, `JobUpdateSheet`, `JobDetailsScreen`, `JobDetailsViewModel`, `JobDetailsUiState`), `ui/navigation/ServoraNavHost.kt`, `res/values/strings.xml` + `res/values-fr/strings.xml`, tests (`AudioPlaybackCollaborators`, `JobActivityAudioTest`, `JobDetailsViewModelTest`, `JobDetailsScreenTest`, `InertJobAudioSession`), `docs/decisions/018-audio-evidence.md`, `docs/design/android-design-system.md`, `README.md`, this tracker | **Android targeted (Tier A, `qa.md` §3.1): PASS** — `compileDebugKotlin`, `compileDebugUnitTestKotlin`, `assembleDebugAndroidTest` (the device-test sources compile; **no `adb` and no device command was run**, `qa.md` §7.3) and `testDebugUnitTest --tests 'com.servora.android.ui.jobs.*' --tests 'com.servora.android.data.jobs.*'` — **246 tests, 0 failures, 0 errors**, `JobActivityAudioTest` 7 → **10** (the seek math, including a fraction that is not a whole second and a recording whose length the player cannot state) and `JobDetailsViewModelTest` 51 → **54** (a pause that keeps the position and a resume from it; a seek applied to the recording the player holds and not to one it does not; and a moving position that does **not** rewrite the state the screen is composed from). **One test correction, stated rather than hidden:** the first run of the new seek test failed on its own expectation — 0.33 of 18 000 ms is 5 940 ms, not a rounded 6 000 — and the code was right; the expectation now states the millisecond it lands on. **Lint: NOT RUN — the Tier B item and still the one command that saturates this machine** (`lint { checkDependencies = true }`), as Phases 9b/9c recorded; run it when the machine is free. **Device QA: NOT RUN — the product owner's** (`qa.md` §7.3): the runbook is the Phase 9e block above, and it must include the defect this phase corrected (a recording that reaches its end returns its control to *Play*) and the `ExperimentalMaterial3Api` opt-in the custom track takes. **Hygiene:** the Gradle and Kotlin daemons this task started were stopped (`make android-stop`, confirmed with `pgrep`); the foundation stack was left untouched; no scratch file remains. |





**Phase 9a's own limitation, stated rather than left to be discovered.** The API accepts and stores an
audio note, so a recording can be submitted and read today, but **no client can produce one yet**: the
Android recorder, the draft, the queued upload and playback are Phases 9b/9c, and the Add update sheet
keeps `canAddAudio = false` until then (`BR-042` — a kind nothing can add stays unoffered). The kind's
metadata is also not yet mirrored in the Android DTOs, which is 9b's work with the upload. **Resolved:**
9b landed the recorder, the draft and the queued upload, and 9c the playback and the timeline entry.

**Phase 9b's own limitation, stated the same way.** A technician can record, review, delete, re-record and
attach a take, and the upload is offline-safe — but **a recording cannot be played back yet** and **an
accepted recording is not drawn in the Job's Activity**: playing a take is Phase 9c, and the
`JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` kinds are not in the Android Activity vocabulary yet, so the mapper
drops those events rather than drawing them (a recording uploaded by 9b becomes visible in the timeline
when 9c lands). A recording the process kills before it stops is not recoverable, and the removal of an
*accepted* recording (the `evidence.audio.remove` affordance) is 9c's work as well. **Resolved by Phase
9c:** a recording plays back from the timeline and from the sheet's own review, the two `JOB_AUDIO_*`
kinds are drawn (so a recording uploaded by 9b is now visible in the timeline), and an accepted recording
can be taken out of ordinary use by a session holding `evidence.audio.remove`.

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

## Tier B lint ran over this slice on 2026-09-17, and two defects it found were corrected

Tracker 037's Phase 4 completion required the full lint of the affected application (`qa.md` §3.1
Tier B, `make android-lint`), which is the check Phases 9b–9e recorded as **NOT RUN**. It surfaced
two errors in this slice's own code, both corrected rather than left for a device to find:

1. **`JobAudioPlayer.player()` guarded `MediaPlayer(Context)` on API 31 (`S`), while the constructor
   was added in API 34 (`UPSIDE_DOWN_CAKE`).** On API 31–33 the app would have called a constructor
   that does not exist — a crash the JVM tests cannot see and that a device on Android 12 or 13 would
   have hit as soon as a recording was played. The guard now names the API that **added** the
   constructor (`dev.md` §1).
2. **`JobDetailsScreenTest.renderSheet` created a state object during composition**
   (`audioProgress = mutableStateOf(null)` inside `setContent`), which lint refuses as
   `UnrememberedMutableState`. The state is now created outside composition.

**Verification after the corrections:** `make android-lint` passes, and the JVM unit suite and the
device-test sources were re-run as part of tracker 037 Phase 4's Tier B sweep.
