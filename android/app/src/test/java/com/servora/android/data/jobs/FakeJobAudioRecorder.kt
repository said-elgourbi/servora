package com.servora.android.data.jobs

/**
 * [JobAudioRecorder] without a microphone.
 *
 * It models what the device's recorder does to the file: nothing is written while it runs, and the
 * container appears when it stops. That is what the feature's own rules are asserted against — a take
 * whose bytes exist becomes a durable draft, and one the recorder could not finish or left empty becomes
 * nothing at all (`BR-014`, `BR-042`).
 */
class FakeJobAudioRecorder(private val files: FakeJobAudioFiles) : JobAudioRecorder {

    /** Whether the recorder can start. A test sets it to `false` to model a device that cannot record. */
    var startSucceeds: Boolean = true

    /** Whether the recorder can finish. A test sets it to `false` to model a take that was cut short. */
    var stopSucceeds: Boolean = true

    /** What the recorder writes when it stops. An empty array models a take with no bytes in it. */
    var recordingBytes: ByteArray = FakeJobAudioFiles.M4A_BYTES

    /** How many times a recording was started, as the one-recording-at-a-time rule is asserted against. */
    var startCalls: Int = 0
        private set

    /** How many times the recorder was released, as the abandoned-recording rule is asserted against. */
    var releases: Int = 0
        private set

    private var path: String? = null

    override fun start(path: String): Boolean {
        startCalls += 1
        if (!startSucceeds) {
            return false
        }
        this.path = path
        return true
    }

    override fun stop(): Boolean {
        val writing = path ?: return false
        path = null
        if (!stopSucceeds) {
            return false
        }
        files.writeBytes(writing, recordingBytes)
        return true
    }

    override fun release() {
        releases += 1
        path = null
    }
}
