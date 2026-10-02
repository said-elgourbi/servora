package com.servora.android.data.jobs

import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
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

/**
 * Request body that moves a Visit between its working statuses (`BR-074`, `BR-075`).
 *
 * It carries no outcome: finishing a Visit is its own business operation on its own route
 * (`CompleteVisitRequestDto`), and the API refuses an outcome here so a caller cannot believe one was
 * recorded by a route that does not record one (`BR-077`).
 *
 * `clientOperationId` is the idempotency key and `capturedAt` is the device instant the technician
 * acted; both are created once when the action is performed and reused for every attempt, so a
 * replay is applied at most once (`BR-031`). `expectedVersion` is the Visit version the screen last
 * saw, so a Visit that moved on is refused rather than overwritten (`BR-086`, `ADR-019` D5).
 * `confirmConflicts` is true only when the technician accepted the `BR-070` conflicts the API
 * reported for a previous attempt at the same operation.
 */
@Serializable
data class ChangeVisitStatusRequestDto(
    val status: String,
    val clientOperationId: String? = null,
    val capturedAt: String? = null,
    val expectedVersion: Int? = null,
    val confirmConflicts: Boolean = false,
)

/**
 * Request body of the explicit Visit completion operation (`BR-077`, `BR-078`; `BR-093`).
 *
 * The outcome the API requires of every completion travels here rather than on the status route, so
 * "what resulted from the attempt" is its own recorded operation. `outcomeSummary` is required — it is
 * the reason for `UNABLE_TO_COMPLETE` and the summary for every other outcome — and the API refuses a
 * completion without it.
 */
@Serializable
data class CompleteVisitRequestDto(
    val outcomeCode: String,
    val outcomeSummary: String,
    val clientOperationId: String? = null,
    val capturedAt: String? = null,
    val expectedVersion: Int? = null,
)

/** Request body that adds one text update to a Visit's activity (`BR-027`, `BR-077`). */
@Serializable
data class AddVisitNoteRequestDto(
    val body: String,
    /**
     * The idempotency key, so a queued note replayed after a timeout is recorded at most once
     * (`BR-031`). A note carries no version, because it is append-only and has no update path
     * (`ADR-019` D5).
     */
    val clientOperationId: String? = null,
    /** The device instant the note was written; provenance beside the server's own instant. */
    val capturedAt: String? = null,
)

/** Request body that corrects one text update after it was posted. */
@Serializable
data class EditVisitNoteRequestDto(
    val body: String,
)

/** Request body that soft-deletes one text update from ordinary activity. */
@Serializable
data class RemoveVisitNoteRequestDto(
    val reason: String,
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

/**
 * Request body that takes one recording out of ordinary use (`BR-089`, `ADR-018` A7).
 *
 * It is the audio kind's own body rather than a shared one, because the two removals are authorized by
 * different capabilities on different routes; the shape is the same because a removal of evidence is
 * one operation (`BR-041`, `BR-089`).
 */
@Serializable
data class RemoveJobAudioNoteRequestDto(
    val reason: String,
)

/**
 * Request body that schedules one further Visit on an existing Job (`BR-071`, `BR-072`).
 *
 * The crew is stated in full with exactly one `LEAD`, because a Visit that is scheduled carries one
 * (`BR-068`), and the API refuses a crew without it rather than promoting anybody on its own
 * (`docs/api/visit-requests.md`). `confirmConflicts` is true only when the user has been shown the
 * `BR-070` conflicts the API reported for a previous attempt at the same schedule.
 */
@Serializable
data class CreateVisitRequestDto(
    val scheduledStart: String,
    val scheduledEnd: String,
    val technicians: List<TechnicianAssignmentRequestDto>,
    val confirmConflicts: Boolean = false,
)

/**
 * Request body that approves a follow-up Visit request into a scheduled Visit (`BR-FV-005`).
 *
 * [expectedStatus] and [expectedVersion] are the request's own state as the client read it, so an
 * approval is refused rather than applied when the request moved on (`BR-086`, `BR-032`): the
 * reviewer decides about the request they were shown. [note] is the optional review note the route
 * records with the approval.
 */
@Serializable
data class ApproveVisitRequestRequestDto(
    val scheduledStart: String,
    val scheduledEnd: String,
    val technicians: List<TechnicianAssignmentRequestDto>,
    val expectedStatus: String,
    val expectedVersion: Int,
    val confirmConflicts: Boolean = false,
    val note: String? = null,
)

/**
 * Request body that submits a follow-up Visit request (`BR-FV-001`, `BR-FV-003`, `BR-FV-008`).
 *
 * It is what a technician proposes, not a decision: the request is not a Visit and does not schedule
 * one (`BR-FV-002`), so the body carries the window the technician suggests rather than a confirmed
 * appointment, and it names no crew — who performs the follow-up is the office's decision
 * (`BR-FV-004`). [sourceVisitId] is the field attempt the request grew from, which `BR-FV-008` requires
 * a request to keep so the business can trace why it was made.
 */
@Serializable
data class SubmitVisitRequestRequestDto(
    val sourceVisitId: String,
    val proposedStart: String,
    val proposedEnd: String,
    val reason: String,
    val sameTechnicianPreferred: Boolean = false,
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

/**
 * Maps a requested crew onto the wire shape both Visit-scheduling routes exchange (`BR-068`).
 *
 * One mapping serves stating an existing Visit's crew and scheduling a Visit with its crew, because
 * the API states a crew one way and a second mapping would be a second answer to the same question
 * (`BR-041`).
 */
internal fun List<TechnicianAssignment>.toRequestedTechnicians(): List<TechnicianAssignmentRequestDto> =
    map { assignment ->
        TechnicianAssignmentRequestDto(
            membershipId = assignment.membershipId,
            roleCode = assignment.role.toRoleCode(),
        )
    }
