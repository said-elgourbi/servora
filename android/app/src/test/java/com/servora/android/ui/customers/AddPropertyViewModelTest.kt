package com.servora.android.ui.customers

import com.servora.android.data.customers.CreatePropertyRequest
import com.servora.android.data.customers.CustomerDetailResult
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.CustomersResult
import com.servora.android.data.customers.PropertyCreateResult
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.PropertyProvince
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
 * What the Add Property form asks [CustomersRepository] for, and what it refuses to ask for.
 *
 * Whether a Property may be created is the backend's decision (`BR-001`, `BR-007`), so these tests
 * assert that a repository answer is reported and that an incomplete form is never sent, rather than
 * that a Property is "correct".
 */
class AddPropertyViewModelTest {

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
    fun `starts empty and writes nothing until asked`() {
        val repository = RecordingRepository()
        val viewModel = AddPropertyViewModel(repository)
        viewModel.start("c1")

        val state = viewModel.uiState.value
        assertEquals("c1", state.customerId)
        assertFalse(state.canSave)
        assertFalse(state.isSaving)
        assertFalse(state.isSaved)
        assertEquals(0, repository.creates)
    }

    @Test
    fun `does not send an incomplete form and marks what is missing`() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = AddPropertyViewModel(repository)
        viewModel.start("c1")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(0, repository.creates)
        assertFalse(viewModel.uiState.value.isSaving)
        assertTrue(viewModel.uiState.value.showsValidationError)
        assertNull(viewModel.uiState.value.failureReason)
    }

    @Test
    fun `sends every field the model has and leaves blank optionals absent`() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = AddPropertyViewModel(repository)
        viewModel.start("c1")
        viewModel.onStreetAddressChange("  987 Cedar Lane ")
        viewModel.onCityChange(" Montreal ")
        viewModel.onProvinceChange(PropertyProvince.QC)
        viewModel.onPostalCodeChange(" H3A 2T6 ")

        viewModel.save()
        advanceUntilIdle()

        val request = requireNotNull(repository.lastRequest)
        assertEquals("c1", repository.lastCustomerId)
        assertEquals("987 Cedar Lane", request.addressLine1)
        assertEquals("Montreal", request.city)
        assertEquals("QC", request.province)
        assertEquals("H3A 2T6", request.postalCode)
        // The optional fields were left empty, so they are absent rather than blank.
        assertNull(request.name)
        assertNull(request.addressLine2)
        assertNull(request.notes)
    }

    @Test
    fun `carries the optional fields when they are filled in`() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = AddPropertyViewModel(repository)
        viewModel.start("c1")
        fillValid(viewModel)
        viewModel.onNameChange("Cedar Lane Building")
        viewModel.onUnitChange("Suite 200")
        viewModel.onNotesChange("Mechanical room in basement B1.")

        viewModel.save()
        advanceUntilIdle()

        val request = requireNotNull(repository.lastRequest)
        assertEquals("Cedar Lane Building", request.name)
        assertEquals("Suite 200", request.addressLine2)
        assertEquals("Mechanical room in basement B1.", request.notes)
    }

    @Test
    fun `reports the created property`() = runTest(dispatcher) {
        val repository = RecordingRepository(
            createResult = PropertyCreateResult.Success(property()),
        )
        val viewModel = AddPropertyViewModel(repository)
        viewModel.start("c1")
        fillValid(viewModel)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.creates)
        assertFalse(viewModel.uiState.value.isSaving)
        assertTrue(viewModel.uiState.value.isSaved)
        assertNull(viewModel.uiState.value.failureReason)
    }

    @Test
    fun `reports a refused create without marking the form saved`() = runTest(dispatcher) {
        val repository = RecordingRepository(
            createResult = PropertyCreateResult.Failure(CustomersFailureReason.VALIDATION),
        )
        val viewModel = AddPropertyViewModel(repository)
        viewModel.start("c1")
        fillValid(viewModel)

        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertFalse(state.isSaving)
        assertEquals(CustomersFailureReason.VALIDATION, state.failureReason)
    }

    @Test
    fun `ignores a second save while the first is still in flight`() = runTest(dispatcher) {
        val repository = RecordingRepository().apply { gate = CompletableDeferred() }
        val viewModel = AddPropertyViewModel(repository)
        viewModel.start("c1")
        fillValid(viewModel)

        viewModel.save()
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.creates)
        assertTrue(viewModel.uiState.value.isSaving)

        repository.gate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `keeps a form already open for the same customer and resets it for another`() {
        val viewModel = AddPropertyViewModel(RecordingRepository())
        viewModel.start("c1")
        viewModel.onStreetAddressChange("1 Main Street")

        viewModel.start("c1")
        assertEquals("1 Main Street", viewModel.uiState.value.addressLine1)

        viewModel.start("c2")
        assertEquals("c2", viewModel.uiState.value.customerId)
        assertEquals("", viewModel.uiState.value.addressLine1)
    }

    @Test
    fun `starts a fresh form for a customer whose property was just created`() = runTest(dispatcher) {
        val repository = RecordingRepository(
            createResult = PropertyCreateResult.Success(property()),
        )
        val viewModel = AddPropertyViewModel(repository)
        viewModel.start("c1")
        fillValid(viewModel)
        viewModel.save()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSaved)

        viewModel.start("c1")

        assertFalse(viewModel.uiState.value.isSaved)
        assertEquals("", viewModel.uiState.value.addressLine1)
    }

    private fun fillValid(viewModel: AddPropertyViewModel) {
        viewModel.onStreetAddressChange("987 Cedar Lane")
        viewModel.onCityChange("Montreal")
        viewModel.onProvinceChange(PropertyProvince.QC)
        viewModel.onPostalCodeChange("H3A 2T6")
    }

    private fun property() = CustomerProperty(
        id = "p1",
        name = "Cedar Lane Building",
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
        jobCount = 0,
        lastServiceAt = null,
    )
}

/** A [CustomersRepository] that answers with a scripted create and counts the writes. */
private class RecordingRepository(
    var createResult: PropertyCreateResult = PropertyCreateResult.Success(
        CustomerProperty(
            id = "p1",
            name = null,
            addressLine1 = "987 Cedar Lane",
            addressLine2 = null,
            city = "Montreal",
            province = "QC",
            postalCode = "H3A 2T6",
            country = "Canada",
            jobCount = 0,
            lastServiceAt = null,
        ),
    ),
) : CustomersRepository {

    /** When set, the write waits here so a test can keep one save in flight. */
    var gate: CompletableDeferred<Unit>? = null

    var creates: Int = 0
    var lastCustomerId: String? = null
    var lastRequest: CreatePropertyRequest? = null

    override suspend fun listCustomers(filters: CustomerFilters): CustomersResult =
        CustomersResult.Success(emptyList())

    override suspend fun loadCustomerDetail(customerId: String): CustomerDetailResult =
        CustomerDetailResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun createProperty(
        customerId: String,
        request: CreatePropertyRequest,
    ): PropertyCreateResult {
        creates += 1
        lastCustomerId = customerId
        lastRequest = request
        gate?.await()
        return createResult
    }
}
