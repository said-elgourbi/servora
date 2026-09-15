package com.servora.android.data.customers

import com.servora.android.data.offline.InMemoryOutboxStore
import com.servora.android.data.offline.InMemoryWorkingSetStore
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.PropertyStatus
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replaying a queued archive or restore (`BR-086`, `offline-first-architecture.md` §5, §6).
 *
 * The two properties that make a late replay safe are pinned here: the operation keeps the
 * idempotency key it was queued with, and it names the version the API reports *now* rather than the
 * one the screen saw hours ago.
 */
class PropertyLifecycleHandlerTest {

    private val api = FakePropertiesApi()
    private val workingSet = InMemoryWorkingSetStore()
    private val outbox = InMemoryOutboxStore()
    private val subject = FakeAuthenticatedSubject()
    private val offline = PropertyOfflineStore(workingSet, outbox, JSON, CLOCK)

    @Test
    fun `applies an archive with the queued key and the version it just read`() = runTest {
        api.detailAnswer = { _, _ -> propertyDto(version = 7) }
        api.archiveAnswer = { propertyDto(status = "ARCHIVED", version = 8) }
        val handler = handler()

        val outcome = handler.replay(queuedArchiveRow())

        assertEquals(ReplayOutcome.Applied, outcome)
        assertEquals("Bearer access-1", api.lastAuthorization)
        assertEquals(1, api.detailCalls)
        assertEquals(1, api.archiveCalls)
        assertEquals("operation-1", api.lastLifecycleRequest?.clientOperationId)
        assertEquals("2026-09-14T12:00:00Z", api.lastLifecycleRequest?.capturedAt)
        assertEquals(7, api.lastLifecycleRequest?.expectedVersion)
    }

    @Test
    fun `records the answer the backend reported`() = runTest {
        api.detailAnswer = { _, _ -> propertyDto(version = 7) }
        api.archiveAnswer = { propertyDto(status = "ARCHIVED", version = 8) }

        handler().replay(queuedArchiveRow())

        val reported = offline.reportedDetail("user-1", "p1")
        assertEquals(8, reported?.version)
        assertEquals(PropertyStatus.ARCHIVED, reported?.status)
    }

    @Test
    fun `applies a restore through the restore route`() = runTest {
        api.detailAnswer = { _, _ -> propertyDto(status = "ARCHIVED", version = 8) }
        api.restoreAnswer = { propertyDto(status = "ACTIVE", version = 9) }

        val outcome = handler().replay(
            queuedArchiveRow().copy(operationType = PropertyOperationTypes.RESTORE),
        )

        assertEquals(ReplayOutcome.Applied, outcome)
        assertEquals(1, api.restoreCalls)
        assertEquals(0, api.archiveCalls)
    }

    @Test
    fun `refuses when the Property has moved on`() = runTest {
        api.detailAnswer = { _, _ -> propertyDto(version = 7) }
        api.archiveAnswer = { throw propertyHttpFailure(409) }

        val outcome = handler().replay(queuedArchiveRow())

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.STALE), outcome)
    }

    @Test
    fun `refuses when the Property no longer exists`() = runTest {
        api.detailAnswer = { _, _ -> throw propertyHttpFailure(404) }

        val outcome = handler().replay(queuedArchiveRow())

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.NOT_FOUND), outcome)
        assertEquals(0, api.archiveCalls)
    }

    @Test
    fun `retries when the API could not be reached for the mutation`() = runTest {
        api.detailAnswer = { _, _ -> propertyDto() }
        api.archiveAnswer = { throw IOException() }

        val outcome = handler().replay(queuedArchiveRow())

        assertEquals(ReplayOutcome.Retryable(OutboxFailureReason.NETWORK), outcome)
    }

    @Test
    fun `retries when the API could not be reached for the read`() = runTest {
        api.detailAnswer = { _, _ -> throw IOException() }

        val outcome = handler().replay(queuedArchiveRow())

        assertEquals(ReplayOutcome.Retryable(OutboxFailureReason.NETWORK), outcome)
        assertEquals(0, api.archiveCalls)
    }

    @Test
    fun `keeps the operation when the session was refused`() = runTest {
        val authenticator = TestSessionAuthenticator(renewal = SessionRenewal.Rejected)
        api.detailAnswer = { _, _ -> throw propertyHttpFailure(401) }

        val outcome = handler(authenticator).replay(queuedArchiveRow())

        assertEquals(ReplayOutcome.Unauthenticated, outcome)
        assertEquals(1, authenticator.renewals)
    }

    @Test
    fun `refuses an operation whose arguments this build cannot read`() = runTest {
        val outcome = handler().replay(queuedArchiveRow().copy(payload = "not json"))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED), outcome)
        assertEquals(0, api.detailCalls)
    }

    @Test
    fun `refuses an operation kind the Property lifecycle does not own`() = runTest {
        val outcome = handler().replay(queuedArchiveRow().copy(operationType = "visit.note.add"))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED), outcome)
        assertEquals(0, api.detailCalls)
    }

    /** The handler under test, with a session the test decides. */
    private fun handler(
        authenticator: TestSessionAuthenticator = TestSessionAuthenticator(),
    ) = PropertyLifecycleHandler(
        api = api,
        sessionAuthenticator = authenticator,
        offline = offline,
        subject = subject,
    )

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
