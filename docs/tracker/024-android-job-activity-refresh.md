# Tracker 024 — Job Activity refresh after a Job or Visit action (Android fix)

**Status: COMPLETE for the Android implementation; physical-device QA is the product owner's**

Date: 2026-09-14
Predecessor: `docs/tracker/022-android-job-activity-timeline.md`
Business rules: `BR-001`, `BR-058`, `BR-067`, `BR-069`, `BR-073`, `BR-074`, `BR-080`
Domain model: `docs/domain/job-visit-domain-model.md` §6 – §7
API contract: `docs/api/job-activity.md` §1, `docs/api/job-actions.md` §1 — **unchanged by this slice**
Design reference: `Figma/src/screens/JobDetails.tsx` (Manager view), `servora-job-details-spec.md`
Design system: `docs/design/android-design-system.md`

## Defect

On Job Details, a **+ Add update** appeared in **Job Activity** immediately, but **Assign** (and
**Reschedule**) did not: the timeline kept showing what the Job looked like before the action until the
screen was left and reopened.

## Cause

The write paths answer differently, and only one of them was being used:

- `POST /jobs/:id/visits/:visitId/notes` answers with the **refreshed Job Activity timeline**
  (`JobActivityDto`), and `JobDetailsViewModel.addActivityText` replaced `JobDetailsUiState.activity`
  with it — hence the immediate entry.
- `PATCH /jobs/:id/status`, `PATCH /jobs/:id/visits/:visitId/schedule` and
  `PUT /jobs/:id/visits/:visitId/technicians` answer with the **Job** (`JobDetailsDto`,
  `docs/api/job-actions.md` §1). `JobDetailsViewModel.submit` replaced `JobDetailsUiState.details` and
  left `activity` untouched, so the timeline was stale until the next `read()` of the Job (which is what
  re-entering the screen performs).

Nothing was missing on the API side: `assignVisitTechnicians` already wrote
`visit_technician_history` rows (`ASSIGNED` / `REMOVED` / `ROLE_CHANGED`), `rescheduleVisit` already
wrote a `visit_schedule_history` row, and `GET /jobs/:id/activity` already projected both. The defect
was a **client refresh gap**, not a missing event.

## Fix

`JobDetailsViewModel.submit` reads the given Job's Activity again once the action succeeded
(`readActivity(result.details.id)`), so the timeline is derived from the history the action just
recorded rather than assembled from the action's answer (`BR-001`, `BR-080`). Nothing else changed: no
API route, no DTO, no table, no capability, no business rule, no copy, no other screen.

| Item                                                              | Status            |
| ----------------------------------------------------------------- | ----------------- |
| The timeline is re-read after an applied status change            | Fixed             |
| The timeline is re-read after an applied reschedule               | Fixed             |
| The timeline is re-read after an applied crew change              | Fixed             |
| A refused or conflict-pending action re-reads nothing (`BR-067`)  | Unchanged (by design) |
| The **+ Add update** path keeps using the write's own answer      | Unchanged         |
| API routes, DTOs, schema, capabilities                            | Unchanged         |
| `docs/api/job-activity.md` §1, `docs/api/job-actions.md` §1       | Documented        |

## Decisions taken here

1. **The timeline is re-read, not patched.** The client could have inserted the new entry locally from
   the action it sent, but Job Activity is a projection of authoritative history (`BR-080`) and the API
   is the system of record (`BR-001`); a locally assembled entry would be a second definition of the
   same event, and it would have to guess an actor, a timestamp and a Visit sequence the client does not
   own. One extra read of a read the screen already performs is the smaller and the more correct change.
2. **The action contract is left alone.** Adding the timeline to the three action responses would change
   `docs/api/job-actions.md` for every consumer (including the Angular client) to fix a presentation
   refresh, and would make an action answer with two projections instead of the one it documents.
3. **A successful action is the trigger, and only a successful action is.** A refusal and a pending
   conflict change nothing, so there is no new history to project and the timeline is not asked for
   again (`BR-067`). A failed activity read is reported exactly as a failed first read is: the section
   shows its failure and its retry, and no stale previous timeline is left standing (`BR-001`).
4. **The stale timeline is not blanked while the re-read is in flight.** The section keeps the last
   timeline until the new one arrives, so an ordinary action does not flash a loading state over a list
   the user was already reading. A failed re-read replaces it with the section's failure state, which is
   the behaviour the first read already had.

## Files changed

```text
android/…/ui/jobs/JobDetailsViewModel.kt            re-read the Activity after an applied action
android/…/test/…/JobDetailsViewModelTest.kt         scripted Activity reads + 3 regression tests
docs/api/job-activity.md                            §1: a client re-reads the projection after an action
docs/api/job-actions.md                             §1: same, from the actions' side
docs/tracker/024-android-job-activity-refresh.md    this slice
```

## Regression coverage added

- `reads the Job Activity again after a crew change so the timeline shows it` — the reported defect: the
  assignment answer is a Job, and the timeline that follows the action holds the assignment entry.
- `reads the Job Activity again after a schedule change so the timeline shows it` — the same for
  `VISIT_RESCHEDULED`, which the defect report was unsure about.
- `does not read the Job Activity again when the action was refused` — the boundary: a refused action
  reads nothing a second time.

## Verification

- Android: `make android-test` (`./gradlew testDebugUnitTest`) — **BUILD SUCCESSFUL**;
  `JobDetailsViewModelTest` 19 tests, 0 failures.
- Android: `make android-lint` (`./gradlew lintDebug`) — **BUILD SUCCESSFUL** (5m 38s).
- Android: `./gradlew assembleDebug` — **BUILD SUCCESSFUL**; `./gradlew assembleDebugAndroidTest` —
  **BUILD SUCCESSFUL** (device-test sources compiled only).
- Regression proof: with the re-read removed, `reads the Job Activity again after a crew change so the
  timeline shows it` and `reads the Job Activity again after a schedule change so the timeline shows it`
  **failed** (`2 failures`); with it restored, all 19 pass.
- No `adb` and no device/emulator command was run (`qa.md` §7.3). No instrumented test was run: device
  QA is the product owner's.
- API: unchanged by this slice, so no API check was run.
- Physical-device QA: **Awaiting product owner** — see the runbook below.

### Android QA runbook

1. Sign in as Manager and open a Job whose represented Visit is `SCHEDULED` with at least one technician.
2. Note the top of **Job Activity**, then **Assign** a different crew (or add/remove a technician) and
   confirm.
   Expected: a new entry appears at the top of Job Activity — "X was assigned as Lead" / "X was removed"
   / "X changed role from Technician to Lead" — without leaving the screen.
3. **Reschedule** the Visit and confirm.
   Expected: a "Visit rescheduled" entry appears at the top of Job Activity without leaving the screen.
4. Change the **Job status** and confirm.
   Expected: an "Updated job status to …" entry appears at the top of Job Activity without leaving the
   screen.
5. Add a **+ Add update**.
   Expected: the note entry still appears immediately, exactly as before.
6. Re-open the Job.
   Expected: the same entries, in the same order, plus none added twice.
7. Switch to French and dark mode, and repeat step 2.
   Expected: the new entries are French and follow the dark scheme.
