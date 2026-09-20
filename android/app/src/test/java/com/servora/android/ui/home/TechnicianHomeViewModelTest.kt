package com.servora.android.ui.home

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.home.TechnicianHomeRepository
import com.servora.android.data.home.TechnicianHomeResult
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAttentionItem
import com.servora.android.domain.model.TechnicianAttentionKind
import com.servora.android.domain.model.TechnicianHome
import com.servora.android.domain.model.TechnicianHomeVisit
import com.servora.android.domain.model.VisitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the technician home screen is told, and what it asks [TechnicianHomeRepository] for.
 *
 * Which Visit is next and which are today's is the backend's answer (`BR-001`), so these tests assert
 * that the answer is reported, that a day served from the device is marked as the last reported one
 * (`BR-013`, offline-first-architecture.md §7), and that a failed read never discards the state the
 * backend already reported (`BR-031`).
 */
class TechnicianHomeViewModelTest {

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
    fun `reads the day for the device zone and reports the backend's values`() =
        runTest(dispatcher) {
            val repository = RecordingTechnicianHomeRepository(
                TechnicianHomeResult.Success(home()),
            )
            val viewModel = TechnicianHomeViewModel(repository)

            viewModel.load(DEVICE_ZONE)
            assertTrue(viewModel.uiState.value.showsInitialLoading)

            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(listOf(DEVICE_ZONE), repository.requestedZones)
            assertFalse(state.isRefreshing)
            assertEquals("visit-2", state.home?.nextVisit?.visitId)
            assertEquals(1, state.home?.visits?.size)
            assertEquals(4, state.home?.upcomingTotal)
            assertEquals(1, state.home?.attentionTotal)
            assertEquals(ReadSource.BACKEND, state.source)
            assertFalse(state.showsLastReportedNotice)
            assertTrue(state.hasContent)
        }

    @Test
    fun `marks a day served from the device as the last reported answer`() = runTest(dispatcher) {
        val repository = RecordingTechnicianHomeRepository(
            TechnicianHomeResult.Success(home(), ReadSource.WORKING_SET),
        )
        val viewModel = TechnicianHomeViewModel(repository)

        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showsLastReportedNotice)
    }

    @Test
    fun `reports the reason a first read failed`() = runTest(dispatcher) {
        val repository = RecordingTechnicianHomeRepository(
            TechnicianHomeResult.Failure(CustomersFailureReason.FORBIDDEN),
        )
        val viewModel = TechnicianHomeViewModel(repository)

        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showsFailure)
        assertEquals(CustomersFailureReason.FORBIDDEN, state.failureReason)
        assertFalse(state.hasContent)
    }

    @Test
    fun `keeps the known day when a later read fails`() = runTest(dispatcher) {
        val repository = RecordingTechnicianHomeRepository(
            TechnicianHomeResult.Success(home()),
            TechnicianHomeResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = TechnicianHomeViewModel(repository)

        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()
        viewModel.retry(DEVICE_ZONE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("visit-2", state.home?.nextVisit?.visitId)
        assertEquals(CustomersFailureReason.NETWORK, state.failureReason)
        // The failure is reported beside the content rather than instead of it.
        assertTrue(state.hasContent)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `reads once for a repeated load and again after a reset`() = runTest(dispatcher) {
        val repository = RecordingTechnicianHomeRepository(
            TechnicianHomeResult.Success(home()),
            TechnicianHomeResult.Success(home()),
        )
        val viewModel = TechnicianHomeViewModel(repository)

        viewModel.load(DEVICE_ZONE)
        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()
        assertEquals(1, repository.requestedZones.size)

        viewModel.reset()
        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()

        // The reset drops the day with the session, and the next session reads it again (`BR-001`).
        assertEquals(2, repository.requestedZones.size)
    }

    @Test
    fun `tells an empty day apart from a day that did not load`() = runTest(dispatcher) {
        val repository = RecordingTechnicianHomeRepository(
            TechnicianHomeResult.Success(
                home().copy(nextVisit = null, visits = emptyList(), upcomingTotal = 0),
            ),
        )
        val viewModel = TechnicianHomeViewModel(repository)

        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Nothing assigned is the read's own answer, not a failure (`BR-042`).
        assertTrue(state.showsNothingAssigned)
        assertFalse(state.showsFailure)
        assertFalse(state.showsInitialLoading)
    }
}

/** A day the backend reported, with a next Visit, one today, an upcoming preview and a condition. */
private fun home(): TechnicianHome =
    TechnicianHome(
        displayName = "Mike Johnson",
        nextVisit = visit(visitId = "visit-2", status = VisitStatus.EN_ROUTE),
        visits = listOf(visit(visitId = "visit-1", status = VisitStatus.COMPLETED)),
        upcoming = emptyList(),
        upcomingTotal = 4,
        attention = listOf(
            TechnicianAttentionItem(
                kind = TechnicianAttentionKind.VISIT_OVERDUE,
                visitId = "visit-9",
                jobId = "job-9",
                jobNumber = 1049,
                jobTitle = "Water heater service",
                jobStatus = JobStatus.SCHEDULED,
                customerId = "customer-1",
                customerName = "ABC Property Management",
                scheduledStart = "2026-09-16T09:00:00.000Z",
                scheduledEnd = "2026-09-16T10:00:00.000Z",
            ),
        ),
        attentionTotal = 1,
    )

private fun visit(visitId: String, status: VisitStatus) =
    TechnicianHomeVisit(
        visitId = visitId,
        visitStatus = status,
        scheduledStart = "2026-09-17T13:00:00.000Z",
        scheduledEnd = "2026-09-17T14:00:00.000Z",
        jobId = "job-2",
        jobNumber = 1043,
        jobTitle = "Furnace repair",
        jobStatus = JobStatus.IN_PROGRESS,
        customerId = "customer-1",
        customerName = "ABC Property Management",
        address = null,
        technicians = emptyList(),
        isOverdue = false,
    )

private const val DEVICE_ZONE = "America/Toronto"

/** A [TechnicianHomeRepository] that answers the results the test scripted, in order. */
private class RecordingTechnicianHomeRepository(
    private vararg val results: TechnicianHomeResult,
) : TechnicianHomeRepository {

    val requestedZones = mutableListOf<String>()

    override suspend fun loadTechnicianHome(timeZone: String): TechnicianHomeResult {
        requestedZones += timeZone
        val index = (requestedZones.size - 1).coerceAtMost(results.lastIndex)
        return results[index]
    }
}

