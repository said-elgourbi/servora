package com.servora.android.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
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
 * Drives the technician's schedule.
 *
 * The ViewModel decides nothing about the work: it asks [ScheduleRepository] for the day the technician
 * is looking at and reports what came back. Which Visits a day holds, their statuses, their crew and
 * whether one is overdue are the backend's answers (`BR-001`, `BR-042`), and **whose** Visits the day
 * may hold is the backend's too: the read is authorized as the caller's own assigned work and never
 * narrows itself to a technician the client picked (`BR-009`, `BR-007`).
 *
 * The day and the week are the screen's own state, because they are the technician's browsing rather
 * than facts about the operation. The device's zone and its first day of the week come from the screen,
 * so the day the API resolves and the week the strip draws are the ones the technician is working in
 * (`BR-041`).
 *
 * A day the backend cannot be reached for is answered from the working set when it was read before, and
 * the state says so rather than presenting a local copy as current (`BR-013`, offline standard §7, §13).
 */
@HiltViewModel
class TechnicianScheduleViewModel @Inject constructor(
    private val repository: ScheduleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TechnicianScheduleUiState())
    val uiState: StateFlow<TechnicianScheduleUiState> = _uiState.asStateFlow()

    /**
     * Identifies the read a result belongs to.
     *
     * Every read takes the next generation, so an answer that arrives after the technician has already
     * moved to another day is dropped rather than written over the day now on screen (`BR-067`).
     */
    private var generation = 0

    /**
     * Opens the screen: selects [today] the first time and reads the day.
     *
     * Coming back to the destination keeps the day the technician had selected and refreshes it, so
     * leaving the screen to open a Job and coming back does not lose their place.
     */
    fun start(today: LocalDate, timeZoneId: String, firstDayOfWeek: DayOfWeek) {
        val hadADate = _uiState.value.selectedDate != null
        _uiState.update { it.copy(timeZoneId = timeZoneId, firstDayOfWeek = firstDayOfWeek) }
        if (hadADate) {
            read()
        } else {
            selectDate(today, firstDayOfWeek)
        }
    }

    /** Re-runs the read after a failure the screen reported. */
    fun retry() {
        read()
    }

    /**
     * Selects [date] and reads its schedule.
     *
     * Content that belonged to the day left behind is released with it: what is on screen is always the
     * day it was read for, so a day that fails to load reports the failure rather than showing another
     * day's Visits as if they were this one's (`BR-042`). The week strip moves to the week the date is
     * in, so tapping a day never leaves the strip showing a week the agenda is not about (`BR-041`).
     */
    fun selectDate(date: LocalDate, firstDayOfWeek: DayOfWeek) {
        if (
            _uiState.value.selectedDate == date &&
            _uiState.value.displayedWeekStart == weekStartOf(date, firstDayOfWeek)
        ) {
            return
        }
        generation += 1
        _uiState.update {
            it.copy(
                selectedDate = date,
                displayedWeekStart = weekStartOf(date, firstDayOfWeek),
                schedule = null,
                source = ReadSource.BACKEND,
                failureReason = null,
            )
        }
        read()
    }

    /**
     * Shows [weekStart] in the strip without changing the selected day.
     *
     * Swiping moves the strip through nearby weeks; the agenda keeps describing the selected day until
     * a day in another week is tapped, so a swipe never reads a day the technician did not choose.
     */
    fun showWeek(weekStart: LocalDate) {
        if (_uiState.value.displayedWeekStart == weekStart) {
            return
        }
        _uiState.update { it.copy(displayedWeekStart = weekStart) }
    }

    /**
     * Returns to [today], which is the day the screen opens on.
     *
     * It is the same selection a tap on the strip makes, so the header's way back and the strip's own
     * day cannot disagree about what "today" is (`BR-041`).
     */
    fun showToday(today: LocalDate, firstDayOfWeek: DayOfWeek) {
        selectDate(today, firstDayOfWeek)
    }

    /**
     * Forgets the loaded state and allows it to be read again.
     *
     * Called when the session ends, so the technician who signs in next does not see the previous
     * technician's work (`BR-001`). A read already in flight is abandoned with it.
     */
    fun reset() {
        generation += 1
        _uiState.value = TechnicianScheduleUiState()
    }

    private fun read() {
        val state = _uiState.value
        val date = state.selectedDate ?: return
        generation += 1
        val readGeneration = generation
        _uiState.update { it.copy(isRefreshing = true, failureReason = null) }
        viewModelScope.launch {
            // The read happens outside the state mutator: `update` may re-run its lambda on
            // contention, and a re-run must never issue a second request. The filter is deliberately
            // empty: this read is the caller's own work, and naming technicians belongs to the office
            // scope (`BR-009`, `BR-068`).
            val result = repository.loadSchedule(
                localDate = date.toString(),
                timeZone = state.timeZoneId,
                membershipIds = emptyList(),
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
                            source = result.source,
                            failureReason = null,
                        )

                    // A refresh of the day already on screen that failed leaves it there and reports
                    // why; after a day change there is nothing to keep, so the failure stands alone
                    // (`BR-013`).
                    is ScheduleResult.Failure ->
                        current.copy(
                            isRefreshing = false,
                            failureReason = result.reason,
                        )
                }
            }
        }
    }
}
