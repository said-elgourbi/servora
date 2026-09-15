package com.servora.android.data.offline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The local store on a real device.
 *
 * Room's queries and constraints are only exercised against SQLite, which is why this cannot be a JVM
 * unit test (`qa.md` §9). It covers what the engine relies on: the queue is read oldest first, a
 * refusal is terminal, an interrupted replay is returned to waiting, and the working set holds one
 * reported answer per subject and entity.
 */
@RunWith(AndroidJUnit4::class)
class OfflineDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: OfflineDatabase
    private lateinit var outbox: RoomOutboxStore
    private lateinit var workingSet: RoomWorkingSetStore

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        outbox = RoomOutboxStore(database.outboxDao())
        workingSet = RoomWorkingSetStore(database.workingSetDao())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun readsTheQueueOldestFirst() = runTest {
        outbox.record(operation(id = "second", recordedAt = 2_000L))
        outbox.record(operation(id = "first", recordedAt = 1_000L))

        assertEquals("first", outbox.head(SUBJECT)?.operationId)
    }

    @Test
    fun returnsNothingWhenTheQueueIsEmpty() = runTest {
        assertNull(outbox.head(SUBJECT))
    }

    @Test
    fun doesNotOfferAnOperationTheBackendRefused() = runTest {
        outbox.record(operation(id = "refused", recordedAt = 1_000L))
        outbox.markRejected("refused", OutboxFailureReason.NOT_AUTHORIZED, at = 2_000L)

        assertNull(outbox.head(SUBJECT))
        assertEquals(0, outbox.awaitingCount(SUBJECT))
        assertEquals(listOf("refused"), outbox.rejected(SUBJECT).map { it.operationId })
    }

    @Test
    fun keepsAnOperationThatFailedRetryablyAndRecordsTheAttempt() = runTest {
        outbox.record(operation(id = "flaky", recordedAt = 1_000L))
        outbox.markRetryable("flaky", OutboxFailureReason.NETWORK, at = 2_000L)

        val queued = outbox.head(SUBJECT)
        assertNotNull(queued)
        assertEquals(OutboxOperationState.FAILED, queued?.state)
        assertEquals(1, queued?.attemptCount)
        assertEquals(2_000L, queued?.lastAttemptAt)
        assertEquals(OutboxFailureReason.NETWORK, queued?.lastFailure)
        assertEquals(1, outbox.awaitingCount(SUBJECT))
    }

    @Test
    fun returnsAnInterruptedReplayToWaiting() = runTest {
        outbox.record(operation(id = "interrupted", recordedAt = 1_000L))
        outbox.markInFlight("interrupted")

        // An operation being applied is not offered for a second replay at the same time.
        assertNull(outbox.head(SUBJECT))

        outbox.recoverInFlight()

        val recovered = outbox.head(SUBJECT)
        assertEquals("interrupted", recovered?.operationId)
        assertEquals(OutboxOperationState.PENDING, recovered?.state)
    }

    @Test
    fun removesAnOperationTheBackendAccepted() = runTest {
        outbox.record(operation(id = "accepted", recordedAt = 1_000L))
        outbox.markApplied("accepted")

        assertNull(outbox.head(SUBJECT))
        assertEquals(0, outbox.awaitingCount(SUBJECT))
    }

    @Test
    fun keepsSubjectsApart() = runTest {
        outbox.record(operation(id = "mine", recordedAt = 1_000L))
        outbox.record(operation(id = "theirs", recordedAt = 1_000L, subject = "user-2"))

        assertEquals(listOf("mine"), queueIds(SUBJECT))
        assertEquals(listOf("theirs"), queueIds("user-2"))
    }

    @Test
    fun reportsTheOperationsQueuedForOneEntity() = runTest {
        outbox.record(operation(id = "archive", recordedAt = 1_000L))
        outbox.record(operation(id = "restore", recordedAt = 2_000L))
        outbox.record(
            operation(id = "elsewhere", recordedAt = 3_000L).copy(targetId = "property-2"),
        )

        assertEquals(
            listOf("archive", "restore"),
            outbox.queuedFor(SUBJECT, "property-1").map { it.operationId },
        )
    }

    @Test
    fun storesOneReportedAnswerPerEntity() = runTest {
        workingSet.put(entry(payload = "{\"version\":1}"))
        workingSet.put(entry(payload = "{\"version\":2}"))

        assertEquals("{\"version\":2}", workingSet.get(SUBJECT, TYPE, "property-1")?.payload)
    }

    @Test
    fun evictsOnlyTheEntryItIsAsked() = runTest {
        workingSet.put(entry(entityId = "property-1"))
        workingSet.put(entry(entityId = "property-2"))

        workingSet.evict(SUBJECT, TYPE, "property-1")

        assertNull(workingSet.get(SUBJECT, TYPE, "property-1"))
        assertNotNull(workingSet.get(SUBJECT, TYPE, "property-2"))
    }

    @Test
    fun clearsEveryEntryOfOneSubjectOnly() = runTest {
        workingSet.put(entry(entityId = "property-1"))
        workingSet.put(entry(entityId = "property-2"))
        workingSet.put(entry(entityId = "property-1", subject = "user-2"))

        workingSet.clear(SUBJECT)

        assertNull(workingSet.get(SUBJECT, TYPE, "property-1"))
        assertNull(workingSet.get(SUBJECT, TYPE, "property-2"))
        assertNotNull(workingSet.get("user-2", TYPE, "property-1"))
    }

    /** The ids one subject's queue holds, oldest first; the queue is consumed as it is read. */
    private suspend fun queueIds(subjectId: String): List<String> {
        val ids = mutableListOf<String>()
        while (true) {
            val head = outbox.head(subjectId) ?: break
            ids += head.operationId
            outbox.markApplied(head.operationId)
        }
        return ids
    }

    private fun operation(
        id: String,
        recordedAt: Long,
        subject: String = SUBJECT,
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

    private fun entry(
        entityId: String = "property-1",
        payload: String = "{}",
        subject: String = SUBJECT,
    ): WorkingSetEntry = WorkingSetEntry(
        subjectId = subject,
        entityType = TYPE,
        entityId = entityId,
        scopeId = "customer-1",
        version = 1,
        payload = payload,
        reportedAt = 1_000L,
    )

    private companion object {
        const val SUBJECT = "user-1"
        const val TYPE = WorkingSetEntityTypes.PROPERTY_DETAIL
    }
}
