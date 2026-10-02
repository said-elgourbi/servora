package com.servora.android.data.schedule

import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxStore
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The ad-hoc work report feature's use of the outbox
 * (`docs/architecture/offline-first-architecture.md` §4).
 *
 * It keeps the feature's own vocabulary out of the generic store: the outbox holds the submission the
 * technician performed while the backend could not be reached, carrying the same idempotency key the
 * online attempt used, so a replay is applied at most once (`BR-031`).
 */
@Singleton
class AdHocWorkReportOfflineStore @Inject constructor(
    private val outbox: OutboxStore,
    private val json: Json,
    private val clock: Clock,
) {

    /**
     * Queues a submission the API could not be reached for (`§4`).
     *
     * The operation's id is the request's idempotency key, so the first attempt and every replay carry
     * the same key (§5). Returns `false` when the request carries no key: an operation that cannot be
     * identified cannot be replayed at most once, so it is refused rather than queued.
     */
    suspend fun queueSubmit(
        subjectId: String,
        request: SubmitAdHocWorkReportRequestDto,
        capturedAt: String?,
    ): Boolean {
        val operationId = request.clientOperationId ?: return false
        outbox.record(
            OutboxOperation(
                operationId = operationId,
                operationType = AdHocWorkReportOperationTypes.SUBMIT,
                // A report does not exist yet, so the operation addresses no existing entity; its own
                // id is the stable key the row is partitioned by.
                targetId = operationId,
                subjectId = subjectId,
                payload = json.encodeToString(
                    AdHocWorkReportOperationPayload.serializer(),
                    AdHocWorkReportOperationPayload(
                        customerId = request.customerId,
                        propertyId = request.propertyId,
                        knownJobId = request.knownJobId,
                        workStartedAt = request.workStartedAt,
                        workEndedAt = request.workEndedAt,
                        outcomeCode = request.outcomeCode,
                        summary = request.summary,
                        notes = request.notes,
                        reportedCustomerName = request.reportedCustomerName,
                        reportedCustomerPhone = request.reportedCustomerPhone,
                        reportedCustomerAddress = request.reportedCustomerAddress,
                    ),
                ),
                capturedAt = capturedAt,
                recordedAt = clock.millis(),
                expectedVersion = null,
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
    internal fun queuedPayload(operation: OutboxOperation): AdHocWorkReportOperationPayload? =
        try {
            json.decodeFromString(
                AdHocWorkReportOperationPayload.serializer(),
                operation.payload,
            )
        } catch (failure: SerializationException) {
            null
        }
}
