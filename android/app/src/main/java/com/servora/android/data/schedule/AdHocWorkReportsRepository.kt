package com.servora.android.data.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.AdHocReportCustomerOption
import com.servora.android.domain.model.AdHocReportJobOption
import com.servora.android.domain.model.AdHocReportPropertyOption
import com.servora.android.domain.model.AdHocWorkReport
import com.servora.android.domain.model.AdHocWorkReportStatus
import com.servora.android.domain.model.ScheduleAddress
import com.servora.android.domain.model.visitOutcomeOrNull
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
    suspend fun searchReviewCustomers(query: String): AdHocReviewDataResult<List<AdHocReportCustomerOption>>
    suspend fun listReviewProperties(customerId: String): AdHocReviewDataResult<List<AdHocReportPropertyOption>>
    suspend fun resolveLocation(report: AdHocWorkReport, request: ResolveAdHocLocationRequestDto): AdHocReviewDataResult<AdHocWorkReport>
    /** Type-ahead Customer search; the query must be at least two characters (`BR-AH-009`). */
    suspend fun searchCustomers(query: String): AdHocReportCustomerOptionsResult

    /** The active Properties of a selected Customer, derived for the form (`BR-050`). */
    suspend fun listProperties(customerId: String): AdHocReportPropertyOptionsResult

    /** The Jobs of a selected Customer, the optional related-work hint. */
    suspend fun listJobs(customerId: String): AdHocReportJobOptionsResult

    /**
     * The Jobs an office reviewer may link a pending report to (`BR-AH-002`). When [customerId] is
     * known the list is scoped to that Customer; otherwise [query] searches the whole organization.
     */
    suspend fun listReviewJobs(
        customerId: String?,
        query: String?,
    ): AdHocReportJobOptionsResult

    /** Submits one report; queued when the API could not be reached (`BR-014`). */
    suspend fun submit(report: AdHocWorkReportDraft): AdHocWorkReportResult

    /** Reads the reports the caller may review or read (`BR-AH-002`, `BR-AH-005`). */
    suspend fun loadReports(): AdHocWorkReportsResult

    /** Voids [report] as not legitimate Servora work, recording [note] (`BR-AH-007`). */
    suspend fun reject(report: AdHocWorkReport, note: String): AdHocWorkReportRejectResult

    /**
     * Reconciles [report] into the existing Job [jobId], recording its completed Visit (`BR-AH-002`,
     * `BR-AH-006`). The returned id names the Job that now carries the reconciled Visit.
     */
    suspend fun link(report: AdHocWorkReport, jobId: String, note: String): AdHocWorkReportReconcileResult

    /**
     * Reconciles [report] into a new Job with [title], recording its completed Visit (`BR-AH-002`).
     * The returned id names the Job the operation created.
     */
    suspend fun convert(
        report: AdHocWorkReport,
        title: String,
        description: String?,
        note: String,
    ): AdHocWorkReportReconcileResult
}

sealed interface AdHocReviewDataResult<out T> {
    data class Success<T>(val value: T) : AdHocReviewDataResult<T>
    data class Failure(val reason: CustomersFailureReason) : AdHocReviewDataResult<Nothing>
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

sealed interface AdHocWorkReportsResult {
    data class Success(val reports: List<AdHocWorkReport>) : AdHocWorkReportsResult
    data class Failure(val reason: CustomersFailureReason) : AdHocWorkReportsResult
}

sealed interface AdHocWorkReportRejectResult {
    data class Success(val report: AdHocWorkReport) : AdHocWorkReportRejectResult
    data class Failure(val reason: CustomersFailureReason) : AdHocWorkReportRejectResult
}

sealed interface AdHocWorkReportReconcileResult {
    data class Success(val jobId: String, val jobNumber: Int) : AdHocWorkReportReconcileResult
    data class Failure(val reason: CustomersFailureReason) : AdHocWorkReportReconcileResult
}

class DefaultAdHocWorkReportsRepository @Inject constructor(
    private val api: AdHocWorkReportsApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val subject: AuthenticatedSubject,
    private val offline: AdHocWorkReportOfflineStore,
) : AdHocWorkReportsRepository {
    override suspend fun searchReviewCustomers(query: String) = reviewData {
        api.reviewCustomerOptions("Bearer $it", query).mapNotNull { dto -> dto.toCustomerOption() }
    }

    override suspend fun listReviewProperties(customerId: String) = reviewData {
        api.reviewPropertyOptions("Bearer $it", customerId).mapNotNull { dto -> dto.toPropertyOption() }
    }

    override suspend fun resolveLocation(report: AdHocWorkReport, request: ResolveAdHocLocationRequestDto) = reviewData {
        api.resolveLocation("Bearer $it", report.id, request.copy(expectedStatus = "PENDING", expectedVersion = report.version))
            .toReport() ?: throw SerializationException("Invalid resolved report")
    }

    private suspend fun <T> reviewData(call: suspend (String) -> T): AdHocReviewDataResult<T> {
        val token = sessionAuthenticator.accessToken()
            ?: return AdHocReviewDataResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return reviewData(token, true, call)
    }

    private suspend fun <T> reviewData(token: String, allowRenewal: Boolean, call: suspend (String) -> T): AdHocReviewDataResult<T> = try {
        AdHocReviewDataResult.Success(call(token))
    } catch (failure: HttpException) {
        if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
            when (val renewal = sessionAuthenticator.renew(token)) {
                is SessionRenewal.Renewed -> reviewData(renewal.accessToken, false, call)
                SessionRenewal.Rejected -> AdHocReviewDataResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
                SessionRenewal.Unavailable -> AdHocReviewDataResult.Failure(CustomersFailureReason.NETWORK)
            }
        } else AdHocReviewDataResult.Failure(failure.toFailureReason())
    } catch (failure: IOException) {
        AdHocReviewDataResult.Failure(CustomersFailureReason.NETWORK)
    } catch (failure: SerializationException) {
        AdHocReviewDataResult.Failure(CustomersFailureReason.UNEXPECTED)
    }


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

