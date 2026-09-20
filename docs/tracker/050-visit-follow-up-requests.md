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
| 3. Technician Android workflow | TODO | Request another Visit from Job Details, direct scheduling for authorized technicians, and request status feedback. |
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
- [ ] Add manager approval/direct scheduling Android forms with crew/date-time input and conflict confirmation.
- [ ] Add technician request submission/status feedback from Job Details.

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
