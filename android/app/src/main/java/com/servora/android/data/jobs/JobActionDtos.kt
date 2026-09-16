package com.servora.android.data.jobs

import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.ScheduleConflict
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire contract for the Job and Visit management actions (`docs/api/job-actions.md`).
 *
 * These types mirror the JSON exactly (camelCase, UUID identifiers, ISO-8601 UTC instants) and stay
 * in the data layer: the rest of the app exchanges `com.servora.android.domain.model` types instead.
 */

/** Request body of a Job status change (`BR-058`). */
@Serializable
data class ChangeJobStatusRequestDto(
    val status: String,
    val note: String? = null,
    /** The version the client last saw, so a change against stale state is refused (`BR-086`). */
    val expectedVersion: Int? = null,
)

/** Request body of a Visit reschedule (`BR-072`, `BR-073`). */
@Serializable
data class RescheduleVisitRequestDto(
    val scheduledStart: String,
    val scheduledEnd: String,
    val arrivalWindowStart: String? = null,
    val arrivalWindowEnd: String? = null,
    val reason: String? = null,
    /** Whether the user accepted the availability conflicts the API reported (`BR-070`). */
    val confirmConflicts: Boolean = false,
    val expectedVersion: Int? = null,
)

/** Request body that states the whole crew a Visit carries (`BR-068`, `BR-069`). */
@Serializable
data class AssignVisitTechniciansRequestDto(
    val technicians: List<TechnicianAssignmentRequestDto>,
    val confirmConflicts: Boolean = false,
    val expectedVersion: Int? = null,
)

/** Request body that adds one text update to a Visit's activity (`BR-027`, `BR-077`). */
@Serializable
data class AddVisitNoteRequestDto(
    val body: String,
)

/**
 * Request body that takes one photo out of ordinary use (`BR-089`).
 *
 * The reason is required: a removal records the actor, the instant and the reason. The actor and the
 * instant are not sent — they come from the session and the backend's own clock (`BR-001`, `BR-031`).
 */
@Serializable
data class RemoveJobPhotoRequestDto(
    val reason: String,
)

/** One technician in an assignment request, with the role they hold on the Visit. */
@Serializable
data class TechnicianAssignmentRequestDto(
    val membershipId: String,
    val roleCode: String,
)

/** One technician the assign sheet may offer (`BR-024`). */
@Serializable
data class AssignableTechnicianDto(
    val membershipId: String,
    val name: String? = null,
)

/**
 * The API's error envelope, read only when a refusal carries something the user must see.
 *
 * It mirrors the documented envelope (`dev.md` §7): a stable machine-readable [code], a
 * user-facing [message] and optional structured [details].
 */
@Serializable
data class ApiErrorDto(
    @SerialName("statusCode") val statusCode: Int? = null,
    val code: String? = null,
    val message: String? = null,
    val details: ApiErrorDetailsDto? = null,
)

/** The structured details a refusal may carry. Only the conflicts are read today (`BR-070`). */
@Serializable
data class ApiErrorDetailsDto(
    val conflicts: List<ScheduleConflictDto> = emptyList(),
)

/** One overlapping Visit the API reported (`BR-070`). */
@Serializable
data class ScheduleConflictDto(
    val visitId: String,
    val jobNumber: Int = 0,
    val technicianName: String? = null,
    val scheduledStart: String,
    val scheduledEnd: String,
)

/** Maps a reported conflict onto the domain value the confirmation dialog presents. */
internal fun ScheduleConflictDto.toConflict(): ScheduleConflict =
    ScheduleConflict(
        visitId = visitId,
        jobNumber = jobNumber,
        technicianName = technicianName,
        scheduledStart = scheduledStart,
        scheduledEnd = scheduledEnd,
    )

/** The wire value of an assignment role (`BR-068`). */
internal fun AssignmentRole.toRoleCode(): String = name
