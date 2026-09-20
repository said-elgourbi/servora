package com.servora.android.data.jobs

/**
 * How a photo is decoded down to a size the device can hold.
 *
 * The sample size is a power of two derived from the image's own bounds, so a 48-megapixel photo is
 * never expanded into memory at full size. The two preparation steps that re-encode a photo before it
 * becomes a draft (`JobPhotoProcessing`) both decode at an edge through this rule, which is why it lives
 * here rather than being restated in each.
 *
 * It stops as soon as halving would take the result **below** [maxEdgePx], so the decode is never
 * smaller than the edge it was given: the callers above want the detail before they shrink it.
 *
 * It takes the bounds as plain numbers rather than a `BitmapFactory.Options` because the rule is
 * arithmetic: keeping it free of the Android runtime is what lets it be verified on the JVM
 * (`qa.md` §6.1).
 *
 * Drawing a preview or the full-size photo no longer comes through here: the image stack samples those
 * decodes at the size they are drawn at (`D4b`, `docs/decisions/016-android-image-stack-and-viewer-zoom.md`).
 */
internal fun jobPhotoSampleSize(widthPx: Int, heightPx: Int, maxEdgePx: Int): Int {
    var sampleSize = 1
    while (widthPx / (sampleSize * 2) >= maxEdgePx || heightPx / (sampleSize * 2) >= maxEdgePx) {
        sampleSize *= 2
    }
    return sampleSize
}
