# Tracker 045 — Job Activity, grouped by the Visit each update belongs to

**Status: IMPLEMENTED** (the presentation model, the Job Activity section, the English and French strings
and the focused tests; **Android physical-device QA is the product owner's**, `qa.md` §7)

Date: 2026-09-18

Business rules: `BR-001`, `BR-011`, `BR-012`, `BR-015`, `BR-028`, `BR-041`, `BR-042`, `BR-047`, `BR-051`,
`BR-052`, `BR-059`, `BR-068`, `BR-071`, `BR-072`, `BR-080`, `BR-081`

Supersedes, for the activity presentation only: `docs/tracker/044-job-details-job-visit-separation.md`
Design reference: none — the redesigned page has no Figma screen, so the established Servora Material 3
visual language and its disclosure convention are followed (`docs/design/android-design-system.md`)

## What this slice changes

The redesigned page presented one Job Activity read in three places: the represented Visit's activity
inside the **Current visit** card, each other Visit's activity inside its **Visit history** row, and the
Job's own events in a **Job updates** section. A reader could not see a Job's field attempts as one
sequence, and two lists of Visits repeated the same facts.

The activity is now **one labelled Job Activity section** holding one foldable group per Visit, newest
first, plus a group for the events that belong to no Visit:

| Group | Heading | Opened by default |
| ----- | ------- | ----------------- |
| One per Visit | `Visit N · date` (`BR-052`, `BR-072`), with the Visit's status and its crew as the folded summary | The Visit the read represents (`BR-081`) |
| The Job's own events | **General job updates** (`BR-080`) | Yes |

An expanded Visit group states a compact summary — the full date, the scheduled start **and** end, and the
technicians assigned to that Visit — and then that Visit's entries in the one timeline component the page
already used (`BR-068`, `BR-072`, `BR-080`). A Visit with nothing recorded says so inside its own group.

> **Refined 2026-09-18 by `docs/tracker/046-android-job-details-visit-period-and-card.md`.** The summary
> is now **one bordered card heading that Visit's part of the section**, rather than loose rows above the
> entries, and the represented Visit's section is labelled for when that field attempt is for instead of
> always reading "Current visit". The grouping, the headings and the fold behaviour this slice defined are
> unchanged.

**Nothing was removed from the page.** The Job overview, the Current visit card with every Job and Visit
action (status, completion, reschedule, manage technicians), the Add update action, the photo tray and
viewer, and the evidence-removal surfaces are untouched. The facts the history rows stated — which Visit,
when, its status, its crew — are stated by the group's heading and summary; `JobDetailsScreen.kt`, the
retained screen, is not touched.

## The two data questions this slice had to answer

1. **Does activity carry a reliable Visit identifier?** **Partly — and the part it carries is the right
   one.** `GET /jobs/:id/activity` reports no Visit UUID: each event carries `visitSequence`, the API's own
   derived `Visit 1`, `Visit 2`, … ordinal (`docs/api/job-activity.md` §3.3,
   `docs/domain/job-visit-domain-model.md` §6), and `GET /jobs/:id` lists the Job's Visits with their `id`
   **and** their `sequence`. Grouping therefore matches on the **sequence**, which is a stable,
   backend-derived Visit identifier that comes from creation order — **never the event's date**, which
   could not distinguish two Visits on the same day. An event naming no Visit of the Job stays Job-wide
   rather than being guessed into one (`BR-042`), which is what `groupJobActivity` already did.
2. **Is technician data attendance or assignment?** **Assignment.** `visits[].technicians` is the crew
   currently **assigned** to that Visit (`BR-068`); Servora records no attendance (time tracking is
   undefined, `BR-034`). The summary is therefore labelled **Assigned technicians** in both languages, and
   it never draws the Job's assembled technician list as if it were a Visit's crew. No attendance data was
   invented, and nothing states who was on site (`BR-042`).

## Presentation rules this slice decided

- **Every Visit gets a group.** A Visit no one has written anything on keeps its group and states that
  nothing has been recorded on it, so an empty account is read as empty rather than as a Visit the page
  forgot (`BR-042`).
- **Newest first, by the schedule the group states** — the convention `docs/tracker/044` already set for a
  Job's Visits: most recent scheduled first, a Visit with no schedule after the scheduled ones rather than
  given an invented position, and two Visits of the same time ordered by the Job's own visit sequence so
  the order is stable (`BR-052`, `BR-072`).
- **The represented Visit opens by default**, whatever its position in the list, because it is the Visit
  the page's own section presents (`BR-081`); every other group starts folded (`BR-012`). The general
  group starts open because it holds the Job's accepted evidence, and hiding that behind a tap by default
  would cost the reader the thing the section is for (`BR-015`).
- **The heading is a control with state.** `Visit N · date` is stated on lines that wrap with an ellipsis
  rather than pressing against the status and the chevron, the chevron's description names the action
  **and** the Visit, and the heading carries `stateDescription` (`Expanded` / `Collapsed`) so a screen
  reader announces the state as well as the action (`BR-011`, `BR-028`).
- **A time window is the best representation the read allows.** A Visit with no schedule says so; an end
  this build cannot read leaves the start stated rather than the window reported as unknown (`BR-042`).
- **No card inside the section.** A group is a heading with a timeline under it, separated by dividers, on
  the page's own surface (`BR-012`).
- **An event is drawn exactly once.** Each entry belongs to its Visit's group or to the general group, and
  the two together are the one timeline the backend reported (`BR-080`, `BR-001`).

## Files changed

| Layer | Files |
| ----- | ----- |
| Android presentation | `android/app/src/main/java/com/servora/android/ui/jobs/JobDetailsOverviewModel.kt` (`VisitActivityGroup`, `visitActivityGroups`, replacing `VisitHistoryEntry` / `visitHistory`) |
| Android UI | `.../ui/jobs/JobDetailsOverviewScreen.kt` (the Job Activity section, `VisitActivityGroupRow`, `VisitActivityGroupBody`, `GeneralJobUpdatesGroup`, `overviewDateText`, `overviewTimeRangeText`; the Current visit section no longer repeats an activity list) |
| Android resources | `android/app/src/main/res/values/strings.xml`, `.../values-fr/strings.xml` (`job_activity_visit_title_format`, the group expand/collapse and state strings, `job_details_visit_time_label`, `job_details_visit_time_range`, `job_details_assigned_technicians_label`; `job_details_job_updates_label` now reads **General job updates**) |
| Android tests | `android/app/src/test/java/.../ui/jobs/JobDetailsOverviewModelTest.kt` (grouping, ordering, default-open and no-duplication rules), `android/app/src/androidTest/java/.../ui/jobs/JobDetailsOverviewScreenTest.kt` (heading formatting, summary, independent folding, per-Visit empty state, general group, uniqueness, long names, accessibility state) |
| Documentation | this tracker, `docs/design/android-design-system.md` (the Job Activity paragraph and the disclosure convention), `docs/tracker/044-job-details-job-visit-separation.md` (pointer), `README.md` |

**Deliberately not changed:** `ui/jobs/JobDetailsScreen.kt` (the retained screen), every route, permission,
database table, migration and API contract — grouping uses data the reads already return, so no API change
was needed.

## Verification

**Scope** (`qa.md` §3.1): **Tier A — targeted**, Android only, because one application and one screen area
changed, and no shared contract, route, permission, schema or migration was touched. The full application
lint and the full suites are Tier B, at feature completion; **a full lint run was explicitly not requested
for this task** (`dev.md` §18).

| Check | Command | Result |
| ----- | ------- | ------ |
| Android compile | `cd android && ./gradlew compileDebugKotlin` | **PASS** |
| Android JVM tests (affected package) | `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.*'` | **PASS** — **159 tests, 0 failures**, of which `JobDetailsOverviewModelTest` **13** (2 kept, 5 rewritten and 4 new grouping rules) |
| Android device-test sources | `cd android && ./gradlew assembleDebugAndroidTest` | **PASS** — `JobDetailsOverviewScreenTest` (14 tests) compiles into the androidTest APK |
| Android lint | — | **NOT RUN — a full lint was explicitly not requested for this task**; the affected application's full lint belongs to Tier B at feature completion (`qa.md` §3.1, `dev.md` §18) |
| Android physical device | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). The agent ran no `adb` and no device or emulator command. Command the product owner may run: `cd android && ./gradlew connectedDebugAndroidTest` |

