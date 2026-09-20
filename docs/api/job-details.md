# Servora API — `/jobs` Contract

**Status: IMPLEMENTED** for `POST /jobs` (§6) and `GET /jobs/:id` (§3).

This read is a **projection**: it derives one Job's details from the authoritative Job, Visit,
assignment, Customer and Property records and stores nothing. It follows the Job & Visit domain model
(`docs/domain/job-visit-domain-model.md` §5 Jobs, §6 Visits, §7 assignment and history), and it rests
on `BR-021` (a Job is a core entity), `BR-047` – `BR-052` (Job identity and number), `BR-058` (Job
status), `BR-068` (assignment is recorded on the Visit), `BR-072` (the Visit's internal schedule) and
`BR-081` (the selected Visit).

References: `BR-001`, `BR-006`, `BR-007`, `BR-021`, `BR-023`, `BR-028`, `BR-041`, `BR-042`, `BR-047`,
`BR-048`, `BR-049`, `BR-050`, `BR-051`, `BR-052`, `BR-053`, `BR-054`, `BR-056`, `BR-057`, `BR-058`,
`BR-059`, `BR-068`, `BR-072`, `BR-074`, `BR-077`, `BR-078`, `BR-079`, `BR-080`, `BR-081`, `BR-092`,
`BR-094`, `dev.md` §7, `Project.md` §15,
`docs/decisions/019-technician-field-experience.md`, `docs/decisions/021-technician-customer-read.md`,
`docs/tracker/017-android-job-details.md`, `docs/tracker/043-create-job.md`,
`docs/tracker/048-android-job-activity-visit-outcome.md`.

## 1. Conventions

JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15). The access token
is presented as `Authorization: Bearer <accessToken>`. Route paths carry no version prefix
(`docs/versioning.md` §7).

## 2. Permissions

| Route           | Permission                                       |
| --------------- | ------------------------------------------------ |
| `POST /jobs`    | `JOB_CREATE`                                      |
| `GET /jobs/:id` | `customers.view` **or** `VISIT_VIEW_ASSIGNED`     |

**Creating a Job uses the capability that already exists for it.** `BR-008` names "create jobs" as a
Manager default, and the foundation catalogue already defines `JOB_CREATE` with its bilingual name and
description and grants it to the default Manager role (`0004_woozy_spitfire`). So the create route is
guarded by `JOB_CREATE` rather than by the interim `JOB_UPDATE` the Job and Visit **actions** reuse:
those reuse an existing capability because their feature has not defined its own, while `JOB_CREATE`
already names exactly this action (`BR-006`, `BR-042`). The `jobs.*` capability-set question in §5
still covers the rest of the Job writes.

**Why these two, for one projection.** The office caller reads through `customers.view`, the capability
the API already uses for exactly the data this read returns: `GET /customers/:id/jobs` and
`GET /home/manager` are guarded by it, so a caller who can open a Job Details screen can already reach
every record it returns. The field caller reads through `VISIT_VIEW_ASSIGNED`, the capability `BR-009`
already gives the technician who does the work — and the route carries **one** projection and **one**
wire contract for both, because a second Job projection would be a second definition of the same Job
(`BR-041`). The catalogue has **no** `jobs.view` capability, and introducing one would be inventing a
permission the Jobs feature has not defined (`BR-006`, `BR-042`). The decision, and the any-of guard
shape it needs, are recorded in `docs/decisions/019-technician-field-experience.md` (D1).

**The field caller's scope (`ADR-019` D2).** A caller admitted by `VISIT_VIEW_ASSIGNED` reads only a Job
that at least one **current** `visit_technicians` row of their own reaches — including a Visit that has
already completed, because a completed assignment stays current until a manager removes it (`BR-069`).
A Job it does not reach is `404`, never `403`, so a Job id cannot be probed for existence. The scope
follows the capability that admitted the caller: a member who also holds `customers.view` reads the
organization's Jobs exactly as before, and `GET /customers/:id/jobs` and `GET /home/manager` are not
affected by this read's widening.

