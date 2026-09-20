package com.servora.android.data.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample size a photo is decoded down to before it is re-encoded (`D3b`, `D3c`).
 *
 * The rule is arithmetic and is the one place the memory a preparation step costs is decided, so it is
 * pinned here rather than only on a device (`qa.md` §6.1): the decode is never smaller than the edge the
 * step works at, and a capture over that edge is halved rather than expanded into memory at full size.
 *
 * Drawing no longer comes through this rule — the image stack samples a preview and the full-size photo
 * at the size they are drawn at (`D4b`) — which is why the viewer's ceiling rule and its cases are gone
 * from here rather than being rewritten.
 */
class JobPhotoSamplingTest {

    @Test
    fun `decodes a photo that already fits at its own size`() {
        assertEquals(1, jobPhotoSampleSize(widthPx = 1024, heightPx = 768, maxEdgePx = PIPELINE_EDGE))
        assertEquals(1, jobPhotoSampleSize(widthPx = 2048, heightPx = 1536, maxEdgePx = PIPELINE_EDGE))
    }

    @Test
    fun `halves a capture that is over the edge it is decoded at`() {
        // A 24-megapixel capture decoded at the preparation step's 2048 px edge: halving it once
        // decodes it at 3000 px rather than at the ~96 MB its full decode would cost.
        assertEquals(2, jobPhotoSampleSize(widthPx = 6000, heightPx = 4000, maxEdgePx = PIPELINE_EDGE))
    }

    @Test
    fun `bounds the longest edge of either orientation`() {
        // The edge is applied to whichever side is longer, so a portrait capture is sampled too.
        assertEquals(2, jobPhotoSampleSize(widthPx = 4000, heightPx = 6000, maxEdgePx = PIPELINE_EDGE))
        // A panorama is sampled by its long edge, not by its short one.
        assertEquals(8, jobPhotoSampleSize(widthPx = 12000, heightPx = 2000, maxEdgePx = 1024))
    }

    @Test
    fun `never decodes smaller than the edge it is given`() {
        val captures = listOf(
            2048 to 1536,
            4032 to 3024,
            6000 to 4000,
            12000 to 2000,
        )
        captures.forEach { (width, height) ->
            val sampleSize = jobPhotoSampleSize(width, height, PIPELINE_EDGE)
            val longest = maxOf(width / sampleSize, height / sampleSize)
            assertTrue(
                "$width x $height sampled by $sampleSize is under $PIPELINE_EDGE px",
                longest >= PIPELINE_EDGE,
            )
        }
    }

    @Test
    fun `decodes nothing down for bounds that declare no image`() {
        // A photo the decoder could not read declares no bounds; the rule must not loop or shrink it
        // into one (`BR-042`).
        assertEquals(1, jobPhotoSampleSize(widthPx = 0, heightPx = 0, maxEdgePx = PIPELINE_EDGE))
    }

    private companion object {
        /** The edge the preparation pipeline decodes at (`DefaultJobPhotoProcessing.DECODE_EDGE_PX`). */
        const val PIPELINE_EDGE = 2048
    }
}
