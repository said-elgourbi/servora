package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.domain.model.VisitOutcome
import com.servora.android.domain.model.VisitStatus
import com.servora.android.domain.model.visitOutcomeOrNull
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * One Visit status transition as the technician asked for it (`BR-074`, `BR-075`, `BR-077`).
 *
 * It carries the identity and the provenance of the action rather than only its destination, because
 * a transition that the API could not be reached for is **queued** and replayed later
 * (`BR-014`, `BR-031`): [operationId] is the idempotency key, [capturedAt] is the device instant the
 * technician acted, and [expectedVersion] is the Visit version the screen saw, which `ADR-019` D5
 * decided is the version a queued command keeps — a stale one is refused rather than re-based.
 */
data class VisitStatusChange(
    val jobId: String,
    val visitId: String,
    val status: VisitStatus,
    /** The outcome recorded with a completion (`BR-077`), absent for any other destination. */
    val outcome: VisitOutcome? = null,
    /** The summary `BR-077` requires with the outcome. */
    val outcomeSummary: String? = null,
    /** True only when the technician accepted the `BR-070` conflicts the API reported. */
    val confirmConflicts: Boolean = false,
    val operationId: String,
    val capturedAt: Instant,
    val expectedVersion: Int,
)

/** One text update as the technician asked for it (`BR-013`, `BR-027`). */
data class VisitNote(
    val jobId: String,
    val visitId: String,
    val body: String,
    /** The idempotency key; a note carries no version, so this alone covers a repeat (`D5`). */
    val operationId: String,
    val capturedAt: Instant,
)


/**
 * The field operations this feature queues
 * (`docs/architecture/offline-first-architecture.md` §4).
 *
 * One code for the Visit's status transition, because the API has one route for it: a completion
 * records the outcome `BR-077` requires in the same request, so separating the transition from the
 * outcome would let the two disagree. One code for the note, on its own route — a note is append-only
 * and carries no version, so idempotency alone covers a repeat of it (`ADR-019` D5).
 */
object VisitFieldOperationTypes {
    /** The Visit's field status transition, a completion's outcome included (`BR-074`, `BR-077`). */
    const val CHANGE_STATUS = "visit.status.change"

    /** One text update added to a Visit's activity (`BR-013`, `BR-027`). */
    const val ADD_NOTE = "visit.note.add"

    /** Every code this feature queues, so the queue is filtered by one definition (`BR-041`). */
    val ALL: Set<String> = setOf(CHANGE_STATUS, ADD_NOTE)
}

/**
 * The arguments a queued status transition carries, stored as the row's payload (`§4`).
 *
 * It is not the whole request body: the idempotency key, the device time and the expected version are
 * properties of the queued operation itself, so the handler takes them from the row rather than
 * duplicating them here. [visitId] is the entity within the target, because the row's `targetId` is
 * the Job the action belongs to — the same partition the Job's reads and its evidence use.
 */
@Serializable
internal data class VisitFieldOperationPayload(
    val visitId: String,
    val status: String,
    /** The outcome recorded with a completion (`BR-077`), absent for any other destination. */
    val outcomeCode: String? = null,
    /** The summary `BR-077` requires with the outcome. */
    val outcomeSummary: String? = null,
)

/**
 * The arguments a queued note carries (`BR-027`).
 *
 * The note's text is what the technician wrote, kept as they wrote it: user-entered content is never
 * translated or replaced (`Project.md` §10).
 */
@Serializable
internal data class VisitNoteOperationPayload(
    val visitId: String,
    val body: String,
)

/**
 * One field action on a Visit that the backend has not answered yet (`BR-014`, §7).
 *
 * It is derived from the outbox row itself rather than from a second local copy of it, so the screen
 * reports the real queue (`BR-041`). Nothing here is applied: it is the technician's own provisional
 * action, never something the backend accepted (`BR-001`).
 *
 * A row whose payload or codes this build cannot read is dropped rather than presented as an action
 * it is not (`BR-042`).
 */
data class QueuedVisitFieldAction(
    /** The idempotency key, which is also how the action is discarded. */
    val operationId: String,
    /** The Visit the action moves. */
    val visitId: String,
    /** The destination the technician chose, from the destinations the API reported (`BR-074`). */
    val status: VisitStatus,
    /** The outcome recorded with a completion (`BR-077`), or `null` for any other destination. */
    val outcome: VisitOutcome?,
    /** The summary the technician wrote with a completion, as they wrote it (`BR-028`). */
    val outcomeSummary: String?,
    /** How far the replay has got (`§6`, §7). */
    val state: OutboxOperationState,
    /** Why the last replay attempt did not apply, or `null` when none has failed (`§6`). */
    val failure: OutboxFailureReason?,
) {
    /** Whether the backend still has to answer this action, rather than having refused it. */
    val isAwaitingSync: Boolean get() = state != OutboxOperationState.REJECTED

    /** Whether this action is a completion carrying the outcome `BR-077` requires. */
    val isCompletion: Boolean get() = status == VisitStatus.COMPLETED
}

/**
 * Reads the destination a queued payload names, or `null` when this build cannot name it.
 *
 * A status Servora does not have cannot be presented, so the row is dropped rather than shown as
 * something it is not (`BR-041`, `BR-042`).
 */
internal fun VisitFieldOperationPayload.toQueuedVisitFieldAction(
    operationId: String,
    state: OutboxOperationState,
    failure: OutboxFailureReason?,
): QueuedVisitFieldAction? {
    val destination = VisitStatus.entries.firstOrNull { it.name == status } ?: return null
    return QueuedVisitFieldAction(
        operationId = operationId,
        visitId = visitId,
        status = destination,
        // A completion always carries the outcome the API accepted at queue time; a code this build
        // cannot name is reported as no outcome rather than invented (`BR-078`, `BR-042`).
        outcome = visitOutcomeOrNull(outcomeCode),
        outcomeSummary = outcomeSummary,
        state = state,
        failure = failure,
    )
}

/**
 * One text update the technician wrote that the backend has not accepted yet (`BR-014`, §7).
 *
 * It is the note's own shape rather than the status action's: a note has no destination, and showing
 * it as an Activity entry is what makes it readable while it waits. Like the queued status action it
 * is **provisional** and is drawn as the device's own pending entry, never as something the backend
 * recorded (`BR-001`, `BR-080`).
 */
data class QueuedVisitNote(
    /** The idempotency key, which is also how the note is discarded. */
    val operationId: String,
    /** The Visit the note belongs to. */
    val visitId: String,
    /** The text the technician wrote, as they wrote it (`BR-028`). */
    val body: String,
    /** How far the replay has got (`§6`, §7). */
    val state: OutboxOperationState,
    /** Why the last replay attempt did not apply, or `null` when none has failed (`§6`). */
    val failure: OutboxFailureReason?,
) {
    /** Whether the backend still has to answer this note, rather than having refused it. */
    val isAwaitingSync: Boolean get() = state != OutboxOperationState.REJECTED
}

/** Reads the note a queued payload carries, or `null` when the row is not a note this build made. */
internal fun VisitNoteOperationPayload.toQueuedVisitNote(
    operationId: String,
    state: OutboxOperationState,
    failure: OutboxFailureReason?,
): QueuedVisitNote? {
    val text = body.trim()
    if (text.isEmpty()) {
        return null
    }
    return QueuedVisitNote(
        operationId = operationId,
        visitId = visitId,
        body = text,
        state = state,
        failure = failure,
    )
}

