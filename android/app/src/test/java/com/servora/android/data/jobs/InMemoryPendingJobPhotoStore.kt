package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxStore
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.PendingJobPhoto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * [PendingJobPhotoStore] in memory, for tests that are about the feature's behaviour rather than
 * about SQLite.
 *
 * It mirrors the stored store's rules — one row per photo, the same subject/job scoping, the same
 * submitted flag — so a test can drive the tray and the upload without a database. The queries
 * themselves are covered on a device by `OfflineDatabaseTest` (`qa.md` §9).
 */
class InMemoryPendingJobPhotoStore(
    private val outbox: OutboxStore? = null,
) : PendingJobPhotoStore {

    private val rows = mutableListOf<Pair<String, PendingJobPhoto>>()
    private val flows = mutableMapOf<String, MutableStateFlow<List<PendingJobPhoto>>>()
    private val payloads = JobPhotoPayloads(Json)

    /** Every row still stored, oldest first, whatever its subject. */
    val stored: List<PendingJobPhoto>
        get() = rows.map { it.second }.sortedBy { it.recordedAt }

    override fun pending(subjectId: String, jobId: String): Flow<List<PendingJobPhoto>> =
        flows.getOrPut(key(subjectId, jobId)) { MutableStateFlow(rowsNow(subjectId, jobId)) }
            .asStateFlow()

    override suspend fun record(photo: PendingJobPhoto) {
        rows += subjectId to photo
        publish()
    }

    override suspend fun updateReview(photoId: String, phase: JobPhotoPhase?, note: String?) {
        replace(photoId) { it.copy(phase = phase, note = note) }
    }

    override suspend fun submit(photo: PendingJobPhoto): Boolean {
        replace(photo.photoId) { it.copy(submitted = true) }
        // Mirrors the stored store: the upload is queued once, in the outbox, with the photo's own id
        // as its idempotency key (`BR-031`, `§5`).
        outbox?.record(
            OutboxOperation(
                operationId = photo.photoId,
                operationType = JobPhotoOperations.ADD_PHOTO,
                targetId = photo.jobId,
                subjectId = subjectId,
                payload = payloads.encode(photo),
                capturedAt = photo.capturedAt,
                recordedAt = photo.recordedAt,
                expectedVersion = null,
                attemptCount = 0,
                lastAttemptAt = null,
                lastFailure = null,
                state = OutboxOperationState.PENDING,
            ),
        )
        return true
    }

    override suspend fun find(photoId: String): PendingJobPhoto? =
        rows.firstOrNull { it.second.photoId == photoId }?.second

    override suspend fun remove(photoId: String) {
        rows.removeAll { it.second.photoId == photoId }
        publish()
    }

    /** The subject a test's photos are recorded under. */
    var subjectId: String = "user-1"

    private fun replace(photoId: String, change: (PendingJobPhoto) -> PendingJobPhoto) {
        val index = rows.indexOfFirst { it.second.photoId == photoId }
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

    private fun rowsNow(subjectId: String, jobId: String): List<PendingJobPhoto> =
        rows.filter { it.first == subjectId && it.second.jobId == jobId }
            .map { it.second }
            .sortedBy { it.recordedAt }

    private fun key(subjectId: String, jobId: String): String = "$subjectId|$jobId"
}
