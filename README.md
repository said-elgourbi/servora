# Servora

Field-service management platform.

Servora is a monorepo built feature by feature as controlled vertical slices.

- **API** — NestJS (TypeScript ESM) + PostgreSQL (Drizzle ORM). The backend is the authoritative system of record (BR-001).
- **Android** — Kotlin / Jetpack Compose native field application (offline-first). Implemented for
  authentication, appearance controls, the Manager Home, the Manager Job Details screen (read and
  management actions), customers and the customer Property surface.
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
> permission gate, and Phase 4, the full-size viewer, are implemented**, with the capability decision in
> `docs/decisions/015-evidence-capabilities.md`. **Phase 0's remaining questions were decided on
> 2026-09-15**: the image stack is Coil 3 behind the existing `JobPhotoImages` port (D4b,
> `docs/decisions/016-android-image-stack-and-viewer-zoom.md`), the viewer gains pinch-zoom and pan (D9,
> Telephoto), evidence is Job-level (D2), a re-encoded photo is stored upright (D7b), a refused photo
> becomes discardable (D6c), and the device's media library is not read (D10). **Phases 4b, 4c and 4d are
> next; Phase 5 stays blocked because D5 was deferred.**

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
```

`make help` lists every target.

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
  action, and the client's evidence gate) and 4 (the full-size viewer a photo opens into) have
  landed; a reported portrait-orientation display defect was fixed on 2026-09-15 (a decode now applies
  the photo's own EXIF orientation). Phase 0's remaining questions were decided on 2026-09-15: evidence is
  Job-level (D2), the image stack is Coil 3 behind the existing port (D4b) with pinch-zoom and pan in the
  viewer (D9), a re-encoded photo is stored upright (D7b), a refused photo becomes discardable (D6c), the
  device's media library is not read (D10), and D5 was deferred. Phases 4b, 4c and 4d are next; Phase 5
  (offline evidence) stays blocked on the deferred D5.**
  `docs/tracker/029-photo-evidence-phases.md`
  (decisions: `docs/decisions/015-evidence-capabilities.md`,
  `docs/decisions/016-android-image-stack-and-viewer-zoom.md`).
- Full ruleset: `docs/versioning.md` — read it before branching or committing.
