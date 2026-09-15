package com.servora.android.data.jobs

import com.servora.android.domain.model.JobPhotoPhase
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recording a photo: the type and size pipeline both sources go through before a draft exists
 * (`BR-014`, `BR-015`, `BR-027`, tracker 029 D3b/D3c).
 *
 * What is asserted is the decision the phase implements: a photo the API already accepts keeps its own
 * type, one it does not is converted, one over the limit is resized to fit, and a photo either step
 * cannot deliver is refused **with nothing recorded** — no row, and no bytes left behind. The device's
 * decoder and encoder are replaced by a fake, because the rules around them are what this slice decides
 * (`qa.md` §6.1).
 */
class JobPhotoSessionTest {

    @Test
    fun `records a photo the API already accepts under its own type`() = runTest {
        val photos = PhotoCollaborators()
        val capture = requireNotNull(photos.session.beginCapture())
        photos.files.writeCapture(capture.localPath, FakeJobPhotoFiles.PNG_BYTES)

        val recorded = photos.session.recordCapture(capture, JOB_ID, PHASE, CAPTURED_AT)

        val photo = recorded.photo()
        assertEquals("image/png", photo.mimeType)
        assertTrue(photo.localPath.endsWith(".png"))
        assertEquals(
            FakeJobPhotoFiles.PNG_BYTES.toList(),
            photos.files.bytesAt(photo.localPath)?.toList(),
        )
        // The file the camera wrote is not left behind beside the file the photo is stored as.
        assertEquals(setOf(photo.localPath), photos.files.storedPaths)
        // Neither step was needed: the bytes were already a type and a size the API accepts.
        assertEquals(0, photos.processing.conversions)
        assertEquals(0, photos.processing.fits)
    }

    @Test
    fun `converts bytes the API does not accept and records the converted type`() = runTest {
        val photos = PhotoCollaborators()
        val converted = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(8) { 0x44 }
        photos.processing.conversion = converted
        val capture = requireNotNull(photos.session.beginCapture())
        photos.files.writeCapture(capture.localPath, FakeJobPhotoFiles.UNACCEPTED_BYTES)

        val recorded = photos.session.recordCapture(capture, JOB_ID, PHASE, CAPTURED_AT)

        val photo = recorded.photo()
        assertEquals(1, photos.processing.conversions)
        assertEquals(0, photos.processing.fits)
        // The draft, its file and its recorded type all describe the converted photo (`D3b`).
        assertEquals("image/jpeg", photo.mimeType)
        assertEquals(converted.toList(), photos.files.bytesAt(photo.localPath)?.toList())
    }

    @Test
    fun `resizes a photo over the API's limit and stores the resized version`() = runTest {
        val photos = PhotoCollaborators()
        val fitted = FakeJobPhotoFiles.JPEG_BYTES + ByteArray(4) { 0x55 }
        photos.processing.fitted = fitted
        val capture = requireNotNull(photos.session.beginCapture())
        photos.files.writeCapture(capture.localPath, oversizedJpeg())

        val recorded = photos.session.recordCapture(capture, JOB_ID, PHASE, CAPTURED_AT)

        val photo = recorded.photo()
        assertEquals(0, photos.processing.conversions)
        assertEquals(1, photos.processing.fits)
        // The stored evidence is the resized version; the original is not kept (`D3c`).
        assertEquals(fitted.toList(), photos.files.bytesAt(photo.localPath)?.toList())
    }

    @Test
    fun `records nothing for a photo this device cannot convert`() = runTest {
        val photos = PhotoCollaborators()
        photos.processing.conversion = null
        val capture = requireNotNull(photos.session.beginCapture())
        photos.files.writeCapture(capture.localPath, FakeJobPhotoFiles.UNACCEPTED_BYTES)

        val recorded = photos.session.recordCapture(capture, JOB_ID, PHASE, CAPTURED_AT)

        assertEquals(JobPhotoRefusal.TYPE_NOT_ACCEPTED, recorded.reason())
        assertNothingRecorded(photos)
    }

    @Test
    fun `records nothing for a photo that cannot be brought under the limit`() = runTest {
        val photos = PhotoCollaborators()
        photos.processing.fitted = null
        val capture = requireNotNull(photos.session.beginCapture())
        photos.files.writeCapture(capture.localPath, oversizedJpeg())

        val recorded = photos.session.recordCapture(capture, JOB_ID, PHASE, CAPTURED_AT)

        assertEquals(JobPhotoRefusal.TOO_LARGE, recorded.reason())
        assertNothingRecorded(photos)
    }

    @Test
    fun `records nothing when the photo's bytes cannot be stored on the device`() = runTest {
        val photos = PhotoCollaborators()
        photos.files.writeSucceeds = false

        // A picked photo is the one that is always written: the camera's own capture is already in
        // app-private storage, so it is not rewritten when neither step changed its bytes.
        val recorded = photos.session.recordPickedPhoto(JOB_ID, FakeJobPhotoFiles.PNG_BYTES, PHASE)

        assertEquals(JobPhotoRefusal.NOT_STORED, recorded.reason())
        assertNothingRecorded(photos)
    }

