package com.servora.android.data.jobs
import com.servora.android.domain.model.PendingJobAudioNote


/**
 * What recording an audio note did (`BR-014`, `BR-091`).
 *
 * A recording either becomes a durable draft on this device or it is refused with the reason, so the
 * screen can tell the technician what actually happened rather than that "something went wrong"
 * (`BR-042`). A refusal records **nothing**: no draft row and no bytes left behind.
 */
sealed interface JobAudioRecordResult {
    /** The recording is on this device, findable after a process death, and not yet attached. */
    data class Recorded(val note: PendingJobAudioNote) : JobAudioRecordResult

    /** Nothing was recorded; [reason] decides what the screen reports. */
    data class Refused(val reason: JobAudioRefusal) : JobAudioRecordResult
}

/**
 * Why a recording could not be recorded.
 *
 * The first is the local session's own condition; the others are what the device's recorder can
 * report — a recorder that never started, one that could not finish, or a file that came out with no
 * bytes — so nothing un-uploadable is created on the device (`BR-014`, `BR-042`).
 */
enum class JobAudioRefusal {
    /** No session is held, so the recording cannot be attributed to anyone or uploaded (§10). */
    NOT_SIGNED_IN,

    /** The device's recorder could not be started, so nothing was recorded. */
    RECORDER_UNAVAILABLE,

    /** The device's recorder could not finish the recording, so nothing was kept. */
    NOT_RECORDED,

    /** The recording finished but the device holds no bytes for it, so no draft was written. */
    NO_BYTES,

    /** The recording's bytes could not be read back, so no draft was written. */
    NOT_STORED,
}
