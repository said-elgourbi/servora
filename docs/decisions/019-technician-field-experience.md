# ADR-019 — The technician's field experience: the field read, the Visit lifecycle and its offline posture

**Status:** Accepted (product-owner decision, 2026-09-17: the recommended set below was proposed by the
agent and accepted by product ownership in the same session, as `Project.md` §31 requires)

Date: 2026-09-17
Tracker: `docs/tracker/037-technician-field-experience.md` — Phase 1. **Every other phase of that slice
implements one of these answers**, which is why no other phase may start before this record exists.
Contracts: `docs/api/job-details.md` §2 and `docs/api/job-activity.md` §2 (both re-guarded by D1),
`docs/api/job-actions.md` (a new Visit section, D3–D5), `docs/api/technician-home.md` (new, once D6 is
answered)
Predecessors: `docs/decisions/014-android-offline-engine.md` (the engine D5 adopts),
`docs/decisions/012-property-lifecycle-and-permissions.md` D7 (the offline standard D5 obeys),
`docs/decisions/015-evidence-capabilities.md` (the per-capability pattern D1 follows, and the two evidence
capabilities a technician already holds), `docs/decisions/011-android-contextual-top-bar.md` (the shell a
technician lands in)

References: `BR-001`, `BR-004`, `BR-006`, `BR-007`, `BR-009`, `BR-010`, `BR-011`, `BR-012`, `BR-013`,
`BR-014`, `BR-028`, `BR-031`, `BR-032`, `BR-033`, `BR-040`, `BR-041`, `BR-042`, `BR-058`, `BR-059`,
`BR-060`, `BR-061`, `BR-062`, `BR-066`, `BR-067`, `BR-068`, `BR-069`, `BR-070`, `BR-072`, `BR-074`,
`BR-075`, `BR-077`, `BR-078`, `BR-079`, `BR-080`, `BR-086`, `BR-088`; `Project.md` §13–§15, §19, §31;
`dev.md` §7, §9, §10; `qa.md` §3.1, §4.3, §8, §13;
`docs/architecture/offline-first-architecture.md` §2, §5, §8, §11, §12, §13;
`docs/domain/job-visit-domain-model.md` §8.3, §9.1, §9.5, §11.1, §14, §15.

## Context

`BR-009` names the technician's capabilities and they already exist: migration
`0004_woozy_spitfire` creates `VISIT_VIEW_ASSIGNED`, `VISIT_UPDATE_ASSIGNED_STATUS`, `VISIT_ADD_NOTE` and
`VISIT_RECORD_OUTCOME` (`api/drizzle/migrations/0004_woozy_spitfire.sql:75-78`) and grants exactly those
four to the default Technician role (`:104-118`). The development seed mirrors the grants
(`api/src/database/run-development-seed.ts:173-194`, `:262-265`).

What does not exist is any enforcement or any client use of them:

- `GET /jobs/:id` and `GET /jobs/:id/activity` are guarded by `customers.view`
  (`api/src/jobs/jobs.controller.ts:121`, `:154`), which the default Technician role does not hold, so a
  technician session is **refused** and cannot open Job Details at all.
- The Visit note route is guarded by `JOB_UPDATE` (`jobs.controller.ts:269`), so the member who holds
  `VISIT_ADD_NOTE` cannot add a note.
- The Visit field lifecycle (`BR-074`), the one correction `BR-075` defines and the outcome completion
  requires (`BR-077`, `BR-078`) are schema and rules only: `visit_status_history` (with `is_correction`,
  `reason_code`, `cancellation_source`, `job_status_history_id`, `captured_at` and the unique partial
  `client_operation_id` index), `visit_outcome_history`, `visits.version` and
  `visits_completed_outcome_check` all exist from migration `0003`.

Three groups of questions follow, and all three are **product** questions the business rules leave
undefined, so none of them may be answered by the implementation (`BR-042`):

1. **The read.** Which capability authorizes a field caller's Job read, and how much of a Job may that
   caller read? `BR-009` names "view assigned Visits", which is narrower than the Job projection the
   screen is built on (D1, D2).
