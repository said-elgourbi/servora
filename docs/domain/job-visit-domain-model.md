# Servora — Job & Visit Domain Model

> Authoritative description of the Servora **Job & Visit domain**: Properties, Customer ↔
> Property relationships, Jobs, Visits, scheduling, assignment, field execution, history and the
> offline/concurrency behaviour those aggregates require.
>
> **This document defines the domain and the target PostgreSQL shape.** The **Property** part of it
> (§3, §4) is implemented by migration `0007_property_lifecycle_and_permissions` and the
> customer/property API; the **Job and Visit** tables are implemented by migration `0003`, and the
> first read over them — one Job with the Visit that represents it and that Visit's technicians — is
> `GET /jobs/:id` (`docs/api/job-details.md`,
> `docs/tracker/017-android-job-details.md`). Their **write** behaviour (scheduling, assignment,
> status transitions, outcomes) is implemented by `docs/api/job-actions.md`: the office actions
> (Job status, rescheduling, crew assignment) reuse the existing `JOB_UPDATE` capability because no
> `jobs.*` capability exists yet (`BR-006`, `BR-042`), and the Visit **field** lifecycle (status
> transitions, completion with its outcome, notes) is enforced by the `VISIT_*` capabilities `BR-009`
> already names (`docs/decisions/019-technician-field-experience.md`).
>
> - Business rules: `Business Rules.md` — principally BR-047 – BR-086 and BR-FV-001 – BR-FV-013
>   (and BR-001, BR-007, BR-013 – BR-015, BR-022, BR-028, BR-031, BR-033, BR-041, BR-042).
> - Foundation domain: `docs/domain/foundation-domain-model.md` (organizations, users, members,
>   customers, contacts, addresses).
> - Conventions: `Project.md`, `dev.md`, `qa.md`.

## 1. Four concepts, four identities

`BR-047` separates four things that must never be merged into one entity:

| Concept      | Meaning                                          | Identity                                     |
| ------------ | ------------------------------------------------ | -------------------------------------------- |
| **Customer** | The person or company receiving service          | `customers.id` (foundation model)            |
| **Property** | The physical location where service is performed | `properties.id`                              |
| **Job**      | The overall work request / work order            | `jobs.id` + organization-scoped `job_number` |
| **Visit**    | One field attempt to perform work for a Job      | `visits.id`                                  |

Consequences that follow directly from `BR-047`, `BR-048`, `BR-051`, `BR-059` and `BR-071`:

- A Job is not a Visit and a Visit is not a Job. One Job may have zero, one or many Visits over
  time.
- A Visit represents one field attempt, never a permanent representation of the Job. Additional
  field attempts are **additional Visits on the same Job**, never a new Job.
- A Job may be created with no Property and no Visit.
- A Visit is never created merely because a Job exists.
- Job status and Visit status are **two separate state machines** (`BR-058`, `BR-074`) that must
  not be merged (`BR-059`).
- The domain must not depend on any presentation concern — tabs, screens or navigation (`BR-047`).

### 1.1 Relationship overview (proposed)

```text
organizations
    │ 1
    ├──────────── * properties ──* property_customer_relationships ──* customers
    │                   │ 1                                            │ 1
    │                   └──────────── 0..1  jobs.property_id           │
    │                                        │ 1                       │
    │ 1                                      │                         │
    └────────────────────────────────────────* jobs ───────────────────┘
                                             │ 1
                                             *
                                          visits
                                             │
        ┌────────────────┬───────────────────┼────────────────┬─────────────────┐
        │                │                   │                │                 │
  visit_status    visit_schedule   visit_technician    visit_outcome      visit_notes
   _history         _history          _history           _history

jobs
    │
    ├── job_status_history        (§7.1)
    ├── job_property_history      (§7.2)  ← referenced by visit_location_review_flags
    ├── job_customer_history      (§5.2)
    └── visits                    (§9.2)
            ├── visit_status_history          (§9.5)
            ├── visit_schedule_history        (§9.4)
            ├── visit_technicians             (§10.2, current assignment)
            ├── visit_technician_history      (§10.3)
            ├── visit_outcome_history         (§11.2)
            ├── follow_up_visit_requests      (§11.3)
            ├── visit_notes                   (§12)
            ├── visit_location_history        (§9.6)
            └── visit_location_review_flags   (§6.3)
```

Every history table is **append-only** and hangs off the aggregate it describes. The section that
introduces each table describes its own history table.

## 2. Ownership, identity and numbering

### 2.1 Tenant model

- The **organization** is the tenant and the owner of all Job & Visit data (`BR-001`).
- Every new table in this domain carries `organization_id uuid NOT NULL` with
  `REFERENCES organizations(id) ON DELETE CASCADE`, matching the foundation model.
- Organization-owned rows are addressed by the composite key `(organization_id, id)`. Fetching an
  aggregate by `id` alone is forbidden (`api/src/tenancy/tenant-scope.ts`).
- A row outside the caller's organization is reported as **not found**, never as **forbidden**, so
  the API never reveals the existence of another tenant's data.
- This matches the foundation model exactly: tenant scoping is enforced in the data-access layer
  and verified by integration tests. This slice follows that pattern and introduces no new
  tenancy architecture (`dev.md` §1, `Project.md` §12).

### 2.2 Identifiers

- Every primary key is a `uuid` defaulting to `gen_random_uuid()`, as in the foundation model.
- `jobs.id`, `visits.id` and `properties.id` are immutable technical identifiers (`BR-052`).
- `jobs.job_number` is a **separate, human-readable, organization-scoped sequential integer**. It
  is never the primary key and never a cross-system key (`BR-052`, §13).
- Foreign keys are `uuid`. Clients never use human-facing business values (a Job number, a status
  code) as an identifier.

### 2.3 Timestamps

- `created_at` / `updated_at` follow the foundation convention:
  `timestamp with time zone NOT NULL DEFAULT now()`, with `updated_at` refreshed through Drizzle
  `$onUpdate` so no client or API-host clock is trusted.
