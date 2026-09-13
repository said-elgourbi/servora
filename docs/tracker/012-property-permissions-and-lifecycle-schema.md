# Tracker 012 — Property permissions, the archived projection and the lifecycle schema

**Status: COMPLETE** (decisions + migration + API authorization + Android gating + documentation)

Date: 2026-09-13
Predecessor: `docs/tracker/011-android-add-property.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-011`, `BR-041`, `BR-057`, `BR-060`, `BR-081`,
`BR-082`, `BR-083`, `BR-084`, `BR-085`, `BR-086`
Decision record: `docs/decisions/012-property-lifecycle-and-permissions.md`
Architecture: `docs/architecture/offline-first-architecture.md`
Domain model: `docs/domain/job-visit-domain-model.md` §3.3, §6.4, §8.4, §21
API contract: `docs/api/customers.md` §2, §3.3, §3.5

## Scope

This slice closes the four product questions `BR-082` – `BR-086` left open and adds the schema the
confirmed Property lifecycle needs, so the lifecycle endpoints can be implemented next against a
standard instead of an invented one.

| # | Item | Status |
| - | ---- | ------ |
| 1 | `properties.create` authorizes Property creation; `properties.view` authorizes the Property projection | Implemented |
| 2 | Property permissions seeded for every organization and granted to the Manager system role | Implemented |
| 3 | Customer-detail Property projection and `propertyCount` exclude `ARCHIVED` Properties | Implemented |
| 4 | `properties` gains `status`, `archived_at`, `archived_by_membership_id`, `version` | Migrated |
| 5 | `property_lifecycle_history` created with `ON DELETE CASCADE` to `properties` | Migrated |
| 6 | `needsSchedulingActiveVisit` / `archiveWarningOpenWork` named as two vocabularies | Documented |
| 7 | Dedicated Property address-history table deferred | Documented |
| 8 | Offline/outbox standard written | Documented |
| 9 | Android permission mirror, Properties-section and Add Property gating | Implemented |

## What changed

**Permission catalogue.** `api/src/auth/permissions.ts` defines `PROPERTY_PERMISSIONS`;
`0007_property_lifecycle_and_permissions.sql` inserts the five rows and grants them to every
organization's `MANAGER` role. The development seed carries the same templates.

**API authorization.** `GET /customers/:id/properties` requires `properties.view`;
`POST /customers/:id/properties` requires `properties.create`. The interim `customers.edit` is gone.

**Projection.** `CustomersService` filters on `properties.status = 'ACTIVE'` in the Property list
and in both `propertyCount` derivations, so the header and the list agree.

**Schema.** The migration matches `docs/domain/job-visit-domain-model.md` §3.1/§3.3 exactly,
including the `property_lifecycle_history` FK being `CASCADE` rather than `RESTRICT`.

**Android.** `Permission` mirrors the Property codes; `CustomerPermissionsUiState` exposes
`canViewProperties` and `canCreateProperty`; the Properties section is omitted without
`properties.view` and Add Property is gated by `properties.create`; the repository treats a `403`
on the Property projection as "not permitted" instead of failing the customer.

## Explicitly NOT in this slice

Archive, restore and permanent-delete **endpoints** and their Android flows. `BR-086` sequences the
offline/outbox standard first, and this slice writes it; the lifecycle implementation is the next
slice. `properties.edit`, `properties.archive` and `properties.delete` therefore exist in the
catalogue but no route enforces them yet — recorded in the ADR, not silently dropped.

The archived/history views `BR-081` refers to are also not built; the archived Property is simply
excluded from the default projection.

## Open questions preserved (not implemented)

1. **Per-operation offline conflict strategy** (`BR-032`). The standard defines the general model
   and explicitly leaves the strategy per operation open.
2. **Property address-history retention** (`BR-057`). Deferred, not decided.
3. **Property-only retention of the archived record** (`BR-033`). Untouched.
4. **Outbox disposition at sign-out** and whether a rejected operation may be discarded. Recorded
   in `docs/architecture/offline-first-architecture.md` §11.

## Files

```text
api/src/auth/permissions.ts                                 (PROPERTY_PERMISSIONS)
api/src/customers/customers.controller.ts                   (guard per route)
api/src/customers/customers.service.ts                      (ACTIVE-only projection)
api/src/database/schema.ts                                  (properties lifecycle + history)
api/src/database/run-development-seed.ts                    (Property permission templates)
api/drizzle/migrations/0007_property_lifecycle_and_permissions.sql   (new)
api/drizzle/migrations/meta/_journal.json                   (journal entry)

android/app/src/main/java/com/servora/android/domain/auth/Permission.kt
android/app/src/main/java/com/servora/android/ui/customers/CustomerPermissionsUiState.kt
android/app/src/main/java/com/servora/android/ui/customers/CustomerDetailScreen.kt
android/app/src/main/java/com/servora/android/ui/navigation/ServoraNavHost.kt
android/app/src/main/java/com/servora/android/data/customers/CustomersRepository.kt

docs/decisions/012-property-lifecycle-and-permissions.md    (new)
docs/architecture/offline-first-architecture.md             (new — the missing BR-086 standard)
docs/api/customers.md, docs/domain/job-visit-domain-model.md, README.md
docs/tracker/011-android-add-property.md                    (superseded-permission banner)
```

## Verification (2026-09-13)

```text
API
  npm run typecheck                              PASS
  npm run lint (oxlint)                          PASS   0 warnings, 0 errors
  npm test                                       PASS   269 tests / 34 files
  npm run test:e2e (PostgreSQL)                  PASS   127 tests / 9 files
                                                        (incl. properties.create /
                                                         properties.view authorization)
  npm run build (nest build)                     PASS

Android
  ./gradlew compileDebugKotlin                   PASS
  ./gradlew compileDebugAndroidTestKotlin        PASS
  ./gradlew testDebugUnitTest                    PASS
  ./gradlew lintDebug                            PASS
  ./gradlew assembleDebug                        PASS
  ./gradlew connectedDebugAndroidTest            NOT RUN — device QA belongs to the product owner
```

Migration `0007_property_lifecycle_and_permissions` was applied by the e2e global setup against the
local PostgreSQL, so the schema and the permission rows are exercised by the suite.

## Physical-device QA (product owner)

```text
1. Sign in as a Manager and open Customers → a customer.
2. Expect: the Properties section is present and + Add Property is shown.
3. Add a Property; expect it appears with 0 jobs.
4. With a custom role holding customers.view but not properties.*, open the same customer.
   Expect: the customer loads, the Properties section is absent, no Add Property affordance.
5. On a device with no connection, open a customer.
   Expect: the current honest failure state (no offline working set exists yet).
```
