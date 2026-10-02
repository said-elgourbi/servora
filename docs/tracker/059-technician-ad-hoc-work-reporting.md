# Tracker 059 - Technician ad-hoc work reporting and office reconciliation

**Status: Phase 0, Phase 1 and Phase 2 complete — decisions AH-D1–AH-D7 recorded (ADR-024, BR-AH-005–BR-AH-009); the API and database foundation is reshaped. Ready for Phase 3.**

Date: 2026-10-01

Source: product-owner request, 2026-10-01: implement a phase-by-phase ad-hoc / unrecorded field work
workflow. The request explicitly says not to implement this as one large change.

## 1. Goal

Support the real field-service case where legitimate technician work happened, or was initiated, without a
proper Servora Visit in the schedule. A technician reports the field facts; the office reconciles the
report into the canonical Job and Visit model.

The technician does **not** create a Job, create a Visit, decide whether the work belongs to an existing
Job, or decide whether a completed Job should be reopened. Those are office reconciliation decisions.

## 2. Product boundaries

Confirmed boundaries from the current request and existing rules:

| Boundary | Rule/source |
| -------- | ----------- |
| An ad-hoc work report is not a Job, not a Visit, and not a Visit status. | `BR-AH-001`, `BR-AH-003` |
| Do not introduce an `AD_HOC` Visit status. | `BR-AH-003` |
| Ad-hoc reports and follow-up Visit requests are separate workflows. | `BR-AH-004`, `docs/api/visit-requests.md` |
| The technician reports facts; the office reconciles. | `BR-AH-002`, product request |
| Technicians must not receive Manager CRUD powers to support this. | `BR-009`, `BR-066` |
| The backend is authoritative for authorization and business decisions. | `BR-001`, `BR-007` |
| Offline technician work must not be silently lost. | `BR-013`, `BR-014`, `docs/architecture/offline-first-architecture.md` |
| Ordinary Visit creation on completed or canceled Jobs remains blocked. | `BR-062`, `docs/api/job-actions.md` |
| Historical reconciliation is not ordinary scheduling. | product request, `BR-067` |

## 3. Current repository state discovered so far

This repository already contains WIP ad-hoc work artifacts:

| Area | Existing artifact |
| ---- | ----------------- |
| Business rules | `BR-AH-001` through `BR-AH-004` |
| Domain docs | `docs/domain/job-visit-domain-model.md` section 22 |
| API docs | `docs/api/ad-hoc-work-reports.md` |
| Database | `api/drizzle/migrations/0020_ad_hoc_work_reports.sql`, `ad_hoc_work_reports` schema |
| API | ad-hoc DTOs, routes, submit/list/link/convert service methods |
| Permissions | `visits.report_ad_hoc_work`, `visits.review_ad_hoc_work` |
| Android | `AdHocWorkReportsApi`, repository stubs, technician schedule dialog, strings |

The existing WIP is not accepted as the final design. It is treated as user work in progress and must be
reshaped rather than blindly extended.

### 3a. End-to-end trace (Phase 0 complete)

**API** (`api/src/jobs/`):

- `POST /jobs/ad-hoc-work-reports` → `jobs.controller.submitAdHocWorkReport` (guarded `visits.report_ad_hoc_work`) → `jobs.service.submitAdHocWorkReport`. Idempotent by `clientOperationId`: partial unique index `ad_hoc_work_reports_client_operation_unique` + `findAppliedAdHocReportOperation` replay; the insert is `onConflictDoNothing`.
- `GET /jobs/ad-hoc-work-reports` → `jobs.service.listAdHocWorkReports`. `REVIEW_AD_HOC_WORK` sees the organization; a reporter holding only `REPORT_AD_HOC_WORK` is scoped to `reportingTechnicianMembershipId`.
- `POST /jobs/ad-hoc-work-reports/:reportId/link` → `jobs.service.linkAdHocWorkReportToJob` → `requirePendingAdHocWorkReport` (optimistic `expectedStatus`/`expectedVersion`) + `requireJobRow` + `insertCompletedVisitFromAdHocReport`.
- `POST /jobs/ad-hoc-work-reports/:reportId/convert` → `jobs.service.convertAdHocWorkReportToNewJob`. Creates a Job (status `ACTIVE`) from the report's customer/property, then the completed Visit.
- `insertCompletedVisitFromAdHocReport` inserts the Visit as `COMPLETED`, the report's work window as its schedule, the report outcome as its outcome, the reporting technician as `LEAD`, and writes `visitScheduleHistory`, `visitStatusHistory`, `visitOutcomeHistory`, `visitTechnicians`, `visitTechnicianHistory`.
- `requireJobRow` blocks `COMPLETED`/`CANCELED` Jobs (`JobClosedForFieldWorkError`), so **reconciliation into a closed Job is currently refused** (AH-D2/AH-D3).
- Schema/migration `0020_ad_hoc_work_reports.sql`: statuses `PENDING`/`LINKED`/`CONVERTED`, no `REJECTED` (AH-D1/AH-D5); no unknown-customer/location free-text columns (AH-D4).

