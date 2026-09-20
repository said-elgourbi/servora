# Servora API — `/jobs/:id/photos` Contract

**Status: IMPLEMENTED** for `POST /jobs/:id/photos` and `GET /jobs/:id/photos/:photoId/content`.

**Existing patterns reused.** The photos live in the existing S3-compatible object store (`ADR-013`),
behind the provider-neutral `ObjectStorage` port in `api/src/storage`; the bytes travel through the
API on the API port, exactly as `ADR-013` D7 decided. No MinIO concept, endpoint or credential appears
in the contract.

References: `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-012`, `BR-014`, `BR-015`, `BR-027`, `BR-028`,
`BR-031`, `BR-041`, `BR-042`, `BR-047`, `BR-051`, `BR-080`, `ADR-013`, `ADR-015`, `dev.md` §7,
`Project.md` §15, `docs/architecture/offline-first-architecture.md` §5, §6, §9.

## 1. What a Job photo is

A photo is **evidence a technician attached to a Job** (`BR-015`, `BR-027`), not a Job field and not a
Visit status. It is recorded on the **Job**, so it can be captured whether or not the Job has a Visit
(`BR-051`), and it is **appended only**: nothing in this contract edits a photo, and there is no
edit-evidence operation in Servora at all (`BR-067`, `BR-088`). A photo is draft material until this API
records it and immutable historical evidence from that moment. From 2026-09-16 an explicit, audited
**remove** operation exists (`BR-089`, tracker 029 Phase 6b): it appends a removal record beside the
photo, which takes the evidence out of ordinary use while its record, its bytes and its history are
preserved — §7.

What the API stores per photo:

| Field                  | Meaning                                                                                 |
| ---------------------- | --------------------------------------------------------------------------------------- |
| `id`                   | The photo's identifier, which is the **client operation id** the device generated.       |
| `jobId`                | The Job the evidence belongs to.                                                        |
| `phase`                | `BEFORE_WORK` / `DURING_WORK` / `AFTER_WORK` — the stable code the technician chose.     |
| `note`                 | The technician's optional note.                                                          |
| `objectKey`            | The object's key in the store. An **object key, never a URL** (`ADR-013` D6.4).          |
| `contentType`          | Decided from the bytes, not from what the client declared (`BR-015`).                    |
| `byteSize`             | The stored size in bytes.                                                                |
| `capturedAt`           | The device instant the photo was taken: provenance only, never business time (`BR-031`).  |
| `recordedAt`           | The backend's own instant, and the one Job Activity orders by (`BR-080`).                 |
| `uploaderMembershipId` | The authenticated member who uploaded it — taken from the session, never from input.      |

## 2. Permissions

| Route                                        | Permission             |
| -------------------------------------------- | ---------------------- |
| `POST /jobs/:id/photos`                      | `evidence.photo.add`   |
| `GET /jobs/:id/photos/:photoId/content`      | `evidence.view`        |
| `POST /jobs/:id/photos/:photoId/removal`     | `evidence.photo.remove` |

Evidence is authorized by the capabilities the catalogue defines for evidence (`BR-006`, `ADR-015`),
not by a Job or Customer capability. A client draws its actions from the same codes (`BR-011`), and the
API enforces them whatever the client shows (`BR-007`).

**Why evidence capabilities.** A photo is recorded by the technician who is on site (`BR-015`), and the
default Technician role holds neither `JOB_UPDATE` nor `customers.view` (`BR-009`): the write route
required `JOB_UPDATE` and the read route `customers.view` as an interim authorization, which made Job
photo evidence unusable by the audience it exists for. This is the decision recorded in
`docs/decisions/015-evidence-capabilities.md` and in tracker 029 (D1, D1b); the interim authorization no
longer applies.

**Per kind.** The catalogue is `evidence.view`, `evidence.photo.add` and `evidence.photo.remove`, so one
kind of evidence can be withdrawn from a member without withdrawing the others. The reserved
`evidence.audio.add` is **not** created yet: audio is agreed as a future kind but no rule accepts it
(`BR-027`, `BR-042`, tracker 029 D8). Adding evidence does not grant reading it back, reading it does not
grant adding it, and **neither grants removing it**: taking accepted evidence out of ordinary use is a
separate, Manager-level capability (`BR-089`).

