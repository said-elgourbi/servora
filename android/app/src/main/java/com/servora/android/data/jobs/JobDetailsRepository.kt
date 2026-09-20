package com.servora.android.data.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.couldNotReachBackend
import com.servora.android.data.offline.OutboxReplayEngine
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobContactPerson
import com.servora.android.domain.model.JobCustomerContact
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsTechnician
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.JobDetailsVisitSummary
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.VisitStatus
import com.servora.android.domain.model.visitOutcomeOrNull
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
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
     * Creates a Job for a Customer at a Property and returns the created Job's id (`BR-094`).
     *
     * The backend authorizes the write with `JOB_CREATE` and owns the identifier, the organization-scoped
     * Job number, the `NEW` status, the address snapshot and the version, so this call never names an
     * organization and never sends any of those values (`BR-001`, `BR-007`, `BR-052`, `BR-056`,
     * `BR-058`). It creates **no Visit**: a Visit is one field attempt (`BR-047`, `BR-051`).
     *
     * The write is **online-only** — the request carries no idempotency key, so it is not queued
     * (`offline-first-architecture.md` §13).
     */
    suspend fun createJob(request: CreateJobRequest): JobCreateResult

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

    /**
     * Adds one text update to the represented Visit and returns the refreshed activity (`BR-027`).
     *
     * It carries its own idempotency key and device instant, so the write is **offline-capable**: an
     * update the API cannot be reached for is queued on the device and reported as
     * [ActivityWriteResult.Queued] rather than lost (`BR-013`, `BR-014`, `ADR-019` D5). The route
     * admits either the office's `JOB_UPDATE` or the technician's `VISIT_ADD_NOTE`, and a note behaves
     * the same way whichever capability admitted the caller (`docs/api/job-actions.md` §2).
     */
    suspend fun addVisitNoteRequest(note: VisitNote): ActivityWriteResult

    /**
     * Takes one photo out of ordinary use, recording [reason], and returns the refreshed activity
     * (`BR-088`, `BR-089`).
     *
     * The backend authorizes it (`evidence.photo.remove`), so this layer never decides whether the
     * removal is allowed: a caller without the capability is refused by the API and reported as
     * [JobActionFailure.FORBIDDEN] (`BR-007`).
     */
    suspend fun removeJobPhoto(
        jobId: String,
        photoId: String,
        reason: String,
    ): ActivityWriteResult

    /**
     * Takes one recording out of ordinary use, recording [reason], and returns the refreshed activity
     * (`BR-088`, `BR-089`).
     *
     * The audio kind's own operation, authorized by `evidence.audio.remove` — a capability separate from
     * the photo one, so a company may withdraw one kind and keep the other (`ADR-018` A7). Like the
     * photo removal it is **online-only**: the route takes no idempotency key and no conflict policy is
     * decided for it, so it is never queued (`offline-first-architecture.md` §5, §8, §13.2).
     */
    suspend fun removeJobAudioNote(
        jobId: String,
        audioNoteId: String,
        reason: String,
    ): ActivityWriteResult

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

    /**
     * Emits after a replay the backend accepted, so a screen showing that work re-reads it
     * (`offline-first-architecture.md` §7).
     *
     * It carries no payload: the screen re-reads the Job it is open on from the API, which is what a
     * successful read does anyway (`§10`).
     */
    val appliedOperations: Flow<Unit>

    /**
     * Moves the represented Visit to [action]'s destination (`BR-074`, `BR-075`), recording the
     * outcome `BR-077` requires when it is a completion.
     *
     * The action is sent against the API; when the API cannot be reached it is **queued on the device**
     * and reported as [JobActionResult.Queued], because a Visit transition is offline-capable
     * (`BR-013`, `ADR-019` D5). A refusal is reported as a failure rather than queued: the API
     * answered, and its answer is the one the technician has to act on (`BR-032`).
     */
    suspend fun changeVisitStatus(action: VisitStatusChange): JobActionResult

    /** The status transition waiting for the backend on this Job, or `null` when none is (§7). */
    suspend fun queuedVisitAction(jobId: String): QueuedVisitFieldAction?

    /** The notes waiting for the backend on this Job, oldest first (§7). */
    suspend fun queuedVisitNotes(jobId: String): List<QueuedVisitNote>

    /**
     * Removes a field action the backend **refused** once the technician has finished with it
     * (`BR-032`, `BR-014`).
     *
     * The return value says whether a refused row was removed; an action that may still apply is never
     * dropped.
     */
    suspend fun discardQueuedVisitAction(jobId: String, operationId: String): Boolean

    /** The same for a refused note (`BR-032`, `BR-014`). */
    suspend fun discardQueuedVisitNote(jobId: String, operationId: String): Boolean
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
    /** The outbox the field action is queued in when the API cannot be reached (`§4`). */
    private val visitFieldActions: VisitFieldActions,
    /**
     * The replay engine's own "something was applied" answer, so the screen re-reads the Job it shows
     * rather than keeping a picture the applied action has moved past (`§7`).
     */
    private val engine: OutboxReplayEngine,
) : JobDetailsRepository {

    override val appliedOperations: Flow<Unit> = engine.applied

    override suspend fun createJob(request: CreateJobRequest): JobCreateResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return JobCreateResult.Failure(JobCreateFailure.UNAUTHENTICATED)

        return create(accessToken, allowRenewal = true, request = request)
    }

    /**
     * Creates the Job once with [accessToken], renewing the session and retrying when the backend
     * refuses the token (`BR-018`).
     *
     * A refusal is reported as a failure rather than queued: the API answered, and its answer is the one
     * the user has to act on (`BR-032`). Only a failure that never reached the backend is
     * [JobCreateFailure.NETWORK], so a connectivity problem is never presented as a rejected form.
     */
    private suspend fun create(
        accessToken: String,
        allowRenewal: Boolean,
        request: CreateJobRequest,
    ): JobCreateResult =
        try {
            JobCreateResult.Success(api.createJob("Bearer $accessToken", request).id)
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryCreate(accessToken, request)
            } else {
                JobCreateResult.Failure(failure.toCreateFailure(json))
            }
        } catch (failure: IOException) {
            JobCreateResult.Failure(JobCreateFailure.NETWORK)
        } catch (failure: SerializationException) {
            JobCreateResult.Failure(JobCreateFailure.UNEXPECTED)
        }

    private suspend fun renewAndRetryCreate(
        rejectedToken: String,
        request: CreateJobRequest,
    ): JobCreateResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                create(renewal.accessToken, allowRenewal = false, request = request)

            SessionRenewal.Rejected ->
                JobCreateResult.Failure(JobCreateFailure.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                JobCreateResult.Failure(JobCreateFailure.NETWORK)
        }

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

    override suspend fun changeVisitStatus(action: VisitStatusChange): JobActionResult {
        val subjectId = subject.current()
            ?: return JobActionResult.Failure(JobActionFailure.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return JobActionResult.Failure(JobActionFailure.UNAUTHENTICATED)

        val request = ChangeVisitStatusRequestDto(
            status = action.status.name,
            outcomeCode = action.outcome?.name,
            // The technician's summary is sent as written but without the surrounding whitespace a
            // text field collects, and an empty one is absent rather than blank: the API requires the
            // summary with a completion, so a blank one is a completion it would refuse (`BR-077`).
            outcomeSummary = action.outcomeSummary?.trim()?.takeIf { it.isNotEmpty() },
            // The key and the device instant are the ones the action was made with, so the queued
            // replay carries exactly them and is applied at most once (`BR-031`, §5).
            clientOperationId = action.operationId,
            capturedAt = action.capturedAt.toString(),
            expectedVersion = action.expectedVersion,
            confirmConflicts = action.confirmConflicts,
        )
        val result = perform(
            accessToken = accessToken,
            allowRenewal = true,
            call = { token ->
                api.changeVisitStatus("Bearer $token", action.jobId, action.visitId, request)
            },
        )
        // A request that never reached the backend is queued rather than reported as a failure: the
        // transition is offline-capable (`BR-013`, `ADR-019` D5), and the technician must not lose it
        // (`BR-014`). A refusal is the backend's own answer and is reported as it is (`BR-032`).
        if (result is JobActionResult.Failure && result.reason == JobActionFailure.NETWORK) {
            return queueChangeStatus(subjectId, action)
        }
        return result
    }

    /** Queues the transition and reports the provisional outcome the screen presents (`§4`, §7). */
    private suspend fun queueChangeStatus(
        subjectId: String,
        action: VisitStatusChange,
    ): JobActionResult {
        if (!visitFieldActions.queueChangeStatus(subjectId, action)) {
            return JobActionResult.Failure(JobActionFailure.NETWORK)
        }
        // Nothing has been applied, so the action is never presented as done: the screen keeps the last
        // state the backend reported and shows the action that is waiting (`BR-001`, §7).
        return JobActionResult.Queued(evidence.reportedJob(subjectId, action.jobId)?.toJobDetails())
    }

    override suspend fun queuedVisitAction(jobId: String): QueuedVisitFieldAction? {
        val subjectId = subject.current() ?: return null
        return visitFieldActions.queuedChangeStatus(subjectId, jobId)
    }

    override suspend fun queuedVisitNotes(jobId: String): List<QueuedVisitNote> {
        val subjectId = subject.current() ?: return emptyList()
        return visitFieldActions.queuedNotes(subjectId, jobId)
    }

    override suspend fun discardQueuedVisitAction(jobId: String, operationId: String): Boolean {
        val subjectId = subject.current() ?: return false
        return visitFieldActions.discard(subjectId, jobId, operationId)
    }

    override suspend fun discardQueuedVisitNote(jobId: String, operationId: String): Boolean =
        discardQueuedVisitAction(jobId, operationId)

    override suspend fun addVisitNoteRequest(note: VisitNote): ActivityWriteResult {
        val subjectId = subject.current()
            ?: return ActivityWriteResult.Failure(JobActionFailure.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return ActivityWriteResult.Failure(JobActionFailure.UNAUTHENTICATED)

        val body = note.body.trim()
        if (body.isEmpty()) {
            // A note with no text is not an update, so nothing is sent and nothing is queued (`BR-027`).
            return ActivityWriteResult.Failure(JobActionFailure.VALIDATION)
        }
        val request = AddVisitNoteRequestDto(
            body = body,
            clientOperationId = note.operationId,
            capturedAt = note.capturedAt.toString(),
        )
        val result = performActivityWrite(
            accessToken = accessToken,
            jobId = note.jobId,
            allowRenewal = true,
            call = { token ->
                api.addVisitNote("Bearer $token", note.jobId, note.visitId, request)
            },
        )
        // The same policy the transition follows: a write the backend never received is queued rather
        // than lost (`BR-013`, `BR-014`), and a refusal is the backend's answer (`BR-032`).
        if (result is ActivityWriteResult.Failure && result.reason == JobActionFailure.NETWORK) {
            if (!visitFieldActions.queueNote(subjectId, note)) {
                return ActivityWriteResult.Failure(JobActionFailure.NETWORK)
            }
            return ActivityWriteResult.Queued
        }
        return result
    }

    override suspend fun removeJobPhoto(
        jobId: String,
        photoId: String,
        reason: String,
    ): ActivityWriteResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return ActivityWriteResult.Failure(JobActionFailure.UNAUTHENTICATED)

        val request = RemoveJobPhotoRequestDto(reason = reason.trim())
        return performActivityWrite(
            accessToken = accessToken,
            jobId = jobId,
            allowRenewal = true,
            call = { token ->
                api.removeJobPhoto("Bearer $token", jobId, photoId, request)
            },
        )
    }

    override suspend fun removeJobAudioNote(
        jobId: String,
        audioNoteId: String,
        reason: String,
    ): ActivityWriteResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return ActivityWriteResult.Failure(JobActionFailure.UNAUTHENTICATED)

        val request = RemoveJobAudioNoteRequestDto(reason = reason.trim())
        return performActivityWrite(
            accessToken = accessToken,
            jobId = jobId,
            allowRenewal = true,
            call = { token ->
                api.removeJobAudioNote("Bearer $token", jobId, audioNoteId, request)
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

    /**
     * Runs an activity write, renewing the session once when the backend rejects the token.
     *
     * A write answers with the refreshed timeline, and that answer **replaces the local copy** exactly
     * as a successful read replaces it (`offline-first-architecture.md` §2, `BR-041`). Without this a
     * device that wrote and then lost connectivity would serve an activity that predates its own
     * change — showing, for a removal, evidence the change took out of ordinary use (`BR-089`).
     */
    private suspend fun performActivityWrite(
        accessToken: String,
        jobId: String,
        allowRenewal: Boolean,
        call: suspend (token: String) -> JobActivityDto,
    ): ActivityWriteResult =
        try {
            val activity = call(accessToken)
            subject.current()?.let { subjectId ->
                evidence.rememberActivity(subjectId, jobId, activity)
            }
            ActivityWriteResult.Success(
                activity.events.mapNotNull { it.toJobActivityEvent() },
            )
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryActivityWrite(accessToken, jobId, call)
            } else {
                ActivityWriteResult.Failure(
                    failure.toActionFailure(failure.errorEnvelope(json)?.code),
                )
            }
        } catch (failure: IOException) {
            ActivityWriteResult.Failure(JobActionFailure.NETWORK)
        } catch (failure: SerializationException) {
            ActivityWriteResult.Failure(JobActionFailure.UNEXPECTED)
        }

    private suspend fun renewAndRetryActivityWrite(
        rejectedToken: String,
        jobId: String,
        call: suspend (token: String) -> JobActivityDto,
    ): ActivityWriteResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                performActivityWrite(
                    accessToken = renewal.accessToken,
                    jobId = jobId,
                    allowRenewal = false,
                    call = call,
                )

            SessionRenewal.Rejected ->
                ActivityWriteResult.Failure(JobActionFailure.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                ActivityWriteResult.Failure(JobActionFailure.NETWORK)
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
private const val CODE_PHOTO_ALREADY_REMOVED = "JOB_PHOTO_ALREADY_REMOVED"
private const val CODE_AUDIO_NOTE_ALREADY_REMOVED = "JOB_AUDIO_NOTE_ALREADY_REMOVED"
private const val CODE_SCHEDULE_CONFLICT = "SCHEDULE_CONFLICT"
private const val CODE_VERSION_CONFLICT = "VERSION_CONFLICT"

/*
 * The Visit field lifecycle's own stable codes (`docs/api/job-actions.md` §7).
 *
 * They are the Visit's answers rather than the Job's, so a refusal from the field route is reported as
 * what the Visit's state machine said (`BR-059`).
 */
private const val CODE_VISIT_TRANSITION_NOT_ALLOWED = "VISIT_STATUS_TRANSITION_NOT_ALLOWED"
private const val CODE_VISIT_SCHEDULING_CONDITION_NOT_MET = "VISIT_SCHEDULING_CONDITION_NOT_MET"
private const val CODE_JOB_CLOSED_FOR_FIELD_WORK = "JOB_CLOSED_FOR_FIELD_WORK"
private const val CODE_VISIT_OPERATION_REUSED = "VISIT_OPERATION_REUSED"

/**
 * The two `404` codes `POST /jobs` answers, so the form can say whether it was the Customer or the
 * Property that is not usable (`docs/api/job-details.md` §6.5).
 */
private const val CODE_CUSTOMER_NOT_FOUND = "CUSTOMER_NOT_FOUND"
private const val CODE_PROPERTY_NOT_FOUND = "PROPERTY_NOT_FOUND"

/**
 * The two `409` codes `POST /jobs` answers: a Customer that is not active and a Property that is
 * archived. Neither is an optimistic-concurrency conflict, which is why the create classifies them
 * itself (`BR-094`, `BR-083`).
 */
private const val CODE_CUSTOMER_INACTIVE = "CUSTOMER_INACTIVE"
private const val CODE_PROPERTY_NOT_AVAILABLE = "PROPERTY_NOT_AVAILABLE_FOR_NEW_WORK"

/**
 * Classifies a refused `POST /jobs` (`docs/api/job-details.md` §6.5).
 *
 * The status alone is not enough: this route answers `404` for a Customer or a Property that is not
 * usable and `409` for a Customer that is not active or a Property that is archived, so the API's stable
 * error code decides which one the user is told. A body this build cannot read still yields an honest
 * answer — [JobCreateFailure.NOT_FOUND] or [JobCreateFailure.CONFLICT] — rather than being guessed at
 * (`BR-042`).
 */
private fun HttpException.toCreateFailure(json: Json): JobCreateFailure =
    when (code()) {
        400, 422 -> JobCreateFailure.VALIDATION
        401 -> JobCreateFailure.UNAUTHENTICATED
        403 -> JobCreateFailure.FORBIDDEN
        404 ->
            when (errorEnvelope(json)?.code) {
                CODE_CUSTOMER_NOT_FOUND -> JobCreateFailure.CUSTOMER_NOT_FOUND
                CODE_PROPERTY_NOT_FOUND -> JobCreateFailure.PROPERTY_NOT_FOUND
                else -> JobCreateFailure.NOT_FOUND
            }

        409 ->
            when (errorEnvelope(json)?.code) {
                CODE_CUSTOMER_INACTIVE -> JobCreateFailure.CUSTOMER_INACTIVE
                CODE_PROPERTY_NOT_AVAILABLE -> JobCreateFailure.PROPERTY_UNAVAILABLE
                else -> JobCreateFailure.CONFLICT
            }

        in 500..599 -> JobCreateFailure.SERVER
        else -> JobCreateFailure.UNEXPECTED
    }

/**
 * Classifies an HTTP answer into the stable reason the screen reports (`dev.md` §7).
 */
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
        CODE_PHOTO_ALREADY_REMOVED -> JobActionFailure.PHOTO_ALREADY_REMOVED
        CODE_AUDIO_NOTE_ALREADY_REMOVED -> JobActionFailure.AUDIO_NOTE_ALREADY_REMOVED
        // The Visit's own lifecycle answers, classified separately from the Job's so the screen can say
        // which state machine refused the change (`BR-059`).
        CODE_VISIT_TRANSITION_NOT_ALLOWED -> JobActionFailure.VISIT_TRANSITION_NOT_ALLOWED
        CODE_VISIT_SCHEDULING_CONDITION_NOT_MET -> JobActionFailure.VISIT_SCHEDULING_CONDITION_NOT_MET
        CODE_JOB_CLOSED_FOR_FIELD_WORK -> JobActionFailure.JOB_CLOSED_FOR_FIELD_WORK
        CODE_VISIT_OPERATION_REUSED -> JobActionFailure.VISIT_OPERATION_REUSED
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
    // The Job's Visits (`BR-047`, `BR-071`). A Visit this build cannot map — an unknown status — fails
    // the whole read rather than being dropped, exactly as the represented Visit does: a screen that
    // presented a Job's history with a Visit silently missing would report the Job wrongly (`BR-042`).
    val jobVisits = visits.map { summary -> summary.toVisitSummary() ?: return null }
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
        // The Customer's contact block is mapped exactly as the API reported it — absent when it did not
        // include it (`BR-092`) — so the client decides no authorization question of its own
        // (`BR-001`, `BR-007`).
        customerContactDetails = customerContactDetails?.toJobCustomerContact(),
        address = address?.toAddress(),
        selectedVisit = visit,
        technicians = assigned,
        visits = jobVisits,
    )
}

/**
 * Maps one of the Job's Visits (`BR-047`, `BR-071`).
 *
 * An unknown Visit status or assignment role fails the whole read for the same reason the represented
 * Visit's does: a Visit the screen cannot name is a Visit it must not present as something else, and a
 * crew whose role is unknown would have to be guessed as Lead or not (`BR-068`, `BR-042`).
 *
 * The Visit's outcome is the one exception, and it is deliberately not fatal: an outcome code this
 * build cannot name is reported as **no** outcome rather than as a different one, and the Job — the
 * Visit, its schedule and its crew with it — stays presentable (`BR-042`). Nothing is lost by that,
 * because the entry that recorded the outcome is still in the Visit's own activity (`BR-080`).
 */
private fun JobDetailsVisitSummaryDto.toVisitSummary(): JobDetailsVisitSummary? {
    val visitStatus = VisitStatus.entries.firstOrNull { it.name == status } ?: return null
    val crew = technicians.map { it.toTechnician() ?: return null }
    return JobDetailsVisitSummary(
        id = id,
        sequence = sequence,
        status = visitStatus,
        outcome = visitOutcomeOrNull(outcomeCode),
        scheduledStart = scheduledStart,
        scheduledEnd = scheduledEnd,
        version = version,
        technicians = crew,
    )
}

/**
 * Maps the Customer's contact block as the API reported it (`BR-092`).
 *
 * Nothing is validated against a vocabulary, because the block carries free values rather than codes: a
 * field the office never recorded stays `null`, and a field it did record is shown as recorded. The
 * contact persons are carried as the API reported them, primary first, and an answer that predates the
 * field maps to none of them rather than failing the read (`BR-042`).
 */
private fun JobDetailsCustomerContactDto.toJobCustomerContact() =
    JobCustomerContact(
        email = email,
        phone = phone,
        notes = notes,
        contacts = contacts.map { it.toJobContactPerson() },
    )

private fun JobDetailsContactPersonDto.toJobContactPerson() =
    JobContactPerson(
        firstName = firstName,
        lastName = lastName,
        phone = phone,
        email = email,
        // The contact's own primary flag (`BR-095`), reported so the Job card can resolve the Customer's
        // effective primary instead of inferring it from the list's order (`BR-041`).
        isPrimary = isPrimary,
    )

private fun JobDetailsVisitDto.toVisit(): JobDetailsVisit? {
    val visitStatus = VisitStatus.entries.firstOrNull { it.name == status } ?: return null
    return JobDetailsVisit(
        id = id,
        status = visitStatus,
        scheduledStart = scheduledStart,
        scheduledEnd = scheduledEnd,
        version = version,
        reschedulable = reschedulable,
        // A destination this build does not know is left out rather than failing the whole read: the
        // Visit is still presentable, and a destination the screen cannot name is one it must not
        // offer (`BR-041`, `BR-042`).
        allowedStatusTransitions = allowedStatusTransitions.mapNotNull { code ->
            VisitStatus.entries.firstOrNull { it.name == code }
        },
        // The API's answer to the other half of the question — whether the caller is authorized to drive
        // this Visit, through their own crew membership or through the office capability that needs no
        // crew at all (`ADR-019` D3, D7; `BR-093`) — is carried through as the API reported it.
        fieldActionable = fieldActionable,
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
        photoRemovalReason = photoRemovalReason,
        audioNoteId = audioNoteId,
        audioPhase = audioPhase,
        audioDurationSeconds = audioDurationSeconds,
        audioRemovalReason = audioRemovalReason,
    )
}
