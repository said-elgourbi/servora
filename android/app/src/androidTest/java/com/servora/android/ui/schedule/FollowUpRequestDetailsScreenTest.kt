package com.servora.android.ui.schedule

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestMessage
import com.servora.android.domain.model.FollowUpVisitRequestMessageAuthorKind
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One of the technician's own follow-up requests, read in full (`BR-FV-002`, `BR-FV-012`, `BR-FV-013`).
 *
 * The destination decides nothing about the request — every value it draws is the backend's answer for
 * the caller's own read — so these tests cover the presentation the platform can check without a phone:
 * the request's own facts, the office's words and the conversation, the action that leaves for the Job,
 * the answer a returned request offers, and the three states a request the list does not hold can be in.
 */
@RunWith(AndroidJUnit4::class)
class FollowUpRequestDetailsScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsTheRequestAndLeavesTheJobOneActionAway() {
        var openedJob: String? = null
        render(
            state = detailsState(requests = listOf(detailsRequest())),
            onOpenJob = { jobId -> openedJob = jobId },
        )

        composeTestRule.onNodeWithTag(technicianRequestDetailTag("request-1")).assertIsDisplayed()
        composeTestRule.onNodeWithText("The part did not fit.").assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(technicianRequestStatusTag("request-1"))
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithTag(technicianRequestDetailOpenJobTag("request-1"))
            .performScrollTo()
            .performClick()

        assertEquals("job-1", openedJob)
    }

    @Test
    fun statesTheOfficeQuestionOnceAndAnswersItFromTheRequest() {
        var answered: Pair<FollowUpVisitRequest, String>? = null
        render(
            state = detailsState(
                requests = listOf(
                    detailsRequest(
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
            onAnswerRequest = { request, body -> answered = request to body },
        )

        // The exchange is the request's own history (`BR-FV-012`), and the question is stated once — by
        // the conversation rather than by the note as well (`BR-041`).
        composeTestRule
            .onNodeWithTag(technicianRequestConversationTag("request-1"))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Which part number?").assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianRequestNoteTag("request-1")).assertDoesNotExist()

        composeTestRule
            .onNodeWithTag(technicianRequestAnswerTag("request-1"))
            .performScrollTo()
            .performClick()

        // The question is repeated beside the field, and the answer cannot be sent empty (`BR-042`).
        composeTestRule.onNodeWithTag(TechnicianRequestAnswerDialogTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianRequestAnswerSendTag("request-1")).assertIsNotEnabled()

        composeTestRule
            .onNodeWithTag(technicianRequestAnswerFieldTag("request-1"))
            .performTextInput("PN-4471.")
        composeTestRule.onNodeWithTag(technicianRequestAnswerSendTag("request-1")).performClick()

        assertEquals("request-1", answered?.first?.id)
        assertEquals("PN-4471.", answered?.second)
    }

    @Test
    fun offersNoAnswerToARequestThatOwesNone() {
        render(
            state = detailsState(
                requests = listOf(detailsRequest(reviewNote = "Please resend the estimate.")),
            ),
        )

        // A request awaiting the office is the office's to decide, and the note it wrote is still the
        // office's words (`BR-FV-013`).
        composeTestRule
            .onNodeWithTag(technicianRequestNoteTag("request-1"))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianRequestAnswerTag("request-1")).assertDoesNotExist()
    }

    @Test
    fun reportsARequestReadThatFailedAndOffersARetry() {
        var retried = false
        render(
            state = detailsState(
                requests = emptyList(),
                requestsRead = false,
                requestsFailureReason = CustomersFailureReason.NETWORK,
            ),
            onRetry = { retried = true },
        )

        // The request is resolved from the caller's own read, so a read that failed is reported rather
        // than presented as a request with nothing in it (`BR-013`, `BR-042`).
        composeTestRule.onNodeWithTag(FollowUpRequestDetailsFailureTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(FollowUpRequestDetailsRetryTag).performClick()

        assertEquals(true, retried)
    }

    @Test
    fun saysWhenTheCallersOwnReadDoesNotHoldTheRequest() {
        render(state = detailsState(requests = emptyList(), requestsRead = true))

        // The read answered and this request is not one of the caller's own: the answer is stated
        // plainly and no request is invented for it (`BR-042`).
        composeTestRule.onNodeWithTag(FollowUpRequestDetailsUnavailableTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianRequestDetailTag("request-1")).assertDoesNotExist()
    }

    @Test
    fun statesTheFirstReadAsLoading() {
        render(state = detailsState(requests = emptyList(), requestsRead = false))

        composeTestRule.onNodeWithTag(FollowUpRequestDetailsLoadingTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(FollowUpRequestDetailsUnavailableTag).assertDoesNotExist()
    }
}

/** The destination a test drives, and the callbacks it recorded. */
private fun FollowUpRequestDetailsScreenTest.render(
    state: TechnicianScheduleUiState,
    requestId: String = "request-1",
    onAnswerRequest: (FollowUpVisitRequest, String) -> Unit = { _, _ -> },
    onAcknowledgeReply: () -> Unit = {},
    onOpenJob: (String) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    composeTestRule.setContent {
        ServoraTheme {
            FollowUpRequestDetailsScreen(
                state = state,
                requestId = requestId,
                onAnswerRequest = onAnswerRequest,
                onAcknowledgeReply = onAcknowledgeReply,
                onOpenJob = onOpenJob,
                onRetry = onRetry,
            )
        }
    }
}

/** The state a destination that shows one of the caller's own requests is handed (`BR-009`). */
private fun detailsState(
    requests: List<FollowUpVisitRequest> = listOf(detailsRequest()),
    requestsRead: Boolean = true,
    requestsFailureReason: CustomersFailureReason? = null,
): TechnicianScheduleUiState = TechnicianScheduleUiState(
    timeZoneId = DETAILS_DEVICE_ZONE,
    requestsRead = requestsRead,
    requests = requests,
    requestsFailureReason = requestsFailureReason,
)

/** One request as the API answers it for the technician who raised it (`BR-FV-001`). */
private fun detailsRequest(
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

/** The zone the screen resolves the conversation's moments in. */
private const val DETAILS_DEVICE_ZONE = "America/Toronto"

