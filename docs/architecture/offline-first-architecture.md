# Servora — Offline-First Architecture Standard (Android)

> **Status: IMPLEMENTED for the operations that adopt it.** The store, the outbox, the replay engine
> and the triggers exist, and two adopters use them: **Property archive and restore** (`BR-086`) and
> the **Customer Detail read**. Everything else is still online-only, and §11 keeps the questions the
> standard deliberately does not answer.
>
> Decision records: `docs/decisions/012-property-lifecycle-and-permissions.md` (D7, which defined this
> standard) and `docs/decisions/014-android-offline-engine.md` (the engine as built).
>
> Implementation record: `docs/tracker/025-android-offline-room-outbox.md`.
>
> **Update (2026-09-14).** The client half now exists. The **server** half of the Property contract
> already did: Property archive and restore accept a client-generated `clientOperationId` and the
> device `capturedAt`, persist them, and recognise a replay instead of applying it twice
> (`docs/tracker/013-property-lifecycle.md`, `docs/api/customers.md` §4.2).
>
> Read together with `Project.md` §13–§15, `dev.md` §10 and the business rules
> `BR-013`, `BR-014`, `BR-015`, `BR-031`, `BR-032` and `BR-086`.

---

## 1. Scope and authority

Android is offline-first (`Project.md` §13). A feature that creates, changes or deletes business
data must define how it behaves without connectivity.

- The API and PostgreSQL remain the system of record (`BR-001`).
- Local state is a working set and a queue of provisional changes. It never becomes a competing
  source of truth.
- The API enforces authentication, authorization, validation and tenant scope. The client never
  decides whether an operation is allowed (`BR-007`).
- The backend is authoritative when the two differ; a local change is provisional until the
  backend accepts it (`BR-031`).

This document defines the *mechanism*. It does not define business behaviour: which operations are
offline-capable, and how a conflict is resolved for a given entity, are product decisions
(`BR-032`). Those remain open where not yet decided and must not be invented.

---

## 2. The two local stores

A feature keeps two kinds of local state, and they have different rules.

| Store       | Holds                                                                      | Authority                                                                     |
| ----------- | -------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| Working set | The last state the backend reported, for display while offline (`BR-013`).  | Backend. Refreshed on read; never edited in place as if it were authoritative. |
| Outbox      | Pending mutations the user performed offline, in the order they were made. | Provisional. Applied only by the backend.                                     |

**Working set.** Reads that must work offline are served from it. It is populated from API
responses; the client does not merge its own guesses into it. A read served from it is presented as
the last answer the backend reported rather than as a current one (`§7`).

**Outbox.** Every offline-capable mutation appends one outbox row and makes the user's own action
visible immediately (`BR-012`).

**How the user's own action is shown.** The working set is not written optimistically: it is only
ever written from an answer the backend gave, because §10 requires a successful read to replace it and
`BR-086` requires a client never to present an unconfirmed change as done. A queued action is instead
**derived from the outbox rows** for the entity and presented next to the record, with its state and
any refusal reason (`docs/decisions/014-android-offline-engine.md` D6).

---

## 3. Storage engine

- The engine is **Room (SQLite)** on AndroidX, wired through Hilt and KSP (already configured for
  Hilt).
- It is chosen over `SharedPreferences`/DataStore because the working set and outbox need
  transactional writes, queries and uniqueness constraints — neither key-value store provides
  them. `SharedPreferences` remains correct for device-local presentation preferences.
- Adding Room pins a version in `android/gradle/libs.versions.toml` (`dev.md` §4). It is the first
  step of the first offline slice and is not added before one exists.
- The schema is part of the app, migrated with Room's migration mechanism. A destructive fallback
  is not acceptable for pending work: a local schema change must migrate the outbox, not drop it
  (`BR-014`).

---

## 4. The outbox operation

One row describes one business mutation.

| Field                                           | Meaning                                                                                |
| ----------------------------------------------- | -------------------------------------------------------------------------------------- |
| `operationId`                                   | Client-generated UUID, created once when the user acts. The idempotency key.            |
| `operationType`                                 | The stable machine-readable operation code (e.g. `property.archive`, `visit.note.add`). |
| `targetId`                                      | The entity the operation acts on.                                                       |
| `subjectId`                                     | The authenticated subject the operation was made under, so a replay is never attributed to another session. See the note below. |
| `payload`                                       | The operation's arguments, as the API request body.                                     |
| `capturedAt`                                    | Device clock at the moment the user acted. Display/diagnostic only (`BR-031`).          |
| `recordedAt`                                    | The instant the outbox accepted the operation. Ordering within a device.                |
| `expectedVersion`                               | The version the client last saw, where the entity is versioned (`BR-086`).              |
| `attemptCount`, `lastAttemptAt`, `lastFailure`  | Retry bookkeeping.                                                                      |
| `state`                                         | `PENDING`, `IN_FLIGHT`, `FAILED`, `REJECTED`.                                           |

**Why the scope field is `subjectId` and not `organizationId`.** This field exists so a replay cannot
widen scope. The API resolves the tenant of every request from the session and never names an
organization to the client — sign-in returns the session id, the token pair and the permission codes
— so the strongest scope the device can hold is the subject of the access token it was issued. The
tenant boundary stays entirely with the API, which authorizes every replay (`BR-001`, `BR-007`,
`BR-039`). Recorded in `docs/decisions/014-android-offline-engine.md` D2.

