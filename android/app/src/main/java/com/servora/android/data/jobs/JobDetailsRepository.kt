package com.servora.android.data.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.couldNotReachBackend
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsTechnician
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.VisitStatus
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * Reads one Job from the backend.
 *
 * The backend authorizes the read, owns the tenant boundary and derives which Visit represents the
 * Job and who is assigned to it, so this layer never names an organization and never decides either
 * (`BR-001`, `BR-007`, `BR-068`, `BR-081`).
 */
interface JobDetailsRepository {
    /**
     * Returns the Job, or why it could not be read.
     *
     * A Job the API cannot be reached for is served from the last Job the backend reported
     * (`offline-first-architecture.md` §2, §12), which is what keeps the evidence a Job holds and the
     * Activity that projects it visible without connectivity (`D5`, `BR-013`).
     */
    suspend fun loadJobDetails(jobId: String): JobDetailsResult

    /**
     * Returns the Job's chronological activity, newest first, or why it could not be read.
     *
     * It follows the same read policy as the Job itself: the API's answer when it can be reached, the
     * last one it reported when it cannot (`offline-first-architecture.md` §12, `D5`).
     */
    suspend fun loadJobActivity(jobId: String): JobActivityResult

    /** Adds one text update to the represented Visit and returns the refreshed activity. */
    suspend fun addVisitNote(
        jobId: String,
        visitId: String,
        body: String,
    ): VisitNoteResult

    /**
     * Moves the Job to [status] (`BR-058`).
     *
     * [expectedVersion] is the Job version the screen last saw: the API refuses the change when the
     * Job has moved on, rather than overwriting a newer state (`BR-086`).
     */
    suspend fun changeJobStatus(
        jobId: String,
        status: JobStatus,
        note: String?,
        expectedVersion: Int,
    ): JobActionResult