- Business-event timestamps are stored separately and explicitly (§14).
- Times are stored as time-zone-aware instants. Localized display is a presentation concern.
  Time-zone _business_ behaviour (for example the organization's scheduling time zone) is not
  defined by the business rules and remains an open question (§21, `BR-026`).

### 2.4 Deletion and history

- History tables in this domain are **append-only** and are never rewritten or deleted
  (`BR-057`, `BR-067`).
- Physical deletion of a Job is an **OPEN QUESTION** (`BR-021`): `BR-008` grants Managers a delete
  capability, but the relationship between deleting a Job and preserving business history is not
  defined. No deletion behaviour is modelled here (§16).
- The **Property** lifecycle is now defined (`BR-082` – `BR-086`): a Property is archived and
  restored, and may be permanently deleted only when no business record references it (§3.1, §3.3).
  Job deletion remains the open question above.
- Deletion behaviour is therefore stated deliberately per reference rather than inherited blindly
  from the ORM default. Aggregate-internal references cascade; references to _other_ aggregates
  (Customer, Property, member) do not, because a cascade would destroy Job/Visit history that
  `BR-057` and `BR-067` require to be preserved. The full matrix is in §6.4.

## 3. Properties

### 3.1 Table — `properties` (migrated: `0007_property_lifecycle_and_permissions`)

A Property is the physical location where service is performed, owned by the organization
(`BR-049`).

| Column                      | Type           | Null? | Notes                                        |
| --------------------------- | -------------- | ----- | -------------------------------------------- |
| `id`                        | `uuid`         | no    | PK, `gen_random_uuid()`                      |
| `organization_id`           | `uuid`         | no    | FK → `organizations.id`, `ON DELETE CASCADE` |
| `name`                      | `varchar(255)` | yes   | Optional Property name (`BR-049`)            |
| `address_line1`             | `varchar(255)` | no    | Structured address (§3.2)                    |
| `address_line2`             | `varchar(255)` | yes   |                                              |
| `city`                      | `varchar(100)` | no    |                                              |
| `province`                  | `varchar(100)` | no    |                                              |
| `postal_code`               | `varchar(20)`  | no    |                                              |
| `country`                   | `varchar(100)` | no    | `NOT NULL DEFAULT 'Canada'`                  |
| `notes`                     | `text`         | yes   | Optional Property notes (`BR-049`)           |
| `status`                    | `varchar(16)`  | no    | `NOT NULL DEFAULT 'ACTIVE'`; CHECK (§3.3)    |
| `archived_at`               | `timestamptz`  | yes   | `NULL` while `ACTIVE` (`BR-082`)             |
| `archived_by_membership_id` | `uuid`         | yes   | FK → `organization_members.id`; who archived |
| `version`                   | `integer`      | no    | `NOT NULL DEFAULT 1` — concurrency (§15)     |
| `created_at` / `updated_at` | `timestamptz`  | no    | Foundation convention                        |

Constraints and indexes:

- `CHECK (status IN ('ACTIVE','ARCHIVED'))` — the `BR-082` lifecycle vocabulary is confirmed and
  closed (§3.3). `CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL))` and
  `CHECK ((status = 'ARCHIVED') = (archived_by_membership_id IS NOT NULL))`.
- `CHECK (version > 0)` — the same optimistic-concurrency convention as `jobs` and `visits` (§15).
- No uniqueness constraint on the address. The business rules do not forbid two Properties sharing
  an address, and Property de-duplication is a data-quality concern rather than a modelled rule.
- Indexes: `(organization_id)`, `(organization_id, postal_code)` — the organization-scoped address
  lookup a management UI needs — and `(organization_id, status)` for the active-list and selector
  queries `BR-083` requires.

### 3.2 Address shape

The structured address is deliberately the **same shape as `customer_addresses`**
(`address_line1`, `address_line2`, `city`, `province`, `postal_code`, `country`). Do **not**
introduce alternative names such as `address_line_1`, `line1`, `province_state`, `state` or `zip`.
`BR-049` requires a structured address rather than a single free-text field, and reusing the
foundation shape keeps one vocabulary for one concept (`BR-041`).

- `address_line1`, `city`, `province`, `postal_code` and `country` are required, matching
  `customer_addresses`.
- A Property is an **independently identified entity**. It does not share a primary key with any
  `customer_addresses` row, and a customer address change never rewrites a Property (`BR-057`).
- Editing a Property's own address is allowed in either lifecycle state, because Jobs and Visits
  keep immutable address snapshots (`BR-084`, §6.3, §9.3). Whether the Property's own address
  change history must additionally be retained is not defined by the business rules and is recorded
  as an open question (§21, `BR-057`).

### 3.3 Property lifecycle — archive, restore and permanent deletion (`BR-082` – `BR-086`)

A Property leaves active use by being **archived**. Permanent deletion is the exception, available
only when nothing has ever referenced the Property.

**State**

| State      | Meaning                                                                     |
| ---------- | --------------------------------------------------------------------------- |
| `ACTIVE`   | Available for new Jobs and shown in normal active lists and selectors.       |
| `ARCHIVED` | Out of active use; still a retrievable record with its identity and history. |

- `properties.status` carries the state (`NOT NULL DEFAULT 'ACTIVE'`, closed CHECK, §3.1).
- `archived_at` / `archived_by_membership_id` record the **current** archive event for fast reads and
  are `NULL` while the Property is `ACTIVE`. They are current state, never history (`BR-041`).
- Archive and restore are explicit actions. Editing never changes `status` (`BR-084`).
- Restoring returns the Property to `ACTIVE`. It never creates a Property and never changes
  `properties.id` (`BR-082`).

**Table — `property_lifecycle_history` (append-only; migrated: `0007_property_lifecycle_and_permissions`)**

| Column                 | Type          | Null? | Notes                                          |
| ---------------------- | ------------- | ----- | ---------------------------------------------- |
| `id`                   | `uuid`        | no    | PK, `gen_random_uuid()`                        |
| `organization_id`      | `uuid`        | no    | FK → `organizations.id`                        |
| `property_id`          | `uuid`        | no    | FK → `properties.id`, default `RESTRICT`       |
| `action`               | `varchar(16)` | no    | CHECK (`ARCHIVED`, `RESTORED`) (`BR-082`)      |
| `actor_membership_id`  | `uuid`        | no    | Who performed the action (`BR-086`)            |
| `note`                 | `text`        | yes   | Optional; no rule requires a reason (`BR-042`) |
| `recorded_at`          | `timestamptz` | no    | Authoritative business time (§14)              |
| `captured_at`          | `timestamptz` | yes   | Device time, display only (§14)                |
| `client_operation_id`  | `uuid`        | yes   | Offline replay idempotency (§14)               |
| `created_at`           | `timestamptz` | no    |                                                |

- `CHECK (action IN ('ARCHIVED','RESTORED'))` — the vocabulary is closed and confirmed (`BR-082`).
- No uniqueness constraint: a Property may be archived and restored repeatedly.
- Index `(organization_id, property_id, recorded_at)`.
- The `property_id` reference is deliberately **`CASCADE`, not `RESTRICT`**. This table records the
  Property's own archive/restore events, and `BR-082`/`BR-086` confirm that this history does **not**
  by itself make the Property permanently undeletable: it is removed with the Property when permanent
  deletion is allowed. Permanent deletion stays blocked by **other** business records (§6.4), which
  is what makes a Property created through the API undeletable in practice.
- This table is the Property lifecycle's authoritative history. `properties.archived_at` /
  `archived_by_membership_id` are not a second history (`BR-041`, `BR-067`).

**Archiving implications (`BR-083`)**

- New Jobs for the archived Property are **blocked**.
- The Property is excluded from normal active lists and from Property selectors used to choose a
  location for new work.
- Existing Jobs and Visits are untouched: nothing is cancelled, archived, rescheduled or otherwise
  modified.
- `SCHEDULED` and ongoing Visits continue. An existing Visit may still be rescheduled (`BR-073`), and
  additional Visits may be added to an existing open Job (`BR-071`).
- `jobs.property_id`, `job_property_history` and the Job's frozen `property_address_snapshot` are
  unchanged (`BR-056`).
- The active `property_customer_relationships` row is not ended: archiving is not a
  Customer-relationship change (`BR-050`).
- Archiving is allowed while active work exists. Android must require **explicit confirmation**, using
  the classification below. This is a client presentation requirement; the API remains authoritative
  (`BR-007`).

**Archive-warning classification (`BR-083`)**

| Concept      | Set for the archive warning                        |
| ------------ | -------------------------------------------------- |
| Active Job   | `NEW`, `SCHEDULED`, `IN_PROGRESS`, `PENDING_REVIEW` |
| Active Visit | any Visit not `COMPLETED`, `CANCELED` or `NO_SHOW` |

- This set is named `archiveWarningOpenWork`. It is **not** the derived "Needs Scheduling / No Active
  Visit" condition of `BR-060`, whose Visit set is named `needsSchedulingActiveVisit` and uses
  `NEW`/`IN_PROGRESS` Jobs and `SCHEDULED`/`EN_ROUTE`/`ON_SITE`/`IN_PROGRESS` Visits. `BR-060` asks
  "does this Job need scheduling?"; `BR-083` asks "could archiving disturb current work?". The two
  are deliberately different **named** concepts (`BR-041`) and neither vocabulary replaces the other.
- A `DRAFT` Visit counts as active for the archive warning because it is not terminal. `BR-060`
  excludes it because it is not yet scheduled work.

**Permanent deletion (`BR-082`)**

- Permanent deletion physically removes the `properties` row. It is allowed **only** when the Property
  has never been referenced by any business record (Job, Visit, note, attachment, history or other).
- The model enforces the precondition **fail-closed** with the existing `RESTRICT`/`NO ACTION`
  references (§6.4): `jobs.property_id`, `visits.property_id`,
  `job_property_history.previous_property_id`/`new_property_id`, `property_customer_relationships.property_id`
  and any future referencing table (such as an address/use history), all refuse the delete while a
  referencing row exists. The Property's **own** lifecycle history is the one exception: it is
  `ON DELETE CASCADE` and is removed with the Property.
- Because those other tables are append-only or never cleared by another path, a Property that has
  ever been referenced by one of them keeps at least one referencing row and therefore cannot be
  deleted. Job deletion is not defined (`BR-021`), so no path currently erases a reference; if Job
  deletion is later defined it must not erase Property references (`BR-082`, `BR-057`).
- The reference check runs in the service layer in the same transaction as the delete, under the
  Property row lock, so the outcome cannot change between check and delete (§15).
- Deleting a Property never cascades to any other table. The tenant reference
  (`organization_id → organizations.id ON DELETE CASCADE`) is unchanged: it is the tenant, not the
  Property, that is being removed.

**Editing (`BR-084`)**

- An `ACTIVE` or `ARCHIVED` Property may be edited.
- Editing an `ARCHIVED` Property leaves it `ARCHIVED`; restoring is a separate explicit action.
- An address edit never rewrites `jobs.property_address_snapshot`, `job_property_history.*_snapshot`
  or `visit_location_history` (`BR-056`, `BR-057`, `BR-084`).
- Whether the Property's own address changes are additionally retained is an **OPEN QUESTION**
  (`BR-057`, §21).

**Audit, offline and concurrency (`BR-086`)**

- Archive and restore append to `property_lifecycle_history`. They are **offline-capable** and follow
  the project offline/outbox conventions (`BR-013`, `BR-014`, `BR-031`). The specific standard to
  follow is an **OPEN QUESTION** (§21, `BR-086`).
- **Permanent deletion is online-only** and is never queued offline (`BR-086`).
- Concurrent Property mutations are resolved by the API; a mutation against stale state is rejected
  rather than applied. That is why `properties` carries a `version` column (§15).
- Update is traceable through the row's `updated_at` and, where the audit architecture later requires
  it, its own event record (`BR-033`). No audit-event table beyond `property_lifecycle_history` is
  invented here (`BR-042`).

## 4. Property ↔ Customer relationship

`BR-049` and `BR-050` make the Customer ↔ Property link a first-class, historical relationship
rather than a column: a Property belongs to the organization, and it may be associated with
different Customers over its lifetime — **one active Customer at a time**.

### 4.1 Proposed table — `property_customer_relationships` (append-only)

| Column                | Type          | Null? | Notes                                                    |
| --------------------- | ------------- | ----- | -------------------------------------------------------- |
| `id`                  | `uuid`        | no    | PK, `gen_random_uuid()`                                  |
| `organization_id`     | `uuid`        | no    | FK → `organizations.id`, `ON DELETE CASCADE`             |
| `property_id`         | `uuid`        | no    | FK → `properties.id`, `ON DELETE RESTRICT`               |
| `customer_id`         | `uuid`        | no    | FK → `customers.id`, `ON DELETE RESTRICT`                |
| `started_at`          | `timestamptz` | no    | `NOT NULL DEFAULT now()`                                 |
| `ended_at`            | `timestamptz` | yes   | `NULL` = this is the active relationship                 |
| `actor_membership_id` | `uuid`        | no    | FK → `organization_members.id`; who performed the change |
| `created_at`          | `timestamptz` | no    | Foundation convention                                    |

Constraints and indexes:

- **Partial unique index** `property_customer_relationships_active_property_unique` on
  `(organization_id, property_id) WHERE ended_at IS NULL` — the database-level expression of
  "one active Customer relationship at a time" (`BR-050`).
- `CHECK (ended_at IS NULL OR ended_at >= started_at)`.
- Index `(organization_id, customer_id)` — "which Properties does this Customer have?" is the
  primary management query.
- The row is **append-only apart from `ended_at`**, which is written exactly once, when the
  relationship ends. A relationship is never reopened and never rewritten (`BR-067`).

### 4.2 Behaviour

Changing the Customer of a Property is **one atomic action** that performs exactly the four steps
of `BR-050`:

1. end the previous Customer relationship (set `ended_at`),
2. create the new Customer relationship (insert a new active row),
3. preserve the historical relationship (the ended row stays),
4. leave existing Jobs associated with their original Customer and Property.

Consequences:

- A Property has at most one active relationship row; ending a relationship is never a deletion;
  the change never touches `jobs`.
- Attaching the first Customer to a Property creates the first active row.
- Whether a Property may exist with **no** active Customer relationship is an **OPEN QUESTION**
  (`BR-050` Notes). The model therefore does not forbid it, and no constraint requires an active
  row to exist.
- The Customer and the Property of a relationship must belong to the same organization, enforced
  by organization-scoped data access and covered by integration tests.
- `BR-067`: nothing changes silently. The action is explicit, the actor is recorded, and the
  history is append-only.

## 5. Customer ↔ Job

### 5.1 Rules

- A Job always belongs to exactly one Customer and cannot exist without one (`BR-048`), so
  `jobs.customer_id` is `NOT NULL`.
- The Job's Customer is **not** derived from the Property's Customer relationship and **not**
  derived from the Customer's addresses. Customer and Property stay separate concepts (`BR-049`).
- A Job keeps the Customer it was created for. Existing Jobs are **never automatically
  transferred** when a Property's Customer relationship changes (`BR-048`, `BR-050`).
- A Customer change on a Job is therefore an **explicit, authorized action** (`BR-048`), never a
  side effect of any other change.
- `customer_id` is a plain FK to `customers.id`; same-organization membership is enforced by
  organization-scoped data access (foundation pattern), not by a composite FK.

### 5.2 Proposed table — `job_customer_history` (append-only)

Where a Job's Customer was explicitly changed, the previous Customer must remain visible.
`BR-033` requires important business changes to be traceable and `BR-067` requires explicit actions
with complete history. No business rule demands a _reason_ for this change, so none is required.

| Column                 | Type          | Null? | Notes                               |
| ---------------------- | ------------- | ----- | ----------------------------------- |
| `id`                   | `uuid`        | no    | PK                                  |
| `organization_id`      | `uuid`        | no    | FK → `organizations.id`             |
| `job_id`               | `uuid`        | no    | FK → `jobs.id`, `ON DELETE CASCADE` |
| `previous_customer_id` | `uuid`        | yes   | `NULL` for the creation event       |
| `new_customer_id`      | `uuid`        | no    | FK → `customers.id`                 |
| `actor_membership_id`  | `uuid`        | no    | Who performed the change            |
| `recorded_at`          | `timestamptz` | no    |                                     |
| `captured_at`          | `timestamptz` | yes   |                                     |
| `client_operation_id`  | `uuid`        | yes   |                                     |
| `created_at`           | `timestamptz` | no    |                                     |

Index: `(organization_id, job_id, recorded_at)`. The current Customer is `jobs.customer_id`; the
history table is the audit trail and never a second source of truth.

> Traceability note: the business rules explicitly require history for status, schedule,
> assignment, cancellation and outcome. Recording the Customer-change event is an addition made
> for traceability (`BR-033`); it is flagged in §21.

## 6. The Job

### 6.1 Proposed table — `jobs`

| Column                      | Type           | Null? | Notes                                                                       |
| --------------------------- | -------------- | ----- | --------------------------------------------------------------------------- |
| `id`                        | `uuid`         | no    | PK, `gen_random_uuid()`; immutable (`BR-052`)                               |
| `organization_id`           | `uuid`         | no    | FK → `organizations.id`, `ON DELETE CASCADE`                                |
| `job_number`                | `integer`      | no    | Immutable, unique per organization (`BR-052`, §13)                          |
| `customer_id`               | `uuid`         | no    | FK → `customers.id`; a Job always belongs to a Customer (`BR-048`)          |
| `property_id`               | `uuid`         | yes   | FK → `properties.id`; `NULL` until a Property is known (`BR-051`, `BR-056`) |
| `property_address_snapshot` | `jsonb`        | yes   | Immutable address snapshot (§6.3)                                           |
| `title`                     | `varchar(255)` | no    | Required (`BR-053`)                                                         |
| `description`               | `text`         | yes   | Optional (`BR-053`)                                                         |
| `type_code`                 | `varchar(50)`  | yes   | Optional, non-controlling (`BR-053`) — see §6.2                             |
| `status`                    | `varchar(20)`  | no    | `NOT NULL DEFAULT 'NEW'`; CHECK against the `BR-058` vocabulary             |
| `owner_membership_id`       | `uuid`         | yes   | Optional Job Owner (`BR-055`); FK → `organization_members.id`               |
| `final_outcome_code`        | `varchar(30)`  | yes   | Set only when an authorized user closes the Job (`BR-062`)                  |
| `version`                   | `integer`      | no    | `NOT NULL DEFAULT 1` — optimistic concurrency (§15)                         |
| `created_at` / `updated_at` | `timestamptz`  | no    | Foundation convention                                                       |

Constraints and indexes:

- `UNIQUE (organization_id, job_number)` — `jobs_organization_job_number_unique` (`BR-052`).
- `CHECK (job_number > 0)`.
- `CHECK (btrim(title) <> '')` — `BR-053` requires a title; an empty string is not a title.
- `CHECK (status IN ('NEW','SCHEDULED','IN_PROGRESS','PENDING_REVIEW','COMPLETED','CANCELED'))` —
  the `BR-058` vocabulary is confirmed and closed, so freezing it as a CHECK is safe. Adding a
  Job status later is a business decision (`BR-040`, `BR-042`) and would be a deliberate migration.
- `CHECK ((property_id IS NULL) = (property_address_snapshot IS NULL))` — the snapshot and the
  Property reference appear and are cleared together (§6.3).
- **No** CHECK for `type_code`: the Job category catalogue is an **OPEN QUESTION** (`BR-053`).
- **No** CHECK for `final_outcome_code`: the `final_outcome` catalogue is undecided (`BR-062`).
- `CHECK (version > 0)`.
- Indexes: `(organization_id, status)`, `(organization_id, customer_id)`,
  `(organization_id, property_id)`, `(organization_id, owner_membership_id)`.
- The Job Owner is a **membership**, not a bare user id: the owner is always a member of the
  organization that owns the Job. Comparison note — Job Owner ≠ Visit technician: the owner is
  never assigned to a Visit by being the owner (`BR-055`).

### 6.2 What a Job needs — and what it deliberately does not have

- **Title is required.** A Job cannot be created without a title (`BR-053`).
- **Description is optional** (`BR-053`).
- **Type/category is optional and non-controlling** (`BR-053`). It exists for reporting, filtering
  and history, and must **not** control scheduling, status or permissions. The catalogue is an
  **OPEN QUESTION** (`BR-053` Notes).
  - Modelling decision: `type_code` is a plain `varchar(50)` with **no CHECK constraint and no
    enum type**. Freezing an enumeration would invent a catalogue the product has not defined
    (`BR-042`). Illustrative values such as `REPAIR`, `MAINTENANCE`, `INSTALLATION`, `INSPECTION`,
    `SERVICE_CALL` and `OTHER` appear in the business rules as examples, not as an authoritative
    catalogue. They must not be hard-coded and no behaviour may branch on them.
- **No priority.** `BR-054` removes Job priority from v1 entirely. There is no priority column, no
  priority value derived from another field, and no automatic scheduling order. Scheduling order is
  controlled by the Manager/authorized user.
- **No technician on the Job.** Assignment is recorded on Visits (`BR-068`); a Job's technicians
  are derived from its Visits (§10).
- **No schedule on the Job.** Scheduling belongs to the Visit (`BR-071`, `BR-072`). A Job is not
  "scheduled"; `jobs.status = 'SCHEDULED'` is a _consequence_ of a Visit being scheduled
  (`BR-058`, §8.2).
- **No outcome on the Job except `final_outcome_code`**, which is only ever set when an authorized
  office user closes the Job. It is never copied automatically from the latest Visit outcome
  (`BR-062`).
- **No stored "Needs Scheduling" flag.** The condition is derived from Visit state and must not be
  persisted as a Job status or boolean (`BR-060`, §8.4).
- **No address copy of the Customer.** The Job references a Property and keeps its own immutable
  address snapshot (`BR-056`, §6.3).
- **No customer duplication.** The Job references `customers.id`; it does not copy customer name,
  phone or email. Customer data is read through the Customer.
- **Evidence is Job-level.** Photos are recorded against the **Job** (`job_photos.job_id`), not a Visit: a
  Job may carry evidence with no Visit at all (`BR-051`), a technician records a before/during/after photo
  without a Visit having to exist, and the Activity projection reports each one as a Job-level event
  (`JOB_PHOTO_ADDED`, `BR-080`). An optional link from evidence to a Visit is available later if a
  requirement names one. Decided by product ownership on 2026-09-15 (`docs/tracker/029-photo-evidence-phases.md` D2).
- Audio and files do not exist yet and remain out of scope (`BR-027`, `D8`).

### 6.3 Property on the Job and the address snapshot

`BR-056` defines how a Job's location behaves:

- A Job may be created with `property_id = NULL` and `property_address_snapshot = NULL`
  (`BR-051`).
- A Property may be **added later**. Until a Property exists the Job cannot be scheduled
  (`BR-056`, `BR-072`).
- When a Property is associated with a Job, the Job stores an **immutable address snapshot** in
  `property_address_snapshot` so historical records keep the location that was relevant at that
  time (`BR-056`, `BR-057`).
- The snapshot uses the same field names as the Property address, in the wire `camelCase`
  convention: `propertyName`, `addressLine1`, `addressLine2`, `city`, `province`, `postalCode`,
  `country`. It is a frozen copy, never a live view of the Property row.
- Snapshot timing (modelling interpretation, flagged in §21): the snapshot is written when the
  association is created or changed, and the **previous** association and its snapshot are
  preserved in `job_property_history` (§7.2). A later edit of the Property's own address does not
  rewrite the Job's snapshot (`BR-057`). `BR-056` does not state whether the Job snapshot is
  refreshed when the _Property's_ address changes; that remains open.

Property-change behaviour (`BR-056`, `BR-067`):

- The Job's current Property may change while the Job is active, and changes are allowed only
  until the Job is `COMPLETED` (`BR-056`, `BR-062`).
- A change is recorded in history, preserves the previous Property, never creates a new Job and
  never automatically modifies Visits.
- If the Job has `SCHEDULED` or active Visits when its Property changes, each affected Visit must
  be **flagged for review** — for example `Location Changed — Review Schedule` (`BR-056`).
- The system must **never** silently change a Visit's schedule or operational location as a side
  effect. The Manager/authorized user must explicitly review and resolve every affected Visit
  (`BR-056`, `BR-067`).

Archive interaction (`BR-083`):

- Archiving a Property never clears or changes `jobs.property_id` and never rewrites
  `property_address_snapshot`. An existing Job keeps its location and its frozen address.
- The Job's Property may still be changed while the Job is active (`BR-056`). The archive state of the
  current Property does not block that change.
- A **new** Job cannot be created for an archived Property (`BR-083`). Existing Jobs remain fully
  readable regardless of the Property's lifecycle state.

Proposed table — `visit_location_review_flags`:

| Column                      | Type          | Null? | Notes                                               |
| --------------------------- | ------------- | ----- | --------------------------------------------------- |
| `id`                        | `uuid`        | no    | PK                                                  |
| `organization_id`           | `uuid`        | no    | FK → `organizations.id`                             |
| `visit_id`                  | `uuid`        | no    | FK → `visits.id`, `ON DELETE CASCADE`               |
| `job_property_history_id`   | `uuid`        | no    | The Property change that raised the flag (`BR-056`) |
| `raised_at`                 | `timestamptz` | no    | `NOT NULL DEFAULT now()`                            |
| `resolved_at`               | `timestamptz` | yes   | `NULL` = still awaiting review                      |
| `resolved_by_membership_id` | `uuid`        | yes   | Who resolved it                                     |
| `resolution_note`           | `text`        | yes   | Free text recorded by the resolver                  |
| `created_at`                | `timestamptz` | no    |                                                     |

- Partial unique index `visit_location_review_flags_open_visit_unique` on `(organization_id,
visit_id) WHERE resolved_at IS NULL` — at most one open flag per Visit.
- `Location Changed — Review Schedule` is a **localized UI label** for the derived state "this
  Visit has an unresolved location review flag". It is never stored as text (`BR-028`, `BR-041`).

### 6.4 Deletion behaviour

Deletion is part of the domain model, so it is stated deliberately instead of being left to
whatever the ORM defaults to.

- `organization_id → organizations.id` uses `ON DELETE CASCADE` on every Job/Visit table: deleting
  a tenant removes that tenant's data. This follows the foundation convention.
- **Aggregate-internal ("part of") references cascade.** A Visit or a history row cannot outlive
  the aggregate it belongs to: `visits.job_id → jobs.id`, `job_status_history.job_id`,
  `job_property_history.job_id`, `job_customer_history.job_id`,
  `visit_status_history.visit_id`, `visit_schedule_history.visit_id`,
  `visit_technician_history.visit_id`, `visit_outcome_history.visit_id`,
  `follow_up_visit_requests.job_id`, `visit_notes.visit_id`, `visit_location_review_flags.visit_id`,
  and `visit_location_history.visit_id`.
- **Cross-aggregate references do not cascade.** `jobs.customer_id`, `jobs.property_id`,
  `jobs.owner_membership_id`, `visits.property_id`,
  `property_customer_relationships.property_id`, `property_customer_relationships.customer_id` and
  the actor/member columns keep the default `RESTRICT`/`NO ACTION`. Cascading a Customer, Property
  or member deletion into Jobs, Visits, Property-Customer relationship history or their history
  would destroy business history that `BR-050`, `BR-057` and `BR-067` require to be preserved. The
  model **fails closed**: the delete is refused instead of rewriting history.
- **A Property's own lifecycle history follows the Property.** `property_lifecycle_history.property_id`
  is `ON DELETE CASCADE` (`BR-082`, `BR-086`). It records the Property's own archive/restore events,
  so it does not by itself block the Property's permanent deletion and is removed with it. It is
  therefore not part of the cross-aggregate list above.
- Manager delete capability for Jobs exists in the permission model (`BR-008`), but the
  **semantics** of deleting a Job that has history are undefined (`BR-021`). Consequently this
  slice models **no deletion path and no `deleted_at` column**: neither physical nor soft deletion
  is defined, so neither is invented. The open decision is recorded in §16 and must be resolved
  before any delete endpoint or tombstone column is designed.

### 6.5 Creating a Job (`BR-047`, `BR-051`, `BR-056`, `BR-094`)

The model permits a Job with `property_id = NULL` (§6.3). The **supported create operation** does not:
`BR-094` decides that the product's own Job-creation operation requires an active Customer **and** an
active Property that Customer currently holds, because a Job is work for a party receiving service
(`BR-048`) performed at a location (`BR-049`). Nothing in the schema changes — the nullable
`property_id` and the `jobs_property_snapshot_check` stay exactly as they are, and the create simply
always writes both.

What the create does, in one transaction:

1. Resolve the Customer by `(organization_id, id)` with `deleted_at IS NULL`; refuse as **not found**
   when it is absent, and as a **conflict** when `customers.status` is not `ACTIVE` (`BR-023`).
2. Resolve the Property inside that Customer's context — `(organization_id, id)` **and** an active
   `property_customer_relationships` row for that Customer (`BR-050`) — refusing as not found when
   either does not hold, and lock the Property row so a concurrent archive is serialized against the
   creation (`BR-083`, `BR-086`).
3. Refuse as a conflict when the Property is not `ACTIVE`: archiving blocks *new* Jobs
   (`BR-083`).
4. Allocate the organization-scoped Job number (§13) and insert the Job `NEW`, `version = 1`, with
   `property_address_snapshot` written from the Property's address at that moment (`BR-052`, `BR-056`,
   `BR-058`).
