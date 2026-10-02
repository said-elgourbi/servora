# Tracker 051 - Job and Visit lifecycle redesign

**Status: NEW - documentation and repository audit first; do not implement application code yet**

Date: 2026-09-20

This tracker supersedes existing Job and Visit lifecycle rules where they conflict. Its first task is to
make the canonical documentation and business rules internally consistent before any API, database,
Android, projection or test implementation changes are made.

## Directive

Do not preserve an old rule merely because it already exists. Identify the old rule, mark it obsolete
or amend it, and leave one source of truth for implementation.

Do not invent additional statuses, workflows, attention states or reason catalogues. Derived office
attention is not lifecycle state.

## Canonical lifecycle vocabulary

### Job status

| Status | Meaning |
| ------ | ------- |
| `NEW` | The Job exists but has not entered execution or scheduling. A persisted/internal draft Visit must not make the Job active by itself. |
| `ACTIVE` | The customer request is open and has entered execution. The first real scheduled/actionable Visit makes the Job active. This does not mean a technician is physically working right now. |
| `COMPLETED` | The customer request has been resolved. A resolving Visit completion moves the Job here automatically. There is no Manager pending-review step. |
| `CANCELED` | The customer request was canceled rather than resolved. Canceling the Job cancels non-terminal/open Visits and leaves completed historical Visits intact. |

### Removed Job status

`PENDING_REVIEW` is no longer part of the Job lifecycle.

Remove or migrate every assumption involving:

- Job `PENDING_REVIEW`.
- technician completion causing `PENDING_REVIEW`.
- Manager approval required to complete a Job.
- UI filters, counts or schedule/home buckets based on `PENDING_REVIEW`.
- transition matrices involving `PENDING_REVIEW`.
- tests, docs, API projections and seed data that use it.

### Visit status

| Status | Meaning |
| ------ | ------- |
| `SCHEDULED` | A real actionable Visit exists. |
| `EN_ROUTE` | The technician is travelling for this Visit. |
| `ON_SITE` | The technician is at the site for this Visit. |
| `IN_PROGRESS` | The technician is actively working on this Visit. |
| `COMPLETED` | The Visit attempt is finished through the explicit completion action. |
| `CANCELED` | This Visit attempt was canceled. |

`NO_SHOW` is no longer a Visit lifecycle status.

`DRAFT` must be audited before removal. If it is persisted and required by an existing creation or
scheduling workflow, report that before changing it. It must never be exposed as a technician working
state and must not activate a Job.

### Technician working states

For an actionable `ACTIVE` Job, an authorized assigned technician may directly select any of:

- `SCHEDULED`
- `EN_ROUTE`
- `ON_SITE`
- `IN_PROGRESS`

No linear transition requirement is allowed. These transitions must be valid and auditable:

- `SCHEDULED` -> `IN_PROGRESS`
- `IN_PROGRESS` -> `ON_SITE`
- `ON_SITE` -> `EN_ROUTE`
- `EN_ROUTE` -> `SCHEDULED`

`COMPLETED` and `CANCELED` are not selected through the normal technician working-status selector.

## Visit completion outcomes

Completing a Visit is an explicit business action and requires an outcome. A Visit cannot become
`COMPLETED` through the ordinary status selector.

| Outcome | Effects |
| ------- | ------- |
| `RESOLVED` | Visit -> `COMPLETED`; Job -> `COMPLETED`. |
| `NEEDS_FOLLOW_UP` | Visit -> `COMPLETED`; Job -> `ACTIVE`; if no future/actionable Visit exists, derive office attention indicating follow-up needs scheduling. |
| `NEEDS_PARTS` | Visit -> `COMPLETED`; Job -> `ACTIVE`; derive office attention indicating parts are required; do not automatically create another Visit; absence of a future Visit is not itself an error while parts are required. |
| `UNABLE_TO_COMPLETE` | Visit -> `COMPLETED`; Job -> `ACTIVE`; a reason is required; derive appropriate office attention from that reason; do not automatically create another Visit. |

