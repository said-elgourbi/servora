package com.servora.android.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.jobs.JobDetailsResult
import com.servora.android.data.jobs.VisitNoteResult
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Job Details screen.
 *
 * The ViewModel decides nothing about the Job: it asks [JobDetailsRepository] for one Job and reports
 * what came back. Which Visit represents the Job, which technicians are assigned to it, which of them
 * is the Lead, which statuses the Job may move to and whether the Visit may be rescheduled are the
 * backend's answers (`BR-001`, `BR-058`, `BR-068`, `BR-073`, `BR-081`).
 *
 * Every management action is an explicit action the user asks for, sent with the version the screen
 * last saw so the API can refuse it against newer state rather than overwriting it (`BR-086`). An
 * action the API refuses because of an availability conflict is **not** a failure: it is held in
 * [JobDetailsUiState.pendingConfirmation] so the user can be shown the conflict and the same action
 * can be resent once accepted (`BR-070`).
 *
 * An action that applies also changes the history Job Activity projects — a status change, a schedule
 * change or a crew change (`BR-069`, `BR-073`, `BR-074`). The action's own answer is the Job rather
 * than the timeline, so once an action succeeds the timeline is read again instead of being assembled
 * from that answer (`BR-001`, `BR-080`). Adding a text update needs no second read: that write answers
 * with the refreshed timeline itself.
 *
 * The work is transient: this screen holds no offline working set yet, because the offline
 * architecture's local store (Room) is the single mechanism that will hold business data on the
 * device (`docs/architecture/offline-first-architecture.md`). Until then an action is online-only.
 */
