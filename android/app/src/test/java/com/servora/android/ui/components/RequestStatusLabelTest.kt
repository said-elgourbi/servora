package com.servora.android.ui.components

import com.servora.android.R
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What each follow-up request status is labelled with (`BR-028`, `BR-041`, `BR-FV-012`).
 *
 * The mapping is pure, so it is asserted without a device. Every status has a label of its own, and a
 * clarified request carries the office screen's own words for it: it is **one** status, so the two
 * screens that present it must not describe it in two vocabularies (`BR-041`).
 */
class RequestStatusLabelTest {

    @Test
    fun `labels every request status with its own words`() {
        assertEquals(
            FollowUpVisitRequestStatus.entries.size,
            FollowUpVisitRequestStatus.entries.map { status -> requestStatusLabel(status) }.toSet().size,
        )
        assertEquals(
            R.string.request_status_pending,
            requestStatusLabel(FollowUpVisitRequestStatus.PENDING),
        )
        assertEquals(
            R.string.schedule_request_needs_clarification,
            requestStatusLabel(FollowUpVisitRequestStatus.NEEDS_CLARIFICATION),
        )
        assertEquals(
            R.string.request_status_approved,
            requestStatusLabel(FollowUpVisitRequestStatus.APPROVED),
        )
        assertEquals(
            R.string.request_status_rejected,
            requestStatusLabel(FollowUpVisitRequestStatus.REJECTED),
        )
    }
}
