# Servora API — `/home/technician` Contract

**Status: IMPLEMENTED** for `GET /home/technician`.

This read is a **projection**: it derives one technician's working day from the authoritative Visit,
Job, assignment and Customer records and stores nothing. The content was decided by product
ownership on 2026-09-17 (`docs/decisions/019-technician-field-experience.md` D6) and is defined in
`docs/domain/job-visit-domain-model.md` §8.5. It rests on `BR-010` and `BR-012` (the technician's
screen answers *what do I need to do next?*), `BR-009` (the capability that authorizes it), `BR-068`
(assignment), `BR-072`/`BR-074` (the schedule and the statuses), and the derived overdue condition.

References: `BR-001`, `BR-004`, `BR-006`, `BR-007`, `BR-009`, `BR-010`, `BR-012`, `BR-013`, `BR-023`,
`BR-028`, `BR-041`, `BR-042`, `BR-060`, `BR-062`, `BR-068`, `BR-072`, `BR-074`, `BR-080`, `dev.md`
§7, `Project.md` §15, `docs/domain/job-visit-domain-model.md` §8.5,
`docs/tracker/037-technician-field-experience.md`.

## 1. Conventions

JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15). The access token
is presented as `Authorization: Bearer <accessToken>`. Route paths carry no version prefix
(`docs/versioning.md` §7).

The `timeZone` parameter and the local-day resolution are the manager home's own: both reads resolve
"today" through `api/src/home/home-day.ts`, so the two screens cannot disagree about which day they
describe (`BR-041`).

## 2. Permissions

| Route                | Permission             |
| -------------------- | ---------------------- |
| `GET /home/technician` | `VISIT_VIEW_ASSIGNED` |

`VISIT_VIEW_ASSIGNED` is the capability `BR-009` names for a technician's read of their own assigned
work, and it already exists in the catalogue (`api/drizzle/migrations/0004_woozy_spitfire.sql:75-78`,
granted to the default Technician role at `:104-118`). This read is its first enforcement
(`ADR-019` D1).

**The office read is a different route.** `GET /home/manager` is guarded by `customers.view` and
answers the organization's day; `customers.view` deliberately does **not** admit a caller here,
because every row this read returns belongs to one technician (`BR-010`, `BR-012`).

**The scope is the caller's own current assignments**, enforced in the service and never in a client
(`ADR-019` D2, `BR-001`, `BR-007`): a Visit the caller's membership is not on the crew of is not this
read's business and is absent from the payload rather than reported as forbidden — nothing here names
a resource the caller could probe for existence. The membership is the one the route was reached
with, resolved from the capability, never from a role name (`BR-004`, `BR-006`).

A member who holds the field capability *and* the office one still gets their own assignments here:
the scope is the caller's crew rows, never their permissions.

## 3. `GET /home/technician`

What one technician has to do: the next Visit, the day's own Visits, a preview of what comes after,
and the conditions on their own work. One request serves the whole screen, so a client never
coordinates several loosely related reads to build it.

### 3.1 Query

| Parameter  | Required | Meaning                                                                                                                                                                                                                                                  |
| ---------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `timeZone` | no       | The IANA time-zone identifier the client renders in; the API resolves the local day from it. An omitted value resolves in `UTC`. A fixed offset a device reports (`GMT+05:30`) is accepted and normalised to `+05:30`. An unresolvable identifier is `400 VALIDATION_FAILED`. |

### 3.2 Response

```json
{
  "generatedAt": "2026-09-17T15:04:05.120Z",
  "day": {
    "timeZone": "America/Toronto",
    "start": "2026-09-17T04:00:00.000Z",
    "end": "2026-09-18T04:00:00.000Z"
  },
  "viewer": { "displayName": "Mike Johnson" },
  "nextVisit": {
    "visitId": "…",
    "visitStatus": "EN_ROUTE",
    "scheduledStart": "2026-09-17T13:00:00.000Z",
    "scheduledEnd": "2026-09-17T14:00:00.000Z",
    "jobId": "…",
    "jobNumber": 1042,
    "jobTitle": "Furnace repair",
    "jobStatus": "SCHEDULED",
    "customerId": "…",
    "customerName": "ABC Property Management",
    "address": {
      "propertyName": "Cedar Lane Building",
      "addressLine1": "987 Cedar Lane",
      "addressLine2": null,
      "city": "Montreal",
      "province": "QC",
      "postalCode": "H3A 2T6",
      "country": "Canada"
    },
    "technicians": [
      { "membershipId": "…", "name": "Mike Johnson", "roleCode": "LEAD" },
      { "membershipId": "…", "name": "John Smith", "roleCode": "TECHNICIAN" }
    ],
    "overdue": false
  },
  "visits": [],
  "upcoming": [],
  "upcomingTotal": 0,
  "attention": { "total": 0, "items": [] }
}
```

### 3.3 Fields

