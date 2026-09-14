# Tracker 007 — Android Customers list

**Status: COMPLETE** (Android list read; 2026-09-12 follow-ups add derived counts to the list payload and refine the row's status/since line, chevron and pressed tint; the 2026-09-14 follow-up makes the row's contact values display-only)

Date: 2026-09-11
Predecessor: `docs/tracker/006-customers-feature-permissions.md`
API contract reference: `docs/api/authentication.md`
Data model reference: `docs/domain/foundation-domain-model.md`
Design reference: `Figma/src/screens/Customers.tsx`,
`Figma/src/imports/pasted_text/customer-section-design.md`

## Scope

The Customers tab previously rendered a hard-coded `PreviewCustomers` list: no customer was ever
read from the backend. The API already exposes `GET /customers` (guarded by `customers.view`), so
this slice wires the Android client to it.

| Item                                                                       | Status |
| -------------------------------------------------------------------------- | ------ |
| `SessionStore` — holds the access token issued at sign-in for authenticated calls | Done |
| `DefaultAuthRepository` keeps the issued session on password/SMS sign-in    | Done   |
| `CustomerDto` + `CustomersApi` (`GET /customers`)                          | Done   |
| `CustomersRepository` maps wire → domain and classifies failures           | Done   |
| `CustomersViewModel` loads once and exposes loading/success/failure state  | Done   |
| `CustomersScreen` renders loading, empty, failure + retry and the real list | Done  |
| Customer rows/detail render only fields the endpoint returns               | Done   |
| EN/FR strings for loading, failure and no-contact states                   | Done   |
| Hilt bindings for the repository and the API                               | Done   |
| Android JVM tests: repository, ViewModel and session retention             | Done   |

Deliberately **not** done (recorded below): customer create/edit/archive wiring, contacts,
properties and jobs reads, session persistence, sign-out and token refresh.

## Behaviour Notes

- The list is read from `GET /customers`, which the backend scopes to the caller's active
  membership that holds `customers.view` (`BR-001`, `BR-007`). The client never names an
  organization, so a modified client cannot widen its own scope.
- The access token is kept in an in-memory `SessionStore` populated when sign-in issues a session.
  It is **not** persisted: this build has no sign-out or session-restore flow, so a stored
  credential could not be revoked (`Project.md` §16).
- Failure classification uses the HTTP answer (`dev.md` §7): `401` → unauthenticated,
  `403` → forbidden, `5xx` → server, no connection → network, anything else → unexpected. The
  error envelope is not parsed for this read because the status already carries the outcome.
- A row whose `type`/`status` this build cannot represent fails the read as unexpected rather than
  being dropped silently (`BR-042`).
- The list is loaded once per signed-in session, and only when the user holds `customers.view`;
  revisiting the tab does not re-read the backend.

## Known Limitations / Follow-ups

- The customer **detail** screen has no contacts, properties or jobs read path. The fabricated
  "last job" and "since" values were removed rather than kept as sample data. The list's
  property/job counts are no longer fabricated: the backend derives them (see the 2026-09-12
  follow-up below).
- Create/Edit/Archive customer actions remain permission-gated placeholders; only the list read is
  wired.
- Search filters the loaded list client-side; the Filter button is wired by
  `docs/tracker/008-android-customers-filter.md`.
- Session persistence across process death, sign-out, and token refresh are out of scope for this
  slice. **Delivered by `docs/tracker/009-android-session-persistence.md`** (`ADR-009`): the session
  is now persisted and restored, and sign-out clears it.
- Offline caching of the customer list is not implemented; the read requires connectivity
  (`BR-013`/`BR-031` remain future slices).

## UI Alignment Pass (2026-09-11)

The list screen was reworked against the design, which is the source of truth for UI. Android
metrics come from `docs/design/android-design-system.md` (which deliberately records larger phone
metrics than the web design it mirrors); glyphs replicate the geometry of the icons the design
uses (`Figma/src/screens/Customers.tsx`, lucide `Search`/`Plus`/`X`/`SlidersHorizontal`/`Building2`)
as vector drawables under `app/src/main/res/drawable/`, following the same convention as the
sign-in appearance controls and adding no dependency.

