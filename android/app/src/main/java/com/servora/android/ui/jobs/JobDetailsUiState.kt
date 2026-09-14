package com.servora.android.ui.jobs

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import java.time.Instant

/** The management action the screen last completed, so its confirmation names what happened. */
enum class JobActionKind {
    /** The Job's lifecycle status changed (`BR-058`). */
    STATUS_CHANGE,

    /** The represented Visit's schedule changed (`BR-073`). */
    RESCHEDULE,

    /** The represented Visit's crew changed (`BR-068`, `BR-069`). */
    ASSIGNMENT,

    /** A text update was added to the represented Visit's activity (`BR-027`, `BR-077`). */
    ACTIVITY_TEXT,
}

/**
 * A management action the API refused until the user accepts the conflicts it reported (`BR-070`).
 *
 * Holding the action here is what lets the screen resend the *same* action once the user confirms,
 * instead of asking them to enter it again. A conflict is a warning rather than a prohibition in v1,
 * so confirming it applies the change.
 */
sealed interface PendingJobAction {
    /** The overlapping Visits the API reported, as the user has to see them. */
    val conflicts: List<ScheduleConflict>

    /** A reschedule waiting for confirmation (`BR-073`). */
    data class Reschedule(
        val scheduledStart: Instant,
        val scheduledEnd: Instant,
        override val conflicts: List<ScheduleConflict>,
    ) : PendingJobAction

    /** An assignment waiting for confirmation (`BR-068`). */
    data class Assign(
        val assignments: List<TechnicianAssignment>,
        override val conflicts: List<ScheduleConflict>,
    ) : PendingJobAction
}

/**
 * Everything the Job Details screen renders.
 *
 * [details] is the last state the backend reported. A failed read never leaves the previous Job on
 * screen: it describes a different record, so the state is dropped and only the failure is reported
 * (`BR-001`).
 *
 * The action flags are separate from the read, so a failed read and a refused action cannot be
 * confused with each other, and nothing here is derived from another client's copy: even whether the
 * represented Visit may be rescheduled is the API's answer (`BR-007`, `BR-073`).
 */
@Immutable
data class JobDetailsUiState(
    /** The Job being read, so a reply can be matched to the Job it was asked for. */
    val jobId: String = "",
    /** The state the backend last reported, or `null` when nothing readable is held. */
    val details: JobDetails? = null,
    /** Whether a read is in flight. */
    val isLoading: Boolean = false,
    /** Why the last read failed, or `null` when it succeeded. */
    val failureReason: CustomersFailureReason? = null,
    /** Whether an action the user asked for is in flight. */
    val isSubmitting: Boolean = false,
    /** The action the screen last completed, until the screen acknowledges it. */
    val completedAction: JobActionKind? = null,
    /** Why the last action did not complete, or `null` when there is none to report. */
    val actionFailure: JobActionFailure? = null,
    /** An action waiting for the user to accept the conflicts it would create (`BR-070`). */
    val pendingConfirmation: PendingJobAction? = null,
    /** The technicians the assign sheet can offer, or `null` while they have not been read. */
    val assignableTechnicians: List<AssignableTechnician>? = null,
    /** Why the technicians could not be read, or `null`. */
    val assignableFailure: JobActionFailure? = null,
    /** The Job's chronological activity, newest first; `null` while it has not been read. */
    val activity: List<JobActivityEvent>? = null,
    /** Why the activity could not be read, or `null`. */
    val activityFailure: CustomersFailureReason? = null,
) {
    /** Nothing has been read yet: the screen shows its first-load state. */
    val showsInitialLoading: Boolean
        get() = details == null && failureReason == null

    /** Nothing is readable and the read failed: the screen reports the failure. */
    val showsFailure: Boolean
        get() = details == null && failureReason != null

    /** Whether an action may be started right now. */
    val canAct: Boolean
        get() = details != null && !isSubmitting

    /** Whether the represented Visit may be rescheduled, as the API answered (`BR-073`). */
    val canReschedule: Boolean
        get() = details?.selectedVisit?.reschedulable == true && canAct

    /** Whether a crew may be stated for the represented Visit (`BR-068`). */
    val canAssign: Boolean
        get() = details?.selectedVisit != null && canAct

    /** Whether the activity has been asked for and not answered yet. */
    val showsActivityLoading: Boolean
        get() = details != null && activity == null && activityFailure == null
}