**Default roles.** `evidence.view` and `evidence.photo.add` are held by the default **Manager** and
**Technician** roles (`0010_evidence_permissions.sql`, and the development seed for the local QA
accounts). `evidence.photo.remove` is granted to the default **Manager** role alone
(`0011_job_photo_removals.sql`): `BR-009`'s field role records and reads evidence and does not remove it.
A custom role or a member's direct permissions may grant any of them the same way as any other
capability (`BR-004`, `BR-006`).

**Not decided here.** The `jobs.*` capability set remains the Jobs feature's own open question
(`docs/api/job-actions.md` §2, `docs/api/job-details.md` §2): this contract decides the evidence
capability only.

## 3. `POST /jobs/:id/photos`

`multipart/form-data`, with one file part and four text parts.

| Part                | Required | Notes                                                                 |
| ------------------- | -------- | --------------------------------------------------------------------- |
| `file`              | yes      | The photo. JPEG, PNG or WebP, decided from the bytes. Max 15 MiB.      |
| `clientOperationId` | yes      | UUID. The idempotency key; **also the photo's id** (§3.2).             |
| `phase`             | yes      | `BEFORE_WORK` / `DURING_WORK` / `AFTER_WORK`.                          |
| `note`              | no       | At most 2000 characters. Absent or blank means no note.                |
| `capturedAt`        | no       | ISO-8601 UTC instant the device captured the photo.                    |

### 3.1 Response — `201 Created`

The refreshed Job Activity projection (`docs/api/job-activity.md` §3.1), because a photo is an Activity
event and there is no separate photo read. The new entry is:

```json
{
  "id": "9d2c…",
  "kind": "JOB_PHOTO_ADDED",
  "recordedAt": "2026-09-15T13:05:00.000Z",
  "actorName": "John Smith",
  "visitSequence": null,
  "fromStatus": null,
  "toStatus": null,
  "technicianName": null,
  "roleCode": null,
  "previousRoleCode": null,
  "outcomeCode": null,
  "outcomeSummary": null,
  "body": "Panel before the repair",
  "photoId": "9d2c…",
  "photoPhase": "BEFORE_WORK",
  "photoRemovalReason": null
}
```

`photoId` is what `GET /jobs/:id/photos/:photoId/content` is asked for, and `body` carries the note.
`photoRemovalReason` is `null` on every event but `JOB_PHOTO_REMOVED`, which carries no `body`
(`docs/api/job-activity.md` §3.2). The entry is Job-level (`visitSequence: null`), matching where the
photo is recorded.

### 3.2 Idempotency (`BR-031`, offline standard §5)

The device generates `clientOperationId` **once**, before its first attempt, and sends the same value on
every retry. The API uses it as the photo's primary key and as the last segment of the object key:

```text
evidence/job-photos/{organizationId}/{jobId}/{clientOperationId}.{jpg|png|webp}
```

Two consequences, both intended:

- A replay **finds the row that already exists** and answers with the same activity instead of storing
  the evidence a second time.
- A retry after a partial failure writes the **same key**, so a failed attempt cannot leave an orphan
  object behind a photo the API never recorded.

A `clientOperationId` already used for a **different** Job is refused with `409`
(`PHOTO_OPERATION_REUSED`): one operation id names one operation, and that operation addressed one Job.
The unique index on `(organizationId, client_operation_id)` is the database-level guarantee, and a
concurrent duplicate that slips past the read is answered from the row that won the race.

### 3.3 Bytes are validated before they become evidence (`ADR-013` D7)

- The image type is **sniffed from the bytes** (JPEG `FF D8 FF`, PNG signature, `RIFF….WEBP`) and stored
  as the object's content type. A file that is not one of the accepted types is refused.
- A declared `Content-Type` that disagrees with the bytes is refused, so a stored object never claims a
  type it does not have.
- An empty part, or a part larger than 15 MiB, is refused.

The route requires `evidence.photo.add` (§2), which the default Technician role holds, so the technician
`BR-015` names records evidence without holding a Manager capability. Until tracker 029 Phase 1 the route
required `JOB_UPDATE`, which a default-Technician session does not hold, so the upload answered `403` to
the audience the feature exists for — that open question was recorded in
`docs/tracker/027-android-job-photo-updates.md` and is closed by the evidence capability
(`docs/decisions/015-evidence-capabilities.md`).

Tenant scope comes from the authenticated session only: a photo is always resolved by
`(organizationId, jobId, photoId)`, and a Job another organization owns is `404`, never `403`

## 4. The stored bytes and the removal

### 4.1 `GET /jobs/:id/photos/:photoId/content`

