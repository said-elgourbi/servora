# Servora API — `/jobs` Contract

**Status: IMPLEMENTED** for `GET /jobs/:id`.

This read is a **projection**: it derives one Job's details from the authoritative Job, Visit,
assignment, Customer and Property records and stores nothing. It follows the Job & Visit domain model
(`docs/domain/job-visit-domain-model.md` §5 Jobs, §6 Visits, §7 assignment and history), and it rests
on `BR-021` (a Job is a core entity), `BR-047` – `BR-052` (Job identity and number), `BR-058` (Job
status), `BR-068` (assignment is recorded on the Visit), `BR-072` (the Visit's internal schedule) and
`BR-081` (the selected Visit).

References: `BR-001`, `BR-006`, `BR-007`, `BR-021`, `BR-023`, `BR-028`, `BR-041`, `BR-042`, `BR-047`,
`BR-048`, `BR-051`, `BR-052`, `BR-056`, `BR-057`, `BR-058`, `BR-059`, `BR-068`, `BR-072`, `BR-074`,
`BR-080`, `BR-081`, `dev.md` §7, `Project.md` §15,
`docs/tracker/017-android-job-details.md`.

## 1. Conventions

JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15). The access token
is presented as `Authorization: Bearer <accessToken>`. Route paths carry no version prefix
(`docs/versioning.md` §7).

## 2. Permissions

| Route           | Permission       |
| --------------- | ---------------- |
| `GET /jobs/:id` | `customers.view` |

**Why `customers.view` and not a `jobs.*` capability.** The read returns the organization's Job and
the Visit that represents it, and `customers.view` is already the capability the API uses for exactly
that data: `GET /customers/:id/jobs` and `GET /home/manager` are guarded by it, so a caller who can
open a Job Details screen can already reach every record it returns. The catalogue has **no**
`jobs.view` capability, and introducing one would be inventing a permission the Jobs feature has not
defined (`BR-006`, `BR-042`).

> **OPEN QUESTION (product ownership).** When the Jobs feature lands with its own capability set
> (`BR-008` names create/view/update/delete jobs as Manager defaults), this route must be re-reviewed
> and guarded by whatever that feature defines. Recorded in
> `docs/tracker/017-android-job-details.md` rather than guessed.

This controller exposes the read here and the Job and Visit **actions** in the same module. Every Job
and Visit action is an explicit authorized action (`BR-066`, `BR-067`) and is specified in
`docs/api/job-actions.md`; the actions are guarded by the existing `JOB_UPDATE` capability for the same
reason this read reuses `customers.view`, and the same open question covers both.

> **OPEN QUESTION (product ownership).** The Job capability set — including what each action requires —
> is recorded in `docs/api/job-actions.md` §2 and `docs/tracker/018-android-job-actions.md` rather than
> guessed.

The backend is the authorization authority; a client that hides a section is not authorization
(`BR-007`).

## 3. `GET /jobs/:id`

One Job with the Visit that represents it and the technicians assigned to that Visit.

### 3.1 Response

```json
{
  "id": "6f1a8d1e-4b26-4f8f-9a34-2b7c9e0d5a11",
  "jobNumber": 1042,
  "title": "Furnace repair",
  "description": "Customer reports the furnace is not producing heat.",
  "typeCode": null,
  "status": "SCHEDULED",
  "allowedStatusTransitions": ["IN_PROGRESS"],
  "version": 3,
  "customerId": "0f0a6c2e-9d4b-4e6a-8f1c-3a2b1c4d5e6f",
  "customerName": "Martha Reynolds",
  "propertyId": "b7c1e2f3-4d5a-4b6c-8d9e-0f1a2b3c4d5e",
  "address": {
    "propertyName": "Cedar Lane Building",
    "addressLine1": "987 Cedar Lane",
    "addressLine2": null,
    "city": "Montreal",
    "province": "QC",
    "postalCode": "H3A 2T6",
    "country": "Canada"
  },
  "selectedVisit": {
    "id": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
    "status": "EN_ROUTE",
    "scheduledStart": "2026-09-14T13:00:00.000Z",
    "scheduledEnd": "2026-09-14T15:00:00.000Z",
    "version": 2,
    "reschedulable": false
  },
  "technicians": [
    {
      "membershipId": "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d",
      "name": "Mike Johnson",
      "roleCode": "LEAD"
    },
    {
      "membershipId": "3b4c5d6e-7f8a-4b9c-8d7e-6f5a4b3c2d1e",
      "name": "Sarah Moreau",
      "roleCode": "TECHNICIAN"
    }
  ]
}
```

### 3.2 Field notes

