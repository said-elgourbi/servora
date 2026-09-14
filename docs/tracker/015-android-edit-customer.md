# Tracker 015 — Edit Customer (Android screen + API edit and type conversion)

**Status: COMPLETE for API and Android implementation; physical-device QA is the product owner's**

Date: 2026-09-14
Predecessor: `docs/tracker/014-android-add-customer.md`
Business rules: `BR-001`, `BR-007`, `BR-011`, `BR-023`, `BR-028`, `BR-041`, `BR-042`, `BR-067`,
**`BR-087`**
Domain model: `docs/domain/foundation-domain-model.md` §7, §7.1, §10, §13
API contract: `docs/api/customers.md` §5.3
Design reference: `Figma/src/screens/Customers.tsx` (`EditCustomerView`)

## Scope

View Customer's **Edit** action opened a stub screen with one inert field: the customer edit write
path did not exist. This slice makes the edit real, and with it the product decision that a customer
can be **converted between individual and company**.

| Item | Status |
| --- | --- |
| `BR-087` added to the business rules (conversion, replacement, history, no field mapping) | Done |
| `PATCH`/`PUT /customers/:id` accepts `type` and performs a conversion | Implemented |
| Conversion is one transaction: `type`, the removed subtype row, the new subtype row | Implemented |
| `customer_lifecycle_history` table + migration `0008` (append-only, one row per conversion) | Implemented |
| A contradictory body (wrong subrecord, missing required fields) is `400`, never silently dropped | Implemented |
| Android `EditCustomerViewModel` + `EditCustomerUiState` (read, prefill, validate, save) | Implemented |
| Android `EditCustomerScreen` — type control, names, phone, email, notes, status, actions | Implemented |
| The conversion notice states what the type change replaces before it is applied | Implemented |
| Navigation: Edit opens the real form and returns to the refreshed customer | Implemented |
| Sign-out releases the form's state | Implemented |
| EN/FR copy for every new string | Implemented |
| API unit spec (edit parsing) and API e2e (conversion both ways, history, refusal, authorization) | Implemented |
| Android JVM tests (ViewModel, repository) and Compose tests | Implemented |
| Offline local store / outbox | **Not implemented — see below** |

## The decision this slice implements

Product ownership decided the conversion's semantics directly. Recorded as `BR-087`:

- The edit form must require the fields that apply to the **new** customer type.
- The old subtype row is deleted and the new matching row created **in the same database transaction**
  as `customers.type` is updated.
- **No field is mapped** between the two concepts — an individual's name never becomes a company's
  legal name.
- The conversion is recorded in customer lifecycle/audit history: customer, `fromType`, `toType`, the
  acting user and the timestamp. The previous subtype's personal/business values are **not**
  snapshotted there.
- The invariant is unchanged: exactly one subtype row exists and it matches `customers.type`.

## Figma vs the authoritative model

| # | Figma | Model | Resolution |
| - | ----- | ----- | ---------- |
| 1 | Customer type Individual / Business | `customers.type` + exactly one subtype row | Drawn; changing it is the conversion (`BR-087`) |
| 2 | Company name | `customer_companies.legal_name` | Drawn |
| 3 | Individual first/last name | `customer_individuals.first_name/last_name` | Drawn |
| 4 | Phone (prototype required) | `customers.phone` optional (`BR-023`) | Drawn and marked optional; the form never requires a value the model allows to be absent |
| 5 | Email (marked optional) | `customers.email` optional | Drawn |
| 6 | Notes | `customers.notes` optional | Drawn |
| 7 | Customer status Active / Inactive | `customers.status` | Drawn as the design's two-option control |
| 8 | — (no field) | `display_name` required by the API | Derived in the client: the company name, or "First Last" |
| 9 | Business primary contact first/last name | A contact is a `customer_contacts` row | **Not drawn**: the create flow already records a contact, and a contact has no update route (`docs/api/customers.md` §5.2) |
| 10 | — (no element) | — | **Added:** a localized notice while the stated type differs from the stored one, because a conversion replaces the current type's details and `BR-067` requires the decision to be explicit |
| 11 | "Properties are managed from Customer Detail." under the status options | — (presentation only) | **Drawn**, worded for the title the user sees rather than the design's internal screen name ("customer detail screen" / "la fiche client") |

One deliberate visual deviation, recorded rather than left implicit: the design tints Active with the
success colour, while the form reuses the same brand-filled segmented control the type control uses,
so one control has one appearance.

