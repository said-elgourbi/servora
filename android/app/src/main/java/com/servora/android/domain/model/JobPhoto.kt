package com.servora.android.domain.model

/**
 * A photo the technician captured for a Job that the backend has not accepted yet.
 *
 * It is device-local working state: the bytes live in app-private storage and this is the record that
 * makes them findable, so a process that dies before the upload does not lose the evidence
 * (`BR-014`, `BR-015`, offline standard §9). It is never authoritative — only the backend's answer
 * makes a photo part of the Job's Activity (`BR-001`).
 */
data class PendingJobPhoto(
    /** The idempotency key the app generated once, before the first upload attempt (§5). */
    val photoId: String,
    val jobId: String,
    /** The app-private path the captured bytes were written to. Never a blob in the local database. */
    val localPath: String,
    /**
     * The phase the technician chose, or `null` when the stored value cannot be read by this build.
     *
     * A photo is never dropped because of it: the tray still shows it, and the upload is refused
     * rather than sent with a phase Servora does not have (`BR-042`, `BR-014`).
     */
    val phase: EvidencePhase?,
    val note: String?,
    /** The device instant the photo was taken (`BR-031`: display and provenance, never business time). */
    val capturedAt: String,
    val mimeType: String,
    /** The instant the pending record was written; it orders the tray. */
    val recordedAt: Long,
    /** Whether the upload has been queued; an unsaved photo may still be removed by the technician. */
    val submitted: Boolean,
)

/** A captured photo the technician has not confirmed yet, so it is still being reviewed. */
data class CapturedJobPhoto(
    val photoId: String,
    val localPath: String,
)

/** How far one photo's upload has got, as the tray presents it (`§6`, `§7`). */
enum class JobPhotoSyncState {
    /** The upload is queued and has not been attempted yet. */
    QUEUED,

    /** A replay is running right now. */
    UPLOADING,

    /** The attempt did not reach the backend; it will be retried. */
    RETRYING,

    /** The backend refused the upload; the photo is kept and the reason is shown (`BR-032`). */
    REFUSED,
}

/**
 * Whether the photo is still the technician's to remove because it has not been submitted
 * (`BR-014`, `BR-027`, offline standard §9).
 *
 * Before submission nothing has been recorded and nothing is evidence yet, so the draft is entirely
 * the technician's own (`BR-088`).
 */
fun PendingJobPhoto.isRemovable(): Boolean = !submitted

/**
 * Whether the photo's upload is finished because the backend **permanently refused** it (`D6c`).
 *
 * A refusal is terminal: the upload is never replayed (offline standard §6), so what is left on the
 * device is the technician's own draft rather than evidence the API might hold. The tray reports the
 * refusal, which is the reason the discard exists.
 */
fun PendingJobPhoto.isRefusedUpload(upload: JobPhotoSyncState?): Boolean =
    submitted && upload == JobPhotoSyncState.REFUSED

/**
 * Whether the technician may remove this photo from the device (`BR-014`, `BR-027`, §9).
 *
 * A photo that has not been submitted is theirs to remove. Once its upload is queued the backend may
 * already hold the evidence, so the only submitted photo that may still be discarded is one the
 * backend **refused** (`D6c`, `BR-031`): the removal is then the technician's explicit action on their
 * own file rather than a decision about accepted evidence — and it is never a silent loss, because a
 * queued or retrying upload stays exactly as it was.
 */
fun PendingJobPhoto.isDiscardable(upload: JobPhotoSyncState?): Boolean =
    isRemovable() || isRefusedUpload(upload)

/**
 * One photo's upload state, derived from the outbox row that carries it.
 *
 * It is derived, never stored: the tray presents exactly what the queue holds, and the API remains
 * the only authority for whether the photo was recorded (`BR-001`, `§7`).
 */
data class JobPhotoUploadState(
    val photoId: String,
    val state: JobPhotoSyncState,
)
