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
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerContact
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType
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
 * What the Edit Contact form reads, sends and refuses to send.
 *
 * Whether an edit is allowed is the backend's decision (`BR-001`, `BR-007`, `BR-095`), so these tests
 * assert that its answer is reported, that the version the form read travels with the edit, and that a
 * form session never inherits the previous session's values or messages (`BR-032`, `BR-086`).
 */
class EditContactViewModelTest {

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
    fun `starts a session by reading the contact it edits`() = runTest(dispatcher) {
        val repository = RecordingContactEditRepository()
        val viewModel = EditContactViewModel(repository)

        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("John", state.firstName)
        assertEquals("Smith", state.lastName)
        assertEquals("555-123-4567", state.phone)
        assertEquals("john@example.com", state.email)
        assertTrue(state.isPrimary)
        assertTrue(state.storedIsPrimary)
        assertEquals(7, state.version)
        assertNull(state.failureReason)
        assertEquals(1, repository.detailReads)
        assertEquals(CUSTOMER_ID, repository.lastDetailCustomerId)
    }

    @Test
    fun `reports a contact the projection does not carry as not found`() = runTest(dispatcher) {
        // A removed contact leaves every ordinary view (`BR-095`, `ADR-022` D8), so the read cannot
        // present it and the form says so rather than offering an edit that would be refused.
        val repository = RecordingContactEditRepository(
            detailResult = CustomerDetailResult.Success(detail(contacts = emptyList())),
        )
        val viewModel = EditContactViewModel(repository)

        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(CustomersFailureReason.NOT_FOUND, state.failureReason)
        assertNull(state.version)
    }

    @Test
    fun `reports a failed read`() = runTest(dispatcher) {
        val repository = RecordingContactEditRepository(
            detailResult = CustomerDetailResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = EditContactViewModel(repository)

        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        advanceUntilIdle()

        assertEquals(CustomersFailureReason.NETWORK, viewModel.uiState.value.failureReason)
        assertNull(viewModel.uiState.value.version)
    }

    @Test
    fun `states the fields it read and the version it read them at`() = runTest(dispatcher) {
        val repository = RecordingContactEditRepository()
        val viewModel = EditContactViewModel(repository)
        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        advanceUntilIdle()

        viewModel.onFirstNameChange(" Jane ")
        viewModel.onLastNameChange(" Doe ")
        viewModel.onPhoneChange(" 555-999-0000 ")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.updates)
        assertEquals(CONTACT_ID, repository.lastContactId)
        val request = repository.lastRequest
        assertEquals("Jane", request?.firstName)
        assertEquals("Doe", request?.lastName)
        assertEquals("555-999-0000", request?.phone)
        // The email the user left as it was read is stated unchanged.
        assertEquals("john@example.com", request?.email)
        // The contact is already primary, so the edit states no promotion.
        assertNull(request?.isPrimary)
        // The version travels with the edit, so a contact that moved on is refused (`BR-032`).
        assertEquals(7, request?.expectedVersion)
    }

    @Test
    fun `cannot express clearing an optional field, so it leaves the stored value alone`() =
        runTest(dispatcher) {
            val repository = RecordingContactEditRepository()
            val viewModel = EditContactViewModel(repository)
            viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
            advanceUntilIdle()

            viewModel.onEmailChange("")
            viewModel.save()
            advanceUntilIdle()

            // A blank optional member keeps its default and is therefore absent from the request, so
            // the API leaves the value as the contact holds it. The form can state a value and it can
            // leave one alone; clearing one is the DTO-shaping limitation tracker 047 records
            // (`BR-042`, `ADR-022` D9).
            assertEquals(1, repository.updates)
            assertNull(repository.lastRequest?.email)
        }

    @Test
    fun `states a promotion only when the contact is not already primary`() = runTest(dispatcher) {
        val repository = RecordingContactEditRepository(
            detailResult = CustomerDetailResult.Success(
                detail(contacts = listOf(contact(isPrimary = false))),
            ),
        )
        val viewModel = EditContactViewModel(repository)
        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.storedIsPrimary)