**The read's second question: the Customer's contact details (`BR-092`).** Beside the guard, this
route asks whether the answer includes the Customer the Job belongs to, and what it includes is the
Customer's own fields together with its **contact persons** (`BR-092`, `BR-095`). A caller holding
`customers.view` or `customers.view_assigned` receives `customerContactDetails`; a caller holding neither
receives `null` for it and the rest of the Job **unchanged**, because a technician assigned to the Job
must still read it (`BR-009`, `BR-011`). The second question is asked separately because a route declares
either an all-of requirement or an any-of one and never both (`ADR-019` D1), and it is asked for **every**
route that returns this projection — the four Job and Visit action routes included — so an action never
returns a Job whose customer block has silently disappeared (`ADR-021` D2). The contacts ride **inside**
the block, so the read still answers exactly one question and a caller who is not admitted receives `null`
for all of it (`ADR-022` D3).

**What the block is scoped by.** The assignment, never the Customer: `customers.view_assigned` reaches a
Customer only through a Job the caller's own current crew includes, and a Job it does not reach is `404`
rather than a different answer about that Customer (`BR-092`). The capability grants no access to the
Customer routes — the customer list, `GET /customers/:id` and `GET /customers/:id/jobs` are guarded by
`customers.view` alone — and it is not an alternative to the capability that admits a caller to this
route at all.

> **OPEN QUESTION (product ownership).** When the Jobs feature lands with its own capability set
> (`BR-008` names create/view/update/delete jobs as Manager defaults), this route must be re-reviewed
> and guarded by whatever that feature defines. Recorded in
> `docs/tracker/017-android-job-details.md` rather than guessed.

