package com.servora.android.data.schedule

import com.servora.android.data.offline.InMemoryOutboxStore
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.AdHocWorkReport
import com.servora.android.domain.model.AdHocWorkReportStatus
import com.servora.android.domain.model.ScheduleAddress
import com.servora.android.domain.model.VisitOutcome
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
    fun `resolution sends the report version and keeps the resolved report pending`() = runTest {
        val original = report().copy(customerId = null, propertyId = null, reportedCustomerName = "Reported")
        api.resolutionAnswer = { reportDto().copy(customerId = "matched", propertyId = "location", version = 4, reportedCustomerName = "Reported") }
        val result = repository.resolveLocation(original, ResolveAdHocLocationRequestDto(existingCustomerId = "matched", existingPropertyId = "location", expectedVersion = 99))
        assertEquals(3, api.lastResolution?.expectedVersion)
        assertEquals("PENDING", api.lastResolution?.expectedStatus)
        val wire = Json.encodeToString(api.lastResolution!!)
        assertTrue(wire.contains("\"expectedStatus\":\"PENDING\""))
        assertTrue(wire.contains("\"expectedVersion\":3"))
        val resolved = (result as AdHocReviewDataResult.Success).value
        assertEquals(AdHocWorkReportStatus.PENDING, resolved.status)
        assertEquals("Reported", resolved.reportedCustomerName)
        assertEquals("matched", resolved.customerId)
        assertTrue(outbox.stored.isEmpty())
    }

    @Test
    fun `failed resolution is never queued and stale versions are reported`() = runTest {
        api.resolutionAnswer = { throw IOException() }
        val request = ResolveAdHocLocationRequestDto(existingCustomerId = "matched", existingPropertyId = "location", expectedVersion = 3)
        assertTrue(repository.resolveLocation(report(), request) is AdHocReviewDataResult.Failure)
        assertTrue(outbox.stored.isEmpty())
        api.resolutionAnswer = { throw HttpException(Response.error<AdHocWorkReportDto>(409, "{}".toResponseBody())) }
        val result = repository.resolveLocation(report(), request) as AdHocReviewDataResult.Failure
        assertEquals(com.servora.android.data.customers.CustomersFailureReason.VERSION_CONFLICT, result.reason)
    }

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

    @Test
    fun `loads reports and maps their facts`() = runTest {
        api.listAnswer = { listOf(reportDto()) }

        val result = repository.loadReports()

        assertTrue(result is AdHocWorkReportsResult.Success)
        val report = (result as AdHocWorkReportsResult.Success).reports.single()
        assertEquals("report-1", report.id)
        assertEquals("Maple Leaf Bakery", report.customerName)
        assertEquals(AdHocWorkReportStatus.PENDING, report.status)
        assertEquals(VisitOutcome.RESOLVED, report.outcomeCode)
    }

    @Test
    fun `rejects with the pending status and the report version`() = runTest {
        api.rejectAnswer = { reportDto(status = "REJECTED") }

        val result = repository.reject(report(), "Not legitimate work")

        assertTrue(result is AdHocWorkReportRejectResult.Success)
        assertEquals(
            AdHocWorkReportStatus.REJECTED,
            (result as AdHocWorkReportRejectResult.Success).report.status,
        )
        assertEquals("PENDING", api.lastRejectRequest?.expectedStatus)
        assertEquals(3, api.lastRejectRequest?.expectedVersion)
        assertEquals("Not legitimate work", api.lastRejectRequest?.note)
    }

    @Test
    fun `links to the known job and returns the reconciled job id`() = runTest {
        val result = repository.link(report(), "job-9", "Linked to the seasonal job")

        assertTrue(result is AdHocWorkReportReconcileResult.Success)
        assertEquals("job-1", (result as AdHocWorkReportReconcileResult.Success).jobId)
        assertEquals("job-9", api.lastLinkRequest?.jobId)
        assertEquals("PENDING", api.lastLinkRequest?.expectedStatus)
        assertEquals(3, api.lastLinkRequest?.expectedVersion)
    }

    @Test
    fun `converts into a new job with its title and description`() = runTest {
        val result = repository.convert(
            report = report(),
            title = "Emergency valve replacement",
            description = "From ad-hoc report",
            note = "New work request was needed",
        )

        assertTrue(result is AdHocWorkReportReconcileResult.Success)
        assertEquals("Emergency valve replacement", api.lastConvertRequest?.title)
        assertEquals("From ad-hoc report", api.lastConvertRequest?.description)
        assertEquals("New work request was needed", api.lastConvertRequest?.note)
    }

    private fun report() = AdHocWorkReport(
        id = "report-1",
        reportingTechnicianMembershipId = "tech-1",
        customerId = "customer-1",
        customerName = "Maple Leaf Bakery",
        propertyId = "property-1",
        propertyAddress = ScheduleAddress(
            propertyName = "Main bakery",
            addressLine1 = "210 Sparks Street",
            addressLine2 = null,
            city = "Ottawa",
            province = "ON",
            postalCode = "K1P 5B4",
            country = "CA",
        ),
        knownJobId = "job-9",
        knownJobNumber = 1042,
        knownJobTitle = "Seasonal maintenance",
        workStartedAt = "2026-10-01T13:00:00Z",
        workEndedAt = "2026-10-01T15:00:00Z",
        outcomeCode = VisitOutcome.RESOLVED,
        summary = "Replaced the leaking valve.",
        notes = null,
        reportedCustomerName = null,
        reportedCustomerPhone = null,
        reportedCustomerAddress = null,
        status = AdHocWorkReportStatus.PENDING,
        reviewerMembershipId = null,
        reviewedAt = null,
        reviewNote = null,
        createdJobId = null,
        createdJobNumber = null,
        createdJobTitle = null,
        createdVisitId = null,
        version = 3,
        createdAt = "2026-10-01T12:00:00Z",
        updatedAt = "2026-10-01T12:00:00Z",
    )

    private fun reportDto(status: String = "PENDING") = AdHocWorkReportDto(
        id = "report-1",
        workStartedAt = "2026-10-01T13:00:00Z",
        workEndedAt = "2026-10-01T15:00:00Z",
        outcomeCode = "RESOLVED",
        summary = "Replaced the leaking valve.",
        notes = null,
        status = status,
        version = 3,
        createdAt = "2026-10-01T12:00:00Z",
        reportingTechnicianMembershipId = "tech-1",
        customerId = "customer-1",
        customerName = "Maple Leaf Bakery",
        propertyId = "property-1",
        propertyAddress = ScheduleAddressDto(
            propertyName = "Main bakery",
            addressLine1 = "210 Sparks Street",
            city = "Ottawa",
            province = "ON",
            postalCode = "K1P 5B4",
            country = "CA",
        ),
        knownJobId = "job-9",
        knownJobNumber = 1042,
        knownJobTitle = "Seasonal maintenance",
        updatedAt = "2026-10-01T12:00:00Z",
    )

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
    var lastResolution: ResolveAdHocLocationRequestDto? = null
    var resolutionAnswer: () -> AdHocWorkReportDto = { submitAnswer().copy(customerId = "customer-1", propertyId = "property-1", version = 2) }
    override suspend fun reviewCustomerOptions(authorization: String, query: String) = listOf(AdHocReportCustomerOptionDto("customer-1", "Match"))
    override suspend fun reviewPropertyOptions(authorization: String, customerId: String) = emptyList<AdHocReportPropertyOptionDto>()
    override suspend fun resolveLocation(authorization: String, reportId: String, request: ResolveAdHocLocationRequestDto): AdHocWorkReportDto {
        lastResolution = request
        return resolutionAnswer()
    }
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

    override suspend fun reviewJobOptions(
        authorization: String,
        customerId: String?,
        query: String?,
    ): List<AdHocReportJobOptionDto> = emptyList()

    var listAnswer: () -> List<AdHocWorkReportDto> = { emptyList() }
    var rejectAnswer: () -> AdHocWorkReportDto = { submitAnswer() }
    var reconcileAnswer: () -> AdHocReconciliationJobDto = {
        AdHocReconciliationJobDto(id = "job-1", jobNumber = 42)
    }
    var lastRejectRequest: RejectAdHocWorkReportRequestDto? = null
        private set
    var lastLinkRequest: LinkAdHocWorkReportRequestDto? = null
        private set
    var lastConvertRequest: ConvertAdHocWorkReportRequestDto? = null
        private set

    override suspend fun list(authorization: String): List<AdHocWorkReportDto> = listAnswer()

    override suspend fun reject(
        authorization: String,
        reportId: String,
        request: RejectAdHocWorkReportRequestDto,
    ): AdHocWorkReportDto {
        lastRejectRequest = request
        return rejectAnswer()
    }

    override suspend fun link(
        authorization: String,
        reportId: String,
        request: LinkAdHocWorkReportRequestDto,
    ): AdHocReconciliationJobDto {
        lastLinkRequest = request
        return reconcileAnswer()
    }

    override suspend fun convert(
        authorization: String,
        reportId: String,
        request: ConvertAdHocWorkReportRequestDto,
    ): AdHocReconciliationJobDto {
        lastConvertRequest = request
        return reconcileAnswer()
    }
}

/** A [SessionAuthenticator] that answers with a fixed token and refuses renewal. */
private class TestSessionAuthenticator : SessionAuthenticator {
    override fun accessToken(): String? = "access-1"
    override suspend fun renew(rejectedToken: String): SessionRenewal = SessionRenewal.Rejected
}

private fun httpFailure(status: Int): HttpException =
    HttpException(Response.error<Any>(status, "".toResponseBody(null)))
