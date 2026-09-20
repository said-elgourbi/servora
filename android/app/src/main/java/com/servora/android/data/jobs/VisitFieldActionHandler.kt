package com.servora.android.data.jobs

import com.servora.android.data.offline.OfflineOperationHandler
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.VisitStatus
import com.servora.android.domain.model.visitOutcomeOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * Replays a queued Visit field action: a status transition, or a note
 * (`BR-074`, `BR-077`, `BR-027`; offline standard §6).
 *
 * Two rules make this safe to run long after the technician acted:
 *
 * - The **idempotency key** is the queued operation's own id, reused for every attempt, so a replay
 *   after a timeout cannot apply the transition twice or record the note twice (`§5`, `BR-031`).
 * - The **version the technician saw** travels with the row. `ADR-019` D5 decided that a Visit
 *   transition is a **command against a state machine**: the API evaluates it under the Visit's row
 *   lock and refuses a stale version rather than applying the action "as close as possible". A queued
 *   transition is therefore never re-based onto newer state — unlike the Property lifecycle, whose
 *   own per-operation strategy re-reads the version (`§8`: the strategy is decided per operation).
 *
 * A refusal is reported as what it is and the row is kept, so nothing the technician did is discarded
 * by a client decision (`BR-014`, `BR-032`).
 */
@Singleton
internal class VisitFieldActionHandler @Inject constructor(
    private val api: JobDetailsApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val evidence: JobEvidenceCache,
    private val subject: AuthenticatedSubject,
    private val json: Json,
) : OfflineOperationHandler {

    override val operationTypes: Set<String> = VisitFieldOperationTypes.ALL

    override suspend fun replay(operation: OutboxOperation): ReplayOutcome =
        when (operation.operationType) {
            VisitFieldOperationTypes.CHANGE_STATUS -> replayChangeStatus(operation)
            VisitFieldOperationTypes.ADD_NOTE -> replayNote(operation)
            // A row this build did not queue cannot be applied by it (`BR-042`).
            else -> ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        }

    /** Applies one queued status transition, with its outcome when it is a completion. */
    private suspend fun replayChangeStatus(operation: OutboxOperation): ReplayOutcome {
        val payload = decode(VisitFieldOperationPayload.serializer(), operation.payload)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        val destination = VisitStatus.entries.firstOrNull { it.name == payload.status }
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.INVALID)

        // `BR-077` requires the outcome with the completion, and the API refuses a completion without
        // one. A queued completion whose outcome this build cannot read is therefore refused here
        // rather than sent as something the API would answer with a validation failure (`BR-078`).
        val outcome = visitOutcomeOrNull(payload.outcomeCode)
        val summary = payload.outcomeSummary?.takeIf { it.isNotBlank() }
        if (destination == VisitStatus.COMPLETED && (outcome == null || summary == null)) {
            return ReplayOutcome.Rejected(OutboxFailureReason.INVALID)
        }

        val accessToken = sessionAuthenticator.accessToken()
            ?: return ReplayOutcome.Unauthenticated

        return send(
            accessToken = accessToken,
            jobId = operation.targetId,
            visitId = payload.visitId,
            request = ChangeVisitStatusRequestDto(
                status = destination.name,
                outcomeCode = outcome?.name,
                outcomeSummary = summary,
                clientOperationId = operation.operationId,
                capturedAt = operation.capturedAt,
                expectedVersion = operation.expectedVersion,
                // Nothing is confirmed for a queued action: `BR-070` requires the conflict to be shown
                // and accepted explicitly, and a queued attempt had no one to ask. A Visit that now
                // conflicts is therefore refused and surfaced rather than silently forced through.
                confirmConflicts = false,
            ),
        )
    }

    /** Applies one queued note and keeps the refreshed timeline the API answered with (`BR-080`). */
    private suspend fun replayNote(operation: OutboxOperation): ReplayOutcome {
        val payload = decode(VisitNoteOperationPayload.serializer(), operation.payload)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        if (payload.body.isBlank()) {
            return ReplayOutcome.Rejected(OutboxFailureReason.INVALID)
        }

        val accessToken = sessionAuthenticator.accessToken()
            ?: return ReplayOutcome.Unauthenticated

        return sendNote(
            accessToken = accessToken,
            jobId = operation.targetId,
            visitId = payload.visitId,
            body = payload.body,
        )
    }

    /** Sends one note, renewing the session once when the backend refuses the token. */
    private suspend fun sendNote(
        accessToken: String,
        jobId: String,
        visitId: String,
        body: String,
    ): ReplayOutcome =
        try {
            val activity = api.addVisitNote(
                authorization = "Bearer $accessToken",
                jobId = jobId,
                visitId = visitId,
                request = AddVisitNoteRequestDto(body = body),
            )
            // The note is recorded now, so the Activity the device serves offline must not keep
            // predating it (`§2`, `BR-080`). A session that ended cannot own the copy, so nothing is
            // filed under an identity the answer did not come from.
            subject.current()?.let { subjectId ->
                evidence.rememberActivity(subjectId, jobId, activity)
            }
            ReplayOutcome.Applied
        } catch (failure: HttpException) {
            if (failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetry(accessToken) { token -> sendNote(token, jobId, visitId, body) }
            } else {
                failure.toReplayOutcome(json)
            }
        } catch (failure: IOException) {
            ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        }

    /** Sends one transition, renewing the session once when the backend refuses the token. */
    private suspend fun send(
        accessToken: String,
        jobId: String,
        visitId: String,
        request: ChangeVisitStatusRequestDto,
    ): ReplayOutcome =
        try {
            api.changeVisitStatus("Bearer $accessToken", jobId, visitId, request)
            ReplayOutcome.Applied
        } catch (failure: HttpException) {
            if (failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetry(accessToken) { token ->
                    send(token, jobId, visitId, request)
                }
            } else {
                failure.toReplayOutcome(json)
            }
        } catch (failure: IOException) {
            ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        }

    /** Renews the session once and retries the cancelled attempt, or reports why it could not. */
    private suspend fun renewAndRetry(
        rejectedToken: String,
        retry: suspend (String) -> ReplayOutcome,
    ): ReplayOutcome =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed -> retry(renewal.accessToken)
            SessionRenewal.Rejected -> ReplayOutcome.Unauthenticated
            SessionRenewal.Unavailable -> ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
        }

    /** Reads one queued payload, or `null` when this build cannot apply what the row holds. */
    private fun <T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: String,
    ): T? =
        try {
            json.decodeFromString(serializer, payload)
        } catch (failure: SerializationException) {
            null
        }
}