5. Append the initial `job_status_history` row: `to_status = 'NEW'`, `from_status = NULL` — the Job
   entered `NEW` from no status, which is what the null-permitting column exists for (`BR-033`,
   `BR-067`).

It creates **no Visit** and records no schedule, crew, owner, priority or category: a Visit is one field
attempt (`BR-047`, `BR-051`), assignment is Visit-scoped (`BR-068`), and `BR-054` removes priority from
v1. The operation is authorized by the existing `JOB_CREATE` capability (`BR-008`) and is **online-only**:
the request carries no idempotency key, so the offline standard's §13 forbids queueing it
(`docs/architecture/offline-first-architecture.md` §13).

## 7. Job history

History tables are **append-only**: no update and no delete path exists for them, and they are
never used as a source of truth for current state (`BR-067`, `BR-041`). All of them carry
`organization_id` so organization-scoped queries never need a join through the aggregate.

### 7.1 Proposed table — `job_status_history`

| Column                | Type          | Null? | Notes                                    |
| --------------------- | ------------- | ----- | ---------------------------------------- |
| `id`                  | `uuid`        | no    | PK                                       |
| `organization_id`     | `uuid`        | no    | FK → `organizations.id`                  |
| `job_id`              | `uuid`        | no    | FK → `jobs.id`, `ON DELETE CASCADE`      |
| `from_status`         | `varchar(20)` | yes   | `NULL` for the creation event            |
| `to_status`           | `varchar(20)` | no    | CHECK against the `BR-058` vocabulary    |
| `reason_code`         | `varchar(30)` | yes   | Structured reason (cancellation, reopen) |
| `note`                | `text`        | yes   | Free-text explanation                    |
| `actor_membership_id` | `uuid`        | no    | Who caused the transition                |
| `recorded_at`         | `timestamptz` | no    | Server-authoritative time                |
| `captured_at`         | `timestamptz` | yes   | Client-captured time for offline actions |
| `client_operation_id` | `uuid`        | yes   | Idempotency key for offline replay (§15) |
| `created_at`          | `timestamptz` | no    |                                          |

- `CHECK (to_status IN (...))`, `CHECK (from_status IS NULL OR from_status IN (...))` and
  `CHECK (from_status IS DISTINCT FROM to_status)`.
- Index `(organization_id, job_id, recorded_at)`.
- `reason_code` carries **no CHECK**: the Job cancellation reason catalogue is an **OPEN QUESTION**
  (`BR-064`). `BR-064` does require a structured reason **and** a mandatory explanation for Job
  cancellation; because the structured catalogue is undefined, that requirement is enforced in the
  service layer for the cancellation action rather than frozen as a database enumeration.
  `BR-063` allows an optional reopen reason.
- Invariant (service layer, same transaction): the newest row's `to_status` equals `jobs.status`.
  A database CHECK cannot express "the latest row wins", so this is enforced where the transition
  is applied, under the Job row lock (§15).

### 7.2 Proposed table — `job_property_history`

| Column                      | Type          | Null? | Notes                                            |
| --------------------------- | ------------- | ----- | ------------------------------------------------ |
| `id`                        | `uuid`        | no    | PK                                               |
| `organization_id`           | `uuid`        | no    | FK → `organizations.id`                          |
| `job_id`                    | `uuid`        | no    | FK → `jobs.id`, `ON DELETE CASCADE`              |
| `previous_property_id`      | `uuid`        | yes   | `NULL` for the first association                 |
| `previous_address_snapshot` | `jsonb`       | yes   | Snapshot of the previous Property                |
| `new_property_id`           | `uuid`        | no    | The Property being associated                    |
| `new_address_snapshot`      | `jsonb`       | no    | Snapshot taken at this change                    |
| `note`                      | `text`        | yes   | Optional note; no reason is required by `BR-056` |
| `actor_membership_id`       | `uuid`        | no    | Who changed the Property                         |
| `recorded_at`               | `timestamptz` | no    |                                                  |
| `captured_at`               | `timestamptz` | yes   |                                                  |
| `client_operation_id`       | `uuid`        | yes   |                                                  |
| `created_at`                | `timestamptz` | no    |                                                  |

- `CHECK ((previous_property_id IS NULL) = (previous_address_snapshot IS NULL))`.
- Index `(organization_id, job_id, recorded_at)`.
- This table is what `visit_location_review_flags.job_property_history_id` points at: an
  unresolved flag means "the disposition of this Visit must be reviewed because the Job's location
  changed" (`BR-056`).

`job_customer_history` is defined in §5.2.

## 8. Job lifecycle

### 8.1 Status vocabulary (`BR-058`)

A Job has exactly one status from this closed, shared vocabulary. The stored value is the stable
code; labels are localized (`BR-028`, `BR-041`).

| Code             | Meaning                                                |
| ---------------- | ------------------------------------------------------ |
| `NEW`            | Created; not yet scheduled                             |
| `SCHEDULED`      | A Visit is scheduled                                   |
| `IN_PROGRESS`    | Work has started and/or work remains                   |
| `PENDING_REVIEW` | Field work appears finished and awaits business review |
| `COMPLETED`      | Closed by an authorized office user                    |
| `CANCELED`       | Canceled by an authorized user                         |

Clients must not invent their own Job status vocabulary (`BR-022`, `BR-041`).

### 8.2 Permitted transitions (`BR-058`)

The table is **structural**: it states which destinations exist. Whether a listed destination may be
entered right now is runtime eligibility owned by `BR-061` (`PENDING_REVIEW`) and `BR-062`
(`COMPLETED`), which the API applies as it applies the change and reports with its own outcome.

| From             | To                                                                    | Trigger                                                            |
| ---------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `NEW`            | `SCHEDULED`, `IN_PROGRESS`, `PENDING_REVIEW`, `COMPLETED`             | Explicit authorized selection by an office user (`BR-058`, `BR-066`) |
| `SCHEDULED`      | `IN_PROGRESS`, `PENDING_REVIEW`, `COMPLETED`                          | Explicit authorized selection by an office user (`BR-058`, `BR-066`) |
| `IN_PROGRESS`    | `SCHEDULED`, `PENDING_REVIEW`, `COMPLETED`                            | Explicit authorized selection by an office user (`BR-058`, `BR-066`) |
| `PENDING_REVIEW` | `SCHEDULED`, `IN_PROGRESS`, `COMPLETED`                               | Explicit authorized selection by an office user (`BR-058`, `BR-066`) |
| `NEW`            | `CANCELED`                                                            | Explicit authorized cancellation (`BR-064`)                         |
| `SCHEDULED`      | `CANCELED`                                                            | Explicit authorized cancellation (`BR-064`)                         |
| `IN_PROGRESS`    | `CANCELED`                                                            | Explicit authorized cancellation (`BR-064`)                         |
| `PENDING_REVIEW` | `CANCELED`                                                            | Explicit authorized cancellation (`BR-064`)                         |
| `COMPLETED`      | `NEW`                                                                 | Explicit authorized reopen (`BR-063`)                               |
| `CANCELED`       | `NEW`                                                                 | Explicit authorized reopen (`BR-063`)                               |

- Every other combination is **rejected by the backend** (`BR-022`). A Job may not "change" to the
  status it already holds, `NEW` is not a destination for an open Job, and there is no transition out
  of `COMPLETED` or `CANCELED` other than reopening to `NEW` (`BR-058`, `BR-063`).
- An open Job reaches **any** permitted destination in **one** operation, forwards or backwards, and
  that operation records **one** transition. A client never reaches a destination by issuing a series
  of transitions, and it never decides the destination set itself: the Job read reports the list
  (`allowedStatusTransitions`) from this table (`BR-041`).
- `CANCELED` is absent from the list the read reports while `BR-064`'s structured reason catalogue is
  an **OPEN QUESTION**, because the API does not apply a cancellation it cannot record properly
  (`BR-042`).
- The transition table above is the single source of truth for Job transitions. It belongs in one
  place in the service layer, keyed only by the Job status and the destination, rather than in
  scattered `if` statements.
- Job status advances **either** as a consequence of Visit lifecycle events and the `BR-060`/`BR-061`
  conditions, **or** through the explicit authorized action above; the backend applies and validates
  the transition either way. Whatever the path, the change is recorded as one transition and moves the
  Job alone: no Visit status and no Visit history is written by a Job status change (`BR-033`,
  `BR-059`, `BR-067`).

**Runtime eligibility over the table**

| Destination      | Condition                                                                                                        | Refusal                        |
| ---------------- | ---------------------------------------------------------------------------------------------------------------- | ------------------------------ |
| `PENDING_REVIEW` | `BR-061`: no active or scheduled Visit and the latest completed Visit resolved the Job (§8.4)                      | `JOB_REVIEW_CONDITION_NOT_MET` |
| `COMPLETED`      | `BR-062`: no open Visit — no Visit other than `COMPLETED`, `CANCELED` or `NO_SHOW` (`BR-074`, §9.1)                | `JOB_COMPLETION_BLOCKED`       |
| `CANCELED`       | `BR-064`: a structured cancellation reason, whose catalogue is undefined                                           | `JOB_CANCELLATION_UNAVAILABLE` |

- A refusal is never expressed by removing a structurally valid destination from
  `allowedStatusTransitions`: a client offers the destination and presents the API's answer
  (`BR-041`, `BR-042`).

### 8.3 Who may act

- Authorization is **permission-based**, never role-name based (`BR-004`, `BR-006`), so the rules
  below describe the permission split, not a hard-coded `Manager` role.
- Technicians **never** change Job status (`BR-059`, `BR-066`). A technician drives the Visit
  lifecycle (§9); the resulting Job status change is applied by the backend as a consequence.
- **A field caller's read is scoped by their own current assignments** (`ADR-019` D2): a caller admitted
  by `VISIT_VIEW_ASSIGNED` rather than by the office read capability reads only a Job that at least one
  current `visit_technicians` row of their own reaches (§10.2), and a Job it does not reach is reported
  as **not found** rather than forbidden, so a Job id cannot be probed for existence. The projection is
  unchanged — one Job, one contract (`BR-041`) — and the scope is enforced by the API, never by a client
  (`BR-001`, `BR-007`).
- **A field caller reads the Customer behind an assigned Job, view-only** (`BR-092`; `ADR-021`). The
  Customer's own name, phone, email and notes are part of the Job projection when the caller holds
  `customers.view` or `customers.view_assigned`, and the block is reported **absent** when they hold
  neither — the Job itself is still returned, because it is theirs to work. The Customer is therefore
  reached only **through** an assigned Job, so the scope above is what bounds it; the Customer's billing
  fields, its preferred contact method, its language, its addresses, its contacts (`BR-023`), its
  Properties and its Jobs are not part of the read at all (`ADR-021` D3).
- Job-level actions — cancel a Job, reopen a Job, close a Job, change a Job's Property, assign or
  remove technicians, cancel a Visit, mark `NO_SHOW` — require the corresponding office/dispatch
  permission (`BR-066`). The API enforces this; UI hiding is convenience only (`BR-007`).
- **The Visit status route has two authorizations, and they stay distinct** (`BR-093`; `ADR-019` D7). A
  technician drives a Visit's field lifecycle through their own **current crew** (§9.1, §10.2); an office
  member holding the office capability that reaches Visit writes (`JOB_UPDATE`, the capability every other
  office action on a Job or Visit requires) drives it **without** being assigned to the crew, which is how
  the office completes a Visit from Job Details. The office capability is an alternative to crew
  membership, never a replacement for a destination's own capability: a completion requires
  `VISIT_RECORD_OUTCOME` of every caller. Whichever authorization admitted the caller, the append-only
  history records the member who actually performed the action (`BR-033`, `BR-067`).
- The implementation must support custom roles and direct member permissions rather than hard-coding
  these actions to the Manager system role (`BR-004`, `BR-066`).

### 8.4 Derived conditions (not stored state)

**"Needs Scheduling" / "No Active Visit" (`BR-060`) — the `needsSchedulingActiveVisit` set**

A Job surfaces this signal when:

```text
jobs.status IN ('NEW', 'IN_PROGRESS')
AND NOT EXISTS (
      SELECT 1 FROM visits v
      WHERE v.job_id = jobs.id
        AND v.status IN ('SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS')
    )
```

- `BR-060` enumerates exactly the four states that count as active work. A `COMPLETED`,
  `CANCELED` or `NO_SHOW` Visit does not count as active work — and neither does a `DRAFT` Visit,
  which is not in the enumerated list.
- A pending follow-up request is not an active Visit. It does not satisfy this condition and does
  not by itself suppress the derived signal (`BR-FV-002`).
- The signal is **derived, not stored**: it is not a Job status, not a boolean column and not a
  materialized field. It must never be persisted as a status (`BR-060`).
- It is an operational signal for a human. Nothing is auto-scheduled from it (`BR-054`).

**Customer-list filter conditions (`GET /customers?jobs=`)**

The customer list offers two derived Jobs-condition filters. They are query predicates over the
authoritative Job and Visit tables (`BR-080`), not stored values and not new statuses (`BR-042`).

*Open Job* — a Job whose status is not terminal (`BR-058`):

```text
jobs.status IN ('NEW', 'SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW')
```

*Overdue Visit* — a Visit still `SCHEDULED` (`BR-074`) whose scheduled end has passed:

```text
visits.status = 'SCHEDULED' AND visits.scheduled_end < now()
```

- `COMPLETED` and `CANCELED` Jobs are not open; a customer with no Jobs has no open Jobs, so "No
  open jobs" includes a customer with no Jobs at all.
- A Visit that has progressed (`EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`) is field work in progress, not
  overdue; a terminal Visit is history.
- Both conditions are evaluated by the backend inside the tenant-scoped query, so a client cannot
  widen its own scope (`BR-001`, `BR-007`). The product decision and API contract are recorded in
  `docs/tracker/008-android-customers-filter.md`.

**Customer detail projections (`BR-081`)**

The customer detail view projects a customer's Properties and Jobs. Every value is derived from the
authoritative tables above; nothing is stored and nothing becomes a second source of truth
(`BR-080`, `BR-042`).

*Property row* — a Property the customer is currently related to (`BR-050`, §4):

```text
jobCount        = count(jobs WHERE jobs.property_id = property.id)
lastServiceDate = max(visits.scheduled_start)
                  WHERE visits.status = 'COMPLETED'
                    AND visits.job_id IN (SELECT id FROM jobs WHERE jobs.property_id = property.id)
```

- `jobCount` counts every Job currently associated with the Property, whatever its status (`BR-048`,
  `BR-056`).
- When no `COMPLETED` Visit exists, `lastServiceDate` is absent and the client presents the
  localized "never serviced" state (`BR-028`).

*Job row* — a Job belonging to the customer (`BR-048`, §5). Exactly one **selected Visit** is chosen
per Job; the displayed date and technicians always come from that same Visit:

```text
selected = the Visit with the earliest scheduled_start among the Job's Visits
             WHERE scheduled_start >= now() AND status <> 'CANCELED'
           else the Visit with the greatest scheduled_start among the Job's past Visits
           else none
```

- displayed date = `selected.scheduled_start`
- displayed technicians = the current `visit_technicians` rows of `selected` (§10.2), Lead first
- no `selected` → no date and no technicians; `selected` with no assignments → no technicians. Both
  are presented by the client as a localized "unassigned" state (`BR-028`).
- `CANCELED` is excluded from the upcoming candidate only; the past fallback is defined over any past
  Visit by scheduled start. That literal reading is flagged for product confirmation in §21.

**Manager home projection (`GET /home/manager`)**

The manager home (`docs/api/manager-home.md`) projects one local day of the organization's operation
for the signed-in manager. Nothing is stored: every value comes from the tables above, and every
condition is one of the derived conditions this section defines (`BR-080`, `BR-042`).

*Day window.* The caller names the IANA time zone it renders in and the API resolves the half-open
window `[local midnight, next local midnight)` for that zone. "Today" is therefore one window over
authoritative records rather than a device's opinion, and two clients of the same operation asked at
the same moment agree on which Visits are today's (`BR-001`).

*Today's schedule* — the Visits whose `scheduled_start` falls inside the window and whose status is
not `CANCELED` or `NO_SHOW` (`BR-074`). A `COMPLETED` Visit stays in the list, so the day's counts
and the list describe the same set. Presentation order is operational, and every rank is derived
from a Visit status and the schedule:

```text
1. overdue   = status = 'SCHEDULED' AND scheduled_end < now()      (the customer-list condition above)
2. active    = status IN ('EN_ROUTE', 'ON_SITE', 'IN_PROGRESS')
3. upcoming  = everything else not completed
4. completed = status = 'COMPLETED'
```

