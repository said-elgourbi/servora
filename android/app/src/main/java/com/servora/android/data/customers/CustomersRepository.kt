package com.servora.android.data.customers

import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerCompany
import com.servora.android.domain.model.CustomerContact
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerIndividual
import com.servora.android.domain.model.CustomerJob
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.CustomerJobTechnician
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.PropertyStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Reads organization-owned customers from the backend. */
interface CustomersRepository {
    /**
     * Returns the signed-in organization's customers narrowed by [filters], or why they could not
     * be read. The default filter is the list's default view: active customers only.
     *
     * A list the API cannot be reached for is served from the last answer it reported for the same
     * filter (`offline-first-architecture.md` §2).
     */
    suspend fun listCustomers(filters: CustomerFilters = CustomerFilters()): CustomersResult

    /**
     * Returns one customer's detail with its Property and Job projections (`BR-081`), or why it
     * could not be read.
     *
     * The projections are the backend's, so this read does not decide which Visit or Property value
     * the screen shows; it only carries what the API derived (`BR-041`).
     */
    suspend fun loadCustomerDetail(customerId: String): CustomerDetailResult

    /**
     * Creates an organization-owned Property for [customerId] and relates it to that customer
     * (`BR-049`, `BR-050`), or reports why it could not be created.
     *
     * The backend authorizes the write and owns the tenant scope, the stored country and the
     * customer relationship, so this call never names an organization and never sends a country
     * (`BR-001`, `BR-007`).
     */
    suspend fun createProperty(
        customerId: String,
        request: CreatePropertyRequest,
    ): PropertyCreateResult

    /**
     * Creates an organization-owned customer (`BR-023`) and reports the created customer's id.
     *
     * The backend authorizes the write and owns the tenant scope, so this call never names an
     * organization (`BR-001`, `BR-007`).
     */
    suspend fun createCustomer(request: CreateCustomerRequest): CustomerCreateResult

    /**
     * Records a contact on [customerId] (`BR-023`).
     *
     * The backend authorizes the write (`customers.edit`) and resolves the customer inside the
     * caller's organization, so a customer the organization does not own is reported as not found
     * (`BR-001`).
     */
    suspend fun createContact(
        customerId: String,
        request: CreateCustomerContactRequest,
    ): ContactCreateResult

    /**
     * Applies an edit to [customerId] (`BR-023`), converting it between individual and company when
     * [request] states another type (`BR-087`), or reports why it could not be applied.
     *
     * The backend authorizes the write with `customers.edit`, resolves the customer inside the
     * caller's organization and owns the conversion, so this call never names an organization and
     * never decides which subtype record is stored (`BR-001`, `BR-007`).
     */
    suspend fun updateCustomer(
        customerId: String,
        request: UpdateCustomerRequest,
    ): CustomerUpdateResult
}

/**
 * Default [CustomersRepository]: reads `GET /customers` with the current session and maps the
 * wire rows onto the domain model.
 *
 * The tenant, the filter and the visibility decision belong to the backend, which derives them
 * from the session and the validated query (`BR-001`, `BR-007`). This class therefore never names
 * an organization, so a modified client cannot widen its own scope.
 *
 * It is also where the customer feature meets the offline standard: the list and the detail are
 * served from the last answer the backend reported when the API cannot be reached (§2, §10).
 *
 * An access token is short-lived. When the backend refuses the one a read used (`401`), the read is
 * retried once with a session renewed through [SessionAuthenticator] (`BR-018`); when it cannot be
 * renewed the read reports its outcome rather than pretending to have data.
 */
