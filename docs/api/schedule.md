# Servora API — `/schedule` Contract

**Status: IMPLEMENTED** for `GET /schedule`.

This read is a **projection**: it derives one local day's schedule, the technician filter's options
and the work that still has no crew from the authoritative Job, Visit, assignment and membership
records, and stores nothing. The day resolution it uses is the same one the two home reads use
(`api/src/home/home-day.ts`), so no two screens can disagree about where a local day begins
(`BR-041`).

References: `BR-001`, `BR-006`, `BR-007`, `BR-023`, `BR-024`, `BR-028`, `BR-041`, `BR-042`, `BR-056`,
`BR-057`, `BR-062`, `BR-068`, `BR-071`, `BR-072`, `BR-074`, `BR-080`, `dev.md` §7, `Project.md` §15,
`docs/decisions/020-manager-schedule-and-unassigned-lane.md`,
`docs/tracker/038-android-manager-schedule.md`,
`docs/tracker/040-android-manager-schedule-density.md`.

## 1. Conventions

JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15). The access token
is presented as `Authorization: Bearer <accessToken>`. Route paths carry no version prefix
(`docs/versioning.md` §7).

## 2. Permissions

| Route           | Permission                                       |
| --------------- | ------------------------------------------------ |
| `GET /schedule` | `customers.view` **or** `VISIT_VIEW_ASSIGNED`     |

The route accepts **either** capability, and which one the caller holds is then resolved into the
**scope** of the read:

| Scope          | Held capability         | What the day holds                                          |
| -------------- | ----------------------- | ----------------------------------------------------------- |
| `ORGANIZATION` | `customers.view`        | The operation's day, narrowed by the technician filter when one is named. |
| `SELF`         | `VISIT_VIEW_ASSIGNED`   | Only the Visits the caller's own membership is currently on. |

`customers.view` is the capability the API already uses for the organization's Job and Visit data
(`GET /customers/:id/jobs`, `GET /home/manager`), and `VISIT_VIEW_ASSIGNED` is the one `BR-009` names
for a technician's own assigned work. Reusing them keeps the schedule inside the existing permission
model instead of hand-writing a `schedule.*` set the Jobs feature has not defined (`BR-006`,
`BR-042`).

**The scope is enforced by the service, never by the client** (`BR-001`, `BR-007`):

- a `SELF` caller reads their own membership, whatever the request asks for;
- a `membershipId` outside the resolved scope is refused with `403` — including an id that names
  nobody, so the refusal cannot be used to learn who the organization employs (`BR-042`, `BR-044`);
- a `SELF` answer carries **no** unassigned lane and **no** technician options, because neither is a
  question that scope answers (`BR-009`).

The scope the read was resolved for travels back in `scope`, so a client presents the work the API
authorized rather than deciding a scope for itself (`BR-041`).

> **OPEN QUESTION (product ownership).** When the Jobs feature lands with its own capability set
> (`BR-008` names create/view/update/delete jobs as Manager defaults), this route must be re-reviewed
> and guarded by whatever that feature defines — the same review `GET /home/manager` carries.

> **OPEN QUESTION (product ownership).** A **team** scope — a field member reading a colleague's
> schedule — has no capability in the catalogue that could authorize it: `VISIT_VIEW_ASSIGNED` is by
> definition the caller's *own* assignments, and `TECHNICIAN_VIEW` authorizes the technician
> **records** (the directory an assignee is chosen from), not another member's work. Nothing here
> invents one (`BR-042`). Until product ownership decides, the only way to read another technician's
> day is the office capability, through the `ORGANIZATION` scope; `docs/domain/job-visit-domain-model.md`
> §21 carries the question with what it blocks.

The backend is the authorization authority; a client that hides a tab is not authorization
(`BR-007`).

## 3. `GET /schedule`

One local day's schedule, the technician filter's options, and the work that still needs a crew.

### 3.1 Query

| Parameter      | Required | Meaning                                                                                                                                                                                                                    |
| -------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `date`         | no       | The local calendar date to read, as `YYYY-MM-DD`. An omitted value answers for **today in the submitted zone**, which is the day `GET /home/manager` answers for. A value that is not a real calendar date is `400 VALIDATION_FAILED`. |
| `timeZone`     | no       | The IANA time-zone identifier the client renders in; the API resolves the day's instants from it. An omitted value resolves in `UTC`. A fixed offset a device reports (`GMT+05:30`) is accepted and normalised. An unresolvable identifier is `400`. |
| `membershipId` | no       | The organization membership the day is narrowed to (`BR-068`). **Repeatable**: `?membershipId=a&membershipId=b` narrows the day to several technicians at once, and an omitted value (or one empty value) answers for the whole organization. A value that is not a UUID is `400 VALIDATION_FAILED`. |

The parameters are defined once (`api/src/home/home-query.ts`, `api/src/schedule/schedule-query.ts`)
so a request that names a day Servora cannot resolve is refused rather than silently answered for a
different one (`dev.md` §7).

