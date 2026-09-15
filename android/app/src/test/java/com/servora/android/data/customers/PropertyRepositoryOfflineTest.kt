package com.servora.android.data.customers

import com.servora.android.data.offline.InMemoryOutboxStore
import com.servora.android.data.offline.InMemoryWorkingSetStore
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxReplayEngine
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionRenewal
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the Property feature meets the offline standard
 * (`docs/architecture/offline-first-architecture.md` §2, §4, §5, §7).
 *
 * What the API does is covered by the API's own tests. These cover what the client does when the API
 * cannot be reached — and what it must not do, which is queue an operation the API already answered,
 * or present a queued action as applied (`BR-086`).
 */
class PropertyRepositoryOfflineTest {

    private val api = FakePropertiesApi()
    private val outbox = InMemoryOutboxStore()
    private val subject = FakeAuthenticatedSubject()
    private val offline = PropertyOfflineStore(
        workingSet = InMemoryWorkingSetStore(),
        outbox = outbox,
        json = Json { ignoreUnknownKeys = true },
        clock = CLOCK,
    )
    private val repository = repositoryWith(TestSessionAuthenticator())

    @Test
    fun `queues an archive the API could not be reached for`() = runTest {
        api.archiveAnswer = { throw IOException() }

        val result = repository.archiveProperty("c1", "p1", lifecycleRequest())

        assertTrue(result is PropertyResult.Queued)
        val queued = outbox.stored.single()
        assertEquals("operation-1", queued.operationId)
        assertEquals(PropertyOperationTypes.ARCHIVE, queued.operationType)
        assertEquals("p1", queued.targetId)
        assertEquals("user-1", queued.subjectId)
        assertEquals("2026-09-14T12:00:00Z", queued.capturedAt)
        assertEquals(3, queued.expectedVersion)
    }

    @Test
    fun `queues a restore the API could not be reached for`() = runTest {
        api.restoreAnswer = { throw IOException() }

        val result = repository.restoreProperty("c1", "p1", lifecycleRequest())

        assertTrue(result is PropertyResult.Queued)
        assertEquals(PropertyOperationTypes.RESTORE, outbox.stored.single().operationType)
    }

    @Test
    fun `reports an archive the API refused and queues nothing`() = runTest {
        api.archiveAnswer = { throw propertyHttpFailure(409) }

        val result = repository.archiveProperty("c1", "p1", lifecycleRequest())

        assertEquals(
            CustomersFailureReason.VERSION_CONFLICT,
            (result as PropertyResult.Failure).reason,
        )
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `does not queue work whose session was refused`() = runTest {
        val repository = repositoryWith(
            TestSessionAuthenticator(renewal = SessionRenewal.Rejected),
        )
        api.archiveAnswer = { throw propertyHttpFailure(401) }

        val result = repository.archiveProperty("c1", "p1", lifecycleRequest())

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            (result as PropertyResult.Failure).reason,
        )
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `does not queue an archive that carries no idempotency key`() = runTest {
        api.archiveAnswer = { throw IOException() }
        val request = PropertyLifecycleRequest(note = null, clientOperationId = null)

        val result = repository.archiveProperty("c1", "p1", request)

        assertEquals(CustomersFailureReason.NETWORK, (result as PropertyResult.Failure).reason)
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `serves the last reported Property when the read cannot reach the API`() = runTest {
        api.detailAnswer = { _, _ -> propertyDto(version = 3) }
        repository.loadProperty("c1", "p1")
        api.detailAnswer = { _, _ -> throw IOException() }

        val result = repository.loadProperty("c1", "p1")

        val success = result as PropertyResult.Success
        assertEquals(ReadSource.WORKING_SET, success.source)
        assertEquals(3, success.property.version)
    }

    @Test
    fun `reports the network failure when nothing was reported yet`() = runTest {
        api.detailAnswer = { _, _ -> throw IOException() }

        val result = repository.loadProperty("c1", "p1")

        assertEquals(CustomersFailureReason.NETWORK, (result as PropertyResult.Failure).reason)
    }

    @Test
    fun `does not serve the local copy when the API refuses the read`() = runTest {
        api.detailAnswer = { _, _ -> propertyDto() }
        repository.loadProperty("c1", "p1")
        api.detailAnswer = { _, _ -> throw propertyHttpFailure(403) }

        val result = repository.loadProperty("c1", "p1")

        // A refusal is the backend's answer: a copy held on the device must never mask it
        // (`BR-007`, `BR-042`).
        assertEquals(CustomersFailureReason.FORBIDDEN, (result as PropertyResult.Failure).reason)
    }

    @Test
    fun `forgets the Property once the backend confirms its deletion`() = runTest {
        api.detailAnswer = { _, _ -> propertyDto() }
        repository.loadProperty("c1", "p1")

        repository.deleteProperty("c1", "p1")

        assertNull(offline.reportedDetail("user-1", "p1"))
    }

    @Test
    fun `reports the queued action of a Property`() = runTest {
        api.archiveAnswer = { throw IOException() }
        repository.archiveProperty("c1", "p1", lifecycleRequest())

        val queued = repository.queuedOperation("p1")

        assertEquals(PropertyLifecycleAction.ARCHIVE, queued?.action)
        assertEquals(true, queued?.isAwaitingSync)
    }

    @Test
    fun `reports a refused queued action as no longer awaiting`() = runTest {
        outbox.record(
            queuedArchiveRow(
                state = OutboxOperationState.REJECTED,
                failure = OutboxFailureReason.STALE,
            ),
        )

        val queued = repository.queuedOperation("p1")

        assertEquals(false, queued?.isAwaitingSync)
        assertEquals(OutboxFailureReason.STALE, queued?.failure)
    }

    /** The repository under test, with a chosen session authenticator. */
    private fun repositoryWith(authenticator: TestSessionAuthenticator) =
        DefaultPropertyRepository(
            api = api,
            sessionAuthenticator = authenticator,
            offline = offline,
            subject = subject,
            engine = OutboxReplayEngine(outbox, emptySet(), subject, CLOCK),
        )

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)
    }
}

/** The lifecycle request a screen produces (`PropertyDetailViewModel`). */
internal fun lifecycleRequest(
    operationId: String = "operation-1",
    expectedVersion: Int? = 3,
): PropertyLifecycleRequest = PropertyLifecycleRequest(
    note = null,
    clientOperationId = operationId,
    capturedAt = "2026-09-14T12:00:00Z",
    expectedVersion = expectedVersion,
)

/** A queued archive row a test builds. */
internal fun queuedArchiveRow(
    operationId: String = "operation-1",
    subjectId: String = "user-1",
    state: OutboxOperationState = OutboxOperationState.PENDING,
    failure: OutboxFailureReason? = null,
): OutboxOperation = OutboxOperation(
    operationId = operationId,
    operationType = PropertyOperationTypes.ARCHIVE,
    targetId = "p1",
    subjectId = subjectId,
    payload = "{\"customerId\":\"c1\"}",
    capturedAt = "2026-09-14T12:00:00Z",
    recordedAt = 1_000L,
    expectedVersion = 3,
    attemptCount = 0,
    lastAttemptAt = null,
    lastFailure = failure,
    state = state,
)
