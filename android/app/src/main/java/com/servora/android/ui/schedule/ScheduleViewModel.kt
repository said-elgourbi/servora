package com.servora.android.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
import com.servora.android.data.schedule.VisitRequestReviewResult
import com.servora.android.data.schedule.VisitRequestsRepository
import com.servora.android.data.schedule.VisitRequestsResult
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
        } else {
            selectDate(today, firstDayOfWeek)
        }
    }

    /** Re-runs the read after a failure the screen reported. */
    fun retry() {
        read()
        readRequests()
    }

    /** Reads requests for a manager request-details destination opened outside Schedule. */
    fun openRequests(timeZoneId: String) {
        _uiState.update { it.copy(timeZoneId = timeZoneId) }
        readRequests()
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
