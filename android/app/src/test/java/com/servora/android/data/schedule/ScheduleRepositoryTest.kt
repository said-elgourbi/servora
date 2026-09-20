package com.servora.android.data.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.InMemoryWorkingSetStore
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.offline.WorkingSetStore
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.ScheduleScopeKind
import com.servora.android.domain.model.VisitStatus
import java.io.IOException
import java.time.Clock
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
 * How [DefaultScheduleRepository] reads `GET /schedule` and classifies its answers.
 *
 * The backend decides which Visits a day holds, who is assigned and what is unassigned (`BR-001`), so
 * these tests cover only that the read carries the session, the date, the device's zone and the
 * technician filter, that a refused access token is renewed once and retried, and that each HTTP
 * answer is reported faithfully.
 */
class ScheduleRepositoryTest {

    @Test
    fun `sends the session, the date, the zone and the filter and maps the payload`() = runTest {
        val api = FakeScheduleApi(answer = { scheduleDto() })
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
        )

        val result = repository.loadSchedule(
            localDate = "2026-09-07",
            timeZone = "America/Toronto",
            membershipIds = listOf("member-1"),
        )

        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals("2026-09-07", api.lastDate)
        assertEquals("America/Toronto", api.lastTimeZone)
        assertEquals(listOf("member-1"), api.lastMembershipIds)
        val schedule = assertSuccess(result)
        assertEquals("2026-09-07", schedule.schedule.day.localDate)
        assertEquals(listOf("visit-1"), schedule.schedule.visits.map { it.visitId })
        assertEquals(VisitStatus.SCHEDULED, schedule.schedule.visits[0].visitStatus)
        assertEquals("Furnace repair", schedule.schedule.visits[0].jobTitle)
        assertEquals("987 Cedar Lane", schedule.schedule.visits[0].address?.addressLine1)
        assertEquals("Mike Johnson", schedule.schedule.visits[0].technicians[0].name)
        assertTrue(schedule.schedule.visits[0].isOverdue)
        assertEquals(listOf("visit-9"), schedule.schedule.unassigned.map { it.visitId })
        // The lane's own total is not the length of the list the API returned.
        assertEquals(3, schedule.schedule.unassignedTotal)
        assertNull(schedule.schedule.unassigned[0].scheduledStart)
        assertEquals(listOf("Mike Johnson"), schedule.schedule.technicians.map { it.name })
    }

    @Test
    fun `sends no filter when the whole organization is shown`() = runTest {
        val api = FakeScheduleApi(answer = { scheduleDto() })
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
        )

        repository.loadSchedule(
            localDate = "2026-09-07",
            timeZone = "UTC",
            membershipIds = emptyList(),
        )

        assertEquals(emptyList<String>(), api.lastMembershipIds)
    }

    @Test
    fun `sends every selected technician in one read`() = runTest {
        val api = FakeScheduleApi(answer = { scheduleDto() })
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
        )

        repository.loadSchedule(
            localDate = "2026-09-07",
            timeZone = "UTC",
            membershipIds = listOf("member-1", "member-2", "member-3"),
        )

        // The API answers with the union of the ids, so a dispatcher's group is one request rather
        // than one per technician (`BR-068`).
        assertEquals(listOf("member-1", "member-2", "member-3"), api.lastMembershipIds)
        assertEquals(1, api.calls)
    }

    @Test
    fun `reports no session as unauthenticated without calling the backend`() = runTest {
        val api = FakeScheduleApi(answer = { scheduleDto() })
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = null),
        )

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(
                repository.loadSchedule("2026-09-07", "UTC", emptyList()),
            ),
        )
        assertEquals(0, api.calls)
    }

    @Test
    fun `renews a refused access token once and retries the read`() = runTest {
        val api = FakeScheduleApi(
            answer = { scheduleDto() },
            failFirstWith = httpFailure(401),
        )
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(
                accessToken = "expired",
                renewal = SessionRenewal.Renewed("renewed"),
            ),
        )

        val schedule = assertSuccess(
            repository.loadSchedule("2026-09-07", "UTC", emptyList()),
        )

        assertEquals(2, api.calls)
        assertEquals("Bearer renewed", api.lastAuthorization)
        assertEquals("2026-09-07", schedule.schedule.day.localDate)
    }

    @Test
    fun `reports a rejected renewal as unauthenticated`() = runTest {
        val api = FakeScheduleApi(
            answer = { scheduleDto() },
            failFirstWith = httpFailure(401),
        )
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(
                accessToken = "expired",
                renewal = SessionRenewal.Rejected,
            ),
        )

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(repository.loadSchedule("2026-09-07", "UTC", emptyList())),
        )
    }

    @Test
    fun `reports the backend's refusals faithfully`() = runTest {
        assertEquals(CustomersFailureReason.FORBIDDEN, failureOf(httpFailure(403)))
        assertEquals(CustomersFailureReason.VALIDATION, failureOf(httpFailure(400)))
        assertEquals(CustomersFailureReason.SERVER, failureOf(httpFailure(503)))
        assertEquals(CustomersFailureReason.NETWORK, failureOf(IOException("no route")))
    }

    @Test
    fun `fails the read when the payload carries a status this build does not know`() = runTest {
        val api = FakeScheduleApi(
            answer = {
                scheduleDto().copy(
                    visits = listOf(scheduleVisitDto(visitId = "visit-1", status = "PAUSED")),
                )
            },
        )
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
        )

        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(repository.loadSchedule("2026-09-07", "UTC", emptyList())),
        )
    }

    private suspend fun failureOf(failure: Throwable): CustomersFailureReason {
        val repository = repository(
            api = FakeScheduleApi(answer = { scheduleDto() }, failWith = failure),
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
        )
        return assertFailure(repository.loadSchedule("2026-09-07", "UTC", emptyList()))
    }

    @Test
    fun `maps the scope the read was resolved for`() = runTest {
        val api = FakeScheduleApi(
            answer = {
                scheduleDto().copy(
                    scope = ScheduleScopeDto(kind = "SELF", membershipId = "member-7"),
                )
            },
        )
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
        )

        val schedule = assertSuccess(
            repository.loadSchedule("2026-09-07", "UTC", emptyList()),
        ).schedule

        assertEquals(ScheduleScopeKind.SELF, schedule.scope.kind)
        assertEquals("member-7", schedule.scope.membershipId)
    }

    @Test
    fun `fails the read when the payload carries a scope this build does not know`() = runTest {
        val api = FakeScheduleApi(
            answer = {
                scheduleDto().copy(
                    scope = ScheduleScopeDto(kind = "TEAM", membershipId = "member-1"),
                )
            },
        )
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
        )

        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(repository.loadSchedule("2026-09-07", "UTC", emptyList())),
        )
    }

    @Test
    fun `reads a field-scoped day as one with no unassigned lane`() = runTest {
        // The office's "waiting for a crew" lane is not this scope's question, and an absent lane is
        // not an empty one (`BR-009`, `BR-042`).
        val api = FakeScheduleApi(answer = { selfScheduleDto() })
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
        )

        val schedule = assertSuccess(
            repository.loadSchedule("2026-09-07", "UTC", emptyList()),
        ).schedule

        assertEquals(emptyList<String>(), schedule.unassigned.map { it.visitId })
        assertEquals(0, schedule.unassignedTotal)
        assertTrue(!schedule.hasUnassignedLane)
        assertEquals(emptyList<String>(), schedule.technicians.map { it.name })
    }

    @Test
    fun `serves a field-scoped day from the working set when the backend cannot be reached`() =
        runTest {
            // `BR-013` makes the caller's own assigned work readable without connectivity, so the day
            // the backend last reported is kept and served (`offline-first-architecture.md` §12, §13).
            val workingSet = InMemoryWorkingSetStore()
            val backend = repository(
                api = FakeScheduleApi(answer = { selfScheduleDto() }),
                sessionAuthenticator = FakeScheduleSessionAuthenticator(
                    accessToken = "access-token",
                ),
                workingSet = workingSet,
            )
            assertSuccess(backend.loadSchedule("2026-09-07", "UTC", emptyList()))

            val offline = repository(
                api = FakeScheduleApi(
                    answer = { selfScheduleDto() },
                    failWith = IOException("no route"),
                ),
                sessionAuthenticator = FakeScheduleSessionAuthenticator(
                    accessToken = "access-token",
                ),
                workingSet = workingSet,
            )
            val result = assertSuccess(offline.loadSchedule("2026-09-07", "UTC", emptyList()))

            assertEquals(ReadSource.WORKING_SET, result.source)
            assertEquals(listOf("visit-1"), result.schedule.visits.map { it.visitId })
            assertEquals("2026-09-07", result.schedule.day.localDate)
        }

    @Test
    fun `does not keep an office-scoped day for offline use`() = runTest {
        // The office board is a dispatcher's read over the operation's own work and stays online-only
        // (`ADR-020` D6): only a day that answered for the caller's own work is remembered.
        val workingSet = InMemoryWorkingSetStore()
        val backend = repository(
            api = FakeScheduleApi(answer = { scheduleDto() }),
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(backend.loadSchedule("2026-09-07", "UTC", emptyList()))

        val offline = repository(
            api = FakeScheduleApi(answer = { scheduleDto() }, failWith = IOException("no route")),
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )

        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(offline.loadSchedule("2026-09-07", "UTC", emptyList())),
        )
    }

    @Test
    fun `never serves a local day for one the backend refused`() = runTest {
        // A refusal is the backend's own answer and is reported as it is, whatever is held locally
        // (`BR-007`, `BR-042`).
        val workingSet = InMemoryWorkingSetStore()
        val backend = repository(
            api = FakeScheduleApi(answer = { selfScheduleDto() }),
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(backend.loadSchedule("2026-09-07", "UTC", emptyList()))

        val refused = repository(
            api = FakeScheduleApi(answer = { selfScheduleDto() }, failWith = httpFailure(403)),
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )

        assertEquals(
            CustomersFailureReason.FORBIDDEN,
            assertFailure(refused.loadSchedule("2026-09-07", "UTC", emptyList())),
        )
    }

    @Test
    fun `never serves one day's answer for another day`() = runTest {
        // The row is keyed by the local date: a day read before is not this day's answer
        // (`offline-first-architecture.md` §13.4).
        val workingSet = InMemoryWorkingSetStore()
        val backend = repository(
            api = FakeScheduleApi(answer = { selfScheduleDto() }),
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(backend.loadSchedule("2026-09-07", "UTC", emptyList()))

        val offline = repository(
            api = FakeScheduleApi(
                answer = { selfScheduleDto() },
                failWith = IOException("no route"),
            ),
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )

        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(offline.loadSchedule("2026-09-08", "UTC", emptyList())),
        )
    }

    @Test
    fun `never serves a local day to another subject`() = runTest {
        // The working set belongs to the session that read it (`offline-first-architecture.md` §10).
        val workingSet = InMemoryWorkingSetStore()
        val backend = repository(
            api = FakeScheduleApi(answer = { selfScheduleDto() }),
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(backend.loadSchedule("2026-09-07", "UTC", emptyList()))

        val anotherSubject = repository(
            api = FakeScheduleApi(
                answer = { selfScheduleDto() },
                failWith = IOException("no route"),
            ),
            sessionAuthenticator = FakeScheduleSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
            subject = FakeScheduleSubject(subjectId = "subject-2"),
        )

        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(anotherSubject.loadSchedule("2026-09-07", "UTC", emptyList())),
        )
    }
}

