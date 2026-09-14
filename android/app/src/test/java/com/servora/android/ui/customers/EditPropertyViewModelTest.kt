package com.servora.android.ui.customers

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.PropertyDeleteResult
import com.servora.android.data.customers.PropertyLifecycleRequest
import com.servora.android.data.customers.PropertyRepository
import com.servora.android.data.customers.PropertyResult
import com.servora.android.data.customers.UpdatePropertyRequest
import com.servora.android.domain.model.PropertyArchiveImpact
import com.servora.android.domain.model.PropertyDetail
import com.servora.android.domain.model.PropertyProvince
import com.servora.android.domain.model.PropertyStatus
import kotlinx.coroutines.CompletableDeferred
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
 * What the Edit Property form reads, sends and refuses to send, and which of its transient values
 * belong to a form session.
 *
 * Whether an edit is allowed is the backend's decision (`BR-001`, `BR-007`), so these tests assert
 * that its answer is reported and that a form session never inherits the previous session's values,
 * validation message or save error (`BR-084`, `BR-086`).
 */
class EditPropertyViewModelTest {

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
    fun `starts a session by reading the Property it edits`() = runTest(dispatcher) {
        val repository = RecordingEditPropertyRepository()
        val viewModel = EditPropertyViewModel(repository)

        viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-1")
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("987 Cedar Lane", state.addressLine1)
        assertEquals("Montreal", state.city)
        assertEquals(PropertyProvince.QC, state.province)
        assertEquals(7, state.version)
        assertNull(state.failureReason)
        assertEquals(1, repository.loads)
    }

    @Test
    fun `keeps the form within a session and starts a new read for a new one`() =
        runTest(dispatcher) {
            val repository = RecordingEditPropertyRepository()
            val viewModel = EditPropertyViewModel(repository)
            viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-1")
            advanceUntilIdle()
            viewModel.onStreetAddressChange("1 Main Street")

            // Recomposing the same destination instance is the same session, so the user's edit is
            // kept and the Property is not read again.
            viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-1")
            assertEquals("1 Main Street", viewModel.uiState.value.addressLine1)
            assertEquals(1, repository.loads)

            // A new destination instance starts a new session, so the form is empty while it reads
            // the authoritative Property again.
            viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-2")
            assertTrue(viewModel.uiState.value.isLoading)
            assertEquals("", viewModel.uiState.value.addressLine1)

            advanceUntilIdle()
            assertEquals(2, repository.loads)
            assertEquals("987 Cedar Lane", viewModel.uiState.value.addressLine1)
        }

    @Test
    fun `does not carry a validation message into a new form session`() = runTest(dispatcher) {
        val repository = RecordingEditPropertyRepository()
        val viewModel = EditPropertyViewModel(repository)
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-1")
        advanceUntilIdle()
        viewModel.onStreetAddressChange("")
        viewModel.save()
        assertTrue(viewModel.uiState.value.showsValidationError)

        // Leaving the form and opening it again is a new destination instance, so the previous
        // attempt's message must not appear again.
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-2")

        assertFalse(viewModel.uiState.value.showsValidationError)
        assertFalse(viewModel.uiState.value.saveAttempted)
    }

    @Test
    fun `does not carry a refused save into a new form session`() = runTest(dispatcher) {
        val repository = RecordingEditPropertyRepository(
            updateResult = PropertyResult.Failure(CustomersFailureReason.VERSION_CONFLICT),
        )
        val viewModel = EditPropertyViewModel(repository)
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-1")
        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()
        assertEquals(
            CustomersFailureReason.VERSION_CONFLICT,
            viewModel.uiState.value.failureReason,
        )

        viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-2")

        assertNull(viewModel.uiState.value.failureReason)
        assertFalse(viewModel.uiState.value.saveAttempted)
        assertFalse(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `sends the values it read back with the version the backend reported`() =
        runTest(dispatcher) {
            val repository = RecordingEditPropertyRepository()
            val viewModel = EditPropertyViewModel(repository)
            viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-1")
            advanceUntilIdle()
            viewModel.onStreetAddressChange("  987 Cedar Lane, Unit 4  ")
            viewModel.onNotesChange("Mechanical room in basement B1.")

            viewModel.save()
            advanceUntilIdle()

            assertEquals(1, repository.updates)
            val request = requireNotNull(repository.lastRequest)
            assertEquals("987 Cedar Lane, Unit 4", request.addressLine1)
            assertEquals("Mechanical room in basement B1.", request.notes)
            // A Property that moved on is refused rather than overwritten (`BR-086`).
            assertEquals(7, request.expectedVersion)
            assertNull(viewModel.uiState.value.failureReason)
            assertTrue(viewModel.uiState.value.isSaved)
        }


    @Test
    fun `does not send a form that has not finished reading`() = runTest(dispatcher) {
        val repository = RecordingEditPropertyRepository().apply {
            loadGate = CompletableDeferred()
        }
        val viewModel = EditPropertyViewModel(repository)
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.save()
        advanceUntilIdle()

        // Saving would overwrite values the user never saw.
        assertEquals(0, repository.updates)

        repository.loadGate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `reset releases the Property the form held when the session ends`() = runTest(dispatcher) {
        val repository = RecordingEditPropertyRepository()
        val viewModel = EditPropertyViewModel(repository)
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-1")
        advanceUntilIdle()

        viewModel.reset()

        assertEquals("", viewModel.uiState.value.addressLine1)
        assertNull(viewModel.uiState.value.propertyId)

        // The release also ends the session, so re-opening the same Property reads it afresh.
        viewModel.start(CUSTOMER_ID, PROPERTY_ID, "session-1")
        advanceUntilIdle()
        assertEquals(2, repository.loads)
    }

    private companion object {
        const val CUSTOMER_ID = "c1"
        const val PROPERTY_ID = "p1"
    }
}


/** A [PropertyRepository] that answers with fixed results and records what it was asked. */
private class RecordingEditPropertyRepository(
    var loadResult: PropertyResult = PropertyResult.Success(editProperty()),
    var updateResult: PropertyResult = PropertyResult.Success(editProperty()),
) : PropertyRepository {

    /** When set, the read waits here so a test can keep one load in flight. */
    var loadGate: CompletableDeferred<Unit>? = null

    var loads: Int = 0
    var updates: Int = 0
    var lastRequest: UpdatePropertyRequest? = null

    override suspend fun loadProperty(customerId: String, propertyId: String): PropertyResult {
        loads += 1
        loadGate?.await()
        return loadResult
    }

    override suspend fun updateProperty(
        customerId: String,
        propertyId: String,
        request: UpdatePropertyRequest,
    ): PropertyResult {
        updates += 1
        lastRequest = request
        return updateResult
    }

    override suspend fun archiveProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult = PropertyResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun restoreProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult = PropertyResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun deleteProperty(
        customerId: String,
        propertyId: String,
    ): PropertyDeleteResult = PropertyDeleteResult.Failure(CustomersFailureReason.UNEXPECTED)
}

/** The Property the form reads: the values the backend last reported, at version 7. */
private fun editProperty() = PropertyDetail(
    id = "p1",
    name = "Cedar Lane Building",
    addressLine1 = "987 Cedar Lane",
    addressLine2 = null,
    city = "Montreal",
    province = "QC",
    postalCode = "H3A 2T6",
    country = "Canada",
    notes = null,
    status = PropertyStatus.ACTIVE,
    version = 7,
    archivedAt = null,
    jobCount = 0,
    lastServiceAt = null,
    archiveImpact = PropertyArchiveImpact(activeJobCount = 0, activeVisitCount = 0),
    canBePermanentlyDeleted = false,
)

