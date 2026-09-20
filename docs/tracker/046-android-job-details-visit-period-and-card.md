# Tracker 046 — Job Details: the represented Visit stated by when it is, in a card that heads its activity

**Status: IMPLEMENTED** (the presentation rule with its JVM tests, the section label and the group's card,
the English and French strings and the focused tests; **Android physical-device QA is the product owner's**,
`qa.md` §7)

Date: 2026-09-18

Business rules: `BR-001`, `BR-007`, `BR-011`, `BR-012`, `BR-028`, `BR-041`, `BR-042`, `BR-047`, `BR-051`,
`BR-059`, `BR-068`, `BR-071`, `BR-072`, `BR-074`, `BR-080`, `BR-081`

Refines, and does not replace: `docs/tracker/044-job-details-job-visit-separation.md` (the Job/Visit
separation) and `docs/tracker/045-android-job-activity-visit-groups.md` (the per-Visit activity groups)

Design reference: none — no Figma screen exists for the redesigned page, so the established Servora
Material 3 visual language and its card convention are followed (`docs/design/android-design-system.md`)

## What this slice changes

Two defects reported from physical-device QA on the redesigned page.

1. **The section that presents the represented Visit was labelled "Current visit" whatever the clock
   said.** A Visit scheduled two days away was presented as the current one, which is not true of the work
   and not true of the page: the read's selection (`BR-081`) says *which* Visit represents the Job, and
   the label said it was happening now. The label now states **when that field attempt is for**, resolved
   from the Visit's own internal schedule (`BR-072`), its own field status (`BR-074`) and the device's
   clock and zone (`BR-028`).
2. **A Visit's revealed activity had no header of its own.** The date, the scheduled window and the
   assigned crew were drawn as loose rows above the entries. They are now one bordered card that **heads
   that Visit's part of the Job Activity section**, so a reader arriving at a timeline is told whose
   timeline it is before reading it (`BR-047`, `BR-080`).

Nothing else changed: the grouping, the headings, the fold behaviour, the default-open Visit, the General
job updates group, the Job overview, the Visit card, the actions, the photo tray and the viewer are all
as `docs/tracker/045-android-job-activity-visit-groups.md` left them.

## The two decisions this slice had to make

1. **What "current" means, and what the other periods are.** The Visit the read selects is not always
   happening now: `BR-081` selects the earliest upcoming Visit, and falls back to the most recent past one
   when nothing is upcoming. The section therefore describes the Visit it was handed, and never
   re-selects it — the selection stays the API's (`BR-001`, `BR-007`, `BR-041`). The periods are:

   | Period | Label | When it applies |
   | ------ | ----- | --------------- |
   | `CURRENT` | **Current visit** | The Visit's field work is under way (`EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`, `BR-074`), or the moment falls inside its scheduled window (`BR-072`), or it has no schedule this build can read (`BR-042`, `BR-051`) |
   | `TODAY` | **Today's visit** | The Visit is scheduled for the device's own today |
   | `TOMORROW` | **Tomorrow's visit** | The Visit is scheduled for the device's own tomorrow |
   | `UPCOMING` | **Upcoming visit** | The Visit is scheduled for a later day |
   | `PREVIOUS` | **Previous visit** | The Visit's own day has passed |

   `DRAFT`, `SCHEDULED`, `CANCELED`, `NO_SHOW` and `COMPLETED` do not make a Visit current: none of them
   says a technician is on the work, and a completed Visit is described by when it was for. The day
   boundary is the **device's** zone, because that is the day the person reading the screen is in
   (`BR-028`). The rule is `representedVisitPeriod(visit, now)` in the presentation model, with `now`
   passed in so it is verifiable without a device.
2. **Whether a card belongs inside the Job Activity section.** Tracker 045 deliberately drew no card
   there: a group was "a heading with a timeline under it". The product owner's report is that the
   revealed facts read as loose rows rather than as the header of the Visit's part of the section, so one
   card now heads that part and the entries stay a timeline — the same bordered `InfoCard` the page states
   facts in elsewhere, with the same `outlineVariant` dividers (`BR-012`, `BR-028`). What *did* stay
   unchanged is that a **group** is still a heading, not a card: the card heads the section's content for
   one Visit, it is not a container the entries sit inside.

## Files changed

