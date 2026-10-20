# Tracker 063 — Angular foundation and authentication

**Status: IMPLEMENTED — foundation and authentication delivered; automated verification passed. Cross-browser, installation/update and real-provider acceptance QA remain open.**

Date: 2026-10-18
Source: product-owner request for a client-rendered, responsive, mobile-friendly Angular PWA,
English/French, Android brand colors, and RBAC; first slice is email/password or phone/SMS OTP authentication.

## 1. Scope and ownership

The Angular application is the management/office client of the existing Servora API and database.
Implement a client-rendered SPA; SSR is not required by this slice. Foundation work is limited to
what is needed to deliver and verify authentication. No business-data migration is expected.

| Capability | Classification | First-slice boundary |
| --- | --- | --- |
| Angular foundation | MVP | Strict TypeScript, standalone components, signals, router, HTTP client, Angular Material/M3, build and test commands |
| Responsive UI | MVP | Usable sign-in and authenticated shell on phone, tablet and desktop |
| Bilingual UI | MVP | English/French, language selection before sign-in, persisted language preference, localized validation and errors |
| Android visual parity | MVP | Shared steel-blue, neutral surface and semantic tokens; light/dark theme mapping |
| PWA | MVP | Manifest, icons, installable production build, service-worker app-shell cache, explicit offline/update states |
| Authentication | MVP | Existing email/password and phone/SMS OTP API flows, current permission read, session refresh and sign-out |
| RBAC | MVP | Effective permission state, protected navigation and permission-aware UI; API remains the security boundary |
| Password reset | BACKLOG for this first slice | Existing Android/API flow is available; no nonfunctional reset link in the initial UI |
| Management feature screens and role administration | BACKLOG | Select subsequent vertical slices separately |
| Offline business data/sync and browser push | BACKLOG | PWA does not imply Android's offline working set or outbox |
| Browser session persistence mechanism | MVP — implemented | Same-origin HttpOnly refresh cookie, memory-only access/identity state; [ADR-026](../../decisions/026-angular-foundation-and-browser-sessions.md) |

## 2. Initial repository scan (before implementation)

This table preserves the initial source-inspection snapshot. The delivered implementation and actual
verification results are recorded in section 6 below. The working tree already contains extensive API/Android feature changes;
preserve them and keep this milestone separate.

| Area | Existing evidence | Missing work / consequence |
| --- | --- | --- |
| Angular workspace | No Angular workspace, `angular.json`, browser package or web deployment configuration in this repository | Establish workspace, pinned supported toolchain, scripts, routing, environments and browser test setup |
| Engineering rules | `.clinerules/Project.md` defines Angular/Material, strict TS, signals, standalone components and Playwright | Referenced `.clinerules/Angular.md` is absent; document web-specific conventions |
| Shared TypeScript contracts | `@servora/shared-types` and `@servora/shared-config` are required by project rules but absent | Introduce the minimum auth wire types/error codes shared by API and Angular; keep database/domain types server-only |
| Authentication API | `api/src/auth/auth.controller.ts`, request parsers and auth services implement password/SMS authentication, refresh, sign-out and `/auth/me` | Build browser adapter against existing endpoints; do not introduce a second identity provider |
| Web platform | `AUTH_SESSION_PLATFORMS` and request parsers already accept `WEB` | Send `platform: WEB` and a non-secret installation identifier with both sign-in methods |
| Session credentials | API returns access/refresh tokens in JSON and accepts Bearer access tokens; refresh rotates tokens | Browser storage, reload restoration, concurrent requests/tabs, revocation and logout races need their own design |
| Android behavior | `AuthRepository`, `AuthFlowScreen`, SMS/sign-in ViewModels, `SessionManager`, `SessionStore`, `SessionAuthenticator` | Reuse product semantics; Android Keystore storage cannot be transferred to the browser |
| RBAC | `/auth/me` returns `userId` and effective permissions; API guards enforce permissions | Browser permission service, route/action checks and forbidden state; do not infer permission from role names |
| Organization context | Current `/auth/me` has no selected organization, membership list or office-role identity; auth permissions aggregate active memberships | Do not invent an organization selector or a manager-only sign-in restriction; design tenant selection with the first feature that requires it |
| Browser API access | `api/src/main.ts` does not configure CORS; current deployment is API/Android only | Define dev proxy and production routing; use restricted origin configuration if cross-origin access is selected |
| Design | `docs/design/android-design-system.md` and Android `ui/theme/Color.kt`, `Theme.kt` contain shared tokens | Implement Angular Material theme mapping; the design doc's former `apps/web/src/styles.scss` claim was stale |
| Localization | Android EN/FR resources and stable API error codes exist | Choose extensible Angular translation approach, define dictionaries and language fallback |
| PWA | No web manifest/service worker/browser assets | Configure shell caching and update lifecycle; exclude auth and protected API data from caches |
| Verification | API auth/password-reset/SMS and authorization tests; Android auth/session tests exist | Add focused browser flow tests, session/guard tests, responsive/a11y checks and production PWA verification |

