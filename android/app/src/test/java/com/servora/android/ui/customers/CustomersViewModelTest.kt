package com.servora.android.ui.customers

import com.servora.android.data.customers.ContactCreateResult
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
import com.servora.android.data.customers.UpdateCustomerRequest
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerJob
import com.servora.android.domain.model.CustomerJobFilter
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerStatusFilter
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.JobStatus
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
 * What the customer list shows, and what it asks [CustomersRepository] for.
 *
 * Whether a customer exists, and whether the caller may see it, is the backend's decision
 * (`BR-001`, `BR-007`), so these tests assert that a repository answer is reported rather than
 * that a list is "correct".
 */
class CustomersViewModelTest {

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
    fun `starts empty and reads nothing until asked`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository()
        val viewModel = CustomersViewModel(repository)

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.customers.isEmpty())
        assertEquals(0, repository.reads)
    }

    @Test
    fun `shows loading and then the customers the repository returned`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            result = CustomersResult.Success(
                listOf(customer(id = "c1", displayName = "Martha Reynolds")),
            ),
        )
        val viewModel = CustomersViewModel(repository)

        viewModel.load()
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.failureReason)
        assertEquals(listOf("c1"), state.customers.map { it.id })
        assertEquals("Martha Reynolds", state.customers.first().displayName)
    }

    @Test
    fun `marks a company customer as a business`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            result = CustomersResult.Success(
                listOf(customer(id = "c1", type = CustomerType.COMPANY)),
            ),
        )
        val viewModel = CustomersViewModel(repository)

        viewModel.load()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.customers.single().isCompany)
    }

    @Test
    fun `re-reads the list when a confirmed mutation changes a derived count`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            result = CustomersResult.Success(listOf(customer(id = "c1", propertyCount = 3))),
        )
        val viewModel = CustomersViewModel(repository)

        viewModel.load()
        advanceUntilIdle()
        assertEquals(1, repository.reads)
        assertEquals(3, viewModel.uiState.value.customers.single().propertyCount)

        // A Property was archived on the backend while the detail was open, so the list is re-read
        // rather than left describing the earlier count (`BR-001`).
        repository.result =
            CustomersResult.Success(listOf(customer(id = "c1", propertyCount = 2)))
        viewModel.reload()
        advanceUntilIdle()

        assertEquals(2, repository.reads)
        assertEquals(2, viewModel.uiState.value.customers.single().propertyCount)
    }

    @Test
    fun `carries the derived property and job counts onto the row`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            result = CustomersResult.Success(
                listOf(customer(id = "c1", propertyCount = 3, jobCount = 5)),
            ),
        )
        val viewModel = CustomersViewModel(repository)

        viewModel.load()
        advanceUntilIdle()

        val row = viewModel.uiState.value.customers.single()
        assertEquals(3, row.propertyCount)
        assertEquals(5, row.jobCount)
    }

    @Test
    fun `carries the created timestamp onto the row so it can show the since date`() =
        runTest(dispatcher) {
            val repository = RecordingCustomersRepository(
                result = CustomersResult.Success(
                    listOf(customer(id = "c1", createdAt = "2025-01-12T10:30:00Z")),
                ),
            )
            val viewModel = CustomersViewModel(repository)

            viewModel.load()
            advanceUntilIdle()

            assertEquals(
                "2025-01-12T10:30:00Z",
                viewModel.uiState.value.customers.single().createdAt,
            )
        }

    @Test
    fun `reads only once while the tab is revisited`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            result = CustomersResult.Success(emptyList()),
        )
        val viewModel = CustomersViewModel(repository)

        viewModel.load()
        viewModel.load()
        advanceUntilIdle()

        assertEquals(1, repository.reads)
    }

    @Test
    fun `reports a failure and retries on request`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            result = CustomersResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = CustomersViewModel(repository)

        viewModel.load()
        advanceUntilIdle()

        assertEquals(CustomersFailureReason.NETWORK, viewModel.uiState.value.failureReason)
        assertFalse(viewModel.uiState.value.isLoading)

        repository.result = CustomersResult.Success(listOf(customer(id = "c1")))
        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.failureReason)
        assertEquals(listOf("c1"), viewModel.uiState.value.customers.map { it.id })
        assertEquals(2, repository.reads)
    }

    @Test
    fun `a failed read clears the previous list so it cannot show another filter's rows`() =
        runTest(dispatcher) {
            val repository = RecordingCustomersRepository(
                result = CustomersResult.Success(listOf(customer(id = "c1"))),
            )
            val viewModel = CustomersViewModel(repository)
            viewModel.load()
            advanceUntilIdle()
            assertEquals(listOf("c1"), viewModel.uiState.value.customers.map { it.id })

            repository.result = CustomersResult.Failure(CustomersFailureReason.NETWORK)
            viewModel.applyFilters(
                CustomerFilters(
                    status = CustomerStatusFilter.INACTIVE,
                    jobs = CustomerJobFilter.ALL,
                ),
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(CustomersFailureReason.NETWORK, state.failureReason)
            assertTrue(state.customers.isEmpty())
        }

    @Test
    fun `reads with the list's default active-only filter until one is applied`() =
        runTest(dispatcher) {
            val repository = RecordingCustomersRepository()
            val viewModel = CustomersViewModel(repository)

            viewModel.load()
            advanceUntilIdle()

            assertEquals(CustomerFilters(), repository.lastFilters)
            assertEquals(CustomerStatusFilter.ACTIVE, repository.lastFilters?.status)
            assertEquals(CustomerJobFilter.ALL, repository.lastFilters?.jobs)
        }

    @Test
    fun `clearing to the unconstrained filter re-reads without a constraint`() =
        runTest(dispatcher) {
            val repository = RecordingCustomersRepository()
            val viewModel = CustomersViewModel(repository)
            viewModel.load()
            advanceUntilIdle()

            viewModel.applyFilters(CustomerFilters.Unconstrained)
            advanceUntilIdle()

            assertEquals(CustomerFilters.Unconstrained, repository.lastFilters)
            assertEquals(2, repository.reads)
            assertFalse(viewModel.uiState.value.filters.isActive)
        }

    @Test
    fun `applying a filter re-reads with it and records it in the state`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            result = CustomersResult.Success(listOf(customer(id = "c1"))),
        )
        val viewModel = CustomersViewModel(repository)
        viewModel.load()
        advanceUntilIdle()

        val filters = CustomerFilters(
            status = CustomerStatusFilter.INACTIVE,
            jobs = CustomerJobFilter.HAS_OVERDUE_VISITS,
        )
        viewModel.applyFilters(filters)
        advanceUntilIdle()

        assertEquals(filters, viewModel.uiState.value.filters)
        assertEquals(filters, repository.lastFilters)
        assertEquals(2, repository.reads)
    }

    @Test
    fun `re-applying the same filter does not read again`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository()
        val viewModel = CustomersViewModel(repository)
        viewModel.load()
        advanceUntilIdle()

        viewModel.applyFilters(CustomerFilters())
        advanceUntilIdle()

        assertEquals(1, repository.reads)
    }

    @Test
    fun `reset drops the loaded list and allows a later read to load again`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            result = CustomersResult.Success(
                listOf(customer(id = "c1", displayName = "Martha Reynolds")),
            ),
        )
        val viewModel = CustomersViewModel(repository)
        viewModel.load()
        advanceUntilIdle()

        // Signing out must not leave one session's rows visible to the next (`BR-001`).
        viewModel.reset()

        assertTrue(viewModel.uiState.value.customers.isEmpty())

        viewModel.load()
        advanceUntilIdle()

        assertEquals(2, repository.reads)
    }

    @Test
    fun `opens a customer and exposes the backend's detail projections`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            detailResult = CustomerDetailResult.Success(detail()),
        )
        val viewModel = CustomersViewModel(repository)

        viewModel.openCustomerDetail("c1")
        assertTrue(viewModel.uiState.value.customerDetail?.isLoading == true)

        advanceUntilIdle()

        val state = viewModel.uiState.value.customerDetail
        assertEquals("c1", state?.customerId)
        assertFalse(state?.isLoading == true)
        assertNull(state?.failureReason)
        assertEquals("c1", repository.lastDetailCustomerId)
        assertEquals(listOf("p1"), state?.detail?.properties?.map { it.id })
        assertEquals(listOf("j1"), state?.detail?.jobs?.map { it.id })
        assertEquals(1, repository.detailReads)
    }

    @Test
    fun `reports a failed detail read without keeping a previous detail`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            detailResult = CustomerDetailResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = CustomersViewModel(repository)

        viewModel.openCustomerDetail("c1")
        advanceUntilIdle()

        val state = viewModel.uiState.value.customerDetail
        assertEquals(CustomersFailureReason.NETWORK, state?.failureReason)
        assertNull(state?.detail)
    }

    @Test
    fun `re-opening the loaded customer does not read again`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            detailResult = CustomerDetailResult.Success(detail()),
        )
        val viewModel = CustomersViewModel(repository)

        viewModel.openCustomerDetail("c1")
        advanceUntilIdle()
        viewModel.openCustomerDetail("c1")
        advanceUntilIdle()

        assertEquals(1, repository.detailReads)
    }

    @Test
    fun `retrying re-reads the customer whose detail failed`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            detailResult = CustomerDetailResult.Failure(CustomersFailureReason.SERVER),
        )
        val viewModel = CustomersViewModel(repository)
        viewModel.openCustomerDetail("c1")
        advanceUntilIdle()

        repository.detailResult = CustomerDetailResult.Success(detail())
        viewModel.retryCustomerDetail()
        advanceUntilIdle()

        assertEquals(2, repository.detailReads)
        assertEquals(
            listOf("j1"),
            viewModel.uiState.value.customerDetail?.detail?.jobs?.map { it.id },
        )
    }

    @Test
    fun `reloading a customer re-reads a detail that is already loaded`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            detailResult = CustomerDetailResult.Success(detail()),
        )
        val viewModel = CustomersViewModel(repository)
        viewModel.openCustomerDetail("c1")
        advanceUntilIdle()
        assertEquals(1, repository.detailReads)

        // A write changed what the customer has, so the detail is re-read instead of being skipped.
        viewModel.reloadCustomerDetail("c1")
        advanceUntilIdle()

        assertEquals(2, repository.detailReads)
    }

    @Test
    fun `closing and resetting both drop the loaded detail`() = runTest(dispatcher) {
        val repository = RecordingCustomersRepository(
            detailResult = CustomerDetailResult.Success(detail()),
        )
        val viewModel = CustomersViewModel(repository)
        viewModel.openCustomerDetail("c1")
        advanceUntilIdle()

        viewModel.closeCustomerDetail()
        assertNull(viewModel.uiState.value.customerDetail)

        viewModel.openCustomerDetail("c1")
        advanceUntilIdle()
        viewModel.reset()

        assertNull(viewModel.uiState.value.customerDetail)
    }

    private fun detail(
        properties: List<CustomerProperty> = listOf(property()),
        jobs: List<CustomerJob> = listOf(job()),
    ) = CustomerDetail(
        customer = customer(),
        contacts = emptyList(),
        properties = properties,
        jobs = jobs,
    )

    private fun property() = CustomerProperty(
        id = "p1",
        name = "Cedar Lane",
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
        jobCount = 1,
        lastServiceAt = null,
    )

    private fun job() = CustomerJob(
        id = "j1",
        jobNumber = 1042,
        title = "HVAC Maintenance",
        status = JobStatus.NEW,
        address = null,
        scheduledStart = null,
        technicians = emptyList(),
    )

    private fun customer(
        id: String = "c1",
        displayName: String = "Customer",
        type: CustomerType = CustomerType.INDIVIDUAL,
        status: CustomerStatus = CustomerStatus.ACTIVE,
        createdAt: String = "2026-01-01T00:00:00Z",
        propertyCount: Int = 0,
        jobCount: Int = 0,
    ) = Customer(
        id = id,
        organizationId = "org-1",
        type = type,
        displayName = displayName,
        email = null,
        phone = null,
        billingEmail = null,
        billingPhone = null,
        notes = null,
        status = status,
        createdAt = createdAt,
        updatedAt = "2026-01-01T00:00:00Z",
        propertyCount = propertyCount,
        jobCount = jobCount,
    )
}

