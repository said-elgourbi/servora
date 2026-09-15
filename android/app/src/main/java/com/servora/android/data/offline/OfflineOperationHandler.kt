package com.servora.android.data.offline

/**
 * What happened when one queued operation was replayed
 * (`docs/architecture/offline-first-architecture.md` §6).
 *
 * The engine maps this onto the row's state, so a handler reports the classification and never
 * decides storage itself.
 */
sealed interface ReplayOutcome {
    /** The backend accepted the operation (`2xx`): the row is removed and the state refreshed. */
    data object Applied : ReplayOutcome

    /** The attempt may succeed later — no connectivity, a `5xx`, or a renewal that did not reach the backend. */
    data class Retryable(val reason: OutboxFailureReason) : ReplayOutcome

    /**
     * The backend refused the operation for a reason that will not change.
     *
     * The row is retained and shown to the user; it is never re-applied blindly (`BR-032`).
     */
    data class Rejected(val reason: OutboxFailureReason) : ReplayOutcome

    /**
     * The session was refused and could not be renewed, so the user has to sign in again.
     *
     * The row is kept and left alone until then (§6).
     */
    data object Unauthenticated : ReplayOutcome
}

/**
 * Applies queued operations against the API.
 *
 * A handler is owned by the feature that queued the operation: it knows the routes, the payloads and
 * what the answers mean, while the engine knows only ordering, retention and retry
 * (`Project.md` §12). One handler may own several related operations — the two directions of one
 * lifecycle endpoint, for example — and applies one operation per call; replaying several is the
 * engine's job.
 */
interface OfflineOperationHandler {
    /** The stable operation codes this handler applies, matched against the queued row's type. */
    val operationTypes: Set<String>

    /**
     * Sends [operation] to the API and reports what came back.
     *
     * The operation's idempotency key travels with the request, so replaying it after a timeout
     * cannot apply it twice (§5).
     */
    suspend fun replay(operation: OutboxOperation): ReplayOutcome
}
