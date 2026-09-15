package com.servora.android.data.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample size the full-size viewer decodes at (`D4`).
 *
 * The rule is arithmetic and is the one place the viewer's memory bound is decided, so it is pinned
 * here rather than only on a device (`qa.md` §6.1): the decode must never be larger than the ceiling,
 * and a photo that already fits must not be shrunk at all.
 */
class JobPhotoSamplingTest {

    @Test
    fun `decodes a photo that already fits at its own size`() {
        assertEquals(1, jobPhotoFittingSampleSize(widthPx = 1024, heightPx = 768, maxEdgePx = 2560))
        assertEquals(1, jobPhotoFittingSampleSize(widthPx = 2560, heightPx = 1440, maxEdgePx = 2560))
    }

    @Test
    fun `halves a capture that is over the ceiling`() {
        // A 12-megapixel phone capture: its longest edge is over the ceiling, and halving it once is
        // enough — 2016 px is what the viewer draws rather than the ~48 MB a full decode would cost.
        assertEquals(2, jobPhotoFittingSampleSize(widthPx = 4032, heightPx = 3024, maxEdgePx = 2560))
    }

    @Test
    fun `bounds the longest edge of either orientation`() {
        // The ceiling is applied to whichever edge is longer, so a portrait capture is bounded too.
        assertEquals(2, jobPhotoFittingSampleSize(widthPx = 3024, heightPx = 4032, maxEdgePx = 2560))
        // A panorama is bounded by its long edge, not by its short one.
        assertEquals(8, jobPhotoFittingSampleSize(widthPx = 12000, heightPx = 2000, maxEdgePx = 2560))
    }

    @Test
    fun `never decodes larger than the ceiling it is given`() {
        val sizes = listOf(
            1024 to 768,
            2560 to 1920,
            4032 to 3024,
            6000 to 4000,
            8000 to 6000,
            12000 to 2000,
        )
        sizes.forEach { (width, height) ->
            val sampleSize = jobPhotoFittingSampleSize(width, height, VIEWER_MAX_EDGE_PX)
            assertTrue(
                "$width x $height sampled by $sampleSize is over $VIEWER_MAX_EDGE_PX px",
                maxOf(width / sampleSize, height / sampleSize) <= VIEWER_MAX_EDGE_PX,
            )
        }
    }

    @Test
    fun `decodes nothing down for bounds that declare no image`() {
        // A photo the decoder could not read states no bounds; the decode is answered as no image, and
        // the rule must not loop or shrink it into one (`BR-042`).
        assertEquals(1, jobPhotoFittingSampleSize(widthPx = 0, heightPx = 0, maxEdgePx = VIEWER_MAX_EDGE_PX))
    }
}
