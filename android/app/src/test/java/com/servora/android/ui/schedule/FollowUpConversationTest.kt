package com.servora.android.ui.schedule

import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestMessage
import com.servora.android.domain.model.FollowUpVisitRequestMessageAuthorKind
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a request's clarification conversation is presented (`BR-FV-012`).
 *
 * The conversation itself is the API's: which messages exist, who wrote them, what they say and when
 * they were recorded are the backend's answers (`BR-001`, `BR-041`). These tests assert the decisions
 * the screens make from them — which speaker a message is attributed to, and whether a card states the
 * office's note, the conversation, or both.
 */
class FollowUpConversationTest {

    @Test
    fun `states the conversation whenever the request carries one`() {
        assertFalse(request().showsConversation)
        assertTrue(request(messages = listOf(message())).showsConversation)
    }

    @Test
    fun `states the office's note while no conversation holds it`() {
        // A note and no exchange: the note is the record, and it is stated (`BR-FV-013`).
        assertTrue(request(reviewNote = "Customer declined the return visit.").showsOfficeNote)
        assertFalse(request(reviewNote = "   ").showsOfficeNote)
        assertFalse(request().showsOfficeNote)
    }

    @Test
    fun `leaves the office's question to the conversation that holds it`() {
        // The office's question is a message of the exchange, so the card states the thread rather than
        // the same sentence twice (`BR-041`).
        val clarified = request(
            status = FollowUpVisitRequestStatus.NEEDS_CLARIFICATION,
            reviewNote = "Which part number?",
            messages = listOf(message(body = "Which part number?")),
        )

        assertFalse(clarified.showsOfficeNote)
        assertTrue(clarified.showsConversation)
    }

    @Test
    fun `states a terminal decision's note even when the request was clarified before it`() {
        val rejected = request(
            status = FollowUpVisitRequestStatus.REJECTED,
            reviewNote = "Customer declined the return visit.",
            messages = listOf(message(), message(body = "PN-4471.")),
        )

        assertTrue(rejected.showsOfficeNote)
        assertTrue(rejected.showsConversation)
    }

    @Test
    fun `aligns the reader's own messages as their side of the exchange`() {
        assertTrue(
            conversationMessageBelongsToReader(
                FollowUpVisitRequestMessageAuthorKind.REQUESTER,
                readerIsRequester = true,
            ),
        )
        assertFalse(
            conversationMessageBelongsToReader(
                FollowUpVisitRequestMessageAuthorKind.REQUESTER,
                readerIsRequester = false,
            ),
        )
        assertTrue(
            conversationMessageBelongsToReader(
                FollowUpVisitRequestMessageAuthorKind.OFFICE,
                readerIsRequester = false,
            ),
        )
        assertFalse(
            conversationMessageBelongsToReader(
                FollowUpVisitRequestMessageAuthorKind.OFFICE,
                readerIsRequester = true,
            ),
        )
    }

    @Test
    fun `attributes a message to the reader's own side`() {
        assertEquals(
            FollowUpConversationSpeaker.YOU,
            conversationSpeaker(
                FollowUpVisitRequestMessageAuthorKind.REQUESTER,
                readerIsRequester = true,
            ),
        )
        assertEquals(
            FollowUpConversationSpeaker.TECHNICIAN,
            conversationSpeaker(
                FollowUpVisitRequestMessageAuthorKind.REQUESTER,
                readerIsRequester = false,
            ),
        )
        // The office is the office to whoever reads it: the request records a decision rather than an
        // author, so a manager reading a colleague's question is not addressed by name (`BR-033`).
        assertEquals(
            FollowUpConversationSpeaker.OFFICE,
            conversationSpeaker(
                FollowUpVisitRequestMessageAuthorKind.OFFICE,
                readerIsRequester = true,
            ),
        )
        assertEquals(
            FollowUpConversationSpeaker.OFFICE,
            conversationSpeaker(
                FollowUpVisitRequestMessageAuthorKind.OFFICE,
                readerIsRequester = false,
            ),
        )
    }

    @Test
    fun `presents a message's moment in the reader's own zone`() {
        val recorded = message(recordedAt = "2026-09-08T02:30:00.000Z")

        val inToronto = conversationMoment(recorded, ZoneId.of("America/Toronto"), Locale.CANADA)
        val inUtc = conversationMoment(recorded, ZoneId.of("UTC"), Locale.CANADA)

        assertTrue(inToronto != null)
        assertNotEquals(inToronto, inUtc)
    }

    @Test
    fun `states no moment for a recorded time it cannot read`() {
        val unreadable = message(recordedAt = "not-an-instant")

        assertNull(conversationMoment(unreadable, ZoneId.of("UTC"), Locale.CANADA))
    }

    @Test
    fun `owes an answer only while the office waits for one`() {
        assertTrue(request(status = FollowUpVisitRequestStatus.NEEDS_CLARIFICATION).awaitsAnswer)
        assertFalse(request().awaitsAnswer)
        assertFalse(request(status = FollowUpVisitRequestStatus.APPROVED).awaitsAnswer)
        assertFalse(request(status = FollowUpVisitRequestStatus.REJECTED).awaitsAnswer)
    }
}

/** One request as the API answers it, with the fields these presentation decisions read. */
private fun request(
    status: FollowUpVisitRequestStatus = FollowUpVisitRequestStatus.PENDING,
    reviewNote: String? = null,
    messages: List<FollowUpVisitRequestMessage> = emptyList(),
): FollowUpVisitRequest = FollowUpVisitRequest(
    id = "request-1",
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
    version = 2,
    createdAt = "2026-09-08T12:00:00.000Z",
    updatedAt = "2026-09-08T13:00:00.000Z",
    messages = messages,
)

/** One message of a request's clarification conversation (`BR-FV-012`). */
private fun message(
    body: String = "Which part number?",
    authorKind: FollowUpVisitRequestMessageAuthorKind = FollowUpVisitRequestMessageAuthorKind.OFFICE,
    recordedAt: String = "2026-09-08T13:00:00.000Z",
): FollowUpVisitRequestMessage = FollowUpVisitRequestMessage(
    id = "message-1",
    authorKind = authorKind,
    body = body,
    recordedAt = recordedAt,
)
