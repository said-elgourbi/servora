# Tracker 057 — QA issue list: the Visit workflow (technician completion → follow-up request → office review)

**Status: OPEN — items 1 and 3 are implemented and verified to the tier this machine allows (§1.6–§1.8,
§3.3–§3.4), and item 5 has landed everything that did not wait on a decision: the two defects (§5.5–§5.6)
and the technician's own requests (§5.8, on the answer to §5.3.5); what remains for those is the product
owner's device QA (§1.9, §3.5, §5.7, §5.10), and items 2 and 4 plus the rest of item 5 are recorded
awaiting their decisions. No item is Done.**

Date: 2026-09-28

Type: **QA issue list.** Five issues reported by product ownership from a physical-device review of one
end-to-end journey — a technician completes a Visit as *Follow up required*, goes back to Home, and the
office reviews the request the technician raised. Nothing here is implemented until its own item says so;
this document is the list, the evidence found in the code, and the questions that need an answer
(`BR-042`, `Project.md` §30).

Source: product ownership, 2026-09-28, verbatim:

> Test Scenario: I clicked on a scheduled job as a tech, hit complete and did follow up required:
> 1- back to home screen, the job still show scheduled
> 2- the request another visit window should appear when the tech select follow up required, possibly under the radio button
> 3- please make sure that save and cancel buttons are visible when typing, not just the text, because user have to hide keyboard to see the buttons
> 4- as a Manager, The tab requests and unassigned seems to be independant of the selected date, that's confusing, offer me options of best practice in term of user UI
> 5- Manager should see only important information: Status of previous visit, reason for new visit, client details and Approve, Reject, Reschedule, right now the manager sees: Open Job, Clarify, Reject, Approve and Schedule, and the header is time from-to, reason, same tech or not same tech, it's confusing because if the manager open job, not details about the visit suggestion are visible in job details, also clarify doesn't do anything.
> We need tech to be able to see rejection, then resubmit with a mandatory reason
> Possibly a list of visit requests for tech to keep track

Business rules this list is bounded by: `BR-001`, `BR-005`, `BR-006`, `BR-007`, `BR-009`, `BR-010`,
`BR-011`, `BR-012`, `BR-013`, `BR-014`, `BR-027`, `BR-028`, `BR-031`, `BR-032`, `BR-041`, `BR-042`,
`BR-047`, `BR-058`, `BR-060`, `BR-062`, `BR-066`, `BR-067`, `BR-068`, `BR-071`, `BR-072`, `BR-074`,
`BR-077`, `BR-078`, `BR-079`, `BR-080`, `BR-092`, `BR-095`, `BR-FV-001` – `BR-FV-013`.

Related trackers (not replaced by this one): `docs/tracker/050-visit-follow-up-requests.md` (the feature
this journey belongs to), `docs/tracker/055-android-home-refresh-on-return.md` (the Home refresh this
item 1 has to be read against), `docs/tracker/051-job-visit-lifecycle-redesign.md` (the lifecycle the
screens draw).

## The list at a glance

| # | Reported | Area | Status | Needs |
| - | -------- | ---- | ------ | ----- |
| 1 | After completing a Visit as *Follow up required*, Home still reads `Scheduled` | Android — technician Home / Schedule | **IN PROGRESS** — the stale-answer causes are fixed (§1.6, §1.7); device QA outstanding (§1.9) | the product owner's device pass |
| 2 | The *request another visit* window should appear when *Follow up required* is chosen | Android — Visit completion sheet | OPEN — **product decision** | whether a `NEEDS_FOLLOW_UP` completion carries the request |
| 3 | Save/Cancel are hidden behind the keyboard in the sheets | Android — every bottom sheet with fields | **LANDED (§3.1–§3.3)** — every sheet that carries a field pins its actions outside its scrolling body; device QA outstanding (§3.5) | the product owner's device pass |
| 4 | The **Requests** and **Unassigned** lanes ignore the selected date | Android — manager Schedule | OPEN — **product decision** | one of the options in §4.2 |
| 5 | The manager review card's contents/actions; the technician cannot see a rejection | Android — manager Schedule + technician My Schedule | **PARTLY LANDED (§5.5–§5.6, §5.8, §5.11, §5.12)** — a clarified request stays reviewable and says so, every Clarify/Reject records the office's note and reports its outcome, the technician reads their own requests — a refusal and its reason included — on a **Requests** tab on My Schedule, answers a returned one in a request-scoped conversation, and opens a request's own row into a **Request details** screen rather than into the Job; the review card's contents and the resubmission flow still need §5.3.1–§5.3.4 | decisions §5.3.1–§5.3.4, then the product owner's device pass (§5.7, §5.10, §5.12) |

Working order unless product ownership reorders: **1 → 3 → 5 → 2 → 4** (1 and 3 have landed, and item 5's
no-decision defects have landed with them; item 5's decisions are next, then its remaining work). Item 1 is first because it is the
report that made the journey feel broken; item 3 is second because it is a plain layout defect with no
decision in it; items 2, 4 and 5 each contain a product decision and cannot be implemented as an
assumption.

---

## 1 — After completing a Visit as *Follow up required*, Home still reads `Scheduled`

**Reported:** *“I clicked on a scheduled job as a tech, hit complete and did follow up required: back to
home screen, the job still show scheduled.”*

**Status: IN PROGRESS — the cause cannot be fixed as an assumption.** Which of the three candidate causes
is the one that happened decides what the correct change is, and the three are not interchangeable: one
is a client refresh gap, one is a queued field write, and one is the read failing silently. One detail
from the product owner separates them (§1.4).

### 1.1 What the backend does — and does not do

The API is **not** a candidate cause, and this was verified by reading the code and its tests rather
than by assuming it:

- `POST /jobs/:id/visits/:visitId/completion` writes `visits.status = 'COMPLETED'` together with the
  outcome, in one transaction (`api/src/jobs/jobs.service.ts` `completeVisit`, ~1046–1100), and applies
  the Job consequence — `NEEDS_FOLLOW_UP` leaves the Job `ACTIVE` (`BR-078`, `visit-job-consequence.ts`).
- The technician home read is `GET /home/technician`
  (`api/src/home/technician-home.service.ts`). Its **day** list deliberately keeps a `COMPLETED` Visit —
  “it is what the day has produced so far, and the technician's own record of it belongs on their day”
  (`findDayVisits`, and `NOT_DAY_WORK_VISIT_STATUSES = ['CANCELED']` in `home-visit-conditions.ts`) — and
  this is pinned by `api/test/technician-home.e2e-spec.ts` (“reads the day in time order and leaves out
  the attempts that did not happen”: a seeded `COMPLETED` Visit is expected **in** the day, `CANCELED` out).
- The **next Visit** and the **upcoming** preview are chosen from *open* Visits only
  (`findOpenVisits` excludes `COMPLETED`/`CANCELED`), and `selectNextVisit` prefers work already started.
  So after a completion, the completed attempt can no longer be “next”, and it cannot be in `upcoming`.

Consequence: when the read reaches the backend, the technician Home reports that Visit as `COMPLETED`
(rendered by `android/.../ui/home/TechnicianHomeScreen.kt` `VisitRow` → `HomeVisitStatusPill`, whose label
is `visit_status_completed`), and the Job is `ACTIVE`, which the field home does not display as a status
at all. `Scheduled` is only ever drawn from `visitStatus == SCHEDULED`.

### 1.2 The three ways the screen can still be showing `Scheduled`

**(a) A client refresh gap — returning to a destination does not re-read it.**
`docs/tracker/055-android-home-refresh-on-return.md` fixed exactly this for **Home**: the re-entry effect
in `android/.../ui/customers/CustomersScreen.kt` (~355–368) reads the day again when the bottom-navigation
area is re-entered. **The same effect does not refresh the Schedule destination** — and both
`ScheduleViewModel.start()` (“Coming back to the destination keeps the day the manager had selected and
refreshes it”) and `TechnicianScheduleViewModel.start()` already exist to do it, but they are only called
from `LaunchedEffect(selectedTab)`, which does not re-run when the *same* tab is re-entered from a
drill-down. A Visit completed from a Job opened **from the My Schedule screen** therefore stays on screen
with its old status until the tab is changed or the app is restarted. This is a real defect regardless of
which cause the report was, and it is in the same family as 055.
Note: 055's own fix was **compile-only** — its device test (`ServoraHomeNavigationTest`) was never run
(`qa.md` §7.3), so the Home half is unverified on a device too.

**(b) The completion was queued on the device instead of applied.**
A field completion carries a client operation id and is queued by the offline/outbox engine when the
backend cannot be reached (`JobDetailsViewModel.submit` → `JobActionResult.Queued`; `BR-014`, `BR-031`,
offline-first standard §5, §9). The Visit is then genuinely still `SCHEDULED` on the server, so Home
correctly reads `Scheduled` — and **Home says nothing about the pending field write**, because the home
read is the backend's answer and the working set is the last answer the backend gave
(`TechnicianHomeRepository` falls back to the working set only when the backend is **unreachable**, and
`TechnicianHomeUiState.showsLastReportedNotice` is what marks that). The technician sees a screen that
looks current and contradicts the work they just did. Whether Home should surface the caller's own pending
work (`BR-014` “failed synchronization remains visible to the user when intervention is required”) is a
decision, not a given.

**(c) The refresh ran and failed, and the failure is invisible while content is on screen.**
`TechnicianHomeViewModel` keeps the last day on a failed read and only reports `failureReason`; the screen
draws a failure state **only** when nothing has been read yet (`TechnicianHomeUiState.showsFailure` is
`home == null && failureReason != null`). With a day already on screen, a failed refresh shows the old
`Scheduled` day with no notice at all. `ScheduleViewModel.read()` goes further and **clears** the day on a
failure (`schedule = null`), so the two destinations behave differently for the same event.

### 1.3 What is decided, and what is not

Decided (already in the rules, not open): who may complete a Visit (`BR-066`, `BR-093`), what a completion
requires (`BR-077`, `BR-078`), what the outcome does to the Job (`BR-058`, `BR-062`, `BR-078`), that the
day's own record keeps a completed attempt (`technician-home.service.ts` and its e2e spec), and that field
work waiting to synchronize must not be lost and stays visible when a decision is needed (`BR-013`,
`BR-014`).

Not decided — **OPEN QUESTION**:
1. Which observation the report was: a stale screen (a), a queued completion (b), or an invisible failed
   refresh (c). §1.4 asks it.
2. Whether the technician Home must surface the caller's own **pending** field work (an unsynchronized
   completion, status change or note) beside the day. The outbox is a device fact the API cannot see, so a
   home read cannot carry it; a client-side answer is the only one available, and `BR-042` forbids
   inventing it.
3. Whether a failed refresh with content already on screen must be stated. Today Home is silent and the
   manager Schedule blanks the whole day; neither behaviour is a decided one.

### 1.4 The observation, as answered — 2026-09-28

Product ownership, asked directly:

> The Visit's status pill still read **“Scheduled”** — nothing else on screen changed (no offline /
> “last reported” notice).

That answer rules out one candidate and settles the fix:

- **(b) is ruled out.** A completion that was queued for want of connectivity draws its own statement on
  the Job Details screen (`R.string.job_action_queued_visit_completion`, “Saved on this device. The visit
  will be completed when you're back online.”) *and* a later Home read taken while the backend is
  unreachable is answered from the working set, which draws the last-reported notice. The report has
  neither, so the completion was not the queued case.
- **What the answer describes is a screen presenting an answer that was not current, without saying so.**
  The pill reading `Scheduled` with no notice at all is exactly what (a) — a read that never ran after the
  drill-down — and (c) — a read that ran and failed while the known day stayed on screen — both look like
  from the seat. Both were unchecked in the field home, and the second was a real asymmetry: the **manager**
  home has stated an unrefreshed day since it was built (`ManagerHomeUiState.showsUnrefreshedNotice`), and
  the field home had no equivalent.

### 1.5 Plan (as carried out)

1. **Fix the re-entry read (a)** — §1.6.
2. **Make an unrefreshed day say so (c)** — §1.7.
3. Device QA of the journey by the product owner — the runbook in §1.9.

### 1.6 What has landed — the missed refresh on the Schedule destination (2026-09-28)

**Landed: fix (a), the missed refresh on the Schedule destination** (`BR-001`, `BR-013`, `BR-072`,
`BR-074`; the gap `docs/tracker/055-android-home-refresh-on-return.md` left).

| File | Change |
| ---- | ------ |
| `android/.../ui/customers/CustomersScreen.kt` | The schedule's read is extracted into one `readSchedule` lambda, resolved by the session's capability exactly as `readHome` is (`BR-006`, `BR-011`); the tab effect and the drill-down re-entry effect both call it, so entering Schedule by its tab and entering it again after a Job are the same read |
| `android/.../androidTest/.../ui/navigation/ServoraHomeNavigationTest.kt` | `FakeScheduleRepository` now answers a scripted day and counts its reads; the new device test `returningFromAJobReadsTheScheduleAgain` opens My Schedule, opens the Job its Visit belongs to, comes back, and requires the schedule to have been read again |

**Not landed, and not implemented as an assumption** (`BR-042`): cause **(b)** — surfacing the caller's own
queued field work on Home. The report's answer (§1.4) rules it out as what happened, and where a pending
completion *should* be visible beyond Job Details is still the open question §1.3 records.

### 1.7 What has landed — the unrefreshed day says so (cause (c))

**Landed: the field home states that the day on screen is not a current answer** (`BR-001`, `BR-010`,
`BR-013`, `BR-014`, `BR-041`). The manager home has made this statement since it was built
(`ManagerHomeUiState.showsUnrefreshedNotice`, `ManagerHomeUnrefreshedTag`, `R.string.home_unrefreshed_notice`);
the field home kept `home` and reported `failureReason` to nobody, so a failed refresh was invisible — the
difference between “the app ignored me” and “the app is showing an older answer”.

| File | Change |
| ---- | ------ |
| `android/.../ui/home/TechnicianHomeUiState.kt` | `showsUnrefreshedNotice`: the day is known and the last read failed. It is deliberately **not** set for a day served from the working set, because that case already states itself (`showsLastReportedNotice`) |
| `android/.../ui/home/TechnicianHomeScreen.kt` | `TechnicianHomeUnrefreshedTag` and the notice itself — the same content and retry the manager home draws, in the app's existing bilingual strings (`home_unrefreshed_notice`, `home_retry`) rather than new ones |
| `android/.../test/.../ui/home/TechnicianHomeViewModelTest.kt` | the known JVM tests now require the statement: a failed later read sets it, a working-set day does not |
| `android/.../androidTest/.../ui/home/TechnicianHomeScreenTest.kt` | the new device test `saysWhenTheDayOnScreenCouldNotBeRefreshed`: the notice is drawn **above** the day, the day stays usable, and the retry asks again |

Nothing else changes: a failed first read still shows the failure state (`showsFailure`), a refresh in
flight still shows the quiet refreshing line, and the notice never blocks the content (`BR-012`).

**Not landed, and not implemented as an assumption** (`BR-042`): cause **(b)** — surfacing the caller's own
queued field work on Home. The report's answer (§1.4) rules it out as what happened, and where a pending
completion *should* be visible beyond Job Details is still the open question §1.3 records.

### 1.8 Verification — Tier A (targeted, Android only; `qa.md` §3.1)

Nothing outside `android/` changed, so no API, Angular or database command was required. At the product
owner's standing instruction for this machine (`docs/tracker/050-visit-follow-up-requests.md`, Verification
Policy) no lint was run.

| Check | Command | Result |
| ----- | ------- | ------ |
| Android main sources | `cd android && ./gradlew compileDebugKotlin` | **PASS** — `BUILD SUCCESSFUL in 46s` |
| Android device-test sources | `cd android && ./gradlew compileDebugAndroidTestKotlin` | **PASS** — same run; the only message is the pre-existing deprecation warning for `createAndroidComposeRule` |
| Android JVM unit tests — the home package, which covers §1.7 | `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.home.*'` | **PASS** — `BUILD SUCCESSFUL in 47s`; 22 tests, 0 failures, 0 errors (`TechnicianHomeViewModelTest` 7, `ManagerHomeViewModelTest` 7, `ManagerHomeUiStateTest` 5, `AttentionSectionExpansionTest` 2, `GreetingPeriodTest` 1) |
| Android JVM unit tests — the shell's re-entry wiring (§1.6) | — | **NOT RUN** — no JVM test covers `CustomersScreen`; its cover is the device test in §1.6, which needs a device |
| Android lint | — | **NOT RUN** — at the product owner's instruction for this machine; the tier defers lint to Tier B (`qa.md` §3.1) |
| Android device/emulator commands | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run |
| API / Angular / database | — | **NOT RUN** — nothing outside `android/` changed |

### 1.9 Device QA runbook (product owner)

```text
Android QA — a screen never presents a stale answer as a current one

1. Sign in as the Technician and open My Schedule; note the status of a Visit.
2. Open that Job from the schedule, complete the Visit (an outcome and a summary), and come back
   with the system Back control.
Expected: the schedule row states the Visit's new status (Completed), not the status it was left
showing, without signing out and back in.

3. Repeat with the office set: as a Manager, open Schedule, open a Visit's Job, change something the
   schedule shows, and come back.
Expected: the day and the Requests lane are read again, and the row reflects the change.

4. Put the device in airplane mode, open the Technician Home, and then reconnect: with a day already
   read, a read that fails must now show "Couldn't refresh. Showing the last update." above the day,
   with Try again — the day stays usable underneath it.
Expected: a day that is not current says so; nothing is blanked.
```

**Hygiene.** The compile and test logs this task wrote under `/tmp` were removed, and the Gradle/Kotlin build
daemons the verification started were stopped with `make android-stop` (`1 Daemon stopped`). The foundation
stack (`make up`) was not touched, no database or storage volume was changed, and no file was opened in the
product owner's editor.

---

## 2 — The *request another visit* window should appear when *Follow up required* is chosen

**Reported:** *“the request another visit window should appear when the tech select follow up required,
possibly under the radio button.”*

**Status: OPEN — a product decision is needed before anything is implemented.**

### 2.1 What the code does today

- The completion sheet (`android/.../ui/jobs/JobDetailsActions.kt` `VisitCompletionSheet`, ~1440–1580) asks
  for exactly what `BR-077` requires of a completion: an outcome (four radio options) plus a required
  summary. Choosing *Follow up required* (`VisitOutcome.NEEDS_FOLLOW_UP`) records the outcome and nothing
  else.
- The request for another attempt is a **separate** action: **Request another visit** in the Visit actions
  row on Job Details, opening `android/.../ui/jobs/RequestFollowUpVisitSheet.kt`, which posts
  `POST /jobs/:id/visit-requests` (`visits.request_follow_up`) with the proposed window, a reason and the
  same-technician preference.
- This matches the rules as written: `BR-FV-001` makes a request the technician's own proposal, `BR-FV-002`
  makes it not a Visit and not a schedule, and `BR-078` derives the follow-up *expectation* from the outcome
  code rather than modelling a separate flag. The rules do **not** say that a `NEEDS_FOLLOW_UP` completion
  must raise a request, that it must require one, or that the two must be one flow — and `BR-FV-006`
  explicitly allows a technician to complete the Visit without knowing the follow-up's schedule.

### 2.2 What the report asks, read literally

*“possibly under the radio button”* — when the technician selects **Follow up required**, the sheet should
reveal the request's own fields (suggested window, reason, same technician) beneath the selected option,
so one screen states both facts.

### 2.3 OPEN QUESTION — the decision this needs (`BR-042`)

1. **Is the request part of the completion, or still a separate proposal?** Either the completion sheet
   *offers* the request inline as an optional, expanded section when `NEEDS_FOLLOW_UP` is chosen (the
   outcome stands alone, and a technician may complete without proposing anything), or choosing
   `NEEDS_FOLLOW_UP` **obliges** a request in the same operation.
2. **If it is obliged, what happens when the technician cannot state a window?** `BR-FV-002` makes a
   request not a Visit, so a completion that must carry one would make the proposal a condition of
   finishing the field attempt, while `BR-FV-006` says the technician may complete without knowing the
   follow-up's schedule.
3. **Is the request still offered for the other outcomes?** `NEEDS_PARTS` and `UNABLE_TO_COMPLETE` also
   expect a follow-up (`BR-078`). If the inline form is tied to `NEEDS_FOLLOW_UP` alone, the other two
   leave the technician with the old, separate action.
4. **Two writes or one?** The request route is online-only and takes no idempotency key
   (`docs/api/visit-requests.md`, offline standard §13.2), while the completion is offline-capable and
   queued. A single sheet submitting both would have to decide what happens when the completion is queued
   and the request cannot be sent — `BR-032`'s per-operation question, not an implementation detail.

**Recommendation to product ownership (not a decision, so nothing is implemented):** offer the request
inline as an optional section under the chosen follow-up outcome, keep the existing **Request another
visit** action for a request raised outside a completion, and submit the two as two writes in a defined
order (complete first, then the request, each reported on its own) so a queued completion never blocks the
proposal.

---

## 3 — Save/Cancel must stay visible while the keyboard is up

**Reported:** *“please make sure that save and cancel buttons are visible when typing, not just the text,
because user have to hide keyboard to see the buttons.”*

**Status: LANDED (2026-09-28) — the item is a layout defect with no product decision in it, and it is fixed;
the product owner's device pass is outstanding (§3.5).**

### 3.1 What the code showed — and what the first reading of it got wrong

The first reading of this item was that the sheets "do not ask for the IME inset, so the platform keyboard
covers their footer", and it listed five sheets. Two of the five carry **no field at all** —
`ScheduleVisitSheet` (a Visit window: a date, two times and a crew list) and the assign-technicians sheet
(checkbox rows) — so no keyboard can be up in them, and a missing inset could not be their defect. What the
sheets that do carry a field showed instead:

