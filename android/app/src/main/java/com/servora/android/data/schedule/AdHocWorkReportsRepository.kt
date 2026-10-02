package com.servora.android.data.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.AdHocReportCustomerOption
import com.servora.android.domain.model.AdHocReportJobOption
import com.servora.android.domain.model.AdHocReportPropertyOption
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Reads the discoverability options and submits the ad-hoc work reports a technician records
 * (`BR-AH-001`, `BR-AH-009`).
 *
 * The backend authorizes every call and owns which Customers, Properties and Jobs a caller may
 * discover, so this layer never decides the scope (`BR-001`, `BR-007`). A submission is idempotent by
 * [AdHocWorkReportDraft.clientOperationId]: the key is created once when the technician acts and is
 * reused for the online attempt and for every replay, so a report is never created twice (`BR-031`).
 */
interface AdHocWorkReportsRepository {
    /** Type-ahead Customer search; the query must be at least two characters (`BR-AH-009`). */
    suspend fun searchCustomers(query: String): AdHocReportCustomerOptionsResult

    /** The active Properties of a selected Customer, derived for the form (`BR-050`). */
    suspend fun listProperties(customerId: String): AdHocReportPropertyOptionsResult

    /** The Jobs of a selected Customer, the optional related-work hint. */
    suspend fun listJobs(customerId: String): AdHocReportJobOptionsResult

    /** Submits one report; queued when the API could not be reached (`BR-014`). */
    suspend fun submit(report: AdHocWorkReportDraft): AdHocWorkReportResult
}

/**
 * The report a technician submits.
 *
 * [clientOperationId] is the idempotency key, created once by the caller and reused across retries.
 * [workStartedAt]/[workEndedAt] are business instants the caller resolved in the device's zone, sent
 * as ISO-8601. The provenance fields carry the reported facts when the technician could not identify
 * the canonical Customer/Property, and are `null` otherwise (`BR-AH-009`).
 */
data class AdHocWorkReportDraft(
    val clientOperationId: String,
    val customerId: String?,
    val propertyId: String?,
    val knownJobId: String?,
    val workStartedAt: Instant,
    val workEndedAt: Instant,
    val outcomeCode: String,
    val summary: String,
    val notes: String?,
    val reportedCustomerName: String?,
    val reportedCustomerPhone: String?,
    val reportedCustomerAddress: String?,
)

sealed interface AdHocWorkReportResult {
    /** The backend recorded the report. */
    data object Success : AdHocWorkReportResult

    /** The API could not be reached, so the report is queued and will sync later (`BR-014`). */
    data object Queued : AdHocWorkReportResult

    /** The operation failed; [reason] decides what the screen reports. */
    data class Failure(val reason: CustomersFailureReason) : AdHocWorkReportResult
}

sealed interface AdHocReportCustomerOptionsResult {
    data class Success(val customers: List<AdHocReportCustomerOption>) :
        AdHocReportCustomerOptionsResult

    data class Failure(val reason: CustomersFailureReason) : AdHocReportCustomerOptionsResult
}

sealed interface AdHocReportPropertyOptionsResult {
    data class Success(val properties: List<AdHocReportPropertyOption>) :
        AdHocReportPropertyOptionsResult

    data class Failure(val reason: CustomersFailureReason) : AdHocReportPropertyOptionsResult
}

sealed interface AdHocReportJobOptionsResult {
    data class Success(val jobs: List<AdHocReportJobOption>) : AdHocReportJobOptionsResult
    data class Failure(val reason: CustomersFailureReason) : AdHocReportJobOptionsResult
}

