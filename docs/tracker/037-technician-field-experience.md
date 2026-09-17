# Tracker 037 — The technician's field experience (Android) and the Visit lifecycle behind it

**Status: IN PROGRESS — Phases 1, 2a, 2b, 3 and 4 have landed: `ADR-019` (accepted by product ownership
2026-09-17), the API field read, `GET /home/technician` with the Android technician home, and the Visit
field lifecycle. D6 was answered by product ownership on 2026-09-17, so the technician home is no longer
design-gated; Phase 5 (the field action on the Job Details screen) has not started, and Phase 6 is the
product owner's device QA.**

Date: 2026-09-17
Predecessors: `docs/tracker/009-android-session-persistence.md` (the session and its permissions),
`docs/tracker/016-android-manager-home.md` (the home read this one mirrors),
`docs/tracker/017-android-job-details.md` and `docs/tracker/018-android-job-actions.md` (the Job Details
screen and its capability gating), `docs/tracker/020-android-job-details-status-control.md` (the status
control), `docs/tracker/025-android-offline-room-outbox.md` and
`docs/tracker/026-android-offline-customers-list.md` (the offline engine and its first offline read),
`docs/tracker/029-photo-evidence-phases.md` + `docs/tracker/035-android-audio-evidence.md` (evidence, the
first capabilities the Technician role actually holds)
Decision record: `docs/decisions/019-technician-field-experience.md` — **does not exist yet; Phase 1
creates it**
Contracts: `docs/api/technician-home.md` (new), `docs/api/job-actions.md` (a new Visit section),
`docs/api/job-details.md` §2 (re-reviewed by this slice)
Business rules: `BR-004`, `BR-006`, `BR-007`, `BR-009`, `BR-010`, `BR-011`, `BR-012`, `BR-013`, `BR-014`,
`BR-028`, `BR-031`, `BR-032`, `BR-033`, `BR-040`, `BR-041`, `BR-042`, `BR-058`, `BR-059`, `BR-060`,
`BR-061`, `BR-062`, `BR-066`, `BR-067`, `BR-068`, `BR-070`, `BR-072`, `BR-074`, `BR-075`, `BR-077`,
`BR-078`, `BR-079`, `BR-080`, `BR-086`, `BR-088`
Domain model: `docs/domain/job-visit-domain-model.md` §8.3, §9.1, §9.5, §11.1, §11.2, §14, §15
Offline standard: `docs/architecture/offline-first-architecture.md` §2, §5, §8, §11, §12, **§13**;
`docs/decisions/014-android-offline-engine.md`
Design: `Figma/src/screens/JobDetails.tsx` — `TechnicianView` (line 612), the **only** technician design
artifact that exists today

## Scope

Servora has no usable technician experience. The Job Details screen is already capability-driven rather
than role-driven, so the *presentation* half is done (`BR-010`, `BR-011`); what is missing is the
authorization, the Visit lifecycle API and an entry point. Concretely:

- `GET /jobs/:id` and `GET /jobs/:id/activity` are guarded by `customers.view`
  (`api/src/jobs/jobs.controller.ts:121`, `:154`). The seeded Technician role holds none of it
  (`api/drizzle/migrations/0004_woozy_spitfire.sql:109-118`), so a technician session is **refused** and
  cannot open Job Details at all.
- The catalogue's four field capabilities — `VISIT_VIEW_ASSIGNED`, `VISIT_UPDATE_ASSIGNED_STATUS`,
  `VISIT_ADD_NOTE`, `VISIT_RECORD_OUTCOME` — are seeded to the Technician role but are **enforced by no
  route and read by no client**. The Visit lifecycle (`BR-074`), the one defined correction (`BR-075`) and
  the outcome completion requires (`BR-077`, `BR-078`) exist today only as schema and rules.
- A Visit note requires `JOB_UPDATE` (`jobs.controller.ts:269`), so the technician who holds
  `VISIT_ADD_NOTE` cannot add one.
- There is no technician entry point: the bottom navigation hides the Customers tab without
  `canOpenCustomers` (`ui/customers/CustomersScreen.kt:347`), the Home tab then loads the **manager** home
  (`:287-300`), which the API refuses for a technician, and Schedule is a `PlaceholderTab` (`:302-306`).
- **No database work is needed.** `visit_status_history` (with `is_correction`, `reason_code`,
  `cancellation_source`, `job_status_history_id`, `captured_at`, `client_operation_id` and its unique
  partial index) and `visit_outcome_history` already exist from migration `0003`
  (`api/drizzle/migrations/0003_purple_black_tarantula.sql:363`, `:359`), together with `visits.version`
  and `visits_completed_outcome_check`. The Visit field lifecycle is a **missing API and client over a
  schema that was designed for it**.

| Item | Status |
| --- | --- |
| `ADR-019` — the field capability model, read authorization, Visit→Job consequences, conflict policy | **Landed — 2026-09-17 (Phase 1)** |
| The read capability decision and its enforcement | **Landed (Phase 2a)** |
| Assignment-scoped read of a Job a technician is working on | **Landed (Phase 2a)** |
| `GET /home/technician` — the assigned work of a day | **Landed — Phase 2b (2026-09-17), once D6 was answered** |
| The Visit status route (`BR-074`) including `BR-075`'s correction | **Landed (Phase 3)** |
| Completion carrying its outcome (`BR-077`, `BR-078`) and the note under `VISIT_ADD_NOTE` | **Landed (Phase 3)** |
| Android: the technician's Home — offline-capable read and entry point | **Landed (Phase 4, 2026-09-17)** |
| Android: the field action on Job Details (primary action, outcome, note) | **Not implemented — Phase 5** |
| Android: the four field capabilities in `Permission.kt` and the screen gates | **Partly — `VISIT_VIEW_ASSIGNED` landed in Phase 4; the three the field action needs are Phase 5** |
| Offline queueing of the field action (idempotency key + conflict policy) | **Phase 5** — the API is capable (D5 ✓) and Android adopts it with the field action |
| Physical-device QA | Phase 6, product owner |
| **Follow-up Visit requests** (`BR-FV-001` – `BR-FV-013`) | **Out of this slice** — its own slice; the office review decision (`BR-FV-004`) is a management surface |
| **Office-side Visit actions** (cancel a Visit, `NO_SHOW`, `BR-075` corrections) | **Out of this slice** — `BR-066` makes them dispatch actions and no capability exists for them (`BR-042`) |
| **Notifications** (`BR-029`), **time tracking** (`BR-034`), **GPS** (`BR-038`) | **Out of this slice** — all OPEN QUESTION |

