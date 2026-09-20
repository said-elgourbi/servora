package com.servora.android.data.offline

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable storage for the pending mutations the user performed
 * (`docs/architecture/offline-first-architecture.md` §2, §4).
 *
 * The store only owns the rows. What an operation means, how it is applied and which failures are
 * retryable belong to the engine and to the feature that queued it, so this contract knows no
 * operation type (`Project.md` §12).
 */
interface OutboxStore {
    /** Appends one operation to the queue. */
    suspend fun record(operation: OutboxOperation)

    /**
     * The earliest operation still waiting for the backend, or `null` when nothing is queued.
     *
     * Rows the backend refused are not returned: they are terminal and never re-applied (§6).
     */
    suspend fun head(subjectId: String): OutboxOperation?

    /** Marks [operationId] as being replayed right now. */
    suspend fun markInFlight(operationId: String)

    /** Removes an operation the backend accepted (§6). */
    suspend fun markApplied(operationId: String)

    /** Keeps an operation that failed in a way that may succeed later, with its retry bookkeeping. */
    suspend fun markRetryable(
        operationId: String,
        reason: OutboxFailureReason,
        at: Long,
    )

    /** Keeps an operation the backend refused, so the user can be shown it (`BR-032`, §6). */
    suspend fun markRejected(
        operationId: String,
        reason: OutboxFailureReason,
        at: Long,
    )

    /**
     * Removes an operation the user has explicitly discarded, and only one the backend **refused**
     * (§11 item 5, `D6c`).
     *
     * A refusal is terminal — [head] never returns it — so removing it decides nothing that could
     * still be applied. The state guard is what keeps this from becoming a way to drop waiting work:
     * an operation that is still pending or retrying is not removed, because `BR-014` forbids losing
     * it. What the user's discard means for the rest of a feature's local state — the evidence slice
     * removes the photo's file and record with it — belongs to the feature, not to this contract.
     */
    suspend fun discardRefused(operationId: String)

    /**
     * Returns every replay an interruption left in flight to waiting (§6): process death at app
     * start, and a cancellation the engine survived.
     */
    suspend fun recoverInFlight()

    /** How many operations are still waiting for the backend. */
    suspend fun awaitingCount(subjectId: String): Int

    /** The operations the backend refused, oldest first. */
    suspend fun rejected(subjectId: String): List<OutboxOperation>

    /** Every queued operation that targets one entity, oldest first (§7). */
    suspend fun queuedFor(subjectId: String, targetId: String): List<OutboxOperation>
}

/**
 * Default [OutboxStore], backed by Room.
 *
 * Every write is a single statement, so the queue cannot be left half-updated; the ordering the
 * engine depends on is the `recordedAt` the caller supplied, never the database's own clock (§4).
 *
 * The implementation is module-internal: [OutboxStore] is the contract the rest of the app uses.
 */
@Singleton
internal class RoomOutboxStore @Inject constructor(
    private val dao: OutboxDao,
) : OutboxStore {

    override suspend fun record(operation: OutboxOperation) {
        dao.insert(operation.toEntity())
    }

    override suspend fun head(subjectId: String): OutboxOperation? =
        dao.head(subjectId, REPLAYABLE_STATES)?.toOperation()

    override suspend fun markInFlight(operationId: String) {
        dao.setState(operationId, OutboxOperationState.IN_FLIGHT.name)
    }

    override suspend fun markApplied(operationId: String) {
        dao.delete(operationId)
    }

    override suspend fun markRetryable(
        operationId: String,
        reason: OutboxFailureReason,
        at: Long,
    ) {
        dao.markAttemptFailed(
            operationId = operationId,
            state = OutboxOperationState.FAILED.name,
            at = at,
            reason = reason.name,
        )
    }

    override suspend fun markRejected(
        operationId: String,
        reason: OutboxFailureReason,
        at: Long,
    ) {
        dao.markAttemptFailed(
            operationId = operationId,
            state = OutboxOperationState.REJECTED.name,
            at = at,
            reason = reason.name,
        )
    }

    override suspend fun discardRefused(operationId: String) {
        dao.deleteRefused(operationId, OutboxOperationState.REJECTED.name)
    }

    override suspend fun recoverInFlight() {
        dao.recoverInFlight(
            inFlight = OutboxOperationState.IN_FLIGHT.name,
            pending = OutboxOperationState.PENDING.name,
        )
    }

    override suspend fun awaitingCount(subjectId: String): Int =
        dao.awaitingCount(subjectId, OutboxOperationState.REJECTED.name)

    override suspend fun rejected(subjectId: String): List<OutboxOperation> =
        dao.rejected(subjectId, OutboxOperationState.REJECTED.name).map { it.toOperation() }

    override suspend fun queuedFor(subjectId: String, targetId: String): List<OutboxOperation> =
        dao.forTarget(subjectId, targetId).map { it.toOperation() }

    private companion object {
        /** The states a replay may pick up: waiting, or waiting again after a retryable failure. */
        val REPLAYABLE_STATES = listOf(
            OutboxOperationState.PENDING.name,
            OutboxOperationState.FAILED.name,
        )
    }
}

/** Maps a stored row onto the domain operation. */
internal fun OutboxOperationEntity.toOperation(): OutboxOperation =
    OutboxOperation(
        operationId = operationId,
        operationType = operationType,
        targetId = targetId,
        subjectId = subjectId,
        payload = payload,
        capturedAt = capturedAt,
        recordedAt = recordedAt,
        expectedVersion = expectedVersion,
        attemptCount = attemptCount,
        lastAttemptAt = lastAttemptAt,
        lastFailure = lastFailure?.toFailureReason(),
        state = state.toOperationState(),
    )

/** Maps a domain operation onto its stored row. */
internal fun OutboxOperation.toEntity(): OutboxOperationEntity =
    OutboxOperationEntity(
        operationId = operationId,
        operationType = operationType,
        targetId = targetId,
        subjectId = subjectId,
        payload = payload,
        capturedAt = capturedAt,
        recordedAt = recordedAt,
        expectedVersion = expectedVersion,
        attemptCount = attemptCount,
        lastAttemptAt = lastAttemptAt,
        lastFailure = lastFailure?.name,
        state = state.name,
    )

/**
 * Reads a stored state.
 *
 * A value this build does not know is treated as a failed attempt rather than being guessed at: the
 * row is retained, never deleted, and replaying it stays at most once because every attempt carries
 * the same idempotency key (`BR-031`, `BR-014`).
 */
internal fun String.toOperationState(): OutboxOperationState =
    OutboxOperationState.entries.firstOrNull { it.name == this }
        ?: OutboxOperationState.FAILED

/** Reads a stored failure reason; an unknown code is reported as unclassifiable (`BR-042`). */
internal fun String.toFailureReason(): OutboxFailureReason =
    OutboxFailureReason.entries.firstOrNull { it.name == this }
        ?: OutboxFailureReason.UNEXPECTED

