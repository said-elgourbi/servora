# Tracker 014 — Add Customer (Android screen + API writes)

**Status: COMPLETE for API and Android implementation; physical-device QA is the product owner's**

Date: 2026-09-13
Predecessor: `docs/tracker/013-property-lifecycle.md`
Business rules: `BR-001`, `BR-007`, `BR-011`, `BR-023`, `BR-028`, `BR-041`, `BR-042`, `BR-047`,
`BR-049`, `BR-050`, `BR-067`, `BR-085`
Domain model: `docs/domain/foundation-domain-model.md` §8, §11
API contract: `docs/api/customers.md` §5
Design reference: `Figma/src/screens/Customers.tsx` (`NewCustomerView`),
`Figma/src/imports/pasted_text/servora-property-redesign.md` §3

## Scope

The customers list's **New Customer** action was a permission-gated placeholder: it opened a stub
screen with one inert field. This slice makes the form real — the customer create plus, optionally,
the customer's first service Property and a business customer's primary contact.

The first Property section is **optional end to end**, as product ownership required: it can be left
untouched, and the Property is then added later from Customer Detail, which already exists.

| Item | Status |
| --- | --- |
| `POST /customers` wired in Android (`CustomersApi`, `CreatedCustomerDto`, request shapes) | Implemented |
| `POST /customers/:id/contacts` (new API route, `customers.edit`) | Implemented |
| Android `AddCustomerViewModel` + `AddCustomerUiState` (validation, steps, partial failure) | Implemented |
| Android `AddCustomerScreen` — type control, customer fields, optional Property, actions | Implemented |
| Optional first Property written only when the caller holds `properties.create` | Implemented |
| Primary contact drawn only for a `COMPANY` customer whose caller holds `customers.edit` | Implemented |
| Navigation: New Customer opens the real form and returns to the created customer | Implemented |
| Sign-out releases the form's state | Implemented |
| EN/FR copy for every new string | Implemented |
| API unit specs (contact DTO), API e2e (contacts route, create contract) | Implemented |
| Android JVM tests (ViewModel, repository) and Compose tests | Implemented |
| Offline local store / outbox | **Not implemented — see below** |

## Figma vs the authoritative model

The design was compared field by field before implementation. Only one field had no home in the
model; product ownership decided the resolution.

| # | Figma | Model | Resolution |
| - | ----- | ----- | ---------- |
| 1 | Customer type Individual / Business | `customers.type` + exactly one subtype row | Drawn as the design's two-option control |
| 2 | Individual first/last name | `customer_individuals.first_name/last_name` | Drawn |
| 3 | Business company name | `customer_companies.legal_name` | Drawn |
| 4 | Business **primary contact first/last name** | No customer column; a contact is a `customer_contacts` row and there was **no create route** | **Product decision:** implement `POST /customers/:id/contacts` in this slice |
| 5 | Phone | `customers.phone` is optional (`BR-023`) | Drawn and marked optional; the form never requires a value the model allows to be absent. The design's prototype required phone — that is prototype validation, not a rule |
| 6 | Email (marked optional) | `customers.email` optional | Drawn |
| 7 | Notes | `customers.notes` optional | Drawn |
| 8 | — (no field) | `display_name` is required by the API | Derived in the client: company name, or "First Last" |
| 9 | First Service Property street/unit/city/province/postal/label/notes | `properties.address_line1/address_line2/city/province/postal_code/name/notes` | Drawn in the design's bordered card, including the "service location, not the billing address" note |
| 10 | — (no field) | `properties.country` NOT NULL default `Canada` | Backend writes it, as Add Property already does |
| 11 | Add Property's "Property type" and "Access instructions" | Not in the model | Not drawn (already excluded by tracker 011) |
| 12 | The older New Customer doc's customer Address section | Service address belongs to Property (`BR-047`, `BR-049`) | Superseded by the redesign; `customer_addresses` stays out of this flow |

Nothing was invented for an OPEN QUESTION: no status, permission, column or relationship was added
beyond what the model and rules already define.

## Behaviour notes

**Create is up to three calls, in order.** The customer (`customers.create`), then the optional first
Property (`properties.create`) and the optional primary contact (`customers.edit`). They are
separate calls rather than one atomic request because the capabilities are independent: a single
embedded create would have to require `properties.create` as well as `customers.create`, and a
Property capability is deliberately never implied by a customer capability (`BR-085`).

**A created customer is never lost to a later failure.** As soon as the customer create is accepted,
its id is kept and the form can no longer submit the create again. If the Property or the contact then
fails, the screen states which part did not save, shows the backend's reason, and turns its primary
action into **Open customer**, where the missing part can be added later (`BR-001`, `BR-067`). A
failure of the customer create itself leaves nothing created and nothing unfinished.