## Manual QA runbook (product owner)

1. Sign in as a Manager and open a Job that has **two Visits** — a closed earlier one and the current one.
2. Read the page: **Job overview**, **Current visit** (with its status, its crew and its actions), then
   **Job Activity** with a group per Visit and a **General job updates** group. Every Job and Visit action
   from the previous screen is still there.
3. The group **`Visit 2 · <date>`** (the Visit the page represents) is already open: it states the
   **date**, the **scheduled start – end**, and the **assigned technicians**, then that Visit's updates.
   The earlier Visit's group is folded.
4. Tap the earlier Visit's heading: it opens, showing its own date, window, crew and updates, and the
   current Visit's group does not move or close. Tap it again: it folds. Open both, then check no update
   appears twice.
5. Tap a Job-level event's group heading (**General job updates**): it folds and opens independently, and
   the Job's photos move with it.
6. Rotate the device with one group open: the same group stays open.
7. Reopen the Job with the network off: the last reported Job is presented with its offline notice and the
   groups are unchanged.
8. Turn TalkBack on and traverse a Visit heading: it announces the Visit it belongs to, whether it is
   expanded or collapsed, and an expand/collapse action naming **which** Visit.
9. Switch the device to French: the headings read `Visite N · date`, the crew label reads
   **Techniciens assignés**, and no date or name overlaps the status or the chevron.

**Expected:** the same updates stay on the page, each under the Visit it belongs to, and every action the
previous page offered still works.

## Hygiene

No scratch file, log or temporary script is left: the compile and test logs this task wrote were deleted,
`git status` shows only the files this tracker lists, and the Gradle/Kotlin build daemons the verification
started were stopped with `make android-stop` (`dev.md` §18). The foundation stack (`make up`) was not
touched and no database or storage volume was changed.