**Android** (`android/app/src/main/java/com/servora/android/`):

- `data/schedule/AdHocWorkReportsApi` (submit only), `AdHocWorkReportsRepository` (submit only), `AdHocWorkReportDtos.kt`.
- `ui/schedule/TechnicianScheduleScreen.AdHocWorkReportDialog`: raw `customerId`/`propertyId`/`knownJobId` UUID text fields, ISO instant strings for start/end, free-text outcome, duplicate `summary`/`notes`.
- `ui/schedule/TechnicianScheduleViewModel.submitAdHocWorkReport`: online-only; the repository generates a **fresh** `clientOperationId` per call, so a retry after a network failure can create a duplicate report (Phase 2 gap).

**Evidence** (`docs/tracker/056`): photo/audio evidence is now **Visit-scoped** (`job_photos.visit_id` / `job_audio_notes.visit_id` required, FK → `visits.id`). A report has no Visit, so report evidence cannot reuse the existing evidence routes without a Visit first — this hardens AH-D6.

## 4. Conflicts and gaps discovered so far

| Finding | Why it matters |
| ------- | -------------- |
| Android currently uses a large modal with raw `customerId`, `propertyId`, `knownJobId`, ISO timestamps, free-text outcome and duplicate summary/notes fields. | The request requires a field-friendly UX, type-ahead search, native date/time presentation and canonical outcome selection. |
| The current API submit shape accepts `customerId`, `propertyId` and `knownJobId` without a documented search/discoverability model. | The request requires server-enforced customer visibility and scoped property/job choices. |
| Unknown customer/property handling is not modelled in the current schema/API. | The request requires a "can't find customer / customer unknown" path without silently creating canonical records. |
| Existing reconciliation is `link` or `convert`, both directly creating a completed Visit. | The request requires a richer historical reconciliation model, especially for completed Jobs and resulting Job state semantics. |
| Completed-Job reconciliation is not explicitly modelled. | `BR-062` blocks ordinary Visit creation on completed/canceled Jobs; this workflow needs an explicit privileged operation or a product decision. |
| Evidence is not included in the current report model. | The request requires photos/evidence to survive reconciliation into the eventual Visit without a parallel evidence system. |
| Offline Android submission is not yet traced end-to-end. | The request requires durable offline submission, idempotency and no duplicate reports on retry. |
| Activity/audit presentation after reconciliation is not defined. | The request requires provenance and understandable Job/Visit activity without dumping implementation events. |
| Evidence is now Visit-scoped (`job_photos.visit_id`, `job_audio_notes.visit_id` required — tracker 056), so a report with no Visit has no existing evidence target. | AH-D6 — report evidence cannot reuse the current evidence routes without a Visit. |
| Android's submit generates a fresh `clientOperationId` per call and is online-only; a retry after a network failure can create a duplicate report. | Phase 2 idempotency must persist one client operation id across retries and replay through the outbox. |

## 5. Open product decisions

These were answered 2026-10-01 — see §5a for the decisions. The table below is the question register:

| ID | Decision needed | Blocks |
| -- | --------------- | ------ |
| AH-D1 | What lifecycle statuses beyond `PENDING`, `LINKED`, `CONVERTED` are required, if any? For example `PENDING_OFFICE_REVIEW`, `RECONCILED_TO_EXISTING_JOB`, `RECONCILED_TO_NEW_JOB`, `REJECTED`/`VOIDED`. | Database/API lifecycle design |
| AH-D2 | How should historical reconciliation against a `COMPLETED` Job be represented: distinct privileged operation, explicit reopen-to-`ACTIVE`, or another audited reconciliation event? | Existing-job reconciliation |
| AH-D3 | After historical reconciliation, should a resolving historical Visit automatically close or keep closed/open the Job using existing `BR-078` consequences, or does reconciliation need a separate consequence table? | Job state after reconciliation |
| AH-D4 | What minimum unknown-customer/location fields are acceptable for office reconciliation? | Technician form/schema |
| AH-D5 | Should office users be able to reject/void a report that was not legitimate Servora work, or must every report reconcile to a Visit? | Report lifecycle and manager UX |
| AH-D6 | Which exact evidence attachment path should reports use before a canonical Visit exists? | Evidence schema/API/Android offline uploads |
| AH-D7 | What technician post-submission visibility is required beyond "submitted, pending office review"? | Technician status UI |

