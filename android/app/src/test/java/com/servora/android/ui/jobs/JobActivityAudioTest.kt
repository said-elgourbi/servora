package com.servora.android.ui.jobs

import com.servora.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The copy and the controls the audio evidence's playback and removal resolve (`BR-028`, `BR-042`,
 * `BR-089`).
 *
 * The screen holds stable, machine-readable codes and resolves them to localized copy, so what these pin
 * is that resolution: every state audio can report is named by its own message — the device could not
 * play the recording, the backend could not be reached for bytes this device does not hold, the backend
 * did not deliver it, a removal was not permitted, the recording is gone, the API could not be reached —
 * and the removal confirmation is the **audio kind's own** copy and tags on the one dialog both kinds
 * share (`BR-041`, `ADR-018` A7). What is per kind is what the manager is told; what is shared is the
 * operation.
 */
class JobActivityAudioTest {

    @Test
    fun `names every playback failure with the reason the technician can act on`() {
        assertEquals(
            R.string.job_audio_error_playback_failed,
            jobAudioFailureMessage(JobAudioFailure.PLAYBACK_FAILED),
        )
        assertEquals(
            R.string.job_audio_error_playback_unreachable,
            jobAudioFailureMessage(JobAudioFailure.PLAYBACK_UNREACHABLE),
        )
        assertEquals(
            R.string.job_audio_error_playback_unavailable,
            jobAudioFailureMessage(JobAudioFailure.PLAYBACK_UNAVAILABLE),
        )
    }

    @Test
    fun `names every removal failure with the reason the manager can act on`() {
        assertEquals(
            R.string.job_audio_error_removal_not_permitted,
            jobAudioFailureMessage(JobAudioFailure.REMOVAL_NOT_PERMITTED),
        )
        assertEquals(
            R.string.job_audio_error_removal_unavailable,
            jobAudioFailureMessage(JobAudioFailure.REMOVAL_NO_LONGER_AVAILABLE),
        )
        assertEquals(
            R.string.job_audio_error_removal_unreachable,
            jobAudioFailureMessage(JobAudioFailure.REMOVAL_UNREACHABLE),
        )
        assertEquals(
            R.string.job_audio_error_removal_failed,
            jobAudioFailureMessage(JobAudioFailure.REMOVAL_FAILED),
        )
    }

    @Test
    fun `reads no two audio states as the same message`() {
        // A state has to say what happened rather than sharing a sentence with another: two failures that
        // read the same leave the technician unable to tell what to do (`BR-042`, `dev.md` §9).
        val messages = JobAudioFailure.entries.map { failure -> jobAudioFailureMessage(failure) }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun `reports a removal of accepted evidence as its own outcome`() {
        assertEquals(
            R.string.job_audio_message_queued,
            jobAudioMessageText(JobAudioMessage.QUEUED),
        )
        assertEquals(
            R.string.job_audio_removed_message,
            jobAudioMessageText(JobAudioMessage.EVIDENCE_REMOVED),
        )
    }

    @Test
    fun `confirms a recording's removal with the audio kind's own copy`() {
        assertEquals(JobAudioRemovalDialogTag, JobAudioRemovalCopy.tag)
        assertEquals(JobAudioRemovalReasonTag, JobAudioRemovalCopy.reasonTag)
        assertEquals(JobAudioRemovalConfirmTag, JobAudioRemovalCopy.confirmTag)
        assertEquals(JobAudioRemovalCancelTag, JobAudioRemovalCopy.cancelTag)
        assertEquals(R.string.job_audio_removal_title, JobAudioRemovalCopy.title)
        assertEquals(R.string.job_audio_removal_message, JobAudioRemovalCopy.message)
        assertEquals(R.string.job_audio_removal_reason_label, JobAudioRemovalCopy.reasonLabel)
    }

    @Test
    fun `does not present a recording's removal as a photo's`() {
        // One dialog serves both kinds, so what is per kind is the copy and the tags: a manager removing a
        // recording must not be asked about a photo (`BR-028`, `BR-041`, `ADR-018` A7).
        assertNotEquals(JobPhotoRemovalCopy.tag, JobAudioRemovalCopy.tag)
        assertNotEquals(JobPhotoRemovalCopy.reasonTag, JobAudioRemovalCopy.reasonTag)
        assertNotEquals(JobPhotoRemovalCopy.confirmTag, JobAudioRemovalCopy.confirmTag)
        assertNotEquals(JobPhotoRemovalCopy.title, JobAudioRemovalCopy.title)
        assertNotEquals(JobPhotoRemovalCopy.message, JobAudioRemovalCopy.message)
    }

    @Test
    fun `names each recording's own controls, so two recordings cannot be confused`() {
        // Every control of a recording is named by that recording, so a tap, a length and a removal always
        // belong to one entry of the timeline (`BR-080`, `qa.md` §6.2).
        assertNotEquals(jobAudioPlayTag("audio-1"), jobAudioPlayTag("audio-2"))
        assertNotEquals(jobAudioPlayTag("audio-1"), jobAudioPositionTag("audio-1"))
        assertNotEquals(jobAudioPositionTag("audio-1"), jobAudioSeekTag("audio-1"))
        assertNotEquals(jobAudioSeekTag("audio-1"), jobAudioSeekTag("audio-2"))
        assertNotEquals(jobAudioPlayTag("audio-1"), jobAudioRemoveTag("audio-1"))
        assertNotEquals(jobAudioActivityNoteTag("audio-1"), jobAudioActivityNoteTag("audio-2"))
        assertNotEquals(jobAudioActivityNoteTag("audio-1"), jobAudioActivityNoteActionTag("audio-1"))
    }

    @Test
    fun `moves a recording to the position the playhead was released at`() {
        // A seek is measured in the player's own timebase, which is the recording's length as the device
        // holds it (`ADR-018` A11).
        assertEquals(9_000, jobAudioSeekPosition(fraction = 0.5f, durationMillis = 18_000))
        assertEquals(0, jobAudioSeekPosition(fraction = 0f, durationMillis = 18_000))
        assertEquals(18_000, jobAudioSeekPosition(fraction = 1f, durationMillis = 18_000))
        // A fraction lands on the nearest millisecond of the recording's own length: 0.33 of 18 s is
        // 5 940 ms, not a rounded 6 000.
        assertEquals(5_940, jobAudioSeekPosition(fraction = 0.33f, durationMillis = 18_000))
    }

    @Test
    fun `keeps a seek inside the recording it belongs to`() {
        // A drag that ran past either end asks for the start or the end of the recording rather than for a
        // position the recording does not have (`BR-042`).
        assertEquals(0, jobAudioSeekPosition(fraction = -0.4f, durationMillis = 18_000))
        assertEquals(18_000, jobAudioSeekPosition(fraction = 1.8f, durationMillis = 18_000))
    }

    @Test
    fun `asks for no position at all when the device states no length`() {
        // A recording whose length the player cannot state has no timebase to move through, so nothing is
        // asked for rather than a position measured against zero (`BR-042`).
        assertEquals(0, jobAudioSeekPosition(fraction = 0.5f, durationMillis = 0))
        assertEquals(0, jobAudioSeekPosition(fraction = 0.5f, durationMillis = -1))
    }
}