Repeating `membershipId` is one **question with several names in it**, not several questions: the ids
are validated and de-duplicated together, the order is the caller's, and the read is answered once.
An id that is not a UUID refuses the whole request, so a filter never narrows to something other than
what was asked.

### 3.2 Response

```json
{
  "generatedAt": "2026-09-07T12:00:00.000Z",
  "day": {
    "localDate": "2026-09-07",
    "timeZone": "America/Toronto",
    "start": "2026-09-07T04:00:00.000Z",
    "end": "2026-09-08T04:00:00.000Z"
  },
  "scope": { "kind": "ORGANIZATION", "membershipId": "9a1c…" },
  "technicians": [{ "membershipId": "9a1c…", "name": "Mike Johnson" }],
  "visits": [
    {
      "visitId": "4f2b…",
      "visitStatus": "SCHEDULED",
      "scheduledStart": "2026-09-07T13:00:00.000Z",
      "scheduledEnd": "2026-09-07T14:30:00.000Z",
      "jobId": "b70e…",
      "jobNumber": 1042,
      "jobTitle": "Furnace repair",
      "customerId": "2d51…",
      "customerName": "ABC Property Management",
      "address": {
        "propertyName": null,
        "addressLine1": "987 Cedar Lane",
        "addressLine2": null,
        "city": "Montreal",
        "province": "QC",
        "postalCode": "H3A 2T6",
        "country": "CA"
      },
      "technicians": [{ "membershipId": "9a1c…", "name": "Mike Johnson", "roleCode": "LEAD" }],
      "overdue": false
    }
  ],
  "unassigned": {
    "total": 3,
    "items": [
      {
        "visitId": "77ab…",
        "visitStatus": "DRAFT",
        "scheduledStart": null,
        "scheduledEnd": null,
        "jobId": "c1de…",
        "jobNumber": 1051,
        "jobTitle": "Boiler service",
        "customerId": "2d51…",
        "customerName": "ABC Property Management",
        "address": null,
        "technicians": [],
        "overdue": false
      }
    ]
  }
}
```

### 3.3 Field semantics

| Field                   | Meaning                                                                                                                                                                                                                                                                        |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `scope`                 | The scope the read was resolved for, and the caller's own membership (`BR-006`, `BR-009`). `kind` is `ORGANIZATION` or `SELF`; stable code, never display text (`BR-028`).                                                                                                     |
| `day`                   | The local calendar day the read was resolved for: the `YYYY-MM-DD` date it was resolved from, the zone it was resolved in, and the half-open instant window `[start, end)` that date covers.                                                                                     |
| `technicians`           | The organization's technicians, for the screen's filter (`BR-024`, `BR-068`). The same list `GET /technicians` returns, carried here so the screen is one request. It is **not** day-scoped. **Empty for a `SELF` scope**: a field caller reads their own work and may not narrow the day to anybody (`BR-009`). |
| `visits`                | The day's Visits, chronologically by scheduled start and then by Job number (`BR-052`). Every row has a schedule: a Visit with no schedule belongs to no day. For a `SELF` scope they are only the Visits the caller's own membership is currently on.                          |
| `visits[].visitStatus`  | The **Visit** status (`BR-074`). The Job's status (`BR-058`) is deliberately not carried: they are separate state machines (`BR-059`), and the schedule is about the field attempt.                                                                                             |
| `visits[].address`      | The Visit's own preserved location, falling back to the Job's preserved address; `null` when neither exists (`BR-056`, `BR-057`).                                                                                                                                               |
| `visits[].technicians`  | The current assignments, Lead first (`BR-068`); empty when the Visit has none. `name` is `null` when the member has no profile yet (`BR-020`).                                                                                                                                  |
| `visits[].overdue`      | The derived overdue condition (`BR-072`, `BR-074`): a Visit still `SCHEDULED` after its scheduled window ended, decided by the API from the server clock so a client presents it without comparing schedules against its own clock (`BR-001`).                                  |
| `unassigned`            | The organization's open Visits that have no technician assigned (`BR-068`), nearest arranged work first and an attempt with no agreed time last. `total` counts every such Visit, including any beyond the returned `items` (`SCHEDULE_UNASSIGNED_LIMIT`, 20). **`null` for a `SELF` scope**: the lane is the office's question and that scope has none, so `null` means "not this read's question" rather than "nothing is waiting" (`BR-009`, `BR-042`). |


### 3.4 The two sets the read answers with

**The day's schedule** — the Visits whose `scheduled_start` falls inside the requested local day,
excluding the attempts that did not happen:

```text
visits.scheduled_start ∈ [day.start, day.end)
AND visits.status NOT IN ('CANCELED', 'NO_SHOW')   -- NOT_DAY_WORK_VISIT_STATUSES
```

A `CANCELED` or `NO_SHOW` Visit is an attempt that did not happen, so it is absent from the day and
from its order — the same classification `GET /home/manager` uses (`BR-074`). A `COMPLETED` Visit
stays: it is what the day has produced so far. When `membershipId` names one or more technicians, the
day is narrowed to the Visits those memberships are **currently** assigned to (`BR-068`). Several ids
are a **union** — a Visit is kept when **any** of them is on it:

