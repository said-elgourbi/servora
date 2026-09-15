package com.servora.android.data.customers

import com.servora.android.data.offline.OfflineOperationHandler
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Replays a queued Property archive or restore (`BR-086`).
 *
 * Two rules make this safe to run long after the user acted:
 *
 * - The **idempotency key** is the queued operation's own id, reused for every attempt, so a replay
 *   after a timeout cannot apply the action twice (§5).
 * - The **expected version** is read from the API immediately before the mutation, not taken from the
 *   row. The version the screen saw may be hours old, and it is behind the subject's own earlier
 *   queued operations, so using it would turn the user's own sequence into a version conflict. The
 *   guard still holds where it matters: a change that lands between this read and the write is refused
 *   by the API (`BR-086`). This is the per-operation conflict strategy §8 leaves open; it is recorded
 *   in `docs/decisions/014-android-offline-engine.md` rather than inferred from another entity.
 */
@Singleton
internal class PropertyLifecycleHandler @Inject constructor(
    private val api: PropertiesApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val offline: PropertyOfflineStore,
    private val subject: AuthenticatedSubject,
) : OfflineOperationHandler {

    override val operationTypes: Set<String> = PropertyOperationTypes.ALL

    override suspend fun replay(operation: OutboxOperation): ReplayOutcome {
        val action = PropertyLifecycleAction.of(operation.operationType)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        val payload = offline.queuedPayload(operation)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        val subjectId = subject.current() ?: return ReplayOutcome.Unauthenticated
        val accessToken = sessionAuthenticator.accessToken()
            ?: return ReplayOutcome.Unauthenticated

        // The version the Property is at right now, so the subject's own earlier queued operations
        // do not conflict with this one.
        val current =
            when (val read = readDetail(accessToken, payload.customerId, operation.targetId)) {
                is Attempt.Answered -> read.row
                is Attempt.Refused -> return read.reason.toReplayOutcome()
                Attempt.Undelivered -> return ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
                Attempt.Unauthenticated -> return ReplayOutcome.Unauthenticated
            }

        val request = PropertyLifecycleRequest(
            note = payload.note,
            clientOperationId = operation.operationId,
            capturedAt = operation.capturedAt,
            expectedVersion = current.version,
        )
        val outcome = send(
            accessToken = accessToken,
            customerId = payload.customerId,
            propertyId = operation.targetId,
            request = request,
            archive = action == PropertyLifecycleAction.ARCHIVE,
        )

        if (outcome is Attempt.Answered) {
            offline.remember(subjectId, payload.customerId, outcome.row)
        }
        return outcome.toReplayOutcome()
    }

    /** Reads the Property's current values, renewing the session once when it is refused. */
    private suspend fun readDetail(
        accessToken: String,
        customerId: String,
        propertyId: String,
    ): Attempt =
        try {
            Attempt.Answered(api.detail("Bearer $accessToken", customerId, propertyId))
        } catch (failure: HttpException) {
            if (failure.code() == HTTP_UNAUTHORIZED) {
                when (val renewal = sessionAuthenticator.renew(accessToken)) {
                    is SessionRenewal.Renewed ->
                        readDetail(renewal.accessToken, customerId, propertyId)

                    SessionRenewal.Rejected -> Attempt.Unauthenticated
                    SessionRenewal.Unavailable -> Attempt.Undelivered
                }
            } else {
                Attempt.Refused(httpFailureReason(failure.code()))
            }
        } catch (failure: IOException) {
            Attempt.Undelivered
        } catch (failure: SerializationException) {
            Attempt.Refused(CustomersFailureReason.UNEXPECTED)
        }

    /** Applies the lifecycle transition, renewing the session once when it is refused. */
    private suspend fun send(
        accessToken: String,
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
        archive: Boolean,
    ): Attempt =
        try {
            Attempt.Answered(
                if (archive) {
                    api.archive("Bearer $accessToken", customerId, propertyId, request)
                } else {
                    api.restore("Bearer $accessToken", customerId, propertyId, request)
                },
            )
        } catch (failure: HttpException) {
            if (failure.code() == HTTP_UNAUTHORIZED) {
                when (val renewal = sessionAuthenticator.renew(accessToken)) {
                    is SessionRenewal.Renewed ->
                        send(renewal.accessToken, customerId, propertyId, request, archive)

                    SessionRenewal.Rejected -> Attempt.Unauthenticated
                    SessionRenewal.Unavailable -> Attempt.Undelivered
                }
            } else {
                Attempt.Refused(httpFailureReason(failure.code()))
            }
        } catch (failure: IOException) {
            Attempt.Undelivered
        } catch (failure: SerializationException) {
            Attempt.Refused(CustomersFailureReason.UNEXPECTED)
        }
}

private const val HTTP_UNAUTHORIZED = 401
