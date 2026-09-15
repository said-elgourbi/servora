package com.servora.android.domain.model

/**
 * The field-work phase a photo was taken in (`BR-027`).
 *
 * The codes are the stable, machine-readable values the API exchanges and stores (`BR-028`,
 * `BR-041`); the screen resolves a localized label for one rather than showing the code, and no
 * translated text is ever sent as the value.
 */
enum class JobPhotoPhase {
    /** The state of the work before the technician started. */
    BEFORE_WORK,

    /** The work in progress. */
    DURING_WORK,

    /** The result once the work was done. */
    AFTER_WORK,
}

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
    val phase: JobPhotoPhase?,
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
 * One photo's upload state, derived from the outbox row that carries it.
 *
 * It is derived, never stored: the tray presents exactly what the queue holds, and the API remains
 * the only authority for whether the photo was recorded (`BR-001`, `§7`).
 */
data class JobPhotoUploadState(
    val photoId: String,
    val state: JobPhotoSyncState,
)
