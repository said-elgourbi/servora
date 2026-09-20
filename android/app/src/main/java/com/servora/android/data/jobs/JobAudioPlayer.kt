package com.servora.android.data.jobs

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The recording the device's player is on, and whether it is moving (`BR-091`, `ADR-018` A9, A11).
 *
 * It names the recording rather than the file, because that is what a control needs in order to know
 * whether *it* is the one the player holds: the sheet's review of a take and the timeline's accepted
 * evidence both ask the same question of the same answer. A recording that is paused is *held* rather
 * than playing, so its control returns to **Play** while its position is kept (`A11`).
 *
 * It carries no position. Where a recording has got to is a second answer, read at a different rate
 * ([progress]), and keeping the two apart is what lets a playhead move without rewriting the screen's
 * own state (`BR-012`).
 */
data class JobAudioPlayback(
    /** The recording's id: the draft's own id, or an accepted recording's `audioNoteId`. */
    val audioNoteId: String,
    /** Whether it is playing right now; a recording that is paused is held, not playing (`A11`). */
    val isPlaying: Boolean,
)

/**
 * How far the recording the device's player is on has played (`ADR-018` A11).
 *
 * It is deliberately a **separate answer** from [JobAudioPlayback]: which recording the player holds
 * changes when the technician taps, while where it has got to changes ten times a second. Reported
 * apart, a moving playhead invalidates the playhead and the elapsed label rather than the Job Details
 * screen, the timeline and every entry around the recording (`BR-012`).
 *
 * [durationMillis] is the **player's own** reading of the file it holds, which is the timebase a seek
 * works in. It is not the stated length: accepted evidence still states the length the API read from its
 * container (`ADR-018` A3) and a take still on the device the length this device measured (`BR-041`).
 */
data class JobAudioPlaybackProgress(
    val audioNoteId: String,
    val positionMillis: Int,
    val durationMillis: Int,
)

/**
 * The device's own player for a recording (`BR-091`, `ADR-018` A9).
 *
 * It is a port for the same reason the recorder is: the player is the one part of playback that cannot
 * exist off a device, so what the feature does with it — one recording at a time, the control that
 * started it told what is happening, and the state released when nothing is playing — is testable
 * without one (`qa.md` §6.1).
 *
 * **One player serves both surfaces** (`ADR-018` A9): the Add update sheet's review of a take this
 * device holds and the Job Activity timeline's accepted evidence play through this one port, so the two
 * cannot diverge, and playing one recording stops the other rather than mixing two voices.
 *
 * It plays the **platform's own stack**; no media dependency is added (`dev.md` §4). The player is
 * **seekable and reports its position** since `A11` — a fill with a playhead, no amplitude and no
 * waveform — while streaming from a presigned URL remains a later decision (`ADR-018` A9).
 */
interface JobAudioPlayer {
    /**
     * Plays the recording at [path], replacing whatever the player holds.
     *
     * `false` when the device could not play it, in which case nothing is held and the caller reports it
     * rather than leaving a control that looks as though it did something (`BR-042`).
     */
    fun play(audioNoteId: String, path: String): Boolean

    /**
     * Holds the recording where it is, keeping the position it reached (`A11`).
     *
     * A recording that is paused stays the player's, so its playhead stays where the technician left it
     * and its control returns to **Play**. Answering nothing when nothing is held (`BR-042`).
     */
    fun pause()

    /** Continues the recording the player holds, from where it was paused (`A11`). */
    fun resume()

    /**
     * Moves the recording the player holds to [positionMillis], clamped to its own length (`A11`).
     *
     * A seek the device does not perform is not reported as one: the player keeps stating the position
     * it actually holds, so a playhead never shows somewhere the recording did not go (`BR-042`).
     */
    fun seekTo(positionMillis: Int)

    /** Stops whatever is playing and lets it go. Answering nothing when nothing is playing (`BR-042`). */
    fun stop()

    /**
     * The recording the player holds and whether it is moving, or `null`.
     *
     * It becomes `null` when the recording ends, when it fails, and when it is stopped, so every control
     * drawn from it returns to its own idle state without the device having to be asked again (`BR-042`).
     */
    val playback: Flow<JobAudioPlayback?>

    /**
     * How far the recording the player holds has played, or `null`.
     *
     * It is reported while a recording is playing — the device is not polled when none is — and it
     * becomes `null` with [playback] when the recording ends, fails or is stopped (`A11`, `BR-042`).
     */
    val progress: Flow<JobAudioPlaybackProgress?>
}

/**
 * The platform's own [MediaPlayer], playing the file the feature hands it (`ADR-018` A9, A11).
 *
 * The position is read while a recording plays so the surface can draw a moving playhead, and a seek is
 * performed on the same player. Nothing here decides what a recording's length *means*: the container's
 * own duration is the timebase a seek works in, while the length a surface states stays the API's
 * reading (`A3`) or the device's own measurement for a take still local (`BR-041`).
 */
@Singleton
class MediaPlayerJobAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : JobAudioPlayer {

    private val _playback = MutableStateFlow<JobAudioPlayback?>(null)
    override val playback: Flow<JobAudioPlayback?> = _playback.asStateFlow()

    private val _progress = MutableStateFlow<JobAudioPlaybackProgress?>(null)
    override val progress: Flow<JobAudioPlaybackProgress?> = _progress.asStateFlow()

    private var player: MediaPlayer? = null

    /** The recording the player holds, or `null` when it holds none (`A11`). */
    private var audioNoteId: String? = null

