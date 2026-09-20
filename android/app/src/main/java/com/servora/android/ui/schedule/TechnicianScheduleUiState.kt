package com.servora.android.ui.schedule

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleVisit
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Everything the technician's schedule renders.
 *
 * [selectedDate], [displayedWeekStart] and [firstDayOfWeek] are the screen's own state — which day the
 * technician is looking at and which week the strip shows — while [schedule] is the last answer the
 * backend gave for the selected day (`BR-001`). Nothing here is a competing source of truth: a
 * successful read replaces [schedule] entirely, and a failed read of the **same** day leaves it as it
 * was while reporting [failureReason].
 *
 * The scope the day was read in comes with [schedule] rather than being decided here: a technician
 * reads their own assigned work (`BR-009`), and the API is what says so (`BR-007`).
 */
@Immutable
data class TechnicianScheduleUiState(
    /** The IANA zone the device renders in, named on every read. */
    val timeZoneId: String = "",
    /** The first day of a week for the device's language (`BR-028`). */
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    /** The local day the agenda is for; `null` until the screen's first read chooses today. */
    val selectedDate: LocalDate? = null,
    /** The first day of the week the strip is showing; swiping moves it past the selection. */
    val displayedWeekStart: LocalDate? = null,
    /** Whether a read is in flight. Content stays visible while it is. */
    val isRefreshing: Boolean = false,
    /** The last day the backend reported for [selectedDate], or `null` when none has arrived. */
    val schedule: Schedule? = null,
    /** Whether [schedule] is the backend's answer or the last one it reported (`readSource`). */
    val source: ReadSource = ReadSource.BACKEND,
    /** Why the last read failed, or `null` when it succeeded. */
    val failureReason: CustomersFailureReason? = null,
) {
    /** The selected day's Visits, in the API's own order (`BR-072`). */
    val visits: List<ScheduleVisit>
        get() = schedule?.visits.orEmpty()

    /** Nothing has been read yet: the screen shows its first-load state. */
    val showsInitialLoading: Boolean
        get() = schedule == null && failureReason == null

    /** Nothing has been read and the read failed: the screen reports the failure. */
    val showsFailure: Boolean
        get() = schedule == null && failureReason != null

    /** The backend has reported this day at least once. */
    val hasContent: Boolean
        get() = schedule != null

    /** Known content is on screen while a later read of the same day is in flight. */
    val showsRefreshingIndicator: Boolean
        get() = schedule != null && isRefreshing

    /** The selected day holds no Visit assigned to the caller. */
    val showsEmptyDay: Boolean
        get() = schedule != null && schedule.visits.isEmpty()

    /**
     * The day on screen is the last one the backend reported rather than a current answer.
     *
     * The screen says so instead of claiming to be current, and the mark clears the moment the
     * backend answers again (`BR-013`, offline standard §7).
     */
    val showsLastReportedNotice: Boolean
        get() = schedule != null && source == ReadSource.WORKING_SET

    /** The membership the read was resolved for, for marking the caller among a crew (`BR-068`). */
    val viewerMembershipId: String
        get() = schedule?.scope?.membershipId.orEmpty()

    /** Whether the strip is showing the week the selected day is in. */
    val showsSelectedWeek: Boolean
        get() = selectedDate != null &&
            displayedWeekStart == weekStartOf(selectedDate, firstDayOfWeek)

    /**
     * Whether the day is something other than the device's today, so the way back is offered.
     *
     * [today] is the device's own date, which is a device fact rather than a business one, so it is
     * handed in rather than derived here (`BR-001`).
     */
    fun showsTodayAction(today: LocalDate): Boolean =
        selectedDate != null && selectedDate != today
}
