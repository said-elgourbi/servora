# Servora API — `/jobs/:id/audio-notes` Contract

**Status: IMPLEMENTED** for `POST /jobs/:id/audio-notes`,
`GET /jobs/:id/audio-notes/:audioNoteId/content` and `POST /jobs/:id/audio-notes/:audioNoteId/removal`.

**Existing patterns reused.** The recordings live in the same S3-compatible object store (`ADR-013`),
behind the provider-neutral `ObjectStorage` port in `api/src/storage`; the bytes travel through the API on
the API port, exactly as `ADR-013` D7 decided and as `docs/api/job-photos.md` already does. No MinIO
concept, endpoint or credential appears in the contract.

References: `BR-001`, `BR-005`, `BR-006`, `BR-007`, `BR-009`, `BR-014`, `BR-027`, `BR-028`, `BR-031`,
`BR-041`, `BR-042`, `BR-051`, `BR-067`, `BR-080`, `BR-088`, `BR-089`, `BR-090`, `BR-091`, `ADR-013`,
`ADR-015`, `ADR-018`, `dev.md` §7, `Project.md` §15,
`docs/architecture/offline-first-architecture.md` §5, §6, §9, §13.

## 1. What a Job audio note is

An audio note is **evidence a technician recorded on a Job** (`BR-091`), of its own kind: it is not a Job
field, not a Visit status and not a photo. It is recorded on the **Job**, so it can be recorded whether or
not the Job has a Visit (`BR-051`), and it is **appended only** — nothing in this contract edits a
recording, and there is no edit-evidence operation in Servora at all (`BR-067`, `BR-088`). A recording is
draft material until this API records it and immutable historical evidence from that moment. The explicit,
audited **removal** operation (`BR-089`) appends a removal record beside it, which takes the evidence out
of ordinary use while its record, its bytes and its history are preserved — §4.2.

What the API stores per audio note:

| Field                  | Meaning                                                                                    |
| ---------------------- | ------------------------------------------------------------------------------------------ |
| `id`                   | The audio note's identifier, which is the **client operation id** the device generated.      |
| `jobId`                | The Job the evidence belongs to.                                                           |
| `phase`                | `BEFORE_WORK` / `DURING_WORK` / `AFTER_WORK` — the same code a photo carries (`BR-091`).      |
| `note`                 | The technician's optional note.                                                            |
| `objectKey`            | The object's key in the store. An **object key, never a URL** (`ADR-013` D6.4).              |
| `contentType`          | Decided from the bytes, not from what the client declared (`BR-015`). `audio/mp4` (§3.3).    |
| `byteSize`             | The stored size in bytes.                                                                  |
| `durationSeconds`      | The recording's length, **read from its own container** (`ADR-018` A3) — never declared.      |
| `capturedAt`           | The device instant the recording was made: provenance only, never business time (`BR-031`).  |
| `recordedAt`           | The backend's own instant, and the one Job Activity orders by (`BR-080`).                    |
| `uploaderMembershipId` | The authenticated member who recorded it — taken from the session, never from input.         |

## 2. Permissions

| Route                                                       | Permission               |
| ----------------------------------------------------------- | ------------------------ |
| `POST /jobs/:id/audio-notes`                                | `evidence.audio.add`     |
| `GET /jobs/:id/audio-notes/:audioNoteId/content`            | `evidence.view`          |
| `POST /jobs/:id/audio-notes/:audioNoteId/removal`           | `evidence.audio.remove`  |

Evidence is authorized by the capabilities the catalogue defines for evidence (`BR-006`, `ADR-015`,
`ADR-018` A7), not by a Job or Customer capability. A client draws its actions from the same codes
(`BR-011`), and the API enforces them whatever the client shows (`BR-007`).

**Per kind, for adding and removing.** `evidence.audio.add` is the code `ADR-015` D2 reserved; it is
created by this slice, because the rules that accept a recording now exist (`BR-091`). Its removal is
`evidence.audio.remove`, separate from `evidence.photo.remove`, so a company may let a member remove one
kind of evidence and not the other (`ADR-018` A7).

**Reading is one capability, not one per kind.** `GET …/content` requires `evidence.view`, the same
capability a photo's bytes need (`ADR-018` A7): reading evidence is one act whichever kind it is.

**Default roles.** `evidence.audio.add` is held by the default **Manager** and **Technician** roles,
exactly as `evidence.photo.add` is — the author of an audio note is the technician who is on site
(`BR-009`, `BR-091`). `evidence.audio.remove` is granted to the default **Manager** role alone
(`0012_job_audio_notes.sql`). A custom role or a member's direct permissions may grant any of them the
same way as any other capability (`BR-004`, `BR-006`).

**The audit/history context.** `?includeRemovedEvidence=true` on the Activity read is authorized by
**any** evidence removal capability (`evidence.photo.remove` or `evidence.audio.remove`), because it asks
one question about one read rather than one question per kind (`ADR-018` A7).

## 3. `POST /jobs/:id/audio-notes`

`multipart/form-data`, with one file part and four text parts.