class DefaultCustomersRepository @Inject constructor(
    private val api: CustomersApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val cache: CustomerDetailCache,
    private val listCache: CustomerListCache,
    private val subject: AuthenticatedSubject,
) : CustomersRepository {

    override suspend fun listCustomers(filters: CustomerFilters): CustomersResult {
        // The list is kept per subject like every other working-set read, so a read whose subject
        // cannot be read is refused rather than answered without local state (`§10`, `BR-001`).
        val subjectId = subject.current()
            ?: return CustomersResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return CustomersResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return when (val read = read(accessToken, allowRenewal = true, filters = filters)) {
            is ListRead.Answered -> {
                // A successful read replaces the local copy rather than being merged into it (§10).
                listCache.remember(subjectId, filters, read.rows)
                CustomersResult.Success(read.customers)
            }

            is ListRead.Failed ->
                // Only a failure that could not reach the backend falls back: a refusal is the
                // backend's answer, and a copy held on the device must never mask it
                // (`BR-007`, `BR-042`).
                if (read.reason.couldNotReachBackend()) {
                    listCache.reported(subjectId, filters)?.let { reported ->
                        CustomersResult.Success(reported, ReadSource.WORKING_SET)
                    } ?: CustomersResult.Failure(read.reason)
                } else {
                    CustomersResult.Failure(read.reason)
                }
        }
    }

    override suspend fun loadCustomerDetail(
        customerId: String,
    ): CustomerDetailResult {
        val subjectId = subject.current()
            ?: return CustomerDetailResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return CustomerDetailResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return when (
            val read = readDetail(accessToken, allowRenewal = true, customerId = customerId)
        ) {
            is DetailRead.Answered -> {
                // A successful read replaces the local copy rather than being merged into it (§10).
                cache.remember(subjectId, customerId, read.payload)
                CustomerDetailResult.Success(read.detail)
            }

            is DetailRead.Failed ->
                // Only a failure that could not reach the backend falls back: a refusal is the
                // backend's answer, and a copy held on the device must never mask it
                // (`BR-007`, `BR-042`).
                if (read.reason.couldNotReachBackend()) {
                    cache.reported(subjectId, customerId)?.let { reported ->
                        CustomerDetailResult.Success(reported, ReadSource.WORKING_SET)
                    } ?: CustomerDetailResult.Failure(read.reason)
                } else {
                    CustomerDetailResult.Failure(read.reason)
                }
        }
    }

    override suspend fun createProperty(
        customerId: String,
        request: CreatePropertyRequest,
    ): PropertyCreateResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyCreateResult.Failure(
                CustomersFailureReason.UNAUTHENTICATED,
            )

        return create(
            accessToken,
            allowRenewal = true,
            customerId = customerId,
            request = request,
        )
    }

    override suspend fun createCustomer(
        request: CreateCustomerRequest,
    ): CustomerCreateResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return CustomerCreateResult.Failure(
                CustomersFailureReason.UNAUTHENTICATED,
            )

        return createCustomer(
            accessToken,
            allowRenewal = true,
            request = request,
        )
    }

    override suspend fun createContact(
        customerId: String,
        request: CreateCustomerContactRequest,
    ): ContactCreateResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return ContactCreateResult.Failure(
                CustomersFailureReason.UNAUTHENTICATED,
            )

        return createContact(
            accessToken,
            allowRenewal = true,
            customerId = customerId,
            request = request,
        )
    }

    override suspend fun updateCustomer(
        customerId: String,
        request: UpdateCustomerRequest,
    ): CustomerUpdateResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return CustomerUpdateResult.Failure(
                CustomersFailureReason.UNAUTHENTICATED,
            )

        return updateCustomer(
            accessToken,
            allowRenewal = true,
            customerId = customerId,
            request = request,
        )
    }

    /**
     * Applies the edit once, renewing the session and retrying when the backend refuses the access
     * token.
     *
     * The retry carries the same body. A renewal happens before the payload is sent, so an edit the
     * backend already applied is never applied a second time (`BR-001`).
     */
    private suspend fun updateCustomer(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
        request: UpdateCustomerRequest,
    ): CustomerUpdateResult =
        try {
            api.updateCustomer(
                authorization = "Bearer $accessToken",
                id = customerId,
                request = request,
            )
            CustomerUpdateResult.Success
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryUpdateCustomer(accessToken, customerId, request)
            } else {
                CustomerUpdateResult.Failure(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            CustomerUpdateResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            CustomerUpdateResult.Failure(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the edit once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetryUpdateCustomer(
        rejectedToken: String,
        customerId: String,
        request: UpdateCustomerRequest,
    ): CustomerUpdateResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                updateCustomer(
                    renewal.accessToken,
                    allowRenewal = false,
                    customerId = customerId,
                    request = request,
                )

            SessionRenewal.Rejected ->
                CustomerUpdateResult.Failure(
                    CustomersFailureReason.UNAUTHENTICATED,
                )

            SessionRenewal.Unavailable ->
                CustomerUpdateResult.Failure(CustomersFailureReason.NETWORK)
        }

    /**
     * Writes the customer once, renewing the session and retrying when the backend refuses the
     * access token.
     *
     * The retry carries the same body. A customer create carries no client mutation identifier, so a
     * renewal happens before a second payload is sent; the form's own step tracking is what keeps a
     * confirmed create from being repeated (`BR-001`).
     */
    private suspend fun createCustomer(
        accessToken: String,
        allowRenewal: Boolean,
        request: CreateCustomerRequest,
    ): CustomerCreateResult =
        try {
            val created = when (request) {
                is CreateCustomerRequest.Individual ->
                    api.createIndividualCustomer(
                        authorization = "Bearer $accessToken",
                        request = request.request,
                    )

                is CreateCustomerRequest.Company ->
                    api.createCompanyCustomer(
                        authorization = "Bearer $accessToken",
                        request = request.request,
                    )
            }
            CustomerCreateResult.Success(created.customer.id)
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryCreateCustomer(accessToken, request)
            } else {
                CustomerCreateResult.Failure(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            CustomerCreateResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            CustomerCreateResult.Failure(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the customer create once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetryCreateCustomer(
        rejectedToken: String,
        request: CreateCustomerRequest,
    ): CustomerCreateResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                createCustomer(
                    renewal.accessToken,
                    allowRenewal = false,
                    request = request,
                )

            SessionRenewal.Rejected ->
                CustomerCreateResult.Failure(
                    CustomersFailureReason.UNAUTHENTICATED,
                )

            SessionRenewal.Unavailable ->
                CustomerCreateResult.Failure(CustomersFailureReason.NETWORK)
        }

    /**
     * Writes the contact once, renewing the session and retrying when the backend refuses the access
     * token.
     */
    private suspend fun createContact(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
        request: CreateCustomerContactRequest,
    ): ContactCreateResult =
        try {
            api.createContact(
                authorization = "Bearer $accessToken",
                id = customerId,
                request = request,
            )
            ContactCreateResult.Success
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryCreateContact(accessToken, customerId, request)
            } else {
                ContactCreateResult.Failure(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            ContactCreateResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ContactCreateResult.Failure(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the contact create once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetryCreateContact(
        rejectedToken: String,
        customerId: String,
        request: CreateCustomerContactRequest,
    ): ContactCreateResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                createContact(
                    renewal.accessToken,
                    allowRenewal = false,
                    customerId = customerId,
                    request = request,
                )

            SessionRenewal.Rejected ->
                ContactCreateResult.Failure(
                    CustomersFailureReason.UNAUTHENTICATED,
                )

            SessionRenewal.Unavailable ->
                ContactCreateResult.Failure(CustomersFailureReason.NETWORK)
        }

    /**
     * Writes the Property once, renewing the session and retrying when the backend refuses the
     * access token.
     *
     * A retry is safe because the create carries no client mutation identifier yet, so a renewal
     * happens before the payload is sent rather than after it may have been applied.
     */
    private suspend fun create(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
        request: CreatePropertyRequest,
    ): PropertyCreateResult =
        try {
            val property = api.createProperty(
                authorization = "Bearer $accessToken",
                id = customerId,
                request = request,
            )
            val created = property.toProperty()
            if (created == null) {
                PropertyCreateResult.Failure(CustomersFailureReason.UNEXPECTED)
            } else {
                PropertyCreateResult.Success(created)
            }
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryCreate(accessToken, customerId, request)
            } else {
                PropertyCreateResult.Failure(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            PropertyCreateResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            PropertyCreateResult.Failure(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the create once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetryCreate(
        rejectedToken: String,
        customerId: String,
        request: CreatePropertyRequest,
    ): PropertyCreateResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                create(
                    renewal.accessToken,
                    allowRenewal = false,
                    customerId = customerId,
                    request = request,
                )

            SessionRenewal.Rejected ->
                PropertyCreateResult.Failure(
                    CustomersFailureReason.UNAUTHENTICATED,
                )

            SessionRenewal.Unavailable ->
                PropertyCreateResult.Failure(CustomersFailureReason.NETWORK)
        }

    /**
     * Reads the detail, its Properties and its Jobs once, renewing the session and retrying when
     * the backend refuses the access token.
     *
     * The reads are one screen's worth of data, so they share a single outcome: a screen that
     * showed the header while its sections failed would present a partly loaded customer as if it
     * were complete (`BR-001`). The Property projection is the exception: it has its own capability
     * (`properties.view`, `BR-085`), and the screen omits that section entirely for a caller who
     * does not hold it, so a refused projection must not fail the rest of the customer.
     *
     * The customer's Properties are read twice, because the default projection excludes an
     * `ARCHIVED` one (`BR-081`) and an archived Property has to stay reachable to be restored
     * (`BR-082`).
     */
    private suspend fun readDetail(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
    ): DetailRead =
        try {
            val authorization = "Bearer $accessToken"
            val detail = api.detail(authorization, customerId)
            val properties = propertiesOrNone(
                authorization = authorization,
                customerId = customerId,
                status = PropertyStatus.ACTIVE.name,
            )
            // Archiving is how a Property leaves active use, so it is excluded from the active
            // projection and asked for explicitly here (`BR-082`, `BR-083`).
            val archivedProperties = propertiesOrNone(
                authorization = authorization,
                customerId = customerId,
                status = PropertyStatus.ARCHIVED.name,
            )
            val jobs = api.jobs(authorization, customerId)
            val payload = CustomerDetailPayload(
                detail = detail,
                properties = properties,
                archivedProperties = archivedProperties,
                jobs = jobs,
            )
            val mapped = detail.toDetail(properties, archivedProperties, jobs)
            if (mapped == null) {
                DetailRead.Failed(CustomersFailureReason.UNEXPECTED)
            } else {
                DetailRead.Answered(payload = payload, detail = mapped)
            }
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryDetail(accessToken, customerId)
            } else {
                DetailRead.Failed(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            DetailRead.Failed(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            DetailRead.Failed(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the detail read once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetryDetail(
        rejectedToken: String,
        customerId: String,
    ): DetailRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                readDetail(
                    renewal.accessToken,
                    allowRenewal = false,
                    customerId = customerId,
                )

            SessionRenewal.Rejected ->
                DetailRead.Failed(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                DetailRead.Failed(CustomersFailureReason.NETWORK)
        }

    /**
     * Reads one of the customer's Property projections, or an empty list when the caller's session
     * may not view Properties (`properties.view`, `BR-085`).
     *
     * The projection is optional to the customer detail. A caller without the capability is refused
     * with `403`, which must not turn the header, the contacts or the Jobs into a failed read; the
     * screen independently omits the Properties section for that caller (`BR-007`, `BR-011`). Any
     * other refusal still propagates so it is not mistaken for "no Properties".
     */
    private suspend fun propertiesOrNone(
        authorization: String,
        customerId: String,
        status: String,
    ): List<CustomerPropertyDto> =
        try {
            api.properties(authorization, customerId, status)
        } catch (failure: HttpException) {
            if (failure.code() == HTTP_FORBIDDEN) {
                emptyList()
            } else {
                throw failure
            }
        }

    /**
     * Reads the list once with [accessToken], renewing the session and retrying when allowed.
     *
     * The wire rows are kept with the mapped customers so a successful read can replace the answer
     * held in the working set without a second mapping step: the offline read then maps the same rows
     * with the same function (`BR-041`).
     */
    private suspend fun read(
        accessToken: String,
        allowRenewal: Boolean,
        filters: CustomerFilters,
    ): ListRead =
        try {
            val rows = api.list(
                authorization = "Bearer $accessToken",
                status = filters.status.name,
                jobs = filters.jobs.name,
            )
            val customers = rows.toCustomers()
            if (customers == null) {
                // A row this build cannot represent is a contract mismatch, not a missing value.
                ListRead.Failed(CustomersFailureReason.UNEXPECTED)
            } else {
                ListRead.Answered(rows = rows, customers = customers)
            }
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetry(accessToken, filters)
            } else {
                ListRead.Failed(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            ListRead.Failed(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ListRead.Failed(CustomersFailureReason.UNEXPECTED)
        }

    /**
     * Retries the read once with a renewed session, or reports why it could not be renewed.
     *
     * A renewal that never reached the backend is reported as [CustomersFailureReason.NETWORK]
     * rather than as an authentication result, so connectivity is not shown as a credential
     * problem.
     */
    private suspend fun renewAndRetry(
        rejectedToken: String,
        filters: CustomerFilters,
    ): ListRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                read(renewal.accessToken, allowRenewal = false, filters = filters)

            SessionRenewal.Rejected ->
                ListRead.Failed(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                ListRead.Failed(CustomersFailureReason.NETWORK)
        }

    private fun HttpException.toFailureReason(): CustomersFailureReason =
        when {
            code() == HTTP_UNAUTHORIZED -> CustomersFailureReason.UNAUTHENTICATED
            code() == HTTP_FORBIDDEN -> CustomersFailureReason.FORBIDDEN
            code() == HTTP_UNPROCESSABLE -> CustomersFailureReason.VALIDATION
            code() == HTTP_BAD_REQUEST -> CustomersFailureReason.VALIDATION
            code() >= HTTP_SERVER_ERROR -> CustomersFailureReason.SERVER
            else -> CustomersFailureReason.UNEXPECTED
        }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * Maps the list read's wire rows onto the domain, or `null` when this build cannot represent one of
 * them.
 *
 * A row the build cannot represent fails the whole read rather than being dropped silently, and the
 * working set holds the same wire rows and maps them with this same function, so an offline read
 * cannot describe the list differently from an online one (`BR-041`, `BR-042`).
 */
internal fun List<CustomerDto>.toCustomers(): List<Customer>? {
    val customers = map { it.toCustomer() }
    return if (customers.any { it == null }) null else customers.filterNotNull()
}

/** Maps a wire row onto the domain customer, or `null` when this build cannot represent it. */
private fun CustomerDto.toCustomer(): Customer? {
    val customerType = CustomerType.entries.firstOrNull { it.name == type } ?: return null
    val customerStatus = CustomerStatus.entries.firstOrNull { it.name == status } ?: return null
    return Customer(
        id = id,
        organizationId = organizationId,
        type = customerType,
        displayName = displayName,
        email = email,
        phone = phone,
        billingEmail = billingEmail,
        billingPhone = billingPhone,
        notes = notes,
        status = customerStatus,
        createdAt = createdAt,
        updatedAt = updatedAt,
        propertyCount = propertyCount,
        jobCount = jobCount,
    )
}

/**
 * Maps the detail read onto the domain, or `null` when this build cannot represent a value it
 * carries.
 *
 * A Job whose status code this build does not know fails the whole read rather than being dropped
 * silently, which is the same contract-mismatch rule the list follows (`BR-042`). The working set
 * holds the same wire answers and maps them with this same function, so an offline read cannot
 * describe the customer differently from an online one (`BR-041`).
 */
internal fun CustomerDetailDto.toDetail(
    properties: List<CustomerPropertyDto>,
    archivedProperties: List<CustomerPropertyDto>,
    jobs: List<CustomerJobDto>,
): CustomerDetail? {
    val domainCustomer = customer.toCustomer() ?: return null
    val domainJobs = jobs.map { it.toJob() ?: return null }
    val domainProperties = properties.map { it.toProperty() ?: return null }
    val domainArchivedProperties = archivedProperties.map { it.toProperty() ?: return null }
    return CustomerDetail(
        customer = domainCustomer,
        individual = individual?.toIndividual(),
        company = company?.toCompany(),
        contacts = contacts.map { it.toContact() },
        properties = domainProperties,
        jobs = domainJobs,
        archivedProperties = domainArchivedProperties,
    )
}

private fun CustomerIndividualDto.toIndividual(): CustomerIndividual =
    CustomerIndividual(
        customerId = customerId,
        firstName = firstName,
        lastName = lastName,
        dateOfBirth = dateOfBirth,
    )

private fun CustomerCompanyDto.toCompany(): CustomerCompany =
    CustomerCompany(
        customerId = customerId,
        legalName = legalName,
        businessName = businessName,
        taxNumber = taxNumber,
    )

private fun CustomerContactDto.toContact(): CustomerContact =
    CustomerContact(
        id = id,
        customerId = customerId,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        role = role,
        isPrimary = isPrimary,
        isBillingContact = isBillingContact,
        isJobContact = isJobContact,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun CustomerPropertyDto.toProperty(): CustomerProperty? {
    val propertyStatus =
        PropertyStatus.entries.firstOrNull { it.name == status } ?: return null
    return CustomerProperty(
        id = id,
        name = name,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        province = province,
        postalCode = postalCode,
        country = country,
        jobCount = jobCount,
        lastServiceAt = lastServiceAt,
        status = propertyStatus,
    )
}

private fun CustomerJobDto.toJob(): CustomerJob? {
    val jobStatus = JobStatus.entries.firstOrNull { it.name == status } ?: return null
    return CustomerJob(
        id = id,
        jobNumber = jobNumber,
        title = title,
        status = jobStatus,
        address = propertyAddress?.toAddress(),
        scheduledStart = scheduledStart,
        technicians = technicians.map {
            CustomerJobTechnician(
                membershipId = it.membershipId,
                name = it.name,
                roleCode = it.roleCode,
            )
        },
    )
}

private fun CustomerJobAddressDto.toAddress(): CustomerJobAddress =
    CustomerJobAddress(
        propertyName = propertyName,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        province = province,
        postalCode = postalCode,
        country = country,
    )

/** The outcome of composing the customer detail read, before the caller decides what to report. */
private sealed interface DetailRead {
    /** The API answered all four reads; [detail] is the mapped projection. */
    data class Answered(
        val payload: CustomerDetailPayload,
        val detail: CustomerDetail,
    ) : DetailRead

    /** The read failed; [reason] decides whether the last reported answer may be served instead. */
    data class Failed(val reason: CustomersFailureReason) : DetailRead
}

/** The outcome of composing the customer list read, before the caller decides what to report. */
private sealed interface ListRead {
    /**
     * The API answered; [rows] are its rows as received, and [customers] is the mapped list.
     *
     * Both are kept so the caller can replace the answer held in the working set without mapping it
     * a second time (`BR-041`).
     */
    data class Answered(
        val rows: List<CustomerDto>,
        val customers: List<Customer>,
    ) : ListRead

    /** The read failed; [reason] decides whether the last reported answer may be served instead. */
    data class Failed(val reason: CustomersFailureReason) : ListRead
}
