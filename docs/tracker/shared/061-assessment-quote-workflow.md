# Tracker 061 - Assessment Quote Workflow

**Status: Phases 0-19 complete and recorded. Android Job Details and Manager Home now present quote workflow state, online quote sent/approval/rejection and PDF upload/open flows are implemented, quote workflow/PDF activity events are labeled, and quote operations are explicitly kept online-only with test coverage.**

Date: 2026-10-04

Source: product-owner request, 2026-10-04: extend Assessment Visits with a quote workflow for cases where an assessment concludes that customer approval is required before a Work Visit can be scheduled.

## 1. Context

Assessment Visit quote handling must stay separate from Job lifecycle status.

- Job status remains lifecycle-only: `NEW`, `ACTIVE`, `COMPLETED`, `CANCELED` in the current codebase. The request used `CANCELLED`; Servora currently persists and exchanges `CANCELED`.
- Visit purpose remains why the technician attends: `ASSESSMENT` or `WORK`.
- Visit status remains execution lifecycle.
- Visit outcome remains the field result. A completed Assessment Visit may record `QUOTE_REQUIRED`.
- Quote workflow is the commercial/customer-decision workflow.
- Attention signals represent what the office needs to do next.

Target business flow:

```text
Assessment Visit
-> COMPLETED + QUOTE_REQUIRED
-> Job remains ACTIVE
-> manager prepares quote externally/in Servora
-> optional PDF quote attachment
-> manager marks quote sent
-> customer decision pending
-> manager marks approved or rejected
```

Approved path:

```text
QUOTE_APPROVED
-> Job remains ACTIVE
-> JOB_WORK_READY_TO_SCHEDULE
-> manager schedules a Work Visit
-> created Visit has purpose = WORK
```

Rejected path:

```text
QUOTE_REJECTED
-> Job remains ACTIVE
-> manager gets Cancel job
-> Job becomes CANCELED only through normal confirmed Job cancellation
```

Do not add quote states to Job status. Do not automatically cancel a Job when a quote is rejected.

## 2. Phase Status

| Phase | Status | Notes |
| ----- | ------ | ----- |
| 0. Investigation only | COMPLETE | Repo traced and findings recorded in this tracker. No code changes were made. |
| 1. Domain rules | COMPLETE | Added explicit quote workflow states and quote-derived attention semantics. Persistence, documents, versioning, and decision correction workflows remain out of scope for this phase. |
| 2. Quote document model | COMPLETE | Added API-side Job document persistence, PDF-only quote document validation, and provider-neutral Job document object keys. Upload/read endpoints and quote workflow transitions remain out of scope for this phase. |
| 3. Quote versioning / replacement | COMPLETE | Added current/superseded quote document metadata and version uniqueness. Replacing a sent quote now has an explicit domain helper for `SENT -> REQUIRED`; wiring it into workflow persistence remains for the mutation phase. |
| 4. Quote decision corrections | COMPLETE | Added a domain helper that allows approved/rejected corrections only while the source Assessment Visit is known and no downstream Work Visit exists. Wiring into workflow persistence remains for the mutation phase. |
| 5. Quote workflow persistence | COMPLETE | Added current quote workflow and append-only quote history persistence with migration `0025_job_quote_workflows.sql`. Mutation routes, read projections, and document upload wiring remain out of scope for this phase. |
| 6. Assessment completion wiring | COMPLETE | Assessment Visit completion with `QUOTE_REQUIRED` now creates/resets the current quote workflow as `REQUIRED`, appends quote history, and feeds Job Details attention from persisted workflow state. Manager quote decision mutations, document upload wiring, activity projection, and Android surfaces remain out of scope for this phase. |
| 7. Quote workflow mutation routes | COMPLETE | Added manager-facing sent/approval/rejection routes, persisted transitions/history, workflow version conflict handling, and decision correction blocking once downstream Work exists. Quote document upload/read endpoints, activity projection, scheduling integration, and Android surfaces remain out of scope. |
| 8. Quote document endpoints | COMPLETE | Added manager-facing quote PDF upload/read endpoints, current/superseded document replacement semantics, and `SENT -> REQUIRED` workflow reset when a sent quote PDF is replaced. Activity projection, scheduling integration, and Android surfaces remain out of scope. |
| 9. Job Activity projection | COMPLETE | Projected quote workflow history and quote PDF upload metadata into `/jobs/:id/activity`. Scheduling integration, Job Details quote block, Manager Home persisted projection, and Android surfaces remain out of scope. |
| 10. Scheduling integration | COMPLETE | Approved quote workflows now allow the direct scheduling route to create a Work Visit and keep extra Visit scheduling aligned with Job Details readiness. Job Details quote block, Manager Home persisted projection, and Android surfaces remain out of scope. |
| 11. Job Details quote block | COMPLETE | Job Details now includes the current quote workflow, current quote PDF metadata, source Assessment Visit sequence, sent/decision metadata, version, and caller-specific allowed quote actions. Manager Home persisted projection and Android surfaces remain out of scope. |
| 12. Manager Home persisted projection | COMPLETE | Manager Home attention now derives quote workflow states from `job_quote_workflows`, so sent/approved/rejected quote attention reflects persisted commercial state instead of completed Visit outcome inference. Android surfaces remain out of scope. |
| 13. Android Job Details quote read model | COMPLETE | Android Job Details DTOs/domain/repository now parse and carry the API quote workflow/document block with focused repository coverage. UI presentation, quote mutations, PDF upload/read, activity labels, Manager Home Android presentation, and offline quote operations remain out of scope. |
| 14. Android Job Details quote presentation | COMPLETE | Job Details overview now shows the current quote workflow, source Assessment Visit, sent/decision metadata, current quote PDF metadata, and allowed quote actions as a read-only section. Android quote mutations, PDF file flows, activity labels, Manager Home Android presentation, and offline quote operations remain out of scope. |
| 15. Android quote workflow mutations | COMPLETE | Added online-only Android repository, ViewModel, navigation, and Job Details overview actions for marking quotes sent, approved, or rejected using backend-reported allowed actions and quote workflow versions. PDF file flows, activity labels, Manager Home Android presentation, and offline quote operations remain out of scope. |
| 16. Android quote PDF file flows | COMPLETE | Added online-only Android PDF picker/upload and current quote PDF read/open flows from Job Details, using backend-reported allowed actions. Activity labels, Manager Home Android presentation, and offline quote document operations remain out of scope. |
| 17. Android Job Activity quote labels | COMPLETE | Android Job Activity now parses quote workflow/PDF activity event fields, labels quote events, shows quote notes/PDF version details, and keeps quote activity in administrative history. Manager Home Android presentation and offline quote operations remain out of scope. |
| 18. Android Manager Home quote presentation | COMPLETE | Android Manager Home now recognizes persisted quote workflow attention codes for customer decision pending, work ready to schedule, and cancellation required, and presents localized labels for each. Offline quote operations remain out of scope. |
| 19. Android offline quote operation guardrails | COMPLETE | Quote workflow mutations and quote PDF file operations remain online-only; repository coverage now asserts unreachable quote workflow writes report network failure without queueing outbox operations. |