private fun repository(
    api: ScheduleApi,
    sessionAuthenticator: SessionAuthenticator,
    workingSet: WorkingSetStore = InMemoryWorkingSetStore(),
    subject: AuthenticatedSubject = FakeScheduleSubject(),
): ScheduleRepository = DefaultScheduleRepository(
    api = api,
    sessionAuthenticator = sessionAuthenticator,
    cache = ScheduleCache(
        workingSet = workingSet,
        json = Json,
        clock = Clock.systemUTC(),
    ),
    subject = subject,
)

/** The subject a test's working set is partitioned by. */
private class FakeScheduleSubject(
    private val subjectId: String? = "subject-1",
) : AuthenticatedSubject {
    override fun current(): String? = subjectId
}

/** The day the repository reported, asserted to be a success. */
private fun assertSuccess(result: ScheduleResult): ScheduleResult.Success =
    when (result) {
        is ScheduleResult.Success -> result
        is ScheduleResult.Failure ->
            throw AssertionError("expected the schedule, got ${result.reason}")
    }

/** The reason a read failed, asserted to be a failure. */
private fun assertFailure(result: ScheduleResult): CustomersFailureReason =
    when (result) {
        is ScheduleResult.Failure -> result.reason
        is ScheduleResult.Success -> throw AssertionError("expected a failure, got a schedule")
    }

