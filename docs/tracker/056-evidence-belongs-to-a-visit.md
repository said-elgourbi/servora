# Tracker 056 — Evidence belongs to the Visit it was recorded on

**Status: IN PROGRESS — not Done.** The API, the database migration, the Android client **and the two API
e2e specs that upload evidence are written**. The API side is verified end to end again: the two specs were
updated to name the Visit and now pass (`npm run test:e2e -- test/job-photos.e2e-spec.ts
test/job-audio-notes.e2e-spec.ts`, **48 tests, 2 files — PASS**, 2026-09-28). What remains is Android JVM and
device verification (at the product owner's instruction the machine is not to carry those runs), the
evidence-ADR sweep, and the open questions recorded below.

Date: 2026-09-28

Type: **business-rule change with a database migration.** A photo and an audio recording are now recorded
against the **Visit** they were taken during, instead of the Job.

Business rules: `BR-001`, `BR-009`, `BR-014`, `BR-015`, `BR-027`, `BR-031`, `BR-032`, `BR-042`, `BR-047`,
`BR-051` (unchanged), `BR-067`, `BR-071`, `BR-080`, `BR-088`, `BR-089`, `BR-091` — and the change closes the
open question recorded in `docs/api/job-photos.md` §7.

## Why

Product ownership, after field testing: *text notes go to the right Visit's section in Job Activity, but
photos and audio go to General job updates at the bottom* — and *"I want photos and audio to be linked to
visits only"*.

The two behaved differently for a real reason, not a display bug: a text note is written by
`POST /jobs/:id/visits/:visitId/notes`, so it carries a Visit, while photos and audio were written by
`POST /jobs/:id/photos` and `POST /jobs/:id/audio-notes` and recorded `visit_id = NULL` on the **Job**. The
Activity read reports each entry with the Visit the API says it belongs to (`visitSequence`), and the screen
groups strictly by that answer (`BR-041`, `BR-042`) — so evidence landed in the Job-level group.

`D2` (2026-09-15, `docs/tracker/029-photo-evidence-phases.md`) decided evidence was Job-level *"with an
optional link from evidence to a Visit available later if a requirement names one"*. A requirement has now
named one, and `tracker 051`'s "a technician's field update on a Job Details screen carries `visitId`"
already assumed it.

## What changed

| Layer | Change |
| ----- | ------ |
| Database | `job_photos.visit_id` and `job_audio_notes.visit_id`, nullable FK → `visits.id` (`ON DELETE CASCADE`), migration `0017_evidence_visit_link.sql` |
| API — upload | `POST /jobs/:id/photos` and `POST /jobs/:id/audio-notes` require a `visitId` multipart field. The Visit must belong to the caller's organization **and** to that Job; a Visit that fails either check is refused `404 VISIT_NOT_FOUND` (`BR-001`, `BR-023`, `BR-042`) |
| API — projection | `JOB_PHOTO_ADDED`, `JOB_AUDIO_ADDED` and both removal events carry their evidence's Visit, so Activity reports the Visit's `visitSequence` for them (`BR-080`) |
| Android — upload | `addJobPhoto`/`addJobAudioNote` send the `visitId` part |
| Android — offline | The Visit is kept with the draft: `pending_job_photos.visitId` / `pending_job_audio_notes.visitId` (Room schema **4**, `MIGRATION_3_4`), the outbox payload carries it, and the upload replays it — so an offline capture keeps the Visit it was taken on (`BR-014`, `BR-031`, offline standard §5, §9) |
| Android — UI | The Job Details screen passes the Visit it represents (`BR-081`) at capture, at pick and when a recording stops. A capture that comes back with no represented Visit is refused with its own message in both languages rather than recorded with no attribution (`BR-042`) |

**What is deliberately not changed:** the permission model, the routes' shapes, the evidence lifecycle
(immutable once accepted, soft removal, no edit), the idempotency keys, the storage layout, and
`BR-051`'s rule that a Job may exist with no Visit. A Job with no Visit simply has no Add update action
(unchanged — it is gated on the represented Visit's `addUpdateAllowed`), so no evidence can be recorded
without a field attempt to belong to.

## What was decided, and what was NOT invented

* **Pre-existing evidence keeps no Visit.** Rows written before this change were recorded on the Job, and no
  rule attributes them to a Visit retroactively — picking one would be inventing business data
  (`BR-042`, `BR-088`), and deleting them would silently destroy evidence (`BR-088` – `BR-090`). The columns
  are therefore nullable **for those rows only**; no write path can leave them null, and the upload handler
  refuses a queued draft that has none rather than uploading it with an invented attribution.
  On the product owner's device this leaves the handful of photos and recordings uploaded before the change
  in the Job-level group; they can be discarded or the local dataset re-seeded whenever they want.
* **The Job-level group itself is untouched.** After this change it holds only the Job's own lifecycle events
  (`JOB_STATUS_CHANGED`, `JOB_PROPERTY_CHANGED`, `JOB_CUSTOMER_CHANGED`) — which `BR-080` names as Activity
  and which no rule moves onto a Visit. Removing the group would hide them, so it is left in place: whether
  the office's Job-level history should be presented differently is a separate product decision.

## Files

| File | Change |
| ---- | ------ |
| `api/src/database/schema.ts` | `visitId` on `jobPhotos` and `jobAudioNotes` |
| `api/drizzle/migrations/0017_evidence_visit_link.sql` | the two columns and their foreign keys, hand-written (see the note below) |
| `api/drizzle/migrations/meta/0017_snapshot.json`, `meta/_journal.json` | the generated snapshot and the journal entry |
| `api/src/jobs/job-photo.dto.ts`, `job-audio.dto.ts` | `visitId` required on both upload DTOs |
| `api/src/jobs/job-photos.service.ts`, `job-audio-notes.service.ts` | `requireVisit` (organization **and** Job) before the write; the column is written |
| `api/src/jobs/jobs.controller.ts` | both evidence error mappers report `VISIT_NOT_FOUND` |
| `api/src/jobs/job-activity.ts` | the four evidence events carry their Visit; the removal reads join the evidence |
| `api/src/jobs/job-photo.dto.spec.ts`, `job-audio.dto.spec.ts` | every field block names the Visit; a new test per kind for the missing/invalid Visit |
| `android/…/data/jobs/JobDetailsApi.kt` | the `visitId` part on both uploads |
| `android/…/data/jobs/JobPhotoOperations.kt`, `JobAudioOperations.kt` | the payload carries it |
| `android/…/domain/model/JobPhoto.kt`, `JobAudioNote.kt` | `PendingJobPhoto.visitId`, `PendingJobAudioNote.visitId` |
| `android/…/data/jobs/PendingJobPhotoEntity.kt`, `PendingJobAudioNoteEntity.kt` | the local column |
| `android/…/data/jobs/PendingJobPhotoStore.kt`, `PendingJobAudioNoteStore.kt` | the payload codec and both mappings |
| `android/…/data/offline/OfflineMigrations.kt`, `OfflineDatabase.kt` | `MIGRATION_3_4`, schema version 4 |
| `android/…/data/jobs/JobPhotoSession.kt`, `JobAudioSession.kt` | the recording operations take the Visit |
| `android/…/data/jobs/JobPhotoUploadHandler.kt`, `JobAudioUploadHandler.kt` | send the Visit; refuse a payload with none |
| `android/…/data/jobs/JobPhotoRecording.kt`, `JobAudioRecording.kt` | `NO_VISIT` refusal |
| `android/…/ui/jobs/JobDetailsUiState.kt`, `JobDetailsViewModel.kt`, `JobPhotoComponents.kt`, `JobAudioComponents.kt` | the represented Visit is passed and the refusal is reported |
| `android/app/src/main/res/values*/strings.xml` | `job_photo_error_no_visit`, `job_audio_error_no_visit` (EN + FR) |
| Android test sources | the fixtures and fakes that build pending evidence, and one assertion per upload handler that the Visit is sent |

**A migration note.** `db:generate` produced a polluted `0017` — the schema snapshot chain has no snapshot
for `0016_job_visit_lifecycle_redesign`, so drizzle diffed against `0014` and repeated 0015's table and
0016's constraints. The SQL is therefore written by hand (like 0016) and the generated
`meta/0017_snapshot.json` is kept, which re-baselines the chain at this migration.

## Verification

Scope (`qa.md` §3.1): the change crosses the API, the database and Android, so it is **Tier B territory**
(a schema change, a shared contract and offline behaviour) — but the machine is not to carry test runs or
lint, at the product owner's instruction, so only compiles and the two DTO unit specs were run.

| Check | Command | Result |
| ----- | ------- | ------ |
| API type check | `cd api && npm run typecheck` | **PASS** (`tsc --noEmit`, exit 0) |
| API unit — the two upload DTOs | `cd api && npx vitest run src/jobs/job-photo.dto.spec.ts src/jobs/job-audio.dto.spec.ts` | **PASS** — 2 files, **30 tests**, including the two new "requires the Visit" tests |
| API e2e | `cd api && npm run test:e2e -- test/job-photos.e2e-spec.ts test/job-audio-notes.e2e-spec.ts` | **PASS — 2 files, 48 tests** (2026-09-28). The fixture now creates the Visit each Job's evidence is recorded on and the upload helper names it; two cases per kind are new: a request that names no Visit is refused `400 VALIDATION_FAILED`, and a Visit of another Job or another organization is refused `404 VISIT_NOT_FOUND`. Both specs also assert the stored row's `visitId` and the `visitSequence` the Activity read reports for the add and the removal (`1`, not `null`) |
| Android compile (main) | `cd android && ./gradlew compileDebugKotlin` | **PASS** |
| Android JVM test sources | `cd android && ./gradlew compileDebugUnitTestKotlin` | **PASS** |
| Android device-test sources | `cd android && ./gradlew compileDebugAndroidTestKotlin` | **PASS** — `OfflineDatabaseTest` and `JobDetailsScreenTest` compile their Visit-carrying fixtures |
| Android lint, JVM tests, device tests | — | **NOT RUN — at the product owner's instruction** (compile only); `connectedDebugAndroidTest` is the product owner's in any case (`qa.md` §7.3) |

Room's exported schema for the new local version (`app/schemas/…OfflineDatabase/4.json`, written by the
compile) shows `visitId` as a nullable `TEXT` column on both pending tables — exactly what `MIGRATION_3_4`
alters, so Room's runtime schema check agrees with the migration.

## Manual QA runbook (product owner)

```text
Android QA — evidence belongs to the Visit

1. Apply the migration and restart the API (make migrate; the API must be running).
2. Sign in as a Technician, open the Job you are working and open the Visit's Add update.
3. Take a photo (and a recording), attach them, and read Job Activity.

Expected: the photo and the recording appear inside that Visit's group — `Visit N · date` — beside the text
note, and not in General job updates. Expanding General job updates shows only the Job's own lifecycle
events (and anything uploaded before this change).
```

4. With the device in airplane mode, take a photo and attach it. Reconnect, let the queue replay, and refresh
   the Job: the photo appears in the same Visit's group it was taken on (`BR-014`, offline standard §5).
5. Complete the Visit you are working, then re-open the Job: the evidence stays with that Visit's group, and
   General job updates is still the only place the Job's own events are stated.

## What is outstanding

| # | Item | Why it matters |
| - | ---- | -------------- |
| 1 | ~~**`api/test/job-photos.e2e-spec.ts` and `api/test/job-audio-notes.e2e-spec.ts` are not updated.**~~ **Done 2026-09-28.** Both fixtures create the Visit the evidence is recorded on (`DRAFT`, `visits.visitId`), the upload helpers send `visitId` (and can send none), a `visitFor(jobId)` accessor fails loudly when a fixture Job has no Visit, and the new cases cover the missing Visit (`400`) and a Visit of another Job or organization (`404 VISIT_NOT_FOUND`) | The API's own behaviour is unverified end to end until they are |
| 2 | Android JVM tests and device tests were **not run** — compile only, at the product owner's instruction | `./gradlew testDebugUnitTest --tests 'com.servora.android.data.jobs.*'`, `connectedDebugAndroidTest` |
| 3 | `docs/api/job-photos.md`, `docs/api/job-audio.md`, `docs/api/job-activity.md`, `docs/domain/job-visit-domain-model.md`, `docs/design/android-design-system.md`, `README.md` and `docs/tracker/029-photo-evidence-phases.md` **are updated**; what remains is a full sweep of the evidence ADRs (`docs/decisions/013…018`) for statements that a photo is held by the Job | Documentation must not describe behaviour that no longer exists (`dev.md` §14) |
| 4 | `docs/tracker/029-photo-evidence-phases.md` `D2` is superseded by this tracker and says so | The decision record must point forward |
| 5 | **The closed-Job trap and the one-word refusal** — a report from the field and three questions it raises are recorded in the section below and are **not** implemented | They are product/UX decisions, not implementation choices (`BR-042`, `Project.md` §30) |

## Reported from the field — "Refused by the server" when saving a photo (2026-09-28)

After the API image and the debug APK were both rebuilt from this slice, a Technician saving a photo saw the
tray's **"Refused by the server"** state. That state is the tray's single label for *every* terminal refusal
(`JobPhotoSyncState.REFUSED`), so it names no reason — and this slice introduced two ways to reach it that did
not exist before. Both are "it worked before" for the same reason: at the slice's base commit the photo route
checked only that the Job existed and required no Visit (`git show <base>:api/src/jobs/job-photos.service.ts` —
`requireJob`, no status check; `job-photo.dto.ts` — no `visitId`).

| # | What reaches the state | What changed | How it can be told apart |
| - | ---------------------- | ------------ | ------------------------ |
| A | The upload is answered `409 JOB_CLOSED_FOR_FIELD_WORK` because the Job is `COMPLETED` or `CANCELED` — the office closed it, or the technician's own `RESOLVED` completion closed it (`BR-062`, `BR-078`). The API refuses it in `requireOpenJob`, which this slice added to both evidence routes | At the base commit the same upload succeeded: `requireJob` accepted a Job in any status. The refusal is **terminal** for the client, so the queued row and the bytes stay until the technician discards them (`BR-014`, `BR-032`) | Was the Job completed or canceled when the photos were saved? `JobDetails.readOnlyReason` is `JOB_COMPLETED` / `JOB_CANCELED` and the screen shows its read-only notice |
| B | The upload handler refuses the queued draft **itself**, before any request: its payload has no `visitId`, because it was written by a build older than this slice. `ReplayOutcome.Rejected(INVALID)` → the same tray label, with the server never asked | The handler is written to refuse rather than upload evidence with an invented attribution — deliberate, and recorded above | Was the photo captured (or left unsaved) before the APK carrying `visitId` was installed? Such a draft can only be discarded; a capture taken by the current build always names a Visit |

**A photo already in this state cannot be uploaded as it stands** — a refused upload is never replayed (`§6`).
Discard it from the tray and capture it again with the current build; if the Job is closed, reopen it first
(`BR-063`) or record the photo against the new field attempt a reopen implies (`BR-051`, `BR-071`).

### Open questions this surfaced — **not implemented** (`BR-042`)

1. **The tray's Save action is the one field write the closed-Job answer does not hide.** `readOnlyReason`
   withholds the Add update action, the Visit status destinations and the completion action, and
   `JobDetailsUiState` states the principle: a closed Job "offers no action either: the API refuses every
   field write under it, so the screen draws no control whose only answer is that refusal" (`BR-062`,
   `BR-079`). The photo tray's **Save photos** is gated only on `unsaved > 0`, so a photo captured while the
   Job was open is still submitted after it closed and the only answer is case A's `409`. Should the tray
   stop offering Save while `readOnlyReason` is set — keeping the photos visible and discardable, since
   `BR-014` forbids silently losing them — and what should it show instead?
   (`docs/design/android-design-system.md` owns the tray's controls.)
2. **Two very different causes read as one word.** "Refused by the server" is shown for a `403`, a `404`, a
   `409`, an invalid body — and for a refusal the **client itself** made without asking the server (case B).
   The outbox row already carries the reason (`OutboxFailureReason`) and the visit-action path already
   resolves reason-specific copy from it (`pendingFailureText`); the evidence tray does not. Is reason-level
   copy wanted for evidence too, and may a locally made refusal say so instead of naming the server?
3. **What should happen to evidence captured while a Job was open but submitted after it closed?** `BR-014`
   and `BR-015` require captured field work not to be lost and a failed upload not to delete it, while case A
   is terminal: the technician's only exit is to discard it. Should the office's closure drop that evidence,
   or should a client be able to re-queue it once the Job is reopened (`BR-063`)? No rule decides it.
