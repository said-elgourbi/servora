# Tracker 048 — Job Activity: each Visit's group states the Visit's own outcome

**Status: IMPLEMENTED** (the API projection field with its unit and e2e coverage, the Android mapping, the
card's outcome row and the English and French strings, with the focused tests; **Android physical-device QA
is the product owner's**, `qa.md` §7)

Date: 2026-09-19

Business rules: `BR-001`, `BR-012`, `BR-028`, `BR-041`, `BR-042`, `BR-047`, `BR-067`, `BR-071`, `BR-074`,
`BR-077`, `BR-078`, `BR-079`, `BR-080`

Refines, and does not replace: `docs/tracker/045-android-job-activity-visit-groups.md` (the per-Visit activity
groups) and `docs/tracker/046-android-job-details-visit-period-and-card.md` (the card that heads a group's
activity)

Contract: `docs/api/job-details.md` §3.2 (`visits[].outcomeCode`)

Design reference: none — no Figma screen exists for the redesigned page, so the established Servora Material 3
visual language and its card convention are followed (`docs/design/android-design-system.md`)

## What this slice changes

A reader who opens a Visit's group in the **Job Activity** section is told what that field attempt was — its
date, the window it was scheduled for and the crew assigned to it — and then reads what happened on it entry by
entry. What the attempt **resulted in** (`BR-078`) was only in those entries: a Manager scanning the Job's
previous Visits had to read the whole timeline of each one to learn that the earlier visit was resolved rather
than left needing parts.

The Visit's card in the Job Activity section now states the Visit's **outcome** as one more row of its own facts
(`Outcome` · `Resolved`), between the scheduled window and the crew, in both languages. It is drawn only when
the Visit holds one.


## The decision this slice had to make: where the outcome comes from

The outcome a Visit holds is the **Visit's own current outcome** and not its history, and the two differ in a
way that matters here (`BR-079`): a completed Visit that was reopened holds **no** current outcome until it is
completed again, while the outcome it recorded stays in append-only history.

That leaves exactly one correct source and rules out the convenient one:

| Candidate source | Why it was not used |
| ---------------- | ------------------- |
| The `VISIT_OUTCOME_RECORDED` event in the Visit's own activity (no API change at all) | A projection of **history**. The latest outcome entry of a Visit that was later reopened states an outcome the Visit no longer holds, and choosing "the current one" from that list is a business determination the client would be making for itself (`BR-001`, `BR-041`, `BR-079`). |
| **`visits[].outcomeCode` on `GET /jobs/:id`** — the Visit's own recorded outcome | The authoritative record the Visit already carries. It is `null` exactly when the Visit holds none, including after a reopen, and the API decides it (`BR-001`, `BR-007`). |

So the read gained **one additive field**, `visits[].outcomeCode` (`docs/api/job-details.md` §3.2), read from
`visits.outcome_code` in the Visit read every projection already shares (`src/jobs/visit-assignment.ts`,
`readJobVisits`). Nothing else about the read changed, and no schema, migration, route, permission or offline
rule was touched. The outcome's **summary** is deliberately not on the wire: it is the technician's account of
what happened and belongs to the entry that recorded it, in that Visit's own activity (`BR-077`, `BR-080`).

**Android mapping.** An unknown Visit status or assignment role still fails the whole read, because a Visit the
screen cannot name must not be presented as something else (`BR-042`). An outcome the build cannot name is
deliberately **not** fatal: it is reported as no outcome rather than as a different one, and the Job, its Visit,
its schedule and its crew stay presentable — nothing is lost, because the entry that recorded the outcome is
still in that Visit's activity (`BR-080`). The row is then simply not drawn.

**One label table, not two.** The card resolves an outcome code with the `visitOutcomeLabel` table the
completion sheet already uses, and the Job Activity timeline's own outcome title was repointed at the same table
instead of keeping a second copy of it (`BR-028`, `BR-041`).

## What this slice does not change

The **represented Visit's own section** above the Activity still states its date, its status and its crew and
nothing more: it is the section the screen's Visit actions belong to, and that Visit's outcome is stated in its
own activity group (`BR-047`, `BR-080`). The grouping, the headings, the fold behaviour, the default-open Visit,
the General job updates group, the photo gallery, the viewer and every action are as `docs/tracker/045` and
`046` left them. No permission, route, table, migration or offline/outbox behaviour changed, and the working set
absorbs the new field as absent for a row an earlier build wrote (`offline-first-architecture.md` §10).

## Verification

**Scope** (`qa.md` §3.1): **Tier A — targeted**, API and Android, because one projection field and one screen
area changed and no endpoint, permission, schema, migration or offline rule was added. The full lint and the
full suites of both applications are Tier B, at feature completion.

| Check | Command | Result |
| ----- | ------- | ------ |
| API typecheck | `cd api && npm run typecheck` | **PASS for this change.** The two remaining errors are `test/technician-home.e2e-spec.ts(351,18)` and `(354,7)` — pre-existing and unrelated: they reproduce on a pristine `git archive HEAD` checkout. |
| API unit tests (projection) | `cd api && npm test -- src/jobs/job-details.dto.spec.ts` | **PASS** — the projection's outcome mapping, present and absent, with the pre-existing cases |
| API build | `cd api && npm run build` | **PASS** — `nest build` |
| API e2e (contract) | `cd api && npm run test:e2e -- test/job-details.e2e-spec.ts` | **PASS** — including the new case that a reopened Visit reports no outcome while history keeps it |
| API e2e (the shared Visit read) | `cd api && npm run test:e2e -- test/job-activity.e2e-spec.ts` | **PASS** — 7 tests: the activity projection reads its Visit list through the same `readJobVisits` this change extended, so the read it shares is verified beside it |
| Android compile | `cd android && ./gradlew compileDebugKotlin` | **PASS** |
| Android JVM tests (affected classes) | `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.data.jobs.JobDetailsRepositoryTest' --tests 'com.servora.android.ui.jobs.JobDetailsOverviewModelTest'` | **PASS** |
| Android device-test sources | `cd android && ./gradlew assembleDebugAndroidTest` | **PASS** — `JobDetailsOverviewScreenTest` compiles into the androidTest APK |
| Android lint | — | **NOT RUN — the affected application's full lint belongs to Tier B at feature completion** (`qa.md` §3.1, `dev.md` §18) |
| Android physical device | — | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). The agent ran no `adb` and no device or emulator command. Command the product owner may run: `cd android && ./gradlew connectedDebugAndroidTest` |

