# 069 — Desktop Office Schedule redesign

Date: 2026-10-20
Status: Implemented; automated acceptance recorded below. Deployed/live-API and physical-device acceptance remain open.
Scope: owner-requested structural redesign of tracker 068's office schedule. Phase 4 remains open.

The earlier centered stack of cards wasted desktop space and made technician allocation hard to scan.
The schedule now uses the full shell content width. A compact toolbar combines date navigation, Today,
technician selection, Schedule/List view selection and refresh. The header summarizes the number of
returned visits, the API's unassigned total, API-reported overdue visits and distinct visits whose
returned time intervals overlap for a technician. No Job priority or additional attention state is inferred.

Desktop defaults to a time grid with technician columns, including eligible technicians with no returned
visits. Time geometry uses elapsed instants within `day.start` / `day.end`; the day is never assumed to
contain 24 hours. The default viewport fits returned visits with one hour of context within those bounds;
Show full day exposes the complete day. Hour labels use the API's time zone and UTC offset, including
repeated hours on fall-back days. Visits spanning an offset change also include both offsets in their visible time label. The time axis and column headings stay visible within a keyboard-
scrollable board. Many technicians scroll inside the board without widening the page.

A Visit with several technicians appears once in each assigned column with the same Job number and a
Shared visit label. Hover/focus highlights its matching copies; summary counts remain per distinct Visit.
Overlapping intervals use separate horizontal slots and a visible warning marker, preserving exact start
and end positions. Adjacent windows do not overlap. Purpose/status text and existing Material color
roles distinguish assessment, work and completed visits. Very short blocks retain exact duration and
also have readable full details in a compact table below the grid. Exact dates, times, offsets, location,
customer, crew and status are accessible on focus/hover; links open the existing precise-Visit destination.
Overnight blocks clip at the day boundary and show a continuation marker; exact end dates/times remain
in accessible labels/tooltips and the list.

List view is a semantic seven-column table (Time, Job, Customer, Location, Crew, Visit Type, Status),
chronologically sorted with unscheduled records last. Crew includes the Lead label. Below 1100px the
page uses a compact agenda with full crew and status information. View switching never issues writes.
The unassigned queue stays one action away with its server count and capped-result explanation;
its dates and technician scope are unchanged. The existing permission checks, request cancellation,
no-store headers, session clearing, error states and exact-Visit links remain in place. Only the schedule
route opts into the wider shell layout. New code stays in the schedule feature; no dependencies added.

## Contract limits

`GET /schedule` selects Visits whose start belongs to the requested local date. It does not supply
carry-in visits that started on earlier days, leave, working hours, travel buffers, or global availability.
The UI labels the starting-date scope and explains that gaps do not confirm availability. Overlap badges
compare the returned intervals only; authoritative scheduling/conflict confirmation remains with existing
API writes. This redesign introduces no backend, database, RBAC, Android or scheduling-rule changes.
The unassigned lane remains across all dates and independent of technician filtering.

The owner explicitly selected the desktop grid and compact table in this request. The mobile-only
Figma scheduler and ADR-020's original Android scope remain references for mobile behavior. Their
historical exclusion of a desktop grid from the Android slice does not prevent this requested web design.

## Verification

Tier B for web: full typecheck, lint, unit suite and production build, with focused Playwright schedule /
workspace journeys. No backend change requires migration or API re-verification for this presentation slice.

- `npm run typecheck --prefix web` — PASS.
- `npm run lint --prefix web` — PASS.
- `npm test --prefix web` — PASS: 76 tests in 18 files, including calendar boundaries, exact block
  geometry, adjacent/double/triple overlaps, shared crew, filtering, midnight, overnight continuation,
  missing times, overlapping unassigned records without technician-conflict warnings, and 23/25-hour DST days with repeated-hour offset formatting.
- `npm run build --prefix web` — PASS. Initial bundle warning remains (~559 kB against 500 kB);
  component style budgets pass.
- `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/home/said/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome npm run test:e2e --prefix web -- schedule-responsive.spec.ts schedule-dispatch-responsive.spec.ts workspace.spec.ts`
  — PASS: 32 browser tests; six desktop-only cases intentionally skipped in phone/tablet projects. Fixtures supply API responses; this is browser UI acceptance.

Light/dark and EN/FR screenshots use 1440, 768 and 390px viewports and are written to Playwright's
ignored `web/test-results/` output (`dispatch-light-en.png`, `dispatch-light-fr.png`,
`dispatch-dark-en.png`, `dispatch-dark-fr.png` and desktop `dispatch-list-<theme>-<language>.png`). Desktop checks exercise exact positioning, shared-copy
highlight, keyboard opening, unique chronological table rows, unassigned discovery, midnight/overnight
geometry, brief-Visit details and board scrolling with twelve technicians. Existing cases cover request
headers, filters, date navigation, forbidden/no-permission reads, retry, cancellation, network loss and
sign-out. Desktop grid tests are deliberately skipped on phone/tablet projects, which test the agenda.

Playwright needs a local loopback server outside the sandbox. It stops its server and browsers after
the run. Live API/provider/deployed-host, spoken screen-reader, Firefox/Edge and physical-device QA are
not claimed by this run. Physical iPhone QA remains owner-deferred. Existing unrelated working-tree
changes and running foundation services are preserved.