| Element              | Before                                                     | After                                                                                              |
| -------------------- | ---------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| New Customer action  | label only                                                 | leading plus glyph (`ic_add`)                                                                       |
| Search               | `OutlinedTextField` (outline box, floating label, ~56 dp)  | filled `shapes.large` control at the design system's 52 dp field height, leading search glyph (`ic_search`), trailing clear (`ic_close`) |
| Filter               | `FilledTonalButton` at ~40 dp, misaligned with the field   | the same 52 dp height and the same fill/border as the field, leading sliders glyph (`ic_filter`)     |
| Row                  | 16 sp name, literal "Business" text mark                   | 14 sp name, business glyph (`ic_business`), row inset aligned to the 16 dp header margin             |
| Empty state          | text only                                                  | glyph, centred copy and the New Customer action                                                      |
| No matches           | reused the empty state                                     | a distinct state with a search glyph and a clear action                                              |

The filter control stays inert: the filter sheet is not part of this slice, so the control is drawn
as designed rather than made to invent filter behaviour (`BR-042`). It is a convenience affordance,
not a claim that filtering exists.

## Follow-up — Floating New Customer action and stacked contact lines (2026-09-12)

Two product-directed adjustments to the list screen. Both are Android UI only — no API, database,
contract or Angular change.

| Element             | Change                                                                                                                                                                                              |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| New Customer action | Left the header row and is now an extended floating action button pinned to the bottom-right of the list. It keeps the plus glyph, the `customers_new` label and its `customers.create` gating, and stays under the same `AddCustomerActionTag`. |
| Row contact lines   | Phone and email were drawn as one `phone · email` line; the email now sits on its own line directly under the phone number.                                                                          |

Everything else still follows `Figma/src/screens/Customers.tsx`, including the empty state's inline
New Customer action and the inert Filter control. The floating action reuses the existing
`customers_new` string, so no new resource or translation was added.

## Follow-up — Row spacing, tappable contacts and derived counts (2026-09-12)

Three product-directed changes to the list row. Unlike the earlier follow-ups, this one changes the
API contract: the list payload did **not** carry the property/job counts the design shows, so they
are now derived by the backend and returned with the row.

| Element     | Change                                                                                                                                                             |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Row spacing | Name, phone and email are stacked with deliberate gaps (4 dp under the name, 2 dp between the contacts, 6 dp before the counts) instead of abutting text lines.       |
| Contacts    | The phone dials and the email composes, each an `ACTION_DIAL`/`ACTION_SENDTO` link with a leading glyph (`ic_phone`/`ic_mail`) in the interaction colour. Superseded for the list row by the 2026-09-14 follow-up below, which makes both display-only; Customer Details still links them. |
| Counts      | An "N properties · N jobs" meta line with Home/Briefcase glyphs (`ic_home`/`ic_briefcase`), localized through plurals (`customers_property_count`, `customers_job_count`). |

### Payload finding and API change

`GET /customers` returned `CustomerDto`, which carried no counts, while the design
(`Figma/src/imports/pasted_text/servora-property-redesign.md`) requires "Number of properties /
Number of jobs" per customer. The counts are derivable, so the endpoint now returns
`CustomerSummaryDto` (a `CustomerDto` plus two fields):

- `propertyCount` — the customer's **active** `property_customer_relationships` (`ended_at IS NULL`,
  `BR-050`). A relationship that has ended stays in history but is no longer one of the customer's
  properties.
- `jobCount` — every Job that belongs to the customer, whatever its status (`BR-048`).

Both are derived in one grouped query in `CustomersService.listCustomersInOrganization` (two
`count(distinct …)` left joins). Nothing new is stored, so there is **no migration**, and the counts
are computed once for every client (`BR-002`).

**OPEN QUESTION** — whether the job count should exclude any statuses (for example `CANCELED`) is
not decided by product (`BR-042`). The design only says "Number of jobs", so the count is every Job
and the question is recorded rather than guessed.

Android mirrors the fields in `CustomerDto` (with defaults, so a payload without them still parses),
`Customer`, `CustomerListItem` and the row. `CustomersScreenTest` asserts the counts render, that the
row's contact values carry no click action of their own and that tapping one opens the customer
(2026-09-14 follow-up below).

## Verification

Automated verification on 2026-09-11:

