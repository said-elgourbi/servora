# Tracker 050 — Visit scheduling and follow-up visit requests

**Status: IN PROGRESS** (Phase 1 started; Android lint intentionally not run)

Date: 2026-09-19

Business rules touched: Job creation remains Job-only, Visits remain field attempts on an existing Job,
pending requests are not scheduled Visits, and authority is capability-based rather than role-name based.

## Scope

Implement follow-up Visit requests and direct Visit scheduling in four phases:

| Phase | Status | Scope |
| ----- | ------ | ----- |
| 1. Domain and API contract | **IN PROGRESS** | Follow-up request model, persistence, DTOs, permissions, API operations, conflict/idempotency rules, and docs. |
| 2. Manager Android workflow | **IN PROGRESS** | Pending requests in Schedule, review/approve/reject/clarify and direct Visit management from Job Details. |
| 3. Technician Android workflow | **IN PROGRESS** | Request another Visit from Job Details (**landed 2026-09-28**), request status feedback (**landed 2026-09-28** as a Requests tab on My Schedule — `docs/tracker/057-qa-issue-list-visit-workflow.md` §5.8), and direct scheduling for authorized technicians. |
| 4. Verification and hardening | TODO | API and Android tests for authorization, lifecycle, duplicate approval, navigation, process recreation, and docs finalization. |

## Progress

- [x] Add the follow-up request schema, migration, lifecycle vocabulary, DTO parsers, permissions, default grants, and Android permission codes.
- [x] Add submit/list/clarify/reject/approve and direct Visit creation API operations.
- [x] Make approval retries return the already-created Visit even when the retry carries the original optimistic-concurrency fields.
- [x] Pin the expanded permission catalogue and cover request/review/scheduling DTO validation (71 focused API tests pass; API lint passes).
- [x] Add database-backed API coverage for authorization, request lifecycle transitions, scheduling conflicts, direct scheduled Visit creation, and duplicate approval idempotency.
- [x] Register migration `0015_follow_up_visit_requests` in Drizzle's journal so e2e databases apply the follow-up request table and permission catalogue rows.
- [x] Start Phase 2 Android: add a follow-up request Retrofit/repository path, model pending requests on the manager Schedule ViewModel, and add a Schedule **Requests** lane with open-job, clarify and reject actions.
- [ ] Complete repository-wide API typecheck after resolving the existing nullable schedule assertions in `test/technician-home.e2e-spec.ts`.
- [x] Add the manager scheduling forms: **Schedule a visit** on Job Details (`POST /jobs/:id/visits`) and **Approve & schedule** on a pending request (`POST /jobs/:id/visit-requests/:requestId/approval`), both with date/time and crew input and `BR-070` conflict confirmation.
- [x] Add technician request submission from Job Details (**Phase 3, 2026-09-28**): the Visit actions row gains **Request another visit**, and the shared window controls gain the request form.
- [x] Add technician request **status feedback** (**landed 2026-09-28**): My Schedule carries a **Requests** tab that lists the caller's own requests — the route answers a requester with their own rows — with each request's status and, when the office wrote one, the office's `note` that states why it was refused or what a clarification still needs (`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.8). The read is online-only and the tab is drawn on `visits.request_follow_up`.
- [x] Add the technician's **answer** to a request returned for clarification (**landed 2026-09-29**): `POST /jobs/:id/visit-requests/:requestId/reply` appends the requester's answer to the request's own append-only conversation and moves it back to `PENDING` in one transaction, the requests view draws the conversation and offers **Answer** while one is owed, and the office lane reads the same thread (`BR-FV-012`, `docs/tracker/057-qa-issue-list-visit-workflow.md` §5.11, `docs/decisions/023-follow-up-clarification-conversation.md`). It is online-only, like the read that lists the request.
- [ ] Add a technician request **Job-scoped** read — showing a request on the Job it belongs to — which needs a filter the route does not have today. It is the later change product ownership named when it answered §5.3.5 of tracker 057.
- [ ] Review direct scheduling for a technician granted `visits.create_schedule` (`BR-FV-011`): the existing **Schedule a visit** action is drawn on that capability alone, but its crew picker reads the organization's technicians, so a technician without `technician.view` has no crew to state.

