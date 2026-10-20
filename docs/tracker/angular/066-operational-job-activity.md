# 066 — Operational Job Activity

Date: 2026-10-19
Superseded by [tracker 067](067-readable-job-activity.md).

Scope: Angular office Job Activity. Supersedes the activity presentation in tracker 065.

The earlier timeline still made managers read an audit log. Activity now has three mutually exclusive
views of the same API response:

- **All activity** (default): substantive updates plus counted summaries of routine records.
- **Updates**: notes/comments, outcomes, active evidence, scheduling/rescheduling, quote decisions,
  job customer/property changes and explanations attached to status changes.
- **System history**: individual routine status, crew and removal records, including removed originals
  when the existing authorized audit option is enabled. Exact actors, timestamps, transitions, roles,
  removal reasons and record references appear directly in these lightweight rows. Substantive updates
  retain exact event metadata under one Event details disclosure in either operational view.

Every returned event belongs to exactly one of Updates or System history. The All activity count equals
the sum of those partitions. Unknown kinds remain accessible in system history, using the existing
localized fallback label rather than displaying a raw event name. The server still decides which
removed records the caller may read. A removed original is history, never a live evidence card; removed
bytes are never requested.

Crew assignment/removal/role changes share one summary family. Status changes and evidence history
have separate families. Groups only combine consecutive related system records for the same visit,
local calendar date and at most one hour from the newest record in the group. Operational events
interrupt grouping. Counts represent actual records and time ranges represent recorded instants;
no crew outcome, resolved condition, appointment time or next step is inferred. A group expands one
level to exact rows. Date separators are flat, never collapsible.

Grouping precedes the 12-entry display limit, so a system burst cannot consume the entire initial
viewport's allocation. Both filtering and progressive disclosure operate over the complete response;
Show older activity adds 12 entries in the selected view. The API remains unpaginated and unchanged.
Notes lead with authored content, outcomes with their recorded result, and scheduling/quote decisions
with their business labels. Long comments have a six-line preview and one Read full update control.
Photos sit beside their captions at 112×72px and retain the existing viewer. Audio retains native
playback. Destructive evidence actions live in event details and the photo viewer with their existing
confirmation/reason flow. EN/FR labels and Material theme roles match existing app conventions.

Implementation separates the pure presentation projection (`job-activity-model`) and typed entry
view (`job-activity-entry`) from the existing office workflow controller. The controller retains API
reads/writes, permissions, conflict handling, session boundaries and media object-URL lifecycle. There
are no API, database, Android, dependency or permission-policy changes.

Validation includes empty, one-update, only-system, long, multi-visit, crew-heavy and comment/evidence
histories. Unit tests verify partition completeness, unknown/removed records, sort stability, grouping
boundaries and a 60-event crew burst followed by a note. Browser fixtures cover filter/keyboard
behavior, exact expanded history, full comment disclosure, complete pagination, evidence viewing and
removal, and responsive EN/FR light/dark screenshots at 1440, 768 and 390px. Screenshots live in
Playwright's ignored test-results directory. These use synthetic fixture evidence and API responses;
live-backend and other-browser acceptance are not part of this run.

Verification commands: `npm run typecheck --prefix web`, `npm run lint --prefix web`,
`npm run build --prefix web`, `npm run test --prefix web` (66 tests), and Playwright's
`jobs.spec.ts`, `job-details-responsive.spec.ts` and `job-activity-responsive.spec.ts` (29 scenarios).
The cached Chromium executable is selected via `PLAYWRIGHT_CHROMIUM_EXECUTABLE`; tests require a local
loopback server. Production build and component style budgets pass with the previously reported initial
bundle warning remaining (approximately 530 kB against 500 kB). No test server or browser is left running.
The foundation stack and existing unrelated working-tree changes are untouched.
