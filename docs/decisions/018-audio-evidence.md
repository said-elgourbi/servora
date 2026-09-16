# ADR-018 — Audio evidence: the model, the vocabulary and the capability

**Status:** Accepted (product-owner decision, 2026-09-16; `A1` decided by the agent under explicit
delegation, 2026-09-16)

Date: 2026-09-16
Tracker: `docs/tracker/035-android-audio-evidence.md` (Phase 9 of `docs/tracker/029-photo-evidence-phases.md`)
Contract: `docs/api/job-audio.md`
Predecessors: `docs/tracker/027-android-job-photo-updates.md` (the photo evidence slice),
`docs/tracker/029-photo-evidence-phases.md` (the evidence lifecycle), `docs/tracker/033-android-add-update-hierarchy.md`
(the sheet's audio seam), `docs/decisions/013-object-storage-minio-and-s3.md` (the store),
`docs/decisions/015-evidence-capabilities.md` (the capability catalogue this activates)

References: `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-011`, `BR-012`, `BR-013`, `BR-014`, `BR-028`,
`BR-031`, `BR-041`, `BR-042`, `BR-051`, `BR-067`, `BR-080`, `BR-088`, `BR-089`, `BR-090`, `BR-091`,
`Project.md` §15, §31, `dev.md` §4, §6, §7, §9, §10, §19, `qa.md` §4.3, §6, §8, §13,
`docs/architecture/offline-first-architecture.md` §5, §8, §9, §13.

## Context

`BR-091` put audio in scope: "**Audio notes are evidence** of their own kind, using the same storage
abstraction, offline outbox, upload lifecycle, authorization and immutable-history rules as photos."
`ADR-015` D2 reserved the code `evidence.audio.add` and deliberately did **not** create it, "because no
product rule defines audio yet (`BR-042`)", and Tracker 029 Phase 9 states that the phase begins with an
**ADR**, because "everything this tracker has not decided belongs there, before the migration is written".

What tracker 029 leaves to this ADR (Phase 9 item 1, `D8`, `BR-091`): the evidence row model for a kind,
the content-type and size vocabulary, length limits, the Activity kind and playback. Tracker `033` left
one further question: whether the audio kind also offers *choose an existing recording* beside *record*.

What already exists is the seam only: `JobUpdateKind.AUDIO` with its `ic_mic` glyph, its *Add audio*
label and an empty `JobUpdateAudioContent` composable, all unreachable because `JobDetailsScreen` states
`canAddAudio = false`. There is no table, no route, no capability row, no Activity kind, no recorder, no
`RECORD_AUDIO` permission and no player.

The product owner answered this round on 2026-09-16: `A2`–`A10` follow the recommendations below, and
`A1` was delegated to the agent, which is the one decision in this ADR taken by the agent rather than by
product ownership.

## Decisions

### A1 — A dedicated append-only audio evidence table, not a unified evidence model

Audio evidence is stored in its **own append-only table**, `job_audio_notes`, with its own removal
history `job_audio_note_removals`, mirroring the shape the photo evidence already has. `job_photos` and
`job_photo_removals` are **not** rewritten, renamed or migrated by this slice.

**Why.** The decision is between one evidence model carrying a `kind` (`D8` option (b)) and a parallel
structure (`D8` option (c)):

- What is already decided is **per kind**: `ADR-015` D2 made the capability per kind so a company can
  withdraw one kind of evidence from a member and keep the others, and `A6`/`A7` below keep the Activity
  kind and the removal capability per kind for the same reason. A unified table would therefore be
  unified in storage while every layer above it stays kind-specific, so it buys much less than it
  appears to.
- `BR-089` and `BR-091` name the photo removal capability and the photo's classification explicitly. A
  unified model would want a kind-agnostic removal capability and a kind-agnostic Activity kind, which is
  a **business-rule change in `BR-089`/`BR-091`**, not an implementation detail — and `BR-040` requires
  product ownership to make that change deliberately.
- A unification migration would move `job_photos` and `job_photo_removals` — the tables the
  evidence-removal slice (`Phase 6b`) had just introduced — and `dev.md` §6 prefers additive,
  backward-compatible evolution while `dev.md` §19 prefers the reversible change while the question is
  open.
- Nothing above the database changes if the tables are unified later: no client ever sees a table name,
  the API answers DTOs, and the object keys are already under one `evidence/` prefix. The unification is
  therefore still reachable, at the cost of a data migration rather than a redesign.

**What this does not decide.** Whether `job_photos` and `job_audio_notes` are ever unified into one
kind-based evidence model remains an **OPEN QUESTION**, with the triggers recorded in §"Open questions"
below. This ADR decides only that audio is added beside photos rather than under a migration of them.

**Changed decision?** No. `D8`'s option (b) was the shape to adopt *if* evidence kinds were unified; the
per-kind decisions `ADR-015`, `A6` and `A7` already took are what this ADR follows.

### A2 — One container: `audio/mp4`, decided from the bytes

The API accepts **`audio/mp4`** (AAC audio in an MP4/M4A container) and nothing else. The type is
**sniffed from the bytes** — an `ftyp` box at offset 0 with a brand the API recognises, plus an audio
track — rather than taken from what a client declared, which is the rule `BR-015` and `ADR-013` D7 set
for photo evidence and which this kind follows. A declared content type that disagrees with the bytes is
refused, and a file that is not the accepted container is refused with nothing recorded.

**Why one container.** It is the container the platform's own recorder produces on every Android version
Servora supports (`MediaRecorder` with `MPEG_4` + AAC), so a second accepted type would exist only for
uploads Servora's own client cannot make. Each additional container would need its own duration parser,
its own playback risk and its own tests, and `BR-042` does not allow accepting a vocabulary nothing
produces. A device whose recorder cannot produce `audio/mp4` is a **refusal**, not a silently different
format: the client refuses the recording rather than storing bytes the API would refuse.

**Consequence.** The object key is `evidence/job-audio-notes/{organizationId}/{jobId}/{audioNoteId}.m4a`,
built by the API from the record — the same provider-agnostic key shape `ADR-013` D6.5 requires and the
photo key already uses.

### A3 — Size and length limits, and the length is derived from the bytes

| Limit                        | Value      | Enforced                                  |
| ---------------------------- | ---------- | ----------------------------------------- |
| Largest recording            | 10 MiB     | Multipart parser **and** the buffered part |
| Longest recording            | 300 s      | Derived from the container, API-side       |
| Shortest recording           | 1 s        | Derived from the container, API-side       |

The **duration is read from the recording's own bytes** (the `mvhd` box inside `moov`, timescale and
duration), and that derived value is what the API stores and what the limits are applied to. A client's
declared duration is never trusted with a product limit and is not stored: `BR-015` requires bytes to be
validated rather than believed, and a length limit a client can assert is not a limit. A recording whose
container the API cannot read a duration from is **refused** rather than accepted unverified.

