# Visit Requests API

Date: 2026-09-19

Follow-up Visit requests are proposals for another field attempt on an existing Job. They are not
Visits and must not be rendered as confirmed appointments until approved.

## Permissions

| Capability | Meaning |
| ---------- | ------- |
| `schedule.view_org` | View the organization dispatch schedule. |
| `VISIT_VIEW_ASSIGNED` | View the caller's assigned Visits. |
| `visits.request_follow_up` | Submit a follow-up Visit request, and answer one the office returned for clarification. |
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

One transition exists beyond `PENDING → APPROVED | REJECTED`: **`NEEDS_CLARIFICATION → PENDING`**, taken by
the requester's own answer (`BR-FV-012`, `docs/decisions/023-follow-up-clarification-conversation.md`).
`APPROVED` and `REJECTED` remain terminal.

Review payloads may carry `expectedStatus` and `expectedVersion`. If the request changed since the
client read it, the API returns `409 FOLLOW_UP_VISIT_REQUEST_CONFLICT`. The answer carries them too.

## Routes

| Method | Route | Permission | Result |
| ------ | ----- | ---------- | ------ |
| `GET` | `/jobs/visit-requests` | `visits.review_requests` or `visits.request_follow_up` | Reviewers see organization requests; requesters see their own. |
| `POST` | `/jobs/:id/visit-requests` | `visits.request_follow_up` | Creates a request on the existing Job. |
| `POST` | `/jobs/:id/visit-requests/:requestId/clarification` | `visits.review_requests` | Moves an open request to `NEEDS_CLARIFICATION`. |
| `POST` | `/jobs/:id/visit-requests/:requestId/reply` | `visits.request_follow_up` | Records the requester's answer and moves the request back to `PENDING`. |
| `POST` | `/jobs/:id/visit-requests/:requestId/rejection` | `visits.review_requests` | Moves an open request to `REJECTED`. |
| `POST` | `/jobs/:id/visit-requests/:requestId/approval` | `visits.review_requests` and `visits.create_schedule` | Creates one scheduled Visit on the same Job and marks the request `APPROVED`. |
| `POST` | `/jobs/:id/visits` | `visits.create_schedule` | Directly creates and schedules another Visit on the existing Job. |

Approval is transactional: the request row is locked, the Visit is inserted, its schedule and crew
history are recorded, and `createdVisitId` is written back to the request in one transaction. A retry of
an already-approved request returns the Job with the already-created Visit rather than creating another.

## The clarification conversation

A request returned for clarification is **answered by its requester**, and answering it is **one**
operation: the answer is appended to the request's conversation and the request returns to `PENDING` — the
state the office reviews it in — in a single transaction. Answering is never a decision: only the office
approves or rejects (`BR-FV-012`, `docs/decisions/023-follow-up-clarification-conversation.md`).

| Part | What it holds |
| ---- | ------------- |
| The office's question | A message, appended in the same transaction that returns the request for clarification, when that decision carried a `note`. |
| The requester's answer | A message, appended by `reply`, in the same transaction that moves the request back to `PENDING`. |
| `authorKind` | `REQUESTER` or `OFFICE`, **derived** from the request's own requester — no column stores it (`BR-041`, `BR-042`). |

The conversation is **append-only**: no route edits or removes a message, and each one carries its author
and the time the server recorded it. Every projection of a request carries its `messages`, oldest first, so
the office's review read and the requester's own read see the same exchange. A rejection appends nothing:
it is a decision that closes the request, and its reason is the request's own `reviewNote`, which keeps its
existing meaning as the **current** decision note (`BR-FV-013`).

| Outcome | When it happens |
| ------- | --------------- |
| `404 FOLLOW_UP_VISIT_REQUEST_NOT_FOUND` | The request is not the caller's own, or names another Job — a request raised by another member is reported as absent rather than forbidden, so an id never becomes a way to reach someone else's request (`BR-007`, `BR-009`). |
| `409 FOLLOW_UP_VISIT_REQUEST_CONFLICT` | `expectedStatus`/`expectedVersion` name a state the request has left (`BR-032`). |
| `409 FOLLOW_UP_VISIT_REQUEST_NOT_AWAITING_REPLY` | The request is not `NEEDS_CLARIFICATION`, so it owes no answer (`BR-FV-012`). |
| `400 VALIDATION_FAILED` | The answer says nothing, or carries no readable body: the office returned the request having asked something, so an empty answer is not one. |

## Clients