    override suspend fun listReviewJobs(
        customerId: String?,
        query: String?,
    ): AdHocReportJobOptionsResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AdHocReportJobOptionsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return listReviewJobs(accessToken, allowRenewal = true, customerId, query)
    }

    private suspend fun listReviewJobs(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String?,
        query: String?,
    ): AdHocReportJobOptionsResult = try {
        AdHocReportJobOptionsResult.Success(
            api.reviewJobOptions("Bearer $accessToken", customerId, query)
                .mapNotNull { it.toJobOption() },
        )
    } catch (failure: HttpException) {
        if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
            when (val renewal = sessionAuthenticator.renew(accessToken)) {
                is SessionRenewal.Renewed ->
                    listReviewJobs(renewal.accessToken, false, customerId, query)

                SessionRenewal.Rejected ->
                    AdHocReportJobOptionsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

                SessionRenewal.Unavailable ->
                    AdHocReportJobOptionsResult.Failure(CustomersFailureReason.NETWORK)
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

    override suspend fun loadReports(): AdHocWorkReportsResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AdHocWorkReportsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return loadReports(accessToken, allowRenewal = true)
    }

    private suspend fun loadReports(
        accessToken: String,
        allowRenewal: Boolean,
    ): AdHocWorkReportsResult = try {
        AdHocWorkReportsResult.Success(
            api.list("Bearer $accessToken").mapNotNull { it.toReport() },
        )
    } catch (failure: HttpException) {
        if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
            when (val renewal = sessionAuthenticator.renew(accessToken)) {
                is SessionRenewal.Renewed -> loadReports(renewal.accessToken, false)
                SessionRenewal.Rejected ->
                    AdHocWorkReportsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

                SessionRenewal.Unavailable ->
                    AdHocWorkReportsResult.Failure(CustomersFailureReason.NETWORK)
            }
        } else {
            AdHocWorkReportsResult.Failure(failure.toFailureReason())
        }
    } catch (failure: IOException) {
        AdHocWorkReportsResult.Failure(CustomersFailureReason.NETWORK)
    } catch (failure: SerializationException) {
        AdHocWorkReportsResult.Failure(CustomersFailureReason.UNEXPECTED)
    }

    override suspend fun reject(
        report: AdHocWorkReport,
        note: String,
    ): AdHocWorkReportRejectResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AdHocWorkReportRejectResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val request = RejectAdHocWorkReportRequestDto(
            note = note.trim().takeUnless { it.isEmpty() },
            expectedStatus = AdHocWorkReportStatus.PENDING.name,
            expectedVersion = report.version,
        )
        return reject(accessToken, allowRenewal = true, report.id, request)
    }

    private suspend fun reject(
        accessToken: String,
        allowRenewal: Boolean,
        reportId: String,
        request: RejectAdHocWorkReportRequestDto,
    ): AdHocWorkReportRejectResult = try {
        val report = api.reject("Bearer $accessToken", reportId, request).toReport()
        if (report == null) {
            AdHocWorkReportRejectResult.Failure(CustomersFailureReason.UNEXPECTED)
        } else {
            AdHocWorkReportRejectResult.Success(report)
        }
    } catch (failure: HttpException) {
        if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
            when (val renewal = sessionAuthenticator.renew(accessToken)) {
                is SessionRenewal.Renewed -> reject(renewal.accessToken, false, reportId, request)
                SessionRenewal.Rejected ->
                    AdHocWorkReportRejectResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

                SessionRenewal.Unavailable ->
                    AdHocWorkReportRejectResult.Failure(CustomersFailureReason.NETWORK)
            }
        } else {
            AdHocWorkReportRejectResult.Failure(failure.toFailureReason())
        }
    } catch (failure: IOException) {
        AdHocWorkReportRejectResult.Failure(CustomersFailureReason.NETWORK)
    } catch (failure: SerializationException) {
        AdHocWorkReportRejectResult.Failure(CustomersFailureReason.UNEXPECTED)
    }

    override suspend fun link(
        report: AdHocWorkReport,
        jobId: String,
        note: String,
    ): AdHocWorkReportReconcileResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AdHocWorkReportReconcileResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val request = LinkAdHocWorkReportRequestDto(
            jobId = jobId,
            note = note.trim().takeUnless { it.isEmpty() },
            expectedStatus = AdHocWorkReportStatus.PENDING.name,
            expectedVersion = report.version,
        )
        return reconcile(accessToken, allowRenewal = true) { token ->
            api.link("Bearer $token", report.id, request)
        }
    }

    override suspend fun convert(
        report: AdHocWorkReport,
        title: String,
        description: String?,
        note: String,
    ): AdHocWorkReportReconcileResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AdHocWorkReportReconcileResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val request = ConvertAdHocWorkReportRequestDto(
            title = title.trim(),
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            note = note.trim().takeUnless { it.isEmpty() },
            expectedStatus = AdHocWorkReportStatus.PENDING.name,
            expectedVersion = report.version,
        )
        return reconcile(accessToken, allowRenewal = true) { token ->
            api.convert("Bearer $token", report.id, request)
        }
    }

    private suspend fun reconcile(
        accessToken: String,
        allowRenewal: Boolean,
        call: suspend (token: String) -> AdHocReconciliationJobDto,
    ): AdHocWorkReportReconcileResult = try {
        val job = call(accessToken)
        AdHocWorkReportReconcileResult.Success(jobId = job.id, jobNumber = job.jobNumber)
    } catch (failure: HttpException) {
        if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
            when (val renewal = sessionAuthenticator.renew(accessToken)) {
                is SessionRenewal.Renewed -> reconcile(renewal.accessToken, false, call)
                SessionRenewal.Rejected ->
                    AdHocWorkReportReconcileResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

                SessionRenewal.Unavailable ->
                    AdHocWorkReportReconcileResult.Failure(CustomersFailureReason.NETWORK)
            }
        } else {
            AdHocWorkReportReconcileResult.Failure(failure.toFailureReason())
        }
    } catch (failure: IOException) {
        AdHocWorkReportReconcileResult.Failure(CustomersFailureReason.NETWORK)
    } catch (failure: SerializationException) {
        AdHocWorkReportReconcileResult.Failure(CustomersFailureReason.UNEXPECTED)
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
    if (id.isBlank() || displayName.isBlank()) null else AdHocReportCustomerOption(id, displayName, phone)

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

/**
 * Maps a report read, or `null` when this build cannot read its status or outcome.
 *
 * An unknown status or outcome code is a report this client cannot present truthfully, exactly as an
 * unknown request status is not a request it can present (`BR-041`, `BR-042`): the row is dropped
 * rather than drawn with the wrong state.
 */
internal fun AdHocWorkReportDto.toReport(): AdHocWorkReport? {
    val status = AdHocWorkReportStatus.entries.firstOrNull { it.name == this.status }
        ?: return null
    val outcome = visitOutcomeOrNull(this.outcomeCode)
        ?: return null
    return AdHocWorkReport(
        id = id,
        reportingTechnicianMembershipId = reportingTechnicianMembershipId,
        customerId = customerId,
        customerName = customerName,
        propertyId = propertyId,
        propertyAddress = propertyAddress?.toScheduleAddress(),
        knownJobId = knownJobId,
        knownJobNumber = knownJobNumber,
        knownJobTitle = knownJobTitle,
        workStartedAt = workStartedAt,
        workEndedAt = workEndedAt,
        outcomeCode = outcome,
        summary = summary,
        notes = notes,
        reportedCustomerName = reportedCustomerName,
        reportedCustomerPhone = reportedCustomerPhone,
        reportedCustomerAddress = reportedCustomerAddress,
        status = status,
        reviewerMembershipId = reviewerMembershipId,
        reviewedAt = reviewedAt,
        reviewNote = reviewNote,
        createdJobId = createdJobId,
        createdJobNumber = createdJobNumber,
        createdJobTitle = createdJobTitle,
        createdVisitId = createdVisitId,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun ScheduleAddressDto.toScheduleAddress(): ScheduleAddress =
    ScheduleAddress(
        propertyName = propertyName,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        province = province,
        postalCode = postalCode,
        country = country,
    )

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
