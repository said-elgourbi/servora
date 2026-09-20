package com.servora.android.data.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.InMemoryOutboxStore
import com.servora.android.data.offline.InMemoryWorkingSetStore
import com.servora.android.data.offline.OutboxReplayEngine
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobContactPerson
import com.servora.android.domain.model.JobCustomerContact
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.VisitOutcome
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
        // This payload carries no customer contact block, which is exactly what the API answers a
        // session it does not admit to the Customer (`BR-092`). The client maps what it was given and
        // decides no authorization question of its own (`BR-001`, `BR-007`).
        assertNull(details.customerContactDetails)
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
        // A payload that carries no `visits` — an answer predating the field, which is what a
        // working-set row written by an earlier build holds — is still readable, with the Job's Visits
        // reported as none rather than failing the whole read (`BR-042`,
        // `offline-first-architecture.md` §10).
        assertTrue(details.visits.isEmpty())
    }

    @Test
    fun `maps the Job's visits with the status, schedule and crew each one carries`() = runTest {
        // A Job with two Visits: the one it is represented by, and the earlier one it holds (`BR-047`,
        // `BR-071`). They carry different schedules and different crews, so a mapping that mixed them up
        // could not pass.
        val api = FakeJobDetailsApi(
            answer = {
                jobDetailsDto().copy(
                    visits = listOf(
                        listedVisitDto(
                            id = "visit-0",
                            sequence = 1,
                            status = "COMPLETED",
                            // The Visit's own **current** outcome travels on the Visit, so a group can
                            // state what the field attempt resulted in (`BR-077`, `BR-078`).
                            outcomeCode = "RESOLVED",
                            scheduledStart = "2026-09-07T13:00:00.000Z",
                            scheduledEnd = "2026-09-07T15:00:00.000Z",
                            version = 7,
                            technicians = listOf(
                                technicianDto(
                                    membershipId = "member-9",
                                    name = "Dave Past",
                                    roleCode = "LEAD",
                                ),
                            ),
                        ),
                        listedVisitDto(
                            id = "visit-1",
                            sequence = 2,
                            status = "SCHEDULED",
                            scheduledStart = "2026-09-14T13:00:00.000Z",
                            scheduledEnd = "2026-09-14T15:00:00.000Z",
                            version = 2,
                            technicians = listOf(technicianDto()),
                        ),
                    ),
                )
            },
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        assertEquals(listOf("visit-0", "visit-1"), details.visits.map { it.id })
        assertEquals(listOf(1, 2), details.visits.map { it.sequence })
        assertEquals(
            listOf(VisitStatus.COMPLETED, VisitStatus.SCHEDULED),
            details.visits.map { it.status },
        )
        // The outcome is the Visit's own: the completed earlier Visit reports the outcome it holds, and
        // the scheduled one reports none rather than borrowing it (`BR-079`, `BR-042`).
        assertEquals(VisitOutcome.RESOLVED, details.visits[0].outcome)
        assertNull(details.visits[1].outcome)
        assertEquals("2026-09-07T13:00:00.000Z", details.visits[0].scheduledStart)
        assertEquals(7, details.visits[0].version)
        // The crew belongs to its own Visit: the earlier Visit's technician is never reported as the
        // represented Visit's, and the represented Visit is simply one of the Job's Visits (`BR-068`,
        // `BR-081`).
        assertEquals("Dave Past", details.visits[0].technicians.single().name)
        assertEquals(listOf("Mike Lead"), details.visits[1].technicians.map { it.name })
        assertEquals("visit-1", details.selectedVisit?.id)
    }

    @Test
    fun `reports a Visit outcome this build cannot name as no outcome`() = runTest {
        // An outcome code Servora's vocabulary does not have cannot be presented, so it is reported as
        // **no** outcome rather than as a different one, and the Job — its Visit, its schedule and its
        // crew with it — stays readable (`BR-042`). The entry that recorded the outcome is still in that
        // Visit's own activity (`BR-080`).
        val api = FakeJobDetailsApi(
            answer = {
                jobDetailsDto().copy(
                    visits = listOf(listedVisitDto(status = "COMPLETED", outcomeCode = "INVENTED")),
                )
            },
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        val listed = details.visits.single()
        assertNull(listed.outcome)
        assertEquals(VisitStatus.COMPLETED, listed.status)
        assertEquals("Mike Lead", listed.technicians.single().name)
    }

    @Test
    fun `maps a listed Visit with no schedule as one with no schedule`() = runTest {
        // A `DRAFT` Visit exists before it is scheduled (`BR-072`): the read lists it, and the client
        // reports no schedule for it rather than inventing one (`BR-042`).
        val api = FakeJobDetailsApi(
            answer = {
                jobDetailsDto().copy(
                    selectedVisit = null,
                    technicians = emptyList(),
                    visits = listOf(
                        listedVisitDto(
                            id = "visit-1",
                            sequence = 1,
                            status = "DRAFT",
                            scheduledStart = null,
                            scheduledEnd = null,
                            technicians = emptyList(),
                        ),
                    ),
                )
            },
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        val listed = details.visits.single()
        assertNull(listed.scheduledStart)
        assertNull(listed.scheduledEnd)
        assertEquals(VisitStatus.DRAFT, listed.status)
        assertTrue(listed.technicians.isEmpty())
    }

    @Test
    fun `maps the customer contact details the API included`() = runTest {
        val api = FakeJobDetailsApi(
            answer = {
                jobDetailsDto().copy(
                    customerContactDetails = JobDetailsCustomerContactDto(
                        email = "martha@example.com",
                        phone = "+15145550142",
                        notes = "Prefers mornings.",
                    ),
                )
            },
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        assertEquals(
            JobCustomerContact(
                phone = "+15145550142",
                email = "martha@example.com",
                notes = "Prefers mornings.",
            ),
            details.customerContactDetails,
        )
    }

    @Test
    fun `keeps a contact detail the office never recorded absent rather than blank`() = runTest {
        val api = FakeJobDetailsApi(
            answer = {
                jobDetailsDto().copy(
                    customerContactDetails = JobDetailsCustomerContactDto(
                        phone = "+15145550142",
                    ),
                )
            },
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        // A field the office never filled in stays null, which is a different thing from the whole block
        // being absent (`BR-092`): the screen leaves that row out rather than drawing an empty one.
        assertEquals(
            JobCustomerContact(phone = "+15145550142", email = null, notes = null),
            details.customerContactDetails,
        )
    }

    @Test
    fun `maps the customer contact persons the API included`() = runTest {
        val api = FakeJobDetailsApi(
            answer = {
                jobDetailsDto().copy(
                    customerContactDetails = JobDetailsCustomerContactDto(
                        phone = "+15145550142",
                        contacts = listOf(
                            JobDetailsContactPersonDto(
                                firstName = "John",
                                lastName = "Smith",
                                phone = "+15551234567",
                                email = "john@example.com",
                                isPrimary = true,
                            ),
                            // A contact whose email the office never recorded, which the screen leaves
                            // out rather than drawing blank (`BR-012`), and which is not the primary:
                            // `isPrimary` is the contact's own flag (`BR-095`).
                            JobDetailsContactPersonDto(
                                firstName = "Marie",
                                lastName = "Tremblay",
                                phone = null,
                            ),
                        ),
                    ),
                )
            },
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        // The read carries the people the technician may need to speak to, in the order the API
        // reported them — primary first (`BR-092`, `BR-095`) — and the client re-orders nothing.
        assertEquals(
            listOf(
                JobContactPerson(
                    firstName = "John",
                    lastName = "Smith",
                    phone = "+15551234567",
                    email = "john@example.com",
                    isPrimary = true,
                ),
                JobContactPerson(
                    firstName = "Marie",
                    lastName = "Tremblay",
                    phone = null,
                    email = null,
                ),
            ),
            details.customerContactDetails?.contacts,
        )
    }

    @Test
    fun `reads a contact person reported without the primary flag as not primary`() = runTest {
        // A working-set row written by a build that predates the field, or an API answer from before it,
        // holds no `isPrimary` (`BR-042`, `offline-first-architecture.md` §10). It is read as "no contact
        // person is flagged", which is the legal zero-primary state in which the Customer is its own
        // primary — the same thing the missing field describes (`BR-095`).
        val api = FakeJobDetailsApi(
            answer = {
                jobDetailsDto().copy(
                    customerContactDetails = JobDetailsCustomerContactDto(
                        phone = "+15145550142",
                        contacts = listOf(
                            JobDetailsContactPersonDto(
                                firstName = "John",
                                lastName = "Smith",
                            ),
                        ),
                    ),
                )
            },
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        assertEquals(
            listOf(
                JobContactPerson(
                    firstName = "John",
                    lastName = "Smith",
                    phone = null,
                    email = null,
                    isPrimary = false,
                ),
            ),
            details.customerContactDetails?.contacts,
        )
    }

    @Test
    fun `maps a customer with no contact persons to an empty list rather than failing`() = runTest {
        val api = FakeJobDetailsApi(
            answer = {
                jobDetailsDto().copy(
                    customerContactDetails = JobDetailsCustomerContactDto(
                        phone = "+15145550142",
                    ),
                )
            },
        )
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
        )

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        // A Customer with no contact person at all — an individual Customer is their own primary and
        // holds no contact row (`BR-095`) — is reported as none, not as a failed read (`BR-042`).
        assertEquals(emptyList<JobContactPerson>(), details.customerContactDetails?.contacts)
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
        // A Visit the read lists but this build cannot name fails the whole read, exactly as the Visit
        // it represents does: a history that silently left a field attempt out would report the Job
        // wrongly (`BR-042`, `BR-047`).
        val unknownListedVisitStatus = repository(
            FakeJobDetailsApi(
                answer = {
                    jobDetailsDto().copy(
                        visits = listOf(listedVisitDto(status = "RESCHEDULED")),
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
        assertEquals(
            CustomersFailureReason.UNEXPECTED,
            assertFailure(unknownListedVisitStatus.loadJobDetails(JOB_ID)),
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
            val result = repository.addVisitNoteRequest(
                VisitNote(
                    jobId = JOB_ID,
                    visitId = "visit-1",
                    body = "  Replaced the filter.  ",
                    operationId = "11111111-1111-4111-8111-111111111111",
                    capturedAt = TEST_CLOCK.instant(),
                ),
            )
        ) {
            is ActivityWriteResult.Success -> result.events
            is ActivityWriteResult.Failure ->
                throw AssertionError("expected activity, got ${result.reason}")

            ActivityWriteResult.Queued -> throw AssertionError("expected activity, got a queued write")
        }

        assertEquals("Bearer access-token", api.lastAuthorization)
        // The note carries the idempotency key and the device instant the action was made with, so a
        // replay after a timeout is recorded at most once (`BR-031`, `ADR-019` D5).
        assertEquals("Replaced the filter.", api.lastNoteRequest?.body)
        assertEquals(
            "11111111-1111-4111-8111-111111111111",
            api.lastNoteRequest?.clientOperationId,
        )
        assertEquals(TEST_CLOCK.instant().toString(), api.lastNoteRequest?.capturedAt)
        assertEquals(listOf("note-1"), events.map { it.id })
    }

    @Test
    fun `maps the Visit destinations the API reports`() = runTest {
        val api = FakeJobDetailsApi(answer = { jobDetailsDto() })
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val details = when (val result = repository.loadJobDetails(JOB_ID)) {
            is JobDetailsResult.Success -> result.details
            is JobDetailsResult.Failure ->
                throw AssertionError("expected the Job, got ${result.reason}")
        }

        // The field lifecycle is the backend's answer, so the screen never holds a second copy of it
        // (`BR-022`, `BR-041`).
        assertEquals(
            listOf(VisitStatus.EN_ROUTE),
            details.selectedVisit?.allowedStatusTransitions,
        )
    }

    @Test
    fun `maps the caller's own reach on the Visit as the API answered it`() = runTest {
        // A Visit the caller may read but whose crew does not include them: the API's answer is what
        // stops the screen offering an action the field route would refuse (`ADR-019` D3, `BR-041`).
        val api = FakeJobDetailsApi(
            answer = {
                jobDetailsDto().copy(
                    selectedVisit = visitDto().copy(
                        allowedStatusTransitions = listOf("EN_ROUTE"),
                        fieldActionable = false,
                    ),
                )
            },
        )
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val details = assertSuccess(repository.loadJobDetails(JOB_ID))

        assertEquals(false, details.selectedVisit?.fieldActionable)
        // The destinations are carried through as reported, whatever the API sent: the projection is the
        // API's answer and this layer never second-guesses it (`BR-041`). Since `BR-093` a real read
        // reports no destination for a caller it cannot place, so this payload checks the pass-through
        // rather than the shape a live answer has.
        assertEquals(
            listOf(VisitStatus.EN_ROUTE),
            details.selectedVisit?.allowedStatusTransitions,
        )
    }

    @Test
    fun `applies a Visit transition with the destination, provenance and version the screen saw`() =
        runTest {
            // The field route is the technician's own lifecycle: it carries the destination, the
            // idempotency key, the device instant and the Visit version the screen last saw
            // (`BR-074`, `BR-086`, `ADR-019` D5).
            val api = FakeJobDetailsApi(answer = { jobDetailsDto().copy(status = "IN_PROGRESS") })
            val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

            val result = repository.changeVisitStatus(
                VisitStatusChange(
                    jobId = JOB_ID,
                    visitId = "visit-1",
                    status = VisitStatus.ON_SITE,
                    operationId = "22222222-2222-4222-8222-222222222222",
                    capturedAt = TEST_CLOCK.instant(),
                    expectedVersion = 2,
                ),
            )

            assertTrue(result is JobActionResult.Success)
            assertEquals("Bearer access-token", api.lastAuthorization)
            assertEquals("visit-1", api.lastVisitId)
            assertEquals(
                ChangeVisitStatusRequestDto(
                    status = "ON_SITE",
                    clientOperationId = "22222222-2222-4222-8222-222222222222",
                    capturedAt = TEST_CLOCK.instant().toString(),
                    expectedVersion = 2,
                ),
                api.lastVisitStatusRequest,
            )
        }

    @Test
    fun `sends the outcome a completion requires in the same request`() = runTest {
        // `BR-077` requires the outcome with the completion, so the two are one operation: there is no
        // request that finishes a Visit without saying what resulted from it.
        val api = FakeJobDetailsApi(answer = { jobDetailsDto().copy(status = "COMPLETED") })
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        repository.changeVisitStatus(
            VisitStatusChange(
                jobId = JOB_ID,
                visitId = "visit-1",
                status = VisitStatus.COMPLETED,
                outcome = VisitOutcome.RESOLVED,
                outcomeSummary = "  Replaced the igniter.  ",
                operationId = "33333333-3333-4333-8333-333333333333",
                capturedAt = TEST_CLOCK.instant(),
                expectedVersion = 5,
            ),
        )

        assertEquals("COMPLETED", api.lastVisitStatusRequest?.status)
        assertEquals("RESOLVED", api.lastVisitStatusRequest?.outcomeCode)
        assertEquals("Replaced the igniter.", api.lastVisitStatusRequest?.outcomeSummary)
    }

    @Test
    fun `queues a Visit transition the API could not be reached for, keeping its key`() = runTest {
        // The transition is offline-capable (`BR-013`), so a request that never reached the backend is
        // durable rather than lost — and it is queued under the **same** idempotency key the attempt
        // used, so a replay applies it once (`BR-014`, `BR-031`, §5). Nothing is presented as applied.
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            failFirstWith = IOException("no connection"),
        )
        val outbox = InMemoryOutboxStore()
        val repository = repository(
            api,
            FakeSessionAuthenticator(accessToken = "access-token"),
            outbox = outbox,
        )

        val result = repository.changeVisitStatus(
            VisitStatusChange(
                jobId = JOB_ID,
                visitId = "visit-1",
                status = VisitStatus.ON_SITE,
                operationId = "44444444-4444-4444-8444-444444444444",
                capturedAt = TEST_CLOCK.instant(),
                expectedVersion = 2,
            ),
        )

        assertTrue(result is JobActionResult.Queued)
        assertEquals(1, outbox.stored.size)
        val row = outbox.stored.single()
        assertEquals("44444444-4444-4444-8444-444444444444", row.operationId)
        assertEquals(VisitFieldOperationTypes.CHANGE_STATUS, row.operationType)
        // The Job is the target and the Visit lives in the payload, so the row is partitioned by the
        // record the screen is open on.
        assertEquals(JOB_ID, row.targetId)
        // The version the technician saw travels with the row: `ADR-019` D5 refuses a stale one rather
        // than re-reading it and applying the command against newer state.
        assertEquals(2, row.expectedVersion)
    }

    @Test
    fun `reports a refused Visit transition as the Visit's own answer and queues nothing`() =
        runTest {
            // A refusal is the backend's own answer, so it is reported rather than queued: the Visit's
            // state machine refused the destination, and that is what the technician has to act on
            // (`BR-032`).
            val api = FakeJobDetailsApi(
                answer = { jobDetailsDto() },
                failFirstWith = HttpException(
                    Response.error<Any>(
                        409,
                        """{"code":"VISIT_STATUS_TRANSITION_NOT_ALLOWED"}"""
                            .toResponseBody("application/json".toMediaType()),
                    ),
                ),
            )
            val outbox = InMemoryOutboxStore()
            val repository = repository(
                api,
                FakeSessionAuthenticator(accessToken = "access-token"),
                outbox = outbox,
            )

            val result = repository.changeVisitStatus(
                VisitStatusChange(
                    jobId = JOB_ID,
                    visitId = "visit-1",
                    status = VisitStatus.ON_SITE,
                    operationId = "55555555-5555-4555-8555-555555555555",
                    capturedAt = TEST_CLOCK.instant(),
                    expectedVersion = 2,
                ),
            )

            assertEquals(
                JobActionFailure.VISIT_TRANSITION_NOT_ALLOWED,
                (result as JobActionResult.Failure).reason,
            )
            assertTrue(outbox.stored.isEmpty())
        }

    @Test
    fun `removes a photo with the trimmed reason and maps the refreshed activity`() = runTest {
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            activityAnswer = {
                jobActivityDto(
                    jobActivityEventDto(
                        id = "removal-1",
                        kind = "JOB_PHOTO_REMOVED",
                        photoId = "photo-1",
                        photoRemovalReason = "Wrong property",
                    ),
                )
            },
        )
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val events = when (
            val result = repository.removeJobPhoto(
                jobId = JOB_ID,
                photoId = "photo-1",
                reason = "  Wrong property  ",
            )
        ) {
            is ActivityWriteResult.Success -> result.events
            is ActivityWriteResult.Failure ->
                throw AssertionError("expected activity, got ${result.reason}")

            ActivityWriteResult.Queued -> throw AssertionError("expected activity, got a queued write")
        }

        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals(JOB_ID, api.lastJobId)
        assertEquals("photo-1", api.lastPhotoId)
        assertEquals(RemoveJobPhotoRequestDto(reason = "Wrong property"), api.lastRemovalRequest)
        assertEquals(listOf("removal-1"), events.map { it.id })
        assertEquals(JobActivityKind.JOB_PHOTO_REMOVED, events.single().kind)
        assertEquals("Wrong property", events.single().photoRemovalReason)
    }

    @Test
    fun `reports an already-removed photo as its own outcome`() = runTest {
        // One photo has one removal and no restore is defined, so the API refuses a repeat with its own
        // code (`BR-089`). The screen has to say that rather than report a generic failure, so the code
        // is classified here where the envelope is read.
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            activityAnswer = {
                throw httpFailure(
                    status = 409,
                    body = """
                        {
                          "statusCode": 409,
                          "code": "JOB_PHOTO_ALREADY_REMOVED",
                          "message": "That photo has already been removed."
                        }
                    """,
                )
            },
        )
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val result = repository.removeJobPhoto(
            jobId = JOB_ID,
            photoId = "photo-1",
            reason = "Wrong property",
        )

        assertEquals(
            JobActionFailure.PHOTO_ALREADY_REMOVED,
            (result as ActivityWriteResult.Failure).reason,
        )
    }

    @Test
    fun `removes a recording with the trimmed reason and maps the refreshed activity`() = runTest {
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            activityAnswer = {
                jobActivityDto(
                    jobActivityEventDto(
                        id = "removal-1",
                        kind = "JOB_AUDIO_REMOVED",
                        body = null,
                        audioNoteId = "audio-1",
                        audioRemovalReason = "Recorded the wrong job",
                    ),
                )
            },
        )
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val events = when (
            val result = repository.removeJobAudioNote(
                jobId = JOB_ID,
                audioNoteId = "audio-1",
                reason = "  Recorded the wrong job  ",
            )
        ) {
            is ActivityWriteResult.Success -> result.events
            is ActivityWriteResult.Failure ->
                throw AssertionError("expected activity, got ${result.reason}")

            ActivityWriteResult.Queued -> throw AssertionError("expected activity, got a queued write")
        }

        // The audio kind's own route and request (`BR-089`, `ADR-018` A7): the reason is stated, trimmed,
        // and the recording is named by the id the caller already knows it by.
        assertEquals("Bearer access-token", api.lastAuthorization)
        assertEquals(JOB_ID, api.lastJobId)
        assertEquals("audio-1", api.lastAudioNoteId)
        assertEquals(
            RemoveJobAudioNoteRequestDto(reason = "Recorded the wrong job"),
            api.lastAudioRemovalRequest,
        )
        assertEquals(listOf("removal-1"), events.map { it.id })
        assertEquals(JobActivityKind.JOB_AUDIO_REMOVED, events.single().kind)
        assertEquals("Recorded the wrong job", events.single().audioRemovalReason)
    }

    @Test
    fun `reports an already-removed recording as its own outcome`() = runTest {
        // One recording has one removal and no restore is defined, so the API refuses a repeat with the
        // audio kind's own code (`BR-089`, `ADR-018` A7). The screen has to say that rather than report a
        // generic failure, so the code is classified here where the envelope is read.
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            activityAnswer = {
                throw httpFailure(
                    status = 409,
                    body = """
                        {
                          "statusCode": 409,
                          "code": "JOB_AUDIO_NOTE_ALREADY_REMOVED",
                          "message": "That recording has already been removed."
                        }
                    """,
                )
            },
        )
        val repository = repository(api, FakeSessionAuthenticator(accessToken = "access-token"))

        val result = repository.removeJobAudioNote(
            jobId = JOB_ID,
            audioNoteId = "audio-1",
            reason = "Recorded the wrong job",
        )

        assertEquals(
            JobActionFailure.AUDIO_NOTE_ALREADY_REMOVED,
            (result as ActivityWriteResult.Failure).reason,
        )
    }

    @Test
    fun `a removal replaces the local copy of the activity`() = runTest {
        // A write answers with the refreshed timeline, and that answer is the last one the backend
        // reported: the local copy is replaced with it, exactly as a successful read replaces it. A
        // device that removed evidence and then lost connectivity must not serve an activity that
        // predates its own change and still lists the photo (`BR-089`, `offline-first-architecture.md`
        // §2).
        val workingSet = InMemoryWorkingSetStore()
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            activityAnswer = {
                jobActivityDto(
                    jobActivityEventDto(
                        id = "removal-1",
                        kind = "JOB_PHOTO_REMOVED",
                        photoId = "photo-1",
                        photoRemovalReason = "Wrong property",
                    ),
                )
            },
        )
        val repository = repository(
            api = api,
            sessionAuthenticator = FakeSessionAuthenticator(accessToken = "access-token"),
            workingSet = workingSet,
        )
        // The device holds a timeline that predates the removal.
        val cache = JobEvidenceCache(
            workingSet = workingSet,
            json = Json { ignoreUnknownKeys = true },
            clock = TEST_CLOCK,
        )
        cache.rememberActivity(
            subjectId = SUBJECT_ID,
            jobId = JOB_ID,
            activity = jobActivityDto(jobActivityEventDto(id = "photo-1", kind = "JOB_PHOTO_ADDED")),
        )

        repository.removeJobPhoto(jobId = JOB_ID, photoId = "photo-1", reason = "Wrong property")

        val held = requireNotNull(cache.reportedActivity(SUBJECT_ID, JOB_ID))
        assertEquals(listOf("removal-1"), held.events.map { it.id })
        assertEquals("JOB_PHOTO_REMOVED", held.events.single().kind)
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
    fun `maps an accepted recording with its own id, phase, length and note`() = runTest {
        val api = FakeJobDetailsApi(
            answer = { jobDetailsDto() },
            activityAnswer = {
                jobActivityDto(
                    jobActivityEventDto(
                        id = "audio-1",
                        kind = "JOB_AUDIO_ADDED",
                        visitSequence = null,
                        body = "Furnace noise",
                        audioNoteId = "audio-1",
                        audioPhase = "DURING_WORK",
                        audioDurationSeconds = 18,
                    ),
                    jobActivityEventDto(
                        id = "removal-1",
                        kind = "JOB_AUDIO_REMOVED",
                        visitSequence = null,
                        body = null,
                        audioNoteId = "audio-1",
                        audioRemovalReason = "Recorded the wrong job",
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

        // A recording carries its own fields rather than a photo's (`BR-091`, `ADR-018` A6): the id its
        // bytes are read with, the phase every evidence kind carries, and the length the API read from the
        // recording's own container — so a client states a length it never measured (`A3`).
        assertEquals(JobActivityKind.JOB_AUDIO_ADDED, events[0].kind)
        assertEquals("audio-1", events[0].audioNoteId)
        assertEquals("DURING_WORK", events[0].audioPhase)
        assertEquals(18, events[0].audioDurationSeconds)
        assertEquals("Furnace noise", events[0].body)
        assertNull(events[0].photoId)

        // A removal names the recording and states why it was taken out of ordinary use (`BR-089`).
        assertEquals(JobActivityKind.JOB_AUDIO_REMOVED, events[1].kind)
        assertEquals("audio-1", events[1].audioNoteId)
        assertEquals("Recorded the wrong job", events[1].audioRemovalReason)
        assertNull(events[1].audioDurationSeconds)
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
    // The destinations the API reports for a `SCHEDULED` Visit (`BR-074`), so the screen draws its
    // field action from the server's own answer rather than a second copy of the lifecycle.
    allowedStatusTransitions = listOf("EN_ROUTE"),
    // The API's other answer: the caller's membership is on this Visit's crew, so the action may be
    // offered (`ADR-019` D3).
    fieldActionable = true,
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

/**
 * One Visit of the Job as `GET /jobs/:id` lists them (`BR-047`, `BR-071`).
 *
 * A Visit with no schedule is expressed by passing both ends as `null`, which is the shape the API
 * reports for a Visit that has not been scheduled yet (`BR-072`).
 */
internal fun listedVisitDto(
    id: String = "visit-1",
    sequence: Int = 1,
    status: String = "SCHEDULED",
    outcomeCode: String? = null,
    scheduledStart: String? = "2026-09-14T13:00:00.000Z",
    scheduledEnd: String? = "2026-09-14T15:00:00.000Z",
    version: Int = 2,
    technicians: List<JobDetailsTechnicianDto> = listOf(technicianDto()),
) = JobDetailsVisitSummaryDto(
    id = id,
    sequence = sequence,
    status = status,
    outcomeCode = outcomeCode,
    scheduledStart = scheduledStart,
    scheduledEnd = scheduledEnd,
    version = version,
    technicians = technicians,
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
    photoId: String? = null,
    photoPhase: String? = null,
    photoRemovalReason: String? = null,
    audioNoteId: String? = null,
    audioPhase: String? = null,
    audioDurationSeconds: Int? = null,
    audioRemovalReason: String? = null,
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
    photoId = photoId,
    photoPhase = photoPhase,
    photoRemovalReason = photoRemovalReason,
    audioNoteId = audioNoteId,
    audioPhase = audioPhase,
    audioDurationSeconds = audioDurationSeconds,
    audioRemovalReason = audioRemovalReason,
)

internal fun jobActivityDto(vararg events: JobActivityEventDto) =
    JobActivityDto(jobId = "job-1", events = events.toList())

private class FakeJobDetailsApi(
    private val answer: () -> JobDetailsDto,
    private val failFirstWith: Throwable? = null,
    private val activityAnswer: () -> JobActivityDto = { JobActivityDto(jobId = "") },
) : JobDetailsApi {

    /**
     * The tests this fake serves are about the Job read and the Job and Visit actions, so creating a
     * Job is not exercised here — the Create Job form has its own fake (`qa.md` §6.1).
     */
    override suspend fun createJob(
        authorization: String,
        request: CreateJobRequest,
    ): JobDetailsDto = throw AssertionError("these tests do not create a Job")

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

    /** The Visit a field lifecycle action named. */
    var lastVisitId: String? = null
        private set

    /** The last Visit status transition this API was asked to apply (`BR-074`, `BR-075`). */
    var lastVisitStatusRequest: ChangeVisitStatusRequestDto? = null
        private set

    var lastRemovalRequest: RemoveJobPhotoRequestDto? = null
        private set

    var lastPhotoId: String? = null
        private set

    var lastAudioRemovalRequest: RemoveJobAudioNoteRequestDto? = null
        private set

    var lastAudioNoteId: String? = null
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

    override suspend fun changeVisitStatus(
        authorization: String,
        jobId: String,
        visitId: String,
        request: ChangeVisitStatusRequestDto,
    ): JobDetailsDto {
        calls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        lastVisitId = visitId
        lastVisitStatusRequest = request
        if (calls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        return answer()
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

    /**
     * The removal route (`BR-089`). It answers with the refreshed timeline like the note route, so a
     * test asserts the request and the mapped events.
     */
    override suspend fun removeJobPhoto(
        authorization: String,
        jobId: String,
        photoId: String,
        request: RemoveJobPhotoRequestDto,
    ): JobActivityDto {
        activityCalls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        lastPhotoId = photoId
        lastRemovalRequest = request
        if (activityCalls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        return activityAnswer()
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

    /**
     * The audio removal route (`BR-089`, `ADR-018` A7). It answers with the refreshed timeline exactly
     * as the photo removal does, so a test asserts the request and the mapped events.
     */
    override suspend fun removeJobAudioNote(
        authorization: String,
        jobId: String,
        audioNoteId: String,
        request: RemoveJobAudioNoteRequestDto,
    ): JobActivityDto {
        activityCalls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        lastAudioNoteId = audioNoteId
        lastAudioRemovalRequest = request
        if (activityCalls == 1 && failFirstWith != null) {
            throw failFirstWith
        }
        return activityAnswer()
    }

    /**
     * The audio content route (`BR-091`). The playback reader is exercised by
     * `JobAudioEvidenceCacheTest`, so this answers the smallest honest thing.
     */
    override suspend fun jobAudioNoteContent(
        authorization: String,
        jobId: String,
        audioNoteId: String,
    ): ResponseBody {
        lastAuthorization = authorization
        lastJobId = jobId
        lastAudioNoteId = audioNoteId
        return "".toResponseBody("audio/mp4".toMediaType())
    }

    /**
     * The audio upload route is not used by these tests. They cover the Job read and the management
     * actions; the audio upload is covered where it lives (`JobAudioUploadHandlerTest`).
     */
    override suspend fun addJobAudioNote(
        authorization: String,
        jobId: String,
        clientOperationId: RequestBody,
        phase: RequestBody,
        note: RequestBody?,
        capturedAt: RequestBody?,
        file: MultipartBody.Part,
    ): JobActivityDto {
        throw NotImplementedError("Not used by these tests.")
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
    outbox: InMemoryOutboxStore = InMemoryOutboxStore(),
    subject: AuthenticatedSubject = FakeAuthenticatedSubject(subjectId),
) = DefaultJobDetailsRepository(
    api = api,
    sessionAuthenticator = sessionAuthenticator,
    evidence = JobEvidenceCache(
        workingSet = workingSet,
        json = Json { ignoreUnknownKeys = true },
        clock = TEST_CLOCK,
    ),
    subject = subject,
    json = Json { ignoreUnknownKeys = true },
    visitFieldActions = VisitFieldActions(
        outbox = outbox,
        offlineSync = FakeOfflineSync(),
        json = Json { ignoreUnknownKeys = true },
        clock = TEST_CLOCK,
    ),
    engine = OutboxReplayEngine(
        outbox = outbox,
        handlers = emptySet(),
        subject = subject,
        clock = TEST_CLOCK,
    ),
)

/** The subject the working set's rows are filed under; a second one stands for another session. */
private const val SUBJECT_ID = "user-1"
