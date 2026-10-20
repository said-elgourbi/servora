# Servora web

Client-rendered Angular PWA for office users and technicians, including iPhone users. Implements email/password and phone/SMS sign-in, browser session
restoration, effective permission state, office and technician Home, and assigned Visit execution.
Office management screens follow in later roadmap phases. Architecture: [ADR-026](../docs/decisions/026-angular-foundation-and-browser-sessions.md).
Delivery plan: [web app roadmap](../docs/tracker/angular/064-web-app-delivery-roadmap.md).
Phase 2 offline design is accepted in [ADR-027](../docs/decisions/027-angular-field-offline-workflow.md):
bounded field snapshots, durable drafts and foreground replay with server-verified scope are implemented.
Reloading offline cannot unlock drafts. Physical iPhone/Safari/installed-PWA acceptance remains deferred.
See [field scope](../docs/api/field-scope.md) and the [field acceptance runbook](../docs/development/web-field-acceptance.md).

## Run locally

From the repository root, the Docker workflow builds and serves Angular at **http://localhost:4200**:

```bash
make up       # build and start Angular, API, PostgreSQL and MinIO
make reboot   # rebuild images and recreate containers after code changes
make down     # stop containers; retain database and uploaded files
```

Make combines `docker-compose.yml` with `docker-compose.local.yml`. The local overlay runs the API
in development mode with `WEB_ORIGIN=http://localhost:4200`, and Nginx serves the built PWA and
proxies API paths to that container. `WEB_PORT` in `.env` changes the browser port and default
origin together; if `WEB_ORIGIN` is explicitly set, it must match the browser address. The base
Compose file retains its production-mode API defaults. This HTTP workflow is for local use;
production hosting still requires HTTPS.

The local overlay selects the API's `development` image target, including its development logger.
Angular waits for the API health check (including the database) before starting. The default API
`runtime` image remains production-only and excludes development dependencies.

After `make reboot`, refresh the browser and use the PWA's Reload prompt if an update is offered.
`make restart` only restarts existing images. `make reboot` uses Docker's normal build cache, which
detects changed source files. Stop a separately running Angular dev server on port 4200 before
switching to this workflow. It does not stop host processes started from another terminal.

### Optional host workflow with live reload

Use Node **24.15+ within 24.x** (verified with 24.20.0), npm 11+, and the existing PostgreSQL stack.
Angular/Material/CLI are pinned to 22.2.3; TypeScript is 6.0.x (verified 6.0.3). Each application has its own lockfile.

```bash
# Repository root: install both clients of the shared contract and compile the API.
npm ci --prefix api
npm ci --prefix web
npm run build --prefix api

# Terminal 1: host API alongside the existing container, using the root .env for DB/JWT settings.
# Set DATABASE_URL in .env to your published PostgreSQL port. No credentials go in source.
cd api
PORT=3001 NODE_ENV=development WEB_ORIGIN=http://localhost:4200 npm run start:prod

# Terminal 2, repository root:
SERVORA_API_TARGET=http://localhost:3001 npm start --prefix web
```

Open **http://localhost:4200**. Use the same users as Android. No seed or database reset is required.
The development proxy forwards `/auth/**`, `/home/**`, `/jobs/**`, `/customers`, `/customers/**`, `/technicians` and `/schedule` and preserves Origin. Only the Angular proxy sees the API
upstream; browser requests use same-origin paths. Restart `npm start` after editing `proxy.conf.cjs`; Angular loads proxy routes only at startup.
A stale proxy can return SPA HTML with status 200 for `/home/**` or `/jobs/**`, causing the
workspace to display a generic error even though sign-in succeeds. The base production-mode Compose API has
browser auth disabled unless an HTTPS WEB_ORIGIN is configured. The host API above is an alternative
to Make's local development overlay. Build the API after server changes; `start:prod` here means run compiled code,
while the explicit NODE_ENV determines development cookie behavior.

For local SMS, select the existing development file provider (`SMS_PROVIDER=file`) on the host API
and follow [authentication contract](../docs/api/authentication.md). Real provider credentials and
SMS delivery are environment-owned. Never commit or capture passwords or OTPs in test artifacts.

## Structure

