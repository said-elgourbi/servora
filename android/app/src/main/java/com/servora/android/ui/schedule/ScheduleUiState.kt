package com.servora.android.ui.schedule

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.TechnicianAssignment
import java.time.DayOfWeek
import java.time.Instant
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
 * An approval the API refused until the reviewer accepts the conflicts it reported (`BR-070`).
 *
 * It holds the request **and** the exact window and crew the reviewer stated, so confirming resends the
 * same decision rather than asking them to fill the form in again — and so the visit that is created is
 * the visit they decided on, not a second guess (`BR-FV-005`, `BR-067`).
 */
@Immutable
data class PendingVisitRequestApproval(
    val requestId: String,
    val scheduledStart: Instant,
    val scheduledEnd: Instant,
    val assignments: List<TechnicianAssignment>,
    val conflicts: List<ScheduleConflict>,
)

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
    /** Whether a request read is currently in flight. */
    val isReadingRequests: Boolean = false,
    /** Whether a request read has answered, successfully or otherwise. */
    val hasReadRequests: Boolean = false,
    /** Why the last read failed, or `null` when it succeeded. */
    val failureReason: CustomersFailureReason? = null,
    /** Why the last read of the requests failed, without replacing the known schedule. */
    val requestReadFailureReason: CustomersFailureReason? = null,
    /** The request currently being reviewed. */
    val reviewingRequestId: String? = null,
    /**
     * The status the last review applied, until the screen has reported it (`BR-FV-013`).
     *
     * It is the status the API answered with, never the one that was asked for, so a decision is only
     * presented as taken once the backend holds it (`BR-001`).
     */
    val reviewedRequestStatus: FollowUpVisitRequestStatus? = null,
    /** Why the last review was not applied, or `null` when it was (`BR-FV-013`). */
    val reviewFailureReason: CustomersFailureReason? = null,
    /**
     * The follow-up request whose approval form is open, or `null` when none is (`BR-FV-005`).
     *
     * The form is the screen's own state rather than a fact about the work: it holds which request the
     * manager decided to schedule and stays open while they state the window and the crew.
     */
    val schedulingRequestId: String? = null,
    /** The technicians the approval form may compose a crew from, or `null` while they are unread. */
    val assignableTechnicians: List<AssignableTechnician>? = null,
    /** Why the technicians could not be read, or `null`. */
    val assignableFailure: JobActionFailure? = null,
    /** The request whose approval is in flight, or `null` when none is. */
    val approvingRequestId: String? = null,
    /** Why the last approval did not complete, or `null`. */
    val approvalFailure: JobActionFailure? = null,
    /** An approval waiting for the user to accept the conflicts the API reported (`BR-070`). */
    val pendingApproval: PendingVisitRequestApproval? = null,
    /** The request whose approval created a Visit, until the screen has reported it. */
    val approvedRequestId: String? = null,
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

    /**
     * The requests the office still has a decision to make about.
     *
     * It is every request the API keeps reviewable: `PENDING`, and a request the office returned for
     * clarification. `BR-FV-012` says a request returned for clarification "remains unresolved until it
     * is later approved or rejected", and the review routes accept a decision about a
     * `NEEDS_CLARIFICATION` request for exactly that reason (`api/src/jobs/jobs.service.ts`,
     * "reviewable when `PENDING` or `NEEDS_CLARIFICATION`"). A lane that drew only `PENDING` therefore
     * took the one screen that can decide a request off the request as soon as it was clarified, so the
     * request was left with no surface that could ever resolve it.
     */
    val reviewableRequests: List<FollowUpVisitRequest>
        get() = visitRequests.filter { it.isAwaitingReview }

    /**
     * The request whose approval form is open, or `null`.
     *
     * It is looked up among the requests the backend reported, so a request the screen no longer holds —
     * one another reviewer decided about, or the read no longer returns — closes the form instead of
     * being scheduled from a copy of a list that has moved on (`BR-001`, `BR-FV-013`).
     */
    val schedulingRequest: FollowUpVisitRequest?
        get() = schedulingRequestId?.let { id -> reviewableRequests.firstOrNull { it.id == id } }

    /** Whether an approval is in flight, so the form is not submitted twice (`BR-031`). */
    val isApproving: Boolean
        get() = approvingRequestId != null

    /** Count shown on the Requests lane badge. */
    val reviewableRequestCount: Int
        get() = reviewableRequests.size

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
 * Whether the office still has a decision to make about this request (`BR-FV-012`).
 *
 * `NEEDS_CLARIFICATION` is unresolved rather than closed: the request has been neither approved into a
 * Visit nor rejected, so the office can still do either (`BR-FV-004`, `BR-FV-005`).
 */
val FollowUpVisitRequest.isAwaitingReview: Boolean
    get() = status == FollowUpVisitRequestStatus.PENDING ||
        status == FollowUpVisitRequestStatus.NEEDS_CLARIFICATION

/**
 * Whether the requester owes the office an answer (`BR-FV-012`).
 *
 * It is the technician's own move: the office returned the request with what it still needs, and
 * answering it — which appends the answer and returns the request to the office's review — is what the
 * requester can do about it. Every other state has nothing to answer: a request awaiting review is the
 * office's, and an approved or rejected one has been decided.
 */
val FollowUpVisitRequest.awaitsAnswer: Boolean
    get() = status == FollowUpVisitRequestStatus.NEEDS_CLARIFICATION

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