## Manual QA runbook (product owner)

1. Sign in as a Manager and open a Job whose earlier Visit is **completed** and whose represented Visit is still
   ahead.
2. Open the Job Activity section and expand the earlier Visit's group: its card reads **Date**, **Scheduled
   time**, **Outcome** (`Resolved`, or whichever outcome that Visit was completed with) and **Assigned
   technicians** — the outcome between the scheduled time and the crew.
3. Look at the Visit the page represents (already open): its card states **no** Outcome row, because that field
   attempt holds none yet.
4. Complete the represented Visit from its own section (the completion sheet asks for the outcome and a summary),
   reopen the Job and expand that Visit's group: its card now states the outcome it was completed with.
5. Reopen that Visit (its status chip → any working status), then reopen the Job: the Outcome row is **gone**
   again, while the Visit's activity still shows the `Recorded outcome` entry — that is `BR-079` and not a lost
   record.
6. Switch the device to French: the row label reads **Résultat** and the value reads the localized outcome
   (`Résolu`, `Pièces requises`, …).
7. Turn TalkBack on and traverse an expanded group: the card announces the date, the scheduled time, the outcome
   and the crew in that order.

**Expected:** every Visit's group states what that Visit resulted in and nothing else's, the row disappears for a
Visit that holds no outcome, and no other part of the page moved.

## Hygiene

No scratch file, log or temporary script is left: the compile and test logs this task wrote were deleted, the
throwaway pristine baseline checkout used to show the API typecheck errors were pre-existing was removed,
`git status` shows only the files this tracker lists, and the Gradle/Kotlin build daemons the verification
started were stopped with `make android-stop` (`dev.md` §18). The foundation stack (`make up`) was not touched
and no database or storage volume was changed.
