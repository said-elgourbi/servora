package com.servora.android.data.customers

import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperationState
import kotlinx.serialization.Serializable

/**
 * The stable operation codes the Property lifecycle queues
 * (`docs/architecture/offline-first-architecture.md` §4).
 *
 * Archive and restore are the operations the standard names as its first adopter, and the only ones
 * whose API accepts an idempotency key today (`BR-086`, `docs/api/customers.md` §4.2). The codes are
 * machine-readable and are never localized (`BR-041`).
 */
object PropertyOperationTypes {
    const val ARCHIVE = "property.archive"
    const val RESTORE = "property.restore"

    /** Every code the Property lifecycle owns. */
    val ALL: Set<String> = setOf(ARCHIVE, RESTORE)
}

/** One direction of the Property lifecycle (`BR-082`). */
enum class PropertyLifecycleAction {
    ARCHIVE,
    RESTORE,
    ;

    /** The operation code this action queues. */
    val operationType: String
        get() = when (this) {
            ARCHIVE -> PropertyOperationTypes.ARCHIVE
            RESTORE -> PropertyOperationTypes.RESTORE
        }

    companion object {
        /** The action a queued operation names, or `null` when the code is not a lifecycle action. */
        fun of(operationType: String): PropertyLifecycleAction? =
            entries.firstOrNull { it.operationType == operationType }
    }
}

/**
 * The arguments a queued lifecycle operation carries, stored as the row's payload (`§4`).
 *
 * It is not the whole request body: the idempotency key, the device time and the expected version
 * are properties of the queued operation itself, so the handler takes them from the row.
 */
@Serializable
internal data class PropertyLifecycleOperationPayload(
    val customerId: String,
    val note: String? = null,
)

/**
 * One queued lifecycle action on a Property, as a screen presents it (`§7`).
 *
 * This is a projection of the outbox row, not a stored value: the property is still whatever the
 * backend last reported, and this is what the user has asked for and the backend has not answered.
 */
data class QueuedPropertyOperation(
    val operationId: String,
    val action: PropertyLifecycleAction,
    val state: OutboxOperationState,
    val failure: OutboxFailureReason?,
) {
    /** Whether the backend still has to answer this action, rather than having refused it. */
    val isAwaitingSync: Boolean
        get() = state != OutboxOperationState.REJECTED
}
