package com.servora.android.ui.jobs

import com.servora.android.data.jobs.JobPhotoBytesUnavailable
import com.servora.android.data.jobs.JobPhotoBytesUnavailableException
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.PendingJobPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which photo the full-size viewer draws, which photos it pages through, where its bytes come from, and
 * what it says when they cannot be produced (`D4`, `D5`, `D11`).
 *
 * The viewer holds only a photo id and resolves the rest from the records that already state it, so
 * these pin the answers that matter: the device's own copy while it still holds the photo, and the
 * backend's evidence once it does not — and nothing at all when neither holds it, because a photo the
 * viewer cannot place must not be replaced by another picture (`BR-042`). They also pin the order the
 * viewer pages in — the order Job Details presents the same photos in (`D11`) — the rule that decides
 * whether a swipe pages or pans, and the rule that tells a photo which is only missing because this
 * device is offline from one that cannot be shown at all (`D5`, `BR-013`).
 */
class JobPhotoViewerTest {

    @Test
    fun `reads a failed photo read as offline only when the read said the backend could not be reached`() {
        assertEquals(
            JobPhotoBytesUnavailable.OFFLINE,
            jobPhotoUnavailableReason(JobPhotoBytesUnavailableException(JobPhotoBytesUnavailable.OFFLINE)),
        )
    }

    @Test
    fun `reads every other failure as a photo that could not be shown`() {
        // A refusal, a photo this device no longer holds, and a failure this build did not raise are all
        // "could not be shown": none of them is fixed by reconnecting (`BR-042`).
        assertEquals(
            JobPhotoBytesUnavailable.UNAVAILABLE,
            jobPhotoUnavailableReason(
                JobPhotoBytesUnavailableException(JobPhotoBytesUnavailable.UNAVAILABLE),
            ),
        )
        assertEquals(JobPhotoBytesUnavailable.UNAVAILABLE, jobPhotoUnavailableReason(null))
        assertEquals(JobPhotoBytesUnavailable.UNAVAILABLE, jobPhotoUnavailableReason(IllegalStateException()))
    }

    @Test
    fun `shows the bytes this device still holds with the phase and note they were recorded with`() {
        val viewed = viewedJobPhoto(
            jobId = JOB_ID,
            photoId = "photo-1",
            pendingPhotos = listOf(pendingPhoto()),
            activity = null,
        )

        assertEquals(
            ViewedJobPhoto.Pending(
                photoId = "photo-1",
                localPath = "app-private/job-photos/user-1/photo-1.jpg",
                phase = JobPhotoPhase.DURING_WORK,
                note = "Sawdust on the belt",
            ),
            viewed,
        )
    }

    @Test
    fun `shows the evidence the backend holds once the device no longer has the photo`() {
        val viewed = viewedJobPhoto(
            jobId = JOB_ID,
            photoId = "photo-1",
            pendingPhotos = emptyList(),
            activity = listOf(photoEvent(photoId = "photo-1", phase = "BEFORE_WORK", note = "Panel")),
        )

        assertEquals(
            ViewedJobPhoto.Stored(
                photoId = "photo-1",
                jobId = JOB_ID,
                phase = JobPhotoPhase.BEFORE_WORK,
                note = "Panel",
            ),
            viewed,
        )
    }

    @Test
    fun `prefers the photo the device holds while it still exists`() {
        // The id is the device's own operation id, so an upload in flight can leave both copies in
        // place; the bytes the technician recorded are the ones they are shown (`§5`, `§9`).
        val viewed = viewedJobPhoto(
            jobId = JOB_ID,
            photoId = "photo-1",
            pendingPhotos = listOf(pendingPhoto()),
            activity = listOf(photoEvent(photoId = "photo-1", phase = "AFTER_WORK", note = "Sent")),
        )

        assertEquals(
            "app-private/job-photos/user-1/photo-1.jpg",
            (viewed as ViewedJobPhoto.Pending).localPath,
        )
    }

    @Test
    fun `shows nothing for a photo neither the device nor the Activity holds`() {
        assertNull(
            viewedJobPhoto(
                jobId = JOB_ID,
                photoId = "photo-9",
                pendingPhotos = listOf(pendingPhoto()),
                activity = listOf(photoEvent(photoId = "photo-1")),
            ),
        )
    }

    @Test
    fun `shows no phase for a code this build cannot read rather than guessing one`() {
        val viewed = viewedJobPhoto(
            jobId = JOB_ID,
            photoId = "photo-1",
            pendingPhotos = emptyList(),
            activity = listOf(photoEvent(photoId = "photo-1", phase = "SOMETIME")),
        )

        assertNull(viewed?.phase)
    }

