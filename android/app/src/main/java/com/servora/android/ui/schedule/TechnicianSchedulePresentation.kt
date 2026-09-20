package com.servora.android.ui.schedule

import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitStatus
import java.time.LocalDate

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
