# Tracker 064 — Web app delivery roadmap

**Status: IN PROGRESS — Phases 1–2 implemented; Phase 3 implementation scope delivered; standalone customer-address management deferred by owner on 2026-10-20. Phase 4 office schedule read implemented; visit management remains open. Manual accessibility, deployed/provider and field-device acceptance remain open. Physical iPhone testing is deferred by the owner.**

Date: 2026-10-18

Source: product-owner request to phase the web app in substantial, usable deliveries rather than
many small phases. The web app must serve technicians as well as office users: there will be no
native iOS app initially, so technicians with iPhones will rely on the PWA.

## iPhone testing deferral — owner decision, 2026-10-18

The owner has no iPhone available and explicitly deferred iPhone testing across this project until
one is available. Physical iPhone/Safari and installed-iPhone-PWA checks in all phases and tracker
063 are deferred and do not block development or phase progression during this period. Keep them
recorded for later execution; do not mark them passed or claim device acceptance. Continue available
automated browser and responsive-layout verification. This decision supersedes the iPhone testing
gates below until the owner has a device; the iPhone product target remains in scope.

## Scope and tracking

- **MVP direction confirmed:** one Angular PWA for office users and technicians, using the existing
  API, domain rules and permission model. Native iOS is outside this initial delivery.
- **Proposed MVP sequence:** the five remaining phases below. Their detailed scope and unresolved
  product/architecture choices are proposals, not approval to implement every capability.
- Keep this as the authoritative web roadmap. Update phase checklists and acceptance evidence here;
  do not create a new numbered phase for each screen, API adapter, styling change, test or bug fix.
- A phase is a complete user workflow, including necessary API work, permissions, EN/FR,
  responsive design, errors, verification and documentation. These are tasks within the phase.
- Implement one phase at a time. Resolve its material questions before dependent code; keep normal
  implementation choices and fixes inside the phase. Existing shared trackers remain authoritative
  for cross-application business behavior.
- Existing Android implementation is reference behavior, not proof a browser feature is implemented.
  Reuse current contracts and rules rather than reproducing historical Android delivery steps.

## Delivery sequence

| Phase | Usable result | Status |
| --- | --- | --- |
| 0 — Foundation and authentication | Users can sign in securely in a responsive, bilingual, installable shell | Implemented in [063](063-angular-foundation-and-authentication.md); acceptance QA remains open |
| 1 — Workspace and both Home experiences | Office users see the operational day; technicians see their own next work | Implemented — automated web checks pass; acceptance gates remain open |
| 2 — Technician field workflow | An iPhone technician can carry out an assigned visit and submit its evidence and outcome | Initial API/web implementation delivered; physical field acceptance pending |
| 3 — Customer and job management | Office users can maintain customers/properties and manage jobs through their lifecycle | Implementation scope delivered — standalone customer addresses deferred by owner; manual acceptance gates remain open |
| 4 — Planning and office follow-through | Office users can schedule crews and resolve follow-ups, ad-hoc reports and quote decisions | In progress — office schedule/unassigned read delivered; visit management and follow-through remain open |
| 5 — Notifications and release acceptance | Both audiences can use the agreed web release on supported devices with verified notification behavior | Planned |

Phase 0 retains its historical tracker and evidence. Its remaining QA is carried into the relevant
phase and release checks below; it must not be relabeled fully accepted. Dependencies may require
a small supporting read or action earlier than its broader management surface. Keep that task in
the phase that needs it, without adding a new phase or claiming the later phase complete.

## Phase 1 — Workspace and both Home experiences

**Outcome:** after sign-in, each user reaches a useful Home and can navigate the workspace on
desktop, tablet or phone.

- [x] Deliver the desktop vertical sidebar and top bar; settle phone/tablet navigation in the same
  design. Phone/tablet use the wordmark Home link, a compact account menu and the Home view switcher; bottom navigation is deferred until more destinations exist.
- [x] Settle top-bar identity: user name/profile, organization label where the backend provides
  authoritative context, and the planned notification entry. Do not invent organization selection
  or assume `/auth/me` already returns display names or selected organization context.
- [x] Implement office Home against `GET /home/manager`: attention, today's work and the existing
  operational summaries. Use the current projection rather than inventing dashboard metrics.
- [x] Implement technician Home against `GET /home/technician`: their next work, today's visits and
  the current classification/attention behavior in tracker 058.
- [x] Define a deterministic Home choice and navigation for users with both office and field
  permissions. Never authorize by role name or assume all users are exclusively one audience.
- [x] Provide the minimum authorized Job/Visit detail read needed for Home links, including field
  customer/property context and useful contact/directions actions. Full execution belongs to Phase 2.
- [x] Show only available destinations. Add notification interaction when backed by Phase 5's
  behavior; no fake unread badge, empty production destination or nonfunctional bell.
- [x] Verify EN/FR, theme, keyboard journeys, loading/empty/error/forbidden states and responsive
  layouts in automated Chromium checks; see the browser acceptance follow-up below.
- [x] Run Firefox/Edge automated acceptance for sign-in, workspace, permissions, keyboard and
  responsive layouts; exact versions and limits are recorded in the browser-matrix follow-up below.
- [ ] Complete screen-reader/manual accessibility acceptance. Safari/iPhone sign-in, restoration
  and installed-PWA navigation remain deferred by the owner.

**Acceptance:** office and field users can sign in, understand their day and open a relevant item;
assigned-only users cannot see office-wide data. Real iPhone checks are recorded alongside browser
automation. Business data is still online-only at this stage, with explicit connection failures.

## Phase 2 — Technician field workflow

**Outcome:** a technician can open assigned work, perform the visit and record the result from
an iPhone PWA, with the agreed behavior during connection loss.

- [x] Complete Job/Visit details, activity, assignment/customer/property context and permitted
  contact actions, preserving current Job/Visit separation and visit purpose.
- [x] Deliver permitted visit transitions, required outcomes, notes, and visit-scoped photo/audio
  evidence together: capture/select, review, submit, browse and play/view. Use current contracts
  and capability checks; do not revive older job-level evidence assumptions.
- [x] Include technician follow-up requests, clarification/replies, and ad-hoc work reporting so
  exceptional field work has a complete path. Office handling follows in Phase 4.
- [x] Settle browser working-set, draft, replay, conflict and session/scope policy in
  [ADR-027](../../decisions/027-angular-field-offline-workflow.md), accepted 2026-10-19.
- [x] Implement the accepted working set, durable drafts/pending uploads and replay together with
  the field workflow. Deliver server-verified context for persisted reads/writes first; verify
  response-loss/dependency behavior before enabling local business data.
- [x] Define what remains online-only and how stale data, pending work and failures are reported.
  Verify foreground/resume recovery without depending on background execution being available.
- [ ] Validate camera/photo selection, microphone/audio, storage/quota/eviction behavior, app closure,
  network loss and reconnect on a physical iPhone, including installed-PWA use.

**Acceptance:** complete the assigned-visit journey against the real API, including evidence and
outcome. Demonstrate the agreed offline journey and recoverable failures on iPhone before claiming
field readiness. Browser offline design is accepted in [ADR-027](../../decisions/027-angular-field-offline-workflow.md);
initial implementation is delivered below. Device acceptance remains pending. Service-worker response caching stays shell-only.

## Phase 3 — Customer and job management

**Outcome:** office users can maintain the customer/property records and jobs needed to run work.

- [x] Deliver customer search/list/filter, details, individual/company behavior, contact people,
  Property service addresses and the permitted create/edit/lifecycle actions supported by current rules.
  Standalone customer contact/billing address management is deferred by owner decision (2026-10-20).
- [x] Deliver property details, create/edit and permitted lifecycle actions with customer context.
- [x] Deliver job discovery through supported API reads, creation, full office details, activity
  and permitted lifecycle actions with required confirmations and invariant handling.
- [x] Reuse field detail/evidence presentation where useful; add office capabilities such as audited
  evidence removal only where authorized. Keep office and assigned-only scopes distinct.
- [x] Identify any missing list/search API contract before building the surface; preserve existing
  clients when adding the necessary contract and shared TypeScript types.
