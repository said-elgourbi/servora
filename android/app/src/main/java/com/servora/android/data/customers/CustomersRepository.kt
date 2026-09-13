package com.servora.android.data.customers

import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerContact
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerJob
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.CustomerJobTechnician
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.JobStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Reads organization-owned customers from the backend. */
interface CustomersRepository {
    /**
     * Returns the signed-in organization's customers narrowed by [filters], or why they could not
     * be read. The default filter is the list's default view: active customers only.
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
}

/**
 * Default [CustomersRepository]: reads `GET /customers` with the current session and maps the
 * wire rows onto the domain model.
 *
 * The tenant, the filter and the visibility decision belong to the backend, which derives them
 * from the session and the validated query (`BR-001`, `BR-007`). This class therefore never names
 * an organization, so a modified client cannot widen its own scope.
 *
 * An access token is short-lived. When the backend refuses the one a read used (`401`), the read is
 * retried once with a session renewed through [SessionAuthenticator] (`BR-018`); when it cannot be
 * renewed the read reports its outcome rather than pretending to have data.
 */
class DefaultCustomersRepository @Inject constructor(
    private val api: CustomersApi,
    private val sessionAuthenticator: SessionAuthenticator,
) : CustomersRepository {

    override suspend fun listCustomers(filters: CustomerFilters): CustomersResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return CustomersResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return read(accessToken, allowRenewal = true, filters = filters)
    }

    override suspend fun loadCustomerDetail(
        customerId: String,
    ): CustomerDetailResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return CustomerDetailResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return readDetail(accessToken, allowRenewal = true, customerId = customerId)
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
            PropertyCreateResult.Success(property.toProperty())
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
     * The three reads are one screen's worth of data, so they share a single outcome: a screen that
     * showed the header while its sections failed would present a partly loaded customer as if it
     * were complete (`BR-001`). The Property projection is the exception: it has its own capability
     * (`properties.view`, `BR-085`), and the screen omits that section entirely for a caller who
     * does not hold it, so a refused projection must not fail the rest of the customer.
     */
    private suspend fun readDetail(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
    ): CustomerDetailResult =
        try {
            val authorization = "Bearer $accessToken"
            val detail = api.detail(authorization, customerId)
            val properties = propertiesOrNone(authorization, customerId)
            val jobs = api.jobs(authorization, customerId)
            val mapped = detail.toDetail(properties, jobs)
            if (mapped == null) {
                CustomerDetailResult.Failure(CustomersFailureReason.UNEXPECTED)
            } else {
                CustomerDetailResult.Success(mapped)
            }
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryDetail(accessToken, customerId)
            } else {
                CustomerDetailResult.Failure(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            CustomerDetailResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            CustomerDetailResult.Failure(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the detail read once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetryDetail(
        rejectedToken: String,
        customerId: String,
    ): CustomerDetailResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                readDetail(
                    renewal.accessToken,
                    allowRenewal = false,
                    customerId = customerId,
                )

            SessionRenewal.Rejected ->
                CustomerDetailResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                CustomerDetailResult.Failure(CustomersFailureReason.NETWORK)
        }

    /**
     * Reads the customer's Property projection, or an empty list when the caller's session may not
     * view Properties (`properties.view`, `BR-085`).
     *
     * The projection is optional to the customer detail. A caller without the capability is refused
     * with `403`, which must not turn the header, the contacts or the Jobs into a failed read; the
     * screen independently omits the Properties section for that caller (`BR-007`, `BR-011`). Any
     * other refusal still propagates so it is not mistaken for "no Properties".
     */
    private suspend fun propertiesOrNone(
        authorization: String,
        customerId: String,
    ): List<CustomerPropertyDto> =
        try {
            api.properties(authorization, customerId)
        } catch (failure: HttpException) {
            if (failure.code() == HTTP_FORBIDDEN) {
                emptyList()
            } else {
                throw failure
            }
        }

    /** Reads once with [accessToken], renewing the session and retrying when allowed. */
    private suspend fun read(
        accessToken: String,
        allowRenewal: Boolean,
        filters: CustomerFilters,
    ): CustomersResult =
        try {
            mapRows(
                api.list(
                    authorization = "Bearer $accessToken",
                    status = filters.status.name,
                    jobs = filters.jobs.name,
                ),
            )
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetry(accessToken, filters)
            } else {
                CustomersResult.Failure(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            CustomersResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            CustomersResult.Failure(CustomersFailureReason.UNEXPECTED)
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
    ): CustomersResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                read(renewal.accessToken, allowRenewal = false, filters = filters)

            SessionRenewal.Rejected ->
                CustomersResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                CustomersResult.Failure(CustomersFailureReason.NETWORK)
        }

    private fun mapRows(rows: List<CustomerDto>): CustomersResult {
        val customers = rows.map { it.toCustomer() }
        // A row this build cannot represent is a contract mismatch, not a missing value.
        return if (customers.any { it == null }) {
            CustomersResult.Failure(CustomersFailureReason.UNEXPECTED)
        } else {
            CustomersResult.Success(customers.filterNotNull())
        }
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
 * silently, which is the same contract-mismatch rule the list follows (`BR-042`).
 */
private fun CustomerDetailDto.toDetail(
    properties: List<CustomerPropertyDto>,
    jobs: List<CustomerJobDto>,
): CustomerDetail? {
    val domainCustomer = customer.toCustomer() ?: return null
    val domainJobs = jobs.map { it.toJob() ?: return null }
    return CustomerDetail(
        customer = domainCustomer,
        contacts = contacts.map { it.toContact() },
        properties = properties.map { it.toProperty() },
        jobs = domainJobs,
    )
}

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

private fun CustomerPropertyDto.toProperty(): CustomerProperty =
    CustomerProperty(
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
    )

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
