package com.servora.android.data.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The photo types the client mirrors from the API (`D3b`, `BR-041`).
 *
 * The client decides the type of a photo it is about to record, and it must decide it the same way the
 * API will: by the bytes. These pin the mirrored magic-number rule, including the arrays a lenient
 * implementation would misread — one too short to declare anything, and one that only looks like a
 * container.
 */
class JobPhotoContentTypeTest {

    @Test
    fun `reads the type the bytes declare`() {
        assertEquals(JobPhotoContentType.JPEG, JobPhotoContentType.ofBytes(jpegBytes()))
        assertEquals(JobPhotoContentType.PNG, JobPhotoContentType.ofBytes(pngBytes()))
        assertEquals(JobPhotoContentType.WEBP, JobPhotoContentType.ofBytes(webpBytes()))
    }

    @Test
    fun `answers nothing for bytes that are not a type Servora accepts`() {
        assertNull(JobPhotoContentType.ofBytes(FakeJobPhotoFiles.UNACCEPTED_BYTES))
        // A RIFF container that is not a WebP image, which only the form type separates.
        assertNull(
            JobPhotoContentType.ofBytes(
                "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray(),
            ),
        )
    }

    @Test
    fun `answers nothing rather than failing for bytes too short to declare a type`() {
        assertNull(JobPhotoContentType.ofBytes(ByteArray(0)))
        assertNull(JobPhotoContentType.ofBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(JobPhotoContentType.ofBytes("RIFF".toByteArray()))
    }

    @Test
    fun `maps the type the API stores back to a type`() {
        assertEquals(JobPhotoContentType.JPEG, JobPhotoContentType.ofMimeType("image/jpeg"))
        assertEquals(JobPhotoContentType.PNG, JobPhotoContentType.ofMimeType("image/png"))
        assertEquals(JobPhotoContentType.WEBP, JobPhotoContentType.ofMimeType("image/webp"))
        assertNull(JobPhotoContentType.ofMimeType("image/heic"))
    }

    @Test
    fun `names the extension the API stores the object under`() {
        assertEquals("jpg", JobPhotoContentType.JPEG.extension)
        assertEquals("png", JobPhotoContentType.PNG.extension)
        assertEquals("webp", JobPhotoContentType.WEBP.extension)
    }
}

/** A minimal but real JPEG header. */
private fun jpegBytes(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

/** A minimal but real PNG signature. */
private fun pngBytes(): ByteArray = FakeJobPhotoFiles.PNG_BYTES

/** A minimal but real WebP container: `RIFF`, its size, then the `WEBP` form type. */
private fun webpBytes(): ByteArray =
    "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(4)
