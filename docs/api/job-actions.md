# Servora API — Job & Visit Actions

**Status: IMPLEMENTED** for the Job and Visit management actions, and for the Visit field lifecycle.

> **Which lifecycle model this documents.** The Job and Visit vocabulary the API exchanges is the model
> `docs/tracker/051-job-visit-lifecycle-redesign.md` defines, and `Business Rules.md` now states it:
> Job status is `NEW`, `ACTIVE`, `COMPLETED` or `CANCELED` (`BR-058`); Visit status is `DRAFT`,
> `SCHEDULED`, `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`, `COMPLETED` or `CANCELED` (`BR-074`); an outcome is
> `RESOLVED`, `NEEDS_FOLLOW_UP`, `NEEDS_PARTS` or `UNABLE_TO_COMPLETE` (`BR-078`); and operational
> attention is **derived**, never a status (`BR-060`). The former `PENDING_REVIEW` Job status and
> `NO_SHOW` Visit status are removed, and `BR-061` is recorded as **REMOVED** (`BR-040`).

These routes are the explicit, authorized actions `BR-058` – `BR-079` define. Each one is performed by
an authenticated member as a deliberate decision, each one is recorded in append-only history
(`BR-067`), and the API is the authority for whether the action is allowed at all (`BR-001`, `BR-007`).

References: `BR-001`, `BR-006`, `BR-007`, `BR-008`, `BR-009`, `BR-010`, `BR-011`, `BR-022`, `BR-031`,
`BR-041`, `BR-042`, `BR-047`, `BR-058`, `BR-059`, `BR-060`, `BR-061`, `BR-062`, `BR-063`, `BR-066`,
`BR-067`, `BR-068`, `BR-069`, `BR-070`, `BR-072`, `BR-073`, `BR-074`, `BR-075`, `BR-076`, `BR-077`,
`BR-078`, `BR-079`, `BR-083`, `BR-086`, `BR-FV-007`, `dev.md` §7, `Project.md` §15,
`docs/domain/job-visit-domain-model.md` §6 – §7, §15,
`docs/decisions/019-technician-field-experience.md`, `docs/tracker/018-android-job-actions.md`,
`docs/tracker/034-manager-job-status-workflow.md`,
`docs/tracker/037-technician-field-experience.md`,
`docs/tracker/051-job-visit-lifecycle-redesign.md` (the lifecycle model this document describes),
`docs/api/visit-requests.md` (the follow-up Visit request routes),
`docs/api/job-details.md` (the read this document's actions answer with).

## 1. Conventions

JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15). The access token is
presented as `Authorization: Bearer <accessToken>`. Route paths carry no version prefix.

Every action answers with **the Job as it now stands** — the same projection `GET /jobs/:id` returns
(`docs/api/job-details.md` §3) — so a client presents the backend's state instead of patching a local
copy (`BR-001`). The exceptions are the writes that already answer with the refreshed **Job Activity**
timeline: the Visit note (§7.5) and the evidence removals (`docs/api/job-photos.md`,
`docs/api/job-audio.md`).

A client that also presents **Job Activity** (`BR-080`) therefore reads `GET /jobs/:id/activity` again
once an action has been applied: the action's answer is the Job, not the timeline, and the history these
actions record (status, schedule, assignment, Visit transitions and outcomes) is what that read
projects.

## 2. Permissions

| Route                                        | Permission                   |
| -------------------------------------------- | ---------------------------- |
| `PATCH /jobs/:id/status`                     | `JOB_UPDATE`                 |
| `PATCH /jobs/:id/visits/:visitId/schedule`   | `visits.update_schedule`     |
| `PUT /jobs/:id/visits/:visitId/technicians`  | `visits.assign_technicians`  |
| `GET /technicians`                           | `TECHNICIAN_VIEW`            |

Scheduling and rescheduling a Visit and stating its crew are **Visit** concerns, so they are authorized by
the Visit capabilities the catalogue already names (`VISIT_PERMISSIONS.UPDATE_SCHEDULE`,
`VISIT_PERMISSIONS.ASSIGN_TECHNICIANS`, `api/src/auth/permissions.ts`) rather than by the office's
`JOB_UPDATE`: a member trusted to arrange work is not therefore trusted to change the Job. The interim
`JOB_UPDATE` authorization this table carried before those capabilities existed no longer applies
(`BR-006`, `BR-041`).

The **Visit field routes** (§7) serve both audiences, and `BR-093` gave the status route the second
authorization the note route has had all along:

| Route                                        | Permission                                                                            |
| -------------------------------------------- | ------------------------------------------------------------------------------------- |
| `PATCH /jobs/:id/visits/:visitId/status`     | `VISIT_UPDATE_ASSIGNED_STATUS` **or** `JOB_UPDATE`                                    |
| `POST /jobs/:id/visits/:visitId/completion`  | `VISIT_UPDATE_ASSIGNED_STATUS` **or** `JOB_UPDATE`, **and** `VISIT_RECORD_OUTCOME`     |
| `POST /jobs/:id/visits/:visitId/notes`       | `VISIT_ADD_NOTE` **or** `JOB_UPDATE`                                                  |

A **completion is its own route** (`§7.6`): the outcome `BR-077` requires is the business operation that
makes the Visit `COMPLETED`, so it is not a destination of the ordinary status route. The status route
carries working destinations only, and a request that supplies an outcome on it is `400`.

The four `VISIT_*` codes already exist in the catalogue and are already granted to the default
Technician role (`0004_woozy_spitfire`), so these routes enforce capabilities the product already names —
they invent none (`BR-009`, `BR-042`, `ADR-019` D1).

**Why these routes accept either capability, and what the choice decides.** `VISIT_ADD_NOTE` and
`VISIT_UPDATE_ASSIGNED_STATUS` are the capabilities `BR-009` gives the technician; `JOB_UPDATE` is the
office capability `BR-008` gives a manager, and the one **every** other office action on a Job or Visit
already requires. The **scope follows the capability that admitted the caller** (`ADR-019` D2, D3, D7;
`BR-093`): a caller without the office capability is bounded by their own **current crew**, and a Visit
their crew does not include is `404 VISIT_NOT_FOUND`; an office caller reaches the organization's Visits
without crew membership, which is how an office member completes a Visit from Job Details. The office
capability is an alternative to crew membership, never a substitute for a destination's own capability —
a completion still requires `VISIT_RECORD_OUTCOME` of every caller (`BR-093`).

> **OPEN QUESTION (product ownership).** The Job capability set, and the capability each Visit action
> (reschedule, assign) should require in its own right. Until it exists, every **office** action here
> requires `JOB_UPDATE`, which never grants more than the catalogue already does — `BR-093`'s office Visit
> completion included; whether the office should have a dedicated Visit capability of its own is part of
> this question. Recorded in `docs/tracker/018-android-job-actions.md`.

**Why `JOB_UPDATE` and not a `jobs.*` capability set.** `BR-008` confirms that the Manager default role
updates Jobs, and the foundation catalogue already carries a `JOB_UPDATE` capability granted to that
role. The Jobs feature has not defined its own `resource.action` capability set, so introducing
`jobs.update` / `jobs.cancel` codes here would invent capabilities product ownership has not defined
(`BR-006`, `BR-042`). This is the same interim-authorization pattern the Job read already follows,
where `GET /jobs/:id` is guarded by the existing `customers.view`.

**Why `TECHNICIAN_VIEW` for `GET /technicians`.** Choosing a crew means choosing from the
organization's technicians, which is the capability `BR-008` already names for reading them. Whether
the Technician capability set is re-expressed as `technicians.*` is an **OPEN QUESTION**.

The backend is the authorization authority; a client that hides an action is not authorization
(`BR-007`).

## 3. `PATCH /jobs/:id/status`

Moves a Job through its lifecycle (`BR-058`).

### 3.1 Request

```json
{
  "status": "IN_PROGRESS",
  "note": "Customer confirmed the technician may enter.",
  "expectedVersion": 3
}
```

| Field             | Required | Meaning                                                                                                                |
| ----------------- | -------- | ---------------------------------------------------------------------------------------------------------------------- |
| `status`          | yes      | A code from the Job status vocabulary (`BR-058`). A code Servora does not have is `400`.                                |
| `note`            | no       | Recorded with the transition; a reopen reason, for instance (`BR-063`).                                                  |
| `expectedVersion` | no       | The Job version the client last saw. When it is supplied and the Job has moved on, the change is refused (`BR-086`).     |

### 3.2 What the API enforces

- **Only `BR-058`'s structurally permitted destinations.** A destination the table does not list is
  `409 JOB_STATUS_TRANSITION_NOT_ALLOWED`, and the response carries the destinations the Job *may*
  move to, so a client can offer an allowed action instead of guessing.
- **Any permitted destination is one operation.** An open Job moves directly to any destination
  `BR-058` lists for its current status — `NEW` → `ACTIVE`, and `NEW`/`ACTIVE` → `COMPLETED` or
  `CANCELED` — in a single request and a single recorded transition. A client never reaches a
  destination by issuing a series of transitions.
- **`COMPLETED` and `CANCELED` are terminal.** Their only destination is `ACTIVE`, the explicit reopen
  (`BR-063`). `NEW` is not a destination for an open Job: an open Job is never moved back to `NEW`.
