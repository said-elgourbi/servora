# ADR-026 — Angular foundation and browser sessions

Date: 2026-10-18
Status: Implemented; external-provider and cross-browser acceptance QA remains open in tracker 063.
Scope: client-rendered office client and authentication. No business schema changes.

Amendment, 2026-10-19: [ADR-027](027-angular-field-offline-workflow.md) accepts a bounded
IndexedDB exception for assigned-field projections, drafts, pending evidence and replay records,
including scope identifiers. Its initial API/web implementation is recorded in tracker 064;
physical field acceptance remains pending. Credential/identity/permission
persistence prohibitions and shell-only service-worker caching remain in force.

## Context

Tracker [063](../tracker/angular/063-angular-foundation-and-authentication.md) introduces the Angular
client alongside API and Android. The API already authenticates users and rotates opaque refresh
tokens. Android's secure credential storage cannot be reused in a browser. The owner requested a
clean, maintainable Angular structure and authorized implementation on 2026-10-18.

## Decisions

### 1. Workspace and dependency boundaries

Use `web/`, a standalone, strict, zoneless Angular 22.2.3 SPA with Angular Material 22.2.3.
The selected Node 24.20.0 and TypeScript 6.0.3 satisfy Angular's
[compatibility table](https://angular.dev/reference/versions). Follow Angular's
[feature-area organization](https://angular.dev/style-guide#organize-your-project-by-feature-areas).
No SSR, BFF, Nx, global store, generic repository framework, or second identity provider is needed.

- `app.*`: bootstrap, root routes and providers only.
- `core/`: browser-wide infrastructure: localization, appearance, connectivity, update lifecycle.
  No business features or authentication policy live here.
- `features/auth/`: auth transport/session state, refresh interceptor, permission guards/directive,
  sign-in forms, validation and error mapping. The feature owns its own routes and tests.
- `features/workspace/`: minimal authenticated landing page; no invented dashboard data.
- `layout/`: authenticated shell composition.
- `shared/ui/`: demonstrated reusable presentation: language/theme controls and status pages.
- `styles/`: shared Material role mappings. Feature styles remain beside their components.

Features may use core and shared presentation. Core cannot import features, layout or shared UI;
shared UI cannot import features or layout. ESLint enforces those dependency directions. Add a
folder when it has a real owner and implementation; never pre-create empty business features.

`packages/shared-types` contains only public TypeScript auth declarations. API and web consume the
same sign-in/SMS requests, identity, browser response and error-envelope declarations. A shared
permission-code JSON catalogue provides explicit client recognition; API capability constants are
compile-time checked against its keys. An older web build denies unrecognized server codes. Existing
Android DTOs and API JSON token endpoints retain their contracts. `packages/shared-config` starts
with strict browser compiler defaults; server-specific compiler settings remain server-owned.
Independent npm projects and their lockfiles remain intact; there is no root workspace migration.

### 2. Same-origin HttpOnly browser refresh credential

Use a browser adapter at `/auth/web/*`, reusing `AuthService`, `SmsOtpService`, parsers, session
persistence and revocation. Password/SMS success and refresh return only `accessToken`,
`accessTokenExpiresAt` and `sessionId`. The refresh token is sent solely as an HttpOnly cookie.

Production cookie: `__Secure-servora-refresh`; Secure, HttpOnly, SameSite=Strict, host-only,
Path=/auth/web. Local development uses `servora-refresh` without Secure for localhost HTTP.
The cookie lifetime follows configured session lifetime; database session/refresh expiry remains
authoritative and is not extended by a browser's cookie expiry.

Access tokens and identity/permissions stay in application memory. Never persist credentials,
passwords, OTPs, identity, permission grants or protected responses in localStorage, sessionStorage,
IndexedDB, URLs or service-worker caches outside ADR-027’s bounded field projection/draft exception.
localStorage contains only language/theme preferences,
a random installation identifier, an invalidation epoch and a pending-sign-out boolean.

On startup, refresh the cookie and load `/auth/me` before entering authenticated state. A `401`
means signed out; connection/server failure produces an explicit retry state without cached identity.
A failed identity read never creates default permissions. Existing `/auth/sign-in`, `/auth/refresh`,
SMS and Bearer endpoints remain available to Android and other existing clients.

This avoids persistent JavaScript-readable refresh credentials. HttpOnly does not eliminate XSS:
a compromised origin could still perform actions. Deployment CSP and ordinary XSS controls remain
necessary; do not add unsafe HTML rendering or third-party scripts to authentication.

### 3. CSRF and routing

Every browser-adapter POST requires both `Origin` equal to configured `WEB_ORIGIN` and
`X-Servora-Web: 1`. This includes login and SMS request, preventing login CSRF as well as cookie
refresh/logout CSRF. No permissive CORS is added. Browser endpoints are disabled in production when
WEB_ORIGIN is unset; configured production origins must use HTTPS with no path/trailing slash.
A production proxy must preserve Origin and route API requests before the SPA fallback.

Development uses Angular's `/auth/**` proxy. `SERVORA_API_TARGET` overrides its local target;
WEB_ORIGIN describes the browser's public origin, never the API upstream. Paths retain the existing
`/auth/...` contract; there is no `/api/v1` prefix. Auth responses and `/auth/me` use no-store.

### 4. Refresh, tabs and sign-out

Coalesce refresh in a tab; serialize every cookie-changing operation across tabs with Web Locks.
The selected supported browser baseline requires Web Locks and browser storage. Fail closed if
coordination is unavailable. A protected API request explicitly opts into the auth interceptor:
only same-origin resource paths may carry Bearer tokens. Retry a `401` once, never refresh for `403`,
and never intercept the session transport itself.

After refresh, reload effective permissions. Route guards and the `appCan` directive match recognized, requested
capability codes exactly; they never infer authorization from a role name or grant default access.
Unrequested/unknown capabilities do not open any navigation or action. The API is the authorization
boundary. The landing shell requires authentication but no invented office-only permission.

Sign-out clears memory immediately and writes a non-secret pending marker/epoch. Other tabs remove
identity and protected content on the storage event. Generations and epochs reject late async session
results. Within the same cross-tab lock, revoke the session using its cookie and clear the cookie.
An expired access token does not prevent revocation. A network/server failure keeps the pending
marker and presents retry; startup completes that sign-out before attempting any restoration.

A browser shares one cookie session. Signing into another account revokes the previous browser
session and invalidates other open tabs; those tabs remain signed out until they explicitly sign in
or reload. Concurrent tab refreshes consume the latest rotated cookie in lock order. On an ambiguous
network failure during rotation, retry may find the consumed credential invalid and require sign-in;
never claim successful restoration with stale identity. Closing a tab during an in-flight mutation
has browser/network-dependent completion behavior and remains part of acceptance QA.

### 5. Language, theme and responsive behavior

Use runtime, typed EN/FR dictionaries in core; all visible auth/status text resolves through `I18n`.
French dictionary completeness is compile-time checked. Initial choice is saved preference, then
browser language, then English. Update `<html lang>` on changes. Additional languages add a dictionary
and selector entry without changing authentication or domain values. Introduce ICU/message tooling
only when richer message requirements justify it.

Appearance supports system/light/dark, persisted as a non-secret preference. Material system roles
map to the [Android design tokens](../design/android-design-system.md), including the true-black
foundation in dark mode. Auth uses a two-column desktop layout and a single-column phone/tablet
layout. Inputs retain labels/autofill, OTP is text with numeric keyboard, and focus indicators and a
skip link support keyboard use. No reset-password link is shown before that flow is delivered.

### 6. PWA and browser coverage

Cache only explicitly listed build assets, manifest and icons. No service-worker data groups,
remote assets, credentials, auth paths or protected API responses are cached. API navigation paths
are excluded from SPA navigation fallback. Session/protected HTTP requests also use ngsw-bypass and
no-store. Offline shell reload presents connection/retry state, never an authenticated offline view.
`navigator.onLine` supplies an advisory banner; request failure is the authoritative connection state.

Show an update-ready prompt; activate the new application through full reload to keep chunks
consistent. HTTPS hosting is required for production installation/service-worker/Web Locks.
Use Angular 22's widely available browser baseline; check current Chrome/Edge, Firefox, Safari/iOS
in acceptance QA. Automated Chromium checks cover 390×844, 768×1024 and 1440×900. Installation and
external SMS delivery require their own environment/device checks.

## Verification and operational consequences

See [web setup and architecture](../../web/README.md) and tracker 063 for executed checks and
remaining QA. Shared file dependencies require API Docker builds from the repository root; Compose
and the Dockerfile copy only the required API and contract inputs. Rebuilding does not restart the
running development container. No new database migration is introduced by this slice.
