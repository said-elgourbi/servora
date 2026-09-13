# Tracker 010 — Customer detail

**Status: COMPLETE** (API read projections + Android detail screen)

Date: 2026-09-12
Predecessor: `docs/tracker/008-android-customers-filter.md`
Business rules: `BR-001`, `BR-007`, `BR-023`, `BR-041`, `BR-048`, `BR-050`, `BR-056`, `BR-058`,
`BR-068`, `BR-080`, **`BR-081`**
Domain model: `docs/domain/job-visit-domain-model.md` §8.4
API contract: `docs/api/customers.md`
Design reference: `Figma/src/screens/Customers.tsx` (`CustomerDetailView`, `AllJobsView`)

## Scope

Tracker 007 left the customer detail as a placeholder that could show only what `GET /customers`
returned. This slice makes the detail real: the API exposes the customer's Properties and Jobs as
derived projections, and Android renders the design's screen against them.

The derived values were **not** invented. They are defined by the new business rule `BR-081`:

| Row      | Value                 | Definition (`BR-081`)                                                                                                    |
| -------- | --------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| Property | `jobCount`            | every Job associated with the Property, whatever its status                                                              |
| Property | `lastServiceDate`     | scheduled start of the most recent `COMPLETED` Visit across those Jobs; absent when there is none                        |
| Job      | displayed date        | the **selected Visit**'s scheduled start                                                                                  |
| Job      | displayed technicians | the same selected Visit's current assignments (`BR-068`), Lead first                                                      |
| Job      | selected Visit        | the earliest upcoming Visit (`scheduledStart >= now`, status ≠ `CANCELED`), else the most recent past Visit by scheduled start |

Both Job-row values always come from the **one** selected Visit; they are never taken from different
Visits.