- [x] Verify a real customer → property → job journey and denied actions on desktop and phone.

**Acceptance:** an authorized office user can create or find a customer/property, create a job,
return to it and manage its permitted lifecycle without using Android. Office writes remain
online-only unless an existing approved contract and a deliberate web decision support replay.

## Phase 4 — Planning and office follow-through

**Outcome:** office users can turn jobs into assigned visits and resolve the work coming back
from technicians.

- [ ] Deliver the office schedule and unassigned work, visit scheduling/rescheduling, crew
  assignment/reassignment and permitted visit management with the existing lifecycle constraints.
- [ ] Deliver the technician's own schedule/history access with assigned scope, reusing Phase 2's
  visit actions. Technician Home continues to provide the daily entry point in earlier phases.
- [ ] Deliver office follow-up review/clarification and ad-hoc report reconciliation end to end.
- [ ] Deliver assessment quote state, optional PDF upload/open, sent/approved/rejected actions and
  subsequent work scheduling using tracker 061. Preserve online-only quote operations and the
  separation between quote decisions, Job lifecycle, Visit status, purpose and outcome.
- [ ] Connect Home attention items to their resolution; refresh affected Home/schedule/detail state
  after changes and handle stale concurrent changes explicitly.
- [ ] Verify office → technician → office journeys, including assignment changes, follow-up,
  report reconciliation and assessment → quote decision → work scheduling.

**Acceptance:** office staff can plan a visit, the assigned technician can execute it, and office
staff can resolve the returned outcome or exception without switching to Android. No full team or
role-administration feature is implied by needing an eligible crew picker.

## Phase 5 — Notifications and release acceptance

**Outcome:** the agreed web scope is ready for office and iPhone field use, with verified browser
delivery, update and recovery behavior.

- [ ] Settle notification scope and deliver the agreed top-bar interaction, preferences and
  authorized deep links. An in-app inbox/read model is an open product decision, not something
  already provided by the Android assignment-push implementation.
- [ ] Design browser registration/delivery for the approved assignment event, using tracker 062's
  domain behavior while documenting browser-specific support, installation and permission needs.
  Do not assume Android's FCM registration/display implementation works unchanged on the web.
- [ ] Define the fallback when push is unsupported or denied; opening Home/schedule must remain
  useful. Test taps through signed-out, signed-in, reassigned and forbidden states.
- [ ] Close tracker 063's remaining relevant acceptance QA, including real-provider authentication,
  supported browsers, physical installation and multi-version update/reload behavior.
- [ ] Verify production-host HTTPS, deep links, update handling with pending drafts/uploads,
  session/account isolation, agreed offline recovery, EN/FR and accessible phone/tablet/desktop UX.
- [ ] Record release evidence, known limitations and the supported browser/device matrix; resolve
  release-blocking failures inside this phase rather than generating small follow-on phases.

**Acceptance:** end-to-end office and field journeys pass on the agreed supported browsers and a
physical iPhone in Safari and installed-PWA mode. Delivery/provider acceptance, actual notification
display and tap behavior are recorded separately. Build success alone does not establish readiness.

## Decisions to settle in the relevant phase

| Decision | When needed | Current position |
| --- | --- | --- |
| Top-bar name/context, phone navigation and mixed-permission Home choice | Phase 1 design | Resolved in Phase 1 implementation decisions below |
| Organization context and data scope | Phase 1 contract review | No organization selector/label until the backend provides authoritative context |
| Browser offline scope, persistence and replay/session policy | Before Phase 2 implementation | ACCEPTED DESIGN — [ADR-027](../../decisions/027-angular-field-offline-workflow.md), 2026-10-19; scoped IndexedDB working set/drafts and foreground replay implemented; service-worker caching remains shell-only |
| Supported iPhone/iOS/browser versions and field capability acceptance | Establish in Phase 1; validate in Phases 2 and 5 | OPEN QUESTION; verify current platform support from primary sources during implementation |
| Notification inbox, event coverage and browser transport | Phase 5 design | OPEN QUESTION; assignment events exist, browser delivery/inbox not yet implemented |

## Backlog outside this proposed sequence

Full user/team/role administration, password reset UI, additional notification event types, reports,
billing/payments, and native iOS are **BACKLOG** unless explicitly selected. Existing backend or
Android support does not automatically add a web management feature to MVP.

## Reference behavior and contracts

- [Foundation/authentication](063-angular-foundation-and-authentication.md),
  [web setup](../../../web/README.md), [browser sessions](../../decisions/026-angular-foundation-and-browser-sessions.md)
- [Office Home](../../api/manager-home.md), [technician Home](../../api/technician-home.md),
  [current technician Home classification](../shared/058-technician-home-classification-and-redesign.md)
- [Job details](../../api/job-details.md), [actions](../../api/job-actions.md),
  [activity](../../api/job-activity.md), [visit-scoped evidence](../shared/056-evidence-belongs-to-a-visit.md)
- [Customers](../../api/customers.md), [schedule](../../api/schedule.md),
  [follow-ups](../../api/visit-requests.md), [ad-hoc reports](../../api/ad-hoc-work-reports.md)
- [Quote workflow](../shared/061-assessment-quote-workflow.md),
  [notifications](../shared/062-notifications-and-firebase.md),
  [offline architecture](../../architecture/offline-first-architecture.md)


## Phase 1 implementation — 2026-10-18

Authorization: owner asked to start Angular, follow this plan and use best recommendations for
uncertain details. That authorizes the Phase 1 choices below. Phases 2–5 remain separate deliveries.

### Decisions and delivered behavior

- Desktop uses a vertical sidebar and top bar. Below 900px, the wordmark returns Home and a native
  expandable account menu contains language, theme and sign-out controls. Only delivered destinations
  appear. No notification bell/badge or placeholder destination is shown.
- `customers.view` admits office Home; `VISIT_VIEW_ASSIGNED` admits My work. With both, office is
  the deterministic default and the user can switch using `?view=field`. An unauthorized requested
  view falls back to the authorized one; no permission means no Home API request. Role names are unused.
- Home reads the browser time zone and consumes the existing server projections, counts, ordering,
  next Visit, overdue and stale-started facts. The technician hierarchy is next → today → attention
  (only when present) → upcoming. Capped sections report server totals. Returning from details reloads
  Home. Refresh, view changes, errors and component destruction clear/cancel prior reads.
- Top-bar identity uses Home's authoritative `viewer.displayName` after that read succeeds, with an
  Account fallback until available (including a direct detail reload). Identity is memory-only and
  bound to the current authenticated user. `/auth/me` provides no profile/organization label, so no
  invented name, organization selection or profile destination is added.
- Home links open `/work/jobs/:jobId?visit=:visitId&view=...`. These browser paths are distinct from
  `/jobs/:id` API paths and work with the existing hosting/service-worker namespace rules. The
  requested Visit is highlighted among the backend-authorized Visit summaries; an unavailable Visit
  receives an explicit message. Job lifecycle, Visit lifecycle, purpose and outcome stay separate.
- Detail reads show customer/property context, description, schedule, crew, outcome and the contact
  block only when the API includes it. Phone/email links and encoded map directions are available
  for supplied values. Missing/reassigned Jobs and forbidden/network errors clear prior content.
  Field writes, activity/evidence browsing and offline business data remain Phase 2.
- Shared public Home types and a read-only Job subset live in `@servora/shared-types`. Existing API
  DTOs extend these contracts for compile-time compatibility; no endpoint runtime, schema or migration
  changes were made. Dev proxy now covers `/home/**` and `/jobs/**` alongside `/auth/**`.
- EN/FR dictionaries cover all new presentation and domain labels, including unknown-code fallback.
  French date locale is registered; Home's API day uses `Intl` with its IANA zone. Android Material
  roles and reduced-motion behavior remain in use. Business reads opt into the auth interceptor
  (Bearer, no-store, worker bypass) and are never persisted or added to service-worker data groups.

### Verification (Tier B: web and API)

