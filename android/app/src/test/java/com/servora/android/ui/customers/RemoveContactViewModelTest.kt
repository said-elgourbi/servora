package com.servora.android.ui.customers

import com.servora.android.data.customers.ContactCreateResult
import com.servora.android.data.customers.ContactRemoveResult
import com.servora.android.data.customers.ContactUpdateResult
import com.servora.android.data.customers.CreateCustomerContactRequest
import com.servora.android.data.customers.CreateCustomerRequest
import com.servora.android.data.customers.CreatePropertyRequest
import com.servora.android.data.customers.CustomerCreateResult
import com.servora.android.data.customers.CustomerDetailResult
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.CustomersResult
import com.servora.android.data.customers.CustomerUpdateResult
import com.servora.android.data.customers.PropertyCreateResult
import com.servora.android.data.customers.RemoveCustomerContactRequest
import com.servora.android.data.customers.UpdateCustomerContactRequest
import com.servora.android.data.customers.UpdateCustomerRequest
import com.servora.android.domain.model.CustomerContact
import com.servora.android.domain.model.CustomerFilters
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
 * What removing a contact person asks [CustomersRepository] for, and what it refuses to ask for.
 *
 * Whether a removal is allowed is the backend's decision (`BR-001`, `BR-007`), so these tests assert
 * that its answer is reported, that the version the row was read with travels with the removal
 * (`BR-095`, `ADR-022` D9), and that one tap cannot become two operations.
 */
class RemoveContactViewModelTest {

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
    fun `removes nothing until it is asked`() {
        val repository = RecordingContactRemoveRepository()
        val viewModel = RemoveContactViewModel(repository)
        viewModel.start("c1", "session-1")

        val state = viewModel.uiState.value
        assertEquals("c1", state.customerId)
        assertNull(state.contactId)
        assertFalse(state.isRemoving)
        assertFalse(state.isRemoved)
        assertEquals(0, repository.removals)
    }

    @Test
    fun `states the version the row was read with`() = runTest(dispatcher) {
        val repository = RecordingContactRemoveRepository()
        val viewModel = RemoveContactViewModel(repository)
        viewModel.start("c1", "session-1")

        viewModel.remove(contact(version = 4))
        advanceUntilIdle()

        assertEquals(1, repository.removals)
        assertEquals("c1", repository.lastCustomerId)
        assertEquals("contact-1", repository.lastContactId)
        // The version is what makes a contact another member changed refuse the removal rather than
        // losing its newer state (`BR-032`, `BR-086`).
        assertEquals(4, repository.lastRequest?.expectedVersion)
    }