## 3. Phase 0 Findings

### 3.1 Existing Domain Shape

- Job status is lifecycle-only in `api/src/jobs/job.types.ts`: `NEW`, `ACTIVE`, `COMPLETED`, `CANCELED`.
- Visit purpose already exists in the local work: `WORK`, `ASSESSMENT`.
- Assessment outcomes already include `QUOTE_REQUIRED`.
- Completion validation already checks outcome against stored Visit purpose through `isVisitOutcomeValidForPurpose`.
- `QUOTE_REQUIRED` already keeps the Job open because non-closing outcomes leave/reopen the Job as `ACTIVE`.
- Attention is derived, not stored, in `api/src/jobs/job-attention.ts`.
- Manager Home attention kinds are projected through `api/src/home/manager-home.dto.ts`.

Relevant current attention codes:

- `JOB_QUOTE_REQUIRED`
- `JOB_WORK_RECOMMENDED`
- `JOB_NEEDS_REASSESSMENT`
- work follow-up codes such as `FOLLOW_UP_NEEDS_SCHEDULING`, `PARTS_REQUIRED`, `UNABLE_TO_COMPLETE`

### 3.2 Current Gaps

- Quote workflow state does not exist. Current `JOB_QUOTE_REQUIRED` is derived directly from the latest completed Visit outcome.
- `canScheduleVisit` currently allows scheduling when there is no selected Visit, or when a completed Work Visit expects follow-up. It does not yet allow scheduling a Work Visit after quote approval.
- There is no generic Job document model.
- Existing accepted file models are evidence-specific:
  - `job_photos`
  - `job_audio_notes`
- Current object storage is reusable at the port level, but quote PDFs need their own domain records and validators.
- Job Activity has no quote workflow or quote document event kinds yet.
- Android has models for Job Details, Manager Home, Visit Purpose, Visit Outcome, field actions, outbox, and evidence upload, but no quote workflow or Job document surface.

## 4. Proposed Domain Model

Add an explicit quote workflow state, separate from Job status:

- `REQUIRED`
- `SENT`
- `APPROVED`
- `REJECTED`

State semantics:

