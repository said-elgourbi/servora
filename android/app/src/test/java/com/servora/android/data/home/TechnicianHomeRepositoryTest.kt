package com.servora.android.data.home

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.InMemoryWorkingSetStore
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.TechnicianAttentionKind
import com.servora.android.domain.model.VisitStatus
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * How [DefaultTechnicianHomeRepository] reads `GET /home/technician` and classifies its answers.
 *
 * The backend decides whose work the day holds and what it contains (`BR-001`, `ADR-019` D2), so
 * these tests cover only that the read carries the session and the device's time zone, that a refused
 * access token is renewed once and retried, that each HTTP answer is reported faithfully, and that a
 * day the device holds is served **only** when the backend could not be reached (`BR-013`,
 * offline-first-architecture.md §13.2).
 */
class TechnicianHomeRepositoryTest {

    @Test
    fun `sends the session and the device zone and maps the payload onto the domain model`() =
        runTest {
            val api = FakeTechnicianHomeApi(answer = { technicianHomeDto() })
            val repository = repository(
                api = api,
                sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
            )

            val result = repository.loadTechnicianHome("America/Toronto")

            assertEquals("Bearer access-token", api.lastAuthorization)
            assertEquals("America/Toronto", api.lastTimeZone)
            val home = assertSuccess(result)
            assertEquals(ReadSource.BACKEND, home.source)
            assertEquals("Mike Johnson", home.home.displayName)
            val next = home.home.nextVisit
            assertEquals("visit-2", next?.visitId)
            assertEquals(VisitStatus.EN_ROUTE, next?.visitStatus)
            assertEquals("Furnace repair", next?.jobTitle)
            assertEquals("987 Cedar Lane", next?.address?.addressLine1)
            assertEquals("John Smith", next?.technicians?.get(1)?.name)
            assertTrue(next?.isOverdue == false)
            assertEquals(listOf("visit-1"), home.home.visits.map { it.visitId })
            assertEquals(listOf("visit-3"), home.home.upcoming.map { it.visitId })
            assertEquals(4, home.home.upcomingTotal)
            assertEquals(TechnicianAttentionKind.VISIT_OVERDUE, home.home.attention[0].kind)
            assertEquals(2, home.home.attentionTotal)
        }