| Surface | What it does |
| ------- | ------------ |
| Android — Job Details, **Request another visit** | The technician's own submission (`visits.request_follow_up`, `BR-FV-001`). The form states the window the technician proposes, why another attempt is needed and whether they would like to carry it out (`BR-FV-003`), and names the field attempt it grew from (`BR-FV-008`). It asks for no crew: the office states who performs the Visit when it approves one (`BR-FV-004`). |
| Android — Schedule, **Requests** lane | The office's review (`visits.review_requests`): **Approve & schedule**, **Clarify** and **Reject** on the requests the organization still has a decision to make about (`BR-FV-004`, `BR-FV-005`). The lane draws every request the API keeps reviewable — `PENDING` **and** `NEEDS_CLARIFICATION`, because `BR-FV-012` keeps a clarified request unresolved until it is approved or rejected — and a clarified request says so on its card. Clarify and Reject are taken in a confirmation that carries the office's `note`: a rejection is sent with the reason it was refused (`BR-FV-013`), and a clarification is offered the question it needs answered. A decision the API refused is reported, and nothing is presented as decided (`BR-001`). The card also draws the request's **conversation**, so the office decides on the answer it asked for rather than on the same information it already had. |
| Android — My Schedule, **Requests** tab | The **requester's own** record of what they asked for (`visits.request_follow_up`). The `GET` answers a caller who does not hold `visits.review_requests` with their own requests only, so the tab lists the caller's own rows, newest first, each with its `status` and the office's `note` when it wrote one — which is how a rejection reaches the technician who raised the request and says why it was refused (`BR-FV-012`, `BR-FV-013`). It is a list the technician opens rather than a notification (`BR-029`), it is drawn on the capability the route accepts for it (`BR-011`), and it renders the proposal as a proposal (`BR-FV-002`). A request returned for clarification draws its conversation and offers **Answer**, which opens a composer stating what the office asked; the answer is sent to `reply` and the request returns to the office's review. A row opens the request's own **details** destination rather than the Job (`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.12), which reads one request **out of this same list** — no per-request read is added — and leaves the Job one action away. |

### Request identity and authorization

Every request projection now carries the limited operational identity needed to recognize the work:
jobNumber, jobTitle, customerName, the Job's preserved address snapshot, and the source Visit's scheduled
start, status and outcome when there is a source Visit. These fields are present on list, create, reply,
clarification and rejection responses; approval continues to return the resulting Job.

The request permission that authorizes a row also authorizes this identity. A requester still receives
only their own requests, while a reviewer receives the organization's requests; customers.view is not an
additional requirement. This does not expose customer contact details, notes, billing information or the
live Customer record. Android My Schedule leads each request card with the Customer and Job, shows the full
proposed date and time, and keeps the complete clarification conversation on the details screen.

Every write is **online-only**: no request route takes a client-generated idempotency key and no conflict
policy is decided for them, so none is queued on the device
(`docs/architecture/offline-first-architecture.md` §13.2, `BR-013`, `BR-032`). An answer the office cannot
see is worse than no answer: it is sent, or it is reported (`BR-014`).

The requester's own read is **online-only** as well, for a different reason: Servora keeps no local copy of a
request's state, because the office's answer is only useful while it is current and a stored answer could
present a decision the backend has since replaced. A read the device cannot make is reported as a failure
rather than answered locally, so the tab never shows a stale decision as the office's
(`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.8, `BR-001`, `BR-014`).

The request is **not** rendered as an appointment anywhere: a pending request is a proposal awaiting a
decision, and only an approved request has a scheduled Visit (`BR-FV-002`, `BR-FV-010`).

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

Answer a request the office returned for clarification:

```json
{
  "body": "The part number is PN-4471.",
  "expectedStatus": "NEEDS_CLARIFICATION",
  "expectedVersion": 2
}
```

A request projection carries its conversation:

```json
{
  "id": "uuid",
  "status": "NEEDS_CLARIFICATION",
  "reviewNote": "Which part number?",
  "version": 2,
  "messages": [
    {
      "id": "uuid",
      "authorMembershipId": "uuid",
      "authorKind": "OFFICE",
      "body": "Which part number?",
      "recordedAt": "2026-09-21T14:05:00Z"
    },
    {
      "id": "uuid",
      "authorMembershipId": "uuid",
      "authorKind": "REQUESTER",
      "body": "The part number is PN-4471.",
      "recordedAt": "2026-09-21T14:31:00Z"
    }
  ]
}
```

For approval, `scheduledStart`/`scheduledEnd` are optional; omitting them uses the request proposal.
