package com.servora.android.data.jobs

import com.servora.android.domain.model.PendingJobPhoto

/**
 * What recording a photo did (`BR-014`, `BR-015`).
 *
 * A photo either becomes a durable draft on this device or it is refused with the reason, so the
 * screen can tell the technician what actually happened rather than that "something went wrong"
 * (`BR-042`). A refusal records **nothing**: no draft row and no bytes left behind.
 */
sealed interface JobPhotoRecordResult {
    /** The photo is on this device, findable after a process death, and not yet submitted. */
    data class Recorded(val photo: PendingJobPhoto) : JobPhotoRecordResult

    /** Nothing was recorded; [reason] decides what the screen reports. */
    data class Refused(val reason: JobPhotoRefusal) : JobPhotoRecordResult
}

/**
 * Why a photo could not be recorded.
 *
 * The first two are the local session's own conditions; the last three are the preparation steps a
 * photo goes through before it becomes a draft (`docs/tracker/029-photo-evidence-phases.md` D3b, D3c).
 */
enum class JobPhotoRefusal {
    /** No session is held, so the photo cannot be attributed to anyone or uploaded (`§10`). */
    NOT_SIGNED_IN,

    /** No bytes are on this device for the photo: nothing was written, or none could be read back. */
    NO_BYTES,

    /**
     * A photo the technician chose handed over nothing readable (`D3`).
     *
     * The item could not be read from the device — a copy that failed, or an item whose bytes could
     * not be opened — so nothing was recorded for it and the item is reported rather than dropped
     * (`BR-014`).
     */
    NOT_READ,

    /**
     * The bytes are not a photo type Servora accepts and could not be converted to one (`D3b`).
     *
     * The API decides what it accepts; this says the device could not bring the photo to that, so
     * nothing un-uploadable was created.
     */
    TYPE_NOT_ACCEPTED,

    /** The photo could not be brought under the API's upload limit (`D3c`). */
    TOO_LARGE,

    /** The photo's bytes could not be stored on this device, so no draft was recorded. */
    NOT_STORED,
}
