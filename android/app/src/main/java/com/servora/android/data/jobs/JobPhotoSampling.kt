package com.servora.android.data.jobs

import android.graphics.BitmapFactory

/**
 * How a photo is decoded down to a size the device can hold.
 *
 * The sample size is a power of two derived from the image's own bounds, so a 48-megapixel photo is
 * never expanded into memory at full size. It is one rule with two uses — a tile that decodes its
 * previews (`JobPhotoImages`) and the two preparation steps that re-encode a photo before it becomes a
 * draft (`JobPhotoProcessing`) — which is why it lives here rather than being restated in each.
 *
 * It stops as soon as halving would take the result **below** [maxEdgePx], so the decode is never
 * smaller than the edge it was given: the two callers above want the detail before they shrink it. A
 * caller that must not exceed an edge asks the other question, and [`jobPhotoFittingSampleSize`] is
 * that rule.
 */
internal fun jobPhotoSampleSize(bounds: BitmapFactory.Options, maxEdgePx: Int): Int {
    var sampleSize = 1
    while (
        bounds.outWidth / (sampleSize * 2) >= maxEdgePx ||
        bounds.outHeight / (sampleSize * 2) >= maxEdgePx
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * The sample size that keeps a decode **no larger** than [maxEdgePx] on its longest edge.
 *
 * The full-size viewer is the caller that needs this guarantee and not the one above: an image that
 * may not exceed an edge has to be sampled from above, because a 4032 px capture decoded at full size
 * is roughly 48 MB and the phone's bitmap budget does not have room for it (`D4`).
 *
 * It takes the image's bounds as plain numbers rather than a `BitmapFactory.Options` because the rule
 * is arithmetic: keeping it free of the Android runtime is what lets it be verified on the JVM
 * (`qa.md` §6.1).
 */
internal fun jobPhotoFittingSampleSize(widthPx: Int, heightPx: Int, maxEdgePx: Int): Int {
    var sampleSize = 1
    while (widthPx / sampleSize > maxEdgePx || heightPx / sampleSize > maxEdgePx) {
        sampleSize *= 2
    }
    return sampleSize
}
