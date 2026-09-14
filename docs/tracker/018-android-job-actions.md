# Tracker 018 — Job & Visit management actions (API + Android) and the demo-data seed

**Status: COMPLETE for API and Android implementation; physical-device QA is the product owner's**

**Continued by `docs/tracker/019-android-job-details-hierarchy.md`**, which moves these actions from the
screen's global action row to the record each one affects, replaces **Reassign** with
**Manage technicians**, and replaces the standing action banner with a transient Snackbar. The actions
themselves and the API contract are unchanged. The manual QA below therefore describes the row as it
was built **by this slice**; run
`docs/tracker/019-android-job-details-hierarchy.md` for the screen as it now reads.

Date: 2026-09-14
Predecessor: `docs/tracker/017-android-job-details.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-008`, `BR-010`, `BR-012`, `BR-020`, `BR-024`,
`BR-028`, `BR-041`, `BR-042`, `BR-053`, `BR-058`, `BR-060`, `BR-061`, `BR-062`, `BR-063`, `BR-064`,
`BR-066`, `BR-067`, `BR-068`, `BR-069`, `BR-070`, `BR-072`, `BR-073`, `BR-079`, `BR-080`, `BR-086`
Domain model: `docs/domain/job-visit-domain-model.md` §6 – §7
API contract: `docs/api/job-actions.md` (new), `docs/api/job-details.md` (extended)
Design reference: `Figma/src/screens/JobDetails.tsx` (Manager view action row)
Design system: `docs/design/android-design-system.md`

## Scope

The Job Details screen from tracker 017 presented a Job but offered no way to act on it, on the
recorded grounds that no capability authorized a Job or Visit action. This slice lands the capabilities
that already existed, together with the actions `BR-066` names, and makes the read say what is possible.

| Item                                                                        | Status      |
| --------------------------------------------------------------------------- | ----------- |
| `PATCH /jobs/:id/status` — `BR-058` transitions, `BR-061` gate, history row  | Implemented |
| `PATCH /jobs/:id/visits/:visitId/schedule` — `BR-073`, schedule history      | Implemented |
| `PUT /jobs/:id/visits/:visitId/technicians` — whole crew, `BR-069` history   | Implemented |
| `GET /technicians` — the crew a Job may be assigned                          | Implemented |
| `BR-070` conflicts reported before a change is applied, and confirmed         | Implemented |
| `BR-086` version checks on Job and Visit mutations                            | Implemented |
| Read carries `allowedStatusTransitions`, `version`, `reschedulable`           | Implemented |
| Action read of the API contract (`docs/api/job-actions.md`)                   | Implemented |
| Android action row: Assign/Reassign, Reschedule, Status                       | Implemented — **reorganized by `docs/tracker/019-android-job-details-hierarchy.md`** |
| Android status menu built from the backend's permitted transitions             | Implemented |
| Android reschedule dialog (date, start time, length)                          | Implemented |
| Android assign sheet (whole crew, one Lead, chosen explicitly)                 | Implemented |
| Android conflict confirmation (`BR-070`)                                      | Implemented |
| **Address is tappable** and opens the device's preferred map application       | Implemented |
| **Customer is tappable** and opens that customer's detail                      | Implemented |
| Offline queueing of these actions                                              | **Not implemented — online-only, see below** |
| Job cancellation                                                              | **Not implemented — blocked by `BR-064`** |
| Job Activity timeline (`BR-080`)                                              | **Not implemented — unchanged from 017** |
| Job deletion (`BR-021`)                                                       | **Not implemented — unchanged from 017** |

## The three design items this slice closes

The product owner's review of the Job Details screen asked for three things. Two were presentation, one
was a capability question:

1. **The address was dead text.** `BR-038` (location) is an open question, but *handing an address to the
device* is not a location feature: the `geo:` URI is the platform's own way of describing a place and
the system resolves it to whichever application the user chose. Servora names no provider and models no
navigation, so nothing was invented. The row is only a control when the Job actually has an address
(`BR-056`).
2. **The customer was dead text.** The Job's Customer is an organization customer the customer list
already opens, so the row now pushes the existing Customer Details destination (`BR-048`).
3. **Reassign, Reschedule and the status dropdown were missing.** They were missing because no
capability authorized them. `BR-008` already names Job update as a Manager default and the foundation
catalogue already carries `JOB_UPDATE`, so the actions are guarded by the capability that exists rather
than by an invented `jobs.*` set. What the screen may *offer* is still the backend's answer: the status
menu is drawn from the Job's `allowedStatusTransitions` and Reschedule from the Visit's
`reschedulable`.

## What the API refuses, and why that is the correct implementation

| Refusal                          | Rule       | Why it is not implemented as an assumption                    |
| -------------------------------- | ---------- | -------------------------------------------------------------- |
| Cancelling a Job                 | `BR-064`   | A **structured** reason is required and its catalogue is undefined, so the API refuses rather than inventing one (`BR-042`). Cancellation is therefore absent from the client's status menu too. |
| `PENDING_REVIEW` without `BR-061` | `BR-061`  | A Visit still active, or a latest outcome other than `RESOLVED`, keeps the Job in `IN_PROGRESS`. |
| `NEEDS_QUOTE_APPROVAL` as resolvable | `BR-061` | Its Job-status effect is an open question, so it is not treated as resolvable. |
| Rescheduling anything but `SCHEDULED` | `BR-073` | The Visit lifecycle is the API's, not the client's.            |
| A crew without exactly one Lead   | `BR-068`   | A Visit with technicians has exactly one Lead, so the API never has to choose one. |

