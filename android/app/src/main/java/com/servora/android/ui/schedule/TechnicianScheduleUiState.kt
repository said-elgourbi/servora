package com.servora.android.ui.schedule

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleVisit
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The two views My Schedule offers (`BR-009`, `BR-FV-012`).
 *
 * They answer different questions about the same member. The schedule asks "what is assigned to me,
 * and what am I doing next?" (`BR-010`, `BR-012`); the requests view asks "what happened to the extra
 * visit I asked for?" — the field technician's own record of a proposal the office decides
 * (`BR-FV-012`, `BR-FV-013`).
 */
enum class TechnicianScheduleTab {
    /** The caller's own assigned work, day by day (`BR-009`). */
    SCHEDULE,

    /**
     * The caller's own follow-up Visit requests, and the office's answer to each of them
     * (`BR-FV-012`, `BR-FV-013`).
     */
    REQUESTS,
}

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
    /** Which of the screen's two views is open. */
    val tab: TechnicianScheduleTab = TechnicianScheduleTab.SCHEDULE,
    /** Whether a read is in flight. Content stays visible while it is. */
    val isRefreshing: Boolean = false,
    /** The last day the backend reported for [selectedDate], or `null` when none has arrived. */
    val schedule: Schedule? = null,
    /** Whether [schedule] is the backend's answer or the last one it reported (`readSource`). */
    val source: ReadSource = ReadSource.BACKEND,
    /** Why the last read failed, or `null` when it succeeded. */
    val failureReason: CustomersFailureReason? = null,
    /**
     * Whether the caller's own requests have been read at least once.
     *
     * The requests view is not date-scoped, so it is read when that view is opened rather than with
     * the day — and this says whether an answer has arrived, so "no requests yet" is never presented
     * before the backend has answered (`BR-042`).
     */
    val requestsRead: Boolean = false,
    /**
     * The caller's own follow-up requests, newest first, as the backend reported them.
     *
     * They are the requests the API resolved for the caller's own membership (`BR-009`, `BR-FV-001`);
     * which requests those are is the backend's answer and never a filter the client applies
     * (`BR-007`, `BR-042`).
     */
    val requests: List<FollowUpVisitRequest> = emptyList(),
    /** Why the last request read failed, or `null` when it succeeded. */
    val requestsFailureReason: CustomersFailureReason? = null,
    /**
     * The request whose answer is being sent, or `null` when none is (`BR-FV-012`).
     *
     * One answer at a time, so a composer is never submitted twice for the same exchange while the first
     * answer is still on its way (`BR-031`).
     */
    val replyingRequestId: String? = null,
    /**
     * The request the API accepted an answer for, until the screen has reported it (`BR-001`).
     *
     * It is set from the API's own answer, never from the answer that was typed: an answer is presented as
     * recorded only once the backend holds it.
     */
    val answeredRequestId: String? = null,
    /** Why the last answer was not recorded, or `null` when it was (`BR-FV-013`). */
    val replyFailureReason: CustomersFailureReason? = null,
    /** Whether an ad-hoc work report is being submitted. */
    val isSubmittingAdHocReport: Boolean = false,
    /** Whether the last ad-hoc work report was accepted, until the screen reports it. */
    val adHocReportSubmitted: Boolean = false,
    /** Whether the last ad-hoc report was queued for later sync, until the screen reports it. */
    val adHocReportQueued: Boolean = false,
    /** Why the last ad-hoc report was not recorded, or `null` when it was. */
    val adHocReportFailureReason: CustomersFailureReason? = null,
    /** Whether the ad-hoc report form is open (`BR-AH-001`). */
    val adHocReportOpen: Boolean = false,
    /** The ad-hoc report form's own state (`BR-AH-009`). */
    val adHocReportForm: AdHocReportFormState = AdHocReportFormState(),
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

    /** Nothing has been answered for the requests yet: the view shows its first-load state. */
    val showsRequestsLoading: Boolean
        get() = !requestsRead && requestsFailureReason == null

    /** Nothing has been answered for the requests and the read failed. */
    val showsRequestsFailure: Boolean
        get() = !requestsRead && requestsFailureReason != null

    /** The backend answered, and the caller has no request of their own. */
    val showsEmptyRequests: Boolean
        get() = requestsRead && requests.isEmpty()

    /**
     * The requests whose answer is still the office's to give (`BR-FV-012`).
     *
     * It is the count on the Requests tab, and it is derived from the API's own list rather than from
     * a second copy of the rules: a clarified request stays in it, because the office can still
     * approve or reject it.
     */
    val awaitingReviewRequestCount: Int
        get() = requests.count { it.isAwaitingReview }

    /**
     * The list on screen is the last one the backend reported rather than a current answer.
     *
     * A request read that failed after the list was known leaves the list in place and says so, the
     * way the day does (`BR-013`, offline standard §7).
     */
    val showsRequestsUnrefreshedNotice: Boolean
        get() = requestsRead && requestsFailureReason != null

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

    /** Whether an answer is on its way, so no second one is sent (`BR-031`). */
    val isReplying: Boolean
        get() = replyingRequestId != null

    /** Whether [requestId] is the request an answer is being sent for. */
    fun isReplyingTo(requestId: String): Boolean = replyingRequestId == requestId

    /**
     * Whether the day is something other than the device's today, so the way back is offered.
     *
     * [today] is the device's own date, which is a device fact rather than a business one, so it is
     * handed in rather than derived here (`BR-001`).
     */
    fun showsTodayAction(today: LocalDate): Boolean =
        selectedDate != null && selectedDate != today
}
