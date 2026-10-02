package com.servora.android.ui.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.schedule.AdHocReportCustomerOptionsResult
import com.servora.android.data.schedule.AdHocReportJobOptionsResult
import com.servora.android.data.schedule.AdHocReportPropertyOptionsResult
import com.servora.android.data.schedule.AdHocWorkReportDraft
import com.servora.android.data.schedule.AdHocWorkReportResult
import com.servora.android.data.schedule.AdHocWorkReportsRepository
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
import com.servora.android.data.schedule.VisitRequestReviewResult
import com.servora.android.data.schedule.VisitRequestsRepository
import com.servora.android.data.schedule.VisitRequestsResult
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestMessage
import com.servora.android.domain.model.FollowUpVisitRequestMessageAuthorKind
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleDay
import com.servora.android.domain.model.ScheduleScope
import com.servora.android.domain.model.ScheduleScopeKind
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitStatus
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
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
 * What the technician's schedule is told, and what it asks [ScheduleRepository] for.
 *
 * Which Visits a day holds, their statuses and who is on them are the backend's answers
 * (`BR-001`, `BR-042`), and so is the scope the day was resolved for (`BR-009`). These tests
 * therefore assert that the day the screen holds is the day the read carries, that the read never
 * narrows itself to a technician the client chose, that a refresh of the day on screen never blanks
 * it, and that a day the technician has already left is released with its content.
 */
