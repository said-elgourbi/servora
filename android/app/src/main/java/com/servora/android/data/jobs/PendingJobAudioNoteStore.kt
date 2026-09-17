package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxStore
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.PendingJobAudioNote
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
 * The recordings a technician made that the backend has not accepted yet
 * (`docs/architecture/offline-first-architecture.md` §2, §9).
 *
 * It is the feature's durable record of **local** evidence: one row per recording, holding the path of
 * the bytes in app-private storage. It is not a second queue — the upload is queued exactly once, in
 * the outbox, when the technician attaches the recording — and it is not a source of truth: only the
 * backend's answer makes a recording part of the Job's Activity (`BR-001`, `BR-031`).
 *
 * Rows are scoped by the signed-in subject (§10), so one technician's pending evidence is never shown,
 * uploaded or deleted by another session.
 */
interface PendingJobAudioNoteStore {
    /** The Job's pending recordings for one subject, oldest first. */
    fun pending(subjectId: String, jobId: String): Flow<List<PendingJobAudioNote>>

    /** Records a finished recording, before the technician has attached anything. */
    suspend fun record(note: PendingJobAudioNote)

    /** Records the phase and note the technician chose while reviewing a not-yet-attached recording. */
    suspend fun updateReview(audioNoteId: String, phase: EvidencePhase?, note: String?)

    /**
     * Queues the recording's upload and marks it submitted.
     *
     * Returns `false` when the queue could not be attributed to a signed-in subject: the recording stays
     * unattached rather than being filed under an identity it did not come from (§10).
     */
    suspend fun submit(note: PendingJobAudioNote): Boolean

    suspend fun find(audioNoteId: String): PendingJobAudioNote?

    /** Removes the record. The caller decides what happens to the bytes (`JobAudioFiles`). */
    suspend fun remove(audioNoteId: String)
}

/**
 * Reads and writes the payload of a queued audio upload.
 *
 * The payload is the **local** half of the operation: the API receives a multipart request, and
 * `localPath` is the file the bytes are read from at replay time. Keeping the codec in one place means
 * the writer and the reader cannot drift (`BR-041`), and it is testable without Room or a network.
 */
internal class JobAudioPayloads @Inject constructor(private val json: Json) {

    fun encode(note: PendingJobAudioNote): String =
        json.encodeToString(
            JobAudioOperationPayload.serializer(),
            JobAudioOperationPayload(
                localPath = note.localPath,
                phase = jobAudioPhaseCode(note),
                note = note.note,
                capturedAt = note.capturedAt,
                mimeType = note.mimeType,
                fileName = JOB_AUDIO_FILE_NAME,
            ),
        )

    /**
     * The arguments a queued upload carries.
     *
     * A payload this build cannot read means the operation cannot be applied by it; the handler
     * reports that rather than guessing (`BR-042`).
     */
    fun decode(payload: String): JobAudioOperationPayload? =
        try {
            json.decodeFromString(JobAudioOperationPayload.serializer(), payload)
        } catch (failure: SerializationException) {
            null
        }
}

/**
 * Default [PendingJobAudioNoteStore], backed by Room.
 *
 * The recording and the queued operation are written together when the technician attaches it: the
 * outbox row carries the idempotency key and the local file reference, and the pending row records
 * that the recording is no longer the technician's to remove locally (§5, §9).
 */
@Singleton
internal class RoomPendingJobAudioNoteStore @Inject constructor(
    private val dao: PendingJobAudioNoteDao,
    private val outbox: OutboxStore,
    private val payloads: JobAudioPayloads,
    private val subject: AuthenticatedSubject,
    private val clock: Clock,
) : PendingJobAudioNoteStore {

    override fun pending(subjectId: String, jobId: String): Flow<List<PendingJobAudioNote>> =
        dao.pending(subjectId, jobId).map { rows -> rows.map { row -> row.toPendingAudioNote() } }

    override suspend fun record(note: PendingJobAudioNote) {
        val subjectId = subject.current() ?: return
        dao.insert(note.toEntity(subjectId))
    }

    override suspend fun updateReview(audioNoteId: String, phase: EvidencePhase?, note: String?) {
        dao.updateReview(audioNoteId, evidencePhaseNameOrNull(phase), note)
    }

    override suspend fun submit(note: PendingJobAudioNote): Boolean {
        val subjectId = subject.current() ?: return false
        outbox.record(
            OutboxOperation(
                operationId = note.audioNoteId,
                operationType = JobAudioOperations.ADD_AUDIO,
                targetId = note.jobId,
                subjectId = subjectId,
                payload = payloads.encode(note),
                capturedAt = note.capturedAt,
                recordedAt = clock.millis(),
                expectedVersion = null,
                attemptCount = 0,
                lastAttemptAt = null,
                lastFailure = null,
                state = OutboxOperationState.PENDING,
            ),
        )
        dao.markSubmitted(note.audioNoteId)
        return true
    }

    override suspend fun find(audioNoteId: String): PendingJobAudioNote? =
        dao.find(audioNoteId)?.toPendingAudioNote()

    override suspend fun remove(audioNoteId: String) {
        dao.delete(audioNoteId)
    }
}

/** Maps a stored row onto the domain type, keeping an unreadable phase as "not recorded". */
private fun PendingJobAudioNoteEntity.toPendingAudioNote(): PendingJobAudioNote =
    PendingJobAudioNote(
        audioNoteId = audioNoteId,
        jobId = jobId,
        localPath = localPath,
        phase = evidencePhaseOrNull(phase),
        note = note,
        capturedAt = capturedAt,
        durationSeconds = durationSeconds,
        mimeType = mimeType,
        recordedAt = recordedAt,
        submitted = submitted,
    )

/** Maps a pending recording onto its stored row, under the subject that produced it. */
private fun PendingJobAudioNote.toEntity(subjectId: String): PendingJobAudioNoteEntity =
    PendingJobAudioNoteEntity(
        audioNoteId = audioNoteId,
        subjectId = subjectId,
        jobId = jobId,
        localPath = localPath,
        phase = jobAudioPhaseCode(this),
        note = note,
        capturedAt = capturedAt,
        durationSeconds = durationSeconds,
        mimeType = mimeType,
        recordedAt = recordedAt,
        submitted = submitted,
    )