    @Test
    fun `records nothing for a capture that wrote no bytes`() = runTest {
        val photos = PhotoCollaborators()
        val capture = requireNotNull(photos.session.beginCapture())

        val recorded = photos.session.recordCapture(capture, JOB_ID, PHASE, CAPTURED_AT)

        assertEquals(JobPhotoRefusal.NO_BYTES, recorded.reason())
        assertNothingRecorded(photos)
    }

    @Test
    fun `keeps the camera's own file when neither step changed the photo`() = runTest {
        val photos = PhotoCollaborators()
        val capture = requireNotNull(photos.session.beginCapture())
        photos.files.writeCapture(capture.localPath)

        val photo = photos.session.recordCapture(capture, JOB_ID, PHASE, CAPTURED_AT).photo()

        // The bytes the camera wrote are the bytes to keep, so they are not copied over themselves:
        // a write that failed would otherwise be able to destroy a valid capture (`BR-014`, §9).
        assertEquals(capture.localPath, photo.localPath)
        assertEquals(
            FakeJobPhotoFiles.JPEG_BYTES.toList(),
            photos.files.bytesAt(photo.localPath)?.toList(),
        )
        assertEquals(0, photos.files.writes)
    }

    @Test
    fun `records a picked photo through the same pipeline as a capture`() = runTest {
        val photos = PhotoCollaborators()

        val recorded = photos.session.recordPickedPhoto(JOB_ID, FakeJobPhotoFiles.PNG_BYTES, PHASE)

        val photo = recorded.photo()
        assertEquals(JOB_ID, photo.jobId)
        assertEquals("image/png", photo.mimeType)
        // The draft carries the phase in effect, exactly as a capture's does, so a photo the technician
        // does not finish reviewing can still be uploaded rather than being trapped by a missing phase
        // (`BR-027`). The review that opens next is where they change it.
        assertEquals(PHASE, photo.phase)
        // Servora records when the photo was recorded, because a picked photo carries no capture time
        // it can trust (`BR-031`, D7).
        assertEquals(TEST_CLOCK.instant().toString(), photo.capturedAt)
        assertEquals(setOf(photo.localPath), photos.files.storedPaths)
        // It is a draft, not a queued upload: nothing is submitted until the technician saves the tray.
        assertEquals(false, photo.submitted)
        assertTrue(photos.outbox.stored.isEmpty())
    }

    @Test
    fun `refuses a picked photo when no session can own it`() = runTest {
        val photos = PhotoCollaborators(subjectId = null)

        val recorded = photos.session.recordPickedPhoto(JOB_ID, FakeJobPhotoFiles.PNG_BYTES, PHASE)

        assertEquals(JobPhotoRefusal.NOT_SIGNED_IN, recorded.reason())
        assertNothingRecorded(photos)
    }

    @Test
    fun `refuses a picked item that handed over no bytes`() = runTest {
        val photos = PhotoCollaborators()

        val recorded = photos.session.recordPickedPhoto(JOB_ID, ByteArray(0), PHASE)

        // An item that handed over nothing is the pick's own refusal, not the camera's: the screen
        // reports that the photo could not be read rather than that a camera saved nothing (`BR-042`).
        assertEquals(JobPhotoRefusal.NOT_READ, recorded.reason())
        assertNothingRecorded(photos)
    }

    @Test
    fun `keeps the capture's id as the photo's identity`() = runTest {
        val photos = PhotoCollaborators()
        val capture = requireNotNull(photos.session.beginCapture())
        photos.files.writeCapture(capture.localPath, FakeJobPhotoFiles.PNG_BYTES)

        val photo = photos.session.recordCapture(capture, JOB_ID, PHASE, CAPTURED_AT).photo()

        // The idempotency key of the upload is the id the camera's target was allocated with (`BR-031`).
        assertEquals(capture.photoId, photo.photoId)
        assertNotEquals(capture.localPath, photo.localPath)
    }

    /** A capture the camera wrote as a JPEG too large for the API, as a high-resolution photo can be. */
    private fun oversizedJpeg(): ByteArray =
        FakeJobPhotoFiles.JPEG_BYTES + ByteArray(MAX_JOB_PHOTO_BYTES)

    /** Asserts a refused photo left nothing on the device (`BR-014`, offline standard §9). */
    private fun assertNothingRecorded(photos: PhotoCollaborators) {
        assertTrue(photos.store.stored.isEmpty())
        assertTrue(photos.files.storedPaths.isEmpty())
    }

    private companion object {
        const val JOB_ID = "job-1"
        val PHASE = JobPhotoPhase.DURING_WORK
        val CAPTURED_AT: Instant = Instant.parse("2026-09-15T13:04:05Z")
    }
}

/** The photo of a result that is expected to have recorded one. */
private fun JobPhotoRecordResult.photo() = (this as JobPhotoRecordResult.Recorded).photo

/** The refusal of a result that is expected to have refused one. */
private fun JobPhotoRecordResult.reason() = (this as JobPhotoRecordResult.Refused).reason