class TechnicianScheduleViewModelTest {

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
    fun `opens on today, reading the caller's own work`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())

        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        assertTrue(viewModel.uiState.value.showsInitialLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        // The filter is empty on purpose: the read is the caller's own work, and the API resolves
        // whose it is from the session (`BR-009`, `BR-007`).
        assertEquals(
            listOf(TechnicianScheduleRead("2026-09-07", TECH_DEVICE_ZONE, emptyList())),
            repository.requested,
        )
        assertEquals(TECH_MONDAY, state.selectedDate)
        assertEquals(TECH_MONDAY, state.displayedWeekStart)
        assertFalse(state.isRefreshing)
        assertEquals(listOf("visit-1"), state.visits.map { it.visitId })
        assertEquals("member-7", state.viewerMembershipId)
        assertFalse(state.showsLastReportedNotice)
        assertFalse(state.showsEmptyDay)
    }

    @Test
    fun `moves to another day and reads it, bringing its week with it`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
            ScheduleResult.Success(technicianDay(localDate = "2026-09-08")),
        )
        val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectDate(TECH_TUESDAY, DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(
            listOf(
                TechnicianScheduleRead("2026-09-07", TECH_DEVICE_ZONE, emptyList()),
                TechnicianScheduleRead("2026-09-08", TECH_DEVICE_ZONE, emptyList()),
            ),
            repository.requested,
        )
        val state = viewModel.uiState.value
        assertEquals(TECH_TUESDAY, state.selectedDate)
        assertEquals(TECH_MONDAY, state.displayedWeekStart)
        assertEquals("2026-09-08", state.schedule?.day?.localDate)
    }

    @Test
    fun `returning to the day already read asks nothing`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectDate(TECH_MONDAY, DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(1, repository.requested.size)
    }

    @Test
    fun `swiping the strip moves the week without reading a day nobody chose`() =
        runTest(dispatcher) {
            val repository = RecordingTechnicianScheduleRepository(
                ScheduleResult.Success(technicianDay()),
            )
            val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())
            viewModel.start(
                today = TECH_MONDAY,
                timeZoneId = TECH_DEVICE_ZONE,
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
            advanceUntilIdle()

            viewModel.showWeek(TECH_MONDAY.plusWeeks(1))
            advanceUntilIdle()

            assertEquals(1, repository.requested.size)
            val state = viewModel.uiState.value
            assertEquals(TECH_MONDAY.plusWeeks(1), state.displayedWeekStart)
            assertEquals(TECH_MONDAY, state.selectedDate)
        }

    @Test
    fun `the way back to today selects and reads today`() = runTest(dispatcher) {
        val later = TECH_MONDAY.plusWeeks(2)
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
            ScheduleResult.Success(technicianDay(localDate = later.toString())),
            ScheduleResult.Success(technicianDay()),
        )
        val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()
        viewModel.selectDate(later, DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.showToday(TECH_MONDAY, DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertEquals(
            TechnicianScheduleRead("2026-09-07", TECH_DEVICE_ZONE, emptyList()),
            repository.requested.last(),
        )
        assertEquals(TECH_MONDAY, viewModel.uiState.value.selectedDate)
    }

    @Test
    fun `keeps the day on screen when a refresh of it fails`() = runTest(dispatcher) {
        // The technician keeps reading the day they already have while the backend is unreachable
        // (`BR-013`): the failure is reported beside the content rather than replacing it.
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
            ScheduleResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("visit-1"), state.visits.map { it.visitId })
        assertEquals(CustomersFailureReason.NETWORK, state.failureReason)
        assertFalse(state.showsFailure)
        assertTrue(state.hasContent)
    }

    @Test
    fun `releases a day that belongs to the day left behind`() = runTest(dispatcher) {
        // Content is always the day it was read for: a day that fails to load reports the failure
        // rather than showing the previous day's Visits as if they were this one's (`BR-042`).
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
            ScheduleResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectDate(TECH_TUESDAY, DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.schedule)
        assertTrue(state.showsFailure)
        assertEquals(CustomersFailureReason.NETWORK, state.failureReason)
    }

    @Test
    fun `marks a day the working set answered rather than the backend`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(
                schedule = technicianDay(),
                source = ReadSource.WORKING_SET,
            ),
        )
        val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showsLastReportedNotice)
    }

    @Test
    fun `drops an answer belonging to a day the technician has already left`() =
        runTest(dispatcher) {
            val repository = RecordingTechnicianScheduleRepository(
                ScheduleResult.Success(technicianDay()),
                ScheduleResult.Success(technicianDay(localDate = TECH_TUESDAY.toString())),
            )
            val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())

            viewModel.start(
                today = TECH_MONDAY,
                timeZoneId = TECH_DEVICE_ZONE,
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
            // The day is moved on before the first answer lands, so that answer belongs to a day the
            // screen is no longer showing (`BR-067`).
            viewModel.selectDate(TECH_TUESDAY, DayOfWeek.MONDAY)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(TECH_TUESDAY, state.selectedDate)
            assertEquals("2026-09-08", state.schedule?.day?.localDate)
        }

    @Test
    fun `reads the caller's own requests when the requests view is opened`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Success(listOf(technicianRequest())),
        )
        val viewModel = scheduleViewModel(repository, requests)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        // Nothing is asked for while the schedule is the view on screen: the requests are the second
        // view's own read rather than part of the day's.
        assertEquals(0, requests.reads)

        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(TechnicianScheduleTab.REQUESTS, state.tab)
        assertEquals(1, requests.reads)
        // The rows are the API's answer for the caller's own membership (`BR-009`, `BR-FV-001`): the
        // ViewModel filters nothing and names no technician.
        assertEquals(listOf("request-1"), state.requests.map { it.id })
        assertEquals(1, state.awaitingReviewRequestCount)
        assertFalse(state.showsRequestsLoading)
        assertFalse(state.showsEmptyRequests)
    }

    @Test
    fun `leaves the day it was showing on screen when the requests view is opened`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Reading the requests is not a reason to forget which day the technician was on, and the day
        // is not read a second time for it (`BR-067`).
        assertEquals(TECH_MONDAY, state.selectedDate)
        assertEquals(listOf("visit-1"), state.visits.map { it.visitId })
        assertEquals(1, repository.requested.size)
    }

    @Test
    fun `does not read the day again when the schedule view is returned to`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val requests = RecordingTechnicianRequestsRepository()
        val viewModel = scheduleViewModel(repository, requests)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()
        viewModel.selectTab(TechnicianScheduleTab.SCHEDULE)
        advanceUntilIdle()

        // The day on screen is the answer the read already gave for the selected date; the
        // destination's own re-entry is where it is refreshed (`BR-012`, `BR-041`).
        assertEquals(TechnicianScheduleTab.SCHEDULE, viewModel.uiState.value.tab)
        assertEquals(1, repository.requested.size)
        assertEquals(1, requests.reads)
    }

    @Test
    fun `reports a failed request read instead of presenting an empty list`() = runTest(dispatcher) {
        val viewModel = scheduleViewModel(
            RecordingTechnicianScheduleRepository(ScheduleResult.Success(technicianDay())),
            RecordingTechnicianRequestsRepository(
                VisitRequestsResult.Failure(CustomersFailureReason.NETWORK),
            ),
        )
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CustomersFailureReason.NETWORK, state.requestsFailureReason)
        assertTrue(state.showsRequestsFailure)
        // A read that never arrived is not the answer "you have no requests" (`BR-042`).
        assertFalse(state.showsEmptyRequests)
        assertFalse(state.showsRequestsUnrefreshedNotice)
    }

    @Test
    fun `keeps the list it already had when a later request read fails`() = runTest(dispatcher) {
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Success(listOf(technicianRequest())),
            VisitRequestsResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = scheduleViewModel(
            RecordingTechnicianScheduleRepository(ScheduleResult.Success(technicianDay())),
            requests,
        )
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()
        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, requests.reads)
        // The list the backend last reported stays on screen, under the notice that says it is not a
        // current answer (`BR-013`, `BR-014`).
        assertEquals(listOf("request-1"), state.requests.map { it.id })
        assertTrue(state.showsRequestsUnrefreshedNotice)
        assertFalse(state.showsRequestsFailure)
        assertFalse(state.showsEmptyRequests)
    }

    @Test
    fun `counts only the requests the office still has to answer`() = runTest(dispatcher) {
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Success(
                listOf(
                    technicianRequest(id = "request-1"),
                    technicianRequest(
                        id = "request-2",
                        status = FollowUpVisitRequestStatus.NEEDS_CLARIFICATION,
                    ),
                    technicianRequest(id = "request-3", status = FollowUpVisitRequestStatus.APPROVED),
                    technicianRequest(id = "request-4", status = FollowUpVisitRequestStatus.REJECTED),
                ),
            ),
        )
        val viewModel = scheduleViewModel(
            RecordingTechnicianScheduleRepository(ScheduleResult.Success(technicianDay())),
            requests,
        )
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()
        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // A clarified request is unresolved rather than closed, so it is still awaiting an answer; an
        // approved or refused one is answered (`BR-FV-012`).
        assertEquals(2, state.awaitingReviewRequestCount)
        // The API's own order is kept: newest first, so a decision the office just made is at the top.
        assertEquals(
            listOf("request-1", "request-2", "request-3", "request-4"),
            state.requests.map { it.id },
        )
    }

    @Test
    fun `brings a decision the office made back when the screen is re-entered`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Success(listOf(technicianRequest())),
            VisitRequestsResult.Success(
                listOf(
                    technicianRequest(
                        status = FollowUpVisitRequestStatus.REJECTED,
                        reviewNote = "No further visit is required.",
                    ),
                ),
            ),
        )
        val viewModel = scheduleViewModel(repository, requests)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()
        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()

        // Coming back to the destination refreshes the view that is open, which is how a rejection
        // reaches the technician who raised the request (`BR-FV-013`).
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, requests.reads)
        assertEquals(FollowUpVisitRequestStatus.REJECTED, state.requests.single().status)
        assertEquals("No further visit is required.", state.requests.single().reviewNote)
        assertEquals(0, state.awaitingReviewRequestCount)
        // The day was not read again for the requests view (`BR-041`).
        assertEquals(1, repository.requested.size)
    }

    @Test
    fun `drops a request answer that arrives after the session ended`() = runTest(dispatcher) {
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Success(listOf(technicianRequest())),
        )
        val viewModel = scheduleViewModel(
            RecordingTechnicianScheduleRepository(ScheduleResult.Success(technicianDay())),
            requests,
        )
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        // The read is started and the session ends before it answers, so the answer belongs to a
        // session that is gone and is dropped (`BR-001`, `BR-067`).
        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        viewModel.reset()
        advanceUntilIdle()

        assertEquals(TechnicianScheduleUiState(), viewModel.uiState.value)
    }

    @Test
    fun `remembers nothing once the session ends`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val viewModel = scheduleViewModel(repository, RecordingTechnicianRequestsRepository())
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        advanceUntilIdle()

        viewModel.reset()

        assertEquals(TechnicianScheduleUiState(), viewModel.uiState.value)
    }

    @Test
    fun `answers a returned request and holds what the API answered`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Success(
                listOf(
                    technicianRequest(status = FollowUpVisitRequestStatus.NEEDS_CLARIFICATION),
                ),
            ),
        )
        // The API's own answer is what the screen presents: the request is the office's again, and the
        // answer itself is a message of the conversation the backend holds (`BR-001`, `BR-041`).
        requests.answeredWith = technicianRequest(
            status = FollowUpVisitRequestStatus.PENDING,
            version = 2,
            messages = listOf(
                technicianMessage(
                    id = "message-1",
                    authorKind = FollowUpVisitRequestMessageAuthorKind.REQUESTER,
                    body = "PN-4471.",
                ),
            ),
        )
        val viewModel = scheduleViewModel(repository, requests)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()

        val returned = viewModel.uiState.value.requests.single()
        assertTrue(returned.awaitsAnswer)

        viewModel.replyToRequest(returned, "PN-4471.")
        // A second answer while the first is on its way is not sent: one exchange, one answer
        // (`BR-031`).
        viewModel.replyToRequest(returned, "PN-4471.")
        assertTrue(viewModel.uiState.value.isReplyingTo("request-1"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("request-1" to "PN-4471."), requests.answers)
        assertFalse(state.isReplying)
        assertEquals("request-1", state.answeredRequestId)
        assertNull(state.replyFailureReason)
        val answered = state.requests.single()
        assertEquals(FollowUpVisitRequestStatus.PENDING, answered.status)
        assertEquals(2, answered.version)
        assertEquals(listOf("PN-4471."), answered.messages.map { it.body })
        assertFalse(answered.awaitsAnswer)

        // The report is released once the screen has shown it (`BR-FV-013`).
        viewModel.acknowledgeReply()
        assertNull(viewModel.uiState.value.answeredRequestId)
    }

    @Test
    fun `reports an answer the API refused instead of presenting it as recorded`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Success(
                listOf(
                    technicianRequest(status = FollowUpVisitRequestStatus.NEEDS_CLARIFICATION),
                ),
            ),
        )
        requests.answerFailure = CustomersFailureReason.VERSION_CONFLICT
        val viewModel = scheduleViewModel(repository, requests)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()

        val returned = viewModel.uiState.value.requests.single()
        viewModel.replyToRequest(returned, "PN-4471.")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CustomersFailureReason.VERSION_CONFLICT, state.replyFailureReason)
        assertNull(state.answeredRequestId)
        assertFalse(state.isReplying)
        // Nothing was recorded and nothing is presented as recorded: the request is exactly as the
        // backend still holds it (`BR-FV-013`, `BR-001`).
        assertEquals(returned, state.requests.single())
    }

    @Test
    fun `answers nothing when the request owes no answer`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Success(listOf(technicianRequest())),
        )
        val viewModel = scheduleViewModel(repository, requests)
        viewModel.start(today = TECH_MONDAY, timeZoneId = TECH_DEVICE_ZONE, firstDayOfWeek = DayOfWeek.MONDAY)
        viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
        advanceUntilIdle()

        val awaitingReview = viewModel.uiState.value.requests.single()
        assertFalse(awaitingReview.awaitsAnswer)

        viewModel.replyToRequest(awaitingReview, "Anything?")
        advanceUntilIdle()

        assertTrue(requests.answers.isEmpty())
        assertNull(viewModel.uiState.value.answeredRequestId)
        assertFalse(viewModel.uiState.value.isReplying)
    }

    @Test
    fun `reads the caller's own requests for a surface that shows one of them`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Success(listOf(technicianRequest())),
        )
        val viewModel = scheduleViewModel(repository, requests)

        // A details destination restored from a back stack composes with nothing read yet, so it asks
        // for the list the request is resolved from (`BR-FV-001`, `BR-013`).
        viewModel.openOwnRequests()
        advanceUntilIdle()

        assertEquals(1, requests.reads)
        assertEquals(TechnicianScheduleTab.REQUESTS, viewModel.uiState.value.tab)
        assertEquals(listOf("request-1"), viewModel.uiState.value.requests.map { it.id })
    }

    @Test
    fun `does not read the requests again for a surface showing one the list already holds`() =
        runTest(dispatcher) {
            val repository = RecordingTechnicianScheduleRepository(
                ScheduleResult.Success(technicianDay()),
            )
            val requests = RecordingTechnicianRequestsRepository(
                VisitRequestsResult.Success(listOf(technicianRequest())),
            )
            val viewModel = scheduleViewModel(repository, requests)
            viewModel.start(
                today = TECH_MONDAY,
                timeZoneId = TECH_DEVICE_ZONE,
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
            viewModel.selectTab(TechnicianScheduleTab.REQUESTS)
            advanceUntilIdle()

            viewModel.openOwnRequests()
            advanceUntilIdle()

            // The list on screen is the answer the request is read from, and opening one of its rows
            // does not replace it (`BR-013`).
            assertEquals(1, requests.reads)
        }

    @Test
    fun `retries a failed request read when a request is opened`() = runTest(dispatcher) {
        val repository = RecordingTechnicianScheduleRepository(
            ScheduleResult.Success(technicianDay()),
        )
        val requests = RecordingTechnicianRequestsRepository(
            VisitRequestsResult.Failure(CustomersFailureReason.NETWORK),
            VisitRequestsResult.Success(listOf(technicianRequest())),
        )
        val viewModel = scheduleViewModel(repository, requests)

        viewModel.openOwnRequests()
        advanceUntilIdle()
        assertEquals(1, requests.reads)

        // A read that failed answered nothing, so opening the request tries again rather than leaving
        // the destination on a failure the technician can do nothing about (`BR-012`).
        viewModel.openOwnRequests()
        advanceUntilIdle()

        assertEquals(2, requests.reads)
        assertEquals(listOf("request-1"), viewModel.uiState.value.requests.map { it.id })
    }
}

