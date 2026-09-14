# Tracker 016 — Manager Home (Android) and its API read

**Status: COMPLETE for API and Android implementation; physical-device QA is the product owner's**

Date: 2026-09-14
Predecessor: `docs/tracker/015-android-edit-customer.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-008`, `BR-010`, `BR-011`, `BR-012`, `BR-013`,
`BR-020`, `BR-023`, `BR-028`, `BR-031`, `BR-041`, `BR-042`, `BR-060`, `BR-061`, `BR-062`, `BR-068`,
`BR-072`, `BR-074`, `BR-080`
Domain model: `docs/domain/job-visit-domain-model.md` §8.4
API contract: `docs/api/manager-home.md`
Design reference: `Figma/src/screens/Dashboard.tsx`, `manager-home-screen.md`,
`servora-manager-home-refinemen.md`
ADR: `docs/decisions/011-android-contextual-top-bar.md` (the one top bar the header reuses),
`docs/decisions/012-property-lifecycle-and-permissions.md` (the offline standard D7)

## Scope

The Home destination existed only as a placeholder: two sample cards with invented copy
(`home_attention_sample_*`, `home_today_*_sample`). This slice makes Home the operational screen the
design specifies, backed by authoritative data, for **Manager** users of the Android application.

| Item | Status |
| --- | --- |
| `GET /home/manager` — one request serving the whole screen | Implemented |
| Authorization: `customers.view`, the existing Job/Visit capability | Implemented |
| Day window resolved by the API from the caller's time zone | Implemented |
| Needs attention: `VISIT_OVERDUE`, `JOB_PENDING_REVIEW`, `JOB_NEEDS_SCHEDULING` | Implemented |
| Today: `total` / `completed` / `inProgress` / `upcoming`, the three counts partitioning the total | Implemented |
| Today's schedule: Visit status, technicians (Lead first), Job title, Customer, address | Implemented |
| Operational ordering of both lists | Implemented |
| "See all" → the Schedule area | Implemented |
| Greeting + current date in the existing contextual top bar | Implemented |
| Loading / cached-while-refreshing / unrefreshed-but-visible / failure states | Implemented |
| No-attention "you're all caught up" state | Implemented |
| EN/FR copy for every new string | Implemented |
| **Follow-up: collapsible "Needs attention"** | **Implemented — see the follow-up section below** |
| API unit spec (`manager-home-day`, `manager-home.dto`) and API e2e (HTTP + derivation) | Implemented |
| Android JVM tests (repository, ViewModel, UI state) and Compose tests updated | Implemented |
| **New Job action** | **Not implemented — blocked, see below** |
| **Notification icon** | **Not implemented — no notifications feature exists** |
| Persisted offline working set (Room) for Home | **Not implemented — see the offline note below** |

## The blocking gap: the New Job action

The Figma's `+ New Job` action must be permission-aware, and **no permission in the catalogue
authorizes creating a job**. `Permission.kt` holds `customers.*` and `properties.*` only, and the
database catalogue has no `jobs.*` code (`api/drizzle/migrations/0003`, `0005`, `0007`).

`BR-008` does name "Create jobs / View jobs / Update jobs / Delete jobs" as the Manager's default
capabilities, so the capability is confirmed in product terms, but the *codes* — and more
importantly the whole `jobs.*` set, including what the Technician role receives (`BR-009`) — belong
to the Jobs feature, which has not been designed as a slice yet.

Per the brief ("If one is needed, stop and document the issue rather than silently inventing it")
and `BR-042`, the action is **not rendered** in this slice rather than being:

- drawn ungated (which would offer an action no permission authorizes), or
- gated on an unrelated capability such as `customers.create`, which `BR-085`'s rule — a capability
  is never implied by another resource's capability — forbids.

**What closing it needs:** a product decision on the `jobs.*` capability set (codes, Manager grant,
Technician grant), then a migration adding the codes and role grants, the Job-create route, and the
Job form. Until then the FAB stays off, and the rest of Home is unaffected.

## What was reused unchanged