### 5a. Decision record (product-owner approved, 2026-10-01)

Finalised in `docs/decisions/024-ad-hoc-work-reconciliation.md`; recorded as `BR-AH-005` – `BR-AH-009` in
`.clinerules/Business Rules.md` §23.

- **AH-D1 — lifecycle.** `PENDING` (actionable) → `LINKED` | `CONVERTED` | `REJECTED` (terminal, one-way). A terminal report cannot be reconciled again; no reopen/resubmission in v1.
- **AH-D2 — closed-Job reconciliation.** A distinct privileged, audited office operation inserts the historical Visit against a `COMPLETED` or `CANCELED` Job without a fake reopen transition. It must not weaken the ordinary closed-Job prohibition.
- **AH-D3 — Job-state consequence.** The reconciled Visit's canonical outcome has its normal consequence (`BR-062`, `BR-078`): `RESOLVED` leaves a `COMPLETED` Job `COMPLETED` and moves a `CANCELED` Job to `COMPLETED`; a follow-up-required outcome moves it to `ACTIVE`. No new Job statuses.
- **AH-D4 — unknown customer/property.** Technician free text is report provenance only (name, phone/contact, address/location); it never creates a Customer/Property/Job. Property choice is scoped to a selected Customer; Customer discovery is authorized type-ahead, not a full list.
- **AH-D5 — reject/void.** `REJECTED` records reviewer + timestamp + reason/note; retained for audit, never hard-deleted; terminal in v1.
- **AH-D6 — evidence.** Report-level evidence is deferred for v1 (evidence is Visit-scoped per tracker 056). Documented as a v1 limitation; future report evidence must preserve assets through reconciliation into the Visit.
- **AH-D7 — visibility.** No third top-level tab. Submit confirms "pending office review"; the technician's own reports appear in the existing Requests surface, visually distinguishable from follow-up requests.

## 6. Implementation phases

The phases below are the intended sequence. Each phase stops with verification and tracker updates before
the next starts.

| Phase | Status | Scope |
| ----- | ------ | ----- |
| 0. Discovery and conflict register | **COMPLETE** | Read docs/ADRs/rules, trace API and Android code, identify existing primitives and conflicts, and keep this tracker current. |
| 1. Domain and product decision record | **COMPLETE** | Finalize report lifecycle, completed-Job reconciliation semantics, unknown-customer/location facts, evidence attachment strategy, permissions and audit/provenance rules. Add/update ADR/business-rule docs as needed before code changes. |
| 2. API and database foundation | **COMPLETE** | Reshape schema/migration/DTOs/routes around the decided lifecycle, idempotent submission, scoped search endpoints or reuse of existing search, report list/detail reads, and authorization. No Android UX rewrite yet. |
| 3. Technician Android report creation | TODO | Replace the prototype modal with the approved Schedule action and dedicated field-friendly screen if navigation review confirms it; add customer type-ahead, property derivation, related Job hint, date/time controls, outcome picker, single work description and pending-sync state. |
| 4. Evidence integration | TODO | Attach report evidence using existing evidence/photo/audio/offline primitives, preserving assets through reconciliation into the canonical Visit. Avoid a parallel evidence system unless Phase 1 proves the existing architecture cannot support it. |
| 5. Office attention and review | TODO | Surface pending reports for manager/office users using existing attention/request patterns without treating them as follow-up requests. Add list/detail/review UX and API support. |
| 6. Reconciliation to existing Job | TODO | Implement explicit audited reconciliation to an eligible existing Job, including the special completed-Job decision path, historical Visit creation, provenance links and resulting Job state handling. |
| 7. Reconciliation to new Job | TODO | Implement office-driven new Job creation plus historical Visit creation from the report, reusing normal Job requirements where applicable while preserving the report as provenance. |
| 8. Technician status feedback | TODO | Provide submission confirmation and appropriate later state visibility without adding a third top-level technician tab unless a product decision requires one. |
| 9. Activity, audit and hardening | TODO | Add user-facing activity entries, technical audit/provenance coverage, conflict/idempotency tests, authorization tests, offline replay tests, localization, docs and final Tier B verification. |

## 7. Acceptance criteria by phase

### Phase 0 - Discovery and conflict register

