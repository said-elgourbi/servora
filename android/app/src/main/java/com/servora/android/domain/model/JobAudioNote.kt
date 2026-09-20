package com.servora.android.domain.model

/**
 * An audio note the technician recorded on a Job that the backend has not accepted yet
 * (`BR-091`, `ADR-018`).
 *
 * It is device-local working state, exactly as [PendingJobPhoto] is for a photo: the bytes live in
 * app-private storage and this is the record that makes them findable, so a process that dies before
 * the upload does not lose the evidence (`BR-014`, `BR-013`, offline standard §9). It is never
 * authoritative — only the backend's answer makes a recording part of the Job's Activity (`BR-001`).
 *
 * Audio is evidence of its own kind (`ADR-018` A1), so this is its own record rather than a photo
 * record carrying a kind: the two kinds have their own tables, capabilities and Activity codes.
 */
data class PendingJobAudioNote(
    /** The idempotency key the app generated once, before the recording started (`BR-031`, §5). */
    val audioNoteId: String,
    val jobId: String,
    /** The app-private path the recording was written to. Never a blob in the local database. */
    val localPath: String,
    /**
     * The phase the technician chose, or `null` when the stored value cannot be read by this build.
     *
     * A recording is never dropped because of it: the notice still shows it, and the upload is refused
     * rather than sent with a phase Servora does not have (`BR-042`, `BR-014`).
     */
    val phase: EvidencePhase?,
    val note: String?,
    /** The device instant the recording was made (`BR-031`: display and provenance, never business time). */
    val capturedAt: String,
    /**
     * The recording's length as this device measured it, in whole seconds.
     *
     * It is what the review and the notice show while the recording is still local. The length the API
     * reads from the container is the authoritative one and is what it stores (`ADR-018` A3), so this
     * value is never sent as a product fact.
     */
    val durationSeconds: Int,
    /** The container the device recorded in, which is the one the API accepts (`ADR-018` A2). */
    val mimeType: String,
    /** The instant the pending record was written; it orders the notice. */
    val recordedAt: Long,
    /** Whether the upload has been queued; an unattached recording may still be removed by the technician. */
    val submitted: Boolean,
)

/** A recording the technician has started, before it has been recorded as a draft. */
data class CapturedJobAudioNote(
    val audioNoteId: String,
    val localPath: String,
    /**
     * The device instant the recording began (`BR-031`: provenance only, never business time).
     *
     * It is fixed before the recorder starts, so the draft an interrupted recording produces still
     * states when the technician made it rather than when the recorder happened to finish.
     */
    val startedAt: String,
)

/**
 * How far one audio note's upload has got, as the notice presents it (§6, §7).
 *
 * The canonical vocabulary is the outbox's own [com.servora.android.data.offline.OutboxOperationState];
 * this is the same projection of it the photo kind presents, named for the kind that reports it
 * (`ADR-018` A1 keeps evidence per kind rather than unifying it).
 */
enum class JobAudioSyncState {
    /** The upload is queued and has not been attempted yet. */
    QUEUED,

    /** A replay is running right now. */
    UPLOADING,

    /** The attempt did not reach the backend; it will be retried. */
    RETRYING,

    /** The backend refused the upload; the recording is kept and the reason is shown (`BR-032`). */
    REFUSED,
}

/**
 * Whether the recording is still the technician's to remove because it has not been submitted
 * (`BR-014`, `BR-091`, §9).
 *
 * Before submission nothing has been recorded and nothing is evidence yet, so the draft is entirely
 * the technician's own (`BR-088`).
 */
fun PendingJobAudioNote.isRemovable(): Boolean = !submitted

/**
 * Whether the recording's upload is finished because the backend **permanently refused** it.
 *
 * A refusal is terminal: the upload is never replayed (§6), so what is left on the device is the
 * technician's own draft rather than evidence the API might hold. The notice reports the refusal,
 * which is the reason the discard exists.
 */
fun PendingJobAudioNote.isRefusedUpload(upload: JobAudioSyncState?): Boolean =
    submitted && upload == JobAudioSyncState.REFUSED

/**
 * Whether the technician may remove this recording from the device (`BR-014`, `BR-091`, §9).
 *
 * An unattached recording is theirs to remove. Once its upload is queued the backend may already hold
 * the evidence, so the only submitted recording that may still be discarded is one the backend
 * **refused** (`BR-031`) — and it is never a silent loss, because a queued or retrying upload stays
 * exactly as it was.
 */
fun PendingJobAudioNote.isDiscardable(upload: JobAudioSyncState?): Boolean =
    isRemovable() || isRefusedUpload(upload)
