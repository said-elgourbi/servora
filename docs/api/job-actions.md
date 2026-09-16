# Servora API — Job & Visit Actions

**Status: IMPLEMENTED** for the Job and Visit management actions.

These routes are the explicit, authorized actions `BR-058` – `BR-079` define. Each one is performed by
an authenticated member as a deliberate decision, each one is recorded in append-only history
(`BR-067`), and the API is the authority for whether the action is allowed at all (`BR-001`, `BR-007`).

References: `BR-001`, `BR-006`, `BR-007`, `BR-008`, `BR-041`, `BR-042`, `BR-047`, `BR-058`, `BR-059`,
`BR-060`, `BR-061`, `BR-062`, `BR-063`, `BR-066`, `BR-067`, `BR-068`, `BR-069`, `BR-070`, `BR-072`,
`BR-073`, `BR-074`, `BR-077`, `BR-079`, `BR-083`, `BR-086`, `dev.md` §7, `Project.md` §15,
`docs/domain/job-visit-domain-model.md` §6 – §7, `docs/tracker/018-android-job-actions.md`,
`docs/tracker/034-manager-job-status-workflow.md`.

## 1. Conventions

JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15). The access token is
presented as `Authorization: Bearer <accessToken>`. Route paths carry no version prefix.

Every action answers with **the Job as it now stands** — the same projection `GET /jobs/:id` returns
(`docs/api/job-details.md` §3) — so a client presents the backend's state instead of patching a local
copy (`BR-001`).

A client that also presents **Job Activity** (`BR-080`) therefore reads `GET /jobs/:id/activity` again
once an action has been applied: the action's answer is the Job, not the timeline, and the history these
actions record (status, schedule, assignment) is what that read projects. The action itself is never
asked to return the timeline, so the contract here is unchanged.

## 2. Permissions

| Route                                        | Permission        |
| -------------------------------------------- | ----------------- |
| `PATCH /jobs/:id/status`                     | `JOB_UPDATE`      |
| `PATCH /jobs/:id/visits/:visitId/schedule`   | `JOB_UPDATE`      |
| `PUT /jobs/:id/visits/:visitId/technicians`  | `JOB_UPDATE`      |
| `GET /technicians`                           | `TECHNICIAN_VIEW` |

**Why `JOB_UPDATE` and not a `jobs.*` capability set.** `BR-008` confirms that the Manager default role
updates Jobs, and the foundation catalogue already carries a `JOB_UPDATE` capability granted to that
role. The Jobs feature has not defined its own `resource.action` capability set, so introducing
`jobs.update` / `jobs.cancel` codes here would invent capabilities product ownership has not defined
(`BR-006`, `BR-042`). This is the same interim-authorization pattern the Job read already follows,
where `GET /jobs/:id` is guarded by the existing `customers.view`.

> **OPEN QUESTION (product ownership).** The Job capability set, and the capability each Visit action
> (reschedule, assign) should require in its own right. Until it exists, every action here requires
> `JOB_UPDATE`, which never grants more than the catalogue already does. Recorded in
> `docs/tracker/018-android-job-actions.md`.

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
- **Any permitted destination is one operation.** An open Job moves directly to any other open status
  — forwards or backwards — or to `COMPLETED`, in a single request and a single recorded transition.
  A client never reaches a destination by issuing a series of transitions.
- **`COMPLETED` and `CANCELED` are terminal.** Their only destination is `NEW`, the explicit reopen
  (`BR-063`). `NEW` is not a destination for an open Job.
- **`BR-061` before `PENDING_REVIEW`.** The Job may await review only when no Visit is active
  (`SCHEDULED`, `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`) and the latest completed Visit's outcome is
  `RESOLVED`. Otherwise `409 JOB_REVIEW_CONDITION_NOT_MET` with `details.reason` either `ACTIVE_VISIT`
  or `OUTCOME`. This is **runtime eligibility, not a structural limit**: `PENDING_REVIEW` stays in
  `allowedStatusTransitions`, a client offers it, and the API answers the attempt.
- **`BR-062` before `COMPLETED`.** The Job must have no open Visit — no Visit whose status is anything
  other than `COMPLETED`, `CANCELED` or `NO_SHOW` (`BR-074`; the classification `BR-083` uses). A Job
  whose Visits are all historical, or that has no Visit at all, may be closed; a `SCHEDULED`,
  `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS` or `DRAFT` Visit blocks it. Otherwise `409
  JOB_COMPLETION_BLOCKED`. Like `BR-061` this is runtime eligibility, so `COMPLETED` stays in
  `allowedStatusTransitions`; a client never infers the Visit state and hides the destination itself.
- **The eligibility conditions are evaluated inside the transaction that applies the change**, through
  the same client, so a Job is never moved on a picture of its Visits that has already changed
  underneath.
