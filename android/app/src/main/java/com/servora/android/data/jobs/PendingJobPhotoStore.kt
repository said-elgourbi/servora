package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxStore
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.PendingJobPhoto
import com.servora.android.domain.model.evidencePhaseNameOrNull
import com.servora.android.domain.model.evidencePhaseOrNull
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The photos a technician captured that the backend has not accepted yet
 * (`docs/architecture/offline-first-architecture.md` §2, §9).
 *
 * It is the feature's durable record of **local** evidence: one row per photo, holding the path of
 * the bytes in app-private storage. It is not a second queue — the upload is queued exactly once, in
 * the outbox, when the technician submits the tray — and it is not a source of truth: only the
 * backend's answer makes a photo part of the Job's Activity (`BR-001`, `BR-031`).
 *
 * Rows are scoped by the signed-in subject (`§10`), so one technician's pending evidence is never
 * shown, uploaded or deleted by another session.
 */
interface PendingJobPhotoStore {
    /** The Job's pending photos for one subject, oldest first. */
    fun pending(subjectId: String, jobId: String): Flow<List<PendingJobPhoto>>

    /** Records a captured photo, before the technician has decided anything about it. */
    suspend fun record(photo: PendingJobPhoto)

    /** Records the phase and note the technician chose while reviewing a not-yet-submitted photo. */
    suspend fun updateReview(photoId: String, phase: EvidencePhase?, note: String?)

    /**
     * Queues the photo's upload and marks it submitted.
     *
     * Returns `false` when the queue could not be attributed to a signed-in subject: the photo stays
     * unsubmitted rather than being filed under an identity it did not come from (`§10`).
     */
    suspend fun submit(photo: PendingJobPhoto): Boolean

    suspend fun find(photoId: String): PendingJobPhoto?

    /** Removes the record. The caller decides what happens to the bytes (`JobPhotoFiles`). */
    suspend fun remove(photoId: String)
}

/**
 * Reads and writes the payload of a queued photo upload.
 *
 * The payload is the **local** half of the operation: the API receives a multipart request, and
 * `localPath` is the file the bytes are read from at replay time. Keeping the codec in one place means
 * the writer and the reader cannot drift (`BR-041`), and it is testable without Room or a network.
 */
internal class JobPhotoPayloads @Inject constructor(private val json: Json) {

    fun encode(photo: PendingJobPhoto): String =
        json.encodeToString(
            JobPhotoOperationPayload.serializer(),
            JobPhotoOperationPayload(
                localPath = photo.localPath,
                phase = evidencePhaseNameOrNull(photo.phase),
                note = photo.note,
                capturedAt = photo.capturedAt,
                mimeType = photo.mimeType,
                fileName = jobPhotoFileName(photo.mimeType),
            ),
        )

    /**
     * The arguments a queued upload carries.
     *
     * A payload this build cannot read means the operation cannot be applied by it; the handler
     * reports that rather than guessing (`BR-042`).
     */
    fun decode(payload: String): JobPhotoOperationPayload? =
        try {
            json.decodeFromString(JobPhotoOperationPayload.serializer(), payload)
        } catch (failure: SerializationException) {
            null
        }
}

/**
 * Default [PendingJobPhotoStore], backed by Room.
 *
 * The pending photo and the queued operation are written together when the technician submits: the
 * outbox row carries the idempotency key and the local file reference, and the pending row records
 * that the photo is no longer the technician's to remove locally (`§5`, `§9`).
 */
@Singleton
internal class RoomPendingJobPhotoStore @Inject constructor(
    private val dao: PendingJobPhotoDao,
    private val outbox: OutboxStore,
    private val payloads: JobPhotoPayloads,
    private val subject: AuthenticatedSubject,
    private val clock: Clock,
) : PendingJobPhotoStore {

    override fun pending(subjectId: String, jobId: String): Flow<List<PendingJobPhoto>> =
        dao.pending(subjectId, jobId).map { rows -> rows.map { row -> row.toPendingPhoto() } }

    override suspend fun record(photo: PendingJobPhoto) {
        val subjectId = subject.current() ?: return
        dao.insert(photo.toEntity(subjectId))
    }

    override suspend fun updateReview(photoId: String, phase: EvidencePhase?, note: String?) {
        dao.updateReview(photoId, evidencePhaseNameOrNull(phase), note)
    }

    override suspend fun submit(photo: PendingJobPhoto): Boolean {
        val subjectId = subject.current() ?: return false
        outbox.record(
            OutboxOperation(
                operationId = photo.photoId,
                operationType = JobPhotoOperations.ADD_PHOTO,
                targetId = photo.jobId,
                subjectId = subjectId,
                payload = payloads.encode(photo),
                capturedAt = photo.capturedAt,
                recordedAt = clock.millis(),
                expectedVersion = null,
                attemptCount = 0,
                lastAttemptAt = null,
                lastFailure = null,
                state = OutboxOperationState.PENDING,
            ),
        )
        dao.markSubmitted(listOf(photo.photoId))
        return true
    }

    override suspend fun find(photoId: String): PendingJobPhoto? =
        dao.find(photoId)?.toPendingPhoto()

    override suspend fun remove(photoId: String) {
        dao.delete(photoId)
    }
}

/** Maps a stored row onto the domain type, keeping an unreadable phase as "not recorded". */
private fun PendingJobPhotoEntity.toPendingPhoto(): PendingJobPhoto =
    PendingJobPhoto(
        photoId = photoId,
        jobId = jobId,
        localPath = localPath,
        phase = evidencePhaseOrNull(phase),
        note = note,
        capturedAt = capturedAt,
        mimeType = mimeType,
        recordedAt = recordedAt,
        submitted = submitted,
    )

/** Maps a pending photo onto its stored row, under the subject that produced it. */
private fun PendingJobPhoto.toEntity(subjectId: String): PendingJobPhotoEntity =
    PendingJobPhotoEntity(
        photoId = photoId,
        subjectId = subjectId,
        jobId = jobId,
        localPath = localPath,
        phase = evidencePhaseNameOrNull(phase),
        note = note,
        capturedAt = capturedAt,
        mimeType = mimeType,
        recordedAt = recordedAt,
        submitted = submitted,
    )
