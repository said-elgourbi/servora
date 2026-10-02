# Tracker 058 — Technician Home: the attention section's classification (landed) and the action-oriented redesign (proposed)

**Status: PARTIAL — the classification/ordering fix is LANDED and verified at Tier A (§5); the
action-oriented redesign is PROPOSED and awaits product ownership's decision before any hierarchy
change (§6). Android physical-device QA is the product owner's (`qa.md` §7).**

Date: 2026-09-29

Type: product-owner-requested revision of the technician Home, deliberately split in two. The first half
corrects the section's *classification and ordering* and changes no business rule; the second half changes
the screen's *hierarchy* and cannot be implemented without an explicit product decision, because
`docs/design/android-design-system.md` §"Home screens" currently forbids exactly what a dashboard adds.

Source: product ownership, 2026-09-29. The request was a technician Home redesigned as an action-oriented
dashboard with correct Overdue / Needs-attention classification and ordering. Asked to choose the scope
(no authoritative spec existed for a dashboard Home), product ownership answered:

> **"Do the safe classification/ordering fix now, and give me a written redesign plan for approval before
> any hierarchy change."**

That answer is what §2 implements and what §6 proposes.

Business rules this work is bounded by: `BR-001`, `BR-007`, `BR-010`, `BR-011`, `BR-012`, `BR-013`,
`BR-014`, `BR-028`, `BR-031`, `BR-041`, `BR-042`, `BR-072`, `BR-074`; `ADR-019` D6; `dev.md` §7, §18;
`qa.md` §3.1, §7. **No business rule is changed by this tracker.** What changes is the
`GET /home/technician` projection and one screen's section order, both recorded in their own documents
(`BR-040`: a projection is not a rule).

Related trackers (not replaced): `docs/tracker/037-technician-field-experience.md` (owns the technician
Home and `ADR-019` D6), `docs/tracker/055-android-home-refresh-on-return.md` (the day is re-read on
return), `docs/tracker/057-qa-issue-list-visit-workflow.md` (the QA list the Home defects came from),
`docs/tracker/016-android-manager-home.md` (the manager home, which shares the presentation).

## 1. What was wrong

### 1.1 One Visit was stated three times on one screen

The read returned `attention` as *every* open overdue Visit of the caller, and an overdue Visit is
usually also one of the day's own rows — and, when nothing has started, the one the read offers as
`nextVisit`. A technician with one attempt left `SCHEDULED` past its window therefore read the same
Visit three times on one screen: as **Up next** (with the derived overdue pill), as a **Today** row
(with the same pill), and as a **Needs attention** card. The section that exists to say *what on your
own work has gone wrong* was repeating what the screen had already said, which is the opposite of the
single question this screen answers (`BR-010`, `BR-012`) and of one condition having one statement
(`BR-041`).

### 1.2 The section was the last thing on the screen

The screen drew Up next → Today → **Upcoming** → Needs attention. A late attempt that was neither
today's work nor the next thing — an attempt from an earlier day that nobody has moved on — sat under a
capped preview of the days *after* today, although it is work to resolve rather than work to look
forward to (`BR-012`).

### 1.3 The order inside the section was incidental

The items came out in the order the open Visits happened to be mapped in. That order *is* urgency
(earliest scheduled start first), but nothing said so: no named rule, no test, and no doc. An incidental
guarantee is one refactor away from being lost.

## 2. What landed

| # | Change | Where |
| - | ------ | ----- |
| 1 | **The section states only what the screen does not already state.** `attention` excludes any Visit the read offers as `nextVisit` (its own card carries the derived overdue condition) and any Visit in today's `visits` (its own row carries it). `attentionTotal` counts the set `items` holds, so the heading can never disagree with the list. | `api/src/home/technician-home.dto.ts` (`selectAttentionVisits`), `api/src/home/technician-home.service.ts` |
| 2 | **Urgency becomes an explicit, named rule.** `compareAttentionItems`: the attempt overdue longest first (earliest scheduled start), the Job number breaking a tie — the order the manager home applies within the same condition, so one Visit is never presented in two orders (`BR-041`). | `api/src/home/technician-home.dto.ts`, applied by the service |
| 3 | **The section is drawn above the preview of the days after today.** Up next → Today → **Needs attention** → Upcoming. | `android/…/ui/home/TechnicianHomeScreen.kt` |
| 4 | The read's contract, the screen's design and this tracker record the classification, the order and the reason. | `docs/api/technician-home.md` §3.3, `docs/design/android-design-system.md` §"Home screens", this tracker |