- `REQUIRED`: Assessment determined authorization/quote handling is required before normal Work scheduling. Attention: `JOB_QUOTE_REQUIRED`. Actions: attach/replace optional quote PDF, mark quote sent, or cancel Job through normal Job cancellation.
- `SENT`: Manager communicated the quote externally and is waiting for customer decision. Attention: `JOB_CUSTOMER_DECISION_PENDING`. Actions: mark approved, mark rejected, attach/replace quote PDF.
- `APPROVED`: Customer authorized the proposed work. Attention: `JOB_WORK_READY_TO_SCHEDULE`. Action: schedule Work Visit. The Job remains `ACTIVE`.
- `REJECTED`: Customer rejected the proposed work. Attention: `JOB_CANCELLATION_REQUIRED` or product-approved equivalent. Action: cancel Job through normal cancellation. The Job remains `ACTIVE` until then.

Do not infer quote state from whether a PDF exists. The PDF is optional.


## 5A. Phase 2 Implementation Notes

Implemented in this phase:

- Added `job_documents` to `api/src/database/schema.ts` and migration `0024_job_documents.sql`.
- Added `QUOTE` as the first Job document type.
- Added PDF-only metadata constraints for `content_type = 'application/pdf'` and positive byte size.
- Added `jobDocumentObjectKey` under the `jobs/documents/...` object-storage namespace so quote PDFs do not share evidence storage paths.
- Added `job-document.dto.ts` with PDF byte sniffing, document type validation, optional display-name validation, and focused unit coverage.

Deliberately not implemented in this phase:

- Manager-facing quote document upload/read endpoints.
- Current/superseded quote version semantics.
- Quote workflow transition mutations.
- Android document surfaces.

## 5. Proposed Persistence

Likely new tables:

- `job_quote_workflows`
  - `id`
  - `organization_id`
  - `job_id`
  - `source_visit_id`
  - `state`
  - `sent_at`
  - `sent_by_membership_id`
  - optional sent metadata, such as note or external reference
  - `decided_at`
  - `decided_by_membership_id`
  - optional decision note
  - `version`
  - `created_at`
  - `updated_at`

- `job_quote_history`
  - append-only events for required/sent/approved/rejected/corrected
  - actor, server recorded time, optional captured time, optional client operation id if offline/idempotency is approved

- `job_documents` or `job_quote_documents`
  - `id`
  - `organization_id`
  - `job_id`
  - document type such as `QUOTE`
  - quote workflow/version reference if needed
  - `object_key`
  - `content_type`
  - `byte_size`
  - `uploaded_by_membership_id`
  - `recorded_at`
  - supersession/current-version fields if replacement is supported

Preference from investigation:

- Use `ObjectStorage` for bytes.
- Do not reuse `job_photos` or `job_audio_notes`.
- Prefer `job_documents` if product expects future Job document types; prefer `job_quote_documents` if scope must remain quote-only.

## 5B. Phase 5 Implementation Notes

Implemented in this phase:

- Added `job_quote_workflows` to persist one current quote workflow per Job.
- Added `job_quote_history` as an append-only audit table for required/sent/approved/rejected/corrected quote workflow events.
- Added database constraints for quote states, sent/decision metadata pairing, nonblank notes/references, positive versions, and idempotent history client operation ids.
- Added migration `0025_job_quote_workflows.sql` and updated the Drizzle migration journal.
- Added API domain vocabulary for quote workflow history events with focused unit coverage.

Deliberately not implemented in this phase:

- Creating workflow rows automatically when an Assessment Visit completes with `QUOTE_REQUIRED`.
- Manager-facing quote sent/approval/rejection/correction mutation routes.
- Job Details and Manager Home queries that read the persisted quote workflow.
- Job Activity projection of quote history events.
- Quote document upload/read endpoints and replacement wiring into `SENT -> REQUIRED`.
- Work Visit scheduling gate from approved quotes.
- Android quote workflow/document surfaces.

## 5C. Phase 6 Implementation Notes

Implemented in this phase:

- Wired Visit completion so an Assessment Visit completed with `QUOTE_REQUIRED` creates the current quote workflow in `REQUIRED` state.
- Added append-only `REQUIRED` quote history alongside the Visit status/outcome history in the same transaction.
- Reset an existing current quote workflow back to `REQUIRED` when a later Assessment completion requires a new quote, clearing sent/decision metadata without changing Job lifecycle status.
- Updated Job Details reads to resolve the persisted current quote workflow, allowing existing attention projection to derive `JOB_QUOTE_REQUIRED` from workflow state instead of only from the latest Visit outcome.
- Added e2e coverage for the Assessment completion path and persisted quote workflow/history.

Deliberately not implemented in this phase:

- Manager-facing quote sent/approval/rejection/correction mutation routes.
- Job Details wire quote block beyond existing attention/readiness derivation.
- Manager Home persisted quote workflow projection.
- Job Activity projection of quote history events.
- Quote document upload/read endpoints and replacement wiring into `SENT -> REQUIRED`.
- Work Visit scheduling gate from approved quotes beyond the existing DTO helper awaiting approval mutation routes.
- Android quote workflow/document surfaces.