2. **The write.** Who on a crew may drive the Visit lifecycle, and what does a Visit event do to the
   Job's status? `BR-009` grants the four capabilities to the role without qualification, `BR-068` says
   every assigned technician is booked for the whole window, and `BR-058` says the Job "advances as a
   consequence of Visit lifecycle events" without naming which ones (D3, D4).
3. **The offline posture.** `BR-013` lists starting, pausing and completing work and adding notes as
   offline-capable field work, while the offline standard's §13.2 only admits a mutation to the outbox
   when its conflict policy is decided — and `BR-032` forbids inventing one (D5).

One technical constraint shapes D1 rather than being decided by it: `@RequirePermissions` is **all-of**.
`PermissionsGuard` resolves a membership holding *every* listed code
(`api/src/auth/permissions.guard.ts:47-55`, `api/src/auth/authorization.service.ts:35-38`), so "a manager
**or** a technician" cannot be expressed by passing two codes today. Whatever D1 answers, that gap has to
be closed deliberately.

## Decisions

### D1 — The Job read accepts either capability, and the all-of gap is closed by an any-of primitive

**Accepted: option A1** of tracker 037's D1 table.

`GET /jobs/:id` and `GET /jobs/:id/activity` accept **`customers.view` or `VISIT_VIEW_ASSIGNED`** — the
first code for the office caller who already reads every Job of the organization, the second for the
field caller `BR-009` names. The projection, the field names and the error vocabulary are **unchanged**:
one Job, one wire contract, one client parser (`BR-041`, `BR-010`).

The missing primitive is added rather than worked around:

- `@RequireAnyPermission(...codes)` contributes its own metadata key, and `PermissionsGuard` answers it
  by resolving the first `ACTIVE` membership holding **at least one** of the codes, through a new
  `AuthorizationService` method beside the existing all-of one. Memberships keep their existing
  `joinedAt` order, so the two resolutions pick the same membership when both would qualify.
- **A route declares either an all-of requirement or an any-of one, never both.** A route that needs two
  questions answered asks the second one in its own service, which is exactly what the activity read's
  `includeRemovedEvidence` flag already does (`jobs.controller.ts:156-166`). Declaring both is refused
  instead of being resolved by a precedence rule no product decision defines (`BR-042`).
- All-of stays the default, so no existing route changes behaviour: `@RequirePermissions` keeps meaning
  "holds every code".

**Why not A2 — a new `jobs.view.assigned` code.** It would invent a capability the Jobs feature has not
defined (`BR-006`, `BR-042`) and needs a catalogue migration plus a `BR-040` record, in order to ask a
question the catalogue already answers: `VISIT_VIEW_ASSIGNED` is the capability `BR-009` names for a
technician's read of their assigned work, it is already granted to the default Technician role, and it is
enforced by no route today.

**Why not A3 — a separate field read over a second projection.** Two contracts for one Job would drift,
which `BR-041` exists to prevent, and the Android Job Details screen would need a second parser for the
same screen. It is the recorded fallback if product ownership ever decides that `GET /jobs/:id` must stay
office-only; nothing in this decision prevents it later.

The read's authorization stays a server decision: widening a guard never moves a business question into a
client (`BR-001`, `BR-007`).

### D2 — A field caller reads the Jobs they are assigned to, and nothing else

The scope is **the caller's own current assignments**, enforced in the service, not in the guard.

- The **assigned-work list** (Phase 2's `GET /home/technician`, whose *content* is D6's answer) is the
  caller's own Visits: `visit_technicians` rows whose `technician_membership_id` is the caller's
  membership. "Assigned" needs no further definition, because assignment is current state with no
  acceptance step of its own (`docs/domain/job-visit-domain-model.md` §10.2, `BR-068`, `BR-069`).
- The **Job read** answers for a Job when the caller's membership is on the current crew of **at least one
  of its Visits** — including a Visit that has already completed, because a completed assignment remains
  a current assignment until a manager removes it (`BR-069`, §10.2). A Job whose current crew does not
  include the caller is reported as **not found (`404`), never forbidden (`403`)**, exactly as a Job of
  another organization is (`docs/api/job-details.md` §4), so a Job id cannot be probed for existence.