## Phase 2 Android scheduling landed 2026-09-28

The two manager writes this phase was missing now exist, and they share one form because both state the
same three things (`BR-068`, `BR-072`):

| Surface | Action | Route | Capability |
| ------- | ------ | ----- | ---------- |
| Job Details (`JobDetailsOverviewScreen`) | **Schedule a visit** — Visit actions row | `POST /jobs/:id/visits` | `visits.create_schedule` |
| Schedule → Requests lane | **Approve & schedule** on a pending card | `POST /jobs/:id/visit-requests/:requestId/approval` | `visits.review_requests` **and** `visits.create_schedule` |

What was added:

- `JobDetailsApi`/`JobDetailsRepository`: `createVisit` and `approveVisitRequest`, both answered with the
  Job, so both reuse the existing `JobActionResult` (including `SCHEDULE_CONFLICT` parsing) and the
  session-renewal/retry contract. Both are **online-only** — neither route takes a client idempotency key,
  so neither may be queued (`offline-first-architecture.md` §13.2).
- `JobActionFailure.VISIT_REQUEST_CHANGED` and `VISIT_REQUEST_NOT_REVIEWABLE`: the approval route's two
  own answers about the request (`FOLLOW_UP_VISIT_REQUEST_CONFLICT`,
  `FOLLOW_UP_VISIT_REQUEST_NOT_REVIEWABLE`), classified apart so the reviewer is told which happened.
- `ui/jobs/ScheduleVisitSheet.kt`: the shared form — one visit date with a start and an end time, crew
  with exactly one Lead,
  validation before submitting (`ScheduleVisitDraft.problem`), loading/empty/failed crew reads.
- Job Details: `JobActionKind.CREATE_VISIT`, `JobActionRequest.CreateVisit`,
  `PendingJobAction.CreateVisit` and `JobDetailsViewModel.scheduleVisit`. `confirmPendingAction` no longer
  requires a represented Visit: scheduling one belongs to the Job, and the Visit it creates does not exist
  yet (`BR-051`, `BR-071`).
- Schedule: the Requests lane gains the approval action; `ScheduleViewModel` gains
  `beginApproval`/`approveRequest`/`confirmApproval`/`dismissApproval*`/`acknowledgeApproval`, holds
  `PendingVisitRequestApproval` for the conflict confirmation, reads the crew from
  `JobDetailsRepository.loadAssignableTechnicians()`, and re-reads the requests **and** the day after a
  successful approval (`BR-001`, `BR-FV-005`).
- `ScheduleConflictDialog` now takes the conflicts rather than the pending action, so the Schedule screen
  decides about an approval's conflicts with the same dialog the Job Details screen uses (`BR-041`).
- Permissions: `CustomerPermissionsUiState.canScheduleVisit` (`visits.create_schedule`) and
  `canReviewVisitRequests` (`visits.review_requests`); the approval is offered only when both are held,
  because the API asks for both.
- Both screens report what the API answered — the Job Details snackbar and the Schedule snackbar — and the
  schedule reports the approval's success or refusal there (`BR-FV-013`).
- English and French copy for every new string (`BR-028`).

