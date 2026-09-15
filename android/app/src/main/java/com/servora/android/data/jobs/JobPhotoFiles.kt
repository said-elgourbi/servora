package com.servora.android.data.jobs

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a captured photo's bytes live until the backend owns them
 * (`docs/architecture/offline-first-architecture.md` §9).
 *
 * The bytes are written to **app-private storage first** and are usable immediately: the technician
 * sees the photo, and a lost connection or a dead process cannot take it away (`BR-014`, `BR-015`).
 * They are deliberately **not** stored in the local database — that store holds this reference, never
 * a blob — and they are deleted only once the backend has confirmed it holds the photo, or when the
 * technician removes a photo that was never submitted.
 *
 * The directory is partitioned by the session's subject, so one signed-in user's pending evidence is
 * never read, uploaded or deleted by another (`§10`).
 */
interface JobPhotoFiles {
    /**
     * The app-private file one pending photo's bytes are written to.
     *
     * The path is derived from the subject, the photo's own id and the type the bytes are stored as, so
     * the same photo always resolves to the same file and a retried upload re-reads the same bytes
     * (`BR-031`). The type's own extension is what the file carries, so the name, the recorded type and
     * the API's object both describe the same photo.
     */
    fun fileFor(subjectId: String, photoId: String, contentType: JobPhotoContentType): File

    fun exists(path: String): Boolean

    /** The photo's bytes, or `null` when the file cannot be read (it is then never deleted). */
    fun readBytes(path: String): ByteArray?

    /**
     * Stores [bytes] at [path], reporting whether they were written.
     *
     * The device's camera writes its own file through the app's `FileProvider`; a photo picked from
     * another application is copied here instead (`D3`). A write that fails is **reported**, never
     * assumed: a draft must not be recorded for bytes the device does not hold (`BR-014`).
     */
    fun write(path: String, bytes: ByteArray): Boolean

    /** Removes the stored bytes. */
    fun delete(path: String)
}

/** The default [JobPhotoFiles], rooted in the app's private `files` directory. */
@Singleton
class PrivateJobPhotoFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) : JobPhotoFiles {

    override fun fileFor(
        subjectId: String,
        photoId: String,
        contentType: JobPhotoContentType,
    ): File = File(directory(subjectId), "$photoId.${contentType.extension}")

    override fun exists(path: String): Boolean = File(path).isFile

    override fun readBytes(path: String): ByteArray? =
        runCatching { File(path).readBytes() }.getOrNull()

    override fun write(path: String, bytes: ByteArray): Boolean =
        runCatching {
            File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }.isSuccess

    override fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    private fun directory(subjectId: String): File =
        File(File(context.filesDir, DIRECTORY), subjectId).apply { mkdirs() }

    private companion object {
        /** The feature's own directory, so nothing else in the app writes into it. */
        const val DIRECTORY = "job-photos"
    }
}

/**
 * The content URI a photo file is handed to the device's camera application with.
 *
 * A `file://` URI cannot be shared with another application, so the camera writes through the app's
 * `FileProvider`, which exposes exactly the photo directory the feature owns
 * (`res/xml/file_paths.xml`).
 */
fun jobPhotoCaptureUri(context: Context, path: String): Uri =
    FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(path),
    )
