# Tracker 011 — Add Property (Android screen + API write)

**Status: COMPLETE** (API write endpoint + Android screen; physical-device QA is the product owner's)

Date: 2026-09-13
Predecessor: `docs/tracker/010-customer-detail.md`

> **Superseded in part — Property authorization.** This slice authorized the Property create with
> the interim `customers.edit`, because no `properties.*` capability existed. Product ownership has
> since confirmed the Property capability set (`BR-085`): `POST /customers/:id/properties` now
> requires `properties.create`. See
> `docs/tracker/012-property-permissions-and-lifecycle-schema.md` and `ADR-012`. Everything else in
> this tracker still describes the shipped behaviour.
Business rules: `BR-001`, `BR-007`, `BR-011`, `BR-028`, `BR-041`, `BR-042`, `BR-047`, `BR-049`,
`BR-050`, `BR-056`, `BR-081`
Domain model: `docs/domain/job-visit-domain-model.md` §3.1, §3.2
API contract: `docs/api/customers.md` §3.5
Design reference: `Figma/src/screens/Customers.tsx` (`PropertyFormView`, `ProvinceSelect`)

## Scope

The approved design introduces Properties, and Customer Detail already renders them from the
backend's projection (`BR-081`). This slice makes a Property **creatable**: the API exposes
`POST /customers/:id/properties`, and Android opens the design's Add Property form from Customer
Detail.

The theme of the slice is that the form draws exactly the authoritative Property fields. The design
shows two fields the model does not have, and one value the model owns rather than the user.

## Mismatches found between the Figma design and the authoritative model

| # | Figma | Authoritative source | Resolution |
| - | ----- | -------------------- | ---------- |
| 1 | `Property type` field | Not in `properties` (`api/src/database/schema.ts`, `job-visit-domain-model.md` §3.1) | **Not implemented** (as instructed); no field, no column, no contract |
| 2 | `Access instructions` field | Not in the model; `notes` is the property's free text | **Not implemented** (as instructed) |
| 3 | No Country field | `properties.country NOT NULL DEFAULT 'Canada'` | The backend writes `Canada`; the client never sends or shows a country |
| 4 | `unit` is its own property; `addressLine2` unmodelled | `properties.address_line2` is the unit/suite column | Unit / Suite maps to `addressLine2` |
| 5 | Province picker over the 13 codes `AB…YT` | The design, the API contract example (`QC`) and the e2e fixtures (`ON`) all use the two-letter code; the **development seed** writes full names (`Ontario`) | The code is the exchanged value; the API validates the 13 codes and the client sends one. The seed's full names are flagged below |
| 6 | Primary action `Save Property` plus `Cancel` | No rule; presentation only | Implemented as designed |
| 7 | Global **+ New Job** FAB (`Dashboard.tsx`) / Customer Detail **Create Job** FAB | Android has no Dashboard FAB; the only FABs are New Customer (list) and Create Job (Customer Detail) | The Create Job FAB lives inside the Customer Detail composable, so it is **not composed** while Add Property is on top. The form draws no FAB of its own and a test asserts the Create Job action is absent |

Nothing was invented for the OPEN QUESTIONs: no field, status, permission or relationship was added
beyond what the model and rules already define.

## Contract and authorization

| Item | Decision |
| ---- | -------- |
| Endpoint | `POST /customers/:id/properties` (`customers.controller.ts`) |
| Permission | **Superseded.** This slice used the interim `customers.edit`, because no `properties.*` capability existed (`BR-042`). `BR-085` now defines the Property catalogue, and the create requires `properties.create` (`ADR-012` D1) |
| Tenant scope | The customer is resolved as `(organizationId, id)` inside the transaction; a customer the caller's organization does not own is `404 CUSTOMER_NOT_FOUND`, never `403` (`BR-001`) |
| Relationship | `properties` is inserted, then the active `property_customer_relationships` row (`BR-049`, `BR-050`). `properties.customer_id` does **not** exist and was not introduced |
| Country | `PROPERTY_COUNTRY = 'Canada'` is written by the service; there is no client field |
| Province | Validated against the 13 stable Canadian codes (`property.dto.ts`); a full name or a foreign code is `400 VALIDATION_FAILED` |
| No migration | The tables already exist (`0003_purple_black_tarantula.sql`); no schema change was needed |

## Android

| Item | Status |
| ---- | ------ |
| `ServoraTopBarState` gained an optional `subtitle`; the bar draws it under the title | Done |
| Route `customer/properties/new/{customerId}` with the destination's own header: **Add Property** over the customer's name | Done |
| Header context resolves from the customer detail the destination itself reads, so a restored back stack still names the customer | Done |
| `AddPropertyScreen` matches the design: two sections with a glyph and an uppercase name, 52 dp fields, quiet fill and outline, labels above the fields with an `(optional)` marker | Done |
| Province is a picker (bottom sheet) over the stable codes, shown as `QC — Québec` | Done |
| Validation message after a save attempt, and a localized message per create failure | Done |
| Cancel and Save Property in a pinned action row (52 dp, above the bottom navigation) | Done |
| Customer Detail's Properties card gains a permission-gated **+ Add Property** row | Done |
| The form draws **no** FAB; the unrelated Create Job action never floats over it | Done |
| Back: the top bar arrow and Android system Back both pop the same entry, so both return to the customer without leaving the app | Done |
| After a successful save the customer's detail is re-read from the backend rather than patched locally (`BR-001`) | Done |
| JVM ViewModel and repository tests; Compose screen and navigation tests | Done |

## Files

```text
api/src/customers/property.dto.ts                       (new — codes, CreatePropertyDto, parser, country)
api/src/customers/property.dto.spec.ts                  (new — parser unit tests)
api/src/customers/customers.service.ts                  (createPropertyForCustomer)
api/src/customers/customers.controller.ts               (POST /customers/:id/properties)
api/test/customer-detail-projection.e2e-spec.ts         (service create + tenant refusal)
api/test/customers-authorization.e2e-spec.ts            (allow/forbid/validate/404)
docs/api/customers.md                                   (§3.5, permissions note, status)

android/.../domain/model/PropertyProvince.kt            (new — the 13 stable codes)
android/.../data/customers/CustomerDetailDtos.kt         (CreatePropertyRequest)
android/.../data/customers/CustomersResult.kt            (PropertyCreateResult, VALIDATION reason)
android/.../data/customers/CustomersApi.kt               (createProperty)
android/.../data/customers/CustomersRepository.kt        (createProperty + renewal retry)
android/.../ui/customers/AddPropertyUiState.kt           (new)
android/.../ui/customers/AddPropertyViewModel.kt         (new)
android/.../ui/customers/AddPropertyScreen.kt            (new)
android/.../ui/customers/CustomersViewModel.kt           (reloadCustomerDetail)
android/.../ui/customers/CustomerDetailScreen.kt         (Add Property affordance)
android/.../ui/customers/CustomersScreen.kt              (shell wiring, header context lookup)
android/.../ui/navigation/ServoraNavHost.kt              (route, destination, header)
android/.../ui/components/ServoraTopBar.kt               (optional context line)
android/.../ui/auth/AuthFlowScreen.kt, MainActivity.kt   (ViewModel wiring)
android/app/src/main/res/drawable/ic_map_pin.xml         (new)
android/app/src/main/res/drawable/ic_file_text.xml       (new)
android/app/src/main/res/drawable/ic_chevron_down.xml    (new)
android/app/src/main/res/drawable/ic_alert_circle.xml    (new)
android/app/src/main/res/values/strings.xml              (EN copy, province names)
android/app/src/main/res/values-fr/strings.xml           (FR copy, province names)

docs/decisions/011-android-contextual-top-bar.md         (subtitle field, D3.1)
docs/design/android-design-system.md                     (context-line token)
```

## Verification (2026-09-13)

```text
API
  npm test                                         PASS   269 tests / 34 files
                                                     (incl. property.dto.spec.ts, 9 tests)
  npm run test:e2e                                 PASS   126 tests / 9 files
  npm run lint (oxlint)                            PASS   0 warnings, 0 errors
  npm run build (nest build)                       PASS

Android
  ./gradlew compileDebugKotlin                     PASS
  ./gradlew testDebugUnitTest                      PASS   160 tests
                                                     (incl. AddPropertyViewModelTest,
                                                      the new repository create cases and
                                                      the reloadCustomerDetail case)
  ./gradlew compileDebugAndroidTestKotlin          PASS
                                                     (incl. AddPropertyScreenTest,
                                                      ServoraTopBarTest context line,
                                                      CustomerDetailScreenTest Add Property,
                                                      ServoraHomeNavigationTest Add Property)
  ./gradlew lintDebug                              PASS
  ./gradlew assembleDebug                          PASS   debug APK
  ./gradlew connectedDebugAndroidTest              NOT RUN — device QA belongs to the product owner
```

Instrumented execution is the product owner's (`qa.md` §7); the agent does not drive the owner's
phone. Command to run against a device or emulator:

```text
cd android && ./gradlew connectedDebugAndroidTest
```

The unit and instrumentation **compilation** gates pass, which is what the agent owns; the Compose
assertions themselves run only on a device.

## Open questions recorded (not implemented)

1. **Property permission — RESOLVED.** Product ownership decided that Properties have their own
   capability set (`BR-085`, `ADR-012` D1). Property creation requires `properties.create`, and the
   customer-detail Property projection requires `properties.view`. The interim `customers.edit`
   authorization described in this tracker no longer applies. Implemented in
   `docs/tracker/012-property-permissions-and-lifecycle-schema.md`.
2. **Province vocabulary in the development seed.** The seed writes full province names
   (`Ontario`, `Quebec`) while the design, the API contract and the tests use the two-letter codes.
   The create path stores and validates the code, so seeded rows and created rows disagree in
   representation. Changing the seed is dev-data work and was not done here; a product decision on
   the canonical stored form is needed before the two can be reconciled.
3. **`BR-050` — a Property with no active Customer relationship.** The create path always
   establishes an active relationship, so the model's open question is untouched.

## Physical-device QA (product owner)

```text
1. Sign in as a Manager.
2. Open Customers → a customer → Properties → + Add Property.
3. Expect: the top bar reads "Add Property" over the customer's name; no Create Job FAB.
4. Leave Street address empty and press Save Property.
   Expect: a message naming what is missing; nothing is written.
5. Fill street, city, pick a province, fill the postal code; optionally fill unit, name, notes.
6. Press Save Property.
   Expect: the app returns to the customer and the new Property appears with 0 jobs.
7. Re-open Add Property and press Cancel, then repeat with the system Back gesture.
   Expect: both return to the customer and never leave the app.
8. Switch the app to French and repeat step 4.
   Expect: French labels, province names and message.
```