class DefaultAdHocWorkReportsRepository @Inject constructor(
    private val api: AdHocWorkReportsApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val subject: AuthenticatedSubject,
    private val offline: AdHocWorkReportOfflineStore,
) : AdHocWorkReportsRepository {

    override suspend fun searchCustomers(query: String): AdHocReportCustomerOptionsResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AdHocReportCustomerOptionsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return searchCustomers(accessToken, allowRenewal = true, query)
    }

    private suspend fun searchCustomers(
        accessToken: String,
        allowRenewal: Boolean,
        query: String,
    ): AdHocReportCustomerOptionsResult = try {
        AdHocReportCustomerOptionsResult.Success(
            api.customerOptions("Bearer $accessToken", query).mapNotNull { it.toCustomerOption() },
        )
    } catch (failure: HttpException) {
        if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
            renewAndRetrySearch(accessToken, query) { token, allow ->
                searchCustomers(token, allow, query)
            }
        } else {
            AdHocReportCustomerOptionsResult.Failure(failure.toFailureReason())
        }
    } catch (failure: IOException) {
        AdHocReportCustomerOptionsResult.Failure(CustomersFailureReason.NETWORK)
    } catch (failure: SerializationException) {
        AdHocReportCustomerOptionsResult.Failure(CustomersFailureReason.UNEXPECTED)
    }

    override suspend fun listProperties(customerId: String): AdHocReportPropertyOptionsResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AdHocReportPropertyOptionsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return listProperties(accessToken, allowRenewal = true, customerId)
    }

    private suspend fun listProperties(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
    ): AdHocReportPropertyOptionsResult = try {
        AdHocReportPropertyOptionsResult.Success(
            api.propertyOptions("Bearer $accessToken", customerId).mapNotNull { it.toPropertyOption() },
        )
    } catch (failure: HttpException) {
        if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
            renewAndRetryProperty(accessToken, customerId) { token, allow ->
                listProperties(token, allow, customerId)
            }
        } else {
            AdHocReportPropertyOptionsResult.Failure(failure.toFailureReason())
        }
    } catch (failure: IOException) {
        AdHocReportPropertyOptionsResult.Failure(CustomersFailureReason.NETWORK)
    } catch (failure: SerializationException) {
        AdHocReportPropertyOptionsResult.Failure(CustomersFailureReason.UNEXPECTED)
    }

    override suspend fun listJobs(customerId: String): AdHocReportJobOptionsResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AdHocReportJobOptionsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return listJobs(accessToken, allowRenewal = true, customerId)
    }

    private suspend fun listJobs(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
    ): AdHocReportJobOptionsResult = try {
        AdHocReportJobOptionsResult.Success(
            api.jobOptions("Bearer $accessToken", customerId).mapNotNull { it.toJobOption() },
        )
    } catch (failure: HttpException) {
        if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
            renewAndRetryJob(accessToken, customerId) { token, allow ->
                listJobs(token, allow, customerId)
            }
        } else {
            AdHocReportJobOptionsResult.Failure(failure.toFailureReason())
        }
    } catch (failure: IOException) {
        AdHocReportJobOptionsResult.Failure(CustomersFailureReason.NETWORK)
    } catch (failure: SerializationException) {
        AdHocReportJobOptionsResult.Failure(CustomersFailureReason.UNEXPECTED)
    }

    override suspend fun submit(report: AdHocWorkReportDraft): AdHocWorkReportResult {
        val subjectId = subject.current()
            ?: return AdHocWorkReportResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AdHocWorkReportResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val capturedAt = Instant.now().toString()
        val request = report.toSubmitRequest()
        return submit(accessToken, allowRenewal = true, subjectId, request, capturedAt)
    }

    private suspend fun submit(
        accessToken: String,
        allowRenewal: Boolean,
        subjectId: String,
        request: SubmitAdHocWorkReportRequestDto,
        capturedAt: String?,
    ): AdHocWorkReportResult = try {
        api.submit("Bearer $accessToken", request)
        AdHocWorkReportResult.Success
    } catch (failure: HttpException) {
        if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
            when (val renewal = sessionAuthenticator.renew(accessToken)) {
                is SessionRenewal.Renewed ->
                    submit(renewal.accessToken, allowRenewal = false, subjectId, request, capturedAt)

                SessionRenewal.Rejected ->
                    AdHocWorkReportResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

                SessionRenewal.Unavailable -> queue(subjectId, request, capturedAt)
            }
        } else {
            AdHocWorkReportResult.Failure(failure.toFailureReason())
        }
    } catch (failure: IOException) {
        queue(subjectId, request, capturedAt)
    } catch (failure: SerializationException) {
        AdHocWorkReportResult.Failure(CustomersFailureReason.UNEXPECTED)
    }

    /** Queues a submission the API could not be reached for, reusing the same idempotency key (`§5`). */
    private suspend fun queue(
        subjectId: String,
        request: SubmitAdHocWorkReportRequestDto,
        capturedAt: String?,
    ): AdHocWorkReportResult {
        val queued = offline.queueSubmit(subjectId, request, capturedAt)
        return if (queued) {
            AdHocWorkReportResult.Queued
        } else {
            AdHocWorkReportResult.Failure(CustomersFailureReason.NETWORK)
        }
    }

    private suspend fun renewAndRetrySearch(
        rejectedToken: String,
        query: String,
        retry: suspend (token: String, allow: Boolean) -> AdHocReportCustomerOptionsResult,
    ): AdHocReportCustomerOptionsResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed -> retry(renewal.accessToken, false)
            SessionRenewal.Rejected ->
                AdHocReportCustomerOptionsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                AdHocReportCustomerOptionsResult.Failure(CustomersFailureReason.NETWORK)
        }

    private suspend fun renewAndRetryProperty(
        rejectedToken: String,
        customerId: String,
        retry: suspend (token: String, allow: Boolean) -> AdHocReportPropertyOptionsResult,
    ): AdHocReportPropertyOptionsResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed -> retry(renewal.accessToken, false)
            SessionRenewal.Rejected ->
                AdHocReportPropertyOptionsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                AdHocReportPropertyOptionsResult.Failure(CustomersFailureReason.NETWORK)
        }

    private suspend fun renewAndRetryJob(
        rejectedToken: String,
        customerId: String,
        retry: suspend (token: String, allow: Boolean) -> AdHocReportJobOptionsResult,
    ): AdHocReportJobOptionsResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed -> retry(renewal.accessToken, false)
            SessionRenewal.Rejected ->
                AdHocReportJobOptionsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                AdHocReportJobOptionsResult.Failure(CustomersFailureReason.NETWORK)
        }
}