**What the technician sees now.** Everything overdue that is already on screen is stated where it
already is — the Up-next card and today's rows carry the overdue pill, and the section no longer repeats
them. The section appears when there is late work the screen says nowhere else (an attempt from an
earlier day that is neither the next Visit nor part of today), listed most-overdue-first. It is
therefore *absent* while a technician may still have something overdue, which is deliberate and is now
stated in both documents: the day on screen is already saying it. Nothing is hidden — the derived
overdue condition is the API's answer on every row that carries it (`BR-001`, `BR-041`).

**Why the classification lives in the API and not in the screen.** Which of the caller's Visits is
"next", which are "today's" and which conditions exist are already the API's answers (`BR-001`,
`BR-041`), and this read is a screen projection by design ("one request serves the whole screen"). A
client that subtracted the sets itself would hold a second definition of the section, and two clients
could disagree about it.

## 3. Files

| File | Change |
| ---- | ------ |
| `api/src/home/technician-home.dto.ts` | the kind's doc comment states the one-statement rule; `selectAttentionVisits` (classification) and `compareAttentionItems` (urgency order) added |
| `api/src/home/technician-home.service.ts` | the attention set is selected by that rule and ordered by it; the count follows the set |
| `api/src/home/technician-home.dto.spec.ts` | 5 new unit tests (3 for the classification, 2 for the order) |
| `api/test/technician-home.e2e-spec.ts` | the section's classification asserted over real PostgreSQL: an overdue Visit stated once (the next one), today's overdue Visits not repeated, and late work from an earlier day stated longest-overdue-first |
| `android/app/src/main/java/com/servora/android/ui/home/TechnicianHomeScreen.kt` | the attention section drawn before the upcoming preview; the screen's KDoc and the card's KDoc state the rule |
| `android/app/src/androidTest/java/com/servora/android/ui/home/TechnicianHomeScreenTest.kt` | a device test: the attention heading sits above the upcoming heading |
| `docs/api/technician-home.md` | §3.3: `attention.total` / `attention.items`, and the paragraph that states the classification and the order |
| `docs/design/android-design-system.md` | the technician-home bullets: where the section is drawn and what it never repeats |
| `docs/tracker/058-technician-home-classification-and-redesign.md` | this entry, including §6's proposal |

## 4. Tests