/** A [CustomersRepository] that answers with fixed results and counts reads. */
private class RecordingCustomersRepository(
    var result: CustomersResult = CustomersResult.Success(emptyList()),
    var detailResult: CustomerDetailResult =
        CustomerDetailResult.Failure(CustomersFailureReason.UNEXPECTED),
    var createResult: PropertyCreateResult =
        PropertyCreateResult.Failure(CustomersFailureReason.UNEXPECTED),
    var updateResult: CustomerUpdateResult =
        CustomerUpdateResult.Failure(CustomersFailureReason.UNEXPECTED),
) : CustomersRepository {

    var reads: Int = 0
    var detailReads: Int = 0
    var propertyCreates: Int = 0
    var updates: Int = 0
    var lastFilters: CustomerFilters? = null
    var lastDetailCustomerId: String? = null
    var lastCreateRequest: CreatePropertyRequest? = null
    var lastUpdateCustomerId: String? = null
    var lastUpdateRequest: UpdateCustomerRequest? = null

    override suspend fun listCustomers(filters: CustomerFilters): CustomersResult {
        reads += 1
        lastFilters = filters
        return result
    }

    override suspend fun loadCustomerDetail(customerId: String): CustomerDetailResult {
        detailReads += 1
        lastDetailCustomerId = customerId
        return detailResult
    }

    override suspend fun createProperty(
        customerId: String,
        request: CreatePropertyRequest,
    ): PropertyCreateResult {
        propertyCreates += 1
        lastDetailCustomerId = customerId
        lastCreateRequest = request
        return createResult
    }

    override suspend fun createCustomer(request: CreateCustomerRequest): CustomerCreateResult =
        CustomerCreateResult.Failure(CustomersFailureReason.UNEXPECTED)

    override suspend fun createContact(
        customerId: String,
        request: CreateCustomerContactRequest,
    ): ContactCreateResult = ContactCreateResult.Failure(CustomersFailureReason.UNEXPECTED)

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