*Today's summary* — `total`, `completed`, `inProgress` (`EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`) and
`upcoming`, over the same set, so `completed + inProgress + upcoming = total`. Exception counts are
deliberately **not** repeated here: they belong to the attention list below.

*Attention list.* Three conditions, each already defined above; a client presents them and never
decides one:

| Kind                   | Condition                                                                      |
| ---------------------- | ------------------------------------------------------------------------------ |
| `VISIT_OVERDUE`        | The overdue-Visit condition defined for the customer-list filters (above).     |
| `JOB_PENDING_REVIEW`   | `jobs.status = 'PENDING_REVIEW'` (§8.4, `BR-061`).                             |
| `JOB_NEEDS_SCHEDULING` | The "Needs Scheduling" signal defined at the start of this section (`BR-060`). |

- The overdue condition is not restricted to the requested day: a Visit scheduled for an earlier day
  that never advanced is still work that has gone wrong, so hiding it because its day passed would
  hide exactly the exception a manager has to resolve.
- Work of a Customer the organization has deleted (`customers.deleted_at`) is excluded, and no Job
  or Visit of another organization is reachable (`BR-023`, `BR-001`).
- The list is capped at `MANAGER_HOME_ATTENTION_LIMIT` items and reports the full count alongside it.

**Technician home projection (`GET /home/technician`)**

The technician home (`docs/api/technician-home.md`) projects **one technician's own working day** —
the screen answers "what do I need to do next?", which is deliberately not the manager's question
"what is happening across the business?" (`BR-010`, `BR-012`; product-ownership decision of
2026-09-17, `ADR-019` D6). Nothing is stored, and every condition is one this section already
defines (`BR-080`, `BR-042`).

*Scope* — the caller's **own current assignments**: `visit_technicians` rows whose
`technician_membership_id` is the membership the route was reached with (§10.2, `ADR-019` D2). A Visit
that membership is not on the crew of is absent from the payload, and no Job or Visit of another
organization is reachable (`BR-001`, `BR-007`). Work of a Customer the organization has deleted is
excluded (`BR-023`).

*Day window* — the same resolved `[local midnight, next local midnight)` window the manager home uses,
so the two screens cannot disagree about which day they describe (`BR-041`).

*Today's list* — the caller's own Visits whose `scheduled_start` falls inside the window and whose
status is not `CANCELED` or `NO_SHOW` (`BR-074`) — the same set the manager's day uses. A `COMPLETED`
Visit stays, because it is what the day has produced so far. The order is **chronological**
(scheduled start, then Job number): a technician reads their day in time order, which is not the
manager's operational order above.

*Next Visit* — chosen over the caller's own **open** Visits that carry a schedule, where *open* is
`BR-062`'s classification (a Visit that is not `COMPLETED`, `CANCELED` or `NO_SHOW`):

```text
1. started  = status IN ('EN_ROUTE', 'ON_SITE', 'IN_PROGRESS')   (work being executed, BR-074)
2. otherwise the smallest scheduled_start, then the smallest Job number
```

- The order is presentation over authoritative statuses, defined by the API so two clients cannot
  disagree about which Visit is "next" (`BR-041`). It is not the manager home's order, which leads
  with the overdue Visit because the office scans for what has gone wrong (`BR-012`).
- The next Visit may be after today: a technician with nothing left today is answered with what they
  do next rather than with nothing.
- A Visit with no schedule is not a candidate: a `DRAFT` Visit the office has not scheduled yet has no
  time to be "next" at, and scheduling it is the office's work, not the technician's
  (`BR-060`, `BR-072`).

*Upcoming preview* — the caller's own open Visits whose `scheduled_start` is at or after the end of
the window, chronologically, capped for the payload with the full count reported beside it. It is a
preview of the Schedule surface (`GET /schedule`, below), which is the office's read rather than the
technician's.

*Attention list.* One condition, already defined above (the overdue-Visit condition the customer-list
filters define), applied to the caller's own Visits:

| Kind            | Condition                                                                    |
| --------------- | ---------------------------------------------------------------------------- |
| `VISIT_OVERDUE` | Visit still `SCHEDULED` (`BR-074`) whose scheduled end has passed (`BR-072`). |

- It is not restricted to the requested day, for the manager home's reason: an attempt that never
  happened on an earlier day is still work the technician has to resolve.
- The kinds the section *could* carry are recorded rather than invented (`BR-042`): a pending
  follow-up request belongs to `BR-FV-001` – `BR-FV-013` and is not modelled, and evidence waiting to
  be synchronized is a device-local fact (`BR-014`, `BR-031`) the API cannot see.

**Manager schedule projection (`GET /schedule`, `BR-072`, `BR-074`, `BR-068`)**

One local day's schedule, the technician filter's options and the work that still needs a crew, all
derived from the tables above. Nothing is stored and nothing is a new status (`BR-080`, `BR-042`).

*The day's schedule* — the Visits scheduled inside the requested local day:

```text
visits.scheduled_start ∈ [day.start, day.end)
AND visits.status NOT IN ('CANCELED', 'NO_SHOW')      -- the day's worked attempts (`BR-074`)
AND jobs.customer_id → customers.deleted_at IS NULL   -- `BR-023`
```

- The day's window is resolved by the API from the local date and zone the client names, through the
  same module the two home reads use, so a local day begins in exactly one place (`BR-041`).
- A `COMPLETED` Visit stays in the day: it is what the day has produced so far.
- The technician filter narrows the day to the Visits that membership is **currently** assigned to
  (§10), applied inside the tenant scope, so a filter can only narrow what the caller could already
  read.
- Order: scheduled start, then Job number (`BR-052`).

*The unassigned lane* — the absence of an assignment on a Visit that is still open:

```text
visits.status NOT IN ('COMPLETED', 'CANCELED', 'NO_SHOW')   -- `BR-062`'s open Visit
AND NOT EXISTS (visit_technicians WHERE visit_id = visits.id)
```

- It is **not** day-scoped: a Visit with no agreed time belongs to no day, and work waiting for a crew
  is waiting whoever's day it lands on. `BR-071`/`BR-072` explicitly allow such a Visit to exist while
  it is being arranged.
- An attempt in the lane may carry no schedule at all, which is why the row's schedule fields are
  optional on the wire.
- Order: scheduled start, then Job number; an attempt with no time sorts last. The lane is capped with
  the whole count reported beside it.

**Entry into `PENDING_REVIEW` (`BR-061`)**


Both conditions must hold:

1. No Visit remains active or scheduled — no Visit in `SCHEDULED`, `EN_ROUTE`, `ON_SITE` or
   `IN_PROGRESS`.
2. The **latest completed Visit outcome** indicates the Job may be resolved (`RESOLVED`), or an
   authorized office decision has resolved the follow-up requirement by determining that no further
   Visit is necessary (`BR-FV-007`). "Latest completed Visit" means the Job's `COMPLETED` Visit with
   the greatest completion `recorded_at`.

- If another Visit remains scheduled or active, the Job stays `IN_PROGRESS` even after a `RESOLVED`
  outcome (`BR-061`).
- `NEEDS_PARTS`, `NEEDS_FOLLOWUP` and `UNABLE_TO_COMPLETE` keep the Job `IN_PROGRESS` while the
  required follow-up work remains unresolved (`BR-061`, `BR-FV-007`).
- A pending or clarification-needed follow-up request blocks review entry. If an authorized office
  user rejects the request because no additional Visit is required, that decision resolves the
  follow-up requirement for review-entry purposes (`BR-FV-004`, `BR-FV-007`, `BR-FV-013`).
- `BR-061` does not say whether a remaining `DRAFT` Visit blocks entry into `PENDING_REVIEW`. That
  remains an **OPEN QUESTION** for review entry, so no classification of `DRAFT` is applied to this
  transition; the unresolved decision is recorded in §21 and must be resolved before implementing
  automatic `PENDING_REVIEW` entry when draft Visits exist. Completion is decided and *does* block on
  a `DRAFT` Visit, which is remaining work that has not happened (§8.5, `BR-062`).
- The Job-status effect of `NEEDS_QUOTE_APPROVAL` is an **OPEN QUESTION** (`BR-061`). No transition
  is implemented for it: the Job remains `IN_PROGRESS`, because no confirmed rule says the Job may
  be resolved. The question is recorded in §21 rather than guessed (`BR-042`).
- The condition is evaluated when a Visit lifecycle event can change whether a Visit is still
  active or scheduled (i.e. on Visit completion and on the terminal Visit transitions of §9.1).

### 8.5 Completion and reopening

**Completion (`BR-062`)**

- Closing is a transition **into** `COMPLETED` (`BR-058`): `PENDING_REVIEW → COMPLETED` is the normal
  path, and an open Job may also be closed directly from `SCHEDULED`, `IN_PROGRESS` or `NEW`, in one
  operation. It is an **explicit business action** performed by an authorized office user holding the
  close permission. It is never automatic and never a technician action (`BR-066`), and a client
  confirms it with the user before sending it.
- **A Job must not be completed while it has an open Visit.** A Visit is open while its status is not
  historical — anything other than `COMPLETED`, `CANCELED` or `NO_SHOW` (§9.1, `BR-074`). This is the
  invariant behind "no remaining work requires another Visit":
  - a `SCHEDULED` or `IN_PROGRESS` Visit blocks completion;
  - a **`DRAFT` Visit blocks completion too** — it is a field attempt that has not happened, which is
    why this set is deliberately not the narrower `needsSchedulingActiveVisit` set of §8.4;
  - Visits that are all `COMPLETED`, `CANCELED` or `NO_SHOW` do not block completion;
  - a Job with **no** Visit may be closed (administrative closure).
  The check is evaluated inside the same transaction that applies the status change, and it is
  reported as `JOB_COMPLETION_BLOCKED` — its own outcome, because the destination is structurally
  permitted (`BR-058`). A client cannot override it.
- `final_outcome_code` is optional, is set at close, and is **never copied automatically** from
  the latest Visit outcome (`BR-062`). Its value catalogue is an **OPEN QUESTION**, so the column
  carries no CHECK.
- Historical `COMPLETED`, `CANCELED` or `NO_SHOW` Visits do **not** prevent completion. The governing
  condition is that no remaining work requires another Visit (`BR-062`).
- Closing a Job writes one `job_status_history` row and moves the Job only: no Visit status and no
  Visit status history is written by a Job status change (`BR-059`, `BR-067`).
- Once completed:
  - the Job's terminal business history is preserved;
  - Visit outcomes become **immutable** (`BR-079`);
  - the Job's Property can no longer be changed (`BR-056`);
  - the Job may still be reopened later (`BR-063`).

**Reopening (`BR-063`)**

- Reopening is `COMPLETED → NEW` or `CANCELED → NEW` (`BR-058`). It never produces `IN_PROGRESS`,
  because it does not imply that work is currently underway.
- Reopening is the **only** way a Job reaches `NEW`: an open Job is never moved back to `NEW`, because
  that is the reopened, not-yet-scheduled state (`BR-058`).
- Reopening does **not** modify or reopen historical completed/canceled Visits, and it does **not**
  create a Visit automatically. A new field attempt is a new Visit created explicitly (`BR-051`,
  `BR-071`).
- Previous completion/cancellation history is preserved; an optional reopen reason may be recorded
  in `job_status_history` (`BR-063`).
- Reopening is an authorized office/dispatch action (`BR-066`). After reopening, the Job is `NEW`
  and the "Needs Scheduling" signal applies again (`BR-060`).

### 8.6 Cancellation (`BR-064`)

- A Job may be canceled from `NEW`, `SCHEDULED`, `IN_PROGRESS` or `PENDING_REVIEW` — **including
  after field work has started**.
- Cancellation is **not** folded into the ordinary status selector: it is a separate explicit action
  with its own requirements. `CANCELED` is a structurally permitted destination (`BR-058`), but the
  API does not apply it while the structured reason catalogue is undefined, and the list the Job read
  reports leaves it out rather than drawing an action the API would refuse (`BR-042`).
- Cancellation requires **both** a structured `reason_code` and a mandatory `note` explanation.
  The structured reason catalogue is an **OPEN QUESTION** (`BR-064`), so this pairing is enforced
  in the service layer for the cancellation action rather than frozen as a database enumeration.
  The explanation matters most when field work has already occurred.
- Cancellation writes a `job_status_history` row (from the current status to `CANCELED`) carrying
  the reason, the explanation, the actor and the timestamp. Cancellation history is preserved.
- Completed Visits remain historical and are not touched. Open Visits follow the cascade in §9.5.
- **Before** the cancellation is confirmed, the UI shows the operational impact: the affected
  Visits and the assigned technicians (`BR-064`). The model supports that question directly — open
  Visits are `visits` rows, and assigned technicians are derived from `visit_technician_history`
  (§10).
- Canceling a Visit never changes the Job status by itself (`BR-076`); canceling a _Job_ does set
  the Job status to `CANCELED` (`BR-064`).

### 8.7 Visit → Job consequences (`ADR-019` D4)

`BR-058` states that a Job "advances as a consequence of Visit lifecycle events" without naming which
ones; `BR-061` and `BR-062` own the conditions a destination must satisfy. `ADR-019` D4 defines the
mapping, and this is its single place in the model (`BR-041`):

| Visit event | Job consequence | Rule |
| --- | --- | --- |
| `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS` | `NEW`/`SCHEDULED`/`PENDING_REVIEW` → `IN_PROGRESS`; a Job already `IN_PROGRESS` is not changed | `BR-058` (the meaning of `IN_PROGRESS`) |
| any **working** destination (`DRAFT`, `SCHEDULED`, `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`) while the Job is `PENDING_REVIEW` | → `IN_PROGRESS` | `BR-061`, `BR-074` |
| `COMPLETED` with `RESOLVED` | → `PENDING_REVIEW` **only while `BR-061`'s conditions hold** (no active or scheduled Visit, no pending follow-up request); otherwise the Job stays `IN_PROGRESS` | `BR-061` |
| `COMPLETED` with `NEEDS_PARTS`, `NEEDS_FOLLOWUP`, `NEEDS_QUOTE_APPROVAL`, `UNABLE_TO_COMPLETE` | → `IN_PROGRESS` | `BR-061` |
| `DRAFT` or `SCHEDULED` while the Job is **not** awaiting review | no Job change | `BR-058` has no backwards Job destination |

The second row is `BR-061`'s invariant read in the other direction. A Job awaits review *because* no Visit
remained active, so a Visit moved into a working status — a **reopen** out of `COMPLETED` included — takes
the Job back out of review. Without it a reopened, actively worked Visit could coexist with a Job awaiting
review, which is exactly the state `BR-061` exists to prevent (`BR-074`).

- A consequence **never** completes a Job (`BR-062`), never cancels one (`BR-064`), and is never applied
  to a terminal Job (`COMPLETED`/`CANCELED` have no destination other than their reopen, `BR-063`).
- It moves the **Job alone**: no Visit status and no Visit history is written by the consequence, exactly
  as no Job status is written by a Visit event (`BR-059`).
- It is applied by the API inside the same transaction as the Visit transition and recorded as one
  `job_status_history` row (§7.1, `BR-033`, `BR-067`). A client never derives or sends it.
- A terminal Job holding an active Visit, and whether a consequence row should carry an explicit link to
  the Visit event that caused it, are **OPEN QUESTION**s recorded by `ADR-019` rather than modelled here.

## 9. The Visit

A Visit is **one field attempt** to perform work for a Job (`BR-071`). A Job may have zero, one or
many Visits; a Visit is never created merely because a Job exists; and a Visit is not a permanent
representation of the Job (`BR-047`, `BR-051`, `BR-071`).

### 9.1 Visit status lifecycle (`BR-074`)

Visit status describes the **field execution lifecycle**. It is a separate state machine from Job
status and the two must never be merged (`BR-059`).

| Code          | Meaning                                             |
| ------------- | --------------------------------------------------- |
| `DRAFT`       | Created; not scheduled                              |
| `SCHEDULED`   | Scheduled (`BR-072`)                                |
| `EN_ROUTE`    | Technician is travelling to the Property            |
| `ON_SITE`     | Technician has arrived                              |
| `IN_PROGRESS` | Work is being performed                             |
| `COMPLETED`   | Field attempt completed, with an outcome (`BR-077`) |
| `CANCELED`    | Canceled (`BR-076`)                                 |
| `NO_SHOW`     | The field attempt could not be performed            |

Working statuses — the field attempt is not over:

```text
DRAFT, SCHEDULED, EN_ROUTE, ON_SITE, IN_PROGRESS, COMPLETED
```

Historical statuses — the field attempt is over:

```text
CANCELED, NO_SHOW
```

The **normal progression** is the order a successful field attempt usually follows:

```text
DRAFT → SCHEDULED → EN_ROUTE → ON_SITE → IN_PROGRESS → COMPLETED
```

That order is the usual path, not a required one. `BR-074` permits **free movement** between the working
statuses, so a Visit may move **directly** between any two of them, in either direction, in one
operation — a skipped step and a step backward are each one transition, and a `COMPLETED` Visit may be
**reopened** into any other working status. Field reality requires correcting a status mistake, catching
up after a missed tap and moving backward when the work genuinely goes back a step.

The **dispatch alternatives** remain exactly as they were:

```text
SCHEDULED   → CANCELED
EN_ROUTE    → CANCELED
ON_SITE     → CANCELED
IN_PROGRESS → CANCELED
SCHEDULED   → NO_SHOW
```

