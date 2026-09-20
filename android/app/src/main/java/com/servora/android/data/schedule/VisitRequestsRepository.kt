package com.servora.android.data.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Reads and reviews follow-up Visit requests. */
interface VisitRequestsRepository {
    suspend fun loadRequests(): VisitRequestsResult

    suspend fun askForClarification(request: FollowUpVisitRequest): VisitRequestReviewResult

    suspend fun reject(request: FollowUpVisitRequest): VisitRequestReviewResult
}

sealed interface VisitRequestsResult {
    data class Success(val requests: List<FollowUpVisitRequest>) : VisitRequestsResult
    data class Failure(val reason: CustomersFailureReason) : VisitRequestsResult
}

sealed interface VisitRequestReviewResult {
    data class Success(val request: FollowUpVisitRequest) : VisitRequestReviewResult
    data class Failure(val reason: CustomersFailureReason) : VisitRequestReviewResult
}

class DefaultVisitRequestsRepository @Inject constructor(
    private val api: VisitRequestsApi,
    private val sessionAuthenticator: SessionAuthenticator,
) : VisitRequestsRepository {

    override suspend fun loadRequests(): VisitRequestsResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return VisitRequestsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return when (val read = read(accessToken = accessToken, allowRenewal = true)) {
            is RequestRead.Answered -> VisitRequestsResult.Success(read.requests)
            is RequestRead.Failed -> VisitRequestsResult.Failure(read.reason)
        }
    }

    override suspend fun askForClarification(
        request: FollowUpVisitRequest,
    ): VisitRequestReviewResult =
        review(request = request, action = ReviewAction.CLARIFY)

    override suspend fun reject(request: FollowUpVisitRequest): VisitRequestReviewResult =
        review(request = request, action = ReviewAction.REJECT)

    private suspend fun read(accessToken: String, allowRenewal: Boolean): RequestRead =
        try {
            val requests = api.visitRequests("Bearer $accessToken").map { it.toRequest() ?: return RequestRead.Failed(CustomersFailureReason.UNEXPECTED) }
            RequestRead.Answered(requests)
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRead(accessToken)
            } else {
                RequestRead.Failed(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            RequestRead.Failed(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            RequestRead.Failed(CustomersFailureReason.UNEXPECTED)
        }

    private suspend fun renewAndRead(rejectedToken: String): RequestRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed -> read(accessToken = renewal.accessToken, allowRenewal = false)
            SessionRenewal.Rejected -> RequestRead.Failed(CustomersFailureReason.UNAUTHENTICATED)
            SessionRenewal.Unavailable -> RequestRead.Failed(CustomersFailureReason.NETWORK)
        }

    private suspend fun review(
        request: FollowUpVisitRequest,
        action: ReviewAction,
    ): VisitRequestReviewResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return VisitRequestReviewResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return when (
            val reviewed = review(
                accessToken = accessToken,
                allowRenewal = true,
                request = request,
                action = action,
            )
        ) {
            is ReviewRead.Answered -> VisitRequestReviewResult.Success(reviewed.request)
            is ReviewRead.Failed -> VisitRequestReviewResult.Failure(reviewed.reason)
        }
    }

    private suspend fun review(
        accessToken: String,
        allowRenewal: Boolean,
        request: FollowUpVisitRequest,
        action: ReviewAction,
    ): ReviewRead =
        try {
            val body = FollowUpVisitReviewRequestDto(
                expectedStatus = request.status.name,
                expectedVersion = request.version,
            )
            val reviewed = when (action) {
                ReviewAction.CLARIFY -> api.clarify(
                    authorization = "Bearer $accessToken",
                    jobId = request.jobId,
                    requestId = request.id,
                    request = body,
                )

                ReviewAction.REJECT -> api.reject(
                    authorization = "Bearer $accessToken",
                    jobId = request.jobId,
                    requestId = request.id,
                    request = body,
                )
            }.toRequest() ?: return ReviewRead.Failed(CustomersFailureReason.UNEXPECTED)
            ReviewRead.Answered(reviewed)
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndReview(rejectedToken = accessToken, request = request, action = action)
            } else {
                ReviewRead.Failed(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            ReviewRead.Failed(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ReviewRead.Failed(CustomersFailureReason.UNEXPECTED)
        }

    private suspend fun renewAndReview(
        rejectedToken: String,
        request: FollowUpVisitRequest,
        action: ReviewAction,
    ): ReviewRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed -> review(
                accessToken = renewal.accessToken,
                allowRenewal = false,
                request = request,
                action = action,
            )

            SessionRenewal.Rejected -> ReviewRead.Failed(CustomersFailureReason.UNAUTHENTICATED)
            SessionRenewal.Unavailable -> ReviewRead.Failed(CustomersFailureReason.NETWORK)
        }
}

private enum class ReviewAction {
    CLARIFY,
    REJECT,
}

private sealed interface RequestRead {
    data class Answered(val requests: List<FollowUpVisitRequest>) : RequestRead
    data class Failed(val reason: CustomersFailureReason) : RequestRead
}

private sealed interface ReviewRead {
    data class Answered(val request: FollowUpVisitRequest) : ReviewRead
    data class Failed(val reason: CustomersFailureReason) : ReviewRead
}

internal fun FollowUpVisitRequestDto.toRequest(): FollowUpVisitRequest? {
    val status = FollowUpVisitRequestStatus.entries.firstOrNull { it.name == this.status }
        ?: return null
    return FollowUpVisitRequest(
        id = id,
        jobId = jobId,
        sourceVisitId = sourceVisitId,
        requestingTechnicianMembershipId = requestingTechnicianMembershipId,
        proposedStart = proposedStart,
        proposedEnd = proposedEnd,
        reason = reason,
        sameTechnicianPreferred = sameTechnicianPreferred,
        status = status,
        reviewerMembershipId = reviewerMembershipId,
        reviewedAt = reviewedAt,
        reviewNote = reviewNote,
        createdVisitId = createdVisitId,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

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
