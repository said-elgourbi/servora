package com.servora.android.data.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.ScheduleConflict

/** Outcome of the Job Details read. */
sealed interface JobDetailsResult {
    /** The backend answered with the Job. */
    data class Success(val details: JobDetails) : JobDetailsResult

    /** The read failed; [reason] decides what the screen reports. */
    data class Failure(val reason: CustomersFailureReason) : JobDetailsResult
}

/** Outcome of the Job Activity read (`BR-080`). */
sealed interface JobActivityResult {
    /** The backend answered with the Job's chronological activity, newest first. */
    data class Success(val events: List<JobActivityEvent>) : JobActivityResult

    /** The read failed; [reason] decides what the section reports. */
    data class Failure(val reason: CustomersFailureReason) : JobActivityResult
}

/** Outcome of writing one text Activity update to the represented Visit. */
sealed interface VisitNoteResult {
    /** The backend answered with the refreshed Job Activity timeline. */
    data class Success(val events: List<JobActivityEvent>) : VisitNoteResult

    /** Why the text update could not be recorded. */
    data class Failure(val reason: JobActionFailure) : VisitNoteResult
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
     * `BR-064` requires a structured cancellation reason whose catalogue product ownership has not
     * defined, so the API does not cancel a Job yet.
     */
    JOB_CANCELLATION_UNAVAILABLE,

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
