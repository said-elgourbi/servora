# Servora API — `/jobs/:id/activity` Contract

**Status: IMPLEMENTED** for `GET /jobs/:id/activity`.

This read is a **projection**: it derives one Job's chronological activity from the append-only
histories the domain already keeps and stores nothing (`BR-001`, `BR-067`, `BR-080`). It follows
`BR-080` (Job Activity is a unified, derived read model ordered newest-first), `BR-041` (one shared
event vocabulary), `BR-028` (stable codes, localized labels) and `docs/domain/job-visit-domain-model.md`
§6 (the Visit sequence is a presentation label, never an identifier).

References: `BR-001`, `BR-006`, `BR-007`, `BR-020`, `BR-041`, `BR-042`, `BR-058`, `BR-067`, `BR-068`,
`BR-073`, `BR-074`, `BR-077`, `BR-078`, `BR-080`, `BR-088`, `BR-089`,
`docs/tracker/022-android-job-activity-timeline.md`, `docs/tracker/029-photo-evidence-phases.md`.

## 1. Conventions

JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15). The access token
is presented as `Authorization: Bearer <accessToken>`. Route paths carry no version prefix
(`docs/versioning.md` §7).

The read is a **projection** of the histories the domain keeps, so a client that presents it reads it
again after a successful action rather than patching its own copy (`BR-001`, `BR-080`). Most actions
answer with the Job (`docs/api/job-actions.md` §1), not with the timeline, so a client that shows both
asks for the timeline once the action has been applied — a Job status change, a reschedule, a crew
change and a Visit's field status or outcome all record history this read projects (`BR-058`, `BR-069`,
`BR-073`, `BR-074`, `BR-077`). The two writes that answer with the refreshed timeline itself are the
exceptions: adding a text update and removing evidence.

## 2. Permissions

| Route                    | Permission       |
| ------------------------ | ---------------- |
| `GET /jobs/:id/activity` | `customers.view` **or** `VISIT_VIEW_ASSIGNED` |
| `GET /jobs/:id/activity?includeRemovedEvidence=true` | `customers.view` or `VISIT_VIEW_ASSIGNED`, **and** an evidence removal capability — `evidence.photo.remove` or `evidence.audio.remove` |

The activity is a projection of the same records `GET /jobs/:id` returns, so it is reached through the
same guard: the office caller through `customers.view`, the field caller through `VISIT_VIEW_ASSIGNED`,
the capability `BR-009` gives the technician who does the work (`ADR-019` D1). It carries the same
**assignment scope** (`ADR-019` D2): a field caller reads the activity of a Job that at least one of
their own current assignments reaches, and a Job it does not reach is `404`, never `403`. The catalogue
has no `jobs.*` capability, and introducing one would invent a permission the Jobs feature has not
defined (`BR-006`, `BR-042`); the same open question recorded for the Job read
(`docs/api/job-details.md` §2) covers this read.

The audit/history context (§3.5) is the one exception: it shows evidence a Manager has taken out of
ordinary use (`BR-089`), so it takes an evidence **removal** capability as well. Either kind's is enough,
because the flag asks one question about one read rather than one question per kind (`ADR-018` A7). The
route's own capability is still required, because the read is still the Job's activity. A field caller
therefore cannot ask for it: the default Technician role holds neither removal capability (`BR-089`).

## 3. `GET /jobs/:id/activity`

One Job's unified activity: its own (Job-level) events and every Visit's events, merged and ordered
newest-first (`BR-080`).

### 3.1 Response

```json
{
  "jobId": "6f1a8d1e-4b26-4f8f-9a34-2b7c9e0d5a11",
  "events": [
    {
      "id": "c7a1…",
      "kind": "VISIT_NOTE_ADDED",
      "recordedAt": "2026-09-14T14:14:00.000Z",
      "actorName": "John Smith",
      "visitSequence": 2,
      "fromStatus": null,
      "toStatus": null,
      "technicianName": null,
      "roleCode": null,
      "previousRoleCode": null,
      "outcomeCode": null,
      "outcomeSummary": null,
      "body": "Found a damaged capacitor."
    }
  ]
}
```

Every field is present on every event; only the fields its `kind` carries are non-null.

### 3.2 Event vocabulary

The `kind` is the stable, machine-readable code (`BR-041`). The client resolves a localized label for
it; the API never returns presentation text (`BR-028`).

