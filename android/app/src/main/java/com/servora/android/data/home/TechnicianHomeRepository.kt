package com.servora.android.data.home

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.couldNotReachBackend
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAttentionItem
import com.servora.android.domain.model.TechnicianAttentionKind
import com.servora.android.domain.model.TechnicianHome
import com.servora.android.domain.model.TechnicianHomeAddress
import com.servora.android.domain.model.TechnicianHomeTechnician
import com.servora.android.domain.model.TechnicianHomeVisit
import com.servora.android.domain.model.VisitStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Reads the technician home from the backend.
 *
 * The read is scoped to the caller's own assignments and authorized by the backend, so this layer
 * never names a membership, an organization or a set of Visits (`BR-001`, `BR-007`, `ADR-019` D2).
 *
 * It follows the offline standard: a successful read is remembered as the last answer the backend
 * reported, and only a failure that **could not reach the backend** is answered from there — an
 * answer, a refusal included, is never replaced by a local copy (`BR-013`, `BR-042`).
 */
interface TechnicianHomeRepository {
    /**
     * Returns the caller's own working day for [timeZone], or why it could not be read.
     *
     * The time zone is the device's, so the day the backend derives is the day the technician is
     * actually working in.
     */
    suspend fun loadTechnicianHome(timeZone: String): TechnicianHomeResult
}

/**
 * Default [TechnicianHomeRepository].
 *
 * It keeps the same session contract as the other repositories: the access token is read from
 * [SessionAuthenticator] and a read the backend refuses with `401` is retried once with a renewed
 * session. The working set is partitioned by the authenticated subject, so a day read for one
 * technician is never served to another (`§10`).
 */
class DefaultTechnicianHomeRepository @Inject constructor(
    private val api: TechnicianHomeApi,
    private val sessionAuthenticator: SessionAuthenticator,
    /** The last day the backend reported, for a read that cannot reach it (§2, §12). */
    private val cache: TechnicianHomeCache,
    /** The subject the working set is partitioned by; local answers never cross sessions. */
    private val subject: AuthenticatedSubject,
) : TechnicianHomeRepository {

    override suspend fun loadTechnicianHome(timeZone: String): TechnicianHomeResult {
        // A read whose subject cannot be read is refused rather than answered without local state
        // (§10) — the working set is scoped to the session that produced it.
        val subjectId = subject.current()
            ?: return TechnicianHomeResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return TechnicianHomeResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return when (val read = read(accessToken, allowRenewal = true, timeZone = timeZone)) {
            is TechnicianRead.Answered -> {
                // A successful read replaces the local copy rather than being merged into it (§10).
                cache.remember(subjectId, read.payload)
                TechnicianHomeResult.Success(read.home)
            }

            is TechnicianRead.Failed ->
                if (read.reason.couldNotReachBackend()) {
                    cache.reported(subjectId)
                        ?.toTechnicianHome()
                        ?.let { reported ->
                            TechnicianHomeResult.Success(reported, ReadSource.WORKING_SET)
                        }
                        ?: TechnicianHomeResult.Failure(read.reason)
                } else {
                    TechnicianHomeResult.Failure(read.reason)
                }
        }
    }

    private suspend fun read(
        accessToken: String,
        allowRenewal: Boolean,
        timeZone: String,
    ): TechnicianRead =
        try {
            val payload = api.technicianHome(
                authorization = "Bearer $accessToken",
                timeZone = timeZone,
            )
            val home = payload.toTechnicianHome()
            if (home == null) {
                TechnicianRead.Failed(CustomersFailureReason.UNEXPECTED)
            } else {
                TechnicianRead.Answered(home, payload)
            }
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetry(accessToken, timeZone)
            } else {
                TechnicianRead.Failed(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            TechnicianRead.Failed(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            TechnicianRead.Failed(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the read once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetry(
        rejectedToken: String,
        timeZone: String,
    ): TechnicianRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                read(renewal.accessToken, allowRenewal = false, timeZone = timeZone)

            SessionRenewal.Rejected ->
                TechnicianRead.Failed(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                TechnicianRead.Failed(CustomersFailureReason.NETWORK)
        }
}

/** One attempt at the read: the backend's answer, or why it did not arrive. */
private sealed interface TechnicianRead {
    data class Answered(
        val home: TechnicianHome,
        /** The wire response as received, so the working set keeps what the backend reported. */
        val payload: TechnicianHomeDto,
    ) : TechnicianRead

    data class Failed(val reason: CustomersFailureReason) : TechnicianRead
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
internal fun TechnicianHomeDto.toTechnicianHome(): TechnicianHome? {
    // A `nextVisit` that is present but unreadable is a contract mismatch; an absent one is the
    // read's own answer ("nothing left to do").
    val nextVisit = nextVisit?.let { it.toVisit() ?: return null }
    val todayVisits = visits.map { it.toVisit() ?: return null }
    val upcomingVisits = upcoming.map { it.toVisit() ?: return null }
    val attentionItems = attention.items.map { it.toAttentionItem() ?: return null }
    return TechnicianHome(
        displayName = viewer.displayName,
        nextVisit = nextVisit,
        visits = todayVisits,
        upcoming = upcomingVisits,
        upcomingTotal = upcomingTotal,
        attention = attentionItems,
        attentionTotal = attention.total,
    )
}

private fun TechnicianAttentionItemDto.toAttentionItem(): TechnicianAttentionItem? {
    val kind = TechnicianAttentionKind.entries.firstOrNull { it.name == this.kind } ?: return null
    val status = JobStatus.entries.firstOrNull { it.name == jobStatus } ?: return null
    return TechnicianAttentionItem(
        kind = kind,
        visitId = visitId,
        jobId = jobId,
        jobNumber = jobNumber,
        jobTitle = jobTitle,
        jobStatus = status,
        customerId = customerId,
        customerName = customerName,
        scheduledStart = scheduledStart,
        scheduledEnd = scheduledEnd,
    )
}

private fun TechnicianHomeVisitDto.toVisit(): TechnicianHomeVisit? {
    val status = VisitStatus.entries.firstOrNull { it.name == visitStatus } ?: return null
    val job = JobStatus.entries.firstOrNull { it.name == jobStatus } ?: return null
    return TechnicianHomeVisit(
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
            TechnicianHomeTechnician(
                membershipId = it.membershipId,
                name = it.name,
                roleCode = it.roleCode,
            )
        },
        isOverdue = overdue,
    )
}

private fun TechnicianHomeAddressDto.toAddress(): TechnicianHomeAddress =
    TechnicianHomeAddress(
        propertyName = propertyName,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        province = province,
        postalCode = postalCode,
        country = country,
    )

