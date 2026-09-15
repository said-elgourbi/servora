package com.servora.android.data.jobs

import com.servora.android.domain.model.JobPhotoPhase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The photo evidence operation the outbox queues (`BR-015`, `BR-027`).
 *
 * There is one operation, because the backend has one route: a photo is uploaded and its record is
 * created by the same request, so queueing them separately would let the two disagree.
 */
object JobPhotoOperations {
    /** The stable machine-readable operation code the replay engine resolves a handler for (§4). */
    const val ADD_PHOTO = "job.photo.add"

    /** Every operation this feature queues. */
    val ALL: Set<String> = setOf(ADD_PHOTO)
}

/**
 * What a queued photo upload carries.
 *
 * The API receives a multipart request, not this JSON: the payload is the **local** half of the
 * operation, and `localPath` is the file the bytes are read from at replay time. That is what §9
 * describes — the outbox references the local file, and the file is kept until the backend confirms
 * it owns the evidence.
 *
 * `phase` is nullable because the local store may hold a photo whose phase this build cannot read; the
 * handler then refuses the operation instead of sending a phase Servora does not have (`BR-042`).
 */
@Serializable
data class JobPhotoOperationPayload(
    @SerialName("localPath") val localPath: String,
    @SerialName("phase") val phase: String?,
    @SerialName("note") val note: String? = null,
    @SerialName("capturedAt") val capturedAt: String,
    @SerialName("mimeType") val mimeType: String,
    @SerialName("fileName") val fileName: String,
)

/** The phase name stored with a pending photo, or `null` when the photo has no recorded phase. */
internal fun phaseNameOrNull(phase: JobPhotoPhase?): String? = phase?.name

/** The stable phase codes this build knows, for reading a stored value back. */
internal fun jobPhotoPhaseOrNull(code: String?): JobPhotoPhase? =
    JobPhotoPhase.entries.firstOrNull { it.name == code }

/** The name a file part is given when the photo's recorded type cannot be read back. */
internal const val DEFAULT_PHOTO_FILE_NAME = "photo.jpg"

/**
 * The name the upload's file part carries.
 *
 * Only the type the request declares decides how the API reads the part — it sniffs the bytes and
 * refuses a declared type that disagrees with them — so the name follows the recorded type's own
 * extension instead of assuming JPEG (`D3b`). The fallback is for a stored type this build cannot read:
 * the rows older builds wrote are JPEG, and the API's answer remains the authority either way.
 */
internal fun jobPhotoFileName(mimeType: String): String =
    JobPhotoContentType.ofMimeType(mimeType)?.let { "photo.${it.extension}" }
        ?: DEFAULT_PHOTO_FILE_NAME
