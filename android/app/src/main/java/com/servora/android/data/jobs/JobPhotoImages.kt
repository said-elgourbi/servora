package com.servora.android.data.jobs

import android.content.Context
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.servora.android.data.session.AuthenticatedSubject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The image stack the photo tray, the Job Activity gallery, the review preview and the full-size
 * viewer draw with (`D4b`, `BR-015`, `BR-027`).
 *
 * It answers one question — *what* a tile, a preview or the viewer should load — and decodes nothing
 * itself: the caller hands the answer to the stack's own composable, and the library fetches, samples,
 * turns by the photo's own EXIF orientation, caches and draws it (`ADR-016`). That is what makes a
 * photo decoded once at the size it is drawn, and read again from memory or from disk instead of from
 * the API on every cold start.
 *
 * The bytes are read by [JobPhotoFetcher], which reads the device's own file for a photo that has not
 * been accepted yet and the API — with the session renewal it has always had — for the evidence the
 * backend holds (`BR-007`, `BR-015`).
 *
 * An answer of `null` means there is nothing to draw: an implementation that draws no previews (what a
 * preview and a UI test use) or a session the photo cannot be attributed to. The caller then presents
 * its own "cannot be shown" state rather than a different photo (`BR-042`).
 */
interface JobPhotoImages {
    /** What a tile draws for a photo whose bytes are still on this device (§9). */
    fun localThumbnail(path: String): ImageRequest?

    /** What a tile draws for a photo the backend holds (`BR-015`). */
    fun jobPhotoThumbnail(jobId: String, photoId: String): ImageRequest?

    /** What the viewer loads for a photo whose bytes are still on this device (`D4`, §9). */
    fun localFullSize(path: String): ImageRequest?

    /** What the viewer loads for the evidence the backend holds (`D4`, `BR-015`). */
    fun jobPhotoFullSize(jobId: String, photoId: String): ImageRequest?

    companion object {
        /**
         * An implementation that draws no previews.
         *
         * It is what a preview or a UI test uses: the tiles still present their phase, note and
         * synchronization state, because those are what the screen is responsible for, and the viewer
         * reports that the photo could not be shown (`qa.md` §6.2).
         */
        val None: JobPhotoImages = object : JobPhotoImages {
            override fun localThumbnail(path: String): ImageRequest? = null

            override fun jobPhotoThumbnail(jobId: String, photoId: String): ImageRequest? = null

            override fun localFullSize(path: String): ImageRequest? = null

            override fun jobPhotoFullSize(jobId: String, photoId: String): ImageRequest? = null
        }
    }
}

/**
 * The longest edge a thumbnail preview is decoded at, which keeps a tray's worth of tiles in memory.
 *
 * It is the size the retired hand-rolled decode asked for, and the library reaches it the same way — a
 * power-of-two sample of the photo's own bounds — so a 12-megapixel capture is still decoded at roughly
 * 1000 px for a tile rather than at full size (`D4b`).
 */
internal const val THUMBNAIL_EDGE_PX = 512

/**
 * The longest edge the viewer's photo is kept at (`D4`).
 *
 * It is the widest screen Servora draws on — a tablet in landscape — so what the technician tapped is
 * never drawn from a decode larger than the device family can present.
 *
 * The stack is asked for **half** of it, because the library samples in powers of two (`BitmapFactory`'s
 * `inSampleSize`): requesting the ceiling itself would leave a 4032 px capture — the case the bound
 * exists for — sampled not at all, and the phone would hold the ~48 MB full decode the viewer's bound
 * was introduced to avoid. Asking for half of it makes the library halve that capture to 2016 px, keeps
 * either orientation under the ceiling, and never upscales a photo that is already small. Handing
 * sampling to the library is the decision (`D4b`); this is how the bound it must respect is expressed
 * to it.
 */
internal const val VIEWER_DECODE_EDGE_PX = 2560 / 2

/** The default [JobPhotoImages]: the app's image stack, reading the device and the API (`D4b`). */
@Singleton
class DefaultJobPhotoImages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subject: AuthenticatedSubject,
) : JobPhotoImages {

    override fun localThumbnail(path: String): ImageRequest? =
        request(JobPhotoImage.Local(path), THUMBNAIL_EDGE_PX)

    override fun jobPhotoThumbnail(jobId: String, photoId: String): ImageRequest? =
        request(JobPhotoImage.Backend(jobId, photoId), THUMBNAIL_EDGE_PX)

    override fun localFullSize(path: String): ImageRequest? =
        request(JobPhotoImage.Local(path), VIEWER_DECODE_EDGE_PX)

    override fun jobPhotoFullSize(jobId: String, photoId: String): ImageRequest? =
        request(JobPhotoImage.Backend(jobId, photoId), VIEWER_DECODE_EDGE_PX)

    /**
     * What the stack loads [image] with, at the decode size the call site asked for, or `null` when
     * there is no session to read the photo under.
     *
     * The subject is read here because it is what partitions the cache ([jobPhotoImageCacheKey]), and a
     * session that cannot be read is a session the bytes cannot be attributed to: no request is built
     * rather than one that could be served from another member's cache (`BR-007`).
     *
     * `Precision.INEXACT` is deliberate: the library samples the decode and the composable scales what
     * it received to the size it is drawn at, so a photo is never upscaled into a larger bitmap than
     * the bytes it came from — the sampling the retired hand-rolled rules did for themselves
     * (`JobPhotoSampling`, `D4b`). The disk cache is keyed for evidence only: a photo still in
     * app-private storage is read from the file that already holds it, so caching those bytes a second
     * time would add nothing (§9).
     */
    private fun request(image: JobPhotoImage, edgePx: Int): ImageRequest? {
        val subjectId = subject.current() ?: return null
        val cacheKey = jobPhotoImageCacheKey(subjectId, image)
        val builder = ImageRequest.Builder(context)
            .data(image)
            .size(edgePx)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .memoryCacheKey(cacheKey)
        if (image is JobPhotoImage.Backend) {
            builder.diskCacheKey(cacheKey)
        }
        return builder.build()
    }
}
