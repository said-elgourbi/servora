# Servora API — `/customers` Contract

**Status: IMPLEMENTED** for `GET /customers`, `GET /customers/:id`, `GET /customers/:id/properties`,
`GET /customers/:id/properties/:propertyId`, `GET /customers/:id/jobs`, `POST /customers`,
`POST /customers/:id/properties`, `PATCH|PUT /customers/:id/properties/:propertyId`,
`POST /customers/:id/properties/:propertyId/archive`,
`POST /customers/:id/properties/:propertyId/restore`,
`DELETE /customers/:id/properties/:propertyId`, `PATCH|PUT /customers/:id` and
`POST /customers/:id/archive`.

Read projections on the customer detail are derived, not stored, and are defined by `BR-081`.
This document records the wire contract; the derivation itself is specified in
`Business Rules.md` (`BR-081`) and `docs/domain/job-visit-domain-model.md` §8.4.

References: `BR-001`, `BR-007`, `BR-023`, `BR-048`, `BR-050`, `BR-056`, `BR-058`, `BR-068`,
`BR-081`, `BR-082`, `BR-083`, `BR-084`, `BR-085`, `BR-086`, `dev.md` §7, `dev.md` §8,
`docs/decisions/012-property-lifecycle-and-permissions.md`,
`docs/tracker/010-customer-detail.md`, `docs/tracker/012-property-permissions-and-lifecycle-schema.md`,
`docs/tracker/013-property-lifecycle.md`.

## 1. Conventions

All payloads are JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15).
Access tokens are presented as `Authorization: Bearer <accessToken>`. Route paths carry no version
prefix (`docs/versioning.md` §7).

Every route requires one of `customers.view`, `customers.create`, `customers.edit`,
`customers.archive` or a `properties.*` capability as noted below. **The backend is the
authorization authority**; a client that hides an action is not authorization (`BR-007`).

## 2. Permissions

Property capabilities are independent of the customer capabilities and are never implied by them
(`BR-085`).

| Permission            | Routes                                                                                     |
| --------------------- | ------------------------------------------------------------------------------------------ |
| `customers.view`      | `GET /customers`, `GET /customers/:id`, `…/jobs`                                           |
| `customers.create`    | `POST /customers`                                                                          |
| `customers.edit`      | `PATCH /customers/:id`, `PUT /customers/:id`                                               |
| `customers.archive`   | `POST /customers/:id/archive`                                                              |
| `properties.view`     | `GET /customers/:id/properties`, `GET /customers/:id/properties/:propertyId`               |
| `properties.create`   | `POST /customers/:id/properties`                                                           |
| `properties.edit`     | `PATCH /customers/:id/properties/:propertyId`, `PUT …`                                     |
| `properties.archive`  | `POST /customers/:id/properties/:propertyId/archive`, `POST …/restore`                     |
| `properties.delete`   | `DELETE /customers/:id/properties/:propertyId`                                             |

> **A Property read or write needs a Property capability.** `GET /customers/:id/properties`
> requires `properties.view` and `POST /customers/:id/properties` requires `properties.create`
> (`BR-085`). Archive and restore share `properties.archive` because they are one lifecycle concern;
> permanent deletion has its own capability because it is destructive and irreversible.
>
> The customer header's `propertyCount` is part of the customer projection and is read under
> `customers.view`, but it counts the same `ACTIVE` set the Property list does (see §3.3).


## 3. Read endpoints

### 3.1 `GET /customers`

The organization's customers, newest first, each with the derived `propertyCount` and `jobCount`.
Optional `status` and `jobs` filters; see `docs/tracker/008-android-customers-filter.md`.

### 3.2 `GET /customers/:id`

The customer detail header. `customer` carries the derived counts, the required subtype record is
returned in `individual` **or** `company`, and `contacts` carries the customer's contacts
(`BR-023`).

```json
{
  "customer": {
    "id": "…",
    "organizationId": "…",
    "type": "COMPANY",
    "displayName": "ABC Property Management",
    "email": "office@abc.example",
    "phone": "+15551234567",
    "status": "ACTIVE",
    "propertyCount": 2,
    "jobCount": 12,
    "createdAt": "2025-01-12T10:30:00.000Z",
    "updatedAt": "2025-01-12T10:30:00.000Z"
  },
  "individual": null,
  "company": {
    "customerId": "…",
    "legalName": "ABC Property Management Ltd.",
    "businessName": null,
    "taxNumber": null
  },
  "contacts": []
}
```

