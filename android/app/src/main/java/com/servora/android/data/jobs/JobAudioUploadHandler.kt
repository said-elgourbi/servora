package com.servora.android.data.jobs

import com.servora.android.data.offline.OfflineOperationHandler
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.evidencePhaseOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * Uploads a queued audio note and records it as Activity evidence (`BR-091`, `BR-031`).
 *
 * It is the same shape as the photo handler, because the API's two upload routes are the same shape:
 *
 * - The **idempotency key** is the queued operation's own id — the recording's id — reused for every
 *   attempt, so a retry after a timeout cannot record the evidence twice (`§5`).
 * - The **bytes are read from app-private storage at replay time**, so a recording that waited hours
 *   for a connection is uploaded with exactly what the technician recorded; the file is deleted only
 *   once the API has answered that it holds the recording (`§9`, `BR-014`).
 *
 * The length is not sent: the API reads it from the container and refuses a recording whose length it
 * cannot read (`ADR-018` A3), so the request carries only what the technician chose.
 *
 * A refusal is reported as what it is: no longer authorized (`403`), the Job is gone (`404`), or the
 * request is invalid (`400`/`413`/`422`) — a refusal that would be the API's answer for the container
 * itself is reported the same way, because the device cannot make the API accept bytes it refuses
 * (`ADR-018` A2). The queued row and the local file are kept in every one of those cases, so nothing the
 * technician recorded is discarded by a client decision (`BR-032`).
 */
@Singleton
internal class JobAudioUploadHandler @Inject constructor(
    private val api: JobDetailsApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val payloads: JobAudioPayloads,
    private val pending: PendingJobAudioNoteStore,
    private val files: JobAudioFiles,
) : OfflineOperationHandler {

    override val operationTypes: Set<String> = JobAudioOperations.ALL

    override suspend fun replay(operation: OutboxOperation): ReplayOutcome {
        val payload = payloads.decode(operation.payload)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        val phase = evidencePhaseOrNull(payload.phase)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.INVALID)
        val bytes = files.readBytes(payload.localPath)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.NOT_FOUND)

        val accessToken = sessionAuthenticator.accessToken()
            ?: return ReplayOutcome.Unauthenticated

        val attempt = send(
            accessToken = accessToken,
            jobId = operation.targetId,
            audioNoteId = operation.operationId,
            phase = phase,
            payload = payload,
            bytes = bytes,
        )

        if (attempt is AudioUploadAttempt.Applied) {
            // The backend owns the evidence now, so the device's copy is no longer the only one (§9).
            // The draft row goes with it, so the notice stops reporting a recording the API holds.
            files.delete(payload.localPath)
            pending.remove(operation.operationId)
        }
        return attempt.toReplayOutcome()
    }

    /** Sends the upload, renewing the session once when the backend refuses the token. */
    private suspend fun send(
        accessToken: String,
        jobId: String,
        audioNoteId: String,
        phase: EvidencePhase,
        payload: JobAudioOperationPayload,
        bytes: ByteArray,
    ): AudioUploadAttempt =
        try {
            api.addJobAudioNote(
                authorization = "Bearer $accessToken",
                jobId = jobId,
                clientOperationId = audioNoteId.toRequestBody(TEXT_PART.toMediaType()),
                phase = phase.name.toRequestBody(TEXT_PART.toMediaType()),
                note = payload.note
                    ?.takeIf { it.isNotBlank() }
                    ?.toRequestBody(TEXT_PART.toMediaType()),
                capturedAt = payload.capturedAt.toRequestBody(TEXT_PART.toMediaType()),
                file = MultipartBody.Part.createFormData(
                    name = FILE_PART,
                    filename = payload.fileName,
                    body = bytes.toRequestBody(payload.mimeType.toMediaType()),
                ),
            )
            AudioUploadAttempt.Applied
        } catch (failure: HttpException) {
            if (failure.code() == HTTP_UNAUTHORIZED) {
                when (val renewal = sessionAuthenticator.renew(accessToken)) {
                    is SessionRenewal.Renewed ->
                        send(renewal.accessToken, jobId, audioNoteId, phase, payload, bytes)

                    SessionRenewal.Rejected -> AudioUploadAttempt.Unauthenticated
                    SessionRenewal.Unavailable -> AudioUploadAttempt.Undelivered
                }
            } else {
                AudioUploadAttempt.Refused(uploadFailureReason(failure.code()))
            }
        } catch (failure: IOException) {
            AudioUploadAttempt.Undelivered
        } catch (failure: SerializationException) {
            AudioUploadAttempt.Refused(OutboxFailureReason.UNEXPECTED)
        }

    private companion object {
        /** The multipart part the API reads the bytes from (`POST /jobs/:id/audio-notes`). */
        const val FILE_PART = "file"

        /** The multipart part type for the request's text fields. */
        const val TEXT_PART = "text/plain"

        const val HTTP_UNAUTHORIZED = 401
    }
}

/**
 * How one upload attempt ended, before the engine decides what happens to the queued row (§6).
 *
 * The distinction that matters is between an upload the API **refused** — an answer the technician has
 * to act on — and one that never applied, which is what makes it safe to retry.
 */
private sealed interface AudioUploadAttempt {
    /** The API accepted the recording and recorded it. */
    data object Applied : AudioUploadAttempt

    /** The API refused the upload; the reason does not change by trying again. */
    data class Refused(val reason: OutboxFailureReason) : AudioUploadAttempt

    /** The request never reached the backend and may succeed later. */
    data object Undelivered : AudioUploadAttempt

    /** The session is over; the technician signs in again before the recording can be uploaded. */
    data object Unauthenticated : AudioUploadAttempt
}

/** What an attempt means for the row the engine is holding (§6). */
private fun AudioUploadAttempt.toReplayOutcome(): ReplayOutcome =
    when (this) {
        AudioUploadAttempt.Applied -> ReplayOutcome.Applied
        is AudioUploadAttempt.Refused -> ReplayOutcome.Rejected(reason)
        AudioUploadAttempt.Undelivered -> ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
        AudioUploadAttempt.Unauthenticated -> ReplayOutcome.Unauthenticated
    }