| Field                 | Notes                                                                                                                                                                                                                                                                                                    |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `generatedAt`         | When the API assembled the answer. The client presents it; it never recomputes a condition from it.                                                                                                                                                                                                       |
| `day`                 | The half-open window `[start, end)` the day was resolved for, and the zone it was resolved in.                                                                                                                                                                                                           |
| `viewer.displayName`  | The signed-in member's name for the greeting. `null` when the member has no profile yet, so the client greets without a name rather than inventing one (`BR-020`).                                                                                                                                        |
| `nextVisit`           | The one Visit to do next, or `null` when no assigned Visit is left to do. Chosen by the **API**, not the client: work already started (`EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`, `BR-074`) comes first, then the nearest by scheduled start. It may be tomorrow's work when nothing remains today.             |
| `visits`              | Today's assigned Visits, **chronologically** (scheduled start, then Job number). A `CANCELED` or `NO_SHOW` Visit is excluded because the attempt did not happen; a `COMPLETED` Visit stays, because it is what the day has produced so far.                                                              |
| `upcoming`            | A **preview** of the assigned Visits whose scheduled start is at or after `day.end`: their status is not `CANCELED`, `NO_SHOW` or `COMPLETED`. Capped at five.                                                                                                                                            |
| `upcomingTotal`       | How many assigned Visits are after today, so a capped preview never under-reports the caller's workload. It is the manager home's `attention.total` shape, applied to the same problem.                                                                                                                  |
| `attention.total`     | Every condition on the caller's own work, including any beyond `items` (capped at 20).                                                                                                                                                                                                                    |
| `attention.items`     | `kind` is a code, never display text (`BR-028`, `BR-041`). `VISIT_OVERDUE` is the only kind today: an assigned Visit still `SCHEDULED` after its window ended. It is not restricted to the requested day, because an attempt that never happened yesterday is still work the technician has to resolve. |
| `visits[].address`    | The Visit's own preserved location, falling back to the Job's preserved address; `null` when neither exists (`BR-056`, `BR-057`).                                                                                                                                                                        |
| `visits[].technicians`| The current assignments, Lead first (`BR-068`); empty when the Visit has none. `name` is `null` when the member has no profile yet.                                                                                                                                                                       |
| `visits[].overdue`    | The derived overdue condition, decided by the API from the server clock, so a client presents it without comparing schedules against its own clock (`BR-001`).                                                                                                                                           |

Nothing here is a business state of its own: `nextVisit`, the day's membership, the preview and the
overdue condition are all derived from authoritative records (`BR-060`, `BR-072`, `BR-074`).

### 3.4 What the read never includes

- Nothing outside the caller's organization: every query is scoped by `organization_id` (`BR-001`),
  and the Customer join is scoped as well.
- No Visit the caller's membership is not on the current crew of, however it was reached
  (`ADR-019` D2).
- Nothing for a Customer the organization has deleted (`customers.deleted_at` is set): `BR-023` hides
  a deleted customer's Jobs by default, exactly as the manager home does.
- No Visit without a schedule: a `DRAFT` Visit the office has not scheduled yet has no time to be
  "next" at, and scheduling it is the office's work (`BR-060`, `BR-072`).
- No Job priority, no ETA, no travel time, no location and no labour figure: none of them is a
  confirmed Servora rule (`BR-034`, `BR-038`, `BR-054`).
- **No counts or KPIs of the operation.** The screen answers "what do I need to do next?", so it
  carries no organization-level summary (`BR-010`, `BR-012`); a section's own count is reported
  beside its list where a list is capped.


## 4. Errors

| Status | Code                | When                                                      |
| ------ | ------------------- | --------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED` | `timeZone` names a zone the server cannot resolve.         |
| `401`  | `UNAUTHENTICATED`   | No usable session.                                         |
| `403`  | `FORBIDDEN`         | The caller does not hold `VISIT_VIEW_ASSIGNED`.            |
| `5xx`  | —                   | Server failure; the client reports it and keeps its cache. |

A caller holding the capability with no assignment is **`200` with an empty screen**, not an error:
that is an honest answer rather than a refusal (`BR-042`).

## 5. Open questions

Recorded rather than guessed (`BR-042`); `docs/tracker/037-technician-field-experience.md` carries the
same list with what each one blocks.

1. **The full schedule surface.** `upcoming` is a capped preview because the Schedule destination is
   still a placeholder (`Figma/src/imports/pasted_text/servora-scheduler-design.md`). A real schedule
   read is its own feature.
2. **A pending follow-up request as a technician-facing condition** (`BR-FV-001` – `BR-FV-013`): not
   modelled, so the attention section cannot carry it yet.
3. **Evidence waiting to be synchronized** is a device-local fact (`BR-014`, `BR-031`) the API cannot
   see; whether the technician's home should report it is undecided, and the offline engine's own
   notices report it on the Job Details screen today.
4. **"Running late"** has no definition: defining it needs a travel/GPS decision
   (`BR-038`, `BR-070`).
5. **Technician eligibility, skills and territory** are out of scope in v1 (`BR-025`).
6. **The `jobs.*` capability set** (`BR-006`, `BR-008`): this read guards with the field capability
   `BR-009` already names, so nothing new was invented; it is re-reviewed when the Jobs feature
   defines its own set.

