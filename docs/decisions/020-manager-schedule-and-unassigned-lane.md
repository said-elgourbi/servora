# ADR-020 — The manager schedule: the day's read, the unassigned lane and the mobile scheduling surface

**Status:** Accepted (agent decision, 2026-09-17, implemented in the same session)

Date: 2026-09-17
Tracker: `docs/tracker/038-android-manager-schedule.md`
Contracts: `docs/api/schedule.md` (new)
Predecessors: `docs/decisions/010-android-navigation.md` (the shell the Schedule destination lives in),
`docs/decisions/011-android-contextual-top-bar.md` (the one top bar),
`docs/decisions/014-android-offline-engine.md` (the working set this read deliberately does not use),
`docs/decisions/019-technician-field-experience.md` (the field read's scope, which this route is not)

References: `BR-001`, `BR-006`, `BR-007`, `BR-008`, `BR-010`, `BR-011`, `BR-012`, `BR-013`, `BR-023`,
`BR-024`, `BR-028`, `BR-041`, `BR-042`, `BR-052`, `BR-054`, `BR-056`, `BR-057`, `BR-059`, `BR-062`,
`BR-066`, `BR-068`, `BR-070`, `BR-071`, `BR-072`, `BR-073`, `BR-074`, `BR-080`, `BR-083`;
`Project.md` §4–§6, §11, §13–§15, §31; `dev.md` §7, §9, §10; `qa.md` §3.1;
`docs/architecture/offline-first-architecture.md` §2, §12, §13;
`docs/domain/job-visit-domain-model.md` §8.4, §9.1, §9.3, §10.

## Context

`Figma/src/imports/pasted_text/servora-scheduler-design.md` defines the mobile scheduling experience: a
week strip and the selected day's agenda, a technician filter, a Schedule/Unassigned distinction and a
month calendar used **only** as a jump-to-date mechanism. The Schedule destination has been a
placeholder in the shell since `docs/decisions/010-android-navigation.md`, the technician home's
`upcoming` preview was explicitly capped because "a real schedule read is its own feature"
(`docs/api/technician-home.md` §5), and the manager home answers only for *today*.

There was therefore no endpoint a schedule screen could read: `GET /home/manager` answers one day and
nothing about the technician filter or unassigned work. Fetching a wider range and grouping it on the
device would have re-derived scheduling rules the backend owns, which `BR-042` and `Project.md` §13
forbid.

## Decisions

### D1 — The day's schedule is its own read, and the client names the date

`GET /schedule` answers one local day, resolved by the API from a `YYYY-MM-DD` date and a `timeZone`
the client submits — the same resolution the two home reads use (`api/src/home/home-day.ts`), so a
local day begins in exactly one place. The client never groups Visits into days itself; the month
calendar and the week strip are **navigation**, and each one produces the date the next read names. An
omitted date answers for today in the submitted zone, which is the day `GET /home/manager` answers for.

### D2 — The unassigned lane is "an open Visit with no current assignment"

The lane answers "what still needs somebody?" from records that already exist:

```text
visits.status NOT IN ('COMPLETED', 'CANCELED', 'NO_SHOW')   -- BR-062's open Visit, BR-074's historical set
AND NOT EXISTS (visit_technicians for the Visit)
```

`BR-068` records assignment on the Visit, `BR-071`/`BR-072` explicitly allow a Visit to exist with no
technicians while it is being arranged, and the absence of an assignment is **not** a status
(`BR-042`) — which is why the lane is a projection and not a new state. The count the screen shows is
the API's own `unassigned.total`, never a client-side rule.

The lane is deliberately **not** day-scoped, and the read that carries it is a single request serving
both lanes: a Visit with no agreed time belongs to no day, and work waiting for a crew is waiting
whoever's day it lands on. Whether product ownership later scopes it to the selected day is recorded as
an open question rather than decided here.

### D3 — The read reuses `customers.view`

The schedule returns the organization's Jobs and Visits for a day, which is the data
`GET /customers/:id/jobs` and `GET /home/manager` already return under `customers.view`. The Jobs
feature has still not defined a `jobs.*` capability set (`BR-006`, `BR-008`), so introducing one here
would invent it. The interim guard and its open question are recorded in `docs/api/schedule.md` §2.

### D4 — The month calendar is navigation, never a second scheduling surface

The date header *is* the date selector: tapping it opens the platform's month calendar, and choosing a
date closes it, moves the week strip to that week and shows that day's agenda. The week strip is a
horizontally swipeable page of weeks, so previous and next weeks are reachable without the calendar,
and a "Today" affordance appears only when another day is selected. No control on the surface
schedules, reassigns or reschedules anything: those are explicit actions that belong to the Job
Details screen and the API's own routes (`BR-066`, `BR-072`, `BR-073`). The technician lanes,
time-grid and drag-and-drop the design reserves for the future web dispatcher are **not** built here.

### D5 — The Schedule destination is the office's, and the card presents the **Visit**

The destination is drawn when the session holds the capability its read is authorized by
(`customers.view`), the same way the Customers destination is, because `BR-010` forbids presenting
management functionality to a technician and the API would refuse the read anyway. A technician's own
schedule is a different read with a different scope (`ADR-019` D2) and its own slice.

The card presents `visitStatus` — the field attempt's own status (`BR-074`) — and never the Job's
(`BR-058`): the two are separate state machines (`BR-059`), and showing the Job's status beside a
Visit's schedule would describe the work as being somewhere it is not. The row opens the Job Details
destination the rest of the app already provides (`BR-012`).

### D6 — The read is online-only, and that is recorded rather than assumed

The manager schedule is not written to the working set. `dev.md` §10 and
`docs/architecture/offline-first-architecture.md` §13 require a new read to state its posture: this one
is a manager's dispatch view over the operation's own work, the offline standard's adopters are the
technician's reads and mutations (`offline-first-architecture.md` §12), and `BR-013` speaks of
"relevant field work" rather than of the office's board. The screen therefore holds the last answer the
backend reported **for the session only** — like the manager home — and reports a failed read rather
than presenting a stale day as current. Adopting the working set later is a change to D6, not a silent
addition.

## What this changes

- `api/src/home/home-day.ts` gains `LocalDate`, `localDateIn`, `formatLocalDate` and
  `resolveLocalDayWindow`, and `resolveDayWindow` is expressed over the last of them, so "today" and
  "a chosen date" are resolved by one implementation.
- New: `api/src/schedule/` (the read, its projections, its query parsing and its module),
  `docs/api/schedule.md`, the Android `data/schedule/` and `ui/schedule/` packages, and the Schedule
  destination in the shell.
- The manager home's "See all" already led to the Schedule destination; that destination now exists.

## Open questions not decided here

1. Whether the unassigned lane is day-scoped (`docs/api/schedule.md` §5).
2. What else "unassigned" should exclude, if anything.
3. The `jobs.*` capability set, and re-guarding this route when it lands.
4. The technician's own schedule, which this route and screen deliberately do not serve.
5. Conflict visualisation on the day, which belongs to `BR-070`'s scheduling action rather than to a
   read.
6. Per-day work indicators on the week strip (the design's "subtle indicators for dates containing
   scheduled work") would need a week-scoped count read; not implemented.

## Decision update — 2026-09-17 (the read admits a field caller, and resolves its own scope)

**Status:** Accepted (agent decision, recorded under `BR-040`; implemented in the same session by
tracker 041)

D5 said the Schedule destination was the office's and that a technician's own schedule is "a different
read with a different scope and its own slice". The slice landed, and the read is **not** a second
contract:

1. **One read, two scopes.** `GET /schedule` accepts `customers.view` **or** `VISIT_VIEW_ASSIGNED`
   (`@RequireAnyPermission`, the primitive `ADR-019` D1 introduced for exactly this shape), and the
   service resolves which work the caller reads: the operation's day (`ORGANIZATION`) or the Visits
   their own membership is on (`SELF`). A second route would have duplicated the day resolution, the
   ordering, the address fallback and the overdue derivation for one payload — the drift `ADR-019` D1
   rejected for the Job read.
2. **The scope is enforced in the service, and it is reported.** A `membershipId` outside the resolved
   scope is refused `403`, uniformly whether or not it names anybody, so a field caller cannot widen
   their read by hand (`BR-007`, `BR-042`, `BR-044`). The payload carries `scope { kind, membershipId }`
   so a client presents the work the API authorized rather than deciding a scope for itself.
3. **The two office-only projections are absent for a field scope**: `unassigned` is `null` and
   `technicians` is empty. The lane asks who is waiting for a crew — the office's question — and the
   options are the memberships a read may be narrowed to, which a field scope has none of
   (`BR-009`).
4. **D6 holds for the office scope and gains one adopter for the field scope.** The office board is
   still not written to the working set. A **field-scoped** day is: `BR-013` names "viewing assigned
   work" as field work that must survive lost connectivity, so the day the backend reported is kept
   in the existing working set, one row per subject per local date, and served only when the API
   cannot be reached (`offline-first-architecture.md` §12 adopter 10, §13).
5. **D5's destination rule widens by capability, not by role.** The Schedule destination is offered
   to a session holding **either** capability and presents the office schedule or the technician's own
   accordingly. A member holding both keeps the office schedule, exactly as `ADR-019` D6.5 keeps the
   office home for them.

**What this does not change.** The day resolution, the unassigned lane's definition for an office
caller, the month calendar as navigation and the absence of any scheduling action on the surface all
stand. **A team scope is not created**: reading a colleague's day as a field member would need a
capability the catalogue does not have, and it is recorded as an open question rather than invented
(`docs/api/schedule.md` §5.4, `docs/domain/job-visit-domain-model.md` §21).