private fun httpFailure(status: Int) = HttpException(
    Response.error<ScheduleDto>(
        status,
        """{"statusCode":$status,"code":"UNKNOWN","message":"ignored"}"""
            .toResponseBody("application/json".toMediaType()),
    ),
)

private fun scheduleDto(): ScheduleDto = ScheduleDto(
    generatedAt = "2026-09-07T12:00:00.000Z",
    day = ScheduleDayDto(
        localDate = "2026-09-07",
        timeZone = "America/Toronto",
        start = "2026-09-07T04:00:00.000Z",
        end = "2026-09-08T04:00:00.000Z",
    ),
    scope = ScheduleScopeDto(kind = "ORGANIZATION", membershipId = "member-1"),
    technicians = listOf(ScheduleTechnicianDto(membershipId = "member-1", name = "Mike Johnson")),
    visits = listOf(scheduleVisitDto(visitId = "visit-1", status = "SCHEDULED")),
    unassigned = ScheduleUnassignedDto(
        total = 3,
        items = listOf(scheduleVisitDto(visitId = "visit-9", status = "DRAFT", scheduled = false)),
    ),
)

private fun scheduleVisitDto(
    visitId: String,
    status: String,
    scheduled: Boolean = true,
): ScheduleVisitDto = ScheduleVisitDto(
    visitId = visitId,
    visitStatus = status,
    scheduledStart = if (scheduled) "2026-09-07T13:00:00.000Z" else null,
    scheduledEnd = if (scheduled) "2026-09-07T14:00:00.000Z" else null,
    jobId = "job-1",
    jobNumber = 1042,
    jobTitle = "Furnace repair",
    customerId = "customer-1",
    customerName = "ABC Property Management",
    address = ScheduleAddressDto(addressLine1 = "987 Cedar Lane", city = "Montreal"),
    technicians = listOf(
        ScheduleTechnicianDto(
            membershipId = "member-1",
            name = "Mike Johnson",
            roleCode = "LEAD",
        ),
    ),
    overdue = scheduled,
)

