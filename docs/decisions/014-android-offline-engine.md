# ADR-014 — The Android offline engine: local store, replay and the first adopters

**Status:** Accepted (implementation of `ADR-012` D7; no earlier decision is superseded)

Date: 2026-09-14
Predecessor: `docs/decisions/012-property-lifecycle-and-permissions.md` (D7 named the standard)
Standard implemented: `docs/architecture/offline-first-architecture.md`
Tracker: `docs/tracker/025-android-offline-room-outbox.md`

References: `BR-001`, `BR-007`, `BR-013`, `BR-014`, `BR-015`, `BR-031`, `BR-032`, `BR-039`,
`BR-041`, `BR-042`, `BR-082`, `BR-086`, `Project.md` §13–§15, `dev.md` §4, §6, §10, §18, `qa.md` §7.3.

## Context

`ADR-012` D7 defined the offline/outbox standard and named Room as the engine, but nothing was
implemented: there was no local store, no outbox, no connectivity awareness, and no offline-capable
operation, even though `BR-086` requires Property archive and restore to be offline-capable. The
Android client therefore failed an archive performed without connectivity, and the Customer Detail
screen — the path through which a Property is reached — could not be read at all offline.

## Decisions

### D1 — Room 2.8.5, one database, two stores, schema committed

`androidx.room:room-runtime`, `room-ktx`, `room-compiler` (KSP) and `room-testing` (instrumented)
are pinned in `android/gradle/libs.versions.toml`, which is the single version source (`Project.md`
§23). One database, `servora-offline.db`, holds the two stores §2 of the standard defines: the
outbox and the working set. The schema is exported to `android/app/schemas` and **committed**; the
database is built with no destructive fallback, so a later schema change has to migrate pending work
rather than drop it (§3, `BR-014`). The exported JSON is also the instrumented-test asset a migration
test compares against.

### D2 — Local rows are scoped by the session's subject, not by an organization id

The standard's outbox row names `organizationId`, "so a replay cannot widen scope". This client never
receives an organization id: the API resolves the tenant from the session, the sign-in response
carries the session id, the token pair and the permission codes, and `GET /auth/me` carries the user
and the permissions. The device therefore partitions its local state by the **subject of the access
token it holds** (`sub`), and the tenant boundary stays entirely with the API, which is the stronger
guarantee (`BR-001`, `BR-007`, `BR-039`). `AuthenticatedSubject` reads that claim; it verifies no
signature, because it grants nothing and every replay is authorized by the API. A subject that cannot
be read means work is not queued rather than filed under an identity it did not come from.

### D3 — A handler is the feature's; the engine knows only ordering, retention and retry

`OfflineOperationHandler` is implemented by the feature that owns the operation and declares the
operation codes it applies; the engine resolves a handler by the queued row's `operationType`,
applies one operation at a time in `recordedAt` order, and maps the reported outcome onto the row
(§4–§6). A run stops at the first operation that may still succeed, so a later operation never
overtakes an earlier one, and an operation this build has no handler for is left untouched for a
build that has one (`BR-014`). Nothing is deleted because a request failed: `403`, `404`, `409` and
`422` become `REJECTED` and stay visible (`BR-032`); `401` waits for the next sign-in; network and
`5xx` retry with bounded backoff.

### D4 — Replay is triggered by lifecycle, connectivity and the user; not by a background worker

Replay runs when a session becomes available (app start or sign-in), when connectivity returns, and
when the user asks. Connectivity is observed with the platform `ConnectivityManager`, which is why
the application declares `ACCESS_NETWORK_STATE`. **WorkManager is not added**: the standard's three
triggers are all reachable in the foreground, so a background scheduler would be a dependency without
a demonstrated requirement (`dev.md` §4). If a future requirement needs replay while the app is not
running, that is a new decision.

### D5 — The per-operation conflict strategy is: read the version, then apply

§8 of the standard leaves the concrete strategy per operation open. For the Property lifecycle it is
**read the version the API reports now, then apply with it**:

- the replay reads the Property immediately before the mutation, so the subject's own earlier queued
  operations cannot conflict with a later one (the version the screen saw hours ago would);
- the guard still holds where it matters, because the API refuses a change that lands between that
  read and the write (`BR-086`);
- the idempotency key is the queued operation's own id, reused for every attempt, so a replay after a
  timeout cannot apply twice (§5).

A refusal (`409`, `403`, `422`, `404`) is terminal for the replay and is surfaced rather than retried.
This decision is per operation, as §8 requires; it is not a general conflict policy for other
entities.

### D6 — The working set is written only from API answers; a queued action is a derived overlay

§2 says an offline mutation "updates the local working set optimistically". The client does not write
a provisional value into the working set, because §2 also says the working set is never edited in
place as if it were authoritative. Instead:

- the working set holds the last answer the backend reported, and only a read or a replay writes it;
- a queued action is derived from the outbox rows for the entity and presented next to the record,
  with its state and any refusal reason.

The user still sees their own action immediately (`BR-012`), and nothing on screen claims the backend
accepted something it has not (`BR-086`, §7). A read served this way is marked as the last reported
update rather than presented as current.

### D7 — Two adopters, and what is deliberately still open

Adopters in this slice:

1. **Property archive and restore** (`BR-086`): queued offline, replayed, with the pending state shown
   and a refusal kept. Property **edits** and **permanent deletion** stay online-only — the API
   accepts no idempotency key for either, which is what §5 requires. Archive and restore were already
   the standard's named first adopter.
2. **The Customer Detail read**: served from the working set when the API cannot be reached, because a
   Property is reached through the Customer Detail and a queued archive is worthless if the path to
   it cannot be read offline. This is a read, not a queued mutation.

Still open and deliberately not decided here:

- **Which other operations are offline-capable** (`BR-032`, §11.1). Job and Visit actions are not:
  their routes accept no idempotency key yet, so queueing them is forbidden by §5 until the API does.
  That is an API slice before it is a client one.
- **The disposition of a non-empty outbox at sign-out** (§10). This slice drops the session's working
  set and **keeps** its pending work, which is what `BR-014` requires; what should happen to that work
  is a product question, not an implementation choice.
- **Whether a rejected operation can be discarded by the user** (§11.5). A refused operation is
  retained and shown; no discard action is offered until the product decides.
- **The per-operation conflict strategy for entities other than the Property lifecycle** (§8).
- **Local retention for the working set** (§11.4).
- Whether the Customer Detail's Property rows should carry the queued-action marker themselves. The
  pending state is shown where the action was taken (the Property screen); extending it to the
  customer's rows is a presentation decision for a later slice.

## Consequences

- `BR-086`'s client half exists: an archive or restore performed without connectivity is retained on
  the device, survives process death, and is applied when the API can be reached.
- Local state is attributed to a subject and dropped when that session ends, while pending work is
  kept and never replayed for another subject.
- The per-feature rule that follows from this engine — which store a new read uses, when a mutation may
  be queued, and what a feature that stays online-only records — is
  `docs/architecture/offline-first-architecture.md` §13. Nothing else in the app adopts the engine
  until that section is satisfied.
- One dependency set is added (Room and its KSP processor, plus `room-testing` for instrumented tests).
- The tests that exercise SQLite and migrations cannot run on the JVM; they are compiled by the agent
  and run by the product owner on a device (`qa.md` §7.3).

## Verification

Recorded in `docs/tracker/025-android-offline-room-outbox.md`.
