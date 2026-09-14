# Tracker 022 — Job Activity timeline (API read + Android)

**Status: COMPLETE for the API and Android implementation; physical-device QA is the product owner's**

Date: 2026-09-14
Predecessor: `docs/tracker/021-android-job-details-status-polish.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-020`, `BR-028`, `BR-041`, `BR-042`, `BR-058`,
`BR-067`, `BR-068`, `BR-073`, `BR-074`, `BR-077`, `BR-078`, `BR-080`
Domain model: `docs/domain/job-visit-domain-model.md` §6, §7
API contract: `docs/api/job-activity.md` — new in this slice
Design reference: `Figma/src/screens/JobDetails.tsx` (Manager view), `servora-job-details-spec.md`
Design system: `docs/design/android-design-system.md`

## Scope

The Job Details **Job Activity** section (`BR-080`), end to end. It adds a read-only Activity read over
the histories the domain already keeps, a shared event vocabulary, and the Android timeline that
presents it. It adds no write, no table, no capability and no business rule.

| Item                                                                 | Status      |
| -------------------------------------------------------------------- | ----------- |
| `GET /jobs/:id/activity`, guarded by `customers.view`                 | Implemented |
| One unified, newest-first list over Job-level and Visit-level events  | Implemented |
| A stable event vocabulary, defined once in the API (`BR-041`)         | Implemented |
| A derived, human-readable `Visit N` sequence (never a database id)    | Implemented |
| The Android timeline: marker, connecting line, action and metadata    | Implemented |
| A Visit-level entry states its Visit; a Job-level entry states none   | Implemented |
| Empty state, loading state, failure state and retry                   | Implemented |
| Bottom clearance for the floating Add update action                  | Implemented |
| EN/FR copy for the section and every event label                      | Implemented |
| API e2e, Android repository/ViewModel/Compose tests                   | Implemented |
| API, schema, capabilities and offline behaviour                       | Unchanged   |

## Decisions taken here

1. **The event vocabulary is the read's contract, and it is closed.** Eleven stable codes project the
   eight append-only histories the domain already keeps (`job_status_history`, `job_property_history`,
   `job_customer_history`, `visit_status_history`, `visit_schedule_history`,
   `visit_technician_history`, `visit_outcome_history`, `visit_notes`). The API returns codes and the
   client resolves localized labels (`BR-028`, `BR-041`); no presentation text crosses the boundary. See
   `docs/api/job-activity.md` §3.2 for the table. This resolves the event-vocabulary open question
   `BR-080` left and records it here rather than in a later slice.

2. **The Visit sequence is a presentation label, not an identifier.** `docs/domain/job-visit-domain-model.md`
   §6 deliberately has no Visit number column. The read derives `Visit 1`, `Visit 2`, … from the Visit's
   creation order within the Job, and clients never see a database id. It is never stored and never a
   cross-system key (`BR-052`, `BR-042`).

3. **A note's text is its entry's primary text.** The Figma's human update leads with the author; the
   screen instead leads with the action — for a note, the note's own text — and states the member, time
   and Visit context as secondary metadata, which is the reading the requirement asked for
   (`Visit 2 · John Smith · 2:14 PM`).

4. **The Add update action stays out of scope.** The write (where an update lives, and who may add one)
   is the open question recorded below, so the screen draws no floating action. It does reserve bottom
   clearance for one, so the last entry is never covered when that action lands.
## Files changed

```text
api/src/jobs/job-activity.ts                 event vocabulary, DTO, projection read
api/src/jobs/jobs.service.ts                 findJobActivityInOrganization (existence guard)
api/src/jobs/jobs.controller.ts              GET /jobs/:id/activity
api/test/job-activity.e2e-spec.ts            e2e coverage
docs/api/job-activity.md                     this read's contract
android/…/domain/model/JobActivity.kt        JobActivityKind, JobActivityEvent
android/…/data/jobs/JobActivityDtos.kt       wire contract
android/…/data/jobs/JobDetailsApi.kt         jobActivity()
android/…/data/jobs/JobDetailsRepository.kt  loadJobActivity + mapper
android/…/data/jobs/JobDetailsResult.kt      JobActivityResult
android/…/ui/jobs/JobDetailsUiState.kt       activity, activityFailure, showsActivityLoading
android/…/ui/jobs/JobDetailsViewModel.kt     readActivity, retryActivity
android/…/ui/jobs/JobActivitySection.kt      the timeline and its states
android/…/ui/jobs/JobDetailsScreen.kt        the section, onRetryActivity, bottom clearance
android/…/ui/navigation/ServoraNavHost.kt    onRetryActivity wiring
android/…/res/values/strings.xml             Job Activity copy (EN)
android/…/res/values-fr/strings.xml          Job Activity copy (FR)
android/…/test/…/JobDetailsRepositoryTest.kt repository coverage
android/…/test/…/JobDetailsViewModelTest.kt  ViewModel coverage
android/…/androidTest/…/JobDetailsScreenTest.kt Compose coverage
docs/tracker/022-android-job-activity-timeline.md   this slice
```

## Open questions recorded by this slice

| # | Question | Rule | How it is handled now | Blocks |
| - | -------- | ---- | --------------------- | ------ |
| 1 | Where a user update lives — a Visit note or a Job note — and who may add one | `BR-027`, `BR-053` | No Add update action is drawn; the timeline only reads the notes already written | The **+ Add update** write |
| 2 | Photos, audio and files as activity evidence | `BR-027` | No tables exist for them, so no evidence kind is invented | Rich evidence entries |
| 3 | Whether a long history needs pagination | `BR-080` | The read returns the whole history, newest-first | Very large histories |
| 4 | The `jobs.*` capability set | `BR-006`, `BR-008` | The read reuses `customers.view`, as `GET /jobs/:id` does | Re-guarding this read |

## Verification

- API: `tsc --noEmit`, `oxlint`, unit tests (363 passed), e2e `test/job-activity.e2e-spec.ts` (5 passed).
- Android: `testDebugUnitTest` (passed), `lintDebug` (passed), `assembleDebug` and
  `assembleDebugAndroidTest` (built).
- Physical-device QA: **Awaiting product owner** — see the runbook below.

### Android QA runbook

1. Open a Job with activity from a Manager account.
   Expected: below the technicians, a **Job Activity** section shows a vertical timeline, newest first.
2. Read a note entry.
   Expected: its text is the entry's primary line, with `Visit N · member · time` beneath it, an
   initials avatar on the left and a connecting line down to the next entry.
3. Read a Job status or Visit status entry.
   Expected: a small dot marker, an action like "Updated job status to In progress", and metadata with
   no Visit label for a Job-level event, or a `Visit N` label for a Visit-level event.
4. Open a Job with no activity.
   Expected: "No activity yet — Updates from the team will appear here."
5. Switch to French and dark mode.
   Expected: the labels are French and the colours follow the dark scheme.