```text
./gradlew testDebugUnitTest   PASS   (includes CustomersRepositoryTest, CustomersViewModelTest)
./gradlew lintDebug           PASS   15 warnings, all pre-existing; none in a file added or changed here
./gradlew assembleDebug       PASS   debug APK
```

UI alignment pass (same date):

```text
./gradlew testDebugUnitTest      PASS   89 tests, 0 failures, 0 errors
./gradlew lintDebug              PASS   0 errors (abortOnError is on)
./gradlew assembleDebug          PASS   debug APK
./gradlew assembleDebugAndroidTest PASS  existing Compose UI tests still compile
```

Follow-up — floating action and stacked contact lines (2026-09-12):

```text
./gradlew testDebugUnitTest          PASS   89 tests, 0 failures, 0 errors
./gradlew lintDebug                  PASS   0 errors (abortOnError is on)
./gradlew assembleDebug              PASS   debug APK
./gradlew assembleDebugAndroidTest   PASS   includes the new CustomersScreenTest (compiles)
```

`CustomersScreenTest` covers the floating New Customer action's presence, its `customers.create`
gate, the create-form wiring and the email-below-phone ordering. Instrumented execution is
**NOT RUN — no online device/emulator was attached**:

```text
cd android && ./gradlew connectedDebugAndroidTest
```

Row, contact links and counts (2026-09-12):

```text
API
  npm run typecheck                    PASS
  npm run lint                         PASS   0 warnings, 0 errors
  npm test                             PASS   248 tests
  npm run test:e2e                     PASS   108 tests (includes the new counts case in customers.e2e-spec.ts)

Android
  ./gradlew testDebugUnitTest          PASS   90 tests, 0 failures, 0 errors
  ./gradlew lintDebug                  PASS   0 errors (abortOnError is on)
  ./gradlew assembleDebug              PASS   debug APK
  ./gradlew assembleDebugAndroidTest   PASS   CustomersScreenTest compiles
```

Instrumented execution of the row tests is **NOT RUN — no online device/emulator was attached**.

Android lint detail: `abortOnError` is enabled in `app/build.gradle.kts`, so a passing `lintDebug`
proves zero lint errors. No `MissingTranslation` or `UnusedResources` finding, which confirms
`values-fr/strings.xml` covers the new strings and no string was orphaned by the screen rework.

Physical-device QA: **Awaiting product owner.**

```text
Android QA — Customers list

1. Sign in as a Manager whose role has `customers.view`.
2. Open the Customers tab.

Expected:
- The header shows the Customers title with its supporting line.
- A New Customer floating action button, carrying a plus glyph and its label, is pinned to the
  bottom-right of the list and stays put while the list scrolls.
- The search field carries a leading search glyph; the Filter control sits beside it at the
  same height and with the same fill, and carries a sliders glyph.
- A full row shows name, a building glyph for companies, the phone number with a phone glyph, the
  email under it with a mail glyph, an "N properties · N jobs" line with Home and Briefcase glyphs,
  and status.
- Every row's avatar lines up with the Customers title (16 dp from the screen edge).

3. Type part of a name.
Expected: the list narrows and a clear (cross) control appears in the field; tapping it restores
the full list. Typing something that matches nothing shows "No customers match these filters" with
a clear action rather than the "No customers yet" empty state.

4. With no customers on the account.
Expected: the empty state shows a building glyph, the "No customers yet" copy and the New Customer
action.

5. Stop the API (or disable connectivity) and reopen the Customers tab.
Expected: a localized failure state with a "Try again" button; the button re-reads once the API is
reachable.

6. Sign in as a user without `customers.view`.
Expected: no Customers tab, and a localized no-access state if the screen is reached directly.

7. Sign in as a user who has `customers.view` but not `customers.create`.
Expected: the customers list is shown without the floating New Customer action.

8. Tap a row's phone number, then its email.
Expected: the phone opens the dialer prefilled with the number and places no call; the email opens
the composer addressed to the address and sends nothing. Both are `ACTION_DIAL`/`ACTION_SENDTO`, so
the app never starts the call or the message itself.

9. Switch the app language to French and repeat 2–5.
Expected: every string is French and no English text leaks through, including "1 propriété" and
"N interventions".
```