## 5D. Phase 7 Implementation Notes

Implemented in this phase:

- Added manager-facing quote workflow mutation routes:
  - `POST /jobs/:id/quote/sent`
  - `POST /jobs/:id/quote/approval`
  - `POST /jobs/:id/quote/rejection`
- Reused the existing `JOB_UPDATE` capability for quote workflow mutations, matching the tracker's interim permission decision.
- Added API request parsers for optional sent metadata, decision notes, and optional quote workflow `expectedVersion` checks.
- Persisted `REQUIRED -> SENT -> APPROVED/REJECTED` transitions on `job_quote_workflows` and appended corresponding `job_quote_history` rows.
- Wired decision corrections through the existing Phase 4 helper: `APPROVED <-> REJECTED` correction is refused once a downstream `WORK` Visit exists after the source Assessment Visit.
- Kept Job lifecycle status unchanged through sent/approved/rejected quote transitions.
- Added targeted e2e coverage for sent/approval attention, persisted metadata/history, active Job status, and correction blocking after downstream Work exists.

Deliberately not implemented in this phase:

- Quote document upload/read endpoints and replacement wiring into `SENT -> REQUIRED`.
- Job Activity projection of quote workflow history.
- Scheduling route enforcement/details beyond returning approved quote attention/readiness from the existing projection.
- Manager Home persisted quote workflow projection.
- Android quote workflow/document surfaces.
- Offline quote mutations.

Verification:

- `npm run typecheck`
- `npm run test:e2e -- visit-field-lifecycle.e2e-spec.ts`

## 6. Proposed Attention Signals

Keep:

- `JOB_QUOTE_REQUIRED`

Add:

- `JOB_CUSTOMER_DECISION_PENDING`
- `JOB_WORK_READY_TO_SCHEDULE`
- `JOB_CANCELLATION_REQUIRED` or product-approved equivalent

After quote workflow exists, quote-related attention should derive from quote workflow state, not directly from the latest `QUOTE_REQUIRED` Visit outcome.

Clear `JOB_WORK_READY_TO_SCHEDULE` when a Work Visit has been created from the approved quote.

## 7. Proposed API Contracts

Likely manager-facing endpoints:

- `POST /jobs/:id/quote/sent`
- `POST /jobs/:id/quote/approval`
- `POST /jobs/:id/quote/rejection`
- `POST /jobs/:id/quote/document`
- `GET /jobs/:id/quote/document/content`

Job Details should include a quote block:

- state
- source Assessment Visit id/sequence
- current document metadata, if present
- historical/current quote version information
- sent/decision actor and timestamps
- allowed quote actions for this caller

Scheduling from an approved quote should still use `POST /jobs/:id/visits`, but the domain/API must ensure the resulting Visit has `purposeCode = WORK`.

## 8. Android Surfaces Affected

- Job Details DTO/domain model/repository
- Job Details overview/action model
- Schedule Visit flow and action gating
- Manager Home attention kinds and card presentation
- Job Activity timeline and event labels
- PDF picker/upload flow
- PDF open/download/read flow
- permissions UI gates
- offline working-set parsing for quote state/document metadata

## 9. File and Storage Reuse

Reusable:

- API `ObjectStorage` port
- S3/MinIO adapter and config
- API multipart upload patterns
- Android Retrofit multipart upload pattern
- Android app-private file/outbox patterns if quote upload is approved as offline-capable

Not reusable directly:

- `job_photos`
- `job_audio_notes`
- evidence phase concepts
- evidence permissions
- photo/audio content validators

Quote PDFs need:

- PDF-only content validation
- quote/document object key function
- Job-level metadata row
- quote/document activity events
- quote/document permissions or approved reuse of existing manager Job permission

## 10. Offline Risks and Limits

Current offline architecture supports adopted operations only. New quote mutations must not be queued offline until their API contracts accept idempotency keys and product ownership defines conflict behavior.

Likely initial stance:

- Quote sent/approved/rejected actions: online-only unless explicitly approved for offline support.
- Quote PDF upload: online-only initially, or adopt the evidence upload pattern with a new document-specific outbox operation if product approves.
- Job Details/Manager Home can show last-known quote metadata from the working set once the DTOs include it.

## 11. Permissions Affected

Current office Job/Visit actions reuse `JOB_UPDATE` as an interim capability. That can cover quote actions initially, but the Jobs capability set remains an open product question in the existing docs.

Possible future permissions, not approved yet:

- `jobs.quotes.manage`
- `jobs.documents.upload`
- `jobs.documents.view`

Do not invent these without product approval.

## 12. Migration Impact

