# Tracker 038 — Manager Schedule (Android) and its API read

**Status: API and Android implemented; automated verification PASS; physical-device QA is the product
owner's**

**Refined by `docs/tracker/039-android-manager-schedule-ux.md`:** the screen's presentation was polished —
the card's information hierarchy, a timeline rail, a "now" cue, a searchable technician filter and a
compacted top section. The read, the routes, the lanes, the permissions and every business rule this
tracker records are unchanged.

Date: 2026-09-17
Predecessor: `docs/tracker/037-technician-field-experience.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-008`, `BR-010`, `BR-011`, `BR-012`, `BR-013`,
`BR-023`, `BR-024`, `BR-028`, `BR-041`, `BR-042`, `BR-052`, `BR-054`, `BR-056`, `BR-057`, `BR-059`,
`BR-062`, `BR-066`, `BR-068`, `BR-070`, `BR-071`, `BR-072`, `BR-073`, `BR-074`, `BR-080`
Domain model: `docs/domain/job-visit-domain-model.md` §8.4, §9.1, §9.3, §10
API contract: `docs/api/schedule.md`
Design reference: `Figma/src/imports/pasted_text/servora-scheduler-design.md`
ADR: `docs/decisions/020-manager-schedule-and-unassigned-lane.md`

## Scope

The Schedule destination has been an empty placeholder since the shell was built
(`docs/decisions/010-android-navigation.md`), and the manager home's "See all" led to it. This slice
makes it the mobile dispatch view the design specifies: a week strip, the selected day's agenda, a
technician filter and the work that still has no crew — for **Manager** users of the Android
application, served by a new read.

