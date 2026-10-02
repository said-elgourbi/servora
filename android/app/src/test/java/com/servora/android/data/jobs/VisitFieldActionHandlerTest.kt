package com.servora.android.data.jobs

import com.servora.android.data.offline.InMemoryWorkingSetStore
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Replaying a queued Visit field action (`BR-074`, `BR-077`, `offline-first-architecture.md` §5, §6).
 *
 * Three properties make a late replay safe, and each is pinned here: the operation keeps the
 * idempotency key and the version the technician saw, a completion travels to the **completion** route
 * with the outcome it recorded rather than being sent as a plain status change, and a refusal is
 * reported as the backend's own answer — the reason included — so the technician is told what happened
 * instead of the action being re-applied (`BR-014`, `BR-032`).
 */
class VisitFieldActionHandlerTest {

    @Test
    fun `replays a working transition to the status route, with its key and version`() = runTest {
        val api = RecordingVisitFieldApi()

        val outcome = handler(api).replay(
            queuedRow(
                operationId = "op-1",
                status = "ON_SITE",
                expectedVersion = 4,
                capturedAt = "2026-09-15T13:05:00Z",
            ),
        )

        assertEquals(ReplayOutcome.Applied, outcome)
        assertEquals("visit-1", api.lastVisitId)
        assertEquals(
            ChangeVisitStatusRequestDto(
                status = "ON_SITE",
                clientOperationId = "op-1",
                capturedAt = "2026-09-15T13:05:00Z",
                expectedVersion = 4,
                // Nothing is confirmed for a queued action: `BR-070` requires the conflict to be shown
                // and accepted explicitly, and a queued attempt had no one to ask.
                confirmConflicts = false,
            ),
            api.lastVisitStatusRequest,
        )
        // The completion route is not involved: a working status is not a completion (`BR-077`).
        assertNull(api.lastVisitCompletionRequest)
    }

    @Test
    fun `replays a completion to the completion route, with the outcome it recorded`() = runTest {
        val api = RecordingVisitFieldApi()

        val outcome = handler(api).replay(
            queuedRow(
                operationId = "op-2",
                status = "COMPLETED",
                outcomeCode = "NEEDS_FOLLOW_UP",
                outcomeSummary = "The part has to be ordered.",
                expectedVersion = 5,
                capturedAt = "2026-09-15T14:00:00Z",
            ),
        )

        assertEquals(ReplayOutcome.Applied, outcome)
        assertEquals(
            CompleteVisitRequestDto(
                outcomeCode = "NEEDS_FOLLOW_UP",
                outcomeSummary = "The part has to be ordered.",
                clientOperationId = "op-2",
                capturedAt = "2026-09-15T14:00:00Z",
                expectedVersion = 5,
            ),
            api.lastVisitCompletionRequest,
        )
        // The status route is not asked to record an outcome: it refuses one (`BR-077`).
        assertNull(api.lastVisitStatusRequest)
    }