## Row refinement (2026-09-12)

The customer row now matches the design's identity block: the status moved from the row's trailing
edge to directly under the name, joined by the customer's "since" date; the trailing edge carries an
open-details chevron; and the row carries Material's standard pressed (ripple) feedback.

| Item                                                                          | Status |
| ----------------------------------------------------------------------------- | ------ |
| Status `StatusPill` moved under the customer name                             | Done   |
| Localized "Since <created date>" beside the status                            | Done   |
| `ic_chevron_right` drawable + tag for the row's open-details affordance        | Done   |
| Row Material ripple/pressed feedback that nested contact links do not trigger  | Done   |
| `CustomerListItem.createdAt` carries the value from the existing payload       | Done   |
| EN/FR `customers_since_format`                                                | Done   |
| Android JVM + Compose test coverage for the row changes                       | Done   |

### Behaviour notes

- **No API, payload or database change was required.** The design's "since" value is the customer's
  created date (`Figma/src/screens/Customers.tsx`, `servora-property-redesign.md`). `GET /customers`
  already returns `createdAt` (`CustomerSummaryDto extends CustomerDto`), the Android `CustomerDto`
  already parses it and `Customer.createdAt` already holds it; only the row did not surface it. The
  status/since line therefore reads an existing authoritative value rather than a new one.
- The date is formatted with `FormatStyle.MEDIUM` in the device locale ("Jan 12, 2025"), matching the
  design. A `createdAt` this build cannot parse hides the "since" text and logs rather than
  fabricating a date (`BR-042`).
- The chevron is decorative (`contentDescription = null`): the whole row remains the click target
  that opens the customer, so screen readers announce one actionable node.
- The row's pressed feedback is Jetpack Compose's standard `clickable` interaction — the Material
  ripple — clipped to the row's rounded shape. There is no custom interaction source and no
  hand-drawn tint: the row is an ordinary Material clickable. The phone and email links are nested
  clickables with their own feedback, so dialing or composing never triggers the row's feedback.

### Verification (2026-09-12)

```text
Android
  ./gradlew testDebugUnitTest          PASS   91 tests, 0 failures, 0 errors
  ./gradlew lintDebug                  PASS   0 errors (2 pre-existing MissingQuantity warnings)
  ./gradlew assembleDebugAndroidTest   PASS   CustomersScreenTest compiles (new row tests)
  ./gradlew assembleDebug              PASS   debug APK
```

Instrumented execution is **NOT RUN — no online device/emulator was attached**:

```text
cd android && ./gradlew connectedDebugAndroidTest
```

Physical-device QA: **Awaiting product owner.** In addition to the runbook above:

```text
Android QA — Customers row refinement

1. Sign in as a Manager whose role has customers.view and open the Customers tab.
Expected: each row shows the status pill directly under the name, followed by "Since <date>"
(en-CA) / "Depuis <date>" (fr-CA), and an open-details chevron at the row's top-right.

2. Press and hold a row.
Expected: the row shows Material's ripple (pressed) feedback, bounded to the row's rounded shape;
releasing opens the customer.

3. Press and hold the phone number, then the email.
Expected: the pressed feedback appears on the contact line itself rather than the row; the dialer/
composer is offered as before. **Superseded by the 2026-09-14 follow-up below:** the row's contact
values are no longer links, so these presses now show the row's feedback and open the customer.
```

## Follow-up — App-shell header and icon bottom navigation (2026-09-12)

Two product-directed adjustments to the signed-in shell that hosts the customers list. Android UI
only — no API, database, contract or Angular change.

| Element        | Change                                                                                                                                                                                       |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Header         | The time-based greeting and the full date line were removed; the header now shows the brand name only (`app_name` = "Servora"). The trailing notification affordance is unchanged.            |
| Bottom nav     | Each destination now carries its glyph above the label, matching the design's icon+label stack (`Figma/src/screens/Dashboard.tsx`): Home `ic_home`, Schedule `ic_calendar`, Customers `ic_users`, Settings `ic_settings`. The selected destination is the only one painted in the brand colour. |

- The three new glyphs (`ic_calendar`/`ic_users`/`ic_settings`) replicate the lucide geometry the
  design's nav uses, as vector drawables under `app/src/main/res/drawable/`, following the same
  convention as the existing row glyphs and adding no dependency.
