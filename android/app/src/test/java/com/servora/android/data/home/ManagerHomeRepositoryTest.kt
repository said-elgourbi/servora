package com.servora.android.data.home

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.ManagerAttentionKind
import com.servora.android.domain.model.VisitStatus
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * How [DefaultManagerHomeRepository] reads `GET /home/manager` and classifies its answers.
 *
 * The backend decides what the manager's day holds (`BR-001`); these tests cover only that the read
 * carries the session and the device's time zone, that a refused access token is renewed once and
 * retried, that each HTTP answer is reported faithfully, and that a response this build cannot
 * interpret is reported rather than shown partially (`BR-042`).
 */
class ManagerHomeRepositoryTest {

    @Test
    fun `sends the session and the device zone and maps the payload onto the domain model`() =
        runTest {
            val api = FakeManagerHomeApi(answer = { managerHomeDto() })
            val repository = DefaultManagerHomeRepository(
                api,
                FakeSessionAuthenticator(accessToken = "access-token"),
            )

            val home = assertSuccess(repository.loadManagerHome("America/Toronto"))

            assertEquals("Bearer access-token", api.lastAuthorization)
            assertEquals("America/Toronto", api.lastTimeZone)
            assertEquals("Sarah Tremblay", home.displayName)
            assertEquals(1, home.attentionTotal)
            assertEquals(ManagerAttentionKind.VISIT_OVERDUE, home.attention[0].kind)
            assertEquals(1042, home.attention[0].jobNumber)
            assertEquals(3, home.today.total)
            assertEquals(1, home.today.completed)
            assertEquals(1, home.today.inProgress)
            assertEquals(1, home.today.upcoming)
            assertEquals(VisitStatus.EN_ROUTE, home.visits[0].visitStatus)
            assertTrue(home.visits[0].isOverdue)
            assertEquals("Mike Johnson", home.visits[0].technicians[0].name)
            assertEquals("LEAD", home.visits[0].technicians[0].roleCode)
            assertEquals("987 Cedar Lane", home.visits[0].address?.addressLine1)
        }

    @Test
    fun `reports no session as unauthenticated without calling the backend`() = runTest {
        val api = FakeManagerHomeApi(answer = { managerHomeDto() })
        val repository = DefaultManagerHomeRepository(
            api,
            FakeSessionAuthenticator(accessToken = null),
        )

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(repository.loadManagerHome("UTC")),
        )
        assertNull(api.lastAuthorization)
    }

    @Test
    fun `renews the session once and retries when the backend refuses the access token`() =
        runTest {
            val api = FakeManagerHomeApi(
                answer = { managerHomeDto() },
                failFirstWith = httpFailure(401),
            )
            val repository = DefaultManagerHomeRepository(
                api,
                FakeSessionAuthenticator(
                    accessToken = "stale-token",
                    renewal = SessionRenewal.Renewed(accessToken = "renewed-token"),
                ),
            )

            assertSuccess(repository.loadManagerHome("UTC"))

            assertEquals(2, api.calls)
            assertEquals("Bearer renewed-token", api.lastAuthorization)
        }

    @Test
    fun `reports a refused renewal as unauthenticated`() = runTest {
        val repository = DefaultManagerHomeRepository(
            FakeManagerHomeApi(answer = { throw httpFailure(401) }),
            FakeSessionAuthenticator(
                accessToken = "stale-token",
                renewal = SessionRenewal.Rejected,
            ),
        )

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(repository.loadManagerHome("UTC")),
        )
    }

    @Test
    fun `reports a caller without customers view as forbidden`() = runTest {
        val repository = failingWith(httpFailure(403))

        assertEquals(
            CustomersFailureReason.FORBIDDEN,
            assertFailure(repository.loadManagerHome("UTC")),
        )
    }

    @Test
    fun `reports an unreachable backend as a network failure`() = runTest {
        val repository = failingWith(IOException("offline"))

        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(repository.loadManagerHome("UTC")),
        )
    }

    @Test
    fun `reports a backend failure as a server failure`() = runTest {
        val repository = failingWith(httpFailure(503))

        assertEquals(
            CustomersFailureReason.SERVER,
            assertFailure(repository.loadManagerHome("UTC")),
        )
    }

    @Test
    fun `reports an unreadable payload as unexpected`() = runTest {
        val repository = failingWith(SerializationException("not the contract"))

        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(repository.loadManagerHome("UTC")),
        )
    }

    @Test
    fun `fails the whole read when a code this build does not know arrives`() = runTest {
        // A Visit status or attention kind added by a newer backend must not be dropped silently:
        // the screen would then describe an operation that is not the one the backend reported
        // (`BR-042`).
        val unknownVisitStatus = DefaultManagerHomeRepository(
            FakeManagerHomeApi(
                answer = {
                    managerHomeDto().copy(
                        visits = listOf(visitDto().copy(visitStatus = "RESCHEDULED")),
                    )
                },
            ),
            FakeSessionAuthenticator(accessToken = "access-token"),
        )
        val unknownKind = DefaultManagerHomeRepository(
            FakeManagerHomeApi(
                answer = {
                    managerHomeDto().copy(
                        attention = ManagerHomeAttentionDto(
                            total = 1,
                            items = listOf(attentionItemDto().copy(kind = "TECH_LATE")),
                        ),
                    )
                },
            ),
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(unknownVisitStatus.loadManagerHome("UTC")),
        )
        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(unknownKind.loadManagerHome("UTC")),
        )
    }

    private fun failingWith(failure: Throwable) =
        DefaultManagerHomeRepository(
            FakeManagerHomeApi(answer = { throw failure }),
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

    private fun assertSuccess(result: ManagerHomeResult) = when (result) {
        is ManagerHomeResult.Success -> result.home
        is ManagerHomeResult.Failure ->
            throw AssertionError("expected the manager's day, got ${result.reason}")
    }

    private fun assertFailure(result: ManagerHomeResult): CustomersFailureReason =
        when (result) {
            is ManagerHomeResult.Failure -> result.reason
            is ManagerHomeResult.Success -> throw AssertionError("expected a failure, got a day")
        }

    private fun httpFailure(status: Int) = HttpException(
        Response.error<ManagerHomeDto>(
            status,
            """{"statusCode":$status,"code":"UNKNOWN","message":"ignored"}"""
                .toResponseBody("application/json".toMediaType()),
        ),
    )
}

