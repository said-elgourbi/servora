package com.servora.android.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
import com.servora.android.data.schedule.VisitRequestReviewResult
import com.servora.android.data.schedule.VisitRequestsRepository
import com.servora.android.data.schedule.VisitRequestsResult
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.ScheduleTechnician
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
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
 */
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val visitRequestsRepository: VisitRequestsRepository,
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

    /** Asks the manager-review route to move a pending request back for clarification. */
    fun askForClarification(request: FollowUpVisitRequest) {
        review(request) { visitRequestsRepository.askForClarification(it) }
    }

    /** Rejects a pending follow-up request. */
    fun rejectRequest(request: FollowUpVisitRequest) {
        review(request) { visitRequestsRepository.reject(it) }
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
        viewModelScope.launch {
            when (val result = visitRequestsRepository.loadRequests()) {
                is VisitRequestsResult.Success ->
                    _uiState.update {
                        it.copy(
                            visitRequests = result.requests,
                            requestFailureReason = null,
                        )
                    }

                is VisitRequestsResult.Failure ->
                    _uiState.update { it.copy(requestFailureReason = result.reason) }
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
            it.copy(reviewingRequestId = request.id, requestFailureReason = null)
        }
        viewModelScope.launch {
            when (val result = action(request)) {
                is VisitRequestReviewResult.Success ->
                    _uiState.update { current ->
                        current.copy(
                            reviewingRequestId = null,
                            requestFailureReason = null,
                            visitRequests = current.visitRequests.map { existing ->
                                if (existing.id == result.request.id) result.request else existing
                            },
                        )
                    }

                is VisitRequestReviewResult.Failure ->
                    _uiState.update {
                        it.copy(
                            reviewingRequestId = null,
                            requestFailureReason = result.reason,
                        )
                    }
            }
        }
    }
}
