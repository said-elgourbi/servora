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
import com.servora.android.domain.model.CustomerCompany
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerIndividual
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
 * What the Edit Customer form asks [CustomersRepository] for, and what it refuses to ask for.
 *
 * Whether a customer may be edited, and whether the stated type may replace the customer's subtype
 * record, is the backend's decision (`BR-001`, `BR-007`, `BR-087`), so these tests assert that the
 * form states the edit the user made and that an incomplete or unread form is never sent, rather
 * than that a record is "correct".
 */
class EditCustomerViewModelTest {

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
    fun `starts empty and reads the customer it edits`() = runTest(dispatcher) {
        val repository = RecordingEditCustomerRepository()
        val viewModel = EditCustomerViewModel(repository)

        viewModel.start(CUSTOMER_ID, "session-a")

        // Nothing is editable before the customer has been read.
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isLoaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isLoaded)
        assertFalse(state.isLoading)
        assertEquals(1, repository.detailReads)
        assertEquals(CUSTOMER_ID, repository.lastDetailCustomerId)
        assertEquals(CustomerType.COMPANY, state.type)
        assertEquals(CustomerType.COMPANY, state.storedType)
        assertEquals("ABC Property Management Ltd.", state.companyName)
        assertEquals("+15551234567", state.phone)
        assertEquals("hello@abc.example", state.email)
        assertEquals(CustomerStatus.ACTIVE, state.status)
        assertFalse(state.isConversion)
    }

    @Test
    fun `fills the individual names from the individual subtype`() = runTest(dispatcher) {
        val repository = RecordingEditCustomerRepository(
            detailResult = CustomerDetailResult.Success(individualDetail()),
        )
        val viewModel = EditCustomerViewModel(repository)

        viewModel.start(CUSTOMER_ID, "session-a")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CustomerType.INDIVIDUAL, state.type)
        assertEquals("Jordan", state.firstName)
        assertEquals("Lee", state.lastName)
        assertEquals("", state.companyName)
    }

    @Test
    fun `reports a failed read and leaves the form unopened`() = runTest(dispatcher) {
        val repository = RecordingEditCustomerRepository(
            detailResult = CustomerDetailResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = EditCustomerViewModel(repository)

        viewModel.start(CUSTOMER_ID, "session-a")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoaded)
        assertFalse(state.isLoading)
        assertEquals(CustomersFailureReason.NETWORK, state.failureReason)
        assertFalse(state.canSave)
    }

    @Test
    fun `retries the read without starting a new session`() = runTest(dispatcher) {
        val repository = RecordingEditCustomerRepository(
            detailResult = CustomerDetailResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = EditCustomerViewModel(repository)
        viewModel.start(CUSTOMER_ID, "session-a")
        advanceUntilIdle()

        repository.detailResult = CustomerDetailResult.Success(detail())
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isLoaded)
        assertEquals(2, repository.detailReads)
        // The session was kept, so the form holds the values it read rather than restarting.
        assertEquals("ABC Property Management Ltd.", viewModel.uiState.value.companyName)
    }

    @Test
    fun `does not save before the customer has been read`() = runTest(dispatcher) {
        val repository = RecordingEditCustomerRepository()
        val viewModel = EditCustomerViewModel(repository)
        viewModel.start(CUSTOMER_ID, "session-a")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(0, repository.updates)
    }

    @Test
    fun `does not send a conversion without the new type's required fields`() =
        runTest(dispatcher) {
            val repository = RecordingEditCustomerRepository()
            val viewModel = EditCustomerViewModel(repository)
            viewModel.start(CUSTOMER_ID, "session-a")
            advanceUntilIdle()

            // The company name is what a company customer requires (`BR-087`).
            viewModel.onCompanyNameChange("  ")
            viewModel.save()
            advanceUntilIdle()

            assertEquals(0, repository.updates)
            assertTrue(viewModel.uiState.value.showsValidationError)
        }

    @Test
    fun `sends the stated type and only that type's subtype when converting`() =
        runTest(dispatcher) {
            val repository = RecordingEditCustomerRepository()
            val viewModel = EditCustomerViewModel(repository)
            viewModel.start(CUSTOMER_ID, "session-a")
            advanceUntilIdle()

            viewModel.onTypeChange(CustomerType.INDIVIDUAL)
            viewModel.onFirstNameChange("Jordan")
            viewModel.onLastNameChange("Lee")
            viewModel.save()
            advanceUntilIdle()

            val request = repository.lastUpdateRequest
            assertEquals(1, repository.updates)
            assertEquals(CUSTOMER_ID, repository.lastUpdateCustomerId)
            assertEquals(CustomerType.INDIVIDUAL.name, request?.type)
            assertEquals("Jordan Lee", request?.displayName)
            assertEquals("Jordan", request?.individual?.firstName)
            assertEquals("Lee", request?.individual?.lastName)
            // Nothing is carried over from the type the customer is leaving (`BR-087`).
            assertNull(request?.company)
            assertTrue(viewModel.uiState.value.isSaved)
        }

    @Test
    fun `sends the customer's status with the edit`() = runTest(dispatcher) {
        val repository = RecordingEditCustomerRepository()
        val viewModel = EditCustomerViewModel(repository)
        viewModel.start(CUSTOMER_ID, "session-a")
        advanceUntilIdle()

        viewModel.onStatusChange(CustomerStatus.INACTIVE)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(CustomerStatus.INACTIVE.name, repository.lastUpdateRequest?.status)
    }

    @Test
    fun `reports a rejected edit and stays open`() = runTest(dispatcher) {
        val repository = RecordingEditCustomerRepository(
            updateResult = CustomerUpdateResult.Failure(CustomersFailureReason.VALIDATION),
        )
        val viewModel = EditCustomerViewModel(repository)
        viewModel.start(CUSTOMER_ID, "session-a")
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CustomersFailureReason.VALIDATION, state.failureReason)
        assertFalse(state.isSaved)
        assertFalse(state.isSaving)
    }

    @Test
    fun `ignores an answer that arrives after the form session ended`() = runTest(dispatcher) {
        val repository = RecordingEditCustomerRepository()
        val viewModel = EditCustomerViewModel(repository)
        viewModel.start(CUSTOMER_ID, "session-a")

        viewModel.reset()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoaded)
        assertFalse(state.isSaved)
    }

    @Test
    fun `releases the customer when the session ends`() = runTest(dispatcher) {
        val repository = RecordingEditCustomerRepository()
        val viewModel = EditCustomerViewModel(repository)
        viewModel.start(CUSTOMER_ID, "session-a")
        advanceUntilIdle()

        viewModel.reset()

        val state = viewModel.uiState.value
        assertEquals("", state.customerId)
        assertFalse(state.isLoaded)
        assertEquals("", state.companyName)
    }

    private companion object {
        const val CUSTOMER_ID = "customer-1"
    }
}