    /**
     * Where a seek is heading, until the platform reports that it arrived (`A11`).
     *
     * A seek is not synchronous, so the position the player states immediately after one may still be the
     * old one; stating the destination until the seek completes is what stops a released playhead from
     * jumping backwards for a frame.
     */
    private var seekTargetMillis: Int? = null

    /**
     * The loop that reads the position while a recording plays, and the thread this player is used on.
     *
     * A `MediaPlayer` is expected to be used from one thread, and that thread is the main one here: the
     * ticker, the tap that starts a recording and the callbacks the platform raises all run on it, so no
     * part of the player is shared across threads. The position is read ten times a second while a
     * recording plays and never while none does (`BR-042`).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    override fun play(audioNoteId: String, path: String): Boolean {
        // One recording at a time: a second tap replaces the first rather than playing two voices over
        // each other (`BR-042`).
        releasePlayer()
        val started = runCatching {
            player().apply {
                // A field note is speech: it plays at the media volume the technician expects and is
                // routed as speech rather than as music (`ADR-018` A9).
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(path)
                // The player is let go by **this** class, named explicitly: inside this `apply` block an
                // unqualified `release()` is `MediaPlayer.release()`, which would stop the sound but
                // leave the feature reporting a recording that had already ended (`BR-042`).
                setOnCompletionListener { releasePlayer() }
                setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    true
                }
                setOnSeekCompleteListener { seekTargetMillis = null }
                // The recording is a local file of at most 10 MiB (`ADR-018` A3), so preparing it is a
                // local read rather than a network wait, and the player is ready when this returns.
                prepare()
                start()
            }
        }.getOrElse {
            releasePlayer()
            return false
        }
        player = started
        this.audioNoteId = audioNoteId
        _playback.value = JobAudioPlayback(audioNoteId, isPlaying = true)
        emitProgress(positionMillis = 0)
        startTicker()
        return true
    }

    override fun pause() {
        val current = player ?: return
        val noteId = audioNoteId ?: return
        stopTicker()
        if (!runCatching { current.pause() }.isSuccess) {
            // A device that cannot hold the recording is not left looking as though it did (`BR-042`).
            releasePlayer()
            return
        }
        _playback.value = JobAudioPlayback(noteId, isPlaying = false)
        emitProgress()
    }

    override fun resume() {
        val current = player ?: return
        val noteId = audioNoteId ?: return
        if (!runCatching { current.start() }.isSuccess) {
            releasePlayer()
            return
        }
        _playback.value = JobAudioPlayback(noteId, isPlaying = true)
        emitProgress()
        startTicker()
    }

    override fun seekTo(positionMillis: Int) {
        val current = player ?: return
        val target = positionMillis.coerceIn(0, durationOf(current))
        if (!runCatching { current.seekTo(target) }.isSuccess) {
            // A seek that did not happen is not drawn as one: the playhead keeps showing where the player
            // says it is (`BR-042`).
            return
        }
        seekTargetMillis = target
        emitProgress(target)
    }

    override fun stop() {
        releasePlayer()
    }

    /**
     * Lets the current player go and answers that it holds nothing.
     *
     * The fields are cleared first, so a second release — from the completion callback of a player whose
     * recording just ended, or from a replacement — finds nothing to release twice (`BR-042`). The
     * position is released with the recording: nothing is left claiming where a recording that is no
     * longer held had got to (`A11`).
     */
    private fun releasePlayer() {
        stopTicker()
        seekTargetMillis = null
        val current = player
        player = null
        audioNoteId = null
        current?.let { playing ->
            runCatching { playing.stop() }
            runCatching { playing.release() }
        }
        _playback.value = null
        _progress.value = null
    }

    /** Reads the position while a recording plays, once per [POSITION_TICK_MILLIS] (`A11`). */
    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MILLIS)
                if (player == null) break
                emitProgress()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * Reports where the recording the player holds has got to (`A11`).
     *
     * [positionMillis] is stated rather than read when the caller already knows it: the start of a
     * recording, or the destination of a seek the platform has not arrived at yet.
     */
    private fun emitProgress(positionMillis: Int? = null) {
        val noteId = audioNoteId ?: return
        val current = player ?: return
        val position = positionMillis
            ?: seekTargetMillis
            ?: runCatching { current.currentPosition }.getOrDefault(0)
        _progress.value = JobAudioPlaybackProgress(
            audioNoteId = noteId,
            positionMillis = position.coerceAtLeast(0),
            durationMillis = durationOf(current),
        )
    }

    /** The length the player holds, or `0` when it cannot state one (`BR-042`). */
    private fun durationOf(current: MediaPlayer): Int =
        runCatching { current.duration }.getOrDefault(0).coerceAtLeast(0)

    /**
     * A player the platform provides.
     *
     * `MediaPlayer(Context)` is the API this app uses where the platform has it; below it the
     * deprecated no-argument constructor is the only one, and it is the same device player either
     * way. The guard names the API that **added** the constructor (34, `UPSIDE_DOWN_CAKE`) rather
     * than the floor this app happens to support: a guard on a lower level calls a constructor that
     * does not exist there (`dev.md` §1, `BR-042`).
     */
    private fun player(): MediaPlayer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            MediaPlayer(context)
        } else {
            @Suppress("DEPRECATION")
            MediaPlayer()
        }

    private companion object {
        /**
         * How often the position is read while a recording plays.
         *
         * Ten times a second is smooth enough for a playhead to read as moving and cheap enough to run on
         * a phone in the field: the read is one call on the thread the player already lives on, and it
         * happens only while something is playing (`BR-012`).
         */
        const val POSITION_TICK_MILLIS = 100L
    }
}
