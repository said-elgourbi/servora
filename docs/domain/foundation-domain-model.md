# Servora — Foundation Domain Model

> Authoritative description of the Servora **foundation domain**: organizations, users,
> authentication, profiles, memberships/roles, customers (individual & company), contacts
> and addresses.
>
> **Scope.** This is the foundation slice only. Jobs and every later-phase entity
> (scheduling, assignment/dispatch, time tracking, parts, assets, invoices, GPS,
> notifications, reporting) are deliberately **not** modelled here.
>
> Decision record: `docs/decisions/003-foundation-domain-model.md`.
> Business rules: `Business Rules.md`.

## 1. Tenant model — the organization is the boundary

- An **organization** is the tenant and the top-level owner of business data.
- `organization_id` is present on every organization-owned table
  (`organization_members`, `customers`).
- Organization-owned rows are **always** addressed by the composite key
  `(organization_id, id)`. Fetching by `id` alone is forbidden (see
  `api/src/tenancy/tenant-scope.ts`).
- Tenant scoping is enforced in the data-access layer (`CustomersService`) and verified by
  `api/test/customers.e2e-spec.ts`.
- A row that is not in the caller's tenant is reported as **not found**, never as
  **forbidden**, so the API does not reveal the existence of another tenant's data.

## 2. Organizations vs customers

They are different concepts and different tables.

| Concept          | Meaning                                                  | Table           |
| ---------------- | -------------------------------------------------------- | --------------- |
| **Organization** | The tenant/company that _uses_ Servora and owns the data | `organizations` |
| **Customer**     | The party that _receives service_ from that organization | `customers`     |

A customer belongs to exactly one organization via `customers.organization_id`.

## 3. Users vs customers

A **user** is an authentication identity that can sign in to Servora. A **customer** is a
service recipient and never a login. A real person may be both, but they are separate records
with separate purposes and no shared table.

## 4. Authentication identity vs profile

A user's data is split so each concern has one home:

| Concern                 | Table                                         | Notes                                            |
| ----------------------- | --------------------------------------------- | ------------------------------------------------ |
| Authentication identity | `users`                                       | `email` (globally unique) + `password_hash` only |
| Personal information    | `user_profiles`                               | name, phone, avatar, locale, timezone            |
| Organizational role     | `organization_members` + `organization_roles` | one organization role + membership status        |

- `user_profiles` is **1–0..1** with `users` (primary key = `user_id`).
- A user has at most one profile and may belong to many organizations.

## 5. Roles and permissions

- Roles are organization-owned business records in `organization_roles`. Organizations may call
  roles anything they want; authorization never depends on the English or French role name.
- Default Manager and Technician roles are created for each organization with stable setup codes
  (`MANAGER`, `TECHNICIAN`) in `organization_roles.system_code`. The setup code identifies the
  default role template; it is not used as an authorization shortcut.
- Role display data is bilingual: `name_en`, `name_fr`, `description_en`, `description_fr`.
- A role assignment is **never** stored on `users` or `user_profiles`.
- `organization_members` is unique on `(organization_id, user_id)` and stores exactly one
  `role_id`, so a user has one role in a given organization membership.
- Permissions are stable machine-readable records in `permissions`, with bilingual names and
  descriptions. Roles and permissions are many-to-many through `role_permissions`.
- Managers may grant extra direct permissions to a member through
  `organization_member_permissions`. Effective permissions are:

```text
role permissions + direct member permissions
```

- A member with direct permissions beyond the base role may be displayed as `Role Name*`. The star
  is a UI indicator only; authorization uses the effective permission set.

## 6. Password rules

- Passwords are hashed with **Argon2id** (`api/src/users/password-hasher.ts`).
- Only the hash is stored, in `users.password_hash`. There is **no plaintext password column**.
- The hash is never returned by the API: `toUserDto` selects fields explicitly, and
  `api/src/users/user.dto.spec.ts` asserts the hash cannot leak.
- `verifyPassword` fails closed (a malformed hash is a non-match, not an error).

## 7. Customer model and subtypes

A customer is either an individual or a company. The shared header lives in `customers`
(`type`, `displayName`, contact/billing fields, `notes`, preferred contact method, language,
soft-deletion fields, `status`). The type-specific data
lives in one subtype table, joined **1–0..1** by `customer_id`:

| `customers.type` | Required subtype table | Subtype fields                              |
| ---------------- | ---------------------- | ------------------------------------------- |
| `INDIVIDUAL`     | `customer_individuals` | `first_name`, `last_name`, `date_of_birth`  |
| `COMPANY`        | `customer_companies`   | `legal_name`, `business_name`, `tax_number` |

**Exactly one subtype record must exist and must match `type`.** This invariant is enforced in
the service layer (`api/src/customers/customer-subtype.ts`), not by a database trigger, and is
covered by unit tests (`customer-subtype.spec.ts`) and integration tests.

Subtype rows share the customer's lifetime. A customer is soft-deleted through `customers.deleted_at`
rather than physically deleted during normal business operations.

## 8. Contacts

- `customer_contacts` holds contact people for a customer.
- A customer may have **any number** of contacts; none is required.
- Contacts are optional and have no uniqueness constraint on email (two contacts may share an
  address; different customers may share an address).
- Flags: `is_primary`, `is_billing_contact`, `is_job_contact`.
- This slice does not require a contact and does not impose email uniqueness.

## 9. Addresses

- `customer_addresses` holds addresses for a customer.
- A customer may have **any number** of addresses; none is required.
- `type` is one of `SERVICE`, `BILLING`, `OTHER`; `is_default` marks a preferred address.
- Required fields: `address_line1`, `city`, `province`, `postal_code`, `country`.
- A customer may have at most one default service address and at most one default billing address.
- Customer addresses are not Properties. Properties are the service-location model used by Jobs and
  Visits.

