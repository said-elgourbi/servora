package com.servora.android.data.customers

import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxStore
import com.servora.android.data.offline.WorkingSetEntityTypes
import com.servora.android.data.offline.WorkingSetEntry
import com.servora.android.data.offline.WorkingSetStore
import com.servora.android.domain.model.PropertyDetail
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The Property feature's use of the two local stores
 * (`docs/architecture/offline-first-architecture.md` §2, §4, §7).
 *
 * It keeps the feature's own vocabulary out of the generic stores: the working set holds the API's
 * answer for one Property exactly as received, so an offline read runs the same mapper the online
 * read runs (`BR-041`), and the outbox holds the lifecycle action the user performed while the
 * backend could not be reached (`BR-086`).
 *
 * It is a storage adapter rather than a contract: the two stores it composes are the abstractions,
 * and a test drives it with their in-memory implementations.
 */
@Singleton
class PropertyOfflineStore @Inject constructor(
    private val workingSet: WorkingSetStore,
    private val outbox: OutboxStore,
    private val json: Json,
    private val clock: Clock,
) {

    /** Records the answer the backend just reported, replacing whatever was held before (§2). */
    suspend fun remember(subjectId: String, customerId: String, row: PropertyDetailDto) {
        workingSet.put(
            WorkingSetEntry(
                subjectId = subjectId,
                entityType = WorkingSetEntityTypes.PROPERTY_DETAIL,
                entityId = row.id,
                scopeId = customerId,
                version = row.version,
                payload = json.encodeToString(PropertyDetailDto.serializer(), row),
                reportedAt = clock.millis(),
            ),
        )
    }

    /** Drops the Property's reported answer, used when the Property ceased to exist. */
    suspend fun forget(subjectId: String, propertyId: String) {
        workingSet.evict(subjectId, WorkingSetEntityTypes.PROPERTY_DETAIL, propertyId)
    }

    /**
     * The last answer the backend reported for one Property, or `null` when none is held.
     *
     * A payload this build cannot map is treated as no answer: the read then reports the honest
     * failure it would report without a local copy (`BR-042`).
     */
    suspend fun reportedDetail(subjectId: String, propertyId: String): PropertyDetail? =
        reportedRow(subjectId, propertyId)?.toPropertyDetail()

    /**
     * Queues a lifecycle action the API could not be reached for (`BR-086`).
     *
     * The operation's identity and arguments come from the request the user's action produced, so the
     * first attempt and every replay carry the same idempotency key (§5).
     *
     * Returns `false` when the request carries no key: an operation that cannot be identified cannot
     * be replayed at most once, so it is refused rather than queued.
     */
    suspend fun queueLifecycle(
        action: PropertyLifecycleAction,
        subjectId: String,
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): Boolean {
        val operationId = request.clientOperationId ?: return false
        outbox.record(
            OutboxOperation(
                operationId = operationId,
                operationType = action.operationType,
                targetId = propertyId,
                subjectId = subjectId,
                payload = json.encodeToString(
                    PropertyLifecycleOperationPayload.serializer(),
                    PropertyLifecycleOperationPayload(
                        customerId = customerId,
                        note = request.note,
                    ),
                ),
                capturedAt = request.capturedAt,
                recordedAt = clock.millis(),
                expectedVersion = request.expectedVersion,
                attemptCount = 0,
                lastAttemptAt = null,
                lastFailure = null,
                state = OutboxOperationState.PENDING,
            ),
        )
        return true
    }

    /**
     * The arguments a queued operation carries.
     *
     * A payload this build cannot read means the operation cannot be applied by it; the handler
     * reports that rather than guessing (`BR-042`).
     */
    internal fun queuedPayload(operation: OutboxOperation): PropertyLifecycleOperationPayload? =
        try {
            json.decodeFromString(
                PropertyLifecycleOperationPayload.serializer(),
                operation.payload,
            )
        } catch (failure: SerializationException) {
            null
        }

    /**
     * The lifecycle action waiting for the backend on one Property, or `null` when none is.
     *
     * The newest row decides what the screen says, and a refused row stays visible until the user
     * deals with it (`BR-032`, §6).
     */
    suspend fun queuedAction(subjectId: String, propertyId: String): QueuedPropertyOperation? =
        outbox.queuedFor(subjectId, propertyId)
            .mapNotNull { operation ->
                val action = PropertyLifecycleAction.of(operation.operationType)
                    ?: return@mapNotNull null
                QueuedPropertyOperation(
                    operationId = operation.operationId,
                    action = action,
                    state = operation.state,
                    failure = operation.lastFailure,
                )
            }
            .lastOrNull()

    /** The last answer the backend reported, as the wire row. */
    private suspend fun reportedRow(subjectId: String, propertyId: String): PropertyDetailDto? {
        val entry = workingSet.get(
            subjectId = subjectId,
            entityType = WorkingSetEntityTypes.PROPERTY_DETAIL,
            entityId = propertyId,
        ) ?: return null
        return try {
            json.decodeFromString(PropertyDetailDto.serializer(), entry.payload)
        } catch (failure: SerializationException) {
            null
        }
    }
}