| Command | Result |
| --- | --- |
| `npm run lint --prefix web` | PASS |
| `npm run typecheck --prefix web` | PASS |
| `npm test --prefix web` | PASS — 34 tests in 8 files |
| `npm run build --prefix web` | PASS — production PWA |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e --prefix web` | PASS — 19 checks, including office/field reads, mixed permissions, scoped requests, contacts, detail reload/return, forbidden/reassigned states, French dates, session clearing, three viewports and production-worker cache/offline-shell regression |
| `npm run lint --prefix api` | PASS with one existing unused `assignedProperty` warning in `ad-hoc-work-report-options.e2e-spec.ts` |
| `npm run typecheck --prefix api` | PASS — DTO/shared read-contract compatibility |
| `npm test --prefix api` | PASS — 711 tests in 72 files |
| `npm run build --prefix api` | PASS |
| `npm run test:e2e --prefix api` | FAIL — 548 passed / 6 failed in 31 files; see below |
| `git diff --check` | PASS |

Browser and API HTTP/database suites require loopback sockets outside the restricted socket sandbox.
The initial restricted API unit run failed its 12 HTTP adapter tests with EPERM; the rerun outside
that sandbox passed all 711 tests. Browser API responses are fixtures; API integration tests exercise
real PostgreSQL separately. Integration setup applies pending development migrations and removes
its own temporary users. No running development API/container was restarted. Test web servers exited after each run; no task-owned server remains. Generated screenshot/trace artifacts were removed after review.

The full API integration run exposes existing failures outside the type-only DTO additions:

- `job-details.e2e-spec.ts`: two exact Visit-summary expectations omit the already-returned
  `purposeCode: WORK` field (28 other tests pass).
- `job-audio-notes.e2e-spec.ts` and `job-photos.e2e-spec.ts`: four removal authorization expectations
  receive 201 where tests expect 403. These need a separate backend capability/fixture investigation;
  Angular Phase 1 exposes no evidence-removal action and does not change that authorization.

### Remaining acceptance gates

The initial six API failures described above are resolved by the follow-up below.

- [x] Resolve the six API integration failures — corrected and verified below.
- [x] Run a connected browser → actual API office/assigned-user journey — local Chromium acceptance
  passed in the follow-up below. Deployed HTTPS acceptance remains separate.
- Physical iPhone/Safari and installed-iPhone-PWA checks are **DEFERRED BY OWNER** until a device
  is available and do not block phase progression. Firefox/Edge and keyboard/screen-reader review
  remain unexecuted. Chromium covers 390×844, 768×1024 and 1440×900; screenshots were visually reviewed.
- Acceptance targets current stable Safari/iOS in both browser and installed mode, plus current
  Chrome/Edge/Firefox; record exact versions/devices when tested before claiming support. Keep the
  generated Angular browser baseline and HTTPS/Web Locks prerequisite. [Web Locks documentation](https://developer.mozilla.org/en-US/docs/Web/API/Web_Locks_API)
  confirms the secure-context requirement; platform availability is not physical-device acceptance.
- Tracker 063's provider authentication, multi-version update and other outstanding acceptance QA
  remains open. Phase 2 offline working-set/replay design is still a required decision before writes.


## API regression fixes — 2026-10-18

Owner selected fixing the six API failures before proceeding to the next Angular phase.

- Corrected the photo/audio removal route guards: `evidence.photo.add` and `evidence.audio.add`
  no longer admit removal. Each route requires its own dedicated removal capability, restoring the
  existing BR-089 / ADR-018 contract. Upload capabilities, role grants and database schema are unchanged.
- Preserved the original denial regressions, strengthened the photo case with an unrelated audio
  removal grant, and added two positive tests proving removal-only callers need neither upload nor
  read permissions. Tests check that an authorized removal is recorded; existing denied cases verify
  no removal record and that the evidence remains visible.
- Updated two exact Visit-summary tests (three expected Visit objects) to include the existing
  `purposeCode: WORK` field. No response fields were removed or changed to satisfy the tests.

Verification: Tier B, API only. All commands completed:

| Command | Result |
| --- | --- |
| `npm run test:e2e --prefix api -- test/job-details.e2e-spec.ts test/job-photos.e2e-spec.ts test/job-audio-notes.e2e-spec.ts` | PASS — 80 tests in 3 files |
| `npm run lint --prefix api` | PASS — one existing unused `assignedProperty` warning in `ad-hoc-work-report-options.e2e-spec.ts` |
| `npm run typecheck --prefix api` | PASS |
| `npm test --prefix api` | PASS — 711 tests in 72 files |
| `npm run test:e2e --prefix api` | PASS — 556 tests in 31 files; all six original failures resolved |
| `npm run build --prefix api` | PASS |
| `api/node_modules/.bin/prettier --check api/src/jobs/jobs.controller.ts api/test/job-details.e2e-spec.ts api/test/job-photos.e2e-spec.ts api/test/job-audio-notes.e2e-spec.ts` | PASS |
| `git diff --check` | PASS |

HTTP/database tests ran outside the restricted socket sandbox using the existing integration harness.
The harness applies pending development migrations and cleans its temporary records. No development
server was started or restarted; all test processes exited. Browser/device QA was not rerun for these
API-only fixes. Physical iPhone QA remains deferred per the owner decision above.

## Connected browser acceptance — 2026-10-18

Selected next item: close Phase 1's browser → actual API office/assigned-user acceptance gate.
No application behavior, business rules, API contracts, schema or offline policy changed.

- Added an opt-in API integration harness that creates a temporary organization, three users with
  explicit capability grants, and two separately assigned Jobs/Visits in PostgreSQL. It runs NestJS
  on a temporary loopback port and cleans its own records afterward using the existing harness.
- Added a separate Playwright configuration and `npm run test:connected --prefix web`. The production
  SPA uses a test-only same-origin proxy to the actual API; no browser responses are mocked. Normal
  fixture-based browser runs exclude these tests; normal API runs skip the opt-in wrapper.
- Both office and assigned-only journeys verify password sign-in, Home, the selected Visit, direct
  detail reload/session restoration, return Home, sign-out and signed-out reload. The field journey
  also verifies absence of office reads and colleague work, then opens the colleague's Job URL and
  confirms the API denial is presented without its title.
- Generated credentials are passed only to the child process environment. Connected traces,
  screenshots and videos are disabled, and output artifacts are not preserved. The fixture suite
  retains its existing screenshot behavior. Existing development accounts and servers are untouched.

Verification: **Tier B — web and API**. Commands and results:

| Command | Result |
| --- | --- |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:connected --prefix web` | PASS — two real browser/API journeys, one API harness test |
| `npm run lint --prefix web` | PASS |
| `npm run typecheck --prefix web` | PASS |
| `npm test --prefix web` | PASS — 34 tests in 8 files |
| `npm run build --prefix web` | PASS |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e --prefix web` | PASS — all 19 existing checks |
| `npm run lint --prefix api` | PASS — existing unused `assignedProperty` warning remains |
| `npm run typecheck --prefix api` | PASS |
| `npm test --prefix api` | PASS — 711 tests in 72 files |
| `npm run test:e2e --prefix api` | PASS — 556 tests; connected wrapper skipped by default and passed separately above |
| `npm run build --prefix api` | PASS |
| `web/node_modules/.bin/prettier --check api/test/browser-workspace.e2e-spec.ts web/e2e/connected/workspace.spec.ts web/playwright.connected.config.ts web/playwright.config.ts web/e2e/fixtures/static-server.mjs web/package.json` | PASS |
| `git diff --check` | PASS |

Initial restricted PostgreSQL access failed with EPERM; integration/browser checks passed with
loopback access outside that socket sandbox. All task-owned servers exited; generated browser
artifacts were removed. This closes the local connected gate only: deployed HTTPS/provider,
Firefox/Edge, manual keyboard/screen-reader and other tracker 063 acceptance remain open. Physical
iPhone/Safari/PWA testing stays deferred by the owner. Phase 2's offline design decision remains open.


## Workspace browser acceptance — 2026-10-18

Selected next open Phase 1 item: automated language/theme, keyboard, state and responsive acceptance.
Added eight workspace acceptance tests and one responsive keyboard test exercised at all three
viewports (eleven additional browser checks). Application behavior, API contracts, schema and
shell-only offline policy are unchanged.

- Keyboard-only navigation activates the skip link, reaches the main landmark, switches Home,
  opens an assigned Visit, returns Home and signs out. Phone/tablet checks expand the native account
  menu and reach language, appearance and sign-out controls using the keyboard. Native language
  selection updates both the document language and visible Home heading.
- An account without Home capabilities displays the permission message without any Home/Job read.
  Empty technician work and empty Job details render in EN/FR without fabricated assignments,
  contacts or attention. Network, server and forbidden Home responses remove prior work; loading
  announces status and disables duplicate refresh; retry restores content. Detail connection failure
  removes prior contact links and Job content before retry.
- System appearance follows browser color-scheme changes; an explicit preference survives reload.
  Responsive checks retain EN/FR, dark appearance and horizontal-overflow assertions at 390×844,
  768×1024 and 1440×900. Generated desktop/tablet Home and phone detail screenshots were visually
  reviewed. This is bounded automated coverage, not complete accessibility or browser acceptance.

Verification: **Tier B — web only**. Final checks:

| Command | Result |
| --- | --- |
| `npm run lint --prefix web` | PASS |
| `npm run typecheck --prefix web` | PASS |
| `npm test --prefix web` | PASS — 34 tests in 8 files |
| `npm run build --prefix web` | PASS — production PWA |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e --prefix web` | PASS — 30 checks, including all eleven new checks and existing auth/PWA regressions |
| `web/node_modules/.bin/tsc --noEmit --target es2022 --module nodenext --moduleResolution nodenext --esModuleInterop --skipLibCheck web/e2e/workspace-acceptance.spec.ts web/e2e/responsive.spec.ts` | PASS |
| `web/node_modules/.bin/prettier --check web/e2e/workspace-acceptance.spec.ts web/e2e/responsive.spec.ts` | PASS |
| `git diff --check` | PASS |

