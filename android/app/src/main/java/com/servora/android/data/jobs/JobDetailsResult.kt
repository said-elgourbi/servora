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

    /** Why the write could not be recorded. */
    data class Failure(val reason: JobActionFailure) : ActivityWriteResult
}

/** Outcome of a Job or Visit management action (`BR-058` – `BR-079`). */
sealed interface JobActionResult {
    /** The backend applied the action and answered with the Job as it now stands. */
    data class Success(val details: JobDetails) : JobActionResult

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

    /** `BR-073` only permits rescheduling a Visit that is `SCHEDULED`. */
    VISIT_NOT_RESCHEDULABLE,

    /** A technician in the requested crew cannot be assigned. */
    TECHNICIANS_NOT_ASSIGNABLE,

    /** The request never reached the backend. */
    NETWORK,

    /** The backend could not complete the request (5xx). */
    SERVER,

    /** A response this build cannot interpret, or an undeclared failure. */
    UNEXPECTED,
}
