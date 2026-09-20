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
 * What the Add Contact form asks [CustomersRepository] for, and what it refuses to ask for.
 *
 * Whether a contact may be recorded is the backend's decision (`BR-001`, `BR-007`, `BR-095`), so these
 * tests assert that a repository answer is reported and that an incomplete form is never sent, rather
 * than that a contact is "correct".
 */
class AddContactViewModelTest {

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
        val repository = RecordingContactCreateRepository()
        val viewModel = AddContactViewModel(repository)
        viewModel.start("c1", "session-1")

        val state = viewModel.uiState.value
        assertEquals("c1", state.customerId)
        assertFalse(state.canSave)
        assertFalse(state.isSaving)
        assertFalse(state.isSaved)
        assertFalse(state.isPrimary)
        assertEquals(0, repository.creates)
    }

    @Test
    fun `does not send an incomplete form and marks what is missing`() = runTest(dispatcher) {
        val repository = RecordingContactCreateRepository()
        val viewModel = AddContactViewModel(repository)
        viewModel.start("c1", "session-1")
        viewModel.onFirstNameChange("John")

        viewModel.save()
        advanceUntilIdle()

        // A contact needs both names (`BR-095`), so a first name alone is not submitted.
        assertEquals(0, repository.creates)
        assertFalse(viewModel.uiState.value.isSaving)
        assertTrue(viewModel.uiState.value.showsValidationError)
        assertNull(viewModel.uiState.value.failureReason)
    }

    @Test
    fun `does not carry a validation message into a new form session`() {
        val viewModel = AddContactViewModel(RecordingContactCreateRepository())
        viewModel.start("c1", "session-1")
        viewModel.save()
        assertTrue(viewModel.uiState.value.showsValidationError)

        // Leaving the form and opening it again is a new destination instance, so the previous
        // attempt's message must not appear again.
        viewModel.start("c1", "session-2")

        assertFalse(viewModel.uiState.value.showsValidationError)
    }

    @Test
    fun `does not carry a refused save into a new form session`() = runTest(dispatcher) {
        val repository = RecordingContactCreateRepository(
            createResult = ContactCreateResult.Failure(CustomersFailureReason.VALIDATION),
        )
        val viewModel = AddContactViewModel(repository)
        viewModel.start("c1", "session-1")
        fillValid(viewModel)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(CustomersFailureReason.VALIDATION, viewModel.uiState.value.failureReason)

        viewModel.start("c1", "session-2")

        assertNull(viewModel.uiState.value.failureReason)
        assertFalse(viewModel.uiState.value.showsValidationError)
        assertEquals("", viewModel.uiState.value.firstName)
    }

    @Test
    fun `sends the contact's own fields and the primary flag`() = runTest(dispatcher) {
        val repository = RecordingContactCreateRepository()
        val viewModel = AddContactViewModel(repository)
        viewModel.start("c1", "session-1")
        fillValid(viewModel)
        viewModel.onPhoneChange(" 555-123-4567 ")
        viewModel.onEmailChange(" john@example.com ")
        viewModel.onPrimaryChange(true)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.creates)
        assertEquals("c1", repository.lastCustomerId)
        val request = repository.lastRequest
        assertEquals("John", request?.firstName)
        assertEquals("Smith", request?.lastName)
        assertEquals("555-123-4567", request?.phone)
        assertEquals("john@example.com", request?.email)
        assertEquals(true, request?.isPrimary)
    }

    @Test
    fun `leaves an optional field the form never filled out of the request`() = runTest(dispatcher) {
        val repository = RecordingContactCreateRepository()
        val viewModel = AddContactViewModel(repository)
        viewModel.start("c1", "session-1")
        fillValid(viewModel)

        viewModel.save()
        advanceUntilIdle()

        // A blank optional member keeps its default, so the request does not carry it and the API
        // stores the `null` it defaults to (`BR-095`, `ADR-022` D9).
        val request = repository.lastRequest
        assertNull(request?.phone)
        assertNull(request?.email)
        assertEquals(false, request?.isPrimary)
    }

    @Test
    fun `reports the recorded contact`() = runTest(dispatcher) {
        val repository = RecordingContactCreateRepository()
        val viewModel = AddContactViewModel(repository)
        viewModel.start("c1", "session-1")
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
        val repository = RecordingContactCreateRepository(
            createResult = ContactCreateResult.Failure(CustomersFailureReason.FORBIDDEN),
        )
        val viewModel = AddContactViewModel(repository)
        viewModel.start("c1", "session-1")
        fillValid(viewModel)

        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertFalse(state.isSaving)
        assertEquals(CustomersFailureReason.FORBIDDEN, state.failureReason)
    }

    @Test
    fun `ignores a second save while the first is still in flight`() = runTest(dispatcher) {
        val repository = RecordingContactCreateRepository().apply { gate = CompletableDeferred() }
        val viewModel = AddContactViewModel(repository)
        viewModel.start("c1", "session-1")
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
    fun `keeps an in-progress form within a session and starts empty for a new one`() {
        val viewModel = AddContactViewModel(RecordingContactCreateRepository())
        viewModel.start("c1", "session-1")
        viewModel.onFirstNameChange("John")

        // Recomposing the same destination instance is the same session, so the form is kept.
        viewModel.start("c1", "session-1")
        assertEquals("John", viewModel.uiState.value.firstName)

        // A new destination instance is a new session, even for the same customer.
        viewModel.start("c1", "session-2")
        assertEquals("c1", viewModel.uiState.value.customerId)
        assertEquals("", viewModel.uiState.value.firstName)

        // A session opened for another customer is scoped to that customer.
        viewModel.start("c2", "session-3")
        assertEquals("c2", viewModel.uiState.value.customerId)
        assertEquals("", viewModel.uiState.value.firstName)
    }

    @Test
    fun `starts a fresh form after a save when the destination is opened again`() =
        runTest(dispatcher) {
            val repository = RecordingContactCreateRepository()
            val viewModel = AddContactViewModel(repository)
            viewModel.start("c1", "session-1")
            fillValid(viewModel)
            viewModel.save()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSaved)

            // Opening Add Contact again is a new destination instance, so the form starts empty
            // instead of immediately re-reporting the previous save.
            viewModel.start("c1", "session-2")

            assertFalse(viewModel.uiState.value.isSaved)
            assertEquals("", viewModel.uiState.value.firstName)
        }

    /** Releases the form, as signing out does, so nothing of it outlives the session. */
    @Test
    fun `releases the form when the session ends`() {
        val viewModel = AddContactViewModel(RecordingContactCreateRepository())
        viewModel.start("c1", "session-1")
        viewModel.onFirstNameChange("John")

        viewModel.reset()

        assertEquals("", viewModel.uiState.value.customerId)
        assertEquals("", viewModel.uiState.value.firstName)
    }

    private fun fillValid(viewModel: AddContactViewModel) {
        viewModel.onFirstNameChange("John")
        viewModel.onLastNameChange("Smith")
    }
}

/**
 * A [CustomersRepository] that answers with a scripted create and counts the writes.
 *
 * The reads and the other writes are not what these tests script, so they answer the outcome an
 * unrepresentable reply would (`BR-042`).
 */
private class RecordingContactCreateRepository(
    var createResult: ContactCreateResult = ContactCreateResult.Success,
) : CustomersRepository {

    /** When set, the write waits here so a test can keep one save in flight. */
    var gate: CompletableDeferred<Unit>? = null

    var creates: Int = 0
    var lastCustomerId: String? = null
    var lastRequest: CreateCustomerContactRequest? = null

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
    ): ContactCreateResult {
        creates += 1
        lastCustomerId = customerId
        lastRequest = request
        gate?.await()
        return createResult
    }

    override suspend fun updateContact(
        customerId: String,
        contactId: String,
        request: UpdateCustomerContactRequest,
    ): ContactUpdateResult = ContactUpdateResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun removeContact(
        customerId: String,
        contactId: String,
        request: RemoveCustomerContactRequest,
    ): ContactRemoveResult = ContactRemoveResult.Failure(CustomersFailureReason.UNEXPECTED)
}
