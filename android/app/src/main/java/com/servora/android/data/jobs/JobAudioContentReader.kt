package com.servora.android.data.jobs

import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

/**
 * Reads one recording's bytes from the backend (`BR-091`, `BR-018`, `BR-007`).
 *
 * It is the one place in the app that downloads audio evidence, used by the cache that plays a
 * recording the backend holds ([JobAudioEvidenceCache]). Evidence is read through the API on the API
 * port, so no storage endpoint, bucket or signature host is configured in or visible to the client
 * (`ADR-013` D7).
 *
 * It is the audio kind's own reader rather than the photo one generalized: the two kinds are read on
 * their own routes, and `ADR-018` A1 keeps evidence per kind at every layer above storage — the same
 * reason the two kinds have their own tables, capabilities and Activity codes. What the caller is told
 * when the bytes do not arrive is **why** ([JobAudioContentRead]): a recording is playable offline only
 * when this device already holds it, so a read that never reached the backend and one the backend
 * refused are different things to say to a technician (`BR-013`, `BR-042`).
 *
 * The response is **streamed**, so its bytes are read by the caller of [read] rather than by the call
 * itself — a read that must not be performed on the main thread (`dev.md` §10). Its one caller, the
 * playback cache, supplies the IO dispatcher.
 */
@Singleton
class JobAudioContentReader @Inject constructor(
    private val api: JobDetailsApi,
    private val sessionAuthenticator: SessionAuthenticator,
) {

    /** The recording's bytes, or why this session did not get them. */
    suspend fun read(jobId: String, audioNoteId: String): JobAudioContentRead {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return JobAudioContentRead.Unavailable
        return try {
            read(jobId, audioNoteId, accessToken)
        } catch (failure: HttpException) {
            when {
                failure.code() == HTTP_UNAUTHORIZED ->
                    renewAndRead(accessToken, jobId, audioNoteId)

                // A backend that answered with a server failure did not deliver the recording; it is a
                // reachability failure, exactly as the offline standard classifies it (`§13`).
                failure.code() >= HTTP_SERVER_ERROR -> JobAudioContentRead.Unreachable

                else -> JobAudioContentRead.Unavailable
            }
        } catch (failure: IOException) {
            JobAudioContentRead.Unreachable
        }
    }

    /** Reads the recording once more with a renewed session, or reports what the renewal meant. */
    private suspend fun renewAndRead(
        rejectedToken: String,
        jobId: String,
        audioNoteId: String,
    ): JobAudioContentRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                try {
                    read(jobId, audioNoteId, renewal.accessToken)
                } catch (failure: HttpException) {
                    JobAudioContentRead.Unavailable
                } catch (failure: IOException) {
                    JobAudioContentRead.Unreachable
                }

            SessionRenewal.Rejected -> JobAudioContentRead.Unavailable
            SessionRenewal.Unavailable -> JobAudioContentRead.Unreachable
        }

    private suspend fun read(
        jobId: String,
        audioNoteId: String,
        accessToken: String,
    ): JobAudioContentRead =
        api.jobAudioNoteContent("Bearer $accessToken", jobId, audioNoteId).use { body ->
            JobAudioContentRead.Bytes(body.bytes())
        }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * What one audio evidence read delivered (`BR-013`, `BR-042`).
 *
 * The distinction exists because it is what a technician can act on: a recording this device does not
 * hold is playable offline **only** when the device already has it, so "the backend could not be
 * reached" is a state field work can wait out, while an answer the backend gave is not.
 */
sealed interface JobAudioContentRead {
    /** The backend served the recording's bytes. */
    class Bytes(val bytes: ByteArray) : JobAudioContentRead {
        // The bytes are the recording itself, so two reads of the same recording are equal data rather
        // than the same instance; the generated `equals` would compare array identity (`dev.md` §1).
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * The read never got an answer: no connectivity, a server failure, or a renewal that was never
     * answered. Nothing is known about the recording, and nothing on the device holds it (`BR-013`).
     */
    data object Unreachable : JobAudioContentRead

    /**
     * The backend answered, or no session could ask it: no session is held, the request was refused, or
     * the recording is not there for this session — including one that has been removed (`BR-089`). A
     * local copy is not substituted for it (`BR-007`).
     */
    data object Unavailable : JobAudioContentRead
}