Streams the stored bytes with the stored content type.

- Authorization is per request, exactly like every other route: the object's **key is derived from the
  record** and is never supplied by the client (`ADR-013` D6.5).
- No presigned URL is issued, so a device needs no storage endpoint, bucket or signature host
  (`ADR-013` D7). Moving from MinIO to an S3-compatible provider stays an API configuration change.
- **Decided direction, not implemented:** reads move to **short-lived presigned GET URLs issued by this
  API after authorization**, with the bucket kept private and uploads staying on the API port
  (`ADR-013`, update of 2026-09-16; tracker 029 Phase 8). The phase must first state how a device reaches
  such a URL, because `ADR-013` D7 records that a signature is bound to its `Host` and that a plain-HTTP
  URL is debug-build-only. Until it lands, the behaviour above is what the API does.
- If the record exists but the store no longer holds the object, the route answers `404` rather than an
  empty `200`: the API never reports success for bytes it does not have.
- **A removed photo is not readable here** (`BR-089`): this is an ordinary read of evidence, and removed
  evidence is out of ordinary use, so the route answers `404 JOB_PHOTO_NOT_FOUND` exactly as it does for
  a photo that does not exist. The response never discloses that there is evidence behind the id.

### 4.2 `POST /jobs/:id/photos/:photoId/removal`

Takes accepted evidence **out of ordinary use** (`BR-088`, `BR-089`). It is not a delete and it is not
an edit: nothing in this contract overwrites a photo, replaces its bytes, changes its phase or rewrites
its note.

Body:

| Field    | Required | Notes                                                                 |
| -------- | -------- | --------------------------------------------------------------------- |
| `reason` | yes      | Free text, at most 2000 characters. A structured catalogue is not defined (`BR-042`). |

- **The record is appended, never written onto.** The API inserts a `job_photo_removals` row naming the
  photo, the actor (from the session, never from input), the instant and the reason; `job_photos` stays
  append-only and every field it holds is untouched (`BR-067`, `BR-089`).
- **One removal per photo.** No restore is defined, so a second removal is refused with
  `409 JOB_PHOTO_ALREADY_REMOVED` rather than recorded as a state change that did not happen.
- **Ordinary reads exclude the evidence**: it leaves the Job Activity's photos, so it leaves the gallery
  a client draws from it, and §4.1 refuses its bytes. The removal itself is **not** hidden: Job Activity
  states that the evidence was removed, by whom, when and why (`JOB_PHOTO_REMOVED`,
  `docs/api/job-activity.md` §3.2, §3.5).
- **Nothing purges the bytes.** The stored object is left exactly where it is; physically removing it is
  a retention concern (`BR-090`) and is not this operation.
- **Online-only.** The route takes no client-generated idempotency key and no conflict policy is decided
  for it, so it is never queued on a device (`offline-first-architecture.md` §5, §8, §13.2). The Android
  client reports an unreachable API rather than queueing the decision.
- Authorization is `evidence.photo.remove` (§2), a Manager-level capability the default Technician role
  does not hold (`BR-089`).

Response — `201 Created`: the refreshed Job Activity projection, like §3.1. The removal appears as:

```json
{
  "id": "b41f…",
  "kind": "JOB_PHOTO_REMOVED",
  "recordedAt": "2026-09-16T18:22:00.000Z",
  "actorName": "Dana Manager",
  "visitSequence": null,
  "fromStatus": null,
  "toStatus": null,
  "technicianName": null,
  "roleCode": null,
  "previousRoleCode": null,
  "outcomeCode": null,
  "outcomeSummary": null,
  "body": null,
  "photoId": "9d2c…",
  "photoPhase": null,
  "photoRemovalReason": "Photographed the wrong property"
}
```

## 5. Errors