Do not invent a large reason catalogue yet. If the repository has no approved catalogue for
`UNABLE_TO_COMPLETE`, record the need as a product decision and keep implementation blocked on that
specific choice.

## Derived attention is not Job status

Do not create Job statuses such as:

- `NEEDS_SCHEDULING`
- `NEEDS_PARTS`
- `OVERDUE`
- `UNASSIGNED`
- `FOLLOW_UP_REQUIRED`

The separation to preserve is:

| Concept | Question it answers |
| ------- | ------------------- |
| Job status | Is the overall customer request open, resolved or canceled? |
| Visit status | Where is this particular field attempt operationally? |
| Visit outcome | What happened when this Visit ended? |
| Attention | What does the office need to act on? |

## Cancellation and read-only rules

Canceling a Job:

- moves the Job to `CANCELED`;
- cancels every non-terminal/open Visit;
- preserves historical `COMPLETED` Visits;
- records actor, time and reason according to existing audit conventions;
- prevents further technician field mutations.

For a `CANCELED` Job, technicians cannot change Visit status, complete a Visit, add text updates, add
photos, add audio, or add other Visit evidence. Existing history and evidence remain readable.

A `COMPLETED` Job is also historical/read-only for technician field mutations. Additional work requires
the Job to be explicitly reopened through the approved office workflow.

Server-side enforcement is required. Android-only hiding is insufficient.

## Visit-scoped field updates

Technician-created field updates from Job Details require a current/actionable Visit and must carry
`visitId`. This includes:

- text notes;
- photos;
- future audio;
- future field evidence.

## Required repository audit

Before implementation, inspect and record every affected item in these areas:

| Area | What to find |
| ---- | ------------ |
| Business rules | Every rule defining Job status, Visit status, completion, reopen, cancellation, field evidence, assignment scope, derived attention and audit behaviour. |
| ADRs | Decisions that mention `PENDING_REVIEW`, manager approval, Visit completion consequences, field actions, schedule/follow-up requests, evidence and offline replay. |
| Existing trackers | Trackers that implemented or planned lifecycle behaviour: manager status workflow, technician field experience, Job Details presentation, activity grouping, Visit outcomes, create Job, schedule, follow-up requests and evidence. |
| API contracts | `job-actions`, `job-details`, `job-activity`, `manager-home`, `technician-home`, `schedule`, `visit-requests`, `job-photos` and `job-audio`. |
| Enums and validation | API and Android status/outcome enums, DTO validators, parsers, type guards, refusal codes and localized labels. |
| Database | `jobs.status`, `visits.status`, checks, migrations, Drizzle schema, snapshots, seed data and fixture builders. |
| Projections | Home counts, schedule lanes, Job Details, Job Activity, technician home, attention signals and offline working-set DTOs. |
| Permissions | Job status changes, Visit working status changes, Visit completion, notes, photos, audio, schedule creation and field evidence gates. |
| Android | Job Details ViewModel/screen, status selectors, completion sheet, Add update, photo/audio evidence, Manager Home, Technician Home, Schedule and offline outbox handlers. |
| Tests | Unit, e2e, Android JVM and Compose tests that pin old transitions, old statuses, pending-review flow, no-show flow, read-only behaviour or status counts. |
| Documentation | Domain model, API docs, design docs, README current-state notes and obsolete tracker statements. |

## Documentation phase

1. Update `Business Rules.md` to the new lifecycle vocabulary.
2. Update `docs/domain/job-visit-domain-model.md` to remove contradictory status tables and derived-state wording.
3. Amend or supersede ADRs that enshrined `PENDING_REVIEW`, manager approval, linear Visit transitions or `NO_SHOW`.
4. Update API documentation to the new contracts before code changes.
5. Add explicit obsolete-rule notes in trackers whose implemented behaviour is being replaced.
6. Record open product decisions rather than silently deciding them.

## Implementation phases