Relevant source locations:

- [Authentication API contract](../../api/authentication.md)
- [Authentication domain model](../../domain/authentication-domain-model.md)
- [Android session decision](../../decisions/009-android-session-persistence.md)
- [Android session tracker](../android/009-android-session-persistence.md)
- [Design tokens](../../design/android-design-system.md)
- [Project rules](../../../.clinerules/Project.md), [business rules](../../../.clinerules/Business%20Rules.md)

## 3. Authentication behavior

### Email and password

1. Show email/password fields, password visibility control and a phone-sign-in alternative.
2. Validate shape locally for usability; preserve password characters exactly.
3. Call `POST /auth/sign-in` with the existing device block and `platform: WEB`.
4. Establish the session, read `GET /auth/me`, and enter the authenticated shell only after the
   required identity/permission state is available. A failed read has a retry/error state;
   it does not grant default permissions.
5. Map stable error codes into EN/FR messages. Unknown account, incorrect password and ineligible
   account remain indistinguishable. Disable duplicate submissions and expose loading/failure states.

### Mobile number and SMS OTP

1. Enter a phone number with country code; use the existing E.164 API contract.
2. Call `POST /auth/sms/request`. Its generic `202` advances to the code step without claiming
   an account exists or a message was delivered.
3. Enter the six-digit OTP as text (preserve leading zeros); allow paste/autofill and number correction.
4. Verify through `POST /auth/sms/verify` with the same web device context, then use the same
   session/permission initialization as password sign-in.
5. The server owns the ten-minute validity, five failed attempts, supersession, one-time use,
   rate limits and sixty-second resend cooldown. A countdown is advisory; handle `429` explicitly.
6. Changing the number clears the previous code. OTP verification authenticates an existing ACTIVE
   account; it does not register a new user or add a second-factor requirement.

### Session and authorization

- Use an explicit startup checking state, then signed-out/authenticated/error states. Do not flash
  protected content or the sign-in form before restoration is resolved.
- Refresh expired access tokens through `POST /auth/refresh`; serialize refreshes and retry a
  protected request at most once. Authentication endpoints must not recurse through refresh logic.
- Invalid/revoked refresh credentials end the local session. Network failure is distinct from
  rejected credentials and must not appear as successful fresh authentication.
- Handle late refresh/sign-in responses after logout or account switching; they must not restore
  a cleared session. Define cross-tab coordination if the selected storage shares credentials.
- Clear local identity, permissions and protected state on sign-out; attempt backend revocation
  using the outgoing session. If the network prevents revocation, do not claim the server revoked it.
- Separate `401` (authentication recovery) from `403` (authenticated but forbidden). A forbidden
  operation does not trigger a sign-in loop.
- Read effective permissions from the backend. Unknown permissions fail closed. Route guards and
  UI visibility complement API authorization; no role-name authorization or client-generated grants.
- After sign-in use a minimal authenticated landing shell. A full manager dashboard is another slice.

## 4. Browser design checklist — resolved in ADR-026

The implementation decision is [ADR-026](../../decisions/026-angular-foundation-and-browser-sessions.md),
covering the following design checklist:

- Workspace location and tooling: proposed `web/` alongside `api/` and `android/`, with only the
  shared packages required for auth. Confirm supported dependency versions against official docs
  at implementation time; this tracker does not select a framework version.
- Credential storage and reload persistence: compare the existing Bearer/JSON contract with a
  cookie-backed approach. In-memory credentials need an explicit reload/sign-in policy; persistent
  JavaScript-readable refresh tokens carry an XSS tradeoff. HttpOnly cookies require deliberate API,
  CSRF, origin and logout changes. Do not silently introduce a BFF/server-rendered application.