An id the caller's organization does not own is `404 CUSTOMER_NOT_FOUND`, never `403` (`BR-001`).

### 3.3 `GET /customers/:id/properties` — the customer's Properties (`BR-081`, `BR-082`)

One entry per active Property relationship (`BR-050`); an ended relationship is history and is not
returned. An optional `status` query selects the lifecycle set:

| `status`         | Meaning                                                |
| ---------------- | ------------------------------------------------------ |
| *(omitted)*      | `ACTIVE` — the default (`BR-082`, `BR-083`)             |
| `ACTIVE`         | Properties available for new work                      |
| `ARCHIVED`       | Properties out of active use, still retrievable        |
| `ALL`            | No constraint on lifecycle state                       |

An unknown value is `400 VALIDATION_FAILED`.

```json
[
  {
    "id": "…",
    "name": "Cedar Lane Building",
    "addressLine1": "987 Cedar Lane",
    "addressLine2": null,
    "city": "Montreal",
    "province": "QC",
    "postalCode": "H3A 2T6",
    "country": "Canada",
    "status": "ACTIVE",
    "jobCount": 4,
    "lastServiceAt": "2026-08-28T13:00:00.000Z"
  }
]
```

- `status` is the Property lifecycle vocabulary (`BR-082`); the client labels the code and must not
  invent a parallel vocabulary (`BR-041`).
- `jobCount` — every Job associated with the Property, whatever its status.
- `lastServiceAt` — scheduled start of the most recent `COMPLETED` Visit across those Jobs, or
  `null` when the Property has never been serviced. The client presents a localized "never
  serviced" state for `null`; the API sends no display text (`BR-028`).
- **Archived Properties are excluded by default** (`BR-082`, `BR-083`). The default projection is
  the customer's current `ACTIVE` relationships, and the customer's derived `propertyCount` counts
  the same set, so the header and the list agree. An archived Property is not deleted: it remains a
  retrievable business record, reached by `?status=ARCHIVED` and by §3.6.

### 3.4 `GET /customers/:id/jobs` — the customer's Jobs (`BR-081`)

Newest job number first. Each entry carries the **selected Visit**'s derived date and technicians,
chosen by the backend so every client shows the same values (`BR-081`).

```json
[
  {
    "id": "…",
    "jobNumber": 1042,
    "title": "HVAC Maintenance",
    "description": null,
    "typeCode": null,
    "status": "COMPLETED",
    "propertyId": "…",
    "propertyAddress": {
      "propertyName": "Cedar Lane Building",
      "addressLine1": "987 Cedar Lane",
      "addressLine2": null,
      "city": "Montreal",
      "province": "QC",
      "postalCode": "H3A 2T6",
      "country": "Canada"
    },
    "scheduledStart": "2026-09-08T13:00:00.000Z",
    "technicians": [
      { "membershipId": "…", "name": "Mike Lead", "roleCode": "LEAD" },
      { "membershipId": "…", "name": "Sarah Tech", "roleCode": "TECHNICIAN" }
    ]
  }
]
```

- `status` is the Job lifecycle vocabulary (`BR-058`). The client labels the code and must not
  invent a parallel vocabulary (`BR-041`).
- `scheduledStart` is the selected Visit's scheduled start, or `null` when the Job has no eligible
  Visit.
- `technicians` are the selected Visit's current assignments (`BR-068`), Lead first. An empty array
  means unassigned; the client presents a localized "unassigned" state.
- `propertyAddress` is the Job's preserved snapshot (`BR-056`), never the Property's current
  address. `null` when the Job has no Property.
- `name` is `null` when the member has no profile yet.

### 3.5 `POST /customers/:id/properties` — create a Property (`BR-049`, `BR-050`)

Creates an organization-owned Property and relates it to the customer in one atomic operation. The
customer association is the active row in `property_customer_relationships`, never a column on the
Property (`BR-049`); the relationship's actor is the calling membership (`BR-050`). The new
Property's `jobCount` is `0` and its `lastServiceAt` is `null` (`BR-081`). It requires
`properties.create` (`BR-085`).

