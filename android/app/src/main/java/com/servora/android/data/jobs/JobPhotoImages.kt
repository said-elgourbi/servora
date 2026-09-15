package com.servora.android.data.jobs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * The previews the photo tray, the Job Activity gallery and the full-size viewer draw (`BR-015`,
 * `D4`).
 *
 * A tile is a *thumbnail*, never the full image: a phone photo is several megabytes and a tray may
 * hold a session's worth of them, so each one is decoded with `inSampleSize` and the decoded result
 * is kept in a small in-memory cache. The viewer asks for the photo itself instead, decoded no larger
 * than the screen it is drawn on (`JobPhotoViewer`). Nothing is written to storage by this layer — the
 * bytes it decodes are the technician's own pending file or the backend's evidence, read through the
 * API on the API port (`ADR-013` D7).
 *
 * A photo that cannot be decoded is reported as `null` rather than as a wrong image: the tile then
 * presents its phase, note and synchronization state without a preview, and the viewer says the photo
 * could not be shown (`BR-042`).
 *
 * Every preview is drawn the way the photo's own bytes say it should be presented: the file's EXIF
 * orientation is applied to the decode, because `BitmapFactory` does not apply it and a camera's
 * portrait capture is stored as landscape pixels plus that tag (`JobPhotoOrientation`). This leaf
 * layer is the one place it has to happen, so the review preview, the tray, the gallery tiles and the
 * viewer all turn a photo the same way. The bytes themselves are never rewritten: the stored evidence
 * stays exactly what the camera wrote.
 */
interface JobPhotoImages {
    /** A thumbnail of a photo whose bytes are still on this device (`§9`). */
    suspend fun localThumbnail(path: String): ImageBitmap?

    /** A thumbnail of a photo the backend holds (`BR-015`). */
    suspend fun jobPhotoThumbnail(jobId: String, photoId: String): ImageBitmap?

    /** The photo itself, for the viewer, from the bytes still on this device (`D4`, `§9`). */
    suspend fun localFullSize(path: String): ImageBitmap?

    /** The photo itself, for the viewer, from the evidence the backend holds (`D4`, `BR-015`). */
    suspend fun jobPhotoFullSize(jobId: String, photoId: String): ImageBitmap?

    companion object {
        /**
         * An implementation that draws no previews.
         *
         * It is what a preview or a UI test uses: the tiles still present their phase, note and
         * synchronization state, because those are what the screen is responsible for, and the viewer
         * reports that the photo could not be shown (`qa.md` §6.2).
         */
        val None: JobPhotoImages = object : JobPhotoImages {
            override suspend fun localThumbnail(path: String): ImageBitmap? = null

            override suspend fun jobPhotoThumbnail(
                jobId: String,
                photoId: String,
            ): ImageBitmap? = null

            override suspend fun localFullSize(path: String): ImageBitmap? = null

            override suspend fun jobPhotoFullSize(
                jobId: String,
                photoId: String,
            ): ImageBitmap? = null
        }
    }
}

/** The longest edge a thumbnail is decoded to, which keeps a tray's worth of tiles in memory. */
private const val THUMBNAIL_EDGE_PX = 512

/**
 * The longest edge the viewer decodes a photo to (`D4`).
 *
 * It is the widest screen Servora draws on — a tablet in landscape — so the photo the technician
 * tapped is never upscaled, and it is a *ceiling*: [`jobPhotoFittingSampleSize`] samples the image
 * down to fit it. A capture of 4032 px is therefore drawn at 2016 px rather than at the ~48 MB its
 * full decode would cost, which is the trade the viewer takes: it shows the evidence, and zooming into
 * it is not decided (`D4`).
 */
internal const val VIEWER_MAX_EDGE_PX = 2560

