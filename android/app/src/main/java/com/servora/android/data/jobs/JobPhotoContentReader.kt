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
 * A session the API refuses is renewed **once**, exactly as every other read of evidence renews: a
 * photo that still cannot be read — a refusal, or a backend that could not be reached — is answered
 * with nothing rather than with an exception, because a caller that has no bytes has nothing to draw
 * or to write (`BR-007`, `BR-042`).
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

    /** The evidence's bytes, or `null` when they cannot be read by this session. */
    suspend fun read(jobId: String, photoId: String): JobPhotoContentBytes? {
        val accessToken = sessionAuthenticator.accessToken() ?: return null
        return try {
            read(jobId, photoId, accessToken)
        } catch (failure: HttpException) {
            if (failure.code() != HTTP_UNAUTHORIZED) {
                return null
            }
            when (val renewal = sessionAuthenticator.renew(accessToken)) {
                is SessionRenewal.Renewed ->
                    try {
                        read(jobId, photoId, renewal.accessToken)
                    } catch (failure: HttpException) {
                        null
                    } catch (failure: IOException) {
                        null
                    }

                SessionRenewal.Rejected, SessionRenewal.Unavailable -> null
            }
        } catch (failure: IOException) {
            null
        }
    }

    private suspend fun read(
        jobId: String,
        photoId: String,
        accessToken: String,
    ): JobPhotoContentBytes =
        api.jobPhotoContent("Bearer $accessToken", jobId, photoId).use { body ->
            JobPhotoContentBytes(
                bytes = body.bytes(),
                contentType = body.contentType()?.toString()?.let { served ->
                    JobPhotoContentType.ofMimeType(served)
                },
            )
        }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}

/** One evidence photo's bytes as the backend served them, with the type it declared for them. */
class JobPhotoContentBytes(
    val bytes: ByteArray,
    val contentType: JobPhotoContentType?,
)