10 MiB is comfortably above 300 s of the mono AAC audio a field note is (roughly 2.4 MB at 64 kbps,
4.8 MB at 128 kbps), so the byte limit is a safety bound rather than the limit a technician meets first.

### A4 — An audio note carries the same field-work phase as a photo

An audio note carries `phase` — `BEFORE_WORK` / `DURING_WORK` / `AFTER_WORK` — and it is the **same
vocabulary** a photo carries, defined once. It is not a second, audio-only classification.

**Why.** The phase is what an update is about rather than what kind of file it is (`BR-091`), and the
technician chooses it once per update in the sheet that records the evidence. Two vocabularies would mean
two codes for one idea, which is what `BR-041` forbids. `BR-091`'s statement that `phase` is "the only
structured classification of a photo" is therefore extended by this decision to the second kind, not
reinterpreted: no tag, category or audio-specific classification is introduced.

### A5 — An audio note may carry a note

An audio note may carry the same optional free-text `note` a photo may (2000 characters, the bound the
photo note already has), and it travels in Activity's `body`, which is the field a client already renders
as an entry's own text (`BR-080`). It is optional: the recording is the evidence.

### A6 — Two Activity kinds, additive

`JOB_AUDIO_ADDED` and `JOB_AUDIO_REMOVED` are added to the Activity vocabulary, with `audioNoteId`,
`audioPhase`, `audioDurationSeconds` and `audioRemovalReason` as their own event fields.

**Why not generalise `JOB_PHOTO_*`.** `BR-041` requires one definition per shared concept, but reusing a
*photo* code for an audio recording would make the code say something it does not mean, and renaming a
shipped kind is a contract break for a client that already resolves a label for it (`BR-088` makes the
Activity history append-only in the same spirit). The addition is additive, and each kind names its own
vocabulary — exactly as the per-kind capabilities do.

**The audit/history context covers audio too.** `?includeRemovedEvidence=true` includes the
`JOB_AUDIO_ADDED` entries of audio notes that have been removed, on the same terms it already includes a
removed photo's entry. It is one flag for one question ("show me removed evidence"), not one flag per
kind.

### A7 — The capability set gains `evidence.audio.add` and `evidence.audio.remove`

| Code                    | Capability                     | Default Manager | Default Technician |
| ----------------------- | ------------------------------ | --------------- | ------------------ |
| `evidence.audio.add`    | Record audio evidence on a Job | yes             | yes                |
| `evidence.audio.remove` | Remove accepted audio evidence | yes             | no                 |

