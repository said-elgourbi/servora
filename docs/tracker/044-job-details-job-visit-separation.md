# Tracker 044 — Job Details: the Job and its Visits presented apart

> **The activity presentation of this slice was superseded on 2026-09-18.** The activity it placed in
> three places — the represented Visit's inside the Current visit card, each other Visit's inside its
> history row, and the Job's own in a Job updates section — is now **one Job Activity section** holding a
> foldable group per Visit (`Visit N · date`) plus a **General job updates** group, so a Job's field
> attempts read as one sequence and no Visit is stated twice. See
> `docs/tracker/045-android-job-activity-visit-groups.md`. Everything else this slice established — the
> additive `visits` field on `GET /jobs/:id`, the crew and `Visit N` derivations, the grouping rules and
> the page's Job/Visit hierarchy — stands unchanged.

**Status: IMPLEMENTED** (the API `visits` field with its tests and docs, the Android data and
presentation layers, the redesigned screen and the destination's wiring, and the focused tests;
**Android physical-device QA is the product owner's**, `qa.md` §7)

Date: 2026-09-18

Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-010`, `BR-011`, `BR-012`, `BR-020`,
`BR-022`, `BR-028`, `BR-041`, `BR-042`, `BR-047`, `BR-051`, `BR-052`, `BR-056`, `BR-058`, `BR-059`,
`BR-066`, `BR-068`, `BR-071`, `BR-072`, `BR-073`, `BR-074`, `BR-077`, `BR-080`, `BR-081`, `BR-092`,
`BR-093`
Domain model: `docs/domain/job-visit-domain-model.md` §6 (Visits, and the `Visit N` sequence this slice
now derives in one place)
API contract: `docs/api/job-details.md` §3.1, §3.2 — **extended by this slice** (`visits`)
Design reference: none — no Figma screen exists for the redesigned page, so the established Servora
Material 3 visual language is followed (`docs/design/android-design-system.md`)

## The problem this slice solves

The Job Details screen presented **one** Visit with the whole of Job Activity beneath it. Job Activity
is one timeline over the Job's own events and every Visit's (`BR-080`), so entries belonging to other
Visits sat under the Visit on screen and read as that Visit's own account. The page also had no way to
show a Job's earlier field attempts at all: `GET /jobs/:id` reported the represented Visit and its
crew, and nothing else (`BR-081`).

The redesign presents the two things separately, in the hierarchy the page is read in:

1. **Job overview** — what belongs to the Job: its number, title, description, lifecycle status, the
   Customer it is for and the address it preserves (`BR-048`, `BR-052`, `BR-056`, `BR-058`).
2. **Current visit** — the Visit that represents the Job (`BR-081`), with its schedule, its status, its
   crew, the actions that address **it**, and **its own** activity (`BR-068`, `BR-072`, `BR-073`,
   `BR-074`, `BR-080`).
3. **Visit history** — the Job's other Visits as folded rows stating the date, the status and the crew
   each carries, expanding to that Visit's own details and activity (`BR-047`, `BR-071`).
4. **Job updates** — the events that belong to the Job itself and to no Visit, which is where the Job's
   recorded evidence is read from (`BR-015`, `BR-080`).

## What was implemented

| Layer | Change |
| ----- | ------ |
| API read | `api/src/jobs/visit-assignment.ts` gains `readJobVisits` — every Visit of one Job in the Job's own visit sequence, with the status, schedule and version each carries. It is the **one** definition of the `Visit N` label: `job-activity.ts` now derives its `visitSequence` through it, so two projections of the same Job cannot number its Visits differently (`BR-041`). |
| API projection | `api/src/jobs/job-details.dto.ts` gains `JobDetailsVisitSummaryDto` and `JobDetailsDto.visits`, plus the internal `JobVisitSummary`. Additive and backward compatible: the field is always present (`[]` for a Job with no Visit), and the crew mapping became one `toTechnicianDto` rather than three copies. |
| API service | `JobsService.findJobDetailsInOrganization` reads the Job's Visits and their crews, with **one** crew query for all of them (`BR-068`, `dev.md` §6 — no per-Visit query). |
| Android data | `JobDetailsVisitSummaryDto` + `JobDetailsDto.visits` (defaulted to `emptyList()` so a working-set row written before the field is still readable, offline standard §10); `JobDetailsVisitSummary` in the domain model; `JobDetailsRepository` maps them, and a Visit status or crew role this build cannot name fails the read rather than being dropped (`BR-042`). |
| Android presentation | New `ui/jobs/JobDetailsOverviewModel.kt`: `JobOverview` (the Job's own information), `groupJobActivity` (the activity split by what it belongs to), `visitHistory` (the Job's other Visits, most recent first, each with its own activity) and `visitStart`. It holds no Android or Compose type, so the rules the page is built on are verifiable without a device. |
| Android UI | New `ui/jobs/JobDetailsOverviewScreen.kt`: the four labelled sections above, the folded visit-history rows with their expand/collapse controls, and the same actions, sheets, dialogs and evidence surfaces the screen keeps. |
| Reused components | `ActivityTimeline`, `ActivityLoading` and `ActivityFailure` in `JobActivitySection.kt` became `internal` (with an optional `modifier`), so the Job's section and each Visit's section draw the one timeline component rather than three copies (`dev.md` §1). The old screen's own use of them is unchanged. |
| Navigation | The `JOB_DETAIL` destination draws `JobDetailsOverviewScreen`. **`JobDetailsScreen` is retained, unmodified, in the same package**: restoring the previous presentation is the import and the single call in `ServoraNavHost`. |
| Strings | English and French for every new string (`BR-028`). |
| Tests | API: `job-details.dto.spec.ts` (2 new), `job-details.e2e-spec.ts` (2 new). Android JVM: `JobDetailsOverviewModelTest` (11 new) and `JobDetailsRepositoryTest` (2 new + 1 assertion). Android device: `JobDetailsOverviewScreenTest` (9 new; compiled here, run by the product owner). |

## Why the API contract changed at all

**The existing response did not carry the information the history needs.** `GET /jobs/:id` reported the
single Visit that represents the Job (`BR-081`) and its crew; a Job's earlier Visits, their schedules
and their crews were readable nowhere. Job Activity carries a `visitSequence` per event
(`docs/api/job-activity.md` §3.3) but no Visit's date, status or crew, so a history built from it would
have had to derive business facts from an event log — which `BR-042` forbids.

The change is therefore **additive and minimal**: one field on the read that already exists, carrying
the Visit facts the read's own records already hold, in the shapes it already uses (`selectedVisit`'s
status/schedule/version, `technicians`' crew). No route was added, no permission changed, no database
change was needed, and nothing existing changed shape or meaning.

## Presentation rules this slice decided, and where they come from

- **The current Visit is the represented Visit** — the API's own `selectedVisit` choice (`BR-081`), not
  a selection the client recomputes.
- **History is every other Visit**, most recent scheduled first, because that is what a row states. A
  Visit with **no** schedule — a `DRAFT` field attempt (`BR-072`) — follows the scheduled ones rather
  than being given an invented position, and two Visits of the same time are ordered by the Job's own
  visit sequence so the order is stable. Which Visit is current versus history, and the history's order,
  are presentation: `BR-081` itself records that ordering is such a concern.
- **Activity is grouped by the API's own answer.** An event whose `visitSequence` names one of the Job's
  Visits belongs to that Visit; an event with no Visit belongs to the Job. An event whose sequence names
  no Visit of the Job — which an answer predating `visits` produces — is reported as Job-wide rather
  than dropped: the client cannot attribute it, and dropping it would hide history (`BR-042`, `BR-080`).
- **Job-level evidence stays with the Job.** A photo is recorded against the Job (`BR-015`, `BR-051`), so
  the photo gallery is drawn in the Job's own section, from the Job-wide entries.
- **The rows are folded by default and state what a reader scans them for** — which Visit, when, its
  status and its crew — because the page is read for what the Job needs next (`BR-012`).
- **Nothing was invented.** No permission, no status, no event kind, no schema change.

## Not changed

- `JobDetailsScreen.kt` — untouched, kept as the reference implementation and the restore point.
- `docs/domain/job-visit-domain-model.md` — no change: the read's field list is the API contract's
  (`docs/api/job-details.md` §3, updated here), and the domain model's `Visit N` rule (§6) already states
  the sequence this slice now derives in one place.
- Every existing route, permission, database table and migration.
- Every existing action: the screen offers the same Job status control, Visit status control,
  completion, reschedule, crew management, Add update, evidence tray/notice, evidence removal and photo
  viewer, with the same capability gates and the same test tags (`JobDetailsTag`,
  `JobDetailsContentTag`, `JobDetailsLoadingTag`, `JobDetailsFailureTag`, `JobDetailsRetryTag`,
  `JobDetailsScheduleTag`, `JobDetailsAddressTag`, `JobDetailsCustomerTag`, the technician and Lead
  tags, `JobDetailsAddActivityTag`, `JobDetailsRescheduleActionTag`,
  `JobDetailsManageTechniciansActionTag`, `JobDetailsVisitActionsTag` and the activity tags).

## Verification

**Scope** (`qa.md` §3.1): **Tier B — full, per affected application**, because this slice is complete and
touches a shared contract, the API service, the Android data layer and a screen. Both applications are
affected, so both were swept in full.

| Check | Command | Result |
| ----- | ------- | ------ |
| API typecheck | `cd api && npm run typecheck` | **PASS** for every file this slice changed. The two reported errors are the **pre-existing** `test/technician-home.e2e-spec.ts` `scheduledEnd` possibly-null ones recorded by trackers 037/042; none of this slice's files is among them and none was silenced. |
| API lint | `cd api && npm run lint` | **PASS** — 0 warnings, 0 errors (206 files). |
| API unit | `cd api && npm test` | **PASS** — 55 files, **492 tests**, 0 failures. |
| API e2e | `cd api && npm run test:e2e` (against the running PostgreSQL foundation) | **PASS** — 22 files, **364 tests**, 0 failures. The changed suite on its own: `job-details` 26 tests, **including the 2 new ones**; `job-activity` 7 tests, which cover the shared `Visit N` derivation `job-activity.ts` now reads through `readJobVisits`. |
| API build | `cd api && npm run build` | **PASS**. |
| Android compile | `cd android && ./gradlew compileDebugKotlin` | **PASS**. |
| Android JVM tests | `cd android && ./gradlew testDebugUnitTest` | **PASS** — 63 suites, **705 tests**, 0 failures. The affected packages on their own: 292 tests, 0 failures, of which `JobDetailsOverviewModelTest` 11 (new) and `JobDetailsRepositoryTest` 45 (2 new). |
| Android lint | `cd android && ./gradlew lintDebug` | **PASS** — **0 errors, 51 warnings**, one fewer than before this slice. The only warning in a file this slice touched is the **pre-existing** `JobActivitySection.kt:112` `ModifierParameter` one (a signature this slice did not change; the app carries seven other pre-existing occurrences, `JobDetailsScreen.kt:249` included). The new screen initially carried that warning alongside `photoImages` and was written to the convention instead, and one string this slice added but did not render (`job_details_visit_status_label`) was **removed** rather than shipped unused — which is the net −1. |
| Android build | `cd android && ./gradlew assembleDebug` | **PASS**. |
| Android device-test sources | `cd android && ./gradlew assembleDebugAndroidTest` | **PASS** — `JobDetailsOverviewScreenTest` compiles into the androidTest APK. |
| Android physical device | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). The agent ran no `adb` and no device or emulator command. Command the product owner may run: `cd android && ./gradlew connectedDebugAndroidTest`. |

## Manual QA runbook (product owner)

1. Sign in as a Manager and open a Job that has **two Visits** — a closed one and a current one.
2. Read the page top to bottom and check the four labelled sections: **Job overview** (number, title,
   description, Job status, Customer, address), **Current visit** (date, Visit status, crew, Reschedule,
   Manage technicians), **Visit history** (folded rows), **Job updates**.
3. Tap a history row: it expands to that Visit's date, its crew and **its own** activity, and the row
   itself does not move. Tap again: it folds. Open one row and then the other: they open independently.
4. Confirm the **current Visit's** activity is under the Current visit section and the other Visit's
   entries are **not**; confirm the Job's own events (Job status changes, photos) appear **only** under
   Job updates.
5. Rotate the device with a row expanded: it stays expanded.
6. Reopen the Job with the network off: the last reported Job is presented with its offline notice, and
   the sections are still separated.
7. Turn TalkBack on and traverse a history row: it announces the row's contents and an expand/collapse
   action naming **which** Visit it belongs to.
8. Re-check the actions the previous screen offered: Job status, Visit status/completion, reschedule,
   manage technicians, Add update, photo tray, evidence removal and the photo viewer.

**Expected:** every existing action still works, the Visit's history is reachable, and no Visit's
activity is presented as the Job's or another Visit's.

## How the redesign distinguishes the four things

- **Job details** are one labelled card holding only what the Job owns: its number, title, description,
  Job status, Customer (with `BR-092`'s contact block when the API includes it) and its preserved address
  snapshot. Nothing in it comes from a Visit (`BR-048`, `BR-052`, `BR-056`, `BR-058`).
- **The current visit** is a section of its own, labelled as a Visit's, holding the represented Visit's
  schedule, its status control, its crew, its actions and **its own activity** — so the timeline under it
  cannot be read as the Job's (`BR-059`, `BR-081`).
- **Previous visits** are folded rows in the Visit history section, each stating which Visit it is, when
  it was for, its status and its crew, expanding to that Visit's details and its own activity (`BR-047`,
  `BR-071`).
- **Activity** is never one list beside one Visit: `visitSequence` splits it, each Visit's events are
  drawn inside that Visit's section, and the Job's own events are drawn in the Job updates section — with
  an event the API leaves unattributable reported as Job-wide rather than dropped (`BR-080`).

## Files changed

| Layer | Files |
| ----- | ----- |
| API | `api/src/jobs/visit-assignment.ts`, `api/src/jobs/job-activity.ts`, `api/src/jobs/job-details.dto.ts`, `api/src/jobs/jobs.service.ts`, `api/src/jobs/job-details.dto.spec.ts`, `api/test/job-details.e2e-spec.ts` |
| Android data | `android/app/src/main/java/com/servora/android/data/jobs/JobDetailsDtos.kt`, `.../data/jobs/JobDetailsRepository.kt`, `.../domain/model/JobDetails.kt` |
| Android UI | `.../ui/jobs/JobDetailsOverviewModel.kt` (**new**), `.../ui/jobs/JobDetailsOverviewScreen.kt` (**new**), `.../ui/jobs/JobActivitySection.kt` (three declarations made `internal`, plus an optional `modifier`), `.../ui/navigation/ServoraNavHost.kt` (the destination's import and call) |
| Android resources | `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-fr/strings.xml` |
| Android tests | `android/app/src/test/java/.../ui/jobs/JobDetailsOverviewModelTest.kt` (**new**), `.../data/jobs/JobDetailsRepositoryTest.kt`, `android/app/src/androidTest/java/.../ui/jobs/JobDetailsOverviewScreenTest.kt` (**new**) |
| Documentation | `docs/api/job-details.md` (§3.1, §3.2), `README.md` (the current-state pointer), this tracker |

**Deliberately not changed:** `ui/jobs/JobDetailsScreen.kt` (the retained screen — `git diff` shows it
untouched by this slice), every route, permission, database table and migration.

## Hygiene

No Gradle or Android daemon was left running: `make android-stop` was run after the last Android command,
and the daemons the verification started are stopped. The foundation stack (`make up`) was **not** touched
and no volume was deleted; the e2e run needed no new migration. Every temporary build log this task wrote
was deleted, and `git status` shows only the files listed above.
