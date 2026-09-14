# Tracker 017 — Job Details (Android) and the Job read behind it

**Status: COMPLETE for API and Android implementation; physical-device QA is the product owner's**

**Continued by `docs/tracker/018-android-job-actions.md`**, which lands the management actions this
slice could not (there was no capability to authorize them), makes the address and the customer
tappable, and gives the development seed a crew to present.

Date: 2026-09-14
Predecessor: `docs/tracker/016-android-manager-home.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-010`, `BR-012`, `BR-020`, `BR-021`, `BR-023`,
`BR-028`, `BR-041`, `BR-042`, `BR-047`, `BR-048`, `BR-051`, `BR-052`, `BR-056`, `BR-057`, `BR-058`,
`BR-059`, `BR-062`, `BR-066`, `BR-067`, `BR-068`, `BR-072`, `BR-074`, `BR-080`, `BR-081`
Domain model: `docs/domain/job-visit-domain-model.md` §5 – §7
API contract: `docs/api/job-details.md`
Design reference: `Figma/src/screens/JobDetails.tsx` (Manager view), `servora-job-details-spec.md`
Design system: `docs/design/android-design-system.md`

## Scope

The Manager Job Details screen. The reference design was written as though a Job carried **one**
technician; Servora does not model that, so the design was adapted to the assignment model rather
than the data model being adapted to the design.

| Item                                                                 | Status      |
| -------------------------------------------------------------------- | ----------- |
| `GET /jobs/:id` — one Job with the Visit that represents it          | Implemented |
| Authorization: `customers.view`, the existing Job/Visit capability    | Implemented |
| Represented Visit: the same `BR-081` selection the customer Job row uses | Implemented |
| **Technicians**: every technician assigned to that Visit, Lead first  | Implemented |
| Lead badge for the Visit's Lead, no badge for the others              | Implemented |
| "Unassigned" state when the Visit has no technicians                  | Implemented |
| Job status (`BR-058`) and represented Visit status (`BR-074`) shown separately | Implemented |
| Schedule, address and Customer rows                                    | Implemented |
| Reachable from the manager Home (attention cards and schedule rows)    | Implemented |
| Loading / failure / retry states                                       | Implemented |
| EN/FR copy for every new string                                        | Implemented |
| API unit spec and API e2e (HTTP + assignment derivation)               | Implemented |
| Android JVM tests (repository, ViewModel) and a Compose test of the section | Implemented |
| **Job Activity timeline** (`BR-080`)                                   | **Not implemented — see below** |
| **Management actions** (status change, assign, cancel, close)          | **Implemented by tracker 018** (`docs/tracker/018-android-job-actions.md`) |
| **Extra information / notes** (`BR-027`)                               | **Not implemented — no Job source** |
| **Tap the address to open a navigation app**                           | **Implemented by tracker 018** |
| **Customer-detail Job rows → Job Details**                             | **Not implemented — out of slice** |

## The assignment model this screen had to fit

`BR-068` records technician assignment on the **Visit**, never on the Job: `visit_technicians`
(migration `0003`) carries `(visit_id, technician_membership_id, role_code)` with
`role_code in ('LEAD','TECHNICIAN')` and a partial unique index allowing one `LEAD` per Visit. There is
no `jobs.technician_id` column and none was added. The screen therefore shows the crew of the **Visit
it represents**, and a Job with several Visits shows that one Visit's crew rather than every
technician who has ever worked the Job.

## Design adaptations (the mismatches this slice found)

| Design reference                                    | Servora's model                                                                                                     | What this slice did                                                                                   |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| A singular "Technician" section on the Job          | Technicians are assigned to a Visit, and a Visit may carry 0, 1 or many (`BR-068`)                                   | Renamed to **Technicians** and rendered as a list of every assignment on the represented Visit            |
| "Crew · N" with "Lead"/"Asst" badges                | The role codes are `LEAD` and `TECHNICIAN`; Servora does not use the term "Helper" (`BR-068`)                        | Section is "Technicians"; only the Lead is badged, because `TECHNICIAN` is the ordinary role              |
| `job.techs` on the Job model                        | A Job's technicians are derived from its Visits; the assignment itself lives on the Visit                            | The screen reads the represented Visit's assignments; nothing is duplicated onto the Job                  |
| One status (`scheduled`/`en-route`/`late`/`unassigned`) | Job status (`BR-058`) and Visit status (`BR-074`) are two separate state machines (`BR-059`)                        | The Job's status is shown in the header and the represented Visit's status with the schedule              |
| "Unassigned" as a status                            | An unassigned Visit is an absence of assignment, not a state either machine has (`BR-042`)                          | "Unassigned" is presented inside the Technicians section, never as a Job or Visit status                  |
| Management actions in the header                    | No `jobs.*` capability exists in the catalogue (`BR-006`, `BR-008`)                                                  | **No action is drawn**; recorded as the blocking open question below                                      |
| "Extra Information" / notes block                   | A Job has a `description` but no notes field; notes belong to Visits (`visit_notes`)                                 | Only the Job's description is presented; a notes block is not invented                                    |

## Decisions taken here

1. **The represented Visit is `BR-081`'s selected Visit.** `jobs/visit-assignment.ts` now defines the
   selection and the technician read **once**, and both the customer-detail Job row and the Job
   Details read call it. The two screens cannot disagree about which Visit represents a Job or who is
   assigned to it (`BR-041`).