| Sheet | The field the keyboard belongs to | The action under it | Before |
| ----- | --------------------------------- | ------------------- | ------ |
| Visit completion — `ui/jobs/JobDetailsActions.kt` `VisitCompletionSheet` | outcome summary / reason (3 lines) | **Cancel** / **Complete visit**, inside the same scrolling region | scrolled away with the body |
| Request another visit — `ui/jobs/RequestFollowUpVisitSheet.kt` | reason (3–5 lines) | the confirm action, last child of the scrolling region | scrolled away with the body |
| Job update — `ui/jobs/JobUpdateSheet.kt`, note kind and audio kind | the note (4 lines) / the note beside a take | **Cancel** / **Save update**; **Delete** / **Attach to update** | no scrolling region at all: on a screen too short for the content the row was clipped |
| Photo review panel — `ui/jobs/JobPhotoReviewSheet.kt` | optional note (2–3 lines) | **Discard** / **Add**, inside the same scrolling region | scrolled away with the body |
| Schedule technician filter — `ui/schedule/ScheduleTopSection.kt` | search | **Clear** / **Apply**, already outside the scrolling list | already correct (but it carried a stray `imePadding()`, §3.2) |

### 3.2 What the libraries actually do — the cause, checked (2026-09-28)

The sheet is a window of its own and **already lifts above the keyboard**: the missing IME inset was not the
cause. Checked in the versions this project resolves (`android/gradle/libs.versions.toml`: Compose BOM
`2026.08.00` → material3 `1.4.0`, foundation-layout `1.12.0`):

- Material 3's sheet dialog is edge-to-edge — `ModalBottomSheet.android.kt` calls
  `WindowCompat.setDecorFitsSystemWindows(window, false)` and, from API 30, sets
  `SOFT_INPUT_ADJUST_NOTHING`, so the IME does not resize that window — and the sheet pads its content with
  `BottomSheetDefaults.windowInsets` (`ModalBottomSheet.kt` → `Modifier.windowInsetsPadding(...)`), which is
  `WindowInsets.safeDrawing` restricted to the top and bottom sides.
- `safeDrawing` is `systemBars ∪ ime ∪ displayCutout` (`foundation-layout` `WindowInsets.android.kt`), so the
  keyboard's height is part of that padding — and the inset is **consumed** there, which is why a sheet's own
  `imePadding()` adds nothing.
- That matches the report exactly: the text was visible while typing (the sheet had lifted above the
  keyboard) and the action beneath it was not. What was missing was not an inset but the **pin** — an action
  inside the region that shrinks with the keyboard is lost to the scroll.

One sheet carried a stray `Modifier.imePadding()` from the older reading of the problem — the schedule's
technician filter sheet, whose footer was never affected. It was a no-op by the above and is removed, so no
sheet's content insets the keyboard itself.

### 3.3 What has landed — every sheet that carries a field pins its actions (2026-09-28)

**Landed: a sheet's body shrinks and scrolls, and the sheet's actions stay on screen** (`BR-012`, `BR-028`;
the rule is recorded in `docs/design/android-design-system.md` §"Keyboard (IME) inset (signed-in
screens)").

The shape is the same in every sheet that carries a field. The body is a `Column` with
`Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())`, and the action row is the sheet's
last non-scrolling child, because Material 3's sheet content lambda is a `ColumnScope`: while everything
fits, `fill = false` keeps the sheet short and the row sits under the body; when the keyboard shortens the
sheet, the body is capped by what is left and scrolls, so the field being typed in and the action that
confirms it are both on screen. The alignment, spacing, heights, labels and test tags are unchanged —
only where each block is laid out is.

| File | Change |
| ---- | ------ |
| `ui/jobs/JobDetailsActions.kt` (`VisitCompletionSheet`) | the outcome list and the summary field scroll; **Cancel** / **Complete visit** are drawn outside the scroll, under the body |
| `ui/jobs/RequestFollowUpVisitSheet.kt` | the request's fields scroll; the confirm action is drawn outside the scroll |
| `ui/jobs/JobPhotoReviewSheet.kt` | the preview, the phase selector and the note scroll; **Discard** / **Add** are drawn outside the scroll |
| `ui/jobs/JobUpdateSheet.kt` | the kind takes the space the sheet's title and selector leave, so each kind's own body can shrink: the note kind scrolls its field and pins **Cancel** / **Save update**, and the audio kind scrolls the phase selector, the take's review and its note and pins that state's action — **Stop**, **Record** with its hint, or **Delete** / **Attach to update** |
| `ui/schedule/ScheduleTopSection.kt` | the stray `imePadding()` is removed (a no-op, §3.2); that sheet's list and its **Clear** / **Apply** footer already sit in this arrangement |
| `docs/design/android-design-system.md` | the keyboard rule now states the verified Material 3 behaviour, forbids `imePadding()` in a sheet's content, requires a sheet's actions to be outside its scrolling body, and gives the pattern; the photo review panel's row records its pinned foot |

**Not changed, deliberately** (`BR-042`): `ScheduleVisitSheet` and the assign-technicians sheet carry no
field, so no keyboard can cover them, and their bodies already fit the sheet; no behaviour was invented for
a case that cannot occur. No API, shared contract, database or localization change was needed — this is a
layout correction inside the Android client, and every string, tag and capability the sheets use is the one
they already had.

### 3.4 Verification — Tier A (targeted, Android only; `qa.md` §3.1)

Nothing outside `android/` and the two documents changed, so no API, Angular or database command was
required. At the product owner's standing instruction for this machine no lint was run (`qa.md` §3.1 defers
the full lint of an affected application to Tier B, when the feature completes).

| Check | Command | Result |
| ----- | ------- | ------ |
| Android main sources | `cd android && ./gradlew compileDebugKotlin` | **PASS** — `BUILD SUCCESSFUL in 52s`; only pre-existing `KT-73255` annotation-target warnings |
| Android device-test sources, the four new contract tests included | `cd android && ./gradlew compileDebugAndroidTestKotlin` | **PASS** — `BUILD SUCCESSFUL in 4s`; the only message is the pre-existing `createComposeRule` deprecation warning |
| Android JVM unit tests — the two touched packages | `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.*' --tests 'com.servora.android.ui.schedule.*'` | **254 tests, 24 failures — all 24 pre-existing and outside this item** (see below); the other 17 classes pass, `JobDetailsViewModelTest` (72 tests) and the whole `ui.schedule` package among them |
| The four new Compose tests for the sheets | `cd android && ./gradlew connectedDebugAndroidTest` | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). Their sources are compiled; this is the command they would run |
| Android lint | — | **NOT RUN** — at the product owner's instruction for this machine; the tier defers lint to Tier B (`qa.md` §3.1) |
| Android device/emulator commands | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run |
| API / Angular / database | — | **NOT RUN** — nothing outside `android/` changed |

| New test | What it pins |
| -------- | ------------ |
| `JobDetailsScreenTest.keepsThePhotoReviewDecisionsOutOfThePanelsScrollingBody` | the panel's note is inside the scrolling region; **Discard** / **Add** are not |
| `JobDetailsScreenTest.keepsTheVisitCompletionDecisionsOutOfTheSheetsScrollingBody` | the summary field is inside it; **Cancel** / **Complete visit** are not |
| `JobDetailsScreenTest.keepsTheUpdateSheetsSaveAndCancelOutOfItsScrollingBody` | the note is inside it; **Cancel** / **Save update** are not |
| `JobDetailsOverviewScreenTest.keepsTheFollowUpRequestsDecisionOutOfTheSheetsScrollingBody` | the reason is inside it; the request's confirm action is not |

They assert the arrangement rather than a screenshot, because the keyboard cannot be shown or hidden from an
instrumentation test (`ClearFocusWhenImeHiddenTest` says the same about the same limit): a field inside a
`verticalScroll` region is reachable while the region shrinks, and a decision outside it is not carried away.
They are the device test the product owner runs, not a run this task made.

**The 24 failures are pre-existing.** Every one is in `JobPhotoCaptureViewModelTest` — a JVM unit test of
`JobDetailsViewModel`'s photo capture, which this item did not touch — and every one is the same assertion
mismatch: the test expects `JobPhotoFailure.NOT_SIGNED_IN` where the ViewModel answers `NO_VISIT`
(`expected:<NOT_SIGNED_IN> but was:<NO_VISIT>`, first at `JobPhotoCaptureViewModelTest.kt:556`). Both
`JobDetailsViewModel.kt` (348 lines changed) and that test file carry **uncommitted** changes from earlier
work in this working tree, so the divergence predates this item, and the files this item changed are Compose
sheets that no ViewModel test compiles or runs. It is recorded so it is not mistaken for a regression here;
it belongs to whoever finishes that earlier work.

### 3.5 Device QA runbook (product owner)

A keyboard covering an action is only observable on a device, so this is the check the automated tier cannot
make (`qa.md` §7).

```text
Android QA — a sheet's action stays visible while it is typed in

1. Sign in as the Technician and open a Job whose Visit is to be completed; tap Complete visit, then tap the
   outcome summary field and type a sentence.
Expected: the keyboard never covers the Cancel / Complete visit row — both stay on screen and tappable
without dismissing the keyboard.

2. On the same Job, tap Request another visit and type a reason.
Expected: the confirm action stays on screen with the keyboard up.

3. As a Manager in a Job, tap Add update and choose Write note; type a note, then repeat on a short screen or
   in landscape.
Expected: Cancel / Save update stay on screen, and the note field scrolls instead of pushing them off.

4. In the same sheet choose Add audio, record a take, and type a note beside it.
Expected: Delete / Attach to update stay on screen.

5. Capture a photo, open the review panel and type a note.
Expected: Discard / Add stay on screen.

6. Open Schedule → the technician filter and type in the search field.
Expected: Clear / Apply stay on screen (behaviour unchanged — its stray inset call was removed, §3.2).
```

---

## 4 — The **Requests** and **Unassigned** lanes ignore the selected date

**Reported:** *“as a Manager, The tab requests and unassigned seems to be independant of the selected date,
that's confusing, offer me options of best practice in term of user UI.”*

**Status: OPEN — the report is correct, and the options below need one decision from product ownership.**

### 4.1 Why they behave that way — it is deliberate on the API side, and undocumented on the screen

- `ScheduleLane.UNASSIGNED` is documented as “the work that still has no crew, **whatever day it may land
  on**” (`ui/schedule/ScheduleUiState.kt`), and the schedule projection says the same: an unassigned Visit
  may have **no agreed time at all**, so it is “deliberately **not** day-scoped” (`api/src/schedule/schedule.dto.ts`;
  `SCHEDULE_UNASSIGNED_LIMIT`, `UNASSIGNED_EXCLUDED_VISIT_STATUSES`).
- The **Requests** lane is drawn from `ScheduleUiState.pendingRequests`, which is the organization's requests
  read (`GET /jobs/visit-requests`, reviewer scope) — a proposal with its own `proposedStart`/`proposedEnd`
  but nothing that ties it to a day, and `BR-FV-002` says explicitly that a pending request is **not**
  scheduled work and does not count for scheduling.
- The screen, however, presents the three lanes as three tabs of one date-scoped screen whose week strip and
  day heading sit above them (`ScheduleTopSection.kt`), so the manager reasonably reads all three as “this
  day”. Nothing on either lane says “all dates”. The `showsNowCue` rule already concedes the point: the
  “now” cue is drawn only in the SCHEDULE lane “because the unassigned lane holds work that belongs to no
  day”.

So this is a **labelling and information-architecture** problem, not a broken read: the data is right and
the screen implies a scope it does not have.

### 4.2 OPEN QUESTION — the decision this needs (`BR-042`)

