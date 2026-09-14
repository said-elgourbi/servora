# Servora API — `/home` Contract

**Status: IMPLEMENTED** for `GET /home/manager`.

This read is a **projection**: it derives the manager's operational day from the authoritative Job,
Visit, Customer and membership records and stores nothing. The derivation is defined in
`docs/domain/job-visit-domain-model.md` §8.4 and rests on `BR-060` (a Job with no active Visit),
`BR-061`/`BR-062` (a Job awaiting office review), and the derived overdue-Visit condition
(`BR-072`, `BR-074`).

References: `BR-001`, `BR-006`, `BR-007`, `BR-008`, `BR-010`, `BR-012`, `BR-023`, `BR-028`,
`BR-041`, `BR-042`, `BR-060`, `BR-061`, `BR-062`, `BR-068`, `BR-072`, `BR-074`, `BR-080`, `dev.md`
§7, `Project.md` §15, `docs/domain/job-visit-domain-model.md` §8.4,
`docs/tracker/016-android-manager-home.md`.

## 1. Conventions

JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15). The access token
is presented as `Authorization: Bearer <accessToken>`. Route paths carry no version prefix
(`docs/versioning.md` §7).

## 2. Permissions

| Route               | Permission       |
| ------------------- | ---------------- |
| `GET /home/manager` | `customers.view` |

**Why `customers.view` and not a `jobs.*` capability.** The read returns the organization's Jobs and
Visits, and `customers.view` is already the capability the API uses for exactly that data:
`GET /customers/:id/jobs` is guarded by it (`docs/tracker/010-customer-detail.md`), so a caller who
can open the manager home can already reach every Job and Visit it returns. The catalogue has **no**
`jobs.view` capability, and introducing one would be inventing a permission the Jobs feature has not
defined (`BR-006`, `BR-042`).

> **OPEN QUESTION (product ownership).** When the Jobs feature lands with its own capability set
> (`BR-008` names create/view/update/delete jobs as Manager defaults), this route — and
> `GET /customers/:id/jobs` — must be re-reviewed and guarded by whatever that feature defines.
> Recorded in `docs/tracker/016-android-manager-home.md` rather than guessed.

The backend is the authorization authority; a client that hides a section is not authorization
(`BR-007`).

## 3. `GET /home/manager`

The signed-in member's operational day: what needs their intervention, how the day is going, and
what is scheduled. One request serves the whole screen, so a client never coordinates several loosely
related reads to build it.

### 3.1 Query

| Parameter  | Required | Meaning                                                                                                                                                                                                                                                  |
| ---------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `timeZone` | no       | The IANA time-zone identifier the client renders in; the API resolves the local day from it. An omitted value resolves in `UTC`. A fixed offset a device reports (`GMT+05:30`) is accepted and normalised to `+05:30`. An unresolvable identifier is `400 VALIDATION_FAILED`. |

### 3.2 Response

```json
{
  "generatedAt": "2026-09-14T15:04:05.120Z",
  "day": {
    "timeZone": "America/Toronto",
    "start": "2026-09-14T04:00:00.000Z",
    "end": "2026-09-15T04:00:00.000Z"
  },
  "viewer": { "displayName": "Sarah Tremblay" },
  "attention": {
    "total": 3,
    "items": [
      {
        "kind": "VISIT_OVERDUE",
        "jobId": "…",
        "jobNumber": 1042,
        "jobTitle": "Furnace repair",
        "jobStatus": "SCHEDULED",
        "customerId": "…",
        "customerName": "ABC Property Management",
        "visitId": "…",
        "scheduledStart": "2026-09-14T11:00:00.000Z",
        "scheduledEnd": "2026-09-14T12:00:00.000Z"
      }
    ]
  },
  "today": { "total": 8, "completed": 3, "inProgress": 1, "upcoming": 4 },
  "visits": [
    {
      "visitId": "…",
      "visitStatus": "SCHEDULED",
      "scheduledStart": "2026-09-14T11:00:00.000Z",
      "scheduledEnd": "2026-09-14T12:00:00.000Z",
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
        { "membershipId": "…", "name": "Mike Johnson", "roleCode": "LEAD" }
      ],
      "overdue": false
    }
  ]
}
```