```text
web/
  src/app/
    app.config.ts, app.routes.ts, app.ts, app.html  composition and bootstrap
    core/
      connectivity/   online hint and browser event lifecycle
      i18n/           typed EN/FR dictionaries and runtime locale
      preferences/    theme and optional preference storage
      updates/        service-worker update lifecycle
    features/
      auth/
        data-access/  session state, HTTP, refresh, cross-tab coordination and errors
        pages/sign-in/ typed email/phone/code forms and responsive auth layout
        ui/           permission visibility directive
        auth.guards.ts, auth.routes.ts
      workspace/       office/field Home, Home access choice and session-bound display name
      jobs/            office Job read and shared Visit/contact presentation
      field/           scoped field reads, drafts/blobs, foreground queue and Visit/report UI
    layout/app-shell/ authenticated composition
    shared/ui/        reused preferences and status presentation
  src/styles/         Material role mapping from Android tokens
  public/             manifest and local application icons
  e2e/                Playwright journeys and isolated fixtures
packages/
  shared-types/       API/web auth declarations and permission-code catalogue
  shared-config/      strict browser compiler defaults
```

Dependencies point toward core/shared. ESLint prevents core/shared from importing features or
layout. Keep feature behavior out of generic infrastructure. Tests sit beside the behavior they
verify. No unused business folders, root global store, SSR or generated demo screen.

## Checks

```bash
npm run lint --prefix web
npm run typecheck --prefix web
npm test --prefix web
npm run build --prefix web

# Install Chromium once (from web/), then test the production output.
cd web
npx playwright install chromium
npm run test:e2e
```

`test:e2e` expects the production build from `npm run build`. Its temporary static server runs on
127.0.0.1:4300 and shuts down after the run. Normal browser journeys mock API responses; the PWA
suite uses the real production worker with an unauthenticated test API. Separately run the API's
PostgreSQL-backed `test/browser-authentication.e2e-spec.ts`, existing `auth-endpoints` and
`sms-authentication` suites. Tests create/clean their own users; the API harness applies pending
local development migrations. Browser screenshots/traces and reports are ignored build artifacts.

If Chromium is already installed elsewhere, set PLAYWRIGHT_CHROMIUM_EXECUTABLE to its absolute
executable path for local checks. CI should use Playwright's bundled version. Chromium viewport
checks do not substitute for Safari, Firefox, physical device installation, or external SMS QA.

## Firefox and Edge acceptance

Build first, then run the opt-in browser matrix:

```bash
cd web
npx playwright install firefox
# Uses Edge if it is already installed. On a dedicated CI machine, install it with:
# npx playwright install msedge
npm run test:acceptance
# Run one project when needed:
npm run test:acceptance -- --project=firefox-desktop
```

