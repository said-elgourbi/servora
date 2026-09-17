package com.servora.android.ui.jobs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.JobAudioSyncState

/*
 * The audio-evidence pieces the Job Details screen and the Add update sheet draw (`BR-091`, `ADR-018`).
 *
 * They are presentation only: what phase or note a recording carries, whether it was accepted and what an
 * upload is doing are all decided elsewhere — by the technician's own input and by the backend
 * (`BR-001`, `BR-042`). The state labels are shared with the photo kind (`R.string.evidence_state_*`),
 * because "waiting to upload" is one idea and must not be worded twice (`BR-041`); what is per kind is
 * which of its own states maps onto that vocabulary.
 */

/** Identifies the notice that reports the recordings the backend has not accepted yet. */
const val JobAudioNoticeTag = "job-audio-notice"

/** Identifies one reported recording. */
fun jobAudioNoticeRowTag(audioNoteId: String): String = "job-audio-note-$audioNoteId"

/** Identifies the upload state a recording reports. */
fun jobAudioNoticeStateTag(audioNoteId: String): String = "job-audio-note-state-$audioNoteId"

/** Identifies the action that attaches an unsubmitted recording. */
fun jobAudioNoticeAttachTag(audioNoteId: String): String = "job-audio-note-attach-$audioNoteId"

/** Identifies the discard a recording offers: before it is attached, or after a refusal. */
fun jobAudioNoticeRemoveTag(audioNoteId: String): String = "job-audio-note-remove-$audioNoteId"

/** How large the notice draws a recording's glyph, and the control that removes one. */
internal val JobAudioNoticeIconSize = 20.dp
internal val JobAudioNoticeRemoveIconSize = 18.dp

/**
 * A recording's length, as the review and the notice show it.
 *
 * It is minutes and seconds of the take this device measured; the label is a format string rather than a
 * hand-built number so the separator and the digits read the way the device's own locale writes them
 * (`BR-028`).
 */
@Composable
internal fun audioDurationLabel(seconds: Int): String {
    val whole = seconds.coerceAtLeast(0)
    return stringResource(R.string.job_audio_duration, whole / SECONDS_PER_MINUTE, whole % SECONDS_PER_MINUTE)
}

/** The localized report of what an audio upload is doing, as the notice shows it (`§7`). */
internal fun jobAudioStateLabel(state: JobAudioSyncState): Int =
    when (state) {
        JobAudioSyncState.QUEUED -> R.string.evidence_state_queued
        JobAudioSyncState.UPLOADING -> R.string.evidence_state_uploading
        JobAudioSyncState.RETRYING -> R.string.evidence_state_retrying
        JobAudioSyncState.REFUSED -> R.string.evidence_state_refused
    }

/** The localized reason an audio action did not complete (`BR-042`). */
internal fun jobAudioFailureMessage(failure: JobAudioFailure): Int =
    when (failure) {
        JobAudioFailure.MICROPHONE_DENIED -> R.string.job_audio_error_microphone_denied
        JobAudioFailure.NOT_SIGNED_IN -> R.string.job_audio_error_not_signed_in
        JobAudioFailure.RECORDER_UNAVAILABLE -> R.string.job_audio_error_recorder_unavailable
        JobAudioFailure.RECORDING_FAILED -> R.string.job_audio_error_recording_failed
        JobAudioFailure.RECORDING_EMPTY -> R.string.job_audio_error_recording_empty
        JobAudioFailure.RECORDING_NOT_SAVED -> R.string.job_audio_error_recording_not_saved
        JobAudioFailure.NOT_QUEUED -> R.string.job_audio_error_not_queued
        JobAudioFailure.ALREADY_SUBMITTED -> R.string.job_audio_error_already_submitted
        // Playback's own failures name what the technician can act on: this device could not play the
        // recording, the backend could not be reached for bytes it does not hold, or the backend did not
        // deliver it (`BR-013`, `BR-042`).
        JobAudioFailure.PLAYBACK_FAILED -> R.string.job_audio_error_playback_failed
        JobAudioFailure.PLAYBACK_UNREACHABLE -> R.string.job_audio_error_playback_unreachable
        JobAudioFailure.PLAYBACK_UNAVAILABLE -> R.string.job_audio_error_playback_unavailable
        // A removal reports through the audio channel for the kind it removed (`BR-089`, `ADR-018` A7).
        JobAudioFailure.REMOVAL_NOT_PERMITTED -> R.string.job_audio_error_removal_not_permitted
        JobAudioFailure.REMOVAL_NO_LONGER_AVAILABLE -> R.string.job_audio_error_removal_unavailable
        JobAudioFailure.REMOVAL_UNREACHABLE -> R.string.job_audio_error_removal_unreachable
        JobAudioFailure.REMOVAL_FAILED -> R.string.job_audio_error_removal_failed
    }

/** The localized report of what the last audio action did. */
internal fun jobAudioMessageText(message: JobAudioMessage): Int =
    when (message) {
        JobAudioMessage.QUEUED -> R.string.job_audio_message_queued
        JobAudioMessage.EVIDENCE_REMOVED -> R.string.job_audio_removed_message
    }

private const val SECONDS_PER_MINUTE = 60