- The header no longer reads the clock or the device locale, so the now-unused `home_greeting_*`
  strings were removed from `values/strings.xml` and `values-fr/strings.xml` rather than left dead.
- The Customers destination keeps its `customers.view` gate and its `CustomersNavTag`.

### Verification (2026-09-12)

```text
Android
  ./gradlew assembleDebug              PASS   debug APK
  ./gradlew testDebugUnitTest          PASS   136 tests, 0 failures, 0 errors
  ./gradlew lintDebug                  PASS   0 errors
  ./gradlew assembleDebugAndroidTest   PASS
```

Instrumented execution is **NOT RUN — no online device/emulator was attached**:

```text
cd android && ./gradlew connectedDebugAndroidTest
```

Physical-device QA: **Awaiting product owner.** Inspect the header (brand name only, no date) and
the bottom navigation (icon above each label, selected destination in brand colour) on the
signed-in screen.

## Follow-up — List rows no longer dial or compose (2026-09-14)

Product-directed change after using the list: tapping a customer's phone number or email dialled or
composed when the intent was to open the customer. The two contact values in a **list row** are now
display-only. Android UI only — no API, database, migration, shared contract or Angular change, and
no permission change.

| Element           | Change                                                                                                                                                                                                                                                                                    |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| List row contacts | The phone number and email are still drawn with their `ic_phone`/`ic_mail` glyphs and the same spacing, but the lines carry no click action of their own. A tap anywhere in the row — including on a contact value — opens Customer Details, so the row has one target and one outcome.          |
| Customer Details  | Unchanged: the phone still dials and the email still composes, each an `ACTION_DIAL`/`ACTION_SENDTO` link.                                                                                                                                                                                  |

- The shared composable is now `CustomerContactLine(text, glyph, modifier, onClick = null)`: it renders
  a link only when the caller supplies an action, and Customer Details is the only caller that does.
  `dialIntent`/`mailIntent`/`startContactIntent` are unchanged and still used by Customer Details.
- This deliberately departs from the design, which draws the row's contacts as `tel:`/`mailto:`
  anchors (`Figma/src/screens/Customers.tsx`): by product direction the row is a single target. The
  `customerPhoneTag`/`customerEmailTag` tags are kept so the row's contact values stay addressable.
- No business rule is affected: the contact actions were never permission-gated and no API, storage
  or synchronization behaviour changes (`BR-066` scopes field execution, not a contact tap).
- The contact values keep the interaction colour they are drawn in; whether the list should also
  present them in a quieter, non-link colour is a further presentation question and is **not**
  decided here.

### Verification (2026-09-14)

```text
Android
  ./gradlew testDebugUnitTest          PASS   205 tests, 0 failures, 0 errors
  ./gradlew lintDebug                  PASS   0 errors (abortOnError is on; the remaining findings
                                              are pre-existing warnings in files this change does not touch)
  ./gradlew assembleDebug              PASS   debug APK
  ./gradlew assembleDebugAndroidTest   PASS   CustomersScreenTest compiles
```

`CustomersScreenTest` now asserts that both contact values have no click action of their own
(`contactValuesAreNotTapTargetsOfTheirOwn`) and that tapping either one reports the customer to open
(`tappingAContactValueOpensTheCustomerLikeTheRestOfTheRow`) — the regression test for the reported
accidental call/compose.

Instrumented execution is **NOT RUN — no online device/emulator was attached**; the agent does not run
device commands on this project:

```text
cd android && ./gradlew connectedDebugAndroidTest
```

Physical-device QA: **Awaiting product owner.**

```text
Android QA — Customers list contact values

1. Sign in as a Manager whose role has customers.view and open the Customers tab.
2. Tap directly on a row's phone number.
Expected: no dialer; Customer Details for that customer opens.

3. Go back and tap directly on the same row's email.
Expected: no email composer; Customer Details for that customer opens.

4. Open that customer's details and tap the phone number, then the email.
Expected: unchanged — the dialer opens prefilled with the number and places no call; the composer
opens addressed to the address and sends nothing.

5. Return to the list and confirm each row still shows the phone number with its phone glyph and the
email underneath it with its mail glyph.
```

