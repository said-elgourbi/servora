package com.servora.android.ui.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleDay
import com.servora.android.domain.model.ScheduleScope
import com.servora.android.domain.model.ScheduleScopeKind
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
 * What the technician's schedule is told, and what it asks [ScheduleRepository] for.
 *
 * Which Visits a day holds, their statuses and who is on them are the backend's answers
 * (`BR-001`, `BR-042`), and so is the scope the day was resolved for (`BR-009`). These tests
 * therefore assert that the day the screen holds is the day the read carries, that the read never
 * narrows itself to a technician the client chose, that a refresh of the day on screen never blanks
 * it, and that a day the technician has already left is released with its content.
 */
class TechnicianScheduleViewModelTest {

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
    fun `opens on today, reading the caller's own work`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val viewModel = TechnicianScheduleViewModel(repository)

        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        assertTrue(viewModel.uiState.value.showsInitialLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        // The filter is empty on purpose: the read is the caller's own work, and the API resolves
        // whose it is from the session (`BR-009`, `BR-007`).
        assertEquals(
            listOf(TechnicianScheduleRead("2026-09-07", TECH_DEVICE_ZONE, emptyList())),
            repository.requested,
        )
        assertEquals(TECH_MONDAY, state.selectedDate)
        assertEquals(TECH_MONDAY, state.displayedWeekStart)
        assertFalse(state.isRefreshing)
        assertEquals(listOf("visit-1"), state.visits.map { it.visitId })
        assertEquals("member-7", state.viewerMembershipId)
        assertFalse(state.showsLastReportedNotice)
        assertFalse(state.showsEmptyDay)
    }

    @Test
    fun `moves to another day and reads it, bringing its week with it`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
            ScheduleResult.Success(technicianDay(localDate = "2026-09-08")),
        )
        val viewModel = TechnicianScheduleViewModel(repository)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectDate(TECH_TUESDAY, DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(
            listOf(
                TechnicianScheduleRead("2026-09-07", TECH_DEVICE_ZONE, emptyList()),
                TechnicianScheduleRead("2026-09-08", TECH_DEVICE_ZONE, emptyList()),
            ),
            repository.requested,
        )
        val state = viewModel.uiState.value
        assertEquals(TECH_TUESDAY, state.selectedDate)
        assertEquals(TECH_MONDAY, state.displayedWeekStart)
        assertEquals("2026-09-08", state.schedule?.day?.localDate)
    }

    @Test
    fun `returning to the day already read asks nothing`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val viewModel = TechnicianScheduleViewModel(repository)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectDate(TECH_MONDAY, DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(1, repository.requested.size)
    }

    @Test
    fun `swiping the strip moves the week without reading a day nobody chose`() =
        runTest(dispatcher) {
            val repository = RecordingTechnicianScheduleRepository(
                ScheduleResult.Success(technicianDay()),
            )
            val viewModel = TechnicianScheduleViewModel(repository)
            viewModel.start(
                today = TECH_MONDAY,
                timeZoneId = TECH_DEVICE_ZONE,
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
            advanceUntilIdle()

            viewModel.showWeek(TECH_MONDAY.plusWeeks(1))
            advanceUntilIdle()

            assertEquals(1, repository.requested.size)
            val state = viewModel.uiState.value
            assertEquals(TECH_MONDAY.plusWeeks(1), state.displayedWeekStart)
            assertEquals(TECH_MONDAY, state.selectedDate)
        }

    @Test
    fun `the way back to today selects and reads today`() = runTest(dispatcher) {
        val later = TECH_MONDAY.plusWeeks(2)
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
            ScheduleResult.Success(technicianDay(localDate = later.toString())),
            ScheduleResult.Success(technicianDay()),
        )
        val viewModel = TechnicianScheduleViewModel(repository)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()
        viewModel.selectDate(later, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.showToday(TECH_MONDAY, DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(
            TechnicianScheduleRead("2026-09-07", TECH_DEVICE_ZONE, emptyList()),
            repository.requested.last(),
        )
        assertEquals(TECH_MONDAY, viewModel.uiState.value.selectedDate)
    }

    @Test
    fun `keeps the day on screen when a refresh of it fails`() = runTest(dispatcher) {
        // The technician keeps reading the day they already have while the backend is unreachable
        // (`BR-013`): the failure is reported beside the content rather than replacing it.
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
            ScheduleResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = TechnicianScheduleViewModel(repository)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("visit-1"), state.visits.map { it.visitId })
        assertEquals(CustomersFailureReason.NETWORK, state.failureReason)
        assertFalse(state.showsFailure)
        assertTrue(state.hasContent)
    }

    @Test
    fun `releases a day that belongs to the day left behind`() = runTest(dispatcher) {
        // Content is always the day it was read for: a day that fails to load reports the failure
        // rather than showing the previous day's Visits as if they were this one's (`BR-042`).
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
            ScheduleResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = TechnicianScheduleViewModel(repository)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectDate(TECH_TUESDAY, DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.schedule)
        assertTrue(state.showsFailure)
        assertEquals(CustomersFailureReason.NETWORK, state.failureReason)
    }

    @Test
    fun `marks a day the working set answered rather than the backend`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(
                schedule = technicianDay(),
                source = ReadSource.WORKING_SET,
            ),
        )
        val viewModel = TechnicianScheduleViewModel(repository)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showsLastReportedNotice)
    }

    @Test
    fun `drops an answer belonging to a day the technician has already left`() =
        runTest(dispatcher) {
            val repository = RecordingTechnicianScheduleRepository(
                ScheduleResult.Success(technicianDay()),
                ScheduleResult.Success(technicianDay(localDate = TECH_TUESDAY.toString())),
            )
            val viewModel = TechnicianScheduleViewModel(repository)

            viewModel.start(
                today = TECH_MONDAY,
                timeZoneId = TECH_DEVICE_ZONE,
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
            // The day is moved on before the first answer lands, so that answer belongs to a day the
            // screen is no longer showing (`BR-067`).
            viewModel.selectDate(TECH_TUESDAY, DayOfWeek.MONDAY)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(TECH_TUESDAY, state.selectedDate)
            assertEquals("2026-09-08", state.schedule?.day?.localDate)
        }

    @Test
    fun `remembers nothing once the session ends`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val viewModel = TechnicianScheduleViewModel(repository)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.reset()

        assertEquals(TechnicianScheduleUiState(), viewModel.uiState.value)
    }
}

