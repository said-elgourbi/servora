package com.servora.android.ui.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
import com.servora.android.data.schedule.VisitRequestReviewResult
import com.servora.android.data.schedule.VisitRequestsRepository
import com.servora.android.data.schedule.VisitRequestsResult
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleScope
import com.servora.android.domain.model.ScheduleScopeKind
import com.servora.android.domain.model.ScheduleDay
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitStatus
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the schedule screen is told, and what it asks [ScheduleRepository] for.
 *
 * Which Visits a day holds, what is unassigned and whether a Visit is overdue are the backend's
 * answers (`BR-001`, `BR-042`), so these tests assert that the day and the filter the screen holds
 * are the ones the read carries, that a failed read never leaves a stale day on screen, and that a
 * result belonging to a day the manager has already left is dropped.
 */
class ScheduleViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opens on today and reads it for the device zone`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())

        viewModel.start(today = MONDAY, timeZoneId = DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        assertTrue(viewModel.uiState.value.showsInitialLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            listOf(ScheduleRead("2026-09-07", DEVICE_ZONE, emptyList())),
            repository.requested,
        )
        assertEquals(MONDAY, state.selectedDate)
        assertEquals(MONDAY, state.displayedWeekStart)
        assertFalse(state.isRefreshing)
        assertEquals(listOf("visit-1"), state.schedule?.visits?.map { it.visitId })
        assertEquals(3, state.unassignedCount)
        assertTrue(state.showsSelectedWeek)
    }

    @Test
    fun `selects a day and moves the strip to its week`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        // Sunday of the following week: the strip follows the date it was given.
        viewModel.selectDate(LocalDate.parse("2026-09-13"), DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(LocalDate.parse("2026-09-13"), state.selectedDate)
        assertEquals(LocalDate.parse("2026-09-07"), state.displayedWeekStart)
        assertEquals(
            ScheduleRead("2026-09-13", DEVICE_ZONE, emptyList()),
            repository.requested.last(),
        )
    }

    @Test
    fun `shows another week without reading a day the manager did not choose`() =
        runTest(dispatcher) {
            val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
            val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
            viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
            advanceUntilIdle()

            viewModel.showWeek(LocalDate.parse("2026-09-14"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(LocalDate.parse("2026-09-14"), state.displayedWeekStart)
            assertEquals(MONDAY, state.selectedDate)
            assertFalse(state.showsSelectedWeek)
            // One read: reaching a week is a move of the strip, not a read of a day nobody picked.
            assertEquals(1, repository.requested.size)
        }

    @Test
    fun `reads the day again when the technician filter changes`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectTechnicians(listOf(technician()))
        advanceUntilIdle()

        assertEquals(
            ScheduleRead("2026-09-07", DEVICE_ZONE, listOf("member-1")),
            repository.requested.last(),
        )
        assertEquals(listOf("member-1"), viewModel.uiState.value.technicianFilterIds)
        assertTrue(viewModel.uiState.value.hasTechnicianFilter)

        // Selecting the same technician again is not a second read.
        viewModel.selectTechnicians(listOf(technician()))
        advanceUntilIdle()
        assertEquals(2, repository.requested.size)
    }

    @Test
    fun `reads one day for a group of technicians rather than a request each`() =
        runTest(dispatcher) {
            val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
            val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
            viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
            advanceUntilIdle()

            viewModel.selectTechnicians(
                listOf(technician(), technician(membershipId = "member-2", name = "Luc Gagnon")),
            )
            advanceUntilIdle()

            // The union of the selected technicians is one read: the API answers with the Visits any
            // of them is assigned to (`BR-068`), so a group is never a request per technician.
            assertEquals(2, repository.requested.size)
            assertEquals(
                ScheduleRead("2026-09-07", DEVICE_ZONE, listOf("member-1", "member-2")),
                repository.requested.last(),
            )
            assertEquals(
                listOf("member-1", "member-2"),
                viewModel.uiState.value.technicianFilterIds,
            )
        }

    @Test
    fun `shows the whole organization again when the filter is cleared`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectTechnicians(listOf(technician()))
        advanceUntilIdle()
        // `All technicians` removes the technician predicate entirely rather than naming everybody.
        viewModel.selectTechnicians(emptyList())
        advanceUntilIdle()

        assertEquals(
            ScheduleRead("2026-09-07", DEVICE_ZONE, emptyList()),
            repository.requested.last(),
        )
        assertFalse(viewModel.uiState.value.hasTechnicianFilter)
    }

    @Test
    fun `carries the filter into another day's read`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectTechnicians(listOf(technician()))
        advanceUntilIdle()
        viewModel.selectDate(LocalDate.parse("2026-09-08"), DayOfWeek.MONDAY)
        advanceUntilIdle()

        // The filter is the screen's own state, so moving to another day narrows it the same way:
        // the manager's selection is not silently dropped by changing date (`BR-068`).
        assertEquals(
            ScheduleRead("2026-09-08", DEVICE_ZONE, listOf("member-1")),
            repository.requested.last(),
        )
        assertEquals(listOf("member-1"), viewModel.uiState.value.technicianFilterIds)
    }

    @Test
    fun `switches lanes without reading the day again`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectLane(ScheduleLane.UNASSIGNED)
        advanceUntilIdle()

        // Both lanes are one read's answers (`BR-042`).
        assertEquals(1, repository.requested.size)
        assertEquals(ScheduleLane.UNASSIGNED, viewModel.uiState.value.lane)
    }

    @Test
    fun `loads pending follow up requests with the schedule`() = runTest(dispatcher) {
        val requests = RecordingVisitRequestsRepository(request())
        val viewModel = ScheduleViewModel(
            repository = RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            visitRequestsRepository = requests,
        )

        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(1, requests.reads)
        assertEquals(listOf("request-1"), viewModel.uiState.value.pendingRequests.map { it.id })
        assertEquals(1, viewModel.uiState.value.pendingRequestCount)
    }

    @Test
    fun `rejecting a pending request updates the pending request count`() = runTest(dispatcher) {
        val pending = request()
        val requests = RecordingVisitRequestsRepository(
            pending,
            reviewed = pending.copy(status = FollowUpVisitRequestStatus.REJECTED, version = 2),
        )
        val viewModel = ScheduleViewModel(
            repository = RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            visitRequestsRepository = requests,
        )
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.rejectRequest(pending)
        advanceUntilIdle()

        assertEquals(listOf("request-1"), requests.rejected.map { it.id })
        assertEquals(0, viewModel.uiState.value.pendingRequestCount)
        assertNull(viewModel.uiState.value.reviewingRequestId)
    }

    @Test
    fun `reports the reason a first read failed`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(
            ScheduleResult.Failure(CustomersFailureReason.FORBIDDEN),
        )
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())

        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showsFailure)
        assertEquals(CustomersFailureReason.FORBIDDEN, state.failureReason)
        assertNull(state.schedule)
    }

    @Test
    fun `never leaves another day's schedule on screen after a failed read`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(
            ScheduleResult.Success(schedule()),
            ScheduleResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectDate(LocalDate.parse("2026-09-08"), DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // The day on screen changed, so the previous day's Visits are not presented as this day's.
        assertNull(state.schedule)
        assertTrue(state.showsFailure)
    }

    @Test
    fun `drops an answer that belongs to a day the manager has already left`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)

        // Two selections before the first read settles: only the last one's answer may be applied.
        viewModel.selectDate(LocalDate.parse("2026-09-08"), DayOfWeek.MONDAY)
        viewModel.selectDate(LocalDate.parse("2026-09-09"), DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(LocalDate.parse("2026-09-09"), viewModel.uiState.value.selectedDate)
        assertTrue(viewModel.uiState.value.hasContent)
    }

    @Test
    fun `tells an empty day apart from a day that did not load`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(
            ScheduleResult.Success(schedule().copy(visits = emptyList(), unassigned = emptyList())),
        )
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())

        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Nothing scheduled is the read's own answer, not a failure (`BR-042`).
        assertTrue(state.showsEmptyDay)
        assertTrue(state.showsNoUnassignedWork)
        assertFalse(state.showsFailure)
        assertFalse(state.showsInitialLoading)
    }

    @Test
    fun `forgets the day with the session`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.reset()
        assertNull(viewModel.uiState.value.selectedDate)

        // The next session opens on its own today again (`BR-001`).
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()
        assertEquals(2, repository.requested.size)
    }
}

