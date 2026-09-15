package com.servora.android.data.jobs

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * Uploads a queued Job photo and records it as Activity evidence (`BR-015`, `BR-027`, `BR-031`).
 *
 * Two rules make this safe to run long after the technician captured the photo:
 *
 * - The **idempotency key** is the queued operation's own id — the photo's id — reused for every
 *   attempt, so a retry after a timeout cannot record the evidence twice (`§5`).
 * - The **bytes are read from app-private storage at replay time**, so a photo that waited hours for a
 *   connection is uploaded with exactly what the technician captured; the file is deleted only once
 *   the API has answered that it holds the photo (`§9`, `BR-014`).
 *
 * A refusal is reported as what it is: no longer authorized (`403`), the Job is gone (`404`), or the
 * request is invalid (`400`/`413`/`422`). The queued row and the local file are kept in every one of
 * those cases, so nothing the technician captured is discarded by a client decision (`BR-032`).
 */
@Singleton
internal class JobPhotoUploadHandler @Inject constructor(
    private val api: JobDetailsApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val payloads: JobPhotoPayloads,
    private val pending: PendingJobPhotoStore,
    private val files: JobPhotoFiles,
) : OfflineOperationHandler {

    override val operationTypes: Set<String> = JobPhotoOperations.ALL

    override suspend fun replay(operation: OutboxOperation): ReplayOutcome {
        val payload = payloads.decode(operation.payload)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
        val phase = jobPhotoPhaseOrNull(payload.phase)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.INVALID)
        val bytes = files.readBytes(payload.localPath)
            ?: return ReplayOutcome.Rejected(OutboxFailureReason.NOT_FOUND)

        val accessToken = sessionAuthenticator.accessToken()
            ?: return ReplayOutcome.Unauthenticated

        val attempt = send(
            accessToken = accessToken,
            jobId = operation.targetId,
            photoId = operation.operationId,
            phase = phase.name,
            payload = payload,
            bytes = bytes,
        )

        if (attempt is PhotoUploadAttempt.Applied) {
            // The backend owns the evidence now, so the device's copy is no longer the only one
            // (`§9`). The pending row goes with it, so the tray stops showing a photo the API has
            // already accepted.
            files.delete(payload.localPath)
            pending.remove(operation.operationId)
        }
        return attempt.toReplayOutcome()
    }

    /** Sends the upload, renewing the session once when the backend refuses the token. */
    private suspend fun send(
        accessToken: String,
        jobId: String,
        photoId: String,
        phase: String,
        payload: JobPhotoOperationPayload,
        bytes: ByteArray,
    ): PhotoUploadAttempt =
        try {
            api.addJobPhoto(
                authorization = "Bearer $accessToken",
                jobId = jobId,
                clientOperationId = photoId.toRequestBody(TEXT_PART.toMediaType()),
                phase = phase.toRequestBody(TEXT_PART.toMediaType()),
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
            PhotoUploadAttempt.Applied
        } catch (failure: HttpException) {
            if (failure.code() == HTTP_UNAUTHORIZED) {
                when (val renewal = sessionAuthenticator.renew(accessToken)) {
                    is SessionRenewal.Renewed ->
                        send(renewal.accessToken, jobId, photoId, phase, payload, bytes)

                    SessionRenewal.Rejected -> PhotoUploadAttempt.Unauthenticated
                    SessionRenewal.Unavailable -> PhotoUploadAttempt.Undelivered
                }
            } else {
                PhotoUploadAttempt.Refused(httpFailureReason(failure.code()))
            }
        } catch (failure: IOException) {
            PhotoUploadAttempt.Undelivered
        } catch (failure: SerializationException) {
            PhotoUploadAttempt.Refused(OutboxFailureReason.UNEXPECTED)
        }

    private companion object {
        /** The multipart part the API reads the bytes from (`POST /jobs/:id/photos`). */

/**
 * How one upload attempt ended, before the engine decides what happens to the queued row (`§6`).
 *
 * The distinction that matters is between an upload the API **refused** — an answer the technician
 * has to act on — and one that never applied, which is what makes it safe to retry.
 */
private sealed interface PhotoUploadAttempt {
    /** The API accepted the photo and recorded it. */
    data object Applied : PhotoUploadAttempt

    /** The API refused the upload; the reason does not change by trying again. */
    data class Refused(val reason: OutboxFailureReason) : PhotoUploadAttempt

    /** The request never reached the backend and may succeed later. */
    data object Undelivered : PhotoUploadAttempt

    /** The session is over; the technician signs in again before the photo can be uploaded. */
    data object Unauthenticated : PhotoUploadAttempt
}

/** What an attempt means for the row the engine is holding (`§6`). */
private fun PhotoUploadAttempt.toReplayOutcome(): ReplayOutcome =
    when (this) {
        PhotoUploadAttempt.Applied -> ReplayOutcome.Applied
        is PhotoUploadAttempt.Refused -> ReplayOutcome.Rejected(reason)
        PhotoUploadAttempt.Undelivered -> ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
        PhotoUploadAttempt.Unauthenticated -> ReplayOutcome.Unauthenticated
    }

/** Classifies a refused upload into the reason the technician is shown (`§6`, `dev.md` §7). */
private fun httpFailureReason(status: Int): OutboxFailureReason =
    when (status) {
        HTTP_BAD_REQUEST, HTTP_TOO_LARGE, HTTP_UNPROCESSABLE -> OutboxFailureReason.INVALID
        HTTP_FORBIDDEN -> OutboxFailureReason.NOT_AUTHORIZED
        HTTP_NOT_FOUND -> OutboxFailureReason.NOT_FOUND
        HTTP_CONFLICT -> OutboxFailureReason.STALE
        in HTTP_SERVER_ERROR..599 -> OutboxFailureReason.SERVER
        else -> OutboxFailureReason.UNEXPECTED
    }

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_TOO_LARGE = 413
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_SERVER_ERROR = 500

        const val FILE_PART = "file"

        /** The multipart part type for the request's text fields. */
        const val TEXT_PART = "text/plain"

        const val HTTP_UNAUTHORIZED = 401
    }
}