| Layer | Files |
| ----- | ----- |
| Android presentation | `android/app/src/main/java/com/servora/android/ui/jobs/JobDetailsOverviewModel.kt` (`VisitSectionPeriod`, `representedVisitPeriod`) |
| Android UI | `.../ui/jobs/JobDetailsOverviewScreen.kt` (the section label and its `@StringRes` mapping, `jobActivityVisitCardTag`, the Visit card in `VisitActivityGroupBody`) |
| Android resources | `android/app/src/main/res/values/strings.xml`, `.../values-fr/strings.xml` (`job_details_today_visit_label`, `job_details_tomorrow_visit_label`, `job_details_upcoming_visit_label`, `job_details_previous_visit_label`) |
| Android tests | `android/app/src/test/java/.../ui/jobs/JobDetailsOverviewModelTest.kt` (the period rule, its window and status cases, and an unreadable schedule), `android/app/src/androidTest/java/.../ui/jobs/JobDetailsOverviewScreenTest.kt` (the label as rendered for a Visit tomorrow and for one that has passed, and the card heading an expanded group's content) |
| Documentation | this tracker, `docs/design/android-design-system.md` (the Job Details label paragraph, the `Visit section label` row, the Job Activity paragraph), `README.md` |

**Deliberately not changed:** every API contract, route, permission, database table, migration and
offline/outbox behaviour. Both changes are presentation over what `GET /jobs/:id` and
`GET /jobs/:id/activity` already return, so no `docs/api/` change was needed and no `BR-` was altered —
`BR-081`'s selection stays the backend's and the page only states what it was handed.
`ui/jobs/JobDetailsScreen.kt`, the retained screen, is untouched.

## Verification

**Scope** (`qa.md` §3.1): **Tier A — targeted**, Android only, because one application and one screen area
changed, and no shared contract, route, permission, schema or migration was touched. The full application
lint and the full suites are Tier B, at feature completion.

| Check | Command | Result |
| ----- | ------- | ------ |
| Android compile | `cd android && ./gradlew compileDebugKotlin` | **PASS** |
| Android JVM tests (affected package) | `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.*'` | **PASS** — **163 tests, 0 failures, 0 errors**, of which `JobDetailsOverviewModelTest` **17** (13 kept, 4 new) |
| Android device-test sources | `cd android && ./gradlew assembleDebugAndroidTest` | **PASS** — `JobDetailsOverviewScreenTest` (17 tests, 3 new) compiles into the androidTest APK |
| Android lint | — | **NOT RUN — the affected application's full lint belongs to Tier B at feature completion** (`qa.md` §3.1, `dev.md` §18) |
| Android physical device | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). The agent ran no `adb` and no device or emulator command. Command the product owner may run: `cd android && ./gradlew connectedDebugAndroidTest` |

## Manual QA runbook (product owner)

1. Sign in as a Manager and open a Job whose represented Visit is **tomorrow**: the section above the Job
   Activity is labelled **Tomorrow's visit**, not **Current visit**.
2. Open a Job whose only Visit is behind the clock: the section is labelled **Previous visit**. Move a
   Visit's schedule to **later today** (Reschedule) and reopen the Job: **Today's visit**; move it a week
   out: **Upcoming visit**.
3. Open a Job whose represented Visit is `En route` / `On site` / `In progress`: the section is labelled
   **Current visit**, and stays so whatever its scheduled window says.
4. Open the Job Activity section and expand a Visit's group: its **date**, **scheduled time** and
   **assigned technicians** now sit in a bordered card, and that Visit's entries are listed under the
   card. Collapse and re-open it: the card comes back with it.
5. Confirm no entry is drawn twice, the group headings and their folded summaries are unchanged, the
   General job updates group still folds independently, and every action (status, completion, reschedule,
   manage technicians, Add update, photo tray, viewer, evidence removal) still works.
6. Switch the device to French: the labels read **Visite en cours**, **Visite d'aujourd'hui**, **Visite de
   demain**, **Prochaine visite**, **Visite précédente**, and no label or card text overlaps the status or
   the chevron.
7. Turn TalkBack on and traverse an expanded group: the card's date, time and crew are announced, and the
   entries follow.

**Expected:** the section never states that work the clock has not reached is current, and each Visit's
activity is headed by that Visit's own card.

## Hygiene

No scratch file, log or temporary script is left: the compile and test logs this task wrote were deleted,
`git status` shows only the files this tracker lists, and the Gradle/Kotlin build daemons the verification
started were stopped with `make android-stop` (`dev.md` §18). The foundation stack (`make up`) was not
touched and no database or storage volume was changed.
