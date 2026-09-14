# Tracker 013 — Edit / Archive / Restore / Delete Property

**Status: COMPLETE for the API; Android implemented — physical-device QA is the product owner's**

Date: 2026-09-13
Predecessor: `docs/tracker/012-property-permissions-and-lifecycle-schema.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-031`, `BR-041`, `BR-056`, `BR-057`, `BR-067`,
`BR-081`, `BR-082`, `BR-083`, `BR-084`, `BR-085`, `BR-086`
Decision record: `docs/decisions/012-property-lifecycle-and-permissions.md`
Offline standard: `docs/architecture/offline-first-architecture.md`
API contract: `docs/api/customers.md` §3.3, §3.6, §4
Domain model: `docs/domain/job-visit-domain-model.md` §3.3, §6.4, §15

## Scope

Tracker 012 put the Property lifecycle **schema** and the Property capability catalogue in place and
recorded, deliberately, that `properties.edit`, `properties.archive` and `properties.delete` were not
enforced by any route. This slice implements the lifecycle itself: the API operations and the Android
actions on Property Detail, plus the form an edit reuses.

No business behaviour was invented: every rule applied here is `BR-082` – `BR-086`, and the open
questions those rules leave open (`BR-032` per-operation conflicts, `BR-057` address history,
`BR-033` retention) are untouched.

| # | Item                                                                  | Status      |
| - | --------------------------------------------------------------------- | ----------- |
| 1 | `GET /customers/:id/properties/:propertyId` — one Property, either state | Implemented |
| 2 | `PATCH`/`PUT …/:propertyId` — edit, active or archived                 | Implemented |
| 3 | `POST …/:propertyId/archive` and `POST …/:propertyId/restore`           | Implemented |
| 4 | `DELETE …/:propertyId` — permanent deletion, `409` while referenced     | Implemented |
| 5 | Default list/selector excludes `ARCHIVED`; `?status=` requests them     | Implemented |
| 6 | `archiveImpact` counts the `archiveWarningOpenWork` set                 | Implemented |
| 7 | Permanent deletion refused with `PROPERTY_HAS_REFERENCES` and kinds     | Implemented |
| 8 | `property_lifecycle_history` append-only, replay-safe via `clientOperationId` | Implemented |
| 9 | Property Detail screen with permission- and state-gated actions         | Implemented |
| 10 | Edit Property reusing the Add Property form                            | Implemented |
| 11 | EN/FR copy for every new string, dark/light safe                       | Implemented |
| 12 | Room-based local working set and outbox                                | **Not implemented — see below** |

## API

### Routes and capabilities (`BR-085`)

```text
GET    /customers/:id/properties/:propertyId           properties.view
PATCH  /customers/:id/properties/:propertyId           properties.edit
PUT    /customers/:id/properties/:propertyId           properties.edit
POST   /customers/:id/properties/:propertyId/archive   properties.archive
POST   /customers/:id/properties/:propertyId/restore   properties.archive
DELETE /customers/:id/properties/:propertyId           properties.delete
```

`GET /customers/:id/properties` gained an optional `status` filter (`ACTIVE` default, `ARCHIVED`,
`ALL`). The archived row now carries its `status` so a caller that asked for archived Properties can
present the lifecycle it actually has.

