# ADR-003 — Foundation Domain Model

- Status: Accepted
- Date: 2026-09-10
- Milestone: `docs/tracker/003-foundation-domain-model.md`
- Supersedes: nothing

## Context

The API foundation (`docs/tracker/001-foundation.md`) stood up NestJS + PostgreSQL + Drizzle
with no domain tables. The first domain slice must model the platform's tenant, identity and
customer foundation — organizations, users/auth, profiles, memberships/roles, customers and
their contacts/addresses — before jobs and later-phase entities are added.

`Project.md` and `dev.md` require domain/database design before application implementation and
prohibit inventing business behaviour for `OPEN QUESTION` rules.

## Decisions

### D1 — Scope is the foundation domain only

The slice covers: organizations, users, user profiles, organization memberships, organization
roles, permissions, role-permission assignments, direct member permissions, customers
(individual & company subtypes), customer contacts and customer addresses. Jobs, assignment,
scheduling, time tracking, parts, assets, invoices, GPS, notifications and reporting are
explicitly out of scope.

### D2 — UUID primary keys via `gen_random_uuid()`

Every primary key is `uuid PRIMARY KEY DEFAULT gen_random_uuid()`; foreign keys are `uuid`.
PostgreSQL's built-in `gen_random_uuid()` (pgcrypto not required) is used so the database is
authoritative and clients never mint identity.

### D3 — Controlled vocabularies are `VARCHAR` + `CHECK`, never native enums

Statuses, setup codes, permission codes, customer types, customer languages, preferred contact
methods and address types are stored as stable machine-readable `VARCHAR` codes guarded by
`CHECK` constraints where the catalogue is closed. Native PostgreSQL enums were rejected because
adding a value requires a schema migration with weaker ergonomics, and because translated
display labels must never be the stored value (`Project.md` §10).

### D4 — Timezone-aware timestamps; `updated_at` refreshed from the DB clock

`created_at`/`updated_at` are `TIMESTAMPTZ NOT NULL DEFAULT now()`. `updated_at` is refreshed
through Drizzle `$onUpdate(() => sql\`now()\`)`, so the timestamp comes from the database clock
and the application never persists an app-local time.

### D5 — Cascades follow ownership; business deletion preserves customers

Child data is owned by its parent: `user_profiles`/`organization_members` → `users`,
`organization_roles`/`organization_members`/`customers` → `organizations`, role-permission rows
→ roles and permissions, direct member-permission rows → members and permissions, and all
customer children (`customer_individuals`, `customer_companies`, `customer_contacts`,
`customer_addresses`) → `customers`. Cross-business references such as the member who soft-deleted
a customer do not cascade. Customer deletion in product workflows is soft deletion through
`deleted_at`, `deleted_by_membership_id`, and `delete_reason`.

### D6 — Customer subtype integrity is enforced in the service layer

A customer must have exactly one subtype record matching its `type`. This is enforced by
`assertCustomerSubtypeIntegrity` in `api/src/customers/customer-subtype.ts` and covered by
tests — **not** by a database trigger, per the instruction not to introduce triggers unless the
architecture requires them.

### D7 — Argon2id password hashing; the hash never leaves the backend

Passwords are hashed with Argon2id (OWASP interactive baseline). `users` stores only
`password_hash`; there is no plaintext column, and `toUserDto` never exposes the hash.

### D8 — Tenant boundary is `organization_id`

Organization-owned rows are addressed by `(organization_id, id)`. `CustomersService` exposes no
lookup by `id` alone; a cross-tenant miss is reported as not-found.

### D9 — Only `users.email` is globally unique

`organizations.email`, `customers.email` and `customer_contacts.email` are non-unique
organization/business data. `organization_members` is unique on `(organization_id, user_id)`.

### D10 — Roles are organization records and permissions authorize behavior

An organization member has exactly one `role_id`. Roles store bilingual names/descriptions and
may be named freely by the organization. Permissions are stable machine-readable codes with
bilingual labels/descriptions. Roles and permissions are many-to-many, and a member may receive
extra direct permissions; effective permissions are role permissions plus direct member
permissions.

### D11 — Android models are added without the Gradle foundation

The on-disk `android/` tree has no Gradle project yet (see ADR-002). Plain Kotlin data classes
were added under `android/app/src/main/java/com/servora/android/domain/model/` (no external
dependencies). They are **not compiled or linted** in this slice; Android build/lint
verification is deferred to the Android Gradle foundation slice.

## Consequences

- The PostgreSQL schema is versioned by Drizzle migrations, starting with
  `api/drizzle/migrations/0000_foundation_domain_model.sql` and extended by later foundation
  migrations such as the role/permission and soft-delete update.
- API types/DTOs/validation live in feature folders; shared helpers in `api/src/validation`.
- Backend verification: `make api-test` (unit) and `make api-test-e2e` (PostgreSQL).
- Adding a later entity means a new migration, new schema entries, and its own feature module.