- **`BR-062` before `COMPLETED`.** The Job must have no open Visit — no Visit whose status is anything
  other than `COMPLETED` or `CANCELED` (`BR-074`; the same classification `BR-083` uses for an active
  Visit). A Job whose Visits are all historical, or that has no Visit at all, may be closed; a
  `DRAFT`, `SCHEDULED`, `EN_ROUTE`, `ON_SITE` or `IN_PROGRESS` Visit blocks it. Otherwise `409
  JOB_COMPLETION_BLOCKED`. This is runtime eligibility, not a structural limit: `COMPLETED` stays in
  `allowedStatusTransitions`, a client offers it, and the API answers the attempt.
- **Closing a Job is an explicit office action** (`BR-062`): it is applied as asked, Visit outcomes
  become immutable from then on (`BR-079`), and a client confirms a consequential change with the user
  before sending it — a confirmation is never a substitute for the open-Visit invariant above.
- **Cancellation cascades to the Job's Visits** (`BR-064`, `BR-065`). Moving a Job to `CANCELED`
  cancels its open Visits inside the same transaction — that is, every Visit whose status is not
  `COMPLETED` or `CANCELED` — each with its own append-only status-history row recording the note, the
  trigger (`cancellationSource: JOB_CANCELLATION`) and the Job-status event it belongs to; historical
  `COMPLETED` Visits are untouched. `BR-064`'s **structured** cancellation-reason catalogue is still
  undefined, so `note` is the only explanation the request carries and the catalogue remains an `OPEN
  QUESTION` (`§10`).
- **Operational attention is never a Job status.** `FOLLOW_UP_NEEDS_SCHEDULING`, `PARTS_REQUIRED`,
  `UNABLE_TO_COMPLETE`, `JOB_NEEDS_SCHEDULING` and `VISIT_OVERDUE` are **derived** conditions the Job
  read reports separately (`attention`, `docs/api/job-details.md` §3). None of them is a status and
  none of them appears in `allowedStatusTransitions`.
- **The eligibility conditions are evaluated inside the transaction that applies the change**, through
  the same client, so a Job is never moved on a picture of its Visits that has already changed
  underneath.
- **A Job status change moves the Job alone.** It never writes a Visit's status, a Visit's history or
  any other Visit record; the explicit cancellation cascade above is the one recorded exception
  (`BR-059`, `BR-065`, `BR-067`).
- **A Job status change moves the Job alone.** It never writes a Visit's status, a Visit's history or
  any other Visit record (`BR-059`, `BR-067`).

### 3.3 Recorded

One append-only `job_status_history` row: previous status, new status, the optional note, the actor and
the timestamp. A direct move records **one** row, however many statuses it skipped. The Job's `version`
is incremented in the same transaction. A cancellation additionally writes one `visit_status_history` row
per open Visit it cascades, in that same transaction (`BR-065`).

## 4. `PATCH /jobs/:id/visits/:visitId/schedule`

Edits the existing Visit's schedule (`BR-073`). It is a **schedule change, not a new Visit**: the
Visit's identity, status, outcome and crew are untouched, and the previous schedule stays fully
reconstructible (`BR-057`, `BR-073`).

### 4.1 Request

```json
{
  "scheduledStart": "2026-09-16T13:00:00.000Z",
  "scheduledEnd": "2026-09-16T15:00:00.000Z",
  "arrivalWindowStart": "2026-09-16T12:30:00.000Z",
  "arrivalWindowEnd": "2026-09-16T13:30:00.000Z",
  "reason": "Customer asked for the afternoon.",
  "confirmConflicts": false,
  "expectedVersion": 2
}
```

| Field                                     | Required | Meaning                                                                                                                  |
| ----------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------ |
| `scheduledStart` / `scheduledEnd`         | yes      | The Visit's internal schedule, which is authoritative for dispatch and conflicts (`BR-072`). Instants with an explicit zone. |
| `arrivalWindowStart` / `arrivalWindowEnd` | no       | The optional customer-facing window. It is a pair: one without the other is `400`.                                        |
| `reason`                                  | no       | Recorded in the schedule history; `BR-073` requires none.                                                                 |
| `confirmConflicts`                        | no       | Whether the user accepted the availability conflicts the API reported (`BR-070`).                                          |
| `expectedVersion`                         | no       | The Visit version the client last saw (`BR-086`).                                                                         |

### 4.2 What the API enforces

- **`BR-073` allows a reschedule only while the Visit is `SCHEDULED`.** Any other state is `409
  VISIT_NOT_RESCHEDULABLE` with the Visit's status.
- **`BR-070` conflicts are reported, not applied silently.** When a technician on the Visit is already
  assigned to another non-canceled Visit whose window overlaps, the first request is refused with `409
  SCHEDULE_CONFLICT` and the conflicts in `details.conflicts` — which Visit, which technician and which
  time. The **same** request with `confirmConflicts: true` applies the change and records what was
  accepted, because in v1 a conflict is a warning rather than a prohibition.
