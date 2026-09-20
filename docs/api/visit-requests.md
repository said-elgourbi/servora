# Visit Requests API

Date: 2026-09-19

Follow-up Visit requests are proposals for another field attempt on an existing Job. They are not
Visits and must not be rendered as confirmed appointments until approved.

## Permissions

| Capability | Meaning |
| ---------- | ------- |
| `schedule.view_org` | View the organization dispatch schedule. |
| `VISIT_VIEW_ASSIGNED` | View the caller's assigned Visits. |
| `visits.request_follow_up` | Submit a follow-up Visit request. |
| `visits.review_requests` | Ask for clarification, reject, or review a request. |
| `visits.create_schedule` | Directly create and schedule a Visit, and approve a request into a Visit. |
| `visits.update_schedule` | Schedule or reschedule an existing Visit. |
| `visits.assign_technicians` | Assign technicians to a Visit. |

Default seeded managers hold the organization schedule, direct scheduling, assignment, and review
capabilities. Default seeded technicians hold assigned-Visit capabilities and `visits.request_follow_up`.

## Lifecycle

Statuses are stable codes:

| Status | Meaning |
| ------ | ------- |
| `PENDING` | Submitted and awaiting review. |
| `NEEDS_CLARIFICATION` | Reviewer needs more information. |
| `APPROVED` | Approved and associated to exactly one created Visit. |
| `REJECTED` | Closed without creating a Visit. |

Review payloads may carry `expectedStatus` and `expectedVersion`. If the request changed since the
client read it, the API returns `409 FOLLOW_UP_VISIT_REQUEST_CONFLICT`.

## Routes

| Method | Route | Permission | Result |
| ------ | ----- | ---------- | ------ |
| `GET` | `/jobs/visit-requests` | `visits.review_requests` or `visits.request_follow_up` | Reviewers see organization requests; requesters see their own. |
| `POST` | `/jobs/:id/visit-requests` | `visits.request_follow_up` | Creates a request on the existing Job. |
| `POST` | `/jobs/:id/visit-requests/:requestId/clarification` | `visits.review_requests` | Moves an open request to `NEEDS_CLARIFICATION`. |
| `POST` | `/jobs/:id/visit-requests/:requestId/rejection` | `visits.review_requests` | Moves an open request to `REJECTED`. |
| `POST` | `/jobs/:id/visit-requests/:requestId/approval` | `visits.review_requests` and `visits.create_schedule` | Creates one scheduled Visit on the same Job and marks the request `APPROVED`. |
| `POST` | `/jobs/:id/visits` | `visits.create_schedule` | Directly creates and schedules another Visit on the existing Job. |

Approval is transactional: the request row is locked, the Visit is inserted, its schedule and crew
history are recorded, and `createdVisitId` is written back to the request in one transaction. A retry of
an already-approved request returns the Job with the already-created Visit rather than creating another.

## Request Shapes

Submit request:

```json
{
  "sourceVisitId": "optional-uuid",
  "proposedStart": "2026-09-21T14:00:00Z",
  "proposedEnd": "2026-09-21T16:00:00Z",
  "reason": "Need to return with a replacement part.",
  "sameTechnicianPreferred": true
}
```

Approve or directly create:

```json
{
  "scheduledStart": "2026-09-21T14:00:00Z",
  "scheduledEnd": "2026-09-21T16:00:00Z",
  "technicians": [
    { "membershipId": "uuid", "roleCode": "LEAD" }
  ],
  "confirmConflicts": false,
  "expectedStatus": "PENDING",
  "expectedVersion": 1,
  "note": "Approved."
}
```

For approval, `scheduledStart`/`scheduledEnd` are optional; omitting them uses the request proposal.
