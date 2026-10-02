package com.servora.android.data.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestMessage
import com.servora.android.domain.model.FollowUpVisitRequestMessageAuthorKind
import com.servora.android.domain.model.ScheduleAddress
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Reads and reviews follow-up Visit requests. */
interface VisitRequestsRepository {
    suspend fun loadRequests(): VisitRequestsResult

    /**
     * Returns [request] to its requester for clarification, recording [note] on the request.
     *
     * The note is the decision's own record (`BR-FV-013`): it is what the office says it still needs
     * before it can decide. A blank note is sent as no note at all, because the route's `note` is
     * optional (`docs/api/visit-requests.md`) and an empty string is not a statement.
     */
    suspend fun askForClarification(
        request: FollowUpVisitRequest,
        note: String,
    ): VisitRequestReviewResult

    /**
     * Refuses [request], recording [note] as why it was refused (`BR-FV-013`).
     *
     * A rejection closes the request without creating a Visit, so the note is the only thing that can
     * carry the reason it was refused. A blank note is sent as no note at all, exactly as for a
     * clarification: the client asks for one where its own confirmation requires it.
     */
    suspend fun reject(request: FollowUpVisitRequest, note: String): VisitRequestReviewResult

    /**
     * Answers [request], which the office returned for clarification (`BR-FV-012`).
     *
     * It is the requester's own action — the route accepts a technician's capability and answers a
     * request raised by another member as not found — and it is **one** operation: the answer joins the
     * request's conversation and the request returns to `PENDING`, the state the office reviews it in.
     * The answer returned is the API's own, so a screen presents the status and the conversation the
     * backend holds rather than its own expectation (`BR-001`).
     *
     * A blank [body] is never sent: the route requires an answer to say something, and the composer
     * refuses to send one rather than letting the API refuse it later.
     */
    suspend fun reply(request: FollowUpVisitRequest, body: String): VisitRequestReviewResult
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
        note: String,
    ): VisitRequestReviewResult =
        review(request = request, action = ReviewAction.CLARIFY, note = note)

    override suspend fun reject(
        request: FollowUpVisitRequest,
        note: String,
    ): VisitRequestReviewResult =
        review(request = request, action = ReviewAction.REJECT, note = note)

    override suspend fun reply(
        request: FollowUpVisitRequest,
        body: String,
    ): VisitRequestReviewResult {
        val answer = body.trim()
        if (answer.isEmpty()) {
            // The route requires an answer that says something (`docs/api/visit-requests.md`) and the
            // composer refuses to send a blank one, so nothing is sent: asking the API to refuse what the
            // screen already knows is not a round trip worth making (`BR-042`).
            return VisitRequestReviewResult.Failure(CustomersFailureReason.VALIDATION)
        }
        val accessToken = sessionAuthenticator.accessToken()
            ?: return VisitRequestReviewResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return when (
            val answered = answer(
                accessToken = accessToken,
                allowRenewal = true,
                request = request,
                body = answer,
            )
        ) {
            is ReviewRead.Answered -> VisitRequestReviewResult.Success(answered.request)
            is ReviewRead.Failed -> VisitRequestReviewResult.Failure(answered.reason)
        }
    }

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
        note: String,
    ): VisitRequestReviewResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return VisitRequestReviewResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return when (
            val reviewed = review(
                accessToken = accessToken,
                allowRenewal = true,
                request = request,
                action = action,
                note = note,
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
        note: String,
    ): ReviewRead =
        try {
            val body = FollowUpVisitReviewRequestDto(
                // The reviewer's own words are the decision's record (`BR-FV-013`). A blank note is
                // sent as no note rather than as an empty statement.
                note = note.trim().takeIf { it.isNotEmpty() },
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
                renewAndReview(
                    rejectedToken = accessToken,
                    request = request,
                    action = action,
                    note = note,
                )
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
        note: String,
    ): ReviewRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed -> review(
                accessToken = renewal.accessToken,
                allowRenewal = false,
                request = request,
                action = action,
                note = note,
            )

            SessionRenewal.Rejected -> ReviewRead.Failed(CustomersFailureReason.UNAUTHENTICATED)
            SessionRenewal.Unavailable -> ReviewRead.Failed(CustomersFailureReason.NETWORK)
        }

    /**
     * Sends one answer and reports what the API answered.
     *
     * The renewal is handled here rather than in its own method, because an expired session is the only
     * failure that makes a second attempt worth making and this flow has no other reason to be split
     * (`BR-013`: a failed write is reported, never queued, and this route carries no idempotency key).
     */
    private suspend fun answer(
        accessToken: String,
        allowRenewal: Boolean,
        request: FollowUpVisitRequest,
        body: String,
    ): ReviewRead =
        try {
            val answered = api.reply(
                authorization = "Bearer $accessToken",
                jobId = request.jobId,
                requestId = request.id,
                request = FollowUpVisitReplyRequestDto(
                    body = body,
                    expectedStatus = request.status.name,
                    expectedVersion = request.version,
                ),
            ).toRequest() ?: return ReviewRead.Failed(CustomersFailureReason.UNEXPECTED)
            ReviewRead.Answered(answered)
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                when (val renewal = sessionAuthenticator.renew(accessToken)) {
                    is SessionRenewal.Renewed -> answer(
                        accessToken = renewal.accessToken,
                        allowRenewal = false,
                        request = request,
                        body = body,
                    )

                    SessionRenewal.Rejected ->
                        ReviewRead.Failed(CustomersFailureReason.UNAUTHENTICATED)

                    SessionRenewal.Unavailable ->
                        ReviewRead.Failed(CustomersFailureReason.NETWORK)
                }
            } else {
                ReviewRead.Failed(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            ReviewRead.Failed(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ReviewRead.Failed(CustomersFailureReason.UNEXPECTED)
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
    val conversation = messages.map { it.toMessage() ?: return null }
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
        messages = conversation,
        jobNumber = jobNumber,
        jobTitle = jobTitle,
        customerName = customerName,
        address = address?.toRequestAddress(),
        sourceVisitScheduledStart = sourceVisitScheduledStart,
        sourceVisitStatus = sourceVisitStatus,
        sourceVisitOutcomeCode = sourceVisitOutcomeCode,
    )
}


private fun ScheduleAddressDto.toRequestAddress(): ScheduleAddress =
    ScheduleAddress(
        propertyName = propertyName,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        province = province,
        postalCode = postalCode,
        country = country,
    )
/**
 * One conversation message, or `null` when this build cannot read the side that wrote it.
 *
 * An author kind the contract stops naming is not a message this client can present truthfully, exactly
 * as an unknown request status is not a request it can present (`BR-041`, `BR-042`): the read is
 * reported as unexpected rather than drawn with the wrong speaker.
 */
private fun FollowUpVisitRequestMessageDto.toMessage(): FollowUpVisitRequestMessage? {
    val kind = FollowUpVisitRequestMessageAuthorKind.entries.firstOrNull { it.name == authorKind }
        ?: return null
    return FollowUpVisitRequestMessage(
        id = id,
        authorKind = kind,
        body = body,
        recordedAt = recordedAt,
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
