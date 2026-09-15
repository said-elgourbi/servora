package com.servora.android.data.customers

import com.servora.android.data.offline.InMemoryWorkingSetStore
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.offline.WorkingSetEntityTypes
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerJobFilter
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerStatusFilter
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.PropertyStatus
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
        val repository = repository(api, authenticator(accessToken = "access-token"))

        repository.listCustomers()

        assertEquals("ACTIVE", api.lastStatus)
        assertEquals("ALL", api.lastJobs)
    }

    @Test
    fun `sends no constraint when the filter is unconstrained`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
        val repository = repository(api, authenticator)

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
            assertFailure(repository(api, authenticator).listCustomers()),
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
            assertFailure(repository(api, authenticator).listCustomers()),
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
            assertFailure(repository(api, authenticator).listCustomers()),
        )
        assertEquals(1, api.calls)
    }

    @Test
    fun `fails without a session and never calls the backend`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = repository(api, authenticator(accessToken = null))

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
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
    fun `asks for the archived Property projection separately and carries it`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            detailAnswer = { detailDto() },
            propertiesAnswer = { listOf(propertyDto()) },
            archivedPropertiesAnswer = { listOf(propertyDto(id = "p2", status = "ARCHIVED")) },
            jobsAnswer = { listOf(jobDto()) },
        )
        val repository = repository(api, authenticator(accessToken = "access-token"))

        val detail = assertDetailSuccess(repository.loadCustomerDetail("c1"))

        // The default projection excludes an archived Property (`BR-081`), so it is asked for
        // explicitly and carried separately; without it the detail has no way to reach a Restore.
        assertEquals(listOf("ACTIVE", "ARCHIVED"), api.propertyStatuses)
        assertEquals(listOf("p1"), detail.properties.map { it.id })
        assertEquals(listOf("p2"), detail.archivedProperties.map { it.id })
        assertEquals(PropertyStatus.ARCHIVED, detail.archivedProperties.single().status)
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
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
            repository(api, authenticator).loadCustomerDetail("c1"),
        )

        assertEquals(1, authenticator.renewals)
        assertEquals("Bearer fresh-token", api.lastAuthorization)
    }

    @Test
    fun `fails the detail read without a session and never calls the backend`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = repository(api, authenticator(accessToken = null))

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
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
        val repository = repository(api, authenticator(accessToken = null))

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
        val repository = repository(api, authenticator(accessToken = "access-token"))

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
            repository(api, authenticator)
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

    @Test
    fun `serves the last reported detail when the API cannot be reached`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() }, detailAnswer = { detailDto() })
        val repository = repository(api, authenticator(accessToken = "access-token"))
        repository.loadCustomerDetail("c1")
        api.failures += IOException()

        val result = repository.loadCustomerDetail("c1")

        val success = result as CustomerDetailResult.Success
        assertEquals(ReadSource.WORKING_SET, success.source)
        assertEquals("c1", success.detail.customer.id)
    }

    @Test
    fun `reports the network failure when nothing was reported yet`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() }, detailAnswer = { detailDto() })
        val repository = repository(api, authenticator(accessToken = "access-token"))
        api.failures += IOException()

        val result = repository.loadCustomerDetail("c1")

        assertEquals(CustomersFailureReason.NETWORK, assertDetailFailure(result))
    }

    @Test
    fun `does not serve the local copy when the API refuses the read`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() }, detailAnswer = { detailDto() })
        val repository = repository(api, authenticator(accessToken = "access-token"))
        repository.loadCustomerDetail("c1")
        // A refusal is the backend's answer, so the copy held on the device must not be shown in its
        // place (`BR-007`, `BR-042`).
        api.failures += httpFailure(403)

        val result = repository.loadCustomerDetail("c1")

        assertEquals(CustomersFailureReason.FORBIDDEN, assertDetailFailure(result))
    }

    @Test
    fun `replaces the local copy with the answer of a later read`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            detailAnswer = { detailDto(displayName = "Martha Reynolds") },
        )
        val repository = repository(api, authenticator(accessToken = "access-token"))
        repository.loadCustomerDetail("c1")
        api.detailAnswer = { detailDto(displayName = "Martha Reynolds-Smith") }
        repository.loadCustomerDetail("c1")
        api.failures += IOException()

        val result = repository.loadCustomerDetail("c1")

        val success = result as CustomerDetailResult.Success
        assertEquals(ReadSource.WORKING_SET, success.source)
        assertEquals("Martha Reynolds-Smith", success.detail.customer.displayName)
    }

    @Test
    fun `stores the rows the backend reported in the working set`() = runTest {
        val api = FakeCustomersApi(answer = { listOf(customerDto(id = "c1")) })
        val workingSet = InMemoryWorkingSetStore()

        assertSuccess(
            repository(api, authenticator(), workingSet = workingSet).listCustomers(),
        )

        val stored = workingSet.stored.single()
        assertEquals("user-1", stored.subjectId)
        assertEquals(WorkingSetEntityTypes.CUSTOMER_LIST, stored.entityType)
        // The filter is the identity of the answer: a row read under another filter is not this
        // read's answer (`BR-001`).
        assertEquals(CustomerFilters().cacheKey(), stored.entityId)
        assertEquals(
            listOf("c1"),
            Json.decodeFromString(CustomerListPayload.serializer(), stored.payload)
                .rows
                .map { it.id },
        )
    }

    @Test
    fun `serves the last reported list when the API cannot be reached`() = runTest {
        val api = FakeCustomersApi(
            answer = { listOf(customerDto(id = "c1"), customerDto(id = "c2")) },
        )
        val repository = repository(api, authenticator())
        assertSuccess(repository.listCustomers())
        api.failures += IOException()

        val result = repository.listCustomers()

        val success = result as CustomersResult.Success
        assertEquals(ReadSource.WORKING_SET, success.source)
        assertEquals(listOf("c1", "c2"), success.customers.map { it.id })
    }

    @Test
    fun `serves the last reported list when the backend fails`() = runTest {
        val api = FakeCustomersApi(answer = { listOf(customerDto(id = "c1")) })
        val repository = repository(api, authenticator())
        assertSuccess(repository.listCustomers())
        api.failures += httpFailure(503)

        val success = repository.listCustomers() as CustomersResult.Success

        assertEquals(ReadSource.WORKING_SET, success.source)
        assertEquals(listOf("c1"), success.customers.map { it.id })
    }

    @Test
    fun `reports the network failure for the list when nothing was reported yet`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        api.failures += IOException("offline")

        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(repository(api, authenticator()).listCustomers()),
        )
    }

    @Test
    fun `does not serve the local rows when the API refuses the list`() = runTest {
        val api = FakeCustomersApi(answer = { listOf(customerDto(id = "c1")) })
        val repository = repository(api, authenticator())
        assertSuccess(repository.listCustomers())
        // A refusal is the backend's answer, so the rows held on the device must not be shown in its
        // place (`BR-007`, `BR-042`).
        api.failures += httpFailure(403)

        assertEquals(
            CustomersFailureReason.FORBIDDEN,
            assertFailure(repository.listCustomers()),
        )
    }

    @Test
    fun `does not serve another subject's rows`() = runTest {
        val api = FakeCustomersApi(answer = { listOf(customerDto(id = "c1")) })
        val workingSet = InMemoryWorkingSetStore()
        assertSuccess(repository(api, authenticator(), workingSet).listCustomers())
        api.failures += IOException()

        val otherSubject = repository(
            api,
            authenticator(),
            workingSet = workingSet,
            subjectId = "user-2",
        )

        assertEquals(CustomersFailureReason.NETWORK, assertFailure(otherSubject.listCustomers()))
    }

    @Test
    fun `does not answer one filter with the list the backend reported for another`() = runTest {
        val api = FakeCustomersApi(answer = { listOf(customerDto(id = "c1")) })
        val repository = repository(api, authenticator())
        // The default view is active customers only; the unconstrained list the user clears to is a
        // different answer (`BR-001`).
        assertSuccess(repository.listCustomers())
        api.failures += IOException()

        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(repository.listCustomers(CustomerFilters.Unconstrained)),
        )
    }

    @Test
    fun `replaces the local rows with the answer of a later read`() = runTest {
        val api = FakeCustomersApi(answer = { listOf(customerDto(id = "c1")) })
        val repository = repository(api, authenticator())
        assertSuccess(repository.listCustomers())
        api.answer = { listOf(customerDto(id = "c1"), customerDto(id = "c2")) }
        assertSuccess(repository.listCustomers())
        api.failures += IOException()

        val success = repository.listCustomers() as CustomersResult.Success

        assertEquals(ReadSource.WORKING_SET, success.source)
        assertEquals(listOf("c1", "c2"), success.customers.map { it.id })
    }

    private fun repositoryFailingCreateWith(failure: Throwable) =
        repository(
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

    private fun detailDto(displayName: String = "ABC Property Management") = CustomerDetailDto(
        customer = customerDto(
            displayName = displayName,
            propertyCount = 2,
            jobCount = 3,
        ),
        company = CustomerCompanyDto(customerId = "c1", legalName = "ABC Property Management Ltd."),
    )

    @Test
    fun `creates an individual customer and reports the created id`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            createCustomerAnswer = { createdCustomerDto(id = "created-1") },
        )
        val repository =
            repository(api, authenticator(accessToken = "access-token"))

        val result = repository.createCustomer(
            CreateCustomerRequest.Individual(
                CreateIndividualCustomerRequest(
                    type = "INDIVIDUAL",
                    displayName = "John Smith",
                    individual = IndividualCustomerPayload(
                        firstName = "John",
                        lastName = "Smith",
                    ),
                ),
            ),
        )

        assertEquals(CustomerCreateResult.Success("created-1"), result)
        assertEquals("Bearer access-token", api.lastAuthorization)
    }

    @Test
    fun `creates a company customer and leaves its optional fields absent`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            createCustomerAnswer = { createdCustomerDto(id = "created-2") },
        )
        val repository =
            repository(api, authenticator(accessToken = "access-token"))

        repository.createCustomer(
            CreateCustomerRequest.Company(
                CreateCompanyCustomerRequest(
                    type = "COMPANY",
                    displayName = "ABC Property Management",
                    company = CompanyCustomerPayload(legalName = "ABC Property Management"),
                ),
            ),
        )

        val request = requireNotNull(api.lastCustomerRequest)
        assertTrue(request is CreateCustomerRequest.Company)
        val company = request as CreateCustomerRequest.Company
        assertNull(company.request.email)
        assertNull(company.request.notes)
    }

    @Test
    fun `renews the session once when the customer create is refused and retries it`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            createCustomerAnswer = { createdCustomerDto(id = "created-1") },
        )
        api.failures += httpFailure(401)
        val authenticator = authenticator(
            accessToken = "expired-token",
            renewal = SessionRenewal.Renewed(accessToken = "fresh-token"),
        )

        val result = repository(api, authenticator).createCustomer(
            CreateCustomerRequest.Individual(
                CreateIndividualCustomerRequest(
                    type = "INDIVIDUAL",
                    displayName = "John Smith",
                    individual = IndividualCustomerPayload(
                        firstName = "John",
                        lastName = "Smith",
                    ),
                ),
            ),
        )

        assertEquals(CustomerCreateResult.Success("created-1"), result)
        assertEquals(1, authenticator.renewals)
        assertEquals("Bearer fresh-token", api.lastAuthorization)
    }

    @Test
    fun `creates a contact on the customer and reports success`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            createContactAnswer = { contactDto() },
        )
        val repository =
            repository(api, authenticator(accessToken = "access-token"))

        val result = repository.createContact(
            customerId = "c1",
            request = CreateCustomerContactRequest(
                firstName = "John",
                lastName = "Smith",
                isPrimary = true,
            ),
        )

        assertEquals(ContactCreateResult.Success, result)
        assertEquals("c1", api.lastContactId)
        assertTrue(api.lastContactRequest?.isPrimary == true)
    }

    @Test
    fun `classifies a rejected contact payload as validation`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        api.failures += httpFailure(400)
        val repository =
            repository(api, authenticator(accessToken = "access-token"))

        val result = repository.createContact(
            customerId = "c1",
            request = CreateCustomerContactRequest(firstName = "John", lastName = "Smith"),
        )

        assertEquals(ContactCreateResult.Failure(CustomersFailureReason.VALIDATION), result)
    }

    @Test
    fun `sends the edit with the session token and reports success`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            updateCustomerAnswer = { createdCustomerDto(id = "c1") },
        )
        val repository =
            repository(api, authenticator(accessToken = "access-token"))

        val result = repository.updateCustomer(
            customerId = "c1",
            request = UpdateCustomerRequest(
                type = "COMPANY",
                displayName = "Cedar Property Management",
                company = CompanyCustomerPayload(legalName = "Cedar Property Management Ltd."),
                status = "ACTIVE",
            ),
        )

        assertEquals(CustomerUpdateResult.Success, result)
        assertEquals("c1", api.lastUpdateId)
        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals("COMPANY", api.lastUpdateRequest?.type)
        assertEquals(
            "Cedar Property Management Ltd.",
            api.lastUpdateRequest?.company?.legalName,
        )
        // A conversion states one kind, so the other subtype payload is never sent alongside it.
        assertNull(api.lastUpdateRequest?.individual)
    }

    @Test
    fun `classifies a rejected edit payload as validation`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        api.failures += httpFailure(400)
        val repository =
            repository(api, authenticator(accessToken = "access-token"))

        val result = repository.updateCustomer(
            customerId = "c1",
            request = UpdateCustomerRequest(type = "COMPANY", displayName = "Cedar"),
        )

        assertEquals(CustomerUpdateResult.Failure(CustomersFailureReason.VALIDATION), result)
    }

    @Test
    fun `renews the session once when the edit is refused and retries it`() = runTest {
        val api = FakeCustomersApi(
            answer = { emptyList() },
            updateCustomerAnswer = { createdCustomerDto(id = "c1") },
        )
        api.failures += httpFailure(401)
        val authenticator = authenticator(
            accessToken = "access-token",
            renewal = SessionRenewal.Renewed(accessToken = "fresh-token"),
        )
        val repository = repository(api, authenticator)

        val result = repository.updateCustomer(
            customerId = "c1",
            request = UpdateCustomerRequest(type = "INDIVIDUAL", displayName = "John Smith"),
        )

        assertEquals(CustomerUpdateResult.Success, result)
        assertEquals(1, authenticator.renewals)
        assertEquals("Bearer fresh-token", api.lastAuthorization)
    }

    @Test
    fun `fails the edit without a session and never calls the backend`() = runTest {
        val api = FakeCustomersApi(answer = { emptyList() })
        val repository = repository(api, authenticator(accessToken = null))

        val result = repository.updateCustomer(
            customerId = "c1",
            request = UpdateCustomerRequest(type = "INDIVIDUAL", displayName = "John Smith"),
        )

        assertEquals(
            CustomerUpdateResult.Failure(CustomersFailureReason.UNAUTHENTICATED),
            result,
        )
        assertEquals(0, api.calls)
    }

    private fun propertyDto(
        id: String = "p1",
        status: String = "ACTIVE",
    ) = CustomerPropertyDto(
        id = id,
        name = "Cedar Lane Building",
        addressLine1 = "987 Cedar Lane",
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
        status = status,
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

    /** The repository under test, with the local store its offline fallback uses. */
    private fun repository(
        api: CustomersApi,
        authenticator: SessionAuthenticator,
        workingSet: InMemoryWorkingSetStore = InMemoryWorkingSetStore(),
        subjectId: String? = "user-1",
    ): DefaultCustomersRepository =
        DefaultCustomersRepository(
            api = api,
            sessionAuthenticator = authenticator,
            cache = CustomerDetailCache(
                workingSet = workingSet,
                json = json(),
                clock = clock(),
            ),
            listCache = CustomerListCache(
                workingSet = workingSet,
                json = json(),
                clock = clock(),
            ),
            subject = FakeAuthenticatedSubject(subjectId),
        )

    private fun json(): Json = Json { ignoreUnknownKeys = true }

    private fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)

    private fun repositoryFailingWith(failure: Throwable) =
        repository(
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

    private fun createdCustomerDto(id: String) = CreatedCustomerDto(
        customer = customerDto(id = id),
    )

    private fun contactDto() = CustomerContactDto(
        id = "ct1",
        customerId = "c1",
        firstName = "John",
        lastName = "Smith",
        isPrimary = true,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )
}