    @Test
    fun `reports no session as unauthenticated without calling the backend`() = runTest {
        val api = FakeTechnicianHomeApi(answer = { technicianHomeDto() })
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = null),
        )

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(repository.loadTechnicianHome("UTC")),
        )
        assertNull(api.lastAuthorization)
    }

    @Test
    fun `reports no subject as unauthenticated rather than answering without local state`() =
        runTest {
            val api = FakeTechnicianHomeApi(answer = { technicianHomeDto() })
            val repository = repository(
                api = api,
                sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
                subjectId = null,
            )

            assertEquals(
                CustomersFailureReason.UNAUTHENTICATED,
                assertFailure(repository.loadTechnicianHome("UTC")),
            )
            assertNull(api.lastAuthorization)
        }

    @Test
    fun `renews the session once and retries when the backend refuses the access token`() =
        runTest {
            val api = FakeTechnicianHomeApi(
                answer = { technicianHomeDto() },
                failFirstWith = httpFailure(401),
            )
            val repository = repository(
                api = api,
                sessionAuthenticator = FakeTechnicianSessionAuthenticator(
                    accessToken = "stale-token",
                    renewal = SessionRenewal.Renewed(accessToken = "fresh-token"),
                ),
            )

            val home = assertSuccess(repository.loadTechnicianHome("UTC"))

            assertEquals(ReadSource.BACKEND, home.source)
            assertEquals(2, api.calls)
            assertEquals("Bearer fresh-token", api.lastAuthorization)
        }

    @Test
    fun `serves the last day the backend reported when it cannot be reached`() = runTest {
        val workingSet = InMemoryWorkingSetStore()
        val online = repository(
            api = FakeTechnicianHomeApi(answer = { technicianHomeDto() }),
            sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )

        // A first successful read is what the device knows, and it is the answer the backend gave.
        assertEquals(
            ReadSource.BACKEND,
            assertSuccess(online.loadTechnicianHome("UTC")).source,
        )

        // The same device, with a read that cannot reach the backend: the day it holds is served,
        // marked as the last reported answer (`BR-013`, offline-first-architecture.md §7).
        val offline = repository(
            api = FakeTechnicianHomeApi(
                answer = { technicianHomeDto() },
                failWith = IOException("offline"),
            ),
            sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )

        val result = offline.loadTechnicianHome("UTC")

        assertEquals(ReadSource.WORKING_SET, assertSuccess(result).source)
        assertEquals("visit-2", assertSuccess(result).home.nextVisit?.visitId)
    }

    @Test
    fun `never masks a refusal with a local copy`() = runTest {
        val workingSet = InMemoryWorkingSetStore()
        val api = FakeTechnicianHomeApi(answer = { technicianHomeDto() })
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(repository.loadTechnicianHome("UTC"))

        // The backend answers — with a refusal — so the answer is reported and never replaced by the
        // day the device holds (`BR-007`, `BR-042`, offline-first-architecture.md §13.2).
        val refusedApi = FakeTechnicianHomeApi(
            answer = { technicianHomeDto() },
            failWith = httpFailure(403),
        )
        val refused = repository(
            api = refusedApi,
            sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )

        assertEquals(
            CustomersFailureReason.FORBIDDEN,
            assertFailure(refused.loadTechnicianHome("UTC")),
        )
    }

    @Test
    fun `never serves one subject the day another one read`() = runTest {
        val workingSet = InMemoryWorkingSetStore()
        val api = FakeTechnicianHomeApi(answer = { technicianHomeDto() })
        val reader = repository(
            api = api,
            sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(reader.loadTechnicianHome("UTC"))

        // Another session has no local day of its own: the working set is scoped to the subject
        // (`§10`), so a read that cannot reach the backend reports the failure instead.
        val otherSession = repository(
            api = FakeTechnicianHomeApi(
                answer = { technicianHomeDto() },
                failWith = IOException("offline"),
            ),
            sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
            subjectId = "user-2",
        )

        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(otherSession.loadTechnicianHome("UTC")),
        )
    }

    @Test
    fun `classifies each HTTP answer into the reason the screen reports`() = runTest {
        val cases = listOf(
            400 to CustomersFailureReason.VALIDATION,
            401 to CustomersFailureReason.UNAUTHENTICATED,
            404 to CustomersFailureReason.NOT_FOUND,
            409 to CustomersFailureReason.VERSION_CONFLICT,
            500 to CustomersFailureReason.SERVER,
            418 to CustomersFailureReason.UNEXPECTED,
        )

        for ((status, expected) in cases) {
            val repository = repository(
                api = FakeTechnicianHomeApi(
                    answer = { technicianHomeDto() },
                    failWith = httpFailure(status),
                ),
                sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
            )

            assertEquals(expected, assertFailure(repository.loadTechnicianHome("UTC")))
        }
    }

    @Test
    fun `reports a payload this build cannot interpret rather than showing it partially`() =
        runTest {
            val repository = repository(
                api = FakeTechnicianHomeApi(
                    // A Visit status this build does not know is a contract mismatch (`BR-042`).
                    answer = {
                        technicianHomeDto().let { dto ->
                            dto.copy(
                                visits = dto.visits.map { it.copy(visitStatus = "TELEPORTED") },
                            )
                        }
                    },
                ),
                sessionAuthenticator = FakeTechnicianSessionAuthenticator(accessToken = "access-token"),
            )

            assertEquals(
                CustomersFailureReason.UNEXPECTED,
                assertFailure(repository.loadTechnicianHome("UTC")),
            )
        }
}


/** The repository these tests exercise, with the in-memory working set and its JSON reader. */
private fun repository(
    api: TechnicianHomeApi,
    sessionAuthenticator: SessionAuthenticator,
    workingSet: InMemoryWorkingSetStore = InMemoryWorkingSetStore(),
    subjectId: String? = SUBJECT_ID,
) = DefaultTechnicianHomeRepository(
    api = api,
    sessionAuthenticator = sessionAuthenticator,
    cache = TechnicianHomeCache(
        workingSet = workingSet,
        json = Json { ignoreUnknownKeys = true },
        clock = Clock.fixed(Instant.parse("2026-09-17T15:00:00.000Z"), ZoneOffset.UTC),
    ),
    subject = FakeAuthenticatedSubject(subjectId),
)

/** The subject the working set's rows are filed under; a second one stands for another session. */
private const val SUBJECT_ID = "user-1"

/** A payload shaped exactly like `GET /home/technician` answers. */
internal fun technicianHomeDto(): TechnicianHomeDto =
    TechnicianHomeDto(
        generatedAt = "2026-09-17T15:00:00.000Z",
        day = TechnicianHomeDayDto(
            timeZone = "America/Toronto",
            start = "2026-09-17T04:00:00.000Z",
            end = "2026-09-18T04:00:00.000Z",
        ),
        viewer = TechnicianHomeViewerDto(displayName = "Mike Johnson"),
        nextVisit = nextVisitDto(),
        visits = listOf(todayVisitDto()),
        upcoming = listOf(upcomingVisitDto()),
        upcomingTotal = 4,
        attention = TechnicianHomeAttentionDto(total = 2, items = listOf(technicianAttentionItemDto())),
    )

