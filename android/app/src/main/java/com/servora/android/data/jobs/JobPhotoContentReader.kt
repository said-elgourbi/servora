package com.servora.android.data.jobs

import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

/**
 * Reads one evidence photo's bytes from the backend (`BR-015`, `BR-018`, `BR-007`).
 *
 * It is the one place in the app that downloads evidence, used by both the reader that draws a photo
 * for the image stack ([JobPhotoFetcher]) and the reader that takes a photo out of Servora
 * ([JobPhotoExportContent]). Evidence is read through the API on the API port, so no storage endpoint,
 * bucket or signature host is configured in or visible to the client (`ADR-013` D7).
 *
 * A session the API refuses is renewed **once**, exactly as every other read of evidence renews. What
 * the caller is told when the bytes do not arrive is **why** ([JobPhotoContentRead]): a photo is
 * readable offline only when this device already holds it, so a read that did not reach the backend and
 * a read the backend refused are different things to say to a technician (`D5`, `BR-013`, `BR-042`).
 *
 * The type is what the API served the bytes as; which type the bytes *are* is proven from the bytes
 * themselves by the caller ([JobPhotoContentType.ofBytes]), which is the rule the upload path uses
 * (`BR-041`).
 */
@Singleton
class JobPhotoContentReader @Inject constructor(
    private val api: JobDetailsApi,
    private val sessionAuthenticator: SessionAuthenticator,
) {

    /** The evidence's bytes, or why this session did not get them. */
    suspend fun read(jobId: String, photoId: String): JobPhotoContentRead {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return JobPhotoContentRead.Unavailable
        return try {
            read(jobId, photoId, accessToken)
        } catch (failure: HttpException) {
            when {
                failure.code() == HTTP_UNAUTHORIZED ->
                    renewAndRead(accessToken, jobId, photoId)

                // A backend that answered with a server failure did not deliver the photo; it is a
                // reachability failure, exactly as the offline standard classifies it (`§13`).
                failure.code() >= HTTP_SERVER_ERROR -> JobPhotoContentRead.Unreachable

                else -> JobPhotoContentRead.Unavailable
            }
        } catch (failure: IOException) {
            JobPhotoContentRead.Unreachable
        }
    }

    /** Reads the photo once more with a renewed session, or reports what the renewal itself meant. */
    private suspend fun renewAndRead(
        rejectedToken: String,
        jobId: String,
        photoId: String,
    ): JobPhotoContentRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                try {
                    read(jobId, photoId, renewal.accessToken)
                } catch (failure: HttpException) {
                    JobPhotoContentRead.Unavailable
                } catch (failure: IOException) {
                    JobPhotoContentRead.Unreachable
                }

            SessionRenewal.Rejected -> JobPhotoContentRead.Unavailable
            SessionRenewal.Unavailable -> JobPhotoContentRead.Unreachable
        }

    private suspend fun read(
        jobId: String,
        photoId: String,
        accessToken: String,
    ): JobPhotoContentRead =
        api.jobPhotoContent("Bearer $accessToken", jobId, photoId).use { body ->
            JobPhotoContentRead.Bytes(
                bytes = body.bytes(),
                contentType = body.contentType()?.toString()?.let { served ->
                    JobPhotoContentType.ofMimeType(served)
                },
            )
        }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * What one evidence read delivered (`D5`, `BR-042`).
 *
 * The distinction exists because it is what a technician can act on: evidence this device does not
 * hold is readable offline **only** when the device already has it, so "the backend could not be
 * reached" is a state field work can wait out, while an answer the backend gave is not.
 */
sealed interface JobPhotoContentRead {
    /** The backend served the photo's bytes, with the type it declared for them. */
    data class Bytes(
        val bytes: ByteArray,
        val contentType: JobPhotoContentType?,
    ) : JobPhotoContentRead {
        // The bytes are the photo itself, so two reads of the same photo are equal data rather than
        // the same instance; the generated `equals` would compare array identity (`dev.md` §1).
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Bytes && contentType == other.contentType && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + (contentType?.hashCode() ?: 0)
    }

    /**
     * The read never got an answer: no connectivity, a server failure, or a renewal that was never
     * answered. Nothing is known about the photo, and nothing on the device holds it (`BR-013`).
     */
    data object Unreachable : JobPhotoContentRead

    /**
     * The backend answered, or no session could ask it: no session is held, the request was refused, or
     * the photo is not there for this session. A local copy is not substituted for it (`BR-007`).
     */
    data object Unavailable : JobPhotoContentRead
}