- Relevant business rules, ADRs, API docs, domain docs, trackers and Android/API code paths are read.
- Existing WIP artifacts are catalogued.
- Conflicts, reusable primitives and open decisions are recorded in this tracker.
- No feature code is changed.

### Phase 1 - Domain and product decision record

- Report lifecycle is explicitly defined.
- Completed-Job reconciliation has an approved, audited domain operation.
- Unknown customer/property capture is defined and does not create canonical records.
- Evidence strategy is defined before evidence implementation starts.
- Permissions are named and server-side authorization boundaries are documented.
- Required ADR/business-rule/API/domain docs are updated.

### Phase 2 - API and database foundation

- Submission creates only a report, never a Job or normal Visit.
- Submission is idempotent by client operation id.
- Customer/property/job discoverability is enforced by the API.
- Report reads are scoped: technicians see their own, office users see reviewable organization reports.
- Existing follow-up Visit request routes/models remain semantically separate.
- API unit/e2e tests cover validation, authorization, idempotency and tenant scope.

### Phase 3 - Technician Android report creation

- Entry point is an action from technician Schedule, not a third top-level tab.
- The form is field-friendly and avoids raw database/API fields.
- Customer selection uses type-ahead search with a minimum query length.
- Unknown customer/property path captures only reconciliation facts.
- Property choices are derived from the selected Customer.
- Related Job is an optional technician hint with user-facing wording.
- Work time uses native/local date-time UI and validates end after start.
- Outcome uses canonical Visit outcome vocabulary.
- Work description is not duplicated unless a documented semantic distinction exists.

### Phase 4 - Evidence integration

- Report evidence uses existing evidence primitives where possible.
- Offline evidence behavior follows the existing pending-upload rules.
- Reconciliation preserves underlying assets and links them to the resulting Visit.
- Tests cover retry/failure paths that could otherwise lose evidence.

### Phase 5 - Office attention and review

- Pending reports are visible to authorized office users.
- Review surfaces technician, customer/property/location facts, related Job hint, time, outcome, description, evidence and submitted time.
- Follow-up Visit requests remain a separate lane/concept.
- Unauthorized users cannot view or reconcile reports.

### Phase 6 - Reconciliation to existing Job

- Office can reconcile into an eligible existing Job as historical work.
- Ordinary users still cannot attach arbitrary Visits to completed Jobs.
- Completed-Job reconciliation requires the approved explicit audited decision.
- Created Visit preserves technician, actual times, outcome, description, evidence and report provenance.
- Resulting Job state follows existing approved semantics.

### Phase 7 - Reconciliation to new Job

- Office can create a canonical Job and historical Visit from the report.
- Normal Job creation requirements are reused where applicable.
- No nonsensical forward scheduling transitions are required just to record already-performed work.
- Report links to the created Job and Visit.

### Phase 8 - Technician status feedback

- Submission clearly reports "submitted, pending office review."
- Later handled/reconciled/refused states are visible using existing UX patterns.
- No permanent third navigation category is introduced without product approval.

### Phase 9 - Activity, audit and hardening

- Report provenance answers who submitted, what they reported, who reconciled, when, and what records resulted.
- Job/Visit activity presents an understandable business event after reconciliation.
- Technical audit data stays available without cluttering user-facing timelines.
- EN/FR strings are complete.
- Tier B verification passes for affected applications before the feature is reported complete.

## 8. Reusable primitives to investigate

| Need | Candidate primitive |
| ---- | ------------------- |
| Offline durable submission | `OutboxStore`, `OutboxReplayEngine`, operation handlers, `clientOperationId` pattern |
| Technician Schedule entry point | `TechnicianScheduleScreen`, tab/action patterns, existing request status feedback |
| Customer search/visibility | `/customers` filters, assigned-customer read, existing permission scope rules |
| Property derivation | `GET /customers/:id/properties`, `PropertiesService.assertPropertyAvailableForCustomerNewWork` |
| Related Job lookup | `GET /customers/:id/jobs`, Job Details assigned scope, schedule/job projection code |
| Visit outcomes | `VISIT_OUTCOME_CODES`, Android `VisitOutcome` domain model |
| Historical Visit creation | Visit lifecycle/action service code, schedule/assignment/history insertion helpers |
| Evidence | Job photo/audio APIs, pending evidence stores, evidence visit-link tracker 056 |
| Activity/audit | `docs/api/job-activity.md`, `jobActivity` projection and history tables |
| Attention/review UX | Manager Schedule Requests lane, manager home attention patterns |