The initial sandboxed browser run could not bind its loopback server (EPERM); browser checks ran
with local socket access. The first responsive keyboard run used a native-select key sequence
that left the popup open; corrected the test to open/select/close explicitly and reran successfully.
All task-owned test servers exited, and generated browser artifacts were removed after review.
No backend or connected-database rerun was needed for this test-only follow-up.

Remaining acceptance: Firefox/Edge, screen-reader/manual accessibility review, deployed HTTPS,
provider authentication and the other outstanding tracker 063 checks. Physical iPhone/Safari/PWA
checks stay deferred by the owner. Phase 2's persistent browser working set and replay/session
policy still require an explicit design decision before implementation that depends on them.


## Local manager Home routing fix — 2026-10-18

Owner reported successful manager sign-in followed by the generic Home error at localhost:4200.
The running Angular server started before the Home/Job proxy entries were added. Direct probes
returned JSON `401` for `/auth/me`, but SPA HTML `200` for `/home/manager` and `/jobs/example`.
The proxy file on disk was already correct; the development server had not reloaded it.

Restarted only the Angular development server, preserving `SERVORA_API_TARGET=http://localhost:3001`.
The rebuilt server now returns API JSON `401` for all three unauthenticated probes, proving Home/Job
routing reaches the API. The API and database were untouched. Documented the proxy-restart requirement
in the web README. This is a runtime/configuration diagnosis; no application code changed and no
new authenticated manager browser acceptance is claimed. The development server remains running
on port 4200 for the owner's session.

## Workspace presentation redesign — 2026-10-18

Owner rejected the plain manager Job details presentation and selected a visual rework of the
workspace. The redesign covers the existing shell, office/field Home cards and read-only Job details.
It follows the existing Servora steel-blue Material roles and Figma information priorities. No new
business destination, action, API contract, schema, permission or persistence behavior is introduced.

- A compact sidebar and surface header provide consistent workspace navigation, an initial avatar,
  language/appearance controls and sign-out. The native phone account disclosure retains keyboard
  operation with a compact avatar/chevron treatment. System fonts avoid external font dependencies.
- Job details has a breadcrumb, reference/status row, prominent title and customer identity, then
  a desktop two-column layout: overview/visits and location/contacts. Phone uses a single column.
  Visit cards distinguish purpose, schedule, crew, outcome and requested Visit selection. Contact
  people use initials, compact contact links and a separate notes surface. Existing omitted-contact,
  forbidden, reassignment, empty and failure behavior is preserved.
- Home uses surfaced summary panels, a segmented office/field selector, grouped attention rows and
  interactive visit cards with status badges. Counts, classifications and ordering still come from
  the API. Local SVG icons and shared presentation-only status badges are reused across features;
  success colors come from the Android design system. EN/FR labels and light/dark roles are retained.
- Job Visit and contact presentation live in feature-local UI components; Home card styles now
  belong to the card rather than compiling the entire page stylesheet into it. Production styles
  stay within the existing component budgets without increasing limits.
- Responsive browser coverage now exercises a longer Job title/description, multiple contacts and
  a long email address, with EN/light and FR/dark screenshots at all three existing viewports.
  Updated the selection assertion to allow additional styling classes and made the keyboard
  journey wait for the detail h1 rather than matching transient duplicate Home card headings.

Verification: **Tier B — web only**.

| Command | Result |
| --- | --- |
| `npm run lint --prefix web` | PASS |
| `npm run typecheck --prefix web` | PASS |
| `npm test --prefix web` | PASS — 34 tests in 8 files |
| `npm run build --prefix web` | PASS — existing bundle/component budgets, no budget warnings |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e --prefix web` | PASS — 30 checks |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e --prefix web -- responsive.spec.ts` | PASS — 9 checks after the final compact account-menu styling |
| Prettier on changed source/styles/browser specs | PASS |
| TypeScript compilation of the three affected browser specs | PASS |
| `git diff --check` | PASS |

Reviewed generated desktop Home/detail, phone detail and tablet detail screenshots, including light
and dark appearance. Browser tests use fixtures; actual authenticated user visual acceptance is not
claimed. API/database behavior is unchanged, so connected/backend suites were not repeated. Test
servers stop after the run; the existing port-4200 development server remains available. Physical
iPhone testing stays deferred, and outstanding browser/provider/screen-reader acceptance remains
open as recorded above. This work does not begin Phase 2's offline/data-persistence implementation.

## Firefox/Edge browser matrix — 2026-10-18

Selected next unfinished Phase 1 item: automated Firefox/Edge acceptance. Added
`web/playwright.acceptance.config.ts` and `npm run test:acceptance --prefix web`, independently
of the normal Chromium/PWA run. Desktop runs all existing fixture-based auth/workspace journeys;
phone/tablet run responsive and keyboard journeys. Each browser runs 29 checks across
390×844, 768×1024 and 1440×900 viewports. API, schema, application behavior and offline policy
are unchanged; connected API acceptance remains the separately recorded Chromium check above.

Browser evidence: Playwright's instrumented **Firefox 157.0**, revision 1555, Ubuntu 22.04 fallback
on this Linux host; official Microsoft **Edge stable 155.0.4283.62**, extracted into `/tmp` without
system installation. Verified the Edge package's SHA-256 against Microsoft's package index.
These are headless Linux runs, not retail Firefox, other operating systems, installed PWA or
physical-device acceptance. Browser versions are evidence for this run, not permanent support pins.

The first matrix run passed 54 checks and exposed four Firefox keyboard-test assumptions.
Forward Tab does not wrap through browser chrome in this runner, and native select popup keys
vary between browsers. Tests now find controls using Tab/Shift+Tab without assuming wraparound
and change the focused language select with ArrowDown. They still use real keyboard input and
assert focus, language, navigation and sign-out; no DOM focus manipulation or skipped browser
assertions were introduced. Compact-layout assertions now use viewport width rather than the
Chromium project's name. Targeted reruns resolved both Firefox and Edge keyboard differences.

Verification: **Tier B — web only**, after final changes:

| Command | Result |
| --- | --- |
| `npm run lint --prefix web` | PASS |
| `npm run typecheck --prefix web` | PASS |
| `npm test --prefix web` | PASS — 34 tests in 8 files |
| `npm run build --prefix web` | PASS — production PWA, existing budgets |
| `PLAYWRIGHT_BROWSERS_PATH=/tmp/servora-playwright PLAYWRIGHT_EDGE_EXECUTABLE=/tmp/servora-edge/opt/microsoft/msedge/msedge npm run test:acceptance --prefix web` | PASS — 58 checks, 29 per browser |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e --prefix web` | PASS — 30 checks, including production-worker offline shell/cache exclusions |
| `web/node_modules/.bin/prettier --check web/playwright.acceptance.config.ts web/e2e/responsive.spec.ts web/e2e/workspace-acceptance.spec.ts web/package.json` | PASS |
| `web/node_modules/.bin/tsc --noEmit --strict --target ES2022 --module commonjs --moduleResolution node --esModuleInterop --skipLibCheck --ignoreDeprecations 6.0 --typeRoots ./api/node_modules/@types --types node web/playwright.acceptance.config.ts web/e2e/responsive.spec.ts web/e2e/workspace-acceptance.spec.ts` | PASS — browser configuration/spec compilation |
| `git diff --check` | PASS |

Visually reviewed the Firefox phone French/dark Job detail screenshot. Temporary browser/server
processes exited, downloaded browsers and generated test artifacts were removed, and the existing
development servers were untouched. No backend suite was repeated for these test-only changes.

Remaining: spoken screen-reader/manual accessibility acceptance, deployed HTTPS/provider and
multi-version update acceptance. Added a focused
[screen-reader runbook](../../development/web-accessibility-acceptance.md) and documented browser
setup/overrides in the web README. Physical iPhone/Safari/installed-PWA review remains deferred by
the owner. Phase 2's persistent working-set/replay design decision is still open.

## Local rebuild commands — 2026-10-19

Owner requested `make reboot` alongside `make up`/`make down` so Angular changes can be rebuilt
and viewed. The existing commands only managed foundation containers, while Angular ran separately.
Make now combines the base Compose file with `docker-compose.local.yml`: Angular is built in a
Node stage and served by Nginx on loopback port 4200, with same-origin API proxying. The local
API overlay uses the existing development-mode browser-cookie configuration and a matching origin;
base Compose production defaults and application authentication code are unchanged.

- `make up`: build/start the local stack including Angular.
- `make reboot`: `up -d --build --force-recreate`, rebuilding changed sources and recreating the
  services while retaining PostgreSQL and MinIO volumes. Normal Docker layer caching remains on.
- `make down`: remove local containers/network and retain stored data. Existing `down-v` remains
  the explicitly destructive option; `restart` retains its no-rebuild behavior.
- `WEB_PORT` configures the browser port and default WEB_ORIGIN together. A separately running
  host Angular server must be stopped before switching to the same port. Refresh the browser
  after reboot and use the existing PWA Reload prompt when an update is offered.
- The shared Docker build-context allowlist admits only required public web/shared-package inputs,
  excluding local environment files, dependencies and build artifacts. The first image-build attempt
  exposed this host's classic builder ignoring Dockerfile-specific ignore files; using the existing
  root allowlist resolved that compatibility issue.

Verification: **Tier A — local orchestration/hosting**, no application source or schema changes:

| Command/check | Result |
| --- | --- |
| `make -n up down reboot`, `make help` | PASS — correct commands/help, no live stack restart |
| `docker compose -f docker-compose.yml -f docker-compose.local.yml config --quiet` | PASS |
| Resolved Compose JSON assertions with default port and `WEB_PORT=4301`, plus base config | PASS — loopback binding, matching origins and preserved base production mode |
| `docker compose -f docker-compose.yml -f docker-compose.local.yml build web` | PASS — clean dependency installation and production Angular compilation |
| `docker compose -f docker-compose.yml -f docker-compose.local.yml build api` | PASS — cached image remains buildable |
| Isolated temporary Nginx + API-stub container smoke check | PASS — Nginx syntax, built shell, Job deep link, all API namespaces, query/origin/custom-header/method/cookie forwarding, no-store API policy, health, missing assets and worker manifest |
| `npm test --prefix api -- src/auth/browser-auth.config.spec.ts` | PASS — 8 existing browser-configuration tests |
| `git diff --check` | PASS |

The smoke check used a stub API, not authenticated real-provider acceptance. Temporary containers,
network and smoke script were removed. Images remain available for the owner to run `make reboot`;
no existing container or host development server was stopped or restarted during verification.
Setup instructions, web README and environment example were updated. Phase 2 scope is unchanged.

## Local API startup correction — 2026-10-19

Owner reported the session-check connection error after using the new local stack. Actual container
logs showed the API exited at startup: development-mode Pino requested `pino-pretty`, which the
production-only runtime image excludes. Nginx therefore returned `502` for browser refresh. The
previous isolated API-stub hosting check did not exercise real API startup and missed this mismatch.

Added an explicit `development` API image target based on the existing compiled build stage, with
its development dependencies and non-root user. The local Compose overlay selects it. The default
production `runtime` target still installs only production dependencies. A local API health check
now verifies `/health`, and the web service waits for API health instead of mere container creation.
No application authentication/logger behavior, dependencies, schema or data was changed.

Verification: Tier A — targeted local-container correction.

- `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build --no-deps api`:
  PASS — rebuilt and restarted only the failed API; it became healthy against the existing database.
- Real requests through `http://localhost:4200`: `/health` returned `200` with database up;
  `POST /auth/web/refresh` with the correct browser headers and no cookie returned normal `401`
  `REFRESH_TOKEN_INVALID`; `/auth/me` returned `401` `UNAUTHENTICATED`, replacing the earlier `502`s.
- Temporary Chromium check against the actual container site, without mocks or credentials:
  PASS — refresh returned `401`, Welcome back and the email field appeared, and the session error
  heading was absent. This verifies signed-out startup, not an authenticated account journey.
- `docker build --target runtime -f api/Dockerfile .`: PASS — default production target still builds.
- `docker compose -f docker-compose.yml -f docker-compose.local.yml config --quiet`,
  `make -n reboot`: PASS.
- `npm test --prefix api -- src/auth/browser-auth.config.spec.ts`: PASS — 8 tests.
- `git diff --check`: PASS.

The temporary browser and script were cleaned up. The repaired API remains running for the owner;
web, database and storage containers were left running. Refresh the existing browser tab or press
Try again to leave its retained session-error state.

## Phase 1 accessibility follow-up — 2026-10-19

Owner selected continued Phase 1 accessibility checks. Phase 2's offline proposal remains
unapproved; no persistent business data or field writes were enabled.

- Reproduced sign-in's broken skip-link activation: the base URL resolved the fragment to the
  site root, causing navigation instead of focusing the sign-in content. Activation now explicitly
  focuses the current main target without changing the URL. Sign-in, status and session-error/check
  targets have `tabindex="-1"`; session checking retains a main landmark with a nested live status.
- Added focus handling after path navigation renders, preserving focus for query-only Home view
  changes. Home's current-page marker now follows the route, including Home view queries, and is
  absent on Job details.
- Added localized page titles for sign-in, Home, Job details, status and session states instead of
  the generic product-only title. Language changes update the title while retaining user focus.
- Added six browser regressions for EN/FR skip/validation descriptions, invalid-email semantics,
  localized titles, three viewport widths and Home/Job focus/navigation behavior. Required empty
  inputs follow Angular Material's native `aria-required` and error-description behavior; no
  framework override was added merely to require `aria-invalid` on an empty required field.

Verification: **Tier B — web only**, because this touches application-wide navigation accessibility.