Which of the four following shapes does product ownership want? Each is implementable with the read the API
already serves; none of them changes a business rule, and (c) and (d) would need an API projection change
if the *set* changes rather than only its presentation.

| # | Option | What the manager sees | Trade-off |
| - | ------ | --------------------- | --------- |
| a | **Say what each lane is** (smallest change) | Each lane carries its own scope line: `This day` / `All dates · no crew` / `All dates · awaiting you`, with the lane's own count. The week strip stays but is visually owned by the schedule lane | Cheapest and honest; the manager still switches tabs to see work that is not about the day, and the “All dates” line has to be read every time |
| b | **Move them off the day screen** | The day screen keeps only the day. Requests and Unassigned become their own destination(s) with counts on the bottom navigation / a badge, e.g. **Work to place**, where the list is sorted by proposed/needed date | Cleanest separation, and matches “a request is not scheduled work”; costs a navigation destination and a badge, and dispatch loses the one-screen overview |
| c | **Scope them to the day, with an explicit escape** | Both lanes honour the selected date by default (`Unassigned on 28 Sep`) and offer an explicit `Show all dates` control that states how many rows are outside the day | The lanes stop contradicting the strip; but an unassigned Visit with **no** time has no day to appear in, so it must stay outside the scope by definition, and the escape has to be visible enough not to hide work |
| d | **Hybrid: day rows first, the rest counted** | Both lanes show the selected day's rows first, then a single header line `6 more not dated for this day ›` that opens the all-dates list | Keeps the day readable while never hiding work; the most implementation of the four, and needs the day/count split to be computed by the API rather than by the client (`BR-041`) |

**Recommendation to product ownership (not a decision):** (b) for a clean model, or (d) if dispatch needs
one screen — and in either case the explicit words “All dates” wherever a lane is not day-scoped. Until
this is decided, the screen keeps its current behaviour, and only the wording is *not* changed, because
even the wording is a UX decision (`BR-042`).

---

## 5 — The manager's review card, *Clarify*, and what the technician can see afterwards

**Reported:** *“Manager should see only important information: Status of previous visit, reason for new
visit, client details and Approve, Reject, Reschedule, right now the manager sees: Open Job, Clarify, Reject,
Approve and Schedule, and the header is time from-to, reason, same tech or not same tech, it's confusing
because if the manager open job, not details about the visit suggestion are visible in job details, also
clarify doesn't do anything. We need tech to be able to see rejection, then resubmit with a mandatory
reason. Possibly a list of visit requests for tech to keep track.”*

**Status: PARTLY LANDED — the two parts that needed no decision are fixed (§5.5–§5.6), and the technician's
own view of a decision is built on the answer to §5.3.5 (§5.8–§5.9); the review card's contents and the
action set still need decisions §5.3.1–§5.3.4, and the device pass is the product owner's (§5.7, §5.10).**

### 5.1 What the manager sees today, and what the API can even supply

`ScheduleRequestCard` (`android/.../ui/schedule/ScheduleScreen.kt`, ~620–700) draws:

| Part | What it holds |
| ---- | ------------- |
| Header | the proposed range, `formatScheduledRange(proposedStart, proposedEnd)`, or “Not scheduled” |
| Body | the technician's `reason`, capped at three lines |
| Line | `Same technician` — only when `sameTechnicianPreferred` is true |
| Actions | **Open job**, **Clarify**, **Reject**, **Approve** (Approve is offered only when the session holds both `visits.review_requests` and `visits.create_schedule`) |

There is **no job number, no customer, no address and no previous-Visit status** on the card — and the API
cannot put them there today: `FollowUpVisitRequestDto`
(`api/src/jobs/follow-up-visit-request.dto.ts`) carries `jobId` and `sourceVisitId` and the proposal's own
fields, and **nothing about the Job, its Customer or the source Visit's status or outcome**. So “status of
previous visit” and “client details” are a projection addition with an authorization question attached,
not a layout tweak (§5.3.1).

Note also that the requested **Reschedule** already half-exists: **Approve** opens the same Visit-window
form the office uses for any new Visit (`ScheduleViewModel.beginApproval` → `ScheduleVisitSheet`), which
states the date, the times and the crew, so approving with a *different* window is what it does today
(`BR-FV-004` “change the date/time”, `BR-FV-012`). What is missing is the label and whether a change should
be visible as its own decision.

### 5.2 What is wrong regardless of any decision

**(a) and (b) are fixed (§5.5). (c)'s own-only read is built (§5.8), on the answer to §5.3.5; the
Job-scoped read is a later change.**

**(a) A clarified request leaves the only screen that can decide it.**
`ScheduleUiState.pendingRequests` is `visitRequests.filter { it.status == PENDING }`, and the Requests lane
draws that list — while the API treats a clarified request as still reviewable
(`jobs.service.js`: “reviewable when `PENDING` or `NEEDS_CLARIFICATION`”, `api/src/jobs/jobs.service.ts`
~1610) and `BR-FV-012` says a request returned for clarification “remains unresolved until it is later
approved or rejected”. So **Clarify removes the row from the office lane and nothing in the client can ever
approve or reject it again** — the request is stranded until some surface that does not exist resolves it.
This is why *“clarify doesn't do anything”* reads as true from the screen: there is no dialog, no note, no
confirmation and no trace — the card simply vanishes.

**(b) No reason is recorded for a Reject or a Clarify.**
The Android review body carries only `expectedStatus` and `expectedVersion`
(`data/schedule/VisitRequestsRepository.kt`), while the route accepts an optional `note` and `BR-FV-013`
requires a rejection to record “that no further Visit is required or why the request was refused”. The
manager's decision is therefore stored without its reason, in the client that has the reason.

**(c) Noticing a decision is the technician's problem, and today it has no answer.**
Nothing reads a request back to the technician who raised it, on any surface
(`docs/tracker/050-visit-follow-up-requests.md` already records this as its own open item: the requests read
is organization-wide for a reviewer and own-only for a requester, with no Job filter). The technician who
submitted the proposal in the field is told nothing when it is rejected, and has no list of their own
requests to keep track of — which is exactly the second half of the report.

**Answered (2026-09-28, §5.8).** The technician's own requests are now readable on My Schedule, so a
refusal, a clarification and an approval reach the technician who raised the request, with the office's own
words. The **Job-scoped** read — showing a request on the Job it belongs to — is the separate change product
ownership named when it answered §5.3.5.

### 5.3 OPEN QUESTION — the decisions this needs (`BR-042`)

1. **The card's contents.** Confirm the minimum set and where each value comes from: the **previous Visit's
   status and outcome** (from `sourceVisitId`), the **reason** (already there), the **customer's name**, the
   **Job number** and/or the **address**. Each is a projection addition. The customer's name carries an
   authorization question of its own: the capability this route requires is `visits.review_requests`, **not**
   `customers.view` — does holding the review capability alone entitle a reviewer to read the Customer's
   name, or does the projection require `customers.view` as well? `BR-006`/`BR-007` leave that to be decided,
   and it must not be assumed.
2. **The action set.** Is **Reschedule** the existing approval form with a changed window (which is
   `BR-FV-004`'s “change the date/time” and what **Approve** does today — so a rename and a clearer label),
   or a **distinct** action that sends the office's counter-proposal back to the technician for acceptance?
   The second is new business behaviour: nothing in `BR-FV-004`–`BR-FV-012` models a counter-proposal, and
   `BR-FV-012` says an approval whose schedule differs from the proposal is simply approved once the Visit is
   created.
3. **What Clarify is for, now that its consequences are visible.** Options: keep it as an office status
   meaning *we need more from you*, **told to the technician**, who can answer it — which needs the
   technician surface of (c) and a transition the API does not have today; or drop it from the client and
   keep **Reject** with a reason (which would be a business-rule change: `BR-FV-004` names “return the request
   for clarification”).
   **ANSWERED (2026-09-29, product ownership).** **Option 1**: *Clarify* stays, the technician is told what
   is needed, and they answer it. The transition the API did not have now exists —
   `NEEDS_CLARIFICATION → PENDING`, taken by the requester's own answer, in one operation that records the
   answer — and the exchange is a **request-scoped, append-only conversation** rather than a note the next
   decision overwrites. It is built: §5.11. `BR-FV-012` carries the `BR-040` change record, and
   `docs/decisions/023-follow-up-clarification-conversation.md` records D1–D8.
4. **Rejection and resubmission.** `BR-FV-012`'s minimum lifecycle is `PENDING → APPROVED | REJECTED`, and a
   rejected request is terminal, while `BR-FV-009` allows several requests on one Job. So *resubmitting after
   a rejection* is a **new** request for the same Job (and presumably the same source Visit), not a revival
   of the rejected one. Is that the decision — and is the mandatory reason the new request's own `reason`
   (already required by the create route), or a separate “what changed since the rejection” statement?
5. **The technician's request list.** Own-only (possible today: a requester's `GET /jobs/visit-requests`
   returns their own) or Job-scoped (needs a new filter, recorded in tracker 050)? And where does it live —
   Home, Job Details, or My Schedule?

   **ANSWERED (2026-09-28, product ownership).** **Own-only**, as a **dedicated Requests tab beside the
   Schedule tab on My Schedule**. Not on Home, and Job-scoped visibility is a separate change to make when
   Job Details is enhanced. It is built — §5.8.

### 5.4 Plan