| Field        | Notes                                                                                                                                                                                                                        |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `status`     | The **Job's** lifecycle status, from the shared vocabulary (`BR-058`). Never the Visit's status and never a derived condition.                                                                                                 |
| `allowedStatusTransitions` | The **structurally** permitted destinations `BR-058` defines for this Job's current status, in vocabulary order. An open Job lists every other open status — forwards or backwards — and `COMPLETED`; a terminal Job lists only `NEW`, its reopen (`BR-063`). The client draws its status actions from this list rather than holding a second copy of the lifecycle (`BR-041`). The list is not filtered by a destination's *current* eligibility: `PENDING_REVIEW` (`BR-061`) and `COMPLETED` (`BR-062`) stay listed, and the API refuses an attempt the Job does not qualify for with that rule's own error. `CANCELED` is absent while `BR-064`'s cancellation-reason catalogue is an open question. `docs/api/job-actions.md` §3 explains both. |
| `version`    | The Job's version, echoed back by `PATCH /jobs/:id/status` so a change against stale state is refused rather than applied (`BR-086`).                                                                                          |
| `selectedVisit` | The single Visit that represents the Job, chosen exactly as the customer-detail Job row chooses it (`BR-081`): the earliest upcoming non-canceled Visit by scheduled start, otherwise the most recent past Visit by scheduled start. `null` when the Job has no Visit with a schedule (`BR-051`). |
| `selectedVisit.status` | The **represented Visit's** lifecycle status (`BR-074`). It is a different state machine from `status` (`BR-059`) and is never merged with it.                                                                      |
| `selectedVisit.scheduledStart` / `scheduledEnd` | The Visit's internal schedule, which is authoritative for dispatch and conflict detection (`BR-072`).                                                              |
| `selectedVisit.version` | The Visit's version, echoed back by the reschedule and assignment actions (`BR-086`).                                                                 |
| `selectedVisit.reschedulable` | Whether `BR-073` permits rescheduling this Visit, which it does only while the Visit is `SCHEDULED`. The rule is defined once here so a client does not re-implement the lifecycle. |
| `technicians` | The **represented Visit's** current assignments, Lead first (`BR-068`); `[]` when nobody is assigned. `name` is `null` when the member has no profile yet (`BR-020`).                                                          |
| `technicians[].roleCode` | `LEAD` or `TECHNICIAN`. Exactly one assigned technician is `LEAD` while a Visit has technicians (`BR-068`).                                                                                                       |
| `address`    | The Job's preserved property address snapshot (`BR-056`). `null` when the Job has no Property yet. A partly known snapshot keeps its absent parts as `null`.                                                                   |
| `jobNumber`  | The organization-scoped, human-readable number (`BR-052`). It is never a cross-system key.                                                                                                                                     |

**Assignment is Visit-scoped, and this read does not change that.** There is no `technicianId` on a
Job: `jobs` has no technician column, the crew is read from `visit_technicians`, and a Job's
technicians are the technicians assigned across its Visits (`BR-068`). A Job with several Visits
therefore shows the crew of the Visit that represents it, not of every Visit it ever had.

### 3.3 What the read never includes

- Nothing outside the caller's organization: every query is scoped by `organization_id` (`BR-001`).
- Nothing for a Job whose Customer the organization has deleted: `BR-023` hides a deleted customer's
  Jobs by default, so the route answers `404` rather than disclosing the Job.
- No Job deletion behaviour, no priority and no Job-level schedule: a Job has no priority (`BR-054`)
  and no scheduled date of its own — scheduling is performed on Visits (`BR-072`, `BR-081`).
- No Job Activity timeline. It is a confirmed read model of its own (`BR-080`) and is not part of this
  read; it is `GET /jobs/:id/activity` (`docs/api/job-activity.md`).

## 4. Errors

| Status | Code              | When                                                       |
| ------ | ----------------- | ---------------------------------------------------------- |
| `401`  | `UNAUTHENTICATED` | No usable session.                                         |
| `403`  | `FORBIDDEN`       | The caller does not hold `customers.view`.                 |
| `404`  | `JOB_NOT_FOUND`   | The Job does not exist in the caller's organization, or its Customer has been deleted. |
| `5xx`  | —                 | Server failure; the client reports it and offers a retry.  |

A Job another organization owns is `404`, never `403`: the API does not confirm that another
organization's Job exists (`BR-001`).

## 5. Open questions

Recorded rather than guessed (`BR-042`); `docs/tracker/017-android-job-details.md` carries the same
list with what each one blocks.

1. **The `jobs.*` capability set** (`BR-006`, `BR-008`): this read and `GET /home/manager` reuse
   `customers.view` until the Jobs feature defines its own capabilities. That feature also defines the
   Job and Visit **actions**, so no action is exposed before it exists.
2. **Job Activity** (`BR-080`): the unified, chronological read over a Job's status, notes, evidence,
   assignment and schedule events is a projection of its own, exposed as `GET /jobs/:id/activity` with
   the event vocabulary recorded in `docs/api/job-activity.md` (`docs/tracker/022-android-job-activity-timeline.md`).
3. **Job deletion** (`BR-021`): how deleting a Job relates to preserved business history is an open
   product question, so no deletion capability, route or behaviour is implied by this read.
4. **The Job category catalogue** (`BR-053`): `typeCode` is carried as stored and is not validated
   against a catalogue, because the catalogue is not defined. It never decides status, scheduling or
   permissions.
5. **Extra information on a Job** (`BR-027`, `BR-053`): a Job has a description but no notes field, and
   Visit notes live on the Visit (`visit_notes`). Notes and attachments are not part of this read.