| Phase | Status | Scope |
| ----- | ------ | ----- |
| 1. Repository audit and canonical docs | **DONE** (2026-09-20) | `Business Rules.md` states the four-status Job lifecycle and the `NO_SHOW`-free Visit lifecycle, records `BR-061` as **REMOVED** and carries every changed decision under `BR-040`; `docs/domain/job-visit-domain-model.md` §8 and §9 are rewritten around the canonical model; `ADR-019` is amended and `ADR-020` corrected; the API docs were brought in line. Trackers whose prose describes the retired vocabulary carry a superseded-vocabulary note. |
| 2. API and database lifecycle migration | **DONE** (2026-09-20) | Statuses, constraints, DTO validation, service transitions, consequences, projections, refusal codes, the development seed, the unit specs and the full e2e suite. 23 e2e files / 387 tests pass. |
| 3. Android lifecycle and offline behaviour | **DONE for vocabulary** (2026-09-20) | The Job Details Visit controls, their offline behaviour, the Android vocabulary they read, the Manager Home attention vocabulary, and the Schedule and Technician Home surfaces were verified: every Android status, outcome, attention and scope enum matches the API's, and each mapper fails closed on a code it does not know. No Android source was changed by the consistency sweep. |
| 4. Verification and cleanup | **IN PROGRESS** | Full affected API verification is done (unit 58 files / 542 tests, e2e 23 files / 387 tests, typecheck, build). **Android automated verification and physical-device QA are the product owner's**: no Android source changed, and the product owner asked that the machine not be loaded with Gradle. The manual QA runbook is below. |

### Consistency sweep (2026-09-20)

The sweep that closed Phases 1–3 re-ran every affected suite and repaired the contradictions it found:

- **The retired vocabulary is gone from source, tests and authoritative documentation.** `PENDING_REVIEW`
  and `NO_SHOW` survive only where they are recorded as **removed**: the `BR-061` historical entry, the
  `BR-040` changed-decision notes, the migration's data treatment, the regression tests that assert an
  unknown code is refused, and the notes this sweep added to superseded trackers and ADRs.
- **`BR-062`'s invariant is restored on the consequence path.** The redesign's Job consequence completed
  a Job on a resolving Visit completion without asking whether another Visit was still open, so a
  technician could close a Job that still had scheduled work — a state the explicit status route refuses
  and a **CONFIRMED** rule forbids. `jobStatusConsequenceForVisitTransition` now takes
  `hasOtherOpenVisit`, `visit-job-consequence.spec.ts` covers it, and §8.7 of the domain model and §7.3 of
  `docs/api/job-actions.md` state it.
- **A pre-existing defect outside the redesign was found and fixed**: `schedule-scope.spec.ts` asserted
  `customers.view` widens a schedule read, while `resolveScheduleScope` had required `schedule.view_org`
  since that capability was created. `docs/api/schedule.md` §2 said `customers.view` too. Both now state
  the capability the API enforces.
- **`docs/api/job-actions.md` §2** listed `JOB_UPDATE` for the reschedule and crew routes, which had been
  guarded by `visits.update_schedule` and `visits.assign_technicians`; the table now matches the
  controller.

### Phase 1 — what landed

1. `Business Rules.md`: BR-058 (four statuses and their transitions), BR-060 (the derived signal's Job
   statuses and what attention is), BR-061 (**REMOVED**), BR-062 (the two ways a Job is closed and the
   open-Visit invariant binding both), BR-063 (`ACTIVE` as the reopen destination), BR-064, BR-066,
   BR-074 (`NO_SHOW` removed, completion as its own operation, the reopen left open), BR-075, BR-076,
   BR-078 (the four-code catalogue), BR-083 and BR-093. Each changed decision is recorded under `BR-040`.
2. `docs/domain/job-visit-domain-model.md` §8 and §9 rewritten: the status vocabularies, the transition
   table, the runtime-eligibility table, the derived-attention table, closing and reopening, the
   cancellation rules, the Visit lifecycle and the Visit → Job consequence table. The retired names no
   longer appear anywhere in the document.
