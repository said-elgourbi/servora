package com.servora.android.ui.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.ActivityWriteResult
import com.servora.android.data.jobs.CreateJobRequest
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.JobCreateResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.jobs.JobDetailsResult
import com.servora.android.data.jobs.QueuedVisitFieldAction
import com.servora.android.data.jobs.QueuedVisitNote
import com.servora.android.data.jobs.VisitCompletion
import com.servora.android.data.jobs.VisitNote
import com.servora.android.data.jobs.VisitStatusChange
import com.servora.android.data.jobs.VisitRequestSubmitResult
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
import com.servora.android.data.schedule.VisitRequestReviewResult
import com.servora.android.data.schedule.VisitRequestsRepository
import com.servora.android.data.schedule.VisitRequestsResult
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.ScheduleScope
import com.servora.android.domain.model.ScheduleScopeKind
import com.servora.android.domain.model.ScheduleDay
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.VisitStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the schedule screen is told, and what it asks [ScheduleRepository] for.
 *
 * Which Visits a day holds, what is unassigned and whether a Visit is overdue are the backend's
 * answers (`BR-001`, `BR-042`), so these tests assert that the day and the filter the screen holds
 * are the ones the read carries, that a failed read never leaves a stale day on screen, and that a
 * result belonging to a day the manager has already left is dropped.
 */
class ScheduleViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opens on today and reads it for the device zone`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())

        viewModel.start(today = MONDAY, timeZoneId = DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        assertTrue(viewModel.uiState.value.showsInitialLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            listOf(ScheduleRead("2026-09-07", DEVICE_ZONE, emptyList())),
            repository.requested,
        )
        assertEquals(MONDAY, state.selectedDate)
        assertEquals(MONDAY, state.displayedWeekStart)
        assertFalse(state.isRefreshing)
        assertEquals(listOf("visit-1"), state.schedule?.visits?.map { it.visitId })
        assertEquals(3, state.unassignedCount)
        assertTrue(state.showsSelectedWeek)
    }

    @Test
    fun `selects a day and moves the strip to its week`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        // Sunday of the following week: the strip follows the date it was given.
        viewModel.selectDate(LocalDate.parse("2026-09-13"), DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(LocalDate.parse("2026-09-13"), state.selectedDate)
        assertEquals(LocalDate.parse("2026-09-07"), state.displayedWeekStart)
        assertEquals(
            ScheduleRead("2026-09-13", DEVICE_ZONE, emptyList()),
            repository.requested.last(),
        )
    }

    @Test
    fun `shows another week without reading a day the manager did not choose`() =
        runTest(dispatcher) {
            val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
            val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
            viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
            advanceUntilIdle()

            viewModel.showWeek(LocalDate.parse("2026-09-14"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(LocalDate.parse("2026-09-14"), state.displayedWeekStart)
            assertEquals(MONDAY, state.selectedDate)
            assertFalse(state.showsSelectedWeek)
            // One read: reaching a week is a move of the strip, not a read of a day nobody picked.
            assertEquals(1, repository.requested.size)
        }

    @Test
    fun `reads the day again when the technician filter changes`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectTechnicians(listOf(technician()))
        advanceUntilIdle()

        assertEquals(
            ScheduleRead("2026-09-07", DEVICE_ZONE, listOf("member-1")),
            repository.requested.last(),
        )
        assertEquals(listOf("member-1"), viewModel.uiState.value.technicianFilterIds)
        assertTrue(viewModel.uiState.value.hasTechnicianFilter)

        // Selecting the same technician again is not a second read.
        viewModel.selectTechnicians(listOf(technician()))
        advanceUntilIdle()
        assertEquals(2, repository.requested.size)
    }

    @Test
    fun `reads one day for a group of technicians rather than a request each`() =
        runTest(dispatcher) {
            val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
            val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
            viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
            advanceUntilIdle()

            viewModel.selectTechnicians(
                listOf(technician(), technician(membershipId = "member-2", name = "Luc Gagnon")),
            )
            advanceUntilIdle()

            // The union of the selected technicians is one read: the API answers with the Visits any
            // of them is assigned to (`BR-068`), so a group is never a request per technician.
            assertEquals(2, repository.requested.size)
            assertEquals(
                ScheduleRead("2026-09-07", DEVICE_ZONE, listOf("member-1", "member-2")),
                repository.requested.last(),
            )
            assertEquals(
                listOf("member-1", "member-2"),
                viewModel.uiState.value.technicianFilterIds,
            )
        }

    @Test
    fun `shows the whole organization again when the filter is cleared`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectTechnicians(listOf(technician()))
        advanceUntilIdle()
        // `All technicians` removes the technician predicate entirely rather than naming everybody.
        viewModel.selectTechnicians(emptyList())
        advanceUntilIdle()

        assertEquals(
            ScheduleRead("2026-09-07", DEVICE_ZONE, emptyList()),
            repository.requested.last(),
        )
        assertFalse(viewModel.uiState.value.hasTechnicianFilter)
    }

    @Test
    fun `carries the filter into another day's read`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectTechnicians(listOf(technician()))
        advanceUntilIdle()
        viewModel.selectDate(LocalDate.parse("2026-09-08"), DayOfWeek.MONDAY)
        advanceUntilIdle()

        // The filter is the screen's own state, so moving to another day narrows it the same way:
        // the manager's selection is not silently dropped by changing date (`BR-068`).
        assertEquals(
            ScheduleRead("2026-09-08", DEVICE_ZONE, listOf("member-1")),
            repository.requested.last(),
        )
        assertEquals(listOf("member-1"), viewModel.uiState.value.technicianFilterIds)
    }

    @Test
    fun `switches lanes without reading the day again`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectLane(ScheduleLane.UNASSIGNED)
        advanceUntilIdle()

        // Both lanes are one read's answers (`BR-042`).
        assertEquals(1, repository.requested.size)
        assertEquals(ScheduleLane.UNASSIGNED, viewModel.uiState.value.lane)
    }

    @Test
    fun `loads the requests awaiting a decision with the schedule`() = runTest(dispatcher) {
        val requests = RecordingVisitRequestsRepository(request())
        val viewModel = ScheduleViewModel(
            repository = RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            visitRequestsRepository = requests,
            jobDetailsRepository = EmptyJobDetailsRepository(),
        )

        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(1, requests.reads)
        assertEquals(listOf("request-1"), viewModel.uiState.value.reviewableRequests.map { it.id })
        assertEquals(1, viewModel.uiState.value.reviewableRequestCount)
    }

    @Test
    fun `keeps a request returned for clarification awaiting a decision`() = runTest(dispatcher) {
        // `BR-FV-012`: a clarified request is unresolved "until it is later approved or rejected", and
        // the review routes keep accepting a decision about it. Drawing only `PENDING` would take the one
        // screen that can decide a request off a request the office still has to decide.
        val clarified = request(status = FollowUpVisitRequestStatus.NEEDS_CLARIFICATION)
        val viewModel = ScheduleViewModel(
            repository = RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            visitRequestsRepository = RecordingVisitRequestsRepository(clarified),
            jobDetailsRepository = EmptyJobDetailsRepository(),
        )
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.reviewableRequestCount)
        assertEquals(
            FollowUpVisitRequestStatus.NEEDS_CLARIFICATION,
            viewModel.uiState.value.reviewableRequests.single().status,
        )
    }

    @Test
    fun `returns a request for clarification with what is still needed`() = runTest(dispatcher) {
        val pending = request()
        val requests = RecordingVisitRequestsRepository(
            pending,
            reviewed = pending.copy(
                status = FollowUpVisitRequestStatus.NEEDS_CLARIFICATION,
                reviewNote = "Which part number?",
                version = 2,
            ),
        )
        val viewModel = ScheduleViewModel(
            repository = RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            visitRequestsRepository = requests,
            jobDetailsRepository = EmptyJobDetailsRepository(),
        )
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.askForClarification(pending, "Which part number?")
        advanceUntilIdle()

        // The office's own words are the decision's record (`BR-FV-013`), and the request stays in the
        // lane because the API keeps it reviewable (`BR-FV-012`).
        assertEquals(listOf("Which part number?"), requests.clarificationNotes)
        assertEquals(FollowUpVisitRequestStatus.NEEDS_CLARIFICATION, viewModel.uiState.value.reviewedRequestStatus)
        assertEquals(1, viewModel.uiState.value.reviewableRequestCount)
    }

    @Test
    fun `rejects a request with the reason it was refused`() = runTest(dispatcher) {
        val pending = request()
        val requests = RecordingVisitRequestsRepository(
            pending,
            reviewed = pending.copy(
                status = FollowUpVisitRequestStatus.REJECTED,
                version = 2,
            ),
        )
        val viewModel = ScheduleViewModel(
            repository = RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            visitRequestsRepository = requests,
            jobDetailsRepository = EmptyJobDetailsRepository(),
        )
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.rejectRequest(pending, "No further visit is required.")
        advanceUntilIdle()

        assertEquals(listOf("request-1"), requests.rejected.map { it.id })
        assertEquals(listOf("No further visit is required."), requests.rejectionNotes)
        assertEquals(FollowUpVisitRequestStatus.REJECTED, viewModel.uiState.value.reviewedRequestStatus)
        assertEquals(0, viewModel.uiState.value.reviewableRequestCount)
        assertNull(viewModel.uiState.value.reviewingRequestId)
    }

    @Test
    fun `reports a review the API refused instead of presenting it as decided`() = runTest(dispatcher) {
        val pending = request()
        val requests = RecordingVisitRequestsRepository(
            pending,
            reviewFailure = CustomersFailureReason.VERSION_CONFLICT,
        )
        val viewModel = ScheduleViewModel(
            repository = RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            visitRequestsRepository = requests,
            jobDetailsRepository = EmptyJobDetailsRepository(),
        )
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.rejectRequest(pending, "No further visit is required.")
        advanceUntilIdle()

        // Nothing was applied, so nothing is presented as decided, and the refusal is reported rather
        // than swallowed (`BR-001`, `BR-FV-013`, `BR-067`).
        assertEquals(CustomersFailureReason.VERSION_CONFLICT, viewModel.uiState.value.reviewFailureReason)
        assertNull(viewModel.uiState.value.reviewedRequestStatus)
        // The request the API still holds is the one the lane keeps showing.
        assertEquals(1, viewModel.uiState.value.reviewableRequestCount)
    }

    @Test
    fun `opening an approval reads the technicians the crew is chosen from`() = runTest(dispatcher) {
        val jobDetails = EmptyJobDetailsRepository(
            technicians = listOf(AssignableTechnician("member-1", "Mike Lead")),
        )
        val viewModel = ScheduleViewModel(
            repository = RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            visitRequestsRepository = RecordingVisitRequestsRepository(request()),
            jobDetailsRepository = jobDetails,
        )
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.beginApproval(request())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("request-1", state.schedulingRequestId)
        assertEquals("request-1", state.schedulingRequest?.id)
        assertEquals(1, jobDetails.technicianReads)
        assertEquals(listOf("member-1"), state.assignableTechnicians?.map { it.membershipId })
        assertNull(state.approvalFailure)
    }

    @Test
    fun `approving a request schedules the Visit with the reviewer's decision and refreshes`() =
        runTest(dispatcher) {
            val pending = request()
            val jobDetails = EmptyJobDetailsRepository()
            jobDetails.approvalResults += JobActionResult.Success(job())
            val requests = RecordingVisitRequestsRepository(pending)
            val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
            val viewModel = ScheduleViewModel(repository, requests, jobDetails)
            viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
            advanceUntilIdle()
            val readsBefore = requests.reads
            val scheduleReadsBefore = repository.requested.size

            viewModel.beginApproval(pending)
            advanceUntilIdle()
            viewModel.approveRequest(
                request = pending,
                scheduledStart = Instant.parse("2026-09-16T13:00:00Z"),
                scheduledEnd = Instant.parse("2026-09-16T15:00:00Z"),
                assignments = listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)),
            )
            advanceUntilIdle()

            // The decision is the reviewer's own window and crew, and the request is named by the state it
            // was read in, so a request that moved on is refused rather than decided about (`BR-086`).
            assertEquals(
                listOf(
                    "request-1:PENDING:1:2026-09-16T13:00:00Z:2026-09-16T15:00:00Z:" +
                        "member-1=LEAD:false",
                ),
                jobDetails.approvals,
            )
            // The approval created one Visit on one Job, so the form closes, the decision is reported and
            // both the requests and the day are read again (`BR-FV-005`, `BR-001`).
            val state = viewModel.uiState.value
            assertNull(state.schedulingRequestId)
            assertEquals("request-1", state.approvedRequestId)
            assertNull(state.approvalFailure)
            assertFalse(state.isApproving)
            assertEquals(readsBefore + 1, requests.reads)
            assertEquals(scheduleReadsBefore + 1, repository.requested.size)
        }

    @Test
    fun `holds the approval until its conflicts are accepted, then resends it confirmed`() =
        runTest(dispatcher) {
            val pending = request()
            val conflicts = listOf(conflict())
            val jobDetails = EmptyJobDetailsRepository()
            jobDetails.approvalResults += JobActionResult.Conflicts(conflicts)
            jobDetails.approvalResults += JobActionResult.Success(job())
            val viewModel = ScheduleViewModel(
                RecordingScheduleRepository(ScheduleResult.Success(schedule())),
                RecordingVisitRequestsRepository(pending),
                jobDetails,
            )
            viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
            advanceUntilIdle()

            viewModel.beginApproval(pending)
            advanceUntilIdle()
            viewModel.approveRequest(
                request = pending,
                scheduledStart = Instant.parse("2026-09-16T13:00:00Z"),
                scheduledEnd = Instant.parse("2026-09-16T15:00:00Z"),
                assignments = listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)),
            )
            advanceUntilIdle()

            // The conflicts are the question the reviewer answers; the draft they answer about is held so
            // confirming resends exactly what they stated (`BR-070`, `BR-067`).
            val state = viewModel.uiState.value
            assertEquals(conflicts, state.pendingApproval?.conflicts)
            assertEquals("request-1", state.schedulingRequestId)
            assertNull(state.approvalFailure)

            viewModel.confirmApproval()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "request-1:PENDING:1:2026-09-16T13:00:00Z:2026-09-16T15:00:00Z:" +
                        "member-1=LEAD:false",
                    "request-1:PENDING:1:2026-09-16T13:00:00Z:2026-09-16T15:00:00Z:" +
                        "member-1=LEAD:true",
                ),
                jobDetails.approvals,
            )
            assertNull(viewModel.uiState.value.pendingApproval)
            assertEquals("request-1", viewModel.uiState.value.approvedRequestId)
        }

    @Test
    fun `leaves the approval conflicts unaccepted without sending anything`() = runTest(dispatcher) {
        val pending = request()
        val jobDetails = EmptyJobDetailsRepository()
        jobDetails.approvalResults += JobActionResult.Conflicts(listOf(conflict()))
        val viewModel = ScheduleViewModel(
            RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            RecordingVisitRequestsRepository(pending),
            jobDetails,
        )
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()
        viewModel.beginApproval(pending)
        advanceUntilIdle()
        viewModel.approveRequest(
            request = pending,
            scheduledStart = Instant.parse("2026-09-16T13:00:00Z"),
            scheduledEnd = Instant.parse("2026-09-16T15:00:00Z"),
            assignments = listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)),
        )
        advanceUntilIdle()

        viewModel.dismissApprovalConflicts()
        advanceUntilIdle()

        assertEquals(1, jobDetails.approvals.size)
        assertNull(viewModel.uiState.value.pendingApproval)
        // The form is still open on the request, so the reviewer can change it and try again (`BR-070`).
        assertEquals("request-1", viewModel.uiState.value.schedulingRequestId)
    }

    @Test
    fun `reports a refused approval and closes the form it was made in`() = runTest(dispatcher) {
        val pending = request()
        val jobDetails = EmptyJobDetailsRepository()
        jobDetails.approvalResults += JobActionResult.Failure(JobActionFailure.FORBIDDEN)
        val viewModel = ScheduleViewModel(
            RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            RecordingVisitRequestsRepository(pending),
            jobDetails,
        )
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()
        viewModel.beginApproval(pending)
        advanceUntilIdle()

        viewModel.approveRequest(
            request = pending,
            scheduledStart = Instant.parse("2026-09-16T13:00:00Z"),
            scheduledEnd = Instant.parse("2026-09-16T15:00:00Z"),
            assignments = listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(JobActionFailure.FORBIDDEN, state.approvalFailure)
        assertNull(state.approvedRequestId)
        assertNull(state.schedulingRequestId)
        // Nothing was created, so nothing is presented as created (`BR-001`).
        assertEquals(1, state.reviewableRequestCount)
    }

    @Test
    fun `does not send a second approval while one is in flight`() = runTest(dispatcher) {
        val pending = request()
        val jobDetails = EmptyJobDetailsRepository()
        jobDetails.approvalResults += JobActionResult.Success(job())
        val viewModel = ScheduleViewModel(
            RecordingScheduleRepository(ScheduleResult.Success(schedule())),
            RecordingVisitRequestsRepository(pending),
            jobDetails,
        )
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()
        viewModel.beginApproval(pending)
        advanceUntilIdle()

        // Two confirmations of the same form before the first is answered are one business decision, not
        // two Visits (`BR-031`).
        viewModel.approveRequest(
            request = pending,
            scheduledStart = Instant.parse("2026-09-16T13:00:00Z"),
            scheduledEnd = Instant.parse("2026-09-16T15:00:00Z"),
            assignments = listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)),
        )
        viewModel.approveRequest(
            request = pending,
            scheduledStart = Instant.parse("2026-09-16T13:00:00Z"),
            scheduledEnd = Instant.parse("2026-09-16T15:00:00Z"),
            assignments = listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)),
        )
        advanceUntilIdle()

        assertEquals(1, jobDetails.approvals.size)
        assertEquals("request-1", viewModel.uiState.value.approvedRequestId)
    }

    @Test
    fun `reports the reason a first read failed`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(
            ScheduleResult.Failure(CustomersFailureReason.FORBIDDEN),
        )
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())

        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showsFailure)
        assertEquals(CustomersFailureReason.FORBIDDEN, state.failureReason)
        assertNull(state.schedule)
    }

    @Test
    fun `never leaves another day's schedule on screen after a failed read`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(
            ScheduleResult.Success(schedule()),
            ScheduleResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectDate(LocalDate.parse("2026-09-08"), DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // The day on screen changed, so the previous day's Visits are not presented as this day's.
        assertNull(state.schedule)
        assertTrue(state.showsFailure)
    }

    @Test
    fun `drops an answer that belongs to a day the manager has already left`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)

        // Two selections before the first read settles: only the last one's answer may be applied.
        viewModel.selectDate(LocalDate.parse("2026-09-08"), DayOfWeek.MONDAY)
        viewModel.selectDate(LocalDate.parse("2026-09-09"), DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(LocalDate.parse("2026-09-09"), viewModel.uiState.value.selectedDate)
        assertTrue(viewModel.uiState.value.hasContent)
    }

    @Test
    fun `tells an empty day apart from a day that did not load`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(
            ScheduleResult.Success(schedule().copy(visits = emptyList(), unassigned = emptyList())),
        )
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())

        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Nothing scheduled is the read's own answer, not a failure (`BR-042`).
        assertTrue(state.showsEmptyDay)
        assertTrue(state.showsNoUnassignedWork)
        assertFalse(state.showsFailure)
        assertFalse(state.showsInitialLoading)
    }

    @Test
    fun `forgets the day with the session`() = runTest(dispatcher) {
        val repository = RecordingScheduleRepository(ScheduleResult.Success(schedule()))
        val viewModel = ScheduleViewModel(repository, EmptyVisitRequestsRepository(), EmptyJobDetailsRepository())
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.reset()
        assertNull(viewModel.uiState.value.selectedDate)

        // The next session opens on its own today again (`BR-001`).
        viewModel.start(MONDAY, DEVICE_ZONE, DayOfWeek.MONDAY)
        advanceUntilIdle()
        assertEquals(2, repository.requested.size)
    }
}

