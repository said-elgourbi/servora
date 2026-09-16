package com.servora.android.data.jobs

/**
 * Why a photo's bytes are not on the screen (`D5`, `BR-013`, `BR-042`).
 *
 * It exists so the surfaces that draw evidence say what is actually true: photographed evidence this
 * device does not hold is readable offline **only** when the device already holds it or the image
 * stack already cached it, so a technician who is told "not available offline" knows to reconnect,
 * while a photo the backend refused to serve is a different statement that reconnecting will not fix.
 *
 * It is deliberately not a business outcome: nothing here records or changes evidence, and no
 * availability state is stored anywhere (`BR-001`).
 */
enum class JobPhotoBytesUnavailable {
    /**
     * The backend could not be reached and this device does not hold the bytes.
     *
     * The photo may be readable as soon as connectivity returns, so this is a state field work can wait
     * out rather than a failure of the record (`BR-013`).
     */
    OFFLINE,

    /** The photo could not be produced for a reason other than connectivity (`BR-042`). */
    UNAVAILABLE,
}

/**
 * Reported by [JobPhotoFetcher] when a photo cannot be delivered to the image stack.
 *
 * It carries the reason because this is the only channel a drawn surface has: the stack reports the
 * failure back to the tile or the viewer, which then has to say something true about the photo
 * (`D5`, `BR-042`).
 */
class JobPhotoBytesUnavailableException(
    val reason: JobPhotoBytesUnavailable,
) : Exception("Job photo bytes unavailable: $reason")
