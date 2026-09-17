package com.servora.android.data.jobs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a recording's bytes live until the backend owns them
 * (`docs/architecture/offline-first-architecture.md` §9).
 *
 * The bytes are written to **app-private storage first** by the device's own recorder and are usable
 * immediately: a lost connection or a dead process cannot take the recording away (`BR-014`, `BR-091`).
 * They are deliberately **not** stored in the local database — that store holds this reference, never a
 * blob — and they are deleted only once the backend has confirmed it holds the recording, or when the
 * technician removes one that was never attached.
 *
 * The directory is partitioned by the session's subject, so one signed-in user's pending evidence is
 * never read, uploaded or deleted by another (§10).
 */
interface JobAudioFiles {
    /**
     * The app-private file one recording is written to.
     *
     * The path is derived from the subject, the recording's own id and the container's extension, so the
     * same recording always resolves to the same file and a retried upload re-reads the same bytes
     * (`BR-031`). The recorder writes it directly, which is why this port offers no `write`: the bytes
     * never pass through the app as a blob (`ADR-018` A2).
     */
    fun fileFor(subjectId: String, audioNoteId: String): File

    fun exists(path: String): Boolean

    /**
     * The size of the recording's bytes, or `null` when they cannot be read.
     *
     * It answers "did the recorder actually write something?" without loading a recording of up to
     * 10 MiB into memory, which is what makes a draft for bytes the device does not hold impossible
     * (`BR-014`).
     */
    fun byteSize(path: String): Long?

    /** The recording's bytes, or `null` when the file cannot be read (it is then never deleted). */
    fun readBytes(path: String): ByteArray?

    /** Removes the stored bytes. */
    fun delete(path: String)
}

/** The default [JobAudioFiles], rooted in the app's private `files` directory. */
@Singleton
class PrivateJobAudioFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) : JobAudioFiles {

    override fun fileFor(subjectId: String, audioNoteId: String): File =
        File(directory(subjectId), "$audioNoteId.$JOB_AUDIO_FILE_EXTENSION")

    override fun exists(path: String): Boolean = File(path).isFile

    override fun byteSize(path: String): Long? =
        runCatching { File(path).takeIf { it.isFile }?.length() }.getOrNull()

    override fun readBytes(path: String): ByteArray? =
        runCatching { File(path).readBytes() }.getOrNull()

    override fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    private fun directory(subjectId: String): File =
        File(File(context.filesDir, DIRECTORY), subjectId).apply { mkdirs() }

    private companion object {
        /** The feature's own directory, so nothing else in the app writes into it. */
        const val DIRECTORY = "job-audio"
    }
}