| Item | Status |
| --- | --- |
| `GET /schedule` — one request serving the day, the filter's options and the unassigned lane | Implemented |
| Authorization: `customers.view`, the existing Job/Visit capability | Implemented |
| Day resolved by the API from the client's `date` + `timeZone`, through the shared day module | Implemented |
| The day's Visits, chronologically, excluding `CANCELED`/`NO_SHOW` | Implemented |
| The technician filter, applied to the day's rows inside the tenant scope | Implemented |
| The unassigned lane: open Visits with no current assignment, with the API's own total | Implemented |
| The overdue condition, decided by the API from the server clock | Implemented |
| Week strip: 7 days, swipeable week-by-week, selected day emphasised, today marked | Implemented |
| Date header as the date selector → the platform month calendar (jump-to-date only) | Implemented |
| `Schedule` / `Unassigned` selector with the unassigned count badge | Implemented |
| Technician filter as a bottom sheet (`All technicians` + the organization's technicians) | Implemented |
| Cards: time window + Visit status, crew, Job title, address, customer | Implemented |
| A card opens the existing Job Details destination | Implemented |
| Empty day / all-assigned / loading / failure+retry states | Implemented |
| EN/FR copy for every new string | Implemented |
| API unit specs (the projection and its query parsing) and API e2e (HTTP + derivation) | Implemented |
| Android JVM tests (repository, ViewModel, week logic) and Compose device tests | Implemented |
| **Technician's own schedule ("My Schedule")** | **Implemented by `docs/tracker/041-android-technician-schedule.md`** — the same read resolved for the caller's own scope, with its own screen |
| **Per-day work indicators on the week strip** | **Not implemented — needs a week-scoped count read** |
| **Assignment / rescheduling / conflict actions on this screen** | **Not implemented — the Job screen's actions** |
| **The future web dispatcher (lanes, time grid, drag-and-drop)** | **Not implemented — explicitly out of scope** |

## What the slice adds

- **API**: `api/src/schedule/` — `schedule.dto.ts` (the projection, its two derived sets and its
  order), `schedule-query.ts` (`date`, `membershipId`), `schedule.service.ts` (the day's rows, the
  unassigned lane, the crew), `schedule.controller.ts` (`GET /schedule`) and `schedule.module.ts`.
  `api/src/home/home-day.ts` gained `LocalDate`, `localDateIn`, `formatLocalDate` and
  `resolveLocalDayWindow`, with `resolveDayWindow` expressed over the last of them, so "today" and "a
  chosen date" are resolved by one implementation (`BR-041`).
- **Android**: `domain/model/Schedule.kt`, `data/schedule/` (API, DTOs, result, repository),
  `ui/schedule/` (`ScheduleUiState` with the week logic, `ScheduleViewModel`, `ScheduleScreen`,
  `ScheduleTopSection`), the DI bindings, the Schedule destination in the shell, and EN/FR strings.

## What is reused rather than re-written

- The **day resolution** both home reads use (`api/src/home/home-day.ts`) and the `timeZone` parameter
  (`api/src/home/home-query.ts`).
- The **not-work-of-the-day** status set (`NOT_DAY_WORK_VISIT_STATUSES`) and the **overdue** condition
  (`isVisitOverdue`) from `api/src/home/home-visit-conditions.ts`, and `BR-062`'s historical set
  (`HISTORICAL_VISIT_STATUSES`) from `api/src/jobs/job.types.ts`.
- The **crew read** (`readAssignedTechnicians`) and the **technician list** (`TechniciansService`), so
  Lead-first ordering and the filter's options have one definition each (`BR-068`, `BR-041`).
- The Android **status pill**, **scheduled-time formatter**, **address line**, **date formatter** and
  **crew summary** from `ui/home/HomePresentation.kt`; the crew summary was extracted from the manager
  home so a Visit is described the same way on every screen (`BR-041`).
- The **Job Details destination** for a card's tap, and the shell's one contextual top bar.

## Offline posture — the `§13` record

`dev.md` §10 and `docs/architecture/offline-first-architecture.md` §13 require a new read to state what
it does about connectivity, and `ADR-020` D6 records the answer:

| Question (`§13`) | Answer |
| --- | --- |
| Does the read have to survive lost connectivity? | **No.** It is the office's dispatch view over the organization's own work; the standard's adopters are the technician's reads and mutations (`offline-first-architecture.md` §12), and `BR-013` names "relevant field work". |
| Is there a local store? | No. The last answer the backend reported is held by the ViewModel for the session, as the manager home does. |
| What does a failed read show? | The failure with a retry. A day that could not be read is never presented as a known day (`BR-042`). |
| Are there any mutations? | **None.** The screen schedules nothing: assignment, rescheduling and status actions live on the Job Details screen and are the API's own routes (`BR-066`, `BR-072`, `BR-073`). |

Adopting the working set for this read later is a change to `ADR-020` D6, not a silent addition.

## Open questions recorded by this slice

| # | Question | What it blocks |
| - | -------- | -------------- |
| 1 | Is the unassigned lane day-scoped? It is not today, because a Visit with no agreed time has no day (`ADR-020` D2). | The lane's query; not the payload's shape |
| 2 | What else should "unassigned" exclude, if anything? | The lane's membership rule |
| 3 | The `jobs.*` capability set (`BR-006`, `BR-008`). | Re-guarding `GET /schedule` (and `GET /home/manager`, `GET /customers/:id/jobs`) |
| 4 | The technician's own schedule. | A field-scoped read and its own screen (`ADR-019` D2) |
| 5 | Conflict visualisation on the day (`BR-070`). | A second definition of a rule the scheduling action owns |
| 6 | Per-day work indicators on the week strip. | A week-scoped count read |
| 7 | Whether a week begins on the day the device's language begins it (the platform's answer) or on a Servora-wide first day. | Which week the strip draws; today it follows the device's language (`BR-028`) |

## Manual QA sketch (the product owner runs this on a phone)

Android QA — Manager Schedule

1. Sign in as a Manager.
2. Open **Schedule**.
3. Confirm the agenda shows today's Visits, chronologically, with the time window, the status chip,
   the crew, the job title, the address and the customer.
4. Tap another day in the week strip; confirm the agenda follows the selected day and that the strip
   marks it.
5. Swipe the strip left and right; confirm the weeks move without the calendar being opened.
6. Tap the date header; pick a date a few weeks away; confirm the picker closes, the strip moves to
   that week and that day's agenda is shown.
