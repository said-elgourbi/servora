package com.servora.android.data.customers

import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerJobFilter
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerStatusFilter
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.JobStatus
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * How [DefaultCustomersRepository] reads `GET /customers` and classifies its answers.
 *
 * The backend decides which customers exist and who may see them (`BR-001`, `BR-007`); these
 * tests cover only that the request carries the session, that a refused access token is renewed
 * once and retried, and that each HTTP answer is reported faithfully.
 */
class CustomersRepositoryTest {

    @Test
    fun `sends the current session token and maps the rows onto the domain model`() = runTest {
        val api = FakeCustomersApi(
            answer = {
                listOf(
                    customerDto(
                        id = "c1",
                        type = "INDIVIDUAL",
                        displayName = "Martha Reynolds",
                        propertyCount = 3,
                        jobCount = 5,
                    ),
                    customerDto(id = "c2", type = "COMPANY", displayName = "ABC Property Management"),
                )
            },
        )
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        val customers = assertSuccess(repository.listCustomers())

        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals(listOf("c1", "c2"), customers.map { it.id })
        assertEquals(CustomerType.INDIVIDUAL, customers[0].type)
        assertEquals(CustomerType.COMPANY, customers[1].type)
        assertEquals(CustomerStatus.ACTIVE, customers[0].status)
        assertEquals("martha@example.com", customers[0].email)
        assertEquals("555-123-4567", customers[0].phone)
        assertEquals(3, customers[0].propertyCount)
        assertEquals(5, customers[0].jobCount)
    }

    @Test
    fun `sends the filter codes the backend validates`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        repository.listCustomers(
            CustomerFilters(
                status = CustomerStatusFilter.INACTIVE,
                jobs = CustomerJobFilter.HAS_OVERDUE_VISITS,
            ),
        )

