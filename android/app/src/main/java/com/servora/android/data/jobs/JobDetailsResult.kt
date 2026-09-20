package com.servora.android.data.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.ScheduleConflict

/** Outcome of the Job Details read. */
sealed interface JobDetailsResult {
    /**
     * The backend answered with the Job, or — when the API could not be reached — the last Job it
     * reported.
     *
     * [source] says which of the two, so the screen presents a local copy as the last reported answer
     * rather than as a current one (`offline-first-architecture.md` §2, §7, `D5`).
     */
    data class Success(
        val details: JobDetails,
        val source: ReadSource = ReadSource.BACKEND,
    ) : JobDetailsResult

    /** The read failed; [reason] decides what the screen reports. */
    data class Failure(val reason: CustomersFailureReason) : JobDetailsResult
}

/**
 * Outcome of creating a Job (`BR-094`; `docs/api/job-details.md` §6).
 *
 * Only the created Job's id is carried forward: the screen that follows navigates to the Job Details
 * destination, which re-reads the Job from the backend rather than trusting a locally assembled copy
 * (`BR-001`), exactly as the customer create carries only the created id.
 *
 * The create is **online-only**. The request carries no idempotency key and no conflict policy is
 * decided for it, so it is not queued on the device — which is the condition the offline standard's §13
 * sets for a new Android mutation (`docs/architecture/offline-first-architecture.md` §13,
 * `BR-013`).
 */
sealed interface JobCreateResult {
    /** The backend created the Job. */
    data class Success(val jobId: String) : JobCreateResult

    /** The create failed; [reason] decides what the form reports. */
    data class Failure(val reason: JobCreateFailure) : JobCreateResult
}

/**
 * Stable reasons a Job create can fail, classified from the HTTP answer (`dev.md` §7).
 *
 * The reasons are the create's own rather than a reuse of [CustomersFailureReason]: this route has two
 * outcomes a generic mapping cannot state — a Customer that is not active, and a Property that is archived
 * — and both arrive as `409`, which the generic mapping reads as an optimistic-concurrency conflict. The
 * codes are the API's stable machine-readable error codes (`docs/api/job-details.md` §6.5); the
 * presentation is localized by the screen (`BR-028`, `BR-041`).
 */
enum class JobCreateFailure {
    /** No session is held, or the backend no longer accepts it. */
    UNAUTHENTICATED,

    /** The signed-in user does not hold `JOB_CREATE` (`BR-007`). */
    FORBIDDEN,

    /** The backend rejected the submitted values. */
    VALIDATION,

    /** The named Customer does not exist in this organization or has been deleted. */
    CUSTOMER_NOT_FOUND,

    /** The named Customer exists but is not `ACTIVE` (`BR-094`). */
    CUSTOMER_INACTIVE,

    /** The named Property does not exist here or the Customer does not currently hold it (`BR-050`). */
    PROPERTY_NOT_FOUND,

    /** The named Property is archived, so it cannot take new work (`BR-083`). */
    PROPERTY_UNAVAILABLE,

    /** A `404` whose code this build cannot read: the Customer or the Property is not usable. */
    NOT_FOUND,

    /** A `409` whose code this build cannot read: the Customer or the Property refuses new work. */
    CONFLICT,

    /** The request never reached the backend. */
    NETWORK,

    /** The backend could not complete the request (5xx). */
    SERVER,

    /** A response this build cannot interpret, or an undeclared failure. */
    UNEXPECTED,
}

/** Outcome of the Job Activity read (`BR-080`). */
sealed interface JobActivityResult {
    /**
     * The backend answered with the Job's chronological activity, newest first, or — when the API
     * could not be reached — the last activity it reported.
     *
     * [source] says which of the two, so a timeline served from the device says so instead of
     * presenting itself as current (`offline-first-architecture.md` §7, `D5`).
     */
    data class Success(
        val events: List<JobActivityEvent>,
        val source: ReadSource = ReadSource.BACKEND,
    ) : JobActivityResult

    /** The read failed; [reason] decides what the section reports. */
    data class Failure(val reason: CustomersFailureReason) : JobActivityResult
}

/**
 * Outcome of a write that answers with the refreshed Job Activity timeline (`BR-080`).
 *
 * Two writes answer that way — adding a text update to a Visit and removing accepted photo evidence —
 * because both change what the Job's Activity projects, so the client is handed the timeline the
 * backend now reports rather than a locally patched copy (`BR-001`).
 */
sealed interface ActivityWriteResult {
    /** The backend answered with the refreshed Job Activity timeline. */
    data class Success(val events: List<JobActivityEvent>) : ActivityWriteResult

    /**
     * The API could not be reached, so the write is **queued on the device** and will be applied when
     * it can be (`BR-014`, `BR-031`; offline standard §2, §4).
     *
     * Nothing has been recorded: the timeline on screen is still the last one the backend reported
     * (`BR-080`), and the waiting update is presented from the queue beside it (`§7`).
     */
    data object Queued : ActivityWriteResult

    /** Why the write could not be recorded. */
    data class Failure(val reason: JobActionFailure) : ActivityWriteResult
}

/** Outcome of a Job or Visit management action (`BR-058` – `BR-079`). */
sealed interface JobActionResult {
    /** The backend applied the action and answered with the Job as it now stands. */
    data class Success(val details: JobDetails) : JobActionResult

    /**
     * The API could not be reached, so the action is **queued on the device** and will be applied
     * when it can be (`BR-014`, `BR-031`; offline standard §2, §4, §13).
     *
     * Nothing has been applied and nothing is presented as applied: the screen keeps the last state
     * the backend reported and shows the waiting action beside it (`§7`). The queued operation
     * carries the idempotency key the first attempt used, so a replay is applied at most once.
     */
    data class Queued(val details: JobDetails?) : JobActionResult

