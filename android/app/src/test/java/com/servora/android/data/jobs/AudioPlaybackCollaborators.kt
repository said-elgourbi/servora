package com.servora.android.data.jobs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The audio slice's playback collaborators, all in memory.
 *
 * The device's player and the bytes behind accepted evidence are the two parts of playback that cannot
 * exist off a device, so they are ports and these are their doubles (`qa.md` §6.1). What the feature
 * does with them — one recording at a time, the control that started it told what is playing, the
 * removal of the recording that is playing stopping it — is the behaviour under test.
 */

/**
 * A player that holds no device.
 *
 * It reports what a real one would report through [playback] and [progress], so the state the screen
 * draws is the player's own answer rather than the tap's. [playSucceeds] lets a test present a device that
 * could not play the recording, which is a refusal the screen has to report (`BR-042`).
 */
class FakeJobAudioPlayer : JobAudioPlayer {

    private val _playback = MutableStateFlow<JobAudioPlayback?>(null)
    override val playback: Flow<JobAudioPlayback?> = _playback.asStateFlow()

    private val _progress = MutableStateFlow<JobAudioPlaybackProgress?>(null)
    override val progress: Flow<JobAudioPlaybackProgress?> = _progress.asStateFlow()

    /** Whether this device can play a recording at all. */
    var playSucceeds: Boolean = true

    /**
     * The length the fake's player states for the file it holds, as a real one reads the container it was
     * given (`ADR-018` A11). It is what a seek is measured against.
     */
    var durationMillis: Int = 18_000

    /** Every recording this player was asked to play, as `audioNoteId:path`. */
    val played = mutableListOf<String>()

    /** How many times playback was stopped, including the stop that replaces one recording with another. */
    var stops = 0
        private set

    /** How many times the recording this player holds was held where it was, and continued from there. */
    var pauses = 0
        private set
    var resumes = 0
        private set

    /** Every position this player was asked to move to, in the order it was asked. */
    val seeks = mutableListOf<Int>()

    override fun play(audioNoteId: String, path: String): Boolean {
        if (!playSucceeds) {
            return false
        }
        played += "$audioNoteId:$path"
        _playback.value = JobAudioPlayback(audioNoteId, isPlaying = true)
        _progress.value = JobAudioPlaybackProgress(
            audioNoteId = audioNoteId,
            positionMillis = 0,
            durationMillis = durationMillis,
        )
        return true
    }

    override fun pause() {
        val held = _playback.value ?: return
        pauses += 1
        _playback.value = held.copy(isPlaying = false)
    }

    override fun resume() {
        val held = _playback.value ?: return
        resumes += 1
        _playback.value = held.copy(isPlaying = true)
    }

    override fun seekTo(positionMillis: Int) {
        val held = _playback.value ?: return
        seeks += positionMillis
        _progress.value = JobAudioPlaybackProgress(
            audioNoteId = held.audioNoteId,
            positionMillis = positionMillis,
            durationMillis = _progress.value?.durationMillis ?: durationMillis,
        )
    }

    override fun stop() {
        stops += 1
        _playback.value = null
        _progress.value = null
    }

    /** Reports that the recording on the device reached its end, as the platform's player would. */
    fun finished() {
        _playback.value = null
        _progress.value = null
    }

    /**
     * Reports where the recording this player holds has got to, as its own loop would while it plays
     * (`ADR-018` A11).
     */
    fun reportProgress(positionMillis: Int) {
        val held = _playback.value ?: return
        _progress.value = JobAudioPlaybackProgress(
            audioNoteId = held.audioNoteId,
            positionMillis = positionMillis,
            durationMillis = durationMillis,
        )
    }
}

/**
 * A cache that answers from memory: what it was asked for, and what it answers with.
 *
 * Its answer stands for the three states playback's bytes can be in — on this device, unreachable, or
 * refused by the backend — so the copy the technician reads for each is testable without a network
 * (`BR-013`, `BR-042`).
 */
class FakeJobAudioEvidenceCache(
    var answer: JobAudioEvidenceRead = JobAudioEvidenceRead.Available("/cache/audio-1.m4a"),
) : JobAudioEvidenceCache {

    /**
     * A read that fails in a way the feature did not foresee, as a device or a library failure would.
     *
     * It is the one failure the cache's own contract does not name, so it is what a caller must be able
     * to survive rather than be taken down by (`BR-042`).
     */
    var failure: Exception? = null

    /** Every recording whose bytes were asked for, as `jobId:audioNoteId`. */
    val requested = mutableListOf<String>()

    override suspend fun read(jobId: String, audioNoteId: String): JobAudioEvidenceRead {
        requested += "$jobId:$audioNoteId"
        failure?.let { fatal -> throw fatal }
        return answer
    }
}