- The activity read carries the same scope, because it is the same Job's records (`BR-080`). Its
  `includeRemovedEvidence=true` context still requires an evidence removal capability, which the default
  Technician role does not hold — unchanged (`BR-089`, `ADR-018` A7).
- An office caller is unaffected: the scope is applied **only** to a caller who does not hold
  `customers.view`, so `GET /jobs/:id` keeps returning what it returns today for a manager. Nothing about
  `GET /customers/:id/jobs` or `GET /home/manager` changes here.

**The disclosure trade-off, stated rather than left to be discovered.** This is *not* "the caller's own
Visits only": a technician assigned to one Visit of a Job reads the whole Job projection, so they can see
the Job's other Visits, who else is on them and the Job's activity (including other members' notes).
The alternative — a field caller sees only their own assigned Visits inside the Job — was rejected
because it needs a second projection (the thing `BR-041` forbids) and would make two technicians on the
same crew see different screens. Evidence **bytes** are unaffected either way: reading a photo or a
recording still requires `evidence.view` and is per kind (`ADR-015` D2, `ADR-018` A7); the Job read
grants neither and bypasses nothing.

### D3 — Any currently-assigned technician may drive the Visit lifecycle

The status transition, the outcome and the note are open to **any technician on the Visit's current crew**,
not only the `LEAD`.

- `BR-009` grants `VISIT_UPDATE_ASSIGNED_STATUS`, `VISIT_ADD_NOTE` and `VISIT_RECORD_OUTCOME` to the
  default Technician role without qualifying them by assignment role, and the catalogue holds no
  Lead-scoped capability that could express the restriction.
- `BR-068` treats `LEAD` as an assignment role, not an authority: *all* assigned technicians are
  considered scheduled for the full Visit window, and `BR-069` lets a manager move `LEAD` to
  `TECHNICIAN` and back at any time — including during an active Visit. A Lead-only rule would therefore
  strand a Visit whose Lead was just changed, and would invent an authority `BR-066` does not name.
- "Currently assigned" is evaluated **at the moment of the action**, against the same current-assignment
  rows D2 reads: a technician a manager removed before the action is refused, and that refusal is a
  scope answer, not a new rule.
- The four capabilities are still the boundary. A member with none of them — including a Manager who does
  not hold them — is refused by the API on the field routes (`BR-006`, `BR-007`).

### D4 — The Visit → Job consequence is the minimal mapping `BR-058`/`BR-061` imply

`BR-058` states that a Job "advances as a consequence of Visit lifecycle events" and `BR-061`/`BR-062` own
the conditions a destination must satisfy, but **which** Visit events move the Job is not defined by the
rules. This ADR defines them, and nothing beyond them:

| Visit event | Job consequence | Rule it rests on |
| --- | --- | --- |
| `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS` | Job `SCHEDULED → IN_PROGRESS`, `NEW → IN_PROGRESS`, or `PENDING_REVIEW → IN_PROGRESS` | `BR-058`'s meaning of `IN_PROGRESS` ("work has started and/or work remains") and its permitted transitions; a Job already `IN_PROGRESS` is not changed, because a status that does not change is not a transition |
| `COMPLETED` with `RESOLVED` | Job `→ PENDING_REVIEW` **only when `BR-061`'s conditions hold**: no Visit remains active or scheduled, and no follow-up request awaits a decision. Otherwise the Job stays `IN_PROGRESS` | `BR-061` states both the entry condition and the outcome of failing it |
| `COMPLETED` with `NEEDS_PARTS`, `NEEDS_FOLLOWUP`, `NEEDS_QUOTE_APPROVAL` or `UNABLE_TO_COMPLETE` | Job `→ IN_PROGRESS` (from `NEW`, `SCHEDULED`, `PENDING_REVIEW`) | `BR-061`: these outcomes keep the Job in `IN_PROGRESS` |
| `EN_ROUTE → SCHEDULED` (the `BR-075` correction) | **no Job change** | `BR-058` has no backwards Job destination and `NEW` is not a destination for an open Job, so mirroring a correction would need two invented rules |

What the mapping deliberately does **not** do:

- **It never completes a Job.** `BR-062` makes closing a Job an explicit authorized office action, and
  `PENDING_REVIEW` is explicitly "a review state [that] does not itself complete the Job".
- **It never cancels a Job.** `BR-064` requires a structured reason and a mandatory explanation.
- **It never touches a terminal Job.** `COMPLETED → X` and `CANCELED → X` have no permitted destination
  other than `NEW` (the reopen, `BR-063`), so no consequence is applied to a terminal Job. A terminal Job
  holding an active Visit is a state `BR-062` prevents reaching through completion; the state itself is
  recorded as an open question below rather than given invented behaviour.
- **It never changes a Visit.** A Job consequence writes no Visit status and no Visit history, and a Visit
  event writes its own Visit history row — the two state machines stay separate (`BR-059`).
- **It is not a client decision.** A client never derives or sends a Job consequence: the API applies it
  inside the same transaction as the Visit transition, through one definition, so the same rule is not
  implemented twice (`BR-001`, `BR-041`).

Each consequence is one recorded Job transition: an append-only `job_status_history` row with the new
status, the actor and the server's `recorded_at` (`BR-033`, `BR-067`). The Job's own history is the
record; the Visit event and its consequence are correlated by the actor, the instant and the Job's
history rather than by a new linkage column, and whether a consequence row should carry an explicit link
to the Visit event that caused it is recorded as an open question below instead of being invented
(`BR-042`).

**This defines an undefined detail; it does not amend `BR-058`.** `BR-058`'s transition table, its
terminal-status rule and `BR-061`/`BR-062`'s conditions are untouched, so no `BR-040` change record is
required. If product ownership treats the mapping as an amendment to `BR-058` rather than as its missing
detail, it is recorded under `BR-040` and the rule text is updated — which this ADR does **not** do.

**One consequence for the manager's own screen, stated so it is not a surprise.** These consequences move
the Job the manager home's attention conditions read (`BR-060`, `BR-061`): a Visit that starts work clears
the derived "Needs Scheduling / No Active Visit" condition, and a resolved last Visit makes the Job
eligible to appear as awaiting review. That is `BR-060`/`BR-061` working as written, not a change to
either.

### D5 — The Visit transition and the note are offline-capable, with a refuse-and-reconcile conflict policy

Both field operations **adopt the offline engine that exists** — `OutboxStore`, `OutboxReplayEngine`,
`OfflineOperationHandler` and authenticated-subject scoping (`ADR-014`) — rather than a second queue, a
second retry loop or a second connectivity check. `BR-013` names starting work, finishing work and adding
notes as offline-capable field work, so leaving them online-only would need a recorded reason; the
standard's §13.2 condition for queueing a mutation is met by this decision, which is the point of D5.

**The route contract.**

- `PATCH /jobs/:id/visits/:visitId/status` accepts a client-generated `clientOperationId` (uuid), the
  device-claimed `capturedAt`, and the Visit's `expectedVersion`.
- `POST /jobs/:id/visits/:visitId/notes` accepts `clientOperationId` and `capturedAt`. It takes no
  expected version, because a note has no update path (`docs/domain/job-visit-domain-model.md` §12.2).
- Replay is recognised by the unique partial index on `(organization_id, client_operation_id)` that
  `visit_status_history` and `visit_notes` already carry (migration `0003`) — a repeat writes no second
  event and answers with the outcome the first attempt produced (`BR-031`, §14).

**The conflict policy (`BR-032`) is refuse-and-reconcile, never last-write-wins.**

- A Visit transition is a **command against a state machine**, not a value assignment: there is no "last
  write" for a second writer to win. The API therefore evaluates it against the current server state,
  under the Visit row lock, in one transaction (§15), and applies it only if `BR-074` (and `BR-075` for
  the correction) permits it from the status the Visit actually holds.
- A stale `expectedVersion`, or a destination the current status does not permit, is **refused** with the
  Visit's current status and version. The API never applies a queued operation "as close as possible",
  and never overwrites newer state (`BR-001`, §15).
- A client that queued the action **keeps it visible** with the API's own reason and offers to discard
  it. `BR-014` requires a failed synchronization to stay visible when intervention is required and a
  permanently failed operation to be retained until it is resolved or explicitly discarded; a refused
  action is therefore not silently dropped, and not silently retried forever.
- A repeat that the server **already applied** answers with the same result instead of being refused as a
  conflict: idempotency is checked before conflict, because a replay is not a second writer.
- A note has no conflict at all — it is append-only and carries no expected version — so idempotency alone
  covers a repeat of it.

**What the client must not do.** No client holds its own copy of the lifecycle: the offered destinations
come from the API (`BR-022`, `BR-041`), and a queued action is a **provisional** change shown as the
technician's own pending action, never as something the backend accepted (`BR-001`, offline standard §2,
§7).

