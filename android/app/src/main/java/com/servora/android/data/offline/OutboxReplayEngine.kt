package com.servora.android.data.offline

import com.servora.android.data.session.AuthenticatedSubject
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one replay run did, so a caller can report it without inspecting the store. */
data class ReplaySummary(
    /** Operations the backend accepted in this run. */
    val applied: Int = 0,
    /** Operations the backend refused in this run. */
    val rejected: Int = 0,
    /** Operations still waiting when the run ended. */
    val remaining: Int = 0,
    /** Whether the run could not attribute the queue to a signed-in subject. */
    val unattributed: Boolean = false,
) {
    /** Whether the queue is clean for the subject this run replayed for. */
    val isClean: Boolean
        get() = applied == 0 && rejected == 0 && remaining == 0 && !unattributed
}

/**
 * Replays the operations the user performed while the backend was unreachable
 * (`docs/architecture/offline-first-architecture.md` §4–§6).
 *
 * The engine owns ordering, retention and retry, and nothing else: which operations exist is the
 * feature's business, and how each one is applied is its handler's (`Project.md` §12). Three rules
 * it is deliberately strict about:
 *
 * - Operations are replayed in `recordedAt` order, one at a time, and a run stops at the first
 *   operation that may still succeed. A later operation therefore never overtakes an earlier one
 *   (§4), which is what keeps a create and a later change to the same entity in the order the user
 *   made them.
 * - Nothing is deleted because a request failed. A row is removed only when the backend accepted it,
 *   and a refusal is kept so the user can be shown it (`BR-014`, `BR-032`).
 * - An interruption this process survives never strands a row in flight: a handler that throws and a
 *   run that is cancelled both leave the row waiting for the next trigger rather than in flight until
 *   the app restarts (§4–§6, `BR-014`).
 */
@Singleton
class OutboxReplayEngine @Inject constructor(
    private val outbox: OutboxStore,
    private val handlers: Set<@JvmSuppressWildcards OfflineOperationHandler>,
    private val subject: AuthenticatedSubject,
    private val clock: Clock,
) {

    private val run = Mutex()
    private var recovered = false

    private val _applied = MutableSharedFlow<Unit>(extraBufferCapacity = APPLIED_EVENT_BUFFER)

    /**
     * Emits after a run that the backend accepted, so a screen showing that work re-reads it (§7).
     *
     * It carries no payload: a screen re-reads the record it is open on from the API, which is what
     * §10 requires a successful read to do anyway.
     */
    val applied: SharedFlow<Unit> = _applied.asSharedFlow()

    /**
     * Replays the signed-in subject's queue until it is clean, exhausted or blocked.
     *
     * Concurrent callers are serialized: a replay triggered by connectivity and another triggered by
     * the user both run, one after the other, rather than applying the same row twice.
     */
    suspend fun replay(): ReplaySummary = run.withLock {
        recoverInterruptedOnce()
        val subjectId = subject.current()
            ?: return@withLock ReplaySummary(unattributed = true)

        var applied = 0
        var rejected = 0
        try {
            while (true) {
                val operation = outbox.head(subjectId) ?: break
                if (!isDue(operation)) {
                    // The earliest operation is still backing off. Later operations wait for it
                    // rather than being applied out of order (§4).
                    break
                }
                val handler = handlerFor(operation) ?: break
                outbox.markInFlight(operation.operationId)
                val outcome = try {
                    handler.replay(operation)
                } catch (cancellation: CancellationException) {
                    // Cancellation is not an answer, so it is left to the run's own handler below
                    // rather than being recorded as a failed attempt (§6).
                    throw cancellation
                } catch (unexpected: Exception) {
                    // A handler that threw answered nothing: the row is kept for retry instead of
                    // being left in flight, and the run stops so a later operation cannot overtake
                    // this one (§4, §6).
                    outbox.markRetryable(
                        operationId = operation.operationId,
                        reason = OutboxFailureReason.UNEXPECTED,
                        at = clock.millis(),
                    )
                    break
                }
                when (outcome) {
                    ReplayOutcome.Applied -> {
                        outbox.markApplied(operation.operationId)
                        applied++
                    }

                    is ReplayOutcome.Rejected -> {
                        outbox.markRejected(
                            operationId = operation.operationId,
                            reason = outcome.reason,
                            at = clock.millis(),
                        )
                        rejected++
                    }

                    is ReplayOutcome.Retryable -> {
                        outbox.markRetryable(
                            operationId = operation.operationId,
                            reason = outcome.reason,
                            at = clock.millis(),
                        )
                        break
                    }

                    ReplayOutcome.Unauthenticated -> {
                        outbox.markRetryable(
                            operationId = operation.operationId,
                            reason = OutboxFailureReason.UNAUTHENTICATED,
                            at = clock.millis(),
                        )
                        break
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            // This process survived the interruption, so the row that was being applied goes back to
            // waiting here rather than staying in flight until the app restarts (§6, `BR-014`). The
            // write has to outlive the cancellation that caused it; runs are serialized by `run`, so
            // the only operation in flight is the one this run was applying.
            withContext(NonCancellable) { outbox.recoverInFlight() }
            throw cancellation
        }

        if (applied > 0) {
            _applied.tryEmit(Unit)
        }

        ReplaySummary(
            applied = applied,
            rejected = rejected,
            remaining = outbox.awaitingCount(subjectId),
        )
    }

    /**
     * Whether [operation] is past its backoff.
     *
     * The device clock is used only for pacing retries, never as business time (§4).
     */
    private fun isDue(operation: OutboxOperation): Boolean {
        val lastAttemptAt = operation.lastAttemptAt ?: return true
        return clock.millis() - lastAttemptAt >= ReplayBackoff.delayMillis(operation.attemptCount)
    }

    /**
     * The handler for a queued operation, or `null` when this build has none.
     *
     * An unanswered `null` leaves the row exactly as it is and ends the run: an operation this build
     * cannot apply must be left for a build that can, not refused on its behalf (`BR-014`).
     */
    private fun handlerFor(operation: OutboxOperation): OfflineOperationHandler? =
        handlers.firstOrNull { operation.operationType in it.operationTypes }

    /**
     * Returns a replay that process death interrupted to waiting, once per process.
     *
     * This is the engine's own startup duty rather than a caller's, so no trigger path can skip it
     * and leave an operation stranded in flight (§6).
     */
    private suspend fun recoverInterruptedOnce() {
        if (recovered) {
            return
        }
        recovered = true
        outbox.recoverInFlight()
    }

    private companion object {
        /** A refresh signal, not a record: a slow collector may miss one and still be correct. */
        const val APPLIED_EVENT_BUFFER = 8
    }
}