Archive and restore answer `200` (an explicit action returning the resource's state) and permanent
deletion answers `204`. Every route requires its own Property capability; a `customers.*` capability
does not open any of them.

### Files

```text
api/src/customers/property.dto.ts             (lifecycle DTOs, status vocabulary, filters, mapper)
api/src/customers/properties.service.ts       (new — the lifecycle rules and queries)
api/src/customers/properties.controller.ts    (new — the lifecycle routes)
api/src/customers/property.dto.spec.ts        (parser and mapper unit tests)
api/src/customers/customers.controller.ts     (status filter on the list route)
api/src/customers/customers.service.ts        (status filter; asDate exported)
api/src/customers/customer-detail.dto.ts      (CustomerPropertyDto.status)
api/src/customers/customers.module.ts         (registers the new controller and service)
api/src/auth/permissions.ts                   (comment: the catalogue is enforced)
api/src/validation/domain-validation.ts       (optionalUuid / optionalInstant / optionalPositiveInteger)
api/test/properties-lifecycle.e2e-spec.ts     (new — 21 tests)
```

### Decisions the implementation makes explicit

**Tenancy.** A Property is always resolved by `(organization_id, id)`. When the Property has Customer
relationships, the request must name one of them; a foreign id, or another customer of the same
organization, is `404 PROPERTY_NOT_FOUND` — never `403` (`BR-001`). A Property with no relationship
at all stays reachable, because `BR-082` keeps permanent deletion open for exactly that Property.

**Archive and restore are idempotent in state.** Archiving an archived Property (or restoring an
active one) changes neither the state nor the `version` and appends no second lifecycle event. A
replayed operation carrying a `clientOperationId` that was already applied is recognised from
`property_lifecycle_history` and returns the current state without applying anything again
(`BR-031`). The `(organization_id, client_operation_id)` unique index remains the backstop.

**Concurrency.** `update`, `archive`, `restore` and `delete` run in a transaction under a
`SELECT … FOR UPDATE` lock on the Property row. `expectedVersion`, when supplied, is compared inside
that lock, so a mutation against newer state is rejected (`409 PROPERTY_VERSION_CONFLICT`) rather
than applied (`BR-086`). The lock also makes the deletion's reference check and delete indivisible: a
concurrent reference insert waits on the row's foreign-key share lock.

**Archiving changes nothing else.** Archive touches only `properties` and appends one history row.
Jobs, Visits, schedules and assignments are read, never written (`BR-083`). The archive answer carries
the open-work counts so a client can state the impact without a second read.

**Permanent deletion.** The reference families checked are `jobs`, `visits`,
`property_customer_relationships`, `job_property_history` and `visit_location_history`. The
Property's **own** `property_lifecycle_history` is deliberately not one of them: it is removed with
the Property, so archiving and restoring an otherwise unused Property does not trap it
(`BR-082`, ADR-012 D5). Nothing is cascade-deleted; a database-level foreign-key refusal is mapped to
the same `409` rather than surfacing as a server error.

**Audit.** Archive and restore append to `property_lifecycle_history` (`BR-086`). An edit is
traceable through the row's `updated_at`, which is what the domain model specifies; no second
Property audit table was invented (`BR-042`). Permanent deletion removes the Property and, with it,
its own lifecycle history.

**No schema change.** `properties.status` / `archived_at` / `archived_by_membership_id` / `version`
and `property_lifecycle_history` already existed from `0007_property_lifecycle_and_permissions`, so
this slice adds no migration.

**Blocking new work.** `BR-083` blocks *new* Jobs for an archived Property. No Job write path exists
yet, so the rule is enforced by `PropertiesService.assertPropertyAvailableForNewWork`, the check the
Job-creation path calls, and by the archived Property being absent from the default selector list.
The service method is covered by tests; it is the single documented place the rule lives rather than
being re-derived per caller.

## Android

| Screen                        | What it does                                                                 |
| ----------------------------- | ---------------------------------------------------------------------------- |
| Property Detail (new)         | The Property's own values, its archived badge and its lifecycle actions        |
| Edit Property (new)           | The Add Property form, pre-populated, saving an edit                          |
| Customer Detail (changed)     | A Property row opens Property Detail                                          |
| Contextual top bar (changed)  | `Property` / `Edit Property` titles, the customer as context, Edit action      |

- Actions are drawn from the caller's capabilities: `properties.edit` for the top bar's **Edit**,
  `properties.archive` for **Archive**/**Restore**, `properties.delete` for **Permanently Delete**,
  which is offered only when the API reported the Property as currently deletable. The backend still
  enforces every one of them (`BR-007`).
- **Edit** reuses `AddPropertyScreen` unchanged apart from its action label, and pre-populates the
  model's own fields — no country (the backend stores it), province as a dropdown over the stable
  Canadian codes, and the version the API last reported carried back as `expectedVersion`.
- **Archive** shows a confirmation that states the Property becomes unavailable for new jobs while
  existing work and history remain, and, when open work exists, shows the API's counts and says
  explicitly that the work continues.
- **Permanent deletion** has its own destructive confirmation. A `409` keeps the user on the screen,
  explains that the Property cannot be deleted, and offers archiving when authorized. Nothing is
  removed from local state before the backend confirms the deletion; after it does, the destination
  leaves and the customer's detail is re-read.
- A `404` on deletion is treated as success: the record is already gone, which is the outcome the
  caller asked for.
- Every new string exists in English and French; the screens use theme colours only, so both themes
  are covered.
- Sign-out now releases the Property screens' state as well as the customer list, since it was read
  with the ending session (`BR-001`).


### Files

```text
android/app/src/main/java/com/servora/android/domain/model/Property.kt          (new)
android/app/src/main/java/com/servora/android/domain/model/CustomerDetail.kt    (status on the row)
android/app/src/main/java/com/servora/android/data/customers/PropertyDtos.kt    (new)
android/app/src/main/java/com/servora/android/data/customers/PropertiesApi.kt   (new)
android/app/src/main/java/com/servora/android/data/customers/PropertyRepository.kt (new)
android/app/src/main/java/com/servora/android/data/customers/CustomerDetailDtos.kt (status)
android/app/src/main/java/com/servora/android/data/customers/CustomersRepository.kt (status mapping)
android/app/src/main/java/com/servora/android/data/customers/CustomersResult.kt  (VERSION_CONFLICT, NOT_FOUND)
android/app/src/main/java/com/servora/android/ui/customers/PropertyDetailScreen.kt  (new)
android/app/src/main/java/com/servora/android/ui/customers/PropertyDetailUiState.kt (new)
android/app/src/main/java/com/servora/android/ui/customers/PropertyDetailViewModel.kt (new)
android/app/src/main/java/com/servora/android/ui/customers/EditPropertyViewModel.kt  (new)
android/app/src/main/java/com/servora/android/ui/customers/AddPropertyUiState.kt     (edit fields)
android/app/src/main/java/com/servora/android/ui/customers/AddPropertyScreen.kt      (save label)
android/app/src/main/java/com/servora/android/ui/customers/AddPropertyViewModel.kt   (reset)
android/app/src/main/java/com/servora/android/ui/customers/CustomersFailureMessage.kt (shared mapping)
android/app/src/main/java/com/servora/android/ui/customers/CustomerPermissionsUiState.kt
android/app/src/main/java/com/servora/android/ui/customers/CustomerDetailScreen.kt   (tappable row)
android/app/src/main/java/com/servora/android/ui/customers/CustomersScreen.kt        (shell wiring)
android/app/src/main/java/com/servora/android/ui/auth/AuthFlowScreen.kt              (shell wiring)
android/app/src/main/java/com/servora/android/ui/navigation/ServoraNavHost.kt        (routes + top bar)
android/app/src/main/java/com/servora/android/MainActivity.kt                        (ViewModels)
android/app/src/main/java/com/servora/android/di/DataModule.kt / NetworkModule.kt
android/app/src/main/res/values/strings.xml, values-fr/strings.xml                  (EN/FR copy)
android/app/src/androidTest/…/ServoraHomeNavigationTest.kt                          (wiring only)
```

## Offline position

`docs/architecture/offline-first-architecture.md` §12 names Property archive/restore as its first
adopter, and `BR-086` requires them to be offline-capable. The **server half of that contract is
implemented**: archive and restore accept a client-generated `clientOperationId` and the device
`capturedAt`, persist them on the lifecycle event, and recognise a replay instead of applying it
twice (`BR-031`); permanent deletion is online-only and is never queued.

The **client half is not implemented**, and this is the slice's one unresolved item. The standard's
local working set and outbox require Room, which is not a dependency in this repository, and neither
is any connectivity or background-work facility. Adding them is a whole architectural slice of its
own (`ADR-012` D7 calls the standard's implementation "the next slice"), and inventing a
`SharedPreferences`-backed queue instead would contradict §3 of the standard. Until it lands, an
archive or restore performed with no connectivity fails with the honest `NETWORK` state, the user
stays on the screen, and nothing is removed locally. The action's idempotency key is generated per
user action rather than reused across a queued replay, because there is no queue.


## Verification (2026-09-13)

```text
API
  npm run typecheck        PASS
  npm run lint             PASS   0 warnings, 0 errors
  npm test                 PASS   295 tests / 34 files
  npm run test:e2e         PASS   148 tests / 10 files
                                   (incl. 21 in properties-lifecycle.e2e-spec.ts)
  npm run build            PASS

Android
  ./gradlew compileDebugKotlin             PASS
  ./gradlew compileDebugAndroidTestKotlin  PASS
  ./gradlew testDebugUnitTest              PASS   165 tests
  ./gradlew lintDebug                      PASS
  ./gradlew assembleDebug                  PASS
  ./gradlew connectedDebugAndroidTest      NOT RUN — device QA belongs to the product owner
```

No migration was added, so the e2e global setup applied nothing new.

## Open questions preserved (not implemented)

1. **Per-operation offline conflict strategy** (`BR-032`). Archive/restore carry `expectedVersion`
   and the API rejects a stale mutation, but which concrete strategy a queued operation should use is
   still undecided.
2. **Property address history** (`BR-057`). Deferred; an edit never rewrites a Job's or a Visit's
   snapshot.
3. **Retention of an archived Property and its history** (`BR-033`). Untouched.
4. **The Room-based local store and outbox** — see above.
5. **Job cancellation and Job deletion** (`BR-021`) — untouched by this slice.

## Physical-device QA (product owner)

```text
1. Sign in as a Manager and open Customers → a customer → a Property.
   Expect: Property Detail, the top bar reads "Property" over the customer's name,
   an Edit action, and an "Archive Property" action.
2. Press Edit, change the street and city, press Save Changes.
   Expect: the form returns to Property Detail showing the new address.
   Reopen the customer: the row shows the new address.
3. Archive the Property (an active-work warning is shown with the API's counts when work exists).
   Expect: the badge "Archived", the action becomes "Restore Property", and the customer's
   Properties list no longer shows it.
4. Restore it. Expect: no badge, available for new work again.
5. Switch the app to French and repeat steps 1–4.
   Expect: French labels, dialogs and badges.
6. With a role holding properties.view only, open the same Property.
   Expect: details are readable, no Edit, no Archive/Restore, no Delete.
7. Turn the device's connectivity off and press Archive.
   Expect: a network message, the user stays on the screen, and the Property is unchanged.
```


## Fix — 2026-09-13: archive/restore rejected the device time

Archive and restore failed on Android with the generic validation message ("Check the details you
entered."). The client was correct; the API's ISO-8601 instant check was too narrow.

- **Symptom.** `POST …/:propertyId/archive` answered `400 VALIDATION_FAILED` with
  `capturedAt must be an ISO-8601 instant` for the device time the app sent.
- **Cause.** `INSTANT_PATTERN` in `api/src/validation/domain-validation.ts` allowed only 1–3
  fractional digits. Kotlin's `Instant.toString()` emits 0, 3, 6 or 9 digits depending on the
  clock's resolution, and Android reports **6** (microseconds) — e.g.
  `2026-09-13T14:39:21.123456Z`. The confirmed failing request carried exactly that shape.
- **Fix.** The pattern accepts up to nanosecond precision (`\.\d{1,9}`). A device time with no
  fractional seconds and with millisecond precision still validates; a fraction finer than a
  nanosecond is still refused.
- **Scope.** `optionalInstant` is used only by the Property lifecycle DTO, so no other route is
  affected and no business rule changed (`BR-086`): the API already documented ISO-8601 instants.
- **Regression coverage.** `domain-validation.spec.ts` (microsecond and nanosecond instants),
  `property.dto.spec.ts` (the DTO boundary) and `properties-lifecycle.e2e-spec.ts` (HTTP, with a
  microsecond `capturedAt` stored on the lifecycle event).


## Fix — 2026-09-13: a confirmed lifecycle action left the customer stale, and an archived Property was unreachable

Two product-owner defects reported after the lifecycle slice shipped on Android.

### 1. Stale customer detail and customer list after archiving

**Symptom.** Customer A has three Properties. Open Property 1, archive it, go back: the customer
still lists three Properties. Back to the customer list: `3 properties`. Reopen customer A: it
correctly shows two. Back on the list: `3 properties` again.

**Cause.** Two read-once caches, and no mutation re-read either one.

- `CustomersViewModel.openCustomerDetail` deliberately skips a re-read for a customer whose detail
  is already settled, and archive/restore on Property Detail refreshed only that screen. Returning
  to the detail therefore presented the earlier read.
- `CustomersViewModel.load` reads the list once per session, and no Property mutation re-read it, so
  the customer's derived `propertyCount` kept its first value. Leaving the detail clears the cached
  detail (`closeCustomerDetail`), which is why reopening the customer read it afresh and showed the
  right count while the list did not.

**Fix.** A confirmed mutation re-reads the projections it invalidates instead of patching them
locally (`BR-001`):

| Element | Change |
| ------- | ------ |
| `CustomersViewModel` | `reload()` re-reads the list even when it is already loaded |
| `PropertyDetailViewModel` | a one-shot `lifecycleChanged` signal is set on a confirmed archive/restore, and `acknowledgeLifecycleChange()` clears it |
| `PropertyDetailScreen` | reports `lifecycleChanged` to the shell |
| `ServoraNavHost` | answers by re-reading the customer detail **and** the list (`reloadCustomerDetail` + `reload`), then acknowledges the signal so re-entering the screen does not read again |
| Delete / Add Property paths | the same list re-read was added where the detail was already re-read |

Editing a Property still re-reads the detail only: an address edit changes no count the list shows.
Nothing is patched locally, so the backend stays the authority for every displayed value.

### 2. An archived Property was unreachable from Android

**Cause.** `BR-081` excludes an archived Property from the customer-detail projection and from
`propertyCount`, and the Android detail had no other view of one. Its Restore action — the only path
back to active use (`BR-082`) — could therefore never be reached from the app.

**Fix (product-owner directive).** The customer detail now reads the customer's Properties twice: the
default `ACTIVE` projection and the explicit `?status=ARCHIVED` projection the API has exposed since
this tracker's API work. When the customer has archived Properties, the Properties section shows an
expandable **"N archived properties"** disclosure. Its rows carry the *Archived* badge and open the
same Property Detail screen, where Restore lives. The section's own count stays the active
projection, so the header and the list still agree (`BR-081`).

**What was not invented.** No API contract, schema or migration changed:
`GET /customers/:id/properties` already accepted `?status=ARCHIVED` (`BR-082`, `BR-083`); the client
asks for it explicitly rather than reading `?status=ALL` and splitting the rows itself. The full
archived/history **screens** `BR-081` refers to remain undefined — this is the reachability the owner
asked for, recorded here rather than assumed, and it does not decide what those screens should be.

**Files.**

```text
android/app/src/main/java/com/servora/android/data/customers/CustomersApi.kt            properties(status)
android/app/src/main/java/com/servora/android/data/customers/CustomersRepository.kt     archived projection read + mapping
android/app/src/main/java/com/servora/android/domain/model/CustomerDetail.kt            archivedProperties
android/app/src/main/java/com/servora/android/ui/customers/CustomersViewModel.kt        reload()
android/app/src/main/java/com/servora/android/ui/customers/PropertyDetailUiState.kt     lifecycleChanged
android/app/src/main/java/com/servora/android/ui/customers/PropertyDetailViewModel.kt   signal + acknowledgement
android/app/src/main/java/com/servora/android/ui/customers/PropertyDetailScreen.kt      reports the signal
android/app/src/main/java/com/servora/android/ui/customers/CustomerDetailScreen.kt      archived disclosure + badge
android/app/src/main/java/com/servora/android/ui/navigation/ServoraNavHost.kt           re-read wiring
android/app/src/main/res/values*/strings.xml                                           EN/FR copy
```


### Regression coverage

| Test | Covers |
| ---- | ------ |
| `CustomersRepositoryTest` — `asks for the archived Property projection separately and carries it` | the detail asks for `ACTIVE` then `ARCHIVED` and maps both |
| `CustomersViewModelTest` — `re-reads the list when a confirmed mutation changes a derived count` | `reload()` re-reads even when the list is already loaded |
| `PropertyDetailViewModelTest` (new) | the read; the `lifecycleChanged` signal on a confirmed archive and restore; its acknowledgement; no signal and no local change when the action fails; the request carries the last-seen version and an operation id |
| `CustomerDetailScreenTest` | the disclosure is hidden with no archived Property, shown with the localized count, keeps its rows behind the disclosure until expanded, and opens a row |

### Verification (2026-09-13)

```text
Android
  ./gradlew testDebugUnitTest              PASS   171 tests
  ./gradlew compileDebugAndroidTestKotlin  PASS   instrumented additions compile
  ./gradlew lintDebug                      PASS   one additional pre-existing MissingQuantity warning
                                                  on the new French plural (the file's existing
                                                  plurals carry the same one/other-only warning)
  ./gradlew assembleDebug                  PASS
  ./gradlew connectedDebugAndroidTest      NOT RUN — device QA belongs to the product owner
```

No API, schema or migration change, so no backend or e2e suite was affected.

### Physical-device QA (product owner)

```text
1. Open a customer with several Properties → a Property → Archive Property.
   Expect: back on the customer, the Properties list no longer shows it.
2. Back to the customer list.
   Expect: the row's Property count decreased.
3. Reopen the customer.
   Expect: the Properties section shows "1 archived property" (localized).
4. Expand it and tap the archived Property.
   Expect: Property Detail with the "Archived" badge and a Restore Property action.
5. Restore it.
   Expect: back on the customer, the Property is listed as active again, the disclosure is gone,
   and the customer list's count increased.
6. Create a Property, then permanently delete an unreferenced one, returning to the list each time.
   Expect: the list's count follows both.
7. Switch the app to French and repeat steps 3–5.
   Expect: French plural, labels and badge.
```



## Fix — 2026-09-13: Edit Property and Property Detail showed the previous session's error

**Symptom.** Two reports of the same shape.

1. Open Edit Property, clear the street address, press Save Property. The form correctly marks what
   is missing. Go back to the Property and open Edit Property again: the message is still there
   before anything is typed, together with any save the API refused.
2. Open a Property, press Archive, and have the API refuse it. Go back to the customer and open the
   same Property again: the refusal panel is still shown for an action nobody just took.

**Cause.** The ViewModels are built by the shell and live for as long as the Activity
(`MainActivity`), not for as long as the destination they drive. `EditPropertyViewModel.start()`
returned early for the same Property while it had not been saved, which kept `saveAttempted` and
`failureReason`; `PropertyDetailViewModel.start()` returned early for a Property it had already
settled, which kept `actionFailure`. Neither could tell a re-composition of the same screen from a
newly opened one.

**Fix.** The destination instance is now the session. Each destination passes its
`NavBackStackEntry` id — stable across a configuration change, fresh for every navigation instance —
and the ViewModel starts a new session whenever that id changes:

| ViewModel | New session |
| --------- | ----------- |
| `EditPropertyViewModel` | resets the form and re-reads the authoritative Property, so a previous session's values, validation message or save error cannot appear |
| `PropertyDetailViewModel` | clears the action outcome (`actionFailure`); the Property it already read is still reused rather than re-fetched |

Re-entering the same id is a no-op, so rotating the device keeps what the screen was showing. A reply
that arrives after the session has ended is dropped instead of being reported by the next session,
and an incomplete or new save attempt clears the previous answer before it is sent.

**Files.**

```text
android/app/src/main/java/com/servora/android/ui/customers/EditPropertyViewModel.kt     session id; incomplete save clears the error
android/app/src/main/java/com/servora/android/ui/customers/PropertyDetailViewModel.kt   session id clears the action outcome; late replies dropped
android/app/src/main/java/com/servora/android/ui/navigation/ServoraNavHost.kt           begins each session before the screen composes
```

### Regression coverage

| Test | Covers |
| ---- | ------ |
| `EditPropertyViewModelTest` (new) | the read; a same-session re-composition keeps the form while a new one re-reads; neither a validation message nor a refused save survives a new session; a form that has not finished reading is never sent; the expected version travels with the request |
| `PropertyDetailViewModelTest` — `does not carry a failed action into a new session` | the reported archive/restore/delete defect, with the read Property reused |

### Verification (2026-09-13)

```text
Android
  ./gradlew testDebugUnitTest              PASS   205 tests
  ./gradlew compileDebugAndroidTestKotlin  PASS
  ./gradlew lintDebug                      PASS
  ./gradlew assembleDebug                  PASS
  ./gradlew connectedDebugAndroidTest      NOT RUN — device QA belongs to the product owner
```

No API, schema or migration change.

### Physical-device QA (product owner)

```text
1. Open a Property → Edit → clear Street → Save Property.
   Expect: the form marks what is missing.
2. Back to the Property, then open Edit again.
   Expect: the form re-reads the Property with no message from the previous attempt.
3. Open a Property → Archive Property; refuse it (turn connectivity off and press Archive).
   Expect: the refusal is shown.
4. Back to the customer, then reopen the same Property.
   Expect: no refusal panel; the Property is shown as the API last described it.
5. Open Edit, change a value, rotate the device.
   Expect: the typed value is still there.
```

