package com.servora.android.data.offline

import com.servora.android.data.session.FakeAuthenticatedSubject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a session's end does to local state
 * (`docs/architecture/offline-first-architecture.md` §10, `BR-014`).
 *
 * The working set belongs to the session that read it and is dropped. Pending work is not: it is the
 * only copy of what the user already did, and how it should be disposed of at sign-out is an open
 * product question rather than something this code decides.
 */
class SessionScopedOfflineStateTest {

    private val outbox = InMemoryOutboxStore()
    private val workingSet = InMemoryWorkingSetStore()
    private val subject = FakeAuthenticatedSubject()
    private val sync = FakeSync()
    private val state = SessionScopedOfflineState(sync, subject, workingSet)

    @Test
    fun `drops the working set of the session that is ending`() = runTest {
        workingSet.put(entry(subject = "user-1", entityId = "property-1"))
        workingSet.put(entry(subject = "user-1", entityId = "property-2"))
        workingSet.put(entry(subject = "user-2", entityId = "property-1"))

        state.onSessionEnding()

        assertTrue(workingSet.stored.none { it.subjectId == "user-1" })
        assertEquals(listOf("user-2"), workingSet.stored.map { it.subjectId })
    }

    @Test
    fun `keeps pending work`() = runTest {
        outbox.record(operation(subject = "user-1"))

        state.onSessionEnding()

        assertEquals(1, outbox.stored.size)
    }

    @Test
    fun `does nothing when no session is held`() = runTest {
        subject.setSubject(null)
        workingSet.put(entry(subject = "user-1", entityId = "property-1"))

        state.onSessionEnding()

        assertEquals(1, workingSet.stored.size)
    }

    @Test
    fun `asks for a replay when a session becomes available`() {
        state.onSessionAvailable()

        assertEquals(1, sync.requests)
    }

    private fun entry(subject: String, entityId: String): WorkingSetEntry = WorkingSetEntry(
        subjectId = subject,
        entityType = WorkingSetEntityTypes.PROPERTY_DETAIL,
        entityId = entityId,
        scopeId = "customer-1",
        version = 1,
        payload = "{}",
        reportedAt = 1_000L,
    )

    private fun operation(subject: String): OutboxOperation = OutboxOperation(
        operationId = "archive",
        operationType = "property.archive",
        targetId = "property-1",
        subjectId = subject,
        payload = "{}",
        capturedAt = null,
        recordedAt = 1_000L,
        expectedVersion = 1,
        attemptCount = 0,
        lastAttemptAt = null,
        lastFailure = null,
        state = OutboxOperationState.PENDING,
    )
}

/** An [OfflineSync] that counts how often a replay was asked for. */
private class FakeSync : OfflineSync {

    var requests = 0
        private set

    override fun requestSync() {
        requests += 1
    }
}