| Kind                          | Level | Carried fields                                  |
| ----------------------------- | ----- | ----------------------------------------------- |
| `JOB_STATUS_CHANGED`          | Job   | `fromStatus?`, `toStatus` (Job status codes)    |
| `JOB_PROPERTY_CHANGED`        | Job   | —                                               |
| `JOB_CUSTOMER_CHANGED`        | Job   | —                                               |
| `VISIT_STATUS_CHANGED`        | Visit | `fromStatus?`, `toStatus` (Visit status codes)  |
| `VISIT_SCHEDULED`             | Visit | —                                               |
| `VISIT_RESCHEDULED`           | Visit | —                                               |
| `VISIT_TECHNICIAN_ASSIGNED`   | Visit | `technicianName?`, `roleCode`                   |
| `VISIT_TECHNICIAN_REMOVED`    | Visit | `technicianName?`                               |
| `VISIT_TECHNICIAN_ROLE_CHANGED` | Visit | `technicianName?`, `previousRoleCode`, `roleCode` |
| `VISIT_OUTCOME_RECORDED`      | Visit | `outcomeCode`, `outcomeSummary?`                |
| `VISIT_NOTE_ADDED`            | Visit | `body`                                          |
| `JOB_PHOTO_ADDED`             | Job   | `photoId`, `photoPhase?`, `body?`               |
| `JOB_PHOTO_REMOVED`           | Job   | `photoId`, `photoRemovalReason?`                |
| `JOB_AUDIO_ADDED`             | Job   | `audioNoteId`, `audioPhase?`, `audioDurationSeconds`, `body?` |
| `JOB_AUDIO_REMOVED`           | Job   | `audioNoteId`, `audioRemovalReason?`            |

`fromStatus`/`toStatus` are the status codes the kind's vocabulary defines (`BR-058` for a Job,
`BR-074` for a Visit); the kind names which vocabulary the codes belong to.

`photoId` is the photo the entry records, and the value its bytes are read with
(`GET /jobs/:id/photos/:photoId/content`, `docs/api/job-photos.md` §4). `photoPhase` is the field-work
phase the technician chose (`BEFORE_WORK` / `DURING_WORK` / `AFTER_WORK`) — a stable code, not a label
(`BR-028`, `BR-041`) — and a photo's note travels in `body`, the same field a text update uses.
`JOB_PHOTO_ADDED` is Job-level, because that is where a photo is recorded (`BR-015`, `BR-051`).

`JOB_PHOTO_REMOVED` records that accepted evidence was taken out of ordinary use (`BR-088`, `BR-089`):
its `photoId` names the evidence, and the reason the removal was performed travels in
`photoRemovalReason` — its own field rather than `body`, because `body` is text an author recorded with
a record while this is the reason a removal was performed. The entry is Job-level and carries neither a
phase nor a photo.

`JOB_AUDIO_ADDED` and `JOB_AUDIO_REMOVED` are the same two events for the **audio** kind
(`BR-091`, `ADR-018` A6): `audioNoteId` names the recording and is the value its bytes are read with
(`GET /jobs/:id/audio-notes/:audioNoteId/content`, `docs/api/job-audio.md` §4.1), `audioPhase` is the
same field-work phase vocabulary a photo carries, `audioDurationSeconds` is the length the API read from
the recording's own container (`ADR-018` A3) so a client can draw it without opening the file, and a
removal's reason travels in `audioRemovalReason` for the same reason a photo's does. Both are Job-level,
because that is where the evidence is recorded (`BR-051`).

### 3.3 The Visit sequence

`visitSequence` is `null` for a Job-level event and otherwise the Visit's **stable, human-readable
sequence** — `1` for the Job's first Visit, `2` for the second, and so on, by the Visit's creation
order within the Job. It is a presentation label only: it is derived, never stored, never an
identifier, and never a cross-system key (`BR-052`, `docs/domain/job-visit-domain-model.md` §6).

### 3.4 What the read never includes

- Nothing outside the caller's organization: every query is scoped by `organization_id` (`BR-001`).
- Nothing for a Job whose Customer the organization has deleted: `BR-023` hides a deleted customer's
  Jobs, so the route answers `404` rather than disclosing the Job.