/** API double standing in for the generated Retrofit implementation. */
private class FakeCustomersApi(
    var answer: suspend () -> List<CustomerDto>,
    var detailAnswer: suspend () -> CustomerDetailDto = {
        error("the detail was not scripted for this test")
    },
    private val propertiesAnswer: suspend () -> List<CustomerPropertyDto> = { emptyList() },
    private val archivedPropertiesAnswer: suspend () -> List<CustomerPropertyDto> = { emptyList() },
    private val jobsAnswer: suspend () -> List<CustomerJobDto> = { emptyList() },
    private val createPropertyAnswer: suspend () -> CustomerPropertyDto = {
        error("the property create was not scripted for this test")
    },
    private val createCustomerAnswer: suspend () -> CreatedCustomerDto = {
        error("the customer create was not scripted for this test")
    },
    private val createContactAnswer: suspend () -> CustomerContactDto = {
        error("the contact create was not scripted for this test")
    },
    private val updateCustomerAnswer: suspend () -> CreatedCustomerDto = {
        error("the customer edit was not scripted for this test")
    },
) : CustomersApi {

    var lastAuthorization: String? = null
    var lastStatus: String? = null
    var lastJobs: String? = null
    var lastDetailId: String? = null
    var lastCreateRequest: CreatePropertyRequest? = null
    var lastCustomerRequest: CreateCustomerRequest? = null
    var lastContactRequest: CreateCustomerContactRequest? = null
    var lastContactId: String? = null
    var lastUpdateId: String? = null
    var lastUpdateRequest: UpdateCustomerRequest? = null
    var calls: Int = 0

    /** The lifecycle projection each Property read asked for, in call order. */
    val propertyStatuses = mutableListOf<String?>()

    /** Failures consumed by successive calls, so a test can script a sequence of answers. */
    val failures = mutableListOf<Throwable>()

    /**
     * Fails only the Property projection.
     *
     * The shared [failures] queue is consumed by whichever call runs next, so it cannot script a
     * refusal of one of the detail read's Property calls.
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
        status: String?,
    ): List<CustomerPropertyDto> {
        calls += 1
        lastAuthorization = authorization
        lastDetailId = id
        propertyStatuses += status
        failures.removeFirstOrNull()?.let { throw it }
        propertiesFailure?.let { throw it }
        return if (status == PropertyStatus.ARCHIVED.name) {
            archivedPropertiesAnswer()
        } else {
            propertiesAnswer()
        }
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

    override suspend fun createIndividualCustomer(
        authorization: String,
        request: CreateIndividualCustomerRequest,
    ): CreatedCustomerDto {
        calls += 1
        lastAuthorization = authorization
        lastCustomerRequest = CreateCustomerRequest.Individual(request)
        failures.removeFirstOrNull()?.let { throw it }
        return createCustomerAnswer()
    }

    override suspend fun createCompanyCustomer(
        authorization: String,
        request: CreateCompanyCustomerRequest,
    ): CreatedCustomerDto {
        calls += 1
        lastAuthorization = authorization
        lastCustomerRequest = CreateCustomerRequest.Company(request)
        failures.removeFirstOrNull()?.let { throw it }
        return createCustomerAnswer()
    }

    override suspend fun createContact(
        authorization: String,
        id: String,
        request: CreateCustomerContactRequest,
    ): CustomerContactDto {
        calls += 1
        lastAuthorization = authorization
        lastContactId = id
        lastContactRequest = request
        failures.removeFirstOrNull()?.let { throw it }
        return createContactAnswer()
    }

    override suspend fun updateCustomer(
        authorization: String,
        id: String,
        request: UpdateCustomerRequest,
    ): CreatedCustomerDto {
        calls += 1
        lastAuthorization = authorization
        lastUpdateId = id
        lastUpdateRequest = request
        failures.removeFirstOrNull()?.let { throw it }
        return updateCustomerAnswer()
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
