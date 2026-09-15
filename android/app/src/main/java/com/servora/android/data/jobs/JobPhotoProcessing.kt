package com.servora.android.data.jobs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The API's limit on one photo, mirrored from `MAX_JOB_PHOTO_BYTES` in `api/src/jobs/job-photo.dto.ts`
 * and `docs/api/job-photos.md` §3.3.
 *
 * The API owns the value; the client mirrors it so a photo can be prepared to fit **before** it is
 * recorded, and it deliberately does not invent a stricter limit of its own
 * (`docs/tracker/029-photo-evidence-phases.md` D3c).
 */
const val MAX_JOB_PHOTO_BYTES: Int = 15 * 1024 * 1024

/**
 * The two device-side steps a photo may need before it becomes a draft
 * (`docs/tracker/029-photo-evidence-phases.md` D3b, D3c).
 *
 * Both need the Android runtime — `BitmapFactory` to decode and `Bitmap.compress` to encode — so they
 * sit behind this port, and the rules that decide **when** each step runs stay in `JobPhotoSession`,
 * where the JVM can check them (`qa.md` §6.1). A step that cannot deliver answers `null`: the photo is
 * then refused and **nothing** is recorded, rather than a photo being created that could never upload
 * (`BR-014`).
 *
 * Each step also **turns the pixels it decoded** so what it writes is upright (`D7b`). That is not
 * cosmetic: `Bitmap.compress` writes no EXIF orientation tag, so a re-encode of a portrait capture —
 * stored as sensor-landscape pixels plus a tag — would become evidence that is sideways *and* says
 * nothing about it, in the bucket and on every client. The tag is read from the **source** bytes and
 * the turn is applied before scaling and encoding, and both the read and the turn are the shared leaf
 * `JobPhotoExifOrientation`, which is also what the display path uses, so the two cannot drift.
 */
interface JobPhotoProcessing {
    /**
     * `D3b`: [bytes] as a type Servora accepts.
     *
     * It is asked only for bytes whose type is not already one Servora accepts, and it answers with the
     * bytes re-encoded as JPEG — a format the API's sniffer and its stored extensions already know.
     */
    suspend fun convertToJpeg(bytes: ByteArray): ByteArray?

    /**
     * `D3c`: [bytes] downscaled/re-compressed to fit [MAX_JOB_PHOTO_BYTES].
     *
     * It is asked only for bytes larger than that limit, and it answers with a JPEG of the resized
     * photo: only a lossy JPEG's size can be bounded by the encoder's quality, and the decision records
     * that the stored evidence for a resized photo **is** the resized version and that the original is
     * not kept.
     */
    suspend fun fitToUploadLimit(bytes: ByteArray): ByteArray?
}

/**
 * The default [JobPhotoProcessing], using the device's own decoder and JPEG encoder.
 *
 * A photo is decoded no larger than the edge the step works at ([jobPhotoSampleSize]), so a very large
 * original is never expanded into memory at full size, and the work of both steps runs off the main
 * thread and per item (`D3b`, `D3c`).
 *
 * What it decodes is turned upright before it is scaled and encoded (`D7b`), using the orientation the
 * **source** bytes declare. A turn never changes which edge is the longest, so it leaves the sampling
 * and the published `FIT_TARGETS` measurement exactly as Phase 2 recorded them: what a resized photo is
 * stored as changes only in orientation. A turn the device cannot perform refuses the photo rather than
 * writing pixels that are known to be wrong (`BR-042`).
 */
@Singleton
class DefaultJobPhotoProcessing @Inject constructor() : JobPhotoProcessing {

    override suspend fun convertToJpeg(bytes: ByteArray): ByteArray? =
        withContext(Dispatchers.Default) {
            val upright = decodeUpright(bytes, DECODE_EDGE_PX, jobPhotoExifOrientation(bytes))
                ?: return@withContext null
            val encoded = encodeJpeg(upright, CONVERSION_QUALITY)
            upright.recycle()
            encoded
        }

    override suspend fun fitToUploadLimit(bytes: ByteArray): ByteArray? =
        withContext(Dispatchers.Default) {
            // The tag describes the source bytes every target decodes again, so it is read once.
            val orientation = jobPhotoExifOrientation(bytes)
            for (target in FIT_TARGETS) {
                val upright = decodeUpright(bytes, target.maxEdgePx, orientation)
                    ?: return@withContext null
                val scaled = upright.scaledTo(target.maxEdgePx)
                val encoded = encodeJpeg(scaled, target.quality)
                // Neither bitmap is needed once the bytes exist, and the next target decodes its own.
                if (scaled !== upright) {
                    scaled.recycle()
                }
                upright.recycle()
                if (encoded != null && encoded.size <= MAX_JOB_PHOTO_BYTES) {
                    return@withContext encoded
                }
            }
            // The photo could not be brought under the limit, so the caller records nothing: a photo
            // the API would refuse must not become a draft (`BR-014`, D3c).
            null
        }

    /**
     * Decodes [bytes] with about [maxEdgePx] on its longest edge and turns it by the orientation the
     * source declares, or `null` when either step cannot deliver.
     */
    private fun decodeUpright(
        bytes: ByteArray,
        maxEdgePx: Int,
        orientation: JobPhotoOrientation,
    ): Bitmap? = decode(bytes, maxEdgePx)?.turnedBy(orientation)

    /**
     * Decodes [bytes] with about [maxEdgePx] on its longest edge, or `null` when the bytes are not an
     * image this device can decode.
     */
    private fun decode(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = jobPhotoSampleSize(bounds, maxEdgePx) },
        )
    }

    /** The decoded photo no longer than [maxEdgePx] on either edge; the same bitmap when it fits. */
    private fun Bitmap.scaledTo(maxEdgePx: Int): Bitmap {
        val longestEdge = maxOf(width, height)
        if (longestEdge <= maxEdgePx) {
            return this
        }
        val scale = maxEdgePx.toDouble() / longestEdge
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** The JPEG the photo is encoded as, or `null` when the encoder could not produce one. */
    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            output.toByteArray()
        } else {
            null
        }
    }

    private companion object {
        /**
         * The quality a converted photo is encoded at.
         *
         * A conversion exists to make the bytes a type the API accepts, not to shrink them, so it
         * encodes once at the quality a camera normally writes.
         */
        const val CONVERSION_QUALITY = 85

        /**
         * The edge a conversion decodes at.
         *
         * The sampling rule keeps the decoded photo at least this wide and less than twice it, which
         * bounds what a conversion reads from a very large original without shrinking one that is
         * already smaller.
         */
        const val DECODE_EDGE_PX = 2048
    }
}

/**
 * One target the size step tries (`D3c`).
 *
 * The list is declared rather than computed so what a resized photo is encoded as stays reviewable:
 * the longest edge it is scaled to and the JPEG quality, strongest first. The values chosen and what
 * they measured are recorded in `docs/tracker/029-photo-evidence-phases.md` (Phase 2).
 */
private data class FitTarget(val maxEdgePx: Int, val quality: Int)

/** The targets the size step tries, in order, before it refuses the photo. */
private val FIT_TARGETS =
    listOf(
        FitTarget(maxEdgePx = 2048, quality = 85),
        FitTarget(maxEdgePx = 2048, quality = 70),
        FitTarget(maxEdgePx = 1280, quality = 70),
        FitTarget(maxEdgePx = 1024, quality = 60),
    )