### D6 — The technician Home's content is **not** decided by this ADR, and Phase 2's assigned-work read waits for it

The technician home is a user-experience surface, and no approved design exists for it: the Figma
project's only technician artifact is `JobDetails.tsx`'s `TechnicianView`. `Project.md` §2 makes the
approved design the definition of product design, so this ADR deliberately answers **nothing** about the
home's content or order, and the tracker's Phase 4 stays stopped until one of two gates is satisfied:

1. an approved technician-home design, or
2. a product-ownership decision that the technician home **is** the manager home's today list restricted
   to the caller's own assignments, reusing the existing list components.

**Consequence, recorded so it is not discovered later:** Phase 2's first work item,
`GET /home/technician`, is **gated on D6** and is not implemented before it is answered — its content
would otherwise be invented (`BR-042`). Phase 2's second work item, the Job read's authorization and
scope (D1, D2), **is not gated** and lands first; a technician can therefore open Job Details before they
have a home screen.

What is already fixed by rules, whichever content D6 produces: the read is guarded by
`VISIT_VIEW_ASSIGNED`, it is scoped to the caller's own assignments (D2), and it is an **offline-capable
read** through the existing `WorkingSetStore`, keyed so one signed-in subject's answer is never served to
another — because `BR-013` names viewing assigned work (offline standard §13).