    /**
     * The API refused the action because a technician on the Visit is already booked over an
     * overlapping window (`BR-070`).
     *
     * This is not a failure: it is the question the user has to answer. [conflicts] is what has to be
     * shown, and the same action resent with the conflicts confirmed applies the change, because a
     * conflict is a warning rather than a prohibition in v1.
     */
    data class Conflicts(val conflicts: List<ScheduleConflict>) : JobActionResult

    /** The action did not complete; [reason] decides what the screen reports. */
    data class Failure(val reason: JobActionFailure) : JobActionResult
}

/** Outcome of reading the technicians a Job's Visit may be assigned to (`BR-024`, `BR-068`). */
sealed interface AssignableTechniciansResult {
    /** The backend answered with the organization's technicians. */
    data class Success(
        val technicians: List<AssignableTechnician>,
    ) : AssignableTechniciansResult

    /** The read failed; [reason] decides what the sheet reports. */
    data class Failure(val reason: JobActionFailure) : AssignableTechniciansResult
}

/**
 * Stable reasons a Job or Visit management action can fail.
 *
 * They are the API's documented outcomes, classified from the status code and the stable error code,
 * so the screen can say what actually happened rather than "something went wrong" (`BR-041`).
 */
enum class JobActionFailure {
    /** No session is held, or the backend no longer accepts it. */
    UNAUTHENTICATED,

    /** The signed-in user does not hold the capability the action needs (`BR-007`). */
    FORBIDDEN,

    /** The Job, the Visit or a technician the action addressed was not found. */
    NOT_FOUND,

    /** The backend rejected the submitted values. */
    VALIDATION,

    /**
     * The record moved past the version the client last saw (`BR-086`).
     *
     * The API is the final authority: the action was refused rather than applied, and the client
     * re-reads the Job before the user tries again.
     */
    VERSION_CONFLICT,

    /** `BR-058` does not permit that Job status transition. */
    JOB_TRANSITION_NOT_ALLOWED,

    /** `BR-061`'s entry conditions for `PENDING_REVIEW` are not met. */
    JOB_REVIEW_CONDITION_NOT_MET,

    /**
     * `BR-062` does not allow the Job to be completed while it has an open Visit.
     *
     * The destination is structurally permitted (`BR-058`), so this is not a transition refusal: the
     * Job's own field work is unfinished, and the Visit has to reach a historical status first
     * (`BR-074`).
     */
    JOB_COMPLETION_BLOCKED,

    /**
     * `BR-064` requires a structured cancellation reason whose catalogue product ownership has not
     * defined, so the API does not cancel a Job yet.
     */
    JOB_CANCELLATION_UNAVAILABLE,

    /**
     * The photo has already been removed from ordinary use (`BR-089`).
     *
     * One photo has one removal and no restore is defined, so a repeat is a conflict with the
     * evidence's own state: the evidence is out of use, and the screen says so rather than reporting a
     * failure it cannot explain (`BR-067`).
     */
    PHOTO_ALREADY_REMOVED,

    /**
     * The recording has already been removed from ordinary use (`BR-089`).
     *
     * It is the audio kind's own code because the API reports each kind's conflict as its own stable
     * code (`docs/api/job-audio.md` §5), and the two capabilities are separate (`ADR-018` A7). Like
     * the photo's, it leaves the manager nothing to decide: the evidence is out of use, and the screen
     * says so rather than reporting a failure it cannot explain (`BR-067`).
     */
    AUDIO_NOTE_ALREADY_REMOVED,

    /** `BR-073` only permits rescheduling a Visit that is `SCHEDULED`. */
    VISIT_NOT_RESCHEDULABLE,

    /**
     * `BR-074` does not permit that Visit status transition from the status the Visit holds.
     *
     * The Visit's own lifecycle is a state machine, so a destination that is not its next permitted
     * step — or `BR-075`'s one correction — is refused rather than applied as closely as possible
     * (`ADR-019` D5). It is the Visit's own code, separate from the Job's
     * [JOB_TRANSITION_NOT_ALLOWED] because the two are different state machines (`BR-059`).
     */
    VISIT_TRANSITION_NOT_ALLOWED,

    /**
     * `BR-072`'s conditions for a Visit becoming `SCHEDULED` are not met.
     *
     * The destination is structurally permitted (`BR-074`), so this is not a transition refusal: the
     * Visit is missing something it needs to be scheduled — the Job's Property, a valid schedule, or
     * a crew with exactly one Lead.
     */
    VISIT_SCHEDULING_CONDITION_NOT_MET,

    /**
     * The Visit's Job is `COMPLETED` or `CANCELED`, so no field work may be recorded under it
     * (`BR-062`, `BR-079`).
     *
     * The API fails closed rather than writing field history — outcomes included — under a closed
     * Job (`ADR-019` D4).
     */
    JOB_CLOSED_FOR_FIELD_WORK,

    /**
     * The idempotency key this action carried was already used for a different Visit (`BR-031`).
     *
     * The key belongs to one operation on one Visit, so a reuse is refused rather than applied.
     */
    VISIT_OPERATION_REUSED,

    /** A technician in the requested crew cannot be assigned. */
    TECHNICIANS_NOT_ASSIGNABLE,

    /** The request never reached the backend. */
    NETWORK,

    /** The backend could not complete the request (5xx). */
    SERVER,

    /** A response this build cannot interpret, or an undeclared failure. */
    UNEXPECTED,
}