```json
{
  "name": "Cedar Lane Building",
  "addressLine1": "987 Cedar Lane",
  "addressLine2": "Suite 200",
  "city": "Montreal",
  "province": "QC",
  "postalCode": "H3A 2T6",
  "notes": "Mechanical room in basement B1."
}
```

- `addressLine1`, `city`, `province` and `postalCode` are required; `name`, `addressLine2` and
  `notes` are optional and are reported as `null` when absent.
- `province` is one of the stable Canadian province/territory codes
  (`AB`, `BC`, `MB`, `NB`, `NL`, `NS`, `NT`, `NU`, `ON`, `PE`, `QC`, `SK`, `YT`). A full name such
  as `Quebec`, a foreign code such as `NY`, or any other value is rejected (`BR-028`, `BR-041`).
- **There is no `country` field.** Servora currently operates in Canada only, so the backend stores
  the value the address model already carries (`Canada`) on the caller's behalf; a client never
  sends it and the field is never requested. A `country` member in the request body is ignored.
- The response is the created Property row in the shape of §3.3 (`id`, `name`, the address, the
  stored `country`, `jobCount: 0`, `lastServiceAt: null`).
- An id the caller's organization does not own is `404 CUSTOMER_NOT_FOUND`, and a rejected payload
  is `400 VALIDATION_FAILED` (`dev.md` §7).
### 3.6 `GET /customers/:id/properties/:propertyId` — one Property (`BR-081`, `BR-082`, `BR-083`)

Returns the Property whether it is `ACTIVE` or `ARCHIVED`, with the derived values a lifecycle screen
renders. It requires `properties.view`.

```json
{
  "id": "…",
  "name": "Cedar Lane Building",
  "addressLine1": "987 Cedar Lane",
  "addressLine2": "Suite 200",
  "city": "Montreal",
  "province": "QC",
  "postalCode": "H3A 2T6",
  "country": "Canada",
  "notes": "Mechanical room in basement B1.",
  "status": "ARCHIVED",
  "version": 5,
  "archivedAt": "2026-09-13T14:00:00.000Z",
  "jobCount": 4,
  "lastServiceAt": "2026-08-28T13:00:00.000Z",
  "archiveImpact": { "activeJobCount": 2, "activeVisitCount": 1 },
  "canBePermanentlyDeleted": false
}
```

- `version` is the optimistic-concurrency value a mutation may carry back (`BR-086`).
- `archivedAt` is `null` while the Property is `ACTIVE`; it is current state, never history.
- `archiveImpact.activeJobCount` counts the Property's Jobs whose status is not `COMPLETED` or
  `CANCELED`; `archiveImpact.activeVisitCount` counts the Visits of those Jobs whose status is not
  `COMPLETED`, `CANCELED` or `NO_SHOW` (`BR-074`, `BR-083`). This is the `archiveWarningOpenWork`
  classification, deliberately not the derived `needsSchedulingActiveVisit` set (`BR-060`). A
  `DRAFT` Visit counts as open work.
- `canBePermanentlyDeleted` is the API's answer to whether `BR-082`'s precondition holds right now.
  A client must not compute it itself (`BR-001`, `BR-007`). It is `false` for every Property created
  through the API, because the create establishes a Customer relationship.
- The Property is resolved inside the addressed Customer's context: an id the caller's organization
  does not own, or one that belongs to another of its customers, is `404 PROPERTY_NOT_FOUND` — never
  `403` (`BR-001`).



## 4. Property mutations

The mutation routes share one shape. Each requires its own Property capability (`BR-085`), each
resolves the tenant and the Customer context the same way, and each answers with the Property's
current values in the §3.6 shape.

| Route                                                 | Capability           | Success |
| ----------------------------------------------------- | -------------------- | ------- |
| `PATCH` / `PUT /customers/:id/properties/:propertyId` | `properties.edit`    | `200`   |
| `POST …/:propertyId/archive`                          | `properties.archive` | `200`   |
| `POST …/:propertyId/restore`                          | `properties.archive` | `200`   |
| `DELETE …/:propertyId`                                | `properties.delete`  | `204`   |

