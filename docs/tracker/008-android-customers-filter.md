# Tracker 008 — Android Customers filter

**Status: COMPLETE** (backend-filtered customer list; Android filter sheet)

Date: 2026-09-12
Predecessor: `docs/tracker/007-android-customers-list.md`
Business rules: `BR-001`, `BR-007`, `BR-041`, `BR-058`, `BR-074`, `BR-080`
Domain model: `docs/domain/job-visit-domain-model.md` (§8.4)
Design reference: `Figma/src/screens/Customers.tsx` (the `FilterSheet` component)

## Scope

Tracker 007 left the customers Filter control deliberately inert. This slice implements the filter
the design defines and narrows the list **on the backend**, so the API remains the authority for
which customers a caller receives (`BR-001`, `BR-007`).

The filter has two single-select dimensions:

| Dimension           | Options                                                              |
| ------------------- | -------------------------------------------------------------------- |
| **Customer Status** | All · Active · Inactive                                              |
| **Jobs**            | All · Has open jobs · No open jobs · Has overdue visits · No jobs yet |

| Item                                                                          | Status |
| ----------------------------------------------------------------------------- | ------ |
| `CustomerListFilters` vocabulary + parser (`customer-list-filter.dto.ts`)     | Done   |
| `GET /customers?status=&jobs=` — validated, tenant-scoped filtering in SQL    | Done   |
| `EXISTS`/`NOT EXISTS` semi-joins so the derived counts are not multiplied     | Done   |
| Android `CustomerFilters` domain model + wire codes on `CustomersApi`         | Done   |
| `CustomersViewModel.applyFilters` re-reads on change; skips an unchanged filter | Done |
| Android filter sheet (status segments, jobs list, Clear, Apply) matching Figma | Done  |
| Empty filtered list reports "no match", not the "no customers yet" state      | Done   |
| EN/FR strings; `ic_check_circle` glyph                                        | Done   |
| API unit + e2e coverage; Android JVM + Compose coverage                       | Done   |
| Tracker, domain-model and API-contract documentation                          | Done   |

Deliberately **not** done: applying a filter offline (the list is a read, not an offline mutation
queue), saved/pinned filters, sorting controls, and multi-select filters.

## Product decisions this slice depends on

Two of the Jobs options had no defined meaning before this slice. The product owner defined them
explicitly for this feature:

- **Open Job** — a Job whose status is not terminal (`BR-058`): `NEW`, `SCHEDULED`, `IN_PROGRESS`
  or `PENDING_REVIEW`. `COMPLETED` and `CANCELED` are not open. A customer with no Jobs has no open
  Jobs, so **No open jobs** includes customers with no Jobs at all.
- **Overdue Visit** — a Visit still `SCHEDULED` (`BR-074`) whose scheduled end is earlier than the
  server's `now()`. A Visit that has progressed (`EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`) is work in
  progress, not overdue, and a terminal Visit is history.

Both are **derived** conditions over the authoritative Job and Visit tables; nothing new is stored
(`BR-080`). They are not new Job or Visit statuses (`BR-042`).

> **Follow-up (product ownership).** These two definitions are confirmed product behaviour and are
> currently recorded here and in `docs/domain/job-visit-domain-model.md`, not in
> `.clinerules/Business Rules.md`. They should be promoted to a numbered business rule so the
> authoritative contract and the implementation stay aligned (`Project.md` §26, `BR-040`).

## API contract

`GET /customers` gained two optional, validated query parameters. Both default to `ALL`, which
constrains nothing, so an existing caller that sends neither is unaffected.

```text
GET /customers?status=ALL|ACTIVE|INACTIVE&jobs=ALL|HAS_OPEN_JOBS|NO_OPEN_JOBS|HAS_OVERDUE_VISITS|NO_JOBS
```

- Codes are stable and machine-readable (`BR-041`); clients never send display text.
- An unrecognized or non-string value fails validation with `400 VALIDATION_FAILED`. A repeated
  parameter reads its first value.
- Filtering is applied inside the tenant-scoped query, so it cannot widen a caller's scope.
- The response shape is unchanged (`CustomerSummaryDto[]`): because the filtering is server-side,
  no new derived count needed to be added to the payload.

## Behaviour Notes

- **Filtering is server-side.** `CustomersService.listCustomersInOrganization(scope, { filters })`
  adds the status predicate and a jobs semi-join to the existing grouped query. The semi-joins
  (`EXISTS` / `NOT EXISTS`) filter customers without multiplying the `propertyCount` / `jobCount`
  rows the same query selects.
- **`overdue` is evaluated against the database clock.** `visits.scheduled_end < now()` uses
  PostgreSQL's `now()`, not a client or API-host clock, so all callers agree on what is overdue.
  `BR-026` leaves organisation scheduling time zones open; this slice introduces no time-zone
  behaviour and compares absolute instants.
