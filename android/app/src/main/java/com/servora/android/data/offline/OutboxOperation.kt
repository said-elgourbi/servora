package com.servora.android.data.offline

/**
 * One business mutation the user performed that the backend has not accepted yet.
 *
 * This is the outbox row of `docs/architecture/offline-first-architecture.md` §4. It is a
 * *provisional* record: it never becomes authoritative, and it is never discarded because a request
 * has not succeeded yet (`BR-014`). The API remains the only authority for whether the operation
 * applied (`BR-001`, `BR-031`).
 */
data class OutboxOperation(
    /** The idempotency key, created once when the user acted and reused for every retry (§5). */
    val operationId: String,
    /** The stable machine-readable operation code the engine resolves a handler for (§4). */
    val operationType: String,
    /** The entity the operation acts on. */
    val targetId: String,
    /**
     * The authenticated subject the operation was made under.
     *
     * The standard names this field `organizationId`, because a replay must not widen tenant scope.
     * This client never receives an organization id: the API resolves the tenant from the session
     * and the Android session carries only its identity, its token pair and its permission codes, so
     * the device-side partition key is the token's subject instead. The tenant boundary is still
     * enforced by the API on every replay, which is the stronger guarantee (`BR-001`, `BR-007`,
     * `BR-039`). Recorded in `docs/decisions/014-android-offline-engine.md`.
     */
    val subjectId: String,
    /** The operation's arguments, as the API expects them. */
    val payload: String,
    /** Device instant of the user's action; display only, never business time (§4). */
    val capturedAt: String?,
    /** The instant the outbox accepted the operation; it is what orders a replay (§4). */
    val recordedAt: Long,
    /** The version the client last saw, where the entity is versioned (`BR-086`). */
    val expectedVersion: Int?,
    /** How many times a replay has been attempted. */
    val attemptCount: Int,
    /** When the last attempt ran, or `null` when none has. */
    val lastAttemptAt: Long?,
    /** Why the last attempt did not apply, or `null` when none has failed. */
    val lastFailure: OutboxFailureReason?,
    val state: OutboxOperationState,
)

/** Where a queued operation stands (§4, §6). */
enum class OutboxOperationState {
    /** Recorded and waiting for its first replay attempt. */
    PENDING,

    /** A replay is in flight. Process death returns this to [PENDING] (§6). */
    IN_FLIGHT,

    /**
     * A replay failed in a way that may succeed later — no connectivity, a `5xx`, or an access token
     * the backend refused while the session itself could not be renewed. The row is retained (§6).
     */
    FAILED,

    /**
     * The backend refused the operation for a reason that will not change: the caller is not
     * authorized (`403`), the entity has moved on (`409`), the request is invalid (`400`/`422`), or
     * the entity is gone (`404`). The row is retained and shown so the user can decide, and it is
     * never re-applied blindly (`BR-032`).
     */
    REJECTED,
}

/**
 * Why a queued operation did not apply.
 *
 * The codes are stable and machine-readable (`BR-041`); the UI resolves a localized explanation from
 * one rather than parsing a message. They are the client-side classification of the API's answers in
 * §6's replay table.
 */
enum class OutboxFailureReason {
    /** `401`, and the session could not be renewed: the user signs in again before it replays. */
    UNAUTHENTICATED,

    /** `403`: the caller does not hold the permission the operation needs (`BR-007`). */
    NOT_AUTHORIZED,

    /** `409`: the entity moved past the version the client last saw (`BR-086`). */
    STALE,

    /** `400`/`422`: the API refused the operation's values. */
    INVALID,

    /** `404`: the entity the operation addresses no longer exists. */
    NOT_FOUND,

    /** `5xx`: the backend could not complete the request. */
    SERVER,

    /** The request never reached the backend. */
    NETWORK,

    /** An answer this build cannot classify. */
    UNEXPECTED,
}