7. Tap **Today**; confirm the strip and the agenda return to today.
8. Choose a technician in the filter; confirm the day narrows to that technician's Visits and that
   **All technicians** restores the whole day.
9. Switch to **Unassigned**; confirm the lane lists the work with no crew, that the badge matches the
   number of items (or reports "more" when the list is capped), and that the lane does not change when
   another day is selected.
10. Tap a card; confirm the Job Details screen opens for that Job.
11. Turn connectivity off and open Schedule; confirm the failure state, then confirm **Try again**
    re-reads once connectivity returns.

Expected:

- The date header, the strip and the calendar always agree on which date is shown.
- The status chip is the **Visit**'s; no Job status appears on a card.
- Nothing on this screen schedules, assigns or reschedules.
- EN and FR copy, and light and dark themes, are correct.

## Phase log

| # | Date | What was done | Files | Verification |
| - | ---- | ------------- | ----- | ------------ |
| 1 | 2026-09-17 | The decisions this slice rests on: the read and its day resolution, the unassigned lane's definition, the reuse of `customers.view`, the calendar as navigation, the office-only destination, and the online-only posture. | `docs/decisions/020-manager-schedule-and-unassigned-lane.md` | Reviewed against `BR-042`, `BR-068`, `BR-071`, `BR-072` |
| 2 | 2026-09-17 | The API read: the projection and its two derived sets, the query parsing, the service, the controller and the module; the shared day resolution gained a named local date. | `api/src/schedule/*`, `api/src/home/home-day.ts`, `api/src/app.module.ts`, `docs/api/schedule.md` | **Tier B for the API.** **Typecheck: PASS with the two errors that predate this slice** (`test/technician-home.e2e-spec.ts:351,354` — verified against a clean worktree at `HEAD`); no new error. **Lint: PASS** (`npm run lint` — 0 warnings, 0 errors). **API unit: PASS** (`npm test` — 461 tests in 53 files, including `schedule.dto.spec.ts`'s 7 and `schedule-query.spec.ts`'s 8; the existing `home-day.spec.ts` still passes unchanged after the refactor). **API e2e: PASS** (`npm run test:e2e` — 327 tests in 21 files against the running foundation stack, including `schedule.e2e-spec.ts`'s 13: `401`, `403` for a field-only session, the three `400`s, the requested day with its window, order, address and Lead-first crew, the overdue condition on a past day and its absence on a future one, `CANCELED`/`NO_SHOW` excluded with `COMPLETED` kept, the technician filter and its own options, the unassigned lane and its day-independence, the Job-address fallback, the tenant boundary, the archived customer, and an office caller who also holds the field capability). **Build: PASS** (`npm run build`). |
| 3 | 2026-09-17 | The Android client: the domain model, the data layer, the ViewModel and the screen, wired into the shell behind the capability its read uses, with EN/FR copy. | `android/.../domain/model/Schedule.kt`, `android/.../data/schedule/*`, `android/.../ui/schedule/*`, `android/.../di/*`, `android/.../ui/customers/CustomersScreen.kt`, `android/.../ui/auth/AuthFlowScreen.kt`, `MainActivity.kt`, `res/values*/strings.xml`, `androidTest/.../ui/schedule/ScheduleScreenTest.kt`, `androidTest/.../ui/navigation/ServoraHomeNavigationTest.kt` | **Tier B for Android.** **Compile: PASS** (`compileDebugKotlin`). **Unit: PASS** (`testDebugUnitTest` — 623 tests in 58 files, 0 failures, including the new `ScheduleRepositoryTest`'s 7, `ScheduleViewModelTest`'s 10 and `ScheduleWeekTest`'s 5). **Lint: PASS** (`lintDebug`; the report's 49 pre-existing findings include none in this slice's files). **Build: PASS** (`assembleDebug`). **Device-test sources: PASS** (`assembleDebugAndroidTest`; **no `adb` and no device command was run**, `qa.md` §7.3 — the Compose tests are the product owner's to run, below). **Physical device: AWAITING PRODUCT OWNER.** |