### D7 — Whether an office user may perform a field action is recorded, not implemented

The field routes require the field capabilities (D3), so an office member who does not hold them cannot
drive a Visit's lifecycle — including a Manager. `BR-066` makes Visit cancellation and `NO_SHOW` dispatch
actions and `BR-075` allows an explicit status correction, but **no capability authorizes any of them**
today, and inventing one is forbidden (`BR-006`, `BR-042`). Whether an office user may perform a field
action on the crew's behalf (and with which capability) is therefore left open below. Nothing in this
slice implements it, and nothing in this slice blocks it: a Manager who is granted the field capabilities
can already use the field routes.

### D8 — "Pausing work" is recorded, not implemented

`BR-013` lists "pausing work" among the offline-capable field workflows, while `BR-074`'s Visit vocabulary
has no paused or on-hold state. No state is invented: a technician who stops working leaves the Visit
`IN_PROGRESS`, and the Visit's own append-only status history is the record of what happened. Whether
Servora needs a paused state is left open below.


## What this changes

| Layer | Change |
| --- | --- |
| Database | **None.** The four field capabilities, `visit_status_history` (with `is_correction`, `reason_code`, `cancellation_source`, `job_status_history_id`, `captured_at`, `client_operation_id` and its unique partial index), `visit_outcome_history`, `visits.version` and `visits_completed_outcome_check` all exist already. This slice writes no migration. |
| API | A second authorization primitive (`@RequireAnyPermission` with its guard and service support, D1); `GET /jobs/:id` and `GET /jobs/:id/activity` widened to it and scoped by D2; `PATCH /jobs/:id/visits/:visitId/status` with D4's consequences (Phase 3); the note route moved onto `VISIT_ADD_NOTE` with D2's scope (Phase 3); D5's identifiers on both write routes |
| Permissions | `api/src/auth/permissions.ts` gains the four field codes as a named group. Nothing is invented: the codes, their bilingual catalogue rows and their default-Technician grants already exist in the database (`0004:75-78`, `:104-118`), so no catalogue row and no `BR-040` record is needed. The Android `Permission.kt` mirrors the same spellings in Phase 5 |
| Contracts | `docs/api/job-details.md` §2 and `docs/api/job-activity.md` §2 (re-guarded), `docs/api/job-actions.md` (a new Visit section), `docs/api/technician-home.md` (new, once D6 is answered) |
| Domain model | `docs/domain/job-visit-domain-model.md`: D4's mapping recorded beside §8.4's derived conditions, and D2's read scope beside §8.3/§10.1 |
| Android | Phases 4–5 of `docs/tracker/037-technician-field-experience.md`: the technician entry point and home (gated on D6), the field action on Job Details, the four capabilities as UI gates, and the queued action through the existing outbox |
| Business rules | **None amended.** `BR-009` already names the four capabilities and they are already granted to the default Technician role; D4 defines an undefined detail of `BR-058` rather than changing it (see D4) |