1. **Ask for decisions 1–5** (§5.3) and record the answers in the business rules before they are built.
2. **Land the defects that need no decision** (§5.2), as their own scoped change:
   - keep a clarified request visible and reviewable by the office (the API already permits reviewing a
     `NEEDS_CLARIFICATION` request, so the client's `PENDING`-only filter is the defect) — how it is
     presented is a UI decision, but that it stays reachable is not;
   - collect a `note` for **Reject** and **Clarify**, so `BR-FV-013`'s record exists, and say what happened
     instead of letting the card vanish silently;
   - the technician's own status feedback (own-only read) as the smallest version of (c), leaving the
     Job-scoped read and the resubmission flow to the decisions.
3. Then the projection additions (card contents) with their authorization answer, and the technician's
   resubmission flow.

**Step 2 has landed (2026-09-28, §5.5 and §5.8):** the first two defects are fixed, and the third — the
technician's own status feedback — is now built on the answer to decision §5.3.5, as a dedicated
**Requests** tab on My Schedule (§5.8). Step 1 is still open for decisions §5.3.1–5.3.4, and step 3 (the
request card's projected contents and the resubmission flow) still depends on them.

### 5.5 What has landed — a clarified request stays decidable, and a decision records its note (2026-09-28)

**Landed: defects (a) and (b) of §5.2** (`BR-001`, `BR-041`, `BR-FV-012`, `BR-FV-013`, `BR-028`). They are
the two parts of item 5 that needed no product decision: the API already keeps a clarified request
reviewable and already accepts a decision's `note`, so the whole of both defects was in the client.

**A clarified request no longer leaves the only screen that can decide it.** The lane now draws the requests
the API still keeps reviewable — `PENDING` **and** `NEEDS_CLARIFICATION` — through a named predicate rather
than an inline `PENDING` comparison, and the lane's badge counts the same set, so the screen and the backend
answer one question (`BR-041`, `BR-FV-012`). The card also says which of the two states it is in, so the
office never decides about one believing it is the other.

**Every Clarify and Reject is now confirmed, and its note is the decision's record.** Tapping either action
opens a confirmation and sends nothing yet (`BR-FV-013`, `BR-067`): a rejection states why the request was
refused and the action that sends it stays disabled until it does, while a clarification's question is
**offered** rather than required, because no rule states a note requirement for one and a client is not the
place to invent a stricter rule (`BR-042`). The typed text survives a configuration change. The screen then
reports what happened — *"Request returned for clarification."*, *"Request rejected."*, or the failure the
API answered with — instead of the row vanishing in silence.

| File | Change |
| ---- | ------ |
| `ui/schedule/ScheduleUiState.kt` | `reviewableRequests` — the requests whose status `isAwaitingReview` (`PENDING` or `NEEDS_CLARIFICATION`, `BR-FV-012`); the lane's badge counts that same set (`reviewableRequestCount`); the request a review is deciding (`reviewingRequestId`), the status the API applied (`reviewedRequestStatus`) and the refusal (`reviewFailureReason`) |
| `ui/schedule/ScheduleViewModel.kt` | `askForClarification`/`rejectRequest` take the office's `note` and hand it to the repository; a success reports the status the API applied and replaces the request the lane holds with the API's own answer, a failure reports the refusal and claims nothing was decided; `acknowledgeReview()` releases the report |
| `ui/schedule/RequestReviewDialog.kt` (new) | the Clarify/Reject confirmation: one dialog, per-decision copy, one note field, and Reject's action disabled until a reason is written |
| `ui/schedule/ScheduleScreen.kt` | the card's Clarify/Reject open the confirmation rather than sending the decision immediately; a clarified request is marked on its card (`scheduleRequestClarifiedTag`); the decision's outcome or failure is reported through the screen's snackbar (`ScheduleRequestsMessageTag`, renamed from `ScheduleApprovalMessageTag` because it now reports reviews too) |
| `data/schedule/VisitRequestsRepository.kt` | `askForClarification`/`reject` carry the note to the route's `note`; a blank note is sent as no note at all, since an optional field's empty string is not a statement |
| `res/values/strings.xml`, `res/values-fr/strings.xml` | the confirmations' and the outcome notices' copy in both languages (`BR-028`) |
| `test/.../ui/schedule/ScheduleViewModelTest.kt` | four new JVM tests, and the existing requests test moved to the reviewable vocabulary (§5.6) |
| `androidTest/.../ui/schedule/ScheduleScreenTest.kt` | three new device tests (§5.6) |
| `androidTest/.../ui/navigation/ServoraHomeNavigationTest.kt` | its fake repository follows the two new signatures |
| `docs/api/visit-requests.md` | a new **Clients** section: what each surface does with the route, that both writes are online-only, and why the lane draws `PENDING` **and** `NEEDS_CLARIFICATION` |

**Not landed, deliberately** (`BR-042`): the rest of item 5. Defect **(c)** — the technician's own view of a
decision — needs the decision §5.3.5, because where the technician reads their own requests is not one to be
assumed. The card's requested contents (the previous Visit's status and outcome, the customer, the Job
number, the address) are projection additions carrying the authorization question §5.3.1 states, and the
action set is §5.3.2. The resubmission flow after a rejection is §5.3.4.

### 5.6 Verification — Tier A (targeted, Android only; `qa.md` §3.1)

Only `android/` and one API document changed. No server change was needed: the route already accepts and
records `note` on a clarification, a rejection and an approval, and `api/test/visit-requests.e2e-spec.ts`
already covers each of them, so no API command was run either. At the product owner's standing instruction
for this machine no lint was run (`qa.md` §3.1 defers the full lint of an affected application to Tier B).

| Check | Command | Result |
| ----- | ------- | ------ |
| Android main sources, device-test sources and the touched JVM package | `cd android && ./gradlew compileDebugKotlin compileDebugAndroidTestKotlin testDebugUnitTest --tests 'com.servora.android.ui.schedule.*'` | **PASS** — `BUILD SUCCESSFUL in 1m 12s`; **64 tests, 0 failures, 0 errors** (`ScheduleViewModelTest` 24, `SchedulePresentationTest` 17, `TechnicianScheduleViewModelTest` 10, `TechnicianSchedulePresentationTest` 8, `ScheduleWeekTest` 5) |
| The new Compose tests for the lane and the confirmations | `cd android && ./gradlew connectedDebugAndroidTest` | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). Their sources are compiled by the run above; this is the command they would run |
| Android lint | — | **NOT RUN** — at the product owner's instruction for this machine; the tier defers lint to Tier B (`qa.md` §3.1) |
| Android device/emulator commands | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run |
| API / Angular / database | — | **NOT RUN** — nothing outside `android/` and one API document changed |

| Test | What it pins |
| ---- | ------------ |
| `ScheduleViewModelTest` `keeps a request returned for clarification awaiting a decision` | a `NEEDS_CLARIFICATION` request stays in the lane and in the badge |
| `ScheduleViewModelTest` `returns a request for clarification with what is still needed` | the office's note reaches the repository, the status the API answered with is what is reported, and the request stays reviewable |
| `ScheduleViewModelTest` `rejects a request with the reason it was refused` | the same for a rejection, and that a `REJECTED` request leaves the lane — it is no longer awaiting review |
| `ScheduleViewModelTest` `reports a review the API refused instead of presenting it as decided` | a refused decision reports its failure, reports no outcome, and leaves the request as the backend still holds it |
| `ScheduleScreenTest` `keepsARequestReturnedForClarificationInTheLane` | the lane draws the clarified request and its marker |
| `ScheduleScreenTest` `rejectsARequestOnlyWithTheReasonItWasRefused` | the rejection's action is disabled until a reason is written, and the reason is what is sent |
| `ScheduleScreenTest` `returnsARequestForClarificationWithWhatIsStillNeeded` | the clarification is sent with the office's own words, and is sendable without them |

The three `ScheduleScreenTest` cases are device tests the product owner runs, not a run this task made
(`qa.md` §7.3). The `ui.schedule` filter is deliberately narrower than the whole JVM suite: it is the package
this change touches, and it keeps out the 24 pre-existing `JobPhotoCaptureViewModelTest` failures §3.4
records, which this change neither causes nor touches.

### 5.7 Device QA runbook (product owner)

Why the lane still holds a request, and whether a confirmation reads clearly with the keyboard up, are only
observable on a device (`qa.md` §7).

```text
Android QA — reviewing a follow-up request as the office

1. Sign in as a Manager, open Schedule and switch to the Requests lane; note a request whose status is
   pending.
2. Tap Clarify, type "Which part number?", and send it.
Expected: a message saying the request was returned for clarification; the card stays in the lane, now
marked as needing clarification, and still offers Approve, Clarify and Reject.

3. Leave Schedule (go to another destination) and come back to the Requests lane, then open the same card.
Expected: the request is still there and still decidable — clarifying it did not take it off the screen.

4. Tap Reject on that request and try to send it with the field empty, then type a reason and send.
Expected: the Reject action is disabled until something is written; after sending, a message says the
request was rejected and the card leaves the lane.

5. Put the device in airplane mode and take a decision on another request.
Expected: a message saying the decision could not be recorded; the card stays exactly as it was, with
nothing presented as decided.

6. Repeat step 2 with the application language set to French.
Expected: the confirmation, its labels and the outcome message are all in French, and the card is otherwise
unchanged.
```

### 5.8 What has landed — the technician's own requests on My Schedule (2026-09-28)

**Landed: the smallest version of (c), on the answer to §5.3.5** (`BR-001`, `BR-011`, `BR-013`, `BR-028`,
`BR-041`, `BR-FV-001`, `BR-FV-002`, `BR-FV-012`, `BR-FV-013`).

My Schedule now has two views. Beside **Schedule**, a **Requests** tab lists the caller's own follow-up
requests, newest first, each with the status the office decided it in and — when the office wrote one — the
office's note, so the decision reaches the technician who made the proposal and a refusal says why it was
refused (`BR-FV-013`). The count on the tab is what the office has still to answer: a request returned for
clarification stays in it, because `BR-FV-012` keeps it unresolved until it is approved or rejected.

Scope and authority:

- The read is `GET /jobs/visit-requests`, the route the office reviews on. A caller who does not hold
  `visits.review_requests` is answered with **their own** requests only
  (`JobsService.listFollowUpVisitRequests`), so the technician's list is the API's own scope answer: the
  device applies no filter of its own and names no technician (`BR-007`, `BR-009`).
- The tab is offered on the capability the route accepts for that read, `visits.request_follow_up`
  (`BR-011`): a session without it is offered no view whose rows it could not fetch.
- A request is not an appointment. The card states the window as **what the technician proposed**, and the
  Visit an approval created is what the Schedule view shows (`BR-FV-002`, `BR-FV-005`, `BR-FV-010`).
- The card carries **no customer, address or Job number**, because the API's request projection holds none
  of them. That is the projection addition decision §5.3.1 has still to make, with its authorization
  question; a client-side copy of the Customer read here is not this screen's to invent
  (`BR-001`, `BR-042`, `BR-092`). The card opens the Job, which is where the work itself is.
- **No notification behaviour is invented** (`BR-029`): this is a list the technician opens, and the count
  of unanswered requests is information rather than an alert.

Offline: the requests read is **online-only** (`BR-013`, `BR-014`, offline standard §13). It has no
working-set projection, and a request's state is only useful while it is current — a locally stored
"rejected" presented as the office's answer is exactly what must not happen — so a read the backend could
not answer is reported instead, and a list already read stays on screen under a "couldn't refresh" notice
(standard §7). What would change that: a working-set projection for the own-requests read and a decision on
how stale such an answer may be.

