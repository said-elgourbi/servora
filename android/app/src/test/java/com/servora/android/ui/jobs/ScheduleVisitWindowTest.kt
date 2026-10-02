package com.servora.android.ui.jobs

import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.TechnicianAssignment
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the schedule form builds a Visit's internal window (`BR-072`).
 *
 * The form asks for one visit date and the two times that day, because a field attempt starts and ends
 * on the same day, and both instants the API is given are built from that one date. These tests pin that
 * rule — the end time never carries a date of its own — rather than the layout that presents it
 * (`BR-041`).
 */
class ScheduleVisitWindowTest {

    @Test
    fun `both ends are built from the one visit date`() {
        val window = scheduleVisitWindow(
            date = VISIT_DATE,
            startHour = 9,
            startMinute = 0,
            endHour = 11,
            endMinute = 30,
            zone = ZONE,
        )!!

        // 09:00 and 11:30 on the visit day, in a zone four hours behind UTC.
        assertEquals(Instant.parse("2026-09-15T13:00:00Z"), window.start)
        assertEquals(Instant.parse("2026-09-15T15:30:00Z"), window.end)
    }

    @Test
    fun `an end time earlier than the start time is refused`() {
        val window = scheduleVisitWindow(
            date = VISIT_DATE,
            startHour = 13,
            startMinute = 0,
            endHour = 12,
            endMinute = 0,
            zone = ZONE,
        )!!

        assertEquals(ScheduleVisitProblem.END_NOT_AFTER_START, draftOf(window).problem)
    }

    @Test
    fun `an end time that would cross midnight stays on the visit day and is refused`() {
        val window = scheduleVisitWindow(
            date = VISIT_DATE,
            startHour = 23,
            startMinute = 0,
            endHour = 1,
            endMinute = 0,
            zone = ZONE,
        )!!

        // The end is 01:00 on the visit day, never 01:00 the next day: the form refuses the window
        // rather than inventing a second date the product does not have.
        assertEquals(Instant.parse("2026-09-15T05:00:00Z"), window.end)
        assertEquals(ScheduleVisitProblem.END_NOT_AFTER_START, draftOf(window).problem)
    }

    @Test
    fun `an unreadable date yields no window`() {
        assertNull(
            scheduleVisitWindow(
                date = "not-a-date",
                startHour = 9,
                startMinute = 0,
                endHour = 11,
                endMinute = 0,
                zone = ZONE,
            ),
        )
    }

    /** A crew that satisfies `BR-068`, so the window is judged on its own (`BR-072`). */
    private fun draftOf(window: ScheduleVisitWindow) = ScheduleVisitDraft(
        start = window.start,
        end = window.end,
        crew = listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)),
    )

    private companion object {
        const val VISIT_DATE = "2026-09-15"

        /** A fixed offset, so the expectations do not depend on the machine's time-zone database. */
        val ZONE: ZoneId = ZoneId.of("-04:00")
    }
}