- The signed-in shell, its `Scaffold`, bottom navigation and single contextual top bar.
- The session contract (`SessionAuthenticator`, `401` → renew once → retry) that every repository
  follows, and the `CustomersFailureReason` vocabulary those repositories already share.
- `Retrofit`/`kotlinx-serialization` transport, Hilt wiring, the design system's tokens and shapes.
- The customer-detail destination as the attention/row target (below).
- The API's existing multi-tenant patterns: `OrganizationScope`, `@RequirePermissions`,
  `AuthGuard`/`PermissionsGuard`, the `AuthApiError` envelope, and the `visit_technicians` →
  `user_profiles` technician projection written for `BR-081`.

## The API gap this slice closed

There was **no Job/Visit read at all**. Deriving Home from existing endpoints would have meant one
customer-scoped call per customer (`GET /customers/:id/jobs`) plus client-side classification of
overdue work: many loosely coordinated requests, and a server-time rule decided on a device. A
dedicated operational read is therefore introduced, returning only what this screen needs. It is not
an analytics endpoint and returns no aggregate beyond the day's counts.

## Deliberate deviations and decisions

| # | Figma / brief | What was built | Why |
| - | ------------- | -------------- | --- |
| 1 | Greeting + date in the page header | The existing contextual top bar shows the greeting as its title and the date as its subtitle | The shell draws exactly one top bar so a screen cannot stack a second header (`ADR-011`); this keeps both the design and the architecture |
| 2 | Notification icon | Not drawn | No notifications feature exists (`BR-029` is an OPEN QUESTION); a bell that opens nothing is a dead control |
| 3 | "2 jobs need a technician" (aggregate card) | One card per condition, carrying the Job number, title, Customer and scheduled time | The brief requires every card to navigate somewhere real and no filtered-list destination exists, so aggregate copy could not address a specific record |
| 4 | "Mike is running late" | Not produced | No confirmed rule defines "running late" (`BR-070`, `BR-038`). The derived `VISIT_OVERDUE` condition is presented instead, and it needs no travel or location data |
| 5 | Job card → Job Details | Job card → the Job's Customer detail | At the time of this slice no Job Details destination existed; Customer detail was the existing screen from which the Job and its Visits are reachable. Product ownership confirmed the intended target is Job Details and deferred it to the Job Details slice — **resolved by `docs/tracker/017-android-job-details.md`**, where the card and the schedule rows open the Job |
| 6 | "See all" → schedule | Switches to the existing Schedule tab | That is the existing Schedule area, and the tab is where today is worked on |
| 7 | Amber warning treatment | `colorScheme.error` tint | The design system has no warning token; adding one is a design-system decision, not a client one |
| 8 | Chronological schedule | Overdue → active → upcoming → completed, then chronological | The brief asks for operational ordering "where this can be implemented cleanly from existing rules"; every rank is a Visit status plus the schedule, so no ambiguous logic was invented |
| 9 | Summary "1 unassigned · 1 late" | Not repeated | Exception counts belong to Needs attention; the brief forbids duplicating them |
| 10 | Three Figma preview states | One screen with real states | Those three were preview controls, not product states |

## Permissions

