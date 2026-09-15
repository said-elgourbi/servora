package com.servora.android.ui.jobs

import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.PendingJobPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which photo the full-size viewer draws, and where its bytes come from (`D4`).
 *
 * The viewer holds only a photo id and resolves the rest from the records that already state it, so
 * these pin the two answers that matter: the device's own copy while it still holds the photo, and the
 * backend's evidence once it does not — and nothing at all when neither holds it, because a photo the
 * viewer cannot place must not be replaced by another picture (`BR-042`).
 */
class JobPhotoViewerTest {

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
