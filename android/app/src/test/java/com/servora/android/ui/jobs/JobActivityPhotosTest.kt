package com.servora.android.ui.jobs

import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.evidencePhaseOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Job's photos as the Activity section presents them (`BR-001`, `BR-015`, `BR-080`).
 *
 * The gallery above the timeline, the count its heading states, the compact preview it draws while it
 * is folded, the timeline's own photo entries and the viewer's paging order are all one collection:
 * the `JOB_PHOTO_ADDED` entries the API returned, in the API's order. These pin that collection and
 * the rule that leaves out a photo this build cannot place, so no surface can present a different set
 * of evidence from the one the backend reported (`BR-042`).
 */
class JobActivityPhotosTest {

    @Test
    fun `keeps only the photos the backend reported, in the order it reported them`() {
        val photos = jobActivityPhotos(
            listOf(
                photoEvent(id = "photo-1"),
                event(id = "event-1", kind = JobActivityKind.VISIT_NOTE_ADDED),
                photoEvent(id = "photo-2"),
                // A photo whose id this build cannot read is not a photo it can draw or open.
                photoEvent(id = "photo-3").copy(photoId = null),
            ),
        )

        assertEquals(listOf("photo-1", "photo-2"), photos.map { event -> event.photoId })
    }

    @Test
    fun `answers no photos when the activity has not been read or holds none`() {
        assertTrue(jobActivityPhotos(null).isEmpty())
        assertTrue(jobActivityPhotos(listOf(event(id = "event-1"))).isEmpty())
    }

    @Test
    fun `previews the first few photos while the gallery is folded`() {
        val photos = jobActivityPhotos(
            (1..5).map { index -> photoEvent(id = "photo-$index") },
        )

        // The preview is the same collection, shortened: it never reorders what it shows (`BR-080`).
        assertEquals(
            listOf("photo-1", "photo-2", "photo-3"),
            jobPhotoGalleryPreview(photos).map { event -> event.photoId },
        )
    }

    @Test
    fun `previews every photo when the Job has fewer than the preview holds`() {
        val photos = jobActivityPhotos(listOf(photoEvent(id = "photo-1")))

        assertEquals(listOf("photo-1"), jobPhotoGalleryPreview(photos).map { event -> event.photoId })
        assertTrue(jobPhotoGalleryPreview(emptyList()).isEmpty())
    }

    @Test
    fun `resolves a phase the API reports and invents none for a code this build cannot read`() {
        assertEquals(EvidencePhase.BEFORE_WORK, evidencePhaseOrNull("BEFORE_WORK"))
        assertEquals(EvidencePhase.DURING_WORK, evidencePhaseOrNull("DURING_WORK"))
        assertEquals(EvidencePhase.AFTER_WORK, evidencePhaseOrNull("AFTER_WORK"))
        assertNull("a phase this build does not have is not guessed", evidencePhaseOrNull("NIGHT_WORK"))
        assertNull(evidencePhaseOrNull(null))
    }
}

/** One Job Activity entry, as the API reports it (`BR-080`). */
private fun event(
    id: String,
    kind: JobActivityKind = JobActivityKind.JOB_STATUS_CHANGED,
) = JobActivityEvent(
    id = id,
    kind = kind,
    recordedAt = "2026-09-15T13:05:00.000Z",
    actorName = "Mike Lead",
    visitSequence = null,
    fromStatus = null,
    toStatus = "SCHEDULED",
    technicianName = null,
    roleCode = null,
    previousRoleCode = null,
    outcomeCode = null,
    outcomeSummary = null,
    body = null,
)

/** One photo the backend accepted, as its Activity entry reports it (`BR-015`). */
private fun photoEvent(id: String) = JobActivityEvent(
    id = id,
    kind = JobActivityKind.JOB_PHOTO_ADDED,
    recordedAt = "2026-09-15T13:06:00.000Z",
    actorName = "Mike Lead",
    visitSequence = null,
    fromStatus = null,
    toStatus = null,
    technicianName = null,
    roleCode = null,
    previousRoleCode = null,
    outcomeCode = null,
    outcomeSummary = null,
    body = "Sawdust on the belt",
    photoId = id,
    photoPhase = "DURING_WORK",
)
