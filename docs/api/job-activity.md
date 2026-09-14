# Servora API — `/jobs/:id/activity` Contract

**Status: IMPLEMENTED** for `GET /jobs/:id/activity`.

This read is a **projection**: it derives one Job's chronological activity from the append-only
histories the domain already keeps and stores nothing (`BR-001`, `BR-067`, `BR-080`). It follows
`BR-080` (Job Activity is a unified, derived read model ordered newest-first), `BR-041` (one shared
event vocabulary), `BR-028` (stable codes, localized labels) and `docs/domain/job-visit-domain-model.md`
§6 (the Visit sequence is a presentation label, never an identifier).

References: `BR-001`, `BR-006`, `BR-007`, `BR-020`, `BR-041`, `BR-042`, `BR-058`, `BR-067`, `BR-068`,
`BR-073`, `BR-074`, `BR-077`, `BR-078`, `BR-080`, `docs/tracker/022-android-job-activity-timeline.md`.

## 1. Conventions

JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15). The access token
is presented as `Authorization: Bearer <accessToken>`. Route paths carry no version prefix
(`docs/versioning.md` §7).

## 2. Permissions

| Route                    | Permission       |
| ------------------------ | ---------------- |
| `GET /jobs/:id/activity` | `customers.view` |

The activity is a projection of the same records `GET /jobs/:id` returns, so it is guarded by the same
capability. The catalogue has no `jobs.*` capability, and introducing one would invent a permission the
Jobs feature has not defined (`BR-006`, `BR-042`); the same open question recorded for the Job read
(`docs/api/job-details.md` §2) covers this read.

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

`fromStatus`/`toStatus` are the status codes the kind's vocabulary defines (`BR-058` for a Job,
`BR-074` for a Visit); the kind names which vocabulary the codes belong to.

### 3.3 The Visit sequence

`visitSequence` is `null` for a Job-level event and otherwise the Visit's **stable, human-readable
sequence** — `1` for the Job's first Visit, `2` for the second, and so on, by the Visit's creation
order within the Job. It is a presentation label only: it is derived, never stored, never an
identifier, and never a cross-system key (`BR-052`, `docs/domain/job-visit-domain-model.md` §6).

### 3.4 What the read never includes

- Nothing outside the caller's organization: every query is scoped by `organization_id` (`BR-001`).
- Nothing for a Job whose Customer the organization has deleted: `BR-023` hides a deleted customer's
  Jobs, so the route answers `404` rather than disclosing the Job.
- No event vocabulary outside the table above, and no `photos`/`audio`/`files`: those evidence kinds
  have no authoritative source yet (`BR-027`).
- No pagination: the read returns the whole history, newest-first. Ordering and row limits are
  presentation concerns; pagination is a later decision.

## 4. Errors

| Status | Code              | When                                                       |
| ------ | ----------------- | ---------------------------------------------------------- |
| `401`  | `UNAUTHENTICATED` | No usable session.                                         |
| `403`  | `FORBIDDEN`       | The caller does not hold `customers.view`.                 |
| `404`  | `JOB_NOT_FOUND`   | The Job does not exist in the caller's organization, or its Customer has been deleted. |
| `5xx`  | —                 | Server failure; the client reports it and offers a retry.  |

A Job another organization owns is `404`, never `403` (`BR-001`).

## 5. Open questions

Recorded rather than guessed (`BR-042`); `docs/tracker/022-android-job-activity-timeline.md` carries
the same list with what each one blocks.

1. **The `jobs.*` capability set** (`BR-006`, `BR-008`): this read reuses `customers.view` until the
   Jobs feature defines its own capabilities, exactly as `GET /jobs/:id` does.
2. **Evidence events** (`BR-027`): photos, audio and files have no authoritative tables yet, so no
   activity kind is invented for them.
3. **Pagination** (`BR-080`): the read returns the whole history; whether a long history needs
   paging is a later product decision.

