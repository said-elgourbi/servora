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
| 1. Repository audit and canonical docs | TODO | Inventory affected rules/contracts/code/tests; update business rules, domain model, ADRs and API docs first. |
| 2. API and database lifecycle migration | TODO | Remove/migrate old statuses, constraints, DTO validation, service transitions, consequences, projections, refusal codes, seed data and e2e coverage. |
| 3. Android lifecycle and offline behaviour | TODO | Update enums, labels, selectors, completion sheet, read-only gates, evidence actions, outbox operations, projections and focused tests. |
| 4. Verification and cleanup | TODO | Full affected API and Android verification, obsolete test removal, migration checks and manual QA runbook. |

## Decisions to flag before code changes

| Decision | Why it matters |
| -------- | -------------- |
| Whether persisted `DRAFT` Visits remain | If an existing creation/scheduling workflow needs them, they may remain internal, but must not activate a Job or appear as technician work. |
| Reopening `COMPLETED` or `CANCELED` Jobs | The existing rule reportedly returns terminal Jobs to `NEW`; under the new model `ACTIVE` may be semantically required when historical Visits already exist. Product ownership must decide explicitly. |
| `UNABLE_TO_COMPLETE` reason catalogue | A reason is required, but no large reason catalogue should be invented without an approved product decision. |
| Shape of derived office attention | Attention is not status, but projections need a stable way to report follow-up-needed, parts-required and reason-derived office action. |
| Migration treatment for historical `PENDING_REVIEW` and `NO_SHOW` rows | Existing data and history may need preservation/mapping rules distinct from current-state checks. |

## Verification note

No verification command is required for creating this tracker. This is documentation only: no API,
Android, migration, schema, seed or test file is changed by this tracker entry.