`evidence.audio.add` is the code `ADR-015` D2 reserved; it is created **now** because the rules that
accept a recording exist, which is the condition that ADR set. `evidence.audio.remove` is separate from
`evidence.photo.remove` because the catalogue is per kind: a company may let a member remove a photo and
not an audio recording, and the bilingual name a member sees should say which kind it governs (`BR-005`,
`BR-006`). Both are enforced by the API on their own route, and neither is implied by the other
(`BR-007`).

**Consequence for the audit context.** `?includeRemovedEvidence=true` is authorized by **any** evidence
removal capability — `evidence.photo.remove` or `evidence.audio.remove` — because it is one read of one
kind of thing (`BR-089`), rather than a read that discloses one kind's removals to a member trusted with
the other kind's.

### A8 — One source: record

The audio kind offers **record** only. Choosing an existing audio file from the device is **not** in
scope, which is what the design specifies: `Figma/…/servora-job-details-spec.md` §"Audio" lists *Record*,
*Stop*, *Playback/review*, *Delete/re-record* and *Attach to update*, and no picker. Tracker `033`'s open
question is therefore answered as **out of scope for v1** rather than left hanging, and no capability is
introduced for reading a file from the device (`BR-042`).

### A9 — Playback on the platform's own stack

Android plays a recording back with the platform's own media stack (`MediaRecorder` to record,
`MediaPlayer` to play). **No media dependency is added** (`dev.md` §4): the requirement is a short mono
AAC recording played inside a screen Servora already renders, which the platform provides. A richer
player (waveforms, a scrubber, streaming from a presigned URL) is a later decision, and Phase 8's read
change is the point at which it would be reconsidered.

### A10 — Offline: the recording upload uses the existing engine, the removal does not

Recording and upload are **offline-capable** and use the engine that exists (`BR-013`, `BR-014`): the
recording is held app-private as a draft, queued through `OutboxStore` under the operation
`job.audio.add`, and replayed by `OutboxReplayEngine` through a handler this feature owns — not a second
queue, retry loop or connectivity check.

The API accepts a **client-generated idempotency key** (`clientOperationId`, `BR-031`), which is the
condition the offline standard's §13.2 sets for queueing a mutation. The audio record is append-only and
**no update path exists** (`BR-088`), so there is nothing for a replay to conflict with: a replay finds
the record the first attempt created and answers with the same Activity, and a retry after a timeout
writes the same object key, so a failed attempt cannot leave an orphan object behind. That is the same
decided policy the photo upload has, not a new one.

Removing accepted audio evidence is **online-only**, for the same reason the photo removal is: the route
takes no idempotency key and no conflict policy is decided for it, which is the offline standard's own
condition for staying out of the outbox (§5, §8, §13.2).

## What this changes

| Layer | Change |
| --- | --- |
| Database | `job_audio_notes` (+ `job_audio_note_removals`), and the two capability rows with their default-role grants |
| API | `POST /jobs/:id/audio-notes`, `GET /jobs/:id/audio-notes/:audioNoteId/content`, `POST /jobs/:id/audio-notes/:audioNoteId/removal`; `JOB_AUDIO_ADDED`/`JOB_AUDIO_REMOVED` in the Activity read |
| Contracts | `docs/api/job-audio.md` (new), `docs/api/job-activity.md` §3.2 |
| Android | the recorder, the draft, the queued upload, the sheet's audio kind becoming reachable, playback (`docs/tracker/035-android-audio-evidence.md`) |
| Business rules | none amended by this ADR: `BR-091` already states audio is evidence. `BR-008`/`BR-009`'s default-role grants are product ownership's text edit, as `BR-040` requires, and remain outstanding exactly as the photo capability's edit did |
| Design system | `docs/design/android-design-system.md` (the Add update sheet's audio kind) |

## Open questions not decided here

Recorded rather than guessed (`BR-042`).

1. **Whether photos and audio are unified into one kind-based evidence model** (A1). Triggers for
   revisiting it: a third evidence kind, evidence reporting in the Angular application (Phase 7), or
   Phase 8's read change making a shared evidence read path worth having. Until one of those arrives the
   two tables stay separate and no rename is performed.
2. **MP4 container metadata.** `BR-091` requires GPS/location and unneeded metadata to be stripped from
   uploaded evidence by default. **Where** that strip runs is Tracker 029 Phase 6c's decision and applies
   to photos and audio alike; this slice stores the container it validated and does not invent a second
   policy. An MP4's `udta`/`©xyz` box is the audio equivalent of EXIF GPS and must be covered by whatever
   Phase 6c decides.
3. **Audio length beyond 300 s, additional containers, and transcription** — none is in scope, and each
   would be a product decision rather than an implementation change.
4. **Audio in the Angular application** — Phase 7, once the Angular application exists.
