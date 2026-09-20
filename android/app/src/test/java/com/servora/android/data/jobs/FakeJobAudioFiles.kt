package com.servora.android.data.jobs

import java.io.File

/**
 * [JobAudioFiles] in memory.
 *
 * The pending store holds paths, so the bytes are kept here as if the file system had them, and a test
 * decides what the recorder wrote. Nothing touches the device's storage, which is what lets the upload
 * path be tested on the JVM (`qa.md` §6.1).
 */
class FakeJobAudioFiles : JobAudioFiles {

    private val files = mutableMapOf<String, ByteArray>()

    /** The paths whose bytes are currently stored, as the feature's cleanup is asserted against. */
    val storedPaths: Set<String> get() = files.keys

    /** The bytes stored at [path], or `null` when nothing is stored there. */
    fun bytesAt(path: String): ByteArray? = files[path]

    /** Stands in for the file the device's recorder writes when the recording ends. */
    fun writeBytes(path: String, bytes: ByteArray) {
        files[path] = bytes
    }

    override fun fileFor(subjectId: String, audioNoteId: String): File =
        File("app-private/job-audio/$subjectId/$audioNoteId.$JOB_AUDIO_FILE_EXTENSION")

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun byteSize(path: String): Long? = files[path]?.size?.toLong()

    override fun readBytes(path: String): ByteArray? = files[path]

    override fun delete(path: String) {
        files.remove(path)
    }

    companion object {
        /**
         * Bytes standing in for a recording.
         *
         * They are not a real MP4: what these tests cover is the device's own half — that the bytes the
         * recorder wrote are the bytes the upload carries, and that they are kept until the API answers.
         * The container itself is the API's rule and is tested there (`ADR-018` A2/A3).
         */
        val M4A_BYTES: ByteArray = ByteArray(64) { 0x11 }
    }
}
