package com.servora.android.data.jobs

import kotlinx.serialization.Serializable

/*
 * Wire contract for `GET /jobs/:id/activity` (`docs/api/job-activity.md`).
 *
 * These types mirror the JSON exactly (camelCase, UUID identifiers, ISO-8601 UTC timestamps) and stay
 * in the data layer: the rest of the app exchanges `com.servora.android.domain.model.JobActivityEvent`
 * instead.
 */

/** Response body of the Job Activity read (`BR-080`). */
@Serializable
data class JobActivityDto(
    val jobId: String,
    val events: List<JobActivityEventDto> = emptyList(),
)

/** One Job Activity event. Status and role values are the API's stable codes (`BR-041`). */
@Serializable
data class JobActivityEventDto(
    val id: String,
    val kind: String,
    val recordedAt: String,
    val actorName: String? = null,
    val visitSequence: Int? = null,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val technicianName: String? = null,
    val roleCode: String? = null,
    val previousRoleCode: String? = null,
    val outcomeCode: String? = null,
    val outcomeSummary: String? = null,
    val body: String? = null,
    val photoId: String? = null,
    val photoPhase: String? = null,
)
