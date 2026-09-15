package com.servora.android.data.offline

import com.servora.android.data.session.FakeAuthenticatedSubject
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The replay engine's rules: order, retention, retry, and what it does with an operation it cannot
 * apply.
 *
 * These are the properties `docs/architecture/offline-first-architecture.md` §4–§6 requires of a
 * replay. What each queued operation *means* is the adopting feature's own test.
 */
class OutboxReplayEngineTest {

    private val outbox = InMemoryOutboxStore()
    private val clock = AdjustableClock(Instant.parse("2026-09-14T12:00:00Z"))
    private val subject = FakeAuthenticatedSubject()

    @Test
    fun `applies a queued operation and removes it`() = runTest {
        outbox.record(operation("archive"))
        val handler = RecordingHandler("property.archive")

        val summary = engine(handler).replay()

        assertEquals(1, summary.applied)
        assertEquals(0, summary.remaining)
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `replays in the order the user acted`() = runTest {
        outbox.record(operation("first", recordedAt = 1_000L))
        outbox.record(operation("second", recordedAt = 2_000L))
        val handler = RecordingHandler("property.archive")

        engine(handler).replay()

        assertEquals(listOf("first", "second"), handler.replayed)
    }

    @Test
    fun `hands the handler the queued operation unchanged`() = runTest {
        val queued = operation("archive").copy(
            payload = "{\"customerId\":\"customer-1\"}",
            capturedAt = "2026-09-14T11:59:00Z",
            expectedVersion = 7,
        )
        outbox.record(queued)
        val handler = RecordingHandler("property.archive")

        engine(handler).replay()

        assertEquals(listOf(queued), handler.operations)
    }

    @Test
    fun `keeps work the API could not be reached for and stops the run`() = runTest {
        outbox.record(operation("first", recordedAt = 1_000L))
        outbox.record(operation("second", recordedAt = 2_000L))
        val handler = RecordingHandler("property.archive") {
            ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
        }

        val summary = engine(handler).replay()

        assertEquals(0, summary.applied)
        assertEquals(2, summary.remaining)
        // The first operation is retained and marked; the second was never attempted, because a
        // later operation must not overtake an earlier one (§4).
        assertEquals(listOf("first"), handler.replayed)
        val retained = outbox.stored.first()
        assertEquals(OutboxOperationState.FAILED, retained.state)
        assertEquals(1, retained.attemptCount)
        assertEquals(OutboxFailureReason.NETWORK, retained.lastFailure)
        assertEquals(Instant.parse("2026-09-14T12:00:00Z").toEpochMilli(), retained.lastAttemptAt)
    }

    @Test
    fun `waits out the backoff before attempting a queued operation again`() = runTest {
        outbox.record(operation("flaky"))
        val handler = RecordingHandler("property.archive") {
            ReplayOutcome.Retryable(OutboxFailureReason.SERVER)
        }
        val engine = engine(handler)

        engine.replay()
        engine.replay()

        assertEquals(1, handler.replayed.size)

        clock.advance(Duration.ofSeconds(31))
        engine.replay()

        assertEquals(2, handler.replayed.size)
    }

    @Test
    fun `keeps an operation the backend refused and continues with the next one`() = runTest {
        outbox.record(operation("refused", recordedAt = 1_000L))
        outbox.record(operation("later", recordedAt = 2_000L))
        val handler = RecordingHandler("property.archive") { queued ->
            if (queued.operationId == "later") {
                ReplayOutcome.Applied
            } else {
                ReplayOutcome.Rejected(OutboxFailureReason.STALE)
            }
        }

        val summary = engine(handler).replay()

        assertEquals(1, summary.rejected)
        assertEquals(1, summary.applied)
        // The refused operation is kept for the user; the later one was applied and removed.
        assertEquals(listOf("refused"), outbox.stored.map { it.operationId })
        assertEquals(listOf("refused"), outbox.rejected("user-1").map { it.operationId })
    }

    @Test
    fun `keeps work whose session was refused until the user signs in again`() = runTest {
        outbox.record(operation("archive"))
        val handler = RecordingHandler("property.archive") { ReplayOutcome.Unauthenticated }

        val summary = engine(handler).replay()

        assertEquals(0, summary.applied)
        assertEquals(1, summary.remaining)
        val retained = outbox.stored.single()
        assertEquals(OutboxOperationState.FAILED, retained.state)
        assertEquals(OutboxFailureReason.UNAUTHENTICATED, retained.lastFailure)
    }

    @Test
    fun `leaves an operation this build cannot apply untouched`() = runTest {
        outbox.record(operation("elsewhere").copy(operationType = "visit.note.add"))
        val handler = RecordingHandler("property.archive")

        val summary = engine(handler).replay()

        assertEquals(0, summary.applied)
        // The row is neither applied nor refused: a build that knows the operation applies it later
        // (`BR-014`).
        val retained = outbox.stored.single()
        assertEquals(OutboxOperationState.PENDING, retained.state)
        assertNull(retained.lastFailure)
        assertTrue(handler.replayed.isEmpty())
    }

    @Test
    fun `returns an interrupted replay to the queue`() = runTest {
        outbox.record(operation("interrupted"))
        outbox.markInFlight("interrupted")
        val handler = RecordingHandler("property.archive")

        val summary = engine(handler).replay()

        assertEquals(1, summary.applied)
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `reports nothing when no session is held`() = runTest {
        outbox.record(operation("archive"))
        subject.setSubject(null)
        val handler = RecordingHandler("property.archive")

        val summary = engine(handler).replay()

        assertTrue(summary.unattributed)
        assertTrue(handler.replayed.isEmpty())
        assertEquals(1, outbox.stored.size)
    }

    @Test
    fun `does not replay another subject's work`() = runTest {
        outbox.record(operation("mine"))
        outbox.record(operation("theirs", subject = "user-2"))
        val handler = RecordingHandler("property.archive")

        engine(handler).replay()

        assertEquals(listOf("mine"), handler.replayed)
        assertEquals(listOf("theirs"), outbox.stored.map { it.operationId })
    }

    private fun engine(vararg handlers: OfflineOperationHandler): OutboxReplayEngine =
        OutboxReplayEngine(
            outbox = outbox,
            handlers = handlers.toSet(),
            subject = subject,
            clock = clock,
        )

    /** A queued operation with the values a test does not care about filled in. */
    private fun operation(
        id: String,
        recordedAt: Long = 1_000L,
        subject: String = "user-1",
    ): OutboxOperation = OutboxOperation(
        operationId = id,
        operationType = "property.archive",
        targetId = "property-1",
        subjectId = subject,
        payload = "{\"customerId\":\"customer-1\"}",
        capturedAt = "2026-09-14T12:00:00Z",
        recordedAt = recordedAt,
        expectedVersion = 1,
        attemptCount = 0,
        lastAttemptAt = null,
        lastFailure = null,
        state = OutboxOperationState.PENDING,
    )
}

/** An [OfflineOperationHandler] that records what it was asked to replay. */
private class RecordingHandler(
    operationType: String,
    private val outcome: (OutboxOperation) -> ReplayOutcome = { ReplayOutcome.Applied },
) : OfflineOperationHandler {

    override val operationTypes = setOf(operationType)

    val replayed = mutableListOf<String>()
    val operations = mutableListOf<OutboxOperation>()

    override suspend fun replay(operation: OutboxOperation): ReplayOutcome {
        replayed += operation.operationId
        operations += operation
        return outcome(operation)
    }
}
