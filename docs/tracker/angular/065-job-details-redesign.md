# 065 — Web Job Details redesign

Date: 2026-10-19
Activity presentation is superseded by [tracker 066](066-operational-job-activity.md).

Scope: office Job Details presentation. No API, database, Android, permission-policy or lifecycle changes.

The job header now groups identity, customer, address and job status with contextual lifecycle actions.
API-derived attention links to its visit. Visits retain their separate status, purpose, schedule, crew
and outcome, and show relevant API-derived attention. Description uses progressive disclosure.
Desktop places location/contact details beside visits and activity; smaller screens stack the layout
and put lifecycle controls below the heading.

Activity is newest-first, grouped by local calendar date. Notes, outcomes and evidence have author
cards; consecutive system events use expandable rows retaining their metadata. Older dates collapse
by default. Rendering starts with 20 events; Show older activity reveals 20 more without discarding
history. Removed evidence remains behind the existing permission-gated audit control.

Photo previews use authenticated content requests and bounded rendered activity, with object URLs
revoked on teardown/session changes. Full photos use a Material dialog with focus management and
Escape dismissal. Audio retains native playback in a compact control. Evidence removal still requires
the existing reason dialog and permissions; lifecycle updates retain version checks, confirmation,
retained explanations and conflict blocking. Labels use the EN/FR dictionaries and existing theme roles.

Validation: web typecheck, lint and production build; 59 unit tests; 17 targeted Chromium browser
checks for lifecycle actions, evidence viewing/removal, read-only permissions, French workflows,
keyboard disclosure/viewer behavior, and 1440/768/390px layouts in light/dark themes. Screenshots are
produced by `web/e2e/job-details-responsive.spec.ts` in Playwright's `test-results` directory. The local
cached Chromium executable is selected using `PLAYWRIGHT_CHROMIUM_EXECUTABLE` because the installed
Playwright version's default browser revision is absent. The production build reports the configured
500 kB initial-bundle warning (approximately 528 kB); component style budgets pass.

Browser checks use fixture API responses. Live-backend and Safari/Firefox acceptance are outside this
verification. Test servers and browser processes are stopped by Playwright; the foundation stack is
untouched. The repository contained extensive existing changes, including the untracked web client;
this slice does not stage or commit those unrelated changes.


## 2026-10-20 — Header action placement

Refresh and Schedule a visit now share one action group beside the heading on desktop and below
it on smaller screens. Schedule a visit is the only filled primary action. Status transitions use
outlined buttons in a separate labeled row below the job details; cancellation retains its red text
style. Controls wrap on narrow screens. Confirmation, permissions and scheduling behavior are unchanged.

Tier A validation (commands run from `web/`):

- `npm run typecheck` — passed.
- `npm run build` — passed; existing 500 kB initial bundle warning remains (548.15 kB).
- `npx eslint src/app/features/jobs/pages/job-details.ts src/app/features/jobs/pages/job-details.html src/app/features/jobs/ui/office-job-workflow.ts src/app/features/jobs/ui/office-job-workflow.html` — passed.
- `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/home/said/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome npx playwright test e2e/job-details-responsive.spec.ts e2e/job-scheduling-responsive.spec.ts --workers=3`
  — 21 passed at desktop, tablet and phone sizes, including French scheduling. Desktop and phone
  screenshots were visually reviewed. Fixture API only; live backend and other browsers were not tested.

The sandbox prevented the localhost test server from listening; browser checks passed after running
outside the sandbox with the available cached Chromium executable. No dependencies were installed.


## 2026-10-20 — Scheduling date and time pickers

The Angular Schedule a visit dialog uses separate Material calendar and time pickers for start/end.
Inputs are read-only: click the field or its calendar/clock button to select a value. Time options
use 15-minute steps. The local date adapter follows EN/FR; field and toggle labels are translated.
Selected local date/time parts combine before the existing UTC request serialization. Required fields,
end-after-start validation, conflict confirmation and uncertain-response duplicate prevention remain.

Tier A validation, run from `web/`:

- `npm run typecheck` — passed.
- `npm run build` — passed; initial bundle warning remains (554.91 kB against 500 kB).
- `npx eslint src/app/features/jobs/ui/schedule-visit.ts src/app/features/jobs/ui/schedule-visit.html src/app/core/i18n/en.ts src/app/core/i18n/fr.ts` — passed.
- `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/home/said/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome npx playwright test e2e/job-scheduling-responsive.spec.ts --workers=3`
  — 15 passed. Tests select dates and times through the pickers, verify Toronto-to-UTC conversion,
  invalid end times, conflict confirmation, retained selections, French labels, and desktop/tablet/phone.
  Desktop/phone form screenshots reviewed. Fixture API only; other browser/device acceptance remains.