- **`BR-064` is not applied.** Cancellation is a transition `BR-058` permits, but `BR-064` requires a
  **structured** cancellation reason whose catalogue product ownership has not defined. The API
  refuses it with `409 JOB_CANCELLATION_UNAVAILABLE` rather than inventing the reason vocabulary
  (`BR-042`). Cancellation is also absent from the read's `allowedStatusTransitions`, so a client does
  not draw an action the API would refuse.
- **Closing a Job is an explicit office action** (`BR-062`): `PENDING_REVIEW` → `COMPLETED` is applied
  as asked, Visit outcomes become immutable from then on (`BR-079`), and a client confirms a
  consequential change with the user before sending it — a confirmation is never a substitute for the
  open-Visit invariant above.
- **Reopening never produces `IN_PROGRESS`** (`BR-063`): `COMPLETED`/`CANCELED` → `NEW`.
- **A Job status change moves the Job alone.** It never writes a Visit's status, a Visit's history or
  any other Visit record (`BR-059`, `BR-067`).

### 3.3 Recorded

One append-only `job_status_history` row: previous status, new status, the optional note, the actor and
the timestamp. A direct move records **one** row, however many statuses it skipped. The Job's `version`
is incremented in the same transaction.

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

## 7. Errors

| Status | Code                                | When                                                                   |
| ------ | ----------------------------------- | ---------------------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`                 | The request body is not a valid action request.                        |
| `401`  | `UNAUTHENTICATED`                   | No usable session.                                                     |
| `403`  | `FORBIDDEN`                         | The caller does not hold the capability the route requires.            |
| `404`  | `JOB_NOT_FOUND`                     | The Job does not exist in the caller's organization.                   |
| `404`  | `VISIT_NOT_FOUND`                   | The Visit does not belong to that Job in the caller's organization.    |
| `409`  | `JOB_STATUS_TRANSITION_NOT_ALLOWED` | `BR-058` does not permit that destination.                             |
| `409`  | `JOB_CANCELLATION_UNAVAILABLE`      | `BR-064`'s cancellation reason catalogue is not defined.               |
| `409`  | `JOB_REVIEW_CONDITION_NOT_MET`      | `BR-061`'s entry conditions for `PENDING_REVIEW` are not met.          |
| `409`  | `JOB_COMPLETION_BLOCKED`            | `BR-062` does not allow completion: the Job still has an open Visit.   |
| `409`  | `VISIT_NOT_RESCHEDULABLE`           | `BR-073` only permits rescheduling a `SCHEDULED` Visit.                |
| `409`  | `SCHEDULE_CONFLICT`                 | `BR-070` conflicts were detected and have not been confirmed.          |
| `409`  | `VERSION_CONFLICT`                  | The Job or Visit moved past the version the client supplied (`BR-086`).|
| `422`  | `TECHNICIANS_NOT_ASSIGNABLE`        | A technician in the requested crew cannot be assigned.                 |

## 8. What these routes never do

- Nothing outside the caller's organization: every query is scoped by `organization_id` (`BR-001`).
- Nothing for a Job whose Customer the organization has deleted: `BR-023` hides a deleted customer's
  Jobs, so the action answers `404`.
- No Visit status transition, and no Visit write of any kind. Advancing a Visit's field status,
  recording its outcome and adding notes are the technician's field lifecycle (`BR-074`, `BR-077`) and
  are not part of the office actions these routes perform. A Job status change therefore leaves every
  Visit — its status, its schedule, its outcome and its history — exactly as it was (`BR-059`, `BR-067`).
- No Job cancellation while `BR-064`'s reason catalogue is undefined.
- No step-by-step walk of the lifecycle: a status route either applies the destination it was given or
  refuses it, and never applies an intermediate status on the way (`BR-058`, `BR-067`).
- No client-side eligibility: whether a structurally permitted destination may be entered is decided
  by `BR-061` and `BR-062` at the API, and a client that hides or offers a destination does not change
  that answer (`BR-007`).

## 9. Open questions

Recorded rather than guessed (`BR-042`); `docs/tracker/018-android-job-actions.md` carries the same list
with what each one blocks.

1. **The `jobs.*` capability set** (`BR-006`, `BR-008`): every action here requires the existing
   `JOB_UPDATE` capability until the Jobs feature defines its own.
2. **`BR-064`'s Job cancellation reason catalogue**: until it exists, cancellation is refused rather
   than given an invented reason vocabulary.
3. **`BR-061` and the `NEEDS_QUOTE_APPROVAL` outcome**: its Job-status effect is undecided, so it is not
   treated as resolvable and `PENDING_REVIEW` is refused for it.
4. **Job Activity** (`BR-080`): the history these routes record (status, schedule, assignment) is what
   an Activity read will project, but its event vocabulary is its own open question.
