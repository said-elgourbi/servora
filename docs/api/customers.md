# Servora API — `/customers` Contract

**Status: IMPLEMENTED** for `GET /customers`, `GET /customers/:id`, `GET /customers/:id/properties`,
`GET /customers/:id/jobs`, `POST /customers`, `POST /customers/:id/properties`, `PATCH|PUT
/customers/:id` and `POST /customers/:id/archive`.

Read projections on the customer detail are derived, not stored, and are defined by `BR-081`.
This document records the wire contract; the derivation itself is specified in
`Business Rules.md` (`BR-081`) and `docs/domain/job-visit-domain-model.md` §8.4.

References: `BR-001`, `BR-007`, `BR-023`, `BR-048`, `BR-050`, `BR-056`, `BR-058`, `BR-068`,
`BR-081`, `BR-082`, `BR-085`, `dev.md` §7, `dev.md` §8, `docs/decisions/012-property-lifecycle-and-permissions.md`,
`docs/tracker/010-customer-detail.md`, `docs/tracker/012-property-permissions-and-lifecycle-schema.md`.

## 1. Conventions

All payloads are JSON, `camelCase`, UUID identifiers and ISO-8601 UTC timestamps (`Project.md` §15).
Access tokens are presented as `Authorization: Bearer <accessToken>`. Route paths carry no version
prefix (`docs/versioning.md` §7).

Every route requires one of `customers.view`, `customers.create`, `customers.edit`,
`customers.archive`, `properties.view` or `properties.create` as noted below. **The backend is the
authorization authority**; a client that hides an action is not authorization (`BR-007`).

## 2. Permissions

Property capabilities are independent of the customer capabilities and are never implied by them
(`BR-085`).

| Permission          | Routes                                                          |
| ------------------- | --------------------------------------------------------------- |
| `customers.view`    | `GET /customers`, `GET /customers/:id`, `…/jobs`                 |
| `customers.create`  | `POST /customers`                                               |
| `customers.edit`    | `PATCH /customers/:id`, `PUT /customers/:id`                     |
| `customers.archive` | `POST /customers/:id/archive`                                   |
| `properties.view`   | `GET /customers/:id/properties`                                 |
| `properties.create` | `POST /customers/:id/properties`                                |

> **A Property read or write needs a Property capability.** `GET /customers/:id/properties`
> requires `properties.view` and `POST /customers/:id/properties` requires `properties.create`
> (`BR-085`). `properties.edit`, `properties.archive` and `properties.delete` are the confirmed
> catalogue entries the Property lifecycle slice wires up; no such route exists yet.
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

### 3.3 `GET /customers/:id/properties` — the customer's active Properties (`BR-081`)

One entry per **active** Property relationship (`BR-050`); an ended relationship is history and is
not returned.

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
    "jobCount": 4,
    "lastServiceAt": "2026-08-28T13:00:00.000Z"
  }
]
```

- `jobCount` — every Job associated with the Property, whatever its status.
- `lastServiceAt` — scheduled start of the most recent `COMPLETED` Visit across those Jobs, or
  `null` when the Property has never been serviced. The client presents a localized "never
  serviced" state for `null`; the API sends no display text (`BR-028`).
- **Archived Properties are excluded** (`BR-082`, `BR-083`). The projection is the customer's
  current `ACTIVE` relationships, and the customer's derived `propertyCount` counts the same set,
  so the header and this list agree. An archived Property is not deleted: it remains a retrievable
  business record for the explicit archived views, which are not part of this contract yet.

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
