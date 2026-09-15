package com.servora.android.data.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn a photo's own EXIF orientation asks for (`BR-015`).
 *
 * The rule is arithmetic and is the one place a photo's presentation is decided, so it is pinned here
 * rather than only on a device (`qa.md` §6.1). Getting a code wrong is not a cosmetic slip: `6` and `8`
 * are the two a phone camera writes for a portrait capture, and a wrong mapping is a photo drawn on
 * its side — which is the defect this rule exists to fix.
 */
class JobPhotoOrientationTest {

    /** Every code the EXIF vocabulary defines, with the pixel mapping each one means. */
    @Test
    fun `turns each declared orientation the way the file says`() {
        val expected = mapOf(
            // 1 — the pixels are already upright.
            1 to JobPhotoOrientation(rotationDegrees = 0, mirror = false),
            // 2 — mirrored left-to-right (a front camera).
            2 to JobPhotoOrientation(rotationDegrees = 0, mirror = true),
            // 3 — upside down.
            3 to JobPhotoOrientation(rotationDegrees = 180, mirror = false),
            // 4 — mirrored top-to-bottom.
            4 to JobPhotoOrientation(rotationDegrees = 180, mirror = true),
            // 5 — mirrored across the main diagonal.
            5 to JobPhotoOrientation(rotationDegrees = 90, mirror = true),
            // 6 — the code a portrait capture is usually written with: a quarter turn clockwise.
            6 to JobPhotoOrientation(rotationDegrees = 90, mirror = false),
            // 7 — mirrored across the anti-diagonal.
            7 to JobPhotoOrientation(rotationDegrees = 270, mirror = true),
            // 8 — the other portrait code: a quarter turn counter-clockwise.
            8 to JobPhotoOrientation(rotationDegrees = 270, mirror = false),
        )
        expected.forEach { (code, orientation) ->
            assertEquals("orientation $code", orientation, JobPhotoOrientation.of(code))
        }
    }

    @Test
    fun `turns a portrait capture a quarter turn and does not mirror it`() {
        // These are the two codes the reported defect turned on: a camera writes the sensor's own
        // landscape pixels and says which way round they go.
        val upright = JobPhotoOrientation.of(6)
        assertFalse(upright.isIdentity)
        assertEquals(90, upright.rotationDegrees)
        assertFalse(upright.mirror)

        val theOtherWay = JobPhotoOrientation.of(8)
        assertFalse(theOtherWay.isIdentity)
        assertEquals(270, theOtherWay.rotationDegrees)
        assertFalse(theOtherWay.mirror)
    }

    @Test
    fun `leaves a photo that is already upright exactly as it is`() {
        assertTrue(JobPhotoOrientation.of(1).isIdentity)
        assertEquals(JobPhotoOrientation.Normal, JobPhotoOrientation.of(1))
    }

    @Test
    fun `keeps every turn a quarter turn`() {
        // The mapping is only ever a quarter-turn plus an optional mirror: a decode never scales the
        // photo, so the presented image keeps every pixel its bytes hold.
        (1..8).forEach { code ->
            val orientation = JobPhotoOrientation.of(code)
            assertTrue(
                "orientation $code asks for ${orientation.rotationDegrees} degrees",
                orientation.rotationDegrees in listOf(0, 90, 180, 270),
            )
        }
    }

    @Test
    fun `never invents a turn for an orientation it does not know`() {
        // No tag, a zero, a value past the vocabulary, or nonsense: the photo is presented as stored
        // rather than guessed into a rotation (`BR-042`).
        listOf(0, -1, 9, 99, Int.MIN_VALUE, Int.MAX_VALUE).forEach { code ->
            assertEquals("orientation $code", JobPhotoOrientation.Normal, JobPhotoOrientation.of(code))
        }
    }
}