## The development seed now exercises the assignment model

The demo data assigned one technician to every Visit, so the screen's central subject — a Visit's crew
— was invisible in development. `make seed` now also:

- creates four further Technician members (`Sarah Moreau`, `John Tremblay`, `Priya Raman`,
  `Luc Gagnon`). They are members only: the two documented QA credentials stay the only seeded logins, so
  no new password is printed;
- assigns every seeded Visit **two or three** technicians with exactly one Lead, rotating the Lead so
  different technicians lead different Visits;
- records the assignment **history** `BR-069` describes — the Lead was first assigned as an ordinary
  technician and promoted later, and some Visits carry a technician who was removed again — as history
  rows, never as a rewritten assignment;
- writes three notes per Visit, from both the manager and the crew (`BR-027`);
- writes the Visit and Job **status history** along the only paths `BR-074` and `BR-058` permit, so the
  histories a future Activity read will project (`BR-080`) are real histories.

## Verification

```text
API
- Unit (Vitest): PASS — 363 tests, including the lifecycle tables, the assignment planner and the
  action request parsers
- API e2e (Vitest + PostgreSQL): PASS — 211 tests, including 20 new job-action tests
- Typecheck (tsc --noEmit): PASS
- Lint (oxlint): PASS
- Format (prettier): PASS
- Seed (npm run db:seed): PASS — idempotent across consecutive runs

Android
- JVM unit tests: PASS — JobDetailsRepository (action requests, conflict bodies, error codes, session
  renewal) and JobDetailsViewModel (action submission, conflict confirmation, refusals)
- Lint (lintDebug): PASS
- Debug build (assembleDebug): PASS
- Device-test sources (assembleDebugAndroidTest): PASS, because Compose tests need a device
- Physical device: AWAITING PRODUCT OWNER
```

## Manual QA (product owner, physical device)

```text
Android QA — Job Details actions

Sign in as the Manager development account (`make seed`, API running).

1. Open Home and tap a Job that has a crew. Read the Technicians section.
   Expected: two or three technicians are listed, exactly one carries the "Lead" badge.

2. Tap the Job's address row.
   Expected: the device opens the location in whichever map application is its default (or offers the
   choice when none is set).

3. Tap the Customer row.
   Expected: the customer's detail screen opens.

4. Tap Change status (beside the Job's status chip) on a Job in Pending review.
   Expected: the menu offers only the transitions the API permits ("Completed"); no "Canceled" entry.
   Choose it. Expected: the pill changes to Completed and "Job status updated successfully" appears
   briefly.

5. With the Job Completed, tap Change status again and choose New.
   Expected: the Job returns to New (reopening), never to In progress.

6. Open a Job whose Visit is Scheduled and tap Reschedule at the foot of the Visit card. Change the
   date, the start time and the length, then confirm.
   Expected: the schedule row shows the new date and time, and "Visit rescheduled successfully" appears
   briefly.

7. Open a Job whose Visit is En route and tap Reschedule.
   Expected: the action is disabled (`BR-073` allows rescheduling only a Scheduled visit).

8. Tap Manage technicians at the foot of the Technicians card on a Job with a crew. Untick the Lead,
   tick another technician, tap "Make lead" on them and save.
   Expected: the crew updates with the new Lead first, and "Visit crew updated successfully" appears
   briefly.

9. Manage technicians so that a technician who is already booked on another Visit over the same window
   is added.
   Expected: a dialog names the technician, the other job and the overlapping time, and offers
   "Schedule anyway"; confirming applies the change.

10. Turn airplane mode on, then tap Change status and choose a transition.
    Expected: "Check your connection and try again." and the Job stays as it was.
```

Expected limitations
- The actions are online-only (no offline queue yet): see the open questions.
- No Job cancellation, no Job Activity timeline, no Job deletion.

## Open questions recorded by this slice

| # | Question | Rule | How it is handled now | Blocks |
| - | -------- | ---- | --------------------- | ------ |
| 1 | The `jobs.*` capability set, and what each Visit action should require | `BR-006`, `BR-008` | Every action requires the existing `JOB_UPDATE`; `GET /technicians` requires `TECHNICIAN_VIEW` | Per-action authorization, and re-guarding the read |
| 2 | The Job cancellation reason catalogue | `BR-064` | Cancellation is refused (`409 JOB_CANCELLATION_UNAVAILABLE`) and is absent from the client's status menu | Cancelling a Job from any client |
| 3 | The Job-status effect of `NEEDS_QUOTE_APPROVAL` | `BR-061`, `BR-078` | Not treated as resolvable, so `PENDING_REVIEW` is refused for it | Completing a Job whose last visit needed a quote |
| 4 | Offline behaviour of these actions | `BR-031`, `BR-032`, `BR-086` | Online-only: the screen holds no offline working set yet, so an action is never queued | Field work with no connectivity |
| 5 | Job Activity's event vocabulary | `BR-080`, `BR-041` | The history the actions record is real, but no Activity read is exposed yet | The Job Activity timeline in both clients |
| 6 | Job deletion behaviour | `BR-021` | Not implemented; no delete capability is implied | Deleting a Job, and what happens to its history |
| 7 | Technician profile, skills, territory, availability | `BR-024` | `GET /technicians` returns a membership id and a name and nothing else | Eligibility-aware dispatch |
