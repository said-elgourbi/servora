# ADR-012 — Property permissions, the archived projection and the Property lifecycle schema

**Status: Accepted** (product-owner decisions, 2026-09-13)

Extends `ADR-009` (Android session persistence) by naming the offline/outbox standard it builds on.
No earlier decision is superseded.

References: `BR-001`, `BR-006`, `BR-007`, `BR-011`, `BR-031`, `BR-032`, `BR-041`, `BR-057`,
`BR-060`, `BR-081`, `BR-082`, `BR-083`, `BR-084`, `BR-085`, `BR-086`, `Project.md` §8, §9, §13,
§14, §15, §31, `dev.md` §4, §6, §7, §10, §18, `docs/architecture/offline-first-architecture.md`,
`docs/domain/job-visit-domain-model.md`, `docs/tracker/011-android-add-property.md`.

## Context

`BR-082` – `BR-086` confirmed the Property lifecycle but left four product questions open, and the
repository carried three consequences of that:

1. Property creation was authorized by `customers.edit` as an explicit **interim** decision,
   because no `properties.*` capability existed. `BR-085` defines the Property capability set and
   states that Property capabilities are independent of `customers.*`.
2. The customer-detail Property projection (`BR-081`) showed every Property the customer was
   related to. Whether it excludes an archived Property was undecided, and `BR-083` flagged two
   different meanings of "active" as a divergence to confirm.
3. `BR-082`'s reference list for permanent deletion included the Property's own lifecycle
   history, which meant a Property that had ever been archived could never be permanently deleted.
4. `BR-086` depended on an offline/outbox standard that did **not** exist in the repository, and a
   dedicated Property address-history table (`BR-057`) had no decision.

## Decisions

### D1 — `properties.create` authorizes creating a Property

The catalogue gains `properties.create` (`BR-085`). Creating a Property is a distinct, common
permission boundary, so it is not folded into `properties.edit` or reused from `customers.edit`.
`POST /customers/:id/properties` requires `properties.create`; the interim `customers.edit`
authorization is removed.

The Manager system role receives all five Property capabilities; the Technician role receives none
(`BR-008`, `BR-009`).

### D2 — The Property projection requires `properties.view`

`GET /customers/:id/properties` requires `properties.view` (`BR-085`): a Property capability is
never implied by `customers.*`. The customer header's `propertyCount` remains a customer
projection read under `customers.view`, but it counts the same `ACTIVE` set as the list so the
header and the list agree.

`CustomerDetail` in Android therefore treats the Property projection as optional: a `403` on it
returns no Properties instead of failing the header, the contacts and the Jobs, and the screen
omits the Properties section for a caller without `properties.view`.

### D3 — The default customer-detail projection excludes archived Properties

The projection is the customer's current **`ACTIVE`** relationships. An `ARCHIVED` Property is
excluded from the list and from `propertyCount`, and remains a retrievable business record reached
through explicit archived/history views (`BR-081`, `BR-082`, `BR-083`).

### D4 — Two named "active" vocabularies, not one

`BR-060`'s Visit set is named `needsSchedulingActiveVisit`; `BR-083`'s Job/Visit warning set is
named `archiveWarningOpenWork`. They answer different questions and are not merged: a `DRAFT`
Visit is open work for the archive warning but is not yet scheduled work for the scheduling signal.

### D5 — A Property's own lifecycle history does not block its permanent deletion

`property_lifecycle_history.property_id` is `ON DELETE CASCADE`. Permanent deletion stays blocked
by **other** business records — Jobs, Visits, Property ↔ Customer relationships, notes,
attachments, address/use history and anything else — but archiving and restoring an otherwise
never-used Property does not permanently trap it (`BR-082`).

`property_customer_relationships.property_id` stays `RESTRICT`: every Property created through the
API has an active relationship, so archiving — not deletion — is its removal.

### D6 — A dedicated Property address-history table is deferred

`BR-057`'s question stays open and is now explicitly **deferred** until an audit screen or a
compliance requirement needs it. Job and Visit address snapshots remain the authoritative record
of the location used at the time; nothing is rewritten.

### D7 — The offline/outbox standard is defined before offline Property lifecycle

`docs/architecture/offline-first-architecture.md` defines the local working set, the outbox
operation, idempotency through a client-generated `operationId`, replay/retry/failure handling,
synchronization-state presentation and the conflict model that `BR-086`'s offline-capable archive
and restore follow. Room (SQLite) is named as the storage engine; it is not yet a dependency.

Implementation of the Property lifecycle endpoints and their Android flows follows that standard
and is the next slice. Permanent deletion is online-only and is never queued.

## Consequences

- The Property capability catalogue exists in the database (`0007_property_lifecycle_and_permissions`),
  the API constant and the Android permission mirror. `properties.edit`, `properties.archive` and
  `properties.delete` are catalogue entries the lifecycle slice wires up; they are not enforced by a
  route yet because that route does not exist.
- `properties` gains `status`, `archived_at`, `archived_by_membership_id` and `version`, and
  `property_lifecycle_history` is created, so the confirmed `BR-082` – `BR-086` model is persisted.
- A role holding `customers.edit` without `properties.create` can no longer add a Property. This is
  the intended behaviour change and is covered by an authorization test.
- A caller without `properties.view` sees a customer without its Properties section rather than a
  refused request.
- The two "active" sets are named in the rules, so a later reviewer does not merge them.
- `BR-086`'s offline open question is closed by the new standard; the per-operation conflict
  strategy remains open (`BR-032`) and is recorded rather than guessed.

## Verification

```text
API
  npm run typecheck        PASS
  npm run lint             PASS   0 warnings, 0 errors
  npm test                 PASS   269 tests / 34 files
  npm run test:e2e         PASS   127 tests / 9 files
  npm run build            PASS

Android
  ./gradlew compileDebugKotlin             PASS
  ./gradlew compileDebugAndroidTestKotlin  PASS
  ./gradlew testDebugUnitTest              PASS
  ./gradlew lintDebug                      PASS
  ./gradlew assembleDebug                  PASS
  ./gradlew connectedDebugAndroidTest      NOT RUN — device QA belongs to the product owner
```

`customers-authorization.e2e-spec.ts` covers `properties.create` and `properties.view`, including
that a customer capability does not open a Property action. `CustomerPermissionsUiStateTest`
covers the same independence in the client. `CustomersRepositoryTest` covers the optional Property
projection.
