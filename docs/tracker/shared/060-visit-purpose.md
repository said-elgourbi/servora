# Tracker 060 - Visit Purpose

**Status: Phases 2–6 implemented and verified locally (persistence, API wiring, operational rules, Android scheduling purpose selector, Android technician completion outcome filter). Phase 1 docs are committed/pushed; Phases 2–6 remain in the working tree, not committed because they sit after an existing uncommitted `0022_visits_ad_hoc_work_flag` migration.**

Date: 2026-10-03

Source: product-owner request, 2026-10-03: introduce Visit Purpose so assessment visits are not modelled as failed or incomplete work visits.

## 1. Product decisions

- Canonical term: **Visit Purpose**.
- Purpose codes: `WORK`, `ASSESSMENT`.
- Direct scheduling defaults to `WORK`; `ASSESSMENT` is an intentional override.
- Purpose can change only while a Visit is still `SCHEDULED`; after execution starts it is immutable.
- Assessment outcomes are `NO_WORK_REQUIRED`, `WORK_RECOMMENDED`, `QUOTE_REQUIRED`, `UNABLE_TO_ASSESS`.
- `ASSESSMENT_COMPLETE` is deliberately not modelled; `COMPLETED` already says the assessment visit finished.
- Assessment recommendations do not use the existing work follow-up request flow.
- A Visit created from an approved work follow-up request is always `WORK`.
- Ad-hoc work reconciliation is always `WORK`.

## 2. Phase status

| Phase | Status | Notes |
| ----- | ------ | ----- |
| 0. Investigation and impact analysis | COMPLETE | Impact report delivered before implementation. |
| 1. Domain rules and documentation | COMPLETE, COMMITTED | Commit `fee9ff4 docs: define visit purpose semantics`, pushed to `origin/updated-business-rules`. |
| 2. Persistence and migration | IMPLEMENTED LOCALLY | Added `visits.purpose_code`, purpose/outcome DB checks, widened Visit outcome history checks, migration `0023_visit_purpose.sql`, and purpose vocabulary constants/tests. Not committed because `_journal.json` also contains pre-existing uncommitted `0022_visits_ad_hoc_work_flag`. |
| 3. API contracts and validation | IMPLEMENTED LOCALLY | Direct Visit creation accepts `purposeCode` and defaults to `WORK`; Job Details, Schedule, Manager Home and Technician Home project `purposeCode`; completion validates outcome against the stored Visit purpose and returns `VISIT_OUTCOME_INVALID_FOR_PURPOSE` for mismatches. Follow-up approval and ad-hoc reconciliation explicitly write `WORK`. |
| 4. Job derivation and operational rules | IMPLEMENTED LOCALLY | `NO_WORK_REQUIRED` is a closing outcome; assessment recommendations keep/reopen Jobs as `ACTIVE` and derive `JOB_WORK_RECOMMENDED`, `JOB_QUOTE_REQUIRED` or `JOB_NEEDS_REASSESSMENT` attention without exposing follow-up-request semantics. |
| 5. Android scheduling/create/edit UI | IMPLEMENTED LOCALLY | New `VisitPurpose` domain enum; the shared `ScheduleVisitSheet` gains a segmented Work/Assessment selector with Work default and localized EN/FR labels, shown only for direct scheduling (`POST /jobs/:id/visits`). `purposeCode` is threaded through `CreateVisitRequestDto`, `JobDetailsRepository.createVisit`, `JobDetailsViewModel.scheduleVisit`, `JobActionRequest.CreateVisit` and `PendingJobAction.CreateVisit`. Follow-up approval and ad-hoc reconciliation keep the selector hidden and stay `WORK`. |
| 6. Technician Visit completion UX | IMPLEMENTED LOCALLY | `VisitOutcome` gains the four assessment outcomes beside the work ones, with `workOptions`/`assessmentOptions` and `visitOutcomesForPurpose(purpose)`; the completion sheet filters its picker to the Visit's purpose, and the ad-hoc form keeps offering work outcomes only. `purposeCode` is threaded through `JobDetailsVisitDto`/`JobDetailsVisitSummaryDto`, `JobDetailsVisit`/`JobDetailsVisitSummary` (default `WORK` for predating payloads) and both completion-sheet call sites. Offline replay keeps sending whatever outcome was queued and the server stays authoritative for the purpose/outcome pair. |
| 7. Job Details / Visit cards / history | TODO | Present purpose where it materially clarifies history and activity. |
| 8. Schedule/Home projections | PARTIAL | API projections now include `purposeCode`; Android/UI rendering remains TODO. |
| 9. Tests | PARTIAL | `JobDetailsRepositoryTest` (forwards `WORK` and `ASSESSMENT` on create; maps `purposeCode` to `VisitPurpose` and defaults absent to `WORK` on read) and `JobDetailsViewModelTest` (records purpose through the CreateVisit action, conflict resend included) updated and passing. New `domain/model/VisitPurposeTest` pins `visitOutcomesForPurpose`, `visitPurposeOrNull` and `visitOutcomeOrNull` for the assessment codes. A `JobDetailsOverviewScreenTest` device test asserts the selector shows Work selected by default. A device test asserting the completion sheet filters outcomes by purpose is still TODO (device QA). The androidTest module still fails to compile on **pre-existing** uncommitted ad-hoc-work errors (see §5), unrelated to this phase. |
| 10. Verification and cleanup | TODO | Full Tier checks after API/Android phases complete. |

