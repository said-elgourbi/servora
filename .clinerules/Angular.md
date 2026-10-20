# Angular — Servora web engineering rules

Read Project.md, dev.md, qa.md and [ADR-026](../docs/decisions/026-angular-foundation-and-browser-sessions.md).
The office and technician PWA client is `web/`, including iPhone field use. Keep changes within the selected feature slice.

## Structure

- Compose providers and lazy routes in `app.*`; do not put feature logic in the root component.
- Group behavior by feature in `features/<feature>/`. Keep routes, pages, local UI, data access and
  colocated tests with their owner. Do not create global components/services/models folders.
- `core/` owns only infrastructure with application-wide lifetime. Authentication is a feature.
- `shared/ui/` owns reusable presentation without feature dependencies. Extract only demonstrated reuse.
- `layout/` composes navigation and shells. It does not own business data or permission policy.
- Add folders when required. No empty placeholder domains, speculative libraries or generic repositories.
- Dependency direction: features → core/shared; layout/app → features/core/shared. Core and shared
  cannot import features. Do not import another feature's page or internal UI to reuse business behavior.

## Angular and state

- Strict TypeScript and strict templates; standalone, OnPush components; zoneless Angular.
- Signals for owned synchronous UI state; expose readonly state and use methods for mutations.
- RxJS for HTTP streams; handle errors and loading at the responsible feature boundary.
- Functional route guards/interceptors. Lazy-load feature routes and pages.
- Typed reactive forms; preserve secrets exactly, OTP as text, no duplicate submissions.
- Avoid any/$any, unsafe casts, subscriptions without teardown, direct DOM styling from components,
  broad barrel exports, base-component hierarchies and unneeded state libraries.
- Share public wire types through `@servora/shared-types`. Never import API/database source into web.

## UX, sessions and verification

- All UI text uses EN/FR dictionaries; dictionary keys represent presentation, domain codes stay stable.
- Follow Android color roles in `src/styles/_theme.scss`; no separate brand palette.
- Responsive phone/tablet/desktop layouts, labels, keyboard/focus access and reduced motion support.
- Memory-only access/identity state; HttpOnly refresh cookie. Session changes follow ADR-026.
- Authenticated resource requests opt into AUTHENTICATED_REQUEST; keep API calls same-origin.
- Permission codes come from the API. Never infer permissions from role names; API authorizes actions.
- Service-worker/HTTP caching stays shell-only; never cache auth/protected API responses there.
  [ADR-027](../docs/decisions/027-angular-field-offline-workflow.md) accepts bounded IndexedDB field
  projections/drafts/replay after server-verified scope enforcement and acceptance checks. The field feature implements this bounded exception; office data stays online-only. Never persist credentials,
  identity profiles or permission grants; stored scope identifiers are partition keys only.
- Test meaningful session/form/permission/localization behavior with Vitest and important journeys with
  Playwright. Run affected lint/typecheck/build and tests; report remaining device/provider QA honestly.
