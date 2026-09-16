package com.servora.android.data.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.InMemoryWorkingSetStore
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.VisitStatus
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * How [DefaultJobDetailsRepository] reads `GET /jobs/:id` and classifies its answers.
 *
 * The backend decides which Visit represents the Job and who is assigned to it (`BR-001`, `BR-081`);
 * these tests cover only that the read carries the session, that the crew arrives Lead first, that a
 * refused access token is renewed once and retried, that each HTTP answer is reported faithfully, and
 * that a response this build cannot interpret is reported rather than shown partially (`BR-042`).
 */
class JobDetailsRepositoryTest {

    @Test
    fun `sends the session and maps the payload with the represented Visit and its crew`() = runTest {
        val api = FakeJobDetailsApi(answer = { jobDetailsDto() })
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals(JOB_ID, api.lastJobId)
        assertEquals(1042, details.jobNumber)
        assertEquals("Furnace repair", details.title)
        assertEquals(JobStatus.SCHEDULED, details.status)
        assertEquals("Martha Reynolds", details.customerName)
        assertEquals("987 Cedar Lane", details.address?.addressLine1)
        assertEquals(VisitStatus.SCHEDULED, details.selectedVisit?.status)
        assertEquals("2026-09-14T13:00:00.000Z", details.selectedVisit?.scheduledStart)
        assertEquals(
            listOf("Mike Lead", "Sarah Moreau", null),
            details.technicians.map { it.name },
        )
        assertEquals(
            listOf(AssignmentRole.LEAD, AssignmentRole.TECHNICIAN, AssignmentRole.TECHNICIAN),
            details.technicians.map { it.role },
        )
        assertTrue(details.technicians[0].isLead)
    }

