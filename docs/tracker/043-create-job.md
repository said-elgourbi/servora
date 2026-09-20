# Tracker 043 — Create Job (API write + Android screen)

**Status: IMPLEMENTED** (API route, tests and docs; the Android screen, its unit tests and the
device-test sources; **Android physical-device QA is the product owner's**, `qa.md` §7)

Date: 2026-09-18

Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-008`, `BR-011`, `BR-012`, `BR-021`, `BR-023`, `BR-028`,
`BR-033`, `BR-041`, `BR-042`, `BR-047` – `BR-058`, `BR-067`, `BR-083`, `BR-086`, `BR-094`
Domain model: `docs/domain/job-visit-domain-model.md` §5, §6.1 – §6.3, **§6.5 (new)**, §13
API contract: `docs/api/job-details.md` **§6 (new)**
Design reference: none — no Figma screen exists for this form, so the established Servora Material 3
visual language is followed (`docs/design/android-design-system.md`)

## Product decision this slice implements

**`BR-094` (new, CONFIRMED).** The supported Job-creation operation requires **both** an active Customer
and an active Property that Customer currently holds. The Job & Visit model still permits a property-less
Job (`BR-051`, `BR-056`), and nothing in the schema changed — this is a constraint on the product's own
create workflow, not on the model. The rule, its rationale and its open questions are recorded in
`.clinerules/Business Rules.md` (`BR-094`, with cross-references added to `BR-051` and `BR-056`).

## What was implemented

| Layer | Change |
| ----- | ------ |
| Database | **No migration.** `jobs`, `organization_job_number_counters`, `job_status_history` and `jobs_property_snapshot_check` already exist (`0003`). |
| Validation | `api/src/jobs/job-create.dto.ts` — `parseCreateJobDto` on the shared helpers: required UUIDs, required trimmed title (255), optional description (2000). `api/src/address/address-snapshot.ts` gains `addressSnapshotOf`, so the snapshot's shape has one definition for reading and writing. |
| Service | `JobsService.createJob` — one transaction: Customer resolved and `ACTIVE`; Property resolved through `PropertiesService.assertPropertyAvailableForCustomerNewWork` (new; takes the transaction and locks the Property row); Job number allocated from `organization_job_number_counters`; Job inserted `NEW`/`version 1` with the address snapshot; initial `job_status_history` row with `from_status = NULL`. The answer is the `GET /jobs/:id` projection. |
| API | `POST /jobs` → `201`, guarded by the existing `JOB_CREATE` capability (`BR-008`; already defined and granted to the Manager role by `0004_woozy_spitfire`). Errors: `400 VALIDATION_FAILED`, `401`, `403`, `404 CUSTOMER_NOT_FOUND`, `404 PROPERTY_NOT_FOUND`, `409 CUSTOMER_INACTIVE`, `409 PROPERTY_NOT_AVAILABLE_FOR_NEW_WORK`. |
| Module | `JobsModule` imports `CustomersModule` for `PropertiesService`, so the Property rules stay in one place (`BR-083`, `BR-041`). |
| Android data | `CreateJobRequest` + `JobDetailsApi.createJob`, `JobDetailsRepository.createJob`, `JobCreateResult`. **Online-only**: the request has no idempotency key, so offline standard §13 forbids queueing it. |
| Android UI | New `ui/jobs/CreateJobScreen.kt`, `CreateJobViewModel.kt`, `CreateJobUiState.kt`: searchable Customer selector (modal sheet), Property selector (disabled until a Customer is chosen, cleared when the Customer changes), title and optional description, stable bottom action area, inline validation and recoverable API errors. Permission gate: `Permission.JOB_CREATE` → `CustomerPermissionsUiState.canCreateJob`. |
| Navigation | `ServoraRoutes.JOB_CREATE = "job/create?customerId={customerId}"` with an **optional** argument: Customer Detail passes the id and the Customer shows as fixed context; the route also opens without one for a future global entry point. Success navigates to Job Details and removes the form from the back stack. |
| Strings | English + French for every visible string. |

## Decisions taken

| # | Decision | Why |
| - | -------- | --- |
| 1 | Create is guarded by `JOB_CREATE`, not the interim `JOB_UPDATE`. | `BR-008` names "create jobs" as a Manager default and the catalogue already defines and grants the code; inventing `jobs.create` would be a new permission (`BR-006`, `BR-042`). |
| 2 | A Property that is not related to the selected Customer answers `404 PROPERTY_NOT_FOUND`. | It is the answer the Customer-scoped Property routes already give, so an id cannot be probed for which Customer holds it, and a second code would be a second definition of the same condition (`BR-050`, `BR-041`). |
| 3 | An inactive Customer answers `409 CUSTOMER_INACTIVE` rather than `404`. | A Customer may be `INACTIVE` without being deleted; the record exists and it is the state that refuses the operation (`dev.md` §7). |
| 4 | The initial `job_status_history` row uses `from_status = NULL`. | The column permits it precisely for this; fabricating a previous status would invent history (`BR-067`). |
| 5 | The Customer selector narrows the list **locally**, over the existing `GET /customers` read. | The API has **no** text-search parameter — `GET /customers` filters by status and jobs only — so there is no server search to debounce, and reading the list through the existing repository is the established behaviour (the customer list screen already narrows its rows locally). A server-side search is not used because it does not exist. |
| 6 | The Customer's Properties are read through `CustomersRepository.loadCustomerDetail`, taking its `properties` projection. | It is the existing read of `GET /customers/:id/properties` (alongside the detail and jobs reads), it already excludes archived Properties by default (`BR-081`, `BR-083`), and it carries the offline working-set fallback. A second repository method for the same route would be a second definition of the same read. |
| 7 | Creating a Job is online-only. | No idempotency key and no conflict policy are decided for it, which is offline standard §13's own condition for queueing a mutation (`BR-013`, `BR-014`). |
| 8 | No screenshot or Compose-preview workflow exists in this repository. | There is no capture pipeline to produce the requested images; verification is the JVM tests and the compiled device-test sources (`qa.md` §7.3). Reported rather than invented. |
| 9 | `organization_job_number_counters` is written with an explicit first value of `1`. | The domain note's increment applies only on conflict, so a fresh counter row must already carry the number the creation takes (`docs/domain/job-visit-domain-model.md` §13). |

## Tests

| Suite | Coverage |
| ----- | -------- |
| `api/src/jobs/job-create.dto.spec.ts` | Required fields, trimming, blank title, UUID form, length caps, and that a value the backend owns is never read from the body. |
| `api/test/job-create.e2e-spec.ts` | 20 cases: successful creation (backend-owned id/number/status/version/snapshot, no Visit, initial history), ignored client attempts at the number/status/version/snapshot, the snapshot frozen after a Property edit, sequential numbering, a fresh organization starting at 1, **no duplicate number under 8 concurrent creations**, required-field and whitespace-title validation, missing/inactive/deleted Customer, missing/archived Property, a Property of another Customer, an ended relationship, organization isolation (including a Job id that is not disclosed), and authorization (no session, capability absent, capability alone). |
| `android/…/test/…/ui/jobs/CreateJobViewModelTest.kt` | Initial state, search and selection, preselected Customer, Property loading, clearing the Property on a Customer change, no-Properties state, validation, loading/submitting, duplicate-submit prevention, an API error preserving the entered values, success. |
| `android/…/androidTest/…/ui/jobs/CreateJobScreenTest.kt` | The form's fields, the fixed-customer row, the no-Properties state, validation, the submit action and when it is enabled. Compiled, **not executed** — device QA is the product owner's (`qa.md` §7.3). |

## Open questions carried forward

1. **A server-side customer search.** The Customer selector narrows the list on the device because the
   API exposes no text query. If customer lists grow, a searchable `GET /customers` parameter is the clean
   answer — a product/API decision, not implemented here (`BR-042`).
2. **Creating a Property from the Create Job screen.** The task allowed an "Add property" action only if it
   fitted the existing navigation; the Property selector needs `properties.view` to be listed, and linking
   a second create flow from this form was not decided. The screen shows an inline empty state instead
   (`BR-094` Notes).
3. **A global Jobs entry point.** The route supports launching without a `customerId`; no tab or FAB is
   wired to it yet, because an organization-level Jobs list does not exist.
4. **The `jobs.*` capability set** (`BR-006`, `BR-008`): create uses `JOB_CREATE`; the remaining Job and
   Visit writes stay on the interim `JOB_UPDATE` (`docs/api/job-actions.md` §2).
5. **Create then schedule.** This slice stops at the work request: creating a Visit, assigning a crew and
   scheduling all belong to the Visit slice (`BR-071`, `BR-072`).