/** One read the ViewModel asked for. */
private data class ScheduleRead(
    val localDate: String,
    val timeZone: String,
    val membershipIds: List<String>,
)

private fun technician(
    membershipId: String = "member-1",
    name: String = "Mike Johnson",
) = ScheduleTechnician(
    membershipId = membershipId,
    name = name,
    roleCode = "",
)

/** The backend's answer for one day: a scheduled Visit, one waiting for a crew, and the crew. */
private fun schedule(): Schedule = Schedule(
    day = ScheduleDay(localDate = "2026-09-07", timeZone = DEVICE_ZONE),
    scope = ScheduleScope(
        kind = ScheduleScopeKind.ORGANIZATION,
        membershipId = "member-1",
    ),
    technicians = listOf(technician()),
    visits = listOf(
        ScheduleVisit(
            visitId = "visit-1",
            visitStatus = VisitStatus.SCHEDULED,
            scheduledStart = "2026-09-07T13:00:00.000Z",
            scheduledEnd = "2026-09-07T14:00:00.000Z",
            jobId = "job-1",
            jobNumber = 1042,
            jobTitle = "Furnace repair",
            customerId = "customer-1",
            customerName = "ABC Property Management",
            address = null,
            technicians = emptyList(),
            isOverdue = false,
        ),
    ),
    unassigned = listOf(
        ScheduleVisit(
            visitId = "visit-9",
            visitStatus = VisitStatus.DRAFT,
            scheduledStart = null,
            scheduledEnd = null,
            jobId = "job-9",
            jobNumber = 1049,
            jobTitle = "Boiler service",
            customerId = "customer-1",
            customerName = "ABC Property Management",
            address = null,
            technicians = emptyList(),
            isOverdue = false,
        ),
    ),
    unassignedTotal = 3,
    hasUnassignedLane = true,
)

