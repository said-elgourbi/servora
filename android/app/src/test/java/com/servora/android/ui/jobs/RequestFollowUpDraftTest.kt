package com.servora.android.ui.jobs

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a follow-up request form must hold before it may be submitted (`BR-FV-003`).
 *
 * The rule is checked here rather than through the layout, so "a proposal needs a reason and a window"
 * is one statement a test can read instead of a detail of the form that presents it (`BR-041`).
 */
class RequestFollowUpDraftTest {

    private val start = Instant.parse("2026-09-15T13:00:00Z")

    @Test
    fun `accepts a proposal that states a reason and a window`() {
        assertNull(draft().problem)
    }

    @Test
    fun `refuses a proposal with no reason`() {
        // `BR-FV-003` requires a reason, and the route refuses a request without one, so the form says
        // what is missing instead of sending a question it already knows the answer to (`BR-042`).
        assertEquals(RequestFollowUpProblem.NO_REASON, draft(reason = "   ").problem)
    }

    @Test
    fun `refuses a proposal whose end is not after its start`() {
        // The proposal states a window like any other (`BR-072`), and the API validates the same rule.
        assertEquals(RequestFollowUpProblem.END_NOT_AFTER_START, draft(end = start).problem)
    }

    private fun draft(
        reason: String = "The part has to be ordered.",
        end: Instant = start.plusSeconds(7_200),
    ) = RequestFollowUpDraft(
        start = start,
        end = end,
        reason = reason,
        sameTechnicianPreferred = false,
    )
}
