package com.servora.android.ui.schedule

import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The technician schedule's own presentation decisions.
 *
 * None of them decides anything about the work: a Visit's status, its crew and its schedule are the
 * API's answers (`BR-001`, `BR-042`). These tests cover what the screen *says* about them — who is on
 * the crew and whether the caller is one of them, which row the day emphasises, what the heading
 * calls the date, and which days a week covers.
 */
class TechnicianSchedulePresentationTest {

    @Test
    fun `states the caller among the crew and counts the others`() {
        val visit = presentationVisit(
            crew = listOf(
                crewTechnician("member-7", "Mike Johnson", roleCode = "LEAD"),
                crewTechnician("member-2", "Sarah Kim"),
                crewTechnician("member-3", null),
            ),
        )

        val crew = technicianCrew(visit, viewerMembershipId = "member-7")

        assertTrue(crew.isViewerOnCrew)
        assertEquals(2, crew.others)
        assertEquals(listOf("Sarah Kim", null), crew.names)
    }

    @Test
    fun `counts a colleague without a profile rather than dropping them`() {
        val visit = presentationVisit(
            crew = listOf(
                crewTechnician("member-7", "Mike Johnson", roleCode = "LEAD"),
                crewTechnician("member-2", null),
            ),
        )

        val crew = technicianCrew(visit, viewerMembershipId = "member-7")

        // A member with no profile yet still counts as a person on the Visit (`BR-020`).
        assertEquals(1, crew.others)
    }

    @Test
    fun `names the crew when the caller is not on it`() {
        val visit = presentationVisit(
            crew = listOf(
                crewTechnician("member-2", "Sarah Kim", roleCode = "LEAD"),
                crewTechnician("member-3", "Alex Martin"),
            ),
        )

        val crew = technicianCrew(visit, viewerMembershipId = "member-7")

        assertFalse(crew.isViewerOnCrew)
        assertEquals(2, crew.others)
        assertEquals(listOf("Sarah Kim", "Alex Martin"), crew.names)
    }

    @Test
    fun `emphasises the first Visit that has not completed a field attempt`() {
        val visits = listOf(
            presentationVisit(visitId = "visit-1", status = VisitStatus.COMPLETED),
            presentationVisit(visitId = "visit-2", status = VisitStatus.IN_PROGRESS),
            presentationVisit(visitId = "visit-3", status = VisitStatus.SCHEDULED),
        )

        // The API's chronology is the day's order, so the first outstanding Visit is the earliest
        // work still to be done (`BR-072`, `BR-012`).
        assertEquals("visit-2", nextOutstandingVisitId(visits))
    }

    @Test
    fun `emphasises nothing when the day is done or empty`() {
        assertNull(
            nextOutstandingVisitId(
                listOf(
                    presentationVisit(visitId = "visit-1", status = VisitStatus.COMPLETED),
                    presentationVisit(visitId = "visit-2", status = VisitStatus.COMPLETED),
                ),
            ),
        )
        assertNull(nextOutstandingVisitId(emptyList()))
    }

    @Test
    fun `names the selected day as today, tomorrow or its own date`() {
        val today = LocalDate.parse("2026-09-16")

        assertEquals(TechnicianDayHeading.TODAY, technicianDayHeading(today, today))
        assertEquals(
            TechnicianDayHeading.TOMORROW,
            technicianDayHeading(today.plusDays(1), today),
        )
        // A day the technician browsed to is named by its date alone: "today" and "tomorrow" would
        // both be wrong there (`BR-041`).
        assertEquals(
            TechnicianDayHeading.OTHER,
            technicianDayHeading(today.plusDays(2), today),
        )
        assertEquals(
            TechnicianDayHeading.OTHER,
            technicianDayHeading(today.minusDays(7), today),
        )
    }

    @Test
    fun `covers the seven days the strip draws`() {
        val weekStart = LocalDate.parse("2026-09-14")

        val range = weekRange(weekStart)

        assertEquals(weekStart, range.start)
        assertEquals(LocalDate.parse("2026-09-20"), range.end)
        assertFalse(range.crossesMonth)
    }

    @Test
    fun `reports a week that straddles two months`() {
        val range = weekRange(LocalDate.parse("2026-08-31"))

        assertEquals(LocalDate.parse("2026-08-31"), range.start)
        assertEquals(LocalDate.parse("2026-09-06"), range.end)
        assertTrue(range.crossesMonth)
    }
}

/** One Visit with the crew the test gives it. */
private fun presentationVisit(
    visitId: String = "visit-1",
    status: VisitStatus = VisitStatus.SCHEDULED,
    crew: List<ScheduleTechnician> = emptyList(),
): ScheduleVisit = ScheduleVisit(
    visitId = visitId,
    visitStatus = status,
    scheduledStart = "2026-09-16T13:00:00.000Z",
    scheduledEnd = "2026-09-16T14:00:00.000Z",
    jobId = "job-1",
    jobNumber = 1042,
    jobTitle = "Furnace repair",
    customerId = "customer-1",
    customerName = "ABC Property Management",
    address = null,
    technicians = crew,
    isOverdue = false,
)

/** One assigned technician, Lead first when the test says so (`BR-068`). */
private fun crewTechnician(
    membershipId: String,
    name: String?,
    roleCode: String = "TECHNICIAN",
): ScheduleTechnician = ScheduleTechnician(
    membershipId = membershipId,
    name = name,
    roleCode = roleCode,
)