**The optional section is optional, not silently dropped.** Leaving the Property section untouched
writes no Property. Starting it, however, requires it to be complete: a half-typed Property blocks
the save and is never skipped without telling the user (`BR-067`). This is stricter than the design
prototype, which silently ignored a partially typed address.

**Permission gating is presentation only.** The Property section is drawn only with
`properties.create`, and the primary contact only for a `COMPANY` customer when `customers.edit` is
held. The backend still enforces every write (`BR-007`).

**Contact authorization is interim and recorded.** `POST /customers/:id/contacts` requires
`customers.edit` — the same interim choice the Property create used before `BR-085` existed
(`ADR-012` D1). Contacts have no capability catalogue of their own; that is recorded as an OPEN
QUESTION in `docs/api/customers.md` §5.2 rather than decided here.

**After a successful create** the list is re-read and the form is popped, so Back from the new
customer returns to the list, not to the form. The customer detail then reads the new customer from
the backend (`BR-001`); nothing is patched locally.

## Files

```text
api/src/customers/customers.controller.ts                 (POST /customers/:id/contacts)
api/src/customers/customer-contact.dto.spec.ts            (new — 7 unit tests)
api/test/customers-contacts.e2e-spec.ts                   (new — 5 e2e tests)
docs/api/customers.md                                     (§5 customer mutations)
docs/api/authentication.md                                (contacts row in the permission table)

android/app/src/main/java/com/servora/android/data/customers/CustomerCreateDtos.kt   (new)
android/app/src/main/java/com/servora/android/data/customers/CustomersApi.kt         (create + contact)
android/app/src/main/java/com/servora/android/data/customers/CustomersRepository.kt  (create + contact)
android/app/src/main/java/com/servora/android/data/customers/CustomersResult.kt      (two results)
android/app/src/main/java/com/servora/android/ui/customers/AddCustomerUiState.kt     (new)
android/app/src/main/java/com/servora/android/ui/customers/AddCustomerViewModel.kt   (new)
android/app/src/main/java/com/servora/android/ui/customers/AddCustomerScreen.kt      (new)
android/app/src/main/java/com/servora/android/ui/customers/AddPropertyScreen.kt      (form controls shared)
android/app/src/main/java/com/servora/android/ui/customers/CustomersScreen.kt        (shell passes the VM)
android/app/src/main/java/com/servora/android/ui/navigation/ServoraNavHost.kt        (create destination)
android/app/src/main/java/com/servora/android/ui/auth/AuthFlowScreen.kt              (pass-through)
android/app/src/main/java/com/servora/android/MainActivity.kt                       (VM + sign-out reset)
android/app/src/main/res/values/strings.xml, values-fr/strings.xml                  (EN/FR copy)

android/app/src/test/.../ui/customers/AddCustomerViewModelTest.kt                   (new — 12 tests)
android/app/src/test/.../data/customers/CustomersRepositoryTest.kt                  (5 new tests)
android/app/src/androidTest/.../ui/customers/AddCustomerScreenTest.kt               (new Compose suite)
android/app/src/androidTest/.../ui/navigation/ServoraHomeNavigationTest.kt          (New Customer wiring)
```

### Shared form controls

The New Customer form reuses the Add Property form's controls (field, notes, section, province picker,
action bar, attention message) rather than duplicating them. They live in `AddPropertyScreen.kt` and
are `internal`; the notes field and the attention message take their label/placeholder/test tag so a
caller can supply its own copy. That is why the Property section's province control carries the Add
Property test tag — the same control is drawn, not a second one.

## Verification (2026-09-13)

```text
API
  npm run typecheck        PASS
  npm run lint             PASS   0 warnings, 0 errors
  npm test                 PASS   305 tests / 35 files
                                   (incl. customer-contact.dto.spec.ts, 7 tests)
  npm run test:e2e         PASS   154 tests / 11 files
                                   (incl. 5 in customers-contacts.e2e-spec.ts)
  npm run build            PASS

Android
  ./gradlew compileDebugKotlin             PASS
  ./gradlew compileDebugAndroidTestKotlin  PASS
  ./gradlew testDebugUnitTest              PASS   190 tests
  ./gradlew lintDebug                      PASS
  ./gradlew assembleDebug                  PASS   debug APK
  ./gradlew connectedDebugAndroidTest      NOT RUN — device QA belongs to the product owner
```

No schema change and no migration: `customer_contacts` already exists, and the contact route writes
the columns it already has.

## Open questions preserved (not implemented)