/** The default [JobPhotoImages]: app-private files and the API, decoded as previews. */
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
        decode(cacheKey = "local:$path", read = { files.readBytes(path) }, decodeBytes = ::thumbnail)

    override suspend fun jobPhotoThumbnail(jobId: String, photoId: String): ImageBitmap? =
        decode(
            cacheKey = "server:$jobId:$photoId",
            read = { download(jobId, photoId) },
            decodeBytes = ::thumbnail,
        )

    /**
     * The viewer's decode is deliberately **not** cached: the cache holds a tray's worth of small
     * bitmaps, and a single full-size photo would evict all of them for a picture the technician looks
     * at one at a time. Keeping such a photo across screens would need the caching decision `D4` left
     * open, which is recorded in `docs/tracker/029-photo-evidence-phases.md` rather than assumed here.
     */
    override suspend fun localFullSize(path: String): ImageBitmap? =
        decode(cacheKey = null, read = { files.readBytes(path) }, decodeBytes = ::viewerPhoto)

    override suspend fun jobPhotoFullSize(jobId: String, photoId: String): ImageBitmap? =
        decode(cacheKey = null, read = { download(jobId, photoId) }, decodeBytes = ::viewerPhoto)

    /** Decodes one photo, answering `null` for anything this build cannot present. */
    private suspend fun decode(
        cacheKey: String?,
        read: suspend () -> ByteArray?,
        decodeBytes: (ByteArray) -> Bitmap?,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (cacheKey != null) {
            cache.get(cacheKey)?.let { return@withContext it.asImageBitmap() }
        }

        val bytes = try {
            read()
        } catch (failure: IOException) {
            null
        } catch (failure: HttpException) {
            null
        } ?: return@withContext null

        val bitmap = decodeBytes(bytes) ?: return@withContext null
        if (cacheKey != null) {
            cache.put(cacheKey, bitmap)
        }
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
 * Decodes [bytes] into a tile preview, no larger than the tile it is drawn in.
 *
 * The sample size is decided from the image's own bounds (`jobPhotoSampleSize`), so a 12-megapixel
 * photo is not expanded into memory at full size just to be drawn as a small tile.
 */
private fun thumbnail(bytes: ByteArray): Bitmap? =
    decodeImage(bytes) { bounds -> jobPhotoSampleSize(bounds, THUMBNAIL_EDGE_PX) }

/**
 * Decodes [bytes] into the photo itself, for the viewer (`D4`).
 *
 * The bound is a ceiling rather than a floor (`jobPhotoFittingSampleSize`), so the photo is drawn at
 * its own resolution up to the widest screen Servora supports and never at a size the device cannot
 * hold. A photo whose bytes are not an image this build can decode is `null`, and the viewer says so
 * instead of drawing something else (`BR-042`).
 */
private fun viewerPhoto(bytes: ByteArray): Bitmap? =
    decodeImage(bytes) { bounds ->
        jobPhotoFittingSampleSize(bounds.outWidth, bounds.outHeight, VIEWER_MAX_EDGE_PX)
    }

/**
 * Reads the image's own bounds, then decodes it with the sample size [sampleSizeFor] decides, and
 * presents it the way the photo's own EXIF orientation says it should be.
 *
 * The sample size is chosen from the stored bounds, which is unchanged by the orientation: a turn
 * never changes which edge is the longest, so the ceiling [sampleSizeFor] enforces still holds after
 * it.
 */
private fun decodeImage(bytes: ByteArray, sampleSizeFor: (BitmapFactory.Options) -> Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds) },
    ) ?: return null
    return decoded.oriented(jobPhotoExifOrientation(bytes))
}

/**
 * The orientation the photo's own bytes declare, or `Normal` when the file states none.
 *
 * `BitmapFactory` does not read EXIF at all, which is why a portrait capture has to be turned here.
 * A file that carries no readable tag — a photo that was stripped of its metadata, a format the
 * device cannot parse EXIF from, or bytes that are not an image — answers `Normal`: the photo is drawn
 * as stored rather than guessed into a rotation (`BR-042`). The read is wrapped because a malformed
 * tag is a property of the file, not a failure of the screen: a photo whose metadata cannot be read is
 * still a photo.
 */
private fun jobPhotoExifOrientation(bytes: ByteArray): JobPhotoOrientation {
    val code = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return JobPhotoOrientation.of(code)
}

/**
 * The photo turned so it is presented upright, or `null` when it cannot be turned.
 *
 * It copies nothing when the photo is already upright, which is what an ordinary photo is. The turned
 * copy is what the caller keeps: the unturned original is released here so a photo is never held
 * twice. A turn the device cannot perform is answered `null` — the same answer as a photo that cannot
 * be decoded — because presenting the unturned pixels instead would be exactly the wrong image the
 * caller must not draw (`BR-042`).
 */
private fun Bitmap.oriented(orientation: JobPhotoOrientation): Bitmap? {
    if (orientation.isIdentity) {
        return this
    }
    return runCatching {
        val matrix = Matrix().apply {
            postRotate(orientation.rotationDegrees.toFloat())
            if (orientation.mirror) {
                postScale(-1f, 1f)
            }
        }
        val turned = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        recycle()
        turned
    }.getOrNull()
}
