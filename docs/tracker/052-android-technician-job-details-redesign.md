# Tracker 052 — The technician's Job Details page

**Status: IMPLEMENTED** (the Android page's hierarchy, its sections, its activity grouping and wording,
its strings in both languages and the tests that cover them; **Android physical-device QA is the product
owner's**, `qa.md` §7)

Date: 2026-09-20

Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-010`, `BR-011`, `BR-012`, `BR-015`, `BR-027`, `BR-028`,
`BR-041`, `BR-042`, `BR-047`, `BR-049`, `BR-052`, `BR-053`, `BR-056`, `BR-058`, `BR-059`, `BR-062`,
`BR-066`, `BR-067`, `BR-068`, `BR-072`, `BR-074`, `BR-077`, `BR-078`, `BR-079`, `BR-080`, `BR-092`,
`BR-095`

Refines, and does not replace: `docs/tracker/044-job-details-job-visit-separation.md` (the Job and its
Visits presented apart), `docs/tracker/045-android-job-activity-visit-groups.md` (the per-Visit activity
groups), `docs/tracker/046-android-job-details-visit-period-and-card.md` (the Visit section's period label)
and `docs/tracker/048-android-job-activity-visit-outcome.md` (the Visit's outcome). **The bordered Visit
facts card those three last trackers added to an expanded activity group is removed by this slice** — what
it stated is now the group's heading and the Visit section's own rows (`BR-047`, `BR-012`).

Depends on: `docs/tracker/051-job-visit-lifecycle-redesign.md` (the approved domain model this page is laid
out around: `ACTIVE` as an open request, no `PENDING_REVIEW`, `NO_SHOW` removed, a technician's updates
Visit-scoped, and a Visit's completion carrying its own outcome). **No domain rule is changed here.**

Design reference: none — no Figma screen exists for the redesigned page, so the established Servora Material
3 visual language and its card, section-label and disclosure conventions are followed
(`docs/design/android-design-system.md`).

## The problem

The page was the Manager's hierarchy with the technician's actions added to it: one **Job overview** card
holding the Job's identity, a routine Job status chip, the Customer, its contacts and the address; a Visit
section; and an activity section whose every Visit group opened onto a second copy of that Visit's date,
scheduled window, outcome and crew. A technician standing at a door had to read three screens' worth of
record to answer four questions — where do I go, who do I call, what do I need to know, and what do I do
now — and the newest domain model (tracker 051) made two of those answers wrong by construction: a routine
`ACTIVE` says nothing about the field work, and a technician's own update belongs to a Visit.

## The hierarchy this slice lands

| # | Section | What it answers |
| - | ------- | --------------- |
| 1 | **Job heading** — number, title, description, no card | What work am I here to do? (`BR-052`, `BR-053`) |
| 2 | Offline notice, then the read-only notice | Whether the page on screen is current, and whether the Job stopped being open (`BR-062`, `BR-079`) |
| 3 | **Job status** — compact inside the Job heading, only for a session that may change it | The Manager's control, when there is one to offer |
| 4 | **Location** — the Job's frozen address snapshot, ending in the navigation affordance | Where is it? (`BR-049`, `BR-056`) |
| 5 | **Contacts** — the Customer row, the effective primary contact with its own tappable phone and email, and the folded other ways of reaching the Customer | Who can I contact? (`BR-092`, `BR-095`) |
| 6 | **Notes** — the notes the office recorded, as their own section | What do I need to know before working? (`BR-092`) |
| 7 | **Current visit** — the period label, date, scheduled window, crew, the four working states, **Add update** and **Complete visit** | What is the current Visit state, and how do I record the work? (`BR-074`, `BR-077`) |
| 8 | **Job Activity** — one group per Visit, newest first, headed `Visit N · date` over `Completed · Needs follow-up`, plus the folded **General job updates** group | What happened previously? (`BR-080`) |

## The decisions this slice had to make

| Decision | What was decided, and why |
| -------- | ------------------------- |
| Whether to draw the Job's status | **No routine status; the control stays for a session that may change the Job.** `ACTIVE` says only that the request is open, which a technician acting on an assigned Visit already knows, so a chip for it is decoration that costs a row (`BR-012`). A session that may move the Job keeps exactly what `docs/tracker/020-android-job-details-status-control.md` built — the chip that presents the status *is* the control — because removing it would take the Manager's only status path away. A Job that has stopped being open is not a chip at all: it is the prominent `JobReadOnlyNotice` both audiences already read before the work (`BR-058`, `BR-062`, `BR-079`) |
| Whether the page is split by audience | **No.** One destination serves both (`BR-002`), and a second technician-only screen would be a second copy of the same read, the same permissions and the same actions. What differs is what each session may act on, and that is already the permission gate (`BR-006`, `BR-011`) |
| Where the customer's contacts go | **Their own section, leading with one contact.** The read returns the Customer's own phone, email and notes plus its contact persons (`BR-092`, `BR-095`); the section presents the **effective primary** — the flagged contact person, or the Customer itself when none is flagged — with the values that person recorded, and folds the rest behind `N other contacts`. Nothing is chosen: a technician with no flagged contact is *not* given one of the contact persons as "the one to call" (`BR-095`, `BR-041`) |
| Whether the contact values act | **Yes — the affordances the decision already recorded.** `ADR-022` D6 decided tap-to-call and tap-to-email on both surfaces on 2026-09-18; the Job Details half is drawn here, through the same `CustomerContactLine` + `dialIntent`/`mailIntent` the customer screens use (`BR-041`) |
| Where the office's notes go | **Their own section, and the same field.** The read carries the Customer's `notes` and no other note field exists; a technician reads them as what the office recorded about the site, so they are no longer a row of the contact metadata (`BR-012`). **No notes domain is invented** — no table, no field and no route (`BR-042`) |
| Where **Add update** is drawn | **With the Visit, with the floating action replacing it once it scrolls away.** A technician's update is Visit-scoped (`tracker 051`), so the action belongs beside the Visit it records against, next to **Complete visit**. The floating action then exists only while that row is off screen, so one action is reachable at every scroll position and neither duplicate competes with the other (`BR-012`). **Superseded by `docs/tracker/053-android-job-details-add-update-floating-action.md` (2026-09-20):** the product owner removed the card's own Add update action and made the floating action the page's one, drawn at every scroll position; the card keeps **Complete visit** alone |
| What an activity group's heading states | **`Visit N · date` over `status · outcome`.** A folded group answers what happened without being opened, in the field's words, from the shared status and outcome labels (`BR-074`, `BR-078`, `BR-041`). The status pill and the crew line left the heading: the words replaced the pill, and the crew belongs to the Visit section (`BR-047`) |
| What an expanded group reveals | **That Visit's own evidence and entries, and nothing else.** The Visit facts card is gone: its date and outcome are the heading's, its schedule and crew are the Visit section's (`BR-047`, `BR-012`) |
| Whether administrative activity belongs in the primary timeline | **No.** Assignment changes, schedule creation and routine bookkeeping stay available in a collapsed administrative history inside the relevant Job/Visit group. The primary timeline leads with notes, evidence, meaningful field milestones and outcomes, so audit noise does not bury what happened operationally (`BR-080`, `BR-012`) |
| Whether the office's own group starts open | **Folded.** It holds Job-level lifecycle events rather than the technician's field work; it stays one tap away because it is where the Job's own evidence is read from (`BR-015`, `BR-012`) |
| How a status event is worded | **In the language of the field, when it is operationally meaningful.** `VISIT_STATUS_CHANGED` milestones such as `Work started`, `Arrived on site`, `En route`, `Visit completed` and `Visit canceled` can appear in the primary timeline; routine scheduled/bookkeeping changes collapse into administrative history. `VISIT_OUTCOME_RECORDED` states the outcome itself, so a completion and its outcome read as one account. **No audit record is altered** — the event, both statuses, the actor and the time are the API's (`BR-001`, `BR-067`) |

## What changed in the code

* `ui/jobs/JobDetailsOverviewScreen.kt` — the page: `JobHeaderSection`, `JobStatusSection`,
  `JobLocationSection`, `JobContactsSection`, `JobNotesSection`, the Visit card's own action row, the group
  heading's summary line, the group body without the facts card, the folded general group, and the floating
  action's visibility gate. The tags for the removed facts card are gone; `JobHeaderSectionTag`,
  `JobStatusSectionTag`, `JobLocationSectionTag`, `JobContactsSectionTag`, `JobDetailsContactPrimaryTag`,
  `JobNotesSectionTag`, `JobDetailsVisitFieldActionsTag`, `JobDetailsVisitAddUpdateTag` and
  `jobActivityVisitSummaryTag` replace them.
* `ui/jobs/JobDetailsOverviewModel.kt` — `JobCustomerContactSummary` now carries the effective primary's
  **name** and **email** as well as its phone plus whether that primary **is the Customer itself**, and
  `contactSummary` resolves all of it from the same `isPrimary` answer: the flagged contact person, or the
  Customer itself when none is flagged (`BR-095`).
* `ui/jobs/JobDetailsActions.kt` — `VisitAddUpdateAction`, the completion action's peer in the Visit's own
  row.
* `ui/jobs/JobActivitySection.kt` — `activityVisitStatusTitle`, the field language for a status change, and
  the outcome entry stating the outcome itself.
* `ui/components/ServoraContacts.kt` (new) — `CustomerContactLine` and the `dialIntent` / `mailIntent` /
  `startContactIntent` actions, moved out of `ui/customers/CustomersScreen.kt` so three features share one
  affordance (`dev.md` §15, the `ADR-021` addendum of 2026-09-20).
* `res/values/strings.xml`, `res/values-fr/strings.xml` — the new section labels, the group summary format
  and the six field phrases; the strings only the removed facts card and the old heading used are gone
  (`job_details_overview_label`, `job_details_assigned_technicians_label`, `job_details_visit_time_label`,
  `job_details_visit_outcome_label`, `job_details_visit_time_range`, `job_activity_visit_status_changed`,
  `job_activity_outcome_recorded`).

**Deliberately not changed:** every route, capability, permission, API contract, projection, database table,
migration and offline/outbox behaviour. The page is a presentation of the reads it already had, so no API,
schema or working-set change was needed and no `BR-` was altered. `ui/jobs/JobDetailsScreen.kt` — the frozen
previous presentation — is untouched, as `tracker 044` left it.

## Verification

| Check | Command | Result |
| ----- | ------- | ------ |
| Android compile (main) | `cd android && ./gradlew compileDebugKotlin` | **PASS** |
| Android full unit suite (Tier B) | `make android-test` (`./gradlew testDebugUnitTest`) | **PASS** — 70 classes, **791 tests, 0 failures, 0 errors**, including the updated `JobDetailsOverviewModelTest` (21) and the new `JobActivityTitleTest` (2) |
| Android lint (Tier B) | `make android-lint` (`./gradlew lintDebug`) | **PASS** — **0 errors, 56 warnings**, and **no warning or hint is reported in any file this change wrote**, nor at any line it changed. The warnings that name files it touched (`JobActivitySection.kt:113`, `CustomerDetailScreen.kt:145`, `JobDetailsScreen.kt:250` — all `ModifierParameter`, and `JobDetailsViewModel.kt:1670` — `EmptySuperCall`) are **pre-existing**, in signatures and bodies this change did not alter. The **first** lint attempt crashed inside lint's own Kotlin analysis (`ExperimentalDetector` → `KaFirKotlinPropertySymbol`, "Unexpected owner function: null") on `JobDetailsOverviewModel.kt`; the same analysis passed on the immediate re-run, so it is a lint-internal crash rather than a defect in the file (`qa.md` §16 — reported rather than hidden) |
| Android debug build (Tier B) | `make android-build` (`./gradlew assembleDebug`) | **PASS** |
| Android device-test sources (Tier B) | `cd android && ./gradlew assembleDebugAndroidTest` | **PASS** — `JobDetailsOverviewScreenTest` and `JobDetailsScreenTest` compile into the androidTest APK |
| Android physical device | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run. Command the product owner may run: `cd android && ./gradlew connectedDebugAndroidTest` |
| API / Angular | — | **NOT RUN — nothing outside `android/` and the documentation changed** |

### What the device tests now cover

`JobDetailsOverviewScreenTest` (device, run by the product owner) was updated with the page: the Job's
identity is the header, the location and the contacts are their own sections, **no routine Job status is
drawn to a session that cannot change the Job** while the status **control** is kept for one that can, a
canceled Job is reported by the notice instead, the Contacts section leads with the effective primary — the
flagged contact person, or the Customer itself — with tappable phone and email, the group heading states the
Visit's status and outcome and nothing about its crew, an expanded group reveals that Visit's activity
**without** repeating its card, and the office's own group starts folded.

## Manual QA runbook (product owner)

1. Sign in as a **Technician** with a Visit assigned today, and open it from the technician home.
2. Read the page top to bottom: **Job #…**, its title and description; the **Location** card with the
   address and its navigation glyph; the **Contacts** card; and, if the office recorded notes, a **Notes**
   section.
3. Confirm **no routine Job status chip** is on the page, while the Visit's own state is stated in its card.
4. Return to the home, open a Job the office has **canceled**: the page states *This job was canceled*
   prominently above the work, and offers no field action.
5. Tap the primary contact's phone line (the dialer opens) and its email line (the mail client opens), then
   open **N other contacts** and do the same for a folded entry.
6. On the Visit card: tap a working state (for example **On site**), then **Add update** → **Write note**,
   and save.
7. Scroll down through Job Activity: confirm the **Add update** floating action stays on screen while the
   timeline is read and opens the same sheet. (`tracker 053` moved this action: it is now the page's only
   Add update control and is drawn at every scroll position, so the card's action row no longer carries
   one — see `docs/tracker/053-android-job-details-add-update-floating-action.md`.)
8. Read the represented Visit's group heading: it reads `Visit N · date` over `En route` (or whatever state
   the Visit is in), and expanding it shows that Visit's activity only — no date, schedule, crew or outcome
   card.
9. Complete the Visit (**Complete visit** → an outcome and a summary), then reopen the Job: the group
   heading reads `Completed · Needs follow-up` (or the outcome chosen), the timeline's own entries read
   `Visit completed` and then the outcome, and **General job updates** is folded.
10. Expand **General job updates**: the Job's own events are there and the Visit's are not.
11. Switch the device to French and repeat steps 2–10: every section label, the group summary and the six
    field phrases are translated (`Lieu`, `Contacts`, `Visite terminée`, `Travail commencé`, …).
12. Turn TalkBack on and traverse the page: each section announces its label, each Visit group announces its
    heading and whether it is expanded, and each contact line announces its value.

**Expected:** the seven questions are answered in order, no routine Job status is drawn, no Visit detail is
stated twice, and every action offered is the one the API will accept.

## Hygiene

No scratch file, log or temporary script is left: the compile, test and lint logs this task wrote under
`/tmp` were removed, and the Gradle/Kotlin build daemons the verification started were stopped with
`make android-stop` — `make tidy` reports no Java build daemon and no Node process running (`dev.md` §18).
The foundation stack (`make up`) was **not** touched: `api`, `minio` and `postgres` are still up, as they
were, and no database or storage volume was changed. No editor tab or file was opened in the product
owner's editor.

`git status` carries two sets of changes and they are deliberately distinguishable: **this slice's** files
are `JobDetailsOverviewScreen.kt`, `JobDetailsOverviewModel.kt`, `JobDetailsActions.kt`,
`JobActivitySection.kt`, the new `ui/components/ServoraContacts.kt`, `CustomersScreen.kt` and
`CustomerDetailScreen.kt` (import updates for the moved affordance), the two `strings.xml` files, the three
test files it added or rewrote, this tracker and the documentation it touched. Everything else in the tree —
the API changes, the migration, and the Android data-layer and ViewModel changes — was already uncommitted
when this task started and belongs to the in-flight `docs/tracker/051-job-visit-lifecycle-redesign.md`
slice. This task neither produced nor reverted it.