## 10. Controlled vocabularies

All controlled values are stored as **stable, machine-readable VARCHAR codes** guarded by
`CHECK` constraints — never native PostgreSQL enums and never localized display text:

| Column                               | Allowed values                                      |
| ------------------------------------ | --------------------------------------------------- |
| `organizations.status`               | `ACTIVE`, `INACTIVE`                                |
| `users.status`                       | `ACTIVE`, `INACTIVE`, `SUSPENDED`                   |
| `organization_roles.system_code`     | `MANAGER`, `TECHNICIAN`, or `NULL` for custom roles |
| `organization_members.status`        | `ACTIVE`, `INACTIVE`                                |
| `customers.type`                     | `INDIVIDUAL`, `COMPANY`                             |
| `customers.status`                   | `ACTIVE`, `INACTIVE`                                |
| `customers.preferred_contact_method` | `EMAIL`, `PHONE`, `SMS`, `NONE`                     |
| `customers.language`                 | `en-CA`, `fr-CA`                                    |
| `customer_addresses.type`            | `SERVICE`, `BILLING`, `OTHER`                       |

Localized labels (English/French) are resolved from these codes at presentation time; the
database and API never store a translated label.

## 11. Identifiers, timestamps and deletion

- **Identifiers.** Every primary key is a `uuid` defaulting to `gen_random_uuid()`. Foreign
  keys are `uuid` too. API clients see stable UUIDs.
- **Timestamps.** `created_at` / `updated_at` are `timestamp with time zone NOT NULL DEFAULT
now()`. `updated_at` is refreshed on update through Drizzle's `$onUpdate` using the database
  clock, so no client or app-local time is trusted.
- **Deletion.** Tenant and aggregate-child foreign keys use `ON DELETE CASCADE`:
  - `user_profiles`, `organization_members` → `users`
  - `organization_roles`, `organization_members`, `customers` → `organizations`
  - `customer_individuals`, `customer_companies`, `customer_contacts`, `customer_addresses` →
    `customers`

  Deleting an organization therefore removes its members and customers (and their children);
  deleting a user removes the profile and memberships. Customer deletion in product workflows is
  soft deletion: `deleted_at`, `deleted_by_membership_id`, and `delete_reason` preserve the record.

- **Uniqueness.** Only `users.email` is globally unique. `organizations.email`,
  `customers.email` and `customer_contacts.email` are **not** unique. Membership is unique on
  `(organization_id, user_id)`. Default role setup codes are unique per organization, and
  permission codes are globally unique.

## 12. Entity relationship diagram

```text
organizations 1 ──── * organization_roles ──── * role_permissions * ──── 1 permissions
      │                         │
      │                         │ 1
      │                         *
      ├────────────── * organization_members * ──── 1 users 1 ──── 0..1 user_profiles
      │
      │ 1
      │
      *                       ┌──────────── 1:1 ── customer_individuals
   customers ────────────────┤
      │                       └──────────── 1:1 ── customer_companies
      │
      ├── * customer_contacts
      └── * customer_addresses

Legend: 1──* one-to-many · 1──0..1 one-to-zero-or-one (subtype) · *──* many-to-many via join table
```

- `organization_members` is the join table for `organizations` ↔ `users`, carrying one `role_id`.
- `organization_member_permissions` grants extra direct permissions to individual memberships.
- A customer has exactly one subtype row (`customer_individuals` for `INDIVIDUAL`,
  `customer_companies` for `COMPANY`).

## 13. Where this lives in the code

| Concern                             | Location                                                                                                    |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Tables + relations (Drizzle)        | `api/src/database/schema.ts`                                                                                |
| Migrations                          | `api/drizzle/migrations/0000_foundation_domain_model.sql`, `api/drizzle/migrations/0004_woozy_spitfire.sql` |
| Domain types + status codes         | `api/src/{organizations,users,members,customers}/*.types.ts`                                                |
| DTOs + parsers (no `password_hash`) | `api/src/{organizations,users,members,customers}/*.dto.ts`                                                  |
| Shared validation helpers           | `api/src/validation/domain-validation.ts`                                                                   |
| Argon2id hashing                    | `api/src/users/password-hasher.ts`                                                                          |
| Tenant scope type                   | `api/src/tenancy/tenant-scope.ts`                                                                           |
| Customer subtype invariant          | `api/src/customers/customer-subtype.ts`                                                                     |
| Tenant-scoped customer persistence  | `api/src/customers/customers.service.ts`                                                                    |
| Android models (Kotlin)             | `android/app/src/main/java/com/servora/android/domain/model/`                                               |

## 14. Verification

- **Unit (Vitest)** — validation, password hashing, DTO leakage, subtype integrity:
  `make api-test`.
- **Integration (Vitest + PostgreSQL)** — constraints, cascades, uniqueness, tenant isolation,
  subtype integrity: `make api-test-e2e`.
- Applied to PostgreSQL with `make migrate`.

## 15. Out of scope (explicitly not modelled here)

Jobs, Properties, Visits, job status/lifecycle, assignment, scheduling, time tracking,
parts/materials, assets, invoices, GPS/location, notifications and reporting. Each of those
becomes its own feature slice once its business rules are defined.

The Job, Property and Visit business behaviour is now defined by `Business Rules.md`
(BR-047 – BR-080). Their concrete data model belongs to the Job & Visit data-model slice and is
deliberately not modelled in the foundation model.