| Part                | Required | Notes                                                                    |
| ------------------- | -------- | ------------------------------------------------------------------------ |
| `file`              | yes      | The recording. `audio/mp4`, decided from the bytes. Max 10 MiB, 1–300 s.  |
| `clientOperationId` | yes      | UUID. The idempotency key; **also the audio note's id** (§3.2).            |
| `phase`             | yes      | `BEFORE_WORK` / `DURING_WORK` / `AFTER_WORK`.                             |
| `note`              | no       | At most 2000 characters. Absent or blank means no note.                   |
| `capturedAt`        | no       | ISO-8601 UTC instant the device made the recording.                       |

There is **no duration part**, deliberately: the length is read from the recording itself (§3.3).

### 3.1 Response — `201 Created`

The refreshed Job Activity projection (`docs/api/job-activity.md` §3.1), because an audio note is an
Activity event and there is no separate audio read. The new entry is:

```json
{
  "id": "b41f…",
  "kind": "JOB_AUDIO_ADDED",
  "recordedAt": "2026-09-16T09:13:00.000Z",
  "actorName": "John Smith",
  "visitSequence": null,
  "fromStatus": null,
  "toStatus": null,
  "technicianName": null,
  "roleCode": null,
  "previousRoleCode": null,
  "outcomeCode": null,
  "outcomeSummary": null,
  "body": "Customer described the noise",
  "photoId": null,
  "photoPhase": null,
  "photoRemovalReason": null,
  "audioNoteId": "b41f…",
  "audioPhase": "BEFORE_WORK",
  "audioDurationSeconds": 18,
  "audioRemovalReason": null
}
```

`audioNoteId` is what `GET /jobs/:id/audio-notes/:audioNoteId/content` is asked for, `body` carries the
note, and `audioDurationSeconds` is the length the API read from the container. `audioRemovalReason` is
`null` on every event but `JOB_AUDIO_REMOVED`, which carries no `body` (`docs/api/job-activity.md` §3.2).
The entry is Job-level (`visitSequence: null`), matching where the recording is held.

### 3.2 Idempotency (`BR-031`, offline standard §5)

The device generates `clientOperationId` **once**, before its first attempt, and sends the same value on
every retry. The API uses it as the audio note's primary key and as the last segment of the object key:

```text
evidence/job-audio-notes/{organizationId}/{jobId}/{clientOperationId}.m4a
```

Two consequences, both intended:

- A replay **finds the row that already exists** and answers with the same activity instead of storing the
  evidence a second time.
- A retry after a partial failure writes the **same key**, so a failed attempt cannot leave an orphan
  object behind a recording the API never recorded.

A `clientOperationId` already used for a **different** Job is refused with `409`
(`AUDIO_NOTE_OPERATION_REUSED`). The unique index on `(organizationId, client_operation_id)` is the
database-level guarantee, and a concurrent duplicate that slips past the read is answered from the row
that won the race.

Because a recording is append-only and there is no update path (`BR-088`), a replay has nothing to
conflict with: the offline standard's requirement that a queued mutation's conflict policy be **decided**
(§5, §13.2) is satisfied by there being no possible conflicting write, which is the same policy the photo
upload has (`ADR-018` A10).

### 3.3 Bytes are validated before they become evidence (`ADR-018` A2/A3)

- The container is **sniffed from the bytes**: the file must start with an accepted `ftyp` brand
  (`M4A `, `M4B `, `isom`, `iso2`, `iso5`, `mp41`, `mp42`), must carry a `moov` with a readable length, and
  must declare an audio track and **no** video track. A file that is not an audio-only MP4 is refused.
- A declared `Content-Type` that disagrees with the bytes is refused, so a stored object never claims a
  type it does not have.
- The **length is read from the container** (`moov/mvhd`: timescale and duration) and stored. A recording
  whose length cannot be read is refused rather than accepted with no length to bound, and the limits are
  applied to that derived length — a duration a client asserts is not a limit (`BR-015`).
- An empty part, a part larger than 10 MiB, a recording longer than 300 s or shorter than 1 s is refused,
  with nothing stored.

Tenant scope comes from the authenticated session only: an audio note is always resolved by
`(organizationId, jobId, audioNoteId)`, and a Job another organization owns is `404`, never `403`.

## 4. The stored bytes and the removal

### 4.1 `GET /jobs/:id/audio-notes/:audioNoteId/content`

Streams the stored bytes with the stored content type (`audio/mp4`), for playback.

- Authorization is per request, exactly like every other route: the object's **key is derived from the
  record** and is never supplied by the client (`ADR-013` D6.5).
- No presigned URL is issued, so a device needs no storage endpoint, bucket or signature host
  (`ADR-013` D7). Moving from MinIO to an S3-compatible provider stays an API configuration change. The
  decided direction — short-lived presigned GET URLs — is tracker 029 Phase 8's work and applies to this
  route the same way it applies to a photo's.
- If the record exists but the store no longer holds the object, the route answers `404` rather than an
  empty `200`: the API never reports success for bytes it does not have.
