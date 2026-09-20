# Servora

Field-service management platform.

Servora is a monorepo built feature by feature as controlled vertical slices.

- **API** — NestJS (TypeScript ESM) + PostgreSQL (Drizzle ORM). The backend is the authoritative system of record (BR-001).
- **Android** — Kotlin / Jetpack Compose native field application (offline-first). Implemented for
  authentication, appearance controls, the Manager Home, the Manager Job Details screen (read and
  management actions), customers, the customer Property surface, and technician photo evidence
  (capture, offline upload, gallery, viewer, zoom). Photo evidence is drawn by **Coil 3** behind the
  feature's own `JobPhotoImages` port, and the full-size viewer zooms and pans through **Telephoto**
  over that same stack (`docs/decisions/016-android-image-stack-and-viewer-zoom.md`). The Job and its
  Activity — and so the evidence it holds — are readable offline through the working set, and a photo
  that is on neither the device nor the image stack's cache is reported as not available offline
  (`docs/tracker/029-photo-evidence-phases.md`, Phase 5).
- **Angular** — management/office client. *Not yet implemented.*

Initial supported languages are **English** and **French**. Development rules live in
`Project.md`, `dev.md`, `qa.md` and the application rules in the project ruleset.

> The repository currently contains the API foundation and the **foundation domain model**
> (organizations, users, profiles, memberships, customers and their contacts/addresses).
> See `docs/tracker/001-foundation.md`, `docs/tracker/002-repository-foundation.md`,
> `docs/tracker/003-foundation-domain-model.md` and
> `docs/tracker/004-authentication-domain-model.md` for status.
> Android app appearance controls (app language, light/dark) are covered by
> `docs/tracker/005-android-appearance-controls.md`.
>
> Authentication persistence (sessions, refresh tokens, password reset tokens) is modelled in
> `docs/domain/authentication-domain-model.md`.
>
> Job, Property and Visit business rules are defined in `Business Rules.md` (§8, §11, §12 and §13;
> BR-047 – BR-086). The **Property** part of the Job & Visit data model is migrated
> (`0007_property_lifecycle_and_permissions`), and the **Job and Visit** tables are migrated by
> `0003`; their first read is `GET /jobs/:id`.
>
> Customer, Property-permission and Property-lifecycle work is tracked in
> `docs/tracker/006-customers-feature-permissions.md` … `docs/tracker/013-property-lifecycle.md`.
> The Property permission set and the lifecycle decisions are recorded in
> `docs/decisions/012-property-lifecycle-and-permissions.md`, and the offline/outbox standard the
> Property lifecycle follows is `docs/architecture/offline-first-architecture.md`.
>
> Add Customer is `docs/tracker/014-android-add-customer.md`; Edit Customer, including converting a
> customer between individual and company (`BR-087`), is `docs/tracker/015-android-edit-customer.md`.
>
> The Android Manager Home and the operational read behind it (`GET /home/manager`) are
> `docs/tracker/016-android-manager-home.md`; the wire contract is `docs/api/manager-home.md`.
>
> The Android Manager Job Details screen and the job read behind it (`GET /jobs/:id`) are
> `docs/tracker/017-android-job-details.md`; the wire contract is `docs/api/job-details.md`.
>
> Local object storage — MinIO behind an S3-only application contract, so production can use a
> third-party S3-compatible provider — is `docs/tracker/023-object-storage-minio.md`
> (decision: `docs/decisions/013-object-storage-minio-and-s3.md`). The storage adapter and the first
> evidence feature, technician Job photo updates, are `docs/tracker/027-android-job-photo-updates.md`
> (wire contract: `docs/api/job-photos.md`), consolidated into one entry point by
> `docs/tracker/028-android-unified-job-update.md`.
>
> The photo-evidence work that follows — the capability set, a photo picker, a viewer, offline evidence
> and the lifecycle questions — is planned in `docs/tracker/029-photo-evidence-phases.md`. **Its Phase 1,
> the evidence capability set (`evidence.view`/`evidence.photo.add`), Phase 2, the content-type-aware
> capture pipeline, Phase 3, the two photo sources (camera and the system photo picker) with the client
> permission gate, Phase 4, the full-size viewer, Phase 4b, which turns a re-encoded photo's pixels so
> what Servora stores is upright, Phase 4c, the image stack (Coil 3 behind the existing
> `JobPhotoImages` port, with a per-session memory and disk cache), Phase 4d, the viewer's pinch-zoom and
> pan, Phase 4e, which makes the viewer page through the Job's photos and lets a photo be saved on the
> device or shared with another application, and **Phase 5, which makes the Job and its Activity readable
> offline through the working set** — with the evidence a Job holds (which photos, with their phase, note
> and time) marked on screen as the last answer the backend reported, the photo-byte cache stated as one
> rule (a pending upload's bytes are never a cache entry, accepted evidence is a bounded, evictable LRU,
> and nothing is prefetched), and a photo that is on neither the device nor that cache reported as **not
> available offline** — are implemented**, with the capability
> decision in
> `docs/decisions/015-evidence-capabilities.md`. **Phase 0's remaining questions were decided on
> 2026-09-15**: the image stack is Coil 3 behind the existing `JobPhotoImages` port (D4b,
> `docs/decisions/016-android-image-stack-and-viewer-zoom.md`), the viewer gains pinch-zoom and pan (D9,
> Telephoto), evidence is Job-level (D2), a re-encoded photo is stored upright (D7b), a refused photo
> becomes discardable (D6c), and the device's media library is not read (D10).
> **Phase 0 is now answered in full (2026-09-16)** — the evidence lifecycle, metadata, audio, the evidence
> entry point and the object store's read model are all decided and recorded as business rules `BR-088` –
> `BR-091`: accepted evidence is **immutable** (no overwrite, no edit route, and removal only through an
> explicit, audited, Manager-level `evidence.photo.remove` that soft-removes it), its **metadata is readable
> offline** with its bytes cached best-effort, **retention is indefinite for the life of the tenant**,
> archiving a Job never deletes evidence, **GPS/location EXIF is stripped** from what is uploaded, `phase`
> stays the only structured classification, **audio notes are in scope** as their own evidence kind while
> generic files are out, and the store gains **no derived thumbnails** — reads move to short-lived
> presigned URLs issued by the API. **Phase 5 (offline visibility of accepted evidence), Phase 6a (a
> refused photo is explicitly discardable) and Phase 6b (removing accepted evidence) are implemented
> (2026-09-16); the phase to run is Phase 6c (evidence metadata: GPS/EXIF stripping and the phase as draft
> state), and Phase 8 is startable behind it.** **Phase 9 (audio evidence) now has its own tracker**: the
> model, the vocabulary and the capability are decided in `docs/decisions/018-audio-evidence.md` (A1–A11)
> and its **API half landed on 2026-09-16** — `job_audio_notes` + `job_audio_note_removals`,
> `evidence.audio.add`/`evidence.audio.remove`, the three `/jobs/:id/audio-notes` routes and the
> `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` Activity kinds, contracted in `docs/api/job-audio.md`
> (`docs/tracker/035-android-audio-evidence.md`). **Its Android half landed on 2026-09-16 (Phase 9b)**: the
> microphone as a runtime permission, the audio kind's own controls in the Add update sheet (record, stop,
> review, delete/re-record, attach), a durable Room draft for a take the API has not accepted, the queued
> offline upload (`job.audio.add`, beside the photo handler) and the notice that reports an unaccepted take
> — so the *Add audio* kind is now offered on `evidence.audio.add`. **Phase 9c landed on 2026-09-17 —
> playback and the Activity surface**: `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` are drawn in the timeline
> with the recording's phase, the length the API read from its container and a play/pause control, **one
> player** serves that entry and the sheet's review (`ADR-018` A9) — so a recording the technician
> recorded is played back where it was recorded — an accepted recording's bytes are read through the API
> once into a session-scoped, evictable cache, and a session holding `evidence.audio.remove` may take a
> recording out of ordinary use behind the same confirmed, reason-carrying dialog the photo removal uses
> (`evidence.audio.remove` added to the Android capability set). **Phase 9e landed on 2026-09-17 — a
> seekable playhead**: a recording states where it is and how long it is (`0:04 / 0:18`) and can be moved
> through with a seek track — the fill and the playhead, **no amplitude**, no waveform (`ADR-018` A11) — and
> a second tap pauses a recording where it got to rather than letting its position go. Device QA of the audio
> evidence is that tracker's Phase 9d.
>
> The technician's field experience — the field read (`GET /home/technician`), the Visit field
> lifecycle (`BR-074`'s free movement, with the outcome a completion requires) and the technician
> home — is `docs/tracker/037-technician-field-experience.md`, with its decisions in
> `docs/decisions/019-technician-field-experience.md` (the field capability, the assignment scope,
> the offline posture) and its contracts in `docs/api/job-actions.md` and
> `docs/api/technician-home.md`.
>
> The **Manager Schedule** — the week strip and the selected day's agenda, the technician filter and
> the work that still has no crew — is `docs/tracker/038-android-manager-schedule.md`, served by a new
> `GET /schedule` read contracted in `docs/api/schedule.md` and decided in
> `docs/decisions/020-manager-schedule-and-unassigned-lane.md`. The month calendar is a
> jump-to-date mechanism only; the screen schedules nothing (assignment, rescheduling and status
> actions remain the Job Details screen's).
>
> The schedule's **density pass** — one compact control row instead of two stacked rows, a collapsing
> week strip, a multi-technician filter (which made `GET /schedule`'s `membershipId` repeatable and its
> ids a union) and a tighter card/timeline — is
> `docs/tracker/040-android-manager-schedule-density.md`. It also carries the two defects
> physical-device QA reported and their fixes (2026-09-17): the technician sheet's **Apply** now closes
> the sheet, and an action's report is drawn **above** whatever a screen pins to the bottom — the floating
> **Add update** action and the evidence waiting to be saved — so its whole text is readable (`BR-042`).
>
> The **technician's own schedule** ("My Schedule") — the same `GET /schedule` read resolved for the
> caller's own work, presented as a week strip and a day of the Visits assigned to that technician, with
> the crew line (`You + 2`), an emphasised next Visit, quieter completed Visits, and the day kept in the
> existing offline working set — is `docs/tracker/041-android-technician-schedule.md`, with the read's
> scope decided in `docs/decisions/020-manager-schedule-and-unassigned-lane.md` (decision update) and
> contracted in `docs/api/schedule.md`. **A field member cannot read a colleague's schedule**: no
> capability authorizes it, the API refuses an off-scope request with `403`, and the question is recorded
> as an open one rather than invented (`BR-042`).
>
> The **technician's read of the Customer behind an assigned Job** — the customer's own phone, email and
> notes on the Job card, view-only, reached only through a Job the technician's own crew includes — is
> `docs/tracker/042-technician-customer-read.md`, decided in
> `docs/decisions/021-technician-customer-read.md` (a dedicated `customers.view_assigned` capability,
> granted to the default Technician role by migration `0013`) and contracted in `docs/api/job-details.md`.
> A session without it reads the same Job with the block reported **absent**, never a refused Job, and the
> office Customer screen is no longer offered where its read would answer `403` (`BR-009`, `BR-011`,
> `BR-092`).
>
> **Create Job** — `POST /jobs` and the Android Create Job form — is
> `docs/tracker/043-create-job.md`. The supported create operation requires both an active Customer and an
> active Property that Customer currently holds (`BR-094`, new), which the Job & Visit model itself does
> not: a property-less Job remains valid (`BR-051`, `BR-056`), and nothing in the schema changed. The route
> is guarded by the existing `JOB_CREATE` capability and allocates the organization-scoped Job number under
> a counter that makes concurrent creation safe (`BR-052`); it creates **no Visit** (`BR-047`, `BR-051`).
> The contract is `docs/api/job-details.md` §6 and the create's place in the domain model is
> `docs/domain/job-visit-domain-model.md` §6.5.
>
> **Job Details presents the Job and its Visits apart** — the Job's own overview, the Visit that
> represents it with **its own** activity, the Job's other Visits as folded history, and the Job's own
> events in their own section — is `docs/tracker/044-job-details-job-visit-separation.md`. `GET /jobs/:id`
> gained one additive field (`visits`) because a history row needs each Visit's date, status and crew and
> nothing in the contract carried them (`BR-042`, `BR-047`, `BR-071`); which event belongs to which Visit
> is the API's own `visitSequence` answer and not a client's guess (`BR-080`,
> `docs/api/job-activity.md` §3.3). The previous screen is retained unmodified as the restore point.
>
> **Its activity is grouped by the Visit each update belongs to** — one foldable group per Visit, headed
> `Visit N · date`, opened on the Visit the page represents, plus a **General job updates** group for the
> events that belong to no Visit — is `docs/tracker/045-android-job-activity-visit-groups.md`. Grouping
> uses the read's own `visitSequence` because no Visit id crosses the activity contract, and each group's
> crew comes from that Visit's own crew list, which is **assignment** and is labelled as such (`BR-068`;
> Servora records no attendance, `BR-034`).
>
> **The represented Visit is stated by when it is for, and its activity is headed by its own card** — the
> section is labelled `Current visit` only while that field attempt is under way or its window is running,
> and otherwise `Today's visit`, `Tomorrow's visit`, an `Upcoming visit` or a `Previous visit`; and an
> expanded group's content is headed by one bordered card stating that Visit's date, scheduled window and
> assigned crew — is `docs/tracker/046-android-job-details-visit-period-and-card.md`. The period is read
> from the Visit's own schedule and field status plus the device's clock, and it re-selects nothing: which
> Visit represents the Job stays the API's answer (`BR-081`).
>
> **That card also states what the field attempt resulted in** — one **Outcome** row between the scheduled
> window and the crew, drawn only when the Visit holds one — is
> `docs/tracker/048-android-job-activity-visit-outcome.md`. It is the Visit's **current** outcome, so
> `GET /jobs/:id` gained one additive field (`visits[].outcomeCode`): a Visit that was reopened after
> completion holds none (`BR-079`), which is exactly why the row is read from the Visit and not derived from
> the timeline, where the outcome it recorded stays as history (`BR-001`).
>
> **Two temporary one-tap sign-in buttons for local device QA** — Manager and Technician, present only in a
> debug build because the release source set carries no accounts — is
> `docs/tracker/049-android-dev-sign-in-buttons.md`. Development tooling rather than product behaviour: each
> button submits the seeded account's real credentials through the ordinary sign-in path.

## Layout

```text
api/        NestJS REST API (system of record)
android/    Android client (scaffold — not yet implemented)
docs/
  decisions/     Architecture decision records
  architecture/  Architecture standards
  tracker/       Feature milestone tracker
  domain/        Domain model documentation
docker-compose.yml   Foundation local stack (PostgreSQL + API + MinIO)
Makefile             Local development orchestration
```

## Prerequisites

- Node.js ≥ 24 (see `api/package.json`)
- npm
- Docker with the Compose plugin
- JDK + Android SDK (only required for Android targets)

## Quick start

```bash
cp .env.example .env        # then adjust local values if ports 5432/3000/9000/9001 are taken
make up                     # build + start PostgreSQL, the API and MinIO
curl http://localhost:3000/health
```

`/health` reports API and database status:

```json
{"status":"ok","database":"up"}            // HTTP 200
{"status":"unavailable","database":"down"} // HTTP 503 when PostgreSQL is unreachable
```

The API boots even when PostgreSQL is down (the database pool is lazy) and reports
`down` via `/health` until the database becomes reachable.

## Common commands

```bash
make ps                  # foundation service status
make logs                # follow foundation logs
make migrate             # apply pending migrations (host tooling → .env DATABASE_URL)
make migration NAME=…    # generate a new Drizzle migration
make db-shell            # psql shell into the development PostgreSQL
make api-test            # API unit tests (Vitest)
make api-test-e2e        # API e2e tests against PostgreSQL (needs `make up`)
make api-lint            # API lint (oxlint)
make api-start           # run the compiled API on the host
make android-build       # Android debug APK (requires Android SDK)
make android-stop        # stop the Gradle/Kotlin build daemons an Android build leaves behind
make tidy                # the same, plus a report of anything else still running
```

`make help` lists every target. Android builds leave Gradle and Kotlin daemons holding several GB of
RAM; `make android-stop` (`make tidy`) releases them — see `docs/development/setup.md` §8.

## Configuration

- `docker-compose.yml` is driven by `.env` at the repository root (copy from `.env.example`).
- `POSTGRES_PORT` / `API_PORT` select the host ports published by the stack.
- `DATABASE_URL` is used by **host-side** tooling (`make migrate`, `npm run start:prod`,
  `npm run test:e2e`). The compose `api` service ignores it and reaches PostgreSQL over
  the compose network.
- When a non-default `DATABASE_URL`/`POSTGRES_PORT` is required on a machine where the
  default ports are taken, set them in the local `.env` — never commit machine-specific
  values (`.env` is git-ignored).
- `MINIO_PORT` / `MINIO_CONSOLE_PORT` select the host ports published for object storage
  (defaults 9000/9001), and `S3_BUCKET` names the bucket `make up` provisions. The
  application contract is S3 — `S3_ENDPOINT`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`,
  `S3_FORCE_PATH_STYLE` and `S3_PUBLIC_ENDPOINT` are documented in `.env.example` and become
  active when the storage adapter lands. See `docs/development/setup.md` §7 and
  `docs/decisions/013-object-storage-minio-and-s3.md`.

- Authentication token lifetimes are configurable per environment: `ACCESS_TOKEN_LIFETIME`
  (default `15m`), `SESSION_LIFETIME` (default `30d`) and `PASSWORD_RESET_TOKEN_LIFETIME`
  (default `30m`). Durations accept `500ms`, `15m`, `2h`, `30d`; the API refuses to start on
  an unusable value. See `docs/domain/authentication-domain-model.md` §7.


## Repository & versioning

- Canonical repository: `https://github.com/said-elgourbi/servora.git` (remote `origin`).
- `main` — stable, release-ready; releases are tagged from `main` (`v0.1.0`, …).
- `develop` — primary integration and development branch (default working branch).
- Foundation status: `docs/tracker/001-foundation.md`,
  `docs/tracker/002-repository-foundation.md`, `docs/tracker/003-foundation-domain-model.md`,
  `docs/tracker/004-authentication-domain-model.md`.
- Domain model: `docs/domain/foundation-domain-model.md`.
- Authentication domain model: `docs/domain/authentication-domain-model.md`
  (decision: `docs/decisions/004-authentication-domain-model.md`).
- Android appearance controls: `docs/tracker/005-android-appearance-controls.md`
  (decision: `docs/decisions/007-android-appearance-controls.md`).
- Android customers, customer detail and Add Property: `docs/tracker/006-…` – `docs/tracker/012-…`.
- Property permissions, the archived projection and the Property lifecycle schema:
  `docs/tracker/012-property-permissions-and-lifecycle-schema.md`
  (decision: `docs/decisions/012-property-lifecycle-and-permissions.md`).
- Property lifecycle (edit, archive, restore, permanent delete) across the API and Android:
  `docs/tracker/013-property-lifecycle.md`.
- Add Customer: `docs/tracker/014-android-add-customer.md`.
- Edit Customer and customer type conversion (individual ⇄ company, `BR-087`):
  `docs/tracker/015-android-edit-customer.md`.
- Android Manager Home and its operational read: `docs/tracker/016-android-manager-home.md`
  (wire contract: `docs/api/manager-home.md`).
- Android Manager Job Details and its Job read: `docs/tracker/017-android-job-details.md`
  (wire contract: `docs/api/job-details.md`); its management actions:
  `docs/tracker/018-android-job-actions.md` (wire contract: `docs/api/job-actions.md`).
- Manager Job Details hierarchy and contextual actions: `docs/tracker/019-android-job-details-hierarchy.md`.
- The Job's status chip as the status control, and the address row's map glyph:
  `docs/tracker/020-android-job-details-status-control.md`.
- Job Details status polish — the labelled **Job status** control, its compact menu and the Visit date
  label: `docs/tracker/021-android-job-details-status-polish.md`.
- Offline/outbox standard for Android, including the rule a new read or mutation follows (§13):
  `docs/architecture/offline-first-architecture.md`
  (decision: `docs/decisions/014-android-offline-engine.md`).
- Object storage and the S3 contract (MinIO locally, a third-party S3-compatible provider in
  production): `docs/tracker/023-object-storage-minio.md`
  (decision: `docs/decisions/013-object-storage-minio-and-s3.md`).
- Technician Job photo updates — capture, offline upload through the outbox, and the Activity gallery:
  `docs/tracker/027-android-job-photo-updates.md` (wire contract: `docs/api/job-photos.md`).
- Photo evidence after 028 — the phased plan for the capability set, a gallery picker, a viewer, offline
  evidence and the lifecycle questions. **Phases 1 (the evidence capability set), 2 (the
  content-type-aware capture pipeline), 3 (the camera and system photo picker behind the update
  action, and the client's evidence gate), 4 (the full-size viewer a photo opens into), 4b (a
  re-encoded photo's pixels are turned, so what Servora stores is upright), 4c (the image stack:
  **Coil 3** draws every preview and the full-size photo behind the existing `JobPhotoImages` port,
  with a memory and disk cache keyed and released per session subject, and the API read keeping its
  `401 → renew once` path), 4d (the viewer's **pinch-zoom and pan**, through Telephoto over that stack)
  and 4e (the viewer **pages** through the Job's photos, and a photo can be **saved on the device** or
  **shared** with another application) have
  landed; a reported portrait-orientation display defect was fixed on 2026-09-15 (a decode now applies
  the photo's own EXIF orientation — the image stack applies it from 4c on). Phase 0's remaining
  questions were decided on 2026-09-15: evidence is
  Job-level (D2), the image stack is Coil 3 behind the existing port (D4b) with pinch-zoom and pan in the
  viewer (D9), a re-encoded photo is stored upright (D7b), a refused photo becomes discardable (D6c), the
  device's media library is not read (D10), and D5 was deferred. **Phase 4d (viewer gestures) landed on
  2026-09-15**: the full-size photo is drawn zoomable and pannable through **Telephoto** over the same
  Coil 3 stack — a pinch zooms, a drag pans within the photo's bounds, and a double-tap goes to the zoom
  ceiling — while the phase badge, the note and the close action, the viewer's three states and the
  platform's back gesture are unchanged. **Phase 4e (viewer paging, save and share) landed on
  2026-09-15**: the viewer draws the Job's photos as one sequence in the order Job Details presents them
  (evidence the backend holds first, then the photos this device still holds), a swipe pages only while
  the photo is at fit — a zoomed photo still pans — the chrome carries the photo's phase, a localized
  position and its note, and two actions read evidence on `evidence.view`: **Save to device** (a `Servora`
  album in the device's own gallery; no permission from Android 10, and `WRITE_EXTERNAL_STORAGE` capped
  at API 28 for Android 8–9, asked for when a save needs it) and **Share** (the platform's own chooser,
  through one added `FileProvider` path). **Phase 4f (the viewer's chrome) landed on 2026-09-16**: the viewer
  is a media surface of its own — a black ground and white ink rather than the theme's `surface`, the photo
  filling the screen under a minimal overlay top bar (close · `1 / 4` · Save/Share icons), the phase as a
  badge over the photo, a **Notes** section with **More**, progress on the action that is running, and the
  report of a save, a share or a refusal drawn **inside the viewer** rather than behind it — which is the
  defect it fixes, since the viewer is a window of its own and the screen's Snackbar was under it.
  **The review panel was fixed on 2026-09-16**: its content **scrolls**, so the note stays reachable while
  the keyboard is up instead of being left underneath it, and its preview draws the **whole** photo rather
  than a crop of it — the tray and gallery tiles still crop, a tile being a square that stands for a photo.
  **Phase 6a (refused photos are explicitly discardable) landed on 2026-09-16**: a photo whose upload the
  backend permanently refused carries a **Discard** action beside the reason the tray reports, and one
  action removes its file, its pending record and its queued refusal together — the refusal is terminal and
  never replayed, so it stops occupying the device, while a queued or retrying upload is left exactly as it
  is (`BR-014`). **Phase 6b (removing accepted evidence) landed on 2026-09-16**: accepted evidence is
  immutable, so the API gained no edit route and only the one operation `BR-089` defines —
  `POST /jobs/:id/photos/:photoId/removal`, authorized by the new Manager-level `evidence.photo.remove`,
  appends a `job_photo_removals` record carrying **who** removed the photo, **when** and **why** while
  `job_photos` stays append-only. The removal takes the evidence out of ordinary use — it leaves the Activity's
  photos, every gallery drawn from them, and its bytes answer `404` — while the removal itself stays in the
  timeline as `JOB_PHOTO_REMOVED`, and `includeRemovedEvidence=true` is the audit/history context that shows
  the removed record with its actor, instant and reason. The removal is **online-only** (no idempotency key,
  no decided conflict policy), and Android offers it in the viewer only to a session holding the capability,
  behind a confirmation that requires a reason. **Phase 6c (evidence metadata) is next.**
  `docs/tracker/029-photo-evidence-phases.md`
  (decisions: `docs/decisions/015-evidence-capabilities.md`,
  `docs/decisions/016-android-image-stack-and-viewer-zoom.md`,
  `docs/decisions/017-viewer-paging-save-and-share.md`).
- The photo viewer's chrome — the media-viewer surface, its top bar, the phase badge over the photo, the
  Notes section with **More**, the in-flight evidence action and the report drawn inside the viewer:
  `docs/tracker/031-android-photo-viewer-ui.md`.
- Job Activity's photo UX — the collapsible **Photos · n** gallery (folded by default, compact preview,
  whole heading a target) and each photo drawn inside its own timeline entry, opening the same viewer:
  `docs/tracker/032-android-job-activity-photo-ux.md`.
- Audio evidence — the model, the container and length vocabulary, the per-kind capabilities, the
  Activity kinds, playback and the offline rules (`docs/decisions/018-audio-evidence.md`), the contract
  (`docs/api/job-audio.md`), and the Android phases still to come:
  `docs/tracker/035-android-audio-evidence.md`.
- Development-environment hygiene — the Gradle/Kotlin build daemons Android verification leaves
  behind, and the `make android-stop` / `make tidy` targets that release them:
  `docs/tracker/030-development-environment-hygiene.md`.
- Verification scope — how much verification an ordinary task runs (the affected application's
  compile/build and the tests that cover the change) versus what it leaves to the end of the feature
  (full lint, full suites, full builds), and why:
  `docs/tracker/036-verification-scope-tiering.md`.
- The manager's Job status workflow — any permitted destination in one operation and one record, the
  completion invariant, and the confirmation before closing or reopening:
  `docs/tracker/034-manager-job-status-workflow.md`.
- The technician's field experience — **`ADR-019` accepted (Phase 1), the field read landed (Phase 2a),
  `GET /home/technician` with the Android technician home landed (Phases 2b and 4), the Visit field
  lifecycle landed (Phase 3) and the Android field action landed (Phase 5)**: `GET /jobs/:id` and
  `/jobs/:id/activity` accept `customers.view` **or** `VISIT_VIEW_ASSIGNED` through a new any-of
  authorization primitive, with a field caller reading only the Jobs their own current assignments
  reach; `PATCH /jobs/:id/visits/:visitId/status` applies `BR-074`'s transitions and `BR-075`'s one
  correction, records every applied change once in append-only history, writes the outcome `BR-077`
  requires with the completion, and applies the Visit → Job consequence `ADR-019` D4 decides — all
  guarded by the field capabilities `BR-009` names, or since **Phase 5c (2026-09-18)** by the office
  capability **`BR-093`** adds (an office member may complete a Visit from Job Details without being on
  its crew), with the scope following the capability that admitted the caller — their own current crew for
  a field caller, the organization's Visits for an office caller — a completion still requiring
  `VISIT_RECORD_OUTCOME` of every caller, and the projection reporting only the destinations that caller
  may execute — and replayed exactly once through a client-generated operation id; the technician's Home is now their own
  day — the Visit to do next, today in time order, a capped preview of what comes after and the overdue
  work on their own Visits, readable offline through the working set; and on the Job Details screen the
  Visit's **status chip is the control** that moves it, drawn from the destinations the API reports, with
  a completion sheet that records `BR-078`'s outcome type and the summary `BR-077` requires in the same
  request, the note written under `VISIT_ADD_NOTE`, and the transition and the note queued through the
  existing outbox when the API cannot be reached. **D6 was answered by product ownership on 2026-09-17**
  (its own screen answering 'what do I need to do next?', not the manager home filtered to one
  technician), **`ADR-019` D7 on 2026-09-18**: a Manager may complete a Visit from Job Details without
  being on its crew, recorded as the business rule `BR-093`; and (Phase 5b) the field action is offered
  only to a caller the API will actually accept. Next: Phase 6, the product owner's device QA:
  `docs/tracker/037-technician-field-experience.md`
  (decision: `docs/decisions/019-technician-field-experience.md`; contracts: `docs/api/job-actions.md`
  §7, `docs/api/technician-home.md`).
- Full ruleset: `docs/versioning.md` — read it before branching or committing.
