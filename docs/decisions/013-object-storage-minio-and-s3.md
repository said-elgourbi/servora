# ADR-013 — Local object storage (MinIO) and the S3-only application contract

- Status: Accepted (product-owner decision, 2026-09-14)
- Milestone: docs/tracker/023-object-storage-minio.md

References: `BR-001`, `BR-007`, `BR-015`, `BR-027`, `BR-041`, `BR-042`, `dev.md` §4, §5, §6, §11
and §14, `Project.md` §13, §14, §16 and §23, `qa.md` §15, `ADR-001` (foundation stack),
`docs/architecture/offline-first-architecture.md` §9.

## Context

Photos, audio and files are business evidence (`BR-015`, `BR-027`) and they must be stored and
served by the backend, which is the system of record (`BR-001`) and authorizes every protected
operation (`BR-007`). **No evidence feature exists yet**: there is no upload endpoint, no
attachment table and no outbox writer, and the retention rules `BR-027` leaves open are still
open. Evidence is uploaded from the Android field application and, later, from the Angular
management application.

Local development needs object storage now, and production will run the API on a VPS against a
**third-party S3-compatible provider**. The decision taken here must therefore work locally and in
production, and the later swap must be configuration rather than a rewrite.

The API foundation (`ADR-001`) is deliberately small — PostgreSQL and the API — and this ADR adds
the storage service that development needs without adding a single line of application code. That
separation is intentional: storage provisioning is stack/deployment setup, not product behaviour
(`BR-042`).

## Decisions

### D1 — MinIO is the local implementation, and it stays behind the S3 contract

`docker-compose.yml` gains a `minio` service (the server), a `minio-init` one-shot service (bucket
bootstrap) and the `minio-data` volume. MinIO appears in exactly those places: no application
dependency, no MinIO-specific vocabulary in the API or the clients, no MinIO-only behaviour. The
application's contract is **S3**, so the local service is a stand-in rather than an integration.

`minio-init` exists because a development stack must converge on a known state on every `make up`,
not because the application needs it (see D4).

### D2 — The images come from `quay.io` and are pinned to exact releases

Docker Hub no longer serves `minio/minio` (the registry answers `object not found`), so the
canonical source is:

- `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`
- `quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z-cpuv1`

Never `latest`: an upstream release must not be able to change the stack silently (`dev.md` §4),
matching the existing `postgres:17-alpine` style. A CPU without x86-64-v2 can use the `-cpuv1`
variant of the server image (`…RELEASE.2025-09-07T16-13-09Z-cpuv1`), published from the same
release.

### D3 — The Console port is pinned, and the Console is not part of the product

The server is started as `server /data --console-address ":9001"`. Without that flag the embedded
Console binds a **random** port, so the published `MINIO_CONSOLE_PORT` mapping would never reach
it. The Console is a developer convenience on the host: no client, no API route and no documented
workflow depends on it.

### D4 — The stack provisions the bucket; the application never does

`minio-init` creates `${S3_BUCKET}` when it does not exist and keeps it private
(`mc anonymous set none`, `dev.md` §11). It is idempotent (`--ignore-existing`), so a fresh clone
and an already-running stack converge on the same state.

A deployment provisions its bucket out of band (provider console, IaC, or an operator), so the API
**must not require bucket-creation rights** and must not assume it may create one. This keeps the
local convenience from becoming a production requirement.

### D5 — Local credentials are local sandbox credentials

`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` carry compose defaults, exactly like the existing
`POSTGRES_*` values, so `make up` works on a machine whose `.env` predates this service. They are
documented as local-development-only in `.env.example`, `.env` stays git-ignored (`dev.md` §5),
and no credential is committed. Nothing in the API or the Android client changes.

### D6 — The application contract is S3, and it is fixed now, before any code uses it

`.env.example` documents the contract the storage adapter will read:

| Variable              | Meaning                                                                  |
| --------------------- | ------------------------------------------------------------------------ |
| `S3_ENDPOINT`         | Endpoint the API talks to (`http://minio:9000` inside compose)            |
| `S3_REGION`           | Signing region (`us-east-1` is MinIO's default)                           |
| `S3_ACCESS_KEY`       | Credential (`MINIO_ROOT_USER` locally)                                    |
| `S3_SECRET_KEY`       | Credential (`MINIO_ROOT_PASSWORD` locally)                                |
| `S3_BUCKET`           | Bucket name; the value `minio-init` provisions and the application uses    |
| `S3_FORCE_PATH_STYLE` | `true` for MinIO; supported by the providers                              |
| `S3_PUBLIC_ENDPOINT`  | Optional client-facing endpoint, needed only once URLs are presigned (D7)  |

The internal/public endpoint split mirrors the existing `DATABASE_URL` convention: host tooling
reaches the service on the published port while the API container uses the service name, and the
same variable carries both depending on where the process runs.

Rules the adapter slice must follow, which this ADR fixes now so they cannot drift into the
implementation:

1. **S3 API only, through an S3-compatible client.** The MinIO SDK is not used and MinIO must not
   appear in the domain or application layer — the same provider-neutrality rule `BR-019` applies
   to SMS (Twilio behind `SmsProvider`) and `ADR-008` to email (Resend behind `EmailProvider`).
2. **Configuration is validated fail-fast at startup**, in the shape `loadEmailConfig` already
   established: a selected provider without usable configuration refuses to start.
3. **`S3_FORCE_PATH_STYLE` is required for MinIO**, because neither `bucket.minio:9000` nor
   `bucket.127.0.0.1:9000` resolves, and it remains a supported setting on the providers.
4. **The database stores an object key**, never a URL and never a signed URL. Read URLs are
   derived when they are needed, which is what makes the provider swap, a custom domain, or a CDN
   non-breaking.
5. **Object keys are generated by the API** and stay provider-agnostic: no host, no bucket, no
   provider vocabulary.
6. **Storage failures are mapped to a domain error type**, so provider-specific error shapes never
   reach the domain (the way `SmsDeliveryError` isolates Twilio).
7. **The SDK's checksum behaviour must be verified against the chosen provider.** Recent AWS SDK
   releases send default `x-amz-checksum-*` values and unsigned-trailer payloads that some
   S3-compatible services reject; where that happens the fix is to pin
   `requestChecksumCalculation` / `responseChecksumValidation` to `WHEN_REQUIRED`.

### D7 — Evidence travels through the API, on the API port

The client uploads and fetches evidence through the API: the API authorizes the request
(`BR-007`), streams the bytes to storage, and records the attachment. Presigned URLs are **not**
used for uploads, and are not used for reads in the first slice.

This is what makes "it must work later" true:

- The Android application keeps exactly one network path — the API base URL it already reaches
  (today through `adb reverse`) — locally and on the VPS. No storage endpoint, bucket name or
  signature host is configured in, or visible to, the client.
- Bytes are validated before they become evidence (`BR-015`), instead of trusting a client PUT.
- Authorization stays per request, instead of being delegated to whoever holds a URL until it
  expires.
- Moving from MinIO to an S3-compatible provider is a configuration change on the API only.

One constraint is recorded here because it decides any future presigning: **a SigV4 signature
covers the `Host` header**, so a presigned URL is valid only for the exact host it was generated
for. The API's internal endpoint (`http://minio:9000`) is not reachable by a device, so presigning
would need `S3_PUBLIC_ENDPOINT` — locally `http://127.0.0.1:9000` together with a second
`adb reverse tcp:9000 tcp:9000`, and in production the provider's public hostname. It would also be
a debug-build-only option against plain HTTP, because release builds permit HTTPS only. Presigned
reads remain a possible read-path optimization, not a decision taken here.

## Consequences

- `make up` now starts PostgreSQL, the API and MinIO, and runs the bucket bootstrap. The smoke test
  is `curl :3000/health` plus `make minio-ls`.
- The local stack has a fourth service, and `make down -v` deletes object-storage data together
  with the database volume.
- The API, the database, the Android client, the business rules and trackers 014–022 are untouched.
- Evidence feature work still has to be designed before it can be implemented: the attachment
  domain model, the object-key layout, retention (`BR-027`, `BR-033`) and thumbnails are all open,
  and this ADR deliberately decides none of them (`BR-042`).

## Update (2026-09-15) — the adapter landed, and the first evidence feature with it

`docs/tracker/027-android-job-photo-updates.md` implements the first evidence slice: Job photo upload
and read (`docs/api/job-photos.md`). Two things here are worth recording, because they are the parts of
D6 that became code — and the parts that did not.

**What this ADR decided is now implemented as written.** `api/src/storage` holds the port
(`object-storage.ts`), the adapter (`s3-object-storage.ts`) and the configuration
(`storage-config.ts`); the job photos table stores an **object key**, never a URL (D6.4); keys are
generated by the API as `evidence/job-photos/{organizationId}/{jobId}/{photoId}.{ext}` and name
nothing about the provider (D6.5); the provider's errors are mapped onto `ObjectStorageError`, so no
provider shape reaches the domain (D6.6); `STORAGE_PROVIDER` fails fast when `s3` is selected without
usable configuration (D6.2); and the SDK's checksum calculation and response validation are pinned to
`WHEN_REQUIRED` together with `S3_FORCE_PATH_STYLE`, which is what D6.7 and D6.3 predicted would be
needed against an S3-compatible service.

**The dependency D6.1 implied was added**: `@aws-sdk/client-s3`, as an S3-compatible client rather
than an Amazon-only dependency, pinned like every other dependency (`dev.md` §4). It is the only new
dependency of that slice.

**Evidence still travels through the API on the API port** (D7), in both directions: the upload is
`POST /jobs/:id/photos` and the read is `GET /jobs/:id/photos/:photoId/content`. No presigned URL is
issued, so the `Host`-binding constraint recorded in D7 has not been exercised, and a device still
configures one base URL. Whether reads ever move to presigning remains open.

**Still open, unchanged by the slice**: retention/lifecycle/versioning, thumbnails and other derived
objects, the production provider and its provisioning, and the evidence domain model beyond Job
photos (the photo model that landed is a Job-scoped, append-only row — `docs/api/job-photos.md` §7
lists what remains undefined).

## Open questions not decided here

1. The evidence domain model, and the object-key layout for evidence.
2. Retention, lifecycle and versioning (`BR-027`, `BR-033`).
3. Thumbnails and other derived objects.
4. Whether reads ever move to presigned URLs, and whether uploads do.
5. The production provider, its bucket, and how it is provisioned.


