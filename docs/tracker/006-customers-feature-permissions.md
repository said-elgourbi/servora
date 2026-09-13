# Tracker 006 — Customers Feature Permissions

Milestone status for customer permissions across the Node API and Android client.

- Status: **IMPLEMENTED**
- Date: 2026-09-11
- Domain reference: `docs/domain/foundation-domain-model.md`
- API contract reference: `docs/api/authentication.md`
- Figma reference: `Figma/src/screens/Customers.tsx`,
  `Figma/src/imports/pasted_text/customer-section-design.md`

## Scope

| Item | Status |
| --- | --- |
| Canonical customer permissions: `customers.view`, `customers.create`, `customers.edit`, `customers.archive` | Implemented |
| Permission data migration from legacy customer permission codes | Implemented |
| API permission decorator/guard using effective role + direct permissions | Implemented |
| `GET /customers` and `GET /customers/:id` require `customers.view` | Implemented |
| `POST /customers` requires `customers.create` | Implemented |
| `PATCH /customers/:id` and `PUT /customers/:id` require `customers.edit` | Implemented |
| `POST /customers/:id/archive` requires `customers.archive` | Implemented |
| Auth responses expose effective permission codes for Android UI decisions | Implemented |
| `GET /auth/me` exposes the authenticated user's effective permission codes for Android post-login refresh | Implemented |
| Android centralized permission checker | Implemented |
| Android Customers bottom navigation visible only with `customers.view` | Implemented |
| Android Create/Edit/Archive customer actions hidden without their permissions | Implemented |
| EN/FR customer UI strings | Implemented |
| API and Android tests for customer permission behavior | Implemented |

## Behaviour Notes

- Android hides permission-unavailable customer affordances. It does not show a disabled Customers
  tab when `customers.view` is absent.
- Direct navigation to the native Customers screen without `customers.view` renders a graceful
  localized no-access state.
- Backend authorization remains mandatory even when Android hides an action.
- Auth responses expose the union of effective permission codes across the caller's active
  memberships for UI visibility. Customer API guards still choose a membership that has the
  required customer permission before applying tenant scope.
- Android refreshes effective permissions from `GET /auth/me` immediately after sign-in/SMS
  sign-in, with the sign-in body as a compatibility fallback. This prevents an empty or stale
  sign-in permission array from hiding the Customers surface after login.
- Customer archive/deactivate uses the existing soft-delete fields and sets `status = INACTIVE`;
  no physical deletion workflow or hard-delete permission was added.
- Properties displayed inside customer screens follow `customers.view` for this slice. No property
  permission was introduced.

## Existing Architecture Issue

The current access token does not carry an organization id, and there is no organization-switching
endpoint yet. The customer permission guard resolves an active membership that satisfies the
required permission and uses that membership as the tenant scope. That is sufficient for the
current Android flow, but a future multi-organization account workflow should make the selected
organization explicit.

## Verification

Automated verification on 2026-09-11:

```text
npm run typecheck          PASS
npm run lint               PASS
npm run test               PASS  248 tests / 31 files
npm run test:e2e           PASS  106 tests / 8 files
npm run build              PASS
./gradlew testDebugUnitTest PASS
./gradlew lintDebug        PASS
./gradlew assembleDebug    PASS
```

Regression verification added after the post-login hidden Customers report:

```text
npm run typecheck          PASS
npm run lint               PASS
npm run test:e2e -- customers-authorization.e2e-spec.ts PASS  8 tests / 1 file
./gradlew testDebugUnitTest --tests com.servora.android.data.auth.DefaultAuthRepositoryTest --tests com.servora.android.data.auth.DefaultAuthRepositoryFlowTest --tests com.servora.android.ui.customers.CustomerPermissionsUiStateTest PASS
git diff --check           PASS
```

Attempted `./gradlew connectedDebugAndroidTest` on a connected Pixel 10 - 17. The suite failed in
pre-existing auth/password-reset Compose tests with `No compose hierarchies found in the app`;
this slice keeps customer permission behaviour covered by JVM tests until the connected Compose
harness is repaired.