| File | Change |
| ---- | ------ |
| `ui/schedule/TechnicianScheduleUiState.kt` | `TechnicianScheduleTab` (`SCHEDULE`, `REQUESTS`) and the requests state — whether the list has been read, the list itself, and why a read failed — with the derived first-load, empty, unrefreshed and awaiting-a-decision answers |
| `ui/schedule/TechnicianScheduleViewModel.kt` | injects `VisitRequestsRepository`; `selectTab` reads the requests when that view is entered, `start` refreshes the view that is open, and `retry` retries the view on screen; a request read takes its own generation, so an answer arriving after the session ended is dropped (`BR-001`, `BR-067`) |
| `ui/schedule/TechnicianScheduleScreen.kt` | the two-view tab row (the office screen's lane-selector pattern), the requests list with its first-load, empty, failure and unrefreshed states, and the request card — status, proposed window, reason, the same-technician line, the office's note, and the way to the Job; the date controls are drawn for the schedule view only |
| `ui/components/ServoraStatusPill.kt` | `RequestStatusPill` and `requestStatusLabel`: the request vocabulary's own chip, so a request's state is never presented in a Visit's or a Job's words (`BR-041`, `BR-047`) |
| `ui/customers/CustomersScreen.kt` | passes the asking capability and `selectTab` to the screen |
| `res/values/strings.xml`, `res/values-fr/strings.xml` | the tab labels, the four request statuses, and the loading, empty, failure, unrefreshed, proposal and office-note copy in both languages (`BR-028`) |
| `test/.../ui/schedule/TechnicianScheduleViewModelTest.kt` | eight new JVM tests and a fake requests repository for the ViewModel's new collaborator (§5.9) |
| `test/.../ui/components/RequestStatusLabelTest.kt` (new) | every request status is labelled in its own words, and a clarified request carries the office screen's own label for it (`BR-041`) |
| `androidTest/.../ui/schedule/TechnicianScheduleScreenTest.kt` | nine new device tests (§5.9) |
| `androidTest/.../ui/navigation/ServoraHomeNavigationTest.kt` | its ViewModel construction follows the new collaborator |
| `docs/api/visit-requests.md` | the **Clients** table: the technician's own read of the route now has a client |
| `docs/design/android-design-system.md` | the two-view tab row is recorded, including why no date control is drawn beside the requests view |

**Not landed, deliberately** (`BR-042`): the review card's projected contents and the action set
(§5.3.1–§5.3.2), what Clarify should mean now that its consequence is visible to the technician (§5.3.3),
and the resubmission-after-a-rejection flow (§5.3.4). The **Job-scoped** request read is the later change
product ownership named when it answered §5.3.5.

### 5.9 Verification — Tier A (targeted, Android only; `qa.md` §3.1)

Only `android/` and two documents changed, and no server change was needed: the route and its own-only scope
for a requester already existed, and `api/test/visit-requests.e2e-spec.ts` covers it. At the product owner's
standing instruction for this machine no lint was run (`qa.md` §3.1 defers the full lint of an affected
application to Tier B).

| Check | Command | Result |
| ----- | ------- | ------ |
| Android main sources, device-test sources and the touched JVM packages | `cd android && ./gradlew compileDebugKotlin compileDebugAndroidTestKotlin testDebugUnitTest --tests 'com.servora.android.ui.schedule.*' --tests 'com.servora.android.ui.components.*'` | **PASS** — `BUILD SUCCESSFUL in 21s`; **73 tests, 0 failures, 0 errors** (`ScheduleViewModelTest` 24, **`TechnicianScheduleViewModelTest` 18**, `SchedulePresentationTest` 17, `TechnicianSchedulePresentationTest` 8, `ScheduleWeekTest` 5, `RequestStatusLabelTest` 1) |
| The new Compose tests for the tab, the list and the card | `cd android && ./gradlew connectedDebugAndroidTest` | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). Their sources are compiled by the run above; this is the command they would run |
| Android lint | — | **NOT RUN** — at the product owner's instruction for this machine; the tier defers lint to Tier B (`qa.md` §3.1) |
| Android device/emulator commands | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run |
| API / Angular / database | — | **NOT RUN** — nothing outside `android/` (and documentation) changed; the read the client now makes is the route's existing own-only answer |

| New test | What it pins |
| -------- | ------------ |
| `TechnicianScheduleViewModelTest` `reads the caller's own requests when the requests view is opened` | the day's read asks for nothing, the requests are read on entering their view, and the rows are the API's own list |
| `TechnicianScheduleViewModelTest` `leaves the day it was showing on screen when the requests view is opened` | the two views are independent — entering the requests view keeps the day and does not read it again |
| `TechnicianScheduleViewModelTest` `does not read the day again when the schedule view is returned to` | returning to the schedule is not a reason to read the day again; the destination's re-entry is |
| `TechnicianScheduleViewModelTest` `reports a failed request read instead of presenting an empty list` | a read that never arrived is not the answer "you have no requests" |
| `TechnicianScheduleViewModelTest` `keeps the list it already had when a later request read fails` | a read that fails after the list was known leaves the list and says it is not current |
| `TechnicianScheduleViewModelTest` `counts only the requests the office still has to answer` | a clarified request still counts, an approved or refused one does not, and the API's own order is kept |
| `TechnicianScheduleViewModelTest` `brings a decision the office made back when the screen is re-entered` | a rejection and its reason reach the technician when the destination is re-entered |
| `TechnicianScheduleViewModelTest` `drops a request answer that arrives after the session ended` | an answer that outlives its session is dropped |
| `RequestStatusLabelTest` `labels every request status with its own words` | one status, one label, and the clarified state shares the office screen's words (`BR-041`) |
| `TechnicianScheduleScreenTest` `offersTheRequestsViewOnlyToASessionThatMayReadIt` | no tab for a session that may not read the route |
| `TechnicianScheduleScreenTest` `offersTheCallersOwnRequestsBesideTheirDay` | the tab row is drawn with the schedule as the view in effect |
| `TechnicianScheduleScreenTest` `reportsTheViewTheTechnicianChose` | the tab reports the technician's choice |
| `TechnicianScheduleScreenTest` `countsTheRequestsStillAwaitingAnAnswer` | the badge counts the unanswered requests |
| `TechnicianScheduleScreenTest` `showsARejectedRequestWithTheReasonItWasRefused` | a refusal and the office's note are on the card, and the window still reads as a proposal |
| `TechnicianScheduleScreenTest` `keepsTheDateControlsOffTheRequestsView` | no date control sits beside a view that is not date-scoped |
| `TechnicianScheduleScreenTest` `saysWhenTheCallerHasAskedForNothing` | the empty answer is stated rather than shown as an empty list |
| `TechnicianScheduleScreenTest` `offersARetryWhenTheRequestsCouldNotBeRead` | a failed read offers the retry |
| `TechnicianScheduleScreenTest` `opensTheJobARequestBelongsTo` | the card opens the Job |

The nine `TechnicianScheduleScreenTest` cases are device tests the product owner runs, not a run this task
made (`qa.md` §7.3).

### 5.10 Device QA runbook — the technician's own requests (product owner)

```text
Android QA — the technician sees what the office decided

1. Sign in as the Technician, open My Schedule and note the two tabs; the Requests tab carries a count
   while something is still waiting for the office.
2. As a Manager (another session or device), reject that technician's follow-up request with a reason.
3. Back as the Technician, leave My Schedule (go to another destination) and return to it, then open the
   Requests tab.
Expected: the request is listed as Rejected with the office's note beside it, and the count has dropped.

4. As the Manager, approve another request; then read it again as the Technician.
Expected: it shows as Approved, and the Visit the approval created appears on the Schedule tab for the day
it was scheduled for.

5. Put the device in airplane mode, then return to My Schedule with the Requests tab open.
Expected: the tab says the requests could not be refreshed and keeps the list it had; opening the Schedule
tab still shows the day it already had.

6. Ask for another follow-up visit from a Job, then open the Requests tab.
Expected: the new request is there, newest first, marked as awaiting review, with its window stated as what
the technician proposed rather than as a scheduled visit.
```

---

### 5.11 What has landed — the clarification conversation and the answer that returns a request to review (2026-09-29)

**Landed: the second half of item 5, on the answer to §5.3.3** (`BR-001`, `BR-006`, `BR-007`, `BR-009`,
`BR-011`, `BR-012`, `BR-013`, `BR-014`, `BR-031`, `BR-032`, `BR-033`, `BR-041`, `BR-042`, `BR-067`,
`BR-068`, `BR-FV-001`, `BR-FV-002`, `BR-FV-012`, `BR-FV-013`).

The office could return a request for clarification, and the person the question was addressed to could not
answer it: there was no transition back and no route, and the question itself lived in a single mutable
column (`review_note`) that the next decision overwrote. Both halves are now built.

**One new transition, taken by the requester.** `POST /jobs/:id/visit-requests/:requestId/reply` appends
the answer to the request's conversation **and** moves the request from `NEEDS_CLARIFICATION` back to
`PENDING` — the state the office reviews it in — in one transaction over the locked request row. A request
another member raised is reported as `404`, never `403`, so an id never becomes a way to reach someone
else's request; a request that owes no answer is refused with its own code
(`FOLLOW_UP_VISIT_REQUEST_NOT_AWAITING_REPLY`), and an answer that says nothing is refused as validation.
The transition exists because product ownership decided it does (§5.3.3), and it is recorded in `BR-FV-012`
and `ADR-023` — not invented by a client.

**The conversation is stored, not a note.** `follow_up_visit_request_messages` (migration 0018) is
request-scoped and append-only, one row per message with its author and the server time. The office's
clarification question enters it in the same transaction as the return, so the thread holds both sides and
survives the decision that follows it. The side a message belongs to is **derived** from the request's own
requester (`authorKind`: `REQUESTER` | `OFFICE`) rather than stored, so no second vocabulary for "who is
who" exists in the schema. Every projection of a request carries its `messages`, so the office's review read
and the requester's own read see the same exchange — no new read route, and no new authorization question
for one (`BR-041`, `BR-042`).

**Both surfaces read the thread, and only the requester writes in it.** The technician's Requests tab draws
the conversation and offers **Answer** while an answer is owed; the office's Requests lane draws the same
thread on the request card, so a manager decides on the answer they asked for. One message is attributed per
reader — "You" to the technician who wrote it, "Office" to both, "Technician" to the office reading the
requester's answer. The composer is a confirmation rather than an inline field on a card that opens a Job,
because the whole card is tappable and a technician typing an answer must not be navigated away by a stray
tap (`BR-012`); it repeats the office's question and cannot send an empty answer.

| File | Change |
| ---- | ------ |
| `api/drizzle/migrations/0018_follow_up_visit_request_messages.sql`, `meta/0018_snapshot.json` | the append-only conversation table, generated by `db:generate` (which also re-baselined the snapshot chain at this migration) |
| `api/src/database/schema.ts` | `followUpVisitRequestMessages`: the request it belongs to, the author, the body, the recorded time, a not-blank check and one index |
| `api/src/jobs/job.types.ts` | `FOLLOW_UP_VISIT_REQUEST_MESSAGE_AUTHOR_KINDS` — the derived `REQUESTER`/`OFFICE` vocabulary |
| `api/src/jobs/follow-up-visit-request.dto.ts` | `messages` on every request projection, the message DTO with its derived `authorKind`, and the reply payload with its parser |
| `api/src/jobs/jobs.service.ts` | `replyToFollowUpVisitRequest` (locked, one transaction), the conversation reader/appender, `reviewFollowUpVisitRequest` now transactional and appending the office's question, and the batch load that keeps a list read one query |
| `api/src/jobs/jobs.controller.ts` | the `reply` route (`visits.request_follow_up`) and `FOLLOW_UP_VISIT_REQUEST_NOT_AWAITING_REPLY` |
| `android/.../domain/model/Schedule.kt` | `FollowUpVisitRequestMessage`, its author kind, and `messages` on `FollowUpVisitRequest` |
| `android/.../data/schedule/VisitRequestDtos.kt`, `VisitRequestsApi.kt`, `VisitRequestsRepository.kt` | the wire contracts, the `reply` call, and the answer's own session-renewal path |
| `android/.../ui/schedule/FollowUpRequestConversation.kt` (new) | the conversation composable, and the decisions it draws from: speaker attribution, which of the note/thread a card states, the moment formatting, `awaitsAnswer` |
| `android/.../ui/schedule/RequestAnswerDialog.kt` (new) | the requester's composer: the office's question repeated, the send action disabled until an answer is written, the draft surviving a configuration change |
| `android/.../ui/schedule/TechnicianScheduleUiState.kt`, `TechnicianScheduleViewModel.kt` | the answer's own state (`replyingRequestId`, `answeredRequestId`, `replyFailureReason`), `replyToRequest` and `acknowledgeReply`; the API's own answer replaces the request the list holds |
| `android/.../ui/schedule/TechnicianScheduleScreen.kt` | the conversation and the **Answer** action on the request card, and the requests view's own snackbar reporting the answer's outcome |
| `android/.../ui/schedule/ScheduleScreen.kt` | the office card draws the same conversation |
| `android/.../ui/customers/CustomersScreen.kt` | passes the two new callbacks to the technician screen |
| `res/values/strings.xml`, `res/values-fr/strings.xml` | the speakers, the composer and its outcome notices in both languages (`BR-028`) |
| `test/.../ui/schedule/TechnicianScheduleViewModelTest.kt` | three new JVM tests |
| `test/.../ui/schedule/FollowUpConversationTest.kt` (new) | eight JVM tests of the presentation decisions |
| `androidTest/.../ui/schedule/TechnicianScheduleScreenTest.kt` | three new device tests, and its `technicianState` helper now forwards the view and the caller's own requests (it dropped them, so its requests-view tests were asserting against the day) |
| `androidTest/.../ui/schedule/ScheduleScreenTest.kt` | one new device test: the office card shows the answer |
| `androidTest/.../ui/navigation/ServoraHomeNavigationTest.kt`, `test/.../ScheduleViewModelTest.kt` | their fakes follow the new repository signature |
| `docs/api/visit-requests.md`, `docs/domain/job-visit-domain-model.md` §11, `.clinerules/Business Rules.md` (`BR-FV-012`), `docs/decisions/023-follow-up-clarification-conversation.md` | the contract, the table and the transition, the `BR-040` change record, and the decision record |

