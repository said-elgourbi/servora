package com.servora.android.ui.schedule

import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The technician schedule's own presentation decisions, kept out of the composables so they can be
 * asserted without a device: which Visit the day emphasises, how the crew is stated when the caller
 * is one of them, what the day heading calls the date, and which days a week range covers.
 *
 * None of them decides anything about the work. Which Visits a day holds, their statuses, their crew
 * and whether one is overdue are the API's own answers (`BR-001`, `BR-042`); these are only ways of
 * drawing the day the screen was handed (`BR-041`).
 */

/**
 * What the follow-up request details destination draws for one of the caller's own requests.
 *
 * The destination shows one request out of the list the API answers the caller with, so "nothing has
 * answered yet", "the read failed" and "the list does not hold it" are three different things and are
 * stated as three rather than collapsed into one empty screen (`BR-042`, `BR-013`). Which one of them
 * it is, is the state's own answer — nothing here decides anything about the request (`BR-001`).
 */
internal sealed interface RequestDetailsContent {
    /** The request the destination was opened for, as the backend holds it. */
    data class Request(val request: FollowUpVisitRequest) : RequestDetailsContent

    /** Nothing has answered for the caller's own requests yet. */
    data object Reading : RequestDetailsContent

    /** The caller's own requests could not be read, so this one could not be resolved. */
    data object Failed : RequestDetailsContent

    /**
     * The read answered and this request is not one of the caller's own.
     *
     * It is reached by a screen restored from a back stack the requests view no longer backs — the
     * caller reads their own requests (`BR-FV-001`, `BR-009`) — so it is reported as the read's own
     * answer rather than as a request with nothing in it (`BR-042`).
     */
    data object Unavailable : RequestDetailsContent
}

/**
 * The request [requestId] as [state] holds it, or the state of the read that would answer for it.
 *
 * A request is looked up in the list the API answered with rather than read again: the requester's own
 * read is the one route that answers with their rows (`BR-009`, `BR-FV-001`), and it is online-only, so
 * a request the list does not hold is never fetched from the device's memory of an older answer
 * (`BR-001`, `BR-014`).
 */
internal fun requestDetailsContent(
    state: TechnicianScheduleUiState,
    requestId: String,
): RequestDetailsContent {
    state.requests.firstOrNull { request -> request.id == requestId }?.let { request ->
        return RequestDetailsContent.Request(request)
    }
    return when {
        state.showsRequestsFailure -> RequestDetailsContent.Failed
        state.requestsRead -> RequestDetailsContent.Unavailable
        else -> RequestDetailsContent.Reading
    }
}
/**
 * A request's proposed window with its calendar date, in the schedule's zone and device language.
 *
 * The date is always present because a time by itself cannot identify a proposed visit. A proposal
 * crossing midnight names both dates; an unreadable instant is omitted rather than guessed.
 */
internal fun formatRequestWindow(
    start: String,
    end: String?,
    zone: ZoneId,
    locale: Locale,
): String? =
    try {
        val from = Instant.parse(start).atZone(zone)
        val to = end?.let { Instant.parse(it).atZone(zone) }
        val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val fromDate = from.format(dateFormatter)
        val fromTime = from.format(timeFormatter)
        when {
            to == null -> "$fromDate · $fromTime"
            to.toLocalDate() == from.toLocalDate() ->
                "$fromDate · $fromTime – ${to.format(timeFormatter)}"
            else ->
                "$fromDate $fromTime – ${to.format(dateFormatter)} ${to.format(timeFormatter)}"
        }
    } catch (unreadable: DateTimeParseException) {
        null
    }


/**
 * The crew of a Visit as the technician's card states it (`BR-068`).
 *
 * [isViewerOnCrew] says whether the caller's own membership is one of the assigned technicians, so
 * the card can say "You" instead of naming the caller — the membership the API resolved the read for
 * is what identifies them (`BR-020`, `BR-041`). [others] counts the technicians besides the caller,
 * and [names] is what the card falls back to naming when none of the crew is the caller.
 */
internal data class TechnicianCrew(
    val isViewerOnCrew: Boolean,
    val others: Int,
    val names: List<String?>,
)

/**
 * The crew of [visit] as it is stated to the technician [viewerMembershipId].
 *
 * A caller who is not on the crew is not "You": their card names the technicians who are, exactly as
 * the office schedule's crew line does, and a member with no profile yet still counts as a person on
 * the Visit rather than being dropped (`BR-020`).
 */
internal fun technicianCrew(
    visit: ScheduleVisit,
    viewerMembershipId: String,
): TechnicianCrew {
    val onCrew = visit.technicians.any { it.membershipId == viewerMembershipId }
    if (!onCrew) {
        return TechnicianCrew(
            isViewerOnCrew = false,
            others = visit.technicians.size,
            names = visit.technicians.map { it.name },
        )
    }
    val others = visit.technicians.filterNot { it.membershipId == viewerMembershipId }
    return TechnicianCrew(
        isViewerOnCrew = true,
        others = others.size,
        names = others.map { it.name },
    )
}

/**
 * The Visit the day's agenda emphasises: the first one that has not completed a field attempt.
 *
 * It answers the question this screen exists for — "what am I doing next?" — and it decides nothing
 * (`BR-012`, `BR-042`): the Visits arrive in the API's own chronological order (`BR-072`), a Visit's
 * status stays the API's answer, and this only says which row carries the emphasis. A Visit already
 * under way and not yet completed is therefore emphasised rather than skipped.
 *
 * A day whose Visits are all completed has nothing to emphasise, which is the day's own answer.
 */
internal fun nextOutstandingVisitId(visits: List<ScheduleVisit>): String? =
    visits.firstOrNull { it.visitStatus != VisitStatus.COMPLETED }?.visitId

/** What the day heading calls the selected date (`BR-028`, `BR-041`). */
internal enum class TechnicianDayHeading { TODAY, TOMORROW, OTHER }

/**
 * How the selected day is named: today, tomorrow, or by its own date.
 *
 * Both dates are calendar dates in the device's zone, which is a device fact rather than a business
 * one: which Visits a day holds, and whether one is overdue, stay the API's answers (`BR-001`).
 */
internal fun technicianDayHeading(
    selectedDate: LocalDate,
    today: LocalDate,
): TechnicianDayHeading =
    when (selectedDate) {
        today -> TechnicianDayHeading.TODAY
        today.plusDays(1) -> TechnicianDayHeading.TOMORROW
        else -> TechnicianDayHeading.OTHER
    }

/** The span the header names: the first and last day of one week. */
internal data class WeekRange(val start: LocalDate, val end: LocalDate) {
    /** Whether the week straddles two months, which the label writes in full. */
    val crossesMonth: Boolean
        get() = start.month != end.month
}

/**
 * The seven days the week of [weekStart] covers, in the order the strip draws them.
 *
 * The range is what the header states — `Sep 14–20` — while the *label* is resolved by the platform
 * in the device's language, so a translated date is never assembled from English parts (`BR-028`).
 */
internal fun weekRange(weekStart: LocalDate): WeekRange =
    WeekRange(start = weekStart, end = weekStart.plusDays(DAYS_IN_A_WEEK - 1L))

private const val DAYS_IN_A_WEEK = 7L