    @Test
    fun `refuses a queued completion whose outcome this build cannot read`() = runTest {
        val api = RecordingVisitFieldApi()

        val outcome = handler(api).replay(
            queuedRow(
                operationId = "op-3",
                status = "COMPLETED",
                outcomeCode = "AN_OUTCOME_SERVORA_DOES_NOT_HAVE",
                outcomeSummary = "Whatever this was.",
            ),
        )

        // A code this build cannot name is not sent as something it is not (`BR-042`, `BR-078`).
        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.INVALID), outcome)
        assertNull(api.lastVisitCompletionRequest)
        assertNull(api.lastVisitStatusRequest)
    }

    @Test
    fun `refuses a queued action whose status this build cannot name`() = runTest {
        val api = RecordingVisitFieldApi()

        val outcome = handler(api).replay(queuedRow(operationId = "op-6", status = "ON_STIE"))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.INVALID), outcome)
        assertNull(api.lastVisitStatusRequest)
    }

    @Test
    fun `reports a Job canceled or completed elsewhere as its own reason`() = runTest {
        val api = RecordingVisitFieldApi(
            failure = httpFailure(409, """{"code":"JOB_CLOSED_FOR_FIELD_WORK"}"""),
        )

        val outcome = handler(api).replay(
            queuedRow(operationId = "op-4", status = "ON_SITE", expectedVersion = 4),
        )

        // The row is refused rather than retried: nothing the technician can do makes their work apply
        // under a Job the office has closed, and the screen has to say so (`BR-014`, `BR-032`,
        // `BR-062`, `BR-079`).
        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.JOB_CLOSED), outcome)
    }

    @Test
    fun `reports a Visit that has moved on as a stale version, not as a closed Job`() = runTest {
        val api = RecordingVisitFieldApi(
            failure = httpFailure(409, """{"code":"VERSION_CONFLICT"}"""),
        )

        val outcome = handler(api).replay(
            queuedRow(operationId = "op-5", status = "ON_SITE", expectedVersion = 4),
        )

        // A version conflict is the state machine's own answer and stays distinct from a closed Job:
        // the technician may re-read and act again (`BR-086`, `ADR-019` D5).
        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.STALE), outcome)
    }

    @Test
    fun `keeps a queued action retryable when the backend cannot be reached`() = runTest {
        val api = RecordingVisitFieldApi(failure = IOException("no connection"))

        val outcome = handler(api).replay(queuedRow(operationId = "op-7", status = "ON_SITE"))

        // The request never reached the backend, so it may succeed later: it is retried rather than
        // refused (`BR-014`, §6).
        assertEquals(ReplayOutcome.Retryable(OutboxFailureReason.NETWORK), outcome)
    }

    private fun handler(api: RecordingVisitFieldApi): VisitFieldActionHandler =
        VisitFieldActionHandler(
            api = api,
            sessionAuthenticator = FakeVisitFieldSessionAuthenticator(),
            evidence = JobEvidenceCache(
                workingSet = InMemoryWorkingSetStore(),
                json = Json { ignoreUnknownKeys = true },
                clock = TEST_CLOCK,
            ),
            subject = FakeAuthenticatedSubject(),
            json = Json { ignoreUnknownKeys = true },
        )

    /** One queued Visit field action, as the outbox holds it (§4). */
    private fun queuedRow(
        operationId: String,
        status: String,
        outcomeCode: String? = null,
        outcomeSummary: String? = null,
        expectedVersion: Int = 4,
        capturedAt: String = "2026-09-15T13:05:00Z",
    ) = OutboxOperation(
        operationId = operationId,
        operationType = VisitFieldOperationTypes.CHANGE_STATUS,
        targetId = "job-1",
        subjectId = "user-1",
        payload = Json.encodeToString(
            VisitFieldOperationPayload.serializer(),
            VisitFieldOperationPayload(
                visitId = "visit-1",
                status = status,
                outcomeCode = outcomeCode,
                outcomeSummary = outcomeSummary,
            ),
        ),
        capturedAt = capturedAt,
        recordedAt = 0,
        expectedVersion = expectedVersion,
        attemptCount = 0,
        lastAttemptAt = null,
        lastFailure = null,
        state = OutboxOperationState.PENDING,
    )
}

/**
 * The Job Details API as a field-action replay uses it (`BR-074`, `BR-077`).
 *
 * Every other operation belongs to another test's fake, so this one delegates them and answers only
 * the two routes a replay can reach: it records which one was asked for, and what the request carried,
 * so a test can assert that a completion travels to the completion route and a working status does not.
 */
private class RecordingVisitFieldApi(
    private val failure: Throwable? = null,
) : JobDetailsApi by PhotoUploadApi() {

    var lastVisitId: String? = null
        private set

    var lastVisitStatusRequest: ChangeVisitStatusRequestDto? = null
        private set

    var lastVisitCompletionRequest: CompleteVisitRequestDto? = null
        private set

    override suspend fun changeVisitStatus(
        authorization: String,
        jobId: String,
        visitId: String,
        request: ChangeVisitStatusRequestDto,
    ): JobDetailsDto {
        failure?.let { throw it }
        lastVisitId = visitId
        lastVisitStatusRequest = request
        return playedJob(status = "ACTIVE")
    }

    override suspend fun completeVisit(
        authorization: String,
        jobId: String,
        visitId: String,
        request: CompleteVisitRequestDto,
    ): JobDetailsDto {
        failure?.let { throw it }
        lastVisitId = visitId
        lastVisitCompletionRequest = request
        return playedJob(status = "COMPLETED")
    }

    /** The Job the replay is answered with: the projection the routes return (`BR-001`). */
    private fun playedJob(status: String) = JobDetailsDto(
        id = "job-1",
        jobNumber = 1042,
        title = "Furnace repair",
        status = status,
        customerId = "customer-1",
        customerName = "Martha Reynolds",
    )
}

/** The session a replay reads its token from (`BR-018`). */
private class FakeVisitFieldSessionAuthenticator : SessionAuthenticator {
    override fun accessToken(): String? = "access-token"

    override suspend fun renew(rejectedToken: String): SessionRenewal = SessionRenewal.Rejected
}

/** A refused answer, as Retrofit raises it (`dev.md` §7). */
private fun httpFailure(status: Int, body: String): HttpException =
    HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))