- **The window must be valid**: `scheduledEnd` after `scheduledStart`, and the arrival window ordered.

### 4.3 Recorded

One append-only `visit_schedule_history` row carrying the previous and new schedule (including the
arrival window), the reason, the actor and the conflicts the user accepted. The Visit's `version` is
incremented in the same transaction.

## 5. `PUT /jobs/:id/visits/:visitId/technicians`

States the **whole crew** a Visit carries (`BR-068`, `BR-069`).

### 5.1 Request

```json
{
  "technicians": [
    { "membershipId": "9a8b7c6d-…", "roleCode": "LEAD" },
    { "membershipId": "1f2e3d4c-…", "roleCode": "TECHNICIAN" }
  ],
  "confirmConflicts": false,
  "expectedVersion": 2
}
```

The request is the complete crew, not a delta. `PUT` is used deliberately: the action states what the
Visit's crew **is**, which is what makes "remove the current Lead and choose the next one" one explicit
action the API can apply without deciding anything for the user (`BR-069`).

### 5.2 What the API enforces

- **Exactly one `LEAD`** and at least one technician (`BR-068`). A crew that does not name exactly one
  Lead is `400`.
- **Every technician must be an active member of the caller's organization**, otherwise `422
  TECHNICIANS_NOT_ASSIGNABLE` with the offending membership ids. Another organization's member is never
  assignable (`BR-001`).
- **`BR-070` applies here too**: assigning a technician to a Visit whose window overlaps another
  non-canceled Visit they are on is reported as a conflict before the crew is applied.
- A crew that does not change is not a change: nothing is written and the Visit keeps its version.

### 5.3 Recorded

One `visit_technician_history` row per change — `ASSIGNED`, `REMOVED` with the role that was held, or
`ROLE_CHANGED` carrying both roles — all with the same actor, which is `BR-069`'s shape. Assignment
history is append-only: a change never rewrites an earlier row.

## 6. `GET /technicians`

The organization's active technicians, for choosing a crew (`BR-024`, `BR-068`). Another organization's
technicians are never listed.

```json
[
  { "membershipId": "9a8b7c6d-…", "name": "Mike Lead" },
  { "membershipId": "1f2e3d4c-…", "name": null }
]
```

`name` is `null` when the member has no profile yet; the client presents that rather than a fabricated
name (`BR-020`). Detailed technician profile, skills, territory and availability are an **OPEN
QUESTION** (`BR-024`) and are not modelled here.

## 7. The Visit field lifecycle

The Visit's own lifecycle (`BR-074`, `BR-075`, `BR-077`, `BR-093`; `ADR-019` D3, D4, D5, D7). These routes
have **two authorizations**, and `BR-093` keeps them distinct:

- a caller admitted by a **field** capability is bounded by their own **current crew**: a Visit their crew
  does not include is answered **`404 VISIT_NOT_FOUND`**, never `403`, exactly as the Job read answers a
  Job the caller's assignments do not reach (`ADR-019` D2, D3). "Currently assigned" is evaluated at the
  moment of the action, so a technician a manager removed before the action is refused;
- a caller admitted by the **office** capability `JOB_UPDATE` writes the organization's Visits **without**
  crew membership, which is how an office member completes a Visit from Job Details (`BR-093`, `ADR-019`
  D7). The office capability is an alternative to crew membership, not a replacement for a destination's
  own capability: a completion still requires `VISIT_RECORD_OUTCOME` of every caller.

The Job Details projection reports the answer as `selectedVisit.fieldActionable`, and narrows
`selectedVisit.allowedStatusTransitions` to the destinations the caller may actually execute
(`docs/api/job-details.md` §3.2), so a client draws the action from the server's own answer instead of
offering one this route would refuse (`BR-041`, `BR-093`).

### 7.1 `PATCH /jobs/:id/visits/:visitId/status`

```json
{
  "status": "EN_ROUTE",
  "capturedAt": "2026-09-17T11:00:00.000Z",
  "clientOperationId": "55555555-5555-4555-8555-555555555555",
  "expectedVersion": 3
}
```

A completion is **not** a destination of this route: it is the explicit completion operation (`§7.6`),
which carries the outcome `BR-077` requires. `status: "COMPLETED"` is
`409 VISIT_STATUS_TRANSITION_NOT_ALLOWED`, and an `outcomeCode` or an `outcomeSummary` supplied here is
`400 VALIDATION_FAILED`, because this route records no outcome and must not tell a caller it did.

| Field               | Required | Meaning                                                                                    |
| ------------------- | -------- | ------------------------------------------------------------------------------------------ |
| `status`            | yes      | A **working** destination `BR-074` permits for the Visit's current status.                  |
| `clientOperationId` | no       | The device's idempotency key (§7.3).                                                        |
| `capturedAt`        | no       | The device instant the action happened; provenance beside the server's own `recordedAt`.     |
| `expectedVersion`   | no       | The Visit's `version` as the client last saw it (`BR-086`).                                  |
| `confirmConflicts`  | no       | Accepts the `BR-070` conflicts reported by a previous attempt.                               |

The answer is **the Job as it now stands** — the projection `GET /jobs/:id` returns
(`docs/api/job-details.md` §3) — so a client presents the backend's state, including the Visit's new
status and the destinations it now offers.

### 7.2 What the API enforces

- **`BR-074`'s free movement between the working statuses.** A Visit may be moved **directly** between
  any two working statuses — `SCHEDULED`, `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS` — in either direction,
  in one operation. It does not have to be advanced one step at a time and it may step backward;
  `EN_ROUTE → SCHEDULED` is additionally recorded as `BR-075`'s correction (`is_correction`). What this
  route does not take is standing still, `DRAFT`, and the terminal statuses: `DRAFT` is an unscheduled,
  internal Visit that is not work a technician is doing, `COMPLETED` is reached through the completion
  operation (`§7.6`), and `CANCELED` is a dispatch destination no capability authorizes for a field
  caller (`BR-066`). Anything else is `409 VISIT_STATUS_TRANSITION_NOT_ALLOWED` with `details.from`,
  `details.to` and `details.allowed`, and `details.allowed` is the full set the Visit may take.
  `BR-074` states that a `COMPLETED` Visit may be reopened; the API does not offer that turn today, so it
  remains an `OPEN QUESTION` (`§10`) and no client may invent one (`BR-042`).
- **`CANCELED` is refused.** `BR-066` makes cancellation a dispatch action, `BR-076` requires a structured
  cancellation reason, and no capability authorizes **any** caller — field or office — to take it, so
  this route does not take it and invents no vocabulary (`BR-042`, `BR-076`, `BR-093`). It stays truly
  terminal, and no working status is reachable from it.
- **`BR-072`'s conditions gate becoming `SCHEDULED`.** The Job must have a Property, the schedule must
  exist, at least one technician must be assigned with exactly one Lead, and the availability check must
  have run with any conflict explicitly confirmed. A Visit that fails one is `409
  VISIT_SCHEDULING_CONDITION_NOT_MET` with `details.reason` (`PROPERTY`, `SCHEDULE`, `CREW_LEAD`) — its
  own code, because the destination is structurally permitted and what refuses it is the Visit's own
  state (`BR-058`'s split between transitions and runtime eligibility).
- **A closed Job's field record is final.** A Visit whose Job is `COMPLETED` or `CANCELED` is refused with
  `409 JOB_CLOSED_FOR_FIELD_WORK`: `BR-062` and `BR-079` make the field record immutable once the office
  closes the Job, and the API fails closed rather than writing field history — outcomes included — under
  it (`ADR-019` D4).
  The refusal is **runtime eligibility**, so `selectedVisit.allowedStatusTransitions` keeps the Visit's
  **structural** destinations even for a closed Job, and the projection carries the closed fact
  separately in `readOnlyReason` (`docs/api/job-details.md` §3), which is what a client hides its field
  controls from. The list answers *which destinations this Visit has*, narrowed only by what the caller
  may execute (`BR-093`); it never answers *whether the Job is open* — the same split `BR-058` states for
  Jobs.
- **A stale `expectedVersion` is refused** with `409 VERSION_CONFLICT` carrying the Visit's current
  version, so a queued operation is never applied "as close as possible" (`BR-086`, `ADR-019` D5).

### 7.3 Idempotency and replay (`BR-031`, `ADR-019` D5)

The Visit write routes accept a client-generated `clientOperationId`. When one is supplied:

- a **repeat** of an operation the API already applied writes nothing further and answers with the state
  that attempt produced, so a queued action replayed after a timeout is applied exactly once;
- a key already used for **another Visit** is refused with `409 VISIT_OPERATION_REUSED`;
- the key is stored on the Visit's own history row, where the unique partial index
  (`organization_id`, `client_operation_id`) makes the guarantee a database one rather than a
  best-effort check.

Without a key there is no replay to recognise, so the request is evaluated against the Visit's current
state: a command that already happened is refused with the state it actually holds
(`VISIT_STATUS_TRANSITION_NOT_ALLOWED`, with `from` equal to `to`). That is the refuse-and-reconcile
answer a client keeps visible with the API's own reason and offers to discard (`BR-014`).

### 7.4 Recorded

One append-only `visit_status_history` row per applied transition: previous status, new status,
`is_correction`, the actor, the server's `recorded_at` and the client's `captured_at` and
`client_operation_id` when they were supplied, and the conflicts a scheduling move accepted. The actor is
the authenticated member who performed the action, whichever authorization admitted them, so an office
completion names the office member who performed it rather than the Visit's crew (`BR-033`, `BR-093`). A
**completion** (`§7.6`) additionally writes one `visit_outcome_history` row and sets the Visit's own
outcome columns in the same transaction, so `BR-077`'s "outcome before completion" is a property of the
record rather than of the request order.

The **Job consequence** `ADR-019` D4 decides is applied inside that same transaction:

| Visit event                                                                   | Job consequence                                                   | Rule                          |
| ----------------------------------------------------------------------------- | ----------------------------------------------------------------- | ----------------------------- |
| a working destination (`SCHEDULED`, `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`)      | `→ ACTIVE`                                                        | `BR-058`, `BR-074`            |
| `COMPLETED` with `RESOLVED`, and no other Visit still open                     | `→ COMPLETED`                                                     | `BR-058`, `BR-062`, `BR-078`  |
| `COMPLETED` with `RESOLVED` while another Visit is still open                  | stays `ACTIVE`                                                    | `BR-062`                      |
| `COMPLETED` with `NEEDS_FOLLOW_UP`, `NEEDS_PARTS` or `UNABLE_TO_COMPLETE`      | `→ ACTIVE`, or stays there                                        | `BR-058`, `BR-078`, tracker 051 |
| any destination while the Job is `COMPLETED` or `CANCELED`                     | none — the action is refused with `JOB_CLOSED_FOR_FIELD_WORK`      | `BR-079`, `ADR-019` D4        |
| `DRAFT`                                                                        | none                                                              | `BR-042`                      |

Each consequence is one recorded Job transition — an append-only `job_status_history` row with the new
status, the actor and the server's instant — and a status that does not change is not a transition, so
nothing is written when the Job already holds it (`BR-058`, `BR-067`). **A resolving completion is the one
place a field event closes a Job**, and it is the entry the tracker's model gives it: there is no
business-review step between the field work and the closure
(`docs/tracker/051-job-visit-lifecycle-redesign.md`). It is bounded by `BR-062`'s invariant, which the API
evaluates for the consequence exactly as it does for `PATCH /jobs/:id/status` (§3.2): a Job is never
`COMPLETED` while it still holds an open Visit. Nothing else about a Job's lifecycle moves on a field
event — a cancel, a reopen and an explicit close remain authorized office actions (`BR-062`, `BR-063`,
`BR-064`) — and nothing here changes a Visit's status, schedule or assignment (`BR-059`).

A `COMPLETED` Visit whose outcome is not `RESOLVED` therefore leaves its Job `ACTIVE`, and the office
learns that the work is unfinished from the **derived** attention the Job read and the manager home
report (`FOLLOW_UP_NEEDS_SCHEDULING`, `PARTS_REQUIRED`, `UNABLE_TO_COMPLETE`) — attention is not a Job
status, so no status is written for it (`BR-060`, `BR-078`, `docs/api/manager-home.md`). The follow-up
Visit request the office may raise instead of a Visit is its own contract
(`docs/api/visit-requests.md`, `BR-FV-001` – `BR-FV-013`).

### 7.5 `POST /jobs/:id/visits/:visitId/notes`

```json
{
  "body": "Customer not home; left a card.",
  "clientOperationId": "88888888-8888-4888-8888-888888888888",
  "capturedAt": "2026-09-17T13:00:00.000Z"
}
```

Adds one text update to the Visit and answers with the refreshed **Job Activity** timeline
(`docs/api/job-activity.md`), which is what this route has always returned. A note is append-only and
carries no version, so idempotency alone covers a repeat (`ADR-019` D5). Which capability reaches it,
and the scope each one carries, are §2's.

### 7.6 `POST /jobs/:id/visits/:visitId/completion`

The explicit Visit completion operation (`BR-077`, `BR-078`, `BR-093`; `ADR-019` D4, D7). Completing a
Visit records what the field attempt resulted in **and** ends it: the Visit becomes `COMPLETED`, its
outcome is stored, and the Job consequence `§7.4` describes is applied — all in one transaction.

```json
{
  "outcomeCode": "RESOLVED",
  "outcomeSummary": "Replaced the igniter and tested the unit.",
  "clientOperationId": "66666666-6666-4666-8666-666666666666",
  "capturedAt": "2026-09-17T14:00:00.000Z",
  "expectedVersion": 5
}
```

| Field               | Required | Meaning                                                                                                                        |
| ------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `outcomeCode`       | yes      | A code from the Visit outcome vocabulary (`BR-078`): `RESOLVED`, `NEEDS_FOLLOW_UP`, `NEEDS_PARTS` or `UNABLE_TO_COMPLETE`. A code Servora does not have is `400`. |
| `outcomeSummary`    | yes      | The text `BR-077` requires with the type. For `UNABLE_TO_COMPLETE` it is the **reason** the attempt could not be completed, and the refusal names that field as `reason`. |
| `clientOperationId` | no       | The device's idempotency key (`§7.3`), so a queued completion replays exactly once.                                             |
| `capturedAt`        | no       | The device instant the technician completed the Visit; provenance beside the server's own instant.                              |
| `expectedVersion`   | no       | The Visit's `version` as the client last saw it (`BR-086`).                                                                     |

What the API enforces:

- **The completion's own capability, of every caller.** The route is reached by
  `VISIT_UPDATE_ASSIGNED_STATUS` **or** the office capability `JOB_UPDATE` — the two authorizations
  `BR-093` keeps distinct — and `VISIT_RECORD_OUTCOME` is required **in addition**, the office caller
  included. A caller without it is `403`, and the projection reports the same answer as
  `selectedVisit.completionAllowed`, so a client draws the action from the server instead of offering one
  that would be refused (`BR-009`, `BR-007`).
- **The outcome is required.** A missing outcome code, or a missing text for it, is `400
  VALIDATION_FAILED`, so a Visit is never `COMPLETED` without a recorded result (`BR-077`).
- **Only a working Visit can be completed.** A Visit that is already `COMPLETED` or `CANCELED` — or still
  `DRAFT` — is refused with `409 VISIT_STATUS_TRANSITION_NOT_ALLOWED`, carrying the destinations the Visit
  really has. There is no reopen through this route either, which is the `OPEN QUESTION` `§10` records.
- **A Visit of a closed Job is refused** with `409 JOB_CLOSED_FOR_FIELD_WORK` (`§7.2`, `BR-079`).
- **Status history, outcome history and the Job consequence are written in one transaction**, decided
  against the Visit row locked in it, so an operation a device queued is never applied "as close as
  possible" (`BR-067`, `BR-086`, `ADR-019` D4, D5).

The answer is **the Job as it now stands** (`§1`), including the Visit's new status, its `outcomeCode` and
the Job status the consequence produced. `UNABLE_TO_COMPLETE`'s **structured** reason catalogue is an
`OPEN QUESTION`: the reason is recorded as the free text `BR-077` requires and the API derives no sub-code
from it (`BR-042`, `§10`).

## 8. Errors

| Status | Code                                 | When                                                                   |
| ------ | ------------------------------------ | ---------------------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`                  | The request body is not a valid action request.                        |
| `401`  | `UNAUTHENTICATED`                    | No usable session.                                                     |
| `403`  | `FORBIDDEN`                          | The caller does not hold the capability the route requires — including `VISIT_RECORD_OUTCOME` on a completion (§7.6). |
| `404`  | `JOB_NOT_FOUND`                      | The Job does not exist in the caller's organization.                   |
| `404`  | `VISIT_NOT_FOUND`                    | The Visit does not belong to that Job, or is not one the field caller's own assignments reach (`ADR-019` D2, D3). |
| `409`  | `JOB_STATUS_TRANSITION_NOT_ALLOWED`  | `BR-058` does not permit that destination.                             |
| `409`  | `JOB_COMPLETION_BLOCKED`             | `BR-062` does not allow completion: the Job still has an open Visit.   |
| `409`  | `JOB_CLOSED_FOR_FIELD_WORK`          | The Visit's Job is `COMPLETED` or `CANCELED`, so its field record is final (`BR-079`). |
| `409`  | `VISIT_NOT_RESCHEDULABLE`            | `BR-073` only permits rescheduling a `SCHEDULED` Visit.                |
| `409`  | `VISIT_STATUS_TRANSITION_NOT_ALLOWED`| `BR-074`/`BR-075` does not permit that Visit destination.               |
| `409`  | `VISIT_SCHEDULING_CONDITION_NOT_MET` | `BR-072`'s conditions for becoming `SCHEDULED` are not met.            |
| `409`  | `VISIT_OPERATION_REUSED`             | The idempotency key was already used for another Visit (`BR-031`).     |
| `409`  | `SCHEDULE_CONFLICT`                  | `BR-070` conflicts were detected and have not been confirmed.          |
| `409`  | `VERSION_CONFLICT`                   | The Job or Visit moved past the version the client supplied (`BR-086`).|
| `422`  | `TECHNICIANS_NOT_ASSIGNABLE`         | A technician in the requested crew cannot be assigned.                 |