/** One read the ViewModel asked for. */
private data class ScheduleRead(
    val localDate: String,
    val timeZone: String,
    val membershipIds: List<String>,
)

private fun technician(
    membershipId: String = "member-1",
    name: String = "Mike Johnson",
) = ScheduleTechnician(
    membershipId = membershipId,
    name = name,
    roleCode = "",
)

/** The backend's answer for one day: a scheduled Visit, one waiting for a crew, and the crew. */
private fun schedule(): Schedule = Schedule(
    day = ScheduleDay(localDate = "2026-09-07", timeZone = DEVICE_ZONE),
    scope = ScheduleScope(
        kind = ScheduleScopeKind.ORGANIZATION,
        membershipId = "member-1",
    ),
    technicians = listOf(technician()),
    visits = listOf(
        ScheduleVisit(
            visitId = "visit-1",
            visitStatus = VisitStatus.SCHEDULED,
            scheduledStart = "2026-09-07T13:00:00.000Z",
            scheduledEnd = "2026-09-07T14:00:00.000Z",
            jobId = "job-1",
            jobNumber = 1042,
            jobTitle = "Furnace repair",
            customerId = "customer-1",
            customerName = "ABC Property Management",
            address = null,
            technicians = emptyList(),
            isOverdue = false,
        ),
    ),
    unassigned = listOf(
        ScheduleVisit(
            visitId = "visit-9",
            visitStatus = VisitStatus.DRAFT,
            scheduledStart = null,
            scheduledEnd = null,
            jobId = "job-9",
            jobNumber = 1049,
            jobTitle = "Boiler service",
            customerId = "customer-1",
            customerName = "ABC Property Management",
            address = null,
            technicians = emptyList(),
            isOverdue = false,
        ),
    ),
    unassignedTotal = 3,
    hasUnassignedLane = true,
)

