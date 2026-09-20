package com.servora.android.data.jobs

import com.servora.android.data.offline.OfflineSync
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxStore
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The Visit field action's use of the outbox
 * (`docs/architecture/offline-first-architecture.md` §2, §4, §7).
 *
 * It keeps this feature's vocabulary out of the generic store: the outbox holds the action the
 * technician performed while the API could not be reached, and this class is what names it, reads it
 * back and discards it. It decides no business rule of its own — whether a transition applies is the
 * API's answer (`BR-001`, `BR-074`, `ADR-019` D5).
 *
 * It is a storage adapter rather than a contract: the store it composes is the abstraction, and a
 * test drives it with that store's in-memory implementation.
 */
@Singleton
class VisitFieldActions @Inject constructor(
    private val outbox: OutboxStore,
    private val offlineSync: OfflineSync,
    private val json: Json,
    private val clock: Clock,
) {

    /**
     * Queues a Visit status transition the API could not be reached for (`BR-014`, §4).
     *
     * The operation's identity, the device instant and the version the technician saw are the ones the
     * first attempt used, so the queued replay carries the same idempotency key and is applied at most
     * once (`BR-031`, §5). Keeping `expectedVersion` as the technician saw it is deliberate:
     * `ADR-019` D5 decided that a stale version is **refused** rather than re-read and applied "as
     * close as possible", so a queued action is a command against the state it was made from.
     *
     * Returns `false` when the row could not be recorded; the action is then reported as the network
     * failure it is rather than being presented as queued (`§5`).
     */
    suspend fun queueChangeStatus(subjectId: String, action: VisitStatusChange): Boolean =
        record(
            OutboxOperation(
                operationId = action.operationId,
                operationType = VisitFieldOperationTypes.CHANGE_STATUS,
                targetId = action.jobId,
                subjectId = subjectId,
                payload = json.encodeToString(
                    VisitFieldOperationPayload.serializer(),
                    VisitFieldOperationPayload(
                        visitId = action.visitId,
                        status = action.status.name,
                        outcomeCode = action.outcome?.name,
                        // Trimmed exactly as the direct attempt trims it, so the queued replay sends the
                        // value the first attempt would have (`BR-031`).
                        outcomeSummary = action.outcomeSummary?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                ),
                capturedAt = action.capturedAt.toString(),
                recordedAt = clock.millis(),
                expectedVersion = action.expectedVersion,
                attemptCount = 0,
                lastAttemptAt = null,
                lastFailure = null,
                state = OutboxOperationState.PENDING,
            ),
        )

    /**
     * Queues a note the API could not be reached for (`BR-013`, §4).
     *
     * A note carries no version — it is append-only and has no update path — so idempotency alone
     * covers a repeat of it (`ADR-019` D5). The text is stored as the technician wrote it, because
     * user-entered content is never replaced (`Project.md` §10).
     */
    suspend fun queueNote(subjectId: String, note: VisitNote): Boolean {
        val body = note.body.trim()
        if (body.isEmpty()) {
            // Nothing to record: a note with no text is not an update the API would accept (`BR-027`).
            return false
        }
        return record(
            OutboxOperation(
                operationId = note.operationId,
                operationType = VisitFieldOperationTypes.ADD_NOTE,
                targetId = note.jobId,
                subjectId = subjectId,
                payload = json.encodeToString(
                    VisitNoteOperationPayload.serializer(),
                    VisitNoteOperationPayload(visitId = note.visitId, body = body),
                ),
                capturedAt = note.capturedAt.toString(),
                recordedAt = clock.millis(),
                expectedVersion = null,
                attemptCount = 0,
                lastAttemptAt = null,
                lastFailure = null,
                state = OutboxOperationState.PENDING,
            ),
        )
    }

    /**
     * The status transition waiting for the backend on one Job, or `null` when none is (§7).
     *
     * The newest row decides what the screen says, so the technician's most recent action is the one
     * presented; a refused row stays visible until they deal with it (`BR-032`, §6).
     */
    suspend fun queuedChangeStatus(subjectId: String, jobId: String): QueuedVisitFieldAction? =
        queued(subjectId, jobId)
            .mapNotNull { operation ->
                if (operation.operationType != VisitFieldOperationTypes.CHANGE_STATUS) {
                    return@mapNotNull null
                }
                decodeChangeStatus(operation)
            }
            .lastOrNull()

    /** The notes waiting for the backend on one Job, oldest first (§7). */
    suspend fun queuedNotes(subjectId: String, jobId: String): List<QueuedVisitNote> =
        queued(subjectId, jobId)
            .mapNotNull { operation ->
                if (operation.operationType != VisitFieldOperationTypes.ADD_NOTE) {
                    return@mapNotNull null
                }
                decodeNote(operation)
            }

    /**
     * Removes a refused action the technician has finished with (`BR-032`, §6).
     *
     * Only a row the backend **refused** can be removed, and the store's own state guard is what
     * enforces that: an action that may still apply is never dropped, because `BR-014` forbids losing
     * it. The return value says whether a refused row was actually removed.
     */
    suspend fun discard(subjectId: String, jobId: String, operationId: String): Boolean {
        val row = queued(subjectId, jobId).firstOrNull { it.operationId == operationId }
            ?: return false
        if (row.state != OutboxOperationState.REJECTED) {
            return false
        }
        outbox.discardRefused(operationId)
        return true
    }

    /** Records one row and asks the existing sync trigger to replay it (§6). */
    private suspend fun record(operation: OutboxOperation): Boolean {
        try {
            outbox.record(operation)
        } catch (cancelled: CancellationException) {
            // A cancellation is not a failed action: the caller's own scope is ending, so it is
            // propagated rather than reported as a queueing failure (`dev.md` §1).
            throw cancelled
        } catch (failure: Exception) {
            // A row that could not be stored is pending nowhere, so the caller reports the action as
            // the failure it is instead of showing work that does not exist (`BR-014`, §5).
            return false
        }
        // A replay may need several round trips, so it is asked for and not waited on: the run belongs
        // to the existing trigger (§6).
        offlineSync.requestSync()
        return true
    }

    /** The Job's queued operations, oldest first, as the store holds them (§7). */
    private suspend fun queued(subjectId: String, jobId: String): List<OutboxOperation> =
        outbox.queuedFor(subjectId, jobId)

    /** Reads one queued transition, or `null` when this build cannot present the row (`BR-042`). */
    private fun decodeChangeStatus(operation: OutboxOperation): QueuedVisitFieldAction? {
        val payload = decode(VisitFieldOperationPayload.serializer(), operation.payload)
            ?: return null
        return payload.toQueuedVisitFieldAction(
            operationId = operation.operationId,
            state = operation.state,
            failure = operation.lastFailure,
        )
    }

    /** Reads one queued note, or `null` when this build cannot present the row (`BR-042`). */
    private fun decodeNote(operation: OutboxOperation): QueuedVisitNote? {
        val payload = decode(VisitNoteOperationPayload.serializer(), operation.payload)
            ?: return null
        return payload.toQueuedVisitNote(
            operationId = operation.operationId,
            state = operation.state,
            failure = operation.lastFailure,
        )
    }

    private fun <T> decode(serializer: KSerializer<T>, payload: String): T? =
        try {
            json.decodeFromString(serializer, payload)
        } catch (failure: SerializationException) {
            null
        }
}
