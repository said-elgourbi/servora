# Tracker 034 — The manager's Job status workflow: any permitted destination, one operation, one record

**Status: COMPLETE for the API and Android implementation; physical-device QA is the product owner's**

**Changes business rules.** This slice amends `BR-058` (permitted destinations), `BR-062` (closing and
the completion invariant), `BR-061` (entry into `PENDING_REVIEW` remains runtime eligibility) and
`BR-063`/`BR-064` (reopen is the only route to `NEW`; cancellation is not folded into the selector).
The change is recorded as a **changed decision** under `BR-058`, as `BR-040` requires.

Date: 2026-09-16
Predecessors: `docs/tracker/018-android-job-actions.md` (the status route and the status control),
`docs/tracker/021-android-job-details-status-polish.md` (the control's presentation)
Business rules: `BR-004`, `BR-006`, `BR-007`, `BR-028`, `BR-033`, `BR-040`, `BR-041`, `BR-042`, `BR-058`,
`BR-059`, `BR-061`, `BR-062`, `BR-063`, `BR-064`, `BR-066`, `BR-067`, `BR-074`, `BR-079`, `BR-083`,
`BR-086`
Domain model: `docs/domain/job-visit-domain-model.md` §8.1 – §8.6, §9.1, §21
API contract: `docs/api/job-actions.md` §3, `docs/api/job-details.md` §3.2 — **changed by this slice**
Design system: `docs/design/android-design-system.md` (status control)

## Scope

The Manager Job status workflow was a ladder: the API reported only the *next* status, so moving a Job
from `NEW` to `COMPLETED` meant four requests, four history rows, and a manager who had to guess which
steps were legitimate. This slice makes a Job's status a **destination the authorized user selects**.

| Item                                                                                      | Status      |
| ----------------------------------------------------------------------------------------- | ----------- |
| An open Job may move to **any other open status, forwards or backwards**, or `COMPLETED`   | Implemented |
| `NEW` is not a destination for an open Job — it is reached by reopening a terminal Job       | Implemented |
| `COMPLETED` and `CANCELED` stay terminal; their only destination is `NEW` (the reopen)       | Implemented |
| `PENDING_REVIEW` stays a **structurally permitted** destination from any open status          | Implemented |
| `BR-061` remains the runtime eligibility condition for entering `PENDING_REVIEW`              | Unchanged   |
| A Job **must not** be completed while it has an open Visit (`BR-062` invariant)               | Implemented |
| One destination = one request = one `job_status_history` row                                  | Implemented |
| The Job moving never writes a Visit status or Visit history (`BR-059`, `BR-067`)              | Unchanged — asserted |
| Closing and reopening are confirmed by the user before the one request is sent                | Implemented |
| `COMPLETED` stays in the reported destination list (eligibility is not hidden client-side)    | Implemented |
| A refused completion is reported with its own code and its own copy                           | Implemented |
| Capabilities, the Visit lifecycle and every other action                                      | Unchanged   |
| EN/FR copy for the new confirmation and refusal                                               | Implemented |

## Decisions taken here

1. **A status is a destination, not a step.** The ladder forced a manager to walk a Job through statuses
   they did not want it in, and left them no way back when the field work changed. An open Job now moves
   directly to any other open status — backwards included — or to `COMPLETED`, in one operation. Nothing
   about the *set* of states changed; what changed is that the authorized user chooses where the Job goes
   rather than the API choosing the next rung.
2. **`NEW` is not one of those destinations.** `NEW` is the created/reopened, not-yet-scheduled state
   (`BR-063`), so an open Job never returns to it. The operational case it used to be reached for — a Job
   whose Visit has gone away and which therefore needs scheduling again — is already covered by the
   derived "Needs Scheduling / No Active Visit" signal, which applies to a `NEW` **or** `IN_PROGRESS` Job
   with no active Visit (`BR-060`). `NEW` is reached exactly one way: reopening a terminal Job.
3. **The transition table is structural; eligibility belongs to the rule that owns it.** `BR-061` keeps
   deciding whether a Job may *enter* `PENDING_REVIEW`, and the `BR-062` invariant decides whether it may
   be *closed*. Both are evaluated when the change is applied and reported with their own outcomes, and
   neither removes a destination from `allowedStatusTransitions`. A client therefore offers what `BR-058`
   permits and presents the API's answer, which is what `BR-041` asks for: one definition, in one place.
   `NEW` → `PENDING_REVIEW` while a Visit is still active is the working example
   (`JOB_REVIEW_CONDITION_NOT_MET`).
4. **The completion invariant is a rule, not a warning.** Completing a Job whose Visit is still on site
   would close the Job while the field work continues, and the two are separate state machines
   (`BR-059`), so the Visit would not be touched — leaving a closed Job with open work and no signal
   anywhere. `BR-062` already stated the governing condition ("no remaining work requires another
   Visit"); this slice makes it checkable: **no Visit that is not historical**. The confirmation dialog
   the user answers is a presentation of the decision and never overrides the invariant.
5. **"Open Visit" reuses the classification the rules already define.** A Visit is open while its status
   is anything other than `COMPLETED`, `CANCELED` or `NO_SHOW` — `BR-074` calls exactly these three
   historical, and `BR-083` classifies an active Visit by the same set. No parallel vocabulary was
   invented. It is deliberately **not** `ACTIVE_VISIT_STATUSES` (`BR-060`): a `DRAFT` Visit is not
   scheduled work for the "Needs Scheduling" signal, but it is a field attempt that has not happened, so
   it is remaining work and blocks completion. The two named sets answer different questions and both
   stay (`BR-060`, `BR-083` Notes).
6. **The invariant is enforced inside the write, server-side.** Both eligibility conditions are evaluated
   through the transaction that applies the status change rather than before it, so the Job is never moved
   on a picture of its Visits that has already changed. The check is *not* mirrored in Android: hiding
   `COMPLETED` from a client that guessed the Visit state would put lifecycle logic in a client and make
   the refusal invisible where it matters.
7. **Closing and reopening are confirmed; nothing else is.** A confirmation on every destination would
   make the ordinary correction slower to make, which is the opposite of the point. The two that change
   what the record *means* — closing a Job (its Visit outcomes become final, `BR-079`) and reopening a
   closed one (it returns to the scheduling workflow, `BR-063`) — are put to the user in a dialog naming
   the Job. Cancellation stays out of the selector entirely: `BR-064` requires a structured reason whose
   catalogue is an **OPEN QUESTION**, so `CANCELED` is not reported as a destination and no client draws
   it (`BR-042`).
8. **One destination is one business operation.** The client sends a single `PATCH` with the status the
   user chose and never walks the lifecycle, so `SCHEDULED` → `COMPLETED` is one request and one history
   row. The API enforces the shape rather than trusting the client to: the route either applies the
   destination it was given or refuses it, and never applies an intermediate status on the way.

## What the API now does

`PATCH /jobs/:id/status` is unchanged in shape — the same request body, the same single
`job_status_history` row, the same Job projection in answer. What changed is the set of destinations it
accepts and the conditions it applies:

| Destination      | Structural | Runtime eligibility                                                              | Refusal                        |
| ---------------- | ---------- | -------------------------------------------------------------------------------- | ------------------------------ |
| another open status | permitted | none                                                                             | —                              |
| `COMPLETED`      | permitted  | `BR-062`: no open Visit — nothing other than `COMPLETED`, `CANCELED`, `NO_SHOW`    | `JOB_COMPLETION_BLOCKED`       |
| `PENDING_REVIEW` | permitted  | `BR-061`: no active Visit and the latest completed Visit resolved the Job          | `JOB_REVIEW_CONDITION_NOT_MET` |
| `CANCELED`       | permitted  | `BR-064`: the structured reason catalogue is undefined, so it is not applied       | `JOB_CANCELLATION_UNAVAILABLE` |
| `NEW` from a terminal Job | permitted | none                                                                    | —                              |
| anything else    | refused    | —                                                                                 | `JOB_STATUS_TRANSITION_NOT_ALLOWED` |

`allowedStatusTransitions` reports the structural column only. `JOB_COMPLETION_BLOCKED` is a new stable
code in the existing envelope, with no `details`: unlike `JOB_REVIEW_CONDITION_NOT_MET` there is exactly
one cause, so a single-valued `reason` would be invented surface.

## Android

- The status control's menu is unchanged in construction — it renders exactly the destinations the API
  reported, and now draws the full set. Choosing a destination still sends **one** action.
- A consequential destination is confirmed first by `JobStatusConfirmDialog`, whose state lives with the
  control (`JobDetailsActions.kt`) and whose copy is `job_status_confirm_close_*` /
  `job_status_confirm_reopen_*`. Dismissing sends nothing.
- `JobActionFailure.JOB_COMPLETION_BLOCKED` is classified from the API's code and reported through the
  existing durable refusal snackbar with `job_action_error_completion_blocked`. `COMPLETED` is **not**
  hidden when a Visit is open: the client does not infer Visit state, and the refusal is the answer.

## Manual QA (product owner, physical device)

```text
Android QA — Manager Job status workflow

1. Sign in as Manager and open a Job in Scheduled with a Visit that is Scheduled.
2. Tap the Job status chip.
   Expected: the menu states "Scheduled" at its head and offers "In progress", "Pending review" and
   "Completed" — three destinations, not one. There is no "New" entry and no "Canceled" entry.
3. Choose "Pending review".
   Expected: the menu closes and the Job status is refused with "The job cannot await review yet: work
   is still open or the last visit did not resolve it." The chip still says Scheduled — nothing moved.
4. Choose "In progress".
   Expected: one change is sent; the chip becomes "In progress" and "Job status updated successfully"
   appears briefly. Open the Activity timeline: exactly one "Updated job status..." entry was added.
5. Reopen the menu and choose "Scheduled" (backwards).
   Expected: the chip returns to "Scheduled" in one step, and one more Activity entry is added —
   never a sequence of intermediate statuses.
6. Reopen the menu and choose "Completed".
   Expected: the dialog "Close this job?" appears naming the Job. Cancel it.
   Expected: nothing was sent and the chip still says Scheduled.
7. Tap the chip again, choose "Completed", and confirm with "Confirm".
   Expected: the change is refused with "This job can't be completed while it has active visits." and
   the Job is still Scheduled.
8. Close the Job's Visit through the field workflow (or use a seeded Job whose Visits are all completed
   or canceled), then choose "Completed" and confirm.
   Expected: the chip becomes "Completed" and the menu now offers only "New".
9. Choose "New" and confirm the reopen dialog.
   Expected: the Job returns to New and never to In progress.
10. Open a Job with no Visit and choose "Completed", confirming the dialog.
    Expected: the Job is closed (administrative closure is allowed).
11. Sign in as a user who may not update Jobs and tap the status chip.
    Expected: the status is presented and does not act; no menu and no dialog open.
12. Switch the app language to French.
    Expected: the menu entries, "Statut actuel", "Terminer cette intervention ?", "Rouvrir cette
    intervention ?", "Confirmer", "Annuler" and "Cette intervention ne peut pas être terminée tant
    qu'elle a des visites en cours." are French.
13. Switch the device to dark mode.
    Expected: the chip, the dots, the menu and the confirmation dialog follow the dark scheme.
```

Expected limitations
- Job cancellation is still unavailable: `BR-064`'s reason catalogue is undefined, so `CANCELED` is not
  a destination the read reports and no client offers it.
- Whether an offered destination is *currently* eligible is the API's answer, so a refusal is a normal
  outcome of step 3 and step 7 — the client does not predict it.
- The status actions remain online-only, unchanged from trackers 018 – 021.
- The Job Details screen does not re-read the Job when a status change is refused; the refusal is
  reported as it always was, and the open Visit that caused it is visible in the Activity timeline
  (its status events are already there).

## Recorded rather than decided

| # | Question                                                                          | Rule                | How it is handled now                                                                                                                                                            | Blocks                                                 |
| - | --------------------------------------------------------------------------------- | ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| 1 | Should the status selector collect the optional reopen reason?                     | `BR-063`            | Reopening sends no note, exactly as before this slice; the field exists on the route and in history                                                                                | Recording why a Job was reopened                       |
| 2 | Does a `DRAFT` Visit block entry into `PENDING_REVIEW`?                            | `BR-061`            | Unchanged: `BR-061` is applied with `ACTIVE_VISIT_STATUSES` and does not classify `DRAFT`. Completion is decided and **does** block on `DRAFT` (`BR-062`)                            | Automatic `PENDING_REVIEW` entry with draft Visits     |
| 3 | The concurrent-Visit boundary of the completion invariant                          | `BR-062`, `BR-086`  | The check runs in the same transaction as the write, under PostgreSQL's default read-committed isolation. No route creates a Visit yet, so no concurrent insert exists to race it; making it airtight would need a row lock or a constraint | Nothing today; a Visit-creation route would revisit it |
| 4 | The `jobs.*` capability set                                                        | `BR-006`, `BR-008`  | Unchanged: every status change still requires the existing `JOB_UPDATE` capability. Widening the *destination set* grants nothing new — it applies to whoever already held the capability, including custom roles and direct member permissions (`BR-004`) | Per-action authorization for Jobs                      |

## Files changed

```text
.clinerules/Business Rules.md                                             BR-058, BR-061, BR-062, BR-063, BR-064
api/src/jobs/job.types.ts                                                 destinations, terminal-Visit vocabulary
api/src/jobs/jobs.service.ts                                              eligibility in the transaction, the completion invariant
api/src/jobs/jobs.controller.ts                                           JOB_COMPLETION_BLOCKED
api/src/jobs/job-details.dto.ts                                           the reported list's meaning
api/src/jobs/job.types.spec.ts                                            the vocabulary
api/src/jobs/job-details.dto.spec.ts                                      the reported list
api/test/job-actions.e2e-spec.ts                                          direct moves, backwards moves, the invariant, refusals
android/.../data/jobs/JobDetailsResult.kt                                 JOB_COMPLETION_BLOCKED
android/.../data/jobs/JobDetailsRepository.kt                             the code's classification
android/.../ui/jobs/JobDetailsActions.kt                                  the confirmation, the refusal's copy
android/.../ui/jobs/JobDetailsScreen.kt                                   the control's Job number
android/app/src/main/res/values/strings.xml                               confirmation and refusal copy
android/app/src/main/res/values-fr/strings.xml                            the same in French
android/app/src/androidTest/.../JobDetailsScreenTest.kt                   menu, confirmation and refusal coverage
android/app/src/test/.../JobDetailsRepositoryTest.kt                      the new code's classification
docs/domain/job-visit-domain-model.md                                     §8.2, §8.4, §8.5, §8.6, §21
docs/api/job-actions.md                                                   §1, §3.2, §3.3, §7, §8
docs/api/job-details.md                                                   allowedStatusTransitions
docs/design/android-design-system.md                                      the status control
docs/tracker/034-manager-job-status-workflow.md                           this slice
README.md                                                                 the tracker index
```