- `CANCELED` and `NO_SHOW` are **truly terminal**: no working status is reachable from them, and they are
  not field destinations — canceling a Visit and marking `NO_SHOW` are office/dispatch actions that need
  a structured reason (`BR-066`, `BR-076`, §8.3).
- `COMPLETED` is historical but **not** a dead end: it is the one historical status that offers
  destinations, which is how a reopen is expressed (`BR-074`).
- Reopening a `COMPLETED` Visit preserves the previous completion and its outcome in append-only history
  while clearing the Visit's **current** outcome; the next completion records a new one (`BR-074`,
  `BR-077`, `BR-079`, §9.5).
- A Visit may not "change" to the status it already holds; that is not a transition (`BR-067`).
- Every transition is validated by the backend against this table; clients must not invent their
  own Visit status vocabulary (`BR-022`, `BR-041`, `BR-074`).
- Status history is append-only (§9.5).
- The correction **flag** stays as narrow as the correction `BR-075` named — `EN_ROUTE → SCHEDULED`
  (§9.5). `BR-074`'s free movement needs no flag of its own: every status event already records its
  previous status, its new status, its actor and its timestamp.
- Who may drive each transition is defined by `BR-066`, `BR-093` and the permission model: a technician
  operates the field lifecycle of a Visit their own current crew includes; an office member holding the
  office capability operates any of the organization's Visits **without** crew membership — completion
  included, and with the outcome capability a completion requires of every caller; canceling a Visit and
  marking `NO_SHOW` are office/dispatch actions no capability authorizes yet (`BR-066`, `BR-076`, §8.3).
- **What the API applies today** is recorded in `docs/api/job-actions.md` §7: the whole working
  vocabulary in either direction with reopens included, `CANCELED` and `NO_SHOW` refused because
  no capability authorizes a dispatch action yet (`BR-042`, `BR-093`), `BR-072`'s conditions gating
  a move to `SCHEDULED`, and `BR-077`'s outcome required on a completion. The scope is the caller's own
  current assignment for a field caller, and the organization's Visits for a caller admitted by the office
  capability (`BR-093`); a Visit a field caller's assignments do not reach is reported as not found
  (`ADR-019` D2, D3).
- The two state machines interact only in one direction: Visit lifecycle events cause Job status
  consequences (§8.2, §8.4, §8.7). A Visit may be `COMPLETED` while its Job remains `IN_PROGRESS`
  (`BR-059`, `BR-077`).

### 9.2 Proposed table — `visits`

| Column                      | Type          | Null? | Notes                                                             |
| --------------------------- | ------------- | ----- | ----------------------------------------------------------------- |
| `id`                        | `uuid`        | no    | PK, `gen_random_uuid()`                                           |
| `organization_id`           | `uuid`        | no    | FK → `organizations.id`, `ON DELETE CASCADE`                      |
| `job_id`                    | `uuid`        | no    | FK → `jobs.id`, `ON DELETE CASCADE`                               |
| `property_id`               | `uuid`        | yes   | Operational location; `NULL` while `DRAFT`                        |
| `location_address_snapshot` | `jsonb`       | yes   | Snapshot taken when the Visit is scheduled / location resolved    |
| `status`                    | `varchar(20)` | no    | `NOT NULL DEFAULT 'DRAFT'`; CHECK against the `BR-074` vocabulary |
| `scheduled_start`           | `timestamptz` | yes   | **Internal** start — authoritative (`BR-072`)                     |
| `scheduled_end`             | `timestamptz` | yes   | **Internal** end — authoritative (`BR-072`)                       |
| `arrival_window_start`      | `timestamptz` | yes   | Optional customer-facing window                                   |
| `arrival_window_end`        | `timestamptz` | yes   | Optional customer-facing window                                   |
| `version`                   | `integer`     | no    | `NOT NULL DEFAULT 1` — optimistic concurrency (§15)               |
| `created_at` / `updated_at` | `timestamptz` | no    | Foundation convention                                             |

Constraints and indexes:

- `CHECK (status IN ('DRAFT','SCHEDULED','EN_ROUTE','ON_SITE','IN_PROGRESS','COMPLETED',
'CANCELED','NO_SHOW'))` — the `BR-074` vocabulary is confirmed and closed.
- `CHECK ((scheduled_start IS NULL) = (scheduled_end IS NULL))` and
  `CHECK (scheduled_end IS NULL OR scheduled_end > scheduled_start)`.
- `CHECK ((arrival_window_start IS NULL) = (arrival_window_end IS NULL))` and
  `CHECK (arrival_window_end IS NULL OR arrival_window_end > arrival_window_start)`.
  **No** constraint ties the arrival window to the internal schedule: `BR-072` does not define that
  relationship, so none is invented.
- `CHECK ((property_id IS NULL) = (location_address_snapshot IS NULL))`.
- `CHECK (status <> 'SCHEDULED' OR (property_id IS NOT NULL AND scheduled_start IS NOT NULL
AND scheduled_end IS NOT NULL))` — a Visit cannot be `SCHEDULED` without an operational location
  and a valid internal schedule (`BR-072`).
- `CHECK (version > 0)`.
- Indexes: `(organization_id, job_id)`, `(organization_id, status)`,
  `(organization_id, scheduled_start)` (dispatch and conflict queries, §10.4).
- **No** Visit sequence/number column. `BR-052` defines a Job number only; the `Visit #1/#2/#3`
  labels in the business rules are illustrative. A Visit's ordinal position is derived from
  creation order for display and is not stored as a numbering scheme the business never defined.
  The Job Activity read (`BR-080`) exposes that derived ordinal as `visitSequence` — `Visit 1`,
  `Visit 2`, … — for Visit-level events, and never exposes a database id (`docs/api/job-activity.md`,
  `docs/tracker/022-android-job-activity-timeline.md`).

### 9.3 Scheduling (`BR-072`)

- The **internal** `scheduled_start` / `scheduled_end` are authoritative for dispatch and for
  conflict detection (`BR-070`, `BR-072`). All timestamps are stored as absolute instants
  (`timestamptz`). Timezone behaviour is an **OPEN QUESTION** (`BR-026`), so no organization-level
  or Visit-level timezone field is modelled here.
- The **customer-facing arrival window** is optional. When it is not supplied, the UI may present
  the scheduled time — a presentation concern, not a stored value.
- A Visit cannot become `SCHEDULED` unless **all** of the following hold (`BR-072`), validated in
  the service layer inside one transaction, under a lock on the Visit row (§15):
  1. the Job has a Property (`BR-056`, `BR-072`),
  2. the scheduled start and end are valid,
  3. at least one technician is assigned,
  4. exactly one assigned technician is `LEAD` (`BR-068`),
  5. scheduling conflict checks have been performed (`BR-070`),
  6. any detected conflicts have been **explicitly confirmed** by the authorized user (`BR-070`).
- A Visit may exist as an **unscheduled/`DRAFT` Visit without technicians** (`BR-068`, `BR-071`,
  `BR-072`), and a Job cannot be scheduled until a Property exists (`BR-056`).
- Archiving the Property does not invalidate scheduling (`BR-083`): a Visit belonging to an existing
  open Job may still be created, scheduled or rescheduled against an archived Property. Only the
  creation of a **new Job** for that Property is blocked. `BR-072`'s precondition is unaffected — the
  Job still has a Property.
- When `visits.status` becomes `SCHEDULED`, the Job transition `NEW → SCHEDULED` follows as a
  consequence (§8.2).

### 9.4 Rescheduling (`BR-073`)

- Rescheduling **edits the existing Visit**; the Visit identity never changes and no new Visit is
  created (`BR-073`).
- It is permitted **only while the Visit is `SCHEDULED`**. A Visit that is `EN_ROUTE`, `ON_SITE`,
  `IN_PROGRESS`, `COMPLETED`, `CANCELED` or `NO_SHOW` cannot be rescheduled (`BR-073`).
- If another field attempt is required, a **new Visit** is created instead (`BR-071`, `BR-073`).
- Every schedule change is recorded: previous schedule, new schedule, actor, timestamp and an
  optional reason (`BR-073`).

Proposed table — `visit_schedule_history`:

| Column                                                | Type          | Null? | Notes                                          |
| ----------------------------------------------------- | ------------- | ----- | ---------------------------------------------- |
| `id`                                                  | `uuid`        | no    | PK                                             |
| `organization_id`                                     | `uuid`        | no    | FK → `organizations.id`                        |
| `visit_id`                                            | `uuid`        | no    | FK → `visits.id`, `ON DELETE CASCADE`          |
| `previous_scheduled_start` / `previous_scheduled_end` | `timestamptz` | yes   | `NULL` for the first schedule                  |
| `new_scheduled_start` / `new_scheduled_end`           | `timestamptz` | no    | Schedule after the change                      |
| `previous_arrival_window_start` / `_end`              | `timestamptz` | yes   | Optional customer window, before               |
| `new_arrival_window_start` / `_end`                   | `timestamptz` | yes   | Optional customer window, after                |
| `reason`                                              | `text`        | yes   | Optional (`BR-073` requires no reason)         |
| `actor_membership_id`                                 | `uuid`        | no    | Who rescheduled                                |
| `recorded_at` / `captured_at` / `client_operation_id` |               |       | Event convention (§14)                         |
| `confirmed_conflicts`                                 | `jsonb`       | yes   | Schedule conflicts explicitly accepted (§10.4) |
| `created_at`                                          | `timestamptz` | no    |                                                |

- The customer-facing arrival window is snapshotted in the same row because it is part of "the
  schedule as it was". The previous schedule must stay fully reconstructible (`BR-073`, `BR-057`).
- Index `(organization_id, visit_id, recorded_at)`. Append-only: a reschedule never updates or
  deletes an earlier row.

### 9.5 Status history, corrections and the cancellation record (`BR-074`–`BR-076`)

`visit_status_history` is the authoritative, append-only record of field execution. It is also the
cancellation record (`BR-076`) and the correction record (`BR-075`). Current status lives in
`visits.status`; the history is never a second source of truth.

| Column                                                | Type          | Null? | Notes                                                                            |
| ----------------------------------------------------- | ------------- | ----- | -------------------------------------------------------------------------------- |
| `id`                                                  | `uuid`        | no    | PK                                                                               |
| `organization_id`                                     | `uuid`        | no    | FK → `organizations.id`                                                          |
| `visit_id`                                            | `uuid`        | no    | FK → `visits.id`, `ON DELETE CASCADE`                                            |
| `from_status`                                         | `varchar(20)` | yes   | `NULL` for the creation event                                                    |
| `to_status`                                           | `varchar(20)` | no    | CHECK against the `BR-074` vocabulary                                            |
| `is_correction`                                       | `boolean`     | no    | `NOT NULL DEFAULT false`                                                         |
| `reason_code`                                         | `varchar(30)` | yes   | Manual Visit cancellation reason (`BR-076`)                                      |
| `note`                                                | `text`        | yes   | Explanation; mandatory when `reason_code = 'OTHER'`                              |
| `cancellation_source`                                 | `varchar(20)` | yes   | `MANUAL` or `JOB_CANCELLATION`                                                   |
| `job_status_history_id`                               | `uuid`        | yes   | FK → `job_status_history.id`; triggering Job cancellation                        |
| `actor_membership_id`                                 | `uuid`        | no    | Who caused the transition                                                        |
| `recorded_at` / `captured_at` / `client_operation_id` |               |       | Event convention (§14)                                                           |
| `confirmed_conflicts`                                 | `jsonb`       | yes   | Schedule conflicts explicitly accepted when the Visit became `SCHEDULED` (§10.4) |
| `created_at`                                          | `timestamptz` | no    |                                                                                  |

Checks:

- `to_status IN (...)`, `from_status IS NULL OR from_status IN (...)`,
  `from_status IS DISTINCT FROM to_status`.
- `NOT is_correction OR (from_status = 'EN_ROUTE' AND to_status = 'SCHEDULED')` — the single
  correction named by `BR-075`. `BR-074`'s free movement is deliberately **not** flagged: it is fully
  auditable from `from_status`, `to_status`, the actor and the timestamp, which is the audit `BR-067`
  requires. Generalizing the flag would need a migration and adds nothing to that record, so it stays
  as narrow as the rule that named it.
- `to_status <> 'CANCELED' OR cancellation_source IS NOT NULL` and
  `to_status = 'CANCELED' OR cancellation_source IS NULL`.
- `cancellation_source IS NULL OR cancellation_source IN ('MANUAL','JOB_CANCELLATION')`.
- `cancellation_source IS DISTINCT FROM 'JOB_CANCELLATION' OR job_status_history_id IS NOT NULL`.
- `reason_code IS NULL OR (to_status = 'CANCELED' AND cancellation_source = 'MANUAL')`.
- `reason_code IS NULL OR reason_code IN ('CUSTOMER_RESCHEDULED','CUSTOMER_CANCELED','WEATHER',
'TECH_UNAVAILABLE','DUPLICATE','OTHER')` — the shared vocabulary is confirmed by `BR-076`.
  Whether organizations may add their own cancellation reasons is an **OPEN QUESTION** (§21).
- Index `(organization_id, visit_id, recorded_at)`. Invariant (service layer): the newest row's
  `to_status` equals `visits.status`.

Cancellation and correction rules recorded here:

- A canceled Visit requires a structured reason and, for `OTHER`, an explanation (`BR-076`).
- Canceling a Visit does **not** change the Job status (`BR-076`, §8.2).
- `cancellation_source` distinguishes a manual cancellation from a cascade caused by Job
  cancellation (`BR-076`, `BR-065`), and a cascade row always references the Job cancellation that
  triggered it.
- `BR-065` requires the cascade record to preserve the cancellation reason. It is preserved through
  `job_status_history_id` — the Job cancellation that caused it, which carries that reason. Whether
  a cascade must additionally carry its own Visit-level reason is recorded as an open question
  (§21) instead of being invented.
- `ON_SITE` and `IN_PROGRESS` Visits are never cascade-canceled silently (`BR-065`): the row is
  written only after the authorized user explicitly confirmed that Visit. The row and its actor are
  the evidence; no separate boolean is modelled.
- `BR-074`'s free movement is recorded the same way as any other transition: one append-only row per
  applied change, carrying the previous status, the new status, the actor and the timestamp. The original
  row is untouched, the Visit identity does not change, and history stays append-only (`BR-067`, `BR-075`).
  `EN_ROUTE → SCHEDULED` is the one case recorded with `is_correction = true`, because `BR-075` named it.
- **Reopening a `COMPLETED` Visit** (`COMPLETED` → any other working status) writes its status row like
  any other transition and additionally **clears the Visit's current outcome columns**
  (`visits.outcome_code`, `outcome_summary`, `outcome_recorded_at`,
  `outcome_recorded_by_membership_id`) in the same transaction. The completion it reverses keeps its own
  `visit_status_history` row and its own `visit_outcome_history` row, so nothing is rewritten or removed;
  the next completion writes a **new** outcome (`BR-074`, `BR-077`, `BR-079`, §11.2). No outcome row is
  written by the reopen itself, because no outcome resulted from it.

### 9.6 Visit location and `visit_location_history`

A Visit's **operational location** is `visits.property_id` together with the frozen
`visits.location_address_snapshot` (§9.2), taken when the Visit is scheduled or when a location
review is resolved. The snapshot is a copy, never a live view of the Property row — which is what
`BR-057` requires: changing a Customer address, a Property address or a Job's Property must not
rewrite historical Visit information.

- A Visit's location never changes as a side effect of a Job Property change (`BR-056`). The Job
  change raises a review flag (§6.3) and the Manager/authorized user must explicitly review and
  resolve each affected Visit (`BR-056`, `BR-067`).
- Every location change is recorded in the append-only `visit_location_history` below: previous
  Property and snapshot, new Property and snapshot, actor, trigger and optional note (`BR-056`,
  `BR-057`, `BR-067`).
- **Modelling boundary:** `BR-056` confirms _that_ resolution is explicit and authorized, but does
  not enumerate the resolution actions (reschedule per `BR-073`, cancel per `BR-076`, accept the new
  location, or another disposition). This model therefore records what was decided without
  prescribing the decision; the missing catalogue is an open question (§21).
- Which Visit statuses permit a location change is likewise not defined. The model imposes no status
  CHECK of its own (`BR-042`) and relies on `BR-074`: a `COMPLETED`, `CANCELED` or `NO_SHOW` Visit is
  historical, so a location change is not offered for one.

Proposed table — `visit_location_history` (append-only):

| Column                                                | Type          | Null? | Notes                                                                |
| ----------------------------------------------------- | ------------- | ----- | -------------------------------------------------------------------- |
| `id`                                                  | `uuid`        | no    | PK                                                                   |
| `organization_id`                                     | `uuid`        | no    | FK → `organizations.id`                                              |
| `visit_id`                                            | `uuid`        | no    | FK → `visits.id`, `ON DELETE CASCADE`                                |
| `previous_property_id`                                | `uuid`        | yes   | `NULL` for the Visit's first location                                |
| `previous_address_snapshot`                           | `jsonb`       | yes   | The snapshot that was replaced                                       |
| `new_property_id`                                     | `uuid`        | no    | The Property now operational for the Visit                           |
| `new_address_snapshot`                                | `jsonb`       | no    | Snapshot taken at this change (`BR-057`)                             |
| `job_property_history_id`                             | `uuid`        | yes   | The Job Property change being resolved (§7.2); `NULL` when unrelated |
| `note`                                                | `text`        | yes   | Free-text explanation recorded by the resolver                       |
| `actor_membership_id`                                 | `uuid`        | no    | Who changed the location                                             |
| `recorded_at` / `captured_at` / `client_operation_id` |               |       | Event convention (§14)                                               |
| `created_at`                                          | `timestamptz` | no    |                                                                      |