@HiltViewModel
class JobDetailsViewModel @Inject constructor(
    private val repository: JobDetailsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JobDetailsUiState())
    val uiState: StateFlow<JobDetailsUiState> = _uiState.asStateFlow()

    private var readInFlight = false
    private var actionInFlight = false

    /**
     * Reads [jobId] unless this destination already holds it.
     *
     * The screen drives this from its destination's arguments, so recomposing the destination must not
     * start a second read of the same Job (`BR-001`). A Job the read failed for is read again, because
     * the user is asking to see it.
     */
    fun start(jobId: String) {
        if (readInFlight) {
            return
        }
        val current = _uiState.value
        val settled = current.jobId == jobId && current.details != null
        if (settled) {
            return
        }
        read(jobId)
    }

    /** Re-runs the read after a failure the screen reported. */
    fun retry() {
        if (readInFlight) {
            return
        }
        val current = _uiState.value
        if (current.jobId.isNotEmpty()) {
            read(current.jobId)
        }
    }

    /**
     * Releases the Job the screen held.
     *
     * Called when the app session ends, so a user who signs in next does not see the previous user's
     * record. The read is scoped to a session by the backend (`BR-001`, `BR-007`), so it must not
     * outlive that session in the UI either.
     */
    fun reset() {
        readInFlight = false
        actionInFlight = false
        _uiState.value = JobDetailsUiState()
    }

    /** Moves the Job to [status] (`BR-058`), with the optional note a reopen records (`BR-063`). */
    fun changeJobStatus(status: JobStatus, note: String? = null) {
        val details = _uiState.value.details ?: return
        submit(
            JobActionRequest.Status(
                jobId = details.id,
                status = status,
                note = note,
                jobVersion = details.version,
            ),
        )
    }

    /** Edits the represented Visit's schedule (`BR-073`). */
    fun rescheduleVisit(scheduledStart: Instant, scheduledEnd: Instant) {
        val details = _uiState.value.details ?: return
        val visit = details.selectedVisit ?: return
        submit(
            JobActionRequest.Reschedule(
                jobId = details.id,
                visitId = visit.id,
                scheduledStart = scheduledStart,
                scheduledEnd = scheduledEnd,
                visitVersion = visit.version,
            ),
        )
    }

    /** States the whole crew the represented Visit carries (`BR-068`, `BR-069`). */
    fun assignVisitTechnicians(assignments: List<TechnicianAssignment>) {
        val details = _uiState.value.details ?: return
        val visit = details.selectedVisit ?: return
        submit(
            JobActionRequest.Assignment(
                jobId = details.id,
                visitId = visit.id,
                assignments = assignments,
                visitVersion = visit.version,
            ),
        )
    }

    /** Adds one text-only update to the represented Visit's Activity (`BR-027`, `BR-077`). */
    fun addActivityText(body: String) {
        val text = body.trim()
        if (text.isEmpty() || actionInFlight) {
            return
        }
        val details = _uiState.value.details ?: return
        val visit = details.selectedVisit ?: return

        actionInFlight = true
        _uiState.update { current ->
            current.copy(
                isSubmitting = true,
                completedAction = null,
                actionFailure = null,
            )
        }
        viewModelScope.launch {
            val result = repository.addVisitNote(
                jobId = details.id,
                visitId = visit.id,
                body = text,
            )
            actionInFlight = false
            _uiState.update { current ->
                when (result) {
                    is VisitNoteResult.Success ->
                        current.copy(
                            isSubmitting = false,
                            activity = result.events,
                            activityFailure = null,
                            completedAction = JobActionKind.ACTIVITY_TEXT,
                        )

                    is VisitNoteResult.Failure ->
                        current.copy(
                            isSubmitting = false,
                            actionFailure = result.reason,
                        )
                }
            }
        }
    }

    /**
     * Resends the action the API refused until its conflicts are accepted (`BR-070`).
     *
     * The same action is sent again with the conflicts confirmed, so the user decides about the
     * conflict they were shown rather than about one they were not.
     */
    fun confirmPendingAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        val details = _uiState.value.details ?: return
        val visit = details.selectedVisit ?: return
        when (pending) {
            is PendingJobAction.Reschedule ->
                submit(
                    JobActionRequest.Reschedule(
                        jobId = details.id,
                        visitId = visit.id,
                        scheduledStart = pending.scheduledStart,
                        scheduledEnd = pending.scheduledEnd,
                        visitVersion = visit.version,
                    ),
                    confirmed = true,
                )

            is PendingJobAction.Assign ->
                submit(
                    JobActionRequest.Assignment(
                        jobId = details.id,
                        visitId = visit.id,
                        assignments = pending.assignments,
                        visitVersion = visit.version,
                    ),
                    confirmed = true,
                )
        }
    }

    /** Leaves the conflicts unaccepted: nothing was applied, so nothing is sent (`BR-070`). */
    fun dismissPendingAction() {
        _uiState.update { current -> current.copy(pendingConfirmation = null) }
    }

    /** Acknowledges the outcome the screen has reported, so it is not reported twice. */
    fun dismissActionMessage() {
        _uiState.update { current ->
            current.copy(completedAction = null, actionFailure = null)
        }
    }

    /** Reads the organization's technicians, for the assign sheet to offer (`BR-024`). */
    fun loadAssignableTechnicians() {
        _uiState.update { current ->
            current.copy(assignableTechnicians = null, assignableFailure = null)
        }
        viewModelScope.launch {
            when (val result = repository.loadAssignableTechnicians()) {
                is AssignableTechniciansResult.Success ->
                    _uiState.update { current ->
                        current.copy(
                            assignableTechnicians = result.technicians,
                            assignableFailure = null,
                        )
                    }

                is AssignableTechniciansResult.Failure ->
                    _uiState.update { current ->
                        current.copy(
                            assignableTechnicians = null,
                            assignableFailure = result.reason,
                        )
                    }
            }
        }
    }

    /** Sends one management action and reports what the backend answered. */
    private fun submit(request: JobActionRequest, confirmed: Boolean = false) {
        if (actionInFlight) {
            return
        }
        actionInFlight = true
        _uiState.update { current ->
            current.copy(
                isSubmitting = true,
                completedAction = null,
                actionFailure = null,
                pendingConfirmation = null,
            )
        }
        viewModelScope.launch {
            val result = request.send(repository, confirmed)
            actionInFlight = false
            _uiState.update { current ->
                when (result) {
                    is JobActionResult.Success ->
                        current.copy(
                            isSubmitting = false,
                            details = result.details,
                            completedAction = request.kind,
                            actionFailure = null,
                            pendingConfirmation = null,
                            // The action answered with the Job as it now stands, so the read failure
                            // it replaced is gone (`BR-001`).
                            failureReason = null,
                        )

                    is JobActionResult.Conflicts -> {
                        val pending = request.pending(result.conflicts)
                        if (pending == null) {
                            // The API reported a conflict for an action that cannot have one. An
                            // answer this build cannot place is reported rather than presented
                            // (`BR-042`).
                            current.copy(
                                isSubmitting = false,
                                actionFailure = JobActionFailure.UNEXPECTED,
                            )
                        } else {
                            current.copy(
                                isSubmitting = false,
                                pendingConfirmation = pending,
                            )
                        }
                    }

                    is JobActionResult.Failure ->
                        current.copy(
                            isSubmitting = false,
                            actionFailure = result.reason,
                        )
                }
            }
            if (result is JobActionResult.Success) {
                // The action answered with the Job, not with the timeline, and the history it just
                // recorded is what Job Activity projects (`BR-080`). The timeline is read again rather
                // than left showing what the Job looked like before the action (`BR-001`).
                readActivity(result.details.id)
            }
        }
    }

    private fun read(jobId: String) {
        readInFlight = true
        _uiState.value = JobDetailsUiState(jobId = jobId, isLoading = true)
        viewModelScope.launch {
            val result = repository.loadJobDetails(jobId)
            readInFlight = false
            _uiState.update { current ->
                if (current.jobId != jobId) {
                    // Another Job replaced this one while the read was in flight.
                    current
                } else {
                    when (result) {
                        is JobDetailsResult.Success ->
                            current.copy(
                                isLoading = false,
                                details = result.details,
                                failureReason = null,
                                // A fresh Job read re-asks for that Job's activity rather than
                                // carrying the previous Job's (`BR-001`).
                                activity = null,
                                activityFailure = null,
                            )

                        is JobDetailsResult.Failure ->
                            current.copy(
                                isLoading = false,
                                // A failed read must not leave a Job on screen: it would be a
                                // different record than the one the screen says it is showing.
                                details = null,
                                failureReason = result.reason,
                                activity = null,
                                activityFailure = null,
                            )
                    }
                }
            }
            if (result is JobDetailsResult.Success) {
                readActivity(jobId)
            }
        }
    }

    /** Reads the Job's chronological activity after the Job itself was read (`BR-080`). */
    private fun readActivity(jobId: String) {
        viewModelScope.launch {
            val result = repository.loadJobActivity(jobId)
            _uiState.update { current ->
                if (current.jobId != jobId) {
                    current
                } else {
                    when (result) {
                        is JobActivityResult.Success ->
                            current.copy(activity = result.events, activityFailure = null)

                        is JobActivityResult.Failure ->
                            current.copy(activity = null, activityFailure = result.reason)
                    }
                }
            }
        }
    }

    /** Re-reads the activity after a failure the section reported. */
    fun retryActivity() {
        val jobId = _uiState.value.jobId
        if (jobId.isNotEmpty()) {
            readActivity(jobId)
        }
    }
}