private val TECH_MONDAY: LocalDate = LocalDate.parse("2026-09-07")
private val TECH_TUESDAY: LocalDate = LocalDate.parse("2026-09-08")

private const val TECH_DEVICE_ZONE = "America/Toronto"

/** One read the ViewModel asked for, so a test can assert the day, the zone and the filter. */
private data class TechnicianScheduleRead(
    val localDate: String,
    val timeZone: String,
    val membershipIds: List<String>,
)

/** A [ScheduleRepository] that answers the results the test scripted, in order. */
private class RecordingTechnicianScheduleRepository(
    private vararg val results: ScheduleResult,
) : ScheduleRepository {

    val requested = mutableListOf<TechnicianScheduleRead>()

    override suspend fun loadSchedule(
        localDate: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleResult {
        requested += TechnicianScheduleRead(localDate, timeZone, membershipIds)
        val index = (requested.size - 1).coerceAtMost(results.lastIndex)
        return results[index]
    }
}

/** One day as the backend answers it for the caller's own work (`BR-009`). */
private fun technicianDay(
    localDate: String = "2026-09-07",
    visits: List<ScheduleVisit> = listOf(technicianVisit("visit-1")),
    membershipId: String = "member-7",
): Schedule = Schedule(
    day = ScheduleDay(localDate = localDate, timeZone = TECH_DEVICE_ZONE),
    scope = ScheduleScope(kind = ScheduleScopeKind.SELF, membershipId = membershipId),
    technicians = emptyList(),
    visits = visits,
    unassigned = emptyList(),
    unassignedTotal = 0,
    hasUnassignedLane = false,
)

/** One assigned Visit. */
private fun technicianVisit(
    visitId: String,
    status: VisitStatus = VisitStatus.SCHEDULED,
): ScheduleVisit = ScheduleVisit(
    visitId = visitId,
    visitStatus = status,
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
)