Verification (Tier A — targeted; full lint/suites are the feature's, `qa.md` §3.1):

- `cd android && ./gradlew compileDebugKotlin` — **PASS**.
- `cd android && ./gradlew compileDebugAndroidTestKotlin` — **PASS** (device-test sources compile; no
  `adb` and no device command was run).
- `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.data.jobs.JobDetailsRepositoryTest'
  --tests 'com.servora.android.ui.jobs.ScheduleVisitDraftTest' --tests
  'com.servora.android.ui.jobs.JobDetailsViewModelTest'
  --tests 'com.servora.android.ui.schedule.ScheduleViewModelTest'` — **PASS**, 151 tests
  (54 + 7 + 69 + 21), including the new request-mapping, validation, conflict-confirmation and
  state-transition coverage.
- Android lint was **not** run (the product owner's instruction for this machine, and the change is not at
  the feature-complete tier).

### API findings recorded, then resolved

Two things the review of the two write routes surfaced. Neither was changed by the original Android
slice — a discovered mismatch was reported before any API contract was touched — and both are now
resolved server-side so the client is never asked to invent a rule the API does not state (`BR-007`,
`BR-042`).

1. **`POST /jobs/:id/visits` did not check the Job's status.** It now refuses a `COMPLETED` or `CANCELED`
   Job with `409 JOB_CLOSED_FOR_FIELD_WORK` (`BR-062`), and reopening (`BR-063`) is the only way a closed
   Job accepts a new Visit again. The Android action is no longer hidden for a closed Job by a local rule:
   the Job read reports `canScheduleVisit`, computed from the caller's capability, the Job's open state and
   its Visit shape, so the screen draws the action from the backend's own answer and keeps only the label.
2. **`POST /jobs/:id/visits` did not check `BR-072`'s Property condition.** `requireJobRow` now refuses a
   Job with no Property (`409 VISIT_SCHEDULING_CONDITION_NOT_MET` with `reason: 'PROPERTY'`), so a scheduled
   Visit can never be created without a location. Android's address-presentation gate remains a convenience,
   never the boundary (`BR-056`, `BR-072`).

Both were answered as "yes, refuse": the create route now enforces the closed-Job and property-less-Job
conditions the transition route already enforced, and the Job Details projection exposes
`canScheduleVisit` so the client follows the backend rather than re-deriving the rule.

## Scheduling form asks for one visit date 2026-09-28

The shared schedule form presented a start date-and-time and an end date-and-time, which offered a choice
the product does not have: a field attempt is one visit on one day (`BR-072`). It now asks for

| Field           | What it states                          |
| --------------- | --------------------------------------- |
| **Visit date**  | the one day the field attempt is on     |
| **Start time**  | when the visit starts on that day       |
| **End time**    | when the visit ends on that same day    |

- `ui/jobs/ScheduleVisitSheet.kt`: `visitDate`, `startHour`/`startMinute` and `endHour`/`endMinute`
  replace the two dates, and one date picker replaces the two. What the API is given is unchanged —
  `scheduledStart` and `scheduledEnd` are still UTC instants — and both are now built from the one date by
  `scheduleVisitWindow`, so "a visit is one day" is a rule with one home instead of a layout detail
  (`BR-041`, `BR-072`).
- The refusal is unchanged in substance and clearer in words: the end time must be later than the start
  time (`ScheduleVisitDraft.problem`, `schedule_visit_problem_end`).
- A proposal still fills the form (`BR-FV-003`): the request's start date is the visit date, and each
  proposed end contributes its own time. A proposal that ends on the following day therefore fills in an
  end time earlier than the start time, and the form refuses it rather than silently moving it — a
  cross-midnight field attempt is not expressible in this form.
- Test tags: `schedule-visit-date` replaces `schedule-visit-start-date` / `schedule-visit-end-date`;
  `schedule-visit-start-time` and `schedule-visit-end-time` are unchanged.
- English and French copy for the three fields and the refusal (`BR-028`).

The reschedule dialog (`RescheduleVisitDialog`, `BR-073`) is deliberately untouched: it already asks for
one date, one start time and a length, and no product decision changed it.

Verification (Tier A — targeted; full lint/suites are the feature's, `qa.md` §3.1):

- `cd android && ./gradlew compileDebugKotlin` — **PASS**.
- `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.ScheduleVisitWindowTest'
  --tests 'com.servora.android.ui.jobs.ScheduleVisitDraftTest'` — **PASS**, 11 tests (4 + 7), including
  the new same-day construction, the earlier-end-time refusal and the midnight-crossing case.
- `cd android && ./gradlew compileDebugAndroidTestKotlin` — **PASS** (device-test sources compile; no
  `adb` and no device command was run).
- Android lint was **not** run (not at the feature-complete tier, `qa.md` §3.1).

## Phase 1 API e2e coverage added 2026-09-19

`api/test/visit-requests.e2e-spec.ts` covers the follow-up request and direct scheduling API from the
HTTP boundary:

- a technician submits a follow-up request and sees only their own requests;
- request, review and create-schedule capabilities are enforced;
- clarification/rejection lifecycle updates, stale expected-status/version conflicts and terminal-state
  review refusals are reported;
- approval creates one scheduled Visit, records the reviewer fields, crew and schedule history, and a
  retry with the original optimistic-concurrency fields returns the same created Visit instead of
  creating another;
- schedule conflicts are reported before approval and persisted on the schedule history once confirmed;
- direct scheduled Visit creation records the Visit, crew, assignment history, schedule history and the
  Job's `NEW` -> `SCHEDULED` consequence;
- inactive technicians are refused and an unknown/other-organization Job is hidden as `404`.

Verification:

- `npm run test:e2e -- test/visit-requests.e2e-spec.ts` — PASS, 7 tests.
- `npm run lint` — PASS.
- `npx vitest run src/jobs/follow-up-visit-request.dto.spec.ts src/auth/permissions.spec.ts` — PASS,
  16 tests.
- `npm run typecheck` — FAILS only on the pre-existing nullable schedule assertions in
  `test/technician-home.e2e-spec.ts:351` and `:354`; no error remains in the new visit-request e2e
  coverage.
- `cd android && ./gradlew compileDebugKotlin` — PASS for the first Phase 2 Android slice.
- `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.schedule.ScheduleViewModelTest'` — PASS.
- `cd android && ./gradlew compileDebugAndroidTestKotlin` — PASS for instrumented test source compile.

## Phase 3 technician submission landed 2026-09-28

The technician half of the workflow now exists on Android. Until this landed, a completion whose outcome
implied further work reached the office only as **derived attention** (`FOLLOW_UP_NEEDS_SCHEDULING`,
`BR-078`): a manager saw "Job #N needs a follow-up visit" on Manager Home while the Schedule screen's
Requests lane stayed empty, because no client had a path to `POST /jobs/:id/visit-requests` at all
(`follow_up_visit_requests` had no writer outside the API tests). The rule was already confirmed — a
request is the technician's own proposal (`BR-FV-001`, `BR-FV-003`), never a consequence of an outcome —
so this slice implements the submission rather than deriving a request from a completion (`BR-042`).

| Surface | Action | Route | Capability |
| ------- | ------ | ----- | ---------- |
| Job Details, Visit actions row | **Request another visit** | `POST /jobs/:id/visit-requests` | `visits.request_follow_up` |

What was added:

- `JobDetailsApi.submitVisitRequest` + `JobDetailsRepository.requestFollowUpVisit`, answering
  `VisitRequestSubmitResult` — the **request**, not the Job, because a request is not a Visit and changes
  no Job (`BR-FV-002`), so nothing is read again afterwards. A refusal is classified through the same
  `toActionOutcome` the other actions use, so one failure vocabulary is reported (`BR-041`).
- `ui/jobs/RequestFollowUpVisitSheet.kt`: the proposal form — the window the technician suggests, why
  another attempt is needed and whether they would like to carry it out (`BR-FV-003`) — with **no crew**,
  because who performs the Visit is the office's decision (`BR-FV-004`). It reuses the schedule form's
  date/time controls, the same `scheduleVisitWindow` and the same "end after start" wording, so the two
  ways a window is stated cannot drift (`BR-041`). `RequestFollowUpDraft` holds the form's own validity so
  it is unit-tested rather than asserted through the layout.
- Job Details: the action is drawn on `visits.request_follow_up` (`BR-006`, `BR-011`) and requires a
  represented Visit, because the request names the field attempt it grew from (`BR-FV-008`). It is
  withheld while the represented Visit is under way. The Visit actions row now scrolls horizontally,
  because a session holding every capability the actions are drawn on can have more of them than a phone
  is wide (`Project.md` §11).
- `JobActionKind.FOLLOW_UP_REQUEST` and the snackbar copy: the report says the request reached the office,
  never that a visit was booked (`BR-FV-002`, `BR-FV-010`).
- English and French copy for every new string (`BR-028`).

**Online-only, and why** (offline standard §13.2): the request route accepts no client-generated
idempotency key and no conflict policy is decided for it (`BR-032`), so a proposal is never queued. It is
reported as **not sent** when the API cannot be reached rather than as waiting on the device, and
`docs/architecture/offline-first-architecture.md` §12's online-only list now names it.

Verification (Tier A — targeted; full lint/suites are the feature's, `qa.md` §3.1, and Android lint is not
run on this machine at the product owner's instruction):

- `cd android && ./gradlew compileDebugKotlin` — **PASS**.
- `cd android && ./gradlew assembleDebug` — **PASS**.
- `cd android && ./gradlew assembleDebugAndroidTest` — **PASS** (device-test sources compile, including the
  new `JobDetailsOverviewScreenTest` cases; no `adb` and no device command was run).
- `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.JobDetailsViewModelTest'
  --tests 'com.servora.android.ui.jobs.RequestFollowUpDraftTest'
  --tests 'com.servora.android.data.jobs.JobDetailsRepositoryTest'` — **PASS**, 131 tests (72 + 3 + 56),
  including the new submission, refusal, blank-reason and draft-validity coverage.
- `JobPhotoCaptureViewModelTest` reports 24 failures in this working tree. They belong to **tracker 056**
  (evidence → Visit), which is in flight and whose Android JVM verification that tracker records as not yet
  run: the failures are the `NO_VISIT` refusals 056 added ahead of the session check in the photo paths.
  Nothing this slice touches is involved, and every class this slice changes passes.

**Not done by this slice** (recorded, not invented): the technician's **Job-scoped** status feedback needs a
request read filtered by Job — the requests route is organization-wide for a reviewer and own-only for a
requester, with no Job filter — and `BR-FV-011`'s direct scheduling for a technician still depends on the
crew picker's technician read. (The own-only half of the status feedback landed on 2026-09-28; see the
progress item above and `docs/tracker/057-qa-issue-list-visit-workflow.md` §5.8.)

## Repository Findings

Existing components to reuse:

| Area | Reuse |
| ---- | ----- |
| Job read/details | `GET /jobs/:id`, `JobsService.findJobDetailsInOrganization`, `JobDetailsDto.visits` and Job Details Android models. |
| Visit scheduling | Existing `visits`, `visit_schedule_history`, `visit_technicians`, and `visit_technician_history` tables. |
| Conflict handling | Existing `ScheduleConflictError` and technician overlap detection in `JobsService`. |
| Manager schedule | `GET /schedule`, `ScheduleService`, and Android manager `ScheduleScreen/ViewModel`. |
| Technician schedule | Assigned-Visit scope through `VISIT_VIEW_ASSIGNED` and Android `TechnicianSchedule*`. |
| Permissions | `permissions`, `role_permissions`, API `PermissionCode`, Android `Permission`. |

Missing backend capabilities:

- A persisted follow-up request that is not a Visit.
- Request lifecycle states: `PENDING`, `NEEDS_CLARIFICATION`, `APPROVED`, `REJECTED`.
- API operations to submit, list, clarify, reject, approve-and-create, and directly create a Visit.
- Narrow scheduling/request permissions rather than relying on `JOB_UPDATE`.
- Transactional duplicate-approval protection.

Required permission changes:

- Add organization schedule read: `schedule.view_org`.
- Add Visit scheduling: `visits.create_schedule`, `visits.update_schedule`, `visits.assign_technicians`.
- Add follow-up request permissions: `visits.request_follow_up`, `visits.review_requests`.
- Keep assigned field work under existing `VISIT_VIEW_ASSIGNED`, `VISIT_UPDATE_ASSIGNED_STATUS`,
  `VISIT_ADD_NOTE`, and `VISIT_RECORD_OUTCOME`.

Database impact:

- Add `follow_up_visit_requests` with request fields, review fields, `created_visit_id`, status/version,
  and a partial unique index that allows one created Visit per approved request.
- Add capability catalogue rows and default grants: managers receive schedule/review/direct scheduling;
  technicians receive request permission only.

Expected files:

- API schema, migration, job types, permissions, DTOs, controller, service, development seed.
- Android permission enum.
- API/domain docs and this tracker.

Current implementation vs docs:

- Current Job creation already does **not** create a Visit.
- Schedule is currently a read workflow, not a scheduling editor.
- Visit reschedule/assignment still use broad `JOB_UPDATE`; Phase 1 narrows that capability surface.
- There is no follow-up request domain object yet.

## Verification Policy

Do **not** run Android lint for this tracker on this machine. The product owner is QA and the machine
overheats during full lint. Verification for agent-run Android work should prefer `./gradlew assembleDebug`
from `android/`, plus focused tests only when they are needed and lightweight.
