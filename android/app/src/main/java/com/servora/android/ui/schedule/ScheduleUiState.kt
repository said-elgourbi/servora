package com.servora.android.ui.schedule

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleTechnician
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** The two things the schedule shows (`BR-072`, `BR-068`). */
enum class ScheduleLane {
    /** The selected day's scheduled Visits, chronologically. */
    SCHEDULE,

    /** The work that still has no crew, whatever day it may land on (`BR-071`, `BR-068`). */
    UNASSIGNED,

    /** Follow-up Visit requests awaiting manager review. */
    REQUESTS,
}

/**
 * Everything the schedule screen renders.
 *
 * [selectedDate], [displayedWeekStart], [lane] and [technicianFilters] are the screen's own state —
 * which day the manager is looking at, which week the strip shows and how the day is narrowed — while
 * [schedule] is the last answer the backend gave for that day and filter (`BR-001`). Nothing here is
 * a competing source of truth: a successful read replaces [schedule] entirely, and a failed read
 * leaves it as it was while reporting [failureReason].
 *
 * [timeZoneId] and [firstDayOfWeek] are device facts the screen supplies, because the day the API
 * resolves and the week the strip draws are the ones the manager is actually working in
 * (`BR-041`).
 */
@Immutable
data class ScheduleUiState(
    /** The IANA zone the device renders in, named on every read. */
    val timeZoneId: String = "",
    /** The first day of a week for the device's language (`BR-028`). */
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    /** The local day the agenda is for; `null` until the screen's first read chooses today. */
    val selectedDate: LocalDate? = null,
    /** The first day of the week the strip is showing; swiping moves it past the selection. */
    val displayedWeekStart: LocalDate? = null,
    val lane: ScheduleLane = ScheduleLane.SCHEDULE,
    /**
     * The memberships the day is narrowed to, in the order they were last applied; empty means the
     * whole organization.
     *
     * The screen holds membership ids because that is the vocabulary an assignment names
     * (`BR-068`); it never decides for itself who is a technician. Several selected technicians are a
     * union — the API answers with the Visits **any** of them is assigned to — so one technician, a
     * small group and the whole team are the same filter with a different number of names in it.
     */
    val technicianFilters: List<ScheduleTechnician> = emptyList(),
    /** Whether a read is in flight. Content stays visible while it is. */
    val isRefreshing: Boolean = false,
    /** The last schedule the backend reported, or `null` when nothing has been read yet. */
    val schedule: Schedule? = null,
    /** Follow-up Visit requests the backend reports for review. */
    val visitRequests: List<FollowUpVisitRequest> = emptyList(),
    /** Why the last read failed, or `null` when it succeeded. */
    val failureReason: CustomersFailureReason? = null,
    /** Why the last visit-request read or review failed, without replacing the known schedule. */
    val requestFailureReason: CustomersFailureReason? = null,
    /** The request currently being reviewed. */
    val reviewingRequestId: String? = null,
) {
    /** Nothing has been read: the screen shows its first-load state. */
    val showsInitialLoading: Boolean
        get() = schedule == null && failureReason == null

    /** Nothing has been read and the read failed: the screen reports the failure. */
    val showsFailure: Boolean
        get() = schedule == null && failureReason != null

    /** The backend has reported this day at least once. */
    val hasContent: Boolean
        get() = schedule != null

    /** Known content is on screen while a later read is in flight. */
    val showsRefreshingIndicator: Boolean
        get() = schedule != null && isRefreshing

    /** The selected day holds no scheduled Visit at all. */
    val showsEmptyDay: Boolean
        get() = schedule != null && schedule.visits.isEmpty()

    /** Nothing on the operation still needs a crew. */
    val showsNoUnassignedWork: Boolean
        get() = schedule != null && schedule.unassigned.isEmpty()

    /**
     * The count the Unassigned lane's badge shows.
     *
     * It is the backend's own total (`BR-042`), so a badge never reports work the screen did not
     * receive and never hides work beyond the returned items.
     */
    val unassignedCount: Int
        get() = schedule?.unassignedTotal ?: 0

    /** Pending follow-up requests awaiting review. */
    val pendingRequests: List<FollowUpVisitRequest>
        get() = visitRequests.filter { it.status == FollowUpVisitRequestStatus.PENDING }

    /** Count shown on the Requests lane badge. */
    val pendingRequestCount: Int
        get() = pendingRequests.size

    /** Whether the day is narrowed to technicians rather than showing the whole organization. */
    val hasTechnicianFilter: Boolean
        get() = technicianFilters.isNotEmpty()

    /**
     * The membership ids the read narrows the day to, in the order the filter holds them.
     *
     * An empty list is the whole organization: `All technicians` removes the technician predicate
     * entirely rather than naming everybody, which is what `BR-068`'s assignment vocabulary can
     * express.
     */
    val technicianFilterIds: List<String>
        get() = technicianFilters.map { it.membershipId }

    /** Whether the strip is showing the week the selected day is in. */
    val showsSelectedWeek: Boolean
        get() = selectedDate != null && displayedWeekStart == weekStartOf(selectedDate, firstDayOfWeek)

    /**
     * Whether the agenda's "now" cue belongs in it.
     *
     * The cue only means something on the day the manager is living through, and only in the lane
     * that presents that day's chronology: the unassigned lane holds work that belongs to no day, so
     * there is no "now" in it (`BR-071`, `BR-042`). [today] is the device's own date, which is a device
     * fact rather than a business one, so it is handed in rather than invented here (`BR-001`).
     */
    fun showsNowCue(today: LocalDate): Boolean =
        lane == ScheduleLane.SCHEDULE && selectedDate != null && selectedDate == today
}

/**
 * The first day of a week for the language the device is set to.
 *
 * It is a locale rule rather than a Servora one, so it is read from the platform: a Canadian English
 * or French device starts its week on Monday, and the architecture stays ready for a language that
 * does not (`BR-028`, `Project.md` §10).
 */
fun firstDayOfWeek(locale: Locale): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

/**
 * The first day of the week [date] falls in.
 *
 * It is presentation, not business state: the API resolves the day a date covers, and which week
 * that date is drawn in is the calendar the manager reads (`BR-042`).
 */
fun weekStartOf(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
    val offset = (date.dayOfWeek.value - firstDayOfWeek.value + DAYS_IN_WEEK) % DAYS_IN_WEEK
    return date.minusDays(offset.toLong())
}

/** The seven days of the week that begins at [weekStart], in order. */
fun weekDaysOf(weekStart: LocalDate): List<LocalDate> =
    (0 until DAYS_IN_WEEK).map { weekStart.plusDays(it.toLong()) }

private const val DAYS_IN_WEEK = 7
