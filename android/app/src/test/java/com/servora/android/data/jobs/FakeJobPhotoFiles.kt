package com.servora.android.data.jobs

import java.io.File

/**
 * [JobPhotoFiles] in memory.
 *
 * The pending store holds paths, so the bytes are kept here as if the file system had them, and a
 * test decides what the camera wrote. Nothing touches the device's storage, which is what lets the
 * upload path be tested on the JVM (`qa.md` §6.1).
 */
class FakeJobPhotoFiles : JobPhotoFiles {

    private val files = mutableMapOf<String, ByteArray>()

    /** The paths whose bytes are currently stored, as the feature's cleanup is asserted against. */
    val storedPaths: Set<String> get() = files.keys

    /** Whether a `write` succeeds. A test sets it to `false` to model a device that cannot store. */
    var writeSucceeds: Boolean = true

    /** How many times bytes were written, as the pipeline's "already stored" rule is asserted against. */
    var writes: Int = 0
        private set

    /** The bytes stored at [path], or `null` when nothing is stored there. */
    fun bytesAt(path: String): ByteArray? = files[path]

    /** Stands in for what the device's camera application writes after a capture. */
    fun writeCapture(path: String, bytes: ByteArray = JPEG_BYTES) {
        files[path] = bytes
    }

    override fun fileFor(
        subjectId: String,
        photoId: String,
        contentType: JobPhotoContentType,
    ): File = File("app-private/job-photos/$subjectId/$photoId.${contentType.extension}")

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun readBytes(path: String): ByteArray? = files[path]

    override fun write(path: String, bytes: ByteArray): Boolean {
        writes += 1
        if (!writeSucceeds) {
            return false
        }
        files[path] = bytes
        return true
    }

    override fun delete(path: String) {
        files.remove(path)
    }

    companion object {
        /** A minimal but real JPEG header: what a capture writes in these tests. */
        val JPEG_BYTES: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) +
            ByteArray(32) { 0x11 }

        /** A minimal but real PNG signature, which the API accepts as its own type. */
        val PNG_BYTES: ByteArray =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            ) + ByteArray(32) { 0x22 }

        /**
         * Bytes the API's sniffer does not accept, standing in for a format Servora does not take
         * (a HEIC pick is the real case).
         */
        val UNACCEPTED_BYTES: ByteArray = ByteArray(32) { 0x33 }
    }
}