- **The Android list re-reads on change.** Applying a different filter re-requests the list; the
  filter is not re-checked on the client. Re-applying the *same* filter is a no-op, so Apply cannot
  cause a duplicate request.
- **Search stays client-side.** The search field narrows the customers the backend already returned
  for the active filter; it is not a server query.
- **Empty vs no-match.** When the backend returns no customers and a filter is active, the list
  shows "No customers match these filters" and its clear action resets both the search and the
  filter. An unconstrained empty list still shows the "No customers yet" empty state, so a genuinely
  empty account is not told its filters matched nothing (`BR-042`).
- **UI still is not authorization.** The filter sheet is shown to anyone who can open the list; the
  backend independently enforces `customers.view` (`BR-007`).
- The filter sheet commits on Apply (closing) or Clear (resetting and staying open, as the design
  wires Clear).
- **The sheet opens fully expanded.** `CustomersFilterSheet` sets
  `rememberModalBottomSheetState(skipPartiallyExpanded = true)`, so the Commit actions (Clear,
  Apply) are on screen the first time the sheet is shown instead of being below the fold at the
  half-height stop. Its content is vertically scrollable so the actions stay reachable if the sheet
  is shorter than its content (for example on a short landscape phone).

## Follow-up fix (2026-09-12)

Two defects were reported against this feature and corrected:

| Defect                                                        | Cause                                                                                                                                                         | Fix                                                                                                            |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| The sheet opened halfway; Clear/Apply required dragging it up. | `ModalBottomSheet` defaulted to the partially expanded stop, which the sheet's content exceeds.                                                                | `skipPartiallyExpanded = true` plus a scrollable content column.                                                |
| Applying a filter changed nothing.                             | The running API container image was built **before** `customer-list-filter.dto.ts` / `customers.controller.ts` existed, so `GET /customers` ignored the query. | No code change was needed — the source already applies the filter. The container was rebuilt (`make up`).        |

Verification of the second defect over HTTP against the rebuilt API (Manager session):

```text
status=ALL&jobs=ALL               200  20 customers
status=ACTIVE&jobs=ALL            200  17 customers
status=INACTIVE&jobs=ALL          200   3 customers
status=ALL&jobs=NO_JOBS           200   0 customers
status=ALL&jobs=HAS_OPEN_JOBS     200  17 customers
status=ALL&jobs=NO_OPEN_JOBS      200   3 customers
status=ALL&jobs=HAS_OVERDUE_VISITS 200  0 customers
status=BOGUS                      400  VALIDATION_FAILED
```

> A stale API image looks exactly like an unwired filter. When a backend-filtered feature appears
> inert on a device, confirm the running container includes the change before treating it as a bug.

## Follow-up fix — session renewal (2026-09-12)

A second report of "filtering by Inactive still shows Active customers" was traced to the
**session**, not the filter. The API container logs showed the device sending `status=INACTIVE` and
the API answering `401`: an access token is short-lived (`ACCESS_TOKEN_LIFETIME`, default `15m`) and
the Android client never renewed it. The list kept the rows from the last successful read, and the
screen only showed an error when the list was empty, so the previous (unfiltered) rows were
presented as the result of the new filter.

| Defect | Cause | Fix |
| ------ | ----- | --- |
| A filtered read shows the previous filter's rows once the session expires. | No access-token renewal; a failed read kept `customers` and the error state required an empty list. | `SessionAuthenticator` renews through `POST /auth/refresh` when the backend refuses the token; `DefaultCustomersRepository` retries the read once; the ViewModel clears `customers` on failure so the error state (with Retry) is shown, never stale rows. |

- Added: `data/session/SessionAuthenticator.kt` (`SessionAuthenticator`, `SessionRenewal`,
  `DefaultSessionAuthenticator`), `AuthApi.refresh`, `RefreshRequestDto`,
  `SessionStore.refreshToken()` and a shared `SignInResponseDto.toIssuedSession()` mapper.
- Refresh rotates the refresh token, so the renewal is serialized with a `Mutex`: a caller that
  raced another renewal reuses the fresh token instead of spending the rotation twice.
- A refused renewal (`401`/`400`) clears the session; one that cannot reach the backend keeps the
  session and is reported as a network failure.

## Verification

```text
API
  tsc --noEmit                         PASS
  oxlint src/ test/                    PASS   0 warnings, 0 errors
  npm test                             PASS   254 tests, 32 files
  npm run test:e2e                     PASS   113 tests, 8 files (PostgreSQL 17)

Android
  ./gradlew testDebugUnitTest          PASS   BUILD SUCCESSFUL
                                              SessionAuthenticatorTest 7, CustomersRepositoryTest 11,
                                              CustomersViewModelTest 11 — 0 failures
  ./gradlew assembleDebugAndroidTest   PASS   CustomersScreenTest compiles
  ./gradlew lintDebug                  PASS   0 errors
  ./gradlew assembleDebug              PASS   debug APK
```