- `CHECK ((previous_property_id IS NULL) = (previous_address_snapshot IS NULL))` — the previous
  location is preserved completely or not at all (`BR-057`).
- `CHECK (previous_property_id IS DISTINCT FROM new_property_id)` — a row records an actual change;
  a no-op write is not history.
- Index `(organization_id, visit_id, recorded_at)`.
- Invariant (service layer, same transaction): the newest row's `new_property_id` /
  `new_address_snapshot` equal `visits.property_id` / `visits.location_address_snapshot`, and
  resolving an open review flag (§6.3) sets that flag's `resolved_at` /
  `resolved_by_membership_id` in the same transaction, so a pending flag and an unresolved location
  can never disagree.

## 10. Assignment and technicians

### 10.1 Rules

- Technician assignment is recorded **on the Visit**, never on the Job (`BR-068`). A Job's
  technicians are **derived** from the technicians assigned across its Visits (`BR-068`); there is
  deliberately no technician column on `jobs` (§6.2).
- A Visit may have **multiple** technicians. While a Visit has technicians assigned, exactly one of
  them is the **Lead**; the others use the role code `TECHNICIAN` (`BR-068`). `LEAD` and `TECHNICIAN`
  are stable machine-readable codes; their labels are localized (`BR-028`, `BR-041`). The term
  "Helper" is not part of Servora's vocabulary (`BR-068`).
- Every assigned technician is considered scheduled for the **full** Visit interval, and all of them
  take part in conflict detection (`BR-068`, `BR-070`).
- Assignment changes — add, remove, `TECHNICIAN → LEAD`, `LEAD → TECHNICIAN` — are allowed **during
  an active Visit**, and assignment history is **append-only** (`BR-069`).
- Removing the current Lead requires the same action to name the **new Lead explicitly**; the system
  never promotes another technician automatically (`BR-069`).
- The Job **Owner** (`jobs.owner_membership_id`, §6.1) is separate from Visit assignment: the Owner
  is not thereby a Visit technician, not automatically the Lead, does not affect availability and
  does not take part in conflict detection (`BR-055`, `BR-068`).
- A Visit may exist as an unscheduled/`DRAFT` Visit with **no** technicians (`BR-068`, `BR-072`).
- Eligibility, skills, territory and travel/buffer rules are out of scope in v1 (`BR-025`, `BR-070`,
  §18).

### 10.2 Proposed table — `visit_technicians` (current assignment)

The current assignment set is stored explicitly so that dispatch and conflict queries (§10.4) stay
simple and so that "at most one Lead per Visit" is enforceable by the database. History is separate
(§10.3).

| Column                      | Type          | Null? | Notes                                                             |
| --------------------------- | ------------- | ----- | ----------------------------------------------------------------- |
| `id`                        | `uuid`        | no    | PK                                                                |
| `organization_id`           | `uuid`        | no    | FK → `organizations.id`                                           |
| `visit_id`                  | `uuid`        | no    | FK → `visits.id`, `ON DELETE CASCADE`                             |
| `technician_membership_id`  | `uuid`        | no    | FK → `organization_members.id` (foundation), `ON DELETE RESTRICT` |
| `role_code`                 | `varchar(20)` | no    | CHECK `IN ('LEAD','TECHNICIAN')`                                  |
| `created_at` / `updated_at` | `timestamptz` | no    | Foundation convention                                             |

Constraints and indexes:

- `UNIQUE (organization_id, visit_id, technician_membership_id)` — the same technician cannot be
  assigned twice to one Visit.
- `UNIQUE (visit_id) WHERE role_code = 'LEAD'` (partial unique index) — **at most one Lead per
  Visit** (`BR-068`). The complementary "at least one Lead while technicians are assigned" is not
  expressible as a plain constraint, so it is enforced as a scheduling precondition in the service
  layer (`BR-072`, §9.3).
- Index `(organization_id, technician_membership_id)` — conflict detection by technician (§10.4).
- This table holds the **current** state only. Removing a technician deletes the row; it never edits
  history (§10.3). `BR-069`'s "never rewrite previous assignment history" applies to the history
  table.

### 10.3 Proposed table — `visit_technician_history` (append-only)

| Column                                                | Type          | Null? | Notes                                              |
| ----------------------------------------------------- | ------------- | ----- | -------------------------------------------------- |
| `id`                                                  | `uuid`        | no    | PK                                                 |
| `organization_id`                                     | `uuid`        | no    | FK → `organizations.id`                            |
| `visit_id`                                            | `uuid`        | no    | FK → `visits.id`, `ON DELETE CASCADE`              |
| `technician_membership_id`                            | `uuid`        | no    | The technician the event concerns                  |
| `event`                                               | `varchar(20)` | no    | CHECK `IN ('ASSIGNED','REMOVED','ROLE_CHANGED')`   |
| `role_code`                                           | `varchar(20)` | yes   | Role **after** the event; `NULL` for `REMOVED`     |
| `previous_role_code`                                  | `varchar(20)` | yes   | Role **before** the event; only for `ROLE_CHANGED` |
| `actor_membership_id`                                 | `uuid`        | no    | Who performed the change                           |
| `recorded_at` / `captured_at` / `client_operation_id` |               |       | Event convention (§14)                             |
| `created_at`                                          | `timestamptz` | no    |                                                    |

Checks:

- `CHECK (event <> 'ASSIGNED' OR (role_code IS NOT NULL AND previous_role_code IS NULL))`.
- `CHECK (event <> 'REMOVED' OR (role_code IS NULL AND previous_role_code IS NOT NULL))`.
- `CHECK (event <> 'ROLE_CHANGED' OR (role_code IS NOT NULL AND previous_role_code IS NOT NULL
AND role_code <> previous_role_code))`.
- `CHECK (role_code IS NULL OR role_code IN ('LEAD','TECHNICIAN'))`, and the same for
  `previous_role_code`.
- Index `(organization_id, visit_id, recorded_at)`.

- The append-only history is what makes `BR-069` auditable: `REMOVED` never erases the earlier
  `ASSIGNED` row, and a Lead change writes new rows rather than updating the past. `BR-069`'s
  example (Mike `LEAD`, John `TECHNICIAN`, Sarah `TECHNICIAN`, John removed, Sarah promoted to
  `LEAD`) is exactly five rows, alongside the resulting rows in `visit_technicians` (§10.2).
- Removing the Lead is one user action producing two recorded facts: the removed Lead's `REMOVED`
  row and the explicitly chosen new Lead's `ROLE_CHANGED` row, both with the same actor. No separate
  "reassignment" record type is invented.

### 10.4 Technician availability conflicts (`BR-070`)

- A conflict is a **warning with mandatory explicit confirmation**, never a hard block in v1
  (`BR-070`).
- A conflict exists when a technician assigned to the Visit being scheduled is also assigned
  (present in `visit_technicians`, §10.2) to **another non-canceled Visit** whose window overlaps:

```text
existing.scheduled_start < new.scheduled_end
AND existing.scheduled_end > new.scheduled_start
```

- Detection uses the **internal** `scheduled_start` / `scheduled_end`, which are authoritative
  (`BR-070`, `BR-072`); the customer-facing arrival window is not used. All technicians assigned to
  the Visit are checked, because each is booked for the whole interval (`BR-068`, `BR-070`).
  Different technicians never conflict (`BR-070`). A `DRAFT` Visit has no schedule and cannot be in
  conflict until it is scheduled; the Visit being scheduled is excluded from its own check.
- `BR-070` excludes **canceled** Visits only. Visits in every other status are compared, because
  the rule names cancelled Visits as the sole exclusion; in practice an overlap requires
  intersecting windows, so a historical Visit only matches when a schedule is genuinely backdated.
- The API must **surface** the conflict — the conflicting Visit, its window and the technician(s) —
  and the authorized user must confirm explicitly before the Visit can become `SCHEDULED`
  (`BR-070`, §9.3).
- The confirmation is recorded as a snapshot on the event that changed the schedule: the
  `DRAFT → SCHEDULED` row in `visit_status_history` (§9.5) and the `visit_schedule_history` row
  (§9.4) each carry `confirmed_conflicts jsonb` — the conflicting Visit ids, their windows and the
  technician ids as they were shown to and accepted by the user. This is a deliberate application of
  `BR-067` ("history must preserve what actually happened"): a knowingly accepted overlap is part of
  what happened. It records no new business rule.
- Overlapping assignments remain **allowed** once confirmed; nothing in the model prevents them
  (`BR-070`).
- The check, the confirmation and the transition happen in the same transaction under the Visit row
  lock, so a concurrent assignment change cannot slip between check and commit (§15).
- Travel/buffer time is out of scope in v1 (`BR-070`, §18).

## 11. Visit outcome

### 11.1 Rules

- A Visit **cannot be completed without an outcome**: before `IN_PROGRESS → COMPLETED` the Technician
  supplies an outcome type (`BR-078`) and an outcome summary (`BR-077`).
- Visit notes/comments are optional but strongly encouraged; photos, audio and files are optional in
  v1 unless a future workflow requires evidence (`BR-077`, §12, §18).
- The outcome records its **authoring actor and a timestamp** (`BR-077`).
- Outcome type codes are the closed `BR-078` vocabulary: `RESOLVED`, `NEEDS_PARTS`,
  `NEEDS_FOLLOWUP`, `NEEDS_QUOTE_APPROVAL`, `UNABLE_TO_COMPLETE`. They are stable machine-readable
  codes; labels are localized (`BR-028`, `BR-041`, `BR-078`).
- **Follow-up expectation is derived from the outcome type** (`RESOLVED` — none expected;
  `NEEDS_PARTS`, `NEEDS_FOLLOWUP`, `UNABLE_TO_COMPLETE` — follow-up expected; `NEEDS_QUOTE_APPROVAL`
  — business follow-up expected) and is never stored as a boolean (`BR-078`).
- A follow-up expectation is not the same thing as a follow-up request. The expectation is derived
  from the Visit outcome; the request is a separate workflow record created by an assigned
  technician, unless a technician has explicit scheduling permission and creates the follow-up Visit
  directly (`BR-FV-001`, `BR-FV-011`).
- Outcome and status are different things (`BR-078`, §9.1): a Visit may be `COMPLETED` while its Job
  remains `IN_PROGRESS` (`BR-059`, `BR-077`). A new **field attempt** is a new Visit, never an edit of a
  completed one. Reopening a completed Visit (`BR-074`) is not a new attempt: it corrects the status of
  the attempt that already exists, and that attempt must record a new outcome at its next completion.
- Job effect (`BR-061`): a Job may enter `PENDING_REVIEW` only when no Visit remains active or
  scheduled and either the latest completed Visit outcome indicates the Job may be resolved
  (`RESOLVED`) or an authorized rejection of the follow-up request because no additional Visit is
  required resolves that requirement for review entry (`BR-FV-004`, `BR-FV-007`). `NEEDS_PARTS`,
  `NEEDS_FOLLOWUP` and `UNABLE_TO_COMPLETE` keep the Job `IN_PROGRESS` while the follow-up
  requirement remains unresolved. The Job-status effect of `NEEDS_QUOTE_APPROVAL` is an **OPEN
  QUESTION** (`BR-061`, §21), so it is treated as **not modelled**: an `IN_PROGRESS` Job is not
  moved to `PENDING_REVIEW` on that outcome, and no alternative transition is invented for it.
- Corrections (`BR-079`): every outcome change preserves the previous outcome, the new outcome, the
  actor, the timestamp and an optional reason. Managers/authorized office users may correct outcomes
  according to their permissions (`BR-006`, `BR-066`). The **technician self-edit window/policy is
  undefined** (`BR-079`): no time window is invented, modelled or enforced.
- **Immutability** (`BR-062`, `BR-079`): once the Job is `COMPLETED`, Visit outcomes are immutable.
  This is enforced in the service layer from the Job's status inside the same transaction that would
  change the outcome. Reopening a Job (`BR-063`) does not reopen historical Visits, so it does not
  un-freeze their outcomes; the boundary case is recorded in §21. A Visit's own reopen (`BR-074`) is a
  **field** action and is refused while the Job is `COMPLETED` or `CANCELED`
  (`JOB_CLOSED_FOR_FIELD_WORK`), so it can never be used to make a closed Job's outcomes mutable.

### 11.2 Current outcome and `visit_outcome_history`

The Visit's **current** outcome lives on `visits` (§9.2) as `outcome_code`, `outcome_summary`,
`outcome_recorded_at` and `outcome_recorded_by_membership_id`. This section defines their semantics
and constraints. Every outcome and every correction is additionally recorded in the append-only
`visit_outcome_history`.

Current outcome columns and constraints on `visits`:

- `outcome_code varchar(30) NULL` — CHECK against the five `BR-078` codes.
- `outcome_summary text NULL` — the required summary of the field attempt.
- `outcome_recorded_at timestamptz NULL` and
  `outcome_recorded_by_membership_id uuid NULL` (FK → `organization_members.id`, `ON DELETE RESTRICT`) — the
  authoring actor and time (`BR-077`).
- `CHECK ((outcome_code IS NULL) = (outcome_recorded_at IS NULL))` and
  `CHECK ((outcome_recorded_at IS NULL) =
(outcome_recorded_by_membership_id IS NULL))` — the outcome fields are written together.
- `CHECK (status <> 'COMPLETED' OR (outcome_code IS NOT NULL AND outcome_summary IS NOT NULL))` —
  a completed Visit always carries an outcome (`BR-077`). The outcome columns and the status change
  to `COMPLETED` are written by one statement, or by one transaction, so this holds at every commit.
- Note: a _draft_ outcome is not modelled. `BR-077` requires the outcome at completion; storing a
  partial, unsubmitted outcome is not defined by the business rules and is therefore not invented.
- Note: **reopening** a completed Visit (`BR-074`) clears all four current-outcome columns in the same
  transaction that changes the status, so a working Visit carries no current outcome. That is why none of
  the checks above is violated: `status <> 'COMPLETED'` makes the outcome columns free to be `NULL`, and
  the pair checks are satisfied because they are cleared together. The `visit_outcome_history` row of the
  completion that was reversed stays exactly as it was written (`BR-067`, `BR-079`).

Proposed table — `visit_outcome_history` (append-only):

| Column                                                | Type          | Null? | Notes                                 |
| ----------------------------------------------------- | ------------- | ----- | ------------------------------------- |
| `id`                                                  | `uuid`        | no    | PK                                    |
| `organization_id`                                     | `uuid`        | no    | FK → `organizations.id`               |
| `visit_id`                                            | `uuid`        | no    | FK → `visits.id`, `ON DELETE CASCADE` |
| `outcome_code`                                        | `varchar(30)` | no    | CHECK against the `BR-078` vocabulary |
| `outcome_summary`                                     | `text`        | no    | Summary as recorded                   |
| `previous_outcome_code`                               | `varchar(30)` | yes   | `NULL` for the first recorded outcome |
| `previous_outcome_summary`                            | `text`        | yes   | The summary that was replaced         |
| `reason`                                              | `text`        | yes   | Optional correction reason (`BR-079`) |
| `actor_membership_id`                                 | `uuid`        | no    | Who recorded the outcome              |
| `recorded_at` / `captured_at` / `client_operation_id` |               |       | Event convention (§14)                |
| `created_at`                                          | `timestamptz` | no    |                                       |

Checks:

- `CHECK (outcome_code IN (...))` for the five codes.
- `CHECK (previous_outcome_code IS NULL OR previous_outcome_code IN (...))`.
- `CHECK ((previous_outcome_code IS NULL) = (previous_outcome_summary IS NULL))` — a correction
  always preserves the complete previous outcome (`BR-079`). A row whose
  `previous_outcome_code IS NULL` is the Visit's initial outcome; a row with a previous value is a
  correction. No separate flag is needed, and none is added.
- Index `(organization_id, visit_id, recorded_at)`.
- Invariant (service layer, same transaction): the newest row's `outcome_code` /
  `outcome_summary` / actor match the current columns on `visits`.
- The outcome record and the `IN_PROGRESS → COMPLETED` status row (§9.5) are written in the same
  transaction, so `BR-077`'s "outcome before completion" is never observable as a completed Visit
  without an outcome.

### 11.3 Follow-up Visit requests (`BR-FV-001` – `BR-FV-013`)

A follow-up request records that an assigned technician believes another field attempt is needed.
It is **not** a Visit, not a schedule and not a confirmed appointment (`BR-FV-002`, `BR-FV-010`).
It stays on the Job until an authorized decision is made.

Rules:

- Only a technician assigned to the source Visit may submit the request unless they also hold direct
  scheduling permission and create the follow-up Visit directly (`BR-FV-001`, `BR-FV-011`).
- Every request references the source Visit, and that Visit must belong to the same Job and
  organization (`BR-FV-008`).
- Proposed date/time, expected duration, notes and same-technician preference are informational
  until an authorized user approves and schedules the request (`BR-FV-003`, `BR-FV-010`).
- Office review is permission-based, never role-name based. A Manager, Dispatcher, Scheduler or
  custom role may act only through the appropriate effective permission (`BR-004`, `BR-006`,
  `BR-FV-004`).