The matrix runs existing auth/workspace/keyboard journeys on desktop and responsive journeys at
390×844, 768×1024 and 1440×900 in Playwright Firefox and branded Microsoft Edge. It reuses fixture
responses and the temporary production server; connected API acceptance and production-worker tests
remain separate. This command requires both browsers and fails if either is unavailable.
Playwright Firefox is its instrumented build, not the system Firefox binary. Edge uses the `msedge`
channel; its installer changes the system browser installation, so prefer an existing installation
on a personal workstation. See [Playwright browser setup](https://playwright.dev/docs/browsers).

`PLAYWRIGHT_BROWSERS_PATH` can locate a temporary Playwright Firefox download;
`PLAYWRIGHT_EDGE_EXECUTABLE` can point to an isolated official Edge executable. The Chromium
executable override does not affect this matrix. Keyboard journeys use Tab and Shift+Tab without
assuming focus wraps through browser chrome. Screen-reader acceptance remains a separate
[review runbook](../docs/development/web-accessibility-acceptance.md); automated checks do not
establish spoken-output or physical-device acceptance.

## Connected workspace acceptance

Run `npm run build --prefix web`, then `npm run test:connected --prefix web` from the repository
root. Set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` if using an existing Chromium installation. This opt-in
check requires the API dependencies and the configured development PostgreSQL database from `.env`.
The existing API integration harness applies pending migrations and creates a temporary organization,
users with explicit office/assigned-only grants, and assigned visits; it cleans these records afterward.
No development server or seeded account is changed.

The harness starts NestJS on a temporary loopback port and the production SPA on port 4300, forwarding
same-origin authentication, Home and Job requests to that API. It checks password sign-in, Home,
visit selection, reload/session restoration, return navigation, sign-out/reload and denial of a
colleague's Job for assigned-only users. No browser responses are mocked. Credentials are generated
per run and passed through the child process environment; traces, screenshots and videos are disabled.
Both servers stop after the run. Normal fixture-based browser tests remain separate, and normal API
runs skip the connected test unless `RUN_CONNECTED_BROWSER=1` is set. This is local Chromium/API
acceptance; deployed HTTPS, external-provider and physical-device acceptance remain separate.

## Production hosting

Serve `web/dist/servora-web/browser/` over HTTPS, with one browser/API origin. Set the API's
WEB_ORIGIN to that exact public origin, e.g. `https://office.example.com`. No trailing slash/path,
no wildcard, and no CORS override. Browser endpoints are intentionally disabled in production when
this value is absent. Cookies are HttpOnly, Secure, SameSite=Strict and scoped to `/auth/web`.
Do not cache auth or protected API responses in a CDN, reverse proxy or service worker.

Example Nginx routing (replace API upstream for your deployment):

```nginx
root /srv/servora/browser;
index index.html;

# API namespaces must win over the SPA deep-link fallback. Keep this list in sync with API routes.
location ~ ^/(auth|jobs|customers|properties|home|schedule|technicians|push)(/|$) {
  proxy_pass http://servora-api:3000;
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-Proto $scheme;
  add_header Cache-Control "no-store" always;
}
location = /health { proxy_pass http://servora-api:3000; }
location = /index.html { add_header Cache-Control "no-cache"; }
location = /ngsw.json { add_header Cache-Control "no-cache"; }
location = /ngsw-worker.js { add_header Cache-Control "no-cache"; }
location ~* \.(js|css|png|svg|webmanifest)$ { try_files $uri =404; }
location / { try_files $uri $uri/ /index.html; }
```

Preserve the browser's Origin header. Add your deployment's security headers/CSP without allowing
unsafe scripts. Keep previous hashed chunks available during deployments for open tabs. The app
prompts for a full reload after the worker installs an update. Browser API and deep-link namespaces
must remain distinct: coordinate new resource paths with the proxy and worker navigation exclusions.

The worker caches explicit JS/CSS, index, manifest and icons only; dataGroups is empty. All auth and
opted-in protected requests use no-store and ngsw-bypass. An offline shell cannot authenticate or
work on business data. Access tokens and permission state exist in memory only; the browser owns
one cookie-backed session across tabs. Cross-tab logout/account switching clears other tabs, and
failed server revocation remains visibly retryable and blocks automatic restoration.


## Workspace (roadmap Phase 1)

Home uses existing API permissions: office `customers.view`, field `VISIT_VIEW_ASSIGNED`. A user
with both starts on office Home and can choose My work; field-only users never request office data.
Read-only details use `/work/jobs/:jobId?visit=:visitId`, keeping page URLs distinct from `/jobs` API
routes. They show the backend-authorized context and contact links; writes belong to Phase 2.
Home refreshes on return. Office information remains online-only and memory-only.
Assigned field reads use the bounded working set described below.

Desktop has a compact sidebar/top bar; phone/tablet have a Home wordmark and collapsible account controls.
Job details uses overview/visit cards alongside location/contact panels, stacking on phones.
Home and details share status badges and local SVG icons, with EN/FR and light/dark styling.
The top bar uses the display name supplied by Home when available; no organization selector or
notification destination is invented. Full checks, the resolution of the six API integration failures, and remaining acceptance gates
are recorded in [tracker 064](../docs/tracker/angular/064-web-app-delivery-roadmap.md#phase-1-implementation--2026-10-18).

### Scoped field execution

`features/field/` owns the verified partition, IndexedDB data, drafts/blobs and command queue. It is
used for field Home and assigned Visit details, actions, outcomes, notes and evidence, follow-up
editors and ad-hoc reports. An office projection never enters that store. Exactly one active field
membership is required; context refusal locks stored work. Multiple memberships keep online reads
available without persistence. After reload, restoration and context verification must succeed online.

Commands use stable UUIDs and captured payloads. One Web Lock serializes foreground replay across
tabs; evidence and notes precede completion. Refusals remain visible, and ambiguous status results
require explicit online review. Reviewing current details never resumes stale descendants. Accepted
media bytes are fetched online. MP4/M4A MIME aliases are canonicalized only after checking the MP4
header; WebM is not relabeled. The server checks the actual audio container and duration.

Logout clears snapshots and accepted command payloads; only minimal accepted receipts needed by
pending dependencies survive alongside locked unsent work/refusals. Seven-day/50-Job limits apply to
unpinned snapshots. Drafts and pending blobs commit together before Saved appears. Browser eviction
or clearing site data may still lose unsent work. Updates and route exits checkpoint editors; saving
failure blocks reload/navigation. Follow-up and clarification writes remain online-only.

### Customer management (roadmap Phase 3, first workflow)

Office users with `customers.view` can open Customers from the desktop sidebar or the phone/tablet
account menu. Browser pages use `/work/customers`, `/work/customers/new`,
`/work/customers/:customerId` and `/work/customers/:customerId/edit`; `/customers` remains an API
namespace. The development proxy, connected test proxy and production host forward that namespace.

The list uses the existing server status/work filters, with an in-memory search over returned display
names, email and phone. It does not search hidden legal names or contact-person names. Details show
contacts, current active property addresses only with `properties.view`, and the customer's complete
Job projection with links to existing office details. Independent contact capabilities control their
own buttons; `customers.edit` alone never enables a contact write.

Customer creation, editing, ACTIVE/INACTIVE changes and type conversion use existing contracts.
Conversions confirm replacement of the previous subtype; billing fields not managed by the form
are omitted, preserving their stored values. Archive is an explicitly confirmed removal from ordinary
use; it is distinct from INACTIVE and no customer restore action exists. Contacts can be added,
edited, marked primary or removed; edits/removals use the version read and leave unedited role and
billing/job flags alone. Conflicts retain the editor and require review/reload before new intent.

All customer reads/editors/writes remain online-only and in memory. No office data or commands enter
IndexedDB or service-worker response caches. Uncertain writes are never retried automatically;
submission is blocked until the user checks current records, avoiding an automatic duplicate create.
EN/FR, keyboard navigation, responsive layouts and current theme roles apply throughout.

This is the customer workflow inside Phase 3, not completion of the phase. Property and Job management are delivered below. Standalone customer-address management has no public
read/write API today (the internal address DTO does not constitute a route); it is not fabricated in
the web client. Property addresses are shown through the existing independently authorized read.


### Property management (roadmap Phase 3)

From customer details, Add property uses `properties.create`; reading the property list uses
`properties.view`. Active properties are the default, with explicit Archived and All filters. Rows
open `/work/customers/:customerId/properties/:propertyId`; create and edit append `/new` to the
collection or `/edit` to a property. Returning from an archived property selects the archived list.
The customer header is read again on return, keeping its active property count authoritative.

`features/properties/` owns these pages and HTTP calls. Property capabilities remain independent:
`customers.edit` grants no property action, and direct property reads do not require `customers.view`.
Optional customer names and the customer's property-filtered Job history are requested only with
`customers.view`; they use existing projections and preserve each Job's recorded address. A user
with create permission alone receives a success receipt from the authorized create response without
performing a property-detail read. Edit requires a property read plus `properties.edit`.

The address form asks for the contract's street, optional unit/name, city, stable Canadian province
code, postal code and optional notes. It does not invent country entry, property types or access
instructions from prototype fields that have no public contract. Editing sends the version read;
a conflict retains selectable/copyable inputs and requires an explicit fresh read before new intent. Credential refresh with unchanged capabilities preserves an unsent property form; real session boundaries still invalidate its reads.
Editing an archived property leaves it archived, with that fact explained in the form.

Archive/restore require `properties.archive`, explicit confirmation and the read version. Archive
confirmation shows server-projected active Job/Visit counts and explains that existing work continues.
Lifecycle requests have a UUID operation identity but are never queued or automatically retried after an uncertain result. Existing authentication recovery after a definitive 401 refusal is unchanged.
Permanent deletion requires `properties.delete` and the server's eligibility flag, with irreversible
confirmation; ordinary customer-associated properties have references and offer archive instead.
The server rechecks references, including concurrent changes. Uncertain writes retain the editor or
review state and block repeat submission until current records are reviewed.

All property workflows remain online-only and memory-only under Phase 3 and ADR-027. No property
records, credentials or office commands enter IndexedDB or service-worker response caches. The UI
supports EN/FR, both themes and phone/tablet/desktop layouts. Job creation/lifecycle management and its connected customer → property → Job journey are described below.


### Job management (roadmap Phase 3)

Find jobs in the customer's complete Job list (search returned job numbers, titles and descriptions),
or in a property's Job history and Home. The API has no organization-wide Job list/search endpoint;
this workflow uses its existing authorized customer projection instead.

New job opens `/work/customers/:customerId/jobs/new`. `JOB_CREATE` permits creation;
`properties.view` supplies the active-property picker. A Property entry preselects its active ID.
The customer name is read only with `customers.view`; create-only users with property read access
receive the authorized creation receipt without making a Job/customer-detail read. The request carries
only customer, property, title and optional description. The server sets the Job number, NEW state
and address snapshot. Visits, scheduling and crew remain Phase 4.

Office details keep customer/property context and all Visit summaries separate from the Job lifecycle.
`JOB_UPDATE` exposes the server's allowed Job destinations, including direct completion and reopening.
Every change is confirmed and sends the version read. Dedicated cancellation requires an explanation,
explains the open-Visit cascade and lists affected Visits/crews, including work already underway.
The existing API keeps its optional-note contract; the browser enforces the business-rule explanation. Open-Visit completion refusal and concurrent
changes require fresh review. Uncertain writes keep form input or the submitted explanation visible
and block resubmission; office commands are never queued or automatically replayed.

Activity includes Visit labels, actors, timestamps, outcomes and notes. `evidence.view` permits
on-demand photo/audio bytes; object URLs are revoked on closure, refresh, navigation and session or
permission changes. Dedicated photo/audio removal capabilities expose a confirmed removal with a
required free-text reason. Authorized audit reads show retained removal history and reasons; removed
bytes remain unavailable through ordinary content endpoints. The field and office views share the
localized activity-label presentation while retaining their separate data scope and storage paths.

All new UI supports EN/FR and responsive layouts using current Material roles. Job/customer office
reads and writes remain online-only, memory-only and excluded from service-worker response caching.
No database migration or API runtime change was needed. Shared public Job request/read/activity types
are checked by the existing API DTOs. Standalone customer-address management still requires a public
contract decision; physical iPhone and spoken accessibility acceptance keep their existing status.

Office schedule is available at `/work/schedule` to users with `schedule.view_org`, from the
desktop navigation or compact account menu. It reads `GET /schedule` online, with a selected local
calendar date, the browser time zone and repeated `membershipId` filters. Empty selection means all
technicians. The unassigned lane spans all dates and technicians; its server total can exceed the
returned rows (currently capped at 20). Each row opens its precise Visit in Job Details. Refresh
re-reads current records; offline/error responses clear rows and offer retry. No schedule data is
persisted for offline use. Scheduling a new Visit remains available from authorized Job Details;
rescheduling and crew reassignment are subsequent Phase 4 tasks.

The Office Schedule now defaults to a full-width technician time grid on desktop (1100px and wider).
Schedule/List switches between that board and a compact chronological table; smaller screens use an
agenda. Grid blocks preserve scheduled durations and overlapping assignments use separate slots.
Shared Visits are labeled and highlight together on focus/hover. Show full day expands the default
fit-to-visits range. Exact times and offsets, including overnight ends, appear in focus/hover details
and the list; short appointments also have readable table details below the board. The read includes
visits **starting on the selected date**, so earlier-date carry-ins are absent and blank grid time
is not confirmed availability. See [dispatch redesign evidence](../docs/tracker/angular/069-desktop-dispatch-redesign.md).