## 9. What these routes never do

- Nothing outside the caller's organization: every query is scoped by `organization_id` (`BR-001`).
- Nothing for a Job whose Customer the organization has deleted: `BR-023` hides a deleted customer's
  Jobs, so the action answers `404`.
- No **office** route performs a Visit write. Advancing a Visit's field status, recording its outcome
  and adding notes are the technician's field lifecycle (§7), and a Job status change therefore leaves
  every Visit — its status, its schedule, its outcome and its history — exactly as it was (`BR-059`,
  `BR-067`). Conversely, no field route changes a Job's status itself: the consequence it may cause is
  the one recorded in §7.4, applied by the API (`BR-058`, `BR-059`).
- No Job cancellation **reason catalogue**: a cancellation is applied today with the request's optional
  `note` as its only explanation, because `BR-064`'s **structured** catalogue is still undefined. Nothing
  here invents one (`§3.2`, `§10`).
- No Visit cancellation on any route: `BR-066` makes it a dispatch action and no capability authorizes
  one, so it is not offered rather than refused by a hidden control (`BR-042`, `BR-007`).
- No step-by-step walk of the lifecycle: a status route either applies the destination it was given or
  refuses it, and never applies an intermediate status on the way (`BR-058`, `BR-067`).
- No client-side eligibility: whether a structurally permitted destination may be entered is decided
  by `BR-062` and `BR-072` at the API, and a client that hides or offers a destination does not change
  that answer (`BR-007`).