#### Verification (Tier A targeted on Android; Tier B for the API, because the slice completes there)

| Check | Command | Result |
| ----- | ------- | ------ |
| API types and the unit spec of the changed DTO | `cd api && npm run typecheck && npm test -- src/jobs/follow-up-visit-request.dto.spec.ts` | **PASS** — `tsc --noEmit` clean; **10 tests, 0 failures** (3 new) |
| API e2e for the new route and the touched service paths | `cd api && npm run test:e2e -- test/visit-requests.e2e-spec.ts` | **PASS** — **8 tests, 0 failures**, including the new `records a clarification conversation and lets the requester answer it back into review`: `404` for another member, `403` for a reviewer, `409` for a stale answer, `400` for an empty one, the conversation read by both sides, the answer back in review, and the approval that follows |
| Android main sources, device-test sources and the touched JVM package | `cd android && ./gradlew compileDebugKotlin compileDebugAndroidTestKotlin testDebugUnitTest --tests 'com.servora.android.ui.schedule.*'` | **PASS** — `BUILD SUCCESSFUL`; **83 tests, 0 failures** (`FollowUpConversationTest` 8, `ScheduleViewModelTest` 24, `TechnicianScheduleViewModelTest` 21, `SchedulePresentationTest` 17, `TechnicianSchedulePresentationTest` 8, `ScheduleWeekTest` 5) |
| API, full (lint, every unit test, build, every e2e) | `make api-lint && make api-test && make api-build && make api-test-e2e` | **PASS** — lint: *Found 0 warnings and 0 errors* (213 files); unit: **553 tests / 58 files, 0 failures**; build: `nest build` exit 0; e2e against PostgreSQL: **393 tests / 23 files, 0 failures** |
| The new Compose tests | `cd android && ./gradlew connectedDebugAndroidTest` | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). Their sources are compiled by the run above; this is the command they would run |
| Android lint | — | **NOT RUN** — at the product owner's instruction for this machine; the tier defers lint to Tier B (`qa.md` §3.1) |
| Android device/emulator commands | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run |

| Test | What it pins |
| ---- | ------------ |
| `TechnicianScheduleViewModelTest` `answers a returned request and holds what the API answered` | the answer reaches the repository once (a second attempt while one is in flight is not sent), the request the list holds is the API's own — status, version and conversation — and `acknowledgeReply` releases the report |
| `TechnicianScheduleViewModelTest` `reports an answer the API refused instead of presenting it as recorded` | a refused answer reports its failure, reports no outcome, and leaves the request exactly as the backend holds it |
| `TechnicianScheduleViewModelTest` `answers nothing when the request owes no answer` | a request awaiting review is not answerable, and nothing is sent for it |
| `FollowUpConversationTest` (8 cases) | which of the note and the thread a card states, that a terminal decision's note is always stated, the speaker each side is attributed to per reader, the moment in the reader's own zone, no invented moment for an unreadable time, and that only a clarified request owes an answer |
| `TechnicianScheduleScreenTest` `drawsTheClarificationConversationTheApiAnswered` | both sides of the exchange are on the card, and the question is stated once |
| `TechnicianScheduleScreenTest` `offersNoAnswerToARequestThatOwesNone` | a request the office holds draws no conversation and no answer action |
| `TechnicianScheduleScreenTest` `answersAReturnedRequestWithWhatIsTyped` | the composer opens on **Answer**, its send action is disabled until something is written, and what is typed is what is sent |
| `ScheduleScreenTest` `showsTheAnswerOnTheRequestItAnswers` | the office reads the same thread, with the requester's answer attributed to the technician |

#### Device QA runbook (product owner)

```text
Android QA — the technician answers a request the office returned

1. Sign in as a Manager, open Schedule → Requests, and Clarify a pending request with "Which part number?".
Expected: the request stays in the lane, marked as returned for clarification, and the card now shows the
question under it.

2. As the Technician, open My Schedule → Requests and find that request.
Expected: the card shows the office's question as a message from the Office with the time it was recorded,
and offers Answer — no other request offers it.

3. Tap Answer, leave the field empty, then type "PN-4471." and send.
Expected: Send answer is disabled while the field is empty; after sending, a message says the answer was
recorded, the status reads Awaiting review, and the conversation shows your answer as "You" under the
office's question.

4. As the Manager, return to the Requests lane and open the same card.
Expected: the lane holds it as pending, the card shows the technician's answer attributed to the
Technician, and Approve & schedule / Clarify / Reject are offered.

5. Put the device in airplane mode and try to answer a returned request.
Expected: the answer is refused with a message saying it was not recorded; the request is unchanged and
still asks for an answer, and nothing is presented as sent.

6. Repeat step 3 with the application language set to French.
Expected: the composer, the speaker labels and the outcome message are all in French, and the exchange is
otherwise unchanged.
```

#### Hygiene

The task ran four Gradle invocations — an initial compile, one repeated after a first run failed on a
third-party `assertDoesNotExist` import that was then removed, the verification run, and a final re-run
after a readability edit to one predicate — which started the Gradle and Kotlin build daemons;
`make android-stop` was run after the last Gradle command and both are gone (`pgrep` finds none, and
`./gradlew --status` reports no daemons running); `make tidy` was run too and reported nothing else this
task left behind. The
build and test logs this task wrote under `/tmp` were removed. No `adb` and no device or emulator command
was run (`qa.md` §7.3). The API e2e run applied the pending migrations — including
`0018_follow_up_visit_request_messages` — to the configured development database, because that is what the
suite's global setup does; no database or storage volume was deleted, and the foundation stack was neither
started nor stopped by this task. No file was opened in the product owner's editor.

### 5.12 What has landed — the technician's request details (2026-09-29)

**Landed: a request's own row opens the request rather than the Job** (`BR-001`, `BR-011`, `BR-013`,
`BR-028`, `BR-041`, `BR-066`, `BR-FV-001`, `BR-FV-002`, `BR-FV-012`, `BR-FV-013`).

Tapping a request in My Schedule's **Requests** view opened the **Job Details** screen. That reads as
"the request *is* the Job", and a request is not the work: it is a **proposal about another field
attempt** (`BR-FV-002`, `BR-047`). The row now opens the request itself, and the Job the proposal is for
is one action away from it (`BR-066`).

| Part | What it does |
| ---- | ------------ |
| Route | `request/detail/{requestId}`, one of the shell's pushed destinations (`docs/decisions/010-android-navigation.md`), with a back control and the title *Request details* / *Détail de la demande*. |
| The request | The office's decision on it (`RequestStatusPill`, `BR-FV-012`), the window the technician **proposed** — named as a proposal, never as an appointment (`BR-FV-002`) — why another attempt was needed, the same-technician preference (`BR-FV-003`), and the office's own note when the office wrote one (`BR-FV-013`). |
| The conversation | The same `FollowUpRequestConversation` the row and the office's card draw, under its own section label: the office's question and the technician's answer, oldest first, each attributed per reader (`BR-FV-012`, `BR-041`). |
| **View job details** | Leaves the request for the Job it was raised on. It is a **pushed** destination, so Back returns to the request the technician was reading. |
| **Answer** | Offered only while the request owes one (`NEEDS_CLARIFICATION`, `BR-FV-012`), and never twice while an answer is on its way (`BR-031`). It opens the same composer the row offers, repeats the office's question, and cannot send an empty answer. The screen reports the API's own outcome — recorded, or refused — and the request the screen shows is replaced by the API's answer (`BR-001`). |

Scope and authority:

- The destination **reads nothing of its own**. The request is resolved out of the caller's own request
  read, which is the route the office reviews on and which answers a requester with their own rows only
  (`BR-009`, `BR-FV-001`, `docs/decisions/023-follow-up-clarification-conversation.md` D5): there is no
  per-request read, so a request the list does not hold is never guessed at.
- Three answers are therefore distinguished rather than collapsed into one empty screen (`BR-042`):
  **nothing read yet** (the first-load state), **the read failed** (reported, with a retry where the
  technician is standing, `BR-013`), and **the read answered and this is not one of the caller's own**
  (stated plainly, with nothing invented to fill the screen).
- The read is asked for **only when nothing has answered for it yet**, which is what a back stack restored
  after the process was killed composes; opening a row of a list already on screen re-reads nothing
  (`BR-013`). The view is set to the requests view at the same time, so Back lands on the list the request
  came from (`BR-012`).
- **Nothing about the request is derived on the device** (`BR-001`, `BR-041`): the status, the window, the
  office's note, the conversation and the answer's outcome are the backend's answers, and the row and the
  details state them with one vocabulary — the proposal is worded once (`proposedWindow`) and the
  same-technician line is one composable, so the two surfaces cannot drift apart.
- **No API change and no new capability.** The destination uses the reads and the write the technician's
  Requests view already uses (`GET /jobs/visit-requests`, `POST …/reply`), both online-only
  (`docs/api/visit-requests.md` §Clients, `BR-013`, `BR-032`). Nothing is queued and nothing is stored.
- **The row keeps what §5.8 and §5.11 landed.** It still states the office's note, the conversation and the
  way to answer, so the QA runbooks of those sections still hold; only what a tap does changed.

