package com.servora.android.data.jobs

import com.servora.android.domain.model.PendingJobAudioNote
import com.servora.android.domain.model.evidencePhaseNameOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The audio evidence operation the outbox queues (`BR-091`, `ADR-018` A10).
 *
 * There is one operation, because the backend has one route: a recording is uploaded and its record is
 * created by the same request, so queueing them separately would let the two disagree.
 */
object JobAudioOperations {
    /** The stable machine-readable operation code the replay engine resolves a handler for (§4). */
    const val ADD_AUDIO = "job.audio.add"

    /** Every operation this feature queues. */
    val ALL: Set<String> = setOf(ADD_AUDIO)
}

/**
 * The one container the API accepts for a recording (`ADR-018` A2).
 *
 * The device records exactly this — an MP4 container holding AAC audio — because a second accepted
 * type would exist only for uploads Servora's own client cannot make, and a device that cannot produce
 * it is a refusal rather than a silently different format. The API decides the type from the bytes
 * rather than from this declaration (`BR-015`); stating it here is what makes a recording that the
 * API would refuse impossible to record in the first place.
 */
const val JOB_AUDIO_MIME_TYPE = "audio/mp4"

/** The extension the container is stored and uploaded under, so the name and the type agree. */
internal const val JOB_AUDIO_FILE_EXTENSION = "m4a"

/** The name the upload's file part carries. */
internal const val JOB_AUDIO_FILE_NAME = "audio.m4a"

/**
 * What a queued audio upload carries.
 *
 * The API receives a multipart request, not this JSON: the payload is the **local** half of the
 * operation, and `localPath` is the file the bytes are read from at replay time. That is what §9
 * describes — the outbox references the local file, and the file is kept until the backend confirms
 * it owns the evidence.
 *
 * The length is deliberately **not** in the payload: the API reads it from the container and refuses
 * one it cannot read (`ADR-018` A3), so sending a declared length would be a limit a client could
 * assert. `phase` is nullable because the local store may hold a recording whose phase this build
 * cannot read; the handler then refuses the operation instead of sending a phase Servora does not have
 * (`BR-042`).
 */
@Serializable
data class JobAudioOperationPayload(
    @SerialName("localPath") val localPath: String,
    @SerialName("phase") val phase: String?,
    @SerialName("note") val note: String? = null,
    @SerialName("capturedAt") val capturedAt: String,
    @SerialName("mimeType") val mimeType: String,
    @SerialName("fileName") val fileName: String,
)

/** The phase code a queued recording was stored with, or `null` when it carries no phase. */
internal fun jobAudioPhaseCode(note: PendingJobAudioNote): String? =
    evidencePhaseNameOrNull(note.phase)
