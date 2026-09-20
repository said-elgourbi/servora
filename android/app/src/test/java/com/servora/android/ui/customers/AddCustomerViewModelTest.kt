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
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.PropertyProvince
import kotlinx.coroutines.Dispatchers
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
 * What the New Customer form asks [CustomersRepository] for, and what it refuses to ask for.
 *
 * Whether a customer, Property or contact may be written is the backend's decision
 * (`BR-001`, `BR-007`), so these tests assert that a repository answer is reported and that an
 * incomplete form is never sent, rather than that a record is "correct".
 */
class AddCustomerViewModelTest {

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
        val repository = RecordingCustomerRepository()
        val viewModel = AddCustomerViewModel(repository)

        val state = viewModel.uiState.value
        assertEquals(CustomerType.INDIVIDUAL, state.type)
        assertFalse(state.canSave)
        assertFalse(state.isSaving)
        assertFalse(state.isSaved)
        assertEquals(0, repository.customerCreates)
        assertEquals(0, repository.propertyCreates)
        assertEquals(0, repository.contactCreates)
    }

    @Test
    fun `does not send an incomplete customer and marks what is missing`() = runTest(dispatcher) {
        val repository = RecordingCustomerRepository()
        val viewModel = AddCustomerViewModel(repository)
        viewModel.onFirstNameChange("John")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(0, repository.customerCreates)
        assertTrue(viewModel.uiState.value.showsValidationError)
        assertNull(viewModel.uiState.value.failureReason)
    }

    @Test
    fun `creates an individual customer with the display name the list shows`() =
        runTest(dispatcher) {
            val repository = RecordingCustomerRepository()
            val viewModel = AddCustomerViewModel(repository)
            viewModel.onFirstNameChange(" John ")
            viewModel.onLastNameChange(" Smith ")
            viewModel.onPhoneChange(" 555-123-4567 ")

            viewModel.save()
            advanceUntilIdle()

            val request = repository.lastCustomerRequest
            assertTrue(request is CreateCustomerRequest.Individual)
            val individual = request as CreateCustomerRequest.Individual
            assertEquals("INDIVIDUAL", individual.request.type)
            assertEquals("John Smith", individual.request.displayName)
            assertEquals("John", individual.request.individual.firstName)
            assertEquals("Smith", individual.request.individual.lastName)
            assertEquals("555-123-4567", individual.request.phone)
            assertNull(individual.request.email)
            assertTrue(viewModel.uiState.value.isSaved)
            assertEquals("created-1", viewModel.uiState.value.createdCustomerId)
        }

    @Test
    fun `creates a business customer whose display name is the company`() = runTest(dispatcher) {
        val repository = RecordingCustomerRepository()
        val viewModel = AddCustomerViewModel(repository)
        viewModel.onTypeChange(CustomerType.COMPANY)
        viewModel.onCompanyNameChange(" ABC Property Management ")

        viewModel.save()
        advanceUntilIdle()

        val request = repository.lastCustomerRequest
        assertTrue(request is CreateCustomerRequest.Company)
        val company = request as CreateCustomerRequest.Company
        assertEquals("COMPANY", company.request.type)
        assertEquals("ABC Property Management", company.request.displayName)
        assertEquals("ABC Property Management", company.request.company.legalName)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `writes no Property when the optional section was left empty`() = runTest(dispatcher) {
        val repository = RecordingCustomerRepository()
        val viewModel = AddCustomerViewModel(repository)
        fillIndividual(viewModel)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.customerCreates)
        assertEquals(0, repository.propertyCreates)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `writes the optional Property for the customer it just created`() = runTest(dispatcher) {
        val repository = RecordingCustomerRepository()
        val viewModel = AddCustomerViewModel(repository)
        fillIndividual(viewModel)
        fillProperty(viewModel)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.customerCreates)
        assertEquals(1, repository.propertyCreates)
        assertEquals("created-1", repository.lastPropertyCustomerId)
        val request = requireNotNull(repository.lastPropertyRequest)
        assertEquals("987 Cedar Lane", request.addressLine1)
        assertEquals("QC", request.province)
        assertNull(request.name)
    }

    @Test
    fun `refuses to send a started but incomplete Property`() = runTest(dispatcher) {
        val repository = RecordingCustomerRepository()
        val viewModel = AddCustomerViewModel(repository)
        fillIndividual(viewModel)
        viewModel.onStreetAddressChange("987 Cedar Lane")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(0, repository.customerCreates)
        assertTrue(viewModel.uiState.value.showsValidationError)
    }

    @Test
    fun `records the primary contact only for a business customer`() = runTest(dispatcher) {
        val repository = RecordingCustomerRepository()
        val viewModel = AddCustomerViewModel(repository)
        fillCompany(viewModel)
        viewModel.onContactFirstNameChange(" John ")
        viewModel.onContactLastNameChange(" Smith ")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.contactCreates)
        assertEquals("created-1", repository.lastContactCustomerId)
        val contact = requireNotNull(repository.lastContactRequest)
        assertEquals("John", contact.firstName)
        assertEquals("Smith", contact.lastName)
        assertTrue(contact.isPrimary)
    }

    @Test
    fun `an individual customer never writes a contact`() = runTest(dispatcher) {
        val repository = RecordingCustomerRepository()
        val viewModel = AddCustomerViewModel(repository)
        fillIndividual(viewModel)
        viewModel.onContactFirstNameChange("John")
        viewModel.onContactLastNameChange("Smith")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(0, repository.contactCreates)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `reports an unfinished Property when the customer was created but the Property failed`() =
        runTest(dispatcher) {
            val repository = RecordingCustomerRepository(
                propertyResult = PropertyCreateResult.Failure(
                    CustomersFailureReason.VALIDATION,
                ),
            )
            val viewModel = AddCustomerViewModel(repository)
            fillIndividual(viewModel)
            fillProperty(viewModel)

            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("created-1", state.createdCustomerId)
            assertEquals(CustomerSetupStep.FIRST_PROPERTY, state.unfinishedStep)
            assertEquals(CustomersFailureReason.VALIDATION, state.failureReason)
            assertTrue(state.hasUnfinishedStep)
            assertFalse(state.isSaved)

            // The customer exists, so the form must never submit the create again.
            viewModel.save()
            advanceUntilIdle()
            assertEquals(1, repository.customerCreates)
        }

    @Test
    fun `reports an unfinished contact when the customer was created but the contact failed`() =
        runTest(dispatcher) {
            val repository = RecordingCustomerRepository(
                contactResult = ContactCreateResult.Failure(
                    CustomersFailureReason.FORBIDDEN,
                ),
            )
            val viewModel = AddCustomerViewModel(repository)
            fillCompany(viewModel)
            viewModel.onContactFirstNameChange("John")
            viewModel.onContactLastNameChange("Smith")

            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(CustomerSetupStep.PRIMARY_CONTACT, state.unfinishedStep)
            assertEquals(CustomersFailureReason.FORBIDDEN, state.failureReason)
            assertFalse(state.isSaved)
        }

    @Test
    fun `a failed customer create leaves no customer and no unfinished step`() =
        runTest(dispatcher) {
            val repository = RecordingCustomerRepository(
                createResult = CustomerCreateResult.Failure(CustomersFailureReason.NETWORK),
            )
            val viewModel = AddCustomerViewModel(repository)
            fillIndividual(viewModel)
            fillProperty(viewModel)

            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull(state.createdCustomerId)
            assertNull(state.unfinishedStep)
            assertFalse(state.hasUnfinishedStep)
            assertEquals(CustomersFailureReason.NETWORK, state.failureReason)
            assertEquals(0, repository.propertyCreates)
        }

    @Test
    fun `keeps an in-progress form within a session and resets a finished one`() = runTest(dispatcher) {
        val repository = RecordingCustomerRepository()
        val viewModel = AddCustomerViewModel(repository)
        viewModel.start("session-1")
        fillIndividual(viewModel)
        viewModel.onPhoneChange("555-123-4567")

        // Recomposing the same destination instance is the same session, so the form is kept.
        viewModel.start("session-1")
        assertEquals("555-123-4567", viewModel.uiState.value.phone)

        viewModel.save()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSaved)

        // Opening New Customer again is a new destination instance, so it starts empty.
        viewModel.start("session-2")
        assertFalse(viewModel.uiState.value.isSaved)
        assertEquals("", viewModel.uiState.value.phone)
        assertNull(viewModel.uiState.value.createdCustomerId)
    }

    @Test
    fun `does not carry a validation message into a new form session`() {
        val viewModel = AddCustomerViewModel(RecordingCustomerRepository())
        viewModel.start("session-1")
        viewModel.save()
        assertTrue(viewModel.uiState.value.showsValidationError)

        viewModel.start("session-2")

        assertFalse(viewModel.uiState.value.showsValidationError)
    }

    @Test
    fun `does not carry a refused create into a new form session`() = runTest(dispatcher) {
        val repository = RecordingCustomerRepository(
            createResult = CustomerCreateResult.Failure(CustomersFailureReason.VALIDATION),
        )
        val viewModel = AddCustomerViewModel(repository)
        viewModel.start("session-1")
        fillIndividual(viewModel)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(CustomersFailureReason.VALIDATION, viewModel.uiState.value.failureReason)

        viewModel.start("session-2")

        assertNull(viewModel.uiState.value.failureReason)
        assertFalse(viewModel.uiState.value.showsValidationError)
        assertEquals("", viewModel.uiState.value.firstName)
        assertEquals("", viewModel.uiState.value.lastName)
    }

    @Test
    fun `clears the validation message once the missing fields are filled`() {
        val viewModel = AddCustomerViewModel(RecordingCustomerRepository())
        viewModel.start("session-1")
        viewModel.save()
        assertTrue(viewModel.uiState.value.showsValidationError)

        fillIndividual(viewModel)

        assertFalse(viewModel.uiState.value.showsValidationError)
    }

    @Test
    fun `reset releases the form when the session ends`() {
        val viewModel = AddCustomerViewModel(RecordingCustomerRepository())
        fillIndividual(viewModel)

        viewModel.reset()

        assertEquals("", viewModel.uiState.value.firstName)
        assertEquals("", viewModel.uiState.value.phone)
    }

    private fun fillIndividual(viewModel: AddCustomerViewModel) {
        viewModel.onFirstNameChange("John")
        viewModel.onLastNameChange("Smith")
    }

    private fun fillCompany(viewModel: AddCustomerViewModel) {
        viewModel.onTypeChange(CustomerType.COMPANY)
        viewModel.onCompanyNameChange("ABC Property Management")
    }

    private fun fillProperty(viewModel: AddCustomerViewModel) {
        viewModel.onStreetAddressChange(" 987 Cedar Lane ")
        viewModel.onCityChange(" Montreal ")
        viewModel.onProvinceChange(PropertyProvince.QC)
        viewModel.onPostalCodeChange(" H3A 2T6 ")
    }
}