## 9. Verification plan

Verification follows `qa.md` tiering:

- Phase-level work uses Tier A targeted checks for affected files/modules.
- Schema, authorization, offline and cross-application contract phases escalate as required by `qa.md`.
- Feature completion requires Tier B for affected applications: API lint/typecheck/tests/e2e/build and Android compile/unit/device-test-source build/lint/build as applicable.
- Android physical-device acceptance remains the product owner's responsibility.

## 10. Current next step

Phase 2 is complete: the schema/migration/DTOs/routes are reshaped around the decided lifecycle
(`REJECTED` status, unknown-customer provenance columns), idempotent offline submission, scoped
customer/property/job discoverability, and report list/detail reads — per the invariants in ADR-024.
Migration `0021_ad_hoc_work_report_lifecycle` adds the `REJECTED` status and the
`reported_customer_name` / `reported_customer_phone` / `reported_customer_address` provenance columns.

### Phase 3 prerequisite — scoped discoverability endpoints (complete)

Phase 3's form must discover the Customer/Property/Job rather than type ids, and a technician holds no
`customers.view`/`properties.view`, so a scoped API surface was built first (`BR-AH-009`, `BR-092`):

- `GET /jobs/ad-hoc-work-reports/customer-options?q=` — type-ahead Customer search, guarded
  `visits.report_ad_hoc_work`. `customers.view` searches the whole organization's active Customers,
  `customers.view_assigned` searches only the Customers of Jobs the caller's own current crew includes,
  and a caller holding neither gets no matches (the unknown-customer path then applies). `q` must be at
  least two characters.
- `GET /jobs/ad-hoc-work-reports/property-options?customerId=` — active Properties of a selectable
  Customer (`BR-050`); an out-of-scope Customer is `404`.
- `GET /jobs/ad-hoc-work-reports/job-options?customerId=` — the optional related-Job hint; same `404`.

New files: `api/src/jobs/ad-hoc-work-report-options.dto.ts` (+ `.spec.ts`),
`api/test/ad-hoc-work-report-options.e2e-spec.ts`; service methods `searchAdHocReportCustomers` /
`listAdHocReportProperties` / `listAdHocReportJobs` in `jobs.service.ts`; routes in `jobs.controller.ts`.
Docs updated in `docs/api/ad-hoc-work-reports.md` §"Discovery options". Verified: `npm run typecheck`,
`npm run build`, unit specs (14) and both ad-hoc e2e suites (16) pass.

### Phase 3 — Technician Android report creation (in progress)

The prototype modal is replaced with the approved Schedule action and a field-friendly form:

- **Data layer** — `SubmitAdHocWorkReportRequestDto` gains `reportedCustomerName` /
  `reportedCustomerPhone` / `reportedCustomerAddress`; `AdHocWorkReportsApi` gains the three scoped
  option reads (`customer-options?q=`, `property-options?customerId=`, `job-options?customerId=`).
  `AdHocWorkReportsRepository` now searches/derives the options and submits **offline-capably**: the
  draft carries one `clientOperationId`, the online attempt uses it, and an unreachable backend queues
  it through `AdHocWorkReportOfflineStore` with the same id as the operation id, replayed by
  `AdHocWorkReportSubmitHandler` (registered in `di/ScheduleOfflineModule.kt`).
- **UI** — `AdHocReportFormState`/`AdHocReportProblem`, a full-screen `AdHocWorkReportForm` (type-ahead
  Customer search, derived Property/Job pickers, unknown-customer provenance path, local date/time
  controls, canonical `VisitOutcome` picker, one work description), driven by
  `TechnicianScheduleViewModel` (debounced search, property/job derivation, submission) and wired
  through `TechnicianScheduleScreen`/`CustomersScreen`. A queued submission reports "saved offline".
- **Strings** — EN and FR (`BR-028`); the FR ad-hoc copy that was missing is added.
- **Tests** — `AdHocWorkReportsRepositoryTest` pins the durable single-key submission; the schedule
  ViewModel/screen test doubles are updated to the new constructor/signature.

Verified: `./gradlew compileDebugKotlin` **PASS**. The JVM and device test sources still carry the
pre-existing, unrelated breakage in `data/jobs`/`ui/jobs` (evidence, visit-notes, Job Details) noted in
tracker 057, so the full test/lint/build sweep is Tier B for feature completion.

Next is Phase 4 (office reconciliation of ad-hoc reports): the Manager-side report list, the reconcile
decision (link/convert) and the reject route (`BR-AH-002`, `BR-AH-005` – `BR-AH-008`).
