package com.servora.android.data.schedule

import kotlinx.serialization.Serializable

/** Wire contracts for follow-up Visit requests (`docs/api/visit-requests.md`). */
@Serializable
data class FollowUpVisitRequestMessageDto(
    val id: String,
    val authorMembershipId: String,
    val authorKind: String,
    val body: String,
    val recordedAt: String,
)

@Serializable
data class FollowUpVisitRequestDto(
    val id: String,
    val jobId: String,
    val sourceVisitId: String? = null,
    val requestingTechnicianMembershipId: String,
    val proposedStart: String,
    val proposedEnd: String,
    val reason: String,
    val sameTechnicianPreferred: Boolean = false,
    val status: String,
    val reviewerMembershipId: String? = null,
    val reviewedAt: String? = null,
    val reviewNote: String? = null,
    val createdVisitId: String? = null,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<FollowUpVisitRequestMessageDto> = emptyList(),
    val jobNumber: Int = 0,
    val jobTitle: String = "",
    val customerName: String = "",
    val address: ScheduleAddressDto? = null,
    val sourceVisitScheduledStart: String? = null,
    val sourceVisitStatus: String? = null,
    val sourceVisitOutcomeCode: String? = null,
)

@Serializable
data class FollowUpVisitReviewRequestDto(
    val note: String? = null,
    val expectedStatus: String? = null,
    val expectedVersion: Int? = null,
)

/**
 * The requester's answer to a request the office returned for clarification (`BR-FV-012`).
 *
 * The body is required by the route: an answer that says nothing is not an answer, and the state the
 * caller read is stated so the answer is refused rather than applied when the request has moved on
 * (`BR-032`).
 */
@Serializable
data class FollowUpVisitReplyRequestDto(
    val body: String,
    val expectedStatus: String? = null,
    val expectedVersion: Int? = null,
)
