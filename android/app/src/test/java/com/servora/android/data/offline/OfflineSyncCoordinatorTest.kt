package com.servora.android.data.offline

import com.servora.android.data.session.FakeAuthenticatedSubject
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator's job: replay when connectivity comes back, and never apply the same work twice
 * because two triggers fired at once
 * (`docs/architecture/offline-first-architecture.md` §6).
 *
 * The coordinator launches on an application-lifetime scope, so the test supplies one built on its
 * own test dispatcher and advances it explicitly.
 */
class OfflineSyncCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()
    private val outbox = InMemoryOutboxStore()
    private val handler = AppliedHandler()
    private val clock = AdjustableClock(Instant.parse("2026-09-14T12:00:00Z"))
    private val engine = OutboxReplayEngine(
        outbox = outbox,
        handlers = setOf(handler),
        subject = FakeAuthenticatedSubject(),
        clock = clock,
    )

    @Test
    fun `replays queued work when a network comes back`() = runTest(dispatcher) {
        outbox.record(operation("archive"))
        val connectivity = FakeConnectivityObserver(online = false)
        OfflineSyncCoordinator(engine, connectivity, coordinatorScope())
        advanceUntilIdle()
        assertEquals(1, outbox.stored.size)

        connectivity.goOnline()
        advanceUntilIdle()

        assertTrue(outbox.stored.isEmpty())
        assertEquals(1, handler.replayed)
    }

    @Test
    fun `replays queued work when it is asked to`() = runTest(dispatcher) {
        outbox.record(operation("archive"))
        val coordinator = OfflineSyncCoordinator(
            engine,
            FakeConnectivityObserver(online = false),
            coordinatorScope(),
        )

        coordinator.requestSync()
        advanceUntilIdle()

        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `does not apply the same operation twice when two triggers fire together`() = runTest(dispatcher) {
        outbox.record(operation("archive"))
        val coordinator = OfflineSyncCoordinator(
            engine,
            FakeConnectivityObserver(online = false),
            coordinatorScope(),
        )

        coordinator.requestSync()
        coordinator.requestSync()
        advanceUntilIdle()

        // The runs are serialized, and the second finds a clean queue rather than the same row.
        assertEquals(1, handler.replayed)
    }

    /** The application-lifetime scope the coordinator runs on, in the test's own scheduler. */
    private fun coordinatorScope(): CoroutineScope = CoroutineScope(dispatcher + Job())

    private fun operation(id: String): OutboxOperation = OutboxOperation(
        operationId = id,
        operationType = PROPERTY_ARCHIVE,
        targetId = "property-1",
        subjectId = "user-1",
        payload = "{\"customerId\":\"customer-1\"}",
        capturedAt = "2026-09-14T12:00:00Z",
        recordedAt = 1_000L,
        expectedVersion = 1,
        attemptCount = 0,
        lastAttemptAt = null,
        lastFailure = null,
        state = OutboxOperationState.PENDING,
    )

    private companion object {
        const val PROPERTY_ARCHIVE = "property.archive"
    }
}

/** A handler that accepts everything it is asked to replay and counts the calls. */
private class AppliedHandler : OfflineOperationHandler {

    override val operationTypes = setOf("property.archive")

    var replayed = 0
        private set

    override suspend fun replay(operation: OutboxOperation): ReplayOutcome {
        replayed += 1
        return ReplayOutcome.Applied
    }
}