1. **A contact's capability.** `POST /customers/:id/contacts` uses the interim `customers.edit`
   (`docs/api/customers.md` §5.2). Whether contacts get their own catalogue, as Properties did in
   `BR-085`, is undecided.
2. **Contact editing and the meaning of "primary".** No rule defines editing a contact, or what
   happens to the primary flag when a second primary is created. Only creation is implemented.
3. **Phone as a required customer field.** `BR-023` makes it optional, and the form follows the rule;
   the design prototype required it. If product ownership wants it required, that is a rule change
   (`BR-040`), not a client validation tweak.
4. **Customer edit.** The Edit Customer screen is still the inert placeholder; this slice covers
   creation only.
5. **Customer `dateOfBirth`, `businessName`, `taxNumber`, `billingEmail`, `billingPhone`,
   `preferredContactMethod` and `language`.** The model has them and `POST /customers` accepts them,
   but the design draws no field for them, so the form does not ask (`BR-042`).
6. **The `customer_addresses` model.** This flow never touches it: the service address is the
   Property (`BR-047`, `BR-049`), and no billing/contact address is captured here.

## Physical-device QA (product owner)

```text
1. Sign in as a Manager whose role has customers.view and customers.create.
2. Customers → New Customer.
   Expect: the top bar reads "New Customer" with a back control; Individual is selected; First name,
   Last name, Phone, Email and Notes are drawn; "First service property" is drawn with its
   "(optional)" marker and the note that it is not the billing address.
3. Press Create Customer with everything empty.
   Expect: "Please fill in the required fields." and nothing is written.
4. Fill first name and last name only, then Create Customer.
   Expect: the app opens the new customer's page, the customer shows 0 properties, and the customer
   appears in the list.
5. New Customer → choose Business.
   Expect: Company name and a "Primary contact" first/last pair are drawn.
6. Fill company name and the primary contact, then Create Customer.
   Expect: the customer exists and its contact is listed on the customer page.
7. New Customer → fill first/last name → fill only the street address (leave city, province, postal).
   Press Create Customer.
   Expect: the form reports the missing fields and writes nothing at all — no customer is created.
8. New Customer → fill first/last name plus a complete property (street, city, province, postal),
   then Create Customer.
   Expect: the customer opens with 1 property showing 0 jobs, and the list's property count agrees.
9. New Customer → press Cancel, then reopen and use the system Back gesture.
   Expect: both return to the customer list and never leave the app.
10. With a role that has customers.create but not properties.create, open New Customer.
    Expect: no "First service property" section is drawn; the customer still creates.
11. Turn connectivity off and press Create Customer.
    Expect: a network message, no customer is created, and the typed values stay on screen.
12. Switch the app to French and repeat steps 2-4.
    Expect: French labels, placeholders and messages.
```

Because this slice includes a new API route, the device must reach an API built at or after this
change. The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.






## Fix — 2026-09-13: a reopened form still showed the previous attempt's error

**Symptom.** Press Create Customer with the mandatory fields empty. The form correctly shows the
incomplete message. Go back and open New Customer again: the message is still there before anything
is typed. The same happened after a create the API refused — including the notice that the customer
was created but an optional part did not save.

**Cause.** The form ViewModels are built by the shell and live for as long as the Activity
(`MainActivity`), not for as long as the destination they drive. `AddCustomerViewModel.start()`
returned early for any form that had not already reached a terminal state — that kept an in-progress
form, but it also kept `saveAttempted`, `failureReason` and `unfinishedStep`, and it could not tell a
re-composition of the same screen from a newly opened one.

**Fix.** The destination instance is now the form session. The destination passes its
`NavBackStackEntry` id — stable across a configuration change, fresh for every navigation instance —
and the ViewModel starts an empty session whenever that id changes. Re-entering the same id is a
no-op, so rotating the device still keeps what the user typed, while leaving and reopening the screen
cannot carry a validation or submission error over. A reply that arrives after the session has ended
is dropped instead of being folded into the next session, and a new attempt clears the previous
answer before it is sent.

**Files.**

```text
android/app/src/main/java/com/servora/android/ui/customers/AddCustomerViewModel.kt   session id; late replies dropped
android/app/src/main/java/com/servora/android/ui/navigation/ServoraNavHost.kt        begins the session before the screen composes
```

### Regression coverage