```text
EXISTS (visit_technicians WHERE visit_id = visits.id AND technician_membership_id IN (…))
```

A dispatch filter asks "whose work am I looking at", not "which Visits do these people share", so a
Visit is never dropped for having only one of the selected technicians on it. One `EXISTS` with an `in`
list answers the whole group, so a filter over several technicians is one query inside the tenant
scope rather than one query per technician. The whole organization is the same read with no ids in it,
so clearing the filter is the absence of the predicate rather than a list of everybody.

**The unassigned lane** — a Visit that is still open and has nobody on it:

```text
visits.status NOT IN ('COMPLETED', 'CANCELED', 'NO_SHOW')   -- HISTORICAL_VISIT_STATUSES
AND NOT EXISTS (visit_technicians for the Visit)
```

This is the absence of an assignment rather than a status (`BR-042`): `BR-071`/`BR-072` explicitly
allow a Visit to exist with no technicians while it is being arranged, `BR-068` records assignment on
the Visit, and `BR-074`'s historical set is what "still needs somebody" excludes. The lane is
deliberately **not day-scoped**: a Visit with no agreed time belongs to no day, and work waiting for
a crew is waiting whoever's day it lands on. It is never narrowed by `membershipId` — with one
technician or several — because a filter by technician cannot select work that has no technician.

An attempt in the lane carries `scheduledStart: null` when nobody has agreed a time for it, and the
client presents that state rather than a time nobody agreed to.

**A `SELF` scope never carries the lane at all** — `unassigned` is `null` and the read answers no
unassigned Visits. The lane asks "what is waiting for a technician?", which is the office's question
about work nobody has been given, and `BR-009` scopes a field caller to their own assigned Visits
(`BR-042`). It is therefore not "an empty lane": an empty lane says nothing is waiting, and `null`
says the question does not belong to this read.

### 3.5 What the read never includes

- Nothing outside the caller's organization: every query is scoped by `organization_id` (`BR-001`),
  and the Customer join is scoped as well.
- Nothing for a Customer the organization has deleted (`customers.deleted_at` is set): `BR-023` hides
  a deleted customer's Jobs by default.
- No Job priority, no ETA, no travel time and no location: none of them is a confirmed Servora rule
  (`BR-054`, `BR-038`).
- No counts or KPIs beyond the unassigned lane's own total: the screen is a dispatch view, not a
  dashboard (`BR-012`).

## 4. Errors

| Status | Code                | When                                                      |
| ------ | ------------------- | --------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED` | `date`, `timeZone` or `membershipId` cannot be resolved.    |
| `401`  | `UNAUTHENTICATED`   | No usable session.                                         |
| `403`  | `FORBIDDEN`         | The caller holds neither capability, or names a technician outside the scope the read was resolved for. |
| `5xx`  | —                   | Server failure; the client reports it and keeps its state.  |

An authorized caller with nothing scheduled is **`200` with an empty `visits`** rather than an error:
that is an honest answer rather than a refusal (`BR-042`).

The `403` for an out-of-scope `membershipId` is deliberately uniform — the same status and code for a
colleague who exists and for an id that names nobody — so it cannot be used to discover who the
organization employs (`BR-042`, `BR-044`).

## 5. Open questions

Recorded rather than guessed (`BR-042`); `docs/tracker/038-android-manager-schedule.md` and
`docs/tracker/041-android-technician-schedule.md` carry the same list with what each one blocks.

1. **Whether the unassigned lane should be day-scoped.** It is not, because a Visit with no agreed
   time has no day to belong to. If product ownership decides the lane belongs to the selected day,
   the lane's query changes — not the shape of the payload.
2. **What "unassigned" excludes.** The lane is "open Visit with no current assignment". Whether a
   Visit the office has deliberately left crew-less for a future day should also be excluded is a
   product decision, not an inference from the schema (`BR-042`).
3. **The `jobs.*` capability set** (`BR-006`, `BR-008`): this read reuses the existing capabilities
   until the Jobs feature defines its own, and is re-reviewed then.
4. **A field member's visibility of another technician's schedule** (a **team** scope). No capability
   in the catalogue authorizes it: `VISIT_VIEW_ASSIGNED` is the caller's own assignments
   (`BR-009`) and `TECHNICIAN_VIEW` is the technician *directory* an assignee is chosen from
   (`BR-024`), not another member's work. Until product ownership decides, a `SELF`-scoped caller
   reads only their own day and a request for anybody else's is refused with `403`. What it blocks:
   the technician Schedule screen offers a technician selector only for a scope that admits other
   technicians, so the selector stays absent for a field-only session. `docs/domain/job-visit-domain-model.md`
   §21 carries the same question.
5. **Availability conflicts on the day.** `BR-070` defines the warning a *scheduling* action shows.
   This read reports no conflict state: presenting one here would be a second definition of a rule
   that belongs to the action that schedules.
6. **Per-day work indicators on a week.** The design's "subtle indicators for dates containing
   scheduled work" would need a week-scoped count read or one read per day; neither is implemented,
   and the technician screen does not pretend to have one.