/**
 * One management action the user asked for, kept whole so it can be sent a second time once its
 * conflicts are accepted (`BR-070`).
 */
private sealed interface JobActionRequest {
    /** The action this request performs, for the confirmation the screen reports. */
    val kind: JobActionKind

    /** The action as the screen presents it while the user decides about its conflicts. */
    fun pending(conflicts: List<ScheduleConflict>): PendingJobAction?

    /** Sends the action, confirming its conflicts only when the user has accepted them. */
    suspend fun send(
        repository: JobDetailsRepository,
        confirmed: Boolean,
    ): JobActionResult

    data class Status(
        val jobId: String,
        val status: JobStatus,
        val note: String?,
        val jobVersion: Int,
    ) : JobActionRequest {
        override val kind: JobActionKind = JobActionKind.STATUS_CHANGE

        override suspend fun send(
            repository: JobDetailsRepository,
            confirmed: Boolean,
        ): JobActionResult =
            repository.changeJobStatus(
                jobId = jobId,
                status = status,
                note = note,
                expectedVersion = jobVersion,
            )

        override fun pending(conflicts: List<ScheduleConflict>): PendingJobAction? =
            // A status change carries no schedule, so the API cannot report a technician's time as
            // conflicting for it (`BR-070`). Reporting one would be presenting an answer this build
            // cannot place, so the screen refuses it rather than guessing (`BR-042`).
            null
    }

    data class Reschedule(
        val jobId: String,
        val visitId: String,
        val scheduledStart: Instant,
        val scheduledEnd: Instant,
        val visitVersion: Int,
    ) : JobActionRequest {
        override val kind: JobActionKind = JobActionKind.RESCHEDULE

        override suspend fun send(
            repository: JobDetailsRepository,
            confirmed: Boolean,
        ): JobActionResult =
            repository.rescheduleVisit(
                jobId = jobId,
                visitId = visitId,
                scheduledStart = scheduledStart,
                scheduledEnd = scheduledEnd,
                reason = null,
                confirmConflicts = confirmed,
                expectedVersion = visitVersion,
            )

        override fun pending(conflicts: List<ScheduleConflict>): PendingJobAction =
            PendingJobAction.Reschedule(scheduledStart, scheduledEnd, conflicts)
    }

    data class Assignment(
        val jobId: String,
        val visitId: String,
        val assignments: List<TechnicianAssignment>,
        val visitVersion: Int,
    ) : JobActionRequest {
        override val kind: JobActionKind = JobActionKind.ASSIGNMENT

        override suspend fun send(
            repository: JobDetailsRepository,
            confirmed: Boolean,
        ): JobActionResult =
            repository.assignVisitTechnicians(
                jobId = jobId,
                visitId = visitId,
                assignments = assignments,
                confirmConflicts = confirmed,
                expectedVersion = visitVersion,
            )

        override fun pending(conflicts: List<ScheduleConflict>): PendingJobAction =
            PendingJobAction.Assign(assignments, conflicts)
    }
}