### What this slice does not change

- **No business rule is changed.** `BR-009` already names the technician's four capabilities and they are
  already in the catalogue and granted to the default Technician role. This slice makes them *enforced*,
  which is what `BR-006` and `BR-007` already require.
- **No new capability code is assumed.** If Phase 1 decides one is needed (D1's option A2), that is a
  catalogue change with its own migration and bilingual name/description (`BR-005`, `BR-006`) and the
  changed decision is recorded under `BR-040`.
- **No new section is added to the Job Details screen for a technician.** The screen already draws what a
  session's capabilities allow (`BR-011`); the technician's field action is one more gated action on the
  same screen (`BR-010`).

## Phases

| # | Phase | Depends on | Layers |
| --- | --- | --- | --- |
| 1 | Decisions and the field capability model — `ADR-019` | — | docs/decisions, product decision |
| 2 | API — the field read: assigned work and the Job read's authorization | 1 | API, permissions, contracts, tests |
| 3 | API — the Visit field lifecycle: status, completion with its outcome, notes | 1, 2 | API, tests, contracts |
| 4 | Android — the technician's Home (assigned work, offline-capable read) | 2, **design gate** | Android, offline, localization, tests |
| 5 | Android — the field action on Job Details | 3, 4 | Android, offline/outbox, localization, tests |
| 6 | Device QA and the landing record | 5 | docs, `qa.md` §7.2 |

Each phase lands on its own short-lived branch and is verified at the tier `qa.md` §3.1 prescribes: the
affected application's compile and the tests that cover the change per task (Tier A), the full lint,
suites and build of every affected application when the phase completes (Tier B).

## Phase 1 — Decisions and the field capability model

**Goal.** One decision record, `docs/decisions/019-technician-field-experience.md`, that answers the
questions this slice cannot answer for itself. It is documentation only: no code, no migration. Every
other phase is blocked on it, because each one implements one of its answers.

**Why it exists.** `BR-042` forbids inventing business behaviour, and the read authorization, the
crew's authority over a Visit, the Visit→Job consequences and the offline conflict policy are all
product decisions that are not in the rules today. The pattern is the one `ADR-015` and `ADR-018` already
followed for evidence.

| # | Decision | Rule it answers to | Blocks |
| --- | --- | --- | --- |
| D1 | **The read capability and its shape** — see the options below | `BR-006`, `BR-009`, `BR-042` | Phase 2 |
| D2 | **Which Jobs and Visits a field caller may read** — the caller's own assignments only, or every Visit of a Job they are assigned to | `BR-009`, `BR-001` | Phase 2 |
| D3 | **Who on a crew may drive the field lifecycle** — any assigned technician, or the Lead only — for the status transition, the outcome and the note | `BR-009`, `BR-066`, `BR-068` | Phase 3 |
| D4 | **The Visit→Job status consequence** — `BR-058` says the Job "advances as a consequence of Visit lifecycle events (`BR-074`)" and `BR-061`/`BR-062` own the conditions, but **which** Visit events move the Job and to which status is not defined | `BR-058`, `BR-059`, `BR-060`, `BR-061`, `BR-062` | Phase 3 (the transition's side effects) |
| D5 | **Which field operations are offline-capable, and each one's conflict policy** (`BR-032`) | `BR-013`, `BR-014`, `BR-031`, `BR-032` | Phase 3 (whether the route takes a `clientOperationId` + expected version) and Phase 5 (whether the action may be queued) |
| D6 | **The technician Home's content and order** — no design exists for it (see the design gate) | `BR-010`, `BR-012`, `BR-042` | Phase 4 |
| D7 | **Whether an office user may perform a field action** when the crew cannot — the routes here require the field capabilities, and no office-side Visit capability exists | `BR-006`, `BR-066`, `BR-042` | Nothing in this slice; recorded so the office actions are not invented here |
| D8 | **"Pausing work"** — `BR-013` lists pausing as an offline-capable workflow, but `BR-074`'s Visit vocabulary has no paused state | `BR-013`, `BR-074` | Nothing in this slice; recorded rather than implemented |

### D1 in detail — because it is the one that changes an existing route

`@RequirePermissions` is **all-of**: `PermissionsGuard` resolves a membership holding *every* listed code
(`api/src/auth/permissions.guard.ts:47-55`, `api/src/auth/authorization.service.ts:35-38`). A route that
either a manager **or** a technician may use therefore cannot be expressed by passing two codes.

| Option | Shape | Consequence |
| --- | --- | --- |
| **A1 — widen the existing route** | `GET /jobs/:id` (and `/activity`) accepts `customers.view` **or** `VISIT_VIEW_ASSIGNED`, plus D2's assignment scope | One projection and one wire contract for both audiences (`BR-041`), and the Android screen keeps parsing what it already parses. Needs an authorization primitive that does not exist today (an any-of decorator/guard), which is itself an architectural decision |
| **A2 — add a field capability and require it alongside** | A new code (for example `jobs.view.assigned`) granted to the Technician role, guarding a field read | Invents a capability the Jobs feature has not defined, so it needs a catalogue migration and `BR-040`'s change record |
| **A3 — a separate field read** | `GET /visits/:id` (or `/home/technician/visits/:id`) guarded by `VISIT_VIEW_ASSIGNED`, leaving `/jobs/:id` manager-only | Each route stays single-purpose and no primitive is added, but the Job Details projection is duplicated and two contracts can drift (`BR-041`) |

**Recommendation.** A1, with the assignment scope enforced in the service (`BR-001`): it keeps one
definition of a Job's details, which is what `BR-041` asks for, and the only new thing is an
authorization shape that the ADR records explicitly. A3 is the fallback if product ownership prefers the
manager route to stay manager-only.

**Not in Phase 1.** Any code, migration or contract change. A decision that changes a confirmed rule is
recorded under `BR-040` first.

**Verification.** `ADR-019` exists, every D has an answer, and each answer names the rule it rests on.
Documentation-only: nothing to test.

## Phase 2 — API: the field read

**Split, and landed as 2a (2026-09-17), then as 2b (2026-09-17).** Item 2 below — the Job read's
authorization and scope — is **2a, landed**: a technician session can now open Job Details. Item 1 —
`GET /home/technician` — is **2b** and was stopped at the D6 gate until product ownership answered it;
it is now **landed**, with its content recorded in `docs/api/technician-home.md` and
`docs/domain/job-visit-domain-model.md` §8.4 rather than invented (`BR-042`). The split and both
landings are recorded in the Phase log.

**Goal.** A technician can read the work they are assigned, and the Job Details read answers them
according to D1 and D2.

**Work.**

1. **The assigned-work read** — `GET /home/technician`, mirroring `GET /home/manager`
   (`docs/api/manager-home.md`): one request serving the technician's day, resolved by the API from the
   caller's time zone, scoped to the caller's own `visit_technicians` rows (accepted and current), and
   guarded by `VISIT_VIEW_ASSIGNED`. Its content is D6's answer, not an invention. The projection's
   definition is recorded in the domain model beside §8.4 and in its own contract doc.
2. **The Job read's authorization** — `GET /jobs/:id` and `/activity` accept the field caller per D1, and
   the service enforces D2's scope. A caller who is not assigned gets `404` and never `403`, exactly as
   another organization's Job does (`docs/api/job-details.md` §4, `BR-001`).
3. **Contracts and docs** — `docs/api/technician-home.md`, `docs/api/job-details.md` §2,
   `docs/domain/job-visit-domain-model.md` (the derivation), the tracker below.

**Not in Phase 2.** Any Visit write. The Visit routes are Phase 3, so this phase cannot be mistaken for
delivering the field action. Nothing is changed about the `customers.view` guard for a manager caller.

**Offline posture (§13).** The assigned-work read **is an offline-capable adopter**: `BR-013` explicitly
names "viewing assigned work" as field work that must survive lost connectivity, so it reads through the
existing `WorkingSetStore` with the wire response and its own projection key, and marks a local answer as
the last one the backend reported. The Job Details/activity reads are already adopters
(`docs/tracker/029-photo-evidence-phases.md` Phase 5); this phase only adds a caller scope, and the row
must be keyed so one caller's answer is never served to another (§13.4, §10).

**Verification.** API unit tests for the projection and the scope; e2e for `401`, `403` (a caller holding
neither capability), the field caller's success, the `404` for an unassigned Job, tenant isolation, and a
Manager caller unchanged. `docs/api/*` and the tracker updated.

## Phase 3 — API: the Visit field lifecycle

**Goal.** The Visit lifecycle `BR-074` defines becomes an explicit, authorized, recorded action set, and
completing a Visit carries the outcome `BR-077` requires.

**Work.**

1. **`PATCH /jobs/:id/visits/:visitId/status`** — guarded by `VISIT_UPDATE_ASSIGNED_STATUS` (plus
   `VISIT_RECORD_OUTCOME` on the completion destination, which `@RequirePermissions` expresses as all-of).
   It applies only the normal lifecycle's forward transitions
   (`DRAFT → SCHEDULED → EN_ROUTE → ON_SITE → IN_PROGRESS → COMPLETED`, `BR-074`) and `BR-075`'s one defined
   correction `EN_ROUTE → SCHEDULED` (`is_correction`). `CANCELED` and `NO_SHOW` are **refused** here:
   `BR-066` makes them dispatch actions, the reason catalogue of `BR-076` belongs to that slice, and
   inventing it is forbidden (`BR-042`). A destination the lifecycle does not permit is its own error code
   with the permitted destinations in the response, as the Job status route already does
   (`docs/api/job-actions.md` §3.2).
2. **Completion writes its outcome in the same transaction** — `outcomeCode` (`BR-078`'s five codes) and
   `outcomeSummary` on the `COMPLETED` request, satisfying `visits_completed_outcome_check` and
   `BR-077`'s "outcome before completion", recorded in `visit_outcome_history` and current on `visits`,
   with the actor and timestamp. `DRAFT` outcome storage stays unmodelled (the domain model says so
   explicitly, §11.2).
3. **The history row** — one `visit_status_history` row per applied transition: previous status, new
   status, `is_correction`, actor, server `recorded_at`, and the client's `captured_at` and
   `client_operation_id` when D5 says they are accepted (§14, §15). The state change and its history row
   are written together (`BR-067`).
4. **The Visit→Job consequences D4 decides** — implemented in the same transaction when they apply, so
   `BR-058`/`BR-060`/`BR-061` stay one definition in one place (`BR-041`). Until D4 answers, **none are
   applied**, which is also what the current Job-status route does for eligibility.
5. **The note route's authorization** — `POST /jobs/:id/visits/:visitId/notes` accepts `VISIT_ADD_NOTE`
   (per D3, with the same assignment scope), so `BR-009`'s technician can add one. The existing route and
   its payload are reused; only the guard and the scope change.
6. **`clientOperationId` and `expectedVersion`** — accepted on both routes **iff** D5 decides the conflict
   policy; the tables and their unique partial indices already support idempotent replay
   (`0003:359`, `0003:363`) and `visits.version` already exists (`§15`).

**Not in Phase 3.** Visit cancellation and `NO_SHOW` (`BR-066`, `BR-076`); outcome corrections and the
technician self-edit window (`BR-079` — every change preserves the previous outcome, and the window is
undefined, so no policy is invented); follow-up requests (`BR-FV-*`); any Android change; any new table
or column.

**Offline posture (§13).** Recorded honestly per D5:

- If D5 decides a conflict policy and the routes accept a `clientOperationId`, the transition and the
  note become **offline-capable adopters** through the existing `OutboxStore` in Phase 5, and
  `docs/architecture/offline-first-architecture.md` §12 moves them out of its online-only list.
- If D5 does not, both routes are **online-only**, and §12's existing statement ("Job and Visit status
  actions, notes … their routes accept no idempotency key yet, or their mutation conflict policy is
  undecided") stays true — with the reason recorded here and in the ADR (`§13` requires the reason, not
  an omission). `BR-013` lists field status work as offline-capable, so this must be a **recorded
  decision with its reason**, never a silent default.

**Verification.** API unit tests for the transition table, the correction, the outcome pairing, the
idempotent replay and the refusal vocabulary; e2e for `401`, `403` (a caller holding `VISIT_VIEW_ASSIGNED`
only), the whole normal lifecycle, every refused destination, `CANCELED`/`NO_SHOW` refused, completion
without an outcome refused, completion with each `BR-078` code, the `BR-079` immutability after a
`COMPLETED` Job, the note route under `VISIT_ADD_NOTE`, and a Job status change still writing **no** Visit
record (`BR-059`). `docs/api/job-actions.md` and the domain model updated.

## Phase 4 — Android: the technician's Home (landed 2026-09-17)

**Goal.** A signed-in technician lands on work they can actually do, and can keep doing it without
connectivity.

**The design gate — satisfied on 2026-09-17, and by the gate's first route rather than its second.**
There is no Figma artifact for a technician home (the project's only technician artifact is
`JobDetails.tsx`'s `TechnicianView`; the Home cards and the Schedule screen are Manager surfaces), so
the phase waited for the product decision `Project.md` §2 requires. Product ownership gave it: the
technician home is **its own screen**, answering *"what do I need to do next?"* — not the manager home
restricted to the caller's assignments (the second route above), which is why the section order,
the next-Visit card and the attention section are the screen's own while the greeting header, the Visit
status pill and the card primitives are shared with the manager home. `ADR-019`'s D6 update records the
answer; the screen itself is Phase 4's work below, and it has landed.

**Work** (once the gate is satisfied).

1. **The Home destination becomes capability-driven rather than manager-only.** Today the Home tab always
   loads the manager home (`ui/customers/CustomersScreen.kt:287-300`) and the Customers tab is hidden
   without `canOpenCustomers` (`:347`), so a technician lands on a screen whose read the API refuses. The
   Home destination now chooses **its content** from the session's capabilities: the manager read for
   `customers.view`, the assigned-work read for `VISIT_VIEW_ASSIGNED`. Which one wins for a session
   holding both is **the product decision recorded in `ADR-019`'s D6 update**: the office home, which
   is the screen that session already had, so the field home is served to a session holding the field
   capability without `customers.view` (`BR-010`, `BR-011`).
2. **The assigned-work list** — one screen state over `GET /home/technician`, following the manager home's
   proven shape (loading, cached-while-refreshing, unrefreshed-but-visible, failure with retry, and the
   "last reported" mark the working set requires).
3. **Offline** — the read goes through the existing `WorkingSetStore` (`§13`, Phase 2's posture), so the
   technician's day is readable in airplane mode. No new cache, no second store.
4. **EN/FR copy** for every new string (`BR-028`), and the design system's existing list/row components
   rather than new ones (`docs/design/android-design-system.md`).
5. **Entry point to Job Details** — a row opens the existing destination (`ServoraRoutes.jobDetail`).

**Landed (2026-09-17).** The capability-driven Home destination, with `VISIT_VIEW_ASSIGNED` in
`domain/auth/Permission.kt` and `canViewAssignedWork` on the permission UI state; `GET /home/technician`
read through `TechnicianHomeRepository` into a new working-set projection (`home.technician`, adopter 9);
`TechnicianHomeScreen` with the product decision's hierarchy — the next Visit first, today
chronologically, the capped upcoming preview, and an attention section that appears only when there is
something — over the same card, section, status-pill, empty and failure primitives the manager home
uses; EN/FR copy for every new string; and every state the screen needs (loading,
cached-while-refreshing, last-reported, failure with retry, nothing assigned).

**Not in Phase 4.** Any write, and therefore any status action on the Home: “Start travel”,
“Arrived” and “Start work” are field **writes** (`BR-074`), so the next Visit's action
opens the Job, which is where the field action lives (`BR-012`). Phase 5 adds those actions to the Home
and to Job Details; the read needs no further contract change to carry them, because the Visit
projection already reports what the server permits (Phase 3). No new bottom-navigation destination was
added: the Schedule tab stays a placeholder, which is why `upcoming` is a capped preview with its whole
count reported beside it.

**Verification.** JVM tests for the ViewModel (initial, loading, success, failure, offline-served, scope),
a Compose test of the list and its states, a test that the read is not offered to a session without the
capability, and `assembleDebugAndroidTest` so the device tests' sources are known to build. No `adb`
(`qa.md` §7.3).

## Phase 5 — Android: the field action on Job Details

**Goal.** The technician's one obvious next action (`BR-012`) on the screen that already exists.

**Work.**

1. **The four field capabilities in the client model** — `VISIT_VIEW_ASSIGNED`,
   `VISIT_UPDATE_ASSIGNED_STATUS`, `VISIT_ADD_NOTE`, `VISIT_RECORD_OUTCOME` are added to
   `domain/auth/Permission.kt` (with the catalogue spellings as their codes) and read into the permission
   state the navigation already passes down (`CustomerPermissionsUiState.kt:47-68`,
   `ServoraNavHost.kt:320-326`). They are UI gates only; the API stays the authority (`BR-007`, `BR-011`).
2. **The primary action** — a `TechnicianView`-style action on the existing Job Details screen, drawn from
   the backend's own answer (the Visit's permitted transitions, or whatever the Phase 3 read reports —
   `BR-041`) rather than from the mock's shortcut. `Figma/src/screens/JobDetails.tsx:617-621` jumps
   `scheduled`/`en-route` straight to `on-site` and has no `DRAFT`, while `BR-074` has `EN_ROUTE` and
   `ON_SITE` as separate states; the client therefore presents what the API offers and never re-implements
   the lifecycle (`BR-022`, `BR-041`), exactly as the Job status control already does
   (`ui/jobs/JobDetailsActions.kt:80-91`).
3. **The completion sheet** — outcome type (`BR-078`'s five codes, localized labels) and the required
   summary, sent as one operation with the transition (`BR-077`). No partial outcome is stored.
4. **The note** — drawn only for a session holding `VISIT_ADD_NOTE` (`BR-009`), instead of today's
   `canUpdateJob` gate (`ui/jobs/JobDetailsScreen.kt:452`).
5. **What the technician must not see** — the Job status control, the reschedule action, crew management
   and Job cancellation stay hidden because the session holds none of their capabilities. Nothing new is
   added to hide them: the existing gates already do it (`:697`, `:711-717`, `:333`).
6. **Offline** — if D5 decided the conflict policy, the transition and the note are queued through the
   existing outbox with a `clientOperationId` and a `capturedAt`, shown as the technician's own pending
   action beside the Visit and never as a change the backend accepted (`§2`, `§7`, `BR-086`), replayed by
   `OutboxReplayEngine` through a handler this feature owns. If D5 did not, the actions are **online-only**
   and the screen says so rather than queueing (`§13`).
7. **Evidence is unchanged** — photos and audio already work for a technician through their own
   capabilities (`evidence.photo.add`, `evidence.audio.add`; `0010:24-29`, `0012:65-70`).

**Not in Phase 5.** The follow-up request UI (`BR-FV-*`); outcome corrections (`BR-079`); Visit
cancellation and `NO_SHOW`; notifications; time tracking.

**Verification.** JVM tests for the ViewModel (each transition, the refusal, the offline-queued action, the
permission-denied path), Compose tests for the action's presence and absence per capability and for the
completion sheet's required fields, `assembleDebugAndroidTest`, then Tier B for the Android application
(`make android-lint && make android-test && make android-build`) and `make android-stop` (`qa.md` §7.4).

## Phase 6 — Device QA and the landing record

**Goal.** The product owner can verify the slice on a real phone, and the record says what shipped.

**Work.** The debug build; the seeded development accounts (a technician membership and a manager
membership) and what each needs; a runbook focused on the changed behaviour (`qa.md` §7.2); the tracker's
landing record with the exact commands and results (`qa.md` §16);
`docs/architecture/offline-first-architecture.md` §11.1/§12 updated to match what actually shipped;
`BR-040`'s change record if any rule changed.

**Verification.** `qa.md` §7.3 — the agent runs no `adb` command; anything needing a device is reported as
`NOT RUN — device QA is the product owner's`, with the command the product owner would run.

## Offline posture — the `§13` record for every read and mutation in this slice

`docs/architecture/offline-first-architecture.md` §13 requires each to be recorded as an adopter, as
online-only with its reason, or as an open product question. This is the record.

| Item | Classification | Where it stands |
| --- | --- | --- |
| Assigned work (`GET /home/technician`) | **Adopter — read** | `WorkingSetStore`, the wire response, its own projection key. `BR-013` names "viewing assigned work" explicitly. Phases 2/4 |
| Job Details + activity, field caller | **Adopter — read** | Already adopted (tracker 029 Phase 5); Phase 2 adds the caller scope, so the row stays scoped to the authenticated subject and the scope it was read under (§10, §13.4) |
| Visit status transition (`PATCH …/status`) | **Adopter — mutation, API-side (Phase 3)** | The route takes a `clientOperationId` and the conflict policy is refuse-and-reconcile (D5 ✓), so it is offline-*capable*; Android's own outbox adoption is Phase 5, so nothing queues it today (`§5`, `§8`, `§13.2`) |
| Visit completion with its outcome | **Adopter — mutation, API-side (Phase 3)** | Same route, same reason: the outcome is written with the transition in one transaction |
| Visit note (`POST …/notes`) | **Adopter — mutation, API-side (Phase 3)** | Same, on the note's own idempotency key; a note has no version, so idempotency alone covers a repeat |
| Evidence capture and upload | **Adopter — mutation** | Unchanged; already queued (trackers 029 and 035) |
| `GET /home/manager`, Job status, reschedule, assignment | **Unchanged, online-only** | Out of this slice; `§12` keeps them where they are |

§11.1 and §12 are updated by the phase that changes an answer, so the standard never claims something the
code does not do.

## What is reused unchanged

- **The one Job Details screen and its capability gating** (`BR-010`, `BR-011`) — no second screen, no role
  check. That is the point of this slice's shape.
- The signed-in shell, the bottom navigation, the one contextual top bar (`ADR-011`), the design system.
- The offline engine as it stands: `WorkingSetStore`, `OutboxStore`, `OutboxReplayEngine`,
  `OfflineOperationHandler`, authenticated-subject scoping (`ADR-014`).
- The session contract (`SessionAuthenticator`, `401` → renew once → retry) and the shared
  `CustomersFailureReason` vocabulary.
- The API's existing patterns: `OrganizationScope`, `@RequirePermissions`, `AuthGuard`/`PermissionsGuard`,
  the `AuthApiError` envelope, the append-only history convention (§14) and the transaction shape the Job
  status route already uses.
- The evidence capabilities the Technician role already holds, and the working-set/outbox shapes the photo
  and audio slices established.

## Open questions recorded by this slice

| # | Question | Rule | How it is handled now | Blocks |
| --- | --- | --- | --- | --- |
| 1 | The `jobs.*` capability set | `BR-006`, `BR-008` | **The read half is decided and landed**: `GET /jobs/:id`, `/activity` and `GET /home/technician` are guarded by `customers.view` or `VISIT_VIEW_ASSIGNED` (`ADR-019` D1), and the office routes stay interim until the Jobs feature defines its own set | Re-guarding `GET /jobs/:id`, `/activity`, `GET /customers/:id/jobs` and `GET /home/manager` together |
| 2 | Whether a field caller reads the whole Job or only their assigned Visit | `BR-009` | **Answered (`ADR-019` D2) and landed**: one Job projection, scoped to the caller's own current assignments; `GET /home/technician` lists only the caller's own Visits | Done |
| 3 | Who on a crew may drive the Visit status, the outcome and the note — any assigned technician, or the Lead | `BR-009`, `BR-066`, `BR-068` | **Answered by `ADR-019` D3 and implemented in Phase 3**: any technician on the Visit's current crew, with the note additionally reachable by the office's `JOB_UPDATE` (the Phase 3 decision) | Phase 3 ✓ |
| 4 | Which Visit events move the Job status, and where to | `BR-058`, `BR-060`, `BR-061` | **Answered by `ADR-019` D4 and implemented in Phase 3** as `visit-job-consequence.ts`, one definition applied inside the transition's transaction | Phase 3 ✓ |
| 5 | Which field operations are offline-capable, and each one's conflict policy | `BR-032`, `BR-013`, `BR-014` | **Answered by `ADR-019` D5**: both routes take a `clientOperationId` and are refuse-and-reconcile; the API is capable and Android adopts them in Phase 5 (`docs/architecture/offline-first-architecture.md` §12, adopter 8) | Phases 3 ✓, 5 |
| 6 | The technician home's content, and whether one Home destination serves both audiences | `BR-010`, `BR-012` | **Answered by product ownership, 2026-09-17**: its own screen, its own hierarchy, one Home destination chosen by capability (`ADR-019`'s D6 update) | Implemented by Phases 2b and 4 |
| 7 | Office-side Visit actions: cancel a Visit, mark `NO_SHOW`, correct a status | `BR-066`, `BR-075`, `BR-076` | Not implemented; no capability authorizes the action, so none is invented | The office half of the Visit lifecycle |
| 8 | The technician self-edit window for an outcome | `BR-079` | Not implemented; `BR-079` confirms a closed Job's immutability and leaves the window undefined | Outcome corrections |
| 9 | "Pausing work" — `BR-013` lists it, `BR-074` has no paused state | `BR-013`, `BR-074` | No state is invented; the Visit's own status is the record | A paused/on-hold Visit state |
| 10 | Follow-up Visit requests | `BR-FV-001` – `BR-FV-013` | Not implemented; its own slice, with the office review decision (`BR-FV-004`) | The technician's "needs a return visit" path |
| 11 | Notifications to a technician about new or changed work | `BR-029` | Not implemented; `BR-029` is an OPEN QUESTION | Any push/notification behaviour |
| 12 | Time tracking on a Visit | `BR-034` | Not implemented; the status history's timestamps are the only time record | Any timer, duration or labour figure |

## Manual QA sketch (Phase 6 writes the real one)

```text
Android QA — Technician field work

Sign in as the TECHNICIAN development account, with the API running and seeded, and a Job whose
represented Visit is assigned to that technician.

1. Home.
   Expected: the technician's assigned work for the day, and no manager-only condition or customer list.
2. Open an assigned Job.
   Expected: Job Details opens with the Job and its represented Visit; there is no Job status control,
   no reschedule, no crew management and no customer-management action.
3. Advance the Visit through the lifecycle the API reports.
   Expected: each applied step is shown, and a step the API refuses is reported with its own reason.
4. Complete the Visit with an outcome and a summary.
   Expected: the outcome is required, the completion is recorded once, and the Job's status is unchanged
   (BR-059).
5. Add a note.
   Expected: the note appears in Job Activity.
6. Turn airplane mode on and reopen Home and the Job.
   Expected: the technician's work and the Job are readable as the last reported answer, marked as such
   (BR-013).
7. If the field action is offline-capable, take one step offline and restore connectivity.
   Expected: the queued action is visible as pending, then applied exactly once after replay (BR-014).
8. Sign in as the MANAGER account and open the same Job.
   Expected: the manager's own actions are unchanged, and the technician's transition and outcome are
   visible in Job Activity and in the Visit's history.
```

## Phase log

Phase records are appended here when a phase lands, in the `qa.md` §16 format, and are never rewritten.

| Phase | Date | What landed | Files | Verification |
| --- | --- | --- | --- | --- |
| 1 | 2026-09-17 | **`ADR-019`, accepted by product ownership.** D1 (the Job read accepts `customers.view` **or** `VISIT_VIEW_ASSIGNED`, with a new any-of authorization primitive), D2 (a field caller reads only the Jobs their own current assignments reach; a Job it does not reach is `404`, never `403`), D3 (any currently-assigned technician may drive the Visit lifecycle), D4 (the minimal Visit → Job consequence mapping), D5 (the transition and the note are offline-capable, refuse-and-reconcile, never last-write-wins), D6 (the technician home's content is **not decided** — Phase 2b and Phase 4 stay gated), D7/D8 (recorded, not implemented). The domain model's §8.3 read-scope bullet and §8.7 consequence mapping record the two decisions that belong there. | `docs/decisions/019-technician-field-experience.md` (new), `docs/domain/job-visit-domain-model.md` (§8.3, §8.7, §9.1 pointer) | **Documentation only — nothing to execute.** Every decision names the rule it rests on, and the acceptance is the product-owner decision of 2026-09-17 (`Project.md` §31) |
| 2a | 2026-09-17 | **The field read's authorization and scope.** The any-of authorization primitive (`@RequireAnyPermission` with its own metadata key, the guard's two shapes with all-of unchanged, and a route that declares both **refused** rather than resolved by an invented precedence), and `GET /jobs/:id` / `GET /jobs/:id/activity` widened to `customers.view` **or** `VISIT_VIEW_ASSIGNED` with D2's assignment scope enforced in the service — a semi-join on the caller's own **current** `visit_technicians` rows, so a Job whose current crew does not include the caller is `404` and never `403`. The four `VISIT_*` codes are named in the API catalogue as `VISIT_PERMISSIONS`; they already exist in the database and are already granted to the default Technician role, so nothing is invented and **no migration is written**. | `api/src/auth/permissions.ts`, `permissions.decorator.ts`, `permissions.guard.ts`, `authorization.service.ts`, `api/src/jobs/jobs.controller.ts`, `api/src/jobs/jobs.service.ts`, `api/src/auth/permissions.spec.ts`, `api/src/auth/permissions.guard.spec.ts` (new), `api/test/job-details.e2e-spec.ts`, `api/test/job-activity.e2e-spec.ts`, `docs/api/job-details.md` §2/§4/§5, `docs/api/job-activity.md` §2/§4/§5, this tracker, `README.md` | Tier A plus the escalation `qa.md` §3.1 requires for a change to authorization. **Typecheck: PASS** (`npm run typecheck`). **API unit: PASS** (`npm test -- src/auth/permissions.guard.spec.ts src/auth/permissions.spec.ts` — 11 tests, including the new guard spec's 6 and the new `visit permissions` catalogue group). **API e2e: PASS** (`npm run test:e2e -- test/job-details.e2e-spec.ts test/job-activity.e2e-spec.ts` — 25 tests against the running foundation stack: the field caller's success on the same projection, an assignment that survived its Visit's completion, the unassigned `404`, the removed-crew `404`, a write-only capability refused with `403`, the office capability winning the scope when both are held, the cross-organization boundary, and the activity read's two cases). **Tier B (full lint, full suites, build) is due when this phase completes and is not claimed here.** No Android file was changed, no Gradle or Kotlin daemon was started, and **no `adb` or device/emulator command was run** (`qa.md` §7.3, §7.4) |

| 3 | 2026-09-17 | **The Visit field lifecycle (`BR-074`, `BR-075`, `BR-077`, `BR-078`; `ADR-019` D3–D5).** `PATCH /jobs/:id/visits/:visitId/status` guarded by `VISIT_UPDATE_ASSIGNED_STATUS`, with `VISIT_RECORD_OUTCOME` required on the `COMPLETED` destination — asked in its own place, because a route declares either an all-of or an any-of requirement and never both (`ADR-019` D1). The note route is reachable by `JOB_UPDATE` **or** `VISIT_ADD_NOTE` and the scope follows the capability that admitted the caller (the product-owner decision recorded below). **New** `VISIT_STATUS_TRANSITIONS`, `isVisitStatusCorrection`, `applicableVisitStatusTransitions` and `VISIT_OUTCOME_CODES` in `job.types.ts` (one definition, `BR-041`), and **new** `visit-job-consequence.ts` holding `jobStatusConsequenceForVisitTransition` — D4's mapping as a pure function, asserted over every status/outcome/eligibility combination. `JobsService.changeVisitStatus` validates the destination against the table, evaluates `BR-072`'s conditions as runtime eligibility (`VISIT_SCHEDULING_CONDITION_NOT_MET` with reason `PROPERTY`/`SCHEDULE`/`CREW_LEAD`, and `BR-070` conflicts reported and then confirmed), then applies the transition, its `visit_status_history` row, the outcome (`visit_outcome_history` **and** the Visit's own outcome columns) and the Job consequence in **one transaction**, deciding the transition against the Visit row taken `FOR UPDATE` so a queued command that arrives after the Visit moved on is refused with the state it actually holds. The Job row is read under its own lock in the same order, so two Visits of one Job cannot both record the same Job transition. Refusals are their own codes: `VISIT_STATUS_TRANSITION_NOT_ALLOWED` (carrying `allowed`), `VISIT_SCHEDULING_CONDITION_NOT_MET`, `VISIT_OPERATION_REUSED`, `JOB_CLOSED_FOR_FIELD_WORK` (a terminal Job fails closed rather than accepting field history — `BR-079`) and `VERSION_CONFLICT`. D5's replay is real: an operation the API already applied answers with the state it produced, a key reused for another Visit is refused, and without a key a repeat is refused with the Visit's real status (refuse-and-reconcile, `BR-014`). The Visit projection gained `allowedStatusTransitions`, so Phase 5 draws the technician's action from the server's own answer instead of a second copy of the lifecycle. `BR-061`'s two conditions were factored into one predicate so the completion's consequence and the Job status route cannot drift. **No migration**: every table and column already existed. | `api/src/jobs/job.types.ts`, `api/src/jobs/visit-job-consequence.ts` (new) and its spec (new), `job-action.dto.ts` and spec, `job-details.dto.ts` and spec, `jobs.service.ts`, `jobs.controller.ts`, `api/test/visit-field-lifecycle.e2e-spec.ts` (new), `docs/api/job-actions.md` §2/§7–§10, `docs/api/job-details.md` §2/§3.2, `docs/domain/job-visit-domain-model.md` (header, §9.1), `docs/architecture/offline-first-architecture.md` §12, `docs/decisions/019-technician-field-experience.md` (decision update), `README.md`, this tracker | Tier B, because the phase completes. **Typecheck: PASS** (`npm run typecheck`). **Lint: PASS** (`npm run lint` — 0 warnings, 0 errors). **API unit: PASS** (`npm test` — 437 tests in 49 files, including the new `visit-job-consequence` spec's 7 and the extended `job.types`, `job-action.dto` and `job-details.dto` specs). **API e2e: PASS** (`npm run test:e2e` — 299 tests in 19 files against the running foundation stack, including the new `visit-field-lifecycle.e2e-spec.ts`'s 23: `401`, `403` for a read-only and for an outcome-less caller, the `404` scope answer, the whole lifecycle with its history, every refused destination, `CANCELED`/`NO_SHOW` refused, completion without an outcome, all five `BR-078` outcomes with their Job consequences, `BR-075`'s correction, `BR-061` with another Visit open, replayed and stale operations, the operation-reused refusal, `BR-072`'s three conditions, a confirmed conflict, `BR-079` on a closed Job, `BR-059`, tenant isolation, and both note paths). **Build: PASS** (`npm run build`). No Android file was changed, no Gradle or Kotlin daemon was started, and **no `adb` or device/emulator command was run** (`qa.md` §7.3, §7.4) |
| 2b | 2026-09-17 | **`GET /home/technician`, once product ownership answered D6.** The read projection (`technician-home.dto.ts`) with its own derived sets — the next Visit (work under way first, then the nearest by schedule, chosen over the caller’s own open Visits), today’s own Visits chronologically, a capped upcoming preview with `upcomingTotal` beside it, and `VISIT_OVERDUE` as its one attention kind; the service scoping every query to the caller’s own current `visit_technicians` rows (`ADR-019` D2) with the tenant boundary and a live customer; the route guarded by `VISIT_VIEW_ASSIGNED`; and the contract in `docs/api/technician-home.md`. Three shared pieces were extracted rather than copied, so the two homes keep one definition each (`BR-041`): `home-day.ts` (the day window both reads resolve “today” through, and the `timeZone` parameter in `home-query.ts`), `home-visit-conditions.ts` (the attempts that did not happen, the started statuses and the overdue condition), and `home-viewer.ts` (the greeting’s name). `HISTORICAL_VISIT_STATUSES` joined `job.types.ts` as `BR-062`’s “open Visit” classification, so no read writes its own `NOT IN (...)` list. | `api/src/home/` (`home-day.ts`, `home-query.ts`, `home-visit-conditions.ts`, `home-viewer.ts`, `technician-home.dto.ts`, `technician-home.service.ts`, `technician-home.controller.ts`, `technician-home.module.ts`, their specs), `api/src/jobs/job.types.ts`, `api/src/app.module.ts`, `api/test/technician-home.e2e-spec.ts`, `docs/api/technician-home.md`, `docs/domain/job-visit-domain-model.md` §8.4, `docs/decisions/019-technician-field-experience.md` (D6 update) | **Tier B, because the phase completes.** **Typecheck: PASS** (`npm run typecheck`). **Lint: PASS** (`npm run lint` — 0 warnings, 0 errors). **API unit: PASS** (`npm test` — 445 tests in 51 files, including `technician-home.dto.spec.ts`’s 8, `home-visit-conditions.spec.ts`’s 4 and the moved day-window cases in `home-day.spec.ts`). **API e2e: PASS** (`npm run test:e2e` — 312 tests in 20 files against the running foundation stack, including the new `technician-home.e2e-spec.ts`’s 13: `401`, `403` for the office capability, the `400` for an unresolvable zone, the caller’s own day with a colleague’s Visit excluded, the `VISIT_VIEW_ASSIGNED` scope when the caller also holds `customers.view`, the empty screen for an unassigned technician, the fixed-offset zone, and — against a pinned clock — the chronological day, the started-Visit choice, the overdue condition and its place in attention, the capped preview, another technician’s / another tenant’s / deleted-customer work excluded, and a draft Visit left out). **Build: PASS** (`npm run build`). No Android file was changed by this phase |
| 4 | 2026-09-17 | **The Android technician home.** `Permission.VISIT_VIEW_ASSIGNED` and `canViewAssignedWork`; the Home destination choosing its content from capabilities (the manager home for `customers.view`, the technician’s own day otherwise), with its greeting coming from whichever home is on screen; `TechnicianHomeApi` / `Dtos` / `Result` / `Cache` / `Repository` with the working-set fallback and the subject scoping (`home.technician`); the domain model; `TechnicianHomeViewModel` and `TechnicianHomeUiState` (loading, refreshing, last-reported, failure with retry, nothing assigned); `TechnicianHomeScreen` in the product decision’s hierarchy (next Visit, today, upcoming, attention) over the shared primitives; `HomePresentation.kt`, extracted from the manager home so both screens greet, format a schedule and present a Visit’s status and the derived overdue condition the same way (`BR-041`); EN/FR copy; the device tests. **Not in this phase:** the status actions on the Home, which are Phase 5’s, so the next Visit’s action opens the Job (`BR-012`). | `android/app/src/main/java/` (`domain/auth/Permission.kt`, `domain/model/TechnicianHome.kt`, `data/home/*`, `data/offline/WorkingSetEntry.kt`, `di/DataModule.kt`, `di/NetworkModule.kt`, `ui/home/HomePresentation.kt`, `ui/home/TechnicianHome*.kt`, `ui/customers/CustomersScreen.kt`, `ui/customers/CustomerPermissionsUiState.kt`, `MainActivity.kt`, `ui/auth/AuthFlowScreen.kt`), `res/values*/strings.xml`, tests (`data/home/TechnicianHomeRepositoryTest.kt`, `ui/home/TechnicianHomeViewModelTest.kt`, `ui/home/TechnicianHomeScreenTest.kt`, `ui/navigation/ServoraHomeNavigationTest.kt`) | **Tier B for the phase.** **Compile: PASS** (`compileDebugKotlin`). **Unit: PASS** (`testDebugUnitTest` — 590 tests in 55 files, 0 failures, 0 errors; the new `TechnicianHomeRepositoryTest` 9 and `TechnicianHomeViewModelTest` 6). **Device-test sources: PASS** (`assembleDebugAndroidTest`; **no `adb` and no device command was run**, `qa.md` §7.3 — the new `TechnicianHomeScreenTest` and the field-session navigation test are the product owner’s to run). **Build: PASS** (`assembleDebug`). **Lint: PASS** (`lintDebug`) |

**Phase 2 is split, and the split is a consequence of D6 rather than a reduction of scope.** Phase 2's
first work item, `GET /home/technician`, would have to invent the technician home's content and order,
which no approved design defines (`Project.md` §2, `BR-042`), so it stays stopped until D6 is answered —
by an approved technician-home design, or by product ownership deciding that the technician home **is**
the manager home's today list restricted to the caller's own assignments. The Job read half (2a) does not
depend on it, and it is the half that stops a technician session being refused Job Details altogether.

**Three things Phase 3 decided explicitly, because the approved plan left a gap between two documents.**

1. **The note route's authorization was decided by product ownership on 2026-09-17**, not by the
   implementation. Phase 3's work item 5 ("`VISIT_ADD_NOTE`, with the same assignment scope") would have
   taken away the Manager's shipped *Write note* in the Add-update sheet
   (`docs/tracker/028-android-job-update.md`, `JobUpdateSheet.kt`), and `ADR-019` D7 itself states that a
   Manager granted the field capabilities can already use the field routes — which the crew scope would
   prevent. The route therefore accepts `JOB_UPDATE` **or** `VISIT_ADD_NOTE`, and the scope follows the
   capability that admitted the caller, which is `ADR-019` D2's own shape. Consequence to carry forward:
   the **status/outcome route stays field-only**, so a Manager reaches it only by holding the field
   capability *and* being on the Visit's current crew; the office half is still `ADR-019` D7's open
   question and is neither implemented nor blocked.
2. **`BR-072`'s conditions are enforced as runtime eligibility** for every destination of `SCHEDULED`,
   the `BR-075` correction included. The plan lists `DRAFT → SCHEDULED` among the transitions this route
   applies, and applying it without those conditions would have broken `BR-072` — or been refused by
   `visits_scheduled_requirements_check` as a 500. The destination is offered and refused with its own
   reason, exactly as `BR-061`/`BR-062` refuse a Job destination (`BR-058`'s split between structural
   transitions and runtime conditions).
3. **The conflict policy is D5's refuse-and-reconcile** and both routes carry a `clientOperationId`, so
   `docs/architecture/offline-first-architecture.md` §12 moves the Visit transition and the Visit note
   out of its online-only list into adopter 8 — recording at the same time that the **Android** adoption
   is Phase 5, so the standard never claims more than the code does.

What Phase 3 did **not** decide: whether the office may perform a field action (`D7`), a paused Visit
state (`D8`), outcome corrections (`BR-079`'s undefined window), and the cancellation reason catalogues
(`BR-064`, `BR-076`). Each stays open and none is approximated.

## Risks and limits

- **D1 changes how an existing route is authorized.** If it lands as A1, the guard change is
  cross-cutting (two routes plus an authorization primitive) and is verified as Tier B, not Tier A
  (`qa.md` §3.1).
- **D4 is a business decision, not a technical one.** Implementing a Visit→Job consequence before it is
  decided would be inventing behaviour (`BR-042`) and would silently change what a manager sees on the
  manager home, whose attention conditions read those very statuses (`BR-060`, `BR-061`).
- **The design gate was real, and it is closed by a decision rather than by a guess.** Phase 4 waited
  until product ownership said what the technician home is (`ADR-019` D6 update, 2026-09-17); a home
  invented in code before that is exactly the invented UX these rules forbid.
- **A technician's session used to be a dead end:** it landed on Home, the manager read was refused, and
  the Customers tab was hidden. Phase 4 replaces that with the technician's own day, and Phase 6
  should confirm the change on a device so the before/after is visible.
- **Out of this slice on purpose:** follow-up requests, office-side Visit actions, outcome corrections,
  notifications, time tracking and GPS. Each is recorded above rather than implemented.

## Definition of Done for this slice

Per `Project.md` §32 and `qa.md` §15, at the tier each phase requires:

- `ADR-019` exists and every decision it records names the rule it rests on.
- The assigned-work read and the field Job read are authorized by the capability the ADR decided, with the
  scope enforced server-side (`BR-001`, `BR-007`).
- The Visit lifecycle route applies only `BR-074`'s transitions and `BR-075`'s correction, records every
  applied transition once, and completes only with an outcome (`BR-067`, `BR-077`).
- A technician's Android session has an entry point and can read, act on, and — where the ADR decided it —
  queue work for offline replay, with EN and FR copy for every new string.
- No `OPEN QUESTION` was implemented as an assumption, and no business rule was changed without a `BR-040`
  record.
- The full lint, suites and builds of every affected application pass when a phase completes, the
  device-test sources compile, the build daemons the task started are stopped (`make android-stop`), and
  the workspace holds only the intended changes.
- Physical-device QA is reported as the product owner's, never claimed.