/** A [CustomersRepository] that answers with a scripted read result and records the edit. */
private class RecordingEditCustomerRepository(
    var detailResult: CustomerDetailResult = CustomerDetailResult.Success(detail()),
    var updateResult: CustomerUpdateResult = CustomerUpdateResult.Success,
) : CustomersRepository {

    var detailReads: Int = 0
    var updates: Int = 0
    var lastDetailCustomerId: String? = null
    var lastUpdateCustomerId: String? = null
    var lastUpdateRequest: UpdateCustomerRequest? = null

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

    override suspend fun createContact(
        customerId: String,
        request: CreateCustomerContactRequest,
    ): ContactCreateResult = ContactCreateResult.Failure(CustomersFailureReason.UNEXPECTED)

    // The contact edit and removal are not what this fake scripts; they answer the same outcome an
    // unrepresentable reply would.
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

    override suspend fun updateCustomer(
        customerId: String,
        request: UpdateCustomerRequest,
    ): CustomerUpdateResult {
        updates += 1
        lastUpdateCustomerId = customerId
        lastUpdateRequest = request
        return updateResult
    }
}

/** A company customer's detail read, as the backend answers it. */
private fun detail() = CustomerDetail(
    customer = customer(type = CustomerType.COMPANY, displayName = "ABC Property Management"),
    company = CustomerCompany(
        customerId = "customer-1",
        legalName = "ABC Property Management Ltd.",
        businessName = null,
        taxNumber = null,
    ),
    contacts = emptyList(),
    properties = emptyList(),
    jobs = emptyList(),
)

/** An individual customer's detail read, as the backend answers it. */
private fun individualDetail() = CustomerDetail(
    customer = customer(type = CustomerType.INDIVIDUAL, displayName = "Jordan Lee"),
    individual = CustomerIndividual(
        customerId = "customer-1",
        firstName = "Jordan",
        lastName = "Lee",
        dateOfBirth = null,
    ),
    contacts = emptyList(),
    properties = emptyList(),
    jobs = emptyList(),
)

private fun customer(type: CustomerType, displayName: String) = Customer(
    id = "customer-1",
    organizationId = "org-1",
    type = type,
    displayName = displayName,
    email = "hello@abc.example",
    phone = "+15551234567",
    billingEmail = null,
    billingPhone = null,
    notes = "Gate code 4412.",
    status = CustomerStatus.ACTIVE,
    createdAt = "2026-01-01T00:00:00Z",
    updatedAt = "2026-01-01T00:00:00Z",
    propertyCount = 0,
    jobCount = 0,
)