    /**
     * Edits the represented Visit's schedule (`BR-073`).
     *
     * [confirmConflicts] is `true` only when the user has been shown the availability conflicts the
     * API reported and accepted them (`BR-070`).
     */
    suspend fun rescheduleVisit(
        jobId: String,
        visitId: String,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        reason: String?,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult

    /**
     * States the whole crew the Visit carries (`BR-068`, `BR-069`).
     *
     * The assignment is stated in full with exactly one Lead, so removing the current Lead and
     * choosing the next one is one action the API applies without deciding anything for the user.
     */
    suspend fun assignVisitTechnicians(
        jobId: String,
        visitId: String,
        assignments: List<TechnicianAssignment>,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult

    /** The organization's technicians, for choosing a crew (`BR-024`). */
    suspend fun loadAssignableTechnicians(): AssignableTechniciansResult
}

/**
 * Default [JobDetailsRepository].
 *
 * It keeps the same session contract as the other repositories: the access token is read from
 * [SessionAuthenticator], and a call the backend refuses with `401` is retried once with a renewed
 * session before the outcome is reported.
 *
 * The actions are **online-only**: an action is not queued, because the backend's answer is the only
 * outcome reported (`BR-001`). The two **reads** follow the offline standard
 * (`docs/architecture/offline-first-architecture.md` §12, §13): the Job and its activity are kept as
 * the last answer the backend reported and served from there when the API cannot be reached, so the
 * evidence a Job holds and the Activity around it stay readable without connectivity (`D5`, `BR-013`).
 * Only a failure that could not reach the backend falls back: an answer, a refusal included, is never
 * replaced by a local copy (`BR-007`, `BR-042`).
 */
class DefaultJobDetailsRepository @Inject constructor(
    private val api: JobDetailsApi,
    private val sessionAuthenticator: SessionAuthenticator,
    /** The last Job and activity the backend reported, for a read that cannot reach it (§2, §12). */
    private val evidence: JobEvidenceCache,
    /** The subject the working set is partitioned by; local answers are never read across sessions. */
    private val subject: AuthenticatedSubject,
    /** Reads the API's error envelope, so a refusal is reported as what it is (`BR-070`). */
    private val json: Json,
) : JobDetailsRepository {

    override suspend fun loadJobDetails(jobId: String): JobDetailsResult {
        // The working set is scoped to the subject of the session that produced it, so a read whose
        // subject cannot be read is refused rather than answered without local state (§10).
        val subjectId = subject.current()
            ?: return JobDetailsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return JobDetailsResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return when (val read = read(accessToken, allowRenewal = true, jobId = jobId)) {
            is JobRead.Answered -> {
                // A successful read replaces the local copy rather than being merged into it (§10).
                evidence.rememberJob(subjectId, jobId, read.job)
                JobDetailsResult.Success(read.details)
            }

            is JobRead.Failed ->
                if (read.reason.couldNotReachBackend()) {
                    evidence.reportedJob(subjectId, jobId)
                        ?.toJobDetails()
                        ?.let { reported ->
                            JobDetailsResult.Success(reported, ReadSource.WORKING_SET)
                        }
                        ?: JobDetailsResult.Failure(read.reason)
                } else {
                    JobDetailsResult.Failure(read.reason)
                }
        }
    }

    override suspend fun loadJobActivity(jobId: String): JobActivityResult {
        val subjectId = subject.current()
            ?: return JobActivityResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return JobActivityResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return when (val read = readActivity(accessToken, allowRenewal = true, jobId = jobId)) {
            is ActivityRead.Answered -> {
                evidence.rememberActivity(subjectId, jobId, read.activity)
                JobActivityResult.Success(read.events)
            }

            is ActivityRead.Failed ->
                if (read.reason.couldNotReachBackend()) {
                    evidence.reportedActivity(subjectId, jobId)
                        ?.let { reported ->
                            JobActivityResult.Success(
                                events = reported.events.mapNotNull { it.toJobActivityEvent() },
                                source = ReadSource.WORKING_SET,
                            )
                        }
                        ?: JobActivityResult.Failure(read.reason)
                } else {
                    JobActivityResult.Failure(read.reason)
                }
        }
    }

    override suspend fun addVisitNote(
        jobId: String,
        visitId: String,
        body: String,
    ): VisitNoteResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return VisitNoteResult.Failure(JobActionFailure.UNAUTHENTICATED)

        val request = AddVisitNoteRequestDto(body = body.trim())
        return performActivityWrite(
            accessToken = accessToken,
            allowRenewal = true,
            call = { token ->
                api.addVisitNote("Bearer $token", jobId, visitId, request)
            },
        )
    }

    override suspend fun changeJobStatus(
        jobId: String,
        status: JobStatus,
        note: String?,
        expectedVersion: Int,
    ): JobActionResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return JobActionResult.Failure(JobActionFailure.UNAUTHENTICATED)

        val request = ChangeJobStatusRequestDto(
            status = status.name,
            note = note?.takeIf { it.isNotBlank() },
            expectedVersion = expectedVersion,
        )
        return perform(
            accessToken = accessToken,
            allowRenewal = true,
            call = { token -> api.changeJobStatus("Bearer $token", jobId, request) },
        )
    }

    override suspend fun rescheduleVisit(
        jobId: String,
        visitId: String,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        reason: String?,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return JobActionResult.Failure(JobActionFailure.UNAUTHENTICATED)

        val request = RescheduleVisitRequestDto(
            scheduledStart = scheduledStart.toString(),
            scheduledEnd = scheduledEnd.toString(),
            reason = reason?.takeIf { it.isNotBlank() },
            confirmConflicts = confirmConflicts,
            expectedVersion = expectedVersion,
        )
        return perform(
            accessToken = accessToken,
            allowRenewal = true,
            call = { token ->
                api.rescheduleVisit("Bearer $token", jobId, visitId, request)
            },
        )
    }

