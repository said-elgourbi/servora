package com.servora.android.data.schedule

import com.servora.android.data.offline.InMemoryOutboxStore
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The ad-hoc work report submission meets the offline standard
 * (`docs/architecture/offline-first-architecture.md` §4, §5, §6).
 *
 * The one property that makes a late replay safe is pinned here: a submission keeps the idempotency
 * key it was created with, so a report the API could not be reached for is queued with that key and a
 * replay carries the same key rather than creating a second report (`BR-031`, `BR-014`).
 */
class AdHocWorkReportsRepositoryTest {

    private val api = FakeAdHocWorkReportsApi()
    private val outbox = InMemoryOutboxStore()
    private val subject = FakeAuthenticatedSubject()
    private val offline = AdHocWorkReportOfflineStore(outbox, JSON, CLOCK)
    private val repository = DefaultAdHocWorkReportsRepository(
        api = api,
        sessionAuthenticator = TestSessionAuthenticator(),
        subject = subject,
        offline = offline,
    )

    @Test
    fun `submits online and reuses the draft's idempotency key`() = runTest {
        val result = repository.submit(draft(clientOperationId = "operation-1"))

        assertTrue(result is AdHocWorkReportResult.Success)
        assertEquals("operation-1", api.lastRequest?.clientOperationId)
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `queues a submission the API could not be reached for`() = runTest {
        api.submitAnswer = { throw IOException() }

        val result = repository.submit(draft(clientOperationId = "operation-1"))

        assertTrue(result is AdHocWorkReportResult.Queued)
        val queued = outbox.stored.single()
        assertEquals("operation-1", queued.operationId)
        assertEquals(AdHocWorkReportOperationTypes.SUBMIT, queued.operationType)
        assertEquals("user-1", queued.subjectId)
    }

    @Test
    fun `reports a refusal and queues nothing`() = runTest {
        api.submitAnswer = { throw httpFailure(422) }

        val result = repository.submit(draft())

        assertTrue(result is AdHocWorkReportResult.Failure)
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `does not queue when no session is held`() = runTest {
        subject.setSubject(null)

        val result = repository.submit(draft())

        assertTrue(result is AdHocWorkReportResult.Failure)
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `replays a queued submission with the queued key`() = runTest {
        val handler = AdHocWorkReportSubmitHandler(api, TestSessionAuthenticator(), offline)
        val row = queuedSubmitRow(operationId = "operation-9")

        val outcome = handler.replay(row)

        assertEquals(ReplayOutcome.Applied, outcome)
        assertEquals("operation-9", api.lastRequest?.clientOperationId)
    }

    private fun draft(clientOperationId: String = "operation-1") = AdHocWorkReportDraft(
        clientOperationId = clientOperationId,
        customerId = null,
        propertyId = null,
        knownJobId = null,
        workStartedAt = START,
        workEndedAt = END,
        outcomeCode = "RESOLVED",
        summary = "Replaced the leaking valve.",
        notes = null,
        reportedCustomerName = "Maple Leaf Bakery",
        reportedCustomerPhone = null,
        reportedCustomerAddress = "210 Sparks Street",
    )

    private fun queuedSubmitRow(operationId: String) = OutboxOperation(
        operationId = operationId,
        operationType = AdHocWorkReportOperationTypes.SUBMIT,
        targetId = operationId,
        subjectId = "user-1",
        payload =
            """{"workStartedAt":"2026-10-01T13:00:00Z","workEndedAt":"2026-10-01T15:00:00Z",""" +
                """"outcomeCode":"RESOLVED","summary":"Replaced the leaking valve."}""",
        capturedAt = "2026-10-01T13:00:00Z",
        recordedAt = 1_000L,
        expectedVersion = null,
        attemptCount = 0,
        lastAttemptAt = null,
        lastFailure = null,
        state = OutboxOperationState.PENDING,
    )

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC)
        val JSON: Json = Json { ignoreUnknownKeys = true }
        val START: Instant = Instant.parse("2026-10-01T13:00:00Z")
        val END: Instant = Instant.parse("2026-10-01T15:00:00Z")
    }
}


/** An [AdHocWorkReportsApi] that answers the results a test scripts. */
private class FakeAdHocWorkReportsApi : AdHocWorkReportsApi {
    var submitAnswer: () -> AdHocWorkReportDto = {
        AdHocWorkReportDto(
            id = "report-1",
            workStartedAt = "2026-10-01T13:00:00Z",
            workEndedAt = "2026-10-01T15:00:00Z",
            outcomeCode = "RESOLVED",
            summary = "Replaced the leaking valve.",
            notes = null,
            status = "PENDING",
            version = 1,
            createdAt = "2026-10-01T12:00:00Z",
        )
    }
    var lastRequest: SubmitAdHocWorkReportRequestDto? = null
        private set

    override suspend fun submit(
        authorization: String,
        request: SubmitAdHocWorkReportRequestDto,
    ): AdHocWorkReportDto {
        lastRequest = request
        return submitAnswer()
    }

    override suspend fun customerOptions(
        authorization: String,
        query: String,
    ): List<AdHocReportCustomerOptionDto> = emptyList()

    override suspend fun propertyOptions(
        authorization: String,
        customerId: String,
    ): List<AdHocReportPropertyOptionDto> = emptyList()

    override suspend fun jobOptions(
        authorization: String,
        customerId: String,
    ): List<AdHocReportJobOptionDto> = emptyList()
}

/** A [SessionAuthenticator] that answers with a fixed token and refuses renewal. */
private class TestSessionAuthenticator : SessionAuthenticator {
    override fun accessToken(): String? = "access-1"
    override suspend fun renew(rejectedToken: String): SessionRenewal = SessionRenewal.Rejected
}

private fun httpFailure(status: Int): HttpException =
    HttpException(Response.error<Any>(status, "".toResponseBody(null)))