| Command | Result |
| --- | --- |
| `npm run lint --prefix web` | PASS |
| `npm run typecheck --prefix web` | PASS |
| `npm test --prefix web` | PASS — 34 tests in 8 files |
| `npm run build --prefix web` | PASS — production PWA, existing budgets |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e --prefix web` | PASS — 36 browser checks including six new accessibility regressions |
| Strict TypeScript compilation of `web/e2e/accessibility.spec.ts` | PASS |
| Prettier on changed web source/templates and the new browser spec | PASS |
| `git diff --check` | PASS |

The initial sandboxed browser server could not bind loopback (`EPERM`); runs used local socket
access. Browser tests use API fixtures, with the existing production-worker test covering shell-only
caching. API/schema/authentication transport and container services were unchanged, so connected
API acceptance was not repeated. Temporary test servers exited; generated browser artifacts were
removed. Existing development/container services were untouched.

The [manual accessibility runbook](../../development/web-accessibility-acceptance.md) remains open
for spoken screen-reader output, zoom and assistive-technology acceptance. No spoken screen-reader
or physical-device acceptance was performed. Firefox/Edge were not rerun in this follow-up;
their prior evidence remains above. Physical iPhone/Safari/installed-PWA checks remain deferred by
the owner. Phase 1's manual acceptance checkbox stays unchecked.

## Phase 2 offline design accepted — 2026-10-19

Owner selected the recommendation to settle the existing offline proposal ("do with your
recommendation"). Recorded the accepted design in
[ADR-027](../../decisions/027-angular-field-offline-workflow.md), with the original proposal linked
to that authoritative decision. This completes the design task; Phase 2 implementation remains pending.

- Adopt scoped IndexedDB field snapshots, durable drafts/pending blobs and foreground replay.
  Keep credentials/identity/permission grants memory-only and service-worker caching shell-only.
- Require online authentication and matching scope after reload/closure. Retain locked unsent work
  across logout; bound unpinned snapshots to seven days and 50 Jobs. Document browser eviction limits.
- Require server-verified scope for both persisted reads and replayed writes. Existing `/auth/me`
  unioned permissions cannot prove draft tenancy. Multiple active memberships remain online-only.
- Source review found status replay returns current Job details rather than an original-operation
  version receipt. After response loss, pause dependent status changes unless the original resulting
  version is established; never silently adopt an intervening writer's version.
- Updated ADR-026's persistence exception, Angular conventions, business rules, architecture links
  and web README. No API endpoint, wire contract, migration or runtime feature is claimed implemented.

Verification: documentation-only targeted checks. Python local-link/whitespace/status assertions
passed for all eight affected files and 48 local links before this entry; `git diff --check` passed
for the same paths. Rechecked the completed documents including this entry. No runtime tests/builds
were repeated because application code and configuration did not change. No services were started,
stopped or restarted; existing owner services and unrelated working-tree changes were preserved.

Next implementation task inside Phase 2: specify the additive field-context/scoped-request contract,
then implement server scope enforcement and real API membership-change/cross-tenant compatibility
tests before enabling browser persistence. Phase 1 manual accessibility and deployed/provider
acceptance remain open; physical iPhone testing retains its existing deferral.

## Phase 2 implementation — 2026-10-19

Implemented the server scope foundation first, then the assigned field workflow on that contract.
The owner authorized this implementation with “implement server-verified field context and scope
checks, then build Phase 2 on that foundation.” No PostgreSQL migration or dependency was added.

- `GET /auth/field-context` verifies exactly one active membership with `VISIT_VIEW_ASSIGNED`.
  The three field headers assert user/organization/membership on every persisted read and command.
  Scoped requests recheck membership/capabilities inside the handler transaction and use field reach
  even for mixed office/field members. Visit locks protect assignment checks during execution;
  existing service transactions become savepoints through the request's database context. Header-free
  Android/office requests keep their existing contracts. See [wire contract](../../api/field-scope.md).
- Added assigned Visit selection/details, customer/property/crew context and contact/directions,
  localized activity, permitted transitions and purpose-specific completion outcomes. Notes, photos,
  supported MP4/AAC recording/file selection, review, phases, submission and accepted-media viewing/
  playback are visit-scoped. MIME aliases require a proved MP4 header; unsupported/oversized pending
  bytes remain available for review. Camera/microphone permission and physical recording behavior
  remain device acceptance work.
- Added online follow-up submission and per-request clarification editors/replies, plus durable
  ad-hoc reporting through the manual customer-details path. Office review/management stays in its
  existing later phase; the connected test uses the API's existing review action to prove the reply.
- Added scoped IndexedDB snapshots, atomic drafts/blobs, immutable commands, dependency ordering,
  bounded retries/Retry-After and foreground replay under one cross-tab Web Lock. Snapshot limits are
  seven days and 50 unpinned Jobs; pending context is pinned. Explicit refusals invalidate snapshots
  and block pending work. Response-lost statuses retain their original payload/id and stop dependent
  work for explicit online review, including when an intervening writer changed the returned version.
- Logout/account/scope changes lock work and clear read/accepted payload data; minimal receipts
  required by pending dependencies survive. Cold offline reload cannot unlock work. Quota or schema
  failures never recreate the database or silently erase drafts. Navigation/update checkpoints save
  editors or refuse reload. Office business data and accepted evidence bytes remain online-only;
  service-worker data caching is unchanged and remains shell-only.

Verification and the remaining owner device checks are in the
[field acceptance runbook](../../development/web-field-acceptance.md). Physical iPhone/Safari and
installed-PWA acceptance remain deferred under the existing owner decision. Phase 1 manual
accessibility and deployed/provider acceptance also remain open. This implementation is not a claim
of physical field readiness. No deployment or owner-service restart was performed.

Executed verification for this implementation:

- API unit suite: **713 passed** across 73 files. API typecheck/build passed; lint passed with the
  pre-existing unused `assignedProperty` warning in `ad-hoc-work-report-options.e2e-spec.ts`.
- Affected real-PostgreSQL integration suite: **127 passed** across six files (scope, existing Visit
  lifecycle/Home, photo/audio and ad-hoc compatibility).
- Web unit suite: **47 passed** across 12 files; typecheck, lint and production build passed.
- Full Chromium Playwright suite: **45 passed** (desktop, phone, tablet and service-worker/PWA project).
  The nine field recovery/responsive cases were rerun after the final pending-work review layout change.
- Connected browser/API/PostgreSQL harness passed both office and full field journeys, including
  actual MP4/AAC playback, follow-up clarification/reply and ad-hoc submission. Object storage used
  the existing in-memory test port; deployed-provider and physical-device acceptance are not inferred.
- Documentation local links and `git diff --check` passed. No Android/device command was run.

## Phase 3 customer management — 2026-10-19

Owner asked to “start working on next item” after Phase 2 delivery. Started the next roadmap phase
with its first customer-management workflow; this does not authorize unrelated backlog features or
claim that all of Phase 3 is complete. Existing physical-iPhone deferral remains in effect.

Delivered:

- Customers destination in the desktop sidebar and phone/tablet account menu; localized page titles,
  current-page navigation, responsive theme-aware layouts and the existing navigation focus behavior.
- Existing server status/work filters and in-memory search by display name/email/phone. Detail reads
  show the subtype, general contact information, optional notes, contact people, customer Jobs linked
  to the existing office details, and active property addresses under `properties.view` only.
- Customer create/edit, ACTIVE/INACTIVE editing, and explicitly confirmed individual/company
  conversion. The form sends exactly the selected subtype, never derives one subtype from the other,
  and omits unmanaged billing fields. Archiving confirms removal from ordinary use and explains that
  no customer restore action exists; INACTIVE is not presented as archiving.
- Contact add/edit/primary selection/removal under their independent capabilities. Contact edits and
  removals send the read version; conflicts preserve the editor and block resubmission until explicit
  review/reload. Role and billing/job flags are not invented as office form fields or overwritten.
- Online-only office requests through the existing authentication/no-store/worker-bypass interceptor.
  No schema, migration, dependency, API runtime behavior, field storage or Android behavior changes.
  Shared customer response contracts are exported from `@servora/shared-types` and API DTOs extend
  them for compile-time compatibility. Uncertain writes preserve input and block automatic resend.

Remaining inside Phase 3:

- Property details and create/edit/lifecycle workflows, followed by Job discovery/create/full office
  actions and authorized audited evidence removal. The current customer Jobs list uses the existing
  complete customer projection; no nonexistent organization-wide Job list API is assumed.
- Standalone customer-address management needs a public contract review: an internal address DTO
  exists, but the controller exposes no address read/write routes. Do not infer such a contract from
  the Figma prototype. Customer-associated property addresses are already shown when authorized.
- The complete real customer → property → Job acceptance journey and remaining manual accessibility
  checks. The Phase 3 checklist remains open until its whole workflow is delivered.

Verification: **Tier B for the web workflow; targeted contract/connected verification for the API**.

| Check | Result |
| --- | --- |
| Web lint, typecheck and production PWA build | PASS — existing bundle/style budgets |
| Full web unit suite | PASS — 50 tests across 13 files |
| Full Chromium Playwright suite | PASS — 54 checks, including nine new customer cases, phone/tablet keyboard navigation, existing field recovery and shell-only worker caching |
| Strict TypeScript compilation of the new browser specs/fixture | PASS — Node types supplied from the existing API toolchain |
| API typecheck, build and lint | PASS — existing unused `assignedProperty` warning remains in `ad-hoc-work-report-options.e2e-spec.ts` |
| Customer DTO/contact/detail/filter unit checks | PASS — 36 tests across four files |
| Connected browser/API/PostgreSQL harness | PASS — new phone-width customer create/contact create/edit/remove/type conversion/archive journey with session reload and both existing office/field journeys; three Playwright cases within one integration harness |
| Documentation links and whitespace | PASS — two documents, 34 local links; `git diff --check` |

The full browser run initially exposed two account-menu keyboard checks that assumed Language was
the first item. They now verify Customers before Language in the new tab order. The customer filter
check also exposed an ambiguous implicit label; native selects now receive explicit localized
accessible names. Preserved editors remain selectable/copyable after a conflict or uncertain result,
while Save stays blocked until review. The final complete browser run passed after these corrections.

Browser servers required local socket access outside the filesystem sandbox. The connected harness
used isolated fixture records and removed its own test data. It did not rebuild/restart the owner's
running containers or deploy the change. No physical-device, spoken screen-reader, Firefox/Edge,
real-provider or deployed-host acceptance was added by this task. Test servers/browsers were stopped
by their runners; generated test outputs and task scratch scripts were removed. Existing owner
services were left untouched.


## Phase 3 property management — 2026-10-19

Owner selected the next Angular item after the customer workflow. Implemented the next existing
Phase 3 checklist item, property management within customer context, without adding a phase or
changing the domain model. The accepted web decision keeps office reads/writes online-only;
Android's archive/restore outbox behavior is unchanged.

Delivered:

- Active/Archived/All property filters in customer details, links to property details, and an
  independent Add property action. Returning from archived details selects Archived and reloads
  the customer header/counts. Property filtering does not discard an open contact editor.
- Lazy property detail/create/edit routes under `/work/customers/:customerId/properties/…`.
  Names/addresses, optional notes, service dates, archive state and customer-scoped Job history use
  existing projections. Job history retains the Job's address snapshot when the Property changes.
- Structured Canadian address forms, stable province codes with localized names, existing limits,
  nonblank required fields, and no invented country/type/access-instruction fields. Create accepts
  the actual summary response and then reads details only with `properties.view`. A create-only
  account receives an accepted-write receipt without an unauthorized follow-up read.
- `properties.*` capabilities govern each action independently. Direct property reads need no
  customer-wide capability; optional customer headers and customer Job history are not requested
  without `customers.view`. Customer edit grants no property action. Edit requires view plus edit.
- Edits and lifecycle actions send the read version. A conflict preserves editable/copyable input
  and blocks resubmission until explicit review/reload. Archived edits keep their lifecycle state.
  Archive/restore have explicit confirmation; archive shows server counts for active Jobs/Visits
  and explains the effect on future versus existing work. Lifecycle calls carry an operation UUID.
- Permanent deletion is offered only with its capability and the API's eligibility flag. Ordinary
  customer-associated Properties cannot meet that precondition because their relationship is a
  business reference. Confirmed deletion still handles a concurrent `PROPERTY_HAS_REFERENCES` refusal
  and offers archive after fresh review; the client never computes eligibility or bypasses the API.
- Loading/error/unavailable states, EN/FR, phone/tablet layouts and theme roles. Unknown write results
  preserve input/review state and block duplicate sending. All requests use existing same-origin
  authentication, no-store and worker bypass. No property/office commands enter field storage.
- Additive shared Property wire types, with existing API DTOs extending the shared shapes for
  compile-time compatibility. No API runtime, schema, migration, dependency or Android change.

Remaining Phase 3 work: Job discovery/create/full office actions and authorized evidence removal,
the full customer → property → Job journey, and the standalone customer-address contract gap already
recorded above. Physical iPhone acceptance keeps the owner's existing deferral; spoken accessibility
and deployed/provider acceptance remain open.

Verification: **Tier B for the web workflow; targeted Property contract and connected verification for the API**.

| Check | Result |
| --- | --- |
| Web lint, typecheck and production PWA build | PASS — existing bundle/style budgets |
| Full web unit suite | PASS — 54 tests across 14 files |
| Full Chromium Playwright suite | PASS — 68 checks, including 14 new Property cases, existing customer/field/authentication flows and phone/tablet/PWA checks |
| Strict TypeScript compilation of new Property browser specs and fixture | PASS — existing Node toolchain types |
| Property DTO unit checks | PASS — 27 tests |
| Real-PostgreSQL Property lifecycle, customer authorization and customer projection suites | PASS — 46 tests across three files |
| API typecheck, build and lint | PASS — pre-existing unused `assignedProperty` warning remains |
| Connected browser/API/PostgreSQL harness | PASS — four browser cases in one integration wrapper: Property lifecycle/assigned-only denial, customer lifecycle, and existing office/field journeys |
| Local documentation links and whitespace | PASS — 34 links in two documents; `git diff --check` |

The first full browser run exposed a test navigating to the lazy Add property page and checking a
customer name before the previous customer page was removed. The assertion now waits for the new
page heading before checking its customer context. Review also found and reproduced an editor reset
when the authentication interceptor refreshed credentials and its safe 401 retry then lost the
connection. Property-form loading now depends on permission results and the actual session boundary,
so unchanged-capability refreshes preserve input; real boundary/permission changes still invalidate
reads. The new recovery regression verifies retained address/province values and blocked resubmission.
The final complete suite passed after both corrections.

All tests used temporary browser/API servers or isolated database fixture records; the harness removes
its own records. No deployment, owner-container rebuild/restart or Android/device operation was
performed. Physical iPhone, spoken screen-reader, Firefox/Edge and deployed/provider acceptance were
not repeated. Test runners stopped their temporary servers/browsers, and task scratch/generated test
outputs were removed. Existing owner services were preserved.



## Phase 3 Job management — 2026-10-19

Owner selected the next item after Property management. Delivered the Job-management workflow
inside the existing phase, using the current domain, API contracts and permissions. No schema,
migration, dependency, Android or API runtime behavior was changed.

Delivered:

- Discover Jobs through customer Job lists, property history and Home. Added customer-scoped search
  over returned Job number/title/description. There is no organization-wide Job list/search route;
  this surface uses the existing complete customer projection rather than inventing one.
- New Job from a customer or active Property, with the Property preselected when supplied. The form
  requires `JOB_CREATE` and `properties.view` for its active-property picker. Optional customer context
  is read only with `customers.view`; users without it receive a creation receipt without unauthorized
  detail reads. The server owns NEW status, Job number and address snapshot; no Visit/crew/schedule
  is inserted by the client. Property availability remains authoritative at submission time.
- Office details link customer/property context, retain Visit summaries and show derived attention
  separately from lifecycle. `JOB_UPDATE` offers the server's structural destinations and submits
  one versioned action after confirmation. Completion/refusal, reopen and cancellation remain distinct
  Job operations. Cancellation is a dedicated action with a required explanation, names open Visits
  and their crews, and explicitly confirms stopping work already underway. The existing API retains its
  optional-note contract for compatibility; the browser enforces BR-064’s explanation requirement.
  The undefined structured reason catalogue is not invented.
- Activity shows actor/time, Visit, outcomes, notes and photo/audio phase, with on-demand authorized
  photo viewing and MP4 audio playback. Object URLs are revoked on close, refresh, navigation and
  session/permission change. The localized activity label is shared presentation; office reads never
  enter the field working set or replay engine.
- Dedicated photo/audio removal capabilities require confirmation and a nonblank reason. Ordinary
  activity is reread after removal; authorized audit mode shows retained removal history/reasons.
  Removed media bytes are not offered through ordinary content routes. No evidence deletion or edit
  behavior is invented.
- EN/FR, phone/tablet/desktop, theme roles, loading/unavailable/forbidden/retry states. Conflicts and
  uncertain results retain inputs/submitted explanation and block repeat submission until explicit
  review. Credential refresh with unchanged capabilities does not reset the creation editor. Parent
  refresh is disabled while an office action is in progress. All office requests remain online-only,
  memory-only, authenticated, no-store and excluded from service-worker response caches.
- Additive shared Job create/status/read/activity declarations with compile-time checks from existing
  API DTOs; no endpoint response or authorization behavior changed. The connected fixture grants the
  independent Job/evidence capabilities and initializes its Job-number counter after its hand-built Jobs.

Phase 3 remains open for the standalone customer-address public contract decision recorded above.
Planning, scheduling/crew management, office follow-up/ad-hoc review and quote decisions remain
Phase 4. Manual spoken accessibility, deployed/provider acceptance and physical iPhone deferral retain
their existing status. No deployment, owner-service restart or Android/device operation was performed.

Verification for this slice: **Tier B web workflow; full API unit/compile checks and targeted real-DB
contract suites**, because the API changes are compile-time declarations plus the connected fixture.

| Command/check | Result |
| --- | --- |
| `npm run lint --prefix web`, `npm run typecheck --prefix web`, `npm test --prefix web` | PASS — 59 tests in 15 files |
| `npm run build --prefix web` | PASS — production PWA; initial bundle 526.76 kB exceeds the 500 kB warning budget by 26.77 kB; error budget unchanged |
| `npm run lint --prefix api`, `npm run typecheck --prefix api`, `npm run build --prefix api` | PASS — existing unused `assignedProperty` lint warning remains |
| `npm test --prefix api` with local socket access | PASS — 713 tests across 73 files |
| `npm run test:e2e --prefix api -- test/job-create.e2e-spec.ts test/job-actions.e2e-spec.ts test/job-activity.e2e-spec.ts test/job-details.e2e-spec.ts test/job-photos.e2e-spec.ts test/job-audio-notes.e2e-spec.ts` | PASS — 137 tests across six files using real PostgreSQL |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e --prefix web -- --workers=2` | PASS — 82 checks including 11 Job cases, existing field/auth/customer/property journeys, responsive layouts and shell-only PWA caching |
| `RUN_CONNECTED_BROWSER=1 PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e --prefix api -- test/browser-workspace.e2e-spec.ts` | PASS — five connected browser cases within one integration wrapper: real customer → property → Job lifecycle/denial at 390px and 1440px, phone customer/contact management and existing office/field journeys |
| Strict TypeScript compilation of Job specs/fixture and connected journey | PASS |
| Prettier, `git diff --check`, local documentation links | PASS — 34 local links across two documents |