| Item                                                                                     | Status |
| ---------------------------------------------------------------------------------------- | ------ |
| `BR-081` added to `Business Rules.md` (derived projections, not stored)                   | Done   |
| `docs/domain/job-visit-domain-model.md` §8.4 records the projections                       | Done   |
| `GET /customers/:id` returns the detail header, subtype, derived counts and contacts       | Done   |
| `GET /customers/:id/properties` — active Properties with `jobCount` + `lastServiceAt`      | Done   |
| `GET /customers/:id/jobs` — Jobs with the selected Visit's `scheduledStart` + technicians  | Done   |
| Set-based selection (`DISTINCT ON`) so the projection stays one row per Job                | Done   |
| No schema change and no migration: the projection reads existing tables                     | Done   |
| API unit specs for the projection DTOs                                                       | Done   |
| API e2e specs for the projections against PostgreSQL                                        | Done   |
| Authorization e2e coverage for the two new routes (401 / 403 / 404 / allowed)               | Done   |
| Android wire DTOs, domain models and `JobStatus` (the backend's `BR-058` codes)             | Done   |
| `CustomersRepository.loadCustomerDetail` with one outcome for the three reads               | Done   |
| `CustomersViewModel` detail state: load, skip when already loaded, retry, close, reset       | Done   |
| Android detail screen: identity, contacts, Properties, Notes, Recent Jobs, Create Job FAB    | Done   |
| Job history screen, opened from "See all N jobs"                                             | Done   |
| EN/FR strings (including the localized "never serviced" and "unassigned" states)              | Done   |
| Android JVM tests (repository, ViewModel) and Compose tests (detail screen)                   | Done   |
| `docs/api/customers.md`, this tracker                                                         | Done   |

## Deliberate deviations from the design

| Design                                                            | This slice                                 | Why                                                                                       |
| ----------------------------------------------------------------- | ------------------------------------------ | ----------------------------------------------------------------------------------------- |
| Create Job sits in the identity/actions row                        | Create Job is a floating action button     | Directed by the product owner                                                              |
| Properties section shows 3 rows and a "See all" that is a no-op     | Every Property is listed; no "See all"     | A no-op control hides data with no way to reach it; there is no Property list screen yet    |
| A Property row is tappable and opens Property detail               | Property rows are not tappable             | Property detail is a separate feature that does not exist yet                               |
| "Add Property" in the empty state                                  | Omitted                                    | There is no Property write path (`POST /properties` does not exist)                          |
| Create Job navigates to the job form                               | Create Job is rendered but inert           | There is no Job create path; rendered rather than fabricated, as the previous placeholders did |
| Archive customer action (previously a placeholder)                 | Removed from the detail                    | The design has no archive action; archiving returns with the Edit Customer flow (`BR-008`). The `customers_archive` string resources are kept for that flow. |

## Permissions

The two new read routes require **`customers.view`**, continuing the decision recorded in
`docs/tracker/006-customers-feature-permissions.md`: *"Properties displayed inside customer screens
follow `customers.view` for this slice. No property permission was introduced."* No `jobs.view` or
`properties.view` permission exists in the catalogue, and inventing one is forbidden (`BR-042`).

> **Follow-up (product ownership).** When the Jobs feature lands with its own permission set, the
> customer-scoped jobs projection must be re-reviewed and guarded by whatever permission that
> feature defines. Recorded here rather than guessed.

## Behaviour notes

- An id the caller's organization does not own is `404`, never `403` (`BR-001`), on all three read
  routes.
- A Job whose status code this build cannot represent fails the whole detail read as `UNEXPECTED`
  rather than being dropped silently — the same contract-mismatch rule the list follows (`BR-042`).
- The three detail reads share a single outcome, so the screen cannot present a loaded header beside
  a failed section (`BR-001`).
- The detail read is skipped while the same customer's detail is already loaded or in flight, so a
  recomposition cannot start a second request.
- The repository performs the read outside the state mutator: `MutableStateFlow.update` may re-run
  its lambda on contention, and a re-run must never issue a second request.
- Dates and status codes are formatted in the client from the stable values the API sends; the API
  sends no display text (`BR-028`, `BR-041`).
- `lastServiceAt` arrives from PostgreSQL's `max()` aggregate, which the driver can hand back as
  text; `asDate()` accepts either shape rather than trusting one.
- The Job's address comes from the Job's preserved snapshot (`BR-056`), never the Property's current
  address, so a later Property edit does not rewrite what the Job row shows.

## Known limitations / follow-ups

- **Create Job is not wired.** There is no Job create path; the FAB is an affordance (`BR-042`).
- **Add Property is omitted.** There is no Property write path.
- **Property detail is not reachable.** Property rows do not navigate.
- **No pagination.** `GET /customers/:id/jobs` returns every Job the customer has, consistent with
  `GET /customers`; a pagination envelope is a separate decision (`dev.md` §7).
- **The customer-scoped jobs projection reads the Job & Visit tables inside the customers module**,
  because no Jobs module exists yet. When the Jobs feature is built, this read should be revisited
  so the Jobs module owns job projections (`Project.md` §12).
- **No offline caching.** The detail is a read and requires connectivity; offline customer detail is
  a future slice (`BR-013`, `BR-031`).

## Verification (2026-09-12)

```text
API
  npm run typecheck            PASS
  npm run lint                 PASS   0 warnings, 0 errors (120 files)
  npm test                     PASS   33 files / 260 tests
  npm run test:e2e             PASS   9 files / 121 tests
  npm run build                PASS

Android
  ./gradlew testDebugUnitTest        PASS   145 tests, 0 failures
  ./gradlew assembleDebugAndroidTest PASS   Compose tests compile
  ./gradlew lintDebug                PASS
  ./gradlew assembleDebug            PASS   debug APK
```

New coverage in this slice:

| Suite                                        | Tests | Covers                                                                              |
| -------------------------------------------- | ----- | ----------------------------------------------------------------------------------- |
| `src/customers/customer-detail.dto.spec.ts`  | 6     | the projection mappers: selected Visit, snapshots, derived dates, counts and contacts |
| `test/customer-detail-projection.e2e-spec.ts`| 6     | the derivations against PostgreSQL: counts, last service, selected Visit, technicians, ordering, tenant refusal |
| `test/customers-authorization.e2e-spec.ts`   | +2    | `401`/`403`/allowed on the new routes, and `404` for a foreign id                     |
| `CustomersRepositoryTest`                    | +4    | the three reads, contract-mismatch handling, renewal, no-session                       |
| `CustomersViewModelTest`                     | +5    | detail load, failure, de-duplication, retry, close/reset                              |
| `CustomerDetailScreenTest`                   | 11    | rendering, states, permissions, the FAB, "See all" and the Job history                |

Instrumented execution is **NOT RUN — no device or emulator was attached**:

```text
cd android && ./gradlew connectedDebugAndroidTest
```

The Compose tests are compiled by every run and were compiled again here, but they have not executed
on a device in this environment.

## Physical-device QA handoff (product owner)

Android acceptance on a real device belongs to the product owner (`qa.md` §7).

1. Sign in as a Manager with `customers.view`.
2. Open **Customers**, then tap a customer with jobs and properties.
   Expected: the detail loads with the name, status, "Since" date, phone and email links.
3. Scroll to **Properties**.
   Expected: each Property shows its address and "N jobs · Last service <date>", or "Never serviced"
   when the API sends no completed Visit.
4. Scroll to **Recent Jobs**.
   Expected: up to three Jobs with `#number`, title, address · date · technicians and the status
   pill. A Job whose selected Visit has no technicians shows "Unassigned".
5. Tap **See all N jobs** (shown only when the customer has more than three jobs).
   Expected: the Job History opens with every job; Back returns to the detail.
6. Tap **Create Job**.
   Expected: the button is present (it is not wired yet — the Jobs feature has no create path).
7. Stop the API and open a customer's detail (or tap Try again after a failure).
   Expected: a localized failure state with **Try again**; the button re-reads once the API returns.
8. Switch the app language to French and repeat 2–5.
   Expected: every string is French, including the Job status labels, "Jamais desservi" and
   "Non assigné".

## Follow-up — secondary screen header and a real back stack (2026-09-12)

**Trigger.** Product-owner defect report against the customer detail: the screen was titled
**"Customers"**, had no back arrow, and Android's system Back button left the app instead of
returning to the Customers list.

**Cause.** The signed-in area had no navigation component at all. `ServoraHomeScreen` held the tab in
a `rememberSaveable` value and `CustomersScreen` held the drill-down in `selectedCustomerId` + `mode`.
Nothing was pushed on a back stack, so system Back had nothing to pop, and the detail's header was a
`TextButton` labelled with the parent section name. The reasoning and the chosen design are in
`docs/decisions/010-android-navigation.md`.

**What changed.**

| Element | Change |
| ------- | ------ |
| Dependency | `androidx.navigation:navigation-compose` (version pinned in `gradle/libs.versions.toml`) |
| Navigation | `ui/navigation/ServoraNavHost.kt` — the signed-in graph: a `ROOT` destination holding the bottom-navigation area, with the customer screens pushed on top of it |
| Navigation | `ui/customers/CustomersScreen.kt` — the manual `selectedCustomerId`/`mode` state is gone; the screen is the list root and reports the customer it opens |
| Header | `ui/components/SecondaryScreenHeader.kt` — one shared header (back control, screen title, actions) used by every drill-down screen |
| Detail | `ui/customers/CustomerDetailScreen.kt` — titled **View Customer** (`customers_view_title`), back control first, Edit on the right |
| Detail | Job History is a destination of its own (`CustomerJobHistoryScreen`) instead of a flag inside the detail, so system Back returns to the detail |
| Resources | `customers_view_title` (EN "View Customer" / FR "Voir le client"), `nav_back` (EN "Back" / FR "Retour"), `ic_chevron_left` |

**Behaviour now.** The customer detail is pushed on top of the Customers list; the header arrow
(`navigateUp()`) and system Back pop the same destination; Edit Customer is pushed on top of the
detail; choosing a bottom-navigation tab pops any drill-down screen first; the app is left only from
the root. The global **Servora** header and the bottom navigation stay in the shell, and the tab
state is unchanged.

**Deliberately unchanged.** The create screen keeps its approved **New Customer** title; only the
detail's title was wrong. Property and Job screens do not exist yet, so the shared header is the
pattern they will use when they land.

**Released detail.** `CustomersViewModel.closeCustomerDetail()` is still called, now when the
bottom-navigation area is re-entered, which is the same "leaving the detail releases it" behaviour
the previous manual state had.

**Verification (2026-09-12).**

```text
Android
  ./gradlew testDebugUnitTest           PASS   145 tests, 0 failures, 0 errors
  ./gradlew compileDebugAndroidTestKotlin PASS  includes the new ServoraHomeNavigationTest
  ./gradlew lintDebug                   PASS   0 errors, 21 warnings (abortOnError is on)
  ./gradlew assembleDebug               PASS   debug APK
```

Instrumented execution is **NOT RUN by the agent** — installed-device acceptance belongs to the
product owner (`qa.md` §7):

```text
cd android && ./gradlew connectedDebugAndroidTest
```

`ServoraHomeNavigationTest` (instrumented, new) covers: open → pushed View Customer, header arrow →
Customers, system Back → Customers, Edit → Back → View Customer, See all → Job History → Back → View
Customer, re-open → no duplicate destination, and system Back on a root destination leaving the app.
`CustomerDetailScreenTest` gains the screen title and the back control's content description.

## Follow-up — one contextual top bar (2026-09-13)

**Trigger.** Product-owner defect report: the global **Servora** header sat above every screen, so a
secondary screen showed two stacked rows (the brand row and its own back-arrow title row). The brand
row was redundant, used vertical space, and carried no information about the current screen.

**Cause.** The shell's `Scaffold` drew `ManagerHeader` as a permanent `topBar`, while each pushed
screen drew a `SecondaryScreenHeader` inside its content. Neither row knew about the other.

**What changed.**

| Element | Change |
| ------- | ------ |
| Header | `ui/components/ServoraTopBar.kt` (new) — the single Material 3 `TopAppBar`, driven by a `ServoraTopBarState` (localized title, root flag, back callback, contextual actions) |
| Header | `ui/components/SecondaryScreenHeader.kt` — deleted; the app shell owns the only header |
| Shell | `ui/customers/CustomersScreen.kt` — the global brand row (`ManagerHeader`) and its inert alert glyph are gone; the shell `Scaffold` draws `ServoraTopBar` and builds the root destinations' titles from the selected tab |
| Navigation | `ui/navigation/ServoraNavHost.kt` — `servoraTopBarState(...)` maps the destination on top to its header; View Customer's **Edit** action is created there and gated on `canEditCustomer` |
| Screens | `CustomerDetailScreen`, `CustomerJobHistoryScreen` and `CustomerFormScreen` draw content only |

Root titles are `nav_home` / `nav_schedule` / `nav_customers` / `nav_settings`; pushed screens use
`customers_view_title` / `customers_all_jobs_title` / `customers_edit_title` / `customers_create_title`.
The Job History count that used to be a trailing item moves into the list. The alert icon is removed
and not relocated (`BR-029` has no documented destination). The reasoning is in
`docs/decisions/011-android-contextual-top-bar.md`.

**Behaviour now.** One top bar per screen. Root destinations show a title with no back control;
pushed screens show their own title with a back control that pops the same entry system Back pops.
The app is still left only from a root destination.

**Verification (2026-09-13).**

```text
Android
  ./gradlew testDebugUnitTest              PASS
  ./gradlew compileDebugAndroidTestKotlin  PASS  includes the new ServoraTopBarTest
  ./gradlew lintDebug                      PASS
  ./gradlew assembleDebug                  PASS   debug APK
  ./gradlew connectedDebugAndroidTest      NOT RUN — device QA belongs to the product owner
```

Instrumented execution is the product owner's (`qa.md` §7); the agent does not drive the owner's
phone over adb. Command to run against a device or emulator:

```text
cd android && ./gradlew connectedDebugAndroidTest
```

A single earlier agent-run attempt against the owner's phone is **not** evidence for or against this
change: 31 of 62 tests, including pre-existing unrelated Compose suites, failed with
`No compose hierarchies found in the app` (mixed pass/fail within the same suites), which is the
device not being in an interactive state. It was stopped at the owner's instruction.

`ServoraTopBarTest` (instrumented, new) covers the bar itself: the destination's own title, no back
control on a root destination, the localized back description and its callback, an empty actions
area, and a rendered action. `ServoraHomeNavigationTest` now also covers the root title without a
back control, View Customer's own title with a back control, and the **Edit** action's permission
gating.