- **A removed recording is not readable here** (`BR-089`): this is an ordinary read of evidence, so the
  route answers `404 JOB_AUDIO_NOTE_NOT_FOUND` exactly as it does for a recording that does not exist. The
  response never discloses that there is evidence behind the id.

### 4.2 `POST /jobs/:id/audio-notes/:audioNoteId/removal`

Takes accepted evidence **out of ordinary use** (`BR-088`, `BR-089`). It is not a delete and it is not an
edit: nothing in this contract overwrites a recording, replaces its bytes, changes its phase or rewrites
its note.

Body:

| Field    | Required | Notes                                                                  |
| -------- | -------- | ---------------------------------------------------------------------- |
| `reason` | yes      | Free text, at most 2000 characters. A structured catalogue is not defined (`BR-042`). |

- **The record is appended, never written onto.** The API inserts a `job_audio_note_removals` row naming
  the recording, the actor (from the session, never from input), the instant and the reason;
  `job_audio_notes` stays append-only and every field it holds is untouched (`BR-067`, `BR-089`).
- **One removal per recording.** No restore is defined, so a second removal is refused with
  `409 JOB_AUDIO_NOTE_ALREADY_REMOVED` rather than recorded as a state change that did not happen.
- **Ordinary reads exclude it**: it leaves the Job Activity's audio entries, and §4.1 refuses its bytes.
  The removal itself is **not** hidden: it appears as `JOB_AUDIO_REMOVED` carrying the actor, the instant
  and the reason, and the audit/history context (`?includeRemovedEvidence=true`) shows the removed
  recording's own entry beside it.
- **Nothing purges the bytes.** The stored object is left exactly where it is; physically removing it is a
  retention concern (`BR-090`) and is not this operation.
- It is **online-only**: it takes no client-generated idempotency key and has no conflict policy, so it is
  never queued on a device (`offline-first-architecture.md` §5, §8, §13.2). The Android client reports an
  unreachable API rather than queueing the decision.
- Authorization is `evidence.audio.remove` (§2), a Manager-level capability the default Technician role
  does not hold (`BR-089`, `ADR-018` A7).

Response — `201 Created`, the refreshed Job Activity projection, with the new entry:

```json
{
  "id": "c93a…",
  "kind": "JOB_AUDIO_REMOVED",
  "recordedAt": "2026-09-16T18:22:00.000Z",
  "actorName": "Dana Manager",
  "visitSequence": null,
  "body": null,
  "audioNoteId": "b41f…",
  "audioRemovalReason": "Recorded the wrong job",
  "audioPhase": null,
  "audioDurationSeconds": null
}
```

## 5. Errors

| Status | Code                             | When                                                                          |
| ------ | -------------------------------- | ----------------------------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`              | A missing/blank `clientOperationId`, an unknown `phase`, a missing or oversized `file`, a file that is not an audio-only MP4, a recording whose length cannot be read or is outside 1–300 s, a missing removal `reason`. |
| `401`  | `UNAUTHENTICATED`                | No usable session.                                                             |
| `403`  | `FORBIDDEN`                      | The caller does not hold the route's capability (§2).                          |
| `404`  | `JOB_NOT_FOUND`                  | The Job does not exist in the caller's organization, or its Customer has been deleted (`BR-023`). |
| `404`  | `JOB_AUDIO_NOTE_NOT_FOUND`       | The recording does not exist in the caller's organization and Job, it has been removed, or its bytes are unavailable. |
| `409`  | `AUDIO_NOTE_OPERATION_REUSED`    | The idempotency key was already used for a different Job.                      |
| `409`  | `JOB_AUDIO_NOTE_ALREADY_REMOVED` | The recording has already been removed.                                        |
| `503`  | `STORAGE_UNAVAILABLE`            | The object store did not store the recording. The API never reports a success it cannot back. |

## 6. Object storage configuration

Identical to `docs/api/job-photos.md` §6: the store is selected by `STORAGE_PROVIDER` (`s3` for any
S3-compatible service, MinIO locally; `noop` by default, which makes an upload a `503`), the bucket is
provisioned outside the API (`ADR-013` D4), and no provider's vocabulary appears above the port. An audio
note's key lives under the same `evidence/` prefix as a photo's, one directory per kind.

## 7. Open questions

Recorded rather than guessed (`BR-042`).

1. **Unifying photos and audio into one kind-based evidence model** — **decided against for this slice**
   (`ADR-018` A1): the two tables stay separate, and the triggers for revisiting it are recorded there.
2. **MP4 container metadata** (`udta`/`©xyz`): `BR-091` requires location and unneeded metadata to be
   stripped from uploaded evidence by default, and **where** the strip runs is tracker 029 Phase 6c's
   decision for evidence as a whole (`ADR-018` open question 2). This route stores the container it
   validated.
3. **Additional containers, a longer recording, and transcription** — none is in scope
   (`ADR-018` A2/A3, open question 3).
4. **Choosing an existing recording from the device** — out of scope for v1 (`ADR-018` A8): the kind
   offers *record* only, which is what the design specifies.
5. **Audio in the Angular application** — tracker 029 Phase 7, once the Angular application exists.