Instrumented execution is **NOT RUN — no online device/emulator was attached**:

```text
cd android && ./gradlew connectedDebugAndroidTest
```

Physical-device QA: **Awaiting product owner.**

```text
Android QA — Customers filter

1. Sign in as a Manager whose role has customers.view and open the Customers tab.
2. Tap Filter.
Expected: a bottom sheet titled "Filter Customers" opens **fully expanded** with a Customer Status
row (All | Active | Inactive) and a Jobs list (All | Has open jobs | No open jobs |
Has overdue visits | No jobs yet), plus Apply visible without dragging; Clear appears only once
something is selected.

3. Choose Customer Status = Inactive and tap Apply.
Expected: the list shows only inactive customers and reflects the backend result.

4. Reopen Filter, choose Jobs = Has overdue visits, tap Apply.
Expected: only customers with a scheduled Visit whose end time has passed are listed.

5. Reopen Filter and tap Clear.
Expected: the list returns to all customers and the sheet stays open.

6. Select Jobs = No jobs yet and tap Apply.
Expected: only customers with no Jobs are listed.

7. In the list, search for a name that matches none of the filtered customers.
Expected: "No customers match these filters" is shown; its action restores the full list.

8. Switch the app language to French and repeat 2–7.
Expected: the sheet, its options and the empty states are French; the filter results are unchanged.

9. Leave the app idle for longer than the access-token lifetime (default 15m), then change the filter.
Expected: the list still updates — the app renews the session through `POST /auth/refresh` and
retries the read — instead of an error or the previous filter's rows.

10. With a session that can no longer be renewed (for example after a sign-out from another device),
change the filter.
Expected: the list is replaced by the error state with Retry; the previous, differently-filtered
rows are never shown.
```

## Product default — active customers only (2026-09-12)

The customer list now opens on **active customers only**, and that default is presented as a real
applied filter rather than hidden state.

| Item                                                                        | Status |
| --------------------------------------------------------------------------- | ------ |
| `CustomerFilters` defaults to `status = ACTIVE`, `jobs = ALL`                | Done   |
| `CustomerFilters.Unconstrained` names the cleared state (`ALL`/`ALL`)        | Done   |
| `CustomerFilters.appliedCount` drives the filter control's applied state     | Done   |
| Filter control is brand-filled and numbered while any filter applies         | Done   |
| Applied-filter chips under the search field, each removable, plus Clear all  | Done   |
| Clearing resets every dimension to `ALL` (the unconstrained list)            | Done   |
| EN/FR strings for Clear all, chip removal and the accessible count           | Done   |
| Android JVM + Compose coverage updated                                       | Done   |

Behaviour notes:

- The default is a client decision about the *view*, not a change to the API. `GET /customers` still
  defaults both dimensions to `ALL`, and the client always sends its filter explicitly (`BR-001`).
  No backend, contract or database change was required.
- The default Active selection is visibly a filter: the control is filled and shows the number of
  applied filters, and the selection appears as a chip under the search field. Removing a chip
  clears only that dimension; **Clear all** returns the unconstrained list, which is how a Manager
  reaches inactive customers.
- An empty result keeps the existing distinction: while any dimension is applied the list reports
  "No customers match these filters"; only the cleared, unconstrained list shows "No customers yet".
  A brand-new account can still create its first customer from the floating action, which does not
  depend on the list state.

### Verification (2026-09-12)

```text
Android
  ./gradlew testDebugUnitTest           PASS   CustomerFilterTest 5, CustomersViewModelTest 13,
                                               CustomersRepositoryTest 12 — 0 failures
  ./gradlew assembleDebugAndroidTest    PASS   CustomersScreenTest compiles (new filter tests)
  ./gradlew lintDebug                   PASS   0 errors
  ./gradlew assembleDebug               PASS   debug APK
```

Instrumented execution is **NOT RUN — no online device/emulator was attached**:

```text
cd android && ./gradlew connectedDebugAndroidTest
```

Physical-device QA: **Awaiting product owner.**

```text
Android QA — Customers default filter

1. Sign in as a Manager whose role has customers.view and open the Customers tab.
Expected: the list shows active customers only; the filter control is filled and shows "1"; an
"Active" chip with a remove affordance and "Clear all" appear under the search field.

2. Tap the Active chip's remove affordance.
Expected: the chip, the "1" and the filled state disappear and the list shows every customer,
including inactive ones.

3. Reopen the filter sheet, choose Customer Status = Inactive, tap Apply.
Expected: the control is filled with "1"; an "Inactive" chip is shown; only inactive customers are
listed.

4. Add Jobs = Has open jobs and tap Apply.
Expected: the control shows "2" and both chips are listed.

5. Tap Clear all.
Expected: every chip disappears, the control reads "Filter" and the full list returns.

6. Switch the app language to French and repeat 1–5.
Expected: chips, Clear all and the spoken filter count are French; the results are unchanged.
```