### 4.1 `PATCH` / `PUT /customers/:id/properties/:propertyId` — edit (`BR-084`)

Replaces the same authoritative fields a create supplies, so the Add Property form is reused. A
`country` member is still not a client field and is ignored.

```json
{
  "name": "Cedar Lane Building",
  "addressLine1": "987 Cedar Lane",
  "addressLine2": "Suite 200",
  "city": "Montreal",
  "province": "QC",
  "postalCode": "H3A 2T6",
  "notes": "Mechanical room in basement B1.",
  "expectedVersion": 3
}
```

- An `ARCHIVED` Property may be edited and **stays archived**; restoring is a separate explicit
  action (`BR-084`).
- Editing never rewrites a historical address snapshot held by a Job or a Visit (`BR-056`, `BR-057`).
- `expectedVersion` is optional. When supplied and the Property has moved on, the mutation is
  rejected with `409 PROPERTY_VERSION_CONFLICT` and `details.currentVersion` (`BR-086`).

### 4.2 `POST /customers/:id/properties/:propertyId/archive` and `…/restore` (`BR-082`, `BR-083`)

Both take an optional body:

```json
{
  "note": "No longer serviced.",
  "clientOperationId": "3f1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c",
  "capturedAt": "2026-09-13T14:00:00.000Z",
  "expectedVersion": 3
}
```

- No reason is required by any rule, so every member is optional. `clientOperationId` is the offline
  replay's idempotency key and `capturedAt` is the device time, kept for display only (`BR-031`,
  `BR-086`). Both are validated: a non-UUID key or a non-instant device time is `400
  VALIDATION_FAILED`. `capturedAt` accepts ISO-8601 fractional seconds at the precision the device
  clock actually reports — up to a nanosecond — so the microsecond value an Android client sends is
  not rejected.
- Archive is idempotent in state: archiving an already-archived Property changes neither the state
  nor the version and appends no second lifecycle event; restoring an active Property behaves the
  same way. A replay carrying a `clientOperationId` that was already applied is recognised and not
  applied again.
- Archiving blocks **new** Jobs for the Property and removes it from the default list and from
  Property selectors. It never cancels, archives, reschedules or otherwise modifies an existing Job
  or Visit, and existing work can still be rescheduled or added to (`BR-083`).
- Every state change appends one row to `property_lifecycle_history`; the table is append-only
  (`BR-067`, `BR-086`).


### 4.3 `DELETE /customers/:id/properties/:propertyId` — permanent deletion (`BR-082`)

Physically removes the Property. It succeeds with `204 No Content` only when **no other business
record has ever referenced the Property**: a Job, a Visit, a Property ↔ Customer relationship, a
Job/Visit location-history row or any other reference. The reference check and the delete run in one
transaction under the Property row lock, so a reference created concurrently cannot slip between
them.

- When anything references the Property, the route answers `409 PROPERTY_HAS_REFERENCES` with
  `details.referenceKinds` naming the families found (for example `customerRelationships`, `jobs`,
  `visits`, `jobPropertyHistory`, `visitLocationHistory`). The client explains the impact and offers
  archiving instead.
- Nothing is ever cascade-deleted. Deleting a Property never deletes or rewrites Jobs, Visits,
  notes, attachments, audit records or other history (`BR-082`).
- The Property's **own** archive/restore history is not a reference: it is removed with the Property,
  so archiving and restoring an otherwise unused Property does not make it permanently undeletable
  (`BR-082`, `ADR-012` D5).
- Permanent deletion is **online-only** and is never queued as an offline operation (`BR-086`).

### 4.4 Error codes

| Status | `code`                      | Meaning                                               |
| ------ | --------------------------- | ----------------------------------------------------- |
| `400`  | `VALIDATION_FAILED`         | A supplied value failed validation                    |
| `401`  | `UNAUTHENTICATED`           | No session                                            |
| `403`  | `FORBIDDEN`                 | The session lacks the route's Property capability     |
| `404`  | `PROPERTY_NOT_FOUND`        | Unknown, or outside the organization/Customer scope   |
| `409`  | `PROPERTY_VERSION_CONFLICT` | The mutation named a version the Property left behind |
| `409`  | `PROPERTY_HAS_REFERENCES`   | Permanent deletion is prohibited while references exist |