**Superseded (2026-09-14 follow-up).** The status options no longer reuse the type control's
appearance. Device review found the selected status option sitting immediately above the action bar
in the same brand fill, same field height and same shape as the **Save** button, so a state and an
action read as one block. The type control is a genuine choice among the form's control family and
keeps the brand-filled appearance; the status options take the state colour language the app already
paints a customer's state with — `StatusPill` (`CustomersScreen`) and `JobStatusPill`
(`CustomerDetailScreen`) — and the design's own tint for the same control
(`Figma/src/screens/Customers.tsx:1411-1419`). The form also regains the design's footer: the hint
line under the status options and the air above the action bar.

## Data model change

`customer_lifecycle_history` is a new append-only table (`BR-033`, `BR-087`):

| Column | Meaning |
| ------ | ------- |
| `id` | UUID primary key |
| `organization_id` | Tenant boundary; cascades with the organization |
| `customer_id` | The customer the event belongs to; cascades with the customer |
| `action` | Event kind, `CHECK`-constrained to `TYPE_CONVERTED` |
| `from_type`, `to_type` | The previous and the new type, `CHECK`-constrained to the customer type codes and to being different |
| `actor_membership_id` | The member who performed the conversion |
| `recorded_at` | Database timestamp (`now()`), never a client time |
| `created_at` | Row creation |

The replaced subtype's own values are deliberately absent: duplicating them would create a second copy
of personal or business data that no rule asks the API to keep (`BR-042`).

The migration is hand-written, like `0005`–`0007`: `drizzle/migrations/meta/` holds no snapshot past
`0004`, so a generated migration would diff against a stale snapshot and try to re-create structures
that already exist.

## Permissions

The edit — including a conversion — requires **`customers.edit`**, the capability that already governs
`PATCH`/`PUT /customers/:id`. No new capability was introduced: a conversion is an edit of the
customer's own data, not a lifecycle concern of its own (unlike Property archive/restore, `BR-085`).
Android reaches the form through the existing permission-gated Edit action, and the backend remains
the authority (`BR-007`).

## Behaviour notes

- The edit is partial: a member the client does not send is left as it is. Stating `type` is the one
  exception — it says which kind of customer this is, so it must be accompanied by `displayName` and
  by the new kind's required fields (`BR-087`).
- Re-stating the customer's **current** type is an ordinary edit: it writes no lifecycle row.
- A subrecord sent without `type` is applied only when it matches the customer's stored type; a
  contradicting payload is `400`, not a silent no-op. The parser cannot judge this (it does not know
  the stored type), so the service does and the controller maps it to `400 VALIDATION_FAILED`.
- No value crosses between the two subtype concepts. The Android form clears the fields of the type
  left behind, so what the user sees is what is stored.
- The customer is read before the form is editable. Until the read answers the fields are not drawn,
  and a failed read is reported with a retry rather than as an empty customer.
- After a save, the customer's detail **and** the list are re-read from the backend rather than
  patched locally: a conversion changes the type the list row shows (`BR-001`).
- `individual.dateOfBirth` is not a form field (the design has none), and omitting it leaves an
  existing value as it is. Converting **to** an individual writes `null`, because there is nothing to
  carry over (`BR-087`).
- Signing out releases the form's state together with the rest of the session-scoped UI state
  (`BR-001`).


## Known limitations and follow-ups

- **No offline path.** The edit is a direct API call; the local store and outbox described in
  `docs/architecture/offline-first-architecture.md` do not exist yet, so an offline save fails with the
  honest failure state rather than being queued (`BR-013`, `BR-014`, `BR-031`).
- **An optional field cannot be cleared from the form.** The shared `Json` configuration omits a
  member that holds its default, so a blanked phone, email or note is omitted and the API leaves it
  unchanged. This is the same behaviour the Property edit has today; clearing a field is a
  cross-cutting follow-up, not a decision invented here (`BR-032`, `BR-042`).
- **A soft-deleted (archived) customer is editable.** `PATCH /customers/:id` has always resolved any
  customer in the caller's scope, and no rule says otherwise. Whether an archived customer should be
  editable, and what converting one means, is an **OPEN QUESTION** (`BR-023`).
- **The Customer status control and archiving are two things, and only one is reversible.**
  `PATCH /customers/:id` has always accepted `status` (`ACTIVE`/`INACTIVE`), so the form draws the
  design's control and sends it. Archiving is separate: `POST /customers/:id/archive` sets `status`
  **and** `deleted_at`, and a customer whose `deleted_at` is set stays hidden from the default list
  whatever its `status` says. Setting the control back to Active therefore does **not** restore an
  archived customer. Whether a Customer should have a restore (as a Property does), and what the
  status control should do for an archived customer, are **OPEN QUESTION**s (`BR-023`, `BR-042`).