    override suspend fun assignVisitTechnicians(
        jobId: String,
        visitId: String,
        assignments: List<TechnicianAssignment>,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return JobActionResult.Failure(JobActionFailure.UNAUTHENTICATED)

        val request = AssignVisitTechniciansRequestDto(
            technicians = assignments.map { assignment ->
                TechnicianAssignmentRequestDto(
                    membershipId = assignment.membershipId,
                    roleCode = assignment.role.toRoleCode(),
                )
            },
            confirmConflicts = confirmConflicts,
            expectedVersion = expectedVersion,
        )
        return perform(
            accessToken = accessToken,
            allowRenewal = true,
            call = { token ->
                api.assignVisitTechnicians("Bearer $token", jobId, visitId, request)
            },
        )
    }

    override suspend fun loadAssignableTechnicians(): AssignableTechniciansResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return AssignableTechniciansResult.Failure(
                JobActionFailure.UNAUTHENTICATED,
            )
        return readTechnicians(accessToken, allowRenewal = true)
    }

    /** Runs one action, renewing the session once when the backend rejects the token. */
    private suspend fun perform(
        accessToken: String,
        allowRenewal: Boolean,
        call: suspend (token: String) -> JobDetailsDto,
    ): JobActionResult =
        try {
            val details = call(accessToken).toJobDetails()
            if (details == null) {
                JobActionResult.Failure(JobActionFailure.UNEXPECTED)
            } else {
                JobActionResult.Success(details)
            }
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryAction(accessToken, call)
            } else {
                failure.toActionOutcome(json)
            }
        } catch (failure: IOException) {
            JobActionResult.Failure(JobActionFailure.NETWORK)
        } catch (failure: SerializationException) {
            JobActionResult.Failure(JobActionFailure.UNEXPECTED)
        }

    private suspend fun renewAndRetryAction(
        rejectedToken: String,
        call: suspend (token: String) -> JobDetailsDto,
    ): JobActionResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                perform(renewal.accessToken, allowRenewal = false, call = call)

            SessionRenewal.Rejected ->
                JobActionResult.Failure(JobActionFailure.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                JobActionResult.Failure(JobActionFailure.NETWORK)
        }

    /** Runs an activity write, renewing the session once when the backend rejects the token. */
    private suspend fun performActivityWrite(
        accessToken: String,
        allowRenewal: Boolean,
        call: suspend (token: String) -> JobActivityDto,
    ): VisitNoteResult =
        try {
            VisitNoteResult.Success(
                call(accessToken).events.mapNotNull { it.toJobActivityEvent() },
            )
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryActivityWrite(accessToken, call)
            } else {
                VisitNoteResult.Failure(failure.toActionFailure())
            }
        } catch (failure: IOException) {
            VisitNoteResult.Failure(JobActionFailure.NETWORK)
        } catch (failure: SerializationException) {
            VisitNoteResult.Failure(JobActionFailure.UNEXPECTED)
        }

    private suspend fun renewAndRetryActivityWrite(
        rejectedToken: String,
        call: suspend (token: String) -> JobActivityDto,
    ): VisitNoteResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                performActivityWrite(renewal.accessToken, allowRenewal = false, call = call)

            SessionRenewal.Rejected ->
                VisitNoteResult.Failure(JobActionFailure.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                VisitNoteResult.Failure(JobActionFailure.NETWORK)
        }

    private suspend fun readTechnicians(
        accessToken: String,
        allowRenewal: Boolean,
    ): AssignableTechniciansResult =
        try {
            AssignableTechniciansResult.Success(
                api.assignableTechnicians("Bearer $accessToken").map { technician ->
                    AssignableTechnician(
                        membershipId = technician.membershipId,
                        name = technician.name,
                    )
                },
            )
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryTechnicians(accessToken)
            } else {
                AssignableTechniciansResult.Failure(failure.toActionFailure())
            }
        } catch (failure: IOException) {
            AssignableTechniciansResult.Failure(JobActionFailure.NETWORK)
        } catch (failure: SerializationException) {
            AssignableTechniciansResult.Failure(JobActionFailure.UNEXPECTED)
        }

    private suspend fun renewAndRetryTechnicians(
        rejectedToken: String,
    ): AssignableTechniciansResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                readTechnicians(renewal.accessToken, allowRenewal = false)

            SessionRenewal.Rejected ->
                AssignableTechniciansResult.Failure(JobActionFailure.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                AssignableTechniciansResult.Failure(JobActionFailure.NETWORK)
        }

    private suspend fun read(
        accessToken: String,
        allowRenewal: Boolean,
        jobId: String,
    ): JobRead =
        try {
            val job = api.jobDetails(
                authorization = "Bearer $accessToken",
                jobId = jobId,
            )
            val details = job.toJobDetails()
            if (details == null) {
                JobRead.Failed(CustomersFailureReason.UNEXPECTED)
            } else {
                // The wire response is carried with the mapped object, so the answer the backend gave
                // is what the local copy holds (`BR-041`).
                JobRead.Answered(job = job, details = details)
            }
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetry(accessToken, jobId)
            } else {
                JobRead.Failed(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            JobRead.Failed(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            JobRead.Failed(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the read once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetry(
        rejectedToken: String,
        jobId: String,
    ): JobRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                read(renewal.accessToken, allowRenewal = false, jobId = jobId)

            SessionRenewal.Rejected ->
                JobRead.Failed(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                JobRead.Failed(CustomersFailureReason.NETWORK)
        }

    /** Reads the Job's activity, renewing the session once when the backend rejects the token. */
    private suspend fun readActivity(
        accessToken: String,
        allowRenewal: Boolean,
        jobId: String,
    ): ActivityRead =
        try {
            val activity = api.jobActivity(authorization = "Bearer $accessToken", jobId = jobId)
            ActivityRead.Answered(
                activity = activity,
                events = activity.events.mapNotNull { it.toJobActivityEvent() },
            )
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryActivity(accessToken, jobId)
            } else {
                ActivityRead.Failed(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            ActivityRead.Failed(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ActivityRead.Failed(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the activity read once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetryActivity(
        rejectedToken: String,
        jobId: String,
    ): ActivityRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                readActivity(renewal.accessToken, allowRenewal = false, jobId = jobId)

            SessionRenewal.Rejected ->
                ActivityRead.Failed(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                ActivityRead.Failed(CustomersFailureReason.NETWORK)
        }
}

/**
 * The outcome of the Job read, before the caller decides what to report.
 *
 * [Answered] carries both the API's response and the object it maps to: the response is what the
 * working set keeps, so the offline read runs the same mapper the online read ran (`BR-041`).
 */
private sealed interface JobRead {
    /** The API answered; [job] is the wire response and [details] is that response mapped. */
    data class Answered(val job: JobDetailsDto, val details: JobDetails) : JobRead

    /** The read failed; [reason] decides whether the last reported Job may be served instead. */
    data class Failed(val reason: CustomersFailureReason) : JobRead
}

/** The outcome of the Job Activity read, before the caller decides what to report (`BR-080`). */
private sealed interface ActivityRead {
    /** The API answered; [activity] is the wire response and [events] is that response mapped. */
    data class Answered(
        val activity: JobActivityDto,
        val events: List<JobActivityEvent>,
    ) : ActivityRead

    /** The read failed; [reason] decides whether the last reported activity may be served instead. */
    data class Failed(val reason: CustomersFailureReason) : ActivityRead
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_SERVER_ERROR = 500

/*
 * The API's stable error codes for the Job and Visit actions (`docs/api/job-actions.md`).
 *
 * They are matched rather than the message text: a code is the machine-readable contract, and the
 * message is written for a person (`BR-041`).
 */
private const val CODE_NOT_FOUND = "JOB_NOT_FOUND"
private const val CODE_VISIT_NOT_FOUND = "VISIT_NOT_FOUND"
private const val CODE_TRANSITION_NOT_ALLOWED = "JOB_STATUS_TRANSITION_NOT_ALLOWED"
private const val CODE_CANCELLATION_UNAVAILABLE = "JOB_CANCELLATION_UNAVAILABLE"
private const val CODE_REVIEW_CONDITION_NOT_MET = "JOB_REVIEW_CONDITION_NOT_MET"
private const val CODE_COMPLETION_BLOCKED = "JOB_COMPLETION_BLOCKED"
private const val CODE_VISIT_NOT_RESCHEDULABLE = "VISIT_NOT_RESCHEDULABLE"
private const val CODE_TECHNICIANS_NOT_ASSIGNABLE = "TECHNICIANS_NOT_ASSIGNABLE"
private const val CODE_SCHEDULE_CONFLICT = "SCHEDULE_CONFLICT"
private const val CODE_VERSION_CONFLICT = "VERSION_CONFLICT"

/** Classifies an HTTP answer into the stable reason the screen reports (`dev.md` §7). */
private fun HttpException.toFailureReason(): CustomersFailureReason =
    when (code()) {
        400, 422 -> CustomersFailureReason.VALIDATION
        401 -> CustomersFailureReason.UNAUTHENTICATED
        403 -> CustomersFailureReason.FORBIDDEN
        404 -> CustomersFailureReason.NOT_FOUND
        409 -> CustomersFailureReason.VERSION_CONFLICT
        in 500..599 -> CustomersFailureReason.SERVER
        else -> CustomersFailureReason.UNEXPECTED
    }

/**
 * Reads the API's error envelope from a refused response.
 *
 * It is best-effort on purpose: the action's outcome is already known from the HTTP status, so a body
 * this build cannot read means the action is reported without its structured detail — never that the
 * action is reported as something it was not.
 */
private fun HttpException.errorEnvelope(json: Json): ApiErrorDto? =
    runCatching {
        val body = response()?.errorBody()?.string()
        if (body.isNullOrBlank()) null else json.decodeFromString<ApiErrorDto>(body)
    }.getOrNull()

/**
 * Classifies a refused action.
 *
 * `BR-070` conflicts are not a failure: they are the question the user has to answer, so they are
 * reported as [JobActionResult.Conflicts] and the same action can be resent once confirmed. A
 * conflict whose detail this build could not read is still a conflict, just one without a list.
 */
private fun HttpException.toActionOutcome(json: Json): JobActionResult {
    val envelope = errorEnvelope(json)
    if (code() == HTTP_CONFLICT && envelope?.code == CODE_SCHEDULE_CONFLICT) {
        return JobActionResult.Conflicts(
            envelope.details?.conflicts?.map { conflict -> conflict.toConflict() }
                ?: emptyList(),
        )
    }
    return JobActionResult.Failure(toActionFailure(envelope?.code))
}

/** Classifies a refused action into the stable reason the screen reports (`dev.md` §7). */
private fun HttpException.toActionFailure(errorCode: String? = null): JobActionFailure =
    when (errorCode) {
        CODE_NOT_FOUND, CODE_VISIT_NOT_FOUND -> JobActionFailure.NOT_FOUND
        CODE_TRANSITION_NOT_ALLOWED -> JobActionFailure.JOB_TRANSITION_NOT_ALLOWED
        CODE_CANCELLATION_UNAVAILABLE -> JobActionFailure.JOB_CANCELLATION_UNAVAILABLE
        CODE_REVIEW_CONDITION_NOT_MET -> JobActionFailure.JOB_REVIEW_CONDITION_NOT_MET
        CODE_COMPLETION_BLOCKED -> JobActionFailure.JOB_COMPLETION_BLOCKED
        CODE_VISIT_NOT_RESCHEDULABLE -> JobActionFailure.VISIT_NOT_RESCHEDULABLE
        CODE_TECHNICIANS_NOT_ASSIGNABLE -> JobActionFailure.TECHNICIANS_NOT_ASSIGNABLE
        CODE_VERSION_CONFLICT, CODE_SCHEDULE_CONFLICT ->
            JobActionFailure.VERSION_CONFLICT

        else ->
            when (code()) {
                HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> JobActionFailure.VALIDATION
                HTTP_UNAUTHORIZED -> JobActionFailure.UNAUTHENTICATED
                HTTP_FORBIDDEN -> JobActionFailure.FORBIDDEN
                HTTP_NOT_FOUND -> JobActionFailure.NOT_FOUND
                HTTP_CONFLICT -> JobActionFailure.VERSION_CONFLICT
                in HTTP_SERVER_ERROR..599 -> JobActionFailure.SERVER
                else -> JobActionFailure.UNEXPECTED
            }
    }

/**
 * Maps the wire payload onto the domain model, or reports a contract mismatch.
 *
 * A code this build does not know — a Job status, a Visit status or an assignment role — fails the
 * whole read rather than being dropped silently, which is the contract-mismatch rule the other reads
 * follow (`BR-042`). An assignment cannot be presented once its role is unknown, because the screen
 * would have to guess whether that technician is the Lead (`BR-068`).
 */
private fun JobDetailsDto.toJobDetails(): JobDetails? {
    val jobStatus = JobStatus.entries.firstOrNull { it.name == status } ?: return null
    val visit = selectedVisit?.toVisit()
    if (selectedVisit != null && visit == null) {
        return null
    }
    val assigned = technicians.map { it.toTechnician() ?: return null }
    return JobDetails(
        id = id,
        jobNumber = jobNumber,
        title = title,
        description = description,
        status = jobStatus,
        // A transition code this build does not know is left out rather than failing the whole read:
        // the Job itself is still presentable, and an action the screen cannot name is one it must
        // not draw (`BR-041`, `BR-042`).
        allowedStatusTransitions = allowedStatusTransitions.mapNotNull { code ->
            JobStatus.entries.firstOrNull { it.name == code }
        },
        version = version,
        customerId = customerId,
        customerName = customerName,
        address = address?.toAddress(),
        selectedVisit = visit,
        technicians = assigned,
    )
}

private fun JobDetailsVisitDto.toVisit(): JobDetailsVisit? {
    val visitStatus = VisitStatus.entries.firstOrNull { it.name == status } ?: return null
    return JobDetailsVisit(
        id = id,
        status = visitStatus,
        scheduledStart = scheduledStart,
        scheduledEnd = scheduledEnd,
        version = version,
        reschedulable = reschedulable,
    )
}

private fun JobDetailsTechnicianDto.toTechnician(): JobDetailsTechnician? {
    val role = AssignmentRole.entries.firstOrNull { it.name == roleCode } ?: return null
    return JobDetailsTechnician(membershipId = membershipId, name = name, role = role)
}

private fun JobDetailsAddressDto.toAddress(): CustomerJobAddress =
    CustomerJobAddress(
        propertyName = propertyName,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        province = province,
        postalCode = postalCode,
        country = country,
    )

/**
 * Maps the wire event onto the domain model, or reports a contract mismatch.
 *
 * A kind this build does not know cannot be named, so the event is dropped rather than drawn as
 * something it is not; the rest of the timeline stays (`BR-041`, `BR-042`). Status and role values are
 * kept as the API's stable codes and resolved to localized labels where the screen presents them.
 */
private fun JobActivityEventDto.toJobActivityEvent(): JobActivityEvent? {
    val eventKind = JobActivityKind.entries.firstOrNull { it.name == kind } ?: return null
    return JobActivityEvent(
        id = id,
        kind = eventKind,
        recordedAt = recordedAt,
        actorName = actorName,
        visitSequence = visitSequence,
        fromStatus = fromStatus,
        toStatus = toStatus,
        technicianName = technicianName,
        roleCode = roleCode,
        previousRoleCode = previousRoleCode,
        outcomeCode = outcomeCode,
        outcomeSummary = outcomeSummary,
        body = body,
        photoId = photoId,
        photoPhase = photoPhase,
    )
}