private val MONDAY: LocalDate = LocalDate.parse("2026-09-07")

private const val DEVICE_ZONE = "America/Toronto"

private fun request(
    id: String = "request-1",
    status: FollowUpVisitRequestStatus = FollowUpVisitRequestStatus.PENDING,
) = FollowUpVisitRequest(
    id = id,
    jobId = "job-1",
    sourceVisitId = "visit-1",
    requestingTechnicianMembershipId = "membership-1",
    proposedStart = "2026-09-08T13:00:00.000Z",
    proposedEnd = "2026-09-08T14:00:00.000Z",
    reason = "Return with a replacement part.",
    sameTechnicianPreferred = true,
    status = status,
    reviewerMembershipId = null,
    reviewedAt = null,
    reviewNote = null,
    createdVisitId = null,
    version = 1,
    createdAt = "2026-09-07T12:00:00.000Z",
    updatedAt = "2026-09-07T12:00:00.000Z",
)

/** A [ScheduleRepository] that answers the results the test scripted, in order. */
private class RecordingScheduleRepository(
    private vararg val results: ScheduleResult,
) : ScheduleRepository {

    val requested = mutableListOf<ScheduleRead>()

    override suspend fun loadSchedule(
        localDate: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleResult {
        requested += ScheduleRead(localDate, timeZone, membershipIds)
        val index = (requested.size - 1).coerceAtMost(results.lastIndex)
        return results[index]
    }
}

private class EmptyVisitRequestsRepository : VisitRequestsRepository {
    override suspend fun loadRequests(): VisitRequestsResult =
        VisitRequestsResult.Success(emptyList())

    override suspend fun askForClarification(
        request: FollowUpVisitRequest,
    ): VisitRequestReviewResult =
        VisitRequestReviewResult.Success(request)

    override suspend fun reject(request: FollowUpVisitRequest): VisitRequestReviewResult =
        VisitRequestReviewResult.Success(request)
}

private class RecordingVisitRequestsRepository(
    private vararg val requests: FollowUpVisitRequest,
    private val reviewed: FollowUpVisitRequest = requests.first(),
) : VisitRequestsRepository {
    var reads = 0
        private set
    val rejected = mutableListOf<FollowUpVisitRequest>()

    override suspend fun loadRequests(): VisitRequestsResult {
        reads += 1
        return VisitRequestsResult.Success(requests.toList())
    }

    override suspend fun askForClarification(
        request: FollowUpVisitRequest,
    ): VisitRequestReviewResult =
        VisitRequestReviewResult.Success(reviewed)

    override suspend fun reject(request: FollowUpVisitRequest): VisitRequestReviewResult {
        rejected += request
        return VisitRequestReviewResult.Success(reviewed)
    }
}
