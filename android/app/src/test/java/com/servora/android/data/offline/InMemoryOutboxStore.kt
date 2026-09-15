package com.servora.android.data.offline

/**
 * [OutboxStore] in memory, for tests that are about the engine rather than about SQLite.
 *
 * It mirrors the SQL-backed store's rules — replayable order, retention on failure, removal only on
 * acceptance — so a test can drive the engine without a database. The queries themselves are covered
 * on a device by `OfflineDatabaseTest` (`qa.md` §9).
 */
class InMemoryOutboxStore : OutboxStore {

    private val rows = mutableListOf<OutboxOperation>()

    /** Every row still stored, oldest first. */
    val stored: List<OutboxOperation>
        get() = rows.sortedBy { it.recordedAt }

    override suspend fun record(operation: OutboxOperation) {
        rows += operation
    }

    override suspend fun head(subjectId: String): OutboxOperation? =
        rows.filter { it.subjectId == subjectId && it.isReplayable() }
            .minByOrNull { it.recordedAt }

    override suspend fun markInFlight(operationId: String) {
        update(operationId) { it.copy(state = OutboxOperationState.IN_FLIGHT) }
    }

    override suspend fun markApplied(operationId: String) {
        rows.removeAll { it.operationId == operationId }
    }

    override suspend fun markRetryable(
        operationId: String,
        reason: OutboxFailureReason,
        at: Long,
    ) {
        update(operationId) { it.failedWith(OutboxOperationState.FAILED, reason, at) }
    }

    override suspend fun markRejected(
        operationId: String,
        reason: OutboxFailureReason,
        at: Long,
    ) {
        update(operationId) { it.failedWith(OutboxOperationState.REJECTED, reason, at) }
    }

    override suspend fun recoverInFlight() {
        rows.replaceAll { row ->
            if (row.state == OutboxOperationState.IN_FLIGHT) {
                row.copy(state = OutboxOperationState.PENDING)
            } else {
                row
            }
        }
    }

    override suspend fun awaitingCount(subjectId: String): Int =
        rows.count {
            it.subjectId == subjectId && it.state != OutboxOperationState.REJECTED
        }

    override suspend fun rejected(subjectId: String): List<OutboxOperation> =
        stored.filter {
            it.subjectId == subjectId && it.state == OutboxOperationState.REJECTED
        }

    override suspend fun queuedFor(subjectId: String, targetId: String): List<OutboxOperation> =
        stored.filter { it.subjectId == subjectId && it.targetId == targetId }

    private fun update(operationId: String, change: (OutboxOperation) -> OutboxOperation) {
        val index = rows.indexOfFirst { it.operationId == operationId }
        if (index >= 0) {
            rows[index] = change(rows[index])
        }
    }
}

private fun OutboxOperation.isReplayable(): Boolean =
    state == OutboxOperationState.PENDING || state == OutboxOperationState.FAILED

private fun OutboxOperation.failedWith(
    state: OutboxOperationState,
    reason: OutboxFailureReason,
    at: Long,
): OutboxOperation = copy(
    state = state,
    attemptCount = attemptCount + 1,
    lastAttemptAt = at,
    lastFailure = reason,
)
