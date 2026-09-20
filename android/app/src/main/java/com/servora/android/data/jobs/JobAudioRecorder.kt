package com.servora.android.data.jobs

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's microphone, recording into the one container the API accepts
 * (`BR-091`, `ADR-018` A2).
 *
 * It is a port because the recorder is the one part of this feature that cannot exist off a device:
 * what the feature does with a finished recording — recording the draft, queueing the upload, keeping
 * the bytes until the backend owns them — is testable without a microphone (`qa.md` §6.1).
 *
 * The recording is **mono AAC in an MP4 container**, which is the `audio/mp4` the API sniffs from the
 * bytes (`ADR-018` A2). A device whose recorder cannot produce it reports the failure rather than
 * writing a format Servora would refuse.
 */
interface JobAudioRecorder {
    /** Starts recording into [path]; `false` when the device's recorder could not start. */
    fun start(path: String): Boolean

    /**
     * Stops and finalizes the recording; `false` when it could not be finished.
     *
     * A recorder that cannot finish may leave an unusable file, so the caller deletes it rather than
     * recording a draft for bytes the API would refuse (`BR-014`).
     */
    fun stop(): Boolean

    /** Releases the device's recorder after a recording that did not finish, or one that was dropped. */
    fun release()
}

/** The device's own [MediaRecorder], wired for a field note (`ADR-018` A2, A9). */
@Singleton
class MediaRecorderJobAudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : JobAudioRecorder {

    private var recorder: MediaRecorder? = null

    override fun start(path: String): Boolean {
        // One recorder at a time: a recording in progress is never replaced by a second one (`BR-042`).
        release()
        val created = runCatching {
            recorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // Mono at a speech-friendly rate and bit rate: a recording is a technician's note, and
                // the API's own byte limit assumes roughly this (`ADR-018` A3). The settings are not a
                // product limit — the length the API reads from the container is (`A3`).
                setAudioChannels(MONO)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BIT_RATE_BITS_PER_SECOND)
                setOutputFile(path)
                prepare()
                start()
            }
        }.getOrElse {
            release()
            return false
        }
        recorder = created
        return true
    }

    override fun stop(): Boolean {
        val current = recorder ?: return false
        recorder = null
        val finished = runCatching { current.stop() }.isSuccess
        runCatching { current.release() }
        return finished
    }

    override fun release() {
        recorder?.let { current -> runCatching { current.release() } }
        recorder = null
    }

    /**
     * A recorder the platform provides.
     *
     * `MediaRecorder(Context)` is the API this app uses from Android 12 on; below it the deprecated
     * constructor is the only one, and it is the same device recorder either way.
     */
    private fun recorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private companion object {
        const val MONO = 1
        const val SAMPLE_RATE_HZ = 44_100
        const val BIT_RATE_BITS_PER_SECOND = 64_000
    }
}