3. `ADR-019` carries an amendment note on D4; `ADR-020`'s unassigned-lane query is corrected; seven
   trackers carry a superseded-vocabulary note.
4. Every open decision this tracker listed is either answered or recorded as still open in the sections
   below, and none was silently decided.

### Phase 3 — what landed (Android, 2026-09-20)

The technician Job Details Visit controls, the interaction and the offline behaviour behind them:

- **The Visit status dropdown is gone.** The four technician working states — `SCHEDULED`, `EN_ROUTE`,
  `ON_SITE`, `IN_PROGRESS` — are drawn directly as a compact `FilterChip` row, the state the Visit holds
  is the selected chip, and a chip is tappable only when the API's own `allowedStatusTransitions`
  reported it for this Visit. One tap is one operation, forwards or backwards; no transition is forced
  through a sequence (`BR-074`, `BR-075`).
- **Completion is its own action, not a chip.** `COMPLETED` is absent from the row; the Visit card
  carries a primary `Complete visit` action gated on the API's `selectedVisit.completionAllowed`, and it
  opens a sheet that requires the outcome (`RESOLVED`, `NEEDS_FOLLOW_UP`, `NEEDS_PARTS`,
  `UNABLE_TO_COMPLETE`), states one line of help per outcome, and asks for the required text as a
  **reason** when the Visit could not be completed and as a **summary** otherwise. Submitting calls
  `POST /jobs/:jobId/visits/:visitId/completion` (`BR-077`, `BR-078`).
- **The status route no longer carries an outcome** from the client: `PATCH .../status` sends the
  destination, the idempotency key, the device instant and the Visit version, and nothing else.
- **`CANCELED` is not among the technician's choices** and is not a destination of the selector at all
  (`BR-066`, `BR-076`). Cancelling remains an office action.
- **A closed Job is read-only, from the API's own answer.** `JobDetails.readOnlyReason` gates every field
  control: the status selector, the completion action and Add update disappear, and a `CANCELED` Job is
  stated in a prominent notice because a cancellation materially changes the work the technician was
  sent to do (`BR-062`, `BR-079`).
- **Add update is Visit-scoped.** The action exists only when the API reports
  `selectedVisit.addUpdateAllowed`, which is what keeps a technician from creating work under a Job the
  office canceled or completed while they were offline.
- **Offline.** A working transition and a completion are both queued when the API cannot be reached and
  replayed with the same idempotency key and the version the technician saw (`ADR-019` D5); the replay
  picks the API route its recorded destination belongs to, so a row an earlier build queued for a
  completion still replays with the meaning it was made with. A `409 JOB_CLOSED_FOR_FIELD_WORK` answer is
  now classified as its own failure reason (`JOB_CLOSED`) rather than as a stale version, and the replay
  engine emits a **refusal** signal beside its applied one, so a screen that is open re-reads the Job and
  stops describing a refused action as saved on the device (`BR-014`, `BR-032`).
- **Vocabulary.** The Android `JobStatus` (`NEW`, `ACTIVE`, `COMPLETED`, `CANCELED`), `VisitStatus`
  (no `NO_SHOW`) and `VisitOutcome` (the four canonical codes) match the API's, with the bilingual
  labels and the refusals the API can still produce.

### Phase 3 — what landed (Manager Home, 2026-09-20)

- **The attention vocabulary is the API's own.** Android's `ManagerAttentionKind` is `VISIT_OVERDUE`,
  `JOB_NEEDS_SCHEDULING`, `FOLLOW_UP_NEEDS_SCHEDULING`, `PARTS_REQUIRED` and `UNABLE_TO_COMPLETE`; the
  retired `JOB_PENDING_REVIEW` is gone, and `home_attention_review_title` with it.
- **This was not cosmetic.** The read fails closed on a code it does not know (`BR-042`), so a Manager
  Home that did not carry the three outcome-derived kinds failed **entirely** — the whole day, not one
  row — as soon as a `NEEDS_FOLLOW_UP`, `NEEDS_PARTS` or `UNABLE_TO_COMPLETE` completion derived one.
  The kind vocabulary now matches `docs/api/manager-home.md` and `api/src/jobs/job-attention.ts`.
