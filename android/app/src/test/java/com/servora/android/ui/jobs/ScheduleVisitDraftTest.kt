package com.servora.android.ui.jobs

import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.TechnicianAssignment
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a schedule form may submit (`BR-068`, `BR-072`).
 *
 * The API refuses a Visit scheduled without a valid window or a crew with exactly one Lead, and the form
 * refuses the same requests before they are sent — so these tests pin the one place that rule lives
 * rather than the enabled-state of a button (`BR-041`, `BR-042`).
 */
class ScheduleVisitDraftTest {

    @Test
    fun `a window with a crew holding one lead may be submitted`() {
        val draft = draft(
            crew = listOf(
                TechnicianAssignment("member-1", AssignmentRole.LEAD),
                TechnicianAssignment("member-2", AssignmentRole.TECHNICIAN),
            ),
        )

        assertNull(draft.problem)
    }

    @Test
    fun `a single-technician crew may be submitted and leads`() {
        val draft = draft(crew = listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)))

        assertNull(draft.problem)
    }

    @Test
    fun `an end that is not after the start is refused`() {
        assertEquals(
            ScheduleVisitProblem.END_NOT_AFTER_START,
            draft(end = START, crew = crewWithLead()).problem,
        )
        assertEquals(
            ScheduleVisitProblem.END_NOT_AFTER_START,
            draft(end = START.minusSeconds(3600), crew = crewWithLead()).problem,
        )
    }

    @Test
    fun `a crew with no technician is refused`() {
        assertEquals(ScheduleVisitProblem.NO_TECHNICIANS, draft(crew = emptyList()).problem)
    }

    @Test
    fun `a crew whose technicians are all regular technicians is refused`() {
        val draft = draft(
            crew = listOf(
                TechnicianAssignment("member-1", AssignmentRole.TECHNICIAN),
                TechnicianAssignment("member-2", AssignmentRole.TECHNICIAN),
            ),
        )

        assertEquals(ScheduleVisitProblem.NO_LEAD, draft.problem)
    }

    @Test
    fun `a crew with two leads is refused`() {
        val draft = draft(
            crew = listOf(
                TechnicianAssignment("member-1", AssignmentRole.LEAD),
                TechnicianAssignment("member-2", AssignmentRole.LEAD),
            ),
        )

        assertEquals(ScheduleVisitProblem.NO_LEAD, draft.problem)
    }

    /** The problem the window's own end is judged by comes first, whatever the crew is (`BR-072`). */
    @Test
    fun `the window is judged before the crew`() {
        val draft = draft(end = START, crew = emptyList())

        assertEquals(ScheduleVisitProblem.END_NOT_AFTER_START, draft.problem)
    }

    private fun draft(
        end: Instant = START.plusSeconds(7200),
        crew: List<TechnicianAssignment> = crewWithLead(),
    ) = ScheduleVisitDraft(start = START, end = end, crew = crew)

    private fun crewWithLead() = listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD))

    private companion object {
        val START: Instant = Instant.parse("2026-09-15T13:00:00Z")
    }
}