- Dev proxy and production same-origin routing versus restricted cross-origin API access; preserve
  Android contracts and paths (`/auth/...`, without a new `/api/v1` prefix).
- EN/FR translation mechanism, initial-language fallback, user preference and runtime switching.
- PWA service-worker scope, shell-only cache rules, update prompt and offline signed-out behavior.
  Credentials, OTPs, auth responses and protected API responses must not enter service-worker caches.
- Supported browsers and phone/tablet/desktop QA targets.

These are design tasks for the selected slice, not missing identity/business functionality.
Any approved API change must update the shared contract and retain Android compatibility.

## 5. Delivery checklist

### Phase 0 — Scan and tracker organization

- [x] Inspect rules, API auth contracts/code, Android auth/session flow, colors and existing tests.
- [x] Record missing browser infrastructure and browser-specific decisions.
- [x] Organize trackers by application/shared ownership; preserve IDs and update references.
- [x] Capture first-slice scope and acceptance criteria.

### Phase 1 — Reviewable web foundation design

- [x] Record the decisions in section 4, including session persistence and API impact.
- [x] Define responsive auth layout using the existing brand tokens and EN/FR content.
- [x] Document web engineering conventions and selected toolchain.

### Phase 2 — Angular foundation

- [x] Create the strict standalone Angular workspace, Material theme and minimal shared contracts.
- [x] Add router, API configuration/dev proxy, localization and theme/language preferences.
- [x] Add responsive auth layout, authenticated shell and accessible loading/error/forbidden states.
- [x] Add manifest, icons, service worker and production hosting/deep-link instructions.

### Phase 3 — Authentication and permission context

- [x] Implement email/password and phone/OTP flows against the existing API.
- [x] Implement the reviewed session storage/restoration, refresh, logout and race handling.
- [x] Read `/auth/me`; add permission state, guards and permission-aware UI.
- [x] Verify existing API enforcement with real authenticated/forbidden requests.

### Phase 4 — Verification and handoff

- [x] Type check, lint and production build; focused session/interceptor/permission/translation tests.
- [x] Playwright: password sign-in, SMS request/verify/cooldown, generic failure messages and `429`. Timed resend delivery remains provider QA.
- [ ] Verify reload restoration, expiry/rotation, concurrent `401`s, refused refresh, offline failure,
  logout during refresh, account switching and cross-tab behavior required by the selected design.
- [x] Verify deep links, `403`, denied actions and no permission/content leakage after sign-out.
- [ ] Check EN/FR, light/dark colors, phone/tablet/desktop, keyboard navigation, focus and form labels.
- [ ] Verify production service worker, installation where supported, updates and offline shell;
  inspect caches to prove auth/protected data are absent.
- [x] Update setup instructions and tracker with exact commands/results and remaining manual QA.

## 6. Implementation milestone — 2026-10-18

Delivered a strict, standalone, zoneless Angular 22.2.3 workspace in `web/`. The feature-based
structure separates app composition, core browser infrastructure, feature-owned auth/session logic,
layout, and demonstrated shared UI. ESLint enforces core/shared dependency direction; no generated
demo, empty business feature folders, root workspace conversion, or additional state framework.

- Responsive email/password and phone/SMS forms; six-digit text OTP, generic eligibility/errors,
  number correction, loading/duplicate-submit handling, advisory cooldown and rate-limit messages.
- Typed runtime EN/FR dictionaries; pre-sign-in language selection, persisted preference and html lang.
- Material 3 role mapping from Android; system/light/dark preference.
- HttpOnly browser refresh adapter at `/auth/web/*`; memory-only access/identity state, startup retry,
  `/auth/me` initialization, serialized refresh and one protected-request retry, persistent non-secret
  logout intent, epoch/generation race cancellation and cross-tab invalidation. Existing Android
  JSON/Bearer endpoints retain their contracts; no schema migration added.
- Shared auth declarations and permission-code catalogue in `packages/shared-types`; strict browser
  defaults in `packages/shared-config`. Unknown permission codes fail closed, even if returned by a
  newer server. Guards and action-visibility directive use effective permission state.
- Manifest/icons, shell-only production worker, update prompt and explicit connection/retry states.
  Production-worker cache inspection found no auth/protected API entries.
- Same-origin dev proxy, exact WEB_ORIGIN validation/CSRF guard, API Docker shared-package support,
  Makefile targets and documented HTTPS production proxy/deep-link configuration.