This controller exposes the read here and the Job and Visit **actions** in the same module. Every Job
and Visit action is an explicit authorized action (`BR-066`, `BR-067`) and is specified in
`docs/api/job-actions.md`; the **office** actions are guarded by the existing `JOB_UPDATE` capability for
the same reason this read reuses `customers.view`, while the **field** routes
(`PATCH /jobs/:id/visits/:visitId/status` and `POST /jobs/:id/visits/:visitId/notes`) are guarded by the
capabilities `BR-009` gives the technician (`docs/api/job-actions.md` §2, §7). The same open question
covers the office actions.

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
  "customerContactDetails": {
    "email": "martha@example.com",
    "phone": "+15145550142",
    "notes": "Gate code 4821. Ring twice.",
    "contacts": [
      {
        "firstName": "John",
        "lastName": "Smith",
        "phone": "+15551234567",
        "email": "john@example.com",
        "isPrimary": true
      }
    ]
  },
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
    "reschedulable": false,
    "allowedStatusTransitions": ["DRAFT", "SCHEDULED", "ON_SITE", "IN_PROGRESS", "COMPLETED"],
    "fieldActionable": true
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
  ],
  "visits": [
    {
      "id": "8d7c6b5a-4e3f-4d2c-9b1a-0f9e8d7c6b5a",
      "sequence": 1,
      "status": "COMPLETED",
      "outcomeCode": "RESOLVED",
      "scheduledStart": "2026-09-07T13:00:00.000Z",
      "scheduledEnd": "2026-09-07T15:00:00.000Z",
      "version": 9,
      "technicians": [
        {
          "membershipId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
          "name": "Dave Tremblay",
          "roleCode": "LEAD"
        }
      ]
    },
    {
      "id": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
      "sequence": 2,
      "status": "EN_ROUTE",
      "outcomeCode": null,
      "scheduledStart": "2026-09-14T13:00:00.000Z",
      "scheduledEnd": "2026-09-14T15:00:00.000Z",
      "version": 2,
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
| `selectedVisit.allowedStatusTransitions` | The statuses **this caller** may move the Visit to (`BR-074`, `BR-075`, `BR-093`): the structural table `PATCH /jobs/:id/visits/:visitId/status` validates against (`docs/api/job-actions.md` §7), narrowed to what the calling member is authorized to execute, so the action is drawn from the server's own answer rather than a second copy of the lifecycle (`BR-022`, `BR-041`). `COMPLETED` is absent unless the caller holds `VISIT_RECORD_OUTCOME` — required of **every** caller, the office included — and the list is empty for a caller who may drive the Visit through neither authorization, because every destination would be refused. `CANCELED` and `NO_SHOW` are always absent: they are dispatch actions no capability authorizes (`BR-066`). Eligibility is deliberately **not** filtered: `BR-072`'s conditions and `BR-070`'s conflicts stay runtime answers and are refused with their own code (`BR-074`). The capability the **session** holds remains the client's own gate and the route's guard (`BR-007`, `BR-011`). |
| `selectedVisit.fieldActionable` | Whether **this caller** is authorized to drive that Visit's field lifecycle right now (`BR-074`, `BR-066`, `BR-093`). Two authorizations answer it, and `BR-093` keeps them distinct: the caller's own membership is on the Visit's **current crew** (evaluated when the action is performed, so a Visit that crew does not include is `404 VISIT_NOT_FOUND`), **or** the caller holds the office capability that reaches Visit writes (`JOB_UPDATE`, the capability every office action on a Job or Visit requires), which addresses the organization's Visits **without** crew membership and is how a manager completes a Visit from Job Details (`ADR-019` D3, D7). A read therefore reports `false` to a caller admitted through a **different** Visit of the same Job with no office capability, and `true` to an office caller on no crew at all. The completion's own capability is a separate question the list above answers, not this one. The capability the **session** holds stays the client's own gate: a field-only session holding `VISIT_VIEW_ASSIGNED` alone is still told `true` for its own Visit (`BR-007`, `BR-011`). |
| `technicians` | The **represented Visit's** current assignments, Lead first (`BR-068`); `[]` when nobody is assigned. `name` is `null` when the member has no profile yet (`BR-020`).                                                          |
| `technicians[].roleCode` | `LEAD` or `TECHNICIAN`. Exactly one assigned technician is `LEAD` while a Visit has technicians (`BR-068`).                                                                                                       |
| `visits`     | **Every Visit of the Job**, in the Job's own visit sequence (`BR-047`, `BR-051`, `BR-071`), each with the status, the outcome it holds, the schedule and the crew it actually carries. `[]` for a Job with no Visit. The represented Visit is one entry of this list — the one whose `id` is `selectedVisit.id` — and is not marked specially, because which Visit represents a Job is `selectedVisit`'s answer (`BR-081`, `BR-041`). A read presents one **projection** of the Job and its Visits rather than two: the list is what lets a client present the Job's other field attempts without a second request, and it is what a Job's history is read from. |
| `visits[].sequence` | The Visit's stable, human-readable sequence within the Job: `1` for the Job's first Visit, `2` for the second, and so on. It is derived from the Visit's creation order and is the **same** `Visit N` label `GET /jobs/:id/activity` reports as `visitSequence` (`docs/api/job-activity.md` §3.3), which is how a client groups that read's events by Visit (`BR-052`, `BR-041`). It is a presentation label only — never stored, never an identifier and never a cross-system key. |
| `visits[].scheduledStart` / `scheduledEnd` | The Visit's own internal schedule, in the same shape `selectedVisit` reports it (`BR-072`). Both ends are `null` together for a Visit that has not been scheduled yet — a `DRAFT` Visit exists before it is scheduled (`BR-051`, `BR-072`) — so a client states "not scheduled" rather than an invented date (`BR-042`). |
| `visits[].outcomeCode` | The outcome the Visit's completion recorded, or `null` when the Visit holds none (`BR-077`, `BR-078`). It is the Visit's **current** outcome: a Visit completed and then reopened holds none until it is completed again, while the outcome it recorded stays in the append-only history `GET /jobs/:id/activity` reports as `VISIT_OUTCOME_RECORDED` (`BR-074`, `BR-079`). A client therefore states what resulted from a field attempt from this field and does not derive it from that Visit's activity events (`BR-001`, `BR-041`). The outcome's **summary** is deliberately absent: the technician's account of what happened is read in that Visit's own activity, where it is not split from the entry that recorded it (`BR-077`, `BR-080`). |
| `visits[].technicians` | That **one Visit's** current assignments, Lead first, in the same shape as `technicians` (`BR-068`, `BR-069`). A Visit's crew is never merged with another Visit's, and a member without a profile keeps `name` as `null` (`BR-020`). |
| `visits[].version` | That Visit's version, which its own reschedule and crew routes echo back (`BR-086`). |
| `visits[].status` | That Visit's own field lifecycle status (`BR-074`). It carries no field action and no reschedule permission: those are answered once on `selectedVisit`, which is the Visit the screen's Visit actions address (`BR-073`, `BR-093`). |
| `address`    | The Job's preserved property address snapshot (`BR-056`). `null` when the Job has no Property yet. A partly known snapshot keeps its absent parts as `null`.                                                                   |
| `jobNumber`  | The organization-scoped, human-readable number (`BR-052`). It is never a cross-system key.                                                                                                                                     |
| `customerId` / `customerName` | The Customer the Job belongs to (`BR-048`). Both are always present, whatever the caller holds: the Job always names its customer.                             |
| `customerContactDetails` | The Customer's **own** contact details together with the Customer's **contact persons**, present only for a caller holding `customers.view` or `customers.view_assigned` (`BR-092`, `BR-095`; `ADR-021` D2/D3). `null` means the caller was not admitted to them and is **not** an error. It carries exactly `email`, `phone`, `notes` and `contacts`; a field the office never recorded is `null`, an empty `contacts` is `[]`, and the Customer's billing fields, its preferred contact method, its language, its addresses, its Properties and its Jobs are deliberately absent. Each entry in `contacts` carries exactly the contact person's `firstName`, `lastName`, `phone`, `email` and `isPrimary` — the billing-contact flag, the free-text `role`, the job-contact flag and the contact's write token are **not** on this wire, because `BR-092` names a name, a phone number, an email address and which contact person is the Customer's flagged primary and nothing else (`BR-095`). `isPrimary` is the contact row's own flag, not a resolution of the Customer's primary: when no contact person is flagged it is `false` on every entry, and the Customer is then the effective primary, which is what a client presents (`BR-095`). The array is primary first, then oldest-first, and a removed contact is never in it (`BR-095`, `ADR-022` D3, D8). |

**Assignment is Visit-scoped, and this read does not change that.** There is no `technicianId` on a
Job: `jobs` has no technician column, the crew is read from `visit_technicians`, and a Job's
technicians are the technicians assigned across its Visits (`BR-068`). `technicians` therefore
describes the Visit that represents the Job — the crew the screen's Visit actions address — while
`visits` describes each Visit's own crew, so a Job's other field attempts are readable without a
second request and no Visit's crew is merged with another's.

### 3.3 What the read never includes

- Nothing outside the caller's organization: every query is scoped by `organization_id` (`BR-001`).
- Nothing for a Job whose Customer the organization has deleted: `BR-023` hides a deleted customer's
  Jobs by default, so the route answers `404` rather than disclosing the Job.
- No Job deletion behaviour, no priority and no Job-level schedule: a Job has no priority (`BR-054`)
  and no scheduled date of its own — scheduling is performed on Visits (`BR-072`, `BR-081`).
- No Job Activity timeline. It is a confirmed read model of its own (`BR-080`) and is not part of this
  read; it is `GET /jobs/:id/activity` (`docs/api/job-activity.md`).
- No Customer data beyond the block above: the Customer's addresses, Properties and Jobs — and its
  billing and communication preferences — are the office read's (`GET /customers/:id`, `BR-023`,
  `BR-092`). The Customer's **contact persons** are the one exception, because `BR-092` adds them to the
  block: what it discloses is their name, phone number and email address, and nothing else.

## 4. Errors

| Status | Code              | When                                                       |
| ------ | ----------------- | ---------------------------------------------------------- |
| `401`  | `UNAUTHENTICATED` | No usable session.                                         |
| `403`  | `FORBIDDEN`       | The caller holds neither `customers.view` nor `VISIT_VIEW_ASSIGNED`. |
| `404`  | `JOB_NOT_FOUND`   | The Job does not exist in the caller's organization, its Customer has been deleted, or the caller is a field caller whose own assignments do not reach it. |
| `5xx`  | —                 | Server failure; the client reports it and offers a retry.  |

A Job another organization owns is `404`, never `403`: the API does not confirm that another
organization's Job exists (`BR-001`). A Job a field caller is not assigned to is `404` for the same
reason (`ADR-019` D2) — the route never uses `403` to say "not yours".

## 5. Open questions

Recorded rather than guessed (`BR-042`); `docs/tracker/017-android-job-details.md` carries the same
list with what each one blocks.

1. **The `jobs.*` capability set** (`BR-006`, `BR-008`): this read and `GET /home/manager` reuse
   `customers.view` until the Jobs feature defines its own capabilities, and the Job and Visit **actions**
   reuse `JOB_UPDATE`. The **create** route is not part of this question: `BR-008` names "create jobs" as a
   Manager default and the catalogue already defines and grants `JOB_CREATE`, so §6 uses the capability
   that already exists for it. The question covers the rest of the Job and Visit writes.
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
6. **The field read's own open questions** (`ADR-019`): whether the field capabilities should be
   re-spelled as `resource.action` codes, and whether a field caller should see a narrower projection
   than the office caller. D1 keeps the existing `VISIT_*` spellings and one projection; both questions
   are recorded in `docs/decisions/019-technician-field-experience.md`.
7. **`preferredContactMethod` and `language` in the field read** (`BR-092`). Whether a technician may read
   the Customer's **contact persons** was question 1 of this list and is **decided**: `BR-092` was amended
   on 2026-09-18 to include them, inside the block and under the same second question (`BR-095`,
   `ADR-022` D3; `ADR-021`'s decision update). What remains open is the two fields that describe how
   Servora communicates with the Customer rather than who the technician should call, so neither is
   implemented (`BR-042`).
8. **Tap-to-call and tap-to-email affordances** on the block. **Decided** on 2026-09-18: both surfaces are
   tappable, reusing the affordances the repository already has (`ADR-022` D6; `ADR-021`'s decision
   update). The Android surfaces are implemented by `docs/tracker/047-customer-contact-persons.md`; the
   visual detail remains the design system's (`docs/design/android-design-system.md`).

## 6. `POST /jobs` — create a Job (`BR-047` – `BR-056`, `BR-094`)

Creates a Job for a Customer at a Property. It requires `JOB_CREATE` (§2).

**The supported create operation requires both an active Customer and an active Property that currently
belongs to that Customer** (`BR-094`). The Job & Visit domain still permits a property-less Job — `BR-051`
keeps a Job creatable with no Visit, and `BR-056` allows a Property to be added later — so a Job created
by another path or an earlier one remains valid; it is **this** operation that states the Property, and a
client must not offer a Job form without one.

```json
{
  "customerId": "0f0a6c2e-9d4b-4e6a-8f1c-3a2b1c4d5e6f",
  "propertyId": "b7c1e2f3-4d5a-4b6c-8d9e-0f1a2b3c4d5e",
  "title": "Furnace repair",
  "description": "Customer reports the furnace is not producing heat."
}
```

### 6.1 Response — `201 Created`

The answer is the **read's own projection** (§3.1), so a client is handed the Job exactly as the Job
Details screen reads it, and no second representation of a Job exists (`BR-041`):

```json
{
  "id": "6f1a8d1e-4b26-4f8f-9a34-2b7c9e0d5a11",
  "jobNumber": 1042,
  "title": "Furnace repair",
  "description": "Customer reports the furnace is not producing heat.",
  "typeCode": null,
  "status": "NEW",
  "allowedStatusTransitions": ["SCHEDULED", "IN_PROGRESS", "PENDING_REVIEW", "COMPLETED"],
  "version": 1,
  "customerId": "0f0a6c2e-9d4b-4e6a-8f1c-3a2b1c4d5e6f",
  "customerName": "Martha Reynolds",
  "customerContactDetails": {
    "email": "martha@example.com",
    "phone": "+15145550142",
    "notes": "Gate code 4821. Ring twice.",
    "contacts": [
      {
        "firstName": "John",
        "lastName": "Smith",
        "phone": "+15551234567",
        "email": "john@example.com",
        "isPrimary": true
      }
    ]
  },
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
  "selectedVisit": null,
  "technicians": []
}
```

### 6.2 What the caller supplies, and what the backend decides

| Field         | Required | Notes                                                                                                                        |
| ------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `customerId`  | yes      | A UUID. The Customer must exist in the caller's organization, must not be deleted, and must be `ACTIVE` (`BR-023`, `BR-048`). |
| `propertyId`  | yes      | A UUID. The Property must be the caller's organization's, `ACTIVE`, and currently related to `customerId` by an active `property_customer_relationships` row (`BR-050`, `BR-083`). |
| `title`       | yes      | Required and trimmed; a whitespace-only title is rejected. At most 255 characters (`BR-053`).                                  |
| `description` | no       | Optional and trimmed; blank is stored as absent. At most 2000 characters (the boundary's cap, not a business limit).           |

The backend decides, and never reads from the request:

| Value                     | Source                                                           |
| ------------------------- | ---------------------------------------------------------------- |
| `id`                      | Generated UUID (`BR-052`).                                       |
| `jobNumber`               | The next organization-scoped sequential number (`BR-052`, §6.3). |
| `status`                  | `NEW` — the Job lifecycle's first state (`BR-058`).              |
| `propertyAddressSnapshot` | Copied from the Property as it stands now (`BR-056`, `BR-057`).  |
| `version`                 | `1`.                                                             |
| `organizationId`          | The caller's organization, from the session (`BR-001`).          |
| `createdAt` / `updatedAt` | The database clock.                                              |

### 6.3 Job number allocation and concurrency

`BR-052` requires the number to be immutable, sequential **within the organization**, and unique within
the organization. It is allocated inside the same transaction that inserts the Job, in one statement that
bootstraps `organization_job_number_counters` for a new organization and increments it otherwise:

```sql
INSERT INTO organization_job_number_counters (organization_id, last_job_number)
VALUES ($1, 1)
ON CONFLICT (organization_id)
DO UPDATE SET last_job_number = organization_job_number_counters.last_job_number + 1
RETURNING last_job_number;
```

The counter row is the **serialization point**: two concurrent creations in one organization cannot read
the same number, and `UNIQUE (organization_id, job_number)` backs it at the database. The inserted value
states the first number explicitly because the increment applies only on conflict. Numbering starts at
`1` for a new organization; `BR-052` does not define a starting value, and the counter carries no product
meaning. No gap-free guarantee is claimed — a rolled-back creation returns its number to the pool rather
than consuming it, and `BR-052` requires uniqueness and immutability rather than a gapless sequence.

### 6.4 The initial status event

The creation records one append-only `job_status_history` row: `toStatus` `NEW`, `fromStatus` **`null`**,
the acting membership and the timestamp (`BR-033`, `BR-067`). The null previous status is what the history
permits and is used deliberately: the Job entered `NEW` from no status, and no previous status is
fabricated for a Job that did not exist.

### 6.5 Errors

| Status | Code                                 | When                                                                                                       |
| ------ | ------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`                   | A required field is missing, a UUID is malformed, the title is blank, or a value exceeds its limit.          |
| `401`  | `UNAUTHENTICATED`                     | No usable session.                                                                                           |
| `403`  | `FORBIDDEN`                           | The caller does not hold `JOB_CREATE`.                                                                       |
| `404`  | `CUSTOMER_NOT_FOUND`                  | The Customer does not exist in the caller's organization, another organization owns it, or it is deleted.    |
| `404`  | `PROPERTY_NOT_FOUND`                  | The Property is not the caller's, does not exist, is held by another Customer, or its relationship to this Customer has ended. |
| `409`  | `CUSTOMER_INACTIVE`                   | The Customer exists but is not `ACTIVE`.                                                                     |
| `409`  | `PROPERTY_NOT_AVAILABLE_FOR_NEW_WORK` | The Property is `ARCHIVED`; archiving blocks new Jobs (`BR-083`).                                             |
| `5xx`  | —                                     | Server failure; the client reports it and offers a retry.                                                    |

A Customer or Property another organization owns is `404`, never `403`: the API does not confirm that
another organization's record exists (`BR-001`). A Property held by a **different** Customer answers
`PROPERTY_NOT_FOUND` as well, which is the answer the Customer-scoped Property routes already give, so an
id cannot be probed for which Customer holds it (`BR-050`).

### 6.6 What the create never does

- **It never creates a Visit.** A Visit is one field attempt (`BR-047`, `BR-051`); creating the work
  request is not scheduling field execution, and the created Job has no Visit and no schedule.
- **It never records a technician, an owner, a priority or a category.** Assignment is Visit-scoped
  (`BR-068`), the owner and category are optional data this operation does not ask for (`BR-053`,
  `BR-055`), and v1 has no Job priority at all (`BR-054`).
- **It never accepts a client's number, status, version, address snapshot or organization** (§6.2).
- **It is online-only.** The request carries no idempotency key and no conflict policy is decided for it,
  so it is not queued on a device — which is the rule the offline standard's §13 sets for a new Android
  mutation (`docs/architecture/offline-first-architecture.md` §13).
- **It is one transaction.** The Customer check, the Property check (under its row lock), the number
  allocation, the Job insert and the initial status event commit together or not at all (`BR-086`).