private val TECH_MONDAY: LocalDate = LocalDate.parse("2026-09-07")
private val TECH_TUESDAY: LocalDate = LocalDate.parse("2026-09-08")

private const val TECH_DEVICE_ZONE = "America/Toronto"

/** One read the ViewModel asked for, so a test can assert the day, the zone and the filter. */
private data class TechnicianScheduleRead(
    val localDate: String,
    val timeZone: String,
    val membershipIds: List<String>,
)

/** A [ScheduleRepository] that answers the results the test scripted, in order. */
private class RecordingTechnicianScheduleRepository(
    private vararg val results: ScheduleResult,
) : ScheduleRepository {

    val requested = mutableListOf<TechnicianScheduleRead>()

    override suspend fun loadSchedule(
        localDate: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleResult {
        requested += TechnicianScheduleRead(localDate, timeZone, membershipIds)
        val index = (requested.size - 1).coerceAtMost(results.lastIndex)
        return results[index]
    }
}

/** One day as the backend answers it for the caller's own work (`BR-009`). */
private fun technicianDay(
    localDate: String = "2026-09-07",
    visits: List<ScheduleVisit> = listOf(technicianVisit("visit-1")),
    membershipId: String = "member-7",
): Schedule = Schedule(
    day = ScheduleDay(localDate = localDate, timeZone = TECH_DEVICE_ZONE),
    scope = ScheduleScope(kind = ScheduleScopeKind.SELF, membershipId = membershipId),
    technicians = emptyList(),
    visits = visits,
    unassigned = emptyList(),
    unassignedTotal = 0,
    hasUnassignedLane = false,
)

/** One assigned Visit. */
private fun technicianVisit(
    visitId: String,
    status: VisitStatus = VisitStatus.SCHEDULED,
): ScheduleVisit = ScheduleVisit(
    visitId = visitId,
    visitStatus = status,
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
)

/**
 * A [VisitRequestsRepository] that answers the results the test scripted, in order.
 *
 * The read is the caller's own: the API answers a requester with their own rows, so the fake answers
 * one list and the test asserts that the ViewModel neither filters it nor names a technician
 * (`BR-009`, `BR-FV-001`). Nothing on this screen reviews a request, so the two review writes are
 * unreachable here and say so rather than pretending to answer.
 */
private class RecordingTechnicianRequestsRepository(
    private vararg val results: VisitRequestsResult,
) : VisitRequestsRepository {

    /** A read a test does not script answers that the caller has no request of their own. */
    constructor() : this(VisitRequestsResult.Success(emptyList()))

    var reads = 0
        private set

    override suspend fun loadRequests(): VisitRequestsResult {
        reads += 1
        return results[(reads - 1).coerceAtMost(results.lastIndex)]
    }

    override suspend fun askForClarification(
        request: FollowUpVisitRequest,
        note: String,
    ): VisitRequestReviewResult = error("the technician's schedule does not review requests")

    override suspend fun reject(
        request: FollowUpVisitRequest,
        note: String,
    ): VisitRequestReviewResult = error("the technician's schedule does not review requests")

    /** Every answer this repository was asked to send, as `requestId to body` (`BR-FV-012`). */
    val answers = mutableListOf<Pair<String, String>>()

    /** The request an answer is met with, or `null` to answer with the request that was sent. */
    var answeredWith: FollowUpVisitRequest? = null

    /** Why an answer was refused, when a test refuses one (`BR-FV-013`). */
    var answerFailure: CustomersFailureReason? = null

    override suspend fun reply(
        request: FollowUpVisitRequest,
        body: String,
    ): VisitRequestReviewResult {
        answers += request.id to body
        answerFailure?.let { return VisitRequestReviewResult.Failure(it) }
        return VisitRequestReviewResult.Success(answeredWith ?: request)
    }
}

/** One request as the API answers it for the technician who raised it (`BR-FV-001`). */
private fun technicianRequest(
    id: String = "request-1",
    status: FollowUpVisitRequestStatus = FollowUpVisitRequestStatus.PENDING,
    reviewNote: String? = null,
    version: Int = 1,
    messages: List<FollowUpVisitRequestMessage> = emptyList(),
): FollowUpVisitRequest = FollowUpVisitRequest(
    id = id,
    jobId = "job-1",
    sourceVisitId = "visit-1",
    requestingTechnicianMembershipId = "member-7",
    proposedStart = "2026-09-09T13:00:00.000Z",
    proposedEnd = "2026-09-09T14:00:00.000Z",
    reason = "The part did not fit.",
    sameTechnicianPreferred = true,
    status = status,
    reviewerMembershipId = null,
    reviewedAt = null,
    reviewNote = reviewNote,
    createdVisitId = null,
    version = version,
    createdAt = "2026-09-08T12:00:00.000Z",
    updatedAt = "2026-09-08T12:00:00.000Z",
    messages = messages,
)

/** One message of a request's clarification conversation, as the API answers it (`BR-FV-012`). */
private fun technicianMessage(
    id: String,
    authorKind: FollowUpVisitRequestMessageAuthorKind,
    body: String,
): FollowUpVisitRequestMessage = FollowUpVisitRequestMessage(
    id = id,
    authorKind = authorKind,
    body = body,
    recordedAt = "2026-09-08T13:00:00.000Z",
)

/** Constructs the ViewModel under test with an inert ad-hoc repository, so its reports are never sent. */
private fun scheduleViewModel(
    repository: ScheduleRepository,
    requests: VisitRequestsRepository,
): TechnicianScheduleViewModel =
    TechnicianScheduleViewModel(repository, requests, RecordingAdHocWorkReportsRepository())

/** An [AdHocWorkReportsRepository] that answers empty options and a successful submit. */
private class RecordingAdHocWorkReportsRepository : AdHocWorkReportsRepository {
    override suspend fun searchCustomers(query: String): AdHocReportCustomerOptionsResult =
        AdHocReportCustomerOptionsResult.Success(emptyList())

    override suspend fun listProperties(customerId: String): AdHocReportPropertyOptionsResult =
        AdHocReportPropertyOptionsResult.Success(emptyList())

    override suspend fun listJobs(customerId: String): AdHocReportJobOptionsResult =
        AdHocReportJobOptionsResult.Success(emptyList())

    override suspend fun submit(report: AdHocWorkReportDraft): AdHocWorkReportResult =
        AdHocWorkReportResult.Success
}