Implementation references: [web README](../../../web/README.md),
[Angular conventions](../../../.clinerules/Angular.md),
[ADR-026](../../decisions/026-angular-foundation-and-browser-sessions.md),
[browser API contract](../../api/authentication.md#browser-cookie-adapter-angular-adr-026).

### Executed verification

Environment: Node 24.20.0, npm 11.19.0, Angular/Material/CLI 22.2.3, web TypeScript 6.0.3.

| Command | Result |
| --- | --- |
| `npm run lint --prefix web` | PASS — TypeScript/templates and dependency boundaries |
| `npm run typecheck --prefix web` | PASS — strict TS and templates |
| `npm test --prefix web` | PASS — 27 tests in 5 files |
| `npm run build --prefix web` | PASS — production PWA; initial bundle ~368 kB / ~94 kB estimated transfer |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE=<existing Chromium> npm run test:e2e --prefix web` | PASS — 11 browser checks: password/reload/logout, SMS/leading zeros/cooldown/number correction, generic failures/429, safe return/deep links, cross-tab logout, EN/FR/theme/keyboard, three viewports and real production-worker offline shell/cache inspection |
| `npm run lint --prefix api` | PASS — one pre-existing unused `assignedProperty` warning in unrelated `ad-hoc-work-report-options.e2e-spec.ts` |
| `npm run typecheck --prefix api` | PASS |
| `npm test --prefix api` | PASS — all 711 tests in 72 files, including browser HTTP adapter/origin/cookie tests |
| `npm run build --prefix api` | PASS |
| `npm run test:e2e --prefix api -- test/browser-authentication.e2e-spec.ts test/auth-endpoints.e2e-spec.ts test/sms-authentication.e2e-spec.ts` | PASS — 35 tests against local PostgreSQL; browser cookie rotation/replay/revocation and existing password/SMS contracts |
| `npm run test:e2e --prefix api -- test/customers-authorization.e2e-spec.ts` | PASS — 16 authenticated/forbidden/permission tests against PostgreSQL |
| `docker compose build api` | PASS — final shared-package image builds; running containers were not restarted |
| `npm audit --prefix web --json` | PASS — zero reported advisories in the new web dependency tree |
| `git diff --check` | PASS — also verified 19 local links in the new/updated web documentation |

Local socket/browser/database checks required running outside the restricted socket sandbox.
The API's existing integration harness applies pending development migrations before creating and
cleaning up its test users. Browser journeys mock API responses; real API/DB tests are separate.
This is not a claim of a single browser-to-real-SMS-provider deployment acceptance test.

### Remaining acceptance QA / known limits

Owner decision, 2026-10-18: physical iPhone/Safari and installed-iPhone-PWA testing is deferred
across the project until the owner has an iPhone. Those checks do not block ongoing development or
phase progression and must remain marked deferred, never passed. See the
[roadmap decision](064-web-app-delivery-roadmap.md#iphone-testing-deferral--owner-decision-2026-10-18).

- Safari/iOS and physical device installation remain deferred by the owner. Firefox/Edge automated
  auth, workspace, keyboard and responsive coverage passed in the
  [tracker 064 browser-matrix follow-up](064-web-app-delivery-roadmap.md#firefoxedge-browser-matrix--2026-10-18).
  Spoken screen-reader output and manual accessibility acceptance remain open; automation does
  not establish physical-device or deployed-host acceptance.
- Real SMS delivery, timed resend, expiry/attempt-limit UX and server rate-limit timing with the
  selected provider. Existing backend SMS integration tests cover the domain rules.
- Real multi-version update/reload behavior on the production host; update-ready handling is implemented.
- Browser/tab closure during an in-flight cookie mutation and provider/network-ambiguous rotation.
  Deterministic logout-during-refresh/sign-in, single-tab concurrent 401s and cross-tab invalidation
  are covered; lost responses can intentionally require signing in again.
- No business feature screens or offline business data were added. The authenticated landing is
  intentionally minimal. Shared cookie session means account switching invalidates other tabs.
- Existing API dependencies reported 2 high and 1 critical production advisories during container
  installation. This slice adds only a dependency-free local contract package to the API; dependency
  remediation remains separate and production readiness is not claimed by this tracker.

Next: complete the acceptance QA above, then select the first office business feature as its own
vertical slice. No additional architecture confirmation is needed to run or review this milestone.
