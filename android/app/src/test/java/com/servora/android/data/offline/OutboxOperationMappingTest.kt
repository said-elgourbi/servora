package com.servora.android.data.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The outbox row's stored form round-trips, and a value this build cannot read is never guessed at.
 *
 * A queued operation must survive storage exactly, because it is the only copy of work the user has
 * already performed (`BR-014`).
 */
class OutboxOperationMappingTest {

    @Test
    fun `round-trips a queued operation through its stored row`() {
        val operation = OutboxOperation(
            operationId = "operation-1",
            operationType = "property.archive",
            targetId = "property-1",
            subjectId = "user-1",
            payload = "{\"customerId\":\"customer-1\"}",
            capturedAt = "2026-09-14T12:00:00Z",
            recordedAt = 1_000L,
            expectedVersion = 3,
            attemptCount = 2,
            lastAttemptAt = 900L,
            lastFailure = OutboxFailureReason.NETWORK,
            state = OutboxOperationState.FAILED,
        )

        assertEquals(operation, operation.toEntity().toOperation())
    }

    @Test
    fun `round-trips an operation that has never been attempted`() {
        val operation = operation(
            attemptCount = 0,
            lastAttemptAt = null,
            lastFailure = null,
            state = OutboxOperationState.PENDING,
            expectedVersion = null,
            capturedAt = null,
        )

        assertEquals(operation, operation.toEntity().toOperation())
    }

    @Test
    fun `reads an unclassifiable stored state as a retained failure`() {
        // A row written by a build that knows a state this one does not is retained and treated as
        // waiting again rather than deleted or guessed at (`BR-014`, `BR-031`).
        assertEquals(
            OutboxOperationState.FAILED,
            "APPLIED_ELSEWHERE".toOperationState(),
        )
    }

    @Test
    fun `reads an unknown stored failure reason as unclassifiable`() {
        assertEquals(
            OutboxFailureReason.UNEXPECTED,
            "SOMETHING_NEW".toFailureReason(),
        )
    }

    @Test
    fun `reads every state and reason this build writes`() {
        OutboxOperationState.entries.forEach { state ->
            assertEquals(state, state.name.toOperationState())
        }
        OutboxFailureReason.entries.forEach { reason ->
            assertEquals(reason, reason.name.toFailureReason())
        }
    }

    /** A queued operation with the values a test does not care about filled in. */
    private fun operation(
        attemptCount: Int,
        lastAttemptAt: Long?,
        lastFailure: OutboxFailureReason?,
        state: OutboxOperationState,
        expectedVersion: Int?,
        capturedAt: String?,
    ): OutboxOperation = OutboxOperation(
        operationId = "operation-1",
        operationType = "property.restore",
        targetId = "property-1",
        subjectId = "user-1",
        payload = "{}",
        capturedAt = capturedAt,
        recordedAt = 1_000L,
        expectedVersion = expectedVersion,
        attemptCount = attemptCount,
        lastAttemptAt = lastAttemptAt,
        lastFailure = lastFailure,
        state = state,
    )
}