Rules:

- Operations are ordered by `recordedAt` and replayed in that order, so a create and a later change
  to the same entity cannot be applied out of order.
- `capturedAt` is **never** used as business time. Business time is the backend's recorded time
  (`BR-086`, `dev.md` §10).
- One operation is one API call. A feature must not batch unrelated operations into one call
  unless the API defines such an endpoint.

---

## 5. Idempotency

`BR-031` requires that duplicate submissions do not create duplicate business outcomes.

- The client generates `operationId` once, before the first attempt, and reuses it for every
  retry.
- The API accepts the identifier and enforces uniqueness per organization, so a replay is a no-op
  that returns the original outcome. `property_lifecycle_history.client_operation_id` is the
  existing example of this column.
- A retry after a timeout is therefore safe: the operation is applied at most once.
- A feature whose API does not yet accept an idempotency key must not be queued offline; it is
  online-only until it does (`BR-086`, permanent deletion).

---

## 6. Replay, retry and failure

Replay runs when the app starts, when connectivity returns, and after a successful sign-in.

| Outcome                         | Handling                                                                             |
| ------------------------------- | ------------------------------------------------------------------------------------ |
| Success (`2xx`)                 | Remove the outbox row; refresh the affected working set from the backend.             |
| Network / `5xx`                 | Keep the row, increment `attemptCount`, retry with backoff, stay `PENDING`/`FAILED`.  |
| `401`                           | Renew the session once (`SessionAuthenticator`); if renewal is refused, keep the row and stop until the user signs in again. |
| `403`                           | `REJECTED`: the caller is not authorized. The row is retained and shown, never silently dropped (`BR-014`). |
| `409` / `422` (stale or invalid) | `REJECTED`: the change conflicts with newer backend state. The row is retained for the user to resolve; it is never re-applied blindly (`BR-032`). |

- No failure path deletes pending work. A `REJECTED` operation stays visible with its reason.
- Retryable and terminal failures are distinguished in code, not by parsing a message.
- Cancellation and process death must not mark an operation as failed.

---

## 7. Synchronization state in the UI

- The client presents the state the API reports; it does not invent an outcome (`BR-086`).
- Pending work is visible without interrupting field work (`BR-012`, `BR-015`): a count or a
  subtle per-row indicator, not a modal.
- A `REJECTED` operation that needs the user's decision is surfaced explicitly.
- Connectivity is never assumed. An operation is queued whenever the API is unreachable, whether
  the device reports a network or not.

---

## 8. Conflict model

The general model is fixed; the per-operation strategy is not.

- The API is the final authority. A mutation against newer state is rejected, not applied
  (`BR-086`, `BR-031`).
- Where an entity is versioned, the mutation carries the version the client last saw. A mismatch is
  a conflict.
- **Last-write-wins is never applied to business data** (`BR-032`).
- Which entity uses which concrete strategy (reject, merge, re-read and retry, prompt the user) is
  an **OPEN QUESTION** per operation until product ownership decides. It is not inferred from
  another entity's decision.
- Conflict state is recorded locally so the user can act on it; the server's record remains the
  authoritative one.

---

## 9. Evidence and files

Photos, audio and files are business evidence (`BR-015`, `BR-027`).

- A captured file is written to app-private storage first and is usable immediately.
- The outbox references the local file; the upload is a queued operation.
- A failed upload never deletes the local file. It is retried, and its state is shown without
  blocking the technician.
- A local file is removed only after the backend confirms it owns the evidence, or by an explicit
  user action.

---

## 10. Local data lifecycle and scope

- Local business data is scoped to the signed-in session's organization; the client clears the
  working set and the outbox for that session on sign-out rather than leaving them readable.
- Pending work must not be lost by signing out: sign-out with a non-empty outbox requires an
  explicit user decision, and the queues' disposition is recorded in the feature that owns them.
  What that decision should be is an **OPEN QUESTION**.
- Local data is never treated as authoritative when the backend answers. A successful read
  replaces the local copy.

---

## 11. What this standard does not decide

These remain **OPEN QUESTION** and must not be invented:

1. Which operations are offline-capable, per feature (`BR-032`). **Adopted so far:** Property archive
   and restore (`BR-086`), and the Customer Detail read. Job and Visit actions cannot be queued until
   their routes accept an idempotency key (§5).
2. The concrete conflict strategy per operation (§8). **Decided for the Property lifecycle only:**
   read the version the API reports now, then apply with it (`docs/decisions/014-android-offline-engine.md` D5).
3. The disposition of a non-empty outbox at sign-out (§10). Pending work is kept; what should happen
   to it is undecided.
4. Local data retention for the working set.
5. Whether a rejected operation can be discarded by the user, and the audit trail for that
   (`BR-014`).

---

## 12. Adopters

1. **Property archive and restore** (`BR-086`) — offline-capable, carrying a `client_operation_id`.
   **Permanent deletion is online-only** and must never be queued; a Property **edit** is online-only
   too, because the API accepts no idempotency key for it (§5).
2. **The Customer Detail read** — served from the working set when the API cannot be reached, so the
   path to a Property survives a loss of connectivity. Only a failure that could not reach the backend
   falls back: an answer, a refusal included, is never replaced by a local copy.