### 3.3 Fields

| Field                | Notes                                                                                                                                                                                                               |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `generatedAt`        | When the API assembled the answer. The client presents it; it never recomputes a condition from it.                                                                                                                  |
| `day`                | The half-open window `[start, end)` the day was resolved for, and the zone it was resolved in.                                                                                                                        |
| `viewer.displayName` | The signed-in member's name for the greeting. `null` when the member has no profile yet, so the client greets without a name rather than inventing one (`BR-020`).                                                     |
| `attention.kind`     | `VISIT_OVERDUE`, `JOB_PENDING_REVIEW` or `JOB_NEEDS_SCHEDULING` — a code, never display text (`BR-028`, `BR-041`).                                                                                                     |
| `attention.total`    | Every matching condition, including the ones beyond `items` (capped at 20). The section's count comes from here, so a capped list never under-reports the operation.                                                   |
| `attention.items`    | Ordered: what has gone wrong first, then what awaits the office, then what has to be planned; within a kind by scheduled time, then Job number. `visitId` and the schedule are `null` for a Job-level condition.        |
| `today`              | `completed + inProgress + upcoming = total`, over the Visits of `visits`.                                                                                                                                             |
| `visits`             | Today's Visits, excluding `CANCELED` and `NO_SHOW`. Ordered overdue → active → upcoming → completed, then by scheduled start and Job number.                                                                            |
| `visits[].address`   | The Visit's own preserved location, falling back to the Job's preserved address; `null` when neither exists (`BR-056`, `BR-057`).                                                                                     |
| `visits[].technicians` | The current assignments, Lead first (`BR-068`); empty when the Visit has none. `name` is `null` when the member has no profile yet.                                                                                  |
| `visits[].overdue`   | The derived overdue condition, decided by the API from the server clock, so a client presents it without comparing schedules against its own clock (`BR-001`).                                                          |

### 3.4 What the read never includes

- Nothing outside the caller's organization: every query is scoped by `organization_id` (`BR-001`),
  and the Customer join is scoped as well.
- Nothing for a Customer the organization has deleted (`customers.deleted_at` is set): `BR-023` hides
  a deleted customer's Jobs by default, and archiving a customer is how Servora deletes one.
- No Job priority, no ETA, no travel time and no location: none of them is a confirmed Servora rule
  (`BR-054`, `BR-038`).

## 4. Errors

| Status | Code                | When                                                     |
| ------ | ------------------- | -------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED` | `timeZone` names a zone the server cannot resolve.        |
| `401`  | `UNAUTHENTICATED`   | No usable session.                                        |
| `403`  | `FORBIDDEN`         | The caller does not hold `customers.view`.                |
| `5xx`  | —                   | Server failure; the client reports it and keeps its cache. |

## 5. Open questions

Recorded rather than guessed (`BR-042`); `docs/tracker/016-android-manager-home.md` carries the same
list with what each one blocks.

1. **"Technician running late"** has no definition. The read produces no such condition and no ETA;
   the overdue-Visit condition is the only time-based exception it derives, and it needs no travel or
   location data. Defining "running late" needs a travel/GPS decision (`BR-038`, `BR-070`).
2. **A Visit cancelled today** produces no attention condition: `visits` carries no cancellation
   instant of its own, and what "today" means for a cancellation is undefined.
3. **A manager-home capability of its own**: the read reuses `customers.view` until the Jobs feature
   defines `jobs.*` (`BR-006`, `BR-008`).
4. **A dedicated warning colour token** for the attention affordance does not exist in
   `docs/design/android-design-system.md`; the client tints it with the error role for now.
5. **An organization-wide "today"** is not modelled. The day is resolved from the zone the device
   reports, which is the manager's actual working day.