## Open questions not decided here

Recorded rather than guessed (`BR-042`), each with what it blocks.

1. **The technician home's content and order** (D6) — blocks Phase 2's `GET /home/technician` and all of
   Phase 4.
2. **Whether an office user may perform a field action, and with which capability** (D7) — blocks the
   office half of the Visit lifecycle.
3. **A paused/on-hold Visit state** (D8; `BR-013` lists "pausing work", `BR-074` has no such state) —
   blocks any pause affordance.
4. **The technician self-edit window for an outcome** (`BR-079`) — blocks outcome correction. `BR-079`'s
   immutability once the Job is closed is confirmed and unaffected.
5. **The `jobs.*` capability set** (`BR-006`, `BR-008`) — the guards this ADR widens stay **interim**.
   When the Jobs feature defines its set, `GET /jobs/:id`, `GET /jobs/:id/activity`,
   `GET /customers/:id/jobs` and `GET /home/manager` must be re-reviewed together.
6. **Whether the field capabilities should be re-spelled as `resource.action` codes** — D1 keeps the
   existing `VISIT_*` spellings, because renaming capabilities that are already granted to live roles is a
   catalogue migration and a `BR-040` record with no product decision behind it.
7. **A terminal Job holding an active Visit** (from D4) — a state `BR-062` prevents reaching through
   completion; no behaviour is invented for it.
8. **Whether a Job consequence records an explicit link to the Visit event that caused it** (from D4) —
   the correlation is the actor, the instant and the Job's own history today.
9. **Follow-up Visit requests** (`BR-FV-001` – `BR-FV-013`) — their own slice; the office review decision
   (`BR-FV-004`) is a management surface.
10. **Notifications** (`BR-029`), **time tracking** (`BR-034`) and **GPS** (`BR-038`) — each is an
    `OPEN QUESTION`. None is implemented and none is approximated.

## Decision update — 2026-09-17 (D3–D5 implemented by tracker 037 Phase 3)

Phase 3 implemented D3–D5. Three points where the implementation had to be precise are recorded here so
this ADR is not read as saying more than the code does:

1. **D3's scope, and the reach D7's sentence describes.** The assignment scope is applied to the field
   routes: a caller admitted by `VISIT_UPDATE_ASSIGNED_STATUS` whose membership is not on the Visit's
   current crew is answered **`404`**, never `403` — D2's own answer to a scope question. D7's sentence
   "a Manager who is granted the field capabilities can already use the field routes" therefore holds only
   for a Manager who is *also* on the Visit's crew. D7's open question is untouched by this: no office
   capability was invented, and the office half stays unbuilt.