/**
 * What a refused replay means for the row the engine is holding (§6).
 *
 * A server fault and a request that never reached the backend may both succeed later, so they are
 * retried; everything else is the backend's answer and is retained for the user rather than re-applied
 * (`BR-032`).
 */
private fun HttpException.toReplayOutcome(json: Json): ReplayOutcome {
    val code = errorEnvelopeCode(json)
    if (code == CODE_VISIT_OPERATION_REUSED) {
        return ReplayOutcome.Rejected(OutboxFailureReason.INVALID)
    }
    return uploadFailureReason(code()).toReplayOutcome()
}

private fun OutboxFailureReason.toReplayOutcome(): ReplayOutcome =
    when (this) {
        OutboxFailureReason.NETWORK, OutboxFailureReason.SERVER -> ReplayOutcome.Retryable(this)
        OutboxFailureReason.UNAUTHENTICATED -> ReplayOutcome.Unauthenticated
        else -> ReplayOutcome.Rejected(this)
    }

/**
 * Reads the API's stable error code from a refused response, best-effort.
 *
 * The classification is already known from the HTTP status, so a body this build cannot read means the
 * refusal is reported without its code — never that it is reported as something it was not.
 */
private fun HttpException.errorEnvelopeCode(json: Json): String? =
    runCatching {
        val body = response()?.errorBody()?.string()
        if (body.isNullOrBlank()) null else json.decodeFromString<ApiErrorDto>(body).code
    }.getOrNull()

private const val HTTP_UNAUTHORIZED = 401

/** The API's stable code for an idempotency key reused on another Visit (`BR-031`). */
private const val CODE_VISIT_OPERATION_REUSED = "VISIT_OPERATION_REUSED"