        assertEquals("INACTIVE", api.lastStatus)
        assertEquals("HAS_OVERDUE_VISITS", api.lastJobs)
    }

    @Test
    fun `sends the list's default active-only filter when none is given`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        repository.listCustomers()

        assertEquals("ACTIVE", api.lastStatus)
        assertEquals("ALL", api.lastJobs)
    }

    @Test
    fun `sends no constraint when the filter is unconstrained`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        repository.listCustomers(CustomerFilters.Unconstrained)

        assertEquals("ALL", api.lastStatus)
        assertEquals("ALL", api.lastJobs)
    }

    @Test
    fun `renews the session and retries once when the backend refuses the token`() = runTest {
        val api = FakeCustomersApi(answer = { listOf(customerDto(id = "c1")) })
        api.failures += httpFailure(401)
        val authenticator = authenticator(
            accessToken = "expired-token",
            renewal = SessionRenewal.Renewed(accessToken = "fresh-token"),
        )
        val repository = DefaultCustomersRepository(api, authenticator)

        val customers = assertSuccess(repository.listCustomers())

        assertEquals(listOf("c1"), customers.map { it.id })
        assertEquals(2, api.calls)
        assertEquals(1, authenticator.renewals)
        assertEquals("expired-token", authenticator.lastRejectedToken)
        assertEquals("Bearer fresh-token", api.lastAuthorization)
    }

    @Test
    fun `does not renew again when the retry is refused too`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        api.failures += listOf(httpFailure(401), httpFailure(401))
        val authenticator = authenticator(
            accessToken = "expired-token",
            renewal = SessionRenewal.Renewed(accessToken = "fresh-token"),
        )

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(DefaultCustomersRepository(api, authenticator).listCustomers()),
        )
        assertEquals(2, api.calls)
        assertEquals(1, authenticator.renewals)
    }

    @Test
    fun `reports unauthenticated when the refused token cannot be renewed`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        api.failures += httpFailure(401)
        val authenticator = authenticator(
            accessToken = "expired-token",
            renewal = SessionRenewal.Rejected,
        )

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(DefaultCustomersRepository(api, authenticator).listCustomers()),
        )
        assertEquals(1, api.calls)
    }

    @Test
    fun `reports a network failure when the renewal cannot reach the backend`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        api.failures += httpFailure(401)
        val authenticator = authenticator(
            accessToken = "expired-token",
            renewal = SessionRenewal.Unavailable,
        )

        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(DefaultCustomersRepository(api, authenticator).listCustomers()),
        )
        assertEquals(1, api.calls)
    }

    @Test
    fun `fails without a session and never calls the backend`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = null))

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(repository.listCustomers()),
        )
        assertEquals(0, api.calls)
    }

    @Test
    fun `classifies authorization failures`() = runTest {
        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(repositoryFailingWith(httpFailure(401)).listCustomers()),
        )
        assertEquals(
            CustomersFailureReason.FORBIDDEN,
            assertFailure(repositoryFailingWith(httpFailure(403)).listCustomers()),
        )
    }

    @Test
    fun `classifies transport, server and contract failures`() = runTest {
        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(repositoryFailingWith(IOException("offline")).listCustomers()),
        )
        assertEquals(
            CustomersFailureReason.SERVER,
            assertFailure(repositoryFailingWith(httpFailure(503)).listCustomers()),
        )
        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(
                repositoryFailingWith(SerializationException("bad body")).listCustomers(),
            ),
        )
    }

    @Test
    fun `reports a row this build cannot represent as unexpected`() = runTest {
        val api = FakeCustomersApi(answer = { listOf(customerDto(type = "FRANCHISE")) })
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        assertEquals(CustomersFailureReason.UNEXPECTED, assertFailure(repository.listCustomers()))
    }

    @Test
    fun `reads the detail, its properties and its jobs with the session token`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            detailAnswer = { detailDto() },
            propertiesAnswer = { listOf(propertyDto()) },
            jobsAnswer = { listOf(jobDto()) },
        )
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        val detail = assertDetailSuccess(repository.loadCustomerDetail("c1"))

        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals("c1", api.lastDetailId)
        assertEquals("ABC Property Management", detail.customer.displayName)
        assertEquals(2, detail.customer.propertyCount)
        assertEquals(3, detail.customer.jobCount)
        assertEquals(listOf("Cedar Lane Building"), detail.properties.map { it.name })
        assertEquals(4, detail.properties.single().jobCount)
        assertEquals(listOf(1042), detail.jobs.map { it.jobNumber })
        assertEquals(JobStatus.COMPLETED, detail.jobs.single().status)
        assertEquals(
            listOf("Mike Lead", "Sarah Tech"),
            detail.jobs.single().technicians.mapNotNull { it.name },
        )
        assertEquals("987 Cedar Lane", detail.jobs.single().address?.addressLine1)
    }

    @Test
    fun `omits the Property projection when the caller may not view Properties`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            detailAnswer = { detailDto() },
            propertiesAnswer = { listOf(propertyDto()) },
            jobsAnswer = { listOf(jobDto()) },
        )
        api.propertiesFailure = httpFailure(403)
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        val detail = assertDetailSuccess(repository.loadCustomerDetail("c1"))

        // The Property projection has its own capability (`properties.view`, `BR-085`), so its
        // refusal must not fail the header, the contacts or the Jobs. The screen omits the section
        // for that caller.
        assertEquals(emptyList<String>(), detail.properties.map { it.id })
        assertEquals("ABC Property Management", detail.customer.displayName)
        assertEquals(listOf(1042), detail.jobs.map { it.jobNumber })
    }

    @Test
    fun `reports a refused customer read as forbidden`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        api.failures += httpFailure(403)
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        // Only the optional Property projection tolerates a refusal; the customer itself does not.
        assertEquals(
            CustomersFailureReason.FORBIDDEN,
            assertDetailFailure(repository.loadCustomerDetail("c1")),
        )
    }

    @Test
    fun `fails the detail read when a Job carries a status this build cannot represent`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            detailAnswer = { detailDto() },
            jobsAnswer = { listOf(jobDto(status = "ARCHIVED")) },
        )
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertDetailFailure(repository.loadCustomerDetail("c1")),
        )
    }

    @Test
    fun `renews the session once when the detail read is refused and retries it`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            detailAnswer = { detailDto() },
        )
        api.failures += httpFailure(401)
        val authenticator = authenticator(
            accessToken = "expired-token",
            renewal = SessionRenewal.Renewed(accessToken = "fresh-token"),
        )

        assertDetailSuccess(
            DefaultCustomersRepository(api, authenticator).loadCustomerDetail("c1"),
        )

        assertEquals(1, authenticator.renewals)
        assertEquals("Bearer fresh-token", api.lastAuthorization)
    }

    @Test
    fun `fails the detail read without a session and never calls the backend`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = null))

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertDetailFailure(repository.loadCustomerDetail("c1")),
        )
        assertEquals(0, api.calls)
    }

    private fun assertDetailSuccess(result: CustomerDetailResult) = when (result) {
        is CustomerDetailResult.Success -> result.detail
        is CustomerDetailResult.Failure ->
            throw AssertionError("expected a detail, got ${result.reason}")
    }

    @Test
    fun `creates a Property with the session token and maps the returned row`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            createPropertyAnswer = { propertyDto() },
        )
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        val property = assertCreateSuccess(
            repository.createProperty("c1", createRequest()),
        )

        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals("c1", api.lastDetailId)
        assertEquals("Cedar Lane Building", property.name)
        assertEquals("QC", property.province)
        assertEquals("Canada", property.country)
        // The row values are the backend's, so the repository carries what the API answered.
        assertEquals(propertyDto().jobCount, property.jobCount)
        assertEquals(propertyDto().lastServiceAt, property.lastServiceAt)
        // The request carries only the authoritative Property fields; the country is the backend's.
        assertEquals("987 Cedar Lane", api.lastCreateRequest?.addressLine1)
        assertEquals("Suite 200", api.lastCreateRequest?.addressLine2)
        assertEquals("QC", api.lastCreateRequest?.province)
        assertEquals("H3A 2T6", api.lastCreateRequest?.postalCode)
    }

    @Test
    fun `fails the create without a session and never calls the backend`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = null))

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertCreateFailure(repository.createProperty("c1", createRequest())),
        )
        assertEquals(0, api.calls)
    }

    @Test
    fun `classifies a rejected create payload as validation`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        api.failures += httpFailure(422)
        val repository = DefaultCustomersRepository(api, authenticator(accessToken = "access-token"))

        assertEquals(
            CustomersFailureReason.VALIDATION,
            assertCreateFailure(repository.createProperty("c1", createRequest())),
        )
    }

    @Test
    fun `classifies the create failures a Property form reports`() = runTest {
        assertEquals(
            CustomersFailureReason.FORBIDDEN,
            assertCreateFailure(
                repositoryFailingCreateWith(httpFailure(403))
                    .createProperty("c1", createRequest()),
            ),
        )
        assertEquals(
            CustomersFailureReason.NETWORK,
            assertCreateFailure(
                repositoryFailingCreateWith(IOException("offline"))
                    .createProperty("c1", createRequest()),
            ),
        )
        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertCreateFailure(
                repositoryFailingCreateWith(SerializationException("bad body"))
                    .createProperty("c1", createRequest()),
            ),
        )
    }

    @Test
    fun `renews the session once when the create is refused and retries it`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            createPropertyAnswer = { propertyDto() },
        )
        api.failures += httpFailure(401)
        val authenticator = authenticator(
            accessToken = "expired-token",
            renewal = SessionRenewal.Renewed(accessToken = "fresh-token"),
        )

        assertCreateSuccess(
            DefaultCustomersRepository(api, authenticator)
                .createProperty("c1", createRequest()),
        )

        assertEquals(1, authenticator.renewals)
        assertEquals("Bearer fresh-token", api.lastAuthorization)
    }

    private fun createRequest() = CreatePropertyRequest(
        name = "Cedar Lane Building",
        addressLine1 = "987 Cedar Lane",
        addressLine2 = "Suite 200",
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
    )

    private fun assertCreateSuccess(result: PropertyCreateResult) = when (result) {
        is PropertyCreateResult.Success -> result.property
        is PropertyCreateResult.Failure ->
            throw AssertionError("expected a property, got ${result.reason}")
    }

    private fun assertCreateFailure(result: PropertyCreateResult): CustomersFailureReason =
        when (result) {
            is PropertyCreateResult.Failure -> result.reason
            is PropertyCreateResult.Success ->
                throw AssertionError("expected a failure, got a property")
        }

    private fun repositoryFailingCreateWith(failure: Throwable) =
        DefaultCustomersRepository(
            FakeCustomersApi(answer = { emptyList() }).apply { failures += failure },
            authenticator(accessToken = "access-token"),
        )

    private fun assertDetailFailure(
        result: CustomerDetailResult,
    ): CustomersFailureReason = when (result) {
        is CustomerDetailResult.Failure -> result.reason
        is CustomerDetailResult.Success ->
            throw AssertionError("expected a failure, got a detail")
    }

    private fun detailDto() = CustomerDetailDto(
        customer = customerDto(
            displayName = "ABC Property Management",
            propertyCount = 2,
            jobCount = 3,
        ),
        company = CustomerCompanyDto(customerId = "c1", legalName = "ABC Property Management Ltd."),
    )

    private fun propertyDto() = CustomerPropertyDto(
        id = "p1",
        name = "Cedar Lane Building",
        addressLine1 = "987 Cedar Lane",
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
        jobCount = 4,
        lastServiceAt = "2026-08-28T13:00:00Z",
    )

    private fun jobDto(status: String = "COMPLETED") = CustomerJobDto(
        id = "j1",
        jobNumber = 1042,
        title = "HVAC Maintenance",
        status = status,
        propertyId = "p1",
        propertyAddress = CustomerJobAddressDto(
            propertyName = "Cedar Lane Building",
            addressLine1 = "987 Cedar Lane",
            city = "Montreal",
            province = "QC",
            postalCode = "H3A 2T6",
            country = "Canada",
        ),
        scheduledStart = "2026-09-08T13:00:00Z",
        technicians = listOf(
            CustomerJobTechnicianDto(membershipId = "m1", name = "Mike Lead", roleCode = "LEAD"),
            CustomerJobTechnicianDto(
                membershipId = "m2",
                name = "Sarah Tech",
                roleCode = "TECHNICIAN",
            ),
        ),
    )

    private fun authenticator(
        accessToken: String? = "access-token",
        renewal: SessionRenewal = SessionRenewal.Rejected,
    ): FakeSessionAuthenticator =
        FakeSessionAuthenticator(accessToken = accessToken, renewal = renewal)

    private fun repositoryFailingWith(failure: Throwable) =
        DefaultCustomersRepository(
            FakeCustomersApi(answer = { throw failure }),
            authenticator(accessToken = "access-token"),
        )

    private fun assertSuccess(result: CustomersResult) = when (result) {
        is CustomersResult.Success -> result.customers
        is CustomersResult.Failure ->
            throw AssertionError("expected customers, got ${result.reason}")
    }

    private fun assertFailure(result: CustomersResult): CustomersFailureReason = when (result) {
        is CustomersResult.Failure -> result.reason
        is CustomersResult.Success -> throw AssertionError("expected a failure, got customers")
    }

    private fun httpFailure(status: Int) = HttpException(
        Response.error<List<CustomerDto>>(
            status,
            """{"statusCode":$status,"code":"UNKNOWN","message":"ignored"}"""
                .toResponseBody("application/json".toMediaType()),
        ),
    )

    private fun customerDto(
        id: String = "c1",
        type: String = "INDIVIDUAL",
        displayName: String = "Martha Reynolds",
        propertyCount: Int = 0,
        jobCount: Int = 0,
    ) = CustomerDto(
        id = id,
        organizationId = "org-1",
        type = type,
        displayName = displayName,
        email = "martha@example.com",
        phone = "555-123-4567",
        status = "ACTIVE",
        propertyCount = propertyCount,
        jobCount = jobCount,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )
}

