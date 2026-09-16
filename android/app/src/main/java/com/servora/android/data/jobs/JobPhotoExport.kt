package com.servora.android.data.jobs

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Taking one photo out of Servora: into the device's own gallery, or to another application through the
 * platform's share sheet (`D12`, `D13`, `BR-027`).
 *
 * Both are the technician's explicit actions on evidence that already exists. Nothing here creates,
 * edits, deletes or re-records a photo, and nothing here decides who may do it: the API authorizes
 * reading the bytes (`evidence.view`), and the client draws the actions on the same capability
 * (`BR-006`, `BR-007`, `BR-011`). A photo that leaves Servora this way is a copy the app no longer
 * controls, which is why the action is always the user's own (`BR-027`).
 */

/** Where one photo's bytes are read from for an export. */
sealed interface JobPhotoExportSource {
    /** The photo's identifier, which is the same one the device and the backend use (`§1`). */
    val photoId: String

    /** A photo whose bytes this device still holds, before the backend accepted it (`§9`). */
    data class Local(override val photoId: String, val path: String) : JobPhotoExportSource

    /** Evidence the backend holds, read through the API (`BR-015`). */
    data class Evidence(override val photoId: String, val jobId: String) : JobPhotoExportSource
}

/** What an export did, as the screen reports it (`BR-042`). */
enum class JobPhotoExportOutcome {
    /** The photo is in the device's own gallery. */
    SAVED,

    /** The photo was handed to the application the technician chose. */
    SHARED,

    /** The bytes could not be read, so nothing was written anywhere. */
    UNREADABLE,

    /** The write failed on this device. */
    FAILED,

    /** Saving needs a storage permission the technician has not granted yet (`D12`). */
    PERMISSION_REQUIRED,

    /** No application on this device accepts a shared image (`D13`). */
    NO_SHARE_TARGET,
}

/** The one photo-export capability the screen uses. */
interface JobPhotoExporter {
    /** Writes the photo into the device's own gallery (`D12`). */
    suspend fun saveToDevice(source: JobPhotoExportSource): JobPhotoExportOutcome

    /** Hands the photo to another application, under [chooserTitle] (`D13`). */
    suspend fun share(source: JobPhotoExportSource, chooserTitle: String): JobPhotoExportOutcome
}

/** The bytes one export writes, and the file it is written as. */
internal class JobPhotoExportBytes(
    val fileName: String,
    val bytes: ByteArray,
    val contentType: JobPhotoContentType,
)

/**
 * The name a photo is written out with: the id it is already known by, and the extension of the type it
 * was proven to be — so the copy a user finds in their gallery still names the record it came from, and
 * the name, the bytes and the type cannot drift (`BR-041`).
 */
internal fun jobPhotoExportFileName(photoId: String, contentType: JobPhotoContentType): String =
    "Servora-$photoId.${contentType.extension}"

/** Where an export writes: the device's gallery, or the platform's share sheet. */
internal interface JobPhotoExportTarget {
    fun saveToGallery(photo: JobPhotoExportBytes): SaveResult

    fun share(photo: JobPhotoExportBytes, chooserTitle: String): ShareResult

    /** What writing into the device's gallery did. */
    enum class SaveResult { SAVED, PERMISSION_REQUIRED, FAILED }

    /** What handing the photo to another application did. */
    enum class ShareResult { SHARED, NO_TARGET, FAILED }
}

/**
 * Reads the bytes an export needs, from wherever they are (`D12`, `D13`).
 *
 * A photo the device still holds is read from the file that holds it; evidence is read through the API
 * with the same `401 → renew once` path every other read of evidence uses ([JobPhotoContentReader]).
 *
 * Which type the bytes are is proven from the bytes themselves — the rule the upload path uses
 * (`BR-041`) — with the type the API served as the fallback. Bytes that are not a type Servora accepts
 * are not written anywhere: an export must not produce a file Servora cannot account for (`BR-042`).
 */
internal class JobPhotoExportContent @Inject constructor(
    private val files: JobPhotoFiles,
    private val reader: JobPhotoContentReader,
) {

    /** The bytes to write, or `null` when they cannot be read or are not a type Servora accepts. */
    suspend fun read(source: JobPhotoExportSource): JobPhotoExportBytes? {
        val bytes: ByteArray
        val contentType: JobPhotoContentType?
        when (source) {
            is JobPhotoExportSource.Local -> {
                bytes = files.readBytes(source.path) ?: return null
                contentType = JobPhotoContentType.ofBytes(bytes)
            }

            is JobPhotoExportSource.Evidence -> {
                val content = reader.read(source.jobId, source.photoId) ?: return null
                bytes = content.bytes
                contentType = JobPhotoContentType.ofBytes(bytes) ?: content.contentType
            }
        }
        val proven = contentType ?: return null
        return JobPhotoExportBytes(
            fileName = jobPhotoExportFileName(source.photoId, proven),
            bytes = bytes,
            contentType = proven,
        )
    }
}

/**
 * The default [JobPhotoExporter]: reads the photo's bytes, then writes them where the technician asked
 * (`D12`, `D13`).
 *
 * The device work runs off the main thread, because writing a photo and starting the share sheet are
 * I/O: a technician moving through photos must never wait on a disk write.
 */
@Singleton
internal class DefaultJobPhotoExporter @Inject constructor(
    private val content: JobPhotoExportContent,
    private val target: JobPhotoExportTarget,
) : JobPhotoExporter {

    override suspend fun saveToDevice(source: JobPhotoExportSource): JobPhotoExportOutcome =
        withContext(Dispatchers.IO) {
            val photo = content.read(source) ?: return@withContext JobPhotoExportOutcome.UNREADABLE
            when (target.saveToGallery(photo)) {
                JobPhotoExportTarget.SaveResult.SAVED -> JobPhotoExportOutcome.SAVED
                JobPhotoExportTarget.SaveResult.PERMISSION_REQUIRED ->
                    JobPhotoExportOutcome.PERMISSION_REQUIRED

                JobPhotoExportTarget.SaveResult.FAILED -> JobPhotoExportOutcome.FAILED
            }
        }

    override suspend fun share(
        source: JobPhotoExportSource,
        chooserTitle: String,
    ): JobPhotoExportOutcome = withContext(Dispatchers.IO) {
        val photo = content.read(source) ?: return@withContext JobPhotoExportOutcome.UNREADABLE
        when (target.share(photo, chooserTitle)) {
            JobPhotoExportTarget.ShareResult.SHARED -> JobPhotoExportOutcome.SHARED
            JobPhotoExportTarget.ShareResult.NO_TARGET -> JobPhotoExportOutcome.NO_SHARE_TARGET
            JobPhotoExportTarget.ShareResult.FAILED -> JobPhotoExportOutcome.FAILED
        }
    }
}
