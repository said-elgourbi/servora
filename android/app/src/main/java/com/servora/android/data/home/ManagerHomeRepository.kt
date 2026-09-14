package com.servora.android.data.home

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.ManagerAttentionItem
import com.servora.android.domain.model.ManagerAttentionKind
import com.servora.android.domain.model.ManagerHome
import com.servora.android.domain.model.ManagerHomeAddress
import com.servora.android.domain.model.ManagerHomeTechnician
import com.servora.android.domain.model.ManagerHomeTodaySummary
import com.servora.android.domain.model.ManagerHomeVisit
import com.servora.android.domain.model.VisitStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Reads the manager home from the backend.
 *
 * The backend authorizes the read and owns the tenant boundary, the derived conditions and the local
 * day, so this layer never names an organization and never decides what needs attention
 * (`BR-001`, `BR-007`).
 */
interface ManagerHomeRepository {
    /**
     * Returns the manager's operational day for [timeZone], or why it could not be read.
     *
     * The time zone is the device's, so the day the backend derives is the day the manager is
     * actually working in.
     */
    suspend fun loadManagerHome(timeZone: String): ManagerHomeResult
}

/**
 * Default [ManagerHomeRepository].
 *
 * It keeps the same session contract as the other repositories: the access token is read from
 * [SessionAuthenticator], and a read the backend refuses with `401` is retried once with a renewed
 * session before the outcome is reported.
 */
class DefaultManagerHomeRepository @Inject constructor(
    private val api: ManagerHomeApi,
    private val sessionAuthenticator: SessionAuthenticator,
) : ManagerHomeRepository {

    override suspend fun loadManagerHome(timeZone: String): ManagerHomeResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return ManagerHomeResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return read(accessToken, allowRenewal = true, timeZone = timeZone)
    }

    private suspend fun read(
        accessToken: String,
        allowRenewal: Boolean,
        timeZone: String,
    ): ManagerHomeResult =
        try {
            val home = api.managerHome(
                authorization = "Bearer $accessToken",
                timeZone = timeZone,
            ).toManagerHome()
            if (home == null) {
                ManagerHomeResult.Failure(CustomersFailureReason.UNEXPECTED)
            } else {
                ManagerHomeResult.Success(home)
            }
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetry(accessToken, timeZone)
            } else {
                ManagerHomeResult.Failure(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            ManagerHomeResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ManagerHomeResult.Failure(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the read once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetry(
        rejectedToken: String,
        timeZone: String,
    ): ManagerHomeResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                read(renewal.accessToken, allowRenewal = false, timeZone = timeZone)

            SessionRenewal.Rejected ->
                ManagerHomeResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                ManagerHomeResult.Failure(CustomersFailureReason.NETWORK)
        }
}

private const val HTTP_UNAUTHORIZED = 401

/** Classifies an HTTP answer into the stable reason the screen reports (`dev.md` §7). */
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

/**
 * Maps the wire payload onto the domain model, or reports a contract mismatch.
 *
 * A code this build does not know — a Visit status, a Job status or an attention kind — fails the
 * whole read rather than being dropped silently, which is the contract-mismatch rule the other reads
 * follow (`BR-042`).
 */
private fun ManagerHomeDto.toManagerHome(): ManagerHome? {
    val attentionItems = attention.items.map { it.toAttentionItem() ?: return null }
    val visits = visits.map { it.toVisit() ?: return null }
    return ManagerHome(
        displayName = viewer.displayName,
        attention = attentionItems,
        attentionTotal = attention.total,
        today = ManagerHomeTodaySummary(
            total = today.total,
            completed = today.completed,
            inProgress = today.inProgress,
            upcoming = today.upcoming,
        ),
        visits = visits,
    )
}

private fun ManagerAttentionItemDto.toAttentionItem(): ManagerAttentionItem? {
    val kind = ManagerAttentionKind.entries.firstOrNull { it.name == this.kind } ?: return null
    val status = JobStatus.entries.firstOrNull { it.name == jobStatus } ?: return null
    return ManagerAttentionItem(
        kind = kind,
        jobId = jobId,
        jobNumber = jobNumber,
        jobTitle = jobTitle,
        jobStatus = status,
        customerId = customerId,
        customerName = customerName,
        visitId = visitId,
        scheduledStart = scheduledStart,
        scheduledEnd = scheduledEnd,
    )
}

private fun ManagerHomeVisitDto.toVisit(): ManagerHomeVisit? {
    val status = VisitStatus.entries.firstOrNull { it.name == visitStatus } ?: return null
    val job = JobStatus.entries.firstOrNull { it.name == jobStatus } ?: return null
    return ManagerHomeVisit(
        visitId = visitId,
        visitStatus = status,
        scheduledStart = scheduledStart,
        scheduledEnd = scheduledEnd,
        jobId = jobId,
        jobNumber = jobNumber,
        jobTitle = jobTitle,
        jobStatus = job,
        customerId = customerId,
        customerName = customerName,
        address = address?.toAddress(),
        technicians = technicians.map {
            ManagerHomeTechnician(
                membershipId = it.membershipId,
                name = it.name,
                roleCode = it.roleCode,
            )
        },
        isOverdue = overdue,
    )
}

private fun ManagerHomeAddressDto.toAddress(): ManagerHomeAddress =
    ManagerHomeAddress(
        propertyName = propertyName,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        province = province,
        postalCode = postalCode,
        country = country,
    )