    @Test
    fun `reports the applied removal`() = runTest(dispatcher) {
        val repository = RecordingContactRemoveRepository()
        val viewModel = RemoveContactViewModel(repository)
        viewModel.start("c1", "session-1")

        viewModel.remove(contact())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isRemoving)
        assertTrue(state.isRemoved)
        assertNull(state.failureReason)
    }

    @Test
    fun `reports a refused removal without claiming it happened`() = runTest(dispatcher) {
        val repository = RecordingContactRemoveRepository(
            removeResult = ContactRemoveResult.Failure(CustomersFailureReason.VERSION_CONFLICT),
        )
        val viewModel = RemoveContactViewModel(repository)
        viewModel.start("c1", "session-1")

        viewModel.remove(contact())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isRemoving)
        assertFalse(state.isRemoved)
        assertEquals(CustomersFailureReason.VERSION_CONFLICT, state.failureReason)
    }

    @Test
    fun `ignores a second removal while the first is still in flight`() = runTest(dispatcher) {
        val repository = RecordingContactRemoveRepository().apply { gate = CompletableDeferred() }
        val viewModel = RemoveContactViewModel(repository)
        viewModel.start("c1", "session-1")

        viewModel.remove(contact())
        viewModel.remove(contact())
        advanceUntilIdle()

        assertEquals(1, repository.removals)
        assertTrue(viewModel.uiState.value.isRemoving)

        repository.gate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isRemoved)
    }

    @Test
    fun `starts a new destination session without the previous outcome`() = runTest(dispatcher) {
        val repository = RecordingContactRemoveRepository(
            removeResult = ContactRemoveResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = RemoveContactViewModel(repository)
        viewModel.start("c1", "session-1")
        viewModel.remove(contact())
        advanceUntilIdle()

        // Leaving the customer and coming back is a new destination instance, so a refusal the user
        // walked away from cannot be shown again (`BR-042`).
        viewModel.start("c1", "session-2")

        assertNull(viewModel.uiState.value.failureReason)
        assertFalse(viewModel.uiState.value.isRemoved)
    }

    @Test
    fun `clears the refusal once the screen has shown it`() = runTest(dispatcher) {
        val repository = RecordingContactRemoveRepository(
            removeResult = ContactRemoveResult.Failure(CustomersFailureReason.SERVER),
        )
        val viewModel = RemoveContactViewModel(repository)
        viewModel.start("c1", "session-1")
        viewModel.remove(contact())
        advanceUntilIdle()

        viewModel.dismissFailure()

        assertNull(viewModel.uiState.value.failureReason)
    }

    @Test
    fun `reports the removal once and acknowledges it`() = runTest(dispatcher) {
        val repository = RecordingContactRemoveRepository()
        val viewModel = RemoveContactViewModel(repository)
        viewModel.start("c1", "session-1")
        viewModel.remove(contact())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isRemoved)

        viewModel.acknowledgeRemoved()

        assertFalse(viewModel.uiState.value.isRemoved)
    }

    @Test
    fun `releases the removal when the session ends`() = runTest(dispatcher) {
        val viewModel = RemoveContactViewModel(RecordingContactRemoveRepository())
        viewModel.start("c1", "session-1")
        viewModel.remove(contact())
        advanceUntilIdle()

        viewModel.reset()

        assertEquals("", viewModel.uiState.value.customerId)
        assertNull(viewModel.uiState.value.contactId)
        assertFalse(viewModel.uiState.value.isRemoved)
    }

    private fun contact(version: Int = 1) = CustomerContact(
        id = "contact-1",
        customerId = "c1",
        firstName = "John",
        lastName = "Smith",
        email = null,
        phone = null,
        role = null,
        isPrimary = true,
        isBillingContact = false,
        isJobContact = false,
        version = version,
        createdAt = "2025-01-12T10:30:00Z",
        updatedAt = "2025-01-12T10:30:00Z",
    )
}

/**
 * A [CustomersRepository] that answers with a scripted removal and counts the writes.
 *
 * The reads and the other writes are not what these tests script, so they answer the outcome an
 * unrepresentable reply would (`BR-042`).
 */
private class RecordingContactRemoveRepository(
    var removeResult: ContactRemoveResult = ContactRemoveResult.Success,
) : CustomersRepository {

    /** When set, the removal waits here so a test can keep one in flight. */
    var gate: CompletableDeferred<Unit>? = null

    var removals: Int = 0
    var lastCustomerId: String? = null
    var lastContactId: String? = null
    var lastRequest: RemoveCustomerContactRequest? = null

    override suspend fun listCustomers(filters: CustomerFilters): CustomersResult =
        CustomersResult.Success(emptyList())

    override suspend fun loadCustomerDetail(customerId: String): CustomerDetailResult =
        CustomerDetailResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun createProperty(
        customerId: String,
        request: CreatePropertyRequest,
    ): PropertyCreateResult = PropertyCreateResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun createCustomer(request: CreateCustomerRequest): CustomerCreateResult =
        CustomerCreateResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun updateCustomer(
        customerId: String,
        request: UpdateCustomerRequest,
    ): CustomerUpdateResult = CustomerUpdateResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun createContact(
        customerId: String,
        request: CreateCustomerContactRequest,
    ): ContactCreateResult = ContactCreateResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun updateContact(
        customerId: String,
        contactId: String,
        request: UpdateCustomerContactRequest,
    ): ContactUpdateResult = ContactUpdateResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun removeContact(
        customerId: String,
        contactId: String,
        request: RemoveCustomerContactRequest,
    ): ContactRemoveResult {
        removals += 1
        lastCustomerId = customerId
        lastContactId = contactId
        lastRequest = request
        gate?.await()
        return removeResult
    }
}