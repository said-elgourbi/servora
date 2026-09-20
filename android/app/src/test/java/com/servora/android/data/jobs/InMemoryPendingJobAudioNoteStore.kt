package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxStore
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.PendingJobAudioNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * [PendingJobAudioNoteStore] in memory, for tests that are about the feature's behaviour rather than
 * about SQLite.
 *
 * It mirrors the stored store's rules — one row per recording, the same subject/job scoping, the same
 * submitted flag, the same queue row written on attach — so a test can drive the notice and the upload
 * without a database. The queries themselves are covered on a device by `OfflineDatabaseTest`
 * (`qa.md` §9).
 */
class InMemoryPendingJobAudioNoteStore(
    private val outbox: OutboxStore? = null,
    private val subjectId: String = "user-1",
) : PendingJobAudioNoteStore {

    private val rows = mutableListOf<Pair<String, PendingJobAudioNote>>()
    private val flows = mutableMapOf<String, MutableStateFlow<List<PendingJobAudioNote>>>()
    private val payloads = JobAudioPayloads(Json)

    /** Every row still stored, oldest first, whatever its subject. */
    val stored: List<PendingJobAudioNote>
        get() = rows.map { it.second }.sortedBy { it.recordedAt }

    /** Whether the queue is written to, so a test can model a session that cannot queue work (§10). */
    var submitsToQueue: Boolean = true

    override fun pending(subjectId: String, jobId: String): Flow<List<PendingJobAudioNote>> =
        flows.getOrPut(key(subjectId, jobId)) { MutableStateFlow(rowsNow(subjectId, jobId)) }
            .asStateFlow()

    override suspend fun record(note: PendingJobAudioNote) {
        rows += subjectId to note
        publish()
    }

    override suspend fun updateReview(audioNoteId: String, phase: EvidencePhase?, note: String?) {
        replace(audioNoteId) { it.copy(phase = phase, note = note) }
    }

    override suspend fun submit(note: PendingJobAudioNote): Boolean {
        if (!submitsToQueue) {
            return false
        }
        replace(note.audioNoteId) { it.copy(submitted = true) }
        // Mirrors the stored store: the upload is queued once, in the outbox, with the recording's own
        // id as its idempotency key (`BR-031`, §5).
        outbox?.record(
            OutboxOperation(
                operationId = note.audioNoteId,
                operationType = JobAudioOperations.ADD_AUDIO,
                targetId = note.jobId,
                subjectId = subjectId,
                payload = payloads.encode(note),
                capturedAt = note.capturedAt,
                recordedAt = note.recordedAt,
                expectedVersion = null,
                attemptCount = 0,
                lastAttemptAt = null,
                lastFailure = null,
                state = OutboxOperationState.PENDING,
            ),
        )
        return true
    }

    override suspend fun find(audioNoteId: String): PendingJobAudioNote? =
        rows.firstOrNull { it.second.audioNoteId == audioNoteId }?.second

    override suspend fun remove(audioNoteId: String) {
        rows.removeAll { it.second.audioNoteId == audioNoteId }
        publish()
    }

    private fun replace(audioNoteId: String, change: (PendingJobAudioNote) -> PendingJobAudioNote) {
        val index = rows.indexOfFirst { it.second.audioNoteId == audioNoteId }
        if (index >= 0) {
            rows[index] = rows[index].first to change(rows[index].second)
            publish()
        }
    }

    private fun publish() {
        flows.forEach { (flowKey, flow) ->
            val parts = flowKey.split('|', limit = 2)
            flow.value = rowsNow(parts[0], parts.getOrElse(1) { "" })
        }
    }

    private fun rowsNow(subjectId: String, jobId: String): List<PendingJobAudioNote> =
        rows.filter { it.first == subjectId && it.second.jobId == jobId }
            .map { it.second }
            .sortedBy { it.recordedAt }

    private fun key(subjectId: String, jobId: String): String = "$subjectId|$jobId"
}