2. **The read is guarded by `customers.view`.** `GET /customers/:id/jobs` and `GET /home/manager`
   already expose the same records under it, and inventing `jobs.view` would define a capability the
   Jobs feature has not (`BR-006`, `BR-042`). Recorded in `docs/api/job-details.md` §2.
3. **A Job whose Customer has been deleted is `404`.** `BR-023` hides a deleted customer's Jobs by
   default, so the read does not disclose one.
4. **The manager Home's attention cards and schedule rows now open the Job** (`BR-012`). Tracker 016
   recorded this as the product-intended target and deferred it until the destination existed; the
   destination exists now, so the deferral is resolved. `ManagerHomeScreen`'s callback is
   `onOpenJob(jobId)` instead of `onOpenCustomer(customerId)`.
5. **No client-side derivation of the crew.** The API orders assignments Lead first and the client
   preserves that order; a role code the client does not know fails the read rather than being shown,
   because the screen would otherwise have to guess whether that technician is the Lead (`BR-042`).
6. **Shared UI primitives were extracted rather than copied**: the Job status pill and the Job/Visit
   status labels (`ui/components/ServoraStatusPill.kt`), the initials avatar rule
   (`ui/components/ServoraAvatar.kt`) and the preserved-address line
   (`ui/components/ServoraAddress.kt`). The Visit status label resources moved from
   `home_visit_status_*` to `visit_status_*`, because the vocabulary is the Visit lifecycle's, not the
   Home's (`BR-041`).
7. **Documentation corrected.** `README.md` and `docs/domain/job-visit-domain-model.md` still said the
   Job and Visit tables were "designed but not migrated"; migration `0003` created them, so both now
   say so.

## Verification

```text
API
- Unit (Vitest): PASS — 342 tests, including 4 new job-details projection tests
- API e2e (Vitest + PostgreSQL): PASS — 191 tests, including 11 new `GET /jobs/:id` tests
- Typecheck (tsc --noEmit): PASS
- Lint (oxlint): PASS
- Format (prettier): PASS on every file this slice touched

Android
- JVM unit tests: PASS — 11 JobDetailsRepository tests + 6 JobDetailsViewModel tests
- Lint (lintDebug): PASS
- Debug build (assembleDebug): PASS
- Device-test sources (assembleDebugAndroidTest): PASS, because Compose tests need a device
- Physical device: AWAITING PRODUCT OWNER
```

## Manual QA (product owner, physical device)

```text
Android QA — Job Details

Sign in as the Manager development account, with the API running and seeded.

1. Open Home and tap a card under "Needs attention".
   Expected: the Job Details screen opens with the Job's status, number and title.

2. Read the Technicians section for a Visit that has several technicians.
   Expected: every assigned technician is listed with their initials avatar and full name, and only
   the Lead carries a "Lead" badge.

3. Open a Job whose represented Visit has no technicians.
   Expected: the Technicians section shows "Unassigned" with "No technician is assigned to this visit
   yet." and no rows.

4. Compare the Technicians section with the same Job on the customer's detail screen (Customers → the
   customer → Recent jobs).
   Expected: the same names in the same order, because both read the same represented Visit.

5. Open a Job with no Visit.
   Expected: the schedule row says "Not scheduled" and the Technicians section shows "Unassigned".

6. Open a Job with an upcoming Visit and one whose Visits are all in the past.
   Expected: the schedule row shows the upcoming Visit's date and time; for the past-only Job it
   shows the most recent past Visit.

7. Turn airplane mode on and open a Job Details screen.
   Expected: "Couldn't load this job" with a "Try again" action; the screen never shows a previous
   Job's values.

8. Switch the app language to French and reopen the screen.
   Expected: the top-bar title, section labels, status labels, "Unassigned", the schedule and the
   failure copy are French.

9. Switch the device to dark mode.
   Expected: the status chips, badge, cards and borders follow the dark scheme.

Expected limitations
- No Job Activity timeline. Management actions, the tappable address and the tappable customer were
  added by `docs/tracker/018-android-job-actions.md`.
```

## Open questions recorded by this slice

| # | Question | Rule | How it is handled now | Blocks |
| - | -------- | ---- | --------------------- | ------ |
| 1 | The `jobs.*` capability set | `BR-006`, `BR-008` | The read reuses `customers.view`; the actions added by tracker 018 reuse the existing `JOB_UPDATE` | Per-action authorization, and re-guarding this route |
| 2 | Job Activity's event vocabulary | `BR-080`, `BR-041` | Not implemented; the screen shows no timeline | The Job Activity timeline in both clients |
| 3 | Job deletion behaviour | `BR-021` | Not implemented; no delete capability is implied | Deleting a Job, and what happens to its history |
| 4 | A Job's extra information / notes | `BR-027`, `BR-053` | Only the description is presented; no notes field is invented | A notes/attachments block on Job Details |
| 5 | Tapping the address to navigate | `BR-038`, `BR-042` | **Resolved by tracker 018**: the address is handed to the device's own map application through a `geo:` intent, so Servora names no provider and models no location | — |
| 6 | The Job category catalogue | `BR-053` | `typeCode` is carried but not presented or validated | Filtering/reporting by category |
| 7 | The customer Job row's target | `BR-010`, `BR-012` | Customer-detail Job rows are not tappable yet; the Job Details screen now opens the customer directly | Reaching Job Details from the customer surface |
