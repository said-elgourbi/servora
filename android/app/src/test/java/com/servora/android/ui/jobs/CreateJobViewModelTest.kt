package com.servora.android.ui.jobs

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
import com.servora.android.data.jobs.ActivityWriteResult
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.CreateJobRequest
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.JobCreateFailure
import com.servora.android.data.jobs.JobCreateResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.jobs.JobDetailsResult
import com.servora.android.data.jobs.QueuedVisitFieldAction
import com.servora.android.data.jobs.QueuedVisitNote
import com.servora.android.data.jobs.VisitNote
import com.servora.android.data.jobs.VisitStatusChange
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerStatusFilter
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAssignment
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
 * What the Create Job form asks the repositories for, and what it refuses to ask for (`BR-094`).
 *
 * Whether a Job may be created is the backend's decision (`BR-001`, `BR-007`), so these tests assert that
 * a repository answer is reported, which reads back the form, and that an incomplete or duplicated
 * submission is never sent — not that a Job is "correct".
 */
class CreateJobViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @Test
    fun `starts empty and writes nothing until asked`() {
        val jobs = RecordingJobCreator()
        val viewModel = CreateJobViewModel(RecordingCustomersRepository(), jobs)

        viewModel.start(customerId = null, customerName = null, sessionId = "session-1")

        val state = viewModel.uiState.value
        assertNull(state.selectedCustomerId)
        assertNull(state.selectedPropertyId)
        assertFalse(state.canSubmit)
        assertFalse(state.isSubmitting)
        assertEquals(0, jobs.creates)
    }

    @Test
    fun `reads the active customers for the selector and does not read them again`() =
        runTest(dispatcher) {
            val customers = RecordingCustomersRepository(
                customers = listOf(customer(id = "c1", name = "Martha Reynolds")),
            )
            val viewModel = CreateJobViewModel(customers, RecordingJobCreator())

            viewModel.start(customerId = null, customerName = null, sessionId = "session-1")
            advanceUntilIdle()
            assertEquals(1, customers.lists)

            // Re-opening the picker does not read a second time while the list is held.
            viewModel.onCustomerPickerOpen()
            advanceUntilIdle()
            assertEquals(1, customers.lists)

            // Only a customer that can take new work is asked for (`BR-001`, `BR-094`).
            assertEquals(CustomerStatusFilter.ACTIVE, customers.lastFilters?.status)
        }

    @Test
    fun `retries the customer read when the picker opens with nothing loaded`() =
        runTest(dispatcher) {
            val customers = RecordingCustomersRepository(
                customers = listOf(customer(id = "c1", name = "Martha Reynolds")),
                listResult = CustomersResult.Failure(CustomersFailureReason.NETWORK),
            )
            val viewModel = CreateJobViewModel(customers, RecordingJobCreator())
            viewModel.start(customerId = null, customerName = null, sessionId = "session-1")
            advanceUntilIdle()
            assertEquals(CustomersFailureReason.NETWORK, viewModel.uiState.value.customersFailure)

            // Opening the picker with nothing loaded is the user asking for the list, so it is read
            // again rather than left failed (`BR-042`).
            customers.listResult = null
            viewModel.onCustomerPickerOpen()
            advanceUntilIdle()

            assertEquals(2, customers.lists)
            assertNull(viewModel.uiState.value.customersFailure)
            assertEquals(listOf("c1"), viewModel.uiState.value.customers.map { it.id })
        }

    @Test
    fun `narrows the customer rows locally without a second read`() = runTest(dispatcher) {
        val customers = RecordingCustomersRepository(
            customers = listOf(
                customer(id = "c1", name = "Martha Reynolds"),
                customer(id = "c2", name = "ABC Property Management"),
            ),
        )
        val viewModel = CreateJobViewModel(customers, RecordingJobCreator())
        viewModel.start(customerId = null, customerName = null, sessionId = "session-1")
        viewModel.onCustomerPickerOpen()
        advanceUntilIdle()

        viewModel.onCustomerQueryChange("abc")

        // `GET /customers` has no text query, so typing narrows what was read rather than asking again
        // (`BR-001`).
        assertEquals(listOf("c2"), viewModel.uiState.value.visibleCustomers.map { it.id })
        assertEquals(1, customers.lists)
    }

    @Test
    fun `reads the properties of the customer that was chosen`() = runTest(dispatcher) {
        val customers = RecordingCustomersRepository(
            customers = listOf(customer(id = "c1", name = "Martha Reynolds")),
            properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
        )
        val viewModel = CreateJobViewModel(customers, RecordingJobCreator())
        viewModel.start(customerId = null, customerName = null, sessionId = "session-1")
        viewModel.onCustomerPickerOpen()
        advanceUntilIdle()

        viewModel.selectCustomer(viewModel.uiState.value.customers.single())
        assertTrue(viewModel.uiState.value.propertiesLoading)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Martha Reynolds", state.selectedCustomerName)
        assertEquals(listOf("p1"), state.properties.map { it.id })
        assertFalse(state.propertiesLoading)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `preselects the customer the form was opened for and reads its properties`() =
        runTest(dispatcher) {
            val customers = RecordingCustomersRepository(
                properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
            )
            val viewModel = CreateJobViewModel(customers, RecordingJobCreator())

            viewModel.start(
                customerId = "c1",
                customerName = "Martha Reynolds",
                sessionId = "session-1",
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.hasFixedCustomer)
            assertEquals("c1", state.selectedCustomerId)
            assertEquals("Martha Reynolds", state.fixedCustomerName)
            assertEquals(listOf("p1"), state.properties.map { it.id })
            // The Customer's own detail is the read that carries its Properties, and the customer list
            // is never read because the user is not searching for one (`BR-081`).
            assertEquals(listOf("c1"), customers.details)
            assertEquals(0, customers.lists)
        }

    @Test
    fun `clears the chosen property when the customer changes`() = runTest(dispatcher) {
        val customers = RecordingCustomersRepository(
            customers = listOf(
                customer(id = "c1", name = "Martha Reynolds"),
                customer(id = "c2", name = "ABC Property Management"),
            ),
            propertiesByCustomer = mapOf(
                "c1" to listOf(property(id = "p1", name = "Cedar Lane Building")),
                "c2" to listOf(property(id = "p2", name = "Maple Street Depot")),
            ),
        )
        val viewModel = CreateJobViewModel(customers, RecordingJobCreator())
        viewModel.start(customerId = null, customerName = null, sessionId = "session-1")
        viewModel.onCustomerPickerOpen()
        advanceUntilIdle()

        viewModel.selectCustomer(viewModel.uiState.value.customers.first { it.id == "c1" })
        advanceUntilIdle()
        viewModel.selectProperty("p1")
        assertEquals("p1", viewModel.uiState.value.selectedPropertyId)

        viewModel.selectCustomer(viewModel.uiState.value.customers.first { it.id == "c2" })
        // The previous Customer's Property is not a location this Job may be created at (`BR-050`).
        assertNull(viewModel.uiState.value.selectedPropertyId)
        assertTrue(viewModel.uiState.value.properties.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("p2"), viewModel.uiState.value.properties.map { it.id })
        assertNull(viewModel.uiState.value.selectedPropertyId)
    }

    @Test
    fun `reports a customer with no active property as its own state`() = runTest(dispatcher) {
        val viewModel = CreateJobViewModel(RecordingCustomersRepository(), RecordingJobCreator())
        viewModel.start(customerId = "c1", customerName = "Martha Reynolds", sessionId = "session-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showsNoProperties)
        assertFalse(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `reports a failed property read and can retry it`() = runTest(dispatcher) {
        val customers = RecordingCustomersRepository(
            properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
            detailResult = CustomerDetailResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = CreateJobViewModel(customers, RecordingJobCreator())
        viewModel.start(customerId = "c1", customerName = null, sessionId = "session-1")
        advanceUntilIdle()

        assertEquals(CustomersFailureReason.NETWORK, viewModel.uiState.value.propertiesFailure)
        // A failed read is not "no properties": the two answers must not be read as each other
        // (`BR-042`).
        assertFalse(viewModel.uiState.value.showsNoProperties)

        customers.detailResult = CustomerDetailResult.Success(detail(properties = emptyList()))
        viewModel.retryProperties()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.propertiesFailure)
        assertTrue(viewModel.uiState.value.properties.isEmpty())
    }

    @Test
    fun `does not send an incomplete form and marks what is missing`() = runTest(dispatcher) {
        val jobs = RecordingJobCreator()
        val viewModel = CreateJobViewModel(RecordingCustomersRepository(), jobs)
        viewModel.start(customerId = "c1", customerName = "Martha Reynolds", sessionId = "session-1")
        advanceUntilIdle()

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(0, jobs.creates)
        assertFalse(viewModel.uiState.value.isSubmitting)
        assertTrue(viewModel.uiState.value.showsValidationError)
        assertNull(viewModel.uiState.value.failureReason)
    }

    @Test
    fun `clears the validation message once the missing values are chosen`() = runTest(dispatcher) {
        val customers = RecordingCustomersRepository(
            properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
        )
        val viewModel = CreateJobViewModel(customers, RecordingJobCreator())
        viewModel.start(customerId = "c1", customerName = "Martha Reynolds", sessionId = "session-1")
        advanceUntilIdle()

        viewModel.submit()
        assertTrue(viewModel.uiState.value.showsValidationError)

        viewModel.selectProperty("p1")
        viewModel.onTitleChange("Furnace repair")

        assertFalse(viewModel.uiState.value.showsValidationError)
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `creates the job with the trimmed values and reports the created id`() =
        runTest(dispatcher) {
            val jobs = RecordingJobCreator(JobCreateResult.Success("job-9"))
            val viewModel = CreateJobViewModel(
                RecordingCustomersRepository(
                    properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
                ),
                jobs,
            )
            viewModel.start(
                customerId = "c1",
                customerName = "Martha Reynolds",
                sessionId = "session-1",
            )
            advanceUntilIdle()
            viewModel.selectProperty("p1")
            viewModel.onTitleChange("  Furnace repair  ")
            viewModel.onDescriptionChange("  Customer reports no heat.  ")

            viewModel.submit()
            assertTrue(viewModel.uiState.value.isSubmitting)
            advanceUntilIdle()

            assertEquals(1, jobs.creates)
            assertEquals(
                CreateJobRequest(
                    customerId = "c1",
                    propertyId = "p1",
                    title = "Furnace repair",
                    description = "Customer reports no heat.",
                ),
                jobs.lastRequest,
            )
            assertEquals("job-9", viewModel.uiState.value.createdJobId)
            assertFalse(viewModel.uiState.value.isSubmitting)
        }

    @Test
    fun `sends an absent description rather than a blank one`() = runTest(dispatcher) {
        val jobs = RecordingJobCreator()
        val viewModel = CreateJobViewModel(
            RecordingCustomersRepository(
                properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
            ),
            jobs,
        )
        viewModel.start(customerId = "c1", customerName = null, sessionId = "session-1")
        advanceUntilIdle()
        viewModel.selectProperty("p1")
        viewModel.onTitleChange("Furnace repair")
        viewModel.onDescriptionChange("   ")

        viewModel.submit()
        advanceUntilIdle()

        assertNull(jobs.lastRequest?.description)
    }

    @Test
    fun `does not send the same job twice while a submit is in flight`() = runTest(dispatcher) {
        val jobs = RecordingJobCreator(JobCreateResult.Success("job-9"))
        jobs.gate = CompletableDeferred()
        val viewModel = CreateJobViewModel(
            RecordingCustomersRepository(
                properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
            ),
            jobs,
        )
        viewModel.start(customerId = "c1", customerName = null, sessionId = "session-1")
        advanceUntilIdle()
        viewModel.selectProperty("p1")
        viewModel.onTitleChange("Furnace repair")

        viewModel.submit()
        viewModel.submit()
        jobs.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, jobs.creates)
    }

    @Test
    fun `keeps every entered value when the api refuses the create`() = runTest(dispatcher) {
        val jobs = RecordingJobCreator(
            JobCreateResult.Failure(JobCreateFailure.PROPERTY_UNAVAILABLE),
        )
        val viewModel = CreateJobViewModel(
            RecordingCustomersRepository(
                properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
            ),
            jobs,
        )
        viewModel.start(customerId = "c1", customerName = "Martha Reynolds", sessionId = "session-1")
        advanceUntilIdle()
        viewModel.selectProperty("p1")
        viewModel.onTitleChange("Furnace repair")
        viewModel.onDescriptionChange("Customer reports no heat.")

        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(JobCreateFailure.PROPERTY_UNAVAILABLE, state.failureReason)
        // The refusal is reported without discarding the form, so the user corrects it rather than
        // retyping it (`BR-042`).
        assertEquals("Furnace repair", state.title)
        assertEquals("Customer reports no heat.", state.description)
        assertEquals("p1", state.selectedPropertyId)
        assertNull(state.createdJobId)
        assertFalse(state.isSubmitting)
        assertFalse(state.showsValidationError)

        viewModel.dismissFailure()
        assertNull(viewModel.uiState.value.failureReason)
        assertEquals("Furnace repair", viewModel.uiState.value.title)
    }

    @Test
    fun `does not carry values or a failure into a new form session`() = runTest(dispatcher) {
        val viewModel = CreateJobViewModel(
            RecordingCustomersRepository(
                properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
            ),
            RecordingJobCreator(JobCreateResult.Failure(JobCreateFailure.SERVER)),
        )
        viewModel.start(customerId = "c1", customerName = "Martha Reynolds", sessionId = "session-1")
        advanceUntilIdle()
        viewModel.selectProperty("p1")
        viewModel.onTitleChange("Furnace repair")
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(JobCreateFailure.SERVER, viewModel.uiState.value.failureReason)

        // A new destination instance is a new form session, so nothing of the previous attempt is drawn
        // again (`BR-042`).
        viewModel.start(customerId = "c1", customerName = "Martha Reynolds", sessionId = "session-2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.failureReason)
        assertEquals("", state.title)
        assertNull(state.selectedPropertyId)
        assertFalse(state.showsValidationError)
        assertNull(state.createdJobId)
    }

    @Test
    fun `keeps what was entered when the same session is composed again`() = runTest(dispatcher) {
        val viewModel = CreateJobViewModel(
            RecordingCustomersRepository(
                properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
            ),
            RecordingJobCreator(),
        )
        viewModel.start(customerId = "c1", customerName = "Martha Reynolds", sessionId = "session-1")
        advanceUntilIdle()
        viewModel.selectProperty("p1")
        viewModel.onTitleChange("Furnace repair")

        // A configuration change composes the same destination instance again, so the form is kept.
        viewModel.start(customerId = "c1", customerName = "Martha Reynolds", sessionId = "session-1")

        assertEquals("Furnace repair", viewModel.uiState.value.title)
        assertEquals("p1", viewModel.uiState.value.selectedPropertyId)
    }

    private fun customer(id: String, name: String) = Customer(
        id = id,
        organizationId = "org-1",
        type = CustomerType.COMPANY,
        displayName = name,
        email = "$id@example.com",
        phone = "+15145550142",
        billingEmail = null,
        billingPhone = null,
        notes = null,
        status = CustomerStatus.ACTIVE,
        createdAt = "2026-01-01T10:00:00Z",
        updatedAt = "2026-01-01T10:00:00Z",
        propertyCount = 1,
        jobCount = 0,
    )

    private fun property(id: String, name: String?) = CustomerProperty(
        id = id,
        name = name,
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
        jobCount = 0,
        lastServiceAt = null,
    )

    private fun detail(properties: List<CustomerProperty>) = CustomerDetail(
        customer = customer(id = "c1", name = "Martha Reynolds"),
        contacts = emptyList(),
        properties = properties,
        jobs = emptyList(),
    )
}

/** A [CustomersRepository] that answers scripted reads and counts what it was asked for. */
private class RecordingCustomersRepository(
    private val customers: List<Customer> = emptyList(),
    private val properties: List<CustomerProperty> = emptyList(),
    private val propertiesByCustomer: Map<String, List<CustomerProperty>> = emptyMap(),
    var detailResult: CustomerDetailResult? = null,
    var listResult: CustomersResult? = null,
) : CustomersRepository {

    var lists: Int = 0
    var lastFilters: CustomerFilters? = null
    val details = mutableListOf<String>()

    override suspend fun listCustomers(filters: CustomerFilters): CustomersResult {
        lists += 1
        lastFilters = filters
        return listResult ?: CustomersResult.Success(customers)
    }

    override suspend fun loadCustomerDetail(customerId: String): CustomerDetailResult {
        details += customerId
        detailResult?.let { return it }
        return CustomerDetailResult.Success(
            CustomerDetail(
                customer = Customer(
                    id = customerId,
                    organizationId = "org-1",
                    type = CustomerType.COMPANY,
                    displayName = "Customer $customerId",
                    email = null,
                    phone = null,
                    billingEmail = null,
                    billingPhone = null,
                    notes = null,
                    status = CustomerStatus.ACTIVE,
                    createdAt = "2026-01-01T10:00:00Z",
                    updatedAt = "2026-01-01T10:00:00Z",
                    propertyCount = 0,
                    jobCount = 0,
                ),
                contacts = emptyList(),
                properties = propertiesByCustomer[customerId] ?: properties,
                jobs = emptyList(),
            ),
        )
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
}

/** A [JobDetailsRepository] that only creates, so the form's own write can be asserted. */
private class RecordingJobCreator(
    private val result: JobCreateResult = JobCreateResult.Success("job-1"),
) : JobDetailsRepository {

    /** When set, the create waits here so a test can keep one submission in flight. */
    var gate: CompletableDeferred<Unit>? = null

    var creates: Int = 0
    var lastRequest: CreateJobRequest? = null

    override suspend fun createJob(request: CreateJobRequest): JobCreateResult {
        creates += 1
        lastRequest = request
        gate?.await()
        return result
    }

    override suspend fun loadJobDetails(jobId: String): JobDetailsResult =
        JobDetailsResult.Failure(CustomersFailureReason.NOT_FOUND)

    override suspend fun loadJobActivity(jobId: String): JobActivityResult =
        JobActivityResult.Failure(CustomersFailureReason.NOT_FOUND)

    override suspend fun addVisitNoteRequest(note: VisitNote): ActivityWriteResult = unreachable()

    override suspend fun changeVisitStatus(action: VisitStatusChange): JobActionResult =
        unreachable()

    override suspend fun queuedVisitAction(jobId: String): QueuedVisitFieldAction? = null

    override suspend fun queuedVisitNotes(jobId: String): List<QueuedVisitNote> = emptyList()

    override suspend fun discardQueuedVisitAction(jobId: String, operationId: String): Boolean =
        unreachable()

    override suspend fun discardQueuedVisitNote(jobId: String, operationId: String): Boolean =
        unreachable()

    override val appliedOperations: Flow<Unit> = emptyFlow()

    override suspend fun removeJobPhoto(
        jobId: String,
        photoId: String,
        reason: String,
    ): ActivityWriteResult = unreachable()

    override suspend fun removeJobAudioNote(
        jobId: String,
        audioNoteId: String,
        reason: String,
    ): ActivityWriteResult = unreachable()

    override suspend fun changeJobStatus(
        jobId: String,
        status: JobStatus,
        note: String?,
        expectedVersion: Int,
    ): JobActionResult = unreachable()

    override suspend fun rescheduleVisit(
        jobId: String,
        visitId: String,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        reason: String?,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult = unreachable()

    override suspend fun assignVisitTechnicians(
        jobId: String,
        visitId: String,
        assignments: List<TechnicianAssignment>,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult = unreachable()

    override suspend fun loadAssignableTechnicians(): AssignableTechniciansResult = unreachable()

    private fun unreachable(): Nothing =
        throw AssertionError("the Create Job form only creates a Job")
}