/** A payload shaped exactly like `GET /home/manager` answers. */
internal fun managerHomeDto(): ManagerHomeDto =
    ManagerHomeDto(
        generatedAt = "2026-09-14T15:00:00.000Z",
        day = ManagerHomeDayDto(
            timeZone = "America/Toronto",
            start = "2026-09-14T04:00:00.000Z",
            end = "2026-09-15T04:00:00.000Z",
        ),
        viewer = ManagerHomeViewerDto(displayName = "Sarah Tremblay"),
        attention = ManagerHomeAttentionDto(total = 1, items = listOf(attentionItemDto())),
        today = ManagerHomeTodayDto(total = 3, completed = 1, inProgress = 1, upcoming = 1),
        visits = listOf(visitDto()),
    )

internal fun attentionItemDto() = ManagerAttentionItemDto(
    kind = "VISIT_OVERDUE",
    jobId = "job-1",
    jobNumber = 1042,
    jobTitle = "Furnace repair",
    jobStatus = "SCHEDULED",
    customerId = "customer-1",
    customerName = "ABC Property Management",
    visitId = "visit-1",
    scheduledStart = "2026-09-14T11:00:00.000Z",
    scheduledEnd = "2026-09-14T12:00:00.000Z",
)

internal fun visitDto() = ManagerHomeVisitDto(
    visitId = "visit-1",
    visitStatus = "EN_ROUTE",
    scheduledStart = "2026-09-14T11:00:00.000Z",
    scheduledEnd = "2026-09-14T12:00:00.000Z",
    jobId = "job-1",
    jobNumber = 1042,
    jobTitle = "Furnace repair",
    jobStatus = "SCHEDULED",
    customerId = "customer-1",
    customerName = "ABC Property Management",
    address = ManagerHomeAddressDto(addressLine1 = "987 Cedar Lane", city = "Montreal"),
    technicians = listOf(
        ManagerHomeTechnicianDto(
            membershipId = "member-1",
            name = "Mike Johnson",
            roleCode = "LEAD",
        ),
    ),
    overdue = true,
)

/** A [ManagerHomeApi] that answers with what the test scripted, or fails the first call. */
private class FakeManagerHomeApi(
    private val answer: suspend () -> ManagerHomeDto,
    private val failFirstWith: Throwable? = null,
) : ManagerHomeApi {

    var calls = 0
    var lastAuthorization: String? = null
    var lastTimeZone: String? = null

    override suspend fun managerHome(
        authorization: String,
        timeZone: String,
    ): ManagerHomeDto {
        calls += 1
        if (calls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        lastAuthorization = authorization
        lastTimeZone = timeZone
        return answer()
    }
}

/** A [SessionAuthenticator] that hands out a fixed token and returns the renewal it was given. */
private class FakeSessionAuthenticator(
    private val accessToken: String?,
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal
}