`GET /home/manager` requires **`customers.view`**, continuing the interim decision recorded in
`docs/tracker/010-customer-detail.md` (*"No `jobs.view` or `properties.view` permission exists in the
catalogue, and inventing one is forbidden (`BR-042`)"*). A caller with `customers.view` can already
reach every Job and Visit this read returns through `GET /customers/:id/jobs`.

> **Follow-up (product ownership).** The Jobs feature's capability set must re-review this route.

No client-side gate hides data from a caller the API would serve: the customer detail destination the
cards open performs its own authorized read.

## Section primitives

`SectionLabel` and `InfoCard` moved from `ui/customers/CustomersScreen.kt` to
`ui/components/ServoraCards.kt`. They are shared presentational primitives with several consumers,
and the manager home is the second feature to render them; a second copy would have let the metrics
drift (`dev.md` §1). No behaviour changed.

## Offline behaviour

The screen follows what the repository **has**: a successful read replaces the state, and a failed
read keeps the last state the backend reported and shows a non-blocking "couldn't refresh" notice with
a retry (`BR-013`, `BR-014`, `BR-031`). The manager home is read-only, so no outbox applies.

It is **not** persisted across process death. The project's offline/outbox standard names Room as the
single local working set and states that it does not exist yet, and `ADR-012` D7 records that its
first adopter is the Property lifecycle slice (`docs/architecture/offline-first-architecture.md` §3,
§12). Adding a second, screen-specific store here would be exactly the parallel caching mechanism the
brief forbids, so the working set stays in the ViewModel, scoped to the session and released on
sign-out.

## Follow-up (2026-09-14) — the "Needs attention" section is collapsible

**Change requested by product ownership.** On a busy day the conditions filled the screen and pushed
**Today** and **Today's schedule** far below the fold, which is the opposite of the section order the
screen is for (`BR-010`, `BR-012`). The heading is now the control that shows and hides its cards.

| Item | Status |
| --- | --- |
| The section heading is a show/hide control (count + chevron turning 90°) | Implemented |
| Opens expanded while it fits a screenful, collapsed beyond that | Implemented |
| A collapsed heading still reports the backend's `attention.total` | Implemented |
| 48 dp touch target; no chevron and no click when nothing needs attention | Implemented |
| Attention card → **Job Details** | **Implemented by `docs/tracker/017-android-job-details.md`** |

**Why a disclosure and not a second screen.** The Figma keeps Needs attention as the day's
highest-priority section, so its cards are not removed or reordered and nothing is hidden that the
manager cannot reach in one tap. What changes is only how much of the screen the section occupies
when its own list is long. This is presentation, not business behaviour: the conditions, their
operational order and their count all remain the backend's answer (`BR-001`, `BR-041`, `BR-042`).

**The rule.** `attentionSectionStartsExpanded(count)` (`ManagerHomeUiState.kt`) opens the section
expanded up to three cards — a screenful — and collapsed beyond that. A refusal to collapse a long
list at all was rejected as the thing being fixed; collapsing a short list was rejected because it
would hide information there is room to show. The threshold is a presentation constant, adjustable
without touching the domain or the API.

**State.** The manager's own toggle lives in `rememberSaveable`, so rotating the device or a
configuration change does not discard it. It is deliberately not persisted: returning to Home shows
the default for the list the backend has just reported.

**Tests.**

- `ManagerHomeUiStateTest.AttentionSectionExpansionTest` (JVM) — the threshold on both sides.
- `ManagerHomeScreenTest` (Compose, new) — every condition shown while the list fits; collapsed with
  the count kept when it does not; the heading reveals and re-hides the cards; no toggle at all when
  nothing needs attention.

**Manual QA (product owner, physical device) — follow-up only.**

```text
Android QA — Manager Home, collapsible Needs attention

Seeded so that at least four conditions need attention
(for example three overdue Visits plus a Job with no Visit).

1. Open Home.
   Expected: "Needs attention · N" is shown and its cards are collapsed; the Today card and Today's
   schedule are on the first screen without scrolling.

2. Tap the "Needs attention" heading.
   Expected: the cards appear, the chevron turns down, and the heading keeps the same count.

3. Tap the heading again.
   Expected: the cards are hidden again.

4. Rotate the device while the cards are hidden.
   Expected: they stay hidden.

5. Reduce the day to three or fewer conditions and open Home again.
   Expected: the cards are shown straight away, with the heading still tappable to hide them.

Known expectation at this stage
- Tapping a card opens the Job Details screen (`docs/tracker/017-android-job-details.md`).
```

## Verification

```text
API
- Unit (Vitest):            PASS  (338 tests, 38 files)
- API e2e (PostgreSQL):     PASS  (180 tests, 13 files)
- lint (oxlint):            PASS
- typecheck (tsc):          PASS

Android
- Unit (JVM):               PASS  (243 tests, 25 classes)
- lint:                     PASS  (0 errors)
- assembleDebug:            PASS
- assembleDebugAndroidTest: PASS
- Physical device:          AWAITING PRODUCT OWNER
```

New tests in this slice:

- `api/src/home/manager-home-day.spec.ts` — day boundaries in a non-UTC zone, a daylight-saving day,
  a month boundary, the half-open window, sub-second independence, zone normalisation.
- `api/src/home/manager-home.dto.spec.ts` — the overdue predicate, both orderings, payload mapping.
- `api/test/manager-home.e2e-spec.ts` — `401`/`403`/`200`, the day window over HTTP, the `400` for an
  unresolvable zone, the fixed-offset device identifier; and against a real PostgreSQL: overdue,
  a progressed Visit, needs-scheduling (including a `DRAFT` Visit), pending review, tenant isolation,
  a deleted Customer's work, ordering, technician order, address fallback, the summary partition and
  the viewer's name.
- `android/.../ManagerHomeRepositoryTest.kt` — token and zone sent, mapping, renewal retry, each HTTP
  classification, and the contract-mismatch rule for an unknown code.
- `android/.../ManagerHomeViewModelTest.kt` — first read, no duplicate request, refresh, a failed
  refresh keeping content, a failure with nothing cached, retry, reset, and a read that finishes
  after the session ended.
- `android/.../ManagerHomeUiStateTest.kt` — the state derivations and the greeting periods.

## Manual QA (product owner, physical device)

```text
Android QA — Manager Home

Sign in as the Manager development account, with the API running and seeded.

1. Open Home.
   Expected: the top bar greets by name with today's date; Needs attention, Today and Today's
   schedule are shown.

2. Leave a Visit `SCHEDULED` whose scheduled window has ended (or use a seeded overdue Visit).
   Expected: it appears first under Needs attention as "Job #… is overdue", and its schedule row
   shows "Overdue".

3. Create a Job with no Visit, or make one `PENDING_REVIEW`.
   Expected: it appears under Needs attention as "needs scheduling" / "waiting for review".

4. Tap an attention card, and separately a schedule row.
   Expected: the Job's Customer opens; Back returns to Home with the day still shown.

5. Tap "See all".
   Expected: the Schedule tab becomes the selected destination.

6. Open Home again after a successful load.
   Expected: the previous day is shown immediately and refreshed behind it (a brief "Updating…"
   line), with no blank screen.

7. Turn airplane mode on, then open Home.
   Expected: the last day stays visible with "Couldn't refresh"; with no cached day, the failure
   state with Try again is shown.

8. Switch the app language to French and reopen Home.
   Expected: the greeting, date, section labels, card copy and status labels are French.

9. Switch the device to dark mode.
   Expected: cards, borders and status colours follow the dark scheme.

Expected limitations
- There is no New Job action (no permission authorizes creating a job yet).
- Notifications are not implemented, so no bell is drawn.
```

## Open questions recorded by this slice

| # | Question | Rule | How it is handled now | Blocks |
| - | -------- | ---- | --------------------- | ------ |
| 1 | Formal definition of "technician running late" | `BR-070`, `BR-038` | No such condition and no ETA; only the schedule-based overdue condition | A late alert, an ETA or a travel estimate |
| 2 | Attention conditions for a Visit cancelled today | `BR-076` | `CANCELED` is excluded from the day and produces no condition | A "cancelled today" condition |
| 3 | The `jobs.*` capability set | `BR-006`, `BR-008` | Home reuses `customers.view`; the New Job action is not rendered | The New Job action; re-guarding this route |
| 4 | A dedicated warning colour token | `BR-028` | The error role tints the attention affordance | Design-system completion |
| 5 | Persisting Home for offline use | `BR-013`, `BR-014`, `BR-031` | In memory for the session; Room is the single working set and is not implemented | A Home that opens with content after process death |
| 6 | An organization-wide "today" | `BR-026` | The day is the zone the device reports, which is the manager's actual working day | Cross-zone operational reporting |
| 7 | Attention card → **Job Details** | `BR-010`, `BR-012` | **Resolved by `docs/tracker/017-android-job-details.md`**: the Job Details destination exists, so the card and the schedule rows now open the Job | — |
