package com.servora.android.data.schedule

import com.servora.android.data.offline.OfflineOperationHandler
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Replays a queued ad-hoc work report submission (`BR-AH-001`, offline standard §6).
 *
 * The idempotency key is the queued operation's own id, reused for every attempt, so a replay after a
 * timeout cannot create a second report (`§5`, `BR-031`). The API owns whether the report applied: a
 * replayed request carrying a key it has already recorded is answered idempotently rather than
 * inserted again.
 */
@Singleton
internal class AdHocWorkReportSubmitHandler @Inject constructor(
    private val api: AdHocWorkReportsApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val offline: AdHocWorkReportOfflineStore,
) : OfflineOperationHandler {

    override val operationTypes: Set<String> = AdHocWorkReportOperationTypes.ALL

    override suspend fun replay(operation: OutboxOperation): ReplayOutcome {
        val payload = offline.queuedPayload(operation)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return ReplayOutcome.Unauthenticated

        val request = SubmitAdHocWorkReportRequestDto(
            customerId = payload.customerId,
            propertyId = payload.propertyId,
            knownJobId = payload.knownJobId,
            workStartedAt = payload.workStartedAt,
            workEndedAt = payload.workEndedAt,
            outcomeCode = payload.outcomeCode,
            summary = payload.summary,
            notes = payload.notes,
            reportedCustomerName = payload.reportedCustomerName,
            reportedCustomerPhone = payload.reportedCustomerPhone,
            reportedCustomerAddress = payload.reportedCustomerAddress,
            clientOperationId = operation.operationId,
        )
        return send(accessToken, request)
    }

    /** Sends one queued submission, renewing the session once when it is refused. */
    private suspend fun send(
        accessToken: String,
        request: SubmitAdHocWorkReportRequestDto,
    ): ReplayOutcome =
        try {
            api.submit("Bearer $accessToken", request)
            ReplayOutcome.Applied
        } catch (failure: HttpException) {
            if (failure.code() == HTTP_UNAUTHORIZED) {
                when (val renewal = sessionAuthenticator.renew(accessToken)) {
                    is SessionRenewal.Renewed -> send(renewal.accessToken, request)
                    SessionRenewal.Rejected -> ReplayOutcome.Unauthenticated
                    SessionRenewal.Unavailable ->
                        ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
                }
            } else {
                failure.toReplayOutcome()
            }
        } catch (failure: IOException) {
            ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        }
}

private const val HTTP_UNAUTHORIZED = 401

/** What a refused submission means for the row the engine is holding (§6). */
private fun HttpException.toReplayOutcome(): ReplayOutcome =
    when (code()) {
        400, 422 -> ReplayOutcome.Rejected(OutboxFailureReason.INVALID)
        401 -> ReplayOutcome.Unauthenticated
        403 -> ReplayOutcome.Rejected(OutboxFailureReason.NOT_AUTHORIZED)
        404 -> ReplayOutcome.Rejected(OutboxFailureReason.NOT_FOUND)
        in 500..599 -> ReplayOutcome.Retryable(OutboxFailureReason.SERVER)
        else -> ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
    }
