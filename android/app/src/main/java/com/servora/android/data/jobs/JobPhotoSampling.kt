package com.servora.android.data.jobs

import android.graphics.BitmapFactory

/**
 * How a photo is decoded down to a size the device can hold.
 *
 * The sample size is a power of two derived from the image's own bounds, so a 48-megapixel photo is
 * never expanded into memory at full size. It is one rule with two uses — a tile that decodes its
 * previews (`JobPhotoImages`) and the two preparation steps that re-encode a photo before it becomes a
 * draft (`JobPhotoProcessing`) — which is why it lives here rather than being restated in each.
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