private val MONDAY: LocalDate = LocalDate.parse("2026-09-07")

private const val DEVICE_ZONE = "America/Toronto"

/**
 * The Job an approval answers with.
 *
 * These tests assert the decision that was sent, not the Job it was answered with, so this is a readable
 * Job of the shape `GET /jobs/:id` returns (`docs/api/job-details.md`) rather than a scripted one.
 */
private fun job() = JobDetails(
    id = "job-1",
    jobNumber = 1042,
    title = "Furnace repair",
    description = null,
    status = JobStatus.ACTIVE,
    allowedStatusTransitions = listOf(JobStatus.COMPLETED, JobStatus.CANCELED),
    version = 8,
    customerId = "customer-1",
    customerName = "Martha Reynolds",
    address = null,
    selectedVisit = null,
    technicians = emptyList(),
)

/** One overlapping Visit the API reported (`BR-070`). */
private fun conflict() = ScheduleConflict(
    visitId = "visit-9",
    jobNumber = 1043,
    technicianName = "Mike Lead",
    scheduledStart = "2026-09-16T13:00:00.000Z",
    scheduledEnd = "2026-09-16T15:00:00.000Z",
)

private fun request(
    id: String = "request-1",
    status: FollowUpVisitRequestStatus = FollowUpVisitRequestStatus.PENDING,
) = FollowUpVisitRequest(
    id = id,
    jobId = "job-1",
    sourceVisitId = "visit-1",
    requestingTechnicianMembershipId = "membership-1",
    proposedStart = "2026-09-08T13:00:00.000Z",
    proposedEnd = "2026-09-08T14:00:00.000Z",
    reason = "Return with a replacement part.",
    sameTechnicianPreferred = true,
    status = status,
    reviewerMembershipId = null,
    reviewedAt = null,
    reviewNote = null,
    createdVisitId = null,
    version = 1,
    createdAt = "2026-09-07T12:00:00.000Z",
    updatedAt = "2026-09-07T12:00:00.000Z",
)