- **Customer archiving is still not reachable from the app.** `docs/tracker/010-customer-detail.md`
  noted it would return with the Edit Customer flow; the design's edit view has no archive action, so
  it was not invented here. `POST /customers/:id/archive` and `customers.archive` remain unused by
  Android.
- **The conversion is not shown anywhere yet.** It is recorded in `customer_lifecycle_history`, but no
  screen reads it: customer activity is `BR-080`'s read model and is not built.
- **Contacts still have no capability of their own** and no update route (unchanged from
  `docs/api/customers.md` §5.2).

## Files

```text
.clinerules/Business Rules.md                                 (BR-087)

api/src/database/schema.ts                                    (customer_lifecycle_history)
api/src/customers/customer.types.ts                           (lifecycle action codes + row types)
api/src/customers/customer.dto.ts                             (type + conversion group parsing)
api/src/customers/customers.service.ts                        (conversion in one transaction)
api/src/customers/customers.controller.ts                     (domain validation -> 400)
api/src/customers/customer.dto.spec.ts                        (new — edit parsing)
api/drizzle/migrations/0008_customer_lifecycle_history.sql    (new)
api/drizzle/migrations/meta/_journal.json                     (journal entry)
api/test/customer-type-conversion.e2e-spec.ts                 (new)

android/app/src/main/java/com/servora/android/data/customers/CustomerUpdateDtos.kt   (new)
android/app/src/main/java/com/servora/android/data/customers/CustomersApi.kt
android/app/src/main/java/com/servora/android/data/customers/CustomersRepository.kt
android/app/src/main/java/com/servora/android/data/customers/CustomersResult.kt
android/app/src/main/java/com/servora/android/domain/model/CustomerDetail.kt
android/app/src/main/java/com/servora/android/ui/customers/EditCustomerUiState.kt     (new)
android/app/src/main/java/com/servora/android/ui/customers/EditCustomerViewModel.kt   (new)
android/app/src/main/java/com/servora/android/ui/customers/EditCustomerScreen.kt      (new)
android/app/src/main/java/com/servora/android/ui/customers/AddCustomerScreen.kt
android/app/src/main/java/com/servora/android/ui/customers/AddPropertyScreen.kt
android/app/src/main/java/com/servora/android/ui/customers/CustomersScreen.kt
android/app/src/main/java/com/servora/android/ui/navigation/ServoraNavHost.kt
android/app/src/main/java/com/servora/android/ui/auth/AuthFlowScreen.kt
android/app/src/main/java/com/servora/android/MainActivity.kt
android/app/src/main/res/values/strings.xml, values-fr/strings.xml

android/app/src/test/java/com/servora/android/data/customers/CustomersRepositoryTest.kt
android/app/src/test/java/com/servora/android/ui/customers/EditCustomerViewModelTest.kt   (new)
android/app/src/androidTest/java/com/servora/android/ui/customers/EditCustomerScreenTest.kt (new)
android/app/src/androidTest/java/com/servora/android/ui/navigation/ServoraHomeNavigationTest.kt

docs/api/customers.md, docs/domain/foundation-domain-model.md
docs/tracker/015-android-edit-customer.md                     (new)
```



## Verification (2026-09-14)

```text
API
  npm run typecheck                              PASS
  npm run lint (oxlint)                          PASS   0 warnings, 0 errors (129 files)
  npm test                                       PASS   314 tests / 36 files
  npm run test:e2e (PostgreSQL)                  PASS   164 tests / 12 files
                                                        (incl. customer-type-conversion: 10 tests)
  npm run build (nest build)                     PASS

Android
  ./gradlew compileDebugKotlin                   PASS
  ./gradlew compileDebugAndroidTestKotlin        PASS
  ./gradlew testDebugUnitTest                    PASS   220 tests / 21 files
  ./gradlew lintDebug                            PASS
  ./gradlew assembleDebug                        PASS   debug APK
  ./gradlew connectedDebugAndroidTest            NOT RUN — device QA belongs to the product owner
```

Migration `0008_customer_lifecycle_history` is applied by the e2e suite's global setup against the
local PostgreSQL, so the table and its constraints are exercised by the conversion tests.

New coverage, by level:

| Level | Where | Covers |
| ----- | ----- | ------ |
| API unit | `api/src/customers/customer.dto.spec.ts` | partial edit, the required conversion group, trimming, the rejected contradicting subrecord, an unknown type |
| API e2e | `api/test/customer-type-conversion.e2e-spec.ts` | conversion both ways, the replaced subtype rows, the lifecycle row (from/to/actor), a Property surviving the conversion, no lifecycle row for an ordinary edit or a re-stated type, `400` for an incomplete or contradictory body, `403`, `404`, `401` |
| Android JVM | `EditCustomerViewModelTest` | read and prefill for both types, load failure, retry, no save before the read, no save of an incomplete conversion, the request a conversion sends, the status, a rejected edit, session scoping, reset |
| Android JVM | `CustomersRepositoryTest` | the edit's HTTP path, the token, validation classification, session renewal, no session |
| Android Compose | `EditCustomerScreenTest` | the loading state, the failed read with retry, each type's fields, the conversion notice, the incomplete-form message, the two actions, returning on success |

## Physical-device QA (product owner)

```text
1. Sign in as a Manager, open Customers, open a customer, tap Edit.
   Expect: the form opens with the customer's current values (type, name, phone, email, notes,
   status) already filled in, and no conversion notice.
2. Change a value (for example Notes) and tap Save Changes.
   Expect: the form closes back to View Customer, and the changed value is shown there.
   Reopen Edit: the value is still the changed one.
3. Open Edit on an individual customer and tap Business.
   Expect: the company name field appears, the first/last name fields are gone, and a notice appears
   saying that changing the type replaces the details of the previous type.
4. Type a company name and tap Save Changes.
   Expect: the form closes, View Customer now shows the business customer, and the Customers list
   shows it as Business.
5. Reopen Edit on the same customer and tap Individual.
   Expect: the company name field is gone, first/last name are empty (nothing was carried over), and
   the notice is shown again.
6. Type a first and last name and tap Save Changes.
   Expect: the form closes and the customer is an individual again in View Customer and in the list.
7. Open Edit again, clear the company name while the type is Business, then tap Save Changes.
   Expect: the form stays open and states that the required fields must be filled in.
8. Toggle Customer status to Inactive and Save.
   Expect: the change is applied (the customer is still listed, with its status shown as Inactive).
9. With the device offline, change a value and tap Save Changes.
   Expect: an honest failure message; nothing is queued (no offline working set exists yet).
10. Rotate the device while the form is open.
    Expect: the values the user typed are kept.
11. Scroll the form to the bottom and check the last row before the Cancel / Save Changes bar.
    Expect: the status options are tinted with the app's state colours — the selected one filled, the
    other quiet — the hint line sits under them, and neither touches the action bar.
```

Known at hand-off: an optional field that is blanked out is left unchanged by the backend (see
"Known limitations"), so this runbook does not use blanking a phone, email or note to check clearing.

## Follow-up — status option appearance and form footer (2026-09-14)

Device review found the status row sitting immediately on top of the Cancel / Save Changes bar and
reading as part of it. Three causes were removed, all presentation-only (`BR-023`, `BR-028`):

| Change | Where |
| ------ | ----- |
| The status options draw their own appearance — `Active` on `stateColors.successContainer`/`success`, `Inactive` on the quiet neutral one, unselected carrying no border — instead of reusing the type control's brand fill | `EditCustomerScreen.kt` → `CustomerStatusOption` |
| The design's hint line under the status options | `EditCustomerScreen.kt` → `CustomerPropertiesHint`, `customers_edit_properties_hint` (EN/FR) |
| A trailing 12 dp keeps the last control off the action bar, on top of the scroll content's own padding | `EditCustomerScreen.kt` → `EditCustomerFormFooterSpacing` |

The type control (`CustomerTypeSection`) is untouched, and so is the shared `CustomerSegmentOption`
that Add Customer also draws (`AddCustomerScreen.kt`). No API, database, permission, contract or
navigation change; the form's status value is still committed only by Save Changes, so no control
implies an immediate write.

```text
Android
  ./gradlew testDebugUnitTest                     PASS
  ./gradlew compileDebugAndroidTestKotlin         PASS
  ./gradlew lintDebug                             PASS
  ./gradlew assembleDebug                         PASS   debug APK
  ./gradlew connectedDebugAndroidTest             NOT RUN — device QA belongs to the product owner
```

Physical-device QA (`qa.md` §7): **AWAITING PRODUCT OWNER** — manual QA step 11 above.

