package com.servora.android.ui.schedule

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.AdHocReportCustomerOption
import com.servora.android.domain.model.AdHocReportJobOption
import com.servora.android.domain.model.AdHocReportPropertyOption
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestMessage
import com.servora.android.domain.model.FollowUpVisitRequestMessageAuthorKind
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleAddress
import com.servora.android.domain.model.ScheduleDay
import com.servora.android.domain.model.ScheduleScope
import com.servora.android.domain.model.ScheduleScopeKind
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitOutcome
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.theme.ServoraTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the technician's schedule presents the caller's own day (`BR-010`, `BR-012`, `BR-041`, `BR-074`).
 *
 * The screen decides nothing about the work — every value it draws is the backend's answer for the
 * caller's own membership — so these tests cover the presentation the platform can check without a
 * phone: the week the header names, the day heading, a Visit's own facts and its **Visit** status, the
 * caller's own crew line, the empty day, and a read that failed offering a retry.
 */
@RunWith(AndroidJUnit4::class)
class TechnicianScheduleScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsTheCallersDayWithTheFactsFieldWorkNeeds() {
        render(state = technicianState(visits = listOf(technicianVisit())))

        composeTestRule.onNodeWithTag(TechnicianScheduleContentTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(technicianScheduleVisitTag("visit-1"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Furnace repair").assertIsDisplayed()
        composeTestRule.onNodeWithText("ABC Property Management").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("987 Cedar Lane, Montreal, QC, H3A 2T6")
            .assertIsDisplayed()
        // The status is the **Visit**'s (`BR-074`, `BR-059`), never the Job's.
        composeTestRule
            .onNodeWithText(string(R.string.visit_status_scheduled))
            .assertIsDisplayed()
    }

    @Test
    fun saysTheCallerIsOnTheCrew() {
        render(
            state = technicianState(
                visits = listOf(
                    technicianVisit(
                        crew = listOf(
                            crewMember("member-7", "Mike Johnson", roleCode = "LEAD"),
                            crewMember("member-2", "Sarah Kim"),
                        ),
                    ),
                ),
            ),
        )

        // "You + 1" is what the field asks: how many are coming with me (`BR-068`).
        composeTestRule
            .onNodeWithText(
                string(R.string.schedule_crew_more, string(R.string.technician_schedule_you), 1),
            )
            .assertIsDisplayed()
    }

    @Test
    fun namesTheCrewWhenTheCallerIsNotOnIt() {
        render(
            state = technicianState(
                visits = listOf(
                    technicianVisit(crew = listOf(crewMember("member-2", "Sarah Kim", roleCode = "LEAD"))),
                ),
            ),
        )

        composeTestRule.onNodeWithText("Sarah Kim").assertIsDisplayed()
    }

    @Test
    fun opensTheJobTheVisitBelongsTo() {
        var opened: String? = null
        render(
            state = technicianState(visits = listOf(technicianVisit())),
            onOpenJob = { jobId, _ -> opened = jobId },
        )

        composeTestRule.onNodeWithTag(technicianScheduleVisitTag("visit-1")).performClick()

        assertEquals("job-1", opened)
    }

    @Test
    fun saysWhenTheDayHasNothingAssigned() {
        render(state = technicianState(visits = emptyList()))

        composeTestRule.onNodeWithTag(TechnicianScheduleEmptyDayTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.technician_schedule_empty_title))
            .assertIsDisplayed()
    }

    @Test
    fun offersARetryWhenNothingCouldBeRead() {
        var retried = false
        render(
            state = TechnicianScheduleUiState(
                timeZoneId = TECHNICIAN_DEVICE_ZONE,
                selectedDate = SELECTED_DAY,
                displayedWeekStart = SELECTED_DAY,
                failureReason = CustomersFailureReason.NETWORK,
            ),
            onRetry = { retried = true },
        )

        composeTestRule.onNodeWithTag(TechnicianScheduleFailureTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TechnicianScheduleRetryTag).performClick()

        assertEquals(true, retried)
    }

    @Test
    fun offersTheRequestsViewOnlyToASessionThatMayReadIt() {
        render(state = technicianState(), canRequestFollowUpVisit = false)

        // The requests read is authorized by the asking capability (`BR-FV-001`, `BR-011`), so a
        // session without it is offered no view whose rows it could not fetch (`BR-007`).
        composeTestRule.onNodeWithTag(TechnicianScheduleTabSelectorTag).assertDoesNotExist()
    }

    @Test
    fun offersTheCallersOwnRequestsBesideTheirDay() {
        render(state = technicianState())

        composeTestRule.onNodeWithTag(TechnicianScheduleTabSelectorTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TechnicianScheduleTabScheduleTag).assertIsSelected()
        composeTestRule.onNodeWithTag(TechnicianScheduleTabRequestsTag).assertIsNotSelected()
    }

    @Test
    fun reportsTheViewTheTechnicianChose() {
        var chosen: TechnicianScheduleTab? = null
        render(state = technicianState(), onSelectTab = { tab -> chosen = tab })

        composeTestRule.onNodeWithTag(TechnicianScheduleTabRequestsTag).performClick()

        assertEquals(TechnicianScheduleTab.REQUESTS, chosen)
    }

    @Test
    fun countsTheRequestsStillAwaitingAnAnswer() {
        render(
            state = technicianState(
                requestsRead = true,
                requests = listOf(
                    technicianRequest(id = "request-1"),
                    technicianRequest(
                        id = "request-2",
                        status = FollowUpVisitRequestStatus.APPROVED,
                    ),
                ),
            ),
        )

        // The badge counts what the office has still to answer — one of the two here (`BR-FV-012`).
        composeTestRule.onNodeWithTag(TechnicianRequestsBadgeTag).assertIsDisplayed()
        composeTestRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun showsARejectedRequestWithTheReasonItWasRefused() {
        render(
            state = technicianState(
                tab = TechnicianScheduleTab.REQUESTS,
                requestsRead = true,
                requests = listOf(
                    technicianRequest(
                        status = FollowUpVisitRequestStatus.REJECTED,
                        reviewNote = "No further visit is required.",
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithTag(TechnicianRequestsContentTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianRequestTag("request-1")).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(technicianRequestStatusTag("request-1"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.request_status_rejected)).assertIsDisplayed()
        // The office's own words are what states why the request was refused (`BR-FV-013`).
        composeTestRule
            .onNodeWithTag(technicianRequestNoteTag("request-1"))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                string(R.string.technician_requests_note, "No further visit is required."),
            )
            .assertIsDisplayed()
        // The window stays a proposal rather than an appointment (`BR-FV-002`).
        composeTestRule.onNodeWithText("You proposed", substring = true).assertIsDisplayed()
    }

    @Test
    fun keepsTheDateControlsOffTheRequestsView() {
        render(
            state = technicianState(
                tab = TechnicianScheduleTab.REQUESTS,
                requestsRead = true,
                requests = listOf(technicianRequest()),
            ),
        )

        // No part of the requests view is scoped to the selected day, so no date control is drawn
        // beside it and nothing there can disagree with the day the schedule holds (`BR-FV-002`,
        // `BR-042`).
        composeTestRule.onNodeWithTag(TechnicianScheduleWeekRangeTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TechnicianScheduleDayHeadingTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TechnicianRequestsContentTag).assertIsDisplayed()
    }

    @Test
    fun saysWhenTheCallerHasAskedForNothing() {
        render(
            state = technicianState(tab = TechnicianScheduleTab.REQUESTS, requestsRead = true),
        )

        composeTestRule.onNodeWithTag(TechnicianRequestsEmptyTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.technician_requests_empty_title))
            .assertIsDisplayed()
    }

    @Test
    fun offersARetryWhenTheRequestsCouldNotBeRead() {
        var retried = false
        render(
            state = technicianState(
                tab = TechnicianScheduleTab.REQUESTS,
                requestsFailureReason = CustomersFailureReason.NETWORK,
            ),
            onRetry = { retried = true },
        )

        composeTestRule.onNodeWithTag(TechnicianRequestsFailureTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TechnicianRequestsRetryTag).performClick()

        assertEquals(true, retried)
    }

    @Test
    fun opensTheRequestACardIsFor() {
        var opened: String? = null
        render(
            state = technicianState(
                tab = TechnicianScheduleTab.REQUESTS,
                requestsRead = true,
                requests = listOf(technicianRequest()),
            ),
            onOpenRequest = { requestId -> opened = requestId },
        )

        composeTestRule.onNodeWithTag(technicianRequestTag("request-1")).performClick()

        // The request's own row opens the request rather than the Job it is about: a request is a
        // proposal about another field attempt, not the work (`BR-FV-002`), and the Job is one action
        // away from the request's details.
        assertEquals("request-1", opened)
    }

    @Test
    fun showsTheFirstReadAsLoading() {
        render(
            state = TechnicianScheduleUiState(
                timeZoneId = TECHNICIAN_DEVICE_ZONE,
                selectedDate = SELECTED_DAY,
                displayedWeekStart = SELECTED_DAY,
            ),
        )

        composeTestRule.onNodeWithTag(TechnicianScheduleLoadingTag).assertIsDisplayed()
    }

    @Test
    fun drawsTheClarificationConversationTheApiAnswered() {
        render(
            state = technicianState(
                tab = TechnicianScheduleTab.REQUESTS,
                requestsRead = true,
                requests = listOf(
                    technicianRequest(
                        status = FollowUpVisitRequestStatus.NEEDS_CLARIFICATION,
                        reviewNote = "Which part number?",
                        version = 2,
                        messages = listOf(
                            conversationMessage(
                                id = "message-1",
                                authorKind = FollowUpVisitRequestMessageAuthorKind.OFFICE,
                                body = "Which part number?",
                            ),
                            conversationMessage(
                                id = "message-2",
                                authorKind = FollowUpVisitRequestMessageAuthorKind.REQUESTER,
                                body = "PN-4471.",
                            ),
                        ),
                    ),
                ),
            ),
        )

        // The exchange is the request's own history, so both sides are on the card (`BR-FV-012`), and
        // the question is stated once — by the conversation rather than by the note as well (`BR-041`).
        composeTestRule
            .onNodeWithTag(technicianRequestConversationTag("request-1"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Which part number?").assertIsDisplayed()
        composeTestRule.onNodeWithText("PN-4471.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianRequestNoteTag("request-1")).assertDoesNotExist()
    }

    @Test
    fun offersNoAnswerToARequestThatOwesNone() {
        render(
            state = technicianState(
                tab = TechnicianScheduleTab.REQUESTS,
                requestsRead = true,
                requests = listOf(technicianRequest(reviewNote = "Please resend the estimate.")),
            ),
        )

        // A request awaiting the office is the office's to decide, and the note it wrote is still the
        // office's words (`BR-FV-013`).
        composeTestRule.onNodeWithTag(technicianRequestNoteTag("request-1")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianRequestAnswerTag("request-1")).assertDoesNotExist()
        composeTestRule
            .onNodeWithTag(technicianRequestConversationTag("request-1"))
            .assertDoesNotExist()
    }

    @Test
    fun answersAReturnedRequestWithWhatIsTyped() {
        var answered: Pair<FollowUpVisitRequest, String>? = null
        render(
            state = technicianState(
                tab = TechnicianScheduleTab.REQUESTS,
                requestsRead = true,
                requests = listOf(
                    technicianRequest(
                        status = FollowUpVisitRequestStatus.NEEDS_CLARIFICATION,
                        reviewNote = "Which part number?",
                        version = 2,
                        messages = listOf(
                            conversationMessage(
                                id = "message-1",
                                authorKind = FollowUpVisitRequestMessageAuthorKind.OFFICE,
                                body = "Which part number?",
                            ),
                        ),
                    ),
                ),
            ),
            onReplyToRequest = { request, body -> answered = request to body },
        )

        composeTestRule.onNodeWithTag(technicianRequestAnswerTag("request-1")).performClick()

        // The question is repeated beside the field, and the answer cannot be sent empty: the office
        // asked something (`BR-FV-012`, `BR-042`).
        composeTestRule.onNodeWithTag(TechnicianRequestAnswerDialogTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianRequestAnswerFieldTag("request-1")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianRequestAnswerSendTag("request-1")).assertIsNotEnabled()

        composeTestRule
            .onNodeWithTag(technicianRequestAnswerFieldTag("request-1"))
            .performTextInput("PN-4471.")
        composeTestRule.onNodeWithTag(technicianRequestAnswerSendTag("request-1")).performClick()

        assertEquals("request-1", answered?.first?.id)
        assertEquals("PN-4471.", answered?.second)
    }
}

/** The screen a test drives, and the callbacks it recorded. */
private fun TechnicianScheduleScreenTest.render(
    state: TechnicianScheduleUiState,
    canRequestFollowUpVisit: Boolean = true,
    canReportAdHocWork: Boolean = false,
    onSelectTab: (TechnicianScheduleTab) -> Unit = {},
    onSelectDate: (LocalDate) -> Unit = {},
    onShowToday: () -> Unit = {},
    onOpenJob: (String, String?) -> Unit = { _, _ -> },
    onOpenRequest: (String) -> Unit = {},
    onReplyToRequest: (FollowUpVisitRequest, String) -> Unit = { _, _ -> },
    onAcknowledgeReply: () -> Unit = {},
    onOpenAdHocReport: () -> Unit = {},
    onDismissAdHocReport: () -> Unit = {},
    onAdHocReportCustomerQueryChange: (String) -> Unit = {},
    onAdHocReportSelectCustomer: (AdHocReportCustomerOption) -> Unit = {},
    onAdHocReportSelectProperty: (AdHocReportPropertyOption) -> Unit = {},
    onAdHocReportSelectJob: (AdHocReportJobOption) -> Unit = {},
    onAdHocReportWorkStartedAtChange: (Instant) -> Unit = {},
    onAdHocReportWorkEndedAtChange: (Instant) -> Unit = {},
    onAdHocReportOutcomeChange: (VisitOutcome) -> Unit = {},
    onAdHocReportSummaryChange: (String) -> Unit = {},
    onAdHocReportNotesChange: (String) -> Unit = {},
    onAdHocReportUnknownCustomerChange: (Boolean) -> Unit = {},
    onAdHocReportReportedCustomerNameChange: (String) -> Unit = {},
    onAdHocReportReportedCustomerPhoneChange: (String) -> Unit = {},
    onAdHocReportReportedCustomerAddressChange: (String) -> Unit = {},
    onSubmitAdHocWorkReport: () -> Unit = {},
    onAcknowledgeAdHocWorkReport: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    composeTestRule.setContent {
        ServoraTheme {
            TechnicianScheduleScreen(
                state = state,
                canRequestFollowUpVisit = canRequestFollowUpVisit,
                canReportAdHocWork = canReportAdHocWork,
                onSelectTab = onSelectTab,
                onSelectDate = onSelectDate,
                onShowWeek = {},
                onShowToday = onShowToday,
                onOpenJob = onOpenJob,
                onOpenRequest = onOpenRequest,
                onReplyToRequest = onReplyToRequest,
                onAcknowledgeReply = onAcknowledgeReply,
                onOpenAdHocReport = onOpenAdHocReport,
                onDismissAdHocReport = onDismissAdHocReport,
                onAdHocReportCustomerQueryChange = onAdHocReportCustomerQueryChange,
                onAdHocReportSelectCustomer = onAdHocReportSelectCustomer,
                onAdHocReportSelectProperty = onAdHocReportSelectProperty,
                onAdHocReportSelectJob = onAdHocReportSelectJob,
                onAdHocReportWorkStartedAtChange = onAdHocReportWorkStartedAtChange,
                onAdHocReportWorkEndedAtChange = onAdHocReportWorkEndedAtChange,
                onAdHocReportOutcomeChange = onAdHocReportOutcomeChange,
                onAdHocReportSummaryChange = onAdHocReportSummaryChange,
                onAdHocReportNotesChange = onAdHocReportNotesChange,
                onAdHocReportUnknownCustomerChange = onAdHocReportUnknownCustomerChange,
                onAdHocReportReportedCustomerNameChange = onAdHocReportReportedCustomerNameChange,
                onAdHocReportReportedCustomerPhoneChange = onAdHocReportReportedCustomerPhoneChange,
                onAdHocReportReportedCustomerAddressChange = onAdHocReportReportedCustomerAddressChange,
                onSubmitAdHocWorkReport = onSubmitAdHocWorkReport,
                onAcknowledgeAdHocWorkReport = onAcknowledgeAdHocWorkReport,
                onRetry = onRetry,
            )
        }
    }
}

private fun TechnicianScheduleScreenTest.string(resId: Int, vararg formatArgs: Any): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId, *formatArgs)

/**
 * The caller's own day: one Visit they are on, unless the test says otherwise.
 *
 * The scope is `SELF`, which is the answer the API gives a field caller, and the lane is absent
 * because that scope has none (`BR-009`).
 */
private fun technicianState(
    visits: List<ScheduleVisit> = listOf(technicianVisit()),
    selectedDate: LocalDate = SELECTED_DAY,
    displayedWeekStart: LocalDate = SELECTED_DAY,
    source: ReadSource = ReadSource.BACKEND,
    tab: TechnicianScheduleTab = TechnicianScheduleTab.SCHEDULE,
    requests: List<FollowUpVisitRequest> = emptyList(),
    requestsRead: Boolean = false,
    requestsFailureReason: CustomersFailureReason? = null,
): TechnicianScheduleUiState = TechnicianScheduleUiState(
    timeZoneId = TECHNICIAN_DEVICE_ZONE,
    firstDayOfWeek = DayOfWeek.MONDAY,
    selectedDate = selectedDate,
    displayedWeekStart = displayedWeekStart,
    schedule = Schedule(
        day = ScheduleDay(localDate = selectedDate.toString(), timeZone = TECHNICIAN_DEVICE_ZONE),
        scope = ScheduleScope(kind = ScheduleScopeKind.SELF, membershipId = VIEWER_MEMBERSHIP_ID),
        technicians = emptyList(),
        visits = visits,
        unassigned = emptyList(),
        unassignedTotal = 0,
        hasUnassignedLane = false,
    ),
    source = source,
    // The view and the caller's own requests are forwarded rather than dropped: a test that asks for
    // the requests view renders the requests view, and one that asks for the day renders the day.
    tab = tab,
    requests = requests,
    requestsRead = requestsRead,
    requestsFailureReason = requestsFailureReason,
)

private fun crewMember(
    membershipId: String,
    name: String,
    roleCode: String = "TECHNICIAN",
): ScheduleTechnician = ScheduleTechnician(
    membershipId = membershipId,
    name = name,
    roleCode = roleCode,
)

private fun technicianVisit(
    visitId: String = "visit-1",
    status: VisitStatus = VisitStatus.SCHEDULED,
    crew: List<ScheduleTechnician> = listOf(
        crewMember(VIEWER_MEMBERSHIP_ID, "Mike Johnson", roleCode = "LEAD"),
    ),
) = ScheduleVisit(
    visitId = visitId,
    visitStatus = status,
    scheduledStart = "2026-09-07T13:00:00.000Z",
    scheduledEnd = "2026-09-07T14:00:00.000Z",
    jobId = "job-1",
    jobNumber = 1042,
    jobTitle = "Furnace repair",
    customerId = "customer-1",
    customerName = "ABC Property Management",
    address = ScheduleAddress(
        propertyName = null,
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = null,
    ),
    technicians = crew,
    isOverdue = false,
)

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
    requestingTechnicianMembershipId = VIEWER_MEMBERSHIP_ID,
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

/** One message of a request's clarification conversation (`BR-FV-012`). */
private fun conversationMessage(
    id: String,
    authorKind: FollowUpVisitRequestMessageAuthorKind,
    body: String,
): FollowUpVisitRequestMessage = FollowUpVisitRequestMessage(
    id = id,
    authorKind = authorKind,
    body = body,
    recordedAt = "2026-09-08T13:00:00.000Z",
)

private val SELECTED_DAY: LocalDate = LocalDate.parse("2026-09-07")

/** The membership the read was resolved for, which is how the screen recognises the caller. */
private const val VIEWER_MEMBERSHIP_ID = "member-7"

/** The zone the screen resolves its days in, so a test can ask what "today" is there. */
private const val TECHNICIAN_DEVICE_ZONE = "America/Toronto"