## 3. Current local implementation notes

- `api/src/jobs/job.types.ts` now separates work outcomes and assessment outcomes while keeping one stored `VISIT_OUTCOME_CODES` vocabulary.
- `expectsFollowUpVisit` remains work-only, so assessment outcomes do not accidentally expose the existing work follow-up request flow.
- `api/src/jobs/jobs.service.ts` validates completion with `isVisitOutcomeValidForPurpose(locked.purposeCode, input.outcomeCode)` before writing the completion.
- Existing historical/direct default behaviour remains `WORK`.
- Job consequence and attention semantics are now purpose-aware: closing outcomes are explicit (`RESOLVED`, `NO_WORK_REQUIRED`), while assessment recommendation outcomes produce office attention codes and do not opt into the work follow-up request flow.
- Android (Phase 5): `domain/model/VisitPurpose.kt` carries the `WORK`/`ASSESSMENT` vocabulary; `ScheduleVisitSheet` renders a `SingleChoiceSegmentedButtonRow` purpose selector behind `showPurposeSelector` (true only for direct scheduling), holding the choice as the wire code so it survives configuration changes; `CreateVisitRequestDto.purposeCode` defaults to `WORK` and the repository sends `purpose.name`. The follow-up approval sheet (`ScheduleScreen`, `ManagerFollowUpRequestDetailsScreen`) passes `showPurposeSelector = false` and ignores the purpose, so a follow-up Visit stays `WORK`.
- Android (Phase 6): `domain/model/VisitOutcome.kt` now carries the four assessment outcomes (`NO_WORK_REQUIRED`, `WORK_RECOMMENDED`, `QUOTE_REQUIRED`, `UNABLE_TO_ASSESS`) beside the four work ones, with `workOptions`/`assessmentOptions` and `visitOutcomesForPurpose(purpose)`. `VisitCompletionSheet` filters its picker through `visitOutcomesForPurpose(visit.purpose)`, the two completion call sites pass `details.selectedVisit.purpose`, and `AdHocWorkReportForm` iterates `VisitOutcome.workOptions` so ad-hoc reporting (always `WORK`) never offers assessment conclusions. `purposeCode` is read from `JobDetailsVisitDto`/`JobDetailsVisitSummaryDto` and mapped with `visitPurposeOrNull(...) ?: VisitPurpose.WORK`. Labels, supporting text and the reason-vs-summary wording (`UNABLE_TO_ASSESS` is asked for as a reason, mirroring `UNABLE_TO_COMPLETE`) are localized EN/FR. The offline replay (`VisitFieldActionHandler`) already sends whatever outcome was queued, so an assessment completion queues and replays unchanged; the API remains the authority for the purpose/outcome pair (`BR-007`).

## 4. Verification completed for Phase 3/4 local work

- Phase 3: `npm test -- src/jobs/job.types.spec.ts src/jobs/follow-up-visit-request.dto.spec.ts src/jobs/job-details.dto.spec.ts src/schedule/schedule.dto.spec.ts src/home/manager-home.dto.spec.ts src/home/technician-home.dto.spec.ts`
- Phase 4: `npm test -- src/jobs/job.types.spec.ts src/jobs/visit-job-consequence.spec.ts src/jobs/job-attention.spec.ts src/home/manager-home.dto.spec.ts`
- `npm run typecheck`
- `git diff --check` for the touched API files

## 5. Phase 5 verification (Android)

- `cd android && ./gradlew compileDebugKotlin` — **PASS** (main sources).
- `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.data.jobs.JobDetailsRepositoryTest' --tests 'com.servora.android.ui.jobs.JobDetailsViewModelTest'` — **PASS** (59 + 72 tests, 0 failures, 0 errors).
- `./gradlew assembleDebugAndroidTest` — **BLOCKED** by pre-existing androidTest compile errors from the uncommitted ad-hoc-work slice (e.g. `JobDetailsOverviewScreenTest` missing `onEditVisitNote`/`onRemoveVisitNote`/`onOpenVisit`/`canManageVisitNotes`, `FakeJobDetailsRepository` missing `editVisitNote`/`removeVisitNote`). None of those errors are in code this phase touched. Device QA for the selector is the product owner's (`qa.md` §7.3).

## 6. Phase 6 verification (Android technician completion UX)

- `cd android && ./gradlew compileDebugKotlin` — **PASS** (main sources; only a pre-existing `ScheduleScreen` deprecation warning).
- `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.domain.model.VisitPurposeTest' --tests 'com.servora.android.data.jobs.JobDetailsRepositoryTest' --tests 'com.servora.android.ui.jobs.JobDetailsViewModelTest' --tests 'com.servora.android.ui.jobs.JobDetailsOverviewModelTest'` — **PASS** (`VisitPurposeTest` 5 tests, `JobDetailsRepositoryTest` 61 tests; 0 failures, 0 errors).
- The completion-sheet purpose filter is a Compose UI behaviour; its device assertion is still TODO and is the product owner's (`qa.md` §7.3).

## 7. Commit boundary caveat

The working tree already contained unrelated/uncommitted ad-hoc work changes before Visit Purpose Phase 2/3 started. In particular, migration journal entry `0022_visits_ad_hoc_work_flag` and file `api/drizzle/migrations/0022_visits_ad_hoc_work_flag.sql` are uncommitted. The Visit Purpose migration is `0023_visit_purpose`, so committing/pushing it independently should wait until the `0022` migration boundary is resolved or intentionally included by the owner.
