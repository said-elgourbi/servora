package com.servora.android.data.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key the image stack caches a photo under (`D4b`, `D5`, `BR-007`).
 *
 * The partition is the point: what one session cached can never be found by another, whatever the two
 * photos are. The key is built here as the port builds it, and read here as the stack reads it, so this
 * pins the rule the whole cache rests on — and it pins the other half of the policy too: which photos
 * may be a **cache** entry at all, since a photo this device holds must never be one (`§9`, `BR-014`).
 */
class JobPhotoImageCacheKeyTest {

    @Test
    fun `keys the same photo of the same session to the same entry`() {
        assertEquals(
            jobPhotoImageCacheKey(SUBJECT_ID, JobPhotoImage.Backend(JOB_ID, PHOTO_ID)),
            jobPhotoImageCacheKey(SUBJECT_ID, JobPhotoImage.Backend(JOB_ID, PHOTO_ID)),
        )
    }

    @Test
    fun `keeps a second session's evidence out of the first session's entries`() {
        val mine = jobPhotoImageCacheKey("user-1", JobPhotoImage.Backend(JOB_ID, PHOTO_ID))
        val other = jobPhotoImageCacheKey("user-2", JobPhotoImage.Backend(JOB_ID, PHOTO_ID))

        assertNotEquals(mine, other)
        assertTrue("The subject must name the entry", mine.startsWith("user-1"))
        assertTrue("The subject must name the entry", other.startsWith("user-2"))
    }

    @Test
    fun `keys a pending photo apart from the evidence of the same photo id`() {
        val pending = jobPhotoImageCacheKey(SUBJECT_ID, JobPhotoImage.Local("/photo-1.jpg"))
        val evidence = jobPhotoImageCacheKey(SUBJECT_ID, JobPhotoImage.Backend(JOB_ID, "photo-1"))

        assertNotEquals(pending, evidence)
    }

    @Test
    fun `gives a pending photo no disk cache key, so its bytes can never be evicted`() {
        // A photo this device holds is not a cache entry: its app-private file is its only copy, and
        // `BR-014` forbids losing a pending upload (`D5`, §9).
        assertNull(jobPhotoImageDiskCacheKey(SUBJECT_ID, JobPhotoImage.Local("/photo-1.jpg")))
    }

    @Test
    fun `keys accepted evidence for the disk cache exactly as the memory cache is keyed`() {
        // Evidence's bytes *are* cached, in the stack's own bounded cache (`D5`).
        assertEquals(
            jobPhotoImageCacheKey(SUBJECT_ID, JobPhotoImage.Backend(JOB_ID, PHOTO_ID)),
            jobPhotoImageDiskCacheKey(SUBJECT_ID, JobPhotoImage.Backend(JOB_ID, PHOTO_ID)),
        )
    }

    @Test
    fun `keys two photos of one Job apart`() {
        assertNotEquals(
            jobPhotoImageCacheKey(SUBJECT_ID, JobPhotoImage.Backend(JOB_ID, "photo-1")),
            jobPhotoImageCacheKey(SUBJECT_ID, JobPhotoImage.Backend(JOB_ID, "photo-2")),
        )
    }

    @Test
    fun `keys the same photo id of two Jobs apart`() {
        assertNotEquals(
            jobPhotoImageCacheKey(SUBJECT_ID, JobPhotoImage.Backend("job-1", PHOTO_ID)),
            jobPhotoImageCacheKey(SUBJECT_ID, JobPhotoImage.Backend("job-2", PHOTO_ID)),
        )
    }

    private companion object {
        const val SUBJECT_ID = "user-1"
        const val JOB_ID = "job-1"
        const val PHOTO_ID = "photo-1"
    }
}