        viewModel.onPrimaryChange(true)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(true, repository.lastRequest?.isPrimary)
    }

    @Test
    fun `never states an un-promotion for a contact that already holds the flag`() =
        runTest(dispatcher) {
            val repository = RecordingContactEditRepository()
            val viewModel = EditContactViewModel(repository)
            viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.canStatePrimary)

            // The API documents `isPrimary: true` and defines no un-promotion, so the form does not
            // state one (`BR-042`, `ADR-022` D9).
            viewModel.onPrimaryChange(false)
            viewModel.save()
            advanceUntilIdle()

            assertNull(repository.lastRequest?.isPrimary)
            assertEquals(1, repository.updates)
        }

    @Test
    fun `does not send an incomplete form`() = runTest(dispatcher) {
        val repository = RecordingContactEditRepository()
        val viewModel = EditContactViewModel(repository)
        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        advanceUntilIdle()

        viewModel.onLastNameChange("")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(0, repository.updates)
        assertTrue(viewModel.uiState.value.showsValidationError)
        assertNull(viewModel.uiState.value.failureReason)
    }

    @Test
    fun `does not send an edit before the contact has been read`() = runTest(dispatcher) {
        val repository = RecordingContactEditRepository()
        val viewModel = EditContactViewModel(repository)
        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        assertTrue(viewModel.uiState.value.isLoading)

        // Submitting a form whose fields are still empty would write over values the user never saw.
        viewModel.save()

        assertEquals(0, repository.updates)
        assertFalse(viewModel.uiState.value.showsValidationError)
    }

    @Test
    fun `reports the applied edit`() = runTest(dispatcher) {
        val repository = RecordingContactEditRepository()
        val viewModel = EditContactViewModel(repository)
        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSaved)
        assertFalse(viewModel.uiState.value.isSaving)
        assertNull(viewModel.uiState.value.failureReason)
    }

    @Test
    fun `reports a refused edit and keeps the form as the user read it`() = runTest(dispatcher) {
        val repository = RecordingContactEditRepository(
            updateResult = ContactUpdateResult.Failure(CustomersFailureReason.VERSION_CONFLICT),
        )
        val viewModel = EditContactViewModel(repository)
        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertFalse(state.isSaving)
        assertEquals(CustomersFailureReason.VERSION_CONFLICT, state.failureReason)
        // Nothing was applied, so the values shown are still the ones the user read (`BR-001`).
        assertEquals("John", state.firstName)
    }

    @Test
    fun `keeps the form within a session and reads the contact again for a new one`() =
        runTest(dispatcher) {
            val repository = RecordingContactEditRepository()
            val viewModel = EditContactViewModel(repository)
            viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
            advanceUntilIdle()
            viewModel.onFirstNameChange("Jane")

            // Recomposing the same destination instance is the same session, so the form is kept.
            viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
            assertEquals("Jane", viewModel.uiState.value.firstName)
            assertEquals(1, repository.detailReads)

            // A new destination instance is a new session: it reads again and keeps nothing.
            viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-2")
            advanceUntilIdle()
            assertEquals(2, repository.detailReads)
            assertEquals("John", viewModel.uiState.value.firstName)
        }

    /** Releases the contact the form held, as signing out does (`BR-001`). */
    @Test
    fun `releases the contact when the session ends`() = runTest(dispatcher) {
        val viewModel = EditContactViewModel(RecordingContactEditRepository())
        viewModel.start(CUSTOMER_ID, CONTACT_ID, "session-1")
        advanceUntilIdle()

        viewModel.reset()

        assertEquals("", viewModel.uiState.value.customerId)
        assertNull(viewModel.uiState.value.contactId)
        assertNull(viewModel.uiState.value.version)
        assertEquals("", viewModel.uiState.value.firstName)
    }

    private fun detail(contacts: List<CustomerContact> = listOf(contact())) = CustomerDetail(
        customer = Customer(
            id = CUSTOMER_ID,
            organizationId = "org-1",
            type = CustomerType.COMPANY,
            displayName = "ABC Property Management",
            email = null,
            phone = null,
            billingEmail = null,
            billingPhone = null,
            notes = null,
            status = CustomerStatus.ACTIVE,
            createdAt = "2025-01-12T10:30:00Z",
            updatedAt = "2025-01-12T10:30:00Z",
            propertyCount = 0,
            jobCount = 0,
        ),
        contacts = contacts,
        properties = emptyList(),
        jobs = emptyList(),
    )

    private fun contact(isPrimary: Boolean = true) = CustomerContact(
        id = CONTACT_ID,
        customerId = CUSTOMER_ID,
        firstName = "John",
        lastName = "Smith",
        email = "john@example.com",
        phone = "555-123-4567",
        role = null,
        isPrimary = isPrimary,
        isBillingContact = false,
        isJobContact = false,
        version = 7,
        createdAt = "2025-01-12T10:30:00Z",
        updatedAt = "2025-01-12T10:30:00Z",
    )

    private companion object {
        const val CUSTOMER_ID = "customer-1"
        const val CONTACT_ID = "contact-1"
    }
}

