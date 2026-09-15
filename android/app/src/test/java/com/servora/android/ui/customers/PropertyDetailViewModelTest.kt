package com.servora.android.ui.customers

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.PropertyDeleteResult
import com.servora.android.data.customers.PropertyLifecycleRequest
import com.servora.android.data.customers.PropertyRepository
import com.servora.android.data.customers.PropertyResult
import com.servora.android.data.customers.QueuedPropertyOperation
import com.servora.android.data.customers.UpdatePropertyRequest
import com.servora.android.domain.model.PropertyArchiveImpact
import com.servora.android.domain.model.PropertyDetail
import com.servora.android.domain.model.PropertyStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the Property lifecycle screen is told, and what it asks [PropertyRepository] for.
 *
 * Whether an archive, restore or delete is allowed is the backend's decision (`BR-001`, `BR-007`),
 * so these tests assert that its answer is reported rather than that an action was "correct". They
 * also cover the one-shot signal that tells the destination a confirmed lifecycle change left
 * another screen's derived projections stale.
 */
class PropertyDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse(CAPTURED_AT), ZoneOffset.UTC)

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `reads the open Property and reports the backend's values`() = runTest(dispatcher) {
        val repository = RecordingPropertyRepository(
            loadResult = PropertyResult.Success(property(status = PropertyStatus.ACTIVE)),
        )
        val viewModel = PropertyDetailViewModel(repository, clock)

        viewModel.start(CUSTOMER_ID, PROPERTY_ID, SESSION_ID)
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.failureReason)
        assertEquals(PropertyStatus.ACTIVE, state.detail?.status)
        assertFalse(state.lifecycleChanged)
    }

    @Test
    fun `reports a confirmed archive as one lifecycle change until it is acknowledged`() =
        runTest(dispatcher) {
            val repository = RecordingPropertyRepository(
                loadResult = PropertyResult.Success(property(status = PropertyStatus.ACTIVE)),
                lifecycleResult = PropertyResult.Success(
                    property(status = PropertyStatus.ARCHIVED, version = 8),
                ),
            )
            val viewModel = PropertyDetailViewModel(repository, clock)
            viewModel.start(CUSTOMER_ID, PROPERTY_ID, SESSION_ID)
            advanceUntilIdle()

            viewModel.archive()
            advanceUntilIdle()

            val archived = viewModel.uiState.value
            assertEquals(PropertyStatus.ARCHIVED, archived.detail?.status)
            assertFalse(archived.isWorking)
            // The customer's Property rows and counts are stale until they are re-read, so the
            // destination is told exactly once (`BR-001`).
            assertTrue(archived.lifecycleChanged)

            // The action carried the version the client last saw and an idempotency key, so a
            // replay cannot be applied twice (`BR-031`, `BR-086`).
            assertEquals(7, repository.lastLifecycleRequest?.expectedVersion)
            assertEquals(CAPTURED_AT, repository.lastLifecycleRequest?.capturedAt)
            assertNotNull(repository.lastLifecycleRequest?.clientOperationId)

            viewModel.acknowledgeLifecycleChange()
            assertFalse(viewModel.uiState.value.lifecycleChanged)
        }

    @Test
    fun `reports a confirmed restore as a lifecycle change`() = runTest(dispatcher) {
        val repository = RecordingPropertyRepository(
            loadResult = PropertyResult.Success(property(status = PropertyStatus.ARCHIVED)),
            lifecycleResult = PropertyResult.Success(property(status = PropertyStatus.ACTIVE)),
        )
        val viewModel = PropertyDetailViewModel(repository, clock)
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, SESSION_ID)
        advanceUntilIdle()

        viewModel.restore()
        advanceUntilIdle()

        assertEquals(PropertyStatus.ACTIVE, viewModel.uiState.value.detail?.status)
        assertTrue(viewModel.uiState.value.lifecycleChanged)
    }

    @Test
    fun `does not signal a lifecycle change when the archive fails`() = runTest(dispatcher) {
        val repository = RecordingPropertyRepository(
            loadResult = PropertyResult.Success(property(status = PropertyStatus.ACTIVE)),
            lifecycleResult = PropertyResult.Failure(CustomersFailureReason.VERSION_CONFLICT),
        )
        val viewModel = PropertyDetailViewModel(repository, clock)
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, SESSION_ID)
        advanceUntilIdle()

        viewModel.archive()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Nothing changed on the backend, so the screen keeps the Property the API last described
        // and no projection is re-read (`BR-001`, `BR-086`).
        assertEquals(PropertyStatus.ACTIVE, state.detail?.status)
        assertEquals(CustomersFailureReason.VERSION_CONFLICT, state.actionFailure)
        assertFalse(state.lifecycleChanged)
    }

    @Test
    fun `does not carry a failed action into a new session`() = runTest(dispatcher) {
        val repository = RecordingPropertyRepository(
            loadResult = PropertyResult.Success(property(status = PropertyStatus.ACTIVE)),
            lifecycleResult = PropertyResult.Failure(CustomersFailureReason.VERSION_CONFLICT),
        )
        val viewModel = PropertyDetailViewModel(repository, clock)
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, SESSION_ID)
        advanceUntilIdle()
        viewModel.archive()
        advanceUntilIdle()
        assertEquals(
            CustomersFailureReason.VERSION_CONFLICT,
            viewModel.uiState.value.actionFailure,
        )

        // Leaving the screen and opening it again is a new destination instance, so the previous
        // instance's failure must not be shown again.
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-2")

        assertNull(viewModel.uiState.value.actionFailure)
        // The Property it already read is reused rather than re-fetched (`BR-001`).
        assertNotNull(viewModel.uiState.value.detail)
        assertEquals(PropertyStatus.ACTIVE, viewModel.uiState.value.detail?.status)
    }

    private fun property(
        status: PropertyStatus,
        version: Int = 7,
    ) = PropertyDetail(
        id = PROPERTY_ID,
        name = "Cedar Lane Building",
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
        notes = null,
        status = status,
        version = version,
        archivedAt = null,
        jobCount = 0,
        lastServiceAt = null,
        archiveImpact = PropertyArchiveImpact(activeJobCount = 0, activeVisitCount = 0),
        canBePermanentlyDeleted = false,
    )

    private companion object {
        const val CUSTOMER_ID = "c1"
        const val PROPERTY_ID = "p1"
        const val SESSION_ID = "session-1"
        const val CAPTURED_AT = "2026-09-13T14:39:21.123456Z"
    }
}