## 10. Open questions

Recorded rather than guessed (`BR-042`); `docs/tracker/018-android-job-actions.md` carries the same list
with what each one blocks.

1. **The `jobs.*` capability set** (`BR-006`, `BR-008`): every **office** action here requires the
   existing `JOB_UPDATE` capability until the Jobs feature defines its own, and whether the office should
   have a **dedicated** Visit capability — beside the completion's own `VISIT_RECORD_OUTCOME` — is part of
   the same question.
2. **`BR-064`'s Job cancellation reason catalogue** (`BR-064`). Cancellation **is** applied: it is a
   destination of §3, it cascades to the Job's open Visits, and the request's optional `note` is its only
   explanation. Whether a **structured** reason — and a mandatory one — becomes required is undecided, so
   no reason vocabulary is invented.
3. **`BR-065`'s confirmation for the cancellation cascade.** `BR-065` requires an explicit confirmation
   before an `ON_SITE` or `IN_PROGRESS` Visit is canceled by a Job cancellation. The API applies the
   cascade inside the Job's transaction, with no per-Visit confirmation, because no client surface for one
   is defined yet.
4. **Reopening a `COMPLETED` Visit** (`BR-074`). The rule permits a Visit completed by mistake, or whose
   work must continue, to be moved back into a working status. The API's structural table gives
   `COMPLETED` no destination and no client offers one, so the turn is **not implemented** — and no client
   may invent it (`BR-042`).
