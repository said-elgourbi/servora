package com.servora.android.data.schedule

import kotlinx.serialization.Serializable

/** Wire contracts for follow-up Visit requests (`docs/api/visit-requests.md`). */
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
)

@Serializable
data class FollowUpVisitReviewRequestDto(
    val note: String? = null,
    val expectedStatus: String? = null,
    val expectedVersion: Int? = null,
)