/** A [PropertyRepository] that answers with fixed results and records what it was asked. */
private class RecordingPropertyRepository(
    var loadResult: PropertyResult = PropertyResult.Failure(CustomersFailureReason.UNEXPECTED),
    var lifecycleResult: PropertyResult = PropertyResult.Failure(CustomersFailureReason.UNEXPECTED),
    var deleteResult: PropertyDeleteResult =
        PropertyDeleteResult.Failure(CustomersFailureReason.UNEXPECTED),
) : PropertyRepository {

    var lastLifecycleRequest: PropertyLifecycleRequest? = null

    /** The lifecycle action the screen should report as waiting, if any. */
    var queuedOperation: QueuedPropertyOperation? = null

    /** Whether a queued operation was accepted by the backend, driven by a test. */
    val applied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val appliedOperations: Flow<Unit> = applied

    override suspend fun queuedOperation(propertyId: String): QueuedPropertyOperation? =
        queuedOperation

    override suspend fun loadProperty(customerId: String, propertyId: String): PropertyResult =
        loadResult

    override suspend fun updateProperty(
        customerId: String,
        propertyId: String,
        request: UpdatePropertyRequest,
    ): PropertyResult = lifecycleResult

    override suspend fun archiveProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult {
        lastLifecycleRequest = request
        return lifecycleResult
    }

    override suspend fun restoreProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult {
        lastLifecycleRequest = request
        return lifecycleResult
    }

    override suspend fun deleteProperty(
        customerId: String,
        propertyId: String,
    ): PropertyDeleteResult = deleteResult
}