5. **The reopen destination of a terminal Job** (`BR-063`, tracker 051). The rule states
   `COMPLETED`/`CANCELED` → `NEW`; the API applies `ACTIVE`, on the tracker's reading that a Job with
   historical Visits has started execution and is therefore not `NEW` again. Product ownership must
   confirm which the rules should say.
6. **`UNABLE_TO_COMPLETE`'s structured reason catalogue** (`BR-078`). The reason is recorded as free text
   and the derived attention reports `reasonCode: null` for it, because no sub-code vocabulary is
   approved.
7. **Job Activity** (`BR-080`): the history these routes record (status, schedule, assignment, Visit
   transitions and outcomes) is what an Activity read projects, and its event vocabulary is defined in
   `docs/api/job-activity.md`.
8. **Whether an office member may perform a field action** — **answered and implemented on 2026-09-18**:
   `BR-093` makes the office capability `JOB_UPDATE` the Visit write routes' second authorization,
   without crew membership, while a completion keeps `VISIT_RECORD_OUTCOME` as its own question (§2, §7;
   `ADR-019` D7).
9. **The complete set of permitted Visit status corrections** (`BR-075`). `BR-074`'s free movement is
   applied in full, so a reversal needs no extra permission; which further turn should additionally be
   *flagged* as a correction is not confirmed, so `is_correction` stays the one pair `BR-075` named,
   `EN_ROUTE → SCHEDULED`.
10. **The technician self-edit window for an outcome** (`BR-079`). No outcome-correction route exists, so
    the window is not implemented and `BR-079`'s confirmed half — immutability once the Job is closed — is
    the only part in force.
11. **A paused or on-hold Visit state** (`BR-013`, `BR-074`). `BR-074` has no such state, so none is
    invented: a technician who stops working leaves the Visit `IN_PROGRESS`, and the Visit's own history
    is the record.
12. **`BR-079`'s immutability in the state `BR-062` prevents** — a terminal Job holding an active Visit.
    The API fails closed on it (§7.2) and gives the state no behaviour of its own (`BR-042`, `ADR-019`
    D4).
13. **The business-rules text itself** (tracker 051, Phase 1). **Landed on 2026-09-20**: `Business Rules.md`
    states the four-status Job lifecycle and the `NO_SHOW`-free Visit lifecycle, records `BR-061` as
    **REMOVED**, and carries the changed decisions under `BR-040`. It remains the governing document, so
    this item stays open only for future rule changes rather than for a backlog of retired vocabulary
    (`BR-040`).
