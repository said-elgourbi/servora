package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxStore
import com.servora.android.data.offline.OfflineSync
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.domain.model.CapturedJobPhoto
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.JobPhotoSyncState
import com.servora.android.domain.model.PendingJobPhoto
import com.servora.android.domain.model.isDiscardable
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * One technician's photo work on one Job (`BR-015`, `BR-027`, offline standard §9).
 *
 * It is the feature's half of the offline contract: it writes the captured bytes to app-private
 * storage, records the durable reference that survives process death, queues the upload through the
 * **existing** outbox, and asks the **existing** sync trigger to replay it (`§4`, `§13`). It decides
 * no business rule of its own — whether a photo is accepted is the API's answer (`BR-001`, `BR-007`).
 *
 * Both photo sources — the device's camera and the device's own photo picker, which hands over the
 * items the technician chose (`D3`) — go through one preparation before a draft is written: the bytes
 * are brought to a type Servora accepts (`D3b`) and to a size the API accepts (`D3c`), and a photo
 * that cannot be brought to both is refused with its reason rather than recorded (`BR-014`).
 *
 * Local state is scoped by the authenticated subject throughout: a pending photo belongs to the
 * session that captured it and is never shown, uploaded or deleted by another (`§10`).
 */
@Singleton
class JobPhotoSession @Inject constructor(
    private val pending: PendingJobPhotoStore,
    private val files: JobPhotoFiles,
    private val processing: JobPhotoProcessing,
    private val outbox: OutboxStore,
    private val subject: AuthenticatedSubject,
    private val offlineSync: OfflineSync,
    private val clock: Clock,
) {

    /** The Job's not-yet-accepted photos, or `null` when no session can own them (`§10`). */
    fun pendingPhotos(jobId: String): Flow<List<PendingJobPhoto>>? =
        subject.current()?.let { pending.pending(it, jobId) }

    /**
     * Allocates the identity and the app-private file one capture will be written to.
     *
     * The id is generated **before** the camera opens and never changes, so the photo, its file and
     * its idempotency key are the same value from the first attempt onwards (`§5`).
     *
     * The file is named as a JPEG because that is what the device's camera application writes; the type
     * the bytes actually are is proven when the capture is recorded (`D3b`).
     */
    fun beginCapture(): CapturedJobPhoto? {
        val subjectId = subject.current() ?: return null
        val photoId = UUID.randomUUID().toString()
        return CapturedJobPhoto(
            photoId = photoId,
            localPath = files.fileFor(subjectId, photoId, JobPhotoContentType.JPEG).absolutePath,
        )
    }

    /**
     * Records a photo the camera just wrote, or the reason none could be recorded.
     *
     * The record is written **before** the technician confirms anything, because that is what makes
     * the photo survive a dead process (`BR-014`): the bytes are on disk, and this row is what finds
     * them again. What the camera wrote goes through the same preparation as a picked photo, so a
     * capture the API would refuse is refused here instead of becoming an un-uploadable draft
     * (`D3b`, `D3c`).
     */
    suspend fun recordCapture(
        capture: CapturedJobPhoto,
        jobId: String,
        phase: JobPhotoPhase?,
        capturedAt: Instant,
    ): JobPhotoRecordResult {
        val subjectId = subject.current()
        if (subjectId == null) {
            // The session ended before the camera returned: nothing can own the photo, so nothing is
            // kept rather than a row filed under an identity it did not come from (`§10`).
            files.delete(capture.localPath)
            return JobPhotoRecordResult.Refused(JobPhotoRefusal.NOT_SIGNED_IN)
        }
        val bytes = files.readBytes(capture.localPath)
        if (bytes == null || bytes.isEmpty()) {
            files.delete(capture.localPath)
            return JobPhotoRecordResult.Refused(JobPhotoRefusal.NO_BYTES)
        }
        return record(
            subjectId = subjectId,
            photoId = capture.photoId,
            sourcePath = capture.localPath,
            source = bytes,
            jobId = jobId,
            phase = phase,
            capturedAt = capturedAt,
        )
    }

    /**
     * Records a photo the technician chose in the device's photo picker, or the reason none could be
     * recorded.
     *
     * It is the same pipeline the camera path uses, from the same point (`D3`, `D3b`, `D3c`): the
     * caller reads the picked item — a content URI is the picker's business, not this layer's — and
     * everything after that is shared, so a picked photo becomes a draft exactly like a captured one.
     *
     * [phase] is the phase in effect, which is what a capture's draft is recorded with too: a draft
     * always carries a phase the API accepts, so a photo the technician does not finish reviewing —
     * or a process that dies mid-review — leaves evidence that can still be uploaded rather than one
     * the upload handler must refuse (`BR-014`, `BR-027`). The review that follows the draft is where
     * the technician changes it.
     *
     * The photo's own capture time is not available to Servora, so `capturedAt` is the device instant
     * the photo was recorded. Reading EXIF instead of recording what happened is the undecided
     * metadata question (`D7`), and guessing it would be inventing behaviour (`BR-042`).
     */
    suspend fun recordPickedPhoto(
        jobId: String,
        source: ByteArray,
        phase: JobPhotoPhase,
    ): JobPhotoRecordResult {
        val subjectId = subject.current()
            ?: return JobPhotoRecordResult.Refused(JobPhotoRefusal.NOT_SIGNED_IN)
        if (source.isEmpty()) {
            // The item the technician chose handed over nothing, so no photo exists: nothing is
            // recorded and the item is reported (`BR-014`).
            return JobPhotoRecordResult.Refused(JobPhotoRefusal.NOT_READ)
        }
        return record(
            subjectId = subjectId,
            photoId = UUID.randomUUID().toString(),
            sourcePath = null,
            source = source,
            jobId = jobId,
            phase = phase,
            capturedAt = clock.instant(),
        )
    }

    /**
     * The pipeline a photo goes through before it becomes a draft (`D3b`, `D3c`).
     *
     * The bytes are brought to a type and a size the API accepts, written to the app-private file for
     * that type, and only then recorded — so the file, the recorded type and the draft cannot disagree
     * and nothing un-uploadable is created (`BR-014`, offline standard §9). A photo any step refuses
     * leaves nothing behind: the bytes a camera wrote are removed with the refusal, because a file no
     * row references is one nothing could ever upload, remove or show.
     *
     * It runs per item, and the work that is actually expensive — decoding, scaling and re-encoding a
     * large photo — runs on a background dispatcher inside [JobPhotoProcessing], so one photo never
     * blocks the technician's screen and one photo's failure never decides another's (`D3c`).
     */
    private suspend fun record(
        subjectId: String,
        photoId: String,
        sourcePath: String?,
        source: ByteArray,
        jobId: String,
        phase: JobPhotoPhase?,
        capturedAt: Instant,
    ): JobPhotoRecordResult {
        // The type step (`D3b`): bytes whose type Servora already accepts keep it; anything else is
        // converted to JPEG. A photo that cannot be brought to an accepted type is refused.
        val sniffed = JobPhotoContentType.ofBytes(source)
        val typed = if (sniffed != null) {
            PreparedPhoto(source, sniffed)
        } else {
            processing.convertToJpeg(source)?.let { PreparedPhoto(it, JobPhotoContentType.JPEG) }
                ?: return refused(JobPhotoRefusal.TYPE_NOT_ACCEPTED, sourcePath)
        }

        // The size step (`D3c`): only a photo over the API's limit is resized, and once it is, the
        // stored evidence is the resized JPEG rather than the original bytes.
        val prepared = if (typed.bytes.size <= MAX_JOB_PHOTO_BYTES) {
            typed
        } else {
            processing.fitToUploadLimit(typed.bytes)
                ?.let { PreparedPhoto(it, JobPhotoContentType.JPEG) }
                ?: return refused(JobPhotoRefusal.TOO_LARGE, sourcePath)
        }

        // A capture the camera already wrote needs no write at all when neither step produced new
        // bytes for the file it wrote; otherwise the prepared bytes are written to the file their
        // type resolves to, so the file, the recorded type and the draft always agree (offline
        // standard §9).
        val path = files.fileFor(subjectId, photoId, prepared.contentType).absolutePath
        val alreadyStored = sourcePath == path && prepared.bytes === source
        if (!alreadyStored && !files.write(path, prepared.bytes)) {
            return refused(JobPhotoRefusal.NOT_STORED, sourcePath)
        }
        if (sourcePath != null && sourcePath != path) {
            files.delete(sourcePath)
        }

        val photo = PendingJobPhoto(
            photoId = photoId,
            jobId = jobId,
            localPath = path,
            phase = phase,
            note = null,
            capturedAt = capturedAt.toString(),
            mimeType = prepared.contentType.mimeType,
            recordedAt = clock.millis(),
            submitted = false,
        )
        pending.record(photo)
        // The store partitions by the signed-in subject, so a session that ended mid-capture cannot
        // own the photo: nothing is kept rather than a row filed under an identity it did not come
        // from (`§10`).
        if (pending.find(photoId) == null) {
            files.delete(path)
            return JobPhotoRecordResult.Refused(JobPhotoRefusal.NOT_SIGNED_IN)
        }
        return JobPhotoRecordResult.Recorded(photo)
    }

    /** A refusal that records nothing and leaves no bytes behind (`BR-014`, §9). */
    private fun refused(reason: JobPhotoRefusal, sourcePath: String?): JobPhotoRecordResult {
        if (sourcePath != null) {
            files.delete(sourcePath)
        }
        return JobPhotoRecordResult.Refused(reason)
    }

    /** Records the phase and the optional note the technician chose while reviewing a photo. */
    suspend fun reviewCapture(photoId: String, phase: JobPhotoPhase?, note: String?) {
        pending.updateReview(photoId, phase, note?.takeIf { it.isNotBlank() })
    }

    /**
     * Removes the bytes of a capture the camera never produced a photo for.
     *
     * A camera that was dismissed may still have created an empty file, and this is what stops it
     * lingering: the file is deleted unless a record already exists for that photo (`§9`).
     */
    suspend fun abandonCapture(capture: CapturedJobPhoto) {
        if (pending.find(capture.photoId) == null) {
            files.delete(capture.localPath)
        }
    }

    /**
     * Removes a photo the technician decided not to keep, and its bytes with it.
     *
     * A photo that has not been submitted is theirs to remove. So is one whose upload the backend
     * **permanently refused**: that upload is finished — it is never replayed — and what is left is the
     * technician's own draft rather than evidence the API might hold (`D6c`, `BR-014`, `BR-031`).
     *
     * A queued or retrying upload is **not** removable: the backend may still accept it, so removing it
     * would be a local decision about evidence that may already exist (`BR-014`, `BR-027`).
     *
     * A discarded refusal leaves nothing behind: its queue row goes with the file, so no row is left
     * pointing at bytes that are gone and nothing can replay what the technician finished with (`§9`).
     */
    suspend fun discard(photo: PendingJobPhoto): Boolean {
        val upload = uploadState(photo)
        if (!photo.isDiscardable(upload)) {
            return false
        }
        files.delete(photo.localPath)
        if (upload == JobPhotoSyncState.REFUSED) {
            outbox.discardRefused(photo.photoId)
        }
        pending.remove(photo.photoId)
        return true
    }

    /** Queues every photo the technician has not submitted yet, then asks for a replay (`§6`). */
    suspend fun submit(photos: List<PendingJobPhoto>): Int {
        var queued = 0
        for (photo in photos.filterNot { it.submitted }) {
            if (pending.submit(photo)) {
                queued += 1
            }
        }
        if (queued > 0) {
            // A replay may need several round trips, so it is asked for and not waited on: the run
            // belongs to the existing trigger (`§6`).
            offlineSync.requestSync()
        }
        return queued
    }

    /** How far each of the Job's queued uploads has got, derived from the queue itself (`§7`). */
    suspend fun uploadStates(jobId: String): Map<String, JobPhotoSyncState> {
        val subjectId = subject.current() ?: return emptyMap()
        return outbox.queuedFor(subjectId, jobId)
            .filter { it.operationType == JobPhotoOperations.ADD_PHOTO }
            .associate { operation ->
                operation.operationId to operation.state.toSyncState()
            }
    }

    /**
     * How far this photo's own upload has got, or `null` when nothing is queued for it (`§7`).
     *
     * The queue row is what decides whether a photo the technician submitted is finished — a refused
     * upload may be discarded, a waiting one may not (`D6c`).
     */
    private suspend fun uploadState(photo: PendingJobPhoto): JobPhotoSyncState? =
        uploadStates(photo.jobId)[photo.photoId]
}

/**
 * A photo's bytes with the type they were proven to be (`D3b`, `D3c`).
 *
 * It is what the file name, the recorded `mimeType` and the uploaded part type are all taken from, so
 * the three cannot drift.
 */
private class PreparedPhoto(val bytes: ByteArray, val contentType: JobPhotoContentType)

/** Presents a queue state as the upload state the tray shows (`§6`, `§7`). */
private fun OutboxOperationState.toSyncState(): JobPhotoSyncState =
    when (this) {
        OutboxOperationState.PENDING -> JobPhotoSyncState.QUEUED
        OutboxOperationState.IN_FLIGHT -> JobPhotoSyncState.UPLOADING
        OutboxOperationState.FAILED -> JobPhotoSyncState.RETRYING
        OutboxOperationState.REJECTED -> JobPhotoSyncState.REFUSED
    }
