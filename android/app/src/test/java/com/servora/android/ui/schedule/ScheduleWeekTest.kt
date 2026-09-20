package com.servora.android.ui.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleScope
import com.servora.android.domain.model.ScheduleScopeKind
import com.servora.android.domain.model.ScheduleDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The week the strip draws (`BR-028`, `BR-042`).
 *
 * Which day a week begins on is a language's own rule, and which week a date falls in has to be the
 * same answer on every screen, so both are asserted rather than left to the drawing code.
 */
class ScheduleWeekTest {

    @Test
    fun `a week begins on the day the language begins it`() {
        // The platform answers the question from the language's own calendar rules, so the strip
        // draws the week the user's language starts on (`BR-028`, `Project.md` §10): a US English
        // device starts on Sunday and a French device on Monday.
        assertEquals(DayOfWeek.SUNDAY, firstDayOfWeek(Locale.US))
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeek(Locale.FRANCE))
    }

    @Test
    fun `finds the first day of the week a date falls in`() {
        // 2026-09-07 is a Monday; 2026-09-13 is the Sunday that ends the same week.
        assertEquals(
            LocalDate.parse("2026-09-07"),
            weekStartOf(LocalDate.parse("2026-09-07"), DayOfWeek.MONDAY),
        )
        assertEquals(
            LocalDate.parse("2026-09-07"),
            weekStartOf(LocalDate.parse("2026-09-13"), DayOfWeek.MONDAY),
        )
        // With Sunday as the first day, the same date ends a different week.
        assertEquals(
            LocalDate.parse("2026-09-13"),
            weekStartOf(LocalDate.parse("2026-09-13"), DayOfWeek.SUNDAY),
        )
        assertEquals(
            LocalDate.parse("2026-09-06"),
            weekStartOf(LocalDate.parse("2026-09-07"), DayOfWeek.SUNDAY),
        )
    }

    @Test
    fun `lists the seven days of a week in order`() {
        val days = weekDaysOf(LocalDate.parse("2026-09-07"))

        assertEquals(7, days.size)
        assertEquals(LocalDate.parse("2026-09-07"), days.first())
        assertEquals(LocalDate.parse("2026-09-13"), days.last())
    }

    @Test
    fun `reports whether the strip is showing the selected day's week`() {
        val state = ScheduleUiState(
            selectedDate = LocalDate.parse("2026-09-07"),
            displayedWeekStart = LocalDate.parse("2026-09-07"),
        )
        assertTrue(state.showsSelectedWeek)

        assertFalse(state.copy(displayedWeekStart = LocalDate.parse("2026-09-14")).showsSelectedWeek)
        // Nothing has been chosen yet, so there is no week to compare.
        assertFalse(state.copy(selectedDate = null).showsSelectedWeek)
    }

    @Test
    fun `tells the screen's states apart`() {
        val monday = LocalDate.parse("2026-09-07")
        val initial = ScheduleUiState(selectedDate = monday, displayedWeekStart = monday)
        assertTrue(initial.showsInitialLoading)
        assertFalse(initial.showsFailure)
        assertFalse(initial.hasContent)
        // Nothing has been read, so nothing is claimed to be empty either.
        assertFalse(initial.showsEmptyDay)
        assertEquals(0, initial.unassignedCount)

        val empty = initial.copy(
            schedule = Schedule(
                day = ScheduleDay(localDate = "2026-09-07", timeZone = "America/Toronto"),
    scope = ScheduleScope(
        kind = ScheduleScopeKind.ORGANIZATION,
        membershipId = "member-1",
    ),
                technicians = emptyList(),
                visits = emptyList(),
                unassigned = emptyList(),
                unassignedTotal = 0,
                hasUnassignedLane = true,
            ),
        )
        assertFalse(empty.showsInitialLoading)
        assertTrue(empty.showsEmptyDay)
        assertTrue(empty.showsNoUnassignedWork)

        val failed = initial.copy(failureReason = CustomersFailureReason.NETWORK)
        assertTrue(failed.showsFailure)
        assertFalse(failed.showsInitialLoading)
    }
}
