package com.servora.android.ui.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import java.time.ZoneId
import java.util.Locale
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

    @Test
    fun `resolves the request the callers own read holds`() {
        val content = requestDetailsContent(
            state = requestState(requests = listOf(request())),
            requestId = "request-1",
        )

        assertEquals(
            RequestDetailsContent.Request(request()),
            content,
        )
    }

    @Test
    fun `states a request the read did not answer with as unavailable`() {
        val content = requestDetailsContent(
            state = requestState(requests = listOf(request(id = "request-2"))),
            requestId = "request-1",
        )

        // The read answered and this request is not one of the caller's own (`BR-FV-001`, `BR-009`).
        assertEquals(RequestDetailsContent.Unavailable, content)
    }

    @Test
    fun `states the first read as nothing answered yet`() {
        val content = requestDetailsContent(
            state = requestState(requestsRead = false, requests = emptyList()),
            requestId = "request-1",
        )

        assertEquals(RequestDetailsContent.Reading, content)
    }

    @Test
    fun `states a read that failed as failed`() {
        val content = requestDetailsContent(
            state = requestState(
                requestsRead = false,
                requests = emptyList(),
                failure = CustomersFailureReason.NETWORK,
            ),
            requestId = "request-1",
        )

        assertEquals(RequestDetailsContent.Failed, content)
    }

    @Test
    fun `resolves a request the list still holds after a refresh failed`() {
        val content = requestDetailsContent(
            state = requestState(
                requests = listOf(request()),
                failure = CustomersFailureReason.NETWORK,
            ),
            requestId = "request-1",
        )

        // The list on screen is the last one the backend reported and it holds the request, so the
        // request is what is read rather than the failed refresh (`BR-013`).
        assertEquals(RequestDetailsContent.Request(request()), content)
    }
    @Test
    fun `includes the proposal date and resolves it in the schedule zone`() {
        val window = formatRequestWindow(
            start = "2026-09-30T01:00:00.000Z",
            end = "2026-09-30T02:00:00.000Z",
            zone = ZoneId.of("America/Toronto"),
            locale = Locale.CANADA,
        )

        assertEquals("Sep 29, 2026 · 9:00 p.m. – 10:00 p.m.", window)
    }

    @Test
    fun `does not invent a window for an unreadable proposal`() {
        assertNull(
            formatRequestWindow(
                start = "not-an-instant",
                end = null,
                zone = ZoneId.of("America/Toronto"),
                locale = Locale.CANADA,
            ),
        )
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

/** The schedule state a request details destination is resolved from. */
private fun requestState(
    requests: List<FollowUpVisitRequest> = listOf(request()),
    requestsRead: Boolean = true,
    failure: CustomersFailureReason? = null,
): TechnicianScheduleUiState = TechnicianScheduleUiState(
    requestsRead = requestsRead,
    requests = requests,
    requestsFailureReason = failure,
)

/** One request as the API answers it for the technician who raised it (`BR-FV-001`). */
private fun request(
    id: String = "request-1",
    status: FollowUpVisitRequestStatus = FollowUpVisitRequestStatus.PENDING,
): FollowUpVisitRequest = FollowUpVisitRequest(
    id = id,
    jobId = "job-1",
    sourceVisitId = "visit-1",
    requestingTechnicianMembershipId = "member-7",
    proposedStart = "2026-09-09T13:00:00.000Z",
    proposedEnd = "2026-09-09T14:00:00.000Z",
    reason = "The part did not fit.",
    sameTechnicianPreferred = false,
    status = status,
    reviewerMembershipId = null,
    reviewedAt = null,
    reviewNote = null,
    createdVisitId = null,
    version = 1,
    createdAt = "2026-09-08T12:00:00.000Z",
    updatedAt = "2026-09-08T12:00:00.000Z",
)
