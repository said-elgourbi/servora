package com.servora.android.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.schedule.AdHocReportJobOptionsResult
import com.servora.android.data.schedule.AdHocReviewDataResult
import com.servora.android.data.schedule.ResolveAdHocLocationRequestDto
import com.servora.android.data.schedule.AdHocWorkReportReconcileResult
import com.servora.android.data.schedule.AdHocWorkReportRejectResult
import com.servora.android.data.schedule.AdHocWorkReportsRepository
import com.servora.android.data.schedule.AdHocWorkReportsResult
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
import com.servora.android.data.schedule.VisitRequestReviewResult
import com.servora.android.data.schedule.VisitRequestsRepository
import com.servora.android.data.schedule.VisitRequestsResult
import com.servora.android.domain.model.AdHocWorkReport
import com.servora.android.domain.model.AdHocWorkReportStatus
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.TechnicianAssignment
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the manager schedule.
 *
 * The ViewModel decides nothing about the schedule: it asks [ScheduleRepository] for the day the
 * manager is looking at and reports what came back. Which Visits a day holds, who is assigned, which
 * work has no crew and whether a Visit is overdue are the backend's answers (`BR-001`, `BR-042`).
 *
 * The day, the lane and the technician filter are the screen's **own** state, because they are the
 * manager's choices rather than facts about the operation. The screen holds the selected date and
 * asks the API about it, so the day the API resolves and the day on screen are one (`BR-041`).
 *
 * The last answer the backend reported is held for as long as the session lasts, so returning to the
 * screen shows what is known and refreshes behind it (`BR-013`). It is not written to disk: the
 * manager schedule is a read over the operation's own work, and the working set
 * (`docs/architecture/offline-first-architecture.md` §2) is the technician's.
 *
 * Reviewing a follow-up request is what the screen **writes**: a request is clarified, rejected, or
 * approved into a scheduled Visit (`BR-FV-004`, `BR-FV-005`). Each decision states its own record — a
 * clarification says what is still needed and a rejection says why it was refused (`BR-FV-013`) — and
 * the review routes answer with what the backend now holds. A decision that changed the requests
 * replaces the request the lane was holding, so what the manager sees is the backend's own state rather
 * than a patched copy (`BR-001`).
 */
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val visitRequestsRepository: VisitRequestsRepository,
    /**
     * The approval's own route (`BR-FV-005`).
     *
     * Approving a request creates a scheduled Visit on a Job, which is a Job/Visit management action and
     * answers with the Job as it now stands — so it goes through the repository that already owns Visit
     * scheduling and `BR-070`'s conflict answers rather than through a second implementation of them
     * (`BR-041`).
     */
    private val jobDetailsRepository: JobDetailsRepository,
    private val adHocWorkReportsRepository: AdHocWorkReportsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    /**
     * Identifies the read a result belongs to.
     *
     * Every read takes the next generation, so an answer that arrives after the manager has already
     * moved to another day or another filter is dropped rather than written over the day now on
     * screen (`BR-067`).
     */
    private var generation = 0

    /**
     * Opens the screen: selects [today] the first time and reads the day.
     *
     * The device's zone and its first day of the week come from the screen, because they are the
     * facts the day and the strip are drawn from (`BR-041`). Coming back to the destination keeps
     * the day the manager had selected and refreshes it.
     */
    fun start(today: LocalDate, timeZoneId: String, firstDayOfWeek: DayOfWeek) {
        val hadADate = _uiState.value.selectedDate != null
        _uiState.update {
            it.copy(timeZoneId = timeZoneId, firstDayOfWeek = firstDayOfWeek)
        }
        if (hadADate) {
            read()
            readRequests()
            readAdHocReports()
        } else {
            selectDate(today, firstDayOfWeek)
        }
    }

    /** Re-runs the read after a failure the screen reported. */
    fun retry() {
        read()
        readRequests()
        readAdHocReports()
    }

    /** Reads requests for a manager request-details destination opened outside Schedule. */
    fun openRequests(timeZoneId: String) {
        _uiState.update { it.copy(timeZoneId = timeZoneId) }
        readRequests()
    }

    /** Reads ad-hoc reports for a manager report-details destination opened outside Schedule. */
    fun openAdHocReports(timeZoneId: String) {
        _uiState.update { it.copy(timeZoneId = timeZoneId) }
        readAdHocReports()
    }


    /**
     * Selects [date] and reads its schedule.
     *
     * The week strip moves to the week the date is in, so tapping a day never leaves the strip
     * showing a week the agenda is not about (`BR-041`).
     */
    fun selectDate(date: LocalDate, firstDayOfWeek: DayOfWeek) {
        if (
            _uiState.value.selectedDate == date &&
            _uiState.value.displayedWeekStart == weekStartOf(date, firstDayOfWeek)
        ) {
            return
        }
        _uiState.update {
            it.copy(
                firstDayOfWeek = firstDayOfWeek,
                selectedDate = date,
                displayedWeekStart = weekStartOf(date, firstDayOfWeek),
            )
        }
        read()
        readRequests()
        readAdHocReports()
    }

    /**
     * Shows [weekStart] in the strip without changing the selected day.
     *
     * Swiping moves the strip through nearby weeks; the agenda keeps describing the selected day
     * until a day in another week is tapped, so a swipe never reads a day the manager did not choose.
     */
    fun showWeek(weekStart: LocalDate) {
        if (_uiState.value.displayedWeekStart == weekStart) {
            return
        }
        _uiState.update { it.copy(displayedWeekStart = weekStart) }
    }

    /** Shows the other lane. Both lanes come from the same read, so no request is made (`BR-042`). */
    fun selectLane(lane: ScheduleLane) {
        if (_uiState.value.lane == lane) {
            return
        }
        _uiState.update { it.copy(lane = lane) }
    }

    /**
     * Returns a pending request for clarification, recording [note] as what is still needed
     * (`BR-FV-004`, `BR-FV-013`).
     *
     * The request stays in the lane: the API keeps a `NEEDS_CLARIFICATION` request reviewable and
     * `BR-FV-012` keeps it unresolved until it is approved or rejected, so clarifying a request is not a
     * decision that closes it.
     */
    fun askForClarification(request: FollowUpVisitRequest, note: String) {
        review(request) { visitRequestsRepository.askForClarification(it, note) }
    }

    /**
     * Rejects a follow-up request, recording [note] as why it was refused (`BR-FV-004`, `BR-FV-013`).
     *
     * A rejection closes the request without creating a Visit, so the note is the only record of the
     * reason and the confirmation the screen takes it in requires one.
     */
    fun rejectRequest(request: FollowUpVisitRequest, note: String) {
        review(request) { visitRequestsRepository.reject(it, note) }
    }

    /**
     * Opens the approval form for [request] and reads the technicians it composes a crew from
     * (`BR-FV-004`, `BR-FV-005`, `BR-024`).
     *
     * The request is not decided here: the manager states the schedule and the crew the Visit will
     * carry, and [approveRequest] sends that decision.
     */
    fun beginApproval(request: FollowUpVisitRequest) {
        _uiState.update {
            it.copy(
                schedulingRequestId = request.id,
                approvalFailure = null,
                pendingApproval = null,
            )
        }
        loadAssignableTechnicians()
    }

    /** Closes the approval form, forgetting the decision it was holding (`BR-070`, `BR-067`). */
    fun dismissApproval() {
        _uiState.update {
            it.copy(
                schedulingRequestId = null,
                pendingApproval = null,
                approvalFailure = null,
            )
        }
    }

    /** Reads the organization's technicians, for the approval form to offer (`BR-024`). */
    fun loadAssignableTechnicians() {
        _uiState.update { it.copy(assignableTechnicians = null, assignableFailure = null) }
        viewModelScope.launch {
            when (val result = jobDetailsRepository.loadAssignableTechnicians()) {
                is AssignableTechniciansResult.Success ->
                    _uiState.update {
                        it.copy(
                            assignableTechnicians = result.technicians,
                            assignableFailure = null,
                        )
                    }

                is AssignableTechniciansResult.Failure ->
                    _uiState.update {
                        it.copy(
                            assignableTechnicians = null,
                            assignableFailure = result.reason,
                        )
                    }
            }
        }
    }

    /**
     * Approves [request], scheduling the Visit it asks for with the window and crew the reviewer stated
     * (`BR-FV-005`, `BR-FV-010`).
     *
     * The reviewer's decision is what is sent, so an approval can differ from the technician's proposal
     * (`BR-FV-003`); the request is named by its own status and version, so a request that moved on is
     * refused rather than decided about (`BR-086`). A submission already in flight is not sent twice
     * (`BR-031`).
     */
    fun approveRequest(
        request: FollowUpVisitRequest,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        assignments: List<TechnicianAssignment>,
    ) {
        if (_uiState.value.isApproving) {
            return
        }
        submitApproval(
            request = request,
            scheduledStart = scheduledStart,
            scheduledEnd = scheduledEnd,
            assignments = assignments,
            confirmed = false,
        )
    }

    /** Resends the approval the API refused until its conflicts are accepted (`BR-070`). */
    fun confirmApproval() {
        val pending = _uiState.value.pendingApproval ?: return
        val request = _uiState.value.reviewableRequests.firstOrNull { it.id == pending.requestId }
        if (request == null) {
            // The request is no longer one the screen holds for review, so there is nothing to approve:
            // the form closes and the requests are read again (`BR-001`, `BR-FV-012`).
            dismissApproval()
            readRequests()
            return
        }
        submitApproval(
            request = request,
            scheduledStart = pending.scheduledStart,
            scheduledEnd = pending.scheduledEnd,
            assignments = pending.assignments,
            confirmed = true,
        )
    }

    /** Leaves the conflicts unaccepted: nothing was applied, so nothing is sent (`BR-070`). */
    fun dismissApprovalConflicts() {
        _uiState.update { it.copy(pendingApproval = null) }
    }

    /** Acknowledges the approval the screen has reported, so it is not reported twice (`BR-FV-013`). */
    fun acknowledgeApproval() {
        _uiState.update { it.copy(approvedRequestId = null, approvalFailure = null) }
    }

    /** Acknowledges the review the screen has reported, so it is not reported twice (`BR-FV-013`). */
    fun acknowledgeReview() {
        _uiState.update {
            it.copy(reviewedRequestStatus = null, reviewFailureReason = null)
        }
    }

    private var locationOptionsJob: kotlinx.coroutines.Job? = null

    fun openAdHocLocation() {
        locationOptionsJob?.cancel()
        _uiState.update { it.copy(adHocLocationCustomers = emptyList(), adHocLocationProperties = emptyList(), adHocLocationLoading = false, adHocLocationFailure = null) }
    }

    fun searchAdHocLocationCustomers(query: String) {
        locationOptionsJob?.cancel()
        _uiState.update { it.copy(adHocLocationCustomers = emptyList(), adHocLocationLoading = query.trim().length >= 2, adHocLocationFailure = null) }
        if (query.trim().length < 2) return
        locationOptionsJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            when (val result = adHocWorkReportsRepository.searchReviewCustomers(query.trim())) {
                is AdHocReviewDataResult.Success -> _uiState.update { it.copy(adHocLocationCustomers = result.value, adHocLocationLoading = false) }
                is AdHocReviewDataResult.Failure -> _uiState.update { it.copy(adHocLocationFailure = result.reason, adHocLocationLoading = false) }
            }
        }
    }

    fun loadAdHocLocationProperties(customerId: String) {
        locationOptionsJob?.cancel()
        _uiState.update { it.copy(adHocLocationProperties = emptyList(), adHocLocationLoading = true, adHocLocationFailure = null) }
        locationOptionsJob = viewModelScope.launch {
            when (val result = adHocWorkReportsRepository.listReviewProperties(customerId)) {
                is AdHocReviewDataResult.Success -> _uiState.update { it.copy(adHocLocationProperties = result.value, adHocLocationLoading = false) }
                is AdHocReviewDataResult.Failure -> _uiState.update { it.copy(adHocLocationFailure = result.reason, adHocLocationLoading = false) }
            }
        }
    }

    fun resolveAdHocLocation(report: AdHocWorkReport, request: ResolveAdHocLocationRequestDto) {
        if (_uiState.value.reviewingAdHocReportId != null) return
        _uiState.update { it.copy(reviewingAdHocReportId = report.id, adHocLocationFailure = null) }
        viewModelScope.launch {
            when (val result = adHocWorkReportsRepository.resolveLocation(report, request)) {
                is AdHocReviewDataResult.Success -> _uiState.update { current -> current.copy(
                    reviewingAdHocReportId = null,
                    adHocReports = current.adHocReports.map { if (it.id == report.id) result.value else it },
                ) }
                is AdHocReviewDataResult.Failure -> {
                    _uiState.update { it.copy(reviewingAdHocReportId = null, adHocLocationFailure = result.reason) }
                    if (result.reason == CustomersFailureReason.VERSION_CONFLICT || result.reason == CustomersFailureReason.NETWORK) readAdHocReports()
                }
            }
        }
    }

    /** Voids [report] as not legitimate Servora work, recording [note] as why (`BR-AH-007`). */
    fun rejectAdHocReport(report: AdHocWorkReport, note: String) {
        reviewAdHoc(report) {
            when (val result = adHocWorkReportsRepository.reject(it, note)) {
                is AdHocWorkReportRejectResult.Success -> AdHocReviewRead.Rejected(result.report)
                is AdHocWorkReportRejectResult.Failure -> AdHocReviewRead.Failed(result.reason)
            }
        }
    }

    /**
     * Reconciles [report] into the existing Job [jobId], recording [note] (`BR-AH-002`, `BR-AH-006`).
     */
    fun linkAdHocReport(report: AdHocWorkReport, jobId: String, note: String) {
        reviewAdHoc(report) {
            when (val result = adHocWorkReportsRepository.link(it, jobId, note)) {
                is AdHocWorkReportReconcileResult.Success ->
                    AdHocReviewRead.Reconciled(AdHocWorkReportStatus.LINKED, result.jobId)

                is AdHocWorkReportReconcileResult.Failure -> AdHocReviewRead.Failed(result.reason)
            }
        }
    }

    /**
     * Reconciles [report] into a new Job with [title], recording [note] (`BR-AH-002`).
     */
    fun convertAdHocReport(
        report: AdHocWorkReport,
        title: String,
        description: String?,
        note: String,
    ) {
        reviewAdHoc(report) {
            when (val result = adHocWorkReportsRepository.convert(it, title, description, note)) {
                is AdHocWorkReportReconcileResult.Success ->
                    AdHocReviewRead.Reconciled(AdHocWorkReportStatus.CONVERTED, result.jobId)

                is AdHocWorkReportReconcileResult.Failure -> AdHocReviewRead.Failed(result.reason)
            }
        }
    }

    /**
     * Opens the link dialog for [report], defaulting to the technician's `knownJobId` but not binding
     * the office to it (`BR-AH-002`). The Job options are read again so the dialog lists the Jobs the
     * backend offers rather than a cached copy (`BR-001`).
     */
    fun openAdHocLink(report: AdHocWorkReport) {
        _uiState.update {
            it.copy(
                adHocReviewSelectedJobId = report.knownJobId,
                adHocReviewJobQuery = "",
                adHocReviewJobOptions = emptyList(),
                adHocReviewJobOptionsFailureReason = null,
            )
        }
        loadAdHocReviewJobOptions(report.customerId, null)
    }

    /** Narrows the link dialog's whole-organization Job search (`BR-AH-002`). */
    fun searchAdHocReviewJobs(report: AdHocWorkReport, query: String) {
        _uiState.update { it.copy(adHocReviewJobQuery = query) }
        loadAdHocReviewJobOptions(
            report.customerId,
            query.trim().takeIf { it.isNotEmpty() },
        )
    }

    /** Selects the Job the report is linked to (`BR-AH-002`). */
    fun selectAdHocReviewJob(jobId: String) {
        _uiState.update { it.copy(adHocReviewSelectedJobId = jobId) }
    }

    private fun loadAdHocReviewJobOptions(customerId: String?, query: String?) {
        _uiState.update {
            it.copy(
                isReadingAdHocReviewJobOptions = true,
                adHocReviewJobOptionsFailureReason = null,
            )
        }
        viewModelScope.launch {
            when (val result = adHocWorkReportsRepository.listReviewJobs(customerId, query)) {
                is AdHocReportJobOptionsResult.Success ->
                    _uiState.update {
                        it.copy(
                            isReadingAdHocReviewJobOptions = false,
                            adHocReviewJobOptions = result.jobs,
                            adHocReviewJobOptionsFailureReason = null,
                        )
                    }

                is AdHocReportJobOptionsResult.Failure ->
                    _uiState.update {
                        it.copy(
                            isReadingAdHocReviewJobOptions = false,
                            adHocReviewJobOptionsFailureReason = result.reason,
                        )
                    }
            }
        }
    }

    /** Acknowledges the ad-hoc review the screen has reported, so it is not reported twice. */
    fun acknowledgeAdHocReview() {
        _uiState.update {
            it.copy(adHocReviewOutcome = null, adHocReviewFailureReason = null)
        }
    }

    /** Acknowledges a reconciled Job the screen has navigated to. */
    fun acknowledgeAdHocReconciliation() {
        _uiState.update { it.copy(reconciledAdHocJobId = null) }
    }

    private fun reviewAdHoc(
        report: AdHocWorkReport,
        action: suspend (AdHocWorkReport) -> AdHocReviewRead,
    ) {
        if (_uiState.value.reviewingAdHocReportId != null) {
            return
        }
        _uiState.update {
            it.copy(
                reviewingAdHocReportId = report.id,
                adHocReviewOutcome = null,
                adHocReviewFailureReason = null,
            )
        }
        viewModelScope.launch {
            when (val result = action(report)) {
                is AdHocReviewRead.Rejected ->
                    _uiState.update { current ->
                        current.copy(
                            reviewingAdHocReportId = null,
                            adHocReviewFailureReason = null,
                            adHocReviewOutcome = result.report.status,
                            adHocReports = current.adHocReports.map { existing ->
                                if (existing.id == result.report.id) result.report else existing
                            },
                        )
                    }

                is AdHocReviewRead.Reconciled ->
                    _uiState.update { current ->
                        current.copy(
                            reviewingAdHocReportId = null,
                            adHocReviewFailureReason = null,
                            adHocReviewOutcome = result.status,
                            reconciledAdHocJobId = result.jobId,
                            adHocReports = current.adHocReports.filterNot { it.id == report.id },
                        )
                    }

                is AdHocReviewRead.Failed -> {
                    _uiState.update {
                        it.copy(
                            reviewingAdHocReportId = null,
                            adHocReviewOutcome = null,
                            adHocReviewFailureReason = result.reason,
                        )
                    }
                    if (result.reason == CustomersFailureReason.VERSION_CONFLICT || result.reason == CustomersFailureReason.NETWORK) readAdHocReports()
                }
            }
        }
    }

    /** Sends one approval and reports what the backend answered. */
    private fun submitApproval(
        request: FollowUpVisitRequest,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        assignments: List<TechnicianAssignment>,
        confirmed: Boolean,
    ) {
        _uiState.update {
            it.copy(
                approvingRequestId = request.id,
                schedulingRequestId = request.id,
                approvalFailure = null,
                pendingApproval = null,
            )
        }
        viewModelScope.launch {
            val result = jobDetailsRepository.approveVisitRequest(
                jobId = request.jobId,
                requestId = request.id,
                scheduledStart = scheduledStart,
                scheduledEnd = scheduledEnd,
                assignments = assignments,
                expectedStatus = request.status,
                expectedVersion = request.version,
                confirmConflicts = confirmed,
            )
            _uiState.update { current ->
                when (result) {
                    is JobActionResult.Success ->
                        current.copy(
                            approvingRequestId = null,
                            schedulingRequestId = null,
                            pendingApproval = null,
                            approvalFailure = null,
                            approvedRequestId = request.id,
                        )

                    is JobActionResult.Conflicts ->
                        current.copy(
                            approvingRequestId = null,
                            // The form stays open in front of the conflicts: accepting them resends the
                            // same decision rather than asking for it again (`BR-070`, `BR-067`).
                            pendingApproval = PendingVisitRequestApproval(
                                requestId = request.id,
                                scheduledStart = scheduledStart,
                                scheduledEnd = scheduledEnd,
                                assignments = assignments,
                                conflicts = result.conflicts,
                            ),
                        )

                    is JobActionResult.Failure ->
                        current.copy(
                            approvingRequestId = null,
                            schedulingRequestId = null,
                            pendingApproval = null,
                            approvalFailure = result.reason,
                        )

                    is JobActionResult.Queued ->
                        // The approval is online-only — its route takes no client-generated idempotency
                        // key and no conflict policy is decided for it
                        // (`offline-first-architecture.md` §13.2) — so an answer this build cannot place
                        // is reported rather than presented (`BR-042`).
                        current.copy(
                            approvingRequestId = null,
                            schedulingRequestId = null,
                            pendingApproval = null,
                            approvalFailure = JobActionFailure.UNEXPECTED,
                        )
                }
            }
            if (result is JobActionResult.Success) {
                // The approval created one Visit on one Job (`BR-FV-005`), so the request is no longer
                // pending and the day now holds the Visit it created: both are read again rather than
                // patched from the reply (`BR-001`, `BR-042`).
                readRequests()
                read()
            }
        }
    }

    /**
     * Narrows the day to [technicians], or shows the whole organization when the list is empty.
     *
     * The filter is part of the read, so the day is asked for again: the narrowing is the backend's
     * (`BR-068`), and a client that filtered the rows it already held would be deciding assignment
     * for itself (`BR-042`). Several technicians are one read that the API answers with their union,
     * so a group is never turned into a request per technician.
     */
    fun selectTechnicians(technicians: List<ScheduleTechnician>) {
        if (_uiState.value.technicianFilters == technicians) {
            return
        }
        _uiState.update { it.copy(technicianFilters = technicians) }
        read()
    }

    /**
     * Forgets the loaded state and allows it to be read again.
     *
     * Called when the session ends, so the next user does not see the previous manager's operation
     * (`BR-001`). A read already in flight is abandoned with it.
     */
    fun reset() {
        generation += 1
        _uiState.value = ScheduleUiState()
    }

    private fun read() {
        val state = _uiState.value
        val date = state.selectedDate ?: return
        generation += 1
        val readGeneration = generation
        _uiState.update { it.copy(isRefreshing = true, failureReason = null) }
        val membershipIds = state.technicianFilterIds
        viewModelScope.launch {
            // The read happens outside the state mutator: `update` may re-run its lambda on
            // contention, and a re-run must never issue a second request.
            val result = repository.loadSchedule(
                localDate = date.toString(),
                timeZone = state.timeZoneId,
                membershipIds = membershipIds,
            )
            if (readGeneration != generation) {
                return@launch
            }
            _uiState.update { current ->
                when (result) {
                    is ScheduleResult.Success ->
                        current.copy(
                            isRefreshing = false,
                            schedule = result.schedule,
                            failureReason = null,
                        )

                    is ScheduleResult.Failure ->
                        current.copy(
                            isRefreshing = false,
                            schedule = null,
                            failureReason = result.reason,
                        )
                }
            }
        }
    }

    private fun readRequests() {
        _uiState.update { it.copy(isReadingRequests = true, requestReadFailureReason = null) }
        viewModelScope.launch {
            when (val result = visitRequestsRepository.loadRequests()) {
                is VisitRequestsResult.Success ->
                    _uiState.update {
                        it.copy(
                            visitRequests = result.requests,
                            isReadingRequests = false,
                            hasReadRequests = true,
                            requestReadFailureReason = null,
                        )
                    }

                is VisitRequestsResult.Failure ->
                    _uiState.update {
                        it.copy(
                            isReadingRequests = false,
                            hasReadRequests = true,
                            requestReadFailureReason = result.reason,
                        )
                    }
            }
        }
    }

    private fun readAdHocReports() {
        _uiState.update { it.copy(isReadingAdHocReports = true, adHocReportReadFailureReason = null) }
        viewModelScope.launch {
            when (val result = adHocWorkReportsRepository.loadReports()) {
                is AdHocWorkReportsResult.Success ->
                    _uiState.update {
                        it.copy(
                            adHocReports = result.reports,
                            isReadingAdHocReports = false,
                            hasReadAdHocReports = true,
                            adHocReportReadFailureReason = null,
                        )
                    }

                is AdHocWorkReportsResult.Failure ->
                    _uiState.update {
                        it.copy(
                            isReadingAdHocReports = false,
                            hasReadAdHocReports = true,
                            adHocReportReadFailureReason = result.reason,
                        )
                    }
            }
        }
    }

    private fun review(
        request: FollowUpVisitRequest,
        action: suspend (FollowUpVisitRequest) -> VisitRequestReviewResult,
    ) {
        if (_uiState.value.reviewingRequestId != null) {
            return
        }
        _uiState.update {
            it.copy(
                reviewingRequestId = request.id,
                reviewedRequestStatus = null,
                reviewFailureReason = null,
            )
        }
        viewModelScope.launch {
            when (val result = action(request)) {
                is VisitRequestReviewResult.Success ->
                    _uiState.update { current ->
                        current.copy(
                            reviewingRequestId = null,
                            reviewFailureReason = null,
                            // The decision the API applied is what is reported, and the request it
                            // answered with is the one the lane now holds (`BR-001`, `BR-FV-013`).
                            reviewedRequestStatus = result.request.status,
                            visitRequests = current.visitRequests.map { existing ->
                                if (existing.id == result.request.id) result.request else existing
                            },
                        )
                    }

                is VisitRequestReviewResult.Failure ->
                    _uiState.update {
                        it.copy(
                            reviewingRequestId = null,
                            // Nothing was applied, so nothing is presented as decided: the request the
                            // API still holds is the one the lane keeps showing (`BR-001`, `BR-067`).
                            reviewedRequestStatus = null,
                            reviewFailureReason = result.reason,
                        )
                    }
            }
        }
    }
}

/** What an ad-hoc review returned, unified so the three actions share one state update. */
private sealed interface AdHocReviewRead {
    /** The report was rejected and returned its new terminal state (`BR-AH-007`). */
    data class Rejected(val report: AdHocWorkReport) : AdHocReviewRead

    /** The report was reconciled and returned the Job the Visit now belongs to (`BR-AH-008`). */
    data class Reconciled(val status: AdHocWorkReportStatus, val jobId: String) : AdHocReviewRead

    /** The decision was not applied. */
    data class Failed(val reason: CustomersFailureReason) : AdHocReviewRead
}