- **`reasonCode` is deliberately not modelled.** The API reports it as `null` for every condition it
  derives today, because no structured reason catalogue is approved; the client stores no field for it
  and invents no reason vocabulary (`BR-042`). It is recorded in the model's own KDoc.
- Each kind is named in English and French, and every kind is covered by a test: the repository test
  pins the three codes against the mapper, and the Compose test pins their labels.

### Phase 3 — closed

The Schedule and Technician Home surfaces were verified against the new vocabulary and the attention
codes, and no per-surface copy still speaks of a pending review. `TechnicianScheduleScreen` emphasises the
API's own status on a muted surface for a completed Visit and marks the viewer's own crew from
`viewerMembershipId`; canceled Visits never reach the client because the API excludes them from the day
(`NOT_DAY_WORK_VISIT_STATUSES`); the technician-home derivation test no longer fabricates a `NO_SHOW`
attempt. Manager Schedule omits the dispatch alternative from the schedule projection by status, so no
obsolete state was filtered out by name either.

### Phase 2 — closed

- **The API e2e specs are through the new vocabulary.** The retired lifecycle assertions are gone from
  `job-actions.e2e-spec.ts`, `visit-field-lifecycle.e2e-spec.ts`, `manager-home.e2e-spec.ts`,
  `customer-detail-projection.e2e-spec.ts`, `schedule.e2e-spec.ts`, `technician-home.e2e-spec.ts` and the
  remaining files whose fixtures inserted a retired Job status (`customers.e2e-spec.ts`,
  `properties-lifecycle.e2e-spec.ts`, `visit-requests.e2e-spec.ts`, `job-activity.e2e-spec.ts`).
  **Measured on 2026-09-20 after the sweep:** the full suite is **23 files / 387 tests, all pass** (it was
  93 failed / 294 passed).
- **The development seed is through the new vocabulary.** `development-seed-scenarios.ts` declares the
  canonical Job, Visit and outcome types; `VISIT_STATUS_PATHS` and `JOB_STATUS_PATHS` walk sequences the
  lifecycle really produces; the transitional `canonicalSeed*` mappers in `run-development-seed.ts` are
  deleted; and the dataset's own coherence assertions now require `NEW`/`ACTIVE` agreement with the Visits
  a Job holds. The one seeded attempt that became `CANCELED` carries the structured reason `BR-076`
  requires.
- **`BR-064`/`BR-065` are no longer open in the API.** `Business Rules.md` and
  `docs/domain/job-visit-domain-model.md` §8.6 state what the API does — cancellation is a destination of
  the Job lifecycle, it cascades to the Job's open Visits in the same transaction, and the request's
  optional note is the only explanation recorded while the **structured** reason catalogue stays an open
  question. `BR-065`'s per-Visit confirmation remains a client-presentation question and is recorded as
  open in `docs/api/job-actions.md` §10.

### Decisions recorded with this phase