/** The wire request a report is sent as, reusing the draft's idempotency key (`BR-031`). */
private fun AdHocWorkReportDraft.toSubmitRequest(): SubmitAdHocWorkReportRequestDto =
    SubmitAdHocWorkReportRequestDto(
        customerId = customerId?.trim().takeUnless { it.isNullOrEmpty() },
        propertyId = propertyId?.trim().takeUnless { it.isNullOrEmpty() },
        knownJobId = knownJobId?.trim().takeUnless { it.isNullOrEmpty() },
        workStartedAt = workStartedAt.toString(),
        workEndedAt = workEndedAt.toString(),
        outcomeCode = outcomeCode,
        summary = summary.trim(),
        notes = notes?.trim().takeUnless { it.isNullOrEmpty() },
        reportedCustomerName = reportedCustomerName?.trim().takeUnless { it.isNullOrEmpty() },
        reportedCustomerPhone = reportedCustomerPhone?.trim().takeUnless { it.isNullOrEmpty() },
        reportedCustomerAddress = reportedCustomerAddress?.trim().takeUnless { it.isNullOrEmpty() },
        clientOperationId = clientOperationId,
    )

private fun AdHocReportCustomerOptionDto.toCustomerOption(): AdHocReportCustomerOption? =
    if (id.isBlank() || displayName.isBlank()) null else AdHocReportCustomerOption(id, displayName)

private fun AdHocReportPropertyOptionDto.toPropertyOption(): AdHocReportPropertyOption? =
    if (id.isBlank()) null else {
        AdHocReportPropertyOption(
            id = id,
            name = name,
            addressLine1 = addressLine1,
            addressLine2 = addressLine2,
            city = city,
            province = province,
            postalCode = postalCode,
            country = country,
        )
    }

private fun AdHocReportJobOptionDto.toJobOption(): AdHocReportJobOption? =
    if (id.isBlank()) null else AdHocReportJobOption(id, jobNumber, title)

private const val HTTP_UNAUTHORIZED = 401

private fun HttpException.toFailureReason(): CustomersFailureReason =
    when (code()) {
        400, 422 -> CustomersFailureReason.VALIDATION
        401 -> CustomersFailureReason.UNAUTHENTICATED
        403 -> CustomersFailureReason.FORBIDDEN
        404 -> CustomersFailureReason.NOT_FOUND
        409 -> CustomersFailureReason.VERSION_CONFLICT
        in 500..599 -> CustomersFailureReason.SERVER
        else -> CustomersFailureReason.UNEXPECTED
    }