Sandbox-only HTTP/browser/database runs initially refused loopback sockets (`EPERM`); reruns used
local socket access. An initial overloaded concurrent browser run stalled during application startup;
two-worker execution resolved it. A removal regression initially checked role visibility during the
Material dialog exit animation, before `afterClosed` initiated the write; it now waits for the actual
removal request and resulting activity. The real-DB fixture initially occupied Job numbers 1–2 without
initializing the creation counter; its counter now starts at 2, matching those fixture records.

The final connected matrix passed on phone and desktop after the phone test was updated to open
and close the existing compact Customers navigation menu. It used real browser authentication,
API authorization and PostgreSQL, with isolated records removed by the harness. The browser fixtures
remain separate from this connected evidence. Test servers/browsers exited; existing owner services
were left running. Full API e2e outside the six affected suites was not repeated. Firefox/Edge,
spoken screen-reader, physical-device and deployed-provider acceptance were not performed here.

Final focused check: `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/opt/google/chrome/chrome npm run test:e2e
--prefix web -- jobs.spec.ts --grep 'customer to property' --workers=1` passed, including the explicit
assertion that cancellation confirmation is disabled without its explanation. Task scratch files
and generated test outputs were removed; documentation links and whitespace passed after the update.

## Phase 3 customer-address contract review — 2026-10-19