| File | Change |
| ---- | ------ |
| `ui/navigation/ServoraNavHost.kt` | `ServoraRoutes.REQUEST_ID` / `REQUEST_DETAIL` / `requestDetail(requestId)`, the destination, its argument and its top-bar title; the destination asks the schedule's ViewModel for the requests read when nothing has answered yet |
| `ui/schedule/TechnicianSchedulePresentation.kt` | `RequestDetailsContent` and `requestDetailsContent(state, requestId)` — the request the list holds, or which of the three read answers the screen is showing |
| `ui/schedule/TechnicianScheduleViewModel.kt` | `openOwnRequests()` — sets the requests view and reads it only when nothing has answered for it yet |
| `ui/schedule/FollowUpRequestDetailsScreen.kt` (new) | the destination's screen: the request's own record, the conversation, **View job details**, **Answer**, and the loading / failure / unavailable states |
| `ui/schedule/TechnicianScheduleScreen.kt` | the request row opens the request (`onOpenRequest`) rather than the Job (`onOpenJob`), which the schedule view's Visit rows still use; `proposedWindow` and the same-technician line are shared with the details screen |
| `ui/customers/CustomersScreen.kt` | the requests row navigates to `request/detail/{requestId}` |
| `res/values/strings.xml`, `res/values-fr/strings.xml` | the destination's title, its section label, its Job action and its unavailable state in both languages (`BR-028`) |

**Not changed, deliberately:** the row's own contents (`BR-042` — §5.3.1's projection addition is still
the decision), the office's card and lane, and the request routes.

#### Verification (Tier A targeted on Android; `qa.md` §3.1)

| Check | Command | Result |
| ----- | ------- | ------ |
| Android compile, device-test source compile, JVM tests of the `ui.schedule` package | `cd android && ./gradlew compileDebugKotlin compileDebugAndroidTestKotlin testDebugUnitTest --tests 'com.servora.android.ui.schedule.*'` | **PASS** — `BUILD SUCCESSFUL` (41s on the first run, after two syntax errors in this task's own new file were corrected; 13s on the final re-run after a French string was corrected to the vocabulary the app already uses). 91 JVM tests in the six `ui.schedule` classes, 0 failures (`TechnicianScheduleViewModelTest` 24, `ScheduleViewModelTest` 24, `SchedulePresentationTest` 17, `TechnicianSchedulePresentationTest` 13, `FollowUpConversationTest` 8, `ScheduleWeekTest` 5) |
| The device-test APK, so the new and updated Compose tests are known to build | `cd android && ./gradlew assembleDebugAndroidTest` | **PASS** — `app-debug-androidTest.apk` assembled |
| The full JVM suite, for the shared navigation shell this change adds a destination to | `cd android && ./gradlew testDebugUnitTest assembleDebugAndroidTest` (the same suite `make android-test` runs) | **24 failures, all in `ui.jobs.JobPhotoCaptureViewModelTest`** — pre-existing in this branch's uncommitted evidence work and unrelated to this change (the class references nothing this task touched, and it fails identically when run alone). Every other class passes, the `ui.schedule` classes included |
| The new Compose tests | `cd android && ./gradlew connectedDebugAndroidTest` | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). Their sources are compiled and packaged by the runs above; this is the command they would run |

| Test | What it pins |
| ---- | ------------ |
| `TechnicianScheduleScreenTest` `opensTheRequestACardIsFor` | touching a request row reports that **request**, not the Job it is about |
| `FollowUpRequestDetailsScreenTest` `showsTheRequestAndLeavesTheJobOneActionAway` | the request's own record is drawn, and **View job details** reports the Job |
| `FollowUpRequestDetailsScreenTest` `statesTheOfficeQuestionOnceAndAnswersItFromTheRequest` | the conversation is drawn with the question stated once, the composer repeats it, an empty answer cannot be sent, and the typed answer is what is sent |
| `FollowUpRequestDetailsScreenTest` `offersNoAnswerToARequestThatOwesNone` | a request awaiting the office draws the office's note and no answer action |
| `FollowUpRequestDetailsScreenTest` `reportsARequestReadThatFailedAndOffersARetry` | a failed request read is reported and can be retried |
| `FollowUpRequestDetailsScreenTest` `saysWhenTheCallersOwnReadDoesNotHoldTheRequest` | a request the caller's own read does not hold is stated plainly, and no request is invented |
| `FollowUpRequestDetailsScreenTest` `statesTheFirstReadAsLoading` | nothing answered yet is the first-load state rather than "no request" |
| `TechnicianSchedulePresentationTest` (5 cases) | the request the list holds resolves to itself, and the three read answers resolve to unavailable, reading and failed — including a list that still holds the request after a refresh failed |
| `TechnicianScheduleViewModelTest` (3 cases) | opening a request reads the list once when nothing has answered, reads nothing when the list already holds it, and retries a read that failed |

#### Device QA runbook (product owner)

```text
Android QA — the technician opens one of their own requests

1. Sign in as a Technician, open My Schedule → Requests, and tap a request's row.
Expected: the **Request details** screen opens — not Job Details — with the office's decision, the time
you proposed, why you asked for another visit, and the same-technician line.

2. Tap **View job details**.
Expected: the Job Details screen opens for that Job; Back returns to the request you were reading.

3. Back on the request, tap Back.
Expected: you are back on the Requests list, with the Requests view still selected.

4. As a Manager, on the same request: Clarify it with "Which part number?".
Expected: the request's card in the lane is marked as returned for clarification.

5. As the Technician, open that request again from Requests.
Expected: the conversation shows the office's question with the time it was recorded, the screen offers
**Answer**, and the office's question is not stated twice.

6. Tap Answer, leave the field empty, then type "PN-4471." and send.
Expected: Send answer is disabled while the field is empty; after sending, a message says the answer was
recorded, the status reads Awaiting review, and the conversation shows your answer as "You".

7. Put the device in airplane mode and open a request that was already listed.
Expected: the request you already read stays on screen under a "couldn't refresh" notice, and no answer
can be sent — a refusal is reported and nothing is presented as sent.

8. Repeat steps 1 and 5 with the application language set to French.
Expected: the title, the section label, the Job action and the unavailable state are all in French, and
the request is otherwise unchanged.
```

#### Hygiene

The task ran six Gradle invocations — three Tier-A runs (the first two failed on syntax errors in this
task's own new file), the full JVM suite with the device-test APK assembly, that class on its own, and a
final Tier-A re-run after a French string was corrected — which started the Gradle and Kotlin build
daemons; `make android-stop` was run after the last Gradle command and both are gone (`pgrep` finds
none). The build and test logs this task wrote under `/tmp` were removed. No `adb` and no device or
emulator command was run (`qa.md` §7.3). The foundation stack (`make up`) was not touched, no database or
storage volume was changed, and no file was opened in the product owner's editor.

## Not in this list — recorded so they are not lost




- **Job Details shows nothing about a pending proposal.** The report notes that opening the Job from the
  request card shows no details of the suggestion. `GET /jobs/:id` does not carry follow-up requests today,
  so a manager has to go back to Schedule to see the proposal they were reading. This belongs to item 5 once
  decisions 5.3.1–5.3.2 are made (the same projection addition), and is recorded here rather than as a
  sixth item.
- **`BR-FV-011` direct scheduling by a technician** remains open in tracker 050: the action is drawn on
  `visits.create_schedule` alone, but the crew picker reads the organization's technicians, so a technician
  without `technician.view` has no crew to state.
- **Tracker 050's own remaining items** (repository-wide API typecheck; technician status feedback) are not
  duplicated here; this list points at them.

## Verification
### 5.13 What has landed — recognizable technician requests (2026-09-29)

The projection and authorization question in section 5.3.1 is decided for the technician's own Requests
view. A request now carries the Job number and title, Customer name, preserved service address, and source
Visit context. The permission that already authorizes the request row authorizes this limited operational
identity; customers.view is not required, and no customer contacts, notes or billing information are
added.

My Schedule now presents each request in this order:

- Customer and Job identity, followed by the service address when one was preserved.
- Request status and the complete proposed calendar date and time.
- The reason for another visit and the same-technician preference.
- The office response when applicable, with the full clarification conversation on request details.

The details destination presents those values as labeled fields and keeps View job details as a separate
action. Source Visit date is shown when the request names a source Visit. The API reads identity for a list
in one batch rather than issuing one Job query per request.

Verification for this addition is recorded with the implementation handoff; Android device QA remains the
product owner's pass.


No item is Done. Verification for each item is recorded here when that item is implemented, at the tier
`qa.md` §3.1 requires (Tier A for a task inside the feature, Tier B when an item completes the feature).
The plan at the time of opening:

| Item | Verification expected |
| ---- | --------------------- |
| 1 | Android compile + the JVM tests covering the field home's state + the device tests for the re-entry read and the notice (Tier A) — **done: see §1.6, §1.7 and §1.8**; device QA by the product owner still outstanding (§1.9) |
| 2 | Android JVM/Compose tests for the completion sheet; API unchanged unless a decision changes it |
| 3 | Android compile + the JVM tests of the jobs and schedule packages + the device-test source compile (Tier A) — **done: see §3.4**; the keyboard behaviour itself is only observable on a device, so that check is the product owner's (§3.5) |
| 4 | Android Compose tests and, if the API projection changes, API e2e |
| 5 | API e2e for any projection/authorization change; Android JVM/Compose tests for the card and the technician's own read — **done for everything that has landed: §5.6 (Tier A — 64 tests, 0 failures) covers the two defects, §5.9 (Tier A — 73 tests, 0 failures) covers the technician's own read, and §5.11 (Tier A — API 10 unit + 8 e2e, Android 83 JVM, all 0 failures) covers the clarification conversation and the answer)**, with the device tests the product owner runs |

## Hygiene

**Opening the list** created **no** code change: the only file that task created is this tracker (plus its
reference in `README.md`). No Gradle command was run to open it, so no build daemon was started and
`make android-stop` was not needed; the foundation stack (`make up`) was not touched, no database or storage
volume was changed, and no file was opened in the product owner's editor.

**Item 1's task** recorded its own hygiene in §1.9. **Item 3's task** ran the Android verification in §3.4
(`compileDebugKotlin`, `compileDebugAndroidTestKotlin` and the JVM tests of the two touched packages), which
started the Gradle and Kotlin build daemons; they were stopped with `make android-stop` after the last Gradle
command, and the build and test logs that task wrote under `/tmp` were removed. No `adb` and no device or
emulator command was run (`qa.md` §7.3). The foundation stack (`make up`) was not touched, no database or
storage volume was changed, and no file was opened in the product owner's editor.

**Item 5's task** ran the Android verification in §5.6 (`compileDebugKotlin`, `compileDebugAndroidTestKotlin`
and the JVM tests of the `ui.schedule` package) in one Gradle invocation, which started the Gradle and Kotlin
build daemons; `make android-stop` was run after it and both are gone (`pgrep` finds none), and the build and
test log that task wrote under `/tmp` was removed. No `adb` and no device or emulator command was run
(`qa.md` §7.3). The foundation stack (`make up`) was not touched, no database or storage volume was changed,
and no file was opened in the product owner's editor.

**The §5.8 task** (the technician's own requests) ran the Android verification in §5.9 — one
`compileDebugKotlin compileDebugAndroidTestKotlin testDebugUnitTest --tests …` invocation, repeated once
after two compile errors in that task's own new test code were corrected — which started the Gradle and
Kotlin build daemons; `make android-stop` was run after the last Gradle command and both are gone (`ps`
finds neither), and the logs that task wrote under `/tmp` were removed. No `adb` and no device or emulator
command was run (`qa.md` §7.3). The foundation stack (`make up`) was not touched, no database or storage
volume was changed, and no file was opened in the product owner's editor.