- No event vocabulary outside the table above. **Photos and audio notes** are the two evidence kinds with an
  authoritative source, so `JOB_PHOTO_ADDED`, `JOB_PHOTO_REMOVED`, `JOB_AUDIO_ADDED` and
  `JOB_AUDIO_REMOVED` are the evidence events
  (`docs/api/job-photos.md`, `docs/api/job-audio.md`). `files` still has none and no kind is invented for
  it (`BR-091`, `BR-042`).
- **Removed evidence** (`BR-088`, `BR-089`): evidence that has been taken out of ordinary use — a photo or
  an audio note — is not an ordinary read, so its `JOB_PHOTO_ADDED`/`JOB_AUDIO_ADDED` event is omitted and
  its bytes are refused (`docs/api/job-photos.md` §4.1, `docs/api/job-audio.md` §4.1). The removal event
  itself is part of **both** reads — the removal is history, and Activity does not silently drop it
  (`BR-080`). §3.5 is the read that includes the removed record.
- No pagination: the read returns the whole history, newest-first. Ordering and row limits are
  presentation concerns; pagination is a later decision.

### 3.5 The audit/history context — `?includeRemovedEvidence=true`

`D6d` requires removed evidence to remain visible in an audit/history context, so the read accepts one
optional query value:

| Query value                | Values          | Effect                                                                 |
| -------------------------- | --------------- | ---------------------------------------------------------------------- |
| `includeRemovedEvidence`   | `true`/`false`  | `true` also returns the `JOB_PHOTO_ADDED` events of photos that have been removed. Default `false`. |

- With `false` (or the value absent) the read is **ordinary**: removed evidence is excluded as §3.4
  describes.
- With `true` the removed record is included exactly as it was recorded — a photo's `photoId`,
  `photoPhase` and `body`, or an audio note's `audioNoteId`, `audioPhase`, `audioDurationSeconds` and
  `body` — beside the removal event that states **who** removed it, **when** and **why**. The bytes are
  still refused by the content route: this context is the record, not a second way to read evidence
  (`BR-089`).
- It is authorized by an evidence **removal** capability as well as the route's own capability —
  `evidence.photo.remove` or `evidence.audio.remove`, either being enough, because the flag asks one
  question about one read (`ADR-018` A7): it is a read of evidence that has been taken out of use, and
  `BR-089` gives that audience to authorized managers. A caller who may read the activity without one is
  refused with `403 FORBIDDEN` rather than quietly answered with the ordinary projection (`BR-007`).
- The value is a closed vocabulary: anything but `true` or `false` is refused with
  `400 VALIDATION_FAILED`, so a caller who asked for the audit context is never silently answered with
  the ordinary one (`BR-042`).

## 4. Errors

| Status | Code              | When                                                       |
| ------ | ----------------- | ---------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED` | `includeRemovedEvidence` is neither `true` nor `false`.   |
| `401`  | `UNAUTHENTICATED` | No usable session.                                         |
| `403`  | `FORBIDDEN`       | The caller holds neither `customers.view` nor `VISIT_VIEW_ASSIGNED`, or asked for the audit/history context (§3.5) without an evidence removal capability. |
| `404`  | `JOB_NOT_FOUND`   | The Job does not exist in the caller's organization, its Customer has been deleted, or the caller is a field caller whose own assignments do not reach it. |
| `5xx`  | —                 | Server failure; the client reports it and offers a retry.  |

A Job another organization owns is `404`, never `403` (`BR-001`), and a Job a field caller is not
assigned to is `404` for the same reason (`ADR-019` D2).

## 5. Open questions

Recorded rather than guessed (`BR-042`); `docs/tracker/022-android-job-activity-timeline.md` carries
the same list with what each one blocks.

1. **The `jobs.*` capability set** (`BR-006`, `BR-008`): this read is reached through `customers.view`
   or `VISIT_VIEW_ASSIGNED` (`ADR-019` D1) until the Jobs feature defines its own capabilities, exactly
   as `GET /jobs/:id` is.
2. **Evidence events** (`BR-027`, `BR-091`): photos and audio notes now have authoritative tables and the
   four kinds above (`docs/api/job-photos.md`, `docs/api/job-audio.md`); generic **files** still have none,
   and no kind is invented for them (`BR-042`, `ADR-018` A8).
3. **Pagination** (`BR-080`): the read returns the whole history; whether a long history needs
   paging is a later product decision.