private fun nextVisitDto() = TechnicianHomeVisitDto(
    visitId = "visit-2",
    visitStatus = "EN_ROUTE",
    scheduledStart = "2026-09-17T13:00:00.000Z",
    scheduledEnd = "2026-09-17T14:00:00.000Z",
    jobId = "job-2",
    jobNumber = 1043,
    jobTitle = "Furnace repair",
    jobStatus = "IN_PROGRESS",
    customerId = "customer-1",
    customerName = "ABC Property Management",
    address = TechnicianHomeAddressDto(addressLine1 = "987 Cedar Lane", city = "Montreal"),
    technicians = listOf(
        TechnicianHomeTechnicianDto(
            membershipId = "member-1",
            name = "Mike Johnson",
            roleCode = "LEAD",
        ),
        TechnicianHomeTechnicianDto(
            membershipId = "member-2",
            name = "John Smith",
            roleCode = "TECHNICIAN",
        ),
    ),
)

private fun todayVisitDto() = TechnicianHomeVisitDto(
    visitId = "visit-1",
    visitStatus = "COMPLETED",
    scheduledStart = "2026-09-17T09:00:00.000Z",
    scheduledEnd = "2026-09-17T10:00:00.000Z",
    jobId = "job-1",
    jobNumber = 1042,
    jobTitle = "Boiler service",
    jobStatus = "IN_PROGRESS",
    customerId = "customer-1",
    customerName = "ABC Property Management",
)

private fun upcomingVisitDto() = TechnicianHomeVisitDto(
    visitId = "visit-3",
    visitStatus = "SCHEDULED",
    scheduledStart = "2026-09-18T13:00:00.000Z",
    scheduledEnd = "2026-09-18T14:00:00.000Z",
    jobId = "job-3",
    jobNumber = 1044,
    jobTitle = "AC inspection",
    jobStatus = "SCHEDULED",
    customerId = "customer-1",
    customerName = "ABC Property Management",
)

private fun technicianAttentionItemDto() = TechnicianAttentionItemDto(
    kind = "VISIT_OVERDUE",
    visitId = "visit-9",
    jobId = "job-9",
    jobNumber = 1049,
    jobTitle = "Water heater service",
    jobStatus = "SCHEDULED",
    customerId = "customer-1",
    customerName = "ABC Property Management",
    scheduledStart = "2026-09-16T09:00:00.000Z",
    scheduledEnd = "2026-09-16T10:00:00.000Z",
)

/** A day the repository reported, asserted to be a success. */
private fun assertSuccess(result: TechnicianHomeResult): TechnicianHomeResult.Success =
    when (result) {
        is TechnicianHomeResult.Success -> result
        is TechnicianHomeResult.Failure ->
            throw AssertionError("expected the day, got ${result.reason}")
    }

/** The reason a read failed, asserted to be a failure. */
private fun assertFailure(result: TechnicianHomeResult): CustomersFailureReason =
    when (result) {
        is TechnicianHomeResult.Failure -> result.reason
        is TechnicianHomeResult.Success -> throw AssertionError("expected a failure, got a day")
    }

private fun httpFailure(status: Int) = HttpException(
    Response.error<TechnicianHomeDto>(
        status,
        """{"statusCode":$status,"code":"UNKNOWN","message":"ignored"}"""
            .toResponseBody("application/json".toMediaType()),
    ),
)

/** A [TechnicianHomeApi] that answers with what the test scripted, or fails the call. */
private class FakeTechnicianHomeApi(
    private val answer: suspend () -> TechnicianHomeDto,
    private val failWith: Throwable? = null,
    private val failFirstWith: Throwable? = null,
) : TechnicianHomeApi {

    var calls = 0
    var lastAuthorization: String? = null
    var lastTimeZone: String? = null

    override suspend fun technicianHome(
        authorization: String,
        timeZone: String,
    ): TechnicianHomeDto {
        calls += 1
        if (calls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        lastAuthorization = authorization
        lastTimeZone = timeZone
        if (failWith != null) {
            throw failWith
        }
        return answer()
    }
}

/** A [SessionAuthenticator] that hands out a fixed token and returns the renewal it was given. */
private class FakeTechnicianSessionAuthenticator(
    private val accessToken: String?,
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal
}