/** A [CustomersRepository] that answers with scripted results and records every write. */
private class RecordingCustomerRepository(
    var createResult: CustomerCreateResult = CustomerCreateResult.Success("created-1"),
    var propertyResult: PropertyCreateResult =
        PropertyCreateResult.Success(customerProperty()),
    var contactResult: ContactCreateResult = ContactCreateResult.Success,
) : CustomersRepository {

    var customerCreates: Int = 0
    var lastCustomerRequest: CreateCustomerRequest? = null
    var propertyCreates: Int = 0
    var lastPropertyCustomerId: String? = null
    var lastPropertyRequest: CreatePropertyRequest? = null
    var contactCreates: Int = 0
    var lastContactCustomerId: String? = null
    var lastContactRequest: CreateCustomerContactRequest? = null

    override suspend fun listCustomers(filters: CustomerFilters): CustomersResult =
        CustomersResult.Success(emptyList())

    override suspend fun loadCustomerDetail(customerId: String): CustomerDetailResult =
        CustomerDetailResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun createProperty(
        customerId: String,
        request: CreatePropertyRequest,
    ): PropertyCreateResult {
        propertyCreates += 1
        lastPropertyCustomerId = customerId
        lastPropertyRequest = request
        return propertyResult
    }

    override suspend fun createCustomer(request: CreateCustomerRequest): CustomerCreateResult {
        customerCreates += 1
        lastCustomerRequest = request
        return createResult
    }

    override suspend fun createContact(
        customerId: String,
        request: CreateCustomerContactRequest,
    ): ContactCreateResult {
        contactCreates += 1
        lastContactCustomerId = customerId
        lastContactRequest = request
        return contactResult
    }

    // The contact edit and removal are not part of what this fake scripts: it answers the same
    // outcome an unrepresentable reply would, so a test cannot read a write it did not arrange.
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
    ): CustomerUpdateResult =
        CustomerUpdateResult.Failure(CustomersFailureReason.UNEXPECTED)
}

/** A Property row the fake answers a create with; its values are not the test's subject. */
private fun customerProperty() = CustomerProperty(
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
)