Owner selected the next Angular item. Reviewed the remaining Phase 3 address gap before starting
Phase 4. **Review complete; product decision pending.** This entry does not approve a deferral,
change the Phase 3 checklist or claim additional acceptance.

Findings:

- [Foundation domain model](../../domain/foundation-domain-model.md#9-addresses) permits zero or
  more customer addresses, with stable `SERVICE`, `BILLING` and `OTHER` types. These records are
  separate from Properties; Jobs use Properties and preserved snapshots. Customer creation and
  the delivered customer → Property → Job journey do not require a customer-address record.
- `api/src/database/schema.ts` already has `customer_addresses`, including at most one default
  `SERVICE` and one default `BILLING` address per Customer. There is no row version or address
  removal/history model. Existing data must be preserved whichever option is selected.
- `customer-address.dto.ts` validates creation and maps rows; `CustomersService.addAddress` and
  `listAddresses` exist internally. `CustomersController` exposes neither operation, and the
  [public customer API](../../api/customers.md) defines no standalone address workflow. The
  insert helper alone does not implement transactional default replacement or a public conflict
  contract. Internal code is not a safe browser integration contract.
- The Property redesign separates customer contact/billing addresses from service locations.
  It does not settle address edit/removal, default replacement, concurrency or historical retention.
  Existing Property screens already cover the operational service-address requirement.

Recommendation for owner decision: **defer standalone customer-address management from the initial
web release** and retain the existing Property workflows. Billing/payments are already backlog,
and a separate address book is not required to plan or perform the delivered service workflow.
If approved, record the explicit scope decision here, qualify Phase 3's customer checklist to cover
Property addresses, and select Phase 4's schedule/crew workflow next. Manual accessibility and other
acceptance gates remain open; this would complete implementation scope only.

Alternative: retain standalone customer addresses in Phase 3 and deliver an API + Angular vertical
slice before Phase 4. The proposed workflow is list/add/edit/remove customer addresses, with
`customers.view` for reads and `customers.edit` for writes, explicit default selection per supported
type, server-controlled concurrent-write handling, and no effect on Properties or historical
Job/Visit snapshots. Before implementation, settle default replacement and removal/history rules,
including BR-057, and specify the backward-compatible public contract and any schema impact.
Verification would cover real PostgreSQL tenancy/authorization/default races, EN/FR browser forms,
conflicts, uncertain writes and phone/desktop journeys. These are proposed decisions, not approved
behavior.

No runtime code, public contract, schema or existing records were changed by this review.

## Phase 3 scope decision and Phase 4 selection — 2026-10-20

Owner approved deferring standalone customer billing/contact address management and proceeding with
scheduling. Existing customer-address records remain preserved. Property service-address workflows
continue to provide operational locations. Standalone address management remains backlog; its public
contract, default replacement, concurrency and removal/history rules must be settled when selected.
This closes Phase 3 implementation scope, with earlier manual acceptance gates still open.

The office schedule read and unassigned-work view is implemented, tracked in
[tracker 068](068-office-schedule.md). Rescheduling, crew reassignment and other Phase 4 follow-through
remain separate implementation tasks; the Phase 4 checklist is not complete.

## Desktop Office Schedule redesign — 2026-10-20

Owner requested a full-width desktop dispatch grid and compact table to replace the initial office
agenda layout. Delivered within the existing Phase 4 schedule task: technician columns, exact time
placement, returned-interval overlap visualization, shared-Visit highlighting, a compact List view and
responsive agenda. Existing API scope and online-only behavior remain in force. See
[tracker 069](069-desktop-dispatch-redesign.md) for contract limits and verification evidence.
Rescheduling, crew writes and later Phase 4 follow-through remain separate work.