- Approval creates the new Visit in the same transaction and records the resulting `visit_id`.
  The new Visit then follows the normal Visit scheduling, assignment and conflict rules (`BR-068`,
  `BR-070`, `BR-072`, `BR-FV-005`).
- A request returned for clarification remains unresolved; a rejection means no new Visit is created
  and records the office decision that no additional Visit is required or why the request was refused
  (`BR-FV-004`, `BR-FV-012`, `BR-FV-013`).
- A technician may complete the current Visit after submitting the request; the Job remains open
  while the follow-up requirement is unresolved (`BR-FV-006`, `BR-FV-007`).
- Multiple requests are allowed over a Job's lifetime. Each request is independently traceable and
  may originate from a different completed Visit (`BR-FV-009`).

Proposed table — `follow_up_visit_requests`:

| Column                         | Type          | Null? | Notes                                                                   |
| ------------------------------ | ------------- | ----- | ----------------------------------------------------------------------- |
| `id`                           | `uuid`        | no    | PK, `gen_random_uuid()`                                                 |
| `organization_id`              | `uuid`        | no    | FK → `organizations.id`, `ON DELETE CASCADE`                            |
| `job_id`                       | `uuid`        | no    | FK → `jobs.id`, `ON DELETE CASCADE`                                     |
| `source_visit_id`              | `uuid`        | no    | FK → `visits.id`; the Visit that caused the request (`BR-FV-008`)       |
| `requested_by_membership_id`   | `uuid`        | no    | Assigned technician who submitted the request                           |
| `status`                       | `varchar(24)` | no    | CHECK against §11.3's status vocabulary                                 |
| `reason`                       | `text`        | yes   | Why another Visit is needed                                             |
| `proposed_start`               | `timestamptz` | yes   | Informational preferred/customer-agreed time (`BR-FV-003`, `BR-FV-010`) |
| `proposed_end`                 | `timestamptz` | yes   | Informational proposed end                                              |
| `expected_duration_minutes`    | `integer`     | yes   | Informational duration when no exact proposed end is known              |
| `same_technician_preferred`    | `boolean`     | no    | `NOT NULL DEFAULT false`; preference only, not assignment               |
| `notes`                        | `text`        | yes   | Technician-entered notes                                                |
| `reviewed_by_membership_id`    | `uuid`        | yes   | Who returned, approved or rejected the request                          |
| `reviewed_at`                  | `timestamptz` | yes   | When the current review decision was made                               |
| `decision_note`                | `text`        | yes   | Clarification request or rejection/approval note                        |
| `created_visit_id`             | `uuid`        | yes   | FK → `visits.id`; set only when approved                                |
| `recorded_at` / `captured_at`  |               |       | Event convention (§14)                                                  |
| `client_operation_id`          | `uuid`        | yes   | Offline replay idempotency (§15)                                        |
| `created_at` / `updated_at`    | `timestamptz` | no    | Foundation convention                                                   |

Status vocabulary:

| Code                  | Meaning                                                                  |
| --------------------- | ------------------------------------------------------------------------ |
| `PENDING`             | Submitted and awaiting office review                                     |
| `NEEDS_CLARIFICATION` | Returned to the technician or field team for more information            |
| `APPROVED`            | Approved; the resulting Visit has been created                           |
| `REJECTED`            | Rejected because no additional Visit is required or the request is refused |

Constraints and indexes:

- `CHECK (status IN ('PENDING','NEEDS_CLARIFICATION','APPROVED','REJECTED'))`.
- `CHECK ((proposed_start IS NULL) = (proposed_end IS NULL))` and
  `CHECK (proposed_end IS NULL OR proposed_end > proposed_start)`.
- `CHECK (expected_duration_minutes IS NULL OR expected_duration_minutes > 0)`.
- `CHECK ((status IN ('APPROVED','REJECTED','NEEDS_CLARIFICATION')) =
  (reviewed_by_membership_id IS NOT NULL AND reviewed_at IS NOT NULL))`.
- `CHECK ((status = 'APPROVED') = (created_visit_id IS NOT NULL))`.
- Invariant (service layer): `source_visit_id` belongs to `job_id` and to the same organization.
- Invariant (service layer): approval creates `created_visit_id` in the same transaction as the
  status change to `APPROVED`.
- Indexes: `(organization_id, job_id, status)`, `(organization_id, source_visit_id)` and
  `(organization_id, requested_by_membership_id, recorded_at)`.
- Unique partial index on `(organization_id, client_operation_id) WHERE client_operation_id IS NOT
  NULL` for offline idempotency.

The request's current `status` is stored for operational reads. The audit requirement is met by the
request row plus `requested_by_membership_id`/`recorded_at`,
`reviewed_by_membership_id`/`reviewed_at`, `decision_note` and `created_visit_id` (`BR-FV-013`).
If later product requirements need every return-for-clarification/resubmission as separate events,
that can be split into an append-only history table without changing the rule that the request itself
has one explicit current lifecycle state.

## 12. Visit notes and evidence

### 12.1 Rules

- Visit notes/comments are optional in v1 (`BR-077`) and are **user-entered content**: stored and
  displayed exactly as entered, never translated and never replaced by localized text
  (`Project.md` §10, `dev.md` §9).
- A note is part of the record of what happened in the field (`BR-027`) and is included in the Job
  Activity projection (`BR-080`, §17).
- Notes are **per Visit**, because the business rules attach field evidence to the field attempt
  (`BR-047`, `BR-071`). The Job view aggregates the notes of its Visits (`BR-080`). No Job-level note
  concept is modelled, because no Job note is confirmed (`BR-042`).
- Notes are append-only in this model: one row per note, carrying its author and time. The model
  provides no update or delete path, because `BR-027` requires evidence not to be silently discarded
  and `BR-067` requires history to preserve what happened. Whether a note may ever be edited or
  deleted is **not defined** by the business rules and is recorded as an open question (§21); no
  edit window is invented.
- Photos, audio and files are optional Visit evidence in v1 (`BR-077`) and no confirmed business rule
  defines their storage, retention or visibility model (`BR-027`, `BR-015`), so **no attachment
  table is proposed here** (§18).

### 12.2 Proposed table — `visit_notes` (append-only)

| Column                                                | Type          | Null? | Notes                                                |
| ----------------------------------------------------- | ------------- | ----- | ---------------------------------------------------- |
| `id`                                                  | `uuid`        | no    | PK                                                   |
| `organization_id`                                     | `uuid`        | no    | FK → `organizations.id`                              |
| `visit_id`                                            | `uuid`        | no    | FK → `visits.id`, `ON DELETE CASCADE`                |
| `author_membership_id`                                | `uuid`        | no    | FK → `organization_members.id`, `ON DELETE RESTRICT` |
| `body`                                                | `text`        | no    | User-entered text, stored as entered                 |
| `recorded_at` / `captured_at` / `client_operation_id` |               |       | Event convention (§14)                               |
| `created_at`                                          | `timestamptz` | no    |                                                      |

- No `updated_at`: there is no update path (§12.1).
- Index `(organization_id, visit_id, recorded_at)` — notes are always read for a Visit, newest first
  in the activity projection (`BR-080`).
- The API rejects an empty or whitespace-only body as input validation (`dev.md` §7). No maximum
  length is asserted here; a limit is an API-contract decision, not a domain rule.

## 13. Job numbering (`BR-052`)

- `jobs.job_number` is a **sequential integer, unique within its organization**, assigned at
  creation and **never changed** (`BR-052`, §2.2). It may repeat across different organizations.
- It is never the primary key and must never be used as a cross-system or API key (`BR-052`). API
  paths and foreign keys use `jobs.id`.
- The number-allocation mechanism is explicitly an implementation concern of this slice (`BR-052`
  Notes), so the proposed mechanism is stated here rather than left implicit.

Proposed constraints and table:

- `jobs.job_number integer NOT NULL`, `CHECK (job_number > 0)`, with
  `UNIQUE (organization_id, job_number)` — the uniqueness `BR-052` requires is enforced by the
  database, not only by application code.
- `organization_job_number_counters`:

| Column            | Type          | Null? | Notes                                            |
| ----------------- | ------------- | ----- | ------------------------------------------------ |
| `organization_id` | `uuid`        | no    | PK, FK → `organizations.id`, `ON DELETE CASCADE` |
| `last_job_number` | `integer`     | no    | `NOT NULL DEFAULT 0`                             |
| `updated_at`      | `timestamptz` | no    | Foundation convention                            |

- Allocation happens inside the Job-creation transaction, in one statement that both bootstraps the
  counter for a new organization and increments it:

```sql
INSERT INTO organization_job_number_counters (organization_id, last_job_number)
VALUES ($1, 1)
ON CONFLICT (organization_id)
DO UPDATE SET last_job_number = organization_job_number_counters.last_job_number + 1
RETURNING last_job_number;
```

- The inserted value states the **first** number explicitly: `DO UPDATE` runs only on conflict, so a
  fresh counter row must already carry the number the creation takes (otherwise a new organization's
  first Job would read the column's `0` default and fail `CHECK (job_number > 0)`).

- The counter row is the serialization point for concurrent Job creation within one organization
  (§15); the counter is monotonic and never moves backwards.
- Because the increment and the Job insert share a transaction, a rolled-back creation returns its
  number to the pool instead of consuming it. **No gap-free guarantee is claimed**: `BR-052`
  requires uniqueness and immutability, not a gapless sequence, and inventing a gapless guarantee
  would add a business property the product never asked for.
- Numbering starts at 1 for a new organization. `BR-052` does not define a starting value; the
  counter's default is the simplest implementation consistent with "sequential integer", and it
  carries no product meaning.
- Display of `Job #<number>` is a presentation concern and follows the shared localization rules
  (`BR-028`, §19).

## 14. Event convention: business time and offline identifiers

Every history/event table in this domain carries the same columns with the same meaning. This
section is their single definition (`BR-033`, `BR-041`).

| Column                | Type          | Null? | Meaning                                                                      |
| --------------------- | ------------- | ----- | ---------------------------------------------------------------------------- |
| `recorded_at`         | `timestamptz` | no    | Server-authoritative business time of the event (`NOT NULL DEFAULT now()`)   |
| `captured_at`         | `timestamptz` | yes   | Time the action happened on the client device, as claimed by the client      |
| `client_operation_id` | `uuid`        | yes   | Client-generated identifier of the offline operation that produced the event |
| `created_at`          | `timestamptz` | no    | Row insertion time (`NOT NULL DEFAULT now()`)                                |

Rules:

- **`recorded_at` is authoritative** for ordering, history display, reporting and audit. It is set by
  the backend when the event is applied (`BR-001`, `BR-033`).
- `captured_at` is **client-claimed evidence, not authority** (`BR-013`, `BR-031`). It may be absent
  (online action, or a client that does not supply it), it is normally earlier than `recorded_at`
  when the action was performed offline, and it may be skewed by an incorrect device clock. The
  backend stores what the client claimed and does not silently rewrite it, because `BR-014` and
  `BR-027` require field work to be preserved rather than corrected behind the user's back. It must
  never be used as the authoritative time for a business decision that depends on the true order of
  events.
- `client_operation_id` supports idempotent replay of offline operations (`BR-031`, §15). It is
  scoped, not global: within each event table it is unique per `organization_id`
  (`UNIQUE (organization_id, client_operation_id)` where the column is non-null), which lets the
  backend recognise a replayed offline operation per table without inventing a global operation
  identity the business rules do not define.
- `recorded_at` and `created_at` are normally equal, because the backend records an event when it
  accepts it. They are kept separate so that an event whose business time is defined by a rule —
  for example a cascade record written as part of a wider transaction (`BR-065`) — can carry that
  time without redefining the row's insertion time. Where no rule defines otherwise, the two are
  written together.
- Timestamps are absolute instants (`timestamptz`, stored in UTC). Rendering them in a user's
  language/locale is presentation (`BR-028`). Scheduling time-zone semantics are **open**
  (`BR-026`, §21) and are not decided here.
- No event table stores a localized string; reasons, notes, summaries and explanations are stored
  as the text the actor entered (`Project.md` §10, §19).

## 15. Concurrency, idempotency and conflict behaviour

`BR-031` requires offline synchronization to preserve business integrity, and `BR-032` forbids
inventing conflict behaviour. This section records only what the confirmed rules imply.

- **Optimistic concurrency**: `jobs` and `visits` carry a `version` column (§2.2, §9.2). Mutations
  that change business state require the caller's expected version; a mismatch is rejected as a
  conflict rather than applied. This is the mechanism `BR-001`'s "backend always takes precedence"
  and `BR-031`'s "not authoritative until accepted by the backend" rely on.
- **Idempotent replay**: an offline operation replays with its `client_operation_id` (§14). A repeat
  of an operation already applied to a table is recognised and does not create a second event or a
  second business outcome (`BR-031`).
- **Row locking for invariants**: transitions that must not interleave are validated under the
  relevant row lock in the same transaction — the Visit lifecycle (`BR-074`–`BR-076`, §9), the
  "at most one Lead" precondition and the conflict check (`BR-068`, `BR-070`, §10.4), Job numbering
  (§13) and Job completion (`BR-062`).
- **Append-only history vs. current state**: history tables are only inserted into (§14). Current
  state tables are updated, and both the state change and its history row are written in the same
  transaction, so history is never missing an applied change (`BR-067`).
- **Conflict behaviour is per operation, not generic** (`BR-032`). Where a specific conflict
  strategy is required but not yet defined by the business rules, it is left open (§21) and is not
  implemented as last-write-wins.

## 16. Physical deletion of a Job — the open decision

Deleting a Job is the one part of this domain the business rules do not settle, so it is stated here
explicitly instead of being answered by default behaviour.

> This section is about **Jobs** only. The **Property** lifecycle — archive, restore and permanent
> deletion — is defined by `BR-082` – `BR-086` (§3.3) and does not answer the Job question.

**What is confirmed**

- `BR-008` grants the default Manager role a delete capability for Jobs.
- `BR-021` records, as an explicit **OPEN QUESTION**, "how deletion of a Job relates to preserved
  business history".
- `BR-057` and `BR-067` require historical Job and Visit information to be preserved: location
  history is append-only and status, schedule, assignment, cancellation and outcome history must
  not be rewritten.
- Deleting a Job cascades inside its own aggregate (§6.4): a hard delete would remove the Job's
  Visits and every history row that hangs off them, erasing the history the rules require to be
  preserved.

**What this model therefore does**

- No deletion path is modelled: no `DELETE` semantics, no `deleted_at`, no tombstone column, no
  "archived" status value. None of these is defined by a confirmed rule, so none is invented
  (`BR-042`).
- No delete endpoint is designed in this slice. If one is added before the question is resolved it
  would be implementing an undecided business behaviour.
- The references that _would_ be destroyed by a Job delete all fail closed in the meantime: a
  Customer, Property or member referenced by Jobs and Visits cannot be deleted while those
  references exist (§6.4), which is the safe direction for `BR-057` and `BR-067`.

**What the eventual decision must respect**

- `BR-057`/`BR-067`: history is append-only; deleting a Job must not silently rewrite what already
  happened.
- `BR-033`: important changes remain traceable, which argues for an explicit, recorded deletion or
  closing action rather than a disappearance.
- `BR-064`/`BR-062`: canceling and completing already exist as explicit, history-preserving
  terminal actions, so they are available when the product needs a Job to stop being active work.
- Whatever the decision is, it is a change to the business rules first (`BR-040`) and a schema
  change with a migration second (`Project.md` §8, `dev.md` §6).

The question is repeated in the open-questions register (§21) and must be resolved before any
delete endpoint or tombstone column is designed.

## 17. Job Activity projection (`BR-080`)

Job Activity is a **derived read model**, not an entity: it is a chronological projection over the
authoritative records this document defines. It introduces **no table of its own** and must never
become a second source of truth (`BR-080`, `BR-001`).

| `BR-080` activity source                                       | Authoritative record               |
| -------------------------------------------------------------- | ---------------------------------- |
| Job status changes (incl. completion, cancellation, reopening) | `job_status_history` (§7.1)        |
| Visit status changes (incl. corrections and cancellation)      | `visit_status_history` (§9.5)      |
| Visit notes                                                    | `visit_notes` (§12)                |
| Outcome changes                                                | `visit_outcome_history` (§11.2)    |
| Follow-up request decisions                                    | `follow_up_visit_requests` (§11.3) |
| Assignment changes                                             | `visit_technician_history` (§10.3) |
| Schedule changes                                               | `visit_schedule_history` (§9.4)    |
| Job Property changes                                           | `job_property_history` (§7.2)      |
| Visit location resolution                                      | `visit_location_history` (§9.6)    |
| Photos, audio and files                                        | Not modelled in v1 (§18)           |

- **Ordering**: newest first by default, ordered by `recorded_at` (§14, `BR-080`). `captured_at` may
  be displayed as "performed at" but never re-orders the authoritative history (§14).
- **Content**: who (the actor membership referenced by the record) → when (`recorded_at`) → what
  happened (the record and its codes). Actor names and avatars come from the foundation model
  (`organization_members`/`users`); they are never copied into event tables.
- **Stable codes, localized labels**: each projected event carries a stable machine-readable type
  derived from its source record and event columns. The localized label for a type code is resolved
  by the client (`BR-028`, `BR-041`, `Project.md` §10). The canonical activity-type code list is
  part of the shared API contract (`@servora/shared-types`), defined once for both clients, not a
  separate stored vocabulary (`BR-041`).
- **No write path**: Activity is read-only. Nothing writes to "activity"; writing goes to the
  authoritative records listed above, which is what keeps `BR-067` true.
- **Authorization**: the projection applies the same authorization and tenant scoping as reading the
  underlying Job and Visit data (`BR-001`, `BR-006`, §2.1). The default Technician permissions are
  defined by `BR-009`; direct member permissions may expand a member's effective permission set.
- **Offline**: Activity adds no synchronization of its own (`BR-031`). A client can only project the
  records it is entitled to and actually holds.

## 18. Deliberately out of scope in v1

These are named by the business rules but are **not** modelled in this slice. Each one is either
confirmed out of scope or still an open product question; none of them may be filled in later
without a business decision (`BR-042`).

| Not modelled here                                 | Rule                         | Disposition                                                                                                                                                            |
| ------------------------------------------------- | ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Job priority                                      | `BR-054`                     | Confirmed out of v1. No column, no derived priority, no automatic scheduling order.                                                                                    |
| Job category catalogue                            | `BR-053`                     | Open. `type_code` is an optional free code with no CHECK (§6.1, §6.2); no behaviour may branch on it.                                                                  |
| Visit evidence attachments (photos, audio, files) | `BR-015`, `BR-027`, `BR-077` | Optional in v1. Storage, retention, visibility and audit rules are open; `BR-015`'s evidence-preservation requirement applies once the evidence slice defines storage. |
| GPS / location capture                            | `BR-038`                     | Open. No location columns on Visits, no geolocation of field actions.                                                                                                  |
| Time tracking                                     | `BR-034`                     | Open. No timers, no time entries, no billing basis.                                                                                                                    |
| Parts and materials                               | `BR-035`                     | Open.                                                                                                                                                                  |
| Assets and equipment                              | `BR-036`                     | Open.                                                                                                                                                                  |
| Invoices and billing                              | `BR-037`                     | Open.                                                                                                                                                                  |
| Technician eligibility, skills, territory         | `BR-025`                     | Open. Assignment records who is assigned; nothing about who is _eligible_.                                                                                             |
| Travel/buffer time and capacity planning          | `BR-026`, `BR-070`           | Open. Conflict detection uses the internal Visit window only.                                                                                                          |
| Time-zone semantics                               | `BR-026`                     | Open. Instants (`timestamptz`) only; no organization or Visit time-zone field (§9.3).                                                                                  |
| Notifications                                     | `BR-029`                     | Open. No notification behaviour is implied by any event table in this document.                                                                                        |
| Reporting and metrics                             | `BR-030`                     | A separate management capability, not part of this domain model.                                                                                                       |
| Offline conflict rules per operation              | `BR-032`                     | Open. Synchronization mechanics belong to the offline-first architecture standard (`Project.md` §13).                                                                  |
| Organization and multi-tenancy administration     | `BR-039`                     | Foundation concern; this document only carries `organization_id` scoping.                                                                                              |
| Audit retention and visibility                    | `BR-033`                     | Open. The history tables exist; how long they are kept and who may read them is undecided.                                                                             |

## 19. Localization of domain values

- Every closed vocabulary in this model is stored as a **stable, machine-readable code** — Job
  statuses (`BR-058`), Visit statuses (`BR-074`), outcome types (`BR-078`), Visit cancellation
  reasons (`BR-076`), assignment role codes `LEAD`/`TECHNICIAN` (`BR-068`) and follow-up request
  statuses (`BR-FV-012`) — never as display text (`BR-028`, `BR-041`, `Project.md` §10, `dev.md`
  §9).
- Localized labels for those codes are resolved in the clients' message catalogs (`Angular.md`,
  `Android.md`) and, for system-generated API messages, through the shared API conventions in
  `docs/api/`. This document defines no new localization mechanism and stores no display text.
