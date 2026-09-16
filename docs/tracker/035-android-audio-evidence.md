# Tracker 035 — Audio evidence on a Job (tracker 029 Phase 9)

**Status: IN PROGRESS — Phase 9a (API, database, permissions) is being implemented; 9b and 9c follow.**

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
| `job_audio_notes` + `job_audio_note_removals`, `evidence.audio.add`/`evidence.audio.remove` | Phase 9a |
| The three API routes and the Activity kinds `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` | Phase 9a |
| Recorder, draft, queued upload, the sheet's audio kind becoming reachable | Phase 9b |
| Playback, timeline entry, EN/FR copy, design system | Phase 9c |
| Physical-device QA | Product owner |

## Phases

| # | Phase | Depends on | Layers |
| --- | --- | --- | --- |
| 9a | Audio evidence API: table, capability, routes, Activity kinds | `ADR-018` ✓, Phase 1 ✓ | database, API, permissions, tests, contracts |
| 9b | Android: record, draft, queue, upload, the reachable kind | 9a | Android, offline, localization, tests |
| 9c | Playback and the Activity surface | 9b | Android, design system, tests |
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

### Phase 9b — Android: record, draft, queue, upload

**Goal.** The technician records a note, it survives process death, uploads when connectivity allows and
never uploads twice.

**Work.** `RECORD_AUDIO` as a runtime permission; a recorder surface in the Add update sheet's audio kind
(record, stop, review, delete/re-record, attach — the design's own list); a Room draft table for
unsubmitted recordings (a Room migration, `BR-014`); the outbox handler for `job.audio.add` beside
`JobPhotoUploadHandler`; `JobDetailsScreen`'s gate reading `evidence.audio.add` so the kind stops being
unreachable; the tray/notice vocabulary a pending recording needs.

**Verification.** JVM tests for the recorder state machine, the outbox handler, the offered-kind gate and
the pending-state mapping; Compose sources compiled (`./gradlew assembleDebugAndroidTest`) and reported
**NOT RUN — device QA is the product owner's**.

### Phase 9c — Playback and the Activity surface

**Goal.** A recording plays back in the Job's Activity, and the evidence reads as evidence.

**Work.** The `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` timeline entries with their localized labels and
duration; playback through the platform's own media stack (`ADR-018` A9); the removal affordance for a
session holding `evidence.audio.remove`, behind the same confirmed, reason-carrying confirmation the photo
removal uses; EN/FR copy; `docs/design/android-design-system.md`.

**Verification.** JVM tests for the Activity mapping and the copy resolution; Compose cases compiled;
runbook for playback, offline recording and a refusal.

## Open questions carried by this tracker

1. **Unifying photos and audio into one kind-based evidence model** (`ADR-018` A1). Not decided; the
   triggers are in the ADR.
2. **MP4 container metadata** (`udta`/`©xyz`) and where the strip runs — tracker 029 Phase 6c owns the
   decision for evidence as a whole (`ADR-018` open question 2).
3. **Where the recording draft lives** if the technician records several notes for one update: one draft
   per update or one per recording. Phase 9b decides it from the sheet's own draft-state rules
   (`BR-091`'s phase-draft-state precedent in Phase 6c) rather than inventing a second policy.

## Phase log

Phase records are appended here when a phase lands, in the `qa.md` §16 format, and are never rewritten.

| Phase | Date | What landed | Files | Verification |
| --- | --- | --- | --- | --- |
| 9a | 2026-09-16 | **The audio evidence API.** `job_audio_notes` and `job_audio_note_removals` (append-only, the removal added beside the record), `evidence.audio.add`/`evidence.audio.remove` with their bilingual catalogue rows and default-role grants (Manager both, Technician `add` only), the three `/jobs/:id/audio-notes` routes (`add`, `content`, `removal`), `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` with `audioNoteId`/`audioPhase`/`audioDurationSeconds`/`audioRemovalReason`, the audit/history context covering removed recordings, and the byte rule: an audio-only MP4 whose **length is read from its own container** (`moov/mvhd`) and bounded at 1–300 s, at most 10 MiB, validated before anything is stored. The phase vocabulary photos and audio share is now defined once (`evidence-phase.ts`). Decided by `ADR-018` A1–A10; contracted in `docs/api/job-audio.md`. | `api/drizzle/migrations/0012_job_audio_notes.sql` (+ its snapshot), `api/src/database/schema.ts`, `api/src/auth/permissions.ts`, `api/src/database/run-development-seed.ts`, `api/src/jobs/evidence-phase.ts`, `mp4-audio.ts`, `job-audio.dto.ts`, `job-audio-notes.service.ts`, `job-activity.ts`, `jobs.controller.ts`, `jobs.module.ts`, `api/src/storage/object-storage.ts`, `api/src/database/unique-violation.ts` (the photo service's private helper, now shared), `docs/api/job-audio.md`, `docs/api/job-activity.md`, `docs/api/job-photos.md`, `docs/decisions/015-…`, `docs/decisions/018-…`, this tracker, tracker 029 | **API unit: PASS** (`npm test` — 408 tests, 47 files, including the new `mp4-audio.spec.ts` 7, `job-audio.dto.spec.ts` 14 and the rewritten `permissions.spec.ts` 3). **API e2e: PASS** (`npm run test:e2e` — 267 tests, 18 files, including the new `test/job-audio-notes.e2e-spec.ts` 21: `401`/`403` per route, the default Technician role recording and reading but not removing, the API-derived key and the Activity projection, idempotent replay, a refused type/phase/length, the tenant boundary, the bytes round trip, storage failure with nothing recorded, removal with the audit/history context, a second removal refused). **Typecheck: PASS** (`npm run typecheck`). **Lint: PASS** (oxlint, 0 warnings, 0 errors, 179 files). **Android: NOT RUN — no Android file was changed by this phase.** **No `adb` and no device or emulator command was run** (`qa.md` §7.3). Left running: the product owner's foundation stack (untouched); no Gradle or Kotlin daemon was started by this phase, so there was nothing to stop |

**Phase 9a's own limitation, stated rather than left to be discovered.** The API accepts and stores an
audio note, so a recording can be submitted and read today, but **no client can produce one yet**: the
Android recorder, the draft, the queued upload and playback are Phases 9b/9c, and the Add update sheet
keeps `canAddAudio = false` until then (`BR-042` — a kind nothing can add stays unoffered). The kind's
metadata is also not yet mirrored in the Android DTOs, which is 9b's work with the upload.