- Requires a new Drizzle migration after the current Visit Purpose migration boundary.
- The current tracker for Visit Purpose notes migration sequencing caveats around uncommitted `0022` and `0023` work.
- Existing Jobs with completed Assessment Visits whose outcome is `QUOTE_REQUIRED` need a backfill decision:
  - create quote workflows in `REQUIRED`, or
  - only create workflows for future completions.

## 13. Product Decisions

Decided on 2026-10-05 before starting the next implementation phase:

- Android quote sent/approved/rejected actions are online-only for now. Do not enqueue these actions offline until idempotency/conflict behavior is explicitly designed.
- Android quote PDF upload/read/open flows are online-only for now. Document replacement can reset `SENT -> REQUIRED`, so ship the online path before adopting document outbox behavior.
- Quote PDFs are replace-only for now. Do not add PDF removal in Android or API unless product later approves remove semantics.
- Reuse `JOB_UPDATE` for quote workflow and quote document actions for now. Do not introduce dedicated quote/document permissions in the next phase.

Recommended defaults, pending product approval:

- Backfill existing active Jobs whose latest completed Assessment Visit outcome is `QUOTE_REQUIRED` into quote workflow state `REQUIRED`, unless they already have a current quote workflow. This preserves office visibility for work that already needs customer authorization without changing Job lifecycle status.
- Keep quote sent metadata lightweight: optional external reference and optional note only. Do not add a dedicated email field until Servora owns quote delivery or email integration; for now, external reference can hold an email thread id, quote number, or external system reference.
- Keep quote rejection note optional in v1. A rejection is primarily a customer decision state; requiring a note would slow office cleanup and create invented text. Encourage notes in UI later, but do not block the workflow.
- Approve `JOB_CANCELLATION_REQUIRED` as the attention code for rejected quotes. It says exactly what the office must do next while preserving the rule that quote rejection does not automatically cancel the Job.
- Keep `job_documents` generic rather than quote-specific. Quote PDFs are the first Job document type, but the storage/version/current-document rules are useful for future office documents and should not be duplicated.

Still needed after product review:

- Confirm or revise the recommended defaults above.

## 14. Implementation Notes for Next Chat

- Start with Phase 1 documentation/domain rules before persistence or UI.
- Do not put quote state on `jobs.status`.
- Do not infer quote state from a PDF.
- Do not auto-cancel on quote rejection.
- Do not expose Schedule Work Visit until quote state is `APPROVED`.
- If replacing a quote after `SENT`, transition the workflow back to `REQUIRED` unless product approves a different state.
- If an approved quote already created a Work Visit, later decision corrections must not silently cancel/delete/alter that Visit.
- Keep Activity/history append-only.
- Keep current quote document visible in Job Details, not only buried in Activity.

## 15. Phase 1 Completion Notes

Completed on 2026-10-04.

Implemented domain-level quote workflow rules without adding persistence or routes:

- Added explicit quote workflow state vocabulary in `api/src/jobs/job.types.ts`: `REQUIRED`, `SENT`, `APPROVED`, `REJECTED`.
- Kept the quote workflow separate from Job lifecycle status, Visit purpose, and Visit outcome.
- Added quote-derived attention semantics in `api/src/jobs/job-attention.ts`:
  - `REQUIRED` -> `JOB_QUOTE_REQUIRED`
  - `SENT` -> `JOB_CUSTOMER_DECISION_PENDING`
  - `APPROVED` -> `JOB_WORK_READY_TO_SCHEDULE`
  - `REJECTED` -> `JOB_CANCELLATION_REQUIRED`
- When a quote workflow is supplied to attention derivation, quote attention is derived from the workflow state instead of inferring state from the latest `QUOTE_REQUIRED` Visit outcome.
- Approved quote scheduling attention clears once a later active `WORK` Visit exists after the source Assessment Visit.
- Job Details projection now accepts an optional quote workflow input so later persistence work can feed the domain rules without changing the attention contract again.
- Manager Home attention vocabulary/ranking now includes the new quote workflow attention codes.

Verification:

- `npm test -- job-attention.spec.ts job.types.spec.ts manager-home.dto.spec.ts job-details.dto.spec.ts`
- `npm run typecheck`

Out of scope for Phase 1 and intentionally left for later phases:

- `job_quote_workflows` persistence and history.
- Quote document/PDF storage.
- Quote mutation endpoints.
- Android quote workflow surfaces.
- Offline quote mutations.


## 16. Phase 3 Completion Notes

Completed on 2026-10-04.

Implemented quote document versioning rules at the persistence/domain boundary:

- Extended `job_documents` with `document_version`, `is_current`, `superseded_at`, and `superseded_by_job_document_id`.
- Added a partial unique index so each Job has at most one current document for a document type.
- Added a version unique index so preserved quote versions cannot reuse the same Job/document-type version number.
- Added supersession consistency checks: current rows cannot point at replacement metadata, and superseded rows must point at the replacing document and the supersession time.
- Added `quoteWorkflowStateAfterQuoteDocumentReplacement`, capturing the Phase 3 rule that replacing a quote in `SENT` moves the workflow back to `REQUIRED` so the replacement does not inherit sent/customer-delivery state.