| Test | Covers |
| ---- | ------ |
| `AddCustomerViewModelTest` — `does not carry a validation message into a new form session` | the reported defect |
| `AddCustomerViewModelTest` — `does not carry a refused create into a new form session` | a backend refusal, not only a client check |
| `AddCustomerViewModelTest` — `keeps an in-progress form within a session and resets a finished one` | a rotation keeps the form; leaving and returning on a new instance does not |
| `AddCustomerViewModelTest` — `clears the validation message once the missing fields are filled` | the message stops as soon as the form is valid |

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
1. Customers → New Customer → leave the name empty → Create Customer.
   Expect: the form marks what is missing.
2. Back to the list, then open New Customer again.
   Expect: an empty form with no message from the previous attempt.
3. Open New Customer, type a name, rotate the device.
   Expect: the typed values are still there.
4. Turn connectivity off and press Create Customer, then leave and reopen the screen.
   Expect: the network message does not reappear on the new form.
```

## Fix — 2026-09-14: the keyboard covered the last field of a form

**Symptom.** Reported during physical-device QA. On New Customer, Add Property and Edit Property,
opening the keyboard left the field being typed in underneath it — most visibly the last field of the
form, together with the Save/Cancel row below it — and the form never moved up.

**Cause.** The signed-in shell draws edge-to-edge (enforced from Android 15 for this target SDK), so
the platform no longer resizes the window for the keyboard: it reports `WindowInsets.Type.ime` and the
application has to apply it. The shell's `Scaffold` applied no keyboard inset, so the keyboard simply
drew over the bottom of the shell. Because the forms sit in a `verticalScroll` area that nothing had
resized, Compose never ran the "scrollable parent resized and the focused node is now hidden" path
that exists for exactly this case (`CoreTextField.kt` lists it as the `softInputMode=ADJUST_RESIZE`
case) and the focused field stayed where it was, behind the keyboard.

**Fix.** The shell applies the keyboard inset once, instead of every form applying its own:

```kotlin
Scaffold(modifier = modifier.fillMaxSize().imePadding(), …)   // ServoraHomeScreen
```

Insetting the shell — rather than a form's own content — is what makes the scrollable area a form is
measured against shrink, which is the resize Compose answers by bringing the focused field back into
view, and it lifts the bottom navigation with the content rather than leaving a bottom-bar-sized gap
between the form and the keyboard. No keyboard height is hard-coded, and where the platform already
resizes the window for the IME the reported inset is zero. Every signed-in destination gets the same
treatment, including the customers search field; no destination may add its own `imePadding()` on top
of it, which would inset twice. The standard is now recorded in
`docs/design/android-design-system.md` → "Keyboard (IME) inset (signed-in screens)".

Not changed, deliberately: the sign-in, password-reset and SMS screens. They are pre-auth, outside
this shell, and apply their own `imePadding()` inside their scroll container. The reported defect was
on the signed-in forms, so they were left exactly as they are; if the same symptom appears there, the
equivalent change is to move their `imePadding()` outside their `verticalScroll`.

**Files.**

```text
android/app/src/main/java/com/servora/android/ui/customers/CustomersScreen.kt   the shell's Scaffold applies the IME inset
docs/design/android-design-system.md                                            the shell standard, recorded
```

### Regression coverage

None, and honestly so. The behaviour is the platform's: it depends on real window insets that a JVM
test cannot produce and that a Compose UI test cannot inject, so it cannot be asserted in the suites
this repository can run — the check is the physical-device runbook below (`qa.md` §7). No existing
test was weakened, deleted or changed; the Android suite is unaffected.

### Verification (2026-09-14)

```text
Android
  ./gradlew compileDebugKotlin             PASS
  ./gradlew testDebugUnitTest              PASS   205 tests
  ./gradlew lintDebug                      PASS
  ./gradlew assembleDebug                  PASS   debug APK
  ./gradlew connectedDebugAndroidTest      NOT RUN — device QA belongs to the product owner
```

No API, schema or migration change.

### Physical-device QA (product owner)

```text
1. Customers → New Customer → scroll to the bottom and tap Notes (the last field).
   Expect: the whole form lifts with the keyboard, the Notes field stays visible and typeable, and
   the Cancel / Create Customer row stays above the keyboard.
2. With the keyboard still open, scroll the form up and down.
   Expect: no empty band between the form and the keyboard, and the bottom navigation sits directly
   above the keyboard.
3. Repeat 1 on Add Property (customer → Add Property) and on Edit Property (Property → Edit).
   Expect: the same, including the Save / Cancel row and the province control.
4. Open Customers and tap the search field.
   Expect: the list lifts with the keyboard and the field stays visible.
5. Dismiss the keyboard (system Back).
   Expect: the shell returns to the full height, with the bottom navigation back at the bottom.
6. Rotate the device with a field focused.
   Expect: the form keeps its values and stays visible with the keyboard open.
```


