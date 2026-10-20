# 068 — Office schedule and unassigned work

Date: 2026-10-20
Status: Implemented — automated browser acceptance passed; live API/browser and manual acceptance remain open.
Presentation superseded by [tracker 069](069-desktop-dispatch-redesign.md), implementing the owner-requested desktop time grid and compact table.

Scope: the first scheduling task within Phase 4 of tracker 064, following the owner's standalone
customer-address deferral. This does not complete Phase 4.

Office users with `schedule.view_org` can open `/work/schedule` from the desktop sidebar or compact
account menu. The online-only page reads the existing `GET /schedule` projection with the browser's
IANA time zone, a local date and repeatable technician membership filters. Date selection, previous /
next day and Today support calendar navigation. Empty technician selection means all technicians;
an explicit All technicians action clears the filter. Server ordering, visit status, purpose, crew,
location and overdue classification are preserved. The page never derives overdue from its own clock.

The unassigned lane spans all dates and technicians as defined by the API. It shows the authoritative
total and explains when only the first records are returned (currently limited by the API to 20).
Each card opens the exact Visit on the existing office Job Details page. The page has loading, empty,
forbidden and connection failure states with retry; changing a request clears old rows and cancels the
previous read. Session boundaries clear data, and a SELF response cannot be rendered as an office
projection. EN/FR text, labels, keyboard focus and phone/tablet layouts use existing Material themes.

The shared type package now describes the existing schedule JSON response; the API contract, endpoint,
database, Android and permission catalogue are unchanged. Authenticated requests use the existing
interceptor, memory-only session and no-store/service-worker bypass headers. No schedule data is
persisted. Development and optional connected-test proxies now forward `/schedule`; production routing
already includes that namespace. References: [schedule API](../../api/schedule.md),
[web setup](../../../web/README.md), [roadmap](064-web-app-delivery-roadmap.md).

Verification: Tier B for the affected web application (full lint, typecheck, unit suite and build),
plus focused Chromium schedule and workspace browser journeys. Exact commands:

- `npm run typecheck --prefix web` — PASS.
- `npm run lint --prefix web` — PASS.
- `npm test --prefix web` — PASS, 70 tests in 17 files.
- `npm run build --prefix web` — PASS; initial bundle warning remains, 556.57 kB against 500 kB.
- `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/home/said/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome npm run test:e2e --prefix web -- schedule-responsive.spec.ts workspace.spec.ts` — PASS, 23 tests. Schedule scenarios run at 1440, 390 and 768px; workspace regression checks run on desktop.

Browser scenarios cover date/year boundaries, technician query/auth headers, unassigned totals and
precise navigation, 403 retry, empty results, French layout, dispatch permission denial without a
request, canceled prior-day reads, refusal of narrower server scope, failed network reads and sign-out.
Fixtures provide the API responses; no live backend, real device, other browser or screen-reader
acceptance is claimed. Playwright required sandbox escalation for its local loopback server and stops
its server/browser processes after the run. Physical iPhone acceptance remains owner-deferred.

Next task: existing-Visit rescheduling and crew assignment/reassignment through the established API,
including conflict confirmation, stale-state handling and refresh of affected office views. New-Visit
scheduling with an explicit lead is already implemented in Job Details. Follow-up/ad-hoc reconciliation,
quote decisions and technician schedule/history remain later Phase 4 work.