- Derived conditions are labels, not values: "Needs Scheduling" / "No Active Visit" (`BR-060`),
  "Location Changed — Review Schedule" (`BR-056`, §6.3) and the follow-up expectation of an outcome
  (`BR-078`) are computed from stored codes and localized for display. None is persisted as text and
  none may be persisted as a status (`BR-060`, `BR-042`).
- **No field in this domain requires bilingual storage.** Job title/description (`BR-053`), Visit
  notes and outcome summaries (`BR-077`), schedule-change reasons (`BR-073`) and location-resolution
  notes (§9.6), and follow-up request reasons, notes and decision notes (§11.3), are
  **user-entered content** and stay in the language the user wrote them in (`Project.md` §10,
  `dev.md` §9). Bilingual _business data_ is required for roles (`BR-005`), which belongs to the
  foundation model, not here.
- Because no language-specific column exists, adding a third language later is catalog work with no
  schema change (`BR-028`).

## 20. Continuity with the foundation domain model

This document **extends** `docs/domain/foundation-domain-model.md`. It does not redefine, replace or
modify any foundation entity, and it does not alter a foundation table.

**Foundation concepts reused**

| Foundation concept     | How this slice uses it                                                                                                                                                                             |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `organizations`        | The tenant boundary. Every table in this slice carries `organization_id` and every query is tenant-scoped (`BR-001`, `BR-039`).                                                                    |
| `organization_members` | The actor identity for every event row (`actor_membership_id`) and for the Job Owner (`owner_membership_id`). A Job Owner is a member of the owning organization, never a bare user id (`BR-055`). |
| `users`                | Profile and photo (`BR-020`). Users appear here only through organization membership.                                                                                                              |
| `customers`            | The party receiving service (`BR-023`). A Job always belongs to exactly one Customer (`BR-048`), and the Property ↔ Customer relationship is modelled in §4.                                       |

**Tables proposed by this slice**

| Table                              | Section | Purpose                                                                                                        |
| ---------------------------------- | ------- | -------------------------------------------------------------------------------------------------------------- |
| `properties`                       | §3.1    | Organization-owned service location with a structured address and lifecycle state (`BR-049`, `BR-082`).        |
| `property_lifecycle_history`       | §3.3    | Append-only Property archive/restore events (`BR-082`, `BR-086`).                                              |
| `property_customer_relationships`  | §4.1    | Append-only history of the single active Property ↔ Customer relationship (`BR-050`).                          |
| `job_customer_history`             | §5.2    | Append-only history of a Job's Customer (`BR-048`).                                                            |
| `jobs`                             | §6.1    | The work request, with the immutable Job number and the address snapshot (`BR-052`, `BR-056`).                 |
| `job_status_history`               | §7.1    | Append-only Job status events, including completion, cancellation and reopening (`BR-058`, `BR-062`–`BR-064`). |
| `job_property_history`             | §7.2    | Append-only history of the Job's Property and its snapshot (`BR-056`, `BR-057`).                               |
| `visits`                           | §9.2    | One field attempt, with scheduling, status and operational location (`BR-071`, `BR-072`, `BR-074`).            |
| `visit_technicians`                | §10.2   | Current technician assignment and Lead role on a Visit (`BR-068`).                                             |
| `visit_technician_history`         | §10.3   | Append-only assignment history (`BR-069`).                                                                     |
| `visit_schedule_history`           | §9.4    | Append-only schedule changes, including confirmed conflicts (`BR-073`).                                        |
| `visit_status_history`             | §9.5    | Append-only Visit status events, corrections and cancellations (`BR-074`–`BR-076`).                            |
| `visit_outcome_history`            | §11.2   | Append-only outcome events and corrections (`BR-077`–`BR-079`).                                                |
| `follow_up_visit_requests`         | §11.3   | Technician follow-up requests and office decisions (`BR-FV-001`–`BR-FV-013`).                                  |
| `visit_notes`                      | §12.2   | Append-only per-Visit notes (`BR-027`, `BR-077`).                                                              |
| `visit_location_history`           | §9.6    | Append-only history of a Visit's operational location (`BR-056`, `BR-057`).                                    |
| `visit_location_review_flags`      | §6.3    | Open review requirement when a Job's Property changes under scheduled or active Visits (`BR-056`).             |
| `organization_job_number_counters` | §13     | Per-organization Job-number allocation (`BR-052`).                                                             |

- The slice is **additive**: it adds tables and references, and removes nothing.
- References into foundation data are restricted, not cascading: this slice never deletes or rewrites
  foundation rows (§6.4). Only the tenant (`organization_id`) reference follows the foundation's
  deletion convention.
- If implementation later needs a change to a _foundation_ table, that is a separate decision with
  its own business-rule change and migration (`BR-040`, `Project.md` §8).

## 21. Open questions register

Every item below is a product decision. Items marked **Decided** or **Deferred** were closed during
this slice and are recorded in `docs/decisions/012-property-lifecycle-and-permissions.md`; the rest
remain open. Where an item is open, this model **fails closed** on it: no behaviour, status,
catalogue or column was invented for it (`BR-042`, `qa.md` §15).

| #   | Open question                                                                                                                 | Rule               | How this model handles it now                                                                                                                                                                         | Blocks                                  |
| --- | ----------------------------------------------------------------------------------------------------------------------------- | ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- |
| 1   | How does deleting a Job relate to preserved business history?                                                                 | `BR-021`           | No deletion path, tombstone or `deleted_at` is modelled; references fail closed (§16).                                                                                                                | Any Job delete endpoint.                |
| 2   | What is the `final_outcome` value catalogue?                                                                                  | `BR-062`           | `final_outcome_code` exists with **no** CHECK and is never copied from a Visit outcome (§6.1, §8.5).                                                                                                  | Offering a completion picker.           |
| 3   | Which Job status follows a `NEEDS_QUOTE_APPROVAL` outcome?                                                                    | `BR-061`           | Treated as **not modelled**: an `IN_PROGRESS` Job is not moved to `PENDING_REVIEW` and no alternative transition is invented (§8.4, §11.1).                                                           | `PENDING_REVIEW` entry rule.            |
| 4   | What is the Job cancellation reason catalogue?                                                                                | `BR-064`           | `reason_code` exists with **no** CHECK; the structured reason plus mandatory note are enforced in the service layer (§7.1, §8.6).                                                                     | The cancellation UI.                    |
| 5   | May organizations add their own Visit cancellation reasons?                                                                   | `BR-076`           | The confirmed six-code vocabulary is a CHECK; no organization-specific catalogue is modelled (§9.5).                                                                                                  | Organization-level reason management.   |
| 6   | Must a Job-cancellation cascade carry its own Visit-level reason in addition to the Job cancellation it references?           | `BR-065`           | The cascade row references the Job cancellation that carries the reason; no extra Visit-level reason is required or invented (§9.5).                                                                  | Cascade record completeness.            |
| 7   | May a Property exist with **no** active Customer relationship?                                                                | `BR-050`           | Not modelled as invalid; the relationship table simply has no open row (§4.1).                                                                                                                        | Property creation rules.                |
| 8   | What is the Job category/type catalogue?                                                                                      | `BR-053`           | `type_code` is an optional free `varchar` with no CHECK and no behaviour branches on it (§6.1, §6.2).                                                                                                 | Category automation or filtering rules. |
| 9   | Is the Job address snapshot refreshed when the _Property's_ own address changes?                                              | `BR-056`, `BR-057` | The snapshot is written when the Property association is created or changed; a Property address edit does not rewrite it, and no refresh rule is invented (§6.3).                                     | Snapshot refresh behaviour.             |
| 10  | May a Visit's operational location be changed at all, by which actions, and in which statuses?                                | `BR-056`           | The requirement (explicit, authorized, recorded) is modelled; the resolution catalogue and permitted statuses are not (§9.6).                                                                         | Location-resolution UI and validation.  |
| 11  | May a Visit note ever be edited or deleted?                                                                                   | `BR-027`, `BR-067` | `visit_notes` is append-only with no update or delete path and no edit window (§12.2).                                                                                                                | Note editing.                           |
| 12  | What is the Technician self-edit window for a Visit outcome, and how does reopening a Job interact with outcome immutability? | `BR-079`           | No time window is modelled or enforced; immutability is derived from the Job being `COMPLETED`, and reopening does not un-freeze outcomes (§11.1).                                                    | Technician outcome editing.             |
| 13  | What is the offline conflict strategy for each synchronization-sensitive operation?                                           | `BR-032`           | Conflict behaviour is per operation; where a strategy is undefined it is left open and never implemented as last-write-wins (§15).                                                                    | Offline mutation design.                |
| 14  | What are the scheduling time-zone semantics?                                                                                  | `BR-026`           | Only absolute instants (`timestamptz`) are stored; no organization or Visit time-zone field exists (§2.3, §9.3, §14).                                                                                 | Display and input of schedules.         |
| 15  | What are the audit retention and audit visibility rules?                                                                      | `BR-033`           | The history tables exist and are append-only; retention and read access are not defined (§2.4).                                                                                                       | Retention jobs and audit screens.       |
| 16  | Must a Property's own address-change history be retained, beyond the Job/Visit snapshots that must not be rewritten?          | `BR-057`, `BR-084` | **Deferred** by product ownership: no Property address-history table is modelled. Editing never rewrites Job/Visit snapshots, which remain the authoritative record of the address used at the time. | Property address-history UI and audit.  |
| 17  | Does a remaining `DRAFT` Visit block entry into `PENDING_REVIEW`?                                                             | `BR-061`           | `BR-060` excludes `DRAFT` from the "Needs Scheduling" active-work signal, but `BR-061` does not classify it for review entry. No automatic transition is implemented for the draft-Visit case (§8.4). Completion is separate and **does** block on a `DRAFT` Visit (§8.5, `BR-062`). | `PENDING_REVIEW` entry rule.            |
| 18  | Does the customer-detail Job row's past-Visit fallback include a `CANCELED` past Visit?                                        | `BR-081`           | Implemented literally: only the upcoming candidate excludes `CANCELED`; the past fallback considers any past Visit by scheduled start (§8.4).                                                          | Customer-detail Job row only.           |
| 19  | Which capability authorizes **creating** a Property?                                                                          | `BR-085`           | **Decided:** `properties.create` (`ADR-012` D1). Enforced by `POST /customers/:id/properties`; the interim `customers.edit` authorization is removed.                                                | —                                       |
| 20  | Does an archived Property still appear in the customer-detail Property projection?                                            | `BR-081`, `BR-083` | **Decided:** the projection and `propertyCount` cover the customer's `ACTIVE` relationships only (`ADR-012` D3). Archived Properties remain retrievable through explicit archived views.                | —                                       |
| 21  | Is the archive-warning "active" classification reconciled with `BR-060`'s narrower derived condition?                         | `BR-060`, `BR-083` | **Decided:** kept as two named concepts — `needsSchedulingActiveVisit` (`BR-060`) and `archiveWarningOpenWork` (`BR-083`) (`ADR-012` D4). No shared vocabulary is imposed.                            | —                                       |
| 22  | Which offline/outbox architecture standard do Property archive and restore follow?                                            | `BR-086`           | **Decided:** `docs/architecture/offline-first-architecture.md` defines the working set, outbox, idempotency, replay and conflict model (`ADR-012` D7).                                                 | Offline Property lifecycle.             |
| 23  | Does a Property's own lifecycle history permanently block its deletion?                                                       | `BR-082`           | **Decided:** no. `property_lifecycle_history.property_id` is `ON DELETE CASCADE`; deletion stays blocked by other references (`ADR-012` D5).                                                          | —                                       |
| 24  | What formally defines a technician as "running late"?                                                                         | `BR-070`, `BR-038` | Not defined. The manager home presents only the **overdue Visit** condition above, which compares the Visit's own scheduled window against the server clock and needs no travel time, location or estimate. No "running late" alert is produced, and no ETA is derived from GPS or travel data (`docs/tracker/016-android-manager-home.md`). | A "running late" alert; any ETA or travel-time estimate. |
| 25  | Which attention conditions should a cancelled Visit raised **today** produce on the manager home?                             | `BR-076`           | Not defined. A `CANCELED` Visit is excluded from today's schedule and counts, and no "cancelled today" condition is produced: the Visit row carries no cancellation instant of its own (only the status history does), and "today" for it is undefined. Recorded rather than inferred. | A "cancelled today" attention condition. |
| 26  | Does the manager home's "Needs attention" list need a capability of its own?                                                 | `BR-006`, `BR-008` | The read is guarded by the existing `customers.view`, the same capability the Job/Visit projections already use. The permission model has no `jobs.*` capability yet, and inventing one is forbidden (`BR-042`) — see `docs/tracker/016-android-manager-home.md`. | The Jobs feature's permission set; the manager home's own capability. |
| 27  | May a **field** member read another technician's schedule (a **team** scope), and which capability authorizes it?             | `BR-006`, `BR-009`, `BR-024` | **Open, and enforced closed.** `GET /schedule` resolves a **scope** from the capability the caller holds: `customers.view` reads the operation's day (`ORGANIZATION`), `VISIT_VIEW_ASSIGNED` reads only the caller's own assigned Visits (`SELF`) (`ADR-020` decision update). A `membershipId` outside the resolved scope is refused `403`, uniformly whether or not it names anybody. No capability for a team scope exists in the catalogue — `VISIT_VIEW_ASSIGNED` is by definition the caller's *own* assignments, and `TECHNICIAN_VIEW` authorizes the technician **records** an assignee is chosen from — so none was invented (`BR-042`). | The technician Schedule screen's technician selector; any `TEAM` scope; `docs/api/schedule.md` §5.4. |

Not an open question, recorded here to prevent a false assumption: **Job-number gaps**. `BR-052`
requires uniqueness and immutability, not a gapless sequence, so no gap-free guarantee is claimed
(§13).