Deliberately still out of scope:

- Upload/replacement endpoints that perform the current-row swap in a transaction.
- Quote workflow persistence that stores the `SENT -> REQUIRED` transition.
- Quote activity/history events for document replacement.

## 17. Phase 4 Completion Notes

Completed on 2026-10-04.

Implemented quote decision correction rules at the domain boundary:

- Added `JobQuoteDecisionState`, `isJobQuoteDecisionState`, and `canCorrectJobQuoteDecision` in `api/src/jobs/job.types.ts`.
- Corrections are allowed only from decided quote states: `APPROVED` or `REJECTED`.
- The helper refuses correction when the source Assessment Visit is unavailable, because the API cannot safely prove whether downstream Work was created.
- The helper refuses correction once any later `WORK` Visit exists after the source Assessment Visit. It does not cancel, delete, rewrite, or otherwise alter that Visit.

Deliberately still out of scope:

- Quote workflow persistence and mutation endpoints that call this helper.
- Append-only quote decision correction history/activity events.
- Android quote correction actions and UI.
- Offline quote decision corrections.

Verification:

- `npm test -- job.types.spec.ts job-document.dto.spec.ts`



## 5E. Phase 8 Implementation Notes

Implemented in this phase:

- Added manager-facing quote document routes:
  - `POST /jobs/:id/documents`
  - `GET /jobs/:id/documents/quote/content`
  - `GET /jobs/:id/documents/:documentId/content`
- Reused the existing `JOB_UPDATE` capability for quote document upload/read because quote PDFs are office/commercial Job artifacts, not field evidence.
- Stored quote PDFs through `ObjectStorage` using the provider-neutral `jobs/documents/...` keyspace and persisted metadata in `job_documents`.
- Preserved current/superseded quote document metadata and incremented `document_version` on replacement.
- Wired sent-quote replacement so replacing the current quote PDF resets the quote workflow from `SENT` back to `REQUIRED`, clears sent metadata, increments workflow version, and appends quote history.
- Added focused e2e coverage for upload/read, replacement/supersession, workflow reset, missing workflow refusal, and PDF validation.

Deliberately not implemented in this phase:

- Job Activity projection of quote workflow/document events.
- Scheduling route enforcement/details beyond existing approved quote readiness projection.
- Manager Home persisted quote workflow projection.
- Android quote workflow/document surfaces.


## 5F. Phase 9 Implementation Notes

Implemented in this phase:

- Added quote workflow history events to Job Activity:
  - `JOB_QUOTE_REQUIRED`
  - `JOB_QUOTE_SENT`
  - `JOB_QUOTE_APPROVED`
  - `JOB_QUOTE_REJECTED`
  - `JOB_QUOTE_CORRECTED`
- Added quote PDF upload metadata to Job Activity as `JOB_QUOTE_DOCUMENT_UPLOADED`.
- Added nullable quote workflow/document fields to each activity event, preserving the existing fixed-shape event contract.
- Kept quote workflow events tied to the source Assessment Visit sequence while quote PDF document uploads remain Job-level commercial artifacts.
- Updated the `/jobs/:id/activity` API contract and added e2e coverage for quote workflow/document projection.

Deliberately not implemented in this phase:

- Scheduling route enforcement/details beyond existing approved quote readiness projection.
- Manager Home persisted quote workflow projection.
- Android quote workflow/document/activity surfaces.

## 5G. Phase 10 Implementation Notes

Implemented in this phase:

- Added write-side scheduling eligibility for the direct Visit creation route so it matches Job Details readiness instead of only checking the Job row.
- Allowed approved quote workflows to create a later Work Visit through POST /jobs/:id/visits.
- Kept quote approval separate from Job lifecycle status and refused extra direct Visit scheduling when the Visit shape is not an initial, follow-up, or approved-quote scheduling opportunity.
- Added e2e coverage that an approved Assessment quote creates a scheduled Work Visit.

Deliberately not implemented in this phase:

- Manager Home persisted quote workflow projection.
- Android quote workflow/document/activity surfaces.
- Offline quote mutations.

Verification:

- npm run test:e2e -- visit-requests.e2e-spec.ts


## 5H. Phase 11 Implementation Notes

Implemented in this phase:

- Added a structured `quote` block to Job Details.
- Projected current quote workflow state, workflow version, source Assessment Visit id/sequence, sent metadata, decision metadata, and the current quote PDF document metadata.
- Added caller-specific `allowedActions` for quote document read/upload, sent marking, approval, rejection, and permitted decision correction using the existing `JOB_UPDATE` capability.
- Kept quote workflow state separate from Job lifecycle status and preserved existing attention/readiness derivation.
- Updated the Job Details API contract and unit coverage for quote projection and correction gating after downstream Work exists.