/**
 * The backend's answer for a technician's own day: one Visit, no filter options and no unassigned
 * lane, because a field scope has neither (`BR-009`).
 */
private fun selfScheduleDto(): ScheduleDto = scheduleDto().copy(
    scope = ScheduleScopeDto(kind = "SELF", membershipId = "member-7"),
    technicians = emptyList(),
    unassigned = null,
)

/** A [ScheduleApi] that answers with what the test scripted, or fails the call. */
private class FakeScheduleApi(
    private val answer: suspend () -> ScheduleDto,
    private val failWith: Throwable? = null,
    private val failFirstWith: Throwable? = null,
) : ScheduleApi {

    var calls = 0
    var lastAuthorization: String? = null
    var lastDate: String? = null
    var lastTimeZone: String? = null
    var lastMembershipIds: List<String>? = null

    override suspend fun schedule(
        authorization: String,
        date: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleDto {
        calls += 1
        if (calls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        lastAuthorization = authorization
        lastDate = date
        lastTimeZone = timeZone
        lastMembershipIds = membershipIds
        if (failWith != null) {
            throw failWith
        }
        return answer()
    }
}

/** A [SessionAuthenticator] that hands out a fixed token and returns the renewal it was given. */
private class FakeScheduleSessionAuthenticator(
    private val accessToken: String?,
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal
}