2. **The note route accepts either capability**, by a **product-owner decision of the same date** rather
   than by this ADR: `VISIT_ADD_NOTE` **or** the office `JOB_UPDATE`, with the scope applied only to the
   caller who does not hold the office capability — D1's any-of primitive and D2's "the scope follows the
   capability that admitted the caller" reused rather than changed. The reason is recorded with it: this
   slice's plan would otherwise have removed an existing Manager capability (`BR-008`, tracker 028's *Write
   note*). The contract is `docs/api/job-actions.md` §2 and §7.5.
3. **`BR-072` is enforced as runtime eligibility** whenever a field event moves a Visit to `SCHEDULED`,
   `BR-075`'s correction included, because `BR-074` permits the destination structurally while `BR-072`
   states the conditions for entering it. It is the split `BR-058`/`BR-061`/`BR-062` already have for
   Jobs, so it defines no new behaviour; the refusal is its own code rather than a missing destination.

D1, D2, D4, D5 and D6–D8 are unchanged.



## Decision update — 2026-09-17 (D6 answered by product ownership; tracker 037 Phases 2b and 4 implemented)

D6 is answered, and it is answered **as its own screen** rather than by the gate's second option: the
technician home is not the manager home restricted to one technician. Product ownership recorded what
the screen is for and what it holds, and Phases 2b (`GET /home/technician`) and 4 (the Android home)
are implemented to that answer.

1. **One question, one screen.** The technician home answers *"what do I need to do next?"*; the manager
   home answers *"what is happening across the business?"*. They may share components — the greeting and
   date header, the Visit status pill, the card and section primitives, the empty and failure states —
   and the technician home deliberately keeps the manager home's information hierarchy out of it
   (`BR-010`, `BR-012`). Its sections are, in order: the caller's identity and the date (in the shell's
   one contextual top bar), the **next Visit**, **today** in chronological order, an **upcoming**
   preview, and **needs attention** — which appears only when there is something to act on.
2. **The read is `GET /home/technician`**, guarded by `VISIT_VIEW_ASSIGNED` and scoped to the caller's
   own current assignments (D2). Its content, its sets and its orders are recorded in
   `docs/api/technician-home.md` and `docs/domain/job-visit-domain-model.md` §8.4. `customers.view`
   does **not** admit a caller to it: that capability opens a different route, because every row here
   belongs to one technician.
3. **The attention section carries only conditions the domain already defines.** `VISIT_OVERDUE` — an
   assigned Visit still `SCHEDULED` after its window, the condition the manager home already derives
   from the same records. The other examples the design direction named are recorded rather than
   invented (`BR-042`): a pending follow-up request belongs to `BR-FV-001` – `BR-FV-013` and is not
   modelled, and evidence waiting to be synchronized is a device-local fact (`BR-014`, `BR-031`) the
   API cannot see.
4. **The status actions the design direction names are Phase 5's, and nothing was invented to fill the
   gap.** "Start travel", "Arrived" and "Start work" are field **writes** (`BR-074`), and Phase 4 is the
   read: the next Visit's action opens the Job, which is where the field action lives (`BR-012`).
   Phase 5 adds those actions to the Home and to Job Details; the read needs no further contract change
   to carry them, because the Visit projection already reports what the server permits
   (`allowedStatusTransitions`, Phase 3).
5. **A member holding both the office and the field capability keeps the office home** (`BR-011`). The
   manager home is the screen they already had, and it is the broader one; the technician home is served
   to a session holding the field capability without `customers.view`. Which home a member lands on is
   decided from their **capabilities**, never from a role name, and it is one decision in one place
   (`ui/customers/CustomersScreen.kt`). A product decision to prefer the field home for a member holding
   both would change that one expression and nothing else.
6. **The read is an offline-capable adopter** (`BR-013`): "viewing assigned work" is field work that has
   to survive a lost connection, so the Android home reads through the existing `WorkingSetStore` and
   marks a local answer as the last one the backend reported (§12 adopter 9).

D1–D5 and D7/D8 are unchanged, and no business rule was amended: `BR-009` already names the capability
this read enforces, and `BR-010`/`BR-012` already say the technician's screen answers what to do next.