/** API double standing in for the generated Retrofit implementation. */
private class FakeCustomersApi(
    private val answer: suspend () -> List<CustomerDto>,
    private val detailAnswer: suspend () -> CustomerDetailDto = {
        error("the detail was not scripted for this test")
    },
    private val propertiesAnswer: suspend () -> List<CustomerPropertyDto> = { emptyList() },
    private val jobsAnswer: suspend () -> List<CustomerJobDto> = { emptyList() },
    private val createPropertyAnswer: suspend () -> CustomerPropertyDto = {
        error("the property create was not scripted for this test")
    },
) : CustomersApi {

    var lastAuthorization: String? = null
    var lastStatus: String? = null
    var lastJobs: String? = null
    var lastDetailId: String? = null
    var lastCreateRequest: CreatePropertyRequest? = null
    var calls: Int = 0

    /** Failures consumed by successive calls, so a test can script a sequence of answers. */
    val failures = mutableListOf<Throwable>()

    /**
     * Fails only the Property projection.
     *
     * The shared [failures] queue is consumed by whichever call runs next, so it cannot script a
     * refusal of the second of the detail read's three calls.
     */
    var propertiesFailure: Throwable? = null

    override suspend fun list(
        authorization: String,
        status: String,
        jobs: String,
    ): List<CustomerDto> {
        calls += 1
        lastAuthorization = authorization
        lastStatus = status
        lastJobs = jobs
        failures.removeFirstOrNull()?.let { throw it }
        return answer()
    }

    override suspend fun detail(
        authorization: String,
        id: String,
    ): CustomerDetailDto {
        calls += 1
        lastAuthorization = authorization
        lastDetailId = id
        failures.removeFirstOrNull()?.let { throw it }
        return detailAnswer()
    }

    override suspend fun properties(
        authorization: String,
        id: String,
    ): List<CustomerPropertyDto> {
        calls += 1
        lastAuthorization = authorization
        lastDetailId = id
        failures.removeFirstOrNull()?.let { throw it }
        propertiesFailure?.let { throw it }
        return propertiesAnswer()
    }

    override suspend fun jobs(
        authorization: String,
        id: String,
    ): List<CustomerJobDto> {
        calls += 1
        lastAuthorization = authorization
        lastDetailId = id
        failures.removeFirstOrNull()?.let { throw it }
        return jobsAnswer()
    }

    override suspend fun createProperty(
        authorization: String,
        id: String,
        request: CreatePropertyRequest,
    ): CustomerPropertyDto {
        calls += 1
        lastAuthorization = authorization
        lastDetailId = id
        lastCreateRequest = request
        failures.removeFirstOrNull()?.let { throw it }
        return createPropertyAnswer()
    }
}

/** A [SessionAuthenticator] that answers with a scripted renewal and records what it was asked. */
private class FakeSessionAuthenticator(
    private val accessToken: String?,
    var renewal: SessionRenewal,
) : SessionAuthenticator {

    var renewals: Int = 0
    var lastRejectedToken: String? = null

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal {
        renewals += 1
        lastRejectedToken = rejectedToken
        return renewal
    }
}