| Level | What is covered | Where |
| ----- | --------------- | ----- |
| API unit | The classification (the next Visit is not repeated; today's row is not repeated; late work from an earlier day is stated) and the urgency order (longest overdue first, Job number on a tie) | `technician-home.dto.spec.ts` — `selectAttentionVisits`, `compareAttentionItems` (new) |
| API e2e (PostgreSQL) | An overdue Visit is offered as the next one and is **not** repeated in the section; both of today's Visits are excluded from it; two late attempts from earlier days are stated longest-overdue-first; a `CANCELED`/`DRAFT` Visit is still not work of the day | `technician-home.e2e-spec.ts` — `offers an overdue Visit as the next one without repeating it in the section`, `states late work the screen says nowhere else, longest overdue first` (new), and the day test's assertion (changed) |
| Android UI (Compose, device) | The attention heading is drawn above the upcoming heading | `TechnicianHomeScreenTest` — `drawsWhatNeedsAttentionBeforeThePreviewOfTheDaysAfterToday` (new; the product owner runs it, `qa.md` §7.3) |

## 5. Verification

Scope (`qa.md` §3.1): **Tier A — targeted, API only** for the executable part. The API projection changed
and no business rule, database schema, migration, capability or shared wire contract changed; the Android
change is one screen's section order and a Compose test. The full lint and full suites of both applications
are Tier B, due when this feature completes.

| Check | Command | Result |
| ----- | ------- | ------ |
| API type check | `cd api && npm run typecheck` | **PASS** — no diagnostics |
| API build | `cd api && npm run build` | **PASS** — `nest build`, no output |
| API unit tests (the changed module) | `cd api && npx vitest run src/home/technician-home.dto.spec.ts` | **PASS** — 13 tests, 1 file |
| API e2e (real PostgreSQL) | `cd api && npm run test:e2e -- test/technician-home.e2e-spec.ts` | **PASS** — 14 tests, 4.04 s (the two new ones included) |
| API lint, changed files only (`make api-lint` is the repository's `oxlint src/ test/`) | `cd api && npx oxlint src/home/technician-home.dto.ts src/home/technician-home.service.ts src/home/technician-home.dto.spec.ts test/technician-home.e2e-spec.ts` | **PASS** — 0 warnings, 0 errors on 4 files |
| Android compile / JVM tests / lint | `cd android && ./gradlew compileDebugKotlin`, `./gradlew testDebugUnitTest --tests 'com.servora.android.ui.home.*'`, `./gradlew assembleDebugAndroidTest` | **NOT RUN — the product owner's standing instruction** (`docs/tracker/037`, `docs/tracker/055`: no Gradle or Android command on this machine while they are working). No Gradle daemon was running before this task and none was started, so nothing was left behind and `make android-stop` was not needed |
| Android physical-device QA | `./gradlew connectedDebugAndroidTest` and the runbook below | **NOT RUN — device QA is the product owner's** (`qa.md` §7.3). No `adb` and no device or emulator command was run |

### 5.1 Manual QA runbook (product owner)

```text
Android QA — the technician Home's Needs attention section

1. Sign in as a Technician whose own work includes one attempt from an earlier day that is still
   Scheduled, and one attempt today that is still Scheduled with a window that has already ended.
2. Read the Home.

Expected:
- Up next states the Visit to do next, with its derived Overdue pill when that Visit is overdue.
- Today lists today's own attempts, each carrying its own condition.
- Needs attention is drawn ABOVE the Upcoming preview and lists only work the screen does not already
  state — the attempt from the earlier day, most overdue first. It does NOT repeat the Up-next Visit
  or a row of today's list.
- Move the Visit to En route (or On site): the attempt is no longer overdue and, if it was the only
  item, the section disappears.
3. Queue a field action offline, return to Home, and re-enter it.

Expected: the section still reads the API's answer; the day already known stays on screen with its own
notice, and nothing about the section is decided from the device clock.
```

## 6. PROPOSED — the action-oriented redesign (awaiting product ownership's decision)

**Nothing in this section is implemented, and nothing in it may be implemented before product ownership
answers §6.6.** It changes the screen's hierarchy, which is why it was separated from §2.

### 6.1 What "action-oriented" has to mean on this screen

The technician Home answers one question — *what do I need to do next?* (`BR-010`, `BR-012`; `ADR-019`
D6) — so action-orientation here means: the thing to do is first and unmistakable, everything that needs
a decision sits in one place, and every row leads to where the action is performed. It does **not**
automatically mean KPI tiles: `docs/design/android-design-system.md` §"Home screens" forbids counts and
dashboard summaries on this home today, and `BR-012`'s own principle is "the technician should be able
to understand what work needs attention and what action is available". If tiles are wanted, that is a
product decision that amends the design document under `BR-040`, not a UI choice.

### 6.2 The proposed hierarchy (recommended)

```text
1. Do this now        the next Visit as the screen's one focal block — time, derived condition, work,
                      location, crew, and the action that opens it
2. Needs attention    immediately under it: the conditions that need the technician to act or decide,
                      one row per thing to act on
3. Today              the day's own attempts in time order, each carrying its own derived condition
4. After today        the capped preview with its whole count
```

That is the §2 classification with one hierarchy change: the section moves from last to second. It keeps
"the next Visit is the largest thing on the screen" and adds no counts.

### 6.3 Where a row's action would come from

A row the technician can *act on* needs the API's own answer about what that caller may do with that
Visit. The Job Details projection already reports `allowedStatusTransitions` per Visit (tracker 037), but
`GET /home/technician` carries no destinations at all. Two options, and the difference is a product
decision (`BR-066`, `BR-074`, `BR-007`):

- **(a) The row opens the Job** — today's behaviour, zero contract change: the action stays beside the
  Visit and the Home stays a read (`BR-012`).
- **(b) The Home performs the first permitted action** ("Start travel" on the row) — the projection gains
  the destinations the caller may execute for that Visit, and the row offers the first one. That is a
  contract change plus an offline question: the mutation must be queued through the existing outbox
  operation `visit.status.change`, which already has the idempotency key and the conflict policy the
  offline standard requires (`docs/architecture/offline-first-architecture.md` §13), so no new
  synchronization design is needed — but the projection and its tests are new work.

### 6.4 What the redesign must not break

`BR-001` / `BR-041` (the API decides which sets exist and one condition has one definition), `BR-007` /
`BR-011` (the API authorizes; the client shows or hides and never enforces), `BR-013` / `BR-014` /
`BR-031` (the home read is offline-capable through the working set — any added projection field must pass
the offline standard's §13 check), `BR-028` (bilingual strings for every new label), `Project.md` §11
(phone *and* tablet layouts), and the presentation the two homes share (`HomeVisitStatusPill`,
`SectionLabel`, `InfoCard`, `OfflineNotice`) so the manager and technician homes keep describing one
Visit in one way.

### 6.5 If approved, the work is

1. The product decision recorded (`BR-040`): the hierarchy in §6.2, whether any count appears, and §6.3's
   (a) or (b).
2. `docs/design/android-design-system.md` §"Home screens" amended to state the new hierarchy.
3. If §6.3 (b): `docs/api/technician-home.md` and the read extended with the caller's permitted
   destinations for the row's Visit, with contract tests and the offline §13 check.
4. The Android screen reworked into the focal block plus action rows, with its Compose tests and the
   product owner's device pass.
5. A tracker of its own, selected as its own feature slice (`Project.md` §7 Step 1) — not "while we are
   here".

### 6.6 The decisions this proposal needs

| # | Decision | Options | Recommendation |
| - | -------- | ------- | -------------- |
| 1 | Does the section move to position 2, under the next Visit? | yes / keep it after Today / keep it last | **yes** — a late attempt is work to resolve, and the section is now the only place the screen names it |
| 2 | Does the Home perform a Visit action itself? | (a) no, the row opens the Job / (b) yes, offer the first permitted action on the row | **(a) for this slice** — it keeps the field action beside the Visit (`BR-074`) and the Home a read; (b) is its own feature with a contract and an offline decision |
| 3 | Does any count or tile appear on this Home? | yes (amends the design doc) / no | **no** — `BR-012` and the design doc both answer "what do I do next?", not "how many" |
| 4 | Which other conditions may the section carry? | §7.3 | decide each one when its own rule exists |

## 7. OPEN QUESTIONS (`BR-042`)

Nothing below is implemented, and none of it may be implemented before it is decided.

1. **Counts or tiles on the technician Home** — the design doc forbids them today (§6.6 decision 3).
2. **A field action performed from the Home** — §6.3 (a) or (b); (b) needs the projection change and the
   offline standard's §13 check.
3. **Other attention kinds** — a **refused or clarified follow-up request** is the technician's own
   business (`BR-FV-010`, `BR-FV-013`; `docs/tracker/057-…` §5.8 landed the technician's own requests read
   on My Schedule), and **evidence waiting to be synchronized** is a device-local fact the API cannot see
   (`BR-014`, `BR-031`). Neither is modelled for this section, and the read's kind vocabulary must not
   gain one before its own rule exists.
4. **A tablet layout for the Home** — `Project.md` §11 requires a layout that is not the phone layout
   stretched; nothing is designed or implemented for the technician Home.

## 8. Hygiene

No Gradle command, no `adb` and no device command was run; no build daemon was running before this task
and none was started, so there was nothing to stop (`dev.md` §18, `qa.md` §7.4). No temporary file was
written by this task. The foundation stack (`make up`, already running) was not touched, and no database
or storage volume was changed. No file was opened in the product owner's editor.