    @Test
    fun `maps a Job with no represented Visit and no crew`() = runTest {
        val repository = repository(
            FakeJobDetailsApi(
                answer = {
                    jobDetailsDto().copy(
                        selectedVisit = null,
                        technicians = emptyList(),
                        propertyId = null,
                        address = null,
                    )
                },
            ),
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        assertNull(details.selectedVisit)
        assertTrue(details.technicians.isEmpty())
        assertNull(details.address)
        assertNull(details.description)
    }

    @Test
    fun `reports no session as unauthenticated without calling the backend`() = runTest {
        val api = FakeJobDetailsApi(answer = { jobDetailsDto() })
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = null),
        )

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(repository.loadJobDetails(JOB_ID)),
        )
        assertNull(api.lastAuthorization)
    }

    @Test
    fun `renews the session once and retries when the backend refuses the access token`() = runTest {
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            failFirstWith = httpFailure(401),
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(
                accessToken = "stale-token",
                renewal = SessionRenewal.Renewed(accessToken = "renewed-token"),
            ),
        )

        assertSuccess(repository.loadJobDetails(JOB_ID))

        assertEquals(2, api.calls)
        assertEquals("Bearer renewed-token", api.lastAuthorization)
    }

    @Test
    fun `reports a refused renewal as unauthenticated`() = runTest {
        val repository = repository(
            FakeJobDetailsApi(answer = { throw httpFailure(401) }),
            FakeSessionAuthenticator(
                accessToken = "stale-token",
                renewal = SessionRenewal.Rejected,
            ),
        )

        assertEquals(
            CustomersFailureReason.UNAUTHENTICATED,
            assertFailure(repository.loadJobDetails(JOB_ID)),
        )
    }

    @Test
    fun `reports a Job the caller may not read as forbidden`() = runTest {
        assertEquals(
            CustomersFailureReason.FORBIDDEN,
            assertFailure(failingWith(httpFailure(403)).loadJobDetails(JOB_ID)),
        )
    }

    @Test
    fun `reports a Job that does not exist as not found`() = runTest {
        assertEquals(
            CustomersFailureReason.NOT_FOUND,
            assertFailure(failingWith(httpFailure(404)).loadJobDetails(JOB_ID)),
        )
    }

    @Test
    fun `reports an unreachable backend as a network failure`() = runTest {
        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(failingWith(IOException("offline")).loadJobDetails(JOB_ID)),
        )
    }

    @Test
    fun `reports a backend failure as a server failure`() = runTest {
        assertEquals(
            CustomersFailureReason.SERVER,
            assertFailure(failingWith(httpFailure(503)).loadJobDetails(JOB_ID)),
        )
    }

    @Test
    fun `reports an unreadable payload as unexpected`() = runTest {
        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(
                failingWith(SerializationException("not the contract")).loadJobDetails(JOB_ID),
            ),
        )
    }

    @Test
    fun `fails the whole read when a code this build does not know arrives`() = runTest {
        // A Job status, Visit status or assignment role added by a newer backend must not be dropped
        // silently: the screen would then describe a Job that is not the one the backend reported, or
        // would have to guess whether a technician is the Lead (`BR-042`, `BR-068`).
        val unknownJobStatus = repository(
            FakeJobDetailsApi(answer = { jobDetailsDto().copy(status = "ON_HOLD") }),
            FakeSessionAuthenticator(accessToken = "access-token"),
        )
        val unknownVisitStatus = repository(
            FakeJobDetailsApi(
                answer = {
                    jobDetailsDto().copy(selectedVisit = visitDto().copy(status = "RESCHEDULED"))
                },
            ),
            FakeSessionAuthenticator(accessToken = "access-token"),
        )
        val unknownRole = repository(
            FakeJobDetailsApi(
                answer = {
                    jobDetailsDto().copy(
                        technicians = listOf(technicianDto().copy(roleCode = "HELPER")),
                    )
                },
            ),
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(unknownJobStatus.loadJobDetails(JOB_ID)),
        )
        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(unknownVisitStatus.loadJobDetails(JOB_ID)),
        )
        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(unknownRole.loadJobDetails(JOB_ID)),
        )
    }

    @Test
    fun `sends a status change with the version the screen last saw`() = runTest {
        val api = FakeJobDetailsApi(answer = { jobDetailsDto().copy(status = "IN_PROGRESS") })
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val result = repository.changeJobStatus(
            jobId = JOB_ID,
            status = JobStatus.IN_PROGRESS,
            note = "  ",
            expectedVersion = 4,
        )

        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals(
            ChangeJobStatusRequestDto(status = "IN_PROGRESS", note = null, expectedVersion = 4),
            api.lastStatusRequest,
        )
        val details = assertActionSuccess(result)
        assertEquals(JobStatus.IN_PROGRESS, details.status)
    }

    @Test
    fun `sends a reschedule with the window and the conflict decision`() = runTest {
        val api = FakeJobDetailsApi(answer = { jobDetailsDto() })
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        repository.rescheduleVisit(
            jobId = JOB_ID,
            visitId = "visit-1",
            scheduledStart = Instant.parse("2026-09-15T13:00:00Z"),
            scheduledEnd = Instant.parse("2026-09-15T15:00:00Z"),
            reason = null,
            confirmConflicts = true,
            expectedVersion = 2,
        )

        val request = api.lastRescheduleRequest
        assertEquals("2026-09-15T13:00:00Z", request?.scheduledStart)
        assertEquals("2026-09-15T15:00:00Z", request?.scheduledEnd)
        assertTrue(request?.confirmConflicts == true)
        assertEquals(2, request?.expectedVersion)
    }

    @Test
    fun `states the whole crew with the roles the assignment names`() = runTest {
        val api = FakeJobDetailsApi(answer = { jobDetailsDto() })
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        repository.assignVisitTechnicians(
            jobId = JOB_ID,
            visitId = "visit-1",
            assignments = listOf(
                TechnicianAssignment("member-2", AssignmentRole.LEAD),
                TechnicianAssignment("member-1", AssignmentRole.TECHNICIAN),
            ),
            confirmConflicts = false,
            expectedVersion = 2,
        )

        assertEquals(
            listOf("member-2:LEAD", "member-1:TECHNICIAN"),
            api.lastAssignmentRequest?.technicians?.map { "${it.membershipId}:${it.roleCode}" },
        )
        assertFalse(api.lastAssignmentRequest?.confirmConflicts == true)
    }

    @Test
    fun `adds a Visit note and maps the refreshed activity`() = runTest {
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            activityAnswer = {
                jobActivityDto(jobActivityEventDto(id = "note-1", body = "Replaced the filter."))
            },
        )
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val events = when (
            val result = repository.addVisitNote(
                jobId = JOB_ID,
                visitId = "visit-1",
                body = "  Replaced the filter.  ",
            )
        ) {
            is VisitNoteResult.Success -> result.events
            is VisitNoteResult.Failure ->
                throw AssertionError("expected activity, got ${result.reason}")
        }

        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals(AddVisitNoteRequestDto(body = "Replaced the filter."), api.lastNoteRequest)
        assertEquals(listOf("note-1"), events.map { it.id })
    }

    @Test
    fun `reports the conflicts the API refused a change for, so the user can be shown them`() = runTest {
        // `BR-070` keeps a conflict a warning rather than a prohibition, so the refusal is not a
        // failure: the same action is resent once the user accepts what overlaps.
        val conflict = """
            {
              "statusCode": 409,
              "code": "SCHEDULE_CONFLICT",
              "message": "The change conflicts with another visit.",
              "details": {
                "conflicts": [
                  {
                    "visitId": "visit-9",
                    "jobId": "job-9",
                    "jobNumber": 1043,
                    "technicianMembershipId": "member-1",
                    "technicianName": "Mike Lead",
                    "scheduledStart": "2026-09-15T13:00:00.000Z",
                    "scheduledEnd": "2026-09-15T15:00:00.000Z"
                  }
                ]
              }
            }
        """.trimIndent()
        val repository = repository(
            FakeJobDetailsApi(
                answer = { jobDetailsDto() },
                failFirstWith = httpFailure(409, conflict),
            ),
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val result = repository.changeJobStatus(JOB_ID, JobStatus.IN_PROGRESS, null, 4)

        val conflicts = when (result) {
            is JobActionResult.Conflicts -> result.conflicts
            else -> throw AssertionError("expected the conflicts, got $result")
        }
        assertEquals(1, conflicts.size)
        assertEquals("visit-9", conflicts[0].visitId)
        assertEquals(1043, conflicts[0].jobNumber)
        assertEquals("Mike Lead", conflicts[0].technicianName)
    }

    @Test
    fun `classifies the API refusals by their stable error code`() = runTest {
        suspend fun failureFor(code: String, status: Int = 409): JobActionFailure {
            val body = """{"statusCode":$status,"code":"$code","message":"ignored"}"""
            val result = repository(
                FakeJobDetailsApi(
                    answer = { jobDetailsDto() },
                    failFirstWith = httpFailure(status, body),
                ),
                FakeSessionAuthenticator(accessToken = "access-token"),
            ).changeJobStatus(JOB_ID, JobStatus.IN_PROGRESS, null, 4)
            return when (result) {
                is JobActionResult.Failure -> result.reason
                else -> throw AssertionError("expected a failure, got $result")
            }
        }

        assertEquals(
            JobActionFailure.JOB_TRANSITION_NOT_ALLOWED,
            failureFor("JOB_STATUS_TRANSITION_NOT_ALLOWED"),
        )
        assertEquals(
            JobActionFailure.JOB_CANCELLATION_UNAVAILABLE,
            failureFor("JOB_CANCELLATION_UNAVAILABLE"),
        )
        assertEquals(
            JobActionFailure.JOB_REVIEW_CONDITION_NOT_MET,
            failureFor("JOB_REVIEW_CONDITION_NOT_MET"),
        )
        assertEquals(
            JobActionFailure.JOB_COMPLETION_BLOCKED,
            failureFor("JOB_COMPLETION_BLOCKED"),
        )
        assertEquals(
            JobActionFailure.VISIT_NOT_RESCHEDULABLE,
            failureFor("VISIT_NOT_RESCHEDULABLE"),
        )
        assertEquals(
            JobActionFailure.TECHNICIANS_NOT_ASSIGNABLE,
            failureFor("TECHNICIANS_NOT_ASSIGNABLE", status = 422),
        )
        assertEquals(JobActionFailure.VERSION_CONFLICT, failureFor("VERSION_CONFLICT"))
        // A refusal this build does not name is still classified by its status, never reported as a
        // success (`dev.md` §7).
        assertEquals(JobActionFailure.FORBIDDEN, failureFor("SOMETHING_NEW", status = 403))
    }

    @Test
    fun `reads the technicians a crew may be chosen from`() = runTest {
        val api = FakeJobDetailsApi(answer = { jobDetailsDto() })
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val result = repository.loadAssignableTechnicians()

        val technicians = when (result) {
            is AssignableTechniciansResult.Success -> result.technicians
            is AssignableTechniciansResult.Failure ->
                throw AssertionError("expected the technicians, got ${result.reason}")
        }
        assertEquals(listOf("Mike Lead", null), technicians.map { it.name })
        assertEquals("Bearer access-token", api.lastAuthorization)
    }

    @Test
    fun `renews the session once when an action is refused with 401`() = runTest {
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            failFirstWith = httpFailure(401),
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(
                accessToken = "expired",
                renewal = SessionRenewal.Renewed("renewed-access-token"),
            ),
        )

        val result = repository.changeJobStatus(JOB_ID, JobStatus.IN_PROGRESS, null, 4)

        assertEquals(2, api.calls)
        assertEquals("Bearer renewed-access-token", api.lastAuthorization)
        assertActionSuccess(result)
    }

    /**
     * What the local copy is for: a read that cannot reach the API is answered from the last Job the
     * backend reported, so the evidence a Job holds and the Activity around it stay readable without
     * connectivity (`D5`, `BR-013`, `§2`).
     */
    @Test
    fun `serves the last reported Job when the backend cannot be reached`() = runTest {
        val workingSet = InMemoryWorkingSetStore()
        var reachable = true
        val repository = repository(
            api = FakeJobDetailsApi(
                answer = {
                    if (reachable) {
                        jobDetailsDto()
                    } else {
                        throw IOException("offline")
                    }
                },
            ),
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )

        // The backend's own answer is what fills the working set.
        assertEquals(1042, assertSuccess(repository.loadJobDetails(JOB_ID)).jobNumber)
        reachable = false

        val result = repository.loadJobDetails(JOB_ID)

        val success = result as JobDetailsResult.Success
        assertEquals(ReadSource.WORKING_SET, success.source)
        // The local answer runs the same mapper the online one does, so it describes the same Job —
        // including who is assigned and who is the Lead (`BR-041`, `BR-068`).
        assertEquals(1042, success.details.jobNumber)
        assertEquals("Furnace repair", success.details.title)
        assertEquals(
            listOf("Mike Lead", "Sarah Moreau", null),
            success.details.technicians.map { it.name },
        )
    }

    @Test
    fun `serves the last reported Job when the backend fails the read too`() = runTest {
        // A `5xx` did not deliver the Job, which is the offline standard's own classification of a
        // failure that could not reach the backend (`§13`).
        val workingSet = InMemoryWorkingSetStore()
        var reachable = true
        val repository = repository(
            api = FakeJobDetailsApi(
                answer = {
                    if (reachable) {
                        jobDetailsDto()
                    } else {
                        throw httpFailure(503)
                    }
                },
            ),
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(repository.loadJobDetails(JOB_ID))
        reachable = false

        assertEquals(
            ReadSource.WORKING_SET,
            (repository.loadJobDetails(JOB_ID) as JobDetailsResult.Success).source,
        )
    }

    @Test
    fun `never replaces a refusal with the last reported Job`() = runTest {
        // A refusal is the backend's own answer, so a copy held on the device must never mask it
        // (`BR-007`, `BR-042`, `§13`).
        val workingSet = InMemoryWorkingSetStore()
        var refusal: Throwable? = null
        val repository = repository(
            api = FakeJobDetailsApi(
                answer = { refusal?.let { throw it } ?: jobDetailsDto() },
            ),
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(repository.loadJobDetails(JOB_ID))

        for (failure in listOf(httpFailure(403), httpFailure(404), httpFailure(422))) {
            refusal = failure

            assertEquals(
                "A refusal must not be answered from the device",
                failure.httpReason(),
                assertFailure(repository.loadJobDetails(JOB_ID)),
            )
        }
    }

    @Test
    fun `reports the read failure when nothing has been reported yet`() = runTest {
        // Nothing was ever read, so there is no answer to serve: the failure is reported rather than a
        // Job invented out of nothing (`BR-042`).
        val repository = repository(
            api = FakeJobDetailsApi(answer = { throw IOException("offline") }),
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
        )

        assertEquals(CustomersFailureReason.NETWORK, assertFailure(repository.loadJobDetails(JOB_ID)))
    }

    @Test
    fun `replaces the reported Job rather than merging a newer answer into it`() = runTest {
        val workingSet = InMemoryWorkingSetStore()
        var title = "Furnace repair"
        var reachable = true
        val repository = repository(
            api = FakeJobDetailsApi(
                answer = {
                    if (reachable) {
                        jobDetailsDto().copy(title = title)
                    } else {
                        throw IOException("offline")
                    }
                },
            ),
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(repository.loadJobDetails(JOB_ID))

        title = "Furnace replacement"
        assertSuccess(repository.loadJobDetails(JOB_ID))
        reachable = false

        val offline = repository.loadJobDetails(JOB_ID) as JobDetailsResult.Success
        assertEquals("Furnace replacement", offline.details.title)
        // One row per Job per subject: the newest answer replaced the earlier one (`§2`, `§10`).
        assertEquals(1, workingSet.stored.size)
        assertEquals(SUBJECT_ID, workingSet.stored.single().subjectId)
    }

    @Test
    fun `keeps another session's reported Job out of this session's read`() = runTest {
        val workingSet = InMemoryWorkingSetStore()
        val reader = repository(
            api = FakeJobDetailsApi(answer = { jobDetailsDto() }),
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertSuccess(reader.loadJobDetails(JOB_ID))

        val otherSession = repository(
            api = FakeJobDetailsApi(answer = { throw IOException("offline") }),
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
            subjectId = "user-2",
        )

        assertEquals(
            CustomersFailureReason.NETWORK,
            assertFailure(otherSession.loadJobDetails(JOB_ID)),
        )
    }

    @Test
    fun `serves the last reported activity when the backend cannot be reached`() = runTest {
        val workingSet = InMemoryWorkingSetStore()
        var reachable = true
        val repository = repository(
            api = FakeJobDetailsApi(
                answer = { jobDetailsDto() },
                activityAnswer = {
                    if (reachable) {
                        jobActivityDto(
                            jobActivityEventDto(
                                id = "event-1",
                                kind = "VISIT_NOTE_ADDED",
                                body = "Fixed.",
                            ),
                        )
                    } else {
                        throw IOException("offline")
                    }
                },
            ),
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertActivitySuccess(repository.loadJobActivity(JOB_ID))
        reachable = false

        val result = repository.loadJobActivity(JOB_ID)

        val success = result as JobActivityResult.Success
        assertEquals(ReadSource.WORKING_SET, success.source)
        // Which photos the Job holds, with their phase, note and time, is what `D5` requires to be
        // readable offline (`BR-013`, `BR-080`).
        assertEquals(listOf("event-1"), success.events.map { it.id })
        assertEquals("Fixed.", success.events.single().body)
    }

    @Test
    fun `never replaces a refused activity with the last reported one`() = runTest {
        val workingSet = InMemoryWorkingSetStore()
        var refusal: Throwable? = null
        val repository = repository(
            api = FakeJobDetailsApi(
                answer = { jobDetailsDto() },
                activityAnswer = {
                    refusal?.let { throw it }
                        ?: jobActivityDto(
                            jobActivityEventDto(id = "event-1", kind = "VISIT_NOTE_ADDED"),
                        )
                },
            ),
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        assertActivitySuccess(repository.loadJobActivity(JOB_ID))
        refusal = httpFailure(403)

        val result = repository.loadJobActivity(JOB_ID)

        assertEquals(
            CustomersFailureReason.FORBIDDEN,
            (result as JobActivityResult.Failure).reason,
        )
    }

    private fun assertActionSuccess(result: JobActionResult) = when (result) {
        is JobActionResult.Success -> result.details
        else -> throw AssertionError("expected the Job, got $result")
    }

    private fun assertActivitySuccess(result: JobActivityResult) = when (result) {
        is JobActivityResult.Success -> result.events
        is JobActivityResult.Failure ->
            throw AssertionError("expected activity, got ${result.reason}")
    }

    /** The reason a refusal is classified as, so the offline test states the answer it expects. */
    private fun Throwable.httpReason(): CustomersFailureReason =
        when ((this as HttpException).code()) {
            403 -> CustomersFailureReason.FORBIDDEN
            404 -> CustomersFailureReason.NOT_FOUND
            else -> CustomersFailureReason.VALIDATION
        }

    private fun failingWith(failure: Throwable) =
        repository(
            FakeJobDetailsApi(answer = { throw failure }),
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

    private fun assertSuccess(result: JobDetailsResult) = when (result) {
        is JobDetailsResult.Success -> result.details
        is JobDetailsResult.Failure ->
            throw AssertionError("expected the Job, got ${result.reason}")
    }

    private fun assertFailure(result: JobDetailsResult): CustomersFailureReason =
        when (result) {
            is JobDetailsResult.Failure -> result.reason
            is JobDetailsResult.Success -> throw AssertionError("expected a failure, got a Job")
        }

    @Test
    fun `sends the session and maps the activity with visit sequences and kinds`() = runTest {
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            activityAnswer = {
                jobActivityDto(
                    jobActivityEventDto(
                        id = "event-1",
                        kind = "VISIT_NOTE_ADDED",
                        visitSequence = 2,
                        body = "Fixed.",
                    ),
                    jobActivityEventDto(
                        id = "event-2",
                        kind = "JOB_STATUS_CHANGED",
                        visitSequence = null,
                        fromStatus = "NEW",
                        toStatus = "SCHEDULED",
                        body = null,
                    ),
                )
            },
        )
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val events = when (val result = repository.loadJobActivity(JOB_ID)) {
            is JobActivityResult.Success -> result.events
            is JobActivityResult.Failure ->
                throw AssertionError("expected activity, got ${result.reason}")
        }

        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals(JOB_ID, api.lastJobId)
        assertEquals(2, events.size)
        assertEquals(JobActivityKind.VISIT_NOTE_ADDED, events[0].kind)
        assertEquals(2, events[0].visitSequence)
        assertEquals("Fixed.", events[0].body)
        assertEquals(JobActivityKind.JOB_STATUS_CHANGED, events[1].kind)
        assertNull(events[1].visitSequence)
    }

    @Test
    fun `drops an event whose kind this build does not know`() = runTest {
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            activityAnswer = {
                jobActivityDto(
                    jobActivityEventDto(id = "event-1", kind = "MYSTERY_KIND"),
                    jobActivityEventDto(id = "event-2", kind = "VISIT_NOTE_ADDED", body = "Kept."),
                )
            },
        )
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val events = when (val result = repository.loadJobActivity(JOB_ID)) {
            is JobActivityResult.Success -> result.events
            is JobActivityResult.Failure ->
                throw AssertionError("expected activity, got ${result.reason}")
        }

        // An event this build cannot name is dropped rather than drawn as something it is not; the
        // rest of the timeline stays (`BR-041`, `BR-042`).
        assertEquals(listOf("event-2"), events.map { it.id })
    }

    private fun httpFailure(
        status: Int,
        body: String = """{"statusCode":$status,"code":"UNKNOWN","message":"ignored"}""",
    ) = HttpException(
        Response.error<JobDetailsDto>(
            status,
            body.toResponseBody("application/json".toMediaType()),
        ),
    )

    private companion object {
        const val JOB_ID = "job-1"
    }
}

/**
 * A payload shaped exactly like `GET /jobs/:id` answers.
 *
 * It carries a crew of three so a test also covers an assigned technician whose member has no profile
 * yet (`name = null`).
 */
internal fun jobDetailsDto(): JobDetailsDto =
    JobDetailsDto(
        id = "job-1",
        jobNumber = 1042,
        title = "Furnace repair",
        description = null,
        status = "SCHEDULED",
        allowedStatusTransitions = listOf("IN_PROGRESS"),
        version = 4,
        customerId = "customer-1",
        customerName = "Martha Reynolds",
        propertyId = "property-1",
        address = JobDetailsAddressDto(
            propertyName = "Cedar Lane Building",
            addressLine1 = "987 Cedar Lane",
            city = "Montreal",
            province = "QC",
            postalCode = "H3A 2T6",
            country = "Canada",
        ),
        selectedVisit = visitDto(),
        technicians = listOf(
            technicianDto(),
            technicianDto(
                membershipId = "member-2",
                name = "Sarah Moreau",
                roleCode = "TECHNICIAN",
            ),
            technicianDto(membershipId = "member-3", name = null, roleCode = "TECHNICIAN"),
        ),
    )

internal fun visitDto() = JobDetailsVisitDto(
    id = "visit-1",
    status = "SCHEDULED",
    scheduledStart = "2026-09-14T13:00:00.000Z",
    scheduledEnd = "2026-09-14T15:00:00.000Z",
    version = 2,
    reschedulable = true,
)

internal fun technicianDto(
    membershipId: String = "member-1",
    name: String? = "Mike Lead",
    roleCode: String = "LEAD",
) = JobDetailsTechnicianDto(
    membershipId = membershipId,
    name = name,
    roleCode = roleCode,
)

internal fun jobActivityEventDto(
    id: String = "event-1",
    kind: String = "VISIT_NOTE_ADDED",
    recordedAt: String = "2026-09-14T14:14:00.000Z",
    actorName: String? = "John Smith",
    visitSequence: Int? = 2,
    fromStatus: String? = null,
    toStatus: String? = null,
    technicianName: String? = null,
    roleCode: String? = null,
    previousRoleCode: String? = null,
    outcomeCode: String? = null,
    outcomeSummary: String? = null,
    body: String? = "Found a damaged capacitor.",
) = JobActivityEventDto(
    id = id,
    kind = kind,
    recordedAt = recordedAt,
    actorName = actorName,
    visitSequence = visitSequence,
    fromStatus = fromStatus,
    toStatus = toStatus,
    technicianName = technicianName,
    roleCode = roleCode,
    previousRoleCode = previousRoleCode,
    outcomeCode = outcomeCode,
    outcomeSummary = outcomeSummary,
    body = body,
)

internal fun jobActivityDto(vararg events: JobActivityEventDto) =
    JobActivityDto(jobId = "job-1", events = events.toList())

private class FakeJobDetailsApi(
    private val answer: () -> JobDetailsDto,
    private val failFirstWith: Throwable? = null,
    private val activityAnswer: () -> JobActivityDto = { JobActivityDto(jobId = "") },
) : JobDetailsApi {

    var calls = 0
        private set

    var lastAuthorization: String? = null
        private set

    var lastJobId: String? = null
        private set

    var lastStatusRequest: ChangeJobStatusRequestDto? = null
        private set

    var lastRescheduleRequest: RescheduleVisitRequestDto? = null
        private set

    var lastAssignmentRequest: AssignVisitTechniciansRequestDto? = null
        private set

    var lastNoteRequest: AddVisitNoteRequestDto? = null
        private set

    var assignableCalls = 0
        private set

    var activityCalls = 0
        private set

    override suspend fun jobDetails(
        authorization: String,
        jobId: String,
    ): JobDetailsDto {
        calls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        if (calls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        return answer()
    }

    override suspend fun jobActivity(
        authorization: String,
        jobId: String,
    ): JobActivityDto {
        activityCalls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        if (activityCalls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        return activityAnswer()
    }

    override suspend fun changeJobStatus(
        authorization: String,
        jobId: String,
        request: ChangeJobStatusRequestDto,
    ): JobDetailsDto {
        calls += 1
        lastAuthorization = authorization
        lastStatusRequest = request
        if (calls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        return answer()
    }

    override suspend fun rescheduleVisit(
        authorization: String,
        jobId: String,
        visitId: String,
        request: RescheduleVisitRequestDto,
    ): JobDetailsDto {
        calls += 1
        lastAuthorization = authorization
        lastRescheduleRequest = request
        if (calls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        return answer()
    }

    override suspend fun assignVisitTechnicians(
        authorization: String,
        jobId: String,
        visitId: String,
        request: AssignVisitTechniciansRequestDto,
    ): JobDetailsDto {
        calls += 1
        lastAuthorization = authorization
        lastAssignmentRequest = request
        if (calls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        return answer()
    }

    override suspend fun addVisitNote(
        authorization: String,
        jobId: String,
        visitId: String,
        request: AddVisitNoteRequestDto,
    ): JobActivityDto {
        activityCalls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        lastNoteRequest = request
        if (activityCalls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        return activityAnswer()
    }

    override suspend fun assignableTechnicians(
        authorization: String,
    ): List<AssignableTechnicianDto> {
        assignableCalls += 1
        lastAuthorization = authorization
        if (calls == 0 && failFirstWith != null) {
            throw failFirstWith
        }
        return listOf(
            AssignableTechnicianDto(membershipId = "member-1", name = "Mike Lead"),
            AssignableTechnicianDto(membershipId = "member-2", name = null),
        )
    }

    /**
     * The photo route (`BR-015`). These tests are about the Job read and its actions, so the photo
     * endpoints answer the smallest honest thing rather than being exercised here; what the upload
     * sends is asserted by `JobPhotoUploadHandlerTest` (`qa.md` §6.1).
     */
    override suspend fun addJobPhoto(
        authorization: String,
        jobId: String,
        clientOperationId: RequestBody,
        phase: RequestBody,
        note: RequestBody?,
        capturedAt: RequestBody?,
        file: MultipartBody.Part,
    ): JobActivityDto {
        lastAuthorization = authorization
        lastJobId = jobId
        return JobActivityDto(jobId = jobId, events = emptyList())
    }

    override suspend fun jobPhotoContent(
        authorization: String,
        jobId: String,
        photoId: String,
    ): ResponseBody {
        lastAuthorization = authorization
        lastJobId = jobId
        return "".toResponseBody("image/jpeg".toMediaType())
    }
}

private class FakeSessionAuthenticator(
    private val accessToken: String?,
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal
}

/** The repository these tests exercise, with the in-memory working set and its JSON reader. */
private fun repository(
    api: JobDetailsApi,
    sessionAuthenticator: SessionAuthenticator,
    workingSet: InMemoryWorkingSetStore = InMemoryWorkingSetStore(),
    subjectId: String? = SUBJECT_ID,
) = DefaultJobDetailsRepository(
    api = api,
    sessionAuthenticator = sessionAuthenticator,
    evidence = JobEvidenceCache(
        workingSet = workingSet,
        json = Json { ignoreUnknownKeys = true },
        clock = TEST_CLOCK,
    ),
    subject = FakeAuthenticatedSubject(subjectId),
    json = Json { ignoreUnknownKeys = true },
)

/** The subject the working set's rows are filed under; a second one stands for another session. */
private const val SUBJECT_ID = "user-1"