/** A [ScheduleRepository] that answers the results the test scripted, in order. */
private class RecordingScheduleRepository(
    private vararg val results: ScheduleResult,
) : ScheduleRepository {

    val requested = mutableListOf<ScheduleRead>()

    override suspend fun loadSchedule(
        localDate: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleResult {
        requested += ScheduleRead(localDate, timeZone, membershipIds)
        val index = (requested.size - 1).coerceAtMost(results.lastIndex)
        return results[index]
    }
}

private class EmptyVisitRequestsRepository : VisitRequestsRepository {
    override suspend fun loadRequests(): VisitRequestsResult =
        VisitRequestsResult.Success(emptyList())

    override suspend fun askForClarification(
        request: FollowUpVisitRequest,
        note: String,
    ): VisitRequestReviewResult =
        VisitRequestReviewResult.Success(request)

    override suspend fun reject(
        request: FollowUpVisitRequest,
        note: String,
    ): VisitRequestReviewResult =
        VisitRequestReviewResult.Success(request)

    override suspend fun reply(
        request: FollowUpVisitRequest,
        body: String,
    ): VisitRequestReviewResult =
        VisitRequestReviewResult.Success(request)
}

/**
 * A [JobDetailsRepository] the schedule tests only use for the technicians a crew is chosen from.
 *
 * The approval itself is exercised by the tests that script its answer, so this fake answers the
 * technician read with the organization's members and refuses everything else: a test that reached
 * another operation would be asserting something this suite does not cover (`qa.md` §6.1).
 */
private class EmptyJobDetailsRepository(
    private val technicians: List<AssignableTechnician> = emptyList(),
) : JobDetailsRepository {

    /** The results a test scripted for the approvals it sends, in order. */
    val approvalResults = ArrayDeque<JobActionResult>()

    /** Every approval this repository was asked to send, as a comparable description. */
    val approvals = mutableListOf<String>()

    /** Every technician read this repository was asked for. */
    var technicianReads = 0
        private set

    override suspend fun loadAssignableTechnicians(): AssignableTechniciansResult {
        technicianReads += 1
        return AssignableTechniciansResult.Success(technicians)
    }

    override suspend fun approveVisitRequest(
        jobId: String,
        requestId: String,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        assignments: List<TechnicianAssignment>,
        expectedStatus: FollowUpVisitRequestStatus,
        expectedVersion: Int,
        confirmConflicts: Boolean,
    ): JobActionResult {
        approvals += "$requestId:$expectedStatus:$expectedVersion:$scheduledStart:" +
            "$scheduledEnd:${assignments.joinToString { "${it.membershipId}=${it.role}" }}:" +
            "$confirmConflicts"
        return if (approvalResults.size > 1) approvalResults.removeFirst() else approvalResults.first()
    }

    /** These tests are about the schedule, so proposing a follow-up Visit is not exercised here. */
    override suspend fun requestFollowUpVisit(
        jobId: String,
        sourceVisitId: String,
        proposedStart: Instant,
        proposedEnd: Instant,
        reason: String,
        sameTechnicianPreferred: Boolean,
    ): VisitRequestSubmitResult = unsupported()

    override val appliedOperations: Flow<Unit> = emptyFlow()

    override val refusedOperations: Flow<Unit> = emptyFlow()

    override suspend fun createJob(request: CreateJobRequest): JobCreateResult = unsupported()

    override suspend fun loadJobDetails(jobId: String): JobDetailsResult = unsupported()

    override suspend fun loadJobActivity(jobId: String): JobActivityResult = unsupported()

    override suspend fun addVisitNoteRequest(note: VisitNote): ActivityWriteResult = unsupported()

    override suspend fun editVisitNote(
        jobId: String,
        noteId: String,
        body: String,
    ): ActivityWriteResult = unsupported()

    override suspend fun removeVisitNote(
        jobId: String,
        noteId: String,
        reason: String,
    ): ActivityWriteResult = unsupported()

    override suspend fun removeJobPhoto(
        jobId: String,
        photoId: String,
        reason: String,
    ): ActivityWriteResult = unsupported()

    override suspend fun removeJobAudioNote(
        jobId: String,
        audioNoteId: String,
        reason: String,
    ): ActivityWriteResult = unsupported()

    override suspend fun changeJobStatus(
        jobId: String,
        status: JobStatus,
        note: String?,
        expectedVersion: Int,
    ): JobActionResult = unsupported()

    override suspend fun rescheduleVisit(
        jobId: String,
        visitId: String,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        reason: String?,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult = unsupported()

    override suspend fun assignVisitTechnicians(
        jobId: String,
        visitId: String,
        assignments: List<TechnicianAssignment>,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult = unsupported()

    override suspend fun createVisit(
        jobId: String,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        assignments: List<TechnicianAssignment>,
        confirmConflicts: Boolean,
    ): JobActionResult = unsupported()

    override suspend fun changeVisitStatus(action: VisitStatusChange): JobActionResult =
        unsupported()

    override suspend fun completeVisit(action: VisitCompletion): JobActionResult = unsupported()

    override suspend fun queuedVisitAction(jobId: String): QueuedVisitFieldAction? = null

    override suspend fun queuedVisitNotes(jobId: String): List<QueuedVisitNote> = emptyList()

    override suspend fun discardQueuedVisitAction(jobId: String, operationId: String): Boolean =
        unsupported()

    override suspend fun discardQueuedVisitNote(jobId: String, operationId: String): Boolean =
        unsupported()

    private fun unsupported(): Nothing =
        throw AssertionError("the schedule tests only approve requests here")
}

private class RecordingVisitRequestsRepository(
    private vararg val requests: FollowUpVisitRequest,
    private val reviewed: FollowUpVisitRequest = requests.first(),
    private val reviewFailure: CustomersFailureReason? = null,
) : VisitRequestsRepository {
    var reads = 0
        private set

    /** Every rejection this repository was asked for. */
    val rejected = mutableListOf<FollowUpVisitRequest>()

    /** The reason each rejection was taken with, in the order they were sent (`BR-FV-013`). */
    val rejectionNotes = mutableListOf<String>()

    /** The question each clarification was taken with, in the order they were sent (`BR-FV-013`). */
    val clarificationNotes = mutableListOf<String>()

    override suspend fun loadRequests(): VisitRequestsResult {
        reads += 1
        return VisitRequestsResult.Success(requests.toList())
    }

    override suspend fun askForClarification(
        request: FollowUpVisitRequest,
        note: String,
    ): VisitRequestReviewResult {
        clarificationNotes += note
        return answer()
    }

    override suspend fun reject(
        request: FollowUpVisitRequest,
        note: String,
    ): VisitRequestReviewResult {
        rejected += request
        rejectionNotes += note
        return answer()
    }

    /**
     * The office reviews a request; answering one is the requester's own move (`BR-FV-012`), so nothing
     * on this screen reaches this path and a test that did would be asserting behaviour this suite does
     * not cover.
     */
    override suspend fun reply(
        request: FollowUpVisitRequest,
        body: String,
    ): VisitRequestReviewResult =
        error("the office's schedule does not answer follow-up requests")

    private fun answer(): VisitRequestReviewResult =
        reviewFailure?.let { VisitRequestReviewResult.Failure(it) }
            ?: VisitRequestReviewResult.Success(reviewed)
}
