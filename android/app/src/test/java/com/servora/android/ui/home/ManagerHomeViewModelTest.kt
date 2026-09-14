package com.servora.android.ui.home

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.home.ManagerHomeRepository
import com.servora.android.data.home.ManagerHomeResult
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.ManagerAttentionItem
import com.servora.android.domain.model.ManagerAttentionKind
import com.servora.android.domain.model.ManagerHome
import com.servora.android.domain.model.ManagerHomeTodaySummary
import com.servora.android.domain.model.ManagerHomeVisit
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the manager home screen is told, and what it asks [ManagerHomeRepository] for.
 *
 * Which Visits need attention and what today's counts are is the backend's answer (`BR-001`), so
 * these tests assert that the answer is reported, and that a failed read never discards the state
 * the backend already reported (`BR-013`, `BR-031`).
 */
class ManagerHomeViewModelTest {

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
    fun `reads the day for the device zone and reports the backend's values`() = runTest(dispatcher) {
        val repository = RecordingManagerHomeRepository(ManagerHomeResult.Success(home()))
        val viewModel = ManagerHomeViewModel(repository)

        viewModel.load(DEVICE_ZONE)
        assertTrue(viewModel.uiState.value.showsInitialLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(DEVICE_ZONE), repository.requestedZones)
        assertFalse(state.isRefreshing)
        assertNull(state.failureReason)
        assertTrue(state.hasContent)
        assertEquals(2, state.home?.attentionTotal)
        assertEquals(5, state.home?.today?.total)
        assertEquals(1, state.home?.visits?.size)
    }

    @Test
    fun `re-entering Home refreshes without issuing a second request while one is in flight`() =
        runTest(dispatcher) {
            val repository = RecordingManagerHomeRepository(ManagerHomeResult.Success(home()))
            val viewModel = ManagerHomeViewModel(repository)

            viewModel.load(DEVICE_ZONE)
            viewModel.load(DEVICE_ZONE)
            advanceUntilIdle()

            assertEquals(1, repository.loads)

            // A later entry, once the first read has finished, is a refresh of known data.
            viewModel.load(DEVICE_ZONE)
            advanceUntilIdle()

            assertEquals(2, repository.loads)
            assertTrue(viewModel.uiState.value.hasContent)
        }

    @Test
    fun `keeps the reported day on screen when a refresh fails`() = runTest(dispatcher) {
        val repository = RecordingManagerHomeRepository(ManagerHomeResult.Success(home()))
        val viewModel = ManagerHomeViewModel(repository)
        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()

        repository.result = ManagerHomeResult.Failure(CustomersFailureReason.NETWORK)
        viewModel.retry(DEVICE_ZONE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CustomersFailureReason.NETWORK, state.failureReason)
        // The screen keeps describing the last state the backend reported rather than blanking.
        assertTrue(state.hasContent)
        assertTrue(state.showsUnrefreshedNotice)
        assertFalse(state.showsFailure)
    }

    @Test
    fun `reports a failure when nothing has been read yet`() = runTest(dispatcher) {
        val repository = RecordingManagerHomeRepository(
            ManagerHomeResult.Failure(CustomersFailureReason.SERVER),
        )
        val viewModel = ManagerHomeViewModel(repository)

        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CustomersFailureReason.SERVER, state.failureReason)
        assertTrue(state.showsFailure)
        assertFalse(state.hasContent)
    }

    @Test
    fun `retries after a failure and reports the day that comes back`() = runTest(dispatcher) {
        val repository = RecordingManagerHomeRepository(
            ManagerHomeResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = ManagerHomeViewModel(repository)
        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()

        repository.result = ManagerHomeResult.Success(home())
        viewModel.retry(DEVICE_ZONE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.failureReason)
        assertTrue(state.hasContent)
        assertEquals(2, repository.loads)
    }

    @Test
    fun `forgets the day when the session ends`() = runTest(dispatcher) {
        val repository = RecordingManagerHomeRepository(ManagerHomeResult.Success(home()))
        val viewModel = ManagerHomeViewModel(repository)
        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasContent)

        viewModel.reset()

        val state = viewModel.uiState.value
        assertFalse(state.hasContent)
        assertNull(state.failureReason)
        assertFalse(state.isRefreshing)
        // The next session's first entry reads again rather than trusting what this one loaded.
        viewModel.load(DEVICE_ZONE)
        advanceUntilIdle()
        assertEquals(2, repository.loads)
    }

    @Test
    fun `drops a read that finishes after the session ended`() = runTest(dispatcher) {
        val repository = RecordingManagerHomeRepository(ManagerHomeResult.Success(home()))
        val viewModel = ManagerHomeViewModel(repository)

        viewModel.load(DEVICE_ZONE)
        // The session ends while the read is still in flight.
        viewModel.reset()
        advanceUntilIdle()

        // The ended session's answer must not be written over the new session's empty state.
        assertFalse(viewModel.uiState.value.hasContent)
    }

    private fun home() = ManagerHome(
        displayName = "Sarah Tremblay",
        attention = listOf(
            ManagerAttentionItem(
                kind = ManagerAttentionKind.VISIT_OVERDUE,
                jobId = "job-1",
                jobNumber = 1042,
                jobTitle = "Furnace repair",
                jobStatus = JobStatus.SCHEDULED,
                customerId = "customer-1",
                customerName = "ABC Property Management",
                visitId = "visit-1",
                scheduledStart = "2026-09-14T11:00:00.000Z",
                scheduledEnd = "2026-09-14T12:00:00.000Z",
            ),
        ),
        attentionTotal = 2,
        today = ManagerHomeTodaySummary(total = 5, completed = 2, inProgress = 1, upcoming = 2),
        visits = listOf(
            ManagerHomeVisit(
                visitId = "visit-1",
                visitStatus = VisitStatus.SCHEDULED,
                scheduledStart = "2026-09-14T11:00:00.000Z",
                scheduledEnd = "2026-09-14T12:00:00.000Z",
                jobId = "job-1",
                jobNumber = 1042,
                jobTitle = "Furnace repair",
                jobStatus = JobStatus.SCHEDULED,
                customerId = "customer-1",
                customerName = "ABC Property Management",
                address = null,
                technicians = emptyList(),
                isOverdue = true,
            ),
        ),
    )

    private companion object {
        const val DEVICE_ZONE = "America/Toronto"
    }
}

/** A [ManagerHomeRepository] that answers with a scripted result and records what it was asked. */
private class RecordingManagerHomeRepository(
    var result: ManagerHomeResult,
) : ManagerHomeRepository {

    var loads = 0
    val requestedZones = mutableListOf<String>()

    override suspend fun loadManagerHome(timeZone: String): ManagerHomeResult {
        loads += 1
        requestedZones += timeZone
        return result
    }
}