    @Test
    fun `shows no note when the record carries none or only blanks`() {
        val noNote = viewedJobPhoto(
            jobId = JOB_ID,
            photoId = "photo-1",
            pendingPhotos = listOf(pendingPhoto(note = null)),
            activity = null,
        )
        val blankNote = viewedJobPhoto(
            jobId = JOB_ID,
            photoId = "photo-1",
            pendingPhotos = emptyList(),
            activity = listOf(photoEvent(photoId = "photo-1", note = "   ")),
        )

        assertNull(noNote?.note)
        assertNull(blankNote?.note)
    }

    @Test
    fun `pages through the evidence the backend holds before the photos this device holds`() {
        val sequence = viewedJobPhotoSequence(
            jobId = JOB_ID,
            pendingPhotos = listOf(pendingPhoto(photoId = "pending-1")),
            activity = listOf(photoEvent(photoId = "stored-1"), photoEvent(photoId = "stored-2")),
        )

        assertEquals(listOf("stored-1", "stored-2", "pending-1"), sequence.map { it.photoId })
    }

    @Test
    fun `lists a photo both records hold once, read from the copy the device holds`() {
        // An upload in flight can leave both copies in place; the id is the device's own operation id,
        // so a swipe must not land on the same photo twice (`§5`, `§9`).
        val sequence = viewedJobPhotoSequence(
            jobId = JOB_ID,
            pendingPhotos = listOf(pendingPhoto(photoId = "photo-1")),
            activity = listOf(photoEvent(photoId = "photo-1")),
        )

        assertEquals(listOf("photo-1"), sequence.map { it.photoId })
        assertTrue(sequence.single() is ViewedJobPhoto.Pending)
    }

    @Test
    fun `leaves out a photo neither record can place rather than paging onto something else`() {
        val sequence = viewedJobPhotoSequence(
            jobId = JOB_ID,
            pendingPhotos = emptyList(),
            activity = listOf(photoEvent(photoId = "photo-1").copy(photoId = null)),
        )

        assertTrue(sequence.isEmpty())
    }

    @Test
    fun `opens on the page the tapped photo is on`() {
        val sequence = viewedJobPhotoSequence(
            jobId = JOB_ID,
            pendingPhotos = listOf(pendingPhoto(photoId = "pending-1")),
            activity = listOf(photoEvent(photoId = "stored-1")),
        )

        assertEquals(0, jobPhotoViewerInitialPage(sequence, "stored-1"))
        assertEquals(1, jobPhotoViewerInitialPage(sequence, "pending-1"))
        assertNull(jobPhotoViewerInitialPage(sequence, "photo-9"))
    }

    @Test
    fun `pages while the photo is at fit and pans once it is zoomed`() {
        assertTrue("a photo not laid out yet has nothing to pan", jobPhotoViewerPagingEnabled(null))
        assertTrue(jobPhotoViewerPagingEnabled(0f))
        assertTrue("a negligible zoom is still fit", jobPhotoViewerPagingEnabled(0.1f))
        assertFalse("a zoomed photo pans instead (`D9`, `D11`)", jobPhotoViewerPagingEnabled(0.5f))
        assertFalse(jobPhotoViewerPagingEnabled(1f))
    }

    private companion object {
        const val JOB_ID = "job-1"
    }
}

/** One photo the technician recorded that the backend has not accepted yet (`§9`). */
private fun pendingPhoto(
    photoId: String = "photo-1",
    phase: JobPhotoPhase? = JobPhotoPhase.DURING_WORK,
    note: String? = "Sawdust on the belt",
) = PendingJobPhoto(
    photoId = photoId,
    jobId = "job-1",
    localPath = "app-private/job-photos/user-1/$photoId.jpg",
    phase = phase,
    note = note,
    capturedAt = "2026-09-15T13:04:05Z",
    mimeType = "image/jpeg",
    recordedAt = 1_000L,
    submitted = false,
)

/** One photo entry of the Job's Activity, as the API reports it (`BR-080`). */
private fun photoEvent(
    photoId: String,
    phase: String? = "DURING_WORK",
    note: String? = null,
) = JobActivityEvent(
    id = photoId,
    kind = JobActivityKind.JOB_PHOTO_ADDED,
    recordedAt = "2026-09-15T13:05:00.000Z",
    actorName = "Mike Lead",
    visitSequence = null,
    fromStatus = null,
    toStatus = null,
    technicianName = null,
    roleCode = null,
    previousRoleCode = null,
    outcomeCode = null,
    outcomeSummary = null,
    body = note,
    photoId = photoId,
    photoPhase = phase,
)