Deliberately not implemented in this phase:

- Manager Home persisted quote workflow projection.
- Android quote workflow/document surfaces.
- Offline quote mutations.

Verification:

- `npm test -- job-details.dto.spec.ts`
- `npm run typecheck`

## 5I. Phase 12 Implementation Notes

Implemented in this phase:

- Updated Manager Home's operational attention read to join the current persisted `job_quote_workflows` row for active Jobs.
- Fed the persisted quote workflow state/source Assessment Visit into `deriveJobAttention`, preserving the existing attention ordering and scheduling suppression behavior.
- Kept quote workflow state separate from Job lifecycle status and Visit outcome inference.
- Added e2e coverage proving a persisted `SENT` quote projects `JOB_CUSTOMER_DECISION_PENDING`, while an approved quote with a later scheduled Work Visit no longer appears as ready to schedule.

Deliberately not implemented in this phase:

- Android quote workflow/document/activity surfaces.
- Offline quote mutations.

Verification:

- `npm run test:e2e -- manager-home.e2e-spec.ts`
- `npm run typecheck`

## 5J. Phase 13 Implementation Notes

Completed on 2026-10-05.

Implemented in this phase:

- Added Android Job Details DTO support for the current `quote` block and current quote PDF metadata.
- Added Android domain read models for quote workflow state, allowed quote actions, current quote document metadata, and the Job's current quote workflow.
- Mapped Job Details quote data through `DefaultJobDetailsRepository`, keeping quote state separate from Job lifecycle status, Visit purpose, and Visit outcome.
- Kept older cached/working-set Job Details payloads readable by defaulting the quote block to `null`.
- Ignored unknown future quote action codes rather than drawing commands this build cannot name; unknown quote workflow states still fail the read as a contract mismatch.
- Added focused Android repository coverage for quote workflow/document mapping and older no-quote payloads.

Deliberately not implemented in this phase:

- Job Details quote block UI presentation.
- Quote sent/approval/rejection actions from Android.
- Quote PDF upload/read/open flows.
- Android Job Activity quote event labels.
- Manager Home Android presentation changes for the newly persisted quote attention states.
- Offline quote mutations or quote document uploads.

Verification:

- `./gradlew testDebugUnitTest --tests com.servora.android.data.jobs.JobDetailsRepositoryTest`

## 5K. Phase 14 Implementation Notes

Completed on 2026-10-05.

Implemented in this phase:

- Added a read-only Android Job Details quote section to the overview screen.
- Presented the current quote workflow state, source Assessment Visit sequence, sent timestamp, external reference, decision timestamp, current quote PDF name/version/size, and backend-reported allowed quote actions.
- Kept quote display derived from the API quote block only; the UI does not infer quote state from whether a PDF exists.
- Added localized English/French strings and focused Compose coverage for a sent quote with a current PDF.

Deliberately not implemented in this phase:

- Quote sent/approval/rejection actions from Android.
- Quote PDF upload/read/open flows.
- Android Job Activity quote event labels.
- Manager Home Android presentation changes for the newly persisted quote attention states.
- Offline quote mutations or quote document uploads.

Verification:

- `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` now compiles the edited main source and `JobDetailsOverviewScreenTest`; the target still fails on pre-existing Android-test drift in `TechnicianHomeScreenTest`, `JobDetailsScreenTest`, `ServoraHomeNavigationTest`, and `ScheduleScreenTest`.

## 5L. Phase 15 Implementation Notes

Completed on 2026-10-05.

Implemented in this phase:

- Added Android DTO/API/repository support for the manager quote workflow routes:
  - `POST /jobs/:id/quote/sent`
  - `POST /jobs/:id/quote/approval`
  - `POST /jobs/:id/quote/rejection`
- Kept Android quote workflow mutations online-only: network failures are reported through the existing `JobActionResult.Failure` path and are not queued in the outbox.
- Sent the quote workflow `expectedVersion` from the current Job Details quote block so stale quote actions are refused by the API instead of overwritten.
- Wired Job Details ViewModel and navigation actions for mark sent, mark approved, and mark rejected, gated by backend-reported `allowedActions`.
- Changed the Job Details quote section so workflow actions render as actual buttons while PDF upload/read actions remain display-only until file flows are implemented.
- Added focused Android repository coverage for sent/approval/rejection request payloads and refreshed quote states.

Deliberately not implemented in this phase:

- Quote PDF upload/read/open flows.
- Prompting for optional sent metadata or decision notes from the UI; Android currently sends no note/reference from the overview buttons.
- Android Job Activity quote event labels.
- Manager Home Android presentation changes for the newly persisted quote attention states.
- Offline quote mutations or quote document uploads.

Verification:

- `./gradlew :app:testDebugUnitTest --tests com.servora.android.data.jobs.JobDetailsRepositoryTest`
- `./gradlew :app:compileDebugAndroidTestKotlin` still fails on pre-existing Android-test drift in `TechnicianHomeScreenTest`, `JobDetailsScreenTest`, `ServoraHomeNavigationTest`, and `ScheduleScreenTest` (signature/fake updates outside this phase's quote workflow surface).


## 18. Phase 16 Completion Notes

Completed on 2026-10-05.

Implemented Android online quote PDF file flows:

- Added Retrofit support for `POST /jobs/:id/documents` and `GET /jobs/:id/documents/quote/content`.
- Added a quote PDF document reader/opener that reads the selected PDF through the device content resolver, validates the PDF header before upload, stages downloaded PDFs in app-private cache storage, and opens them through the existing `FileProvider` grant flow.
- Wired Job Details ViewModel actions for online-only quote PDF upload and open/read, with explicit failure/message states and no offline queueing.
- Turned Job Details quote document allowed actions into real Upload/Open buttons, backed by the device document picker and backend read route.
- Added localized strings for quote PDF upload/open success and failure outcomes.

Verification:

- `./gradlew :app:compileDebugKotlin` passes.
- `./gradlew :app:testDebugUnitTest` compiles unit tests but the suite has pre-existing/unrelated failures in photo capture and technician schedule presentation tests.
- `./gradlew :app:compileDebugAndroidTestKotlin` is blocked by existing unrelated Android test compile drift around visit-note/ad-hoc-report signatures and fakes.

Deliberately still out of scope:

- Offline quote PDF upload/read/open queues or caches.
- PDF removal semantics.
- Job Activity Android labels for quote events.
- Manager Home Android quote attention presentation.

## 19. Phase 17 Completion Notes

Completed on 2026-10-05.

Implemented Android Job Activity quote labels and read support:

- Added Android Job Activity enum/DTO/domain fields for quote workflow events:
  - `JOB_QUOTE_REQUIRED`
  - `JOB_QUOTE_SENT`
  - `JOB_QUOTE_APPROVED`
  - `JOB_QUOTE_REJECTED`
  - `JOB_QUOTE_CORRECTED`
  - `JOB_QUOTE_DOCUMENT_UPLOADED`
- Carried quote workflow state fields, quote notes, and quote PDF document metadata from the activity API into the Android domain model.
- Added timeline labels for quote workflow and quote PDF upload events, including optional quote note content and quote PDF name/version details.
- Classified quote workflow/PDF activity as administrative history rather than primary field-work activity, preserving the existing Job Details activity split.
- Added localized English/French strings and focused unit coverage for quote activity labels and repository mapping.

Verification:

- `./gradlew :app:testDebugUnitTest --tests com.servora.android.ui.jobs.JobActivityTitleTest --tests com.servora.android.data.jobs.JobDetailsRepositoryTest`

Deliberately still out of scope:

- Manager Home Android presentation changes for quote attention states.
- Offline quote mutations or quote PDF upload/read/open queues or caches.
- Quote PDF removal semantics.

## 20. Phase 18 Completion Notes

Completed on 2026-10-05.

Implemented Android Manager Home quote attention presentation:

- Added Android Manager Home domain support for persisted quote workflow attention codes:
  - `JOB_CUSTOMER_DECISION_PENDING`
  - `JOB_WORK_READY_TO_SCHEDULE`
  - `JOB_CANCELLATION_REQUIRED`
- Added localized English/French Manager Home labels for sent, approved, and rejected quote workflow attention.
- Kept Manager Home's contract-mismatch behavior: unknown future attention kinds still fail the read instead of being silently dropped.
- Added focused repository coverage that the quote workflow attention codes map from the API payload.

Verification:

- `./gradlew :app:testDebugUnitTest --tests com.servora.android.data.home.ManagerHomeRepositoryTest`

Deliberately still out of scope:

- Offline quote mutations or quote PDF upload/read/open queues or caches.
- Quote PDF removal semantics.

## 21. Phase 19 Completion Notes

Completed on 2026-10-05.

Recorded and covered the Android offline quote operation stance:

- Kept quote sent/approval/rejection mutations online-only; they report backend/network outcomes directly and do not create outbox rows.
- Kept quote PDF upload/read/open online-only; downloaded PDFs are staged only for opening and are not a working-set/offline document cache.
- Added repository coverage proving an unreachable quote workflow mutation reports `NETWORK` and leaves the outbox empty.

Verification:

- `./gradlew :app:testDebugUnitTest --tests com.servora.android.data.jobs.JobDetailsRepositoryTest`

Deliberately still out of scope:

- Offline quote workflow mutations.
- Offline quote PDF upload/read/open queues or caches.
- Quote PDF removal semantics.

