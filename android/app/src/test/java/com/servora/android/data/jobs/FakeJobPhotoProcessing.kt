package com.servora.android.data.jobs

/**
 * [JobPhotoProcessing] a test decides.
 *
 * The device's decoder and encoder are what a real photo needs; the tests are about the **rules** the
 * session applies around them — keep a type the API accepts, convert one it does not, fit one that is
 * over the limit, record nothing when either step cannot deliver — so the fake answers with the bytes a
 * test states and counts how often it was asked (`qa.md` §6.1, tracker 029 D3b/D3c).
 */
class FakeJobPhotoProcessing : JobPhotoProcessing {

    /** The bytes `convertToJpeg` answers with, or `null` when it cannot convert. */
    var conversion: ByteArray? = null

    /** The bytes `fitToUploadLimit` answers with, or `null` when it cannot fit. */
    var fitted: ByteArray? = null

    /** How many times the type step was asked. */
    var conversions: Int = 0
        private set

    /** How many times the size step was asked. */
    var fits: Int = 0
        private set

    override suspend fun convertToJpeg(bytes: ByteArray): ByteArray? {
        conversions += 1
        return conversion
    }

    override suspend fun fitToUploadLimit(bytes: ByteArray): ByteArray? {
        fits += 1
        return fitted
    }
}