| Status | Code                       | When                                                                         |
| ------ | -------------------------- | ---------------------------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`        | A missing or malformed part: no file, unknown phase, bad id, oversized note, or a removal with no reason. |
| `401`  | `UNAUTHENTICATED`          | No usable session.                                                            |
| `403`  | `FORBIDDEN`                | The caller does not hold the capability the route requires (§2).              |
| `404`  | `JOB_NOT_FOUND`            | The Job is not in the caller's organization (or its Customer was deleted).    |
| `404`  | `JOB_PHOTO_NOT_FOUND`      | The photo is not in that organization and Job, its bytes are unavailable, or it has been removed (§4.1). |
| `409`  | `PHOTO_OPERATION_REUSED`   | The idempotency key was already used for another Job.                         |
| `409`  | `JOB_PHOTO_ALREADY_REMOVED` | The photo has already been taken out of ordinary use (§4.2).                 |
| `413`  | —                          | The upload exceeded the multipart limit.                                      |
| `503`  | `STORAGE_UNAVAILABLE`      | The object store did not store the photo. **Nothing was recorded.**           |

`503` matters for the client: an unreachable store is a failure, never a success with a lost photo, so a
queued upload retries rather than being reported as applied (`BR-014`, `BR-015`).

## 6. Object storage configuration

The adapter is bound in `api/src/storage/storage.module.ts` and selected by configuration (`ADR-013`
D6.2):

| Variable                                        | Meaning                                                             |
| ----------------------------------------------- | ------------------------------------------------------------------- |
| `STORAGE_PROVIDER`                              | `s3` (any S3-compatible service, MinIO locally) or `noop` (default). |
| `S3_ENDPOINT`                                   | `http://minio:9000` inside compose; a provider's endpoint elsewhere. |
| `S3_REGION` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | Credentials; never logged, never committed.                          |
| `S3_BUCKET`                                     | The provisioned bucket. The API never creates one (`ADR-013` D4).    |
| `S3_FORCE_PATH_STYLE`                           | Defaults to `true`, which MinIO requires (`ADR-013` D6.3).           |

`STORAGE_PROVIDER=noop` is the default so a machine without storage credentials boots and runs its tests
— and an upload is then **refused** with `503` rather than reported as stored. The API never claims
evidence it does not hold (`BR-014`, `BR-015`, `BR-042`).

The S3 client is `@aws-sdk/client-s3`, the S3-compatible client `ADR-013` D6.1 named. Two of its
behaviours are pinned in the adapter because `ADR-013` D6.7 recorded them as provider risks: checksum
calculation and response validation are set to `WHEN_REQUIRED`, and path-style addressing is configured
from `S3_FORCE_PATH_STYLE`.

## 7. Open questions

Recorded rather than guessed (`BR-042`).

1. **The `jobs.*` capability set** (`BR-006`): the Job read and its activity are still guarded by
   `customers.view`, and the Job and Visit actions by `JOB_UPDATE` — the Jobs feature has not defined
   its own set. **Closed for evidence only**: the Job photo routes use the evidence capabilities in §2
   (`ADR-015`, tracker 029 Phase 1).
2. **Photo retention, visibility and audit** (`BR-027`, `BR-033`, `BR-015`): **decided 2026-09-16** —
   evidence is immutable once accepted (`BR-088`), may be removed only by an audited, Manager-level
   `evidence.photo.remove` (`BR-089`, §4.2 — **implemented 2026-09-16**, tracker 029 Phase 6b),
   retention is indefinite for the life of the tenant, and normal product flows never hard-delete a Job
   (`BR-090`). A **structured removal-reason catalogue** is still not defined: the reason is free text
   (`BR-042`).
3. **Thumbnails and derived objects** (`ADR-013`): **decided 2026-09-16** — none in v1. The stored object
   is the normalized original and the client renders from it; a derived pipeline is revisited only when
   performance proves it necessary.
4. **Audio and other evidence** (`BR-027`): **decided 2026-09-16** — audio notes are evidence of their own
   kind, following this shape with the same storage, outbox, upload, authorization and immutability rules
   (`BR-091`). **Implemented 2026-09-16** for the API half (`docs/api/job-audio.md`,
   `docs/tracker/035-android-audio-evidence.md`, `ADR-018`): `evidence.audio.add` and the audio kind's own
   `evidence.audio.remove` are created, the three routes exist, and generic file attachments remain out of
   scope in v1.
5. **Whether evidence may be attached to a Visit rather than the Job.** This slice records photos on the
   Job, because that is where Job Activity projects them (`BR-080`) and because a Job need not have a
   Visit (`BR-051`). A Visit-scoped photo stream would be a product decision.
6. **The metadata written into evidence.** **Decided 2026-09-16** (`BR-091`, tracker 029 Phase 6c):
   GPS/location EXIF and metadata Servora does not need are stripped from uploaded evidence by default,
   and only what the application needs is kept — normalized orientation and dimensions, plus Servora's own
   capture/upload timestamps. **Not implemented**: this API still stores the bytes it accepts as they are.

(`BR-001`).