| Decision | Where it lives | Why it is not an accident |
| -------- | -------------- | ------------------------- |
| A **closed Job keeps the Visit's structural `allowedStatusTransitions`**; the closed fact travels separately in `readOnlyReason`, which is what hides the field controls. | `docs/api/job-actions.md` §7.2, `docs/api/job-details.md` §3, `api/src/jobs/job.types.ts` | The list answers *which destinations a Visit has* — narrowed only by what the caller may execute (`BR-093`) — and never *whether the Job is open*. Narrowing it for a closed Job would express runtime eligibility by removing a structurally valid destination, which `BR-058`/`BR-074` forbid; the API refuses those writes with `JOB_CLOSED_FOR_FIELD_WORK` and the client hides the controls from `readOnlyReason`. |
| **Photo and audio uploads classify a refusal by the API's stable code**, so a `409 JOB_CLOSED_FOR_FIELD_WORK` is reported as a closed Job rather than as a stale version. | `android/.../UploadFailureReason.kt`, both upload handlers, `docs/api/job-photos.md` §5, `docs/api/job-audio.md` §5 | A `409` alone cannot tell the two apart, and they need different words: "the office closed the job" is not "someone else changed it, re-read and try again". The row and the local bytes are kept either way (`BR-014`, `BR-032`). |
| **A working-set row cached by an earlier build holds retired status codes.** | `docs/architecture/offline-first-architecture.md` (the contract-mismatch rule), `BR-042` | The client fails closed on a code it cannot read, so offline such a row is treated as **no answer** until the API is reachable again. Nothing is migrated on the device and nothing is guessed; recording it here is what makes that a decision rather than an oversight. |
| **The API docs were brought in line on 2026-09-20.** | `docs/api/job-actions.md` (rewritten around the new model, completion as its own route, §10 open questions), `docs/api/job-photos.md`, `docs/api/job-audio.md` | The tracker's own documentation phase requires the API contract to describe what the API does; the retired `PATCH .../status` outcome payload, `PENDING_REVIEW`/`NO_SHOW` and the removed refusal codes are gone, and the stale code comments that asserted them (`job.types.ts`, `jobs.controller.ts`, `job-details.dto.ts`, the home and schedule reads) were corrected with them. `HISTORICAL_VISIT_STATUSES`, a second name for `TERMINAL_VISIT_STATUSES`, was removed so one set has one definition (`BR-041`). |

## Decisions this tracker recorded

| Decision | Status after the sweep |
| -------- | ---------------------- |
| Whether persisted `DRAFT` Visits remain | **Answered by the audit.** None was removed: `DRAFT` stays a persisted, internal status. No API path creates one — the supported create operation schedules the Visit it creates — so a `DRAFT` is an attempt that was never scheduled. It has **no** destination in the field status table, it never activates a Job, and no client presents it as a technician's working state. `BR-074` states this and the seed still exercises it. |
| Reopening `COMPLETED` or `CANCELED` Jobs | **Answered: `ACTIVE`.** `BR-063` and `BR-058` now say `ACTIVE`, with the changed decision recorded under `BR-040`; the API applies it and the e2e suite pins it. |
| `UNABLE_TO_COMPLETE` reason catalogue | **Still open** (`BR-078`). The reason is free text and the derived attention reports `reasonCode: null`. |
| Shape of derived office attention | **Still open** (`BR-060`, `BR-078`). The codes are implemented and pinned; the *reason* a condition carries (`reasonCode`) has no catalogue and is reported as `null`. |
| Migration treatment for historical `PENDING_REVIEW` and `NO_SHOW` rows | **Answered by migration `0016`**: a Job that was `PENDING_REVIEW` with no open Visit and a resolving latest outcome becomes `COMPLETED`, any other `PENDING_REVIEW` (and `SCHEDULED`/`IN_PROGRESS`) becomes `ACTIVE`, `NO_SHOW` becomes `CANCELED` with the reason `OTHER`, `NEEDS_FOLLOWUP` becomes `NEEDS_FOLLOW_UP`, and `NEEDS_QUOTE_APPROVAL` **aborts the migration** rather than being mapped, because no approved canonical mapping exists for it. History rows are migrated with the current state and their note records that the collapse happened, so the row is preserved rather than rewritten. |
| Reopening a `COMPLETED` Visit | **Still open** (`BR-074`). The rule permits the turn; the API gives `COMPLETED` no destination and offers no route, so no client may invent it. |

## Verification note

The sweep's own verification is reported in the completion summary and is scoped by `qa.md` §3.1: the
affected API application at **Tier B** (full typecheck, the full unit suite, the full e2e suite and the
build). **Android is not verified by the agent**: no Android source changed and the product owner asked
that the machine not be loaded with Gradle, so no `android-lint`, `android-test` or
`assembleDebugAndroidTest` was run. The commands the product owner would run are:

```bash
cd android && ./gradlew testDebugUnitTest
cd android && ./gradlew lintDebug
cd android && ./gradlew assembleDebugAndroidTest
make android-stop
```
