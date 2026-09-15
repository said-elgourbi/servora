package com.servora.android.data.jobs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * The thumbnails the photo tray and the Job Activity gallery draw.
 *
 * A tile is a *thumbnail*, never the full image: a phone photo is several megabytes and a tray may
 * hold a session's worth of them, so each one is decoded with `inSampleSize` and the decoded result
 * is kept in a small in-memory cache. Nothing is written to storage by this layer — the bytes it
 * decodes are the technician's own pending file or the backend's evidence, read through the API on
 * the API port (`ADR-013` D7).
 *
 * A photo that cannot be decoded is reported as `null` rather than as a wrong image: the tile then
 * presents its phase, note and synchronization state without a preview (`BR-042`).
 */
interface JobPhotoImages {
    /** A thumbnail of a photo whose bytes are still on this device (`§9`). */
    suspend fun localThumbnail(path: String): ImageBitmap?

    /** A thumbnail of a photo the backend holds (`BR-015`). */
    suspend fun jobPhotoThumbnail(jobId: String, photoId: String): ImageBitmap?

    companion object {
        /**
         * An implementation that draws no previews.
         *
         * It is what a preview or a UI test uses: the tiles still present their phase, note and
         * synchronization state, because those are what the screen is responsible for (`qa.md` §6.2).
         */
        val None: JobPhotoImages = object : JobPhotoImages {
            override suspend fun localThumbnail(path: String): ImageBitmap? = null

            override suspend fun jobPhotoThumbnail(
                jobId: String,
                photoId: String,
            ): ImageBitmap? = null
        }
    }
}

/** The longest edge a thumbnail is decoded to, which keeps a tray's worth of tiles in memory. */
private const val THUMBNAIL_EDGE_PX = 512

/** The default [JobPhotoImages]: app-private files and the API, decoded as thumbnails and cached. */
@Singleton
class DefaultJobPhotoImages @Inject constructor(
    private val api: JobDetailsApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val files: JobPhotoFiles,
) : JobPhotoImages {

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHED_THUMBNAILS) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    override suspend fun localThumbnail(path: String): ImageBitmap? =
        decode(cacheKey = "local:$path", read = { files.readBytes(path) })

    override suspend fun jobPhotoThumbnail(jobId: String, photoId: String): ImageBitmap? =
        decode(cacheKey = "server:$jobId:$photoId", read = { download(jobId, photoId) })

    /** Decodes one photo, answering `null` for anything this build cannot present. */
    private suspend fun decode(
        cacheKey: String,
        read: suspend () -> ByteArray?,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(cacheKey)?.let { return@withContext it.asImageBitmap() }

        val bytes = try {
            read()
        } catch (failure: IOException) {
            null
        } catch (failure: HttpException) {
            null
        } ?: return@withContext null

        val bitmap = thumbnail(bytes) ?: return@withContext null
        cache.put(cacheKey, bitmap)
        bitmap.asImageBitmap()
    }

    /** Reads a photo's bytes from the API, renewing the session once when it is refused. */
    private suspend fun download(jobId: String, photoId: String): ByteArray? {
        val accessToken = sessionAuthenticator.accessToken() ?: return null
        return try {
            api.jobPhotoContent("Bearer $accessToken", jobId, photoId).use { it.bytes() }
        } catch (failure: HttpException) {
            if (failure.code() != HTTP_UNAUTHORIZED) {
                return null
            }
            when (val renewal = sessionAuthenticator.renew(accessToken)) {
                is SessionRenewal.Renewed ->
                    api.jobPhotoContent("Bearer ${renewal.accessToken}", jobId, photoId)
                        .use { it.bytes() }

                // A refusal, or a backend that could not be reached: the tile is drawn without a
                // preview and the next composition tries again.
                SessionRenewal.Rejected, SessionRenewal.Unavailable -> null
            }
        }
    }

    private companion object {
        /** A tray's worth of thumbnails, and no more: they are cheap to decode again. */
        const val MAX_CACHED_THUMBNAILS = 24

        const val HTTP_UNAUTHORIZED = 401
    }
}

/**
 * Decodes [bytes] into a thumbnail no longer than the tile it is drawn in.
 *
 * The sample size is decided from the image's own bounds (`jobPhotoSampleSize`), so a 12-megapixel
 * photo is not expanded into memory at full size just to be drawn as a small tile.
 */
private fun thumbnail(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = jobPhotoSampleSize(bounds, THUMBNAIL_EDGE_PX) },
    )
}