/**
 * A [CustomersRepository] that answers with a scripted customer detail and counts the writes.
 *
 * The contact edit reads through the customer detail projection, because the API exposes no single
 * contact route (`docs/api/customers.md` §5.2), so a scripted detail is how a test decides what the
 * form sees.
 */
private class RecordingContactEditRepository(
    var detailResult: CustomerDetailResult = CustomerDetailResult.Success(
        CustomerDetail(
            customer = Customer(
                id = "customer-1",
                organizationId = "org-1",
                type = CustomerType.COMPANY,
                displayName = "ABC Property Management",
                email = null,
                phone = null,
                billingEmail = null,
                billingPhone = null,
                notes = null,
                status = CustomerStatus.ACTIVE,
                createdAt = "2025-01-12T10:30:00Z",
                updatedAt = "2025-01-12T10:30:00Z",
                propertyCount = 0,
                jobCount = 0,
            ),
            contacts = listOf(
                CustomerContact(
                    id = "contact-1",
                    customerId = "customer-1",
                    firstName = "John",
                    lastName = "Smith",
                    email = "john@example.com",
                    phone = "555-123-4567",
                    role = null,
                    isPrimary = true,
                    isBillingContact = false,
                    isJobContact = false,
                    version = 7,
                    createdAt = "2025-01-12T10:30:00Z",
                    updatedAt = "2025-01-12T10:30:00Z",
                ),
            ),
            properties = emptyList(),
            jobs = emptyList(),
        ),
    ),
    var updateResult: ContactUpdateResult = ContactUpdateResult.Success,
) : CustomersRepository {

    var detailReads: Int = 0
    var updates: Int = 0
    var lastDetailCustomerId: String? = null
    var lastContactId: String? = null
    var lastRequest: UpdateCustomerContactRequest? = null

    override suspend fun listCustomers(filters: CustomerFilters): CustomersResult =
        CustomersResult.Success(emptyList())

    override suspend fun loadCustomerDetail(customerId: String): CustomerDetailResult {
        detailReads += 1
        lastDetailCustomerId = customerId
        return detailResult
    }

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
    ): ContactUpdateResult {
        updates += 1
        lastDetailCustomerId = customerId
        lastContactId = contactId
        lastRequest = request
        return updateResult
    }

    override suspend fun removeContact(
        customerId: String,
        contactId: String,
        request: RemoveCustomerContactRequest,
    ): ContactRemoveResult = ContactRemoveResult.Failure(CustomersFailureReason.UNEXPECTED)
}